# DeskPilot

DeskPilot turns an Android 9+ phone into an offline Bluetooth HID mouse and trackpad for Windows. It uses Android's official `BluetoothHidDevice` profile: no desktop companion, cloud account, accessibility service, clipboard access, or keyboard UI is used.

## Build and install

1. Open this folder in Android Studio Ladybug or newer, let it install the Android 35 SDK, then select **Build → Build APK(s)**.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on the Galaxy S23 Ultra. Enable developer-install sources if Android asks.
3. On the first launch, approve **Nearby devices**. This grants only Bluetooth connection and advertising access.

From a terminal with Gradle available, run `gradle :app:assembleDebug`. The project targets API 35 and has a minimum API of 28, the first release containing the HID Device API.

## Pairing with Windows 11

1. Open DeskPilot and choose **Connect**. When the screen says **Ready to pair** (and the persistent DeskPilot notification is visible), Bluetooth HID is enabled. The enable button is only used after a permission or Bluetooth error.
2. Tap **Make phone discoverable for 5 minutes** and approve the Android system prompt.
3. Before that five-minute window expires, on the Galaxy Book use **Settings → Bluetooth & devices → Add device → Bluetooth**.
4. Select the phone using its normal Bluetooth name (for example, *Sagar's S23*), not necessarily *DeskPilot*. Approve the pairing confirmation on both devices. DeskPilot is the HID service the phone exposes after pairing.
4. Return to DeskPilot's **Connection** screen and press **Connect** beside the remembered Galaxy Book. It will show *Connected* only after Android receives the HID connection callback.

DeskPilot remembers the computer selected with **Connect** and automatically requests its HID connection the next time DeskPilot becomes ready. You should not need to remove the phone from Windows Bluetooth devices to reconnect. If Windows has an old, failed pairing from before this version, remove it once on both devices and complete the pairing flow again.

DeskPilot can also initiate a connection to the paired Windows HID host. Keep its persistent "DeskPilot is ready" notification active while pairing or connecting; Android automatically unregisters HID-device apps that are no longer foreground. If Windows retains a stale pairing, remove *DeskPilot* from Windows Bluetooth devices, forget the Galaxy Book from Android Bluetooth settings, then pair once more.

## Controls

- One finger moves the relative mouse cursor; tap/double tap click/double-click.
- Two-finger tap is right click.
- A second tap followed by movement starts a drag. The on-screen buttons also support press-and-hold drag.
- Two fingers scroll vertically and horizontally. Pinch sends Ctrl + / Ctrl -.
- Advanced profile: configure every three-, four-, and five-finger swipe or tap independently. The defaults follow Windows navigation (Task View/Desktop/app switching and desktop switching); five-finger gestures begin with no action.
- The keyboard button beside the mouse buttons opens the phone's normal keyboard with a slim DeskPilot typing strip attached just above it. It sends basic Latin text, punctuation, Backspace, Tab, and Enter to the focused Windows field. It is available only while the advanced HID profile is connected.
- The compact media strip offers mute, previous, play/pause, next, and a 50-detent haptic volume-adjustment control above the trackpad. Dragging it fully down/up sends enough standard reports to clamp Windows volume to its actual minimum/maximum. Assigning volume to a multi-finger gesture makes it continuous while the fingers keep moving. Standard Bluetooth media reports still cannot read the PC's current absolute volume for intermediate slider positions.
- The action bar sends shortcuts to the focused Windows application. Copy and Paste are Windows Ctrl+C/Ctrl+V, not Android clipboard operations.
- Volume shortcuts are opt-in, run only while DeskPilot is foreground and connected, and ignore Android key repeats. Disabling them restores normal volume behavior.

## Important limitations / device validation

`BluetoothHidDevice` availability and the Windows HID connection flow are device/OEM dependent. The correct Android API path and composite mouse/keyboard/consumer-control descriptor are implemented, but final HID behavior must be verified on the Galaxy S23 Ultra and Galaxy Book3 Pro 360. Windows receives ordinary HID wheel reports, which are not equivalent to native Windows Precision Touchpad gestures; horizontal wheel support also depends on the focused Windows app.

Version 1.7.0 changes the optional advanced HID descriptor to add standard Windows media controls. If Advanced laptop gestures are enabled, remove the old DeskPilot Bluetooth pairing in Windows, pair the phone again once, and then reconnect DeskPilot. This does not change settings for any physical mouse.

This release deliberately keeps the existing standard HID descriptor. A previous high-resolution wheel descriptor experiment was reverted after it caused unstable scrolling, so true high-resolution wheel support remains a separate, re-pairing-required experiment rather than part of the stable profile.

Android does not expose the protected power button to normal apps. DeskPilot includes Samsung Side-button guidance but does not claim to override it. Configure **Settings → Advanced features → Side button → Double press → Open app → DeskPilot** if available on the device.

## Manual acceptance checklist

1. Pair successfully and confirm the connection indicator names the Galaxy Book.
2. Verify stationary finger causes no cursor movement; try slow and fast movements and pointer speed tuning.
3. Verify tap, double tap, two-finger right click, three-/four-/five-finger taps, and drag after app background/foreground transitions.
4. Verify vertical/horizontal scroll in a browser and File Explorer; test natural-scroll setting.
5. Confirm Copy/Paste and action-bar shortcuts affect Windows, not the phone.
6. Enable volume shortcuts, test Copy/Paste once per press, then disable them and verify phone volume works normally.
7. During a drag, turn Bluetooth off or background the app, reconnect, and confirm no mouse or modifier is stuck.
8. With Advanced laptop gestures enabled, test slow and fast two-finger vertical, horizontal, and diagonal movement in Chrome, Windows Settings, and File Explorer. Confirm a pinch never scrolls and a scroll never zooms.
9. After re-pairing the advanced profile, test each media-strip button and its haptic volume steps. Choose a few custom multi-finger assignments and verify one swipe or tap results in exactly one selected action.
10. Focus a text field in Windows, open DeskPilot's keyboard button, and type letters, punctuation, Backspace, Tab, and Enter. Verify the text appears only in the focused Windows field.
