#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <SoundTouch.h>
#include <memory>
#include <vector>
#include <mutex>
#include <cmath>

#define LOG_TAG "PitchShifterEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<soundtouch::SoundTouch> sSoundTouch;
static std::shared_ptr<oboe::AudioStream> sOutputStream;
static int sChannels = 2;
static float sCurrentPitch = 4.0f; // Default +4 semitones for obvious audible shift
static float sCurrentGain = 1.5f;  // Slight boost to overcome source audio
static uint64_t sTotalFramesProcessed = 0;
static std::mutex sEngineMutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_startNativeAudioEngine(
        JNIEnv *env, jobject /* this */, jint sampleRate, jint channels) {

    std::lock_guard<std::mutex> lock(sEngineMutex);
    sChannels = channels;
    sTotalFramesProcessed = 0;

    // Initialize SoundTouch
    sSoundTouch = std::make_unique<soundtouch::SoundTouch>();
    sSoundTouch->setSampleRate(static_cast<uint>(sampleRate));
    sSoundTouch->setChannels(static_cast<uint>(channels));
    sSoundTouch->setPitchSemiTones(sCurrentPitch);
    sSoundTouch->setSetting(SETTING_USE_AA_FILTER, 1);
    sSoundTouch->setSetting(SETTING_SEQUENCE_MS, 40);
    sSoundTouch->setSetting(SETTING_SEEKWINDOW_MS, 15);
    sSoundTouch->setSetting(SETTING_OVERLAP_MS, 8);

    LOGI("SoundTouch initialized: rate=%d, ch=%d, pitch=%.1f semitones",
         sampleRate, channels, sCurrentPitch);

    // Initialize Oboe output stream
    // IMPORTANT: Use Shared mode! Exclusive mode fails with ErrorUnavailable when other audio apps are active.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(channels)
           ->setSampleRate(sampleRate)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music);

    oboe::Result result = builder.openStream(sOutputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream (Shared AAudio): %s. Falling back to OpenSLES...",
             oboe::convertToText(result));
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        result = builder.openStream(sOutputStream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream completely: %s", oboe::convertToText(result));
        sOutputStream.reset();
        return JNI_FALSE;
    }

    result = sOutputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        sOutputStream->close();
        sOutputStream.reset();
        return JNI_FALSE;
    }

    LOGI("Oboe output stream started successfully (API: %s, Sharing: %s)",
         oboe::convertToText(sOutputStream->getAudioApi()),
         oboe::convertToText(sOutputStream->getSharingMode()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_setNativePitch(
        JNIEnv *env, jobject /* this */, jfloat semitones) {
    std::lock_guard<std::mutex> lock(sEngineMutex);
    sCurrentPitch = semitones;
    if (sSoundTouch) {
        sSoundTouch->setPitchSemiTones(semitones);
        LOGI("Native pitch updated to %.1f semitones", semitones);
    }
}

JNIEXPORT void JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_setNativeGain(
        JNIEnv *env, jobject /* this */, jfloat gain) {
    std::lock_guard<std::mutex> lock(sEngineMutex);
    sCurrentGain = gain;
    LOGI("Native gain updated to %.2fx", gain);
}

JNIEXPORT jlong JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_getNativeProcessedFrames(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(sEngineMutex);
    return static_cast<jlong>(sTotalFramesProcessed);
}

JNIEXPORT jboolean JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_isNativeEngineRunning(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(sEngineMutex);
    return (sOutputStream && sOutputStream->getState() == oboe::StreamState::Started) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_processAudioBuffer(
        JNIEnv *env, jobject /* this */, jshortArray buffer, jint size) {

    std::lock_guard<std::mutex> lock(sEngineMutex);

    if (!sSoundTouch || !sOutputStream) {
        return;
    }

    // Get input samples (int16)
    jshort *inputSamples = env->GetShortArrayElements(buffer, nullptr);
    if (!inputSamples) return;

    int numSamples = size;
    int numFrames = numSamples / sChannels;

    // Convert int16 to float for SoundTouch
    std::vector<float> floatInput(numSamples);
    for (int i = 0; i < numSamples; i++) {
        floatInput[i] = static_cast<float>(inputSamples[i]) / 32768.0f;
    }

    // Feed into SoundTouch
    sSoundTouch->putSamples(floatInput.data(), static_cast<uint>(numFrames));

    // Drain processed samples from SoundTouch until buffer is empty
    std::vector<float> floatOutput(numSamples * 2);
    std::vector<int16_t> outputInt16(numSamples * 2);

    uint receivedFrames = 0;
    do {
        receivedFrames = sSoundTouch->receiveSamples(
                floatOutput.data(),
                static_cast<uint>(numSamples));

        if (receivedFrames > 0) {
            sTotalFramesProcessed += receivedFrames;
            int totalOutputSamples = static_cast<int>(receivedFrames) * sChannels;

            for (int i = 0; i < totalOutputSamples; i++) {
                float val = floatOutput[i] * sCurrentGain;
                if (val > 1.0f) val = 1.0f;
                if (val < -1.0f) val = -1.0f;
                outputInt16[i] = static_cast<int16_t>(val * 32767.0f);
            }

            // Write to Oboe output stream
            auto writeResult = sOutputStream->write(
                    outputInt16.data(),
                    static_cast<int32_t>(receivedFrames),
                    100000000LL); // 100ms timeout
            if (!writeResult) {
                LOGE("Oboe write error: %s", oboe::convertToText(writeResult.error()));
            }
        }
    } while (receivedFrames > 0);

    env->ReleaseShortArrayElements(buffer, inputSamples, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_realtime_pitchshifter_AudioCaptureService_stopNativeAudioEngine(
        JNIEnv *env, jobject /* this */) {

    std::lock_guard<std::mutex> lock(sEngineMutex);
    LOGI("Stopping native audio engine...");

    if (sOutputStream) {
        sOutputStream->requestStop();
        sOutputStream->close();
        sOutputStream.reset();
    }

    if (sSoundTouch) {
        sSoundTouch->clear();
        sSoundTouch.reset();
    }

    LOGI("Native audio engine stopped.");
}

} // extern "C"
