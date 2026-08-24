#ifndef FASTFORWARDAUDIOPROCESSOR_H
#define FASTFORWARDAUDIOPROCESSOR_H

#include <vector>
#include "SPU.h"
#include "SoundTouch.h"

namespace MelonDSAndroid
{

class FastForwardAudioProcessor
{
public:
    FastForwardAudioProcessor();
    void ResetStream();

    int GetConfiguredInitialLatencyFrames() const;

    static melonDS::AudioOutputProcessorResult ProcessCallback(
            void* context, melonDS::s16* samples, int frames);

private:
    melonDS::AudioOutputProcessorResult Process(melonDS::s16* samples, int frames);

    static constexpr int SampleRate = 48000;
    static constexpr int Channels = 2;

    soundtouch::SoundTouch SoundTouch;
    int OutputPhase = 0;
    std::vector<float> FloatInput;
    std::vector<float> FloatOutput;
};

}

#endif // FASTFORWARDAUDIOPROCESSOR_H
