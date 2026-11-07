package io.github.herbalblitz.ailabcockpit;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class ExperimentsView implements MainActivity.Screen {

    private final MainActivity activity;
    private final Store store;
    private final Api api;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final LinearLayout list;
    private final EditText titleInput;
    private final EditText commandInput;
    private final EditText timeoutInput;
    private final TextView startButton;
    private final TextView targetCpu;
    private final TextView targetGpu;
    private final LinearLayout customChips;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String target = "cpu";
    private boolean creating;
    private boolean visible;
    private boolean loading;
    private boolean templatesRequested;

    public ExperimentsView(MainActivity activity, Store store, Api api) {
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

        LinearLayout head = Ui.row(activity);
        head.addView(Ui.label(activity, "NEW EXPERIMENT"));
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        head.addView(Ui.chip(activity, "BRIDGE QUEUE", "info"));
        body.addView(head, Ui.matchWrap());

        LinearLayout form = Ui.card(activity);
        Ui.margin(form, 0, Ui.dp(activity, 8), 0, 0);

        titleInput = field("Title", "e.g. nanoGPT smoke test");
        form.addView(titleInput, Ui.matchWrap());

        TextView commandLabel = Ui.label(activity, "COMMAND");
        Ui.margin(commandLabel, 0, Ui.dp(activity, 12), 0, Ui.dp(activity, 5));
        form.addView(commandLabel);

        commandInput = new EditText(activity);
        commandInput.setHint("bash command or GPU experiment command");
        commandInput.setHintTextColor(Ui.color(activity, R.color.fd_muted));
        commandInput.setTextColor(Ui.color(activity, R.color.fd_text));
        commandInput.setTextSize(11.5f);
        commandInput.setTypeface(Typeface.MONOSPACE);
        commandInput.setBackgroundResource(R.drawable.bg_input);
        commandInput.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        commandInput.setMinLines(4);
        commandInput.setGravity(Gravity.TOP | Gravity.START);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(commandInput, Ui.matchWrap());

        LinearLayout targetRow = Ui.row(activity);
        Ui.margin(targetRow, 0, Ui.dp(activity, 12), 0, 0);
        targetRow.addView(Ui.label(activity, "TARGET"));
        targetCpu = Ui.chip(activity, "CPU LOCAL", "info");
        targetCpu.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 7),
                Ui.dp(activity, 12), Ui.dp(activity, 7));
        Ui.margin(targetCpu, Ui.dp(activity, 10), 0, 0, 0);
        targetCpu.setOnClickListener(v -> setTarget("cpu"));
        targetRow.addView(targetCpu);
        targetGpu = Ui.chip(activity, "GPU SSH", "neutral");
        targetGpu.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 7),
                Ui.dp(activity, 12), Ui.dp(activity, 7));
        Ui.margin(targetGpu, Ui.dp(activity, 6), 0, 0, 0);
        targetGpu.setOnClickListener(v -> setTarget("gpu"));
        targetRow.addView(targetGpu);
        form.addView(targetRow, Ui.matchWrap());

        LinearLayout timeoutRow = Ui.row(activity);
        Ui.margin(timeoutRow, 0, Ui.dp(activity, 12), 0, 0);
        timeoutRow.addView(Ui.label(activity, "TIMEOUT"));
        timeoutInput = new EditText(activity);
        timeoutInput.setText("3600");
        timeoutInput.setTextColor(Ui.color(activity, R.color.fd_text));
        timeoutInput.setTextSize(12f);
        timeoutInput.setTypeface(Typeface.MONOSPACE);
        timeoutInput.setBackgroundResource(R.drawable.bg_input);
        timeoutInput.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 5),
                Ui.dp(activity, 10), Ui.dp(activity, 5));
        timeoutInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeoutInput.setLayoutParams(Ui.lp(Ui.dp(activity, 90), Ui.dp(activity, 38)));
        Ui.margin(timeoutInput, Ui.dp(activity, 10), 0, 0, 0);
        timeoutRow.addView(timeoutInput);
        TextView seconds = Ui.text(activity, "seconds", 10f, R.color.fd_muted);
        Ui.margin(seconds, Ui.dp(activity, 6), 0, 0, 0);
        timeoutRow.addView(seconds);
        form.addView(timeoutRow, Ui.matchWrap());

        startButton = Ui.button(activity, "Queue experiment", true);
        Ui.margin(startButton, 0, Ui.dp(activity, 14), 0, 0);
        startButton.setOnClickListener(v -> create());
        form.addView(startButton, Ui.matchWrap());

        TextView note = Ui.text(activity,
                "GPU jobs run over SSH through the bridge. Set gpu.ssh_host in bridge.json "
                        + "and use key auth.", 10f, R.color.fd_muted);
        Ui.margin(note, 0, Ui.dp(activity, 9), 0, 0);
        form.addView(note, Ui.matchWrap());

        LinearLayout templates = Ui.column(activity);
        Ui.margin(templates, 0, Ui.dp(activity, 6), 0, 0);
        templates.addView(Ui.label(activity, "TEMPLATES"));
        HorizontalScrollView hscroll = new HorizontalScrollView(activity);
        hscroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(activity);
        chips.setPadding(0, Ui.dp(activity, 6), 0, 0);
        addTemplate(chips, "CPU smoke", "CPU smoke test",
                "python3 -c \"import platform,sys; print(platform.platform()); print(sys.version)\"",
                "cpu", 120);
        addTemplate(chips, "GPU check", "GPU check",
                "nvidia-smi", "gpu", 120);
        addTemplate(chips, "Host health", "Host health",
                "df -h; echo; free -h; echo; uptime", "cpu", 60);
        addTemplate(chips, "Artifacts", "List artifacts",
                "ls -lah ~/ai-lab/artifacts 2>/dev/null || echo 'no artifacts directory'",
                "cpu", 60);
        hscroll.addView(chips);
        templates.addView(hscroll, Ui.matchWrap());

        customChips = Ui.row(activity);
        customChips.setPadding(0, Ui.dp(activity, 4), 0, 0);
        HorizontalScrollView customScroll = new HorizontalScrollView(activity);
        customScroll.setHorizontalScrollBarEnabled(false);
        customScroll.addView(customChips);
        templates.addView(customScroll, Ui.matchWrap());
        form.addView(templates, Ui.matchWrap());
        body.addView(form, Ui.matchWrap());

        LinearLayout listHead = Ui.row(activity);
        Ui.margin(listHead, 0, Ui.dp(activity, 20), 0, Ui.dp(activity, 8));
        listHead.addView(Ui.label(activity, "RECENT RUNS"));
        TextView listSpacer = new TextView(activity);
        listSpacer.setLayoutParams(Ui.weight(1f));
        listHead.addView(listSpacer);
        TextView refresh = Ui.smallButton(activity, "Refresh", false);
        refresh.setOnClickListener(v -> refresh());
        listHead.addView(refresh);
        body.addView(listHead, Ui.matchWrap());

        list = Ui.column(activity);
        body.addView(list, Ui.matchWrap());
        renderLoading();
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShown() {
        visible = true;
        loadTemplates();
        refresh();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 10_000);
    }

    @Override
    public void onHidden() {
        visible = false;
        handler.removeCallbacks(tick);
    }

    @Override
    public void refresh() {
        if (loading || !visible) return;
        loading = true;
        api.experiments(new Api.Cb<JSONArray>() {
            @Override
            public void ok(JSONArray value) {
                loading = false;
                activity.setOnline(true, "LIVE");
                render(value, null);
            }

            @Override
            public void err(String message) {
                loading = false;
                render(null, message);
                activity.setOnline(false, "OFFLINE");
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            refresh();
            handler.postDelayed(this, 10_000);
        }
    };

    public void focusForm() {
        titleInput.requestFocus();
        commandInput.requestFocus();
        Ui.toast(activity, "Describe the experiment and queue it");
    }

    public void prefill(String title, String command, String targetName, int timeoutSec) {
        titleInput.setText(title == null ? "" : title);
        commandInput.setText(command == null ? "" : command);
        timeoutInput.setText(String.valueOf(timeoutSec <= 0 ? 3600 : timeoutSec));
        setTarget("gpu".equals(targetName) ? "gpu" : "cpu");
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
        Ui.toast(activity, "Template loaded — review and queue");
    }

    private EditText field(String hint, String placeholder) {
        EditText view = new EditText(activity);
        view.setHint(placeholder);
        view.setTextColor(Ui.color(activity, R.color.fd_text));
        view.setHintTextColor(Ui.color(activity, R.color.fd_muted));
        view.setTextSize(12.5f);
        view.setTypeface(Ui.font(activity, R.font.manrope_regular));
        view.setBackgroundResource(R.drawable.bg_input);
        view.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return view;
    }

    private void loadTemplates() {
        if (templatesRequested) return;
        templatesRequested = true;
        api.templates(new Api.Cb<JSONArray>() {
            @Override
            public void ok(JSONArray value) {
                customChips.removeAllViews();
                for (int i = 0; i < value.length(); i++) {
                    JSONObject item = value.optJSONObject(i);
                    if (item == null) continue;
                    String command = item.optString("command", "").trim();
                    if (command.isEmpty()) continue;
                    addTemplate(customChips,
                            item.optString("name", "Custom"),
                            item.optString("title", "Custom experiment"),
                            command,
                            item.optString("target", "cpu"),
                            item.optInt("timeout_sec", 3600));
                }
            }

            @Override
            public void err(String message) {
                templatesRequested = false;
            }
        });
    }

    private void addTemplate(LinearLayout parent, String label, String title, String command,
                             String targetName, int timeout) {
        TextView chip = Ui.chip(activity, label, "neutral");
        chip.setAllCaps(false);
        chip.setTextSize(10.5f);
        chip.setPadding(Ui.dp(activity, 11), Ui.dp(activity, 7),
                Ui.dp(activity, 11), Ui.dp(activity, 7));
        Ui.margin(chip, 0, 0, Ui.dp(activity, 6), 0);
        chip.setOnClickListener(v -> {
            titleInput.setText(title);
            commandInput.setText(command);
            timeoutInput.setText(String.valueOf(timeout));
            setTarget(targetName);
            Ui.styleChip(chip, "info");
            Ui.toast(activity, "Template loaded");
        });
        parent.addView(chip);
    }

    private void setTarget(String value) {
        target = value;
        boolean cpu = "cpu".equals(value);
        Ui.styleChip(targetCpu, cpu ? "info" : "neutral");
        Ui.styleChip(targetGpu, cpu ? "neutral" : "info");
    }

    private void create() {
        if (creating) return;
        String title = titleInput.getText().toString().trim();
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            Ui.toast(activity, "Command is required");
            commandInput.requestFocus();
            return;
        }
        if (title.isEmpty()) {
            title = command.length() > 48 ? command.substring(0, 48) + "…" : command;
        }
        int timeout;
        try {
            timeout = Integer.parseInt(timeoutInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            timeout = 3600;
        }
        timeout = Math.max(10, timeout);
        creating = true;
        startButton.setEnabled(false);
        startButton.setAlpha(0.5f);
        final String finalTitle = title;
        api.createExperiment(title, command, target, timeout, new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject value) {
                creating = false;
                startButton.setEnabled(true);
                startButton.setAlpha(1f);
                if (value == null) {
                    Ui.toast(activity, "Bridge returned no experiment");
                    return;
                }
                Ui.toast(activity, "Queued · " + value.optString("id", ""));
                titleInput.setText("");
                commandInput.setText("");
                setTarget("cpu");
                refresh();
            }

            @Override
            public void err(String message) {
                creating = false;
                startButton.setEnabled(true);
                startButton.setAlpha(1f);
                Ui.toast(activity, message);
            }
        });
    }

    private void renderLoading() {
        list.removeAllViews();
        list.addView(Ui.text(activity, "Loading experiments…", 11f, R.color.fd_muted));
    }

    private void render(JSONArray jobs, String error) {
        list.removeAllViews();
        if (error != null) {
            LinearLayout card = Ui.card(activity);
            card.addView(Ui.label(activity, "BRIDGE"));
            card.addView(Ui.text(activity, error, 11.5f, R.color.fd_danger));
            TextView setup = Ui.smallButton(activity, "Open setup", false);
            Ui.margin(setup, 0, Ui.dp(activity, 8), 0, 0);
            setup.setOnClickListener(v -> activity.openSettings());
            card.addView(setup);
            list.addView(card, Ui.matchWrap());
            return;
        }
        if (jobs == null || jobs.length() == 0) {
            LinearLayout card = Ui.card(activity);
            card.addView(Ui.text(activity, activity.getString(R.string.empty_skills),
                    11.5f, R.color.fd_muted));
            card.addView(Ui.text(activity,
                    "Start with the CPU smoke template; it proves the bridge, the log stream and alerts.",
                    10.5f, R.color.fd_muted));
            list.addView(card, Ui.matchWrap());
            return;
        }
        int limit = Math.min(40, jobs.length());
        for (int i = 0; i < limit; i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job != null) list.addView(jobCard(job), Ui.matchWrap());
        }
    }

    private View jobCard(JSONObject job) {
        final String id = job.optString("id");
        String status = job.optString("status");
        LinearLayout card = Ui.card(activity);
        Ui.margin(card, 0, Ui.dp(activity, 7), 0, 0);
        card.setClickable(true);
        card.setOnClickListener(v -> showDetail(id));

        LinearLayout head = Ui.row(activity);
        head.addView(Ui.chip(activity, Ui.statusLabel(status), Ui.statusKind(status)));
        TextView title = Ui.bold(activity, job.optString("title", "Untitled"), 13f,
                R.color.fd_text);
        title.setLayoutParams(Ui.weight(1f));
        title.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), 0);
        title.setSingleLine(true);
        head.addView(title);
        head.addView(Ui.text(activity, Ui.ago(job.optDouble("created_at", 0)), 9.5f,
                R.color.fd_muted));
        card.addView(head);

        String targetName = "gpu".equals(job.optString("target")) ? "GPU · SSH" : "CPU · local";
        String meta = targetName + "  ·  timeout " + job.optInt("timeout_sec", 0) + "s";
        if (!job.isNull("exit_code")) meta += "  ·  exit " + job.optInt("exit_code");
        TextView metaView = Ui.text(activity, meta, 9.5f, R.color.fd_muted);
        metaView.setPadding(0, Ui.dp(activity, 5), 0, 0);
        card.addView(metaView);

        TextView command = Ui.text(activity, job.optString("command", ""), 10f,
                R.color.fd_muted);
        command.setTypeface(Typeface.MONOSPACE);
        command.setBackground(Ui.round(activity, Ui.color(activity, R.color.fd_code), 8));
        command.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 7),
                Ui.dp(activity, 9), Ui.dp(activity, 7));
        command.setMaxLines(2);
        command.setSingleLine(false);
        Ui.margin(command, 0, Ui.dp(activity, 7), 0, 0);
        card.addView(command, Ui.matchWrap());

        if (!job.optString("error").isEmpty()) {
            TextView error = Ui.text(activity, job.optString("error"), 10f, R.color.fd_danger);
            Ui.margin(error, 0, Ui.dp(activity, 6), 0, 0);
            card.addView(error, Ui.matchWrap());
        }

        LinearLayout actions = Ui.row(activity);
        Ui.margin(actions, 0, Ui.dp(activity, 9), 0, 0);
        TextView open = Ui.smallButton(activity, "View log", false);
        open.setOnClickListener(v -> showDetail(id));
        actions.addView(open);
        if ("running".equals(status) || "queued".equals(status)) {
            TextView cancel = Ui.smallButton(activity, "Stop", false);
            cancel.setTextColor(Ui.color(activity, R.color.fd_danger));
            Ui.margin(cancel, Ui.dp(activity, 8), 0, 0, 0);
            cancel.setOnClickListener(v -> cancelJob(id));
            actions.addView(cancel);
        }
        card.addView(actions, Ui.matchWrap());
        return card;
    }

    private void cancelJob(String id) {
        api.cancel(id, new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject value) {
                Ui.toast(activity, "Stop requested");
                refresh();
            }

            @Override
            public void err(String message) {
                Ui.toast(activity, message);
            }
        });
    }

    // ------------------------------------------------------------- detail dialog

    private void showDetail(String id) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = Ui.column(activity);
        content.setBackground(Ui.roundStroke(activity, Ui.color(activity, R.color.fd_surface),
                Ui.color(activity, R.color.fd_stroke), 20));
        content.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 16),
                Ui.dp(activity, 16), Ui.dp(activity, 16));
        scroll.addView(content);

        TextView title = Ui.bold(activity, "Experiment", 16f, R.color.fd_text);
        content.addView(title);
        TextView status = Ui.chip(activity, "…", "neutral");
        Ui.margin(status, 0, Ui.dp(activity, 8), 0, 0);
        content.addView(status);
        TextView meta = Ui.text(activity, "", 10f, R.color.fd_muted);
        Ui.margin(meta, 0, Ui.dp(activity, 8), 0, 0);
        content.addView(meta);

        TextView commandLabel = Ui.label(activity, "COMMAND");
        Ui.margin(commandLabel, 0, Ui.dp(activity, 12), 0, 0);
        content.addView(commandLabel);
        TextView command = Ui.text(activity, "", 10.5f, R.color.fd_text);
        command.setTypeface(Typeface.MONOSPACE);
        command.setTextIsSelectable(true);
        command.setBackground(Ui.round(activity, Ui.color(activity, R.color.fd_code), 8));
        command.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 7),
                Ui.dp(activity, 9), Ui.dp(activity, 7));
        content.addView(command, Ui.matchWrap());

        TextView logLabel = Ui.label(activity, "LOG");
        Ui.margin(logLabel, 0, Ui.dp(activity, 12), 0, 0);
        content.addView(logLabel);
        final TextView log = Ui.text(activity, "Loading…", 9.5f, R.color.fd_text);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setBackground(Ui.round(activity, Ui.color(activity, R.color.fd_code), 8));
        log.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 7),
                Ui.dp(activity, 9), Ui.dp(activity, 7));
        log.setMinLines(8);
        content.addView(log, Ui.matchWrap());

        LinearLayout actions = Ui.row(activity);
        Ui.margin(actions, 0, Ui.dp(activity, 12), 0, 0);
        TextView refresh = Ui.smallButton(activity, "Refresh", false);
        actions.addView(refresh);
        TextView copy = Ui.smallButton(activity, "Copy log", false);
        Ui.margin(copy, Ui.dp(activity, 8), 0, 0, 0);
        actions.addView(copy);
        TextView stop = Ui.smallButton(activity, "Stop job", false);
        stop.setTextColor(Ui.color(activity, R.color.fd_danger));
        Ui.margin(stop, Ui.dp(activity, 8), 0, 0, 0);
        actions.addView(stop);
        TextView close = Ui.smallButton(activity, "Close", true);
        Ui.margin(close, Ui.dp(activity, 8), 0, 0, 0);
        actions.addView(close);
        content.addView(actions, Ui.matchWrap());

        final String[] loadedLog = {""};
        final Runnable load = new Runnable() {
            @Override
            public void run() {
                api.experiment(id, new Api.Cb<JSONObject>() {
                    @Override
                    public void ok(JSONObject job) {
                        title.setText(job.optString("title", "Experiment"));
                        String state = job.optString("status");
                        status.setText(Ui.statusLabel(state));
                        Ui.styleChip(status, Ui.statusKind(state));
                        String details = ("gpu".equals(job.optString("target")) ? "GPU · SSH" : "CPU · local")
                                + " · timeout " + job.optInt("timeout_sec", 0) + "s"
                                + " · created " + Ui.dateTime(job.optDouble("created_at", 0));
                        if (!job.isNull("exit_code")) details += " · exit " + job.optInt("exit_code");
                        if (!job.optString("error").isEmpty()) details += "\n" + job.optString("error");
                        meta.setText(details);
                        command.setText(job.optString("command", ""));
                        boolean active = "running".equals(state) || "queued".equals(state);
                        stop.setVisibility(active ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void err(String message) {
                        meta.setText(message);
                    }
                });
                api.log(id, new Api.Cb<String>() {
                    @Override
                    public void ok(String value) {
                        loadedLog[0] = value;
                        log.setText(value.isEmpty() ? "(no output yet)" : value);
                    }

                    @Override
                    public void err(String message) {
                        log.setText("Cannot load log: " + message);
                    }
                });
            }
        };
        refresh.setOnClickListener(v -> load.run());
        copy.setOnClickListener(v -> Ui.copy(activity, "experiment log", loadedLog[0]));
        stop.setOnClickListener(v -> {
            cancelJob(id);
            handler.postDelayed(load, 600);
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.6f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout((int) (Ui.screenWidth(activity) * 0.92),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        load.run();
    }
}
