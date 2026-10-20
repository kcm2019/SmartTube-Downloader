package com.kurt.smarttubedownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Deletes the downloaded APK once SmartTube has been installed or updated.
 * Declared in the manifest (works on API 25; PACKAGE_ADDED/REPLACED are also
 * exempt from the Android 8 implicit-broadcast restrictions for later levels).
 */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) return;
        String pkg = data.getSchemeSpecificPart();
        if (pkg == null || !pkg.startsWith(MainActivity.SMART_TUBE_PKG_PREFIX)) return;
        MainActivity.deleteDownloadedApk(context);
    }
}
