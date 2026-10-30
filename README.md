# VolumeFlow

A new Android volume-control app foundation built with Kotlin, Jetpack Compose, Material 3 and Preferences DataStore.

## Included in this starter
- Material 3 dashboard with system dynamic colors and dark mode.
- Discovery of installed launcher apps.
- Local per-app profile persistence.
- GitHub Actions debug-build workflow.

## Important status
This is the UI and persistence foundation only. The sliders currently save preferences; they do **not** yet change other apps' audio. The Shizuku audio-session backend is the next integration phase and must be tested before the controls are represented as functional volume attenuation.

## Build
Requires JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
