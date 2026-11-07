package io.github.herbalblitz.ailabcockpit;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

public final class SettingsView implements MainActivity.Screen {

    private final MainActivity activity;
    private final Store store;
    private final Api api;
    private final ScrollView scroll;
    private final EditText urlInput;
    private final EditText tokenInput;
    private final EditText modelInput;
    private final TextView testStatus;
    private final Switch notifySwitch;

    public SettingsView(MainActivity activity, Store store, Api api) {
        this.activity = activity;
        this.store = store;
        this.api = api;

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 4),
                Ui.dp(activity, 16), Ui.dp(activity, 28));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout connection = Ui.card(activity);
        connection.addView(Ui.label(activity, "BRIDGE CONNECTION"));

        urlInput = field("Bridge URL", "http://100.x.x.x:8787", false);
        Ui.margin(urlInput, 0, Ui.dp(activity, 10), 0, 0);
        connection.addView(urlInput, Ui.matchWrap());

        tokenInput = field("Bridge token", "token from bridge.json", true);
        Ui.margin(tokenInput, 0, Ui.dp(activity, 8), 0, 0);
        connection.addView(tokenInput, Ui.matchWrap());

        modelInput = field("Hermes model", "hermes-agent", false);
        Ui.margin(modelInput, 0, Ui.dp(activity, 8), 0, 0);
        connection.addView(modelInput, Ui.matchWrap());

        LinearLayout actions = Ui.row(activity);
        Ui.margin(actions, 0, Ui.dp(activity, 12), 0, 0);
        TextView save = Ui.button(activity, "Save", true);
        save.setLayoutParams(Ui.weight(1f));
        save.setOnClickListener(v -> save());
        actions.addView(save);
        TextView test = Ui.button(activity, "Test", false);
        test.setLayoutParams(Ui.weight(1f));
        Ui.margin(test, Ui.dp(activity, 8), 0, 0, 0);
        test.setOnClickListener(v -> test());
        actions.addView(test);
        connection.addView(actions, Ui.matchWrap());

        testStatus = Ui.text(activity, "", 10.5f, R.color.fd_muted);
        Ui.margin(testStatus, 0, Ui.dp(activity, 9), 0, 0);
        connection.addView(testStatus, Ui.matchWrap());

        TextView clearToken = Ui.smallButton(activity, "Clear saved token", false);
        clearToken.setTextColor(Ui.color(activity, R.color.fd_danger));
        Ui.margin(clearToken, 0, Ui.dp(activity, 9), 0, 0);
        clearToken.setOnClickListener(v -> {
            store.setToken("");
            tokenInput.setText("");
            tokenInput.setHint("token from bridge.json");
            activity.reconfigure();
            Ui.toast(activity, "Token cleared");
        });
        connection.addView(clearToken, Ui.matchWrap());
        body.addView(connection, Ui.matchWrap());

        LinearLayout alerts = Ui.card(activity);
        Ui.margin(alerts, 0, Ui.dp(activity, 10), 0, 0);
        LinearLayout alertRow = Ui.row(activity);
        LinearLayout alertText = Ui.column(activity);
        alertText.setLayoutParams(Ui.weight(1f));
        alertText.addView(Ui.bold(activity, "Completion alerts", 13f, R.color.fd_text));
        alertText.addView(Ui.text(activity,
                "Notify when an experiment succeeds or fails, even when the app is closed.",
                10.5f, R.color.fd_muted));
        alertRow.addView(alertText);
        notifySwitch = new Switch(activity);
        notifySwitch.setChecked(store.notifications());
        notifySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            store.setNotifications(checked);
            if (checked) {
                Notify.init(activity);
                PollScheduler.schedule(activity);
            }
        });
        alertRow.addView(notifySwitch);
        alerts.addView(alertRow, Ui.matchWrap());
        body.addView(alerts, Ui.matchWrap());

        LinearLayout stack = Ui.card(activity);
        Ui.margin(stack, 0, Ui.dp(activity, 10), 0, 0);
        stack.addView(Ui.label(activity, "LAB STACK"));
        stack.addView(stackRow("Hermes Agent", "Conductor · memory · tools"));
        stack.addView(stackRow("AI-Scientist-v2", "Research experiments on the free GPU"));
        stack.addView(stackRow("Evolver", "Self-improvement and skill evolution"));
        stack.addView(stackRow("OpenHands", "Coding agent for larger changes"));
        stack.addView(stackRow("Ollama / vLLM", "Local model serving for the agents"));
        body.addView(stack, Ui.matchWrap());

        LinearLayout security = Ui.card(activity);
        Ui.margin(security, 0, Ui.dp(activity, 10), 0, 0);
        security.addView(Ui.label(activity, "SECURITY"));
        security.addView(Ui.text(activity, activity.getString(R.string.security_note),
                10.5f, R.color.fd_muted));
        body.addView(security, Ui.matchWrap());

        LinearLayout about = Ui.card(activity);
        Ui.margin(about, 0, Ui.dp(activity, 10), 0, 0);
        about.addView(Ui.label(activity, "BUILD"));
        about.addView(Ui.text(activity, "AI-Lab Cockpit " + BuildInfo.VERSION
                + "  ·  " + BuildInfo.PACKAGE, 10.5f, R.color.fd_muted));
        about.addView(Ui.text(activity, "Built " + BuildInfo.DATE
                + "  ·  minSdk 26 / targetSdk 35", 10.5f, R.color.fd_muted));
        TextView repo = Ui.smallButton(activity, "Open repository", false);
        Ui.margin(repo, 0, Ui.dp(activity, 9), 0, 0);
        repo.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildInfo.REPO)));
            } catch (Exception e) {
                Ui.toast(activity, "No browser available");
            }
        });
        about.addView(repo, Ui.matchWrap());
        TextView clearChat = Ui.smallButton(activity, "Clear chat history", false);
        Ui.margin(clearChat, Ui.dp(activity, 8), Ui.dp(activity, 9), 0, 0);
        clearChat.setOnClickListener(v -> {
            store.clearHistory();
            Ui.toast(activity, "Chat history cleared");
        });
        about.addView(clearChat, Ui.matchWrap());
        body.addView(about, Ui.matchWrap());

        load();
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShown() {
        load();
    }

    @Override
    public void onHidden() {
    }

    @Override
    public void refresh() {
        load();
    }

    private void load() {
        urlInput.setText(store.url());
        modelInput.setText(store.model());
        tokenInput.setText("");
        tokenInput.setHint(store.hasToken()
                ? "Saved — leave blank to keep"
                : "token from bridge.json");
        testStatus.setText("");
    }

    private EditText field(String hint, String placeholder, boolean password) {
        EditText view = new EditText(activity);
        view.setHint(placeholder);
        view.setTextColor(Ui.color(activity, R.color.fd_text));
        view.setHintTextColor(Ui.color(activity, R.color.fd_muted));
        view.setTextSize(12f);
        view.setTypeface(password ? Typeface.MONOSPACE : Ui.font(activity, R.font.manrope_regular));
        view.setBackgroundResource(R.drawable.bg_input);
        view.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        view.setSingleLine(true);
        if (password) {
            view.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            view.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_URI);
        }
        return view;
    }

    private View stackRow(String name, String detail) {
        LinearLayout row = Ui.column(activity);
        row.setPadding(0, Ui.dp(activity, 7), 0, Ui.dp(activity, 2));
        LinearLayout head = Ui.row(activity);
        head.addView(Ui.bold(activity, name, 11.5f, R.color.fd_text));
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        head.addView(Ui.chip(activity, "STACK", "neutral"));
        row.addView(head);
        row.addView(Ui.text(activity, detail, 10f, R.color.fd_muted));
        return row;
    }

    private void save() {
        String url = Store.cleanUrl(urlInput.getText().toString());
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Ui.toast(activity, "URL must start with http:// or https://");
            return;
        }
        store.setUrl(url);
        String token = tokenInput.getText().toString().trim();
        if (!token.isEmpty()) store.setToken(token);
        store.setModel(modelInput.getText().toString());
        urlInput.setText(store.url());
        tokenInput.setText("");
        tokenInput.setHint(store.hasToken()
                ? "Saved — leave blank to keep"
                : "token from bridge.json");
        activity.reconfigure();
        Ui.toast(activity, "Saved");
    }

    private void test() {
        String url = Store.cleanUrl(urlInput.getText().toString());
        String token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) token = store.token();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            testStatus.setText("URL must start with http:// or https://");
            testStatus.setTextColor(Ui.color(activity, R.color.fd_danger));
            return;
        }
        testStatus.setText("Testing…");
        testStatus.setTextColor(Ui.color(activity, R.color.fd_muted));
        final Api probe = new Api();
        probe.configure(url, token);
        final String finalUrl = url;
        probe.health(new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject health) {
                probe.status(new Api.Cb<JSONObject>() {
                    @Override
                    public void ok(JSONObject status) {
                        JSONObject hermes = status.optJSONObject("hermes");
                        boolean hermesOk = hermes != null && hermes.optBoolean("ok", false);
                        testStatus.setText("Bridge v" + health.optString("version", "?")
                                + " reachable · Hermes " + (hermesOk ? "live" : "down")
                                + " · " + finalUrl);
                        testStatus.setTextColor(Ui.color(activity,
                                hermesOk ? R.color.fd_accent : R.color.fd_gold));
                        activity.setOnline(hermesOk, hermesOk ? "LIVE" : "NO HERMES");
                        probe.close();
                    }

                    @Override
                    public void err(String message) {
                        testStatus.setText("Bridge reachable, token rejected: " + message);
                        testStatus.setTextColor(Ui.color(activity, R.color.fd_danger));
                        probe.close();
                    }
                });
            }

            @Override
            public void err(String message) {
                testStatus.setText(message);
                testStatus.setTextColor(Ui.color(activity, R.color.fd_danger));
                activity.setOnline(false, "OFFLINE");
                probe.close();
            }
        });
    }
}
