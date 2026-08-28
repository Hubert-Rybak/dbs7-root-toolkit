# Root vector: hd_cmd_service hijack

All steps verified live on DBS7 Ultra Max (DBOS 14, MT5877), 2026-08-27.

## The chain

1. **HAL binder service**: `vendor.mediatek.tv.mtktvfactory.IMtkTvFApiSystem/default`
   (runs as SYSTEM uid). SELinux is **permissive** on factory firmware → **any
   caller** is accepted, including shell (uid 2000) via `service call` and
   regular apps via `Runtime.exec`.
2. **IMtkTvFApiSystem** AIDL (35 methods) — the interesting file primitives:
   - `#1  change_file_mode(String path, int mode)` — chmod as SYSTEM uid.
     **The mode is parsed DECIMAL** (`644` decimal ≠ `0644` octal — pass 420
     for 0644, 448 for 0700, 438 for 0666)
   - `#2  check_file(String)` / `#3  check_folder(String)` — exists probe
     (binder reply `00000000` = exists)
   - `#5  create_file(String)` / `#6  create_folder(String)`
   - `#13 remove_file(String)`
   - `#17 write_file(String path, String content, bool append, bool ?)` —
     full-file write or append; **multiline content works** in one call
   - `#27 read_emmc` / `#33 write_emmc` — raw eMMC (not needed here)
   - `#15 send_cmd_to_factory_svc(int, String)` — needs a live `factory_svc`
     (abstract socket `@FACTORY_SERVER`); on this firmware factory_svc is
     `stopped` because its binary lacks +x and cannot be chmod'ed by SYSTEM
     either — dead end, documented for completeness
3. **The target**: init service `hd_cmd_server`
   (`/vendor/bin/hd_deamon_cmd_server.sh`, `class main`, **user root**) does:

   ```sh
   FILE_NEW_CMDSERV=/data/system/hd_cmd_service
   if [ -f "$FILE_NEW_CMDSERV" ]; then chmod 700 $FILE_NEW_CMDSERV; $FILE_NEW_CMDSERV; else $FILE_CMDSERV; fi
   ```

   → **at every boot (and every service restart), init executes
   `/data/system/hd_cmd_service as root`** if that file exists.

## Exploit flow (as implemented in the toolkit)

1. Write the payload daemon to `/data/system/hd_cmd_service` via HAL
   `tx#5 create_file` + `tx#17 write_file` (system:system 0600 initially).
2. Fix the mode with `tx#1` (init chmods it to 0700 anyway before exec).
3. Pre-create `/data/local/tmp/root_cmd.txt` + `root_out.txt` with `0666`
   (decimal 438) so the adb-shell user can read/write the queue later.
4. Reboot. `hd_cmd_server` execs the payload **as root**
   (`uid=0 ... context=u:r:su:s0` — verified via `id > hdpwn.txt` proof file).
5. The payload is a **while-loop daemon**: consume line 1 of
   `root_cmd.txt` → `sh -c` it as root → append output to `root_out.txt`,
   sleep 5, repeat. `hd_cmd_server` has no `oneshot`, so init keeps restarting
   it (with backoff) — the queue keeps being served.

## Persistence & rollback

- The planted file **is** the persistence: init re-executes it at every boot.
- Rollback: `service call vendor.mediatek.tv.mtktvfactory.IMtkTvFApiSystem/default
  13 s16 '/data/system/hd_cmd_service'` (tx#13 remove_file), or the
  *Remove root service* button in the toolkit.
- Nothing on read-only partitions is touched.

## Operational notes

- `/data` is mounted `nosuid` → SUID-shell escalation does **not** work; the
  daemon/queue design is the way to run root commands.
- `hd_cmd_server` init restart uses backoff — a freshly killed daemon can take
  up to ~90 s to come back.
- The daemon also enforces `persist.adb.tcp.port=5555` + adbd restart at every
  boot (remote ADB), and re-asserts its bind-mounts (EN patch, GMS shadow).
