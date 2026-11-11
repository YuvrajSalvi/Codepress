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
