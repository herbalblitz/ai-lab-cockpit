package io.github.herbalblitz.ailabcockpit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

/** Local settings: bridge URL, model, alert cursor and the chat transcript. */
public final class Store {

    private static final String PREFS = "ailab_cockpit";
    private static final String URL = "bridge_url";
    private static final String MODEL = "model";
    private static final String NOTIFY = "notify";
    private static final String LAST_ALERT = "last_alert";
    private static final String HISTORY = "chat_history";
    private static final String DEFAULT_URL = "http://127.0.0.1:8787";

    private final Context context;
    private final SharedPreferences prefs;

    public Store(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String url() {
        String value = prefs.getString(URL, DEFAULT_URL);
        return value == null || value.trim().isEmpty() ? DEFAULT_URL : value.trim();
    }

    public void setUrl(String value) {
        prefs.edit().putString(URL, cleanUrl(value)).apply();
    }

    public String token() {
        return SecureStore.read(context);
    }

    public void setToken(String value) {
        SecureStore.save(context, value == null ? "" : value.trim());
    }

    public boolean hasToken() {
        return SecureStore.has(context);
    }

    public String model() {
        String value = prefs.getString(MODEL, "hermes-agent");
        return value == null || value.trim().isEmpty() ? "hermes-agent" : value.trim();
    }

    public void setModel(String value) {
        prefs.edit().putString(MODEL, value == null ? "" : value.trim()).apply();
    }

    public boolean notifications() {
        return prefs.getBoolean(NOTIFY, true);
    }

    public void setNotifications(boolean enabled) {
        prefs.edit().putBoolean(NOTIFY, enabled).apply();
    }

    public double lastAlert() {
        return Double.longBitsToDouble(prefs.getLong(LAST_ALERT, Double.doubleToLongBits(0)));
    }

    public void setLastAlert(double timestamp) {
        prefs.edit().putLong(LAST_ALERT, Double.doubleToLongBits(timestamp)).apply();
    }

    public JSONArray history() {
        String raw = prefs.getString(HISTORY, "[]");
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public void setHistory(JSONArray history) {
        prefs.edit().putString(HISTORY, history == null ? "[]" : history.toString()).apply();
    }

    public void clearHistory() {
        prefs.edit().remove(HISTORY).apply();
    }

    public static String cleanUrl(String value) {
        if (value == null) return DEFAULT_URL;
        String out = value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? DEFAULT_URL : out;
    }
}
