// Schlanke JNI-Brücke zu llama.cpp: Modell laden, Chat-Prompt formatieren,
// Antwort erzeugen (optional mit Grammatik, z. B. für JSON). Alles lokal.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "ProtokollKI-LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int BATCH_SIZE = 512;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_batch_ext *batch = nullptr;
    std::string chat_template;
    std::atomic<bool> cancel{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

bool abort_cb(void *data) {
    return static_cast<Engine *>(data)->cancel.load();
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java erwartet "modified UTF-8"; über ein byte[] umgehen wir Probleme mit Emojis usw.
jstring to_jstring(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass str_cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str_cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(str_cls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(str_cls);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, special);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0) {
        llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(),
                       (int32_t) tokens.size(), true, special);
    }
    return tokens;
}

std::string format_chat(const Engine &e, const std::string &system, const std::string &user) {
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char *tmpl = e.chat_template.empty() ? nullptr : e.chat_template.c_str();
    std::vector<char> buf(2 * (system.size() + user.size()) + 1024);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        // Unbekannte Vorlage: einfaches Fallback-Format
        return system + "\n\n" + user + "\n\nAntwort:\n";
    }
    return std::string(buf.data(), n);
}

int decode(Engine &e, const llama_token *tokens, int n, llama_pos pos0, bool logits_last) {
    llama_batch_ext_clear(e.batch);
    for (int i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(e.batch, 0, tokens[i]);
        const llama_pos pos = pos0 + i;
        llama_batch_ext_set_pos(e.batch, idx, &pos);
    }
    if (logits_last) llama_batch_ext_set_output_logits(e.batch, n - 1, true);
    return llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, e.batch);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_de_protokollki_engine_LlamaNative_init(JNIEnv *env, jobject, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    const std::string dir = jstr(env, native_lib_dir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_de_protokollki_engine_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx,
                                            jint n_threads, jboolean fast) {
    auto *e = new Engine();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Umgepackte Gewichte: schneller, aber eine zweite Kopie im RAM. Nur wenn genug frei ist.
    mp.use_extra_bufts = fast == JNI_TRUE;
    const std::string path = jstr(env, jpath);
    e->model = llama_model_load_from_file(path.c_str(), mp);
    if (!e->model) {
        LOGE("Modell konnte nicht geladen werden: %s", path.c_str());
        delete e;
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    const int trained = llama_model_n_ctx_train(e->model);
    cp.n_ctx = (uint32_t) std::min<int>(n_ctx, trained > 0 ? trained : n_ctx);
    cp.n_batch = BATCH_SIZE;
    cp.n_ubatch = BATCH_SIZE;
    // Erzeugen (Token für Token) ist speichergebunden: 4 Threads auf den großen Kernen sind am
    // schnellsten, mehr Threads landen auf den Sparkernen und bremsen. Einlesen profitiert von mehr.
    const int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    int threads = n_threads > 0 ? n_threads : std::max(2, std::min(4, cores - 2));
    cp.n_threads = threads;
    cp.n_threads_batch = n_threads > 0 ? n_threads : std::max(2, std::min(6, cores - 2));
    cp.no_perf = true;
    // Speicher sparen: KV-Cache in 8 Bit (halbiert den größten RAM-Posten nach den Gewichten).
    // Quantisierter V-Cache braucht Flash Attention; klappt das nicht, Rückfall auf 16 Bit.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) {
        LOGE("Kontext mit 8-Bit-KV nicht möglich, versuche 16 Bit");
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        e->ctx = llama_init_from_model(e->model, cp);
    }
    if (!e->ctx) {
        LOGE("Kontext konnte nicht erzeugt werden");
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    llama_set_abort_callback(e->ctx, abort_cb, e);
    e->batch = llama_batch_ext_init(e->ctx);
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (tmpl) e->chat_template = tmpl;
    LOGI("Modell geladen, n_ctx=%u, Threads=%d, schnell=%d", llama_n_ctx(e->ctx), threads, (int) fast);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jint JNICALL
Java_de_protokollki_engine_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    return e ? (jint) llama_n_ctx(e->ctx) : 0;
}

JNIEXPORT jint JNICALL
Java_de_protokollki_engine_LlamaNative_countTokens(JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return 0;
    return (jint) tokenize(llama_model_get_vocab(e->model), jstr(env, text), false).size();
}

JNIEXPORT void JNICALL
Java_de_protokollki_engine_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->cancel.store(true);
}

// callback: Objekt mit Methode "boolean onProgress(int phase, int done, int total)".
// phase 0 = Prompt lesen, 1 = Antwort schreiben. Rückgabe false = abbrechen.
JNIEXPORT jstring JNICALL
Java_de_protokollki_engine_LlamaNative_complete(JNIEnv *env, jobject, jlong handle,
                                                jstring jsystem, jstring juser, jint max_tokens,
                                                jfloat temperature, jstring jgrammar,
                                                jobject callback) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    e->cancel.store(false);

    jmethodID on_progress = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        on_progress = env->GetMethodID(cls, "onProgress", "(III)Z");
        env->DeleteLocalRef(cls);
    }
    auto report = [&](int phase, int done, int total) -> bool {
        if (!on_progress) return true;
        bool keep = env->CallBooleanMethod(callback, on_progress, phase, done, total);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        if (!keep) e->cancel.store(true);
        return keep;
    };

    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    const std::string prompt = format_chat(*e, jstr(env, jsystem), jstr(env, juser));
    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);

    const int n_ctx = (int) llama_n_ctx(e->ctx);
    const int budget = n_ctx - std::max(64, (int) max_tokens) - 8;
    if ((int) tokens.size() > budget) {
        LOGE("Prompt zu lang (%zu Tokens, erlaubt %d)", tokens.size(), budget);
        return to_jstring(env, "\001PROMPT_TOO_LONG");
    }

    llama_memory_clear(llama_get_memory(e->ctx), true);

    // Prompt in Blöcken verarbeiten
    const int n_prompt = (int) tokens.size();
    for (int i = 0; i < n_prompt; i += BATCH_SIZE) {
        const int n = std::min(BATCH_SIZE, n_prompt - i);
        if (decode(*e, tokens.data() + i, n, i, i + n == n_prompt) != 0 || e->cancel) {
            return e->cancel ? nullptr : to_jstring(env, "\001DECODE_FAILED");
        }
        if (!report(0, i + n, n_prompt)) return nullptr;
    }

    // Sampler: niedrige Temperatur für sachliche Protokolle
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    const std::string grammar = jstr(env, jgrammar);
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g) llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    llama_pos pos = n_prompt;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        if (pos + 1 >= n_ctx) break;
        if (decode(*e, &tok, 1, pos, true) != 0 || e->cancel) break;
        ++pos;
        if (i % 8 == 0 && !report(1, i + 1, max_tokens)) break;
    }
    llama_sampler_free(smpl);
    if (e->cancel) return nullptr;
    return to_jstring(env, out);
}

JNIEXPORT void JNICALL
Java_de_protokollki_engine_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->batch) llama_batch_ext_free(e->batch);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // extern "C"
