# PRoot Android binaries

The GitHub Actions workflow runs `scripts/prepare-proot.sh` before Gradle. That script downloads the pinned upstream Android PRoot release for `aarch64` and `x86_64`, checks SHA-256, checks ELF machine architecture, and places the executable and its matching 64-bit loader here:

```text
app/src/main/jniLibs/
  arm64-v8a/libproot.so
  arm64-v8a/libproot-loader.so
  x86_64/libproot.so
  x86_64/libproot-loader.so
```

`libproot.so` is an executable ELF file despite the `.so` suffix. CodePress launches it by absolute path from `ApplicationInfo.nativeLibraryDir`; it is not loaded with `System.loadLibrary`. The loader is passed via `PROOT_LOADER`. Do not mix ABIs or loader versions.

To prepare the binaries locally (Linux/Termux with curl, unzip, sha256sum, readelf, and install):

```sh
./scripts/prepare-proot.sh
```

The script is intentionally pinned to PRoot release `v26.10.08-a179d3e` and verifies published SHA-256 values. Update the version and hashes together when upgrading.

Upstream binary release and corresponding source: https://github.com/ahmed-alnassif/proot/releases/tag/v26.10.08-a179d3e
License: AGPL-3.0. The PRoot source and license must remain available to recipients when redistributing a build containing these binaries. See `NOTICE`.
