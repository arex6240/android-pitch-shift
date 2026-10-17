# Pitch Shifter for Android

An Android app that captures device audio and applies real-time pitch shifting
before playback. The audio engine uses [Oboe](https://github.com/google/oboe)
for low-latency audio I/O and [SoundTouch](https://codeberg.org/soundtouch/soundtouch)
for pitch processing.

## Features

- Real-time pitch shifting in semitones
- Device audio capture through Android MediaProjection
- Low-latency native audio processing with Oboe
- SoundTouch anti-aliasing and time-domain processing
- Native C++ engine exposed to Kotlin through JNI

## Requirements

- Android Studio with Android SDK Platform 34
- Android NDK and CMake 3.22.1
- A physical Android device running Android 10 (API 29) or newer
- A device that supports audio playback capture and MediaProjection

The native build fetches SoundTouch 2.3.3 from Codeberg during the first
configuration, so an internet connection is required for that step.

## Build and run

1. Open this project in Android Studio.
2. Allow Gradle to sync and install the required SDK, NDK, and CMake components.
3. Connect an Android device or start a compatible emulator.
4. Build and run the `app` configuration.
5. Grant microphone, notification, and screen/audio capture permissions when
   prompted.

From a terminal, a debug APK can be built with:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Project structure

- `app/src/main/java` - Kotlin activity and foreground audio capture service
- `app/src/main/cpp` - JNI audio engine and native build configuration
- `app/src/main/res` - Android resources

## Notes

Audio capture and playback behavior depends on device manufacturer
restrictions and Android version. The app requests MediaProjection access each
time capture starts and does not include generated build output in version
control.
