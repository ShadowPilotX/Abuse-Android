## Abuse (1996) — Android Port
<img width="2460" height="1080" alt="1000659808" src="https://github.com/user-attachments/assets/65d90094-2877-4e61-8d95-a320b4cf144c" />

A native Android port of the classic 1996 dark sci-fi platformer **Abuse** by Crack dot Com, built on top of [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025) (SDL2 fork) via the SDL Android project template.

Compiled entirely on-device using Termux (no PC/laptop required) — Android NDK, Gradle, and the full toolchain running natively on aarch64.

## Status

- ✅ Compiles and runs on Android (arm64-v8a, armeabi-v7a, x86, x86_64)
- ✅ Fullscreen rendering
- ✅ Bundled game data (extracted from APK assets on first launch)
- ✅ Gamepad / Bluetooth controller support (via SDL_GameController — recommended)
- ⚠️ On-screen touch controls: not yet included. **Best played with a gamepad or Bluetooth controller** for now.

## Controls

**Gamepad (recommended):** D-pad/stick to move, buttons to jump/shoot (standard SDL controller mapping).

(**Touch controls are still WIP** ⌛)

## Building from source

Built and tested entirely within Termux on Android (aarch64), using:
- Android NDK patched for aarch64 hosts ([lzhiyong/termux-ndk](https://github.com/lzhiyong/termux-ndk))
- Gradle + Android SDK cmdline-tools installed via Termux
- Game data bundled as APK assets, extracted to internal storage on first run

## Credits

- Original game: Crack dot Com (1996)
- Modern SDL2 source port: [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025)
- Android port: this repo

## Contributing

Feel free to contribute! Touch controls in particular are still rough and could use love — PRs welcome for better touch/on-screen input, bug fixes, or general improvements.
