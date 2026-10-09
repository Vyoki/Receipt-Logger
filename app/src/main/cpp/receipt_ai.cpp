#include "receipt_ai.h"

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <chrono>
#include <cstring>
#include <mutex>
#include <vector>

namespace receipt_ai {

struct Engine {
    llama_model * model = nullptr;
    mtmd_context * mtmd = nullptr;
    int n_threads = 4;
    int n_threads_batch = 4;
    std::atomic<bool> cancel{false};
    // The working memory of the last question, reused by the next one of the same size (creating it allocates and
    // clears hundreds of MB each time). Emptied before every question, so each still starts from nothing.
    llama_context * lctx = nullptr;
    int lctx_n_ctx = 0;
    // The picture of the last question, already processed: the start of the working memory up to the end of the
    // picture is kept, and a next question on the same picture (same bytes) only processes its new words. Reading
    // a picture is most of the time a question takes.
    uint64_t img_hash = 0;
    int img_w = 0, img_h = 0;
    llama_pos prefix_n_past = -1; // position after the picture; -1 = nothing kept
};

static uint64_t fnv1a(const uint8_t * p, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}

void cancel(Engine * e) { if (e) e->cancel = true; }

static std::once_flag g_backend_once;

LoadResult load(const std::string & backend_dir, const std::string & model_path, const std::string & mmproj_path,
               int n_threads, int n_threads_batch) {
    std::call_once(g_backend_once, [&] {
        if (!backend_dir.empty()) ggml_backend_load_all_from_path(backend_dir.c_str());
        else ggml_backend_load_all();
        llama_backend_init();
    });
    LoadResult r;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU: works on every phone
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) { r.error = "Cannot load the AI model file"; return r; }

    mtmd_context_params cp = mtmd_context_params_default();
    cp.use_gpu = false;
    cp.n_threads = n_threads_batch; // the vision encoder is compute bound
    cp.print_timings = false;
    cp.warmup = false;
    mtmd_context * mctx = mtmd_init_from_file(mmproj_path.c_str(), model, cp);
    if (!mctx) { llama_model_free(model); r.error = "Cannot load the AI vision file (mmproj)"; return r; }
    if (!mtmd_support_vision(mctx)) {
        mtmd_free(mctx); llama_model_free(model);
        r.error = "The vision file does not support images"; return r;
    }
    auto * e = new Engine();
    e->model = model;
    e->mtmd = mctx;
    e->n_threads = n_threads;
    e->n_threads_batch = n_threads_batch;
    r.engine = e;
    return r;
}

void free_engine(Engine * e) {
    if (!e) return;
    if (e->lctx) llama_free(e->lctx);
    if (e->mtmd) mtmd_free(e->mtmd);
    if (e->model) llama_model_free(e->model);
    delete e;
}

std::string check_grammar(Engine * e, const std::string & grammar) {
    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_sampler * s = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
    if (!s) return "grammar does not parse";
    llama_sampler_free(s);
    return "";
}

// Wraps the user's message in the model's own chat template (Qwen: ChatML).
static std::string chat_prompt(const llama_model * model, const std::string & user) {
    const std::string content = std::string(mtmd_default_marker()) + "\n" + user;
    const char * tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message msg = { "user", content.c_str() };
    std::vector<char> buf(content.size() * 2 + 1024);
    int32_t n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        return "<|im_start|>user\n" + content + "<|im_end|>\n<|im_start|>assistant\n";
    }
    return std::string(buf.data(), n);
}

