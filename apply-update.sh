#!/bin/sh
# Adds the icon library to the project (idempotent). Run from inside the CodePress folder.
grep -q "androidx-compose-icons-extended" gradle/libs.versions.toml || \
  sed -i '/^androidx-compose-material3 = /a androidx-compose-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }' gradle/libs.versions.toml
grep -q "icons.extended" app/build.gradle.kts || \
  sed -i '/implementation(libs.androidx.compose.material3)/a\    implementation(libs.androidx.compose.icons.extended)' app/build.gradle.kts
grep -n "icons" gradle/libs.versions.toml app/build.gradle.kts
