# WXT + Svelte

This template should help get you started developing with Svelte in WXT.

## Agent-controlled tabs

On Chrome, Coach automatically allows tabs with an attached debugger, including
tabs controlled by Kimi WebBridge. Agents need no Coach-specific setup. This also
allows tabs being inspected with DevTools; Chrome does not identify the debugger's
owner. Coach only reads attachment status and never attaches a debugger itself.

New tabs get up to one second for an agent to attach before Coach blocks them.
Once the debugger detaches, normal blocking applies on the next navigation.
Firefox keeps its existing blocking behavior because it has no equivalent API.

Run `npm test` and `npm run check` to validate changes, then
`npm run install:browsers` to rebuild and reinstall the local browser extensions.

## Personal whitelists

The source files live outside this repository:

- `~/.local/share/coach/whitelist-chrome.txt`
- `~/.local/share/coach/whitelist-firefox.txt`

Use one hostname per line and `#` for comments. Builds and the native host
create an empty file if it is missing and preserve existing contents.
The options page shows each file's absolute path.

Run `npm run install:host` to enable live reading and editing in both browsers.
Chrome builds link to the local file, so editor changes also work without the
native host. Firefox packages include a snapshot for use without the host;
rebuild and reinstall after changing the file in that case. These personal
files are never tracked in Git. Locally built extension packages include the
current whitelist for that browser.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Svelte](https://marketplace.visualstudio.com/items?itemName=svelte.svelte-vscode).
