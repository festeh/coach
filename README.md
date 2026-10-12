# Coach

Personal focus and attention system, with one backend and three clients.

```text
backend/    Go API and embedded SolidJS admin interface
browser/    WXT and Svelte browser extension
android/    Native Kotlin and Jetpack Compose Android app
wear/       Flutter Wear OS app
```

Each component owns its dependencies, configuration, and build output.
The root justfile provides commands for all four components.

## Development

| Component | Setup | Commands from the root |
| --- | --- | --- |
| [Backend](backend/README.md) | Go from `backend/.go-version`, Node from `backend/admin/.node-version`, PostgreSQL | `just backend-dev`, `just backend-test`, `just backend-build` |
| [Browser](browser/README.md) | Node and `cd browser && npm ci` | `just browser-dev`, `just browser-test`, `just browser-build` |
| [Android](android/README.md) | Android SDK and Java 21 | `just android-test`, `just android-build` |
| [Wear](wear/README.md) | Flutter 3.35.6, Android SDK, Java 21 | `just wear-check`, `just wear-build` |

Keep runtime configuration in each component's ignored `.env` file.
Backend commands use `DATABASE_URL` from the caller's environment.

`just dev`, `just test`, and `just build` are aliases for the backend commands.
The backend release archive is written to `backend/build/coach.tar.gz`;
Android and Wear APKs are written under their own `build/` directories.

## Production

The workflow deploys the backend when its build inputs change on `main`.
Infrastructure owns PostgreSQL, runtime secrets, systemd, and Caddy.
See [backend documentation](backend/README.md) for the deployment contract.
