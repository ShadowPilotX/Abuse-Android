
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
