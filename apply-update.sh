#!/bin/sh
# CodePress Phase 1. Run from inside the CodePress project folder, after copying these files over it.
# Safe to run more than once.

# 1. icon library (needed for the icons used in the UI)
grep -q "androidx-compose-icons-extended" gradle/libs.versions.toml || \
  sed -i '/^androidx-compose-material3 = /a androidx-compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }' gradle/libs.versions.toml
grep -q "icons.extended" app/build.gradle.kts || \
  sed -i '/implementation(libs.androidx.compose.material3)/a\    implementation(libs.androidx.compose.icons.extended)' app/build.gradle.kts

# 2. permission for status-bar notifications (Android 13+)
M=app/src/main/AndroidManifest.xml
grep -q "POST_NOTIFICATIONS" "$M" || \
  sed -i '0,/<application/s//<uses-permission android:name="android.permission.POST_NOTIFICATIONS" \/>\n    <application/' "$M"
grep -q "android.permission.INTERNET" "$M" || \
  sed -i '0,/<application/s//<uses-permission android:name="android.permission.INTERNET" \/>\n    <application/' "$M"

grep -n "icons" gradle/libs.versions.toml app/build.gradle.kts
grep -n "uses-permission" "$M"
