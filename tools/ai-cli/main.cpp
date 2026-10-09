// receipt-ai-cli <model.gguf> <mmproj.gguf> <image.ppm> <instruction.txt> <grammar.gbnf> [--check-grammar]
// Runs exactly the app's reader code on a desktop, for CI. The image is a binary PPM (P6), RGB.
#include "receipt_ai.h"
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

static std::string slurp(const char * path) {
    std::ifstream f(path, std::ios::binary);
    std::stringstream ss; ss << f.rdbuf(); return ss.str();
}

int main(int argc, char ** argv) {
    if (argc < 6) { fprintf(stderr, "usage: %s model mmproj image.ppm instruction.txt grammar.gbnf\n", argv[0]); return 2; }
    auto lr = receipt_ai::load("", argv[1], argv[2], 4, 4);
    if (!lr.engine) { fprintf(stderr, "load failed: %s\n", lr.error.c_str()); return 1; }
    std::string grammar = slurp(argv[5]);
    auto gerr = receipt_ai::check_grammar(lr.engine, grammar);
    if (!gerr.empty()) { fprintf(stderr, "grammar: %s\n", gerr.c_str()); return 1; }
    if (argc > 6 && std::string(argv[6]) == "--check-grammar") { printf("grammar ok\n"); return 0; }

    std::string ppm = slurp(argv[3]);
    int w = 0, h = 0, maxv = 0, off = 0;
    if (sscanf(ppm.c_str(), "P6 %d %d %d%n", &w, &h, &maxv, &off) != 3) { fprintf(stderr, "bad ppm\n"); return 1; }
    off += 1; // single whitespace after maxval
    receipt_ai::Request req;
    req.rgb = reinterpret_cast<const uint8_t *>(ppm.data() + off);
    req.width = w; req.height = h;
    req.user_text = slurp(argv[4]);
    req.grammar = grammar;
    req.max_tokens = 3000;
    req.n_ctx = 8192;
    auto r = receipt_ai::generate(lr.engine, req, [](int stage, int n) { if (stage == 0) fprintf(stderr, "reading image...\n"); return true; });
    fprintf(stderr, "prompt=%d generated=%d encode=%.1fs write=%.1fs error=%s\n", r.prompt_tokens, r.generated_tokens,
            r.encode_seconds, r.generate_seconds, r.error.c_str());
    printf("%s\n", r.text.c_str());
    // RECEIPT_AI_REPEAT=1: ask the same question again on the same engine (the app reuses the working memory
    // between questions); the answer must be identical to the first.
    int code = r.error.empty() ? 0 : 1;
    if (getenv("RECEIPT_AI_REPEAT") && r.error.empty()) {
        auto r2 = receipt_ai::generate(lr.engine, req, [](int, int) { return true; });
        bool same = r2.error.empty() && r2.text == r.text;
        fprintf(stderr, "reuse=%s picture_reused=%d encode=%.1fs write=%.1fs\n", same ? "same" : "DIFFERENT", r2.picture_reused ? 1 : 0,
                r2.encode_seconds, r2.generate_seconds);
        if (!same) code = 3;
    }
    // RECEIPT_AI_THEN=<instruction>,<grammar>,<answer file>: then a different question on the same picture, which
    // reuses the processed picture; CI compares its answer with the same question asked on a fresh engine.
    if (const char * then = getenv("RECEIPT_AI_THEN"); then && r.error.empty()) {
        std::string spec(then);
        size_t a = spec.find(','), b = spec.find(',', a + 1);
        if (a != std::string::npos && b != std::string::npos) {
            receipt_ai::Request q = req;
            q.user_text = slurp(spec.substr(0, a).c_str());
            q.grammar = slurp(spec.substr(a + 1, b - a - 1).c_str());
            auto r3 = receipt_ai::generate(lr.engine, q, [](int, int) { return true; });
            std::ofstream(spec.substr(b + 1)) << r3.text << "\n";
            fprintf(stderr, "then: picture_reused=%d encode=%.1fs write=%.1fs error=%s\n", r3.picture_reused ? 1 : 0,
                    r3.encode_seconds, r3.generate_seconds, r3.error.c_str());
            if (!r3.error.empty()) code = 4;
        }
    }
    receipt_ai::free_engine(lr.engine);
    return code;
}
