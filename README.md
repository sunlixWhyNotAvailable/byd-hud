# BYD HUD

> This is personal side project for own purposes
>
> HUD navigation app for Chinise BYD cars on DiLink: captures navigation data and projects maneuver, distance, street, and lane guidance to the vehicle HUD
>
> AI was used to analize and write the code

## Supported Navigation Apps

- Google Maps
- Waze

## Features

- HUD output for navigation maneuver, distance, road name, ETA, and remaining distance
- Google Maps foreground and notification-based route detection
- Waze accessibility and visual crop parsing
- Waze lane guidance support where available
- Background runtime service with watchdog recovery

## Installation

Download and install the latest APK from GitHub Releases
After first launch:
1. Open BYD HUD.	
2. Grant ADB access when Android shows the RSA authorization prompt.
3. Set `Disable background Apps -> BYD HUD = OFF` in the BYD system settings.
4. Optionally change prefered navigation settings.
5. Choose supported navigation app from the list of supported apps and toogle "HUD"
6. Optionally "Send to dashboard" (WIP)

## Known Limitations

Navigation parsing depends on the UI and notification structure of third-party apps.
Google Maps notification mode may provide text route data but not full maneuver or lane graphics.
Waze visual parsing may require template updates when Waze changes its UI. Heavily dependent on the screen capture

## Tested

Tested on *Chinese version* of `BYD Sea Lion 07 EV 2025`, `DiLink 5.0`

## TO DO

- add dashboard HUD output support (prio!)
- add base abrp support. as of now abrp is the same as waze: no notification, only accessibility with no lanes/maneuvers (won't go crop path again). potentially explore SDK maybe something usefull here
