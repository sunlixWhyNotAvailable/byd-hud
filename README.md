# BYD HUD

> This is personal side project for own purposes
>
> HUD navigation app for Chinise BYD cars on DiLink: captures navigation data and projects maneuver, distance, street, and lane guidance to the vehicle HUD
>
> Most of the lanes and maneuvers which were not originally supported by by base nav app but used in Waze app were manually created for this project
>
> AI was used to analize logs and write code implementation

## Supported Navigation Apps

- Google Maps
- Waze

## Features

- HUD output for navigation maneuver, distance, road name, ETA, and remaining distance
- `Google Maps` foreground and notification-based route detection
- `Waze` accessibility and visual crop maneuvers parsing
- `Waze` lanes crop parsing
- background runtime service with watchdog recovery
- storage management
- built-in stable and opt-in beta update channels

## Installation

Download and install the latest APK from GitHub Releases

After first launch:
1. Open BYD HUD.	
2. Grant ADB access when Android shows the RSA authorization prompt.
3. Set `Disable background Apps -> BYD HUD = OFF` in the BYD system settings.
4. Optionally change prefered navigation settings.
5. Choose supported navigation app from the list of supported apps and toogle `HUD`.
6. If `Waze` is chosen then press `Start now` when `Casting with BYD HUD` prompt appears (need to do this every session).
7. Optionally use `Send to dashboard`.
8. If you plan in helping debugging maneuvers/lanes parser - turn on `Save diagnostic screenshots and extended logs` to start saving screenshots for analysis (be aware - it can clog internal storage).
9. Optionally take part in beta-testing (warning: broken builds are to be expected).
   
## Known Limitations

- Other apps utilising the same SOME/IP channel for HUD projection may cause HUD blinking and instability.
- Navigation parsing depends on the UI and notification structure of navigator apps.
- `Google Maps` accessibility mode doensn't provide lane guidance.
- `Google Maps` notification mode may provide text route data but rarely or no maneuvers and no lane guidance.
- `Waze` visual parsing may require template updates when Waze changes its UI elements. Heavily dependent on the screen capture and resolution.
- `Waze` offers vast range of maneuvers/lanes glyphs which are not standard for base car navigation. Therefore all new lanes/maneuvers are created using `GIMP`.

## Tested

Tested on *Chinese version* of `BYD Sea Lion 07 EV 2025`, `DiLink 5.0`
If there are missing glyphs or inconsistency in glyph outputs, archive of relevant sessions for the day (paths are shown in the app) needs to be sent for analysis

## Known bugs, troubleshooting and other info

- There is up to couple seconds delay between new maneuver on `Waze` screen and HUD output. This is not a bug but a working process of maneuvers parser.
- Waze maneuver/lanes parsing might break on different tablet resolution and depend on maneuvers position on the screen (meaning `No GPS` additional row at the top can break HUD parsing). This was partially addressed by introducing waze navigation bounds (black square in top left corner) parsing. Also should help in case of other resolution.
- `Waze` roundabouts might be broken. Needs additional testing.
- If there are missing glyphs or incorrect HUD outputs, please open an `Issue` and attach the relevant session archive for that day. Session paths are shown inside the app.
- `Google Maps` maneuver parsing depends on the language. English is supported and tested; Ukrainian support requires additional testing.

## Navigators used in development

- Standard `Waze` - used for image parsing (https://drive.google.com/drive/folders/1kVPVqM16AEy1XVtyCwerJ_7iKZmXGt0f?usp=drive_link)
- Patched `Waze` - TBA
- Standard `Google Maps` - used for text extraction (https://drive.google.com/drive/folders/18SuTko75jInBAPNxndgtkk4OPlxiJGHS?usp=drive_link)
- Patched `Google Maps` - TBA

## To Do

- `v1.6.1` is the last stable patch with current `Waze` parser; further work continues on the beta branch
- add dashboard window mode toggle support;
- add basic `ABRP` support. As of now `ABRP` is the same as `Waze`: no notification, only accessibility with no lanes/maneuvers (won't go crop path again). Potentially explore SDK maybe something usefull here.

## DISCLAIMER

License: Apache License 2.0

This project is an independent personal/community project and is not affiliated with, endorsed by, or sponsored by BYD, DiLink, Waze, Google, Google Maps or ABRP.
BYD, DiLink, Waze, Google Maps, ABRP and related names or logos are trademarks of their respective owners.
Use this software at your own risk.
