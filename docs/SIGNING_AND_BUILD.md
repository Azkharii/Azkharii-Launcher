# Build & Signing Guide: Cinematic Android TV Launcher

Documented in accordance with MASTER_PROMPT Annex F (Build and Installation) and project safety standards.

## 1. Automated CI Build (GitHub Actions)
The workflow `.github/workflows/build-apk.yml` handles automated building on `ubuntu-latest`:
- **Compatibility:** OpenJDK 17 + Android SDK API 34 + AGP 8.2.2 + Gradle 8.5.
- **Pre-requisite Gate:** `verify.sh` and `run_tests.sh` execute before any build step.
- **Compilation Command:**
  ```bash
  bash gradlew :launcher:app:assembleDebug --stacktrace --no-daemon
  ```
- **Output Artifact:**
  `launcher/app/build/outputs/apk/debug/app-debug.apk`
- **Signing:** Standard Android `AndroidDebugKey`, verified and installable on test TV hardware without requiring private credentials.

## 2. Release Signing Procedure (Production APK)
To build and sign an official release APK without embedding secrets in the repository:

### Step 1: Generate Release Keystore (Local/Secure)
```bash
keytool -genkeypair -v -keystore release-key.jks -alias tvlauncher \
  -keyalg RSA -keysize 2048 -validity 10000
```

### Step 2: Assemble Release APK
```bash
./gradlew :launcher:app:assembleRelease
```
This produces an unaligned, unsigned APK:
`launcher/app/build/outputs/apk/release/app-release-unsigned.apk`

### Step 3: Align and Sign with apksigner
```bash
# 1. Align APK
zipalign -v -p 4 \
  launcher/app/build/outputs/apk/release/app-release-unsigned.apk \
  launcher/app/build/outputs/apk/release/app-release-aligned.apk

# 2. Sign APK (v2 / v3 scheme required for Android TV)
apksigner sign --ks release-key.jks --ks-key-alias tvlauncher \
  --out launcher/app/build/outputs/apk/release/cinematic-tv-launcher-release.apk \
  launcher/app/build/outputs/apk/release/app-release-aligned.apk

# 3. Verify signature
apksigner verify --verbose launcher/app/build/outputs/apk/release/cinematic-tv-launcher-release.apk
```

### Step 4: GitHub Secrets Configuration (Optional CI Automation)
If signing directly within GitHub Actions is desired later, store these repository secrets:
- `RELEASE_KEYSTORE_BASE64`: Base64 encoded `release-key.jks`
- `RELEASE_KEY_ALIAS`: Keystore alias
- `RELEASE_KEY_PASSWORD`: Key password
- `RELEASE_STORE_PASSWORD`: Keystore password