static bool abort_cb(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

static Result generate_once(Engine * e, const Request & req, const std::function<bool(int, int)> & progress);

Result generate(Engine * e, const Request & req, const std::function<bool(int, int)> & progress) {
    Result r = generate_once(e, req, progress);
    // After a stop or an error the working memory is not trusted again: the next question makes a new one.
    if ((r.cancelled || !r.error.empty()) && e->lctx) { llama_free(e->lctx); e->lctx = nullptr; e->lctx_n_ctx = 0; e->prefix_n_past = -1; }
    return r;
}

static Result generate_once(Engine * e, const Request & req, const std::function<bool(int, int)> & progress) {
    Result r;
    using clock = std::chrono::steady_clock;
    e->cancel = false;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = req.n_ctx;
    cp.n_batch = 2048;
    cp.n_ubatch = 512;
    cp.n_threads = e->n_threads;
    cp.n_threads_batch = e->n_threads_batch;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = &e->cancel;
    if (e->lctx && e->lctx_n_ctx != req.n_ctx) { llama_free(e->lctx); e->lctx = nullptr; e->lctx_n_ctx = 0; }
    const uint64_t hash = fnv1a(req.rgb, (size_t) req.width * (size_t) req.height * 3);
    bool reuse = e->lctx && e->prefix_n_past > 0 && hash == e->img_hash && req.width == e->img_w && req.height == e->img_h;
    const llama_pos kept = e->prefix_n_past;
    e->prefix_n_past = -1; // valid again only once this question's picture is in the working memory
    if (!e->lctx) {
        reuse = false;
        e->lctx = llama_init_from_model(e->model, cp);
        if (!e->lctx) { r.error = "Not enough memory for the AI reader"; return r; }
        e->lctx_n_ctx = req.n_ctx;
    } else if (reuse) {
        // Same picture: keep it, drop the words of the previous question and its answer.
        if (!llama_memory_seq_rm(llama_get_memory(e->lctx), 0, kept, -1)) {
            llama_memory_clear(llama_get_memory(e->lctx), true);
            reuse = false;
        }
    } else {
        llama_memory_clear(llama_get_memory(e->lctx), true); // nothing of the previous question remains
    }
    llama_context * lctx = e->lctx;

    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!req.grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, req.grammar.c_str(), "root");
        if (!g) { llama_sampler_free(smpl); r.error = "Invalid grammar"; return r; }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    mtmd_bitmap * bmp = mtmd_bitmap_init((uint32_t) req.width, (uint32_t) req.height, req.rgb);
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const std::string prompt = chat_prompt(e->model, req.user_text);
    mtmd_input_text text{ prompt.c_str(), prompt.size(), true, true };
    const mtmd_bitmap * bitmaps[] = { bmp };

    auto cleanup = [&] {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        llama_sampler_free(smpl);
        // The context stays with the engine for the next question (freed with the engine).
    };

    if (mtmd_tokenize(e->mtmd, chunks, &text, bitmaps, 1) != 0) {
        cleanup(); r.error = "Could not prepare the image"; return r;
    }
    r.prompt_tokens = (int) mtmd_helper_get_n_tokens(chunks);
    if (r.prompt_tokens + 256 > req.n_ctx) {
        cleanup(); r.error = "The page is too large for the AI reader"; return r;
    }

    if (progress && !progress(0, 0)) { cleanup(); r.cancelled = true; return r; }
    auto t0 = clock::now();
    // The prompt is: the start of the chat turn, the picture, the question. With the same picture as the last
    // question, the working memory already holds the first two: only the question is processed.
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    size_t img_idx = n_chunks;
    for (size_t i = 0; i < n_chunks; i++) {
        if (mtmd_input_chunk_get_type(mtmd_input_chunks_get(chunks, i)) == MTMD_INPUT_CHUNK_TYPE_IMAGE) { img_idx = i; break; }
    }
    if (img_idx == n_chunks) reuse = false;
    llama_pos n_past = reuse ? kept : 0;
    for (size_t i = reuse ? img_idx + 1 : 0; i < n_chunks; i++) {
        if (mtmd_helper_eval_chunk_single(e->mtmd, lctx, mtmd_input_chunks_get(chunks, i), n_past, 0, (int32_t) cp.n_batch,
                                          i + 1 == n_chunks, &n_past) != 0) {
            cleanup();
            if (e->cancel) r.cancelled = true; else r.error = "The AI could not read the image";
            return r;
        }
        if (i == img_idx) { e->img_hash = hash; e->img_w = req.width; e->img_h = req.height; e->prefix_n_past = n_past; }
    }
    if (reuse) { e->prefix_n_past = kept; r.picture_reused = true; }
    auto t1 = clock::now();
    r.encode_seconds = std::chrono::duration<double>(t1 - t0).count();

    llama_batch batch = llama_batch_init(1, 0, 1);
    char piece[256];
    for (int i = 0; i < req.max_tokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, lctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) r.text.append(piece, n);
        r.generated_tokens++;
        if (e->cancel || (progress && (i % 8 == 0) && !progress(1, r.generated_tokens))) { r.cancelled = true; break; }
        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(lctx, batch) != 0) { r.error = "The AI stopped while writing"; break; }
    }
    r.generate_seconds = std::chrono::duration<double>(clock::now() - t1).count();
    llama_batch_free(batch);
    cleanup();
    return r;
}

} // namespace receipt_ai
