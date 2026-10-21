package dev.syed.tvpilot.tv;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** A small in-memory log shown on the main screen (and in logcat as "TVPilotTV"). */
final class Log {
    private Log() {}
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static synchronized void w(String s) {
        android.util.Log.i("TVPilotTV", s);
        LINES.addFirst(TIME.format(new Date()) + "  " + s);
        while (LINES.size() > 40) LINES.removeLast();
    }

    static synchronized String recent(int n) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String l : LINES) {
            if (i++ >= n) break;
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        return b.toString();
    }
}
