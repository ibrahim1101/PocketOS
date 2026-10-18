#!/usr/bin/env bash
# Cross-build a minimal Android ARM64 QEMU executable and static runtime dependencies.
set -euo pipefail

ndk_root="${1:?usage: build-android-qemu.sh <ndk-root> <qemu-source> <jni-libs-root> <build-root>}"
qemu_source="${2:?usage: build-android-qemu.sh <ndk-root> <qemu-source> <jni-libs-root> <build-root>}"
jni_libs_root="${3:?usage: build-android-qemu.sh <ndk-root> <qemu-source> <jni-libs-root> <build-root>}"
build_root="${4:?usage: build-android-qemu.sh <ndk-root> <qemu-source> <jni-libs-root> <build-root>}"

if [[ ! -x "$qemu_source/configure" ]]; then
  echo "QEMU source is missing its configure script: $qemu_source" >&2
  exit 2
fi

host_tag="linux-x86_64"
toolchain="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin"
cc="$toolchain/aarch64-linux-android26-clang"
cxx="$toolchain/aarch64-linux-android26-clang++"
llvm_readelf="$toolchain/llvm-readelf"
llvm_ar="$toolchain/llvm-ar"
llvm_ranlib="$toolchain/llvm-ranlib"
llvm_strip="$toolchain/llvm-strip"
llvm_nm="$toolchain/llvm-nm"
llvm_objcopy="$toolchain/llvm-objcopy"
llvm_objdump="$toolchain/llvm-objdump"
llvm_as="$toolchain/llvm-as"
llvm_ld="$toolchain/ld.lld"

for required_tool in "$cc" "$cxx" "$llvm_readelf" "$llvm_ar" "$llvm_ranlib" "$llvm_strip" "$llvm_nm" "$llvm_objcopy" "$llvm_objdump" "$llvm_as" "$llvm_ld"; do
  if [[ ! -x "$required_tool" ]]; then
    echo "Required Android NDK tool is missing: $required_tool" >&2
    exit 2
  fi
done

mkdir -p "$build_root" "$jni_libs_root/arm64-v8a"
build_root="$(cd "$build_root" && pwd)"
prefix="$build_root/prefix"
downloads="$build_root/downloads"
sources="$build_root/sources"
wrappers="$build_root/wrappers"
mkdir -p "$downloads" "$sources" "$wrappers" "$prefix"

for tool in ar ranlib strip nm objcopy objdump as ld; do
  case "$tool" in
    ar) target="$llvm_ar" ;;
    ranlib) target="$llvm_ranlib" ;;
    strip) target="$llvm_strip" ;;
    nm) target="$llvm_nm" ;;
    objcopy) target="$llvm_objcopy" ;;
    objdump) target="$llvm_objdump" ;;
    as) target="$llvm_as" ;;
    ld) target="$llvm_ld" ;;
  esac
  ln -sf "$target" "$wrappers/aarch64-linux-android-$tool"
done

fetch_source() {
  local archive="$1"
  local url="$2"
  local digest="$3"
  curl --fail --location --retry 3 --output "$downloads/$archive" "$url"
  printf '%s  %s\n' "$digest" "$downloads/$archive" | sha256sum --check --status
  tar -xf "$downloads/$archive" -C "$sources"
}

fetch_source "libffi-3.8.0.tar.gz" \
  "https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz" \
  "7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4"
fetch_source "pcre2-10.49.tar.bz2" \
  "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.49/pcre2-10.49.tar.bz2" \
  "53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384"
fetch_source "libiconv-1.19.tar.gz" \
  "https://mirrors.kernel.org/gnu/libiconv/libiconv-1.19.tar.gz" \
  "88dd96a8c0464eca144fc791ae60cd31cd8ee78321e67397e25fc095c4a19aa6"
fetch_source "glib-2.90.1.tar.xz" \
  "https://download.gnome.org/sources/glib/2.90/glib-2.90.1.tar.xz" \
  "93c941aa17d5eb1d53fe838365f29a8b4e539c222a256d974ec8f30fc413e396"
fetch_source "pixman-0.46.4.tar.gz" \
  "https://cairographics.org/releases/pixman-0.46.4.tar.gz" \
  "d09c44ebc3bd5bee7021c79f922fe8fb2fb57f7320f55e97ff9914d2346a591c"
fetch_source "libslirp-4.9.5.tar.gz" \
  "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.9.5/libslirp-v4.9.5.tar.gz" \
  "f43e68b60b580647574ec4a0e2b6c600a56281e6c39f79426510832dc810f483"

export CC="$cc"
export CXX="$cxx"
export AR="$llvm_ar"
export RANLIB="$llvm_ranlib"
export CFLAGS="-O2 -fPIC -D__BIONIC__=1"
export CXXFLAGS="-O2 -fPIC"
export CPPFLAGS="-I$prefix/include"
export LDFLAGS="-L$prefix/lib"
export PKG_CONFIG_PATH="$prefix/lib/pkgconfig:$prefix/share/pkgconfig"
export PKG_CONFIG_LIBDIR="$PKG_CONFIG_PATH"
export PKG_CONFIG_ALLOW_CROSS=1

static_pkg_config="$wrappers/pkg-config-static"
cat > "$static_pkg_config" <<'EOF'
#!/usr/bin/env bash
exec pkg-config --static "$@"
EOF
chmod 755 "$static_pkg_config"
export PKG_CONFIG="$static_pkg_config"

