# Locked out? How to get out of Willbolt

A padlock in Willbolt is a promise you make to yourself. It is meant to be a nuisance to undo, not impossible. Every lock opens
in the way you chose when you set it, and there is always a way out.

## 1. Open the lock

Tap the closed padlock next to the thing you want to change.

* **After a wait**: tap *Start unlocking*. A countdown runs under the padlock. When it ends, change what you need. You can call
  the countdown off at any time.
* **With the password**: type the password. If you forgot it, tap *Forgot it?*; you can then set a new one after a 4-day wait.
* **Not before a set time**: nothing opens it before the date and time you picked. It opens by itself then.
* Any lock may also be set to **open by itself** at a date and time.

While something is locked you can still make it *stricter* (add a site, lower a limit, raise the sensitivity); you cannot
loosen it.

## 2. Remove Willbolt

* **Play Store edition**: Android Settings > Apps > Willbolt > Uninstall. If you switched on *Device administrator* in Willbolt,
  turn it off first in Settings > Security > Device admin apps.
* **Full edition** (from GitHub): if you set up *Uninstall protection* (it is off until you do), Willbolt closes its own App
  info (Force stop, Clear data, Uninstall), uninstall and your phone's battery-manager screens (Honor: Battery > App launch) while
  it is on - and if you also activated the device administrator, Android's Settings greys out Force stop and Clear storage and
  wants it deactivated before an uninstall. To remove Willbolt: tap the **Uninstall protection** card on Home to turn it off (if
  it says it is locked, first unlock its padlock, or the locks it names - **Locks**, the pill at the top of Home, lists them),
  turn the device administrator off in Settings (top right) and uninstall. Or use the way out below.

## 3. If the password is lost or a lock will not open

* Restart the phone in **Safe Mode** (hold the power button, long-press *Power off*, choose *Safe mode*; on some phones hold
  Volume-down while it starts). Third-party accessibility services do not run in Safe Mode, so Android's own settings work:
  deactivate the device administrator (Settings > Security > Device admin apps > Willbolt), then uninstall.
* If you install the full edition again and parts come back locked, run `adb shell settings delete secure willbolt_state` once.
* Last resort: a factory reset.

The difference between the two editions is described in the README ("Two editions").
