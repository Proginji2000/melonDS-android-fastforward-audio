#include "FastForwardAudioProcessor.h"
#include "MelonLog.h"
#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <chrono>
#include <cmath>
#include <ctime>
#include <pthread.h>
#include <stdexcept>

namespace MelonDSAndroid
{

namespace
{

constexpr char FF_AUDIO_DIAG_TAG[] = "FFAudioDiag";

// 0 = SoundTouch, 1 = synchronous Rubber Band R2, 2 = synchronous R3,
// 3 = asynchronous Rubber Band R2 with linked stereo channels.
constexpr int FastForwardTimeStretchEngine = 3;
static_assert(FastForwardTimeStretchEngine >= 0 && FastForwardTimeStretchEngine <= 3);
constexpr bool UseAsyncRubberBand = FastForwardTimeStretchEngine == 3;

constexpr int SoundTouchQualityProfile = 1;
static_assert(SoundTouchQualityProfile >= 0 && SoundTouchQualityProfile <= 3);

RubberBand::RubberBandStretcher::Options GetRubberBandOptions()
{
    if constexpr (FastForwardTimeStretchEngine == 1)
    {
        return RubberBand::RubberBandStretcher::OptionProcessRealTime
                | RubberBand::RubberBandStretcher::OptionEngineFaster;
    }

    if constexpr (FastForwardTimeStretchEngine == 2)
    {
        return RubberBand::RubberBandStretcher::OptionProcessRealTime
                | RubberBand::RubberBandStretcher::OptionEngineFiner
                | RubberBand::RubberBandStretcher::OptionChannelsTogether;
    }

    return RubberBand::RubberBandStretcher::OptionProcessRealTime
            | RubberBand::RubberBandStretcher::OptionEngineFaster
            | RubberBand::RubberBandStretcher::OptionChannelsTogether;
}

uint64_t SteadyTimeUs()
{
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
}

uint64_t ThreadCpuTimeUs()
{
    timespec time {};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &time) != 0)
        return 0;
    return static_cast<uint64_t>(time.tv_sec) * 1000000ULL
            + static_cast<uint64_t>(time.tv_nsec) / 1000ULL;
}

void UpdateMaximum(std::atomic<uint64_t>& maximum, uint64_t value)
{
    uint64_t previous = maximum.load(std::memory_order_relaxed);
    while (previous < value &&
           !maximum.compare_exchange_weak(
                   previous, value, std::memory_order_relaxed))
    {
    }
}

melonDS::s16 FloatToS16(float sample)
{
    const int value = static_cast<int>(std::lrint(sample * 32768.0f));
    return static_cast<melonDS::s16>(std::clamp(value, -32768, 32767));
}

void ApplySoundTouchQualityProfile(soundtouch::SoundTouch& soundTouch)
{
    if constexpr (SoundTouchQualityProfile == 0)
        return;

    soundTouch.setSetting(SETTING_USE_QUICKSEEK, 0);
    if constexpr (SoundTouchQualityProfile == 1)
    {
        soundTouch.setSetting(SETTING_SEQUENCE_MS, 40);
        soundTouch.setSetting(SETTING_SEEKWINDOW_MS, 20);
        soundTouch.setSetting(SETTING_OVERLAP_MS, 12);
    }
    else if constexpr (SoundTouchQualityProfile == 2)
    {
        soundTouch.setSetting(SETTING_SEQUENCE_MS, 82);
        soundTouch.setSetting(SETTING_SEEKWINDOW_MS, 28);
        soundTouch.setSetting(SETTING_OVERLAP_MS, 12);
    }
    else if constexpr (SoundTouchQualityProfile == 3)
    {
        soundTouch.setSetting(SETTING_SEQUENCE_MS, 40);
        soundTouch.setSetting(SETTING_SEEKWINDOW_MS, 15);
        soundTouch.setSetting(SETTING_OVERLAP_MS, 12);
    }
}

}

