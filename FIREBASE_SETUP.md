# Firebase setup for CodePress

The supplied `google-services.json` is installed at `app/google-services.json`.
It targets package `com.codepress.app` and includes a web OAuth client ID.

## Gradle wiring included
- Google Services Gradle plugin 4.4.4
- Firebase BoM 34.5.0
- Firebase Authentication, Cloud Firestore, Cloud Storage
- Google Play Services Auth 21.3.0

## Console checks required
1. Firebase Console > Authentication > Sign-in method: enable Google.
2. Add the SHA-1 and SHA-256 fingerprints for the signing certificate used by the APK.
3. Confirm Firestore Database is created and its rules require `request.auth != null` and
   restrict each user's data to `request.auth.uid`.
4. Enable Cloud Storage and apply equivalent per-user path rules.

## Important
Google Sign-In (Credential Manager -> Firebase Auth) is now implemented: Settings > Account.
It uses the web client ID from google-services.json (`default_web_client_id`). It has NOT been run on a device.
The JSON has no Android (type 1) OAuth client, which means no SHA-1 is registered yet: add your debug
SHA-1 in Firebase Console, re-download google-services.json, replace app/google-services.json, rebuild.

Still not implemented: Firestore chat sync, Firestore chat synchronization, Storage project-file synchronization,
conflict resolution, or offline queue. Do not treat SDK wiring as completed cloud sync.
Never publish permissive Firestore/Storage rules.

## Fixed debug key (needed for Google Sign-In)

Builds are now signed with `app/codepress-debug.jks` so the fingerprint never changes:

- SHA-1:   48:BC:6A:0F:20:3C:26:19:C3:94:2E:FB:65:92:C4:55:A8:4C:B1:B0
- SHA-256: C1:DE:E2:70:29:2A:68:73:10:21:B0:66:83:A2:76:22:F1:E1:CE:1E:8C:1A:E7:83:3A:06:3A:2F:50:89:87:9B

1. Firebase Console > Project settings > Your apps > com.codepress.app > Add fingerprint: paste both.
2. Authentication > Sign-in method > enable Google.
3. Download the new google-services.json, replace `app/google-services.json`, push, rebuild.
4. Uninstall the old CodePress once (it was signed with a different key), then install the new APK.
