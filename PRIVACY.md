# Privacy

**English** | [Українська](PRIVACY.uk.md)

BYD HUD does not automatically send crash reports, analytics, navigation data, screenshots, logs, or usage telemetry.

## Navigation log upload

Navigation logs are uploaded only when the user selects one or more stored days, presses Share, reviews the warning, chooses `Send to developer`, and confirms `OK` in the optional-comment dialog.

An empty or whitespace-only comment is omitted. Otherwise, the full trimmed comment is included in the Sentry event's additional data, and a shortened single-line form is used in its title. The comment is not added to the ZIP or technical logs. Without a comment, the event title contains the app version and the local date/time of confirmation.

The uploaded ZIP is the same complete archive offered through Android's normal share chooser. Depending on enabled diagnostics, it can contain:

- exact coordinates, routes, streets, navigation instructions, and search text;
- Waze screenshots and direct-channel maneuver, lane, or alert images;
- BYD HUD event, navigation, SOME/IP transmission, and recorded full-system logcat files;
- Shanghai test evidence: UDP network packet captures, raw received SOME/IP messages, ADAS readings, and before/after system snapshots of location services, network interfaces and routes, and running processes;
- data from both public and app-private log storage for the selected days.

Shanghai evidence is included with its selected storage day. Location snapshots may contain the vehicle's real last-known coordinates in addition to the simulated route; network snapshots and packet captures may contain IP and MAC addresses and other network identifiers. These diagnostic files are not covered by the network-identifier masking described below for the separate vehicle configuration archive.

Each explicit upload uses its own Sentry client, sends one event with one ZIP attachment, waits for that transfer's result, and closes its client. Another upload can start after the 30-second admission cooldown while an earlier transfer continues. Automatic crash, ANR, session, breadcrumb, tracing, profiling, screenshot, view-hierarchy, and replay collection are disabled. Failed uploads are not retried in the background.

Selected source logs are not deleted after an upload. The temporary ZIP is deleted when Sentry delivery succeeds, fails or is interrupted. A ZIP at or above 39,000,000 bytes is never submitted to Sentry: the user can share that same file through another app, or cancel to delete it immediately. Files passed to Android's share chooser follow the existing local share-cache cleanup.

## Android share chooser

Choosing `Another app` sends the ZIP only to the Android application selected by the user. The selected recipient's own privacy policy then applies.

## Vehicle configuration archive

`Export configuration` is a separate user-requested export. It includes available device, package, permission, process, display, audio, network, HUD/dashboard and SOME/IP diagnostics, a BYD feature-ID catalog, and supported read-only vehicle values. It never changes vehicle modes, repairs permissions, or requests ADB authorization solely for this export. Existing authorization enables more readings and file access.

The archive also includes diagnostically relevant stock APKs and their splits, native libraries and dependencies, framework implementations, dashboard executables and selected configuration, including complete CarSettingsPlugins. Unrelated apps, private app data, accounts, navigation routes and recordings are not read. In text diagnostics and configuration, network identifiers are replaced with stable per-archive aliases and sensitive values are redacted. Binary firmware files are copied unchanged for analysis and may contain vendor-embedded data; no binary-level anonymization is promised. Share the archive only with a trusted recipient.

After reviewing the warning, the user creates the archive and opens Android's share chooser with `Share`. Configuration exports never use Sentry. A large ZIP is split into volumes of up to 1 GB and shared as multiple attachments; a single file uses the ordinary single-attachment action. Nothing is sent automatically and opening a chooser is not treated as delivery confirmation.

The temporary files expire 15 minutes after archive creation finishes. Their absolute creation and expiry timestamps are stored outside the ZIP, survive process restarts, and are never renewed by sharing, retrying or closing the card. Cleanup runs independently of HUD. Overdue archives left while the app or car was off are removed on the next allowed execution; older owned exports are included using their last-write timestamp. Closing the card hides it without immediate deletion. Cancelling collection removes that operation's unfinished files. A recipient that has not opened a queued volume before expiry may no longer be able to read it.
