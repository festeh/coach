# Coach

Coach is a personal focus and attention service with a Go API, WebSocket
clients, and an embedded SolidJS administration interface.

## Development

Coach requires Go and Node versions declared in `.go-version` and
`admin/.node-version`, plus a PostgreSQL `DATABASE_URL`.

```bash
just test
just dev
```

`just build` creates a tested, immutable Linux artifact at
`build/coach.tar.gz`. The archive contains only `REVISION` and the static Go
binary; it never contains database credentials or authentication tokens.

## Production

The infrastructure repository owns PostgreSQL, runtime secrets, the systemd
unit, Caddy authentication, and the restricted `coach-deploy` identity. A push
to `main` may upload, activate, and restart only Coach through its forced SSH
command. The workflow has no shell, sudo, database, Caddy, or Ansible access.

Coach binds explicitly to `127.0.0.1:8080`. Caddy is its only public ingress:
machine clients use the rotated API token, while browser access uses Caddy
Basic Authentication over HTTPS.
