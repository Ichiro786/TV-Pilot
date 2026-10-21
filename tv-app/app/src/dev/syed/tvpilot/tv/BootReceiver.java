package dev.syed.tvpilot.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or an update, switches the mic button service back on (when setup granted the permission). */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Prefs.init(c);
        if (Setup.canWriteSettings(c)) Setup.enableService(c);
    }
}
