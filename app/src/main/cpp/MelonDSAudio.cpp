#include "MelonDSAudio.h"
#include "FastForwardAudioProcessor.h"
#include "MelonDS.h"
#include "MicInputOboeCallback.h"
#include "mic_blow.h"
#include "OboeCallback.h"
#include "MelonLog.h"
#include <oboe/Oboe.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>

#define MIC_BUFFER_SIZE 2048

constexpr char FF_AUDIO_DIAG_TAG[] = "FFAudioDiag";
constexpr float INTERNAL_FRAME_RATE = 59.8260982880808f;

namespace
{
constexpr bool EnablePreDspCapture = false;
constexpr size_t PreDspCaptureSampleRate = 48000;
constexpr size_t PreDspCaptureChannels = 2;
constexpr size_t PreDspCaptureFrames = 30 * PreDspCaptureSampleRate;
constexpr size_t PreDspCaptureSamples = PreDspCaptureFrames * PreDspCaptureChannels;
constexpr uint32_t PreDspCaptureDataBytes =
        PreDspCaptureSamples * sizeof(melonDS::s16);

enum class PreDspCaptureState : uint8_t
{
    Idle,
    Capturing,
    Ready,
    Writing,
    Written,
    Failed,
};

struct WavHeader
{
    char Riff[4];
    uint32_t RiffSize;
    char Wave[4];
    char Fmt[4];
    uint32_t FmtSize;
    uint16_t AudioFormat;
    uint16_t Channels;
    uint32_t SampleRate;
    uint32_t ByteRate;
    uint16_t BlockAlign;
    uint16_t BitsPerSample;
    char Data[4];
    uint32_t DataSize;
};

static_assert(sizeof(WavHeader) == 44);
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__);

std::array<melonDS::s16, PreDspCaptureSamples> preDspCaptureBuffer {};
std::atomic<PreDspCaptureState> preDspCaptureState {PreDspCaptureState::Idle};
std::atomic<size_t> preDspCapturedFrames {0};
std::atomic<size_t> preDspCaptureBlocks {0};

std::string preDspCapturePath()
{
    return MelonDSAndroid::internalFilesDir + "/thor_ff_source_normal.wav";
}

void armPreDspCapture()
{
    if constexpr (!EnablePreDspCapture)
        return;

    std::remove(preDspCapturePath().c_str());
    preDspCapturedFrames.store(0, std::memory_order_relaxed);
    preDspCaptureBlocks.store(0, std::memory_order_relaxed);
    preDspCaptureState.store(PreDspCaptureState::Capturing, std::memory_order_release);
}

melonDS::AudioOutputProcessorResult capturePreDspAudio(
        void*, melonDS::s16* samples, int frames)
{
    melonDS::AudioOutputProcessorResult result;
    result.OutputFrames = std::max(0, frames);

    if constexpr (!EnablePreDspCapture)
        return result;
    if (frames <= 0 ||
        preDspCaptureState.load(std::memory_order_acquire) !=
                PreDspCaptureState::Capturing)
        return result;

    const size_t offset = preDspCapturedFrames.load(std::memory_order_relaxed);
    const size_t copiedFrames = std::min(
            static_cast<size_t>(frames), PreDspCaptureFrames - offset);
    std::memcpy(preDspCaptureBuffer.data() + offset * PreDspCaptureChannels,
                samples,
                copiedFrames * PreDspCaptureChannels * sizeof(melonDS::s16));

    const size_t capturedFrames = offset + copiedFrames;
    preDspCaptureBlocks.fetch_add(1, std::memory_order_relaxed);
    preDspCapturedFrames.store(capturedFrames, std::memory_order_release);
    if (capturedFrames == PreDspCaptureFrames)
        preDspCaptureState.store(PreDspCaptureState::Ready, std::memory_order_release);

    return result;
}

