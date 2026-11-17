# Releasing Willbolt on Google Play

Notes for the person publishing (checked 28 October 2026). Nothing here is sent anywhere; it is a checklist plus the texts
Play Console asks for, written to match what the app actually does. If the app changes, change these with it: **the in-app
disclosure, Android's own consent text, the Console declarations and your review video must all say the same thing.**

## Two editions of one app

Play does not allow an app to stop people from disabling or uninstalling it through the Accessibility API (policy
*Permissions and APIs that Access Sensitive Information*, section *Accessibility API*; the only exceptions are parental-control
apps and enterprise administrators, so a lock you set on yourself is not covered), to change user settings behind their back,
to work around Android's privacy controls, or to add features that keep an app from being uninstalled or stopped (policy
*Malware*, section *Elevated Privilege Abuse*). Checked against the live policy pages on 30 October 2026. So the code is built
in two editions (`app/src/play` and `app/src/full`, one Gradle flavor each):

| | **Play edition** (what you upload) | **Full edition** (GitHub, sideloading) |
|---|---|---|
| Blocks, time limits, break timers, screen check, website filter, adult list, padlocks, password | yes | yes |
| Locks protect settings *inside* Willbolt | yes | yes |
| Uninstall guard (closes Willbolt's App-info / uninstall screens while *Uninstall protection* is on: one tap on Home, held on by its padlock or by the *Uninstall protection while locked* switch in any lock's sheet; off by default) | **no** | yes |
| Honest lock note ("Android's Settings can still switch Willbolt off...") under every lock form | yes | its own note (what the guard covers, and that it is a setting on Home) |
| Activity log of a force-stop, a crash (and whether the phone's battery manager did it) or a switched-off accessibility service while locked ("Switched off") | yes | yes |
| "Fully private" card on Home for a new install, one-line reminder after | yes | yes |
| Turns its own accessibility service back on, undoes Private DNS / auto-time changes (needs a USB `adb` grant) | **no** | yes |
| Locks mirrored into system settings so clearing data does not drop them | **no** | yes |
| Foreground "guard" service and the device-admin service that exempts the app from hibernation | **no** | yes |
| Optional device administrator (Android asks you to deactivate it before uninstalling); a row on Home, worded as below | yes | yes |
| Battery: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog | no, opens the list instead | yes |
| Permissions only a computer can grant (`WRITE_SECURE_SETTINGS`) | none | yes |

Both editions have the same app ID, and builds you make yourself are signed with the same key, so one installs over the other
on a phone. The copy Google Play delivers is different: Play signs it with its own app signing key unless you give it yours
(see "Before you upload"), and Android refuses an update signed with a different key.

Never describe the Play edition as unable to be bypassed. Say what it is: a speed bump you set up yourself.

### What the Play edition does instead

Chosen so that nothing in it tries to stop a person from switching Willbolt off or removing it, which is the line Play draws:

* **It tells the truth when you lock.** The lock sheet says a lock guards Willbolt's own settings and that Android's Settings
  can still switch Willbolt off or stop it (Activity then notes when), or clear its data or uninstall it.
* **Switching it off is never silent.** If Willbolt is force-stopped or crashes, or its accessibility service is off for a minute
  or more, while something is locked, Activity gets a "Switched off" entry with the time and, once it is back, how long it was
  off - and says so when the phone maker's own battery manager did it (Android words such a stop "... PowerGenie inactive
  clean" on Honor), and Home's battery-manager row turns red. This reads only Android's record of how Willbolt's *own* process
  ended (`ActivityManager.getHistoricalProcessExitReasons`, Android 11+, no permission) and the accessibility service's own
  connect / disconnect. It stops nothing and sends nothing.
* **Home shows what is wrong** (service off, filter off, Private DNS bypass, battery, hibernation) and opens the right Android
  screen. It never changes a system setting itself.
* **The optional device administrator stays**, as before: opt-in, described honestly ("Android asks you to deactivate it
  before Willbolt can be uninstalled", no other powers), switched off in Android's settings at any time, and Willbolt does
  nothing to hinder that. It is the one place a reviewer could object (*Malware > Elevated Privilege Abuse*, "don't use device
  admin features to block uninstallation"); if Google does, drop the receiver and the Home and Settings rows (one switch)
  rather than argue. Since 0.6.3 it is also one row on Home ("Optional: Android asks you to switch it off before
  uninstalling." with an Activate button); turning it off stays in Settings. The listing and the in-app Play texts promise only what Android's own deactivate prompt does. Android's Settings also
  greys out Force stop and Clear storage for an app with an active administrator (true on stock Android and in Honor's
  Settings code), but do not promise that in the Play listing or the Play edition's texts.

Build: `gradlew bundlePlayRelease` (AAB for Play) and `gradlew assembleFullRelease` (APK for GitHub). Both run
`verifyManifestPlayRelease` / `verifyManifestFullRelease`, which fail the build when the merged manifest gains a permission, component or provider that is not in
`app/manifest-allowlist/<edition>.txt`. After every dependency update, read what it complains about instead of adding to the list
blindly. (This is how the ONNX Runtime telemetry provider was found.)

## Before you upload

* Target API 36 / compileSdk 36 (done; Play has required it for new apps and updates since 31 August 2026). Next year: API 37.
* 16 KB page size: the native libraries are aligned (checked), keep checking after each ONNX Runtime update.
* Use **Play App Signing**. By default Google makes its own app signing key and the key in `keystore/` is only your *upload
  key*; back it up somewhere that is not git, and keep `keystore.properties` out of the repository.
* **Decide before the first release whether a Play install and your own full-edition builds should replace each other.** With
  Google's key they cannot: switching means uninstalling first, which deletes the app's data (and every lock with it). If you
  want them to, choose *Export and upload a key from Java keystore* (the PEPK tool) on the App signing page so Play signs with
  your existing key. The app signing key is hard to change afterwards. Either way, the phone you test the closed-test build on
  needs the full edition removed first unless you chose your own key.
* Test on a clean phone: install, turn on the accessibility service from the in-app disclosure, start the website filter, run a
  block and a break timer.
* Record the review video (see "Accessibility declaration").

## Play Console texts

### Accessibility declaration (App content > Sensitive app permissions)

* **Is accessibility core to the app?** Yes. Willbolt is a self-control app: it blocks the apps and websites the user chose,
  counts the user's time in apps for their own time limits and break timers, and checks the screen for adult content.
* **Why no narrower API?** Which app and which browser address is in front can only be read through accessibility events and
  window text; there is no API for another app's address bar. The on-device adult-content check needs a screenshot of the
  screen, which only the accessibility `takeScreenshot` call provides without a persistent screen-recording session.
* **What is read:** the package name of the app in front, window text and the browser address bar, and (only if the user turns the screen
  check on) a screenshot every few seconds. **What is done with it:** matched on the phone against the user's own blocks and a
  local model. **Stored or sent:** no. Screenshots are kept in memory for a fraction of a second.
* **`isAccessibilityTool`:** not set, and must stay unset. Willbolt is not an accessibility tool.
* **Video:** open the app on a fresh install (show the *Fully private* card, tap *Got it*) > Home > *Turn on* > show the full disclosure dialog (scroll to the end) > tap *No thanks* (show that
  nothing is enabled) > open it again and tap *Agree* > Android's accessibility screen with Willbolt's description > switch it on >
  show a block, the screen check and a break timer working > show Settings > About > Privacy policy.

### VPN declaration (VpnService)

* Core functionality: a **local DNS filter**. The VPN runs only on the device and answers DNS lookups; no traffic is sent to a
  VPN server, nothing is encrypted or tunnelled, no other traffic is redirected. Allowed lookups are passed on to the DNS server
  the user chose (their network's by default, or Cloudflare Family / CleanBrowsing Family if they pick one).
* It does not manipulate traffic for advertising or monetisation, and does not collect or share what passes through it.
* Start is user-initiated (Filter > DNS > *Start*, with Android's own VPN consent), and can be stopped in the app or in Android's
  settings.

### Foreground service (specialUse)

* One service is declared: the local DNS filter, with the subtype *On-device DNS filter for a self-control app*. Play needs a
  short video of it: Filter > DNS > Start > blocked site fails to load > Stop. The Play edition has no other foreground service.

### Data safety

Suggested answers; you are responsible for them:

* *Does your app collect or share any of the required user data types?* **No.** Willbolt has no server and sends nothing to the
  developer; screenshots, window text and addresses are processed on the phone and not kept.
* The optional DNS choice sends the user's DNS lookups to a provider **the user picks** (a "family" DNS), like Android's own
  Private DNS setting. If you prefer to be conservative, declare *Web browsing history - shared - optional - app functionality*
  and link the policy.
* *Is all data encrypted in transit?* Not applicable (nothing is collected). *Can users request deletion?* Nothing is held
  anywhere else; uninstalling deletes it all.

### Other forms

* **Privacy policy URL:** publish `PRIVACY.md` (for example the file view on GitHub once the repository is public) and paste the URL.
  The same text is in the app: Settings > About > Privacy policy.
* **App access:** no login. Instructions for the reviewer: "Open the app, tap *Turn on* on Home, agree, switch Willbolt on
  in Android's accessibility list. To see the website filter, open Filter > DNS and tap *Start*."
* **Target audience:** 18+ only. **Ads:** none. **Content rating:** answer the questionnaire truthfully (the app contains a list of adult
  *domain names* used for blocking, and no sexual content). **Category:** Tools or Productivity.
* **Health apps declaration:** not a health app; do not market it as treatment for addiction.
* **Financial features, news, government, COVID:** none.

## Store listing

* Title up to 30 characters, short description up to 80, full description up to 4000. No keyword stuffing, no "#1 / best / free"
  claims, no competitor names.
* Say what it does and what it does not: "A self-control app. It blocks apps and sites you choose and checks the screen for
  adult content, on your phone only. Locks only protect Willbolt's own settings; you can still uninstall it."
* State the VPN use ("a local DNS filter on your device; no traffic goes to any server") and the accessibility use.
* **Lead with privacy, truthfully.** Short description, for example: "Block apps and sites, set limits, check for adult content.
  Fully private." (73 characters; the limit is 80.) First lines of the full description: "Willbolt works only on your phone. No
  account, no ads, no analytics, no server." and, in the same breath, the one exception: the optional website filter passes DNS
  lookups to the DNS server you pick (your network's by default). Keep it the same in meaning as the in-app "Fully private" card,
  `PRIVACY.md` and the Data safety form; if one changes, change all four. The Data safety summary ("No data collected") is what
  Play shows at the top of the listing, so it is the strongest "private" signal people see before installing - answer it
  carefully. Use the card as one of the screenshots (no explicit imagery).
* No explicit imagery in screenshots or the icon. No "cure addiction" claims. No "100% blocks" or "can't be bypassed".
* Do not link the listing's website to a page with donation buttons (see below).
* **No link to the full edition's APK, in the Play app or on its listing.** Google's Developer Program Policy gives "linking to a
  non-compliant APK hosted outside Google Play" as an example of providing access to functionality that breaks its terms, and the
  full edition's uninstall guard is exactly what Play does not allow (Device and Network Abuse; the Malware policy repeats the
  example). The GPL notice in the app's licences page names the source repository, which is how a GPL app on Play offers its
  source; keep it to that wording and don't add "get the full edition" texts or buttons. (A US-only court order since
  29 October 2025 lets developers link US users to outside downloads, but it does not cover the rest of the world.)
* Check the name and icon for trademark clashes before publishing (a Play search on 26 October 2026 found no "Willbolt").

## New personal accounts

Personal developer accounts created after November 2023 must run a **closed test with at least 12 testers for 14 days** before
production access. Start it as soon as the AAB is ready.

## Donations, tips and feedback

* **Donations:** not in the first release. Reports from 2026 (AnkiDroid) say Google flags apps that send users to an outside
  donation page unless the money goes to a registered non-profit; I could not find that in the official Payments policy text, so
  read it before deciding. On Play use **Google Play Billing** (a tip
  as a consumable in-app product, needs the billing library, a listing that exists and a licensed tester to try it). Keep an
  external donation link only in the full edition, the README and your own website, never in the Play app or on the page the
  listing points to. Add it in an update once the first version is approved. Since 0.6.9 the full edition has one ("Buy me a
  coffee" under Password on Home, and a one-time "Happy with Willbolt?" popup after 3 days and 5 blocks, `src/full/.../ui/Support.kt`);
  the Play edition's `src/play/.../ui/Support.kt` is empty, so neither the link nor the popup is in the Play build.
* **Feedback:** *Home > the speech-bubble icon* and *Settings > About > Send feedback* open the user's email app with a message
  addressed to the address in `gradle.properties` (`willbolt.feedbackEmail`). Nothing is sent by Willbolt itself. Do not add
  a "do you like the app?" step that sends happy users to the Play review and unhappy ones to feedback: that is review gating
  and breaks the Play rules.
* **Rating:** if you add a "Rate Willbolt" row later, open the Play listing or use the In-App Review API; never reward ratings.

## After release

* Bump the target API every year before 31 August.
* Answer Play policy emails within their deadline (usually 7-30 days); appeal through Play Console if rejected.
* Never hide a feature from reviewers or switch one on remotely after approval (cloaking gets accounts terminated).
* Google's developer verification starts for sideloaded installs in four countries on 30 September 2026 and widens in 2027; it
  matters for the *full* edition's APK downloads (the README explains).
