package org.robbiemed.kotts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Verifies the model as soon as the last file lands, even if the app is closed. */
public class DownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        ModelManager.Progress p = ModelManager.progress(c);
        if (p == null || p.running || p.failed) return;
        PendingResult r = goAsync();
        new Thread(() -> {
            try { ModelManager.finish(c.getApplicationContext()); } finally { r.finish(); }
        }).start();
    }
}
