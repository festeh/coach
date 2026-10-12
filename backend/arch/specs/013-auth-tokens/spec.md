# Spec: Auth Tokens

## Problem

Every phase so far shipped with the same accepted risk: the coach's
API and WebSocket are open to the internet. Anyone who finds the URLs
can read my attention history, post fake temptations, release my agent
lock, or chat with my judge. The risk was time-boxed to Phase F. This
is Phase F.

## What we build

One shared secret — `COACH_API_TOKEN` — and a rule at the edge: every
public request to the coach or agents API carries either that token or
an OAuth browser session. No token, no session, no entry.

The rule lives in Caddy, not in the applications. The apps already
trust their front door; teaching each of them token-checking would
spread the same six lines across two servers and their tests. Caddy
gates all of it in one file, and the servers stay unchanged.

### Who presents what

| Caller | Credential |
|---|---|
| Browser extension (both WebSockets) | `?token=` query — the browser WebSocket API cannot set headers |
| Android (focus socket, chat socket) | `?token=` query, same reason |
| My terminal, scripts | `Authorization: Bearer` or `?token=` |
| Admin page, agents frontend | OAuth session cookie they already have — their same-origin API calls pass `forward_auth` like the pages themselves |
| my-agents → coach | nothing new — it talks to `127.0.0.1:8080` behind Caddy's back |

### The paths

- `coach.dimalip.in`: `/admin*` and `/oauth2/*` keep their current
  handling; everything else — the API and `/connect` — now requires
  token or session.
- `agents.dimalip.in`: `/api/*` (today fully public "for phone
  clients") requires token or session; the frontend keeps its OAuth
  wall.
- `etobaza.dimalip.in` (PocketBase) is out of scope: it enforces its
  own superuser auth on every collection.

## What does not change

- **The Go server and my-agents.** Zero code. They keep listening on
  localhost; Caddy decides who reaches them.
- **The judge, the ledger, the protocol.** Frames and endpoints are
  byte-identical; only the door gets a lock.

## Rollout order

Clients first, edge last. A token in a query string is inert while the
edge still lets everyone in, so the extension and the APK can ship
ahead; flipping the Caddyfile is the last, instant step. Nothing needs
to break in between.

## Known limits

- **One token for everything.** Per-device tokens would let me revoke
  one lost device; I have three clients and one owner. Rotating the
  single token is editing two .env files and rerunning two playbooks.
- **Token in query strings** means token in Caddy's access log on my
  own server. Accepted; the alternative (subprotocol smuggling) is not
  worth its confusion.
- **Baked into client builds.** The extension bundle and the APK
  contain the token. Both are local-only artifacts that never leave my
  machines.

## Success check

`curl https://coach.dimalip.in/agent-lock/state` — 401-redirect.
Same curl with the bearer token — the JSON. Browsers and phone
reconnect and beacon as before; the admin page still renders; the
coach still chats. The standing "open endpoint" note in specs 009–012
is retired.
