# Privacy

**English** | [Українська](PRIVACY.uk.md)

BYD HUD does not automatically send crash reports, analytics, navigation data, screenshots, logs, or usage telemetry.

## Navigation log upload

Navigation logs are uploaded only when the user selects one or more stored days, presses Share, reviews the warning, and chooses `Send to developer`.

The uploaded ZIP is the same complete archive offered through Android's normal share chooser. Depending on enabled diagnostics, it can contain:

- exact coordinates, routes, streets, navigation instructions, and search text;
- Waze screenshots and direct-channel maneuver, lane, or alert images;
- BYD HUD event, navigation, SOME/IP transmission, and recorded full-system logcat files;
- data from both public and app-private log storage for the selected days.

The app initializes the Sentry service SDK only for that explicit upload, sends one event with one ZIP attachment, waits for the transfer, and closes the SDK. Automatic crash, ANR, session, breadcrumb, tracing, profiling, screenshot, view-hierarchy, and replay collection are disabled. Failed uploads are not retried in the background.

Selected source logs are not deleted after an upload. The temporary ZIP is deleted after successful delivery; failed delivery may retain it in the local share cache.

## Android share chooser

Choosing `Another app` sends the ZIP only to the Android application selected by the user. The selected recipient's own privacy policy then applies.

## Vehicle configuration archive

`Export configuration` is a separate user-requested export. It includes available device, package, permission, process, display, audio, network, HUD/dashboard and SOME/IP diagnostics, a BYD feature-ID catalog, and supported read-only vehicle values. It never changes vehicle modes, repairs permissions, or requests ADB authorization solely for this export. Existing authorization enables more readings and file access.

The archive also includes diagnostically relevant stock APKs and their splits, native libraries and dependencies, framework implementations, dashboard executables/resources and configuration. Unrelated apps, private app data, accounts, navigation routes and recordings are not read. In text diagnostics and configuration, network identifiers are replaced with stable per-archive aliases and sensitive values are redacted. Binary firmware files are copied unchanged for analysis and may contain vendor-embedded data; no binary-level anonymization is promised. Share the archive only with a trusted recipient.

After reviewing the warning, the user chooses either `Another app` or `Send to developer`. The first option uses Android's normal share chooser. The second explicitly sends the configuration ZIP through the same telemetry-disabled Sentry service workflow described above. Archives exceeding its 20-MiB limit are not uploaded, split or truncated: they remain available through Android sharing. A failed upload retains the ready archive for another recipient; successful delivery removes the temporary ZIP. No failed upload is retried automatically. Cancelling collection removes that operation's unfinished files.
