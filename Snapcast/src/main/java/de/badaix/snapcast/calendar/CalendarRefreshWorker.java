package de.badaix.snapcast.calendar;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.badaix.snapcast.utils.Settings;

/**
 * Periodically re-fetches the Calendar Alarms feed in the background (see
 * CalendarAlarmScheduler#schedulePeriodicRefresh) and re-arms the next start alarm,
 * so the schedule stays current even if the phone was out of contact with the home
 * server for a while. Failures (e.g. server unreachable) are retried with backoff
 * rather than treated as an error, since that's the expected common case.
 */
public class CalendarRefreshWorker extends Worker {
    private static final String TAG = "CalendarRefreshWorker";

    public CalendarRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (Settings.getInstance(context).getCalendarAlarmsUrl().trim().isEmpty()) {
            return Result.success();
        }

        try {
            CalendarAlarmScheduler.fetchAndStoreSync(context);
            CalendarAlarmScheduler.scheduleNext(context);
            return Result.success();
        } catch (Exception e) {
            Log.d(TAG, "Periodic calendar refresh failed, will retry", e);
            return Result.retry();
        }
    }
}
