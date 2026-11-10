#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

constexpr const char* kTag = "EtaWhisper";

struct Session {
    whisper_context* context = nullptr;
    int threads = 4;
};

long newSession(const char* modelPath, int threads) {
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* context = whisper_init_from_file_with_params(modelPath, params);
    if (context == nullptr) return 0;
    auto* session = new Session();
    session->context = context;
    session->threads = threads > 0 ? threads : 4;
    return reinterpret_cast<long>(session);
}

std::string transcribe(Session* session, const int16_t* samples, size_t count, const std::string& language) {
    if (session == nullptr || session->context == nullptr || count == 0) return {};

    std::vector<float> pcm(count);
    for (size_t index = 0; index < count; ++index) {
        pcm[index] = static_cast<float>(samples[index]) / 32768.0f;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.n_threads = session->threads;
    params.language = language.empty() ? nullptr : language.c_str();

    if (whisper_full(session->context, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "whisper_full failed");
        return {};
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->context);
    for (int index = 0; index < segments; ++index) {
        const char* segment = whisper_full_get_segment_text(session->context, index);
        if (segment != nullptr) text += segment;
    }
    return text;
}

void deleteSession(Session* session) {
    if (session == nullptr) return;
    if (session->context != nullptr) whisper_free(session->context);
    delete session;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mangi_eta_agent_voice_WhisperBridge_nativeInit(
        JNIEnv* env, jobject /*this*/, jstring modelPath, jint threads) {
    if (modelPath == nullptr) return 0;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    const long handle = newSession(path, static_cast<int>(threads));
    env->ReleaseStringUTFChars(modelPath, path);
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mangi_eta_agent_voice_WhisperBridge_nativeTranscribe(
        JNIEnv* env, jobject /*this*/, jlong handle, jbyteArray pcm, jstring language) {
    auto* session = reinterpret_cast<Session*>(static_cast<intptr_t>(handle));
    if (session == nullptr || pcm == nullptr) return env->NewStringUTF("");

    const jsize length = env->GetArrayLength(pcm);
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (bytes == nullptr) return env->NewStringUTF("");

    std::string languageTag;
    if (language != nullptr) {
        const char* tag = env->GetStringUTFChars(language, nullptr);
        if (tag != nullptr) {
            languageTag = tag;
            env->ReleaseStringUTFChars(language, tag);
        }
    }

    const std::string text = transcribe(
            session, reinterpret_cast<const int16_t*>(bytes), static_cast<size_t>(length) / sizeof(int16_t),
            languageTag);
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_mangi_eta_agent_voice_WhisperBridge_nativeRelease(
        JNIEnv* /*env*/, jobject /*this*/, jlong handle) {
    deleteSession(reinterpret_cast<Session*>(static_cast<intptr_t>(handle)));
}
