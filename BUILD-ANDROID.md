# Building the Pocket Ledger Android app

The Android app is the same screens as the Windows app, packaged with Capacitor. All the web files are already built
in `android-app/www`. Only the Android build step remains. Pick one way.

## A. GitHub builds it for you (no tools to install)
1. Create a private repository on GitHub and push this whole folder to it.
2. Open the repository's Actions tab, choose "Android APK", then Run workflow (it also runs on every push).
3. When it finishes, open the run and download the `pocket-ledger-debug-apk` file. Unzip it to get `app-debug.apk`.

## B. Android Studio
1. Install Android Studio (it brings the Android tools and the right Java).
2. Open the folder `android-app/android`. Wait for the first sync to finish.
3. Build > Build APK(s). The file appears at `android-app/android/app/build/outputs/apk/debug/app-debug.apk`.

## C. Command line (Android SDK and Java 21 installed)
    cd android-app && npm install && npx cap sync android
    cd android && ./gradlew assembleDebug

## Putting it on your phone
Copy `app-debug.apk` to the phone (USB, Drive, WhatsApp to yourself) and open it. Android asks once to allow
installing from that source. It is a debug-signed test build, so Play Protect may ask you to confirm.
Later builds with the same signing key update the app and keep your data.

## Where the data lives
In the app's private storage (one file, with the previous version kept as a fallback). It is not visible to other
apps. Use Settings > Backup & restore to save a copy through the share sheet (Drive, Files, email). The backup
file is the same format the Windows app writes, so you can move data between them by restoring the backup.

## Changing the screens
Edit `reference/prototype.html`, then run `python3 build.py` in `android-app` followed by `npx cap sync android`.