void writePreDspCaptureIfReady()
{
    if constexpr (!EnablePreDspCapture)
        return;

    PreDspCaptureState expected = PreDspCaptureState::Ready;
    if (!preDspCaptureState.compare_exchange_strong(
            expected, PreDspCaptureState::Writing, std::memory_order_acq_rel))
        return;

    const WavHeader header = {
            {'R', 'I', 'F', 'F'},
            36 + PreDspCaptureDataBytes,
            {'W', 'A', 'V', 'E'},
            {'f', 'm', 't', ' '},
            16,
            1,
            PreDspCaptureChannels,
            PreDspCaptureSampleRate,
            PreDspCaptureSampleRate * PreDspCaptureChannels * sizeof(melonDS::s16),
            PreDspCaptureChannels * sizeof(melonDS::s16),
            8 * sizeof(melonDS::s16),
            {'d', 'a', 't', 'a'},
            PreDspCaptureDataBytes,
    };

    const std::string path = preDspCapturePath();
    FILE* file = std::fopen(path.c_str(), "wb");
    bool written = false;
    if (file != nullptr)
    {
        const bool samplesWritten =
                std::fwrite(&header, sizeof(header), 1, file) == 1 &&
                std::fwrite(preDspCaptureBuffer.data(), sizeof(melonDS::s16),
                            PreDspCaptureSamples, file) == PreDspCaptureSamples;
        written = std::fclose(file) == 0 && samplesWritten;
    }

    preDspCaptureState.store(
            written ? PreDspCaptureState::Written : PreDspCaptureState::Failed,
            std::memory_order_release);
    LOG_INFO(FF_AUDIO_DIAG_TAG,
             "pre_dsp_capture written=%s frames=%zu blocks=%zu path=%s",
             written ? "true" : "false",
             preDspCapturedFrames.load(std::memory_order_acquire),
             preDspCaptureBlocks.load(std::memory_order_relaxed),
             path.c_str());
}
}

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
bool fastForwardAudioEnabled = false;
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
        if constexpr (EnablePreDspCapture)
            instance->setAudioOutputProcessor(&capturePreDspAudio, nullptr);
        if (outputCallback)
            outputCallback->activeInstance = activeInstance;
    }

    AudioOutputMetrics getAudioOutputMetrics()
    {
        auto instance = activeInstance.lock();
        writePreDspCaptureIfReady();
        if (fastForwardAudioProcessor)
            fastForwardAudioProcessor->LogDiagnostics("query");
        return instance ? instance->getAudioOutputMetrics() : AudioOutputMetrics{};
    }

    AudioOutputMetrics resetAudioOutputMetrics()
    {
        auto instance = activeInstance.lock();
        const AudioOutputMetrics metrics =
                instance ? instance->resetAudioOutputMetrics() : AudioOutputMetrics{};
        if (fastForwardAudioProcessor)
            fastForwardAudioProcessor->ResetDiagnostics();
        if (instance)
            armPreDspCapture();
        return metrics;
    }

    void setFastForwardAudioTempo(int tempo)
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!instance || !fastForwardAudioProcessor)
            return;

        const auto start = std::chrono::steady_clock::now();
        if (tempo > 0)
        {
            if (fastForwardAudioEnabled)
                instance->setAudioOutputProcessor(&discardAudioOutput, nullptr);
            fastForwardAudioProcessor->ConfigureTempo(tempo);
            instance->setAudioOutputProcessor(
                    &FastForwardAudioProcessor::ProcessCallback,
                    fastForwardAudioProcessor.get());
            fastForwardAudioEnabled = true;
            fastForwardAudioNeedsResume = false;
            const auto transitionUs = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - start).count();
            LOG_INFO(FF_AUDIO_DIAG_TAG,
                     "dsp_transition enabled=true backend=rubberband_r2_async_channels_together "
                     "tempo=%d.000 pitch=1.000 "
                     "transition_us=%lld configured_initial_latency_frames=%d",
                     tempo,
                     static_cast<long long>(transitionUs),
                     fastForwardAudioProcessor->GetConfiguredInitialLatencyFrames());
            return;
        }

        instance->setAudioOutputProcessor(nullptr, nullptr);
        fastForwardAudioProcessor->ResetStream();
        fastForwardAudioEnabled = false;
        fastForwardAudioNeedsResume = false;
        const auto transitionUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "dsp_transition enabled=false backend=rubberband_r2_async_channels_together "
                 "transition_us=%lld",
                 static_cast<long long>(transitionUs));
    }

    void resetFastForwardAudioForPause()
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!instance || !fastForwardAudioProcessor || !fastForwardAudioEnabled)
            return;

        const auto start = std::chrono::steady_clock::now();
        instance->setAudioOutputProcessor(&discardAudioOutput, nullptr);
        fastForwardAudioProcessor->ResetStream();
        fastForwardAudioNeedsResume = true;
        const auto resetUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "dsp_stream_suspend reason=pause "
                 "backend=rubberband_r2_async_channels_together reset_us=%lld",
                 static_cast<long long>(resetUs));
    }

    void resumeFastForwardAudioAfterPause()
    {
        std::lock_guard<std::mutex> lock(fastForwardAudioMutex);
        auto instance = activeInstance.lock();
        if (!fastForwardAudioNeedsResume || !instance || !fastForwardAudioProcessor ||
            !fastForwardAudioEnabled)
            return;

        const auto start = std::chrono::steady_clock::now();
        fastForwardAudioProcessor->ResumeStream();
        instance->setAudioOutputProcessor(
                &FastForwardAudioProcessor::ProcessCallback,
                fastForwardAudioProcessor.get());
        fastForwardAudioNeedsResume = false;
        const auto resumeUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
        LOG_INFO(FF_AUDIO_DIAG_TAG,
                 "dsp_stream_resume backend=rubberband_r2_async_channels_together "
                 "resume_us=%lld",
                 static_cast<long long>(resumeUs));
    }

    void cleanupAudio()
    {
        writePreDspCaptureIfReady();
        if (fastForwardAudioProcessor && fastForwardAudioEnabled)
            setFastForwardAudioTempo(0);
        cleanupAudioOutputStream();
        cleanupMicInputStream();
        fastForwardAudioProcessor.reset();
        fastForwardAudioEnabled = false;
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
        writePreDspCaptureIfReady();
    }
}
