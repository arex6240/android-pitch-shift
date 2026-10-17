cmake_minimum_required(VERSION 3.22.1)
project(pitchshifter_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Oboe (via Prefab) ---
find_package(oboe REQUIRED CONFIG)

# --- SoundTouch (via FetchContent) ---
include(FetchContent)

# Disable building SoundTouch CLI tools
set(SOUNDTOUCH_DLL OFF CACHE BOOL "" FORCE)
set(SOUNDSTRETCH OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    soundtouch
    GIT_REPOSITORY https://codeberg.org/soundtouch/soundtouch.git
    GIT_TAG        2.3.3
    GIT_SHALLOW    TRUE
)

# We only need the SoundTouch library, not the full project
FetchContent_GetProperties(soundtouch)
if(NOT soundtouch_POPULATED)
    FetchContent_Populate(soundtouch)

    # Build SoundTouch as a static library from source files directly
    set(SOUNDTOUCH_SRC_DIR "${soundtouch_SOURCE_DIR}/source/SoundTouch")

    add_library(SoundTouch STATIC
        ${SOUNDTOUCH_SRC_DIR}/AAFilter.cpp
        ${SOUNDTOUCH_SRC_DIR}/BPMDetect.cpp
        ${SOUNDTOUCH_SRC_DIR}/FIFOSampleBuffer.cpp
        ${SOUNDTOUCH_SRC_DIR}/FIRFilter.cpp
        ${SOUNDTOUCH_SRC_DIR}/InterpolateCubic.cpp
        ${SOUNDTOUCH_SRC_DIR}/InterpolateLinear.cpp
        ${SOUNDTOUCH_SRC_DIR}/InterpolateShannon.cpp
        ${SOUNDTOUCH_SRC_DIR}/PeakFinder.cpp
        ${SOUNDTOUCH_SRC_DIR}/RateTransposer.cpp
        ${SOUNDTOUCH_SRC_DIR}/SoundTouch.cpp
        ${SOUNDTOUCH_SRC_DIR}/TDStretch.cpp
        ${SOUNDTOUCH_SRC_DIR}/cpu_detect_x86.cpp
    )

    target_include_directories(SoundTouch PUBLIC
        "${soundtouch_SOURCE_DIR}/include"
    )

    target_compile_definitions(SoundTouch PRIVATE
        SOUNDTOUCH_FLOAT_SAMPLES=1
    )
endif()

# --- Our JNI library ---
add_library(pitchshifter_engine SHARED
    NativeAudioEngine.cpp
)

target_link_libraries(pitchshifter_engine
    oboe::oboe
    SoundTouch
    log
)
