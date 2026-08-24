#include "MelonDSAudio.h"
#include "FastForwardAudioProcessor.h"
#include "MicInputOboeCallback.h"
#include "mic_blow.h"
#include "OboeCallback.h"
#include "MelonLog.h"
#include <oboe/Oboe.h>
#include <algorithm>
#include <chrono>
#include <memory>

#define MIC_BUFFER_SIZE 2048

constexpr char FF_AUDIO_DIAG_TAG[] = "FFAudioDiag";
constexpr float INTERNAL_FRAME_RATE = 59.8260982880808f;

std::weak_ptr<MelonDSAndroid::MelonInstance> activeInstance;

std::shared_ptr<oboe::AudioStream> audioStream;
std::shared_ptr<OboeCallback> outputCallback;
std::shared_ptr<oboe::StabilizedCallback> stabilizedOutputCallback;

std::shared_ptr<oboe::AudioStream> micInputStream;
std::shared_ptr<MicInputOboeCallback> micInputCallback;

MelonDSAndroid::AudioSettings currentAudioSettings;
std::mutex micBufferMutex;
std::mutex fastForwardAudioMutex;
std::unique_ptr<MelonDSAndroid::FastForwardAudioProcessor> fastForwardAudioProcessor;
bool fastForwardAudioX2Enabled = false;
bool fastForwardAudioNeedsResume = false;
int actualMicSource = 0;
bool isMicInputEnabled = true;
bool isMicOn = false;
int micBufferReadPos = 0;

static melonDS::AudioOutputProcessorResult discardAudioOutput(void*, melonDS::s16*, int)
{
    return {};
}

namespace MelonDSAndroid
{
    // AUDIO OUTPUT

    void resetAudioOutputStream();
    void resumeFastForwardAudioAfterPause();

