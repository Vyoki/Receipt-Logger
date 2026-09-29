// On-device vision-language reader for supplier documents (llama.cpp + libmtmd).
// Platform independent: used by the Android JNI bridge and by the desktop test tool in CI.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>

namespace receipt_ai {

struct Engine;

struct LoadResult {
    Engine * engine = nullptr;
    std::string error;
};

// Loads the language model and its vision projector ("mmproj"). backend_dir: where ggml CPU backend
// variants live (Android: the app's native library directory); empty = built-in backends only.
// n_threads: for writing the answer (memory bound: the performance cores); n_threads_batch: for reading the
// image and the prompt (compute bound: every fast core).
LoadResult load(const std::string & backend_dir, const std::string & model_path, const std::string & mmproj_path,
               int n_threads, int n_threads_batch);

struct Request {
    const uint8_t * rgb = nullptr;  // width * height * 3 bytes
    int width = 0;
    int height = 0;
    std::string user_text;          // instruction; the image is placed before it
    std::string grammar;            // GBNF; empty = free text
    int max_tokens = 3000;
    int n_ctx = 8192;
};

struct Result {
    std::string text;
    std::string error;
    int prompt_tokens = 0;
    int generated_tokens = 0;
    double encode_seconds = 0;
    double generate_seconds = 0;
    bool cancelled = false;
};

// progress(stage, count): stage 0 = reading the image, 1 = writing (count = tokens so far).
// Returning false cancels.
Result generate(Engine * engine, const Request & request, const std::function<bool(int, int)> & progress);

void free_engine(Engine * engine);

// Asks a running generate() to stop as soon as possible (safe from any thread).
void cancel(Engine * engine);

// Parses a GBNF grammar with the model's vocabulary; returns an empty string if valid, else the reason.
std::string check_grammar(Engine * engine, const std::string & grammar);

} // namespace receipt_ai
