package io.github.herbalblitz.ailabcockpit;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ChatView implements MainActivity.Screen {

    private final MainActivity activity;
    private final Store store;
    private final Api api;
    private final LinearLayout root;
    private final ScrollView scroll;
    private final LinearLayout messages;
    private final EditText input;
    private final TextView send;
    private final TextView skillChip;
    private boolean sending;
    private boolean skillLoaded;
    private String activeSkill;
    private TextView thinking;

    public ChatView(MainActivity activity, Store store, Api api) {
        this.activity = activity;
        this.store = store;
        this.api = api;

        root = Ui.column(activity);
        root.setBackgroundColor(Ui.color(activity, R.color.fd_bg));

        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 6));
        TextView model = Ui.chip(activity, store.model(), "info");
        head.addView(model);
        skillChip = Ui.chip(activity, "SKILL", "neutral");
        skillChip.setAllCaps(false);
        skillChip.setTextSize(9f);
        skillChip.setVisibility(View.GONE);
        Ui.margin(skillChip, Ui.dp(activity, 6), 0, 0, 0);
        head.addView(skillChip);
        TextView spacer = new TextView(activity);
        spacer.setLayoutParams(Ui.weight(1f));
        head.addView(spacer);
        TextView fresh = Ui.smallButton(activity, "New chat", false);
        fresh.setOnClickListener(v -> newChat());
        head.addView(fresh);
        root.addView(head, Ui.matchWrap());

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        messages = Ui.column(activity);
        messages.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 4),
                Ui.dp(activity, 14), Ui.dp(activity, 10));
        scroll.addView(messages, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composer = Ui.row(activity);
        composer.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
                Ui.dp(activity, 12), Ui.dp(activity, 10));
        composer.setBackgroundColor(Ui.color(activity, R.color.fd_bg2));

        input = new EditText(activity);
        input.setHint("Ask Hermes to plan, build or verify…");
        input.setHintTextColor(Ui.color(activity, R.color.fd_muted));
        input.setTextColor(Ui.color(activity, R.color.fd_text));
        input.setTextSize(13f);
        input.setTypeface(Ui.font(activity, R.font.manrope_regular));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(Ui.dp(activity, 13), Ui.dp(activity, 10),
                Ui.dp(activity, 13), Ui.dp(activity, 10));
        input.setMinHeight(Ui.dp(activity, 46));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setLayoutParams(Ui.weight(1f));
        composer.addView(input);

        send = Ui.button(activity, "Send", true);
        send.setLayoutParams(Ui.lp(Ui.dp(activity, 74), Ui.dp(activity, 46)));
        Ui.margin(send, Ui.dp(activity, 8), 0, 0, 0);
        send.setOnClickListener(v -> send());
        composer.addView(send);
        root.addView(composer, Ui.matchWrap());

        renderHistory();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public void onShown() {
        loadSkill();
        scrollToBottom();
    }

    @Override
    public void onHidden() {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    @Override
    public void refresh() {
        // Chat has no polling loop; a send is the refresh.
    }

    private void renderHistory() {
        messages.removeAllViews();
        JSONArray history = store.history();
        if (history.length() == 0) {
            renderWelcome();
            return;
        }
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role", "assistant");
            String content = item.optString("content", "");
            if (!content.isEmpty()) addBubble(role, content, false);
        }
        scrollToBottom();
    }

    private void renderWelcome() {
        addBubble("assistant",
                "I'm Hermes, running beside the lab bridge on the CPU host. I can plan work, "
                        + "call tools, inspect the machine and verify results. Experiments run "
                        + "through the Runs tab.", false);
        LinearLayout suggestions = Ui.column(activity);
        suggestions.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 6), 0, 0);
        String[] ideas = {
                "What is the lab status right now?",
                "Plan a nanoGPT experiment for the free GPU host.",
                "Summarize today's experiments and failures."
        };
        for (String idea : ideas) {
            TextView chip = Ui.chip(activity, idea, "info");
            chip.setAllCaps(false);
            chip.setTextSize(10.5f);
            chip.setPadding(Ui.dp(activity, 11), Ui.dp(activity, 7),
                    Ui.dp(activity, 11), Ui.dp(activity, 7));
            Ui.margin(chip, 0, Ui.dp(activity, 5), 0, 0);
            chip.setOnClickListener(v -> {
                input.setText(idea);
                input.setSelection(input.getText().length());
            });
            suggestions.addView(chip, Ui.matchWrap());
        }
        messages.addView(suggestions, Ui.matchWrap());
    }

    private void loadSkill() {
        if (skillLoaded) return;
        skillLoaded = true;
        api.skills(new Api.Cb<JSONArray>() {
            @Override
            public void ok(JSONArray value) {
                for (int i = 0; i < value.length(); i++) {
                    JSONObject item = value.optJSONObject(i);
                    if (item == null) continue;
                    if ("lab-operator".equals(item.optString("name"))) {
                        activeSkill = item.optString("name");
                        skillChip.setText("SKILL " + item.optString("version", "v1"));
                        skillChip.setVisibility(View.VISIBLE);
                        return;
                    }
                }
                activeSkill = null;
                skillChip.setVisibility(View.GONE);
            }

            @Override
            public void err(String message) {
                activeSkill = null;
                skillChip.setVisibility(View.GONE);
            }
        });
    }

    private void send() {
        if (sending) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        JSONArray history = store.history();
        appendHistory(history, "user", text);
        addBubble("user", text, false);
        input.setText("");
        sending = true;
        send.setEnabled(false);
        send.setAlpha(0.45f);

        LinearLayout wrap = addBubble("assistant", "Thinking…", true);
        thinking = (TextView) wrap.getChildAt(0);
        scrollToBottom();

        JSONArray payload = new JSONArray();
        int start = Math.max(0, history.length() - 20);
        for (int i = start; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item != null) payload.put(item);
        }

        api.chat(payload, store.model(), activeSkill, new Api.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject value) {
                sending = false;
                send.setEnabled(true);
                send.setAlpha(1f);
                String reply = value.optString("reply", "").trim();
                if (reply.isEmpty()) reply = "(Hermes returned an empty reply)";
                if (thinking != null) {
                    thinking.setText(reply);
                    thinking.setTextColor(Ui.color(activity, R.color.fd_text));
                    thinking = null;
                }
                JSONArray now = store.history();
                appendHistory(now, "assistant", reply);
                scrollToBottom();
            }

            @Override
            public void err(String message) {
                sending = false;
                send.setEnabled(true);
                send.setAlpha(1f);
                if (thinking != null) {
                    thinking.setText("Error: " + message);
                    thinking.setTextColor(Ui.color(activity, R.color.fd_danger));
                    thinking = null;
                }
                activity.setOnline(false, "OFFLINE");
                scrollToBottom();
            }
        });
    }

    private void appendHistory(JSONArray history, String role, String content) {
        try {
            history.put(new JSONObject().put("role", role).put("content", content));
            while (history.length() > 24) history.remove(0);
        } catch (JSONException ignored) {
        }
        store.setHistory(history);
    }

    private LinearLayout addBubble(String role, String text, boolean muted) {
        boolean user = "user".equals(role);
        LinearLayout row = Ui.row(activity);
        row.setGravity(user ? Gravity.END : Gravity.START);

        TextView bubble = new TextView(activity);
        bubble.setText(text);
        bubble.setTextSize(12.5f);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(0f, 1.32f);
        bubble.setPadding(Ui.dp(activity, 13), Ui.dp(activity, 10),
                Ui.dp(activity, 13), Ui.dp(activity, 10));
        int max = Ui.screenWidth(activity) - Ui.dp(activity, 82);
        bubble.setMaxWidth(max);
        if (user) {
            bubble.setTextColor(Ui.color(activity, R.color.fd_text));
            bubble.setBackground(Ui.roundStroke(activity, 0xFF1B2540,
                    Ui.color(activity, R.color.fd_primary), 16));
        } else {
            bubble.setTextColor(Ui.color(activity, muted ? R.color.fd_muted : R.color.fd_text));
            bubble.setBackgroundResource(R.drawable.bg_card);
        }
        row.addView(bubble);

        LinearLayout wrapper = Ui.column(activity);
        wrapper.setPadding(0, Ui.dp(activity, 7), 0, 0);
        wrapper.addView(row, Ui.matchWrap());
        messages.addView(wrapper, Ui.matchWrap());
        return wrapper;
    }

    private void newChat() {
        store.clearHistory();
        thinking = null;
        sending = false;
        send.setEnabled(true);
        send.setAlpha(1f);
        renderHistory();
        Ui.toast(activity, "New chat");
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
