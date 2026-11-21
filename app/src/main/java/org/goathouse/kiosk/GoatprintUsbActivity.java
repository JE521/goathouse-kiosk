package org.goathouse.kiosk;

import android.app.Activity;
import android.os.Bundle;

/** Target of USB_DEVICE_ATTACHED for the fingerprint reader: gives the app standing USB permission, wakes the engine. */
public class GoatprintUsbActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Goatprint.get(this).start();
        finish();
    }
}
