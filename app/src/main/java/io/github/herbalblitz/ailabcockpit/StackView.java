package io.github.herbalblitz.ailabcockpit;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * One place to see and drive the whole lab: Hermes, AI-Scientist-v2, Evolver,
 * OpenHands and the local model server. Launch buttons are driven by the
 * bridge's configured templates, so no command is invented on the phone.
 */
public final class StackView implements MainActivity.Screen {

    private final MainActivity activity;
    private final Store store;
    private final Api api;
    private final ScrollView scroll;
    private final LinearLayout body;
    private boolean loading;

    public StackView(MainActivity activity, Store store, Api api) {
        this.activity = activity;
        this.store = store;
        this.api = api;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 4),
                Ui.dp(activity, 16), Ui.dp(activity, 28));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderLoading();
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShown() {
        refresh();
    }

    @Override
    public void onHidden() {
    }

    @Override
    public void refresh() {
        if (loading) return;
        loading = true;
        api.status(new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject value) {
                loading = false;
                render(value, null);
            }

            @Override
            public void err(String message) {
                loading = false;
                render(null, message);
            }
        });
    }

    private void renderLoading() {
        body.removeAllViews();
        body.addView(Ui.label(activity, "LAB STACK"));
        body.addView(Ui.bold(activity, "Reading components…", 26f, R.color.fd_text));
    }

    private void render(JSONObject data, String error) {
        body.removeAllViews();

        LinearLayout head = Ui.row(activity);
        head.addView(Ui.label(activity, "LAB STACK"));
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        TextView refresh = Ui.smallButton(activity, "Refresh", false);
        refresh.setOnClickListener(v -> refresh());
        head.addView(refresh);
        body.addView(head, Ui.matchWrap());

        body.addView(Ui.bold(activity, "All systems, one app.", 26f, R.color.fd_text));
        body.addView(Ui.text(activity,
                "Control the conductor, dispatch research and coding runs, and watch the "
                        + "model server without leaving the cockpit.",
                12f, R.color.fd_muted));

        if (error != null || data == null) {
            activity.setOnline(false, "OFFLINE");
            LinearLayout card = Ui.card(activity);
            Ui.margin(card, 0, Ui.dp(activity, 12), 0, 0);
            card.addView(Ui.label(activity, "BRIDGE"));
            card.addView(Ui.text(activity, error == null ? "No response" : error,
                    11.5f, R.color.fd_danger));
            TextView setup = Ui.smallButton(activity, "Open setup", false);
            Ui.margin(setup, 0, Ui.dp(activity, 9), 0, 0);
            setup.setOnClickListener(v -> activity.openSettings());
            card.addView(setup);
            body.addView(card, Ui.matchWrap());
            return;
        }

        JSONObject hermes = data.optJSONObject("hermes");
        boolean hermesOk = hermes != null && hermes.optBoolean("ok", false);
        activity.setOnline(hermesOk, hermesOk ? "LIVE" : "NO HERMES");

        JSONObject stack = data.optJSONObject("stack");
        JSONArray components = stack == null ? null : stack.optJSONArray("components");
        JSONArray templates = data.optJSONArray("templates");
        int ready = stack == null ? 0 : stack.optInt("ok_count", 0);
        int total = stack == null ? 5 : stack.optInt("total", 5);

        LinearLayout summary = Ui.card(activity);
        Ui.margin(summary, 0, Ui.dp(activity, 12), 0, 0);
        LinearLayout summaryHead = Ui.row(activity);
        summaryHead.addView(Ui.label(activity, "COMPONENTS"));
        TextView summarySpacer = new TextView(activity);
        summarySpacer.setLayoutParams(Ui.weight(1f));
        summaryHead.addView(summarySpacer);
        summaryHead.addView(Ui.chip(activity, ready + "/" + total + " READY",
                ready == total ? "success" : ready > 0 ? "warn" : "danger"));
        summary.addView(summaryHead);
        Ui.divider(activity, summary);
        summary.addView(Ui.text(activity,
                "Hermes is the main agent. AI-Scientist-v2 handles research, Evolver improves "
                        + "the system, OpenHands writes code, and Ollama/vLLM serves models.",
                10.5f, R.color.fd_muted));
        body.addView(summary, Ui.matchWrap());

        if (components == null || components.length() == 0) {
            LinearLayout card = Ui.card(activity);
            Ui.margin(card, 0, Ui.dp(activity, 10), 0, 0);
            card.addView(Ui.text(activity, "The bridge did not report component status.",
                    11f, R.color.fd_muted));
            body.addView(card, Ui.matchWrap());
        } else {
            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.optJSONObject(i);
                if (component != null) {
                    body.addView(componentCard(component, templates, stack), Ui.matchWrap());
                }
            }
        }

        LinearLayout actions = Ui.row(activity);
        Ui.margin(actions, 0, Ui.dp(activity, 14), 0, 0);
        TextView run = Ui.button(activity, "New experiment", true);
        run.setLayoutParams(Ui.weight(1f));
        run.setOnClickListener(v -> activity.newExperiment());
        actions.addView(run);
        TextView chat = Ui.button(activity, "Hermes chat", false);
        chat.setLayoutParams(Ui.weight(1f));
        Ui.margin(chat, Ui.dp(activity, 8), 0, 0, 0);
        chat.setOnClickListener(v -> activity.openChat());
        actions.addView(chat);
        body.addView(actions, Ui.matchWrap());

        body.addView(Ui.text(activity, "Bridge " + store.url(), 9.5f, R.color.fd_muted));
    }

    private View componentCard(JSONObject component, JSONArray templates, JSONObject stack) {
        String id = component.optString("id");
        boolean ok = component.optBoolean("ok", false);
        LinearLayout card = Ui.card(activity);
        Ui.margin(card, 0, Ui.dp(activity, 8), 0, 0);

        LinearLayout head = Ui.row(activity);
        head.addView(Ui.bold(activity, component.optString("name", id), 14f, R.color.fd_text));
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        head.addView(Ui.chip(activity, ok ? "READY" : "DOWN", ok ? "success" : "warn"));
        card.addView(head);

        card.addView(Ui.label(activity, component.optString("role", "")));
        card.addView(Ui.text(activity, component.optString("detail", "—"),
                10.5f, ok ? R.color.fd_muted : R.color.fd_gold));

        LinearLayout actions = Ui.row(activity);
        Ui.margin(actions, 0, Ui.dp(activity, 9), 0, 0);

        if ("hermes".equals(id)) {
            TextView chat = Ui.smallButton(activity, "Open chat", true);
            chat.setOnClickListener(v -> activity.openChat());
            actions.addView(chat);
            TextView runs = Ui.smallButton(activity, "Agent runs", false);
            Ui.margin(runs, Ui.dp(activity, 6), 0, 0, 0);
            runs.setOnClickListener(v -> activity.openExperiments());
            actions.addView(runs);
        } else if ("serving".equals(id)) {
            TextView models = Ui.smallButton(activity, "Refresh models", true);
            models.setOnClickListener(v -> refresh());
            actions.addView(models);
            TextView run = Ui.smallButton(activity, "Model job", false);
            Ui.margin(run, Ui.dp(activity, 6), 0, 0, 0);
            run.setOnClickListener(v -> activity.newExperiment());
            actions.addView(run);
        } else {
            JSONObject template = findTemplate(templates, id);
            if (template != null) {
                final JSONObject launch = template;
                TextView start = Ui.smallButton(activity, "Launch " + template.optString("name", "run"), true);
                start.setOnClickListener(v -> activity.launchExperiment(
                        launch.optString("title", "Stack experiment"),
                        launch.optString("command", ""),
                        launch.optString("target", "cpu"),
                        launch.optInt("timeout_sec", 3600)));
                actions.addView(start);
            } else {
                TextView run = Ui.smallButton(activity, "New run", true);
                run.setOnClickListener(v -> activity.newExperiment());
                actions.addView(run);
                TextView hint = Ui.text(activity, "add a template in bridge.json to launch here",
                        9f, R.color.fd_muted);
                Ui.margin(hint, Ui.dp(activity, 8), 0, 0, 0);
                actions.addView(hint);
            }
            JSONObject urls = stack == null ? null : stack.optJSONObject("urls");
            String openhandsUrl = urls == null ? "" : urls.optString("openhands", "");
            if ("openhands".equals(id) && ok && !openhandsUrl.isEmpty()) {
                TextView open = Ui.smallButton(activity, "Open UI", false);
                Ui.margin(open, Ui.dp(activity, 6), 0, 0, 0);
                open.setOnClickListener(v -> openUrl(openhandsUrl));
                actions.addView(open);
            }
        }
        card.addView(actions, Ui.matchWrap());
        return card;
    }

    private JSONObject findTemplate(JSONArray templates, String id) {
        if (templates == null) return null;
        String key = "ai-scientist".equals(id) ? "scientist"
                : "evolver".equals(id) ? "evolver"
                : "openhands".equals(id) ? "openhands" : "";
        if (key.isEmpty()) return null;
        for (int i = 0; i < templates.length(); i++) {
            JSONObject item = templates.optJSONObject(i);
            if (item == null) continue;
            String haystack = (item.optString("name") + " " + item.optString("title"))
                    .toLowerCase(Locale.US);
            if (haystack.contains(key)) return item;
        }
        return null;
    }

    private void openUrl(String raw) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(raw)));
        } catch (Exception e) {
            Ui.toast(activity, "No browser available");
        }
    }
}
