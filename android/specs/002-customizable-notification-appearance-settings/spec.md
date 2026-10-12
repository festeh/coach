# Customizable Overlay Appearance

## Context

When the user opens a monitored app during a focus session, a blocking overlay appears on screen. It currently shows a fixed message ("I detected app [package name]. It's time to focus!") on a dark card with a red "Got it!" button. None of this is customizable.

## What Users Can Do

1. **Change the overlay message**

   - **Scenario: Set a custom message**
     - **Given:** User is on the Settings screen, Overlay section
     - **When:** User edits the message text field
     - **Then:** The overlay uses that message instead of the default

   - **Scenario: Use the default message**
     - **Given:** User has not changed the message (or clears the field)
     - **When:** The overlay appears
     - **Then:** It shows the default "I detected [App Name]. It's time to focus!"

   - **Scenario: App name placeholder**
     - **Given:** User wrote a message containing `{app}`
     - **When:** The overlay appears for Instagram
     - **Then:** `{app}` is replaced with "Instagram" (friendly name, not package name)

2. **Change the overlay color**

   - **Scenario: Pick a background color**
     - **Given:** User is on the Settings screen, Overlay section
     - **When:** User selects a color from preset options
     - **Then:** The overlay background uses that color (with the same transparency)

3. **Preview the overlay**

   - **Scenario: See changes before leaving settings**
     - **Given:** User is adjusting overlay settings
     - **When:** User taps "Preview"
     - **Then:** The overlay appears briefly with the current settings applied

## Requirements

- [ ] A new "Overlay" section appears in the Settings screen
- [ ] Users can set a custom message (text field, with `{app}` placeholder for the detected app name)
- [ ] `{app}` resolves to the friendly app label (e.g., "Instagram"), not the package name
- [ ] The default message also uses the friendly app label instead of the package name (bug fix)
- [ ] Users can pick a background color from presets (dark, blue, red, green)
- [ ] Button text ("Got it!") and button color stay as they are (not customizable)
- [ ] A preview button shows the overlay with current settings
- [ ] All settings persist across app restarts via SharedPreferences
- [ ] The native Kotlin overlay builder reads these preferences
- [ ] Default values match the current behavior so nothing changes for existing users
