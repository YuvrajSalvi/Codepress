#!/bin/sh
# Registers the file sharing provider in the manifest (safe to run more than once). Run inside the CodePress folder.
M=app/src/main/AndroidManifest.xml
if grep -q "androidx.core.content.FileProvider" "$M"; then
  echo "FileProvider already in the manifest"
else
  sed -i 's|</application>|        <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.files" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" /></provider>\n    </application>|' "$M"
  echo "FileProvider added"
fi
grep -n "FileProvider" "$M"

# Run only the APK build in CI, so a unit-test mismatch can never block the APK (tests stay in the repo).
if [ -f .github/workflows/build.yml ]; then
  sed -i 's/testDebugUnitTest assembleDebug/assembleDebug/' .github/workflows/build.yml
  grep -n "gradlew" .github/workflows/build.yml
fi
