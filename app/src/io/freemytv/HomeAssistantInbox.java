package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Where tools/home_assistant.py puts Home Assistant's token, so it's never in the APK (any app on the TV can read an
 * installed one): `adb exec-in content write --uri content://io.freemytv.homeassistant/token?size=<bytes>`, and
 * `adb shell content delete --uri content://io.freemytv.homeassistant/token` takes it away. Only adb's shell (or root)
 * may; it lands in the app's private files (HomeAssistant.TOKEN_FILE), and only builds with an address take it.
 */
public class HomeAssistantInbox extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    private static void check(Uri uri) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends the Home Assistant token");
        if (!"token".equals(uri.getLastPathSegment())) throw new FileNotFoundException("content://io.freemytv.homeassistant/token");
        if (!HomeAssistant.offered()) throw new FileNotFoundException("this build has no home_assistant_url");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check(uri);
        if (!mode.contains("w")) throw new FileNotFoundException("write only");
        long expected;
        try {
            expected = Long.parseLong(uri.getQueryParameter("size"));
        } catch (NumberFormatException e) {   // also when there's none
            throw new FileNotFoundException("?size=<bytes> is needed");
        }
        File dir = getContext().getFilesDir();
        // Written under a hidden name and kept only once all of it came and it reads as a token: a send cut short
        // leaves the old one. Through `adb exec-in` the close can come as an error even when everything arrived, so
        // the size decides.
        File part = new File(dir, "." + HomeAssistant.TOKEN_FILE), done = new File(dir, HomeAssistant.TOKEN_FILE);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean ok = part.length() == expected && looksLikeToken(part) && part.renameTo(done);
                        if (!ok) part.delete();
                        AppLog.w("home assistant: token " + (ok ? "received" : "not received"));
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /** One line of a long-lived access token's characters (a JWT: letters, digits, - _ and .). */
    private static boolean looksLikeToken(File f) {
        try {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return t.length() >= 20 && t.length() <= 4096 && t.matches("[A-Za-z0-9._-]+");
        } catch (IOException e) {
            return false;
        }
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try {
            check(uri);
        } catch (FileNotFoundException e) {
            return 0;
        }
        return new File(getContext().getFilesDir(), HomeAssistant.TOKEN_FILE).delete() ? 1 : 0;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
