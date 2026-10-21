package dev.syed.tvpilot.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Turning the mic button service on: by itself when it may write settings, or once through the TV's own ADB. */
final class Setup {
    private Setup() {}

    static final String PKG = "dev.syed.tvpilot.tv";
    static final String COMPONENT = PKG + "/" + PKG + ".PilotService";
    private static final String SHORT = PKG + "/.PilotService";

    static boolean serviceEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        for (String part : s.split(":")) if (part.equalsIgnoreCase(COMPONENT) || part.equalsIgnoreCase(SHORT)) return true;
        return false;
    }

    static boolean canWriteSettings(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Needs WRITE_SECURE_SETTINGS. */
    static boolean enableService(Context c) {
        if (serviceEnabled(c)) return true;
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String v = s == null || s.trim().isEmpty() ? COMPONENT : s + ":" + COMPONENT;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, v);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.w("mic button service switched on");
            return true;
        } catch (SecurityException e) {
            Log.w("couldn't switch the service on: " + e.getMessage());
            return false;
        }
    }

    /** Through the TV's ADB (127.0.0.1:5555): grants the mic and the settings permission, then switches the service on. */
    static String viaAdb(Context c, Adb.Approval approval) {
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            adb.shell("pm grant " + PKG + " android.permission.RECORD_AUDIO");
            adb.shell("pm grant " + PKG + " android.permission.WRITE_SECURE_SETTINGS");
            adb.shell("appops set " + PKG + " ACCESS_RESTRICTED_SETTINGS allow");
            if (!enableService(c)) {
                String cur = adb.shell("settings get secure enabled_accessibility_services").trim();
                String v = cur.isEmpty() || "null".equals(cur) ? COMPONENT : cur + ":" + COMPONENT;
                adb.shell("settings put secure enabled_accessibility_services " + v);
                adb.shell("settings put secure accessibility_enabled 1");
            }
            return null;
        } catch (Exception e) {
            Log.w("ADB setup: " + e.getMessage());
            return e.getMessage();
        }
    }

    static final String KATNISS = "com.google.android.katniss";
    static final String ASSIST_COMPONENT = PKG + "/" + PKG + ".AssistActivity";

    /** null: Google's TV voice search isn't installed. */
    static Boolean googleVoiceOn(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(KATNISS, 0).enabled;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isTvAssistant(Context c) {
        String a = Settings.Secure.getString(c.getContentResolver(), "assistant");
        return a != null && a.startsWith(PKG + "/");
    }

    /**
     * The clean route: TV Pilot becomes the TV's assistant (the Assistant role + the "assistant" setting), so the
     * system hands it the mic button itself. Optionally Google's voice search is switched off so it can't grab the
     * button first (undo: the same row again; voice search inside other apps needs it on).
     */
    static String makeAssistant(Context c, boolean googleOff, Adb.Approval approval) {
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            String out = adb.shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT " + PKG + " 2>&1; "
                    + "settings put secure assistant " + ASSIST_COMPONENT + "; "
                    + "settings get secure assistant");
            Log.w("assistant setup: " + out.trim().replace('\n', ' '));
            if (googleOff) Log.w("Google voice: " + adb.shell("pm disable-user --user 0 " + KATNISS + " 2>&1").trim());
            return null;
        } catch (Exception e) {
            Log.w("assistant setup: " + e.getMessage());
            return e.getMessage();
        }
    }

    /** Apps that must never be switched off by "Free the mic". */
    private static boolean core(String pkg) {
        return pkg.equals("android") || pkg.equals(PKG) || pkg.startsWith("com.android.") || pkg.contains("audioserver")
                || pkg.equals("com.google.android.tvlauncher") || pkg.contains("bluetooth")
                || pkg.startsWith("com.tcl.") || pkg.startsWith("com.sony.") || pkg.startsWith("com.mediatek.") || pkg.startsWith("com.google.android.apps.tv");
    }

    /** Last apps found holding the mic (from the audio system, which apps themselves can't see). */
    private static final java.util.Set<String> protectedPkgs = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static volatile java.util.List<String> holders = new java.util.ArrayList<>();

    /** Asks the audio system, over the TV's own debugging, which apps have recordings open (hidden ones too). */
    static String findMicHolders(Context c, Adb.Approval approval) {
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            String pol = adb.shell("dumpsys media.audio_policy 2>&1 | grep -iE 'uid|source|silenced|active|client' | head -80");
            String aud = adb.shell("dumpsys audio 2>&1 | grep -iE -A14 'record' | head -60");
            String pkgs = adb.shell("pm list packages -U 2>/dev/null");
            // Only ever user-installed apps: system and vendor packages (TCL's launcher, TV input...) are never touched.
            java.util.Set<String> thirdParty = new java.util.HashSet<>();
            // Never the home screen (FreeMyTV, Google TV...), the keyboard or an accessibility service.
            for (String l : adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null; settings get secure default_input_method; settings get secure enabled_accessibility_services").split("[\\n:/]"))
                protectedPkgs.add(l.trim());
            for (String l : adb.shell("pm list packages -3 2>/dev/null").split("\n")) if (l.startsWith("package:")) thirdParty.add(l.substring(8).trim());
            Log.w("audio policy: " + pol.trim().replace('\n', ' ').substring(0, Math.min(600, pol.trim().length())));
            Log.w("audio recs: " + aud.trim().replace('\n', ' ').substring(0, Math.min(400, aud.trim().length())));
            java.util.Map<String, java.util.List<String>> byUid = new java.util.HashMap<>();
            for (String line : pkgs.split("\n")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("package:(\\S+) uid:(\\d+)").matcher(line.trim());
                if (m.find()) {
                    java.util.List<String> l = byUid.get(m.group(2));
                    if (l == null) byUid.put(m.group(2), l = new java.util.ArrayList<>());
                    l.add(m.group(1));
                }
            }
            java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)uid[:= ]+(\\d{4,6})").matcher(pol + "\n" + aud);
            while (m.find()) {
                java.util.List<String> l = byUid.get(m.group(1));
                // Shared system UIDs (below 10000) cover dozens of system apps; never guess from those.
                if (l != null && Integer.parseInt(m.group(1)) >= 10000 && l.size() == 1)
                    for (String p : l) if (!core(p) && thirdParty.contains(p) && !protectedPkgs.contains(p)) found.add(p);
            }
            m = java.util.regex.Pattern.compile("(?i)pack(?:age)?(?:name)?[:= ]+([a-z][\\w.]+\\.[\\w.]+)").matcher(pol + "\n" + aud);
            // (Package names in the dumps also list apps that only PLAY audio, so they're no evidence of recording.)
            holders = new java.util.ArrayList<>(found);
            Log.w("mic holders: " + (found.isEmpty() ? "none found" : found));
            return null;
        } catch (Exception e) {
            Log.w("mic holders: " + e.getMessage());
            return e.getMessage();
        }
    }

    /** Switches off the apps found holding the mic (undo: pm enable, or "Google voice search app" for Google). */
    static String freeMic(Context c, Adb.Approval approval) {
        java.util.List<String> h = holders;
        if (h.isEmpty()) return "nothing found to switch off";
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            for (String p : h) {
                // Only ever stopped, never disabled: it starts again normally next time it's opened or the TV restarts.
                Log.w("stopping " + p + ": " + adb.shell("am force-stop " + p + " 2>&1").trim());
            }
            holders = new java.util.ArrayList<>();
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Turns back on everything "Free the mic" switched off. */
    static String restoreMic(Context c, Adb.Approval approval) {
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            for (String p : Prefs.disabled()) Log.w("switching on " + p + ": " + adb.shell("pm enable " + p + " 2>&1").trim());
            Prefs.clearDisabled();
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static String setGoogleVoice(Context c, boolean on, Adb.Approval approval) {
        try (Adb adb = Adb.connect(c, 40_000, approval)) {
            String out = adb.shell((on ? "pm enable " : "pm disable-user --user 0 ") + KATNISS + " 2>&1").trim();
            Log.w("Google voice " + (on ? "on" : "off") + ": " + out);
            return null;
        } catch (Exception e) {
            Log.w("Google voice: " + e.getMessage());
            return e.getMessage();
        }
    }
}