FastForwardAudioProcessor::FastForwardAudioProcessor()
{
    SoundTouch.setSampleRate(SampleRate);
    SoundTouch.setChannels(Channels);
    ApplySoundTouchQualityProfile(SoundTouch);
    SoundTouch.setRate(1.0);
    SoundTouch.setPitch(1.0);

    if constexpr (FastForwardTimeStretchEngine != 0)
    {
        RubberBandProcessor = std::make_unique<RubberBand::RubberBandStretcher>(
                SampleRate, Channels, GetRubberBandOptions(), 1.0 / Tempo, 1.0);
        RubberBandProcessor->setMaxProcessSize(MaxProcessFrames);
    }

    if constexpr (UseAsyncRubberBand)
    {
        if (sem_init(&WorkerSemaphore, 0, 0) != 0)
            throw std::runtime_error("Failed to initialize fast-forward DSP semaphore");
        WorkerSemaphoreInitialized = true;
    }
}

FastForwardAudioProcessor::~FastForwardAudioProcessor()
{
    if constexpr (UseAsyncRubberBand)
    {
        StopWorker();
        if (WorkerSemaphoreInitialized)
            sem_destroy(&WorkerSemaphore);
    }
}

void FastForwardAudioProcessor::ConfigureTempo(int tempo)
{
    if constexpr (FastForwardTimeStretchEngine == 0)
    {
        SoundTouch.clear();
        SoundTouch.setTempo(tempo);
    }

    Tempo = tempo;
    OutputPhase = 0;

    if constexpr (UseAsyncRubberBand)
    {
        StopWorker();
        ClearAsyncState();
        ResetDiagnostics();
        StartWorker();
    }
    else if constexpr (FastForwardTimeStretchEngine != 0)
        ResetRubberBandStream();
}

void FastForwardAudioProcessor::ResetStream()
{
    if constexpr (FastForwardTimeStretchEngine == 0)
        SoundTouch.clear();
    else if constexpr (UseAsyncRubberBand)
    {
        StopWorker();
        CaptureStopState();
        ClearAsyncState();
        RubberBandProcessor->reset();
    }
    else
        ResetRubberBandStream();

    OutputPhase = 0;
}

void FastForwardAudioProcessor::ResumeStream()
{
    if constexpr (UseAsyncRubberBand)
        StartWorker();
}

int FastForwardAudioProcessor::GetConfiguredInitialLatencyFrames() const
{
    if constexpr (FastForwardTimeStretchEngine == 0)
        return SoundTouch.getSetting(SETTING_INITIAL_LATENCY);
    else
        return static_cast<int>(RubberBandInitialLatencyFrames);
}

FastForwardAudioDiagnostics FastForwardAudioProcessor::GetDiagnostics() const
{
    FastForwardAudioDiagnostics diagnostics;
    diagnostics.AsyncInputFramesEnqueued =
            AsyncInputFramesEnqueued.load(std::memory_order_relaxed);
    diagnostics.AsyncInputFramesDropped =
            AsyncInputFramesDropped.load(std::memory_order_relaxed);
    diagnostics.AsyncOutputFramesProduced =
            AsyncOutputFramesProduced.load(std::memory_order_relaxed);
    diagnostics.AsyncOutputFramesDequeued =
            AsyncOutputFramesDequeued.load(std::memory_order_relaxed);
    diagnostics.AsyncOutputFramesDropped =
            AsyncOutputFramesDropped.load(std::memory_order_relaxed);
    diagnostics.InputRingCurrent = InputRingFrames();
    diagnostics.InputRingHighWater = InputRingHighWater.load(std::memory_order_relaxed);
    diagnostics.OutputRingCurrent = OutputRingFrames();
    diagnostics.OutputRingHighWater = OutputRingHighWater.load(std::memory_order_relaxed);
    diagnostics.OutputDebtCurrent = OutputDebtCurrent.load(std::memory_order_relaxed);
    diagnostics.OutputDebtMaximum = OutputDebtMaximum.load(std::memory_order_relaxed);
    diagnostics.OutputDebtClampedFrames =
            OutputDebtClampedFrames.load(std::memory_order_relaxed);
    diagnostics.InputRingAtStop = InputRingAtStop.load(std::memory_order_relaxed);
    diagnostics.OutputRingAtStop = OutputRingAtStop.load(std::memory_order_relaxed);
    diagnostics.OutputDebtAtStop = OutputDebtAtStop.load(std::memory_order_relaxed);
    diagnostics.WorkerProcessingTimeUs =
            WorkerProcessingTimeUs.load(std::memory_order_relaxed);
    diagnostics.WorkerCpuTimeUs = WorkerCpuTimeUs.load(std::memory_order_relaxed);
    diagnostics.WorkerMaxProcessingTimeUs =
            WorkerMaxProcessingTimeUs.load(std::memory_order_relaxed);
    diagnostics.WorkerWakeups = WorkerWakeups.load(std::memory_order_relaxed);
    diagnostics.RubberBandAvailable = RubberBandAvailable.load(std::memory_order_relaxed);
    return diagnostics;
}

