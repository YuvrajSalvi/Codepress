# CodePress Alpine Linux setup

## What this change adds

- Downloads an Alpine minirootfs only when the user taps Install in the Terminal tab.
- Verifies the archive SHA-256 before extraction and rejects unsafe tar paths/symlinks.
- Runs Alpine through PRoot from Android's extracted native-library directory.
- Mounts the current CodePress project workspace at `/work`.
- Lets the Shell tab open an interactive Alpine shell when the rootfs and PRoot binaries are present.
- Lets the AI `run_command` use Alpine when **Settings → Use Linux for AI commands** is enabled.
- Keeps Android `/system/bin/sh` as the fallback when Alpine is not installed/enabled.

## GitHub Actions flow

On every push, `.github/workflows/build.yml`:

1. Installs Android SDK/NDK and build tools.
2. Runs `scripts/prepare-proot.sh` to download pinned PRoot `v26.10.08-a179d3e` packages for `aarch64` and `x86_64`.
3. Verifies the published SHA-256 hashes and ELF machine type.
4. Places `libproot.so` and `libproot-loader.so` under the matching `jniLibs` ABI directories.
5. Runs unit tests and builds the debug APK.
6. Uploads `CodePress-debug-apk` as a workflow artifact.

The PRoot binaries are deliberately not committed to this source ZIP. CI prepares them at build time. See `NOTICE` for third-party licensing information.

## Phone-side steps

1. Install the APK built by the GitHub Actions workflow.
2. Open a CodePress project and go to the Terminal tab.
3. Tap **Install** and wait for Alpine installation to finish.
4. Tap **Restart** in the terminal toolbar.
5. Test:

```sh
cat /etc/alpine-release
apk update
apk add --no-cache python3 git nano
python3 --version
git --version
pwd
```

The current project should be mounted at `/work`. For example, `pwd` should show `/work` (or the selected project subdirectory).

6. Open **Settings** and enable **Use Linux for AI commands**.
7. Ask the AI to run `uname -a`, `python3 --version`, or `apk info` using `run_command`.

## Important notes

- This is Alpine Linux, not Termux. Use `apk`, not `pkg`.
- Package names can differ from Termux. Alpine may enforce PEP 668 for global Python package installs; use a virtual environment for pip packages.
- PRoot depends on ptrace support. Some Android kernels/OEM builds may restrict it; test on the target phone.
- The source ZIP does not include a built APK and I could not run Gradle in this environment because Gradle distribution download was blocked by DNS/network access. The GitHub Actions run is the real build verification.
- The pinned PRoot release is an external dependency. The CI script intentionally fails closed if hashes, architecture, or loader layout do not match; if upstream changes its release asset structure, update the script rather than bypassing verification.
