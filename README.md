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
- `Waze` accessibility and visual crop parsing
- `Waze` lane guidance support where available
- background runtime service with watchdog recovery
- storage management

## Installation

Download and install the latest APK from GitHub Releases

After first launch:
1. Open BYD HUD.	
2. Grant ADB access when Android shows the RSA authorization prompt.
3. Set `Disable background Apps -> BYD HUD = OFF` in the BYD system settings.
4. Optionally change prefered navigation settings.
5. Choose supported navigation app from the list of supported apps and toogle `HUD`
6. Optionally use `Send to dashboard`.

## Known Limitations

Navigation parsing depends on the UI and notification structure of navigator apps.
`Google Maps` accessibility mode doensn't provide lane guidance.
`Google Maps` notification mode may provide text route data but no maneuvers or lane guidance.
`Waze` visual parsing may require template updates when Waze changes its UI elements. Heavily dependent on the screen capture and resolution
`Waze` offers vast range of maneuvers/lanes glyphs which are not standard for base car navigation. Therefore all new lanes/maneuvers are created using `GIMP`

## Tested

Tested on *Chinese version* of `BYD Sea Lion 07 EV 2025`, `DiLink 5.0`
If there are missing glyphs or inconsistency in glyph outputs, archive of relevant sessions for the day (paths are shown in the app) needs to be sent analysis

## Limitations and troubleshooting

Waze maneuver/lanes parsing might break on different tablet resolution and depend on maneuvers position on the screen (meaning `No GPS` additional row at the top can break HUD parsing). 
If there are missing glyphs or incorrect HUD outputs, please open an `Issue` and attach the relevant session archive for that day. Session paths are shown inside the app.

## To Do

- add dashboard HUD output support for Waze
- add basic `ABRP` support. As of now `ABRP` is the same as `Waze`: no notification, only accessibility with no lanes/maneuvers (won't go crop path again). Potentially explore SDK maybe something usefull here

## DISCLAIMER

License: Apache License 2.0

This project is an independent personal/community project and is not affiliated with, endorsed by, or sponsored by BYD, DiLink, Waze, Google, Google Maps or ABRP.
BYD, DiLink, Waze, Google Maps, ABRP and related names or logos are trademarks of their respective owners.
Use this software at your own risk.
