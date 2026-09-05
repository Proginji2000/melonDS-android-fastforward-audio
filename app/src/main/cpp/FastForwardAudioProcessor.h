#ifndef FASTFORWARDAUDIOPROCESSOR_H
#define FASTFORWARDAUDIOPROCESSOR_H

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <semaphore.h>
#include <thread>
#include <vector>
#include "SPU.h"
#include "SoundTouch.h"
#include "rubberband/RubberBandStretcher.h"

namespace MelonDSAndroid
{

struct FastForwardAudioDiagnostics
{
    uint64_t AsyncInputFramesEnqueued = 0;
    uint64_t AsyncInputFramesDropped = 0;
    uint64_t AsyncOutputFramesProduced = 0;
    uint64_t AsyncOutputFramesDequeued = 0;
    uint64_t AsyncOutputFramesDropped = 0;
    uint64_t InputRingCurrent = 0;
    uint64_t InputRingHighWater = 0;
    uint64_t OutputRingCurrent = 0;
    uint64_t OutputRingHighWater = 0;
    uint64_t OutputDebtCurrent = 0;
    uint64_t OutputDebtMaximum = 0;
    uint64_t OutputDebtClampedFrames = 0;
    uint64_t InputRingAtStop = 0;
    uint64_t OutputRingAtStop = 0;
    uint64_t OutputDebtAtStop = 0;
    uint64_t WorkerProcessingTimeUs = 0;
    uint64_t WorkerCpuTimeUs = 0;
    uint64_t WorkerMaxProcessingTimeUs = 0;
    uint64_t WorkerWakeups = 0;
    int64_t RubberBandAvailable = 0;
};

class FastForwardAudioProcessor
{
public:
    FastForwardAudioProcessor();
    ~FastForwardAudioProcessor();
    void ConfigureTempo(int tempo);
    void ResetStream();
    void ResumeStream();

    int GetConfiguredInitialLatencyFrames() const;
    FastForwardAudioDiagnostics GetDiagnostics() const;
    void ResetDiagnostics();
    void LogDiagnostics(const char* reason) const;

    static melonDS::AudioOutputProcessorResult ProcessCallback(
            void* context, melonDS::s16* samples, int frames);

private:
    melonDS::AudioOutputProcessorResult Process(melonDS::s16* samples, int frames);
    void ResetRubberBandStream();
    void PrepareAsyncRubberBandStream();
    void StartWorker();
    void StopWorker();
    void WorkerLoop();
    void ProcessWorkerBlock(size_t frames);
    void DrainRubberBandOutput();
    size_t EnqueueInput(const melonDS::s16* samples, size_t frames);
    size_t DequeueInput();
    size_t EnqueueOutput(size_t offset, size_t frames);
    size_t DequeueOutput(melonDS::s16* samples, size_t frames);
    size_t InputRingFrames() const;
    size_t OutputRingFrames() const;
    void ClearAsyncState();
    void CaptureStopState();

    static constexpr int SampleRate = 48000;
    static constexpr int Channels = 2;
    // SPU's Blip buffers are constructed with a 512-frame capacity.
    static constexpr int MaxProcessFrames = 512;
    static constexpr size_t RingCapacityFrames = 32768;
    static constexpr size_t RingMask = RingCapacityFrames - 1;
    static constexpr uint64_t MaximumOutputDebtFrames = RingCapacityFrames;
    static_assert((RingCapacityFrames & RingMask) == 0);

    soundtouch::SoundTouch SoundTouch;
    std::unique_ptr<RubberBand::RubberBandStretcher> RubberBandProcessor;
    int Tempo = 2;
    int OutputPhase = 0;
    size_t RubberBandPreferredStartPadFrames = 0;
    size_t RubberBandInitialLatencyFrames = 0;
    size_t RubberBandDelayFramesToDiscard = 0;
    std::vector<float> FloatInput;
    std::vector<float> FloatOutput;
    std::array<std::array<float, MaxProcessFrames>, Channels> RubberBandInput {};
    std::array<std::array<float, MaxProcessFrames>, Channels> RubberBandOutput {};

    std::array<melonDS::s16, RingCapacityFrames * Channels> InputRing {};
    std::array<melonDS::s16, RingCapacityFrames * Channels> OutputRing {};
    std::atomic<uint64_t> InputRead {0};
    std::atomic<uint64_t> InputWrite {0};
    std::atomic<uint64_t> OutputRead {0};
    std::atomic<uint64_t> OutputWrite {0};

    sem_t WorkerSemaphore {};
    bool WorkerSemaphoreInitialized = false;
    std::thread WorkerThread;
    std::atomic<bool> WorkerStopRequested {false};
    std::atomic<bool> WorkerWakePending {false};
    uint64_t OutputDebtFrames = 0;
    uint64_t OutputPacingRemainder = 0;

    std::atomic<uint64_t> AsyncInputFramesEnqueued {0};
    std::atomic<uint64_t> AsyncInputFramesDropped {0};
    std::atomic<uint64_t> AsyncOutputFramesProduced {0};
    std::atomic<uint64_t> AsyncOutputFramesDequeued {0};
    std::atomic<uint64_t> AsyncOutputFramesDropped {0};
    std::atomic<uint64_t> InputRingHighWater {0};
    std::atomic<uint64_t> OutputRingHighWater {0};
    std::atomic<uint64_t> OutputDebtCurrent {0};
    std::atomic<uint64_t> OutputDebtMaximum {0};
    std::atomic<uint64_t> OutputDebtClampedFrames {0};
    std::atomic<uint64_t> InputRingAtStop {0};
    std::atomic<uint64_t> OutputRingAtStop {0};
    std::atomic<uint64_t> OutputDebtAtStop {0};
    std::atomic<uint64_t> WorkerProcessingTimeUs {0};
    std::atomic<uint64_t> WorkerCpuTimeUs {0};
    std::atomic<uint64_t> WorkerMaxProcessingTimeUs {0};
    std::atomic<uint64_t> WorkerWakeups {0};
    std::atomic<int64_t> RubberBandAvailable {0};
};

}

#endif // FASTFORWARDAUDIOPROCESSOR_H
