# BYD HUD

**English** | [Українська](README.uk.md)

<img align="right" src="app/src/main/res/drawable-nodpi/hud_apk_icon.png" alt="BYD HUD icon" width="112">

BYD HUD connects an active Google Maps or Waze route to the navigation fields already available in compatible Chinese-market BYD vehicles. The map stays in the navigator; maneuver, distance, street, lanes, alerts, and optional trip metrics are sent separately to the HUD.

The app also controls navigator projection on the instrument cluster, keeps day-based diagnostic logs, can download fixed verified navigator builds, and can locally prepare compatible navigator packages for a structured direct channel.

- **Supported navigators:** Google Maps and Waze
- **Tested platform:** Android 12 / DiLink 5.0
- **Get started:** [download the latest release](https://github.com/sunlixWhyNotAvailable/byd-hud/releases/latest) and follow [Installation](#installation)
- **Need help:** read [Troubleshooting](#troubleshooting), then [report a problem](#report-a-problem) with the relevant day archive

BYD HUD is not an Android Auto replacement. Normal navigator activities remain responsible for route search and selection; an optional Waze custom surface applies only after navigation starts.

## What BYD HUD does

BYD HUD takes active route guidance from a supported navigator and sends the useful parts to the vehicle:

- current maneuver and native turn arrow;
- distance to the maneuver;
- street name or text direction;
- recommended lanes when the source provides them;
- Waze alerts when the active Waze session provides them;
- optional ETA, remaining time, and remaining distance;
- optional projection of the navigator to the instrument cluster.

Google Maps and Waze remain responsible for the map and route. BYD HUD only coordinates the navigation data, HUD output, dashboard projection, diagnostics, and optional local navigator patching.

## Features

| Area | What it provides |
| --- | --- |
| Navigation output | Maneuver image, native arrow, distance, street or cue, lanes, and optional route metrics |
| Direct channels | Structured low-latency data from a compatible patched Google Maps or Waze build |
| Optional legacy input | Accessibility, notification, or visual parsing after its settings and services are explicitly enabled |
| Dashboard control | Move a running navigator between displays and set the height of its dashboard projection |
| Navigator downloads | Download and validate the supported Waze and Google Maps builds from the fixed project release |
| Navigator patcher | Inspect and locally patch compatible Google Maps or Waze packages for direct-channel support |
| Logs and storage | Record diagnostics, manage logs by day, and share only the selected data |
| Updates | Stable releases by default, with an optional beta channel |
| Manual testing | Send individual HUD fields and glyph IDs for diagnostics |

<p align="center"><img src="docs/screenshots/en/apps.png" alt="Apps tab with supported navigators and HUD controls" width="100%"></p>

## Navigation channels

BYD HUD prefers a compatible direct channel for the selected navigator. Legacy inputs are optional, disabled on a clean installation, and become available only after their settings, permissions, and services are enabled.

| Navigator | Preferred direct channel | Optional legacy input | Main limitations |
| --- | --- | --- | --- |
| Google Maps | Structured maneuver, distance, road, rendered maneuver image, ETA, remaining time, and remaining distance | Accessibility and notification data after capture services are granted and connected | Legacy input has no lane guidance and may have limited maneuver data |
| Waze | Structured maneuver, distance, street, lanes, session alerts, and patched cluster trip metrics | Screen capture and visual parsing after `Screen capture channel (legacy)` is enabled | The legacy channel is off by default, layout-dependent, and no longer supported by the developer |

By default, the direct channel leaves the navigator activity unchanged while navigation data is sent to BYD HUD in the background. With `Start with custom surface` enabled, Waze still handles search and route selection in its normal activity, then supplies the active route map and controls to a separate BYD HUD surface after navigation starts. Normal Waze direct guidance remains available while the surface starts; if it is not ready within five seconds, BYD HUD keeps that guidance without ending the route. Google Maps always keeps its normal activity.

### Current navigator contract

| Navigator use | Package | Currently supported profile |
| --- | --- | --- |
| Waze direct output and patching | `com.waze` | stock `4.95.0.3` or project-patched `5.20.0.1` |
| Google Maps direct output and patching | `app.revanced.android.apps.maps` | Google Maps ReVanced `25.16.03.747108139` |
| Official Google Maps legacy input only | `com.google.android.apps.maps` | Accessibility/notification capture when its services are enabled; not accepted by the patcher |

The patcher still verifies exact package, signer, split, and DEX structures. A matching version label alone is not sufficient.

### Direct-channel switching

- BYD HUD switches to a direct channel as soon as valid data arrives.
- A five-second direct-frame timeout permits a legacy handoff only when that navigator's optional legacy path is already enabled and ready.
- Google Maps requires granted and connected accessibility/notification capture services. Waze additionally requires the unsupported `Screen capture channel (legacy)` switch, which is off by default.
- Without those prerequisites, no fallback starts after the timeout. When valid direct data returns, BYD HUD clears any legacy state and switches back immediately.
- The `HUD` status is active only while navigation payloads are actually being sent. Selecting a navigator without starting navigation leaves it in the waiting state.

## Using the Apps tab

The `Apps` tab is the normal starting screen after initial setup.

The notice at the top lists the supported navigator builds. Each `Download` action retrieves one fixed asset from the project release, shows inline progress, verifies its SHA-256, package, version, version code, and signer, then offers the Android installer. An already compatible installation is updated normally; uninstall is requested only after explicit confirmation when Android cannot replace the installed signer or version safely.

1. Find Google Maps or Waze under `Supported navigation apps`.
2. Enable `HUD` before or after launching the navigator.
3. Start a route in the navigator.
4. Use `Send to dashboard` or `Send to main` only while the app is running.
5. Enable `Log` only when you need extended logs for a specific app session. Basic daily event and navigation logs are recorded independently.

The same tab also lists other applications that can be moved between displays, but navigation parsing and HUD output are limited to supported navigators.

### Status pills

| Status | Meaning |
| --- | --- |
| `HUD: running` | A navigation payload is currently reaching the vehicle HUD |
| `HUD: waiting` | HUD output is selected, but there is no current navigation payload |
| `HUD: failed` | A required permission or service is unavailable |
| `ADB: OK` | The local ADB bridge is authorized and available |
| `ADB: not granted` | ADB-dependent maintenance functions are unavailable |
| `Permissions: OK` | Required base permissions are present |
| `Permissions: missing` | One or more required base permissions must be restored |

## Navigation output options

The `Options` tab controls what BYD HUD sends. Changing a switch affects the next outgoing HUD state; it does not change the map inside the navigator.

<p align="center"><img src="docs/screenshots/en/options.png" alt="Navigation output and dashboard options" width="100%"></p>

### Basic navigation output

| Setting | Default | When enabled | When disabled |
| --- | --- | --- | --- |
| `PNG output` | On | Shows the rendered maneuver or alert image | Hides the rendered image field |
| `Native output` | On | Shows the vehicle's native turn arrow | Hides the native arrow field |
| `Lane output` | On | Shows recommended lanes when available | Hides lane guidance |
| `Distance output` | On | Shows distance to the current maneuver | Hides maneuver distance |
| `Street output` | On | Shows the current road or street text | Hides street text |
| `Text direction output` | On | Uses a cue such as `Continue straight` when no street text is available | Does not substitute a cue for a missing street name |

Street text has priority over the text direction because both use the same vehicle HUD field.

### Route metrics

| Setting | Default | Behavior |
| --- | --- | --- |
| `ETA/time/distance output mode` | Off | Selects `Off`, `Next stop`, or `Entire route`; Waze always supplies only next-stop values |
| `Show ETA` | Off | Adds the expected arrival time to the street field |
| `Show remaining time` | Off | Adds remaining travel time to the street field |
| `Show remaining distance` | Off | Adds remaining route distance to the street field |

The three value switches are disabled while the mode is `Off`, but their saved values are preserved.

Enabled metrics are added before the street or cue, for example:

```text
[ETA: 12:20 | 11 min | 5.1 km] Main Street
```

Google Maps direct supplies the complete metric contract. Project-patched Waze `5.20.0.1` supplies ETA and remaining distance to the next destination in its cluster session. It does not currently supply remaining travel time or distinguish whole-route metrics there.

### Speed limit

| Setting | Default | Behavior |
| --- | --- | --- |
| `Speed limit output mode` | Off | Places the current direct-channel speed-limit sign in the maneuver field, lane field, or the first free field |
| `Overlay in "In a free field" mode` | Off | When both fields are occupied, optionally allows a timed replacement of the maneuver or lane field |
| `Display time when overlapping` | 5 seconds | Sets the temporary replacement time from 1 to 10 seconds |

An alert occupies the maneuver field with the same priority as a route maneuver. A sign in a genuinely free field remains visible until the direct source changes or clears it; the timer applies only when an occupied maneuver, alert, or lane field is replaced. This feature requires the current project-patched direct profile for Google Maps or Waze.

### Additional navigation behavior

| Setting | Default | Behavior |
| --- | --- | --- |
| `Small distance clamp` | Off | Replaces positive distances below 11 m with 11 m to avoid invalid characters on affected HUD firmware |
| `Roundabout left-hand traffic` | Off | Uses left-hand roundabout glyphs in the legacy visual parser |

### Waze functions

| Setting | Default | Behavior |
| --- | --- | --- |
| `Show Waze alerts` | On | Keeps a supplied Waze alert visible while route distance, street, lanes, and the matching native maneuver continue to update |
| `Start with custom surface` | Off | Samples the setting when a Waze route starts, then opens Waze-rendered route content in a separate surface; Back returns to the normal Waze activity for the rest of that route |

The custom surface is route-scoped. Changing its switch during an active route does not hot-swap sessions. A five-second readiness failure keeps the standard Waze direct guidance active and suppresses another surface attempt until the next route. Returning to Waze during the same active route restores the surface; route-end reason codes close it only for terminal events. The Waze alert switch controls HUD alert output only; alerts supplied to the custom Waze surface remain visible there.

## Dashboard projection

`Send to dashboard` moves a running application to the instrument cluster. `Send to main` returns it to the center display.

| Setting | Default | Behavior |
| --- | --- | --- |
| `Fullscreen dashboard` | On | Uses the full dashboard navigation mode; off selects the compact map mode |
| `Height` | 100% | Changes the projected window height live from 20% to 100% |

The navigator must already be running. Before moving it, BYD HUD sends the OEM `30011` command for the selected full or compact layout, then verifies that the application task reached the target display. During an active Waze custom-surface route, the separate surface task follows the navigator task so the BYD HUD settings screen is never moved by mistake. BYD HUD cannot read back the actual fullscreen/partial cluster mode and does not run a watchdog that reapplies that mode. The switch therefore represents the requested layout, not an authoritative vehicle state. Dashboard control requires authorized ADB access on the tested firmware.

<!-- Photo slot: docs/screenshots/en/dashboard.jpg
Use a landscape photo showing the real instrument cluster. Place it here.
Suggested markup:
<p align="center"><img src="docs/screenshots/en/dashboard.jpg" alt="Navigator projected to the BYD instrument cluster" width="900"></p>
-->

> Use dashboard controls only while parked. Some stock cluster layouts add dark top and bottom overlays; BYD HUD can resize the window but cannot remove firmware-owned overlays.

## Navigator patcher

The `Patch` tab is optional. It can add a BYD HUD direct channel to a structurally compatible Waze or Google Maps ReVanced package without uploading the APK anywhere.

<p align="center"><img src="docs/screenshots/en/patch.png" alt="Local navigator patch compatibility and status" width="100%"></p>

### Supported input

- an installed `com.waze` or `app.revanced.android.apps.maps` package;
- a downloaded `.apk` file;
- compatible `.apkm` or `.apks` split-package archives;
- an APK-only `.xapk` archive.

Official Google Maps package `com.google.android.apps.maps`, bundles with OBB expansion data, and unsupported feature-split layouts are rejected. The currently guarded patch profiles are Waze `4.95.0.3` / `5.20.0.1` and Google Maps ReVanced `25.16.03.747108139`.

### How patching works

1. Select the installed navigator or optionally choose a downloaded version.
2. Press `Check` to inspect its package name, signer, splits, and exact DEX structures.
3. Review the component status pills. Waze reports the direct channel, stable session, and Waze alerts separately; Google Maps reports the direct and audio channels.
4. Press `Patch` and confirm the warning.
5. BYD HUD repeats all structural checks, patches only known targets, signs the complete package set with a key generated on this tablet, and asks Android to install it.

The direct-channel layer is mandatory. Optional layers, such as the Google Maps navigation audio channel or Waze stable-session and alert support, may be unavailable without preventing the direct channel from working. The Waze stable-session layer covers route lifecycle, direct speed limits, and cluster trip metrics. The Waze alert hook is currently limited to the structurally compatible Waze 5.20.0.1 layout.

### Signing and app data

- A persistent signing key is created locally inside BYD HUD.
- A later compatible patch signed by the same key can normally update in place.
- An incompatible existing signer can require uninstalling the navigator first, which removes that navigator's local data.
- Clearing BYD HUD data or uninstalling BYD HUD also removes its local signing key.
- Unknown publisher signatures are rejected.

Back up important navigator data and sign in to its account before patching. The patcher never sends selected APKs to a server. The separate download actions on the `Apps` tab retrieve only the three fixed, pinned navigator assets documented above; patching a user-selected source still operates locally.

## Runtime and maintenance options

| Setting | Default | Purpose |
| --- | --- | --- |
| `Boot runtime service` | On | Restarts the BYD HUD runtime after supported boot and update events |
| `Save diagnostic screenshots and extended logs` | Off | Records additional navigation evidence; use it only while diagnosing because it consumes more storage |
| `Check for updates` | On | Checks the stable GitHub release channel |
| `Take part in beta testing` | Off | Includes prereleases, which may be unstable or broken |

`ADB permissions` runs the permission setup when needed. `Background apps` opens the BYD system page where BYD HUD should be excluded from background blocking. `Shutdown` stops HUD output and the runtime cleanly.

## Logs, storage, and privacy

BYD HUD stores navigation evidence in day folders so one trip can be shared without exposing unrelated days.

<p align="center"><img src="docs/screenshots/en/storage.png" alt="Daily log storage and sharing" width="100%"></p>

<p align="center"><img src="docs/screenshots/en/logs.png" alt="Logcat and configuration diagnostics" width="100%"></p>

### Storage tab

- shows current log size and the configured size limit;
- groups navigation logs, snapshots, screenshots, and optional logcat by day;
- shares or deletes only the selected days;
- shows file count, archive size, and a sensitive-data warning before creating a ZIP.

`Share selected` offers two explicit destinations:

- `Another app` opens the normal Android share chooser;
- `Send to developer` uploads the same selected ZIP to the Sentry service once.

### Logs tab

- `Record Logcat` starts an explicit system log recording;
- `Stop Logcat` ends the current recording;
- `Share configuration` creates a smaller diagnostic archive for device, permission, network, patcher, and SOME/IP setup.

Configuration sharing works without ADB. If the local ADB bridge is already authorized, the archive also includes additional read-only system diagnostics. Sharing does not request ADB access and does not enable telemetry.

BYD HUD does not automatically send crashes, ANRs, sessions, breadcrumbs, traces, screenshots, replays, or navigation logs to the Sentry service. Read the complete policy in [PRIVACY.md](PRIVACY.md).

## Manual HUD testing

The `Manual` tab is a diagnostic tool. It can send individual text, distance, lane, PNG, and native-arrow values without starting a route. It is useful for confirming vehicle glyph mappings and should not be used as a navigation source.

<p align="center"><img src="docs/screenshots/en/manual.png" alt="Manual HUD payload testing" width="100%"></p>

## Installation

### Requirements

- Android 10 or newer;
- a compatible Chinese-market BYD vehicle and DiLink system;
- permission to install APK files on the tablet;
- the `Navigation fusion` option enabled in the vehicle HUD settings for native maneuver arrows;
- BYD HUD excluded from BYD background-app blocking.

### First setup

1. Download the current APK from [GitHub Releases](https://github.com/sunlixWhyNotAvailable/byd-hud/releases/latest).
2. Install it on the vehicle tablet and open BYD HUD.
3. On the first launch, review the `Options` tab and grant the required base permissions.
4. Optionally authorize the ADB RSA prompt for dashboard control, public log storage, automatic permission repair, and richer diagnostics.
5. Open BYD system settings and set `Disable background Apps -> BYD HUD` to `Off`.
6. Return to `Apps`, enable `HUD` for Google Maps or Waze, and start navigation.
7. For a compatible navigator, the direct channel starts automatically. A legacy input starts only if its capture settings, permissions, and services were enabled separately. The Waze screen-capture channel is off by default and no longer supported by the developer.

### What requires ADB

| Function | Without ADB | With authorized ADB |
| --- | --- | --- |
| Compatible direct navigator -> SOME/IP -> HUD | Works | Works |
| App UI and navigation options | Works | Works |
| Automatic permission repair | Limited | Available |
| Public day-based log storage | Limited by Android permissions | Available |
| Dashboard projection controls | Unavailable on tested firmware | Available |
| Extended configuration diagnostics | Basic report | Enriched report |
| Runtime recovery after the process is killed | May require reopening the app | Boot/runtime setup can restore it |

## Troubleshooting

### HUD stays in `waiting`

- Confirm that `HUD` is enabled for the correct navigator.
- Start an active route; selecting a navigator alone does not produce HUD data.
- Check whether the navigator is using a direct or fallback channel.
- Confirm that `Navigation fusion` is enabled in the vehicle HUD settings if only the native arrow is missing.

### HUD shows `failed`

- Open `Options` and run `ADB permissions` if ADB is available.
- Check the `ADB` and `Permissions` status pills separately.
- Confirm that BYD HUD is allowed to run in the background.

### Output blinks or alternates

Another application may be writing to the same SOME/IP HUD channel. Stop the other HUD application and test again.

### Optional Waze legacy output is delayed or incorrect

The unsupported screen-capture parser depends on Waze layout, resolution, language, and temporary rows such as `No GPS`. Enable detailed diagnostics for one reproducible trip and share that day.

### Optional Google Maps legacy output has no lanes

This is expected. Accessibility and notification fallback do not provide lane guidance. Use a compatible direct build when structured lanes or complete route metrics are required.

## Report a problem

Open a [GitHub issue](https://github.com/sunlixWhyNotAvailable/byd-hud/issues) and include:

- BYD HUD version;
- vehicle model, model year, DiLink version, and tablet firmware;
- navigator name, version, and whether it used direct or fallback output;
- what you expected and what appeared on the tablet and HUD;
- the approximate local time of the problem;
- the selected day archive, configuration archive, or Sentry service event/trace identifier when relevant.

For a navigation-output problem:

1. Reproduce it with `Save diagnostic screenshots and extended logs` enabled when possible.
2. Open `Storage` and select only the affected day.
3. Press `Share selected` and choose your messenger or `Send to developer`.
4. Disable detailed diagnostics after the test to reduce storage use.

For a permission, device, patcher, or startup problem, use `Logs -> Share configuration` instead.

> Navigation archives may contain coordinates, searched destinations, street names, notification text, screenshots, and device identifiers. Review the warning before sharing and use only the minimum required day.

## Known limitations

- Other applications using the same SOME/IP output can cause HUD blinking or instability.
- Legacy parsing is off until its settings and capture services are enabled, and then depends on the navigator UI, language, notification structure, and screen resolution.
- Google Maps legacy input has no lane guidance and may provide incomplete maneuver data.
- Waze screen-capture output is off by default and no longer supported by the developer.
- The patcher supports Google Maps ReVanced package `app.revanced.android.apps.maps`, not official package `com.google.android.apps.maps`.
- Project-patched Waze `5.20.0.1` cluster direct output provides ETA and remaining distance only to the next destination; remaining travel time and whole-route metrics still depend on another Waze session exposing them.
- Waze alerts still depend on Waze supplying an alert; the built-in alert hook is available only for the supported Waze 5.20.0.1 structure.
- Waze custom surface is an opt-in beta path. It requires a working Waze direct channel and is activated only after Waze reports active navigation; readiness failure keeps the normal direct guidance active after five seconds.
- Stock cluster firmware may add dark overlays to dashboard projection.
- Unsupported split-package topologies and XAPK files with OBB data cannot be patched.
- Native arrows require `Navigation fusion` in the vehicle HUD settings.
- Some firmware may kill the process without ADB-assisted background and boot setup.

## Contributions and acknowledgements

Issues and focused pull requests are welcome. Please describe the vehicle, firmware, navigator version, and a reproducible user-visible problem before proposing navigator-specific parsing changes.

- MaxTitan shared the original direct-channel idea and reference source material.
- The dashboard resize approach was inspired by [BYD Mate](https://github.com/AndyShaman/BYDMate).
- Additional Waze maneuver and lane glyphs used by the fallback parser were created specifically for this project.

## Tested devices

| Vehicle | Market | DiLink | Status |
| --- | --- | --- | --- |
| BYD Sea Lion 07 EV 2025 | China | 5.0 | Tested by the maintainer |
| BYD Sea Lion 07 EV 2024 | China | 5.0 | Tested by a project user |

HUD output is known to work on the tested tablet firmware starting from version `2510`. Other models, regions, firmware versions, resolutions, and instrument clusters may behave differently.

## License and disclaimer

BYD HUD is licensed under the [GNU Affero General Public License v3.0](LICENSE).

This project is independent and is not affiliated with, endorsed by, or sponsored by BYD, DiLink, Waze, Google, Google Maps, or ABRP. All product names and trademarks belong to their respective owners.

Use the software at your own risk. Modifying or replacing navigator packages can remove local app data or cause unexpected behavior. Follow local laws, keep your attention on the road, and configure or diagnose the system only while parked.
