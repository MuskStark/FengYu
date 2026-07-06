/**
 * llama_jni.cpp — JNI bridge between Java LlamaContext and llama.cpp.
 *
 * Build commands (see docs/BUILD.md):
 *
 * Linux:
 *   g++ -O2 -std=c++17 -shared -fPIC \
 *       -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
 *       -I<llama.cpp>/include -I<llama.cpp>/ggml/include -I<llama.cpp>/common \
 *       llama_jni.cpp \
 *       <llama.cpp>/build/src/libllama.a \
 *       <llama.cpp>/build/common/libllama-common.a \
 *       <llama.cpp>/build/common/libllama-common-base.a \
 *       <llama.cpp>/build/ggml/src/libggml.a \
 *       <llama.cpp>/build/ggml/src/libggml-base.a \
 *       <llama.cpp>/build/ggml/src/libggml-cpu.a \
 *       <llama.cpp>/build/ggml/src/libggml-blas/libggml-blas.a \
 *       -lpthread -ldl -lm -Wl,-rpath,'$ORIGIN' \
 *       -o ../resources/native/libllama_jni.so
 *
 * macOS (Apple Silicon, Metal):
 *   clang++ -O2 -std=c++17 -dynamiclib \
 *       -install_name @rpath/libllama_jni-aarch64.dylib \
 *       -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin \
 *       -I<llama.cpp>/include -I<llama.cpp>/ggml/include -I<llama.cpp>/common \
 *       llama_jni.cpp \
 *       <llama.cpp>/build/src/libllama.a \
 *       <llama.cpp>/build/common/libllama-common.a \
 *       <llama.cpp>/build/common/libllama-common-base.a \
 *       <llama.cpp>/build/ggml/src/libggml.a \
 *       <llama.cpp>/build/ggml/src/libggml-base.a \
 *       <llama.cpp>/build/ggml/src/libggml-cpu.a \
 *       <llama.cpp>/build/ggml/src/ggml-metal/libggml-metal.a \
 *       <llama.cpp>/build/ggml/src/libggml-blas/libggml-blas.a \
 *       -framework Foundation -framework Metal -framework MetalKit -framework Accelerate \
 *       -o ../resources/native/libllama_jni-aarch64.dylib
 *
 * Windows:
 *   cl /O2 /LD /EHsc /std:c++17 \
 *       /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" \
 *       /I<llama.cpp>\include /I<llama.cpp>\ggml\include /I<llama.cpp>\common \
 *       llama_jni.cpp \
 *       /link <llama.cpp>\build\Release\llama.lib <llama.cpp>\build\Release\ggml.lib \
 *       /OUT:..\resources\native\llama_jni.dll
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <stdexcept>

#include "llama.h"
#include "ggml.h"
#include "build-info.h"

// ── LlamaWrapper: holds native state for one Java LlamaContext ────────

struct LlamaWrapper {
    llama_model*      model;
    llama_context*    ctx;
    const llama_vocab* vocab;
    int               n_ctx;

    LlamaWrapper() : model(nullptr), ctx(nullptr), vocab(nullptr), n_ctx(0) {}

    ~LlamaWrapper() {
        if (ctx)   llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

// ── Helpers ───────────────────────────────────────────────────────────

static void throwJavaException(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) env->ThrowNew(exClass, msg);
}

struct CallbackProxy {
    JNIEnv*   env;
    jobject   callback;
    jmethodID onTokenMethod;
    jmethodID onDoneMethod;
    jmethodID onErrorMethod;
};

static CallbackProxy getCallbackProxy(JNIEnv* env, jobject callback) {
    CallbackProxy proxy;
    proxy.env = env;
    proxy.callback = env->NewGlobalRef(callback);

    jclass cls = env->GetObjectClass(callback);
    proxy.onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    proxy.onDoneMethod  = env->GetMethodID(cls, "onDone", "(Ljava/lang/String;)V");
    proxy.onErrorMethod = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");

    return proxy;
}

static void releaseCallbackProxy(CallbackProxy& proxy) {
    if (proxy.callback) {
        proxy.env->DeleteGlobalRef(proxy.callback);
        proxy.callback = nullptr;
    }
}

// ── Tokenize helper using new vocab-based API ────────────────────────

static std::vector<llama_token> tokenize_impl(
    const llama_vocab* vocab, const char* text, int text_len,
    bool add_special, bool parse_special)
{
    // First call: get required buffer size (returns negative of required size)
    int n_max = llama_tokenize(vocab, text, text_len, nullptr, 0, add_special, parse_special);
    if (n_max < 0) n_max = -n_max;
    if (n_max == 0) return {};

    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(vocab, text, text_len, tokens.data(), n_max, add_special, parse_special);
    if (n < 0) {
        // Buffer too small, resize and retry
        n_max = -n;
        tokens.resize(n_max);
        n = llama_tokenize(vocab, text, text_len, tokens.data(), n_max, add_special, parse_special);
    }
    if (n > 0) tokens.resize(n);
    return tokens;
}

// ═══════════════════════════════════════════════════════════════════════
// JNI Method Implementations
// ═══════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jlong JNICALL
Java_fan_summer_ai_nativejni_LlamaContext_nativeInit(
    JNIEnv* env, jclass cls,
    jstring modelPath, jint nCtx, jint nGpuLayers, jint nThreads, jint flashAttn)
{
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        throwJavaException(env, "modelPath is null");
        return 0;
    }

    auto* wrapper = new (std::nothrow) LlamaWrapper();
    if (!wrapper) {
        env->ReleaseStringUTFChars(modelPath, path);
        throwJavaException(env, "Failed to allocate LlamaWrapper");
        return 0;
    }

    try {
        ggml_backend_load_all();

        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = nGpuLayers;

        wrapper->model = llama_model_load_from_file(path, model_params);
        if (!wrapper->model) {
            throw std::runtime_error(std::string("Failed to load model: ") + path);
        }

        env->ReleaseStringUTFChars(modelPath, path);

        auto ctx_params = llama_context_default_params();
        ctx_params.n_ctx           = (uint32_t)nCtx;
        ctx_params.n_threads       = nThreads;
        ctx_params.n_threads_batch = nThreads;
        ctx_params.flash_attn_type = flashAttn
            ? LLAMA_FLASH_ATTN_TYPE_ENABLED
            : LLAMA_FLASH_ATTN_TYPE_DISABLED;

        wrapper->ctx = llama_init_from_model(wrapper->model, ctx_params);
        if (!wrapper->ctx) {
            throw std::runtime_error("Failed to create llama context");
        }

        wrapper->vocab = llama_model_get_vocab(wrapper->model);
        wrapper->n_ctx = nCtx;

        return reinterpret_cast<jlong>(wrapper);

    } catch (const std::exception& e) {
        delete wrapper;
        if (path) env->ReleaseStringUTFChars(modelPath, path);
        throwJavaException(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_fan_summer_ai_nativejni_LlamaContext_nativeGenerate(
    JNIEnv* env, jclass cls,
    jlong ptr, jstring prompt,
    jint maxNewTokens, jfloat temperature, jfloat topP,
    jfloat repeatPenalty, jlong seed,
    jobject callback)
{
    if (ptr == 0) {
        throwJavaException(env, "Null LlamaContext pointer");
        return env->NewStringUTF("");
    }

    auto* wrapper = reinterpret_cast<LlamaWrapper*>(ptr);
    if (!wrapper->ctx || !wrapper->model || !wrapper->vocab) {
        throwJavaException(env, "LlamaContext not initialized");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    if (!promptStr) {
        throwJavaException(env, "prompt is null");
        return env->NewStringUTF("");
    }

    // Tokenize
    std::vector<llama_token> tokens = tokenize_impl(
        wrapper->vocab, promptStr, (int)strlen(promptStr), true, true);
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (tokens.empty()) {
        throwJavaException(env, "Failed to tokenize prompt");
        return env->NewStringUTF("");
    }

    // Build sampler chain
    auto* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());

    float effectivePenalty = (repeatPenalty > 1.0f) ? repeatPenalty : 1.1f;
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        -1,                // last n = full context
        effectivePenalty,  // repeat penalty
        0.0f,              // frequency penalty
        0.0f               // presence penalty
    ));

    if (temperature == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        if (topP < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        }
        uint32_t rngSeed = (seed >= 0) ? (uint32_t)seed : (uint32_t)time(nullptr);
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(rngSeed));
    }

    CallbackProxy cbProxy = {nullptr, nullptr, nullptr, nullptr, nullptr};
    if (callback) {
        cbProxy = getCallbackProxy(env, callback);
    }

    std::string fullResponse;

    try {
        // Clear KV cache from any previous generation
        llama_memory_t mem = llama_get_memory(wrapper->ctx);
        llama_memory_seq_rm(mem, -1, 0, -1);

        int n_prompt = (int)tokens.size();

        // Prefill: decode all prompt tokens in one batch.
        // llama_batch_get_one auto-tracks positions and defaults logits to last-token-only.
        llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);

        if (llama_decode(wrapper->ctx, batch) != 0) {
            throw std::runtime_error("llama_decode failed during prefill");
        }

        // Sample the first new token after prefill
        llama_token newToken = llama_sampler_sample(smpl, wrapper->ctx, -1);
        int generated = 0;

        while (generated < maxNewTokens
               && !llama_vocab_is_eog(wrapper->vocab, newToken))
        {
            char buf[256];
            int n = llama_token_to_piece(wrapper->vocab, newToken, buf, sizeof(buf), 0, false);
            if (n > 0) {
                std::string piece(buf, n);
                fullResponse += piece;

                if (cbProxy.callback) {
                    jstring jPiece = cbProxy.env->NewStringUTF(piece.c_str());
                    jboolean cont = cbProxy.env->CallBooleanMethod(
                        cbProxy.callback, cbProxy.onTokenMethod, jPiece);
                    cbProxy.env->DeleteLocalRef(jPiece);
                    if (cont == JNI_FALSE) break;
                }
            }

            generated++;

            // Prepare next batch with the sampled token
            batch = llama_batch_get_one(&newToken, 1);
            if (llama_decode(wrapper->ctx, batch) != 0) {
                throw std::runtime_error("llama_decode failed during generation");
            }

            newToken = llama_sampler_sample(smpl, wrapper->ctx, -1);
        }

        // onDone
        if (cbProxy.callback) {
            jstring jFull = cbProxy.env->NewStringUTF(fullResponse.c_str());
            cbProxy.env->CallVoidMethod(cbProxy.callback, cbProxy.onDoneMethod, jFull);
            cbProxy.env->DeleteLocalRef(jFull);
        }

    } catch (const std::exception& e) {
        if (cbProxy.callback) {
            jstring jMsg = cbProxy.env->NewStringUTF(e.what());
            cbProxy.env->CallVoidMethod(cbProxy.callback, cbProxy.onErrorMethod, jMsg);
            cbProxy.env->DeleteLocalRef(jMsg);
        }
    }

    releaseCallbackProxy(cbProxy);
    llama_sampler_free(smpl);

    return env->NewStringUTF(fullResponse.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_fan_summer_ai_nativejni_LlamaContext_nativeTokenize(
    JNIEnv* env, jclass cls, jlong ptr, jstring text)
{
    if (ptr == 0) {
        throwJavaException(env, "Null LlamaContext pointer");
        return 0;
    }

    auto* wrapper = reinterpret_cast<LlamaWrapper*>(ptr);
    if (!wrapper->vocab) return 0;

    const char* textStr = env->GetStringUTFChars(text, nullptr);
    if (!textStr) return 0;

    std::vector<llama_token> tokens = tokenize_impl(
        wrapper->vocab, textStr, (int)strlen(textStr), true, false);
    env->ReleaseStringUTFChars(text, textStr);

    return (jint)tokens.size();
}

extern "C" JNIEXPORT void JNICALL
Java_fan_summer_ai_nativejni_LlamaContext_nativeFree(
    JNIEnv* env, jclass cls, jlong ptr)
{
    if (ptr == 0) return;
    auto* wrapper = reinterpret_cast<LlamaWrapper*>(ptr);
    delete wrapper;
}

extern "C" JNIEXPORT jstring JNICALL
Java_fan_summer_ai_nativejni_LlamaContext_version(
    JNIEnv* env, jclass cls)
{
    return env->NewStringUTF(llama_commit());
}
