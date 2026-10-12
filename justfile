set shell := ["bash", "-euo", "pipefail", "-c"]

alias dev := backend-dev
alias test := backend-test
alias build := backend-build
alias clean := backend-clean

# Run the Go backend and build its embedded admin interface.
backend-dev:
    just --justfile backend/justfile dev

# Run backend tests and admin type/security checks.
backend-test:
    just --justfile backend/justfile test

# Package the backend production release.
backend-build:
    just --justfile backend/justfile build

# Remove generated backend dependencies and release artifacts.
backend-clean:
    just --justfile backend/justfile clean

# Run the Chrome extension development build.
browser-dev:
    cd browser && npm run dev

# Run configured browser tests and Svelte/TypeScript checks.
browser-test:
    cd browser && npm run test --if-present && npm run check

# Build the Chrome extension and Firefox package.
browser-build:
    cd browser && npm run build:all

# Build the Android release APK without installing it.
android-build:
    just --justfile android/justfile build-release

# Run Android JVM tests without a device.
android-test:
    just --justfile android/justfile test

# Resolve the Wear app's locked dependencies and analyze its Dart sources.
wear-check:
    cd wear && flutter pub get --enforce-lockfile && flutter analyze

# Build the Wear release APK without installing it.
wear-build:
    just --justfile wear/justfile build
