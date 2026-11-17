# Willbolt privacy policy

Last updated: 31 October 2026 (Willbolt 0.6.2). Contact: seisiete@tutamail.com

**Short version.** Willbolt does not collect your data. It has no accounts, no ads, no analytics, no crash reporting and no
server of its own. Everything it looks at is looked at on your phone, and nothing it looks at is sent to the developer or to
anyone else.

## What Willbolt looks at

Willbolt works through Android's **Accessibility service**, which you switch on yourself after reading an in-app
explanation. With it, Willbolt reads:

* the name of the app that is open, and the text on the screen, including the address bar of a browser. It uses this to block
  the apps and websites you chose and to count the time you spend in apps for your time limits and breaks;
* if you turn the **screen check** on, a screenshot of the screen every few seconds. A model that runs on the phone looks at
  it for adult content. The picture stays in memory for a fraction of a second and is thrown away. It is never written to
  storage and never leaves the phone.

None of this is stored beyond what is listed below, and none of it is sent anywhere.

## What Willbolt keeps on your phone

In its own private storage, which other apps cannot read and which Android does not back up (`allowBackup` is off):

* your settings: the blocks, time limits, break timers and locks you set;
* counters of how many minutes you used each limited app today;
* a short activity log of what Willbolt did ("blocked a site at 14:02"), which you can read and clear in the Activity tab.
  It also notes when Willbolt was found switched off or force-stopped while something was locked ("off for 14 min since
  14:03"). To notice a force-stop Willbolt reads the record Android keeps of how Willbolt's *own* process ended; it reads
  nothing about other apps that way;
* if you set one, a salted hash of your lock password (never the password itself).

Uninstalling Willbolt, or clearing its storage in Android's settings, deletes all of it.

The *full edition* (installed from GitHub, not from the Play Store) also copies the on/off state and timing of your padlocks
into Android's system settings so that clearing the app's data cannot silently drop them. That copy holds no personal data
(only "locked, wait 30 minutes" and similar).

## What goes over the network

* **Nothing is sent to the developer or to any analytics, advertising or crash-reporting service.** Willbolt has no server.
  (In the full edition, *Buy me a coffee* on Home opens buymeacoffee.com in your browser when you tap it; that site's own
  privacy policy applies there. Willbolt only counts its first few blocks on the phone to know when to ask about it once.)
* The optional **website filter** is a VPN that runs on your phone and handles only DNS lookups (the "what address is this
  website" questions). Lookups for sites that are not blocked are passed on, unchanged, to a DNS server: your network's own
  by default. If you choose *Cloudflare Family* or *CleanBrowsing Family* in Filter > DNS, your lookups go to that provider
  instead, and its own privacy policy applies. No other traffic goes through Willbolt, and there is no VPN server.
* The libraries Willbolt uses are not allowed to phone home. In particular, ONNX Runtime (which runs the screen-check model)
  includes a Microsoft telemetry component on Android; Willbolt removes that component from the app and turns telemetry
  off, so the model runs fully offline. (Versions before 0.6.0 did not do this.)

## Permissions

* *Accessibility service*: described above.
* *VPN*: only for the website filter, only when you start it.
* *Device administrator* (optional): makes Android ask you to switch it off before Willbolt can be uninstalled. It requests no
  other administrator powers.
* *Internet*: only for the DNS lookups above.
* *Battery*, *start at boot*, *foreground service*, *close other apps' background processes*: to keep the filter running,
  and to stop an app that you blocked or whose time limit is used up.

## Children

Willbolt is for adults (18+) who want to control their own use of their phone. It is not directed at children.

## Your choices

Switch the Accessibility service off in Android > Settings > Accessibility at any time; Willbolt then sees nothing. Stop the
website filter in Willbolt. Uninstall Willbolt to remove everything. Locks you set in Willbolt are your own commitment: they
stop *you* from loosening settings inside the app until the lock opens, and they are not a way for anyone else to watch or
control your phone.

## Changes

If this policy changes, the new version will be in the app (Settings > About > Privacy policy) and in the project repository,
with a new date at the top.
