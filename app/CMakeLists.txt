cmake_minimum_required(VERSION 3.4.1)

project(melonDS-android-frontend)

add_compile_definitions(MELONDS_GL_HEADER=<GLES3/gl32.h>)
add_compile_definitions(EGL_EGLEXT_PROTOTYPES)

set(BUILD_QT_SDL OFF)
set(ENABLE_GDBSTUB OFF)

set(ANDROID-CPP src/main/cpp)
set(CORE-LIB ../melonDS-android-lib)
set(OBOE ${ANDROID-CPP}/oboe)
set(FAAD2 ${ANDROID-CPP}/faad2)
set(RCHEEVOS ${ANDROID-CPP}/rcheevos)
set(SOUNDTOUCH ${ANDROID-CPP}/third_party/soundtouch)

if(ANDROID)
    string(REPLACE "-O2" "-O3" CMAKE_C_FLAGS_RELWITHDEBINFO "${CMAKE_C_FLAGS_RELWITHDEBINFO}")
    string(REPLACE "-O2" "-O3" CMAKE_CXX_FLAGS_RELWITHDEBINFO "${CMAKE_CXX_FLAGS_RELWITHDEBINFO}")
endif()

add_subdirectory(${CORE-LIB} ./melonDS-android-lib)
add_subdirectory(${OBOE} ./oboe)
add_subdirectory(${FAAD2} ./faad2)
add_subdirectory(${RCHEEVOS} ./rcheevos)
add_subdirectory(${ANDROID-CPP}/enet ./enet)
target_include_directories(enet INTERFACE ${ANDROID-CPP}/enet/include)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/${CORE-LIB}/cmake")
add_subdirectory(${CORE-LIB}/src/net ./net-utils)

add_library(soundtouch STATIC
        ${SOUNDTOUCH}/source/SoundTouch/AAFilter.cpp
        ${SOUNDTOUCH}/source/SoundTouch/cpu_detect_x86.cpp
        ${SOUNDTOUCH}/source/SoundTouch/FIFOSampleBuffer.cpp
        ${SOUNDTOUCH}/source/SoundTouch/FIRFilter.cpp
        ${SOUNDTOUCH}/source/SoundTouch/InterpolateCubic.cpp
        ${SOUNDTOUCH}/source/SoundTouch/InterpolateLinear.cpp
        ${SOUNDTOUCH}/source/SoundTouch/InterpolateShannon.cpp
        ${SOUNDTOUCH}/source/SoundTouch/mmx_optimized.cpp
        ${SOUNDTOUCH}/source/SoundTouch/RateTransposer.cpp
        ${SOUNDTOUCH}/source/SoundTouch/SoundTouch.cpp
        ${SOUNDTOUCH}/source/SoundTouch/sse_optimized.cpp
        ${SOUNDTOUCH}/source/SoundTouch/TDStretch.cpp)
target_include_directories(soundtouch PUBLIC ${SOUNDTOUCH}/include)
target_compile_definitions(soundtouch PUBLIC SOUNDTOUCH_FLOAT_SAMPLES)
target_compile_options(soundtouch PRIVATE -ffast-math)

if(ANDROID AND ANDROID_ABI MATCHES "^(armeabi-v7a|arm64-v8a)$")
    target_compile_definitions(soundtouch PRIVATE SOUNDTOUCH_USE_NEON)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_compile_options(soundtouch PRIVATE -mfpu=neon)
    endif()
endif()

add_library(
        melonDS-android-frontend

        SHARED

        ${ANDROID-CPP}/AndroidMelonEventMessenger.cpp
        ${ANDROID-CPP}/CartLoader.cpp
        ${ANDROID-CPP}/DSiSupport.cpp
        ${ANDROID-CPP}/EmulatorArgsBuilder.cpp
        ${ANDROID-CPP}/EmulatorMessageQueueJNI.cpp
        ${ANDROID-CPP}/FastForwardAudioProcessor.cpp
        ${ANDROID-CPP}/JniEnvHandler.cpp
        ${ANDROID-CPP}/MelonDS.cpp
        ${ANDROID-CPP}/MelonDSAndroidCameraHandler.cpp
        ${ANDROID-CPP}/MelonDSAndroidConfiguration.cpp
        ${ANDROID-CPP}/MelonDSAndroidInterface.cpp
        ${ANDROID-CPP}/MelonDSAndroidJNI.cpp
        ${ANDROID-CPP}/MelonDSAudio.cpp
        ${ANDROID-CPP}/MelonDSNandJNI.cpp
        ${ANDROID-CPP}/MelonInstance.cpp
        ${ANDROID-CPP}/MicInputOboeCallback.cpp
        ${ANDROID-CPP}/NativeGlContext.cpp
        ${ANDROID-CPP}/OboeCallback.cpp
        ${ANDROID-CPP}/OpenGLContext.cpp
        ${ANDROID-CPP}/PlatformAndroid.cpp
        ${ANDROID-CPP}/PlatformAndroid_AAC.cpp
        ${ANDROID-CPP}/RetroAchievementsMapper.cpp
        ${ANDROID-CPP}/RewindManager.cpp
        ${ANDROID-CPP}/RomIconBuilder.cpp
        ${ANDROID-CPP}/SDCardArgsBuilder.cpp
        ${ANDROID-CPP}/SaveManager.cpp
        ${ANDROID-CPP}/UriFileHandler.cpp
        ${ANDROID-CPP}/performancehint/JniPerformanceHintManager.cpp
        ${ANDROID-CPP}/performancehint/NdkPerformanceHintManager.cpp
        ${ANDROID-CPP}/performancehint/PerformanceHintManagerFactory.cpp
        ${ANDROID-CPP}/performancehint/ThreadSafePerformanceHintSession.cpp
        ${ANDROID-CPP}/renderer/FrameQueue.cpp
        ${ANDROID-CPP}/renderer/ScreenshotRenderer.cpp
        ${ANDROID-CPP}/retroachievements/RetroAchievementsManager.cpp
)

target_include_directories(melonDS-android-frontend PUBLIC
        ${ANDROID-CPP}/
        ${CORE-LIB}/src/frontend
        ${OBOE}/include
        ${FAAD2}/include
        ${RCHEEVOS}/include
)
target_link_libraries(melonDS-android-frontend android core net-utils oboe faad enet rcheevos soundtouch EGL GLESv3)

if(ANDROID)
    target_compile_options(core PRIVATE "$<$<AND:$<CONFIG:RelWithDebInfo>,$<COMPILE_LANGUAGE:C,CXX>>:-flto=thin>")
    target_compile_options(soundtouch PRIVATE "$<$<AND:$<CONFIG:RelWithDebInfo>,$<COMPILE_LANGUAGE:CXX>>:-flto=thin>")
    target_compile_options(melonDS-android-frontend PRIVATE "$<$<AND:$<CONFIG:RelWithDebInfo>,$<COMPILE_LANGUAGE:C,CXX>>:-flto=thin>")
    target_link_options(melonDS-android-frontend PRIVATE "$<$<CONFIG:RelWithDebInfo>:-flto=thin>")
endif()
