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

DeskPilot can also initiate a connection to the paired Windows HID host. Keep its persistent "DeskPilot is ready" notification active while pairing or connecting; Android automatically unregisters HID-device apps that are no longer foreground. If Windows retains a stale pairing, remove *DeskPilot* from Windows Bluetooth devices, forget the Galaxy Book from Android Bluetooth settings, then pair once more.

## Controls

- One finger moves the relative mouse cursor; tap/double tap click/double-click.
- Two-finger tap is right click; three-finger tap is middle click.
- A second tap followed by movement starts a drag. The on-screen buttons also support press-and-hold drag.
- Two fingers scroll vertically and horizontally. Pinch sends Ctrl + / Ctrl -.
- Three fingers: up Task View, down Show Desktop, sideways Alt+Tab. Four fingers switch virtual desktops.
- The action bar sends shortcuts to the focused Windows application. Copy and Paste are Windows Ctrl+C/Ctrl+V, not Android clipboard operations.
- Volume shortcuts are opt-in, run only while DeskPilot is foreground and connected, and ignore Android key repeats. Disabling them restores normal volume behavior.

## Important limitations / device validation

`BluetoothHidDevice` availability and the Windows HID connection flow are device/OEM dependent. The correct Android API path and composite mouse/keyboard descriptor are implemented, but final HID behavior must be verified on the Galaxy S23 Ultra and Galaxy Book3 Pro 360. Windows receives ordinary HID wheel reports, which are not equivalent to native Windows Precision Touchpad gestures; horizontal wheel support also depends on the focused Windows app.

Android does not expose the protected power button to normal apps. DeskPilot includes Samsung Side-button guidance but does not claim to override it. Configure **Settings → Advanced features → Side button → Double press → Open app → DeskPilot** if available on the device.

## Manual acceptance checklist

1. Pair successfully and confirm the connection indicator names the Galaxy Book.
2. Verify stationary finger causes no cursor movement; try slow and fast movements and pointer speed tuning.
3. Verify tap, double tap, two-finger right click, three-finger middle click, and drag after app background/foreground transitions.
4. Verify vertical/horizontal scroll in a browser and File Explorer; test natural-scroll setting.
5. Confirm Copy/Paste and action-bar shortcuts affect Windows, not the phone.
6. Enable volume shortcuts, test Copy/Paste once per press, then disable them and verify phone volume works normally.
7. During a drag, turn Bluetooth off or background the app, reconnect, and confirm no mouse or modifier is stuck.
