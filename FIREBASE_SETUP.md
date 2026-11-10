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

## Implemented
- Google sign-in screen on first launch (`ui/LoginScreen.kt`, `data/CloudAuth.kt`), with "Skip for now".
- Chats are backed up to Firestore at `users/{uid}/chats/{projectId}` and restored after sign-in.

## Not implemented
Storage sync of project files, conflict resolution and an offline queue. Chats over ~900 KB are not backed up (Firestore 1 MiB limit).

## Required for Google sign-in to work
Add the SHA-1 of the key that signs your APK (debug key for CI builds) in Firebase, then re-download
`google-services.json`. Without a type-1 Android OAuth client in that file, sign-in fails with error 10.

Suggested Firestore rule:
`match /users/{uid}/{document=**} { allow read, write: if request.auth != null && request.auth.uid == uid; }`
Never publish permissive Firestore/Storage rules.
