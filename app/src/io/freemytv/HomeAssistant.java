package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Buttons for your own Home Assistant in the bottom drawer (config.properties: home_assistant_url, _token and
 * _entities). Nothing runs until Home Settings › Home Assistant is on: then the drawer reads the entities' states while
 * it is open (when it opens, then every 30 s) and OK calls the entity's service. The only host is the server you name.
 * Locks, alarms and every other domain outside DOMAINS are never offered: anyone in the room can press a TV remote.
 */
final class HomeAssistant {
    private HomeAssistant() {}

    /** What a tile can be, and what OK does with it (see service()). */
    static final List<String> DOMAINS = Collections.unmodifiableList(Arrays.asList(
            "light", "switch", "fan", "input_boolean", "cover", "scene", "script", "automation", "button", "input_button"));
    /** At most this many tiles: the drawer's row stays readable next to the inputs and shortcuts. */
    static final int MAX = 8;
    /** While the drawer is open, the states are read again this often. */
    static final long REFRESH_MS = 30_000;

    private static final String SERVER = trimSlash(Config.HA_URL.trim());
    private static final String TOKEN = Config.HA_TOKEN.trim();

    /** One entity from home_assistant_entities. Its name and state change as the server answers. */
    static final class Control {
        final String entity, domain;
        volatile String name;
        volatile String state = "";   // as Home Assistant says it ("on", "off", "open", "unavailable"…); "" = not read

        Control(String entity) {
            this.entity = entity;
            this.domain = entity.substring(0, entity.indexOf('.'));
            this.name = fallbackName(entity);
        }

        /** Lit tile: a light, switch or fan that is on, a cover that is open. Scenes, scripts and buttons stay lit. */
        boolean on() {
            if (state.equals("unavailable") || state.equals("unknown")) return false;
            switch (domain) {
                case "light": case "switch": case "fan": case "input_boolean": case "automation":
                    return state.equals("on");
                case "cover":
                    return state.equals("open") || state.equals("opening");
                default:
                    return true;
            }
        }

        /** Something that is on or off (TalkBack says which); scenes, scripts and buttons are only pressed. */
        boolean hasOnOff() {
            return !domain.equals("scene") && !domain.equals("script") && !domain.equals("button")
                    && !domain.equals("input_button");
        }

        boolean unavailable() {
            return state.equals("unavailable");
        }
    }

    private static final List<Control> CONTROLS = parse(Config.HA_ENTITIES);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-assistant");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean names, fetching;
    private static volatile long readAt;

    /** A server, a token and at least one entity it can show: without them, nothing here runs. */
    static boolean configured() {
        return !SERVER.isEmpty() && !TOKEN.isEmpty() && !CONTROLS.isEmpty();
    }

    /** Configured and turned on in Home Settings. */
    static boolean on() {
        return configured() && Prefs.homeAssistant();
    }

    static List<Control> controls() {
        return CONTROLS;
    }

    /** The drawer's tiles, one per entity, with the names last read from the server. */
    static List<HomeApps.Item> items(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        if (!on()) return out;
        loadNames(c);
        for (Control ctl : CONTROLS) out.add(HomeApps.homeAssistant(ctl, symbol(ctl.domain)));
        return out;
    }

    /** The symbol for a tile (app/assets/symbols). */
    static String symbol(String domain) {
        switch (domain) {
            case "light": return "lightbulb";
            case "switch": case "input_boolean": return "toggle";
            case "fan": return "fan";
            case "cover": return "blinds";
            case "scene": return "sparkles";
            case "script": return "play";
            case "automation": return "bolt";
            default: return "smart_button";
        }
    }

    // MARK: - Reading the states

