# TS / forfan MT6768 in an Alfa Romeo MiTo (2012)

| | |
|---|---|
| Hardware | MediaTek MT6768, 8 cores, 8 GB RAM, 128 GB |
| Screen | 1024x600 panel, rendered at 1280x720 |
| Android | 10 (the firmware reports 13), build `QP1A.190711.020 p5spm8666p164P2`, userdebug, `su` present |
| MainUI | `com.ts.MainUI` 1.0.75 |
| CAN | Fiat/Alfa protocol: outside temperature (speed comes from GPS through MainUI). No battery voltage. |

## What was changed

- 12 packages disabled: [`debloat.txt`](debloat.txt).
- Animations at 0.5x:
  ```sh
  adb shell settings put global window_animation_scale 0.5
  adb shell settings put global transition_animation_scale 0.5
  adb shell settings put global animator_duration_scale 0.5
  ```
- Optional ad blocking with Private DNS:
  ```sh
  adb shell settings put global private_dns_specifier dns.adguard-dns.com
  adb shell settings put global private_dns_mode hostname
  ```
- Home: [Kvaesitso](https://github.com/MM2-0/Kvaesitso), set as the default home with
  `adb shell cmd package set-home-activity de.mm20.launcher2.release/de.mm20.launcher2.ui.launcher.LauncherActivity`.
  The stock launcher is `com.android.launcher/com.android.launcher2.Launcher`; the same command brings it back.
- The companion app from [`app/`](../../app), with the speed/temperature widget.
- Radio region set to **Europ** in the factory menu (Radio > Zone), then a new station scan.

## Notes

- The REM output ("Rem Ampli" in Settings > Sound, "AMP" in the factory menu) switches external amplifiers or a
  motorized antenna. It does not power an active antenna: there is no software setting for that.
- If `adb connect` says "No route to host", the unit is asleep or off Wi-Fi: turn the ignition on.
