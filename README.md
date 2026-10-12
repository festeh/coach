# Coach

Coach is a personal focus and attention system. This repository contains the
Go API, its embedded SolidJS administration interface, and all three clients.

| Component | Directory | Stack |
| --- | --- | --- |
| Backend | Repository root | Go, PostgreSQL |
| Administration | `admin/` | SolidJS, Vite |
| Browser extension | `browser/` | WXT, Svelte |
| Android app | `android/` | Kotlin, Jetpack Compose |
| Wear app | `wear/` | Flutter, Dart |

The Android app's Gradle project is in `android/android/`; the Wear app's is
in `wear/android/`. Each client keeps its own dependencies and build tools.

## Development

Coach requires Go and Node versions declared in `.go-version` and
`admin/.node-version`, plus a PostgreSQL `DATABASE_URL`.

```bash
just test
just dev
```

Run client commands from the repository root:

```bash
just browser-dev
just browser-test
just browser-build
just android-build
just wear-check
just wear-build
```

Install browser dependencies with `cd browser && npm ci`. Android needs an
Android SDK, a Gradle wrapper, and Java 21. Wear needs Flutter and an Android
SDK. See the component READMEs and [migration notes](docs/monorepo.md) for
configuration, wrapper setup, preserved history, and browser installation.
Runtime configuration stays in ignored `.env` files in the root and each
client directory.

`just build` creates a tested, immutable Linux artifact at
`build/coach.tar.gz`. The archive contains only `REVISION` and the static Go
binary; it never contains database credentials or authentication tokens.

## Production

The infrastructure repository owns PostgreSQL, runtime secrets, the systemd
unit, Caddy authentication, and the restricted `coach-deploy` identity. A push
to `main` that changes backend build inputs may upload, activate, and restart
Coach through its forced SSH command. Client-only changes do not trigger a
backend deployment. The workflow has no shell, sudo, database, Caddy, or
Ansible access.

Coach binds explicitly to `127.0.0.1:8080`. Caddy is its only public ingress:
machine clients use the rotated API token, while browser access uses Caddy
Basic Authentication over HTTPS.
