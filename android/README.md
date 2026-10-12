# Coach Android

Native Kotlin and Jetpack Compose client. The Gradle project lives in
`android/` relative to this directory; Flutter is not required.

Use Java 21, an Android SDK, and the Gradle wrapper described in
[migration notes](../docs/monorepo.md). Put `sdk.dir` in the ignored
`android/local.properties` file and configure `WEBSOCKET_URL`, `AGENTS_URL`,
and `COACH_API_TOKEN` in this directory's ignored `.env` file.

From this directory, `just build-release` builds the release APK. `just run`
builds and installs a debug APK; `just install-release` builds and installs
a release APK. Set `COACH_JAVA_HOME` if Java 21 is installed somewhere other
than the default mise location used by the justfile.

From the repository root, `just android-build` builds the release APK in
`android/build/app/outputs/apk/release/`.
