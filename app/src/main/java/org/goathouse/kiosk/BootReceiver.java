package org.goathouse.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the Music Assistant speaker at boot and after an update (Obtainium), even before the dashboard is shown. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        SendspinService.startIfConfigured(c);
    }
}
