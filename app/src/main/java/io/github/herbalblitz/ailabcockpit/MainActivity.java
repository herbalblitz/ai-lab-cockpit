package io.github.herbalblitz.ailabcockpit;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    public interface Screen {
        View view();

        void onShown();

        void onHidden();

        void refresh();
    }

    private Store store;
    private Api api;
    private FrameLayout content;
    private TextView title;
    private TextView subtitle;
    private TextView status;
    private final ImageView[] navIcons = new ImageView[5];
    private final TextView[] navLabels = new TextView[5];
    private Screen[] screens;
    private int current = -1;

    private DashboardView dashboard;
    private ChatView chat;
    private ExperimentsView experiments;
    private StackView stack;
    private SettingsView settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.color(this, R.color.fd_bg));
        getWindow().setNavigationBarColor(Ui.color(this, R.color.fd_bg));

        store = new Store(this);
        api = new Api();
        api.configure(store.url(), store.token());
        if (store.lastAlert() <= 0) {
            store.setLastAlert(System.currentTimeMillis() / 1000.0);
        }
        Notify.init(this);
        PollScheduler.schedule(this);
        requestNotificationPermission();

        build();
        select(0);
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.fd_bg));

        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 8));

        ImageView logo = Ui.icon(this, R.drawable.ic_logo, R.color.fd_primary);
        logo.setLayoutParams(Ui.lp(Ui.dp(this, 30), Ui.dp(this, 30)));
        top.addView(logo);

        LinearLayout titles = Ui.column(this);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        title = Ui.bold(this, "AI-Lab Cockpit", 17f, R.color.fd_text);
        subtitle = Ui.label(this, "autonomous lab · control plane");
        subtitle.setTextSize(8.5f);
        titles.addView(title);
        titles.addView(subtitle);
        top.addView(titles);

        status = Ui.chip(this, "OFFLINE", "danger");
        status.setOnClickListener(v -> openSettings());
        top.addView(status);

        ImageView refresh = Ui.icon(this, R.drawable.ic_refresh, R.color.fd_muted);
        refresh.setBackgroundResource(R.drawable.bg_icon_button);
        refresh.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        refresh.setLayoutParams(Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        Ui.margin(refresh, Ui.dp(this, 8), 0, 0, 0);
        refresh.setOnClickListener(v -> refreshCurrent());
        top.addView(refresh);
        root.addView(top, Ui.matchWrap());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dashboard = new DashboardView(this, store, api);
        chat = new ChatView(this, store, api);
        experiments = new ExperimentsView(this, store, api);
        stack = new StackView(this, store, api);
        settings = new SettingsView(this, store, api);
        screens = new Screen[]{dashboard, chat, experiments, stack, settings};
        for (Screen screen : screens) {
            View view = screen.view();
            view.setVisibility(View.GONE);
            content.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout nav = Ui.row(this);
        nav.setBackgroundResource(R.drawable.bg_bottom_nav);
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        int[] icons = {R.drawable.ic_home, R.drawable.ic_chat, R.drawable.ic_flask,
                R.drawable.ic_skills, R.drawable.ic_settings};
        String[] labels = {"Lab", "Chat", "Runs", "Stack", "Setup"};
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            ImageView icon = Ui.icon(this, icons[i], R.color.fd_muted);
            TextView label = Ui.bold(this, labels[i], 9f, R.color.fd_muted);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 3), 0, 0);
            item.addView(icon);
            item.addView(label);
            item.setOnClickListener(v -> select(index));
            navIcons[i] = icon;
            navLabels[i] = label;
            nav.addView(item);
        }
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        setContentView(root);
    }

    public void select(int index) {
        if (screens == null || index < 0 || index >= screens.length) return;
        if (current == index) return;
        if (current >= 0) {
            screens[current].view().setVisibility(View.GONE);
            screens[current].onHidden();
        }
        current = index;
        screens[index].view().setVisibility(View.VISIBLE);
        for (int i = 0; i < screens.length; i++) {
            boolean selected = i == index;
            navIcons[i].setColorFilter(Ui.color(this,
                    selected ? R.color.fd_primary : R.color.fd_muted));
            navLabels[i].setTextColor(Ui.color(this,
                    selected ? R.color.fd_primary : R.color.fd_muted));
        }
        String[] titles = {"AI-Lab Cockpit", "Hermes chat", "Experiments", "Lab stack", "Setup"};
        String[] subs = {"autonomous lab · control plane", "plan, ask, verify",
                "cpu + free gpu · real logs", "hermes · research · code · serving",
                "bridge, token, alerts"};
        title.setText(titles[index]);
        subtitle.setText(subs[index]);
        screens[index].onShown();
    }

    public void refreshCurrent() {
        if (screens != null && current >= 0) screens[current].refresh();
    }

    public void openChat() {
        select(1);
    }

    public void openExperiments() {
        select(2);
    }

    public void openSettings() {
        select(4);
    }

    public void openStack() {
        select(3);
    }

    public void newExperiment() {
        select(2);
        experiments.focusForm();
    }

    public void launchExperiment(String title, String command, String target, int timeoutSec) {
        select(2);
        experiments.prefill(title, command, target, timeoutSec);
    }

    public void setOnline(boolean online, String label) {
        if (status == null) return;
        status.setText(label);
        Ui.styleChip(status, online ? "success" : "danger");
    }

    public Store store() {
        return store;
    }

    public Api api() {
        return api;
    }

    public void reconfigure() {
        api.configure(store.url(), store.token());
        setOnline(false, "CHECK");
        refreshCurrent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screens != null && current >= 0) {
            screens[current].onShown();
        }
    }

    @Override
    protected void onPause() {
        if (screens != null && current >= 0) {
            screens[current].onHidden();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        api.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (current != 0) {
            select(0);
        } else {
            super.onBackPressed();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }
}
