## Abuse (1996) — Android Port
<img width="2460" height="1080" alt="1000659808" src="https://github.com/user-attachments/assets/65d90094-2877-4e61-8d95-a320b4cf144c" />

A native Android port of the classic 1996 dark sci-fi platformer **Abuse** by Crack dot Com, built on top of [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025) (SDL2 fork) via the SDL Android project template.

Compiled entirely on-device using Termux (no PC/laptop required) — Android NDK, Gradle, and the full toolchain running natively on aarch64.

## Status

- ✅ Compiles and runs on Android (arm64-v8a, armeabi-v7a, x86, x86_64)
- ✅ Fullscreen rendering
- ✅ Bundled game data (extracted from APK assets on first launch)
- ✅ Gamepad / Bluetooth controller support (via SDL_GameController — recommended)
- ⚠️ On-screen touch controls: not yet included. **Best played with a keyboard/mouse, gamepad or Bluetooth controller** for now.

## Controls

**Keyboard and Mouse:**
<img width="960" height="600" alt="1000660872" src="https://github.com/user-attachments/assets/a44ec3c3-613a-41e1-81e1-11651f7c6280" />

To better reflect modern game controls, the original arrow key controls have been replaced with WASD for movement. Mouse controls aim, left button shoots, right button activates special powers, and mouse scroll switches between weapons.

**Gamepad:** D-pad/stick to move, buttons to jump/shoot (standard SDL controller mapping).

**Touch controls:** Native touch controls are planned for a future update.

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

## License

This project is licensed under GPL-2.0 (see [LICENSE](LICENSE)), consistent with the upstream [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025) source.

The underlying Abuse source code has mixed licensing: original Crack dot Com game code is public domain, while `sdlport/*` files (which this port modifies) are GPL-2.0+, and some `lol/*`/`tools/*` files are WTFPL. GPL-2.0 is applied here as the governing license for the combined work.
