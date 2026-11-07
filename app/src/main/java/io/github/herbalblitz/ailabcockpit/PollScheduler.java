package io.github.herbalblitz.ailabcockpit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** Schedules a periodic experiment-alert poll. */
public final class PollScheduler {

    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    private static final long FIRST_MS = 60 * 1000L;
    private static final int REQUEST = 41;

    private PollScheduler() {
    }

    public static void schedule(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST,
                new Intent(context, PollReceiver.class), flags);
        manager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + FIRST_MS, INTERVAL_MS, pending);
    }
}
