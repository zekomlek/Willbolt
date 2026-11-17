# Willbolt

A self-control app for Android, in the spirit of Cold Turkey on PC: block apps and websites on a schedule, lock yourself in, and
check the screen for adult content. **Fully private**: it all happens on the phone, with no account, no ads, no analytics and no
paywall, and the app says so on its first screen.

Free and open-source under the [GNU GPL v3 or later](LICENSE). App ID `app.willbolt`.

## Download

* **[Download the latest APK](https://github.com/zekomlek/willbolt/releases/latest)** - the full edition, for Android 8 or newer on a
  64-bit ARM phone (nearly every phone sold since about 2017). One file, about 64 MB.
* **Updates without thinking about it:** add Willbolt to [Obtainium](https://obtainium.imranr.dev/)
  ([one tap](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/zekomlek/willbolt)); it checks this page's
  releases and offers each new version. Without it, download the new APK and open it: it installs over the old one and keeps
  your blocks and locks.
* **Check what you downloaded:** every release lists the APK's SHA-256 and the signing certificate's fingerprint. Android only
  installs an update signed with the same key, so nobody else can push an "update" over your copy.

## Install it (no computer needed)

1. On the phone, open the download link above and tap the `.apk` file. Android asks whether your browser may install apps: allow
   it (you can switch that off again afterwards). If Play Protect warns about an unknown app, tap *More details* > *Install anyway*.
2. Open Willbolt and tap **Turn on** on Home. If Android says the Accessibility switch is a **restricted setting** (Android 13 and
   newer do that for apps from outside a store): *Settings > Apps > Willbolt >* the three dots at the top > **Allow restricted
   settings**, then tap Turn on again.
3. Go through *Settings > Setup* in Willbolt - mainly the battery steps, so your phone doesn't stop Willbolt in the background.
4. Try it for a day with short waits before you lock anything for long. If you ever get stuck, read [`LOCKED-OUT.md`](LOCKED-OUT.md).

Optional, with a computer: [the USB step](#put-it-on-your-phone) also lets Willbolt switch itself back on when something turns it
off ("self-repair").

Willbolt was called Seawall up to 0.4.2 (`app.seawall`), and Bulwark before that. A new app ID makes Android treat it as a
different app, so moving over from those means a fresh install: set it up again, then uninstall the old one.

Also in this folder: [`PRIVACY.md`](PRIVACY.md) (the privacy policy, also in the app under Settings > About),
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) (licences), [`LOCKED-OUT.md`](LOCKED-OUT.md) (how to get out of a lock) and
[`PLAY-RELEASE.md`](PLAY-RELEASE.md) (notes for publishing on Google Play).

## What it does

| | |
|---|---|
| **Block apps** | Always, on a weekly schedule, or "for the next 3 hours". Opening a blocked app throws you back to the home screen with a card saying why. |
| **Time limits** | "Instagram: 30 minutes a day." When the time is used up the app is closed and stays closed until **the new day starts - a time you choose** (say 04:00) - or for a closure length you choose (never past the next new day). Time only counts while the screen is on and one of the block's apps is in front. A "1 minute left" notice comes first. |
| **Break timers** | After every *N* of use a full-screen countdown of *M* (both yours to set, in seconds, minutes or hours) covers the app, swallows every touch and mutes the media sound; the app cannot be used again until it ends. Optional: a timer of its own length right when you **open** the app, and a countdown that **pauses while you're in another app**. Leaving the app for a minute counts as a break by itself. |
| **Block websites** | A DNS-only VPN answers name lookups locally (nothing is routed through any server), so blocked sites do not load in any browser or app. The browser address bar is checked as a second net: once a blocked page is open (typed, a link, a shortcut), Willbolt goes **Back** off it, so the browser does not reopen on it, and to the home screen if Back cannot leave it. What you are still *typing* never counts, even when the browser has already filled in a whole blocked domain. |
| **Adult sites** | An offline list of ~47,700 known domains, **sorted most-visited-first**; you choose how many are used (type any number, or 1,000 / 5,000 / 20,000 / all; 5,000 is pre-filled). **Off on a new install** until you switch it on. Forced safe search on Google / Bing / DuckDuckGo / YouTube, encrypted-DNS loopholes closed, optional family DNS server upstream. |
| **Live screen check** | **Off on a new install** until you switch it on (sensitivity 50 and a check every 7 seconds are pre-filled). Every *N seconds* (you choose, 1-30) while the screen is on, Android hands Willbolt a screenshot (Accessibility `takeScreenshot`, Android 11+), and an on-device model looks at it. Found something -> warn, home screen, home and pause that app for N minutes, or lock the phone. Pictures are never stored or sent. |
| **Private / incognito tabs** | Android hides these from screenshots, so Willbolt cannot check them. A browser window that comes back pure black for ~6 s is treated as a private tab and blocked (switchable). |
| **Padlocks** | Every block, the screen check, the adult-site list, the website filter (DNS) and Willbolt's own uninstall protection has a **padlock**. Locked = it can only get stricter. It opens the way you chose when you locked it - **after a wait** (minutes to days), **with Willbolt's password**, or **not before a set time** - and any lock can also **open by itself** at a date and time. See "Locks" below. |
| **Can't force-stop or uninstall** | **Full edition only, and only when you set it up.** Tap the **Uninstall protection** card on **Home** to turn it on or off (the card turns teal while it is on; the same switch is in **Settings**). Its padlock is optional: lock it and the protection stays on - the tap can't turn it off - until that lock opens, and then it stays on until you switch it off. *Uninstall protection while locked* in the lock sheet of any other lock (a block, the screen check, the website filter ...) also holds it on while that lock is locked. Off for a new install. While it is on, Willbolt bounces you out of its own App-info (Force stop, Clear data, Uninstall) / device-admin / uninstall screens, and out of your phone maker's battery-manager screen that lists it (Honor: *App launch*, where its three switches decide whether the phone may stop it). Turning it on also offers the device administrator (one tap): then Android's own Settings greys out Force stop and Clear storage and refuses to uninstall it. The Play edition only has the device administrator (a row on Home; Android asks you to switch it off first). |
| **Never silent** | If Willbolt is force-stopped, crashes or its accessibility service is switched off while something is locked, **Activity** says so, when, and for how long - and when it was the phone maker's battery manager that stopped it (Honor: "PowerGenie inactive clean"), **Home** says so too. Both editions. |
| **Stays switched on** | See "The 'my phone keeps switching it off' problem" below. |
| **Feedback** | The speech-bubble icon at the top of Home (and Settings > About) opens your email app with a message to the developer. Willbolt sends nothing itself. |
| **Buy me a coffee** | **Full edition only.** A row under *Password* on Home opens [buymeacoffee.com/zekomlek](https://buymeacoffee.com/zekomlek) in your browser. Once Willbolt has been installed for 3 days **and** has blocked something 5 times (an app, a page, a picture or a private tab you saw; DNS answers don't count), Home asks once - "Happy with Willbolt?" - and never again, whichever button you press. Willbolt sends nothing itself. |

Every number in the app can be typed (with - / + buttons and a few quick choices next to it), and every length of time takes
the unit you want (s / min / h / days).

## Two editions

One source, two builds (Gradle flavors `play` and `full`; same app ID, and builds you make yourself share one signing key, so one installs over the other; the copy Google Play delivers is signed by Play, see [`PLAY-RELEASE.md`](PLAY-RELEASE.md)):

| | **Play edition** (Google Play) | **Full edition** (GitHub, sideloading) |
|---|---|---|
| Blocks, limits, break timers, screen check, website filter, adult list, padlocks, password, feedback | yes | yes |
| Locks protect settings *inside* Willbolt | yes | yes |
| Guards its own App-info / uninstall screens, and the phone maker's battery-manager screens that list it (Honor: App launch), while *Uninstall protection* is on (one tap on Home; its padlock, or the option in any lock's sheet, holds it on) | no | yes |
| Logs a force-stop, a crash or a switched-off accessibility service while locked, and says when the phone's battery manager did it ("Switched off" in Activity, a row on Home) | yes | yes |
| "Fully private" card on the first screen, one-line reminder after it | yes | yes |
| Turns its accessibility service back on, undoes Private DNS and automatic-time changes (one USB `adb` grant) | no | yes |
| Locks mirrored into system settings, so clearing data does not drop them | no | yes |
| Foreground guard service, device-admin service that exempts it from hibernation | no | yes |
| Optional device administrator (a row on Home) | yes | yes |
| "Buy me a coffee" row on Home, asked about once after 3 days and 5 blocks (Play allows tips only through its own billing) | no | yes |
| One-tap battery dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | opens the list instead | yes |

Google Play does not allow an app to stop people from disabling or uninstalling it through the Accessibility API, to change
system settings behind their back, or to dodge Android's privacy controls, so the Play edition has none of that: the code is not
just switched off, it is not in the build (`app/src/play` has empty stand-ins, `app/src/full` has the real ones). Details and the
texts for Play Console are in [`PLAY-RELEASE.md`](PLAY-RELEASE.md). Never describe the Play edition as unbypassable: with it, the
locks stop *you* from loosening settings inside Willbolt, and Android's own settings can still switch Willbolt off.

## Locks

There is no global "strict mode". Each part has its own padlock - on each block's card in **Blocks**, on the **Screen check** and
**Websites** sections in **Filter**, on **DNS** there too once its website filter is started, and on **Uninstall protection** (the card on **Home**, and **Settings**) - and **Home** shows how many are locked (tap it
for all of them in one list).

* **Lock**: tap an open padlock and pick how it unlocks (default: what you used last time):
  * **After a wait** - you start unlocking, then wait it out (1 minute to 30 days, typed or a quick choice);
  * **With the password** - Willbolt's one password opens it at once (see below);
  * **Not before a set time** - nothing opens it early; it opens by itself at that date and time (up to a year ahead).

  A wait or password lock can also **open by itself** at a set date and time; without that it stays locked until you unlock it.
  While locked, anything that makes it **stricter** still works: add apps or sites, lower a limit, break more often, raise the
  sensitivity, or *Lock it tighter* (a longer wait, a later end, no end at all, or no unlocking at all until its end).
* **Uninstall protection** (full edition): the lock sheet of every lock but Willbolt's own has an *Uninstall protection while
  locked* switch (off for a new install, then what you used last). On, Willbolt keeps its own App-info, uninstall and device-admin
  screens - and your phone maker's battery manager where it lists Willbolt (Honor: *Battery > App launch*) - closed for as long
  as that lock is locked, so Android's Settings can neither force-stop nor remove it and the battery manager cannot be told to
  stop it - and the only way to end that is the way the lock opens. (If the phone has already been stopping Willbolt in the last
  two hours, the battery-manager screen stays reachable so the cause can be fixed; see "The phone's own battery manager" below.) A locked lock can take the switch on (stricter) but not give it up. The
  **Uninstall protection** card on Home is the same protection with a switch of its own: one tap turns it on or off while no lock
  holds it. Its padlock is optional - locking it switches the protection on and keeps it on until that lock opens.
* **Unlock**: tap a closed padlock. A wait lock starts its countdown (shown under the padlock; Activity notes when it opened)
  and you can call it off at any time; a password lock asks for the password. Once open it stays open until you lock it again.
* **DNS** can only be locked once its website filter is started. Locked, the filter keeps running (Willbolt starts it again if
  it stops) and safe search, the DNS loopholes and the DNS server can only get stricter. A locked block with websites also
  keeps a started filter on. The **Websites** padlock covers only the adult-site list.
* Willbolt keeps its own clock, and changing the phone's clock does not move it. Locks and waits, the minutes an app is paused
  after the screen check found something, a block "for a while", the hours of weekly schedules, time limits and break timers all
  run on it. (Before 0.6.6 the pause, "for a while" and the weekly hours still followed the phone's clock, so moving it ended or
  skipped them.) It is checked against the phone's stopwatch, which nobody can set, **every time anything asks for the time** and
  again the moment Android says the clock was set, so there is no moment in which a lock looks open because the clock was just
  moved. (Before 0.6.6 there was one, up to five seconds long: long enough to replace a lock that was unlocking with a 5-minute
  one, and to change what the lock protects.) A move of 30 seconds or more is noted in Activity; a few seconds are let through,
  because the phone's own time sync is that far off now and then. What this cannot see is listed under "What this can and cannot
  stop".
* Locks made with version 0.3's *fixed time* keep working as before: no unlocking until that time, then the wait.
* While a part is locked, the numbers that can only get stricter - the screen check's sensitivity slider, *Check every*, the
  pause length and how many adult sites - are *picked* first and change nothing until you tap **Confirm**. A drag, a tap or a
  typed digit that goes too far can still be pulled back (the pick stops at the locked value), and **Cancel** throws it away.
  The same goes for **When something is found** (Filter > Screen check): while the screen check is locked, tapping a stricter
  way - say *Lock the phone* instead of *Just warn me* - only picks it, and it applies when you tap **Confirm**, because it can't
  be made milder again afterwards. Not locked, it still changes at once.

**The password** is one for every lock - handy when someone you trust keeps it. Set it up in the lock sheet the first time you
pick *With the password*, or on **Home** (above *Recent*). Changing or removing it needs the current one; if it is lost, *Forgot
it?* lets you set a new one without it **after a 4-day wait** (which you can call off). Five wrong tries in a row earn a 5-minute
pause, so a short code cannot be guessed faster than that. Willbolt keeps only a salted hash of it (PBKDF2), never the password.
* **Full edition:** while anything is locked, Willbolt turns its accessibility service back on if something switches it off
  (needs the one USB step), keeps *Automatic date & time* on, and - when DNS or a block with sites is locked - undoes a custom
  Private DNS. The Play edition cannot do this; Home shows what is wrong and you fix it in Android's settings.

## Blocks, limits and break timers

A *block* (Blocks tab, **New block**) is one of three kinds:

* **Block** - the apps (and optionally websites) cannot be opened while the block is on (always, a weekly schedule, or "for a while").
* **Time limit** - the apps may be used for *N minutes* a day. When the time is used up they are sent to the home screen and their
  background process is stopped. They open again when **the new day starts** (any time of day you pick; default midnight) or, if you
  prefer, after a closure you choose (never later than the next new day). A "1 minute left" notice appears first. Time counts only
  while the screen is on and unlocked and one of the block's apps is in front, and it is added up over all apps in the same block.
* **Breaks** - nothing is closed; only the break timer applies.

On a time limit or a breaks block the **break timer** covers the app with a countdown after every *N* of use. It swallows every touch,
and it keeps running if you leave and come back - the app cannot be used until it has run out. "Also when I open the app" shows a
countdown first thing after you open it (when you had been away for more than a minute; being away that long counts as a break
by itself), with **a length of its own** (say 10 seconds on opening, 2 minutes after every half hour). **Pause when I leave the app**
stops a running countdown while you use another app, so it can't be sat out somewhere else: you come back to the same seconds you
left. It keeps counting while the screen is off - not using the phone at all is a break. While the countdown is on screen the
phone's **media sound is muted** (videos, music, games; calls and alarms are not touched), muted again if a volume key turns it on,
and put back as it was when the countdown goes. Time limits and break timers work for apps, not websites.

A block without a name is named after what it blocks ("Instagram, TikTok +1"). Switch it off on its page to pause it without
deleting it. Leave a block's page without saving and a *Changes not saved* note shows for five seconds; tap it to go back to the
page exactly as you left it.

## Build

Tools live under `%USERPROFILE%\android-dev` (set `WILLBOLT_TOOLS` or `ANDROID_HOME` if yours are elsewhere; JDK 17 and the Android SDK, API 36, in `android-sdk\`; Gradle 8.13 comes with
the wrapper). Keep them out of
`%LOCALAPPDATA%`: the Claude desktop app is a packaged app and hides files it writes there from your own terminal.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\make-keystore.ps1   # once - creates the signing key (BACK UP keystore\ and keystore.properties)
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1           # full edition -> app\build\outputs\apk\full\release\app-full-release.apk
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1 -Edition play -Bundle   # Play edition -> app\build\outputs\bundle\playRelease\app-play-release.aab
```

Every release build first runs `verifyManifest<Edition>Release`: it fails when the merged manifest (what the libraries add to
ours) has a permission, service, receiver or provider that is not listed in `app/manifest-allowlist/<edition>.txt`. After a
dependency update, read what it reports before allowing anything. The feedback address is `willbolt.feedbackEmail` in
`gradle.properties`.

The signing key in `keystore\` keeps its old file name (`bulwark-release.jks`, alias `bulwark`); `keystore.properties` points at
it, and `make-keystore.ps1` will not replace an existing one.

Unit tests (DNS packets and checksums, schedules, locks, time limits and break timers, the adult list, settings
compatibility, locked-number picks): `. .\tools\env.ps1; .\gradlew.bat testFullDebugUnitTest testPlayDebugUnitTest`.
If Windows says a file "is being used by another process", run `.\gradlew.bat --stop` and build again.

The adult-site list in the app (`app/src/main/assets/lists/adult-domains.txt`) is generated: `tools/make-adult-list.py` takes the
raw StevenBlack hosts file (kept in `tools/data/`), folds subdomains into their parent domains and sorts the result most-common-first
using the free Majestic Million ranking (CC BY 3.0, download `majestic_million.csv` from <https://majestic.com/reports/majestic-million>):
`python -I tools/make-adult-list.py --hosts tools/data/porn-only-hosts.txt --rank <majestic_million.csv> --out app/src/main/assets/lists/adult-domains.txt`.

Why a release build: a debug build is "debuggable", which lets anyone with a USB cable read and edit the app's private files,
including the locks.

## Put it on your phone

1. Phone: *Settings > About phone* > tap **Build number** 7 times. Then *Settings > System & updates > Developer options* >
   **USB debugging** on. Plug the phone in and tap **Allow** on the prompt.
2. PC: double-click `tools\install-to-phone.bat` (or run `powershell -ExecutionPolicy Bypass -File .\tools\install-to-phone.ps1`)  (installs the **full edition** APK and grants the permissions
   only a computer can grant - read the script, every line is a plain `adb` command). Honor may show its own "install via USB"
   or "risky app" prompt on the phone - allow it.
3. Phone: open Willbolt, tap **Turn on** on Home, then go through *Settings > Setup* (battery, your phone's own battery
   manager - the steps are shown in the app). Start the website filter in *Filter > DNS*.
4. Walk around your phone with *Filter > Show live score* on for a few minutes and set the sensitivity.
5. When you are happy, lock what you want locked. For the first days use a short wait (5 min) so you can undo mistakes.

Android 13+ greys out the Accessibility switch for apps installed from outside a store ("Restricted setting"). The USB step
sidesteps that. Without it: *Settings > Apps > Willbolt > (three dots) > Allow restricted settings*, then switch it on.

## The "my phone keeps switching it off" problem

Three different things do this, and Willbolt handles each:

1. **"Pause app activity if unused" / "Remove permissions if app isn't used"** (Android 11+). If you do not *open* the app for a
   few months Android resets its permissions. *Settings > Setup* detects it and links you to the switch. The full edition's install
   script exempts the app and the app is also registered as a device-policy controller, a category Android never hibernates; the
   Play edition leaves Android's privacy control alone and just asks you to switch "Keep permissions when unused" off.
2. **The phone's own battery manager** (Honor: *Battery > App launch*). It force-stops background apps. *Settings > Setup* shows the
   exact steps for your phone and opens the screen. This one cannot be set by any app - it is the one manual step. See
   "The phone's own battery manager" below for what it does if the step is skipped.
3. **Anything that switches the accessibility service off** (a crash, a cleaner app, you in a weak moment). In the full edition,
   with the one-time USB grant, Willbolt watches the system setting and, while anything is locked, turns the service back on within
   a second, plus a 30-second foreground-service tick, a 15-minute WorkManager job and a boot receiver as backups. Without the USB
   grant, and in the Play edition, it can only warn you. The website filter you started comes back after a reboot either way.

### The phone's own battery manager

Found on an Honor Magic5 Lite (MagicOS) on 2 November, and the reason for the "App launch" steps in *Settings > Setup*. Honor's
manager (PowerGenie) **force-stops** an app as soon as the screen has been off a while, unless that app is set to keep running
(*Battery > App launch > Willbolt*: *Manage automatically* off and **Auto-launch**, **Secondary launch** and **Run in background**
all on). A foreground service, the notification and an enabled accessibility service do **not** protect an app from it. Android's
own record of the stop reads `iAwareR[PowerGenie inactive clean]`, which is how Willbolt can tell this from you.

After any force-stop Android switches the app's accessibility service off, cancels its jobs and alarms, and starts nothing until you
*open* the app again. So with those switches off, blocking was off for about 19 of 24 hours that day, with every lock still
"locked".

* **Setup** lists the exact steps and opens the screen. Tick **Done** only when the switches are on.
* **Full edition, while *Uninstall protection* is on:** the guard closes that battery-manager screen as soon as it lists
  Willbolt, as it does the App-info page, so the switches cannot be turned off while it is on. Set them **before** you turn it on. If the phone
  has stopped Willbolt in the last two hours (since you last ticked **Done**) the screen stays reachable, because closing it
  would only leave blocking off: fix the switches there, tick Done, and it closes again.
* **Both editions:** *Activity* logs every stop with the time and who did it ("the phone's battery manager ... PowerGenie inactive
  clean", a force-stop, a crash), and Home's battery-manager row turns red while something is locked and the phone is doing it.
  The 15-minute background job also brings back a crashed accessibility service now (it used to leave a young process alone).
* **What Willbolt cannot do:** read or set those switches (Honor keeps them behind a permission only its own apps have), or come back
  by itself once the phone has force-stopped it. Honor's *Power saving* / *Ultra power saving* and Phone Manager's clean-up were not
  tested; the Setup steps ask for them to be off. Other makers' battery managers (Xiaomi, Oppo, Vivo, Samsung) are covered by
  the same rule with the package names their managers are known by - not checked on a phone.

## What this can and cannot stop (honestly)

Nothing on a normal, un-rooted phone is unbeatable by the person holding it. Willbolt is built to make the impulsive route slow and
annoying, not impossible:

* **Play edition:** no uninstall guard and no self-repair. Anyone can switch the accessibility service off, force-stop it, clear its
  data or uninstall Willbolt from Android's settings (the optional device administrator only makes Android ask them to switch it
  off first). The padlocks protect the settings inside Willbolt, and that is all they claim to do - the lock sheet says so when you
  lock something, and Activity records every time Willbolt was found switched off while something was locked.
* **Full edition, Force stop:** with *Uninstall protection* on (the card on Home; a lock made without it does not turn it on and
  Force stop still works then - Activity notes it), the guard closes the App-info page. While it is on but nothing holds it on, one
  tap on Home turns it off again - lock its padlock if that should take a wait. If the guard misses a screen on some
  phone (makers rename things), or you use `adb`, Force stop still works - the device administrator is the Android-level answer: its
  Settings app disables Force stop and Clear storage for an app that has one (checked in the code of Android's and Honor's Settings).
* **Full edition, the phone's own battery manager:** the one stop the guard cannot make safe on its own. If Willbolt's *Run in
  background* is off when a lock is made, the phone force-stops Willbolt whenever the screen has been off a while and nothing is
  blocked until you open it (see "The phone's own battery manager" above). The guard keeps that screen closed while locked, so
  the switch cannot be turned off afterwards; Activity and Home report every stop, but nothing can undo one.
* **The phone's clock** (both editions): moving it changes nothing in Willbolt (see "Locks"). Two things it cannot tell apart from
  the real thing: a clock moved *forward* while Willbolt is not running - force-stopped, say - and then the phone restarted looks
  like a phone that was off for that long (a clock moved back is caught after a restart as well); and a change of **time zone**
  moves the hours of a weekly schedule and the moment a time limit starts a new day, because those are the hours on the phone's
  wall, and travelling does the same. Neither speeds up a lock or a wait.
* **Safe Mode** boots without third-party accessibility services, so the uninstall guard is off there. Device-admin still blocks
  plain uninstall, but you can deactivate the admin and uninstall from Safe Mode. (Only a "device owner" setup can forbid Safe Mode;
  that needs the phone to have no accounts when it is set up and is not included.)
* **A USB connection** (`adb`) can do anything: wipe the app's data, edit settings. The locks of the screen check, the website
  filter and the uninstall protection are mirrored into the system settings, so merely clearing the app's data does not drop them,
  but an adb user can delete that too. Blocks and the password live only in the app's data (a password lock that comes back from
  the mirror becomes a 4-day wait, like a forgotten password).
* **A factory reset** removes everything.
* The uninstall guard recognises screens by Android's App-info header, by the class of Android's own uninstall dialog (also what
  the home screen's *Uninstall* opens) and of its device-admin screen - the same in every language - and, for the rest, by the word
  "Willbolt" plus Uninstall / Force stop / Deactivate (in ~20 languages). A phone maker's battery manager (Honor/Huawei
  `systemmanager`, Xiaomi `securitycenter`, Oppo/OnePlus `safecenter`, Vivo, Samsung `lool`) is known by its package and closed
  whenever its window mentions Willbolt - only the Honor/Huawei one was seen on a real phone. Phone makers rename things, and so
  do translations (Slovenian Android says "odmestiti", not "odstrani", in the uninstall dialog, which the guard once missed).
  *Settings > Guard diagnostics* logs what it sees so the matching can be tuned for your phone.
* Apps that talk to a hard-coded encrypted-DNS server by IP address can skip the website filter. Only one VPN can be active at a
  time: if you switch to another VPN app the filter stops (Home shows it). The live screen check still works.
* The address-bar check reads what the browser shows, so it depends on how each browser builds its bar. It knows Chrome and the
  browsers built on it (Edge, Brave, Opera, Vivaldi, Kiwi), Samsung Internet, DuckDuckGo and Firefox (including the rebuilt bar of
  Firefox 15x, which only *describes* the address to accessibility services). A browser that shows the page title instead of the
  address, or an app's built-in browser, is only covered by the DNS filter. Browsers keep recently used addresses for a minute or
  so, so the DNS filter alone can let a site you had open a moment ago load once more.
* To see what the address-bar check reads on your phone, connect it over USB and run
  `adb shell dumpsys activity service app.willbolt/.service.GuardAccessibilityService fields` with the browser in front (add
  `tree` instead of `fields` for the whole window; *Settings > Guard diagnostics* on also keeps the last readings).
* Screens that Android marks secure (banking apps, incognito) come back black from `takeScreenshot` and cannot be checked.
* Time-limit and break-timer counters live in the app's private storage (`usage.json`). Clearing the app's data (or force-stopping within the
  last few seconds) can lose some of the day's count. The counters follow the phone's clock through the same clock-tamper guard as the
  locks, but they count what the Accessibility service can see: a video playing in picture-in-picture or audio with the screen off is not
  "use". The new day follows the phone's *time zone*, so changing the time zone (or travelling) moves it.
* The model is a classifier, not magic: it will miss some things and flag some things. Treat it as one layer.

## Getting out

The short version is in [`LOCKED-OUT.md`](LOCKED-OUT.md).

* Inside the app: unlock what you want to change (tap its padlock: wait, or the password). To remove Willbolt (full edition):
  unlock **everything** that is locked - while anything is, its App-info and uninstall screens are closed - turn off the device
  administrator in Settings, then **Uninstall Willbolt**, unless a lock is set until a time that has not come yet.
* If a set time is still running, the password is gone, or something is broken: restart the phone in **Safe Mode** (hold the power button, long-press
  "Power off", choose Safe mode; or hold Volume-down while it boots). Third-party accessibility services do not run there, so
  *Settings > Security > Device admin apps > Willbolt > Deactivate* works, and then you can uninstall it normally. Deliberately a
  hassle. If you reinstall afterwards and parts come back locked, run `adb shell settings delete secure willbolt_state` once.
* Last resort: factory reset.

## Privacy

* **Fully private, and the app says it first.** A new install opens on a "Fully private" card (no account, no ads, no analytics, no
  server; screenshots checked in memory and thrown away; the one network exception, the optional website filter, named right
  there). After "Got it" it stays as a one-line reminder under the title that opens the policy. Keep that card true to
  [`PRIVACY.md`](PRIVACY.md) - the policy is the source, the card is its first line.
* No accounts, no analytics, no ads, no crash reporting, no cloud. The only network traffic is the DNS lookups your phone makes
  anyway (forwarded to your network's DNS server, or the family DNS you pick in Filter > DNS).
  Since 0.6.0 that is also true of the libraries: ONNX Runtime's Android library starts a Microsoft telemetry client from a
  content provider of its own (it reports to `mobile.events.data.microsoft.com`). Earlier versions shipped it; Willbolt now removes
  the provider in its manifest and turns telemetry off in code, and the build checks the merged manifest so a library cannot
  bring something like that back unseen. The full text is in [`PRIVACY.md`](PRIVACY.md).
* Screenshots exist in memory for a fraction of a second, are cut up, fed to the model and discarded. They are never written to
  disk. The Activity log stores only "app X, score 0.97, 21:14".
* The app is visible: launcher icon, a clear in-app disclosure before the accessibility switch, and Android's own list of
  active apps while its background service runs.
* **No notifications.** Willbolt sends no alerts; what needs attention is on Home, and what happened is in Activity. Android
  requires a notice while a background service runs - the guard runs only while something is locked, the website filter only
  while it is on - and Willbolt keeps that notice silent. On Android 13 and newer it does not ask to post notifications at all,
  so even that notice stays out of the notification shade (it shows only in Android's list of active apps).
  There is deliberately **no** hidden mode and **no** remote control - that is what separates a self-control app from stalkerware.

## Releasing it to other people

Things I could verify while building this (checked October 2026) and things you must check yourself before publishing:

* **Direct APK downloads (GitHub etc.)** work today. Google's *developer verification* starts 30 Sep 2026 for installs from
  participating app stores in Brazil, Indonesia, Singapore and Thailand and widens to all certified Android phones in 2027;
  ADB installs are exempt and there is a free "limited distribution" tier (up to 20 devices, no ID). For a public sideloaded
  release plan to register in the Android Developer Console (government ID + $25 one-off). See
  <https://developer.android.com/developer-verification>.
* **Google Play** allows accessibility-based apps only with a declared, prominent use case; expect to fill in the Accessibility
  API declaration, a VPN-service declaration, justify the `specialUse` foreground service and provide a privacy policy. The Play
  edition is built for that and [`PLAY-RELEASE.md`](PLAY-RELEASE.md) has the texts and the checklist. The visible, consent-based
  design here is what those reviews look for.
* **Android 17's Advanced Protection Mode** blocks accessibility for apps that are not genuine accessibility tools. On phones
  with that mode on, the accessibility-based parts will not work; the DNS filter still will.
* **Will it work on all Androids?** Core features need Android 8+, the live screen check needs Android 11+. Every maker's battery
  manager behaves differently; the app has step lists for Honor/Huawei, Xiaomi, Oppo/OnePlus/realme, Vivo and Samsung, but only
  the Honor path is something you can test yourself. Not iPhone.
* Before release: move the signing key somewhere safe (with Play App Signing it becomes your upload key). `targetSdk` is 36 and
  the native libraries are 16 KB page-size aligned (re-check both after updates). The name and the app ID (`app.willbolt`) are
  settled; the ID can never change once published.

## Licence

Willbolt is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It comes
with no warranty. See [`LICENSE`](LICENSE). Copies and changed versions you share must stay under the same licence, with their
source.

## Third-party

See `THIRD_PARTY_NOTICES.md` (also in the app: Settings > About > Open-source licences). Those parts keep their own licences
(Apache 2.0, MIT, CC BY 3.0), all of which allow use in a GPL-3.0 app.
