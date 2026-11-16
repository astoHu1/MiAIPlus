package io.github.miaiplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Set;

/**
 * Debug-only: lets a device script switch the Phone Use action protocol and put it back. The receiver is
 * protected by DUMP, which only the shell and the system hold, and accepts only the three known values.
 */
public final class ProtocolProbe extends BroadcastReceiver {
    private static final Set<String> VALUES = Set.of("phone_use", "autoglm", EtaAgent.PROTOCOL);

    @Override public void onReceive(Context context, Intent intent) {
        String previous = new Config(context).protocol, wanted = intent.getStringExtra("protocol");
        if (wanted != null && VALUES.contains(wanted)
            && !Config.prefs(context).edit().putString("protocol", wanted).commit()) {
            setResult(1, "save_failed", null);
            return;
        }
        setResult(wanted == null || VALUES.contains(wanted) ? 0 : 2, "previous=" + previous
            + " now=" + new Config(context).protocol, null);
    }
}