    void setupAudioOutputStream(int audioLatency, int volume)
    {
        oboe::PerformanceMode performanceMode;
        switch (audioLatency) {
            case 0:
                performanceMode = oboe::PerformanceMode::LowLatency;
                break;
            case 1:
                performanceMode = oboe::PerformanceMode::None;
                break;
            case 2:
                performanceMode = oboe::PerformanceMode::PowerSaving;
                break;
            default:
                performanceMode = oboe::PerformanceMode::None;
        }

        outputCallback = std::make_shared<OboeCallback>(volume, resetAudioOutputStream);
        stabilizedOutputCallback = std::make_shared<oboe::StabilizedCallback>(outputCallback.get());

        outputCallback->activeInstance = activeInstance;

        oboe::AudioStreamBuilder streamBuilder;
        streamBuilder.setChannelCount(2);
        streamBuilder.setSampleRate(48000);
        streamBuilder.setFormat(oboe::AudioFormat::I16);
        streamBuilder.setFormatConversionAllowed(true);
        streamBuilder.setDirection(oboe::Direction::Output);
        streamBuilder.setPerformanceMode(performanceMode);
        streamBuilder.setSharingMode(oboe::SharingMode::Shared);
        streamBuilder.setUsage(oboe::Usage::Game);
        streamBuilder.setDataCallback(stabilizedOutputCallback);
        streamBuilder.setErrorCallback(stabilizedOutputCallback);

        oboe::Result result = streamBuilder.openStream(audioStream);
        if (result != oboe::Result::OK || !audioStream) {
            LOG_ERROR(FF_AUDIO_DIAG_TAG, "open_failed result=%s stream_valid=%s",
                      oboe::convertToText(result), audioStream ? "true" : "false");
            if (audioStream)
                audioStream->close();
            audioStream = nullptr;
            outputCallback = nullptr;
            stabilizedOutputCallback = nullptr;
            return;
        }

        audioStream->setPerformanceHintEnabled(true);
        auto bufferSizeResult = audioStream->setBufferSizeInFrames(
                std::min(audioStream->getBufferCapacityInFrames(), 2048));
        if (!bufferSizeResult) {
            LOG_WARN(FF_AUDIO_DIAG_TAG, "buffer_size_failed result=%s",
                     oboe::convertToText(bufferSizeResult.error()));
        }

        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "open_success api=%s sample_rate=%d format=%s channels=%d "
                 "frames_per_burst=%d frames_per_callback=%d buffer_capacity_frames=%d "
                 "buffer_size_frames=%d performance_mode=%s sharing_mode=%s direction=%s "
                 "usage=%s content_type=%s device_id=%d session_id=%s performance_hint=%s "
                 "format_conversion=%s src_quality=%s",
                 oboe::convertToText(audioStream->getAudioApi()), audioStream->getSampleRate(),
                 oboe::convertToText(audioStream->getFormat()), audioStream->getChannelCount(),
                 audioStream->getFramesPerBurst(), audioStream->getFramesPerDataCallback(),
                 audioStream->getBufferCapacityInFrames(), audioStream->getBufferSizeInFrames(),
                 oboe::convertToText(audioStream->getPerformanceMode()),
                 oboe::convertToText(audioStream->getSharingMode()),
                 oboe::convertToText(audioStream->getDirection()),
                 oboe::convertToText(audioStream->getUsage()),
                 oboe::convertToText(audioStream->getContentType()), audioStream->getDeviceId(),
                 oboe::convertToText(audioStream->getSessionId()),
                 audioStream->isPerformanceHintEnabled() ? "true" : "false",
                 audioStream->isFormatConversionAllowed() ? "true" : "false",
                 oboe::convertToText(audioStream->getSampleRateConversionQuality()));
    }

    void cleanupAudioOutputStream()
    {
        if (audioStream) {
            if (audioStream->getState() < oboe::StreamState::Closing) {
                audioStream->requestStop();
                audioStream->close();
            }

            audioStream = nullptr;
            outputCallback = nullptr;
            stabilizedOutputCallback = nullptr;
        }
    }

    void resetAudioOutputStream()
    {
        resetFastForwardAudioForPause();
        cleanupAudioOutputStream();
        setupAudioOutputStream(currentAudioSettings.audioLatency, currentAudioSettings.volume);
        if (audioStream) {
            audioStream->requestStart();
        }
        resumeFastForwardAudioAfterPause();
    }

    // MICROPHONE

    void setupMicInputStream()
    {
        micInputCallback = std::make_shared<MicInputOboeCallback>(MIC_BUFFER_SIZE, micBufferMutex);
        oboe::AudioStreamBuilder micStreamBuilder;
        micStreamBuilder.setChannelCount(1);
        micStreamBuilder.setFramesPerCallback(1024);
        micStreamBuilder.setSampleRate(48000);
        micStreamBuilder.setFormat(oboe::AudioFormat::I16);
        micStreamBuilder.setFormatConversionAllowed(true);
        micStreamBuilder.setDirection(oboe::Direction::Input);
        micStreamBuilder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        micStreamBuilder.setPerformanceMode(oboe::PerformanceMode::None);
        micStreamBuilder.setSharingMode(oboe::SharingMode::Exclusive);
        micStreamBuilder.setUsage(oboe::Usage::Game);
        micStreamBuilder.setDataCallback(micInputCallback);

        oboe::Result micResult = micStreamBuilder.openStream(micInputStream);
        if (micResult != oboe::Result::OK)
        {
            actualMicSource = 1;
            Log(Error, "Failed to init mic audio stream");
            micInputCallback = nullptr;
        }
    }

    void cleanupMicInputStream()
    {
        if (micInputStream)
        {
            micInputStream->requestStop();
            micInputStream->close();

            micInputStream = nullptr;

            std::lock_guard<std::mutex> lock(micBufferMutex);
            micInputCallback = nullptr;
        }
    }

    void startMicStreamIfAllowed()
    {
        if (actualMicSource == 2 && micInputStream && isMicInputEnabled && isMicOn)
            micInputStream->requestStart();
    }

    void userEnableMic()
    {
        isMicInputEnabled = true;
        startMicStreamIfAllowed();
    }

    void userDisableMic()
    {
        isMicInputEnabled = false;
        if (micInputStream)
            micInputStream->requestStop();
    }

    void enableMic()
    {
        isMicOn = true;
        startMicStreamIfAllowed();
    }

    void disableMic()
    {
        isMicOn = false;
        if (micInputStream)
            micInputStream->requestStop();
    }

    int readMic(s16* data, int maxlength)
    {
        int micSource = actualMicSource;
        if (!isMicInputEnabled)
        {
            micSource = 0;
        }

        if (micSource == 0)
        {
            memset(data, 0, maxlength * sizeof(s16));
            return maxlength;
        }

        int micBufferLength;
        s16* micBuffer;

        if (micSource == 2)
        {
            micBufferMutex.lock();
            if (!micInputCallback)
            {
                micBufferMutex.unlock();
                memset(data, 0, maxlength * sizeof(s16));
                return maxlength;
            }
            micBufferLength = MIC_BUFFER_SIZE / sizeof(s16);
            micBuffer = micInputCallback->buffer;
        }
        else
        {
            micBufferLength = sizeof(mic_blow) / sizeof(s16);
            micBuffer = (s16*) &mic_blow[0];
        }

        int readlength = 0;
        while (readlength < maxlength)
        {
            int thislen = maxlength - readlength;
            if ((micBufferReadPos + thislen) > micBufferLength)
                thislen = micBufferLength - micBufferReadPos;

            if (micSource == 2)
            {
                if (thislen > micInputCallback->bufferCount)
                    thislen = micInputCallback->bufferCount;

                micInputCallback->bufferCount -= thislen;
            }

            if (!thislen)
                break;

            memcpy(data, &micBuffer[micBufferReadPos], thislen * sizeof(s16));
            data += thislen;
            micBufferReadPos += thislen;
            if (micBufferReadPos >= micBufferLength)
                micBufferReadPos -= micBufferLength;

            readlength += thislen;
        }

        if (micSource == 2)
            micBufferMutex.unlock();

        return readlength;
    }

    // GENERAL

    void setupAudio(AudioSettings audioSettings)
    {
        isMicOn = false;
        actualMicSource = audioSettings.micSource;
        currentAudioSettings = audioSettings;
        fastForwardAudioProcessor = std::make_unique<FastForwardAudioProcessor>();

        if (audioSettings.soundEnabled)
            setupAudioOutputStream(audioSettings.audioLatency, audioSettings.volume);

        if (audioSettings.micSource == 2)
            setupMicInputStream();
    }

    void updateAudioSettings(AudioSettings audioSettings)
    {
        if (audioSettings.soundEnabled && currentAudioSettings.volume > 0) {
            if (!audioStream) {
                setupAudioOutputStream(audioSettings.audioLatency, audioSettings.volume);
            } else if (currentAudioSettings.audioLatency != audioSettings.audioLatency || currentAudioSettings.volume != audioSettings.volume) {
                // Recreate audio stream with new settings
                cleanupAudioOutputStream();
                setupAudioOutputStream(audioSettings.audioLatency, audioSettings.volume);
            }
        } else if (audioStream) {
            cleanupAudioOutputStream();
        }

        int oldMicSource = actualMicSource;
        actualMicSource = audioSettings.micSource;

        if (oldMicSource == 2 && audioSettings.micSource != 2) {
            // No longer using device mic. Destroy stream
            cleanupMicInputStream();
        } else if (oldMicSource != 2 && audioSettings.micSource == 2) {
            // Now using device mic. Setup stream
            setupMicInputStream();
        }

        currentAudioSettings = audioSettings;
    }

    void setAudioActiveInstance(std::shared_ptr<MelonInstance> instance)
    {
        const double skew = std::clamp(60.0 / INTERNAL_FRAME_RATE, 0.995, 1.005);
        instance->setAudioOutputSkew(skew);
        activeInstance = instance;
        if (outputCallback)
            outputCallback->activeInstance = activeInstance;
    }

    AudioOutputMetrics getAudioOutputMetrics()
    {
        auto instance = activeInstance.lock();
        return instance ? instance->getAudioOutputMetrics() : AudioOutputMetrics{};
    }

    AudioOutputMetrics resetAudioOutputMetrics()
    {
        auto instance = activeInstance.lock();
        return instance ? instance->resetAudioOutputMetrics() : AudioOutputMetrics{};
    }

    void setFastForwardAudioX2Enabled(bool enabled)
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!instance || !fastForwardAudioProcessor)
            return;

        const auto start = std::chrono::steady_clock::now();
        if (enabled)
        {
            fastForwardAudioProcessor->ResetStream();
            instance->setAudioOutputProcessor(
                    &FastForwardAudioProcessor::ProcessCallback,
                    fastForwardAudioProcessor.get());
            fastForwardAudioX2Enabled = true;
            fastForwardAudioNeedsResume = false;
            const auto transitionUs = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - start).count();
            LOG_INFO(FF_AUDIO_DIAG_TAG,
                     "dsp_transition enabled=true backend=soundtouch tempo=2.000 pitch=1.000 "
                     "transition_us=%lld configured_initial_latency_frames=%d",
                     static_cast<long long>(transitionUs),
                     fastForwardAudioProcessor->GetConfiguredInitialLatencyFrames());
            return;
        }

        instance->setAudioOutputProcessor(nullptr, nullptr);
        fastForwardAudioProcessor->ResetStream();
        fastForwardAudioX2Enabled = false;
        fastForwardAudioNeedsResume = false;
        const auto transitionUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "dsp_transition enabled=false backend=soundtouch transition_us=%lld",
                 static_cast<long long>(transitionUs));
    }

    void resetFastForwardAudioForPause()
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!instance || !fastForwardAudioProcessor || !fastForwardAudioX2Enabled)
            return;

        const auto start = std::chrono::steady_clock::now();
        instance->setAudioOutputProcessor(&discardAudioOutput, nullptr);
        fastForwardAudioProcessor->ResetStream();
        fastForwardAudioNeedsResume = true;
        const auto resetUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "dsp_stream_suspend reason=pause backend=soundtouch reset_us=%lld",
                 static_cast<long long>(resetUs));
    }

    void resumeFastForwardAudioAfterPause()
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!fastForwardAudioNeedsResume || !instance || !fastForwardAudioProcessor ||
            !fastForwardAudioX2Enabled)
            return;

        const auto start = std::chrono::steady_clock::now();
        instance->setAudioOutputProcessor(
                &FastForwardAudioProcessor::ProcessCallback,
                fastForwardAudioProcessor.get());
        fastForwardAudioNeedsResume = false;
        const auto resumeUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG, "dsp_stream_resume backend=soundtouch resume_us=%lld",
                 static_cast<long long>(resumeUs));
    }

    void cleanupAudio()
    {
        if (fastForwardAudioProcessor && fastForwardAudioX2Enabled)
            setFastForwardAudioX2Enabled(false);
        cleanupAudioOutputStream();
        cleanupMicInputStream();
        fastForwardAudioProcessor.reset();
        fastForwardAudioX2Enabled = false;
        fastForwardAudioNeedsResume = false;
    }

    void startAudio()
    {
        if (audioStream)
            audioStream->requestStart();

        resumeFastForwardAudioAfterPause();

        startMicStreamIfAllowed();
    }

    void pauseAudio()
    {
        if (audioStream)
            audioStream->requestPause();

        if (micInputStream)
            micInputStream->requestStop();

        resetFastForwardAudioForPause();
    }
}
