# Plan: Improve Extension Icon with SVG Graphics

**Spec**: arch/specs/004-improve-icon-with-svg-graphics/spec.md

## Tech Stack

- Language: TypeScript
- Framework: WXT (browser extension), Svelte
- Build: Vite
- SVG-to-PNG: build script using `sharp` (new dev dependency)
- Testing: manual visual check in Firefox

## Structure

Where new/changed code lives:

```
src/
├── public/
│   ├── icons/
│   │   ├── active-16.png      # generated from SVG
│   │   ├── active-32.png
│   │   ├── active-48.png
│   │   ├── active-128.png
│   │   ├── inactive-16.png
│   │   ├── inactive-32.png
│   │   ├── inactive-48.png
│   │   ├── inactive-128.png
│   │   ├── disconnected-16.png
│   │   ├── disconnected-32.png
│   │   ├── disconnected-48.png
│   │   └── disconnected-128.png
│   └── (remove old c-*.jpeg and icon*.jpeg files)
├── icons/
│   ├── active.svg             # master SVG sources
│   ├── inactive.svg
│   └── disconnected.svg
├── entrypoints/
│   └── background.ts          # add icon swapping logic
└── lib/
    └── icons.ts               # icon path helper
scripts/
└── generate-icons.ts          # SVG-to-PNG build script
```

## Approach

1. **Create master SVG icons for all 3 states**

   Hand-write 3 SVG files based on the designs from icon-lab.html:
   - `active.svg`: Sunset gradient rounded square, white bold C
   - `inactive.svg`: Neon green rounded square, dark green bold C
   - `disconnected.svg`: Flat red rounded square, pink C, small X badge

   Store in `src/icons/` (source files, not shipped to extension).

2. **SVG-to-PNG build script**

   Add `sharp` as a dev dependency. Create `scripts/generate-icons.ts` that:
   - Reads the 3 SVGs
   - Renders each at 16, 32, 48, 128px as PNG
   - Outputs to `src/public/icons/`

   Add `"icons": "tsx scripts/generate-icons.ts"` to package.json scripts.
   Run once to generate PNGs, commit them. Re-run only when SVGs change.

3. **Update manifest icons**

   In `wxt.config.ts`, change the default icons to the active set:
   ```
   icons: {
     16: "icons/active-16.png",
     32: "icons/active-32.png",
     48: "icons/active-48.png",
     128: "icons/active-128.png",
   }
   ```

4. **Dynamic icon swapping in background script**

   Add an icon update function in `src/lib/icons.ts`:
   ```
   function getIconPaths(state: 'active' | 'inactive' | 'disconnected') → icon object
   ```

   In `background.ts`, call `browser.browserAction.setIcon()` when state changes:
   - On `onConnected` → check `focusing` to pick active or inactive
   - On `onDisconnected` → set disconnected icons
   - On `onFocusMessage` → swap between active and inactive

   Hook into the existing `onStorageChanged` or directly in the WebSocket callbacks.

5. **Update notification icon**

   Change `iconUrl: "c-48.jpeg"` to `iconUrl: "icons/active-48.png"` in background.ts.

6. **Clean up old files**

   Delete from `src/public/`:
   - `c-16.jpeg`, `c-32.jpeg`, `c-48.jpeg`, `c-128.jpeg`
   - `c-nice.jpeg`
   - `icon16.jpeg`, `icon32.jpeg`, `icon48.jpeg`, `icon128.jpeg`
   - `wxt.svg`

## Risks

- **PNG quality at 16px**: The bold C design was validated in the icon lab at 16px — should be fine. Double-check after generation.
- **browserAction.setIcon timing**: If called before extension fully loads, it may fail silently. Set icons after WebSocket manager initializes.
- **sharp install size**: It's a dev dependency only, not shipped. Acceptable.
- **Firefox MV2 icon format**: Firefox MV2 supports PNG in browserAction.setIcon. SVG is not supported for manifest icons.

## Open Questions

None — design and approach are clear. Ready to implement.