void FastForwardAudioProcessor::ResetDiagnostics()
{
    AsyncInputFramesEnqueued.store(0, std::memory_order_relaxed);
    AsyncInputFramesDropped.store(0, std::memory_order_relaxed);
    AsyncOutputFramesProduced.store(0, std::memory_order_relaxed);
    AsyncOutputFramesDequeued.store(0, std::memory_order_relaxed);
    AsyncOutputFramesDropped.store(0, std::memory_order_relaxed);
    InputRingHighWater.store(0, std::memory_order_relaxed);
    OutputRingHighWater.store(0, std::memory_order_relaxed);
    OutputDebtCurrent.store(0, std::memory_order_relaxed);
    OutputDebtMaximum.store(0, std::memory_order_relaxed);
    OutputDebtClampedFrames.store(0, std::memory_order_relaxed);
    InputRingAtStop.store(0, std::memory_order_relaxed);
    OutputRingAtStop.store(0, std::memory_order_relaxed);
    OutputDebtAtStop.store(0, std::memory_order_relaxed);
    WorkerProcessingTimeUs.store(0, std::memory_order_relaxed);
    WorkerCpuTimeUs.store(0, std::memory_order_relaxed);
    WorkerMaxProcessingTimeUs.store(0, std::memory_order_relaxed);
    WorkerWakeups.store(0, std::memory_order_relaxed);
    RubberBandAvailable.store(0, std::memory_order_relaxed);
}

void FastForwardAudioProcessor::LogDiagnostics(const char* reason) const
{
    if constexpr (!UseAsyncRubberBand)
        return;

    const FastForwardAudioDiagnostics metrics = GetDiagnostics();
    LOG_INFO(FF_AUDIO_DIAG_TAG,
             "async_snapshot reason=%s async_input_frames_enqueued=%" PRIu64 " "
             "async_input_frames_dropped=%" PRIu64 " "
             "async_output_frames_produced=%" PRIu64 " "
             "async_output_frames_dequeued=%" PRIu64 " "
             "async_output_frames_dropped=%" PRIu64 " "
             "input_ring_current=%" PRIu64 " input_ring_high_water=%" PRIu64 " "
             "output_ring_current=%" PRIu64 " output_ring_high_water=%" PRIu64 " "
             "output_debt_current=%" PRIu64 " output_debt_maximum=%" PRIu64 " "
             "output_debt_clamped_frames=%" PRIu64 " "
             "input_ring_at_stop=%" PRIu64 " output_ring_at_stop=%" PRIu64 " "
             "output_debt_at_stop=%" PRIu64 " worker_processing_time_us=%" PRIu64 " "
             "worker_cpu_time_us=%" PRIu64 " worker_max_processing_time_us=%" PRIu64 " "
             "worker_wakeups=%" PRIu64 " rubberband_available=%" PRId64,
             reason,
             metrics.AsyncInputFramesEnqueued, metrics.AsyncInputFramesDropped,
             metrics.AsyncOutputFramesProduced, metrics.AsyncOutputFramesDequeued,
             metrics.AsyncOutputFramesDropped,
             metrics.InputRingCurrent, metrics.InputRingHighWater,
             metrics.OutputRingCurrent, metrics.OutputRingHighWater,
             metrics.OutputDebtCurrent, metrics.OutputDebtMaximum,
             metrics.OutputDebtClampedFrames,
             metrics.InputRingAtStop, metrics.OutputRingAtStop,
             metrics.OutputDebtAtStop, metrics.WorkerProcessingTimeUs,
             metrics.WorkerCpuTimeUs, metrics.WorkerMaxProcessingTimeUs,
             metrics.WorkerWakeups, metrics.RubberBandAvailable);
}

