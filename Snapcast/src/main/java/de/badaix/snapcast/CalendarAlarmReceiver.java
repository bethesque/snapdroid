package de.badaix.snapcast;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.badaix.snapcast.calendar.CalendarAlarmScheduler;

/**
 * Fires from AlarmManager 45 seconds before an upcoming calendar notification's
 * play_datetime (ACTION_ALARM_START) and again 5 minutes later (ACTION_ALARM_STOP),
 * driving Snapclient start/stop and re-arming the next alarm.
 */
public class CalendarAlarmReceiver extends android.content.BroadcastReceiver {
    private static final String TAG = "CalendarAlarmReceiver";
    public static final String ACTION_ALARM_START = "de.badaix.snapcast.ALARM_START";
    public static final String ACTION_ALARM_STOP = "de.badaix.snapcast.ALARM_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ALARM_START.equals(action)) {
            android.content.BroadcastReceiver.PendingResult pendingResult = goAsync();
            Context appContext = context.getApplicationContext();
            new Thread(() -> {
                try {
                    if (CalendarAlarmScheduler.isSnapserverReachable(appContext)) {
                        BroadcastReceiver.startService(appContext, SnapclientService.ACTION_START);
                        CalendarAlarmScheduler.scheduleStop(appContext);
                    } else {
                        Log.d(TAG, "onReceive: snapserver unreachable (not on home network?), skipping this occurrence");
                        CalendarAlarmScheduler.scheduleNext(appContext);
                    }
                } finally {
                    pendingResult.finish();
                }
            }).start();
        } else if (ACTION_ALARM_STOP.equals(action)) {
            BroadcastReceiver.startService(context, SnapclientService.ACTION_STOP);
            android.content.BroadcastReceiver.PendingResult pendingResult = goAsync();
            Context appContext = context.getApplicationContext();
            new Thread(() -> {
                try {
                    // The alarm may have been snoozed since this occurrence was scheduled, so
                    // refresh the feed before re-arming; fall back to the existing data if the
                    // refresh fails rather than leaving the next alarm unscheduled.
                    CalendarAlarmScheduler.fetchAndStoreSync(appContext);
                } catch (Exception e) {
                    Log.w(TAG, "onReceive: calendar refresh failed, using existing data", e);
                } finally {
                    CalendarAlarmScheduler.scheduleNext(appContext);
                    pendingResult.finish();
                }
            }).start();
        }
    }
}