    /** Reads every entity's state and name, off the main thread; `done` runs on the main thread when they're in. */
    static void refresh(Context c, Runnable done) {
        if (!on() || fetching) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            boolean changed = false;
            try {
                for (Control ctl : CONTROLS) changed |= read(ctl);
                readAt = System.currentTimeMillis();
            } catch (IOException e) {
                AppLog.w("home assistant: " + e.getMessage());
                for (Control ctl : CONTROLS) {
                    changed |= !ctl.state.isEmpty();
                    ctl.state = "";   // shown dimmed, as not read, until the server answers again
                }
            } finally {
                fetching = false;
            }
            if (changed) saveNames(app);
            if (done != null) MAIN.post(done);
        });
    }

    /** True if the name or the state changed. A missing entity reads as unavailable; any other error stops the round. */
    private static boolean read(Control ctl) throws IOException {
        String before = ctl.name + "\n" + ctl.state;
        try {
            JSONObject o = new JSONObject(request("GET", "/api/states/" + ctl.entity, null));
            ctl.state = o.optString("state", "");
            JSONObject attributes = o.optJSONObject("attributes");
            String name = attributes == null ? "" : attributes.optString("friendly_name", "").trim();
            if (!name.isEmpty()) ctl.name = name;
        } catch (NotFound e) {
            ctl.state = "unavailable";
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer for " + ctl.entity);
        }
        return !before.equals(ctl.name + "\n" + ctl.state);
    }

    /** When the states were last read (0: never, or the server didn't answer). */
    static long readAt() {
        return readAt;
    }

    // MARK: - OK on a tile

    interface Result {
        void done(boolean ok);
    }

    /**
     * Calls the tile's service: a light, switch, fan or cover toggles, a scene or script turns on, an automation runs,
     * a button is pressed. The tile shows its new state at once; it is read again a moment later, and put back if the
     * call failed. `result` runs on the main thread.
     */
    static void activate(Context c, Control ctl, Result result) {
        if (!on() || ctl.unavailable()) {
            if (result != null) result.done(false);
            return;
        }
        final String before = ctl.state;
        if (toggles(ctl.domain)) ctl.state = flipped(ctl);
        IO.execute(() -> {
            boolean ok;
            try {
                request("POST", "/api/services/" + service(ctl.domain), "{\"entity_id\":" + JSONObject.quote(ctl.entity) + "}");
                ok = true;
            } catch (IOException e) {
                AppLog.w("home assistant: " + ctl.entity + ": " + e.getMessage());
                ctl.state = before;
                ok = false;
            }
            final boolean worked = ok;
            MAIN.post(() -> {
                if (result != null) result.done(worked);
            });
            if (ok) {
                // Home Assistant answers before the device has changed: read it once it has.
                try {
                    Thread.sleep(1200);
                    read(ctl);
                } catch (InterruptedException | IOException ignored) {
                }
                if (result != null) MAIN.post(() -> result.done(true));
            }
        });
    }

    private static boolean toggles(String domain) {
        return domain.equals("light") || domain.equals("switch") || domain.equals("fan")
                || domain.equals("input_boolean") || domain.equals("cover");
    }

    private static String flipped(Control ctl) {
        if (ctl.domain.equals("cover")) return ctl.on() ? "closed" : "open";
        return ctl.on() ? "off" : "on";
    }

    /** Domain/service for OK, as /api/services takes it. */
    static String service(String domain) {
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean": case "cover":
                return "homeassistant/toggle";
            case "scene": return "scene/turn_on";
            case "script": return "script/turn_on";
            case "automation": return "automation/trigger";
            default: return domain + "/press";   // button, input_button
        }
    }

    // MARK: - Turning it off

    /** Home Settings › Home Assistant off: the names it read are forgotten, and nothing runs until it's on again. */
    static void forget(Context c) {
        prefs(c).edit().clear().apply();
        names = false;
        readAt = 0;
        for (Control ctl : CONTROLS) {
            ctl.state = "";
            ctl.name = fallbackName(ctl.entity);
        }
    }

    // MARK: - Names, kept so the tiles have them before the server answers

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("home-assistant", Context.MODE_PRIVATE);
    }

    private static void loadNames(Context c) {
        if (names) return;
        names = true;
        SharedPreferences p = prefs(c);
        for (Control ctl : CONTROLS) {
            String name = p.getString("name:" + ctl.entity, "");
            if (!name.isEmpty()) ctl.name = name;
        }
    }

    private static void saveNames(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (Control ctl : CONTROLS) e.putString("name:" + ctl.entity, ctl.name);
        e.apply();
    }

    /** "light.living_room" → "Living room", until the server gives its name. */
    private static String fallbackName(String entity) {
        String id = entity.substring(entity.indexOf('.') + 1).replace('_', ' ').trim();
        return id.isEmpty() ? entity : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    // MARK: - The settings and the server

    /** home_assistant_entities: comma separated, in order; anything outside DOMAINS (locks, alarms…) is left out. */
    static List<Control> parse(String setting) {
        List<Control> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String raw : setting.split(",")) {
            String entity = raw.trim().toLowerCase(Locale.ROOT);
            int dot = entity.indexOf('.');
            if (dot <= 0 || dot == entity.length() - 1 || seen.contains(entity)) continue;
            if (!entity.matches("[a-z0-9_]+\\.[a-z0-9_]+")) continue;
            if (!DOMAINS.contains(entity.substring(0, dot))) {
                AppLog.w("home assistant: " + entity + " left out (only " + DOMAINS + ")");
                continue;
            }
            if (out.size() == MAX) {
                AppLog.w("home assistant: more than " + MAX + " entities, " + entity + " left out");
                break;
            }
            seen.add(entity);
            out.add(new Control(entity));
        }
        return Collections.unmodifiableList(out);
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static final class NotFound extends IOException {
        NotFound() {
            super("HTTP 404");
        }
    }

    private static String request(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 404) throw new NotFound();
            if (code == 401) throw new IOException("the token was refused (HTTP 401)");
            if (code != 200 && code != 201) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