void FastForwardAudioProcessor::ResetRubberBandStream()
{
    RubberBandProcessor->reset();
    RubberBandProcessor->setTimeRatio(1.0 / Tempo);
    RubberBandProcessor->setPitchScale(1.0);

    const size_t preferredStartPad = RubberBandProcessor->getPreferredStartPad();
    RubberBandInitialLatencyFrames = RubberBandProcessor->getStartDelay();
    RubberBandDelayFramesToDiscard = RubberBandInitialLatencyFrames;

    for (auto& channel : RubberBandInput)
        std::fill(channel.begin(), channel.end(), 0.0f);

    const float* silence[Channels] = {
            RubberBandInput[0].data(), RubberBandInput[1].data()};
    size_t remainingPad = preferredStartPad;
    while (remainingPad > 0)
    {
        const size_t frames = std::min(remainingPad, static_cast<size_t>(MaxProcessFrames));
        RubberBandProcessor->process(silence, frames, false);
        remainingPad -= frames;
    }
}

void FastForwardAudioProcessor::PrepareAsyncRubberBandStream()
{
    RubberBandProcessor->reset();
    RubberBandProcessor->setTimeRatio(1.0 / Tempo);
    RubberBandProcessor->setPitchScale(1.0);
    RubberBandPreferredStartPadFrames = RubberBandProcessor->getPreferredStartPad();
    RubberBandInitialLatencyFrames = RubberBandProcessor->getStartDelay();
    RubberBandDelayFramesToDiscard = RubberBandInitialLatencyFrames;
    for (auto& channel : RubberBandInput)
        std::fill(channel.begin(), channel.end(), 0.0f);
}

void FastForwardAudioProcessor::StartWorker()
{
    if constexpr (!UseAsyncRubberBand)
        return;
    if (WorkerThread.joinable())
        return;

    ClearAsyncState();
    PrepareAsyncRubberBandStream();
    while (sem_trywait(&WorkerSemaphore) == 0)
    {
    }
    WorkerStopRequested.store(false, std::memory_order_release);
    WorkerWakePending.store(false, std::memory_order_release);
    WorkerThread = std::thread(&FastForwardAudioProcessor::WorkerLoop, this);
}

void FastForwardAudioProcessor::StopWorker()
{
    if constexpr (!UseAsyncRubberBand)
        return;
    if (!WorkerThread.joinable())
        return;

    WorkerStopRequested.store(true, std::memory_order_release);
    sem_post(&WorkerSemaphore);
    WorkerThread.join();
    WorkerWakePending.store(false, std::memory_order_release);
}

size_t FastForwardAudioProcessor::InputRingFrames() const
{
    const uint64_t write = InputWrite.load(std::memory_order_acquire);
    const uint64_t read = InputRead.load(std::memory_order_acquire);
    return static_cast<size_t>(write - read);
}

size_t FastForwardAudioProcessor::OutputRingFrames() const
{
    const uint64_t write = OutputWrite.load(std::memory_order_acquire);
    const uint64_t read = OutputRead.load(std::memory_order_acquire);
    return static_cast<size_t>(write - read);
}

void FastForwardAudioProcessor::ClearAsyncState()
{
    InputRead.store(0, std::memory_order_relaxed);
    InputWrite.store(0, std::memory_order_relaxed);
    OutputRead.store(0, std::memory_order_relaxed);
    OutputWrite.store(0, std::memory_order_relaxed);
    OutputDebtFrames = 0;
    OutputPacingRemainder = 0;
    OutputDebtCurrent.store(0, std::memory_order_relaxed);
    RubberBandAvailable.store(0, std::memory_order_relaxed);
}

void FastForwardAudioProcessor::CaptureStopState()
{
    InputRingAtStop.store(InputRingFrames(), std::memory_order_relaxed);
    OutputRingAtStop.store(OutputRingFrames(), std::memory_order_relaxed);
    OutputDebtAtStop.store(OutputDebtFrames, std::memory_order_relaxed);
}

