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

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import de.badaix.snapcast.utils.Log;

public class LogActivity extends AppCompatActivity {

    // Ordered to match res/values/ids.xml's log_level_array, so the spinner's
    // selected index can be used directly to look up the minimum priority.
    private static final int[] LEVELS = {Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR};

    private TextView tvLog;
    private String rawLogs = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        tvLog = findViewById(R.id.tvLog);

        Spinner spinnerLogLevel = findViewById(R.id.spinnerLogLevel);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.log_level_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLogLevel.setAdapter(adapter);
        spinnerLogLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showFilteredLogs(LEVELS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        new Thread(() -> {
            rawLogs = Log.readLogs();
            runOnUiThread(() -> showFilteredLogs(LEVELS[spinnerLogLevel.getSelectedItemPosition()]));
        }).start();
    }

    private void showFilteredLogs(int minPriority) {
        String logs = Log.filterByPriority(rawLogs, minPriority);
        tvLog.setText(logs.isEmpty() ? getString(R.string.no_logs) : logs);
    }
}
