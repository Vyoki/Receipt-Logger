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
    std::atomic<bool> cancel{false};
};

void cancel(Engine * e) { if (e) e->cancel = true; }

static std::once_flag g_backend_once;

LoadResult load(const std::string & backend_dir, const std::string & model_path, const std::string & mmproj_path, int n_threads) {
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
    cp.n_threads = n_threads;
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
    r.engine = e;
    return r;
}

void free_engine(Engine * e) {
    if (!e) return;
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

Result generate(Engine * e, const Request & req, const std::function<bool(int, int)> & progress) {
    Result r;
    using clock = std::chrono::steady_clock;
    e->cancel = false;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = req.n_ctx;
    cp.n_batch = 2048;
    cp.n_ubatch = 512;
    cp.n_threads = e->n_threads;
    cp.n_threads_batch = e->n_threads;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = &e->cancel;
    llama_context * lctx = llama_init_from_model(e->model, cp);
    if (!lctx) { r.error = "Not enough memory for the AI reader"; return r; }

    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!req.grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, req.grammar.c_str(), "root");
        if (!g) { llama_sampler_free(smpl); llama_free(lctx); r.error = "Invalid grammar"; return r; }
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
        llama_free(lctx);
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
    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(e->mtmd, lctx, chunks, 0, 0, (int32_t) cp.n_batch, true, &n_past) != 0) {
        cleanup();
        if (e->cancel) r.cancelled = true; else r.error = "The AI could not read the image";
        return r;
    }
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