iconv_source="$sources/libiconv-1.19"
(
  cd "$iconv_source"
  ./configure \
    --host=aarch64-linux-android \
    --prefix="$prefix" \
    --enable-static \
    --disable-shared \
    --enable-extra-encodings
  make -j2
  make install
)

libffi_source="$sources/libffi-3.8.0"
(
  cd "$libffi_source"
  ./configure \
    --host=aarch64-linux-android \
    --prefix="$prefix" \
    --enable-static \
    --disable-shared \
    --disable-docs \
    --disable-multi-os-directory
  make -j2
  make install
)

pcre2_source="$sources/pcre2-10.49"
(
  cd "$pcre2_source"
  ./configure \
    --host=aarch64-linux-android \
    --prefix="$prefix" \
    --enable-static \
    --disable-shared \
    --disable-jit \
    --enable-pcre2-8 \
    --disable-pcre2-16 \
    --disable-pcre2-32
  make -j2
  make install
)

cross_file="$build_root/android-aarch64.ini"
cat > "$cross_file" <<EOF
[binaries]
c = '$cc'
cpp = '$cxx'
ar = '$llvm_ar'
strip = '$llvm_strip'
pkgconfig = '$static_pkg_config'

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

meson setup "$build_root/glib-build" "$sources/glib-2.90.1" \
  --cross-file="$cross_file" \
  --prefix="$prefix" \
  --libdir=lib \
  --buildtype=release \
  --default-library=static \
  --wrap-mode=default \
  -Dtests=false \
  -Dinstalled_tests=false \
  -Dintrospection=disabled \
  -Dman-pages=disabled \
  -Dlibmount=disabled \
  -Dlibelf=disabled \
  -Dselinux=disabled \
  -Dnls=disabled \
  -Ddocumentation=false
ninja -C "$build_root/glib-build" -j2 install

meson setup "$build_root/pixman-build" "$sources/pixman-0.46.4" \
  --cross-file="$cross_file" \
  --prefix="$prefix" \
  --libdir=lib \
  --buildtype=release \
  --default-library=static \
  --wrap-mode=nofallback \
  -Dtests=disabled \
  -Ddemos=disabled \
  -Dlibpng=disabled
ninja -C "$build_root/pixman-build" -j2 install

meson setup "$build_root/libslirp-build" "$sources/libslirp-v4.9.5" \
  --cross-file="$cross_file" \
  --prefix="$prefix" \
  --libdir=lib \
  --buildtype=release \
  --default-library=static \
  --wrap-mode=nofallback
ninja -C "$build_root/libslirp-build" -j2 install

export PATH="$wrappers:$PATH"
export CFLAGS="-O2 -fPIE -D__BIONIC__=1 -I$prefix/include"
export CXXFLAGS="-O2 -fPIE"
export LDFLAGS="-pie -L$prefix/lib"
qemu_build="$build_root/qemu-build"
mkdir -p "$qemu_build"
(
  cd "$qemu_build"
  "$qemu_source/configure" \
    --prefix="$prefix" \
    --cross-prefix="$wrappers/aarch64-linux-android-" \
    --host-cc="$(command -v cc)" \
    --cc="$cc" \
    --cxx="$cxx" \
    --ar="$llvm_ar" \
    --target-list=aarch64-softmmu \
    --disable-werror \
    --disable-docs \
    --disable-gtk \
    --disable-sdl \
    --disable-sdl-image \
    --disable-vnc \
    --disable-curses \
    --disable-guest-agent \
    --disable-kvm \
    --disable-hvf \
    --disable-whpx \
    --disable-xen \
    --disable-xen-pci-passthrough \
    --disable-capstone \
    --disable-curl \
    --disable-gnutls \
    --disable-nettle \
    --disable-libssh \
    --disable-libusb \
    --disable-usb-redir \
    --disable-spice \
    --disable-virtfs \
    --disable-bzip2 \
    --disable-lzo \
    --disable-zstd \
    --disable-snappy \
    --disable-seccomp \
    --disable-vhost-user \
    --disable-vhost-user-blk-server \
    --enable-slirp \
    --enable-trace-backends=nop \
    --disable-stack-protector
)
ninja -C "$qemu_build" -j2 qemu-system-aarch64

qemu_binary="$qemu_build/qemu-system-aarch64"
readelf_header="$("$llvm_readelf" -h "$qemu_binary")"
if ! grep -Eq 'Machine:.*AArch64' <<<"$readelf_header"; then
  echo "QEMU output is not an Android ARM64 executable" >&2
  exit 1
fi
if ! grep -Eq 'Type:.*DYN' <<<"$readelf_header"; then
  echo "Expected QEMU output to be a PIE executable" >&2
  exit 1
fi
if ! "$llvm_readelf" -l "$qemu_binary" | grep -q '/system/bin/linker64'; then
  echo "QEMU output does not use Android's system dynamic linker" >&2
  exit 1
fi
if "$llvm_readelf" -d "$qemu_binary" | grep -Eq '\((RPATH|RUNPATH)\)'; then
  echo "QEMU output contains an RPATH or RUNPATH" >&2
  exit 1
fi

staged_binary="$build_root/stage/arm64-v8a/libqemu-system-aarch64.so"
mkdir -p "$(dirname "$staged_binary")"
cp "$qemu_binary" "$staged_binary"
chmod 755 "$staged_binary"
install -m 755 "$staged_binary" "$jni_libs_root/arm64-v8a/libqemu-system-aarch64.so"
echo "Built and staged Android ARM64 QEMU executable: $jni_libs_root/arm64-v8a/libqemu-system-aarch64.so"
