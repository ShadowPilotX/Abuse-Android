<img width="1810" height="592" alt="1000661176" src="https://github.com/user-attachments/assets/b3922b02-780d-41a8-8f2b-6fd40bd9502a" />

# Abuse (1996) — Android Port

A native Android port of the classic 1996 dark sci-fi platformer **Abuse** by Crack dot Com, built on top of [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025) (SDL2 fork) via the SDL Android project template.

Compiled entirely on-device using Termux (no PC/laptop required) — Android NDK, Gradle, and the full toolchain running natively on aarch64.

## Data Files

This Android port includes the required game data files needed to play Abuse. The game data is bundled with the application, allowing the game to run without requiring users to manually provide or copy additional files.
The bundled assets originate from the Abuse_2025 project and its associated game data.

## Controls

**Keyboard and Mouse:**

![hero](https://github.com/user-attachments/assets/a44ec3c3-613a-41e1-81e1-11651f7c6280)

To better reflect modern game controls, the original arrow key controls have been replaced with WASD for movement. Mouse controls aim, left button shoots, right button activates special powers, and mouse scroll switches between weapons.

**Touch controls:**

![touch controls](https://github.com/user-attachments/assets/e1bb43b1-f4f4-4b1d-aaf8-d6f6a340ee07)

Fire/aim: Tap to shoot, or hold and drag to aim. The reticle aiming speed directly tracks your finger movement speed.

**Gamepad:**

- D-pad / Left Stick — Movement
- Right Stick — Aiming
- Face Buttons — Gameplay actions
- Shoulder Buttons / Triggers — Additional controls
- Start — Confirm / Enter
- Back — Escape / Back

## Cheats

To use cheats, press <kbd>c</kbd> on the keyboard to open the console. Tap the console window on screen for input and type the desired cheat command. Press <kbd>Enter↩️</kbd> when done, or type "quit"/"exit" to close the console.

Available cheats:

- `god` - Makes you invulnerable to all damage
- `giveall` - Gives all weapons and maximum ammunition
- `flypower` - Grants Anti-Gravity Boots effect
- `sneakypower` - Grants Cloak effect
- `fastpower` - Grants Flash Speed effect
- `healthpower` - Grants Ultra-Health effect
- `nopower` - Removes all active special abilities

## Building from source

Built and tested entirely within Termux on Android (aarch64), using:
- Android NDK patched for aarch64 hosts ([lzhiyong/termux-ndk](https://github.com/lzhiyong/termux-ndk))
- Gradle + Android SDK cmdline-tools installed via Termux
- Game data bundled as APK assets, extracted to internal storage on first run

## Credits

- Original game: Crack dot Com (1995-1996)
- Modern SDL2 source port: [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025)
- Android port: this repo

## Source code releases
[Original source code](https://archive.org/details/abuse_sourcecode)  
[Anthony Kruize Abuse SDL port (2001)](http://web.archive.org/web/20070205093016/http://www.labyrinth.net.au/~trandor/abuse)  
[Jeremy Scott Windows port (2001)](http://web.archive.org/web/20051023123223/http://www.webpages.uidaho.edu/~scot4875)  
[Sam Hocevar Abuse SDl port (2011)](http://abuse.zoy.org)  
[Xenoveritas SDL2 port (2014)](http://github.com/Xenoveritas/abuse)  
[Antonio Radojkovic Abuse 1996](https://github.com/antrad/Abuse_1996)
[apancik/Abuse_2025](https://github.com/apancik/Abuse_2025)

## License

This project is licensed under GPL-2.0 (see [LICENSE](LICENSE)), consistent with the upstream [apancik/Abuse_2025](https://github.com/apancik/Abuse_2025) source.

The underlying Abuse source code has mixed licensing: original Crack dot Com game code is public domain, while `sdlport/*` files (which this port modifies) are GPL-2.0+, and some `lol/*`/`tools/*` files are WTFPL. GPL-2.0 is applied here as the governing license for the combined work.
