package de.badaix.snapcast;

import android.content.Context;
import android.content.Intent;

import de.badaix.snapcast.calendar.CalendarAlarmScheduler;

/**
 * Fires from AlarmManager 15 seconds before an upcoming calendar notification's
 * play_datetime (ACTION_ALARM_START) and again 5 minutes later (ACTION_ALARM_STOP),
 * driving Snapclient start/stop and re-arming the next alarm.
 */
public class CalendarAlarmReceiver extends android.content.BroadcastReceiver {
    public static final String ACTION_ALARM_START = "de.badaix.snapcast.ALARM_START";
    public static final String ACTION_ALARM_STOP = "de.badaix.snapcast.ALARM_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ALARM_START.equals(action)) {
            BroadcastReceiver.startService(context, SnapclientService.ACTION_START);
            CalendarAlarmScheduler.scheduleStop(context);
        } else if (ACTION_ALARM_STOP.equals(action)) {
            BroadcastReceiver.startService(context, SnapclientService.ACTION_STOP);
            CalendarAlarmScheduler.scheduleNext(context);
        }
    }
}
