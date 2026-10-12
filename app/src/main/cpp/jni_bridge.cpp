// JNI entry points for com.kitchenreceipts.app.ai.NativeAi
#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cstdint>

#include "receipt_ai.h"
#include "llama.h"

// The model's words as a Java string. NewStringUTF wants "modified UTF-8" and aborts the app (CheckJNI, on in debug
// builds) on anything else: an answer cut inside a multi-byte character, or a 4-byte one such as an emoji. Decoded
// here instead: an invalid or incomplete sequence becomes U+FFFD, a 4-byte one a surrogate pair.
static jstring javaString(JNIEnv * env, const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    const auto * b = reinterpret_cast<const unsigned char *>(s.data());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = b[i];
        uint32_t cp; size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); i++; continue; }
        if (i + len > n) { out.push_back(0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k < len; k++) {
            if ((b[i + k] & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (b[i + k] & 0x3F);
        }
        if (!ok || (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)) || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD); i++; continue;
        }
        if (cp >= 0x10000) { cp -= 0x10000; out.push_back((char16_t) (0xD800 + (cp >> 10))); out.push_back((char16_t) (0xDC00 + (cp & 0x3FF))); }
        else out.push_back((char16_t) cp);
        i += len;
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), (jsize) out.size());
}

static void android_log(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "ReceiptAI", "%s", text);
}

static std::string str(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeLoad(JNIEnv * env, jclass, jstring backendDir, jstring model, jstring mmproj,
                                                    jint threads, jint batchThreads, jobjectArray errorOut) {
    llama_log_set(android_log, nullptr);
    auto r = receipt_ai::load(str(env, backendDir), str(env, model), str(env, mmproj), threads, batchThreads);
    if (!r.engine && errorOut && env->GetArrayLength(errorOut) > 0) {
        env->SetObjectArrayElement(errorOut, 0, env->NewStringUTF(r.error.c_str()));
    }
    return reinterpret_cast<jlong>(r.engine);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeGenerate(JNIEnv * env, jclass, jlong handle, jbyteArray rgb, jint width, jint height,
                                                        jstring text, jstring grammar, jint maxTokens, jint nCtx,
                                                        jobject listener) {
    auto * engine = reinterpret_cast<receipt_ai::Engine *>(handle);
    std::vector<uint8_t> pixels(env->GetArrayLength(rgb));
    env->GetByteArrayRegion(rgb, 0, (jsize) pixels.size(), reinterpret_cast<jbyte *>(pixels.data()));

    receipt_ai::Request req;
    req.rgb = pixels.data();
    req.width = width;
    req.height = height;
    req.user_text = str(env, text);
    req.grammar = str(env, grammar);
    req.max_tokens = maxTokens;
    req.n_ctx = nCtx;

    jmethodID onProgress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onProgress = env->GetMethodID(cls, "onProgress", "(II)Z");
    }
    auto progress = [&](int stage, int count) -> bool {
        if (!listener || !onProgress) return true;
        jboolean ok = env->CallBooleanMethod(listener, onProgress, stage, count);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return ok == JNI_TRUE;
    };
    auto r = receipt_ai::generate(engine, req, progress);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(3, stringClass, nullptr);
    env->SetObjectArrayElement(out, 0, javaString(env, r.text));
    env->SetObjectArrayElement(out, 1, javaString(env, r.cancelled ? std::string("cancelled") : r.error));
    char stats[160];
    snprintf(stats, sizeof(stats), "prompt=%d generated=%d encode=%.1fs write=%.1fs%s", r.prompt_tokens, r.generated_tokens,
             r.encode_seconds, r.generate_seconds, r.picture_reused ? " picture=reused" : "");
    env->SetObjectArrayElement(out, 2, env->NewStringUTF(stats));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeCancel(JNIEnv *, jclass, jlong handle) {
    receipt_ai::cancel(reinterpret_cast<receipt_ai::Engine *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeFree(JNIEnv *, jclass, jlong handle) {
    receipt_ai::free_engine(reinterpret_cast<receipt_ai::Engine *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeCheckGrammar(JNIEnv * env, jclass, jlong handle, jstring grammar) {
    auto err = receipt_ai::check_grammar(reinterpret_cast<receipt_ai::Engine *>(handle), str(env, grammar));
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kitchenreceipts_app_ai_NativeAi_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}
