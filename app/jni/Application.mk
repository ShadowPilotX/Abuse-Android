
# Uncomment this if you're using STL in your project
# You can find more information here:
# https://developer.android.com/ndk/guides/cpp-support
# APP_STL := c++_shared

APP_ABI := armeabi-v7a arm64-v8a

# Min runtime API level
APP_PLATFORM=android-24
APP_ALLOW_MISSING_DEPS := true
APP_STL := c++_shared

# Enable OGG Vorbis music support (STB decoder, no external lib needed)
SUPPORT_OGG_STB := true

# Force -O0 (unoptimized) even for release builds. The upstream Abuse lisp
# interpreter (lisp.cpp / lisp.h) has a latent null-pointer bug that only
# manifests under -O2 optimization (release default) — safe under -O0
# (debug default). Crashes with SIGSEGV in item_type()/LObject::Eval() during
# load_data() at startup otherwise. Performance impact is negligible for a
# 1996 2D platformer on modern hardware.
APP_OPTIM := debug
