package io.github.herbalblitz.ailabcockpit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/** Polls finished experiments and posts local notifications. */
public class PollReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Notify.init(context);
        final Store store = new Store(context);
        if (!store.notifications() || !store.hasToken()) return;

        double since = store.lastAlert();
        if (since <= 0) {
            store.setLastAlert(System.currentTimeMillis() / 1000.0);
            return;
        }

        final PendingResult pending = goAsync();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Api api = new Api();
        api.configure(store.url(), store.token());
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) {
                api.close();
                pending.finish();
            }
        }, 25_000);

        api.alerts(since, new Api.Cb<JSONArray>() {
            @Override
            public void ok(JSONArray jobs) {
                double latest = since;
                for (int i = 0; i < jobs.length(); i++) {
                    JSONObject job = jobs.optJSONObject(i);
                    if (job == null) continue;
                    double at = job.optDouble("finished_at", 0);
                    if (at > latest) latest = at;
                    String status = job.optString("status");
                    String title = job.optString("title", "Experiment");
                    String text;
                    if ("succeeded".equals(status)) {
                        text = "Finished successfully · " + Ui.dateTime(at);
                    } else if ("cancelled".equals(status)) {
                        text = "Stopped by operator · " + Ui.dateTime(at);
                    } else {
                        String error = job.optString("error", "failed");
                        text = "Failed: " + error + " · " + Ui.dateTime(at);
                    }
                    int id = Math.abs(job.optString("id").hashCode());
                    Notify.post(context, id, title, text);
                }
                if (latest > since) store.setLastAlert(latest);
                if (finished.compareAndSet(false, true)) {
                    api.close();
                    pending.finish();
                }
            }

            @Override
            public void err(String message) {
                if (finished.compareAndSet(false, true)) {
                    api.close();
                    pending.finish();
                }
            }
        });
    }
}
