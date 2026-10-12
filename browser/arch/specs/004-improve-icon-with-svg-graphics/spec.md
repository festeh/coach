# Improve Extension Icon with SVG Graphics

## Problem

The current icon is a serif "C" over a pastel landscape illustration. At 128px it looks pleasant, but at 16px (browser toolbar size) it becomes an unreadable blur. The JPEG format adds compression artifacts and lacks transparency.

## What Users See

1. **Toolbar icon (16px/32px)**

   - **Scenario: Icon at rest**
     - **Given:** Extension is installed
     - **When:** User looks at the browser toolbar
     - **Then:** The "C" letter is clearly readable against any browser theme (light or dark)

   - **Scenario: Icon at small size**
     - **Given:** Icon renders at 16x16 pixels
     - **When:** User glances at toolbar
     - **Then:** The shape is distinct and recognizable, not a blurry blob

2. **Extension page icon (48px/128px)**

   - **Scenario: Larger display contexts**
     - **Given:** Icon appears in extension manager or store listing
     - **When:** User sees the icon at 48px or 128px
     - **Then:** Icon looks polished with clean edges and visible detail

3. **Notifications (48px)**

   - **Scenario: Focus notification**
     - **Given:** Coach sends a browser notification
     - **When:** Notification appears
     - **Then:** Icon is clear and recognizable in the notification tray

4. **Icon reflects connection and focus state**

   - **Scenario: Connected, focus active**
     - **Given:** WebSocket is connected and focus mode is on
     - **When:** User looks at toolbar icon
     - **Then:** Icon shows a vibrant/active style (full color)

   - **Scenario: Connected, focus inactive**
     - **Given:** WebSocket is connected but focus mode is off
     - **When:** User looks at toolbar icon
     - **Then:** Icon shows toxic neon green — an eyesore that nags you to focus

   - **Scenario: Disconnected**
     - **Given:** WebSocket connection is lost
     - **When:** User looks at toolbar icon
     - **Then:** Icon goes flat red — unmissable danger signal

## Design (Decided)

- **Shape:** Rounded square background
- **Letter:** Bold, geometric "C" with round stroke caps
- **Active:** Sunset gradient (top #e17055 to bottom #fdcb6e), white C
- **Inactive:** Toxic neon green (#76ff03) background, dark green C (#1b5e20)
- **Disconnected:** Flat red (#cc0000) background, light pink C (#ffcccc), small X in top-right corner

## Requirements

- [ ] Create master SVG icons for all 3 states
- [ ] SVGs must look sharp and readable at 16x16
- [ ] "C" letter clearly distinguishable from background at all sizes
- [ ] Icons work on both light and dark browser themes
- [ ] Background script dynamically swaps the toolbar icon based on state
- [ ] Export PNG versions at 16, 32, 48, and 128px from the SVG source
- [ ] Replace current JPEG icons in manifest and notification references
- [ ] Clean up unused legacy icon files (icon16.jpeg, icon32.jpeg, etc.)