size_t FastForwardAudioProcessor::EnqueueInput(
        const melonDS::s16* samples, size_t frames)
{
    const uint64_t write = InputWrite.load(std::memory_order_relaxed);
    const uint64_t read = InputRead.load(std::memory_order_acquire);
    const size_t used = static_cast<size_t>(write - read);
    const size_t accepted = std::min(frames, RingCapacityFrames - used);
    for (size_t frame = 0; frame < accepted; frame++)
    {
        const size_t destination = (static_cast<size_t>(write) + frame) & RingMask;
        InputRing[destination * Channels] = samples[frame * Channels];
        InputRing[destination * Channels + 1] = samples[frame * Channels + 1];
    }
    InputWrite.store(write + accepted, std::memory_order_release);

    AsyncInputFramesEnqueued.fetch_add(accepted, std::memory_order_relaxed);
    AsyncInputFramesDropped.fetch_add(frames - accepted, std::memory_order_relaxed);
    UpdateMaximum(InputRingHighWater, used + accepted);

    if (accepted > 0 &&
        !WorkerWakePending.exchange(true, std::memory_order_acq_rel))
        sem_post(&WorkerSemaphore);
    return accepted;
}

size_t FastForwardAudioProcessor::DequeueInput()
{
    const uint64_t read = InputRead.load(std::memory_order_relaxed);
    const uint64_t write = InputWrite.load(std::memory_order_acquire);
    const size_t frames = std::min(
            static_cast<size_t>(write - read), static_cast<size_t>(MaxProcessFrames));
    for (size_t frame = 0; frame < frames; frame++)
    {
        const size_t source = (static_cast<size_t>(read) + frame) & RingMask;
        RubberBandInput[0][frame] =
                static_cast<float>(InputRing[source * Channels]) / 32768.0f;
        RubberBandInput[1][frame] =
                static_cast<float>(InputRing[source * Channels + 1]) / 32768.0f;
    }
    InputRead.store(read + frames, std::memory_order_release);
    return frames;
}

size_t FastForwardAudioProcessor::EnqueueOutput(size_t offset, size_t frames)
{
    const uint64_t write = OutputWrite.load(std::memory_order_relaxed);
    const uint64_t read = OutputRead.load(std::memory_order_acquire);
    const size_t used = static_cast<size_t>(write - read);
    const size_t accepted = std::min(frames, RingCapacityFrames - used);
    for (size_t frame = 0; frame < accepted; frame++)
    {
        const size_t destination = (static_cast<size_t>(write) + frame) & RingMask;
        OutputRing[destination * Channels] = FloatToS16(RubberBandOutput[0][offset + frame]);
        OutputRing[destination * Channels + 1] =
                FloatToS16(RubberBandOutput[1][offset + frame]);
    }
    OutputWrite.store(write + accepted, std::memory_order_release);

    AsyncOutputFramesDropped.fetch_add(frames - accepted, std::memory_order_relaxed);
    UpdateMaximum(OutputRingHighWater, used + accepted);
    return accepted;
}

size_t FastForwardAudioProcessor::DequeueOutput(
        melonDS::s16* samples, size_t frames)
{
    const uint64_t read = OutputRead.load(std::memory_order_relaxed);
    const uint64_t write = OutputWrite.load(std::memory_order_acquire);
    const size_t available = static_cast<size_t>(write - read);
    const size_t dequeued = std::min(frames, available);
    for (size_t frame = 0; frame < dequeued; frame++)
    {
        const size_t source = (static_cast<size_t>(read) + frame) & RingMask;
        samples[frame * Channels] = OutputRing[source * Channels];
        samples[frame * Channels + 1] = OutputRing[source * Channels + 1];
    }
    OutputRead.store(read + dequeued, std::memory_order_release);
    AsyncOutputFramesDequeued.fetch_add(dequeued, std::memory_order_relaxed);
    return dequeued;
}

