set shell := ["bash", "-euo", "pipefail", "-c"]

# Run the local server using DATABASE_URL from the caller's environment.
dev:
    cd admin && npm run build
    go run ./cmd/coach

# Run backend tests and frontend type/security checks.
test:
    go test ./...
    cd admin && npm ci && npm run check && npm audit --audit-level=low

# Build and package the immutable Linux production release.
build:
    ./scripts/build-release

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

# Resolve the Wear app's locked dependencies and analyze its Dart sources.
wear-check:
    cd wear && flutter pub get --enforce-lockfile && flutter analyze

# Build the Wear release APK without installing it.
wear-build:
    just --justfile wear/justfile build

# Remove generated backend dependencies and release artifacts.
clean:
    rm -rf admin/node_modules admin/dist build
