// JNI entry points for com.kitchenreceipts.app.ai.NativeAi
#include <jni.h>
#include <android/log.h>
#include <vector>

#include "receipt_ai.h"
#include "llama.h"

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
                                                    jint threads, jobjectArray errorOut) {
    llama_log_set(android_log, nullptr);
    auto r = receipt_ai::load(str(env, backendDir), str(env, model), str(env, mmproj), threads);
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
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(r.text.c_str()));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(r.cancelled ? "cancelled" : r.error.c_str()));
    char stats[160];
    snprintf(stats, sizeof(stats), "prompt=%d generated=%d encode=%.1fs write=%.1fs", r.prompt_tokens, r.generated_tokens,
             r.encode_seconds, r.generate_seconds);
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
