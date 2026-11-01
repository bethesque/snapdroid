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

package au.bethesque.calendaralarms;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import de.badaix.snapcast.AboutActivity;
import de.badaix.snapcast.BroadcastReceiver;
import de.badaix.snapcast.LogActivity;
import de.badaix.snapcast.SnapclientActivity;
import de.badaix.snapcast.SnapclientService;

public class MainActivity extends AppCompatActivity {

    private static final DateTimeFormatter NEXT_NOTIFICATION_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("EEE d MMM, h:mm")
            .appendText(ChronoField.AMPM_OF_DAY, Map.of(0L, "am", 1L, "pm"))
            .toFormatter(Locale.getDefault());

    private CoordinatorLayout coordinatorLayout;
    private TextView tvNotificationsDisabled;
    private TextView tvNextLabel;
    private TextView tvNextNotification;
    private TextView tvNextDate;
    private Button btnStopPhoneAlarm;
    private Button btnStopAlarmForAll;
    private Button btnSnoozeAlarmForAll;

    private boolean serviceBound = false;
    private SnapclientService snapclientService;

    private final ServiceConnection snapclientServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            snapclientService = ((SnapclientService.LocalBinder) service).getService();
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            snapclientService = null;
        }
    };

    // Held in a field because SharedPreferences only keeps a weak reference to listeners
    private final SharedPreferences.OnSharedPreferenceChangeListener alarmModeListener = (prefs, key) -> {
        if (Settings.KEY_ALARM_MODE.equals(key)) {
            updateButtons();
            // The alarm that just finished may still have been showing as the next event
            updateNextNotification();
        }
    };

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

        tvNotificationsDisabled = findViewById(R.id.tvNotificationsDisabled);
        styleAsSnackbar(tvNotificationsDisabled);
        tvNextLabel = findViewById(R.id.tvNextLabel);
        tvNextNotification = findViewById(R.id.tvNextNotification);
        tvNextDate = findViewById(R.id.tvNextDate);
        btnStopPhoneAlarm = findViewById(R.id.btnStopPhoneAlarm);
        btnStopPhoneAlarm.setOnClickListener(v -> {
            if (Settings.getInstance(this).getAlarmMode() == Settings.AlarmMode.OFFLINE)
                stopOfflineAlarm();
            else
                stopPhoneAlarm();
        });

        btnStopAlarmForAll = findViewById(R.id.btnStopAlarmForAll);
        btnStopAlarmForAll.setText(buttonText(R.string.action_stop_alarm_for_all,
                R.string.action_stop_alarm_for_all_subtitle));
        btnStopAlarmForAll.setOnClickListener(v -> stopAlarmForAll());

        btnSnoozeAlarmForAll = findViewById(R.id.btnSnoozeAlarmForAll);
        btnSnoozeAlarmForAll.setText(buttonText(R.string.action_snooze_alarm_for_all,
                R.string.action_snooze_alarm_for_all_subtitle));
        btnSnoozeAlarmForAll.setOnClickListener(v -> snoozeAlarmForAll());

        askNotificationPermission();
        CalendarAlarmScheduler.schedulePeriodicRefresh(this);
    }

    private CharSequence buttonText(int titleRes, int subtitleRes) {
        SpannableStringBuilder text =
                new SpannableStringBuilder(getString(titleRes)).append('\n');
        int subtitleStart = text.length();
        text.append(getString(subtitleRes));
        text.setSpan(new RelativeSizeSpan(0.65f), subtitleStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNextNotification();
        invalidateOptionsMenu();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind without BIND_AUTO_CREATE: this only connects if SnapclientService is
        // already running, letting stopPhoneAlarm() know whether there's anything to stop.
        bindService(new Intent(this, SnapclientService.class), snapclientServiceConnection, 0);
        Settings.getInstance(this).getPrefs().registerOnSharedPreferenceChangeListener(alarmModeListener);
        updateButtons();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Settings.getInstance(this).getPrefs().unregisterOnSharedPreferenceChangeListener(alarmModeListener);
        if (serviceBound) {
            unbindService(snapclientServiceConnection);
            serviceBound = false;
            snapclientService = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean enabled = Settings.getInstance(this).isNotificationsEnabled();
        MenuItem toggle = menu.findItem(R.id.action_toggle_notifications);
        toggle.setIcon(enabled ? R.drawable.volume_up_24px : R.drawable.volume_off_24px);
        toggle.setTitle(enabled ? R.string.action_disable_notifications : R.string.action_enable_notifications);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_toggle_notifications) {
            Settings settings = Settings.getInstance(this);
            settings.setNotificationsEnabled(!settings.isNotificationsEnabled());
            invalidateOptionsMenu();
            updateNextNotification();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, ServerSettingsActivity.class));
            return true;
        } else if (id == R.id.action_snapclient) {
            startActivity(new Intent(this, SnapclientActivity.class));
            return true;
        } else if (id == R.id.action_refresh_notifications) {
            refreshCalendarNotifications();
            return true;
        } else if (id == R.id.action_logs) {
            startActivity(new Intent(this, LogActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void refreshCalendarNotifications() {
        if (Settings.getInstance(this).getActiveCalendarAlarmsUrl().trim().isEmpty()) {
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
        if (!serviceBound || !snapclientService.isRunning()) {
            showWarning(getString(R.string.snapclient_not_running));
            return;
        }

        // The scheduled stop is left armed: it clears the buttons and re-arms the next alarm
        BroadcastReceiver.startService(this, SnapclientService.ACTION_STOP);
        showWarning(getString(R.string.phone_alarm_stopped));
    }

    private void stopOfflineAlarm() {
        // Cancel the scheduled playback too, in case it hasn't started yet
        CalendarAlarmScheduler.cancelOfflinePlayback(this);
        startService(new Intent(this, OfflineAlarmService.class).setAction(OfflineAlarmService.ACTION_STOP));
        showWarning(getString(R.string.offline_alarm_stopped));
    }

    /**
     * Shows the buttons for how the current alarm is being played: MUTE/STOP/SNOOZE while
     * the snapclient is playing it, just STOP while it's being played offline, or none.
     */
    private void updateButtons() {
        Settings.AlarmMode mode = Settings.getInstance(this).getAlarmMode();
        boolean snapserver = mode == Settings.AlarmMode.SNAPSERVER;
        boolean offline = mode == Settings.AlarmMode.OFFLINE;

        btnStopPhoneAlarm.setText(buttonText(offline ? R.string.action_stop_offline_alarm : R.string.action_stop_phone_alarm,
                R.string.action_stop_phone_alarm_subtitle));
        btnStopPhoneAlarm.setVisibility(snapserver || offline ? View.VISIBLE : View.GONE);
        btnStopAlarmForAll.setVisibility(snapserver ? View.VISIBLE : View.GONE);
        btnSnoozeAlarmForAll.setVisibility(snapserver ? View.VISIBLE : View.GONE);
        matchButtonWidths();
    }

    /**
     * Sizes the visible buttons to the widest of them, measuring each at its natural width
     * so the result doesn't depend on a width set by a previous call.
     */
    private void matchButtonWidths() {
        List<Button> visible = new ArrayList<>();
        for (Button button : new Button[]{btnStopPhoneAlarm, btnStopAlarmForAll, btnSnoozeAlarmForAll}) {
            if (button.getVisibility() == View.VISIBLE)
                visible.add(button);
        }

        int width = 0;
        for (Button button : visible) {
            button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            width = Math.max(width, button.getMeasuredWidth());
        }
        for (Button button : visible) {
            button.getLayoutParams().width = width;
            button.requestLayout();
        }
    }

    private void stopAlarmForAll() {
        if (Settings.getInstance(this).getActiveCalendarAlarmsUrl().trim().isEmpty()) {
            showWarning(getString(R.string.calendar_alarms_url_empty));
            return;
        }

        CalendarAlarmScheduler.stopOnServer(this,
                () -> showWarning(getString(R.string.alarm_stopped_for_all)),
                e -> showWarning(getString(R.string.stop_alarm_for_all_failed, e.getMessage())));
    }

    private void snoozeAlarmForAll() {
        if (Settings.getInstance(this).getActiveCalendarAlarmsUrl().trim().isEmpty()) {
            showWarning(getString(R.string.calendar_alarms_url_empty));
            return;
        }

        CalendarAlarmScheduler.snoozeOnServer(this,
                () -> showWarning(getString(R.string.alarm_snoozed_for_all)),
                e -> showWarning(getString(R.string.snooze_alarm_for_all_failed, e.getMessage())));
    }

    private void updateNextNotification() {
        Settings settings = Settings.getInstance(this);
        boolean enabled = settings.isNotificationsEnabled();
        tvNotificationsDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);
        float alpha = enabled ? 1f : 0.4f;
        tvNextLabel.setAlpha(alpha);
        tvNextNotification.setAlpha(alpha);
        tvNextDate.setAlpha(alpha);

        Instant after = Instant.now();
        // An offline alarm stopped before its play time is over, so don't keep showing it as next
        if (settings.getAlarmMode() == Settings.AlarmMode.NONE) {
            Instant offlineAlarmPlay = Instant.ofEpochMilli(settings.getOfflineAlarmPlayMillis());
            if (offlineAlarmPlay.isAfter(after))
                after = offlineAlarmPlay;
        }
        List<CalendarNotification> nextGroup = CalendarAlarmScheduler.getNextGroup(this, after);
        if (nextGroup.isEmpty()) {
            tvNextLabel.setVisibility(View.GONE);
            tvNextNotification.setText(R.string.no_upcoming_notification);
            tvNextDate.setVisibility(View.GONE);
        } else {
            String summaries = nextGroup.stream()
                    .map(CalendarNotification::getSummary)
                    .collect(Collectors.joining(", "));
            String when = nextGroup.get(0).getPlayDateTime().format(NEXT_NOTIFICATION_FORMATTER);
            tvNextLabel.setVisibility(View.VISIBLE);
            tvNextNotification.setText(summaries);
            tvNextDate.setText(when);
            tvNextDate.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Gives a view the same colours and shape as the default Material Snackbar used by
     * showWarning(), which blends theme colours in a way plain XML attributes can't express.
     */
    private void styleAsSnackbar(TextView view) {
        int surface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(4 * getResources().getDisplayMetrics().density);
        background.setColor(MaterialColors.layer(view, com.google.android.material.R.attr.colorSurface,
                com.google.android.material.R.attr.colorOnSurface, 0.8f));
        view.setBackground(background);
        view.setTextColor(MaterialColors.compositeARGBWithAlpha(surface, Math.round(0.87f * 255)));
    }

    private void showWarning(String msg) {
        Snackbar.make(coordinatorLayout, msg, Snackbar.LENGTH_LONG).show();
    }
}
