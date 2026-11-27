# MainUI internals

What the companion app relies on, read from the decompiled `com.ts.MainUI` **1.0.75**
(`/system/app/MainUI/MainUI.apk`). Other MainUI versions may number things differently: check before you
rely on a number, and send a pull request when yours differ.

## Outside temperature

MainUI writes the CAN outside temperature into a system property, and the (hidden) status bar shows it:

```sh
adb shell getprop forfan.user.info      # 14,5℃
```

Any app can read it, e.g. with `android.os.SystemProperties.get("forfan.user.info")` through reflection. The
shell can also `setprop` it, which is handy for testing a widget.

## Binder interface

Bind to the MainUI service with action `android.intent.action.MAIN_UI`, package `com.ts.MainUI`. The binder
speaks `com.ts.main.common.ITsCommon`. No permission is required. There is no AIDL file to import: write the
interface token, the arguments and read the reply by hand (see `call()` in
[`SpeedService.java`](../app/src/it/radio/velocita/SpeedService.java)).

| Transaction | Method | Returns / notes |
|---|---|---|
| 6 | `SendMcuKey(int key)` | Presses a head unit key. 43 = radio band, 44 = next, 45 = previous, 60 = play/pause (media) |
| 10 | `nGetWorkMode()` | Active source: 0 none, 1 radio, 2 disc, 3 USB, 4 SD, 5 Bluetooth audio (A2DP) |
| 23 | `GetSpeed()` | `float`, km/h from GPS |
| 24 | `GetTemp()` | Not used here: the property above is simpler |
| 33 | `GetBand()` | `"FM"` or `"AM"` |
| 34 | `GetFreq()` | `"98,70"` (FM, MHz, with the locale's decimal separator) or `"1008"` (AM, kHz) |
| 36 | `EnterActivity(int)` | -1 opens the screen of the active source |
| 55 | `getSpecialBinder(String)` | `"Radio"` returns the radio binder below |

### Radio binder

`getSpecialBinder("Radio")` returns a binder that speaks `com.ts.main.common.ITsRadioCommon`:

| Transaction | Method | Returns / notes |
|---|---|---|
| 30 | `Radio_GetPsName()` | RDS station name, 15 characters padded with spaces or NULs; blank without RDS |
| 1 / 2 | `GetBand()` / `GetFreq()` | Same as above |

## Activities

| Screen | Component |
|---|---|
| Radio | `com.ts.MainUI/com.ts.main.radio.RadioMainActivity` |
| Bluetooth music | `com.ts.MainUI/com.ts.bt.BtMusicActivity` |
| Car settings | `com.ts.MainUI/com.ts.set.SetMainActivity` |

The settings pages and the factory menu are not exported: open them from the car settings.

## Factory menu

> [!CAUTION]
> The keypad that opens the factory menu also accepts codes that MainUI names `custom_destroy_system_num`
> (`4444`), `custom_destroy_id_num` (`9090`) and `custom_destroy_id_root_num` (`9191`). Never type them: going by
> their names, they wipe the system or the unit's ID. Type only the code below, and write down every value before
> you change it.

1. Open the car settings and scroll to the last entry, **About Device** (Italian: **Dispositivo**).
2. Tap the first row, the one with the version: a keypad opens.
3. Type **`8878`** (the string resource `custom_fatctory_num` in MainUI).

Useful pages:

| Page | Setting | Values |
|---|---|---|
| Radio | Zone (tuner region) | 0 Asia, 1 Europe, 2 USA1, 3 USA2, 4 Brazil, 5 Japan, 6 Brazil2, 7 Japan2, 8 Brazil3, 9 Australia |
| Radio | FM/AM sensitivity, band limits, RDS | |
| Others | AMP | The REM output, same as "Ext Amp" in Settings > Sound. It switches external amplifiers or a motorized antenna, not antenna power |

After changing the radio zone, scan the stations again.
