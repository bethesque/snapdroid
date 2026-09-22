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

package au.bethesque.calendaralarms.utils;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drop-in replacement for android.util.Log (same v/d/i/w/e API, so callers just
 * swap the import) that also persists log lines to a rotating file on disk, so
 * they can be read back from within the app (see LogActivity).
 */
public final class Log {

    public static final int VERBOSE = android.util.Log.VERBOSE;
    public static final int DEBUG = android.util.Log.DEBUG;
    public static final int INFO = android.util.Log.INFO;
    public static final int WARN = android.util.Log.WARN;
    public static final int ERROR = android.util.Log.ERROR;

    private static final String TAG = "Log";
    private static final String LOG_FILE_NAME = "snapcast.log";
    private static final String LOG_FILE_BACKUP_NAME = "snapcast.log.1";
    private static final long MAX_LOG_FILE_SIZE = 256 * 1024;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");
    private static final Pattern ENTRY_HEADER = Pattern.compile("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ([VDIWE])/");

    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private static volatile File logFile;
    private static volatile File logFileBackup;

    private Log() {
    }

    public static void init(Context context) {
        File dir = context.getApplicationContext().getFilesDir();
        logFile = new File(dir, LOG_FILE_NAME);
        logFileBackup = new File(dir, LOG_FILE_BACKUP_NAME);
    }

    public static void v(String tag, String msg) {
        log(android.util.Log.VERBOSE, tag, msg, null);
    }

    public static void v(String tag, String msg, Throwable tr) {
        log(android.util.Log.VERBOSE, tag, msg, tr);
    }

    public static void d(String tag, String msg) {
        log(android.util.Log.DEBUG, tag, msg, null);
    }

    public static void d(String tag, String msg, Throwable tr) {
        log(android.util.Log.DEBUG, tag, msg, tr);
    }

    public static void i(String tag, String msg) {
        log(android.util.Log.INFO, tag, msg, null);
    }

    public static void i(String tag, String msg, Throwable tr) {
        log(android.util.Log.INFO, tag, msg, tr);
    }

    public static void w(String tag, String msg) {
        log(android.util.Log.WARN, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable tr) {
        log(android.util.Log.WARN, tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        log(android.util.Log.ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        log(android.util.Log.ERROR, tag, msg, tr);
    }

    // Blocks on file I/O (it hands the read to the same executor that serializes
    // writes, to keep the result consistent) - never call this from the main thread.
    public static String readLogs() {
        try {
            return ioExecutor.submit(Log::readLogsInternal).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException e) {
            android.util.Log.e(TAG, "Failed reading log file", e);
            return "";
        }
    }

    private static void log(int priority, String tag, String msg, Throwable tr) {
        if (tr != null) {
            android.util.Log.println(priority, tag, msg + '\n' + android.util.Log.getStackTraceString(tr));
        } else {
            android.util.Log.println(priority, tag, msg);
        }

        File file = logFile;
        if (file == null) {
            return;
        }
        String line = TIMESTAMP_FORMATTER.format(LocalDateTime.now()) + " " + priorityChar(priority) + "/" + tag + ": " + msg
                + (tr != null ? "\n" + android.util.Log.getStackTraceString(tr) : "");
        ioExecutor.execute(() -> writeLine(file, line));
    }

    private static void writeLine(File file, String line) {
        rotateIfNeeded(file);
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException e) {
            android.util.Log.e(TAG, "Failed writing log file", e);
        }
    }

    private static void rotateIfNeeded(File file) {
        if (file.length() < MAX_LOG_FILE_SIZE) {
            return;
        }
        File backup = logFileBackup;
        if (backup == null) {
            return;
        }
        if (backup.exists() && !backup.delete()) {
            android.util.Log.e(TAG, "Failed deleting old log backup");
        }
        if (!file.renameTo(backup)) {
            android.util.Log.e(TAG, "Failed rotating log file");
        }
    }

    private static String readLogsInternal() {
        StringBuilder sb = new StringBuilder();
        File backup = logFileBackup;
        File file = logFile;
        if (backup != null) {
            appendFileContents(backup, sb);
        }
        if (file != null) {
            appendFileContents(file, sb);
        }
        return sb.toString();
    }

    private static void appendFileContents(File file, StringBuilder sb) {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            android.util.Log.e(TAG, "Failed reading log file: " + file.getName(), e);
        }
    }

    private static char priorityChar(int priority) {
        switch (priority) {
            case android.util.Log.VERBOSE:
                return 'V';
            case android.util.Log.DEBUG:
                return 'D';
            case android.util.Log.INFO:
                return 'I';
            case android.util.Log.WARN:
                return 'W';
            case android.util.Log.ERROR:
                return 'E';
            default:
                return '?';
        }
    }

    private static int priorityFromChar(char c) {
        switch (c) {
            case 'V':
                return VERBOSE;
            case 'D':
                return DEBUG;
            case 'I':
                return INFO;
            case 'W':
                return WARN;
            case 'E':
                return ERROR;
            default:
                return VERBOSE;
        }
    }

    // Keeps whole entries (an entry's stack trace continuation lines have no
    // header of their own, so they're grouped with the header line above them).
    public static String filterByPriority(String logs, int minPriority) {
        StringBuilder result = new StringBuilder();
        StringBuilder entry = null;
        boolean keep = false;
        for (String line : logs.split("\n", -1)) {
            Matcher matcher = ENTRY_HEADER.matcher(line);
            if (matcher.find()) {
                if (keep && entry != null) {
                    result.append(entry);
                }
                entry = new StringBuilder();
                keep = priorityFromChar(matcher.group(1).charAt(0)) >= minPriority;
            }
            if (entry == null) {
                continue;
            }
            entry.append(line).append('\n');
        }
        if (keep && entry != null) {
            result.append(entry);
        }
        return result.toString();
    }
}
