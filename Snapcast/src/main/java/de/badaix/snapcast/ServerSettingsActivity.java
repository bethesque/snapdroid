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

import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import de.badaix.snapcast.calendar.CalendarAlarmScheduler;
import de.badaix.snapcast.utils.NsdHelper;
import de.badaix.snapcast.utils.Settings;

public class ServerSettingsActivity extends AppCompatActivity implements View.OnClickListener {

    private static final String TAG = "ServerSettings";

    private Button btnScan;
    private EditText editHost;
    private EditText editStreamPort;
    private EditText editControlPort;
    private EditText editCalendarAlarmsUrl;
    private EditText editCalendarRefreshIntervalMinutes;
    private CheckBox checkBoxResample;
    private Spinner spinnerAudioEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        btnScan = findViewById(R.id.btn_scan);
        btnScan.setOnClickListener(this);

        editHost = findViewById(R.id.host);
        editStreamPort = findViewById(R.id.stream_port);
        editControlPort = findViewById(R.id.control_port);
        editCalendarAlarmsUrl = findViewById(R.id.calendar_alarms_url);
        editCalendarRefreshIntervalMinutes = findViewById(R.id.calendar_refresh_interval_minutes);

        spinnerAudioEngine = findViewById(R.id.audio_engine);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.audio_engine_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAudioEngine.setAdapter(adapter);
        checkBoxResample = findViewById(R.id.checkBoxResample);

        loadSettings();
    }

    private void loadSettings() {
        Settings settings = Settings.getInstance(this);
        editHost.setText(settings.getHost());
        editStreamPort.setText(Integer.toString(settings.getStreamPort()));
        editControlPort.setText(Integer.toString(settings.getControlPort()));
        for (int i = 0; i < spinnerAudioEngine.getCount(); ++i) {
            if (spinnerAudioEngine.getItemAtPosition(i).toString().equals(settings.getAudioEngine())) {
                spinnerAudioEngine.setSelection(i);
                break;
            }
        }
        checkBoxResample.setChecked(settings.doResample());
        editCalendarAlarmsUrl.setText(settings.getCalendarAlarmsUrl());
        editCalendarRefreshIntervalMinutes.setText(Integer.toString(settings.getCalendarRefreshIntervalMinutes()));
    }

    /**
     * @return true if settings were valid and saved, false if the save was rejected
     * (the caller should keep the settings screen open so the user can fix the input).
     */
    private boolean saveSettings() {
        String host = editHost.getText().toString();
        int streamPort;
        int controlPort;
        try {
            streamPort = Integer.parseInt(editStreamPort.getText().toString());
            controlPort = Integer.parseInt(editControlPort.getText().toString());
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return false;
        }

        int refreshIntervalMinutes;
        try {
            refreshIntervalMinutes = Integer.parseInt(editCalendarRefreshIntervalMinutes.getText().toString());
        } catch (NumberFormatException e) {
            refreshIntervalMinutes = -1;
        }
        if (refreshIntervalMinutes < CalendarAlarmScheduler.MIN_REFRESH_INTERVAL_MINUTES) {
            Toast.makeText(this, getString(R.string.calendar_refresh_interval_too_low,
                    CalendarAlarmScheduler.MIN_REFRESH_INTERVAL_MINUTES), Toast.LENGTH_LONG).show();
            return false;
        }

        Settings settings = Settings.getInstance(this);
        settings.setHost(host, streamPort, controlPort);
        settings.setAudioEngine(spinnerAudioEngine.getSelectedItem().toString(), checkBoxResample.isChecked());
        settings.setCalendarAlarmsUrl(editCalendarAlarmsUrl.getText().toString());
        settings.setCalendarRefreshIntervalMinutes(refreshIntervalMinutes);
        CalendarAlarmScheduler.schedulePeriodicRefresh(this);
        return true;
    }

    @Override
    public void onClick(View v) {
        NsdHelper.getInstance(this).startListening("_snapcast._tcp.", "Snapcast", new NsdHelper.NsdHelperListener() {
            @Override
            public void onResolved(NsdHelper nsdHelper, NsdServiceInfo serviceInfo) {
                Log.d(TAG, "onResolved: " + serviceInfo.getHost().getCanonicalHostName());
                editHost.setText(serviceInfo.getHost().getCanonicalHostName());
                editStreamPort.setText(Integer.toString(serviceInfo.getPort()));
                editControlPort.setText(Integer.toString(serviceInfo.getPort() + 1));
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_server_settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_save) {
            if (saveSettings()) {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
