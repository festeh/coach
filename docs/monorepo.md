# Repository migration

The backend stays at the repository root. The former `coach_browser`,
`coach_android`, and `coach_wear` repositories are imported into `browser/`,
`android/`, and `wear/` using Git subtree merges without squashing. Their
original commits remain ancestors of the migration branch with unchanged
commit IDs. There are no nested Git repositories or submodules.

The local migration branch is `migration/coach-monorepo`. Source repositories
remain in their original sibling directories. Their local branch tips are
also retained under `refs/remotes/legacy-browser/`,
`refs/remotes/legacy-android/`, and `refs/remotes/legacy-wear/`; these local
references include feature branches that were never merged. They are not
published automatically when pushing the migration branch.

Uncommitted Browser and Wear changes are copied into the corresponding
directories and stay uncommitted. Per-client `.env` files, native Android
Gradle wrappers, and Android `local.properties` files are copied locally and
remain ignored. Build output and dependencies are regenerated as needed.

## Fresh checkout

- Backend: use the Go and Node versions in `.go-version` and
  `admin/.node-version`, then run `just test` or `just build`.
- Browser: run `npm ci` in `browser/`, then `just browser-test` and
  `just browser-build` from the root.
- Android: configure the Android SDK and Java 21. The original repository
  ignores its wrapper scripts and JAR. Restore them from the existing local
  checkout, or generate them with Gradle 8.10.2. To generate without
  configuring the app first, run `gradle wrapper --gradle-version 8.10.2
  --distribution-type all` in an empty temporary directory and copy
  `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` into
  `android/android/`. Keep the committed wrapper properties. Then run
  `just android-build`.
- Wear: install a Flutter SDK compatible with `pubspec.lock`, create
  `wear/.env`, and configure the Android SDK. Flutter generates its local
  Android wrapper when building. Run `just wear-check` and `just wear-build`.

The copied Wear lockfile resolves unchanged with Flutter 3.35.6. Newer Flutter
releases can require changes to the SDK's pinned dependencies; select this
version to preserve the current lockfile during migration.

## Browser installation after moving

Build from `browser/` and load Chrome's unpacked extension from
`browser/dist/chrome-mv3`. Moving that directory changes its path-derived
Chrome extension ID and creates a separate Chrome installation, including
separate local extension storage. Remove the previous loaded copy when
switching to the new installation.

Run `npm run install:host` from `browser/` to register the native messaging
host at its new absolute path. Its installer derives the Chrome ID from the
current build directory. Set `COACH_CHROME_EXTENSION_ID` to override this
for a keyed installation. `npm run install:browsers` rebuilds Chrome and
installs the Firefox package; Firefox retains `coach@dimalip.in` as its ID.

The existing browser installations keep using the sibling source repo until
these installation commands are run. Do not delete that repo before switching.

## Deployment

The existing backend release script and production deployment path stay at
the root. The push workflow filters for backend build inputs so client-only
changes do not deploy the Go service. Manual workflow dispatch remains
available. Client builds use their own dependency files and toolchains.
