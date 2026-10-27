package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * The remote's app buttons inside other apps on TCL's Android 11, where the key service stays
 * off (#325: with a key filter on, the TV acts on a volume key twice). A small script in the TV's shell reads the
 * remote with getevent, which only watches the keys and never holds them back, so the volume and every other key go on
 * as always, and opens the app each button is set to with `am start`. It runs on its own in the shell (TCL freezes
 * our processes while another app is in front), from the moment HomeMode turns Free My TV on, after a boot or a change
 * in Remote Buttons, until Original or the next restart.
 */
final class KeyWatch {
    private KeyWatch() {}

    /** On TCL's Android 11 only: elsewhere the key service does this. */
    static final boolean ON = RemoteKeys.TCL && !RemoteKeys.FILTER_SAFE;
    /** In the script's command line, so it can be found and stopped. */
    private static final String MARK = "freemytv-keywatch";
    private static final Pattern COMPONENT = Pattern.compile("[\\w.]+/[\\w.$]+");
    private static final String HOME = "-a android.intent.action.MAIN -c android.intent.category.HOME";

    /**
     * TCL's Linux key codes for its app buttons, as getevent prints them (Generic.kl: TCL_NETFLIX, TCL_YOUTUBE,
     * TCL_PRIMEVIDEO, TCL_QUICK_MENU for TCL Channel, TCL_GLOBO_PLAY, TCL_DISNEY).
     */
    private static final String[] CODES = {"00f2", "00f5", "02f0", "00e3", "024a", "02fa"};
    private static final int[] KEYS = {Prefs.KEY_NETFLIX, Prefs.KEY_YOUTUBE, Prefs.KEY_PRIME, Prefs.KEY_TCL_CHANNEL,
            Prefs.KEY_TCL_GLOBOPLAY, Prefs.KEY_TCL_DISNEY};
    /**
     * The gear (TCL_SET, keycode 4077, which our window's KEYCODE_SETTINGS case never sees): TV Settings, as the stock
     * home screen opened them. Always that, not a Remote Buttons choice.
     */
    private static final String GEAR = "00e9) am start -a android.settings.SETTINGS >/dev/null 2>&1;; ";

    /** The buttons the script answers (our window sees some of them too, and leaves them to it). */
    static boolean watches(int keyCode) {
        if (!ON) return false;
        for (int key : KEYS) if (key == keyCode) return true;
        return false;
    }

    /** (Re)starts the script with each button's current app (and the gear's TV Settings). */
    static void start(Context c, Adb adb) throws IOException {
        stop(adb);
        StringBuilder cases = new StringBuilder(GEAR);
        for (int i = 0; i < KEYS.length; i++) {
            String target = target(c, Prefs.keyApp(KEYS[i]));
            if (target != null) cases.append(CODES[i]).append(") am start ").append(target).append(" >/dev/null 2>&1;; ");
        }
        // getevent again if it ever ends (a remote reconnecting); stop() ends the loop itself.
        String script = ": " + MARK + "; while :; do getevent -q | while read d t c v; do"
                + " [ \"$t\" = 0001 ] && [ \"$v\" = 00000001 ] || continue; case $c in " + cases + "esac; done; sleep 1; done";
        // setsid: its own session, so it outlives this ADB connection. The command waits a moment before it ends:
        // ADB ends what's left of it, and a script not in its own session by then would end with it.
        String out = adb.shell("setsid sh -c '" + script + "' </dev/null >/dev/null 2>&1 & sleep 1; pgrep -f '"
                + pattern() + "' >/dev/null && echo on");
        AppLog.w(out.trim().equals("on") ? "key watch on" : "key watch didn't start");
    }

    /**
     * Ends the script and its getevent. The pattern doesn't match its own command line ("…c[h]" isn't "…ch"): pkill
     * would otherwise end the shell it runs in, the command would seem done while pkill still goes through the
     * processes, and it would end part of the script start() launches next (its key reader, leaving getevent to die
     * at the next press).
     */
    static void stop(Adb adb) throws IOException {
        adb.shell("pkill -f '" + pattern() + "'; pkill -x getevent");
    }

    private static String pattern() {
        return MARK.substring(0, MARK.length() - 1) + "[" + MARK.charAt(MARK.length() - 1) + "]";
    }

    /** What `am start` opens for a button's app: its component, the home screen, or null for Nothing. */
    private static String target(Context c, String pkg) {
        if (pkg.isEmpty()) return null;
        if (pkg.equals(HomeMode.PKG) || pkg.equals(Prefs.ALL_APPS)) return HOME;
        PackageManager pm = c.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null) return null;
        // Only a plain component name goes into the shell command.
        String name = component.flattenToShortString();
        return COMPONENT.matcher(name).matches() ? "-n " + name : null;
    }
}
