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
This commit wires the Firebase SDK and app configuration only. It does not yet implement
the sign-in screen, Firestore chat synchronization, Storage project-file synchronization,
conflict resolution, or offline queue. Do not treat SDK wiring as completed cloud sync.
Never publish permissive Firestore/Storage rules.
