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

# Remove generated dependencies and release artifacts.
clean:
    rm -rf admin/node_modules admin/dist build
