package io.github.herbalblitz.ailabcockpit;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared view helpers for the dark cockpit look. */
public final class Ui {

    private Ui() {
    }

    // ---------------------------------------------------------------- metrics

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int color(Context context, int id) {
        return context.getResources().getColor(id, context.getTheme());
    }

    public static Typeface font(Context context, int id) {
        try {
            return context.getResources().getFont(id);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    public static int screenWidth(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    // ---------------------------------------------------------------- layout

    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public static LinearLayout card(Context context) {
        LinearLayout view = column(context);
        view.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(context, 16);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    public static LinearLayout softCard(Context context) {
        LinearLayout view = column(context);
        view.setBackgroundResource(R.drawable.bg_card_soft);
        int pad = dp(context, 14);
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    public static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float value) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, value);
    }

    public static void margin(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) raw).setMargins(left, top, right, bottom);
        }
    }

    public static View space(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(lp(1, dp(context, heightDp)));
        return view;
    }

    public static void divider(Context context, LinearLayout parent) {
        View line = new View(context);
        line.setBackgroundColor(color(context, R.color.fd_stroke));
        LinearLayout.LayoutParams params = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        params.topMargin = dp(context, 10);
        params.bottomMargin = dp(context, 10);
        line.setLayoutParams(params);
        parent.addView(line);
    }

    // ----------------------------------------------------------------- text

    public static TextView text(Context context, String value, float sizeSp, int colorId) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(context, colorId));
        view.setTypeface(font(context, R.font.manrope_regular));
        view.setLineSpacing(0f, 1.28f);
        return view;
    }

    public static TextView bold(Context context, String value, float sizeSp, int colorId) {
        TextView view = text(context, value, sizeSp, colorId);
        view.setTypeface(font(context, R.font.manrope_bold));
        return view;
    }

    public static TextView label(Context context, String value) {
        TextView view = bold(context, value == null ? "" : value.toUpperCase(Locale.US),
                9.5f, R.color.fd_muted);
        view.setLetterSpacing(0.14f);
        view.setAllCaps(true);
        return view;
    }

    public static TextView chip(Context context, String label, String kind) {
        TextView view = new TextView(context);
        view.setTextSize(9.5f);
        view.setTypeface(font(context, R.font.manrope_bold));
        view.setLetterSpacing(0.08f);
        view.setAllCaps(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4));
        styleChip(view, kind);
        view.setText(label == null ? "" : label);
        return view;
    }

    public static void styleChip(TextView view, String kind) {
        Context context = view.getContext();
        String key = kind == null ? "neutral" : kind;
        switch (key) {
            case "success":
                view.setBackgroundResource(R.drawable.bg_chip_success);
                view.setTextColor(color(context, R.color.fd_accent));
                break;
            case "warn":
                view.setBackgroundResource(R.drawable.bg_chip_warn);
                view.setTextColor(color(context, R.color.fd_gold));
                break;
            case "danger":
                view.setBackgroundResource(R.drawable.bg_chip_danger);
                view.setTextColor(color(context, R.color.fd_danger));
                break;
            case "info":
                view.setBackgroundResource(R.drawable.bg_chip_info);
                view.setTextColor(color(context, R.color.fd_primary));
                break;
            default:
                view.setBackgroundResource(R.drawable.bg_chip);
                view.setTextColor(color(context, R.color.fd_muted));
                break;
        }
    }

    public static void statusChip(TextView view, String status) {
        view.setText(statusLabel(status));
        styleChip(view, statusKind(status));
    }

    public static String statusLabel(String status) {
        if (status == null) return "QUEUED";
        switch (status) {
            case "running":
                return "RUNNING";
            case "queued":
                return "QUEUED";
            case "succeeded":
            case "completed":
                return "DONE";
            case "failed":
                return "FAILED";
            case "cancelled":
                return "STOPPED";
            default:
                return status.toUpperCase(Locale.US);
        }
    }

    public static String statusKind(String status) {
        if (status == null) return "neutral";
        switch (status) {
            case "running":
                return "info";
            case "succeeded":
            case "completed":
                return "success";
            case "failed":
                return "danger";
            case "queued":
            case "cancelled":
            default:
                return "neutral";
        }
    }

    // --------------------------------------------------------------- widgets

    public static TextView button(Context context, String label, boolean primary) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(12.5f);
        view.setTypeface(font(context, R.font.manrope_bold));
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        view.setBackgroundResource(primary ? R.drawable.bg_button_primary : R.drawable.bg_button_ghost);
        view.setTextColor(color(context, primary ? R.color.fd_white : R.color.fd_text));
        return view;
    }

    public static TextView smallButton(Context context, String label, boolean primary) {
        TextView view = button(context, label, primary);
        view.setTextSize(10.5f);
        view.setPadding(dp(context, 11), dp(context, 8), dp(context, 11), dp(context, 8));
        return view;
    }

    public static ImageView icon(Context context, int drawable, int colorId) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setColorFilter(color(context, colorId));
        view.setLayoutParams(lp(dp(context, 22), dp(context, 22)));
        return view;
    }

    public static ProgressBar progress(Context context) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressDrawable(context.getDrawable(R.drawable.bg_progress));
        bar.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6)));
        bar.setMinimumHeight(dp(context, 6));
        return bar;
    }

    public static GradientDrawable round(Context context, int fillColor, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable roundStroke(Context context, int fillColor,
                                               int strokeColor, float radiusDp) {
        GradientDrawable drawable = round(context, fillColor, radiusDp);
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    // ---------------------------------------------------------------- values

    public static String bytes(long value) {
        if (value >= 1_073_741_824L) {
            return String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0);
        }
        if (value >= 1_048_576L) {
            return String.format(Locale.US, "%.0f MB", value / 1_048_576.0);
        }
        if (value >= 1024L) {
            return String.format(Locale.US, "%.0f KB", value / 1024.0);
        }
        return value + " B";
    }

    public static int percent(long used, long total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, Math.round(used * 100.0 / total)));
    }

    public static String ago(double epochSeconds) {
        if (epochSeconds <= 0) return "";
        long delta = Math.max(0, (long) (System.currentTimeMillis() / 1000.0 - epochSeconds));
        if (delta < 60) return "now";
        if (delta < 3600) return (delta / 60) + "m ago";
        if (delta < 86400) return (delta / 3600) + "h ago";
        return (delta / 86400) + "d ago";
    }

    public static String dateTime(double epochSeconds) {
        if (epochSeconds <= 0) return "—";
        return new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(new Date((long) (epochSeconds * 1000)));
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void copy(Context context, String label, String value) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(label, value));
            toast(context, context.getString(R.string.copied));
        }
    }
}
