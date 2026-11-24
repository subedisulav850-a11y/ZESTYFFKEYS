# FF AES Extractor v1.1 — Fixed Root

## What was fixed
- No longer requires pre-installed `frida-server` binary
- Explicit "FORCE ROOT" button so Magisk/KernelSU popup appears
- Better su detection for temporary root
- Clear status messages
- Fallback logcat + hex scanner if frida binary is missing
- Still supports full Frida hooks when a matching binary is present

## How to use on temporary root
1. Install APK
2. Open Magisk / KernelSU → Superuser → allow **FF AES Extractor**
3. Open the app → tap **FORCE ROOT / RE-REQUEST SU**
4. Status should show "Root: GRANTED ✓"
5. Enter package (e.g. `com.garena.game.ffi`)
6. Tap **START EXTRACTOR**
7. Open the game and go to Login screen
8. Keys appear in the app + `/sdcard/Download/ff_keys.txt`

## Optional: better capture rate
If you want full Frida Java + native hooks:
- Download matching frida-server for your arch from https://github.com/frida/frida/releases
- `adb push frida-server /data/local/tmp/`
- `adb shell su -c "chmod 755 /data/local/tmp/frida-server"`
- Restart the extractor

## Build
Open in Android Studio or use the GitHub Actions workflow.
