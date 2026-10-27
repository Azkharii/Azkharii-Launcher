# Automatic versioning and in-app updates

## Current implementation

- Every workflow build uses the GitHub Actions run number as Android `versionCode`, with `versionName` set to `1.0.N`.
- The production release workflow uses a matching `v1.0.N` tag.
- A non-debug launcher checks the latest published release on resume and every six hours while the launcher is visible.
- It validates the expected APK name, public SHA-256 digest, package ID and version before opening Android's package installer.
- The actual package installation remains user-approved by Android. Debug builds intentionally do not self-update.

## Required one-time setup

### 1. Configure a stable signing key

Android only accepts an in-place app update when the application ID matches and the APK is signed with the same certificate (or a valid signing-key rotation). Keep the private key out of Git.

Create a release keystore locally and add the following secrets under **Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: signing alias
- `ANDROID_KEY_PASSWORD`: key password

The keystore must remain stable for all future releases. The debug application uses a `.debug` application ID and a CI debug key, so it is not the starting package for in-place production updates. Install the signed production APK once.

### 2. Publish a publicly readable release feed

**The current GitHub repository is private.** GitHub's latest-release endpoint returns 404 to an anonymous client for a private repository. The television app cannot query private releases without credentials, and embedding a personal access token in the APK would disclose that token. This workflow deliberately does not embed credentials or change repository visibility.

To activate in-app discovery, either make the repository public or host signed release metadata/APKs on a separate public feed and point `UpdateManager` to that feed. Public releases are published automatically only from `main`, and only when signing secrets are configured.

### 3. Allow Android's installer

For the first update Android may ask you to permit Cinematic TV to initiate package installations. After enabling that permission, the app opens Android's installer for the APK it already verified. Approve the installation in Android; no silent install is attempted.

Pushing to `main` publishes `cinematic-tv-launcher-release.apk` under `v1.0.N` when the signing and public-feed prerequisites are met. Pushing to a feature branch still produces a debug APK artifact only.