void FastForwardAudioProcessor::DrainRubberBandOutput()
{
    float* output[Channels] = {
            RubberBandOutput[0].data(), RubberBandOutput[1].data()};
    while (true)
    {
        const int available = RubberBandProcessor->available();
        RubberBandAvailable.store(available, std::memory_order_relaxed);
        if (available <= 0)
            return;

        const size_t requested = std::min(
                static_cast<size_t>(available), static_cast<size_t>(MaxProcessFrames));
        const size_t retrieved = RubberBandProcessor->retrieve(output, requested);
        if (retrieved == 0)
            return;

        const size_t discarded = std::min(RubberBandDelayFramesToDiscard, retrieved);
        RubberBandDelayFramesToDiscard -= discarded;
        const size_t produced = retrieved - discarded;
        if (produced > 0)
        {
            AsyncOutputFramesProduced.fetch_add(produced, std::memory_order_relaxed);
            EnqueueOutput(discarded, produced);
        }
    }
}

void FastForwardAudioProcessor::ProcessWorkerBlock(size_t frames)
{
    const uint64_t wallStartUs = SteadyTimeUs();
    const uint64_t cpuStartUs = ThreadCpuTimeUs();
    const float* input[Channels] = {
            RubberBandInput[0].data(), RubberBandInput[1].data()};
    RubberBandProcessor->process(input, frames, false);
    DrainRubberBandOutput();
    const uint64_t cpuTimeUs = ThreadCpuTimeUs() - cpuStartUs;
    const uint64_t wallTimeUs = SteadyTimeUs() - wallStartUs;
    WorkerProcessingTimeUs.fetch_add(wallTimeUs, std::memory_order_relaxed);
    WorkerCpuTimeUs.fetch_add(cpuTimeUs, std::memory_order_relaxed);
    UpdateMaximum(WorkerMaxProcessingTimeUs, wallTimeUs);
}

void FastForwardAudioProcessor::WorkerLoop()
{
    pthread_setname_np(pthread_self(), "FFAudioDSP");

    size_t remainingPad = RubberBandPreferredStartPadFrames;
    while (remainingPad > 0 &&
           !WorkerStopRequested.load(std::memory_order_acquire))
    {
        const size_t frames = std::min(
                remainingPad, static_cast<size_t>(MaxProcessFrames));
        ProcessWorkerBlock(frames);
        remainingPad -= frames;
    }

    uint64_t nextSnapshotUs = SteadyTimeUs() + 5000000ULL;
    while (!WorkerStopRequested.load(std::memory_order_acquire))
    {
        int waitResult;
        do
        {
            waitResult = sem_wait(&WorkerSemaphore);
        }
        while (waitResult != 0 && errno == EINTR);
        if (waitResult != 0 || WorkerStopRequested.load(std::memory_order_acquire))
            break;

        WorkerWakePending.store(false, std::memory_order_release);
        WorkerWakeups.fetch_add(1, std::memory_order_relaxed);
        while (!WorkerStopRequested.load(std::memory_order_acquire))
        {
            const size_t frames = DequeueInput();
            if (frames == 0)
                break;
            ProcessWorkerBlock(frames);

            const uint64_t nowUs = SteadyTimeUs();
            if (nowUs >= nextSnapshotUs)
            {
                LogDiagnostics("periodic");
                nextSnapshotUs = nowUs + 5000000ULL;
            }
        }
    }
}

melonDS::AudioOutputProcessorResult FastForwardAudioProcessor::ProcessCallback(
        void* context, melonDS::s16* samples, int frames)
{
    return static_cast<FastForwardAudioProcessor*>(context)->Process(samples, frames);
}

