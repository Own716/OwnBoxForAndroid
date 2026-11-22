#!/bin/bash
set -euo pipefail

HEVTUN_COMMIT="c5584feca8f6df70ee53eb2879e034b849a3d15a"
ABIS="armeabi-v7a arm64-v8a x86 x86_64"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  if [ -n "${ANDROID_NDK_LATEST_HOME:-}" ] && [ -d "$ANDROID_NDK_LATEST_HOME" ]; then
    ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
  elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
  elif [ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ]; then
    ANDROID_NDK_HOME="$NDK_HOME"
  elif [ -n "${NDK:-}" ] && [ -d "$NDK" ]; then
    ANDROID_NDK_HOME="$NDK"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk-bundle" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk-bundle"
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -x "$ANDROID_NDK_HOME/ndk-build" ]; then
  echo "Android NDK not found (ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-unset})" >&2
  exit 1
fi

echo "Using Android NDK: $ANDROID_NDK_HOME"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMPDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

git clone --no-checkout https://github.com/heiher/hev-socks5-tunnel.git "$TMPDIR/hev-socks5-tunnel"
git -C "$TMPDIR/hev-socks5-tunnel" checkout --force "$HEVTUN_COMMIT"
git -C "$TMPDIR/hev-socks5-tunnel" submodule update --init --recursive

mkdir -p "$TMPDIR/jni"
ln -s "$TMPDIR/hev-socks5-tunnel" "$TMPDIR/jni/hev-socks5-tunnel"

cat > "$TMPDIR/jni/exec.mk" <<'EXECMK'
TOP_PATH := $(call my-dir)/hev-socks5-tunnel

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
    ENABLE_MEMALLOC_SLICE := 0
    include $(TOP_PATH)/third-part/hev-task-system/Android.mk
endif

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := hevsockstun
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -fno-stack-clash-protection $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
EXECMK

JOBS="$(nproc 2>/dev/null || echo 4)"
"$ANDROID_NDK_HOME/ndk-build" \
  "-j$JOBS" \
  "NDK_PROJECT_PATH=$TMPDIR" \
  "APP_BUILD_SCRIPT=$TMPDIR/jni/exec.mk" \
  "APP_ABI=$ABIS" \
  APP_PLATFORM=android-24 \
  "NDK_LIBS_OUT=$TMPDIR/libs-exec" \
  "NDK_OUT=$TMPDIR/obj-exec" \
  "APP_CFLAGS=-O3" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" \
  "REV_ID=${HEVTUN_COMMIT:0:7}"

for abi in $ABIS; do
  src_bin="$TMPDIR/libs-exec/$abi/hevsockstun"
  dest_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
  dest_bin="$dest_dir/libhevsockstun.so"
  if [ ! -s "$src_bin" ]; then
    echo "Missing built executable for $abi: $src_bin" >&2
    exit 1
  fi
  mkdir -p "$dest_dir"
  cp "$src_bin" "$dest_bin"
  chmod 0755 "$dest_bin"
  ls -lh "$dest_bin"
done
