# Privacy

BYD HUD does not automatically send crash reports, analytics, navigation data, screenshots, logs, or usage telemetry.

## Navigation log upload

Navigation logs are uploaded only when the user selects one or more stored days, presses Share, reviews the warning, and chooses `Send to developer`.

The uploaded ZIP is the same complete archive offered through Android's normal share chooser. Depending on enabled diagnostics, it can contain:

- exact coordinates, routes, streets, navigation instructions, and search text;
- Waze screenshots and direct-channel maneuver, lane, or alert images;
- BYD HUD event, navigation, SOME/IP transmission, and recorded full-system logcat files;
- data from both public and app-private log storage for the selected days.

The app initializes the Sentry service SDK only for that explicit upload, sends one event with one ZIP attachment, waits for the transfer, and closes the SDK. Automatic crash, ANR, session, breadcrumb, tracing, profiling, screenshot, view-hierarchy, and replay collection are disabled. Failed uploads are not retried in the background.

Selected source logs are not deleted after an upload. The temporary ZIP is deleted after success or failure.

## Android share chooser

Choosing `Another app` sends the ZIP only to the Android application selected by the user. The selected recipient's own privacy policy then applies.

## Vehicle configuration archive

`Share configuration` is a separate user-requested export. It can include device properties, installed packages and signers, running processes and services, network addresses and routes, SOME/IP configuration, and navigation-related system configuration. Sensitive configuration values such as credentials, VIN, location, and account identifiers are redacted before inclusion. When local ADB is already authorized, the archive can also include a read-only catalog of static BYD SDK feature IDs. It never requests ADB access solely for this export and does not read or write live vehicle feature values.

After reviewing the warning, the user chooses either `Another app` or `Send to developer`. The first option uses Android's normal share chooser. The second explicitly sends the configuration ZIP through the same telemetry-disabled Sentry service workflow described above. The temporary ZIP is deleted after the upload attempt, and failed uploads are not retried in the background.
