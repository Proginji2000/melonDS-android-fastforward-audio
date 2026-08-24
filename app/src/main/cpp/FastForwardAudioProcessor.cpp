#include "FastForwardAudioProcessor.h"
#include <algorithm>
#include <cmath>

namespace MelonDSAndroid
{

FastForwardAudioProcessor::FastForwardAudioProcessor()
{
    SoundTouch.setSampleRate(SampleRate);
    SoundTouch.setChannels(Channels);
    SoundTouch.setRate(1.0);
    SoundTouch.setPitch(1.0);
    ConfigureTempo(Tempo);
}

void FastForwardAudioProcessor::ConfigureTempo(int tempo)
{
    SoundTouch.clear();
    SoundTouch.setTempo(tempo);
    Tempo = tempo;
    OutputPhase = 0;
}

void FastForwardAudioProcessor::ResetStream()
{
    SoundTouch.clear();
    OutputPhase = 0;
}

int FastForwardAudioProcessor::GetConfiguredInitialLatencyFrames() const
{
    return SoundTouch.getSetting(SETTING_INITIAL_LATENCY);
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

}
