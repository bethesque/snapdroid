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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;

/**
 * Created by johannes on 21.02.16.
 */
public class Settings {
    public static final String KEY_ALARM_MODE = "alarmMode";

    /**
     * How the current calendar alarm is being played, decided at the pre-alarm check.
     */
    public enum AlarmMode {
        NONE,
        SNAPSERVER,
        OFFLINE
    }

    private static Settings instance = null;
    private Context ctx = null;

    public static Settings getInstance(Context context) {
        if (instance == null) {
            instance = new Settings();
        }
        if (context != null)
            instance.ctx = context.getApplicationContext();

        return instance;
    }

    public Context getContext() {
        return ctx;
    }

    public Resources getResources() {
        return ctx.getResources();
    }

    public SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public Settings put(String key, String value) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(key, value);
        editor.apply();
        return this;
    }

    public Settings put(String key, boolean value) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putBoolean(key, value);
        editor.apply();
        return this;
    }

    public Settings put(String key, float value) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putFloat(key, value);
        editor.apply();
        return this;
    }

    public Settings put(String key, int value) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putInt(key, value);
        editor.apply();
        return this;
    }

    public Settings put(String key, long value) {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putLong(key, value);
        editor.apply();
        return this;
    }

    public String getString(String key, String defaultValue) {
        return getPrefs().getString(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return getPrefs().getBoolean(key, defaultValue);
    }

    public float getFloat(String key, float defaultValue) {
        return getPrefs().getFloat(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return getPrefs().getInt(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return getPrefs().getLong(key, defaultValue);
    }

    public String getHost() {
        return getString("host", "");
    }

    public int getStreamPort() {
        return getInt("streamPort", 1704);
    }

    public int getControlPort() {
        return getInt("controlPort", getStreamPort() + 1);
    }

    public String getAudioEngine() {
        return "Auto"; // getString("audioEngine", "Auto");
    }

    public boolean doResample() {
        return true; // getBoolean("resample", true);
    }

    public void setAudioEngine(String engine, boolean resample) {
        put("audioEngine", engine);
        put("resample", resample);
    }

    public void setHost(String host, int streamPort, int controlPort) {
        put("host", host);
        put("streamPort", streamPort);
        put("controlPort", controlPort);
    }

    public boolean isSnapclientEnabled() {
        return getBoolean("snapclientEnabled", true);
    }

    public void setSnapclientEnabled(boolean snapclientEnabled) {
        put("snapclientEnabled", snapclientEnabled);
    }

    public boolean isNotificationsEnabled() {
        return getBoolean("notificationsEnabled", true);
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        put("notificationsEnabled", notificationsEnabled);
    }

    public String getCalendarAlarmsUrl() {
        return getString("calendarAlarmsUrl", "");
    }

    public void setCalendarAlarmsUrl(String calendarAlarmsUrl) {
        put("calendarAlarmsUrl", calendarAlarmsUrl);
    }

    public boolean isCalendarAlarmsDev() {
        return getBoolean("calendarAlarmsDev", false);
    }

    public void setCalendarAlarmsDev(boolean calendarAlarmsDev) {
        put("calendarAlarmsDev", calendarAlarmsDev);
    }

    public String getCalendarAlarmsDevUrl() {
        return getString("calendarAlarmsDevUrl", "");
    }

    public void setCalendarAlarmsDevUrl(String calendarAlarmsDevUrl) {
        put("calendarAlarmsDevUrl", calendarAlarmsDevUrl);
    }

    /**
     * The Calendar Alarms URL to actually connect to: the dev URL when dev is ticked,
     * otherwise the normal URL.
     */
    public String getActiveCalendarAlarmsUrl() {
        return isCalendarAlarmsDev() ? getCalendarAlarmsDevUrl() : getCalendarAlarmsUrl();
    }

    public String getCalendarNotificationsJson() {
        return getString("calendarNotificationsJson", "");
    }

    public void setCalendarNotificationsJson(String calendarNotificationsJson) {
        put("calendarNotificationsJson", calendarNotificationsJson);
    }

    public int getCalendarRefreshIntervalMinutes() {
        return getInt("calendarRefreshIntervalMinutes", 15);
    }

    public void setCalendarRefreshIntervalMinutes(int calendarRefreshIntervalMinutes) {
        put("calendarRefreshIntervalMinutes", calendarRefreshIntervalMinutes);
    }

    public AlarmMode getAlarmMode() {
        try {
            return AlarmMode.valueOf(getString(KEY_ALARM_MODE, AlarmMode.NONE.name()));
        } catch (IllegalArgumentException e) {
            return AlarmMode.NONE;
        }
    }

    public void setAlarmMode(AlarmMode alarmMode) {
        put(KEY_ALARM_MODE, alarmMode.name());
    }

    /**
     * The play_datetime (epoch millis) of the most recently scheduled offline alarm, or 0.
     */
    public long getOfflineAlarmPlayMillis() {
        return getLong("offlineAlarmPlayMillis", 0);
    }

    public void setOfflineAlarmPlayMillis(long offlineAlarmPlayMillis) {
        put("offlineAlarmPlayMillis", offlineAlarmPlayMillis);
    }

    /**
     * Sets the alarm mode back to NONE, but only if it's still the given mode, so that a
     * late stop from one mode can't clear an alarm that's since started in the other.
     */
    public void clearAlarmMode(AlarmMode alarmMode) {
        if (getAlarmMode() == alarmMode)
            setAlarmMode(AlarmMode.NONE);
    }
}
