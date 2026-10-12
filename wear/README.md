# Coach Wear

Flutter client for Wear OS. Use a Flutter SDK compatible with the Dart
version in `pubspec.lock`, plus an Android SDK and Java 21.

Configure `COACH_ADDR` in this directory's ignored `.env` file, using the
backend's WebSocket base URL. From this directory, `just debug` runs the
app, `just build` builds a release APK, and `just release` builds and
installs it.

The build recipe selects Java 21 for Gradle, including when Flutter selects
a newer Android Studio runtime for its launcher. Set `COACH_JAVA_HOME` if
Java 21 is installed outside the default mise location.

From the repository root, `just wear-check` resolves locked dependencies
and analyzes Dart sources; `just wear-build` builds a release APK in
`wear/build/app/outputs/flutter-apk/`.
