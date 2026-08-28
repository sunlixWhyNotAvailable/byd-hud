# Third-party artwork

## Dashboard mode pictures

The four `app/src/main/res/drawable/ic_widget_*.xml` files are static vector
snapshots of frame 24 from BYD's stock LauncherMap artwork, approved as the
widget pictures in the preview. They are not project-authored artwork, and
their original ownership is unchanged. This notice does not relicense them
under the project's source-code license.

Source capture, relative to the parent workspace:
`references/byd/system-apps/bydlaunchermap/capture-2026-06-06/BydLaunchermap.apk`.
SHA-256: `AC729DEAD6B6D30D3836DF0D9C993B6A140161D9EC4DE75EB07C3C645C6341AB`.

| Widget resource | Source entry under `res/raw/` |
| --- | --- |
| `ic_widget_ipc_off.xml` | `icon_dark_public_3_projection_screen_navi.json` (`meter_close_drak`) |
| `ic_widget_tbt.xml` | `icon_dark_public_simple_navi.json` |
| `ic_widget_mini.xml` | `icon_dark_public_small_screen_navi.json` |
| `ic_widget_full.xml` | `icon_dark_public_full_screen_navi.json` |

Only static paths and final transforms are retained, with centered padding.
No animation runtime or executable code from the stock map is included.
