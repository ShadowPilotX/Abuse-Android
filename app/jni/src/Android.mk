LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := main

SDL_PATH := ../SDL
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(SDL_PATH)/include $(LOCAL_PATH) $(LOCAL_PATH)/imlib $(LOCAL_PATH)/lisp $(LOCAL_PATH)/lol $(LOCAL_PATH)/net $(LOCAL_PATH)/sdlport $(LOCAL_PATH)/tool $(LOCAL_PATH)/ui

LOCAL_SRC_FILES := \
ability.cpp \
ant.cpp \
automap.cpp \
cache.cpp \
chars.cpp \
chat.cpp \
clisp.cpp \
collide.cpp \
compiled.cpp \
configuration.cpp \
console.cpp \
cop.cpp \
crc.cpp \
demo.cpp \
dev.cpp \
devsel.cpp \
director.cpp \
endgame.cpp \
extend.cpp \
file_utils.cpp \
fnt6x13.cpp \
game.cpp \
gamma.cpp \
gui.cpp \
help.cpp \
imlib/dprint.cpp \
imlib/event.cpp \
imlib/filesel.cpp \
imlib/filter.cpp \
imlib/fonts.cpp \
imlib/guistat.cpp \
imlib/image.cpp \
imlib/include.cpp \
imlib/input.cpp \
imlib/jrand.cpp \
imlib/jwindow.cpp \
imlib/keys.cpp \
imlib/linked.cpp \
imlib/palette.cpp \
imlib/pcxread.cpp \
imlib/pmenu.cpp \
imlib/scroller.cpp \
imlib/specs.cpp \
imlib/sprite.cpp \
imlib/status.cpp \
imlib/supmorph.cpp \
imlib/tools.cpp \
imlib/transimage.cpp \
imlib/video.cpp \
innet.cpp \
intsect.cpp \
items.cpp \
level.cpp \
light.cpp \
lisp/lisp.cpp \
lisp/lisp_gc.cpp \
lisp/lisp_opt.cpp \
lisp/trig.cpp \
loader2.cpp \
loadgame.cpp \
lol/matrix.cpp \
lol/timer.cpp \
menu.cpp \
morpher.cpp \
net/fileman.cpp \
net/gclient.cpp \
net/gserver.cpp \
net/sock.cpp \
net/tcpip.cpp \
netcfg.cpp \
nfclient.cpp \
objects.cpp \
particle.cpp \
points.cpp \
profile.cpp \
property.cpp \
sdlport/errorui.cpp \
sdlport/event.cpp \
sdlport/hmi.cpp \
sdlport/jdir.cpp \
sdlport/joystick.cpp \
sdlport/setup.cpp \
sdlport/sound.cpp \
sdlport/timing.cpp \
sdlport/video.cpp \
sensor.cpp \
seq.cpp \
smallfnt.cpp \
specache.cpp \
statbar.cpp \
transp.cpp \
ui/volumewindow.cpp \
view.cpp \


LOCAL_SHARED_LIBRARIES := SDL2 SDL2_mixer
LOCAL_CPP_FEATURES += exceptions
LOCAL_CFLAGS += -include config.h
LOCAL_CFLAGS += -DASSETDIR='"."'
LOCAL_LDLIBS := -lGLESv1_CM -lGLESv2 -llog

LOCAL_CFLAGS += -Wno-error=format-security
include $(BUILD_SHARED_LIBRARY)