melonDS::AudioOutputProcessorResult FastForwardAudioProcessor::Process(
        melonDS::s16* samples, int frames)
{
    melonDS::AudioOutputProcessorResult result;
    if (frames <= 0)
        return result;

    if constexpr (FastForwardTimeStretchEngine == 0)
    {
        const int pacedOutputFrames = (frames + OutputPhase) / Tempo;
        OutputPhase = (frames + OutputPhase) % Tempo;

        FloatInput.resize(frames * Channels);
        FloatOutput.resize(pacedOutputFrames * Channels);
        for (int i = 0; i < frames * Channels; i++)
            FloatInput[i] = static_cast<float>(samples[i]) / 32768.0f;

        SoundTouch.putSamples(FloatInput.data(), frames);
        const int outputFrames = static_cast<int>(SoundTouch.receiveSamples(
                FloatOutput.data(), pacedOutputFrames));
        for (int i = 0; i < outputFrames * Channels; i++)
        {
            const int value = static_cast<int>(std::lrint(FloatOutput[i] * 32768.0f));
            samples[i] = static_cast<melonDS::s16>(std::clamp(value, -32768, 32767));
        }

        result.OutputFrames = outputFrames;
        result.BufferedInputFrames = SoundTouch.numUnprocessedSamples();
        result.BufferedOutputFrames = SoundTouch.numSamples();
        return result;
    }

    if constexpr (UseAsyncRubberBand)
    {
        const size_t acceptedInputFrames = EnqueueInput(
                samples, static_cast<size_t>(frames));
        const uint64_t pacingInput = OutputPacingRemainder + acceptedInputFrames;
        const uint64_t newlyDueFrames = pacingInput / static_cast<uint64_t>(Tempo);
        OutputPacingRemainder = pacingInput % static_cast<uint64_t>(Tempo);

        const uint64_t availableDebtCapacity =
                MaximumOutputDebtFrames - OutputDebtFrames;
        if (newlyDueFrames > availableDebtCapacity)
        {
            OutputDebtClampedFrames.fetch_add(
                    newlyDueFrames - availableDebtCapacity,
                    std::memory_order_relaxed);
            OutputDebtFrames = MaximumOutputDebtFrames;
        }
        else
        {
            OutputDebtFrames += newlyDueFrames;
        }
        UpdateMaximum(OutputDebtMaximum, OutputDebtFrames);

        const size_t outputCapacity = static_cast<size_t>(std::min<uint64_t>(
                static_cast<uint64_t>(frames), OutputDebtFrames));
        const size_t outputFrames = DequeueOutput(samples, outputCapacity);
        OutputDebtFrames -= outputFrames;
        OutputDebtCurrent.store(OutputDebtFrames, std::memory_order_relaxed);

        result.OutputFrames = static_cast<int>(outputFrames);
        result.BufferedInputFrames = InputRingFrames();
        result.BufferedOutputFrames = OutputRingFrames();
        return result;
    }

    if (frames > MaxProcessFrames)
        return result;

    const int pacedOutputFrames = (frames + OutputPhase) / Tempo;
    OutputPhase = (frames + OutputPhase) % Tempo;

    for (int i = 0; i < frames; i++)
    {
        RubberBandInput[0][i] = static_cast<float>(samples[i * Channels]) / 32768.0f;
        RubberBandInput[1][i] = static_cast<float>(samples[i * Channels + 1]) / 32768.0f;
    }

    const float* input[Channels] = {
            RubberBandInput[0].data(), RubberBandInput[1].data()};
    RubberBandProcessor->process(input, frames, false);

    float* output[Channels] = {
            RubberBandOutput[0].data(), RubberBandOutput[1].data()};
    while (RubberBandDelayFramesToDiscard > 0)
    {
        const int availableFrames = RubberBandProcessor->available();
        if (availableFrames <= 0)
            break;

        const size_t discardFrames = std::min(
                RubberBandDelayFramesToDiscard,
                static_cast<size_t>(std::min(availableFrames, MaxProcessFrames)));
        const size_t discardedFrames = RubberBandProcessor->retrieve(output, discardFrames);
        if (discardedFrames == 0)
            break;
        RubberBandDelayFramesToDiscard -= discardedFrames;
    }

    const int availableFrames = std::max(0, RubberBandProcessor->available());
    const int framesToRetrieve = std::min(pacedOutputFrames, availableFrames);
    const int outputFrames = static_cast<int>(
            RubberBandProcessor->retrieve(output, framesToRetrieve));
    for (int i = 0; i < outputFrames; i++)
    {
        for (int channel = 0; channel < Channels; channel++)
        {
            const int value = static_cast<int>(
                    std::lrint(RubberBandOutput[channel][i] * 32768.0f));
            samples[i * Channels + channel] = static_cast<melonDS::s16>(
                    std::clamp(value, -32768, 32767));
        }
    }

    result.OutputFrames = outputFrames;
    // getSamplesRequired() is an input-demand hint, not a buffered-input count.
    result.BufferedInputFrames = 0;
    result.BufferedOutputFrames = static_cast<melonDS::u64>(
            std::max(0, RubberBandProcessor->available()));
    return result;
}

}
