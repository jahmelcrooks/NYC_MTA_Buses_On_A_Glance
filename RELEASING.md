# Releasing

## One-time setup

1. **Create an upload key** (keep the file and passwords safe and private; never commit them):
   ```
   keytool -genkeypair -v -keystore release.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Add repository secrets** (GitHub > Settings > Secrets and variables > Actions):
   - `RELEASE_KEYSTORE_BASE64`: output of `base64 -w0 release.jks` (macOS: `base64 -i release.jks`)
   - `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`), `RELEASE_KEY_PASSWORD`
   - `MTA_API_KEY`: your Bus Time key
3. In Play Console, keep **Play App Signing** on (the default). Google holds the real signing
   key; your `release.jks` is only the upload key, so it can be reset if it's ever lost.

## Each release

1. Bump `versionCode` (must always go up) and `versionName` in `app/build.gradle.kts`.
2. Run **Actions > Release build > Run workflow**, or push a tag: `git tag v1.10 && git push origin v1.10`.
3. Download the `nyc-buses-release` artifact:
   - `app-release.aab` goes to Play Console (Play only accepts bundles for new apps).
   - `app-release.apk` is for sideloading: `adb install app-release.apk`.

## Building locally instead
Create `keystore.properties` in the project root (it's git-ignored):
```
storeFile=release.jks
storePassword=...
keyAlias=upload
keyPassword=...
```
Then run `./gradlew bundleRelease assembleRelease`.

## Play Console checklist (Wear OS)
- **App ID** is `com.dngro.nycbuses`. It's permanent after the first upload.
- **Privacy policy URL** is required because the app uses location. Use
  `https://github.com/jahmelcrooks/NYC_MTA_Buses_On_A_Glance/blob/master/PRIVACY.md`.
- **Data safety:** precise and approximate location are used for app functionality and sent
  to the MTA API; nothing is collected by the developer, shared for ads, or stored off-device.
  Data is encrypted in transit (HTTPS). Answer the form from `PRIVACY.md`.
- **Wear OS:** add a Wear OS form factor, upload at least one round Wear screenshot
  (384×384 or larger), and opt in to Wear OS distribution. The app is standalone.
- **Content rating** questionnaire, **target audience** (not for children), **ads:** none.
- **Name and listing:** don't use MTA logos, and keep the "not affiliated with the MTA" line
  in the description.
- New personal developer accounts must run a **closed test** (at least 12 testers for 14
  days) before production access is granted.
