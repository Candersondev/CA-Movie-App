# Movy TV

Independent Android TV prototype for https://movy.sx.

## APK
Go to Actions > Android TV APK > latest successful run > Artifacts > movy-tv-debug-apk.

## Current status
This first version loads the website in a TV launcher with fullscreen support. It is not yet a native catalog. Navigation and playback require testing on an Android TV device. Some third-party embedded players or DRM formats may not work.

## Build
JDK 17, Android SDK 35, Gradle 8.9. Run `gradle :app:assembleDebug`.
