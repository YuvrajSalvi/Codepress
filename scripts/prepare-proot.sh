#!/usr/bin/env bash
set -euo pipefail

# PRoot Android release pinned to a published release and verified by SHA-256.
VERSION="v26.10.08-a179d3e"
BASE_URL="https://github.com/ahmed-alnassif/proot/releases/download/${VERSION}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch_arch() {
  local arch="$1" expected="$2" abi="$3"
  local archive="$TMP/proot-${arch}.zip"
  local unpack="$TMP/${arch}"
  mkdir -p "$unpack" "$OUT/$abi"
  echo "[PRoot] Downloading ${arch} (${VERSION})"
  curl --fail --location --retry 3 --retry-delay 2 \
    "$BASE_URL/proot-${arch}.zip" -o "$archive"
  echo "${expected}  ${archive}" | sha256sum --check --status || {
    echo "SHA-256 verification failed for proot-${arch}.zip" >&2
    exit 1
  }
  unzip -q "$archive" -d "$unpack"

  local proot loader
  proot="$(find "$unpack" -type f -name proot -print -quit)"
  if [[ -z "$proot" ]]; then
    echo "Could not locate the PRoot executable in proot-${arch}.zip" >&2
    find "$unpack" -type f -print >&2
    exit 1
  fi

  # Release archives contain the matching 64-bit loader under loader/.
  loader="$(find "$unpack" -type f \( -name loader -o -name loader64 -o -name 'libproot-loader.so' \) ! -name '*32*' -print -quit)"
  if [[ -z "$loader" ]]; then
    echo "Could not locate the 64-bit PRoot loader in proot-${arch}.zip" >&2
    find "$unpack" -type f -print >&2
    exit 1
  fi

  chmod 0755 "$proot" "$loader"
  case "$arch" in
    aarch64) expected_machine='AArch64' ;;
    x86_64) expected_machine='Advanced Micro Devices X86-64' ;;
  esac
  local machine
  machine="$(readelf -h "$proot" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
  if [[ "$machine" != "$expected_machine" ]]; then
    echo "Wrong PRoot architecture for $abi: $machine" >&2
    exit 1
  fi
  machine="$(readelf -h "$loader" | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p')"
  if [[ "$machine" != "$expected_machine" ]]; then
    echo "Wrong loader architecture for $abi: $machine" >&2
    exit 1
  fi

  install -m 0755 "$proot" "$OUT/$abi/libproot.so"
  install -m 0755 "$loader" "$OUT/$abi/libproot-loader.so"

  # Copy any non-platform shared-library dependencies shipped in the release.
  # Android system libraries are provided by the OS and must not be bundled.
  while IFS= read -r needed; do
    case "$needed" in
      libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libz.so|libjnigraphics.so|libc++_shared.so) continue ;;
    esac
    local dependency
    dependency="$(find "$unpack" -type f -name "$needed" -print -quit)"
    if [[ -z "$dependency" ]]; then
      echo "Missing non-system PRoot dependency for $abi: $needed" >&2
      readelf -d "$proot" >&2 || true
      exit 1
    fi
    install -m 0644 "$dependency" "$OUT/$abi/$needed"
  done < <(readelf -d "$proot" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')

  echo "[PRoot] Installed $abi PRoot + loader"
}

fetch_arch aarch64 d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3 arm64-v8a
fetch_arch x86_64 ef06aeb47f5bb9c96bb9019fe8efa320ed199151020428b2335af6035c05f166 x86_64

# Fail early if either ABI is incomplete.
for abi in arm64-v8a x86_64; do
  test -s "$OUT/$abi/libproot.so"
  test -s "$OUT/$abi/libproot-loader.so"
done
