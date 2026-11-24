# Bluetooth Radio
A foreground Android push-to-talk prototype for two paired phones (Android 8+). It uses a Bluetooth Classic RFCOMM socket carrying mono PCM audio and speaking-turn messages. The host gives one phone the floor at a time.

## Build the APK
With Gradle 8.9, JDK 17 and Android SDK 35, run `gradle assembleDebug` from this directory. Install app/build/outputs/apk/debug/app-debug.apk on both phones. To download the prebuilt test APK, open a successful Build Android test APK run in GitHub Actions while signed in and tap BluetoothRadio.apk. The file can be installed directly. The build machine needs internet for the first dependency download.

## Test on two phones
1. Turn off hotspot, Wi-Fi and mobile data on both phones. Turn Bluetooth on and pair the phones in Android settings.
2. Open the app on both phones. Tap Host channel on A. On B, tap Join paired phone and select A. Allow Nearby devices if requested.
3. Keep both apps open. Allow microphone access on first press, then hold the button again to talk. Release it for the other person to reply. Playback uses the media volume.
4. Test both directions from separate rooms, simultaneous presses, leaving and reconnecting, and reversing the host role. If the connection drops, host and join again.
