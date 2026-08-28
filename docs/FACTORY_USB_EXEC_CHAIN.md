# Factory USB exec chain (uid 1000 arbitrary script execution)

Verified working on DBS7 Ultra Max (logs from 2026-08-27 after reboot).
This is a **separate** trigger chain from the HAL vector used by the toolkit —
documented here for completeness.

## The chain

1. `com.dangs.factorytest` (`/vendor/app/Factorytest`, **uid 1000**,
   `sharedUser=android.uid.system`, DBOS platform cert) ships a
   `FactoryUsbInjectReceiver` — **exported=true**, action `MEDIA_MOUNTED`,
   scheme `file://`, priority MAX.
2. The receiver looks for a file named
   `dangs_factory_test_<model lowercase, no spaces><char>` on the USB stick
   (on DBS7 Ultra Max: `dangs_factory_test_dbs7ultramaxe`), or a literal GUID
   filename.
3. The file is processed **line by line**
   (`PreConfigTool.checkConfigAndExecute`):
   - `reboot_press=<sec>` → sleep + reboot
   - `volume=<n>` → setStreamVolume(3, n)
   - `debug=<anything>` → log only
   - `shell=<name>` → startService(ShellService) with extra
     `file=<USB_ROOT>/<name>`
4. `ShellService` copies the file to `/cache/<name>` and runs
   **`sh /cache/<name>` as uid 1000** (Runtime.exec from a system-shared app).
5. After checkConfigAndExecute the receiver opens the factory `MainActivity`
   (the "version mismatch, cannot test" screen — normal on retail units).

## Boot trigger (the important part)

The receiver receives `MEDIA_MOUNTED` **automatically at every boot** when the
USB stick is plugged in **before** power-on: the system mounts the volume
during init and the static receiver with priority MAX catches the event.
Confirmed in the log:

```
09:54:41 [...,1000,com.dangs.factorytest,broadcast,{...FactoryUsbInjectReceiver}]
09:54:44 ContextImpl.startService ... PreConfigTool$1.run:92
```

No re-plugging needed — insert the stick, reboot, the script runs.

## What uid 1000 (system sharedUser) can do

- **DAC**: e.g. `fifo_am_write` is `root:100 660` → group 100 = AID_SYSTEM →
  **writing the RTOS FIFO works**
- `/data/vendor/3rd_rw/upgrade/` (`root:root 775`): read yes, write no
- `INSTALL_PACKAGES` granted (system app) → silent `pm install` of any APK
- no `su` binary on the device

## Dead ends (checked, negative)

- `am broadcast MEDIA_MOUNTED` from adb shell → `SecurityException`
  (protected broadcast)
- `pm install` of a `sharedUserId=android.uid.system` APK via the normal
  installer path → rejected (uid-caller check)
- `app_process` + `bindService` to the launcher's download service →
  "Unable to find app for caller" (no IApplicationThread)

## Probe recipe

- Report through `log -t PWN2` (logcat) — independent of file permissions
  (uid 1000 cannot write into shell-owned `/data/local/tmp` subdirs).
- USB layout (FAT32): `dangs_factory_test_dbs7ultramaxe` containing
  `shell=pwn.sh` plus the `pwn.sh` script in the stick root.
- Insert stick → reboot → read results: `adb shell "logcat -d -s PWN2"`.
