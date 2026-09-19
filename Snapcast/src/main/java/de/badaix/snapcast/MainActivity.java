/*
 *     This file is part of snapcast
 *     Copyright (C) 2014-2018  Johannes Pohl
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.badaix.snapcast;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import de.badaix.snapcast.calendar.CalendarAlarmScheduler;
import de.badaix.snapcast.calendar.CalendarNotification;
import de.badaix.snapcast.utils.Settings;

public class MainActivity extends AppCompatActivity {

    private static final DateTimeFormatter NEXT_NOTIFICATION_FORMATTER = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault());

    private CoordinatorLayout coordinatorLayout;
    private TextView tvNextNotification;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT)
                            .show();
                } else {
                    Toast.makeText(this, "Snapdroid can't post notifications without POST_NOTIFICATIONS permission",
                            Toast.LENGTH_LONG).show();
                }
            });

    private void askNotificationPermission() {
        // This is only necessary for API Level > 33 (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) {
                // FCM SDK (and your app) can post notifications.
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        coordinatorLayout = findViewById(R.id.homeCoordinatorLayout);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvNextNotification = findViewById(R.id.tvNextNotification);
        Button btnRefreshNotifications = findViewById(R.id.btnRefreshNotifications);
        btnRefreshNotifications.setOnClickListener(v -> refreshCalendarNotifications());

        Button btnStopPhoneAlarm = findViewById(R.id.btnStopPhoneAlarm);
        btnStopPhoneAlarm.setOnClickListener(v -> stopPhoneAlarm());

        askNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNextNotification();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            startActivity(new Intent(this, ServerSettingsActivity.class));
            return true;
        } else if (id == R.id.action_snapclient) {
            startActivity(new Intent(this, SnapclientActivity.class));
            return true;
        } else if (id == R.id.action_refresh_notifications) {
            refreshCalendarNotifications();
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void refreshCalendarNotifications() {
        if (Settings.getInstance(this).getCalendarAlarmsUrl().trim().isEmpty()) {
            showWarning(getString(R.string.calendar_alarms_url_empty));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (!alarmManager.canScheduleExactAlarms()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.exact_alarm_permission_title)
                        .setMessage(R.string.exact_alarm_permission_message)
                        .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }

        CalendarAlarmScheduler.fetchAndStore(this,
                () -> {
                    boolean hasUpcoming = CalendarAlarmScheduler.scheduleNext(this);
                    updateNextNotification();
                    showWarning(getString(hasUpcoming
                            ? R.string.calendar_notifications_refreshed
                            : R.string.calendar_notifications_none_upcoming));
                },
                e -> showWarning(getString(R.string.calendar_notifications_fetch_failed, e.getMessage())));
    }

    private void stopPhoneAlarm() {
        BroadcastReceiver.startService(this, SnapclientService.ACTION_STOP);
        CalendarAlarmScheduler.cancelStop(this);
        showWarning(getString(R.string.phone_alarm_stopped));
    }

    private void updateNextNotification() {
        CalendarNotification next = CalendarAlarmScheduler.getNext(this);
        if (next == null)
            tvNextNotification.setText(R.string.no_upcoming_notification);
        else
            tvNextNotification.setText(getString(R.string.next_notification, next.getPlayDateTime().format(NEXT_NOTIFICATION_FORMATTER)));
    }

    private void showWarning(String msg) {
        Snackbar.make(coordinatorLayout, msg, Snackbar.LENGTH_LONG).show();
    }
}
