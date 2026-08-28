# Persistent foothold (uid-1000 + app) — DBS7 Ultra Max

Companion note to `FACTORY_USB_EXEC_CHAIN.md`: how to keep an execution
foothold without root.

## Model (verified live)

1. **Trigger**: `MEDIA_MOUNTED` → `com.dangs.factorytest/FactoryUsbInjectReceiver`
   (exported, priority MAX). Fires at every boot with the stick inserted, and
   also on `sm unmount`/`sm mount` from adb — no physical touch needed.
2. **USB config file**: `dangs_factory_test_dbs7ultramaxe` with line
   `shell=pwn.sh`.
3. **pwn.sh** runs as **uid 1000 (system)** — confirmed:
   `uid=1000(system) gid=1000(system) ... context=u:r:zygote:s0`.
4. pwn.sh runs `pm install -r -t /storage/<vol>/pwn_app.apk` — **works** as
   system (AVC denials on FUSE reads are cosmetic; SELinux is permissive).
   Installed app in the verified run: `com.factory.test`.
5. **PwnPersist.apk** (debug-signed):
   - activity `.Pwn` + receiver `.BootRcv` (`BOOT_COMPLETED`)
   - reads `/sdcard/pwn_cmd.txt`; format: `exec:<sh command>`
   - executes `sh -c <cmd>`, logs + writes output to /sdcard
   - alive marker: `/sdcard/Android/data/com.factory.test/files/alive.txt`

## Iterating without physical access

```bash
adb push pwn_cmd.txt /sdcard/pwn_cmd.txt   # e.g. exec:id > /sdcard/out.txt
adb shell "sm unmount public:8,1; sleep 2; sm mount public:8,1"   # or reboot
adb shell cat /sdcard/out.txt
# or trigger directly: adb shell am start -n com.factory.test/.Pwn
```

## Boundaries (what does NOT work from uid 1000 / app)

- `/data/vendor/3rd_rw/upgrade/` (`root:root 775`): write **denied** even for
  uid 1000
- `fifo_am_write` (`root:100 660`): write denied for gid 1000 (≠100);
  unlink+recreate works (parent dir 777) but the FIFO is only an
  input-event injection channel
- no `su` binary; SELinux is permissive but DAC still rules; child processes
  of uid-1000 get no extra capabilities
- the launcher app runs as uid 10018, not 1000

## Possible next steps

- **A.** System-app `pm install` persistence → install a launcher replacement
  without root (but **not** priv-apps: those need the DBOS platform cert).
- **B.** Privilege escalation: `NAV_UPDATER` scans
  `/data/vendor/3rd_rw/upgrade/` **as root** at every boot waiting for
  `upgrade.pkg` — if a file could be planted there, root would follow. Write
  access is the missing piece.
- **C.** Other root services reading configs from gid-1000-writable paths.
