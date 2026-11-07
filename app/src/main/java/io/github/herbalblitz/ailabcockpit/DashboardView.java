package io.github.herbalblitz.ailabcockpit;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class DashboardView implements MainActivity.Screen {

    private final MainActivity activity;
    private final Store store;
    private final Api api;
    private final ScrollView scroll;
    private final LinearLayout body;
    private boolean loading;

    public DashboardView(MainActivity activity, Store store, Api api) {
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
        LinearLayout hero = hero();
        hero.addView(Ui.bold(activity, "Connecting…", 28f, R.color.fd_text));
        hero.addView(Ui.text(activity, "Reading the lab bridge.", 12f, R.color.fd_muted));
        body.addView(hero, Ui.matchWrap());
    }

    private void render(JSONObject data, String error) {
        body.removeAllViews();
        if (data == null || error != null) {
            activity.setOnline(false, "OFFLINE");
            renderOffline(error == null ? "No response from the bridge" : error);
            return;
        }
        JSONObject hermes = data.optJSONObject("hermes");
        boolean hermesOk = hermes != null && hermes.optBoolean("ok", false);
        activity.setOnline(hermesOk, hermesOk ? "LIVE" : "NO HERMES");

        JSONObject host = data.optJSONObject("host");
        JSONObject gpu = data.optJSONObject("gpu");
        JSONObject experiments = data.optJSONObject("experiments");

        // Hero ---------------------------------------------------------------
        LinearLayout hero = hero();
        hero.addView(Ui.label(activity, "AI LAB · CONTROL PLANE"));
        hero.addView(Ui.bold(activity, hermesOk ? "Lab online." : "Lab reachable.",
                30f, R.color.fd_text));
        hero.addView(Ui.text(activity,
                hermesOk
                        ? "Hermes is live. The bridge is queuing experiments and watching the host."
                        : "The bridge is up, but Hermes is not answering. Chat and agent runs need it.",
                12f, R.color.fd_muted));
        LinearLayout chips = Ui.row(activity);
        chips.setPadding(0, Ui.dp(activity, 10), 0, 0);
        chips.addView(Ui.chip(activity, hermesOk ? "HERMES LIVE" : "HERMES DOWN",
                hermesOk ? "success" : "danger"));
        TextView bridgeChip = Ui.chip(activity, "BRIDGE v" + data.optString("version", "?"), "info");
        Ui.margin(bridgeChip, Ui.dp(activity, 6), 0, 0, 0);
        chips.addView(bridgeChip);
        hero.addView(chips);
        body.addView(hero, Ui.matchWrap());

        // Quick actions ------------------------------------------------------
        LinearLayout quick = Ui.row(activity);
        Ui.margin(quick, 0, Ui.dp(activity, 12), 0, 0);
        TextView newRun = Ui.button(activity, "New experiment", true);
        newRun.setLayoutParams(Ui.weight(1f));
        newRun.setOnClickListener(v -> activity.newExperiment());
        quick.addView(newRun);
        TextView openChat = Ui.button(activity, "Open chat", false);
        openChat.setLayoutParams(Ui.weight(1f));
        Ui.margin(openChat, Ui.dp(activity, 8), 0, 0, 0);
        openChat.setOnClickListener(v -> activity.openChat());
        quick.addView(openChat);
        body.addView(quick, Ui.matchWrap());

        // Host pulse ---------------------------------------------------------
        body.addView(section("HOST PULSE"));
        LinearLayout stats = Ui.row(activity);
        int cpu = (int) Math.round(host == null ? 0 : host.optDouble("cpu_percent", 0));
        long memUsed = host == null ? 0 : host.optLong("mem_used_bytes", 0);
        long memTotal = host == null ? 0 : host.optLong("mem_total_bytes", 0);
        long diskUsed = host == null ? 0 : host.optLong("disk_used_bytes", 0);
        long diskTotal = host == null ? 0 : host.optLong("disk_total_bytes", 0);
        int memPct = Ui.percent(memUsed, memTotal);
        int diskPct = Ui.percent(diskUsed, diskTotal);
        stats.addView(stat("CPU", cpu + "%", (host == null ? 0 : host.optInt("cores", 0)) + " cores", cpu));
        stats.addView(withLeftMargin(stat("RAM", memPct + "%",
                Ui.bytes(memUsed) + " / " + Ui.bytes(memTotal), memPct)));
        stats.addView(withLeftMargin(stat("DISK", diskPct + "%",
                Ui.bytes(diskUsed) + " / " + Ui.bytes(diskTotal), diskPct)));
        body.addView(stats, Ui.matchWrap());

        // Hermes -------------------------------------------------------------
        body.addView(section("HERMES GATEWAY"));
        LinearLayout hermesCard = Ui.card(activity);
        LinearLayout hermesHead = Ui.row(activity);
        hermesHead.addView(Ui.label(activity, "AGENT RUNTIME"));
        TextView hermesChip = Ui.chip(activity, hermesOk ? "ONLINE" : "DOWN",
                hermesOk ? "success" : "danger");
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.leftMargin = Ui.dp(activity, 8);
        hermesHead.addView(hermesChip, chipParams);
        hermesCard.addView(hermesHead);
        Ui.divider(activity, hermesCard);
        hermesCard.addView(info("Version", hermes == null ? "—" : hermes.optString("version", "—")));
        hermesCard.addView(info("State", hermes == null ? "—" : hermes.optString("status", "—")));
        JSONObject apiServer = hermes == null ? null : hermes.optJSONObject("api_server");
        hermesCard.addView(info("Active runs", apiServer == null ? "0"
                : String.valueOf(apiServer.optInt("active_runs", 0))));
        hermesCard.addView(info("Endpoint", store.url()));
        TextView test = Ui.smallButton(activity, "Test connection", false);
        Ui.margin(test, 0, Ui.dp(activity, 10), 0, 0);
        test.setOnClickListener(v -> testConnection());
        hermesCard.addView(test);
        body.addView(hermesCard, Ui.matchWrap());

        // Lab stack -----------------------------------------------------------
        body.addView(section("LAB STACK"));
        LinearLayout stackCard = Ui.card(activity);
        JSONObject stack = data.optJSONObject("stack");
        JSONArray components = stack == null ? null : stack.optJSONArray("components");
        int ready = stack == null ? 0 : stack.optInt("ok_count", 0);
        int total = stack == null ? 5 : stack.optInt("total", 5);
        LinearLayout stackHead = Ui.row(activity);
        stackHead.addView(Ui.label(activity, "ALL-IN-ONE"));
        TextView stackSpacer = new TextView(activity);
        stackSpacer.setLayoutParams(Ui.weight(1f));
        stackHead.addView(stackSpacer);
        stackHead.addView(Ui.chip(activity, ready + "/" + total + " READY",
                ready == total ? "success" : ready > 0 ? "warn" : "danger"));
        stackCard.addView(stackHead);
        if (components == null || components.length() == 0) {
            stackCard.addView(Ui.text(activity,
                    "This bridge does not report the lab stack yet.", 10.5f, R.color.fd_muted));
        } else {
            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.optJSONObject(i);
                if (component != null) stackCard.addView(stackRow(component));
            }
        }
        TextView openSettings = Ui.smallButton(activity, "Stack setup", false);
        Ui.margin(openSettings, 0, Ui.dp(activity, 10), 0, 0);
        openSettings.setOnClickListener(v -> activity.openSettings());
        stackCard.addView(openSettings);
        body.addView(stackCard, Ui.matchWrap());

        // Experiments --------------------------------------------------------
        body.addView(section("EXPERIMENT QUEUE"));
        LinearLayout expCard = Ui.card(activity);
        LinearLayout expHead = Ui.row(activity);
        expHead.addView(Ui.label(activity, "BRIDGE RUNNER"));
        TextView expChip = Ui.chip(activity,
                String.valueOf(experiments == null ? 0 : experiments.optInt("running", 0)) + " RUNNING",
                "info");
        LinearLayout.LayoutParams expChipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        expChipParams.leftMargin = Ui.dp(activity, 8);
        expHead.addView(expChip, expChipParams);
        expCard.addView(expHead);
        Ui.divider(activity, expCard);
        LinearLayout counts = Ui.row(activity);
        counts.addView(count("TOTAL", experiments == null ? 0 : experiments.optInt("total", 0),
                R.color.fd_text));
        counts.addView(count("DONE", experiments == null ? 0 : experiments.optInt("succeeded", 0),
                R.color.fd_accent));
        counts.addView(count("FAILED", experiments == null ? 0 : experiments.optInt("failed", 0),
                R.color.fd_danger));
        counts.addView(count("QUEUED", experiments == null ? 0 : experiments.optInt("queued", 0),
                R.color.fd_gold));
        expCard.addView(counts);
        JSONArray recent = data.optJSONArray("recent");
        if (recent != null && recent.length() > 0) {
            Ui.divider(activity, expCard);
            int limit = Math.min(3, recent.length());
            for (int i = 0; i < limit; i++) {
                JSONObject job = recent.optJSONObject(i);
                if (job != null) expCard.addView(recentJob(job));
            }
        } else {
            Ui.divider(activity, expCard);
            expCard.addView(Ui.text(activity, "No experiments yet. Start a smoke test on the CPU host.",
                    11.5f, R.color.fd_muted));
        }
        TextView openRuns = Ui.smallButton(activity, "Open experiments", false);
        Ui.margin(openRuns, 0, Ui.dp(activity, 10), 0, 0);
        openRuns.setOnClickListener(v -> activity.openExperiments());
        expCard.addView(openRuns);
        body.addView(expCard, Ui.matchWrap());

        // GPU ----------------------------------------------------------------
        body.addView(section("GPU TARGET"));
        LinearLayout gpuCard = Ui.card(activity);
        String mode = gpu == null ? "unconfigured" : gpu.optString("mode", "unconfigured");
        LinearLayout gpuHead = Ui.row(activity);
        gpuHead.addView(Ui.label(activity, "REMOTE WORKER"));
        TextView gpuChip = Ui.chip(activity, "ssh".equals(mode) ? "SSH READY" : "NOT SET",
                "ssh".equals(mode) ? "success" : "warn");
        LinearLayout.LayoutParams gpuChipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gpuChipParams.leftMargin = Ui.dp(activity, 8);
        gpuHead.addView(gpuChip, gpuChipParams);
        gpuCard.addView(gpuHead);
        Ui.divider(activity, gpuCard);
        gpuCard.addView(info("Host", gpu == null || gpu.optString("ssh_host").isEmpty()
                ? "not configured" : gpu.optString("ssh_host")));
        gpuCard.addView(info("Workdir", gpu == null ? "—" : gpu.optString("workdir", "—")));
        gpuCard.addView(Ui.text(activity,
                "ssh".equals(mode)
                        ? "GPU jobs run over SSH through the bridge. Configure key auth and a free GPU host."
                        : "Set gpu.ssh_host in ~/.ai-lab-cockpit/bridge.json to enable remote GPU experiments.",
                10.5f, R.color.fd_muted));

        // Footer -------------------------------------------------------------
        LinearLayout footer = Ui.column(activity);
        footer.setPadding(0, Ui.dp(activity, 18), 0, 0);
        footer.addView(Ui.text(activity, "Bridge " + store.url(), 9.5f, R.color.fd_muted));
        footer.addView(Ui.text(activity, "Updated " + Ui.dateTime(System.currentTimeMillis() / 1000.0),
                9.5f, R.color.fd_muted));
        body.addView(footer, Ui.matchWrap());
    }

    private void renderOffline(String error) {
        LinearLayout hero = hero();
        hero.addView(Ui.label(activity, "AI LAB · CONTROL PLANE"));
        hero.addView(Ui.bold(activity, "Bridge offline.", 30f, R.color.fd_text));
        hero.addView(Ui.text(activity, error, 12f, R.color.fd_danger));
        TextView setup = Ui.button(activity, "Open setup", true);
        Ui.margin(setup, 0, Ui.dp(activity, 12), 0, 0);
        setup.setOnClickListener(v -> activity.openSettings());
        hero.addView(setup);
        body.addView(hero, Ui.matchWrap());
        LinearLayout note = Ui.card(activity);
        note.addView(Ui.label(activity, "WHAT THE PHONE NEEDS"));
        note.addView(Ui.text(activity,
                "A reachable lab bridge URL and its bearer token. The bridge runs next to Hermes on the CPU host.",
                11.5f, R.color.fd_muted));
        body.addView(note, Ui.matchWrap());
    }

    private LinearLayout hero() {
        LinearLayout hero = Ui.column(activity);
        hero.setBackgroundResource(R.drawable.bg_hero);
        int pad = Ui.dp(activity, 20);
        hero.setPadding(pad, pad, pad, pad);
        Ui.margin(hero, 0, Ui.dp(activity, 6), 0, 0);
        return hero;
    }

    private View section(String label) {
        TextView view = Ui.label(activity, label);
        Ui.margin(view, Ui.dp(activity, 4), Ui.dp(activity, 18), 0, Ui.dp(activity, 8));
        return view;
    }

    private LinearLayout stat(String label, String value, String sub, int progress) {
        LinearLayout tile = Ui.column(activity);
        tile.setBackgroundResource(R.drawable.bg_stat);
        int pad = Ui.dp(activity, 11);
        tile.setPadding(pad, pad, pad, pad);
        tile.setLayoutParams(Ui.weight(1f));
        tile.addView(Ui.label(activity, label));
        tile.addView(Ui.bold(activity, value, 21f, R.color.fd_text));
        TextView subView = Ui.text(activity, sub, 9f, R.color.fd_muted);
        subView.setSingleLine(true);
        tile.addView(subView);
        ProgressBar bar = Ui.progress(activity);
        Ui.margin(bar, 0, Ui.dp(activity, 7), 0, 0);
        bar.setProgress(Math.max(0, Math.min(100, progress)));
        tile.addView(bar);
        return tile;
    }

    private LinearLayout withLeftMargin(LinearLayout view) {
        Ui.margin(view, Ui.dp(activity, 8), 0, 0, 0);
        return view;
    }

    private LinearLayout info(String label, String value) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 3), 0, Ui.dp(activity, 3));
        TextView left = Ui.text(activity, label, 11f, R.color.fd_muted);
        left.setLayoutParams(Ui.weight(1f));
        TextView right = Ui.text(activity, value == null || value.isEmpty() ? "—" : value,
                11f, R.color.fd_text);
        right.setGravity(Gravity.END);
        right.setSingleLine(false);
        right.setMaxLines(2);
        row.addView(left);
        row.addView(right);
        return row;
    }

    private LinearLayout count(String label, int value, int colorId) {
        LinearLayout item = Ui.column(activity);
        item.setLayoutParams(Ui.weight(1f));
        TextView number = Ui.bold(activity, String.valueOf(value), 18f, colorId);
        item.addView(number);
        item.addView(Ui.label(activity, label));
        return item;
    }

    private View recentJob(JSONObject job) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
        row.setClickable(true);
        row.setOnClickListener(v -> activity.openExperiments());
        TextView chip = Ui.chip(activity, Ui.statusLabel(job.optString("status")),
                Ui.statusKind(job.optString("status")));
        row.addView(chip);
        TextView title = Ui.text(activity, job.optString("title", "Untitled"), 11.5f,
                R.color.fd_text);
        title.setLayoutParams(Ui.weight(1f));
        title.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        title.setSingleLine(true);
        row.addView(title);
        row.addView(Ui.text(activity, Ui.ago(job.optDouble("created_at", 0)), 9.5f,
                R.color.fd_muted));
        return row;
    }

    private View stackRow(JSONObject component) {
        boolean ok = component.optBoolean("ok", false);
        LinearLayout row = Ui.column(activity);
        row.setPadding(0, Ui.dp(activity, 7), 0, Ui.dp(activity, 7));
        LinearLayout head = Ui.row(activity);
        head.addView(Ui.bold(activity, component.optString("name", "Component"), 11.5f,
                R.color.fd_text));
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        head.addView(Ui.chip(activity, ok ? "READY" : "MISSING", ok ? "success" : "warn"));
        row.addView(head);
        String role = component.optString("role", "");
        String detail = component.optString("detail", "");
        row.addView(Ui.text(activity, role + (detail.isEmpty() ? "" : " · " + detail),
                9.5f, R.color.fd_muted));
        return row;
    }

    private void testConnection() {
        api.health(new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject value) {
                Ui.toast(activity, "Bridge reachable · v" + value.optString("version", "?"));
                activity.setOnline(true, "LIVE");
            }

            @Override
            public void err(String message) {
                Ui.toast(activity, message);
                activity.setOnline(false, "OFFLINE");
            }
        });
    }
}
