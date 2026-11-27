# Contributing

Thanks for helping other people's head units. Two kinds of contributions are most welcome.

## 1. Add your radio

No Git? Open an issue with the **My radio** form and paste the outputs below; someone will turn it into a folder.

With Git, open a pull request that adds one folder, `radios/<firmware>-<soc>-<car>/`, and changes nothing
outside it. Copy [`radios/ts-mt6768-alfa-romeo-mito/`](radios/ts-mt6768-alfa-romeo-mito/) as the template.

1. Collect the facts:
   ```sh
   adb shell getprop ro.board.platform                 # SoC, e.g. mt6768
   adb shell getprop ro.build.display.id               # firmware build
   adb shell getprop ro.build.version.release          # real Android version
   adb shell getprop ro.forfan.version.release         # Android version the firmware claims
   adb shell dumpsys package com.ts.MainUI | grep -m1 versionName
   adb shell pm list packages -d                       # disabled packages
   ```
2. `radio.json`: the short facts, same keys as the template.
3. `debloat.txt`: one package per line, grouped under a comment that says what the group is, with a trailing
   comment on anything non-obvious. Put the build and MainUI version in the header. Only list packages you have
   disabled with `pm disable-user` and lived with for a few days. Note anything that broke and what you
   re-enabled. Never list packages to uninstall, and never disable `com.ts.MainUI`: radio, Bluetooth, CAN and
   car settings all live in it.
4. `README.md`: settings you changed (with the exact commands), quirks, and which companion app features work.
   Leave out personal data: IP addresses, IMEI, serial numbers, location.

## 2. Fix or extend the companion app

- Keep it small: no Gradle, no libraries, no internet permission. `app/build.sh` must still pass its
  self-checks; add one for any new parsing or formatting logic.
- If your MainUI version numbers its binder transactions differently, document the differences in
  [`docs/mainui.md`](docs/mainui.md) together with the MainUI version, before changing the code.
- Test on a real head unit and say which one in the pull request.
- Never commit a signing key (`*.jks`) or an APK.
