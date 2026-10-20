package com.kurt.smarttubedownloader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * SmartTube Downloader: fetches the latest SmartTube release info from GitHub,
 * downloads the _universal.apk, prompts to install it, and deletes the APK
 * once installed. Target: Fire OS 6 / Android 7.1 (API 25).
 */
public class MainActivity extends Activity {

    static final String SMART_TUBE_PKG = "org.smarttube.stable";
    static final String SMART_TUBE_PKG_BETA = "org.smarttube.beta";
    static final String SMART_TUBE_PKG_PREFIX = "org.smarttube.";
    static final String APK_NAME = "SmartTube_universal.apk";
    static final String RELEASES_URL =
            "https://api.github.com/repos/yuliskov/SmartTube/releases?per_page=10";
    static final String USER_AGENT = "SmartTubeDownloader/1.0";

    private TextView releaseView;
    private TextView installedView;
    private TextView statusView;
    private ProgressBar progressBar;
    private TextView progressLabel;
    private Button actionButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile String downloadUrl;
    private volatile long assetSize;
    private volatile boolean downloading;
    private volatile boolean installLaunched;

    /** Download directory: external files dir, falling back to internal files dir. */
    static File downloadDir(android.content.Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        if (d == null) {
            d = ctx.getFilesDir();
        }
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    static File apkFile(android.content.Context ctx) {
        return new File(downloadDir(ctx), APK_NAME);
    }

    static void deleteDownloadedApk(android.content.Context ctx) {
        try {
            File f = apkFile(ctx);
            if (f.exists() && !f.delete()) {
                // best effort; nothing to do
            }
        } catch (Exception ignored) {
            // never crash on cleanup
        }
    }

    static String installedVersion(android.content.Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName != null ? pi.versionName : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    static String installedSmartTubeVersion(android.content.Context ctx) {
        return installedVersion(ctx, SMART_TUBE_PKG);
    }

    /** Package info of the downloaded APK file, or null if it can't be read. */
    private PackageInfo downloadedApkInfo() {
        File f = apkFile(this);
        if (!f.exists()) return null;
        try {
            return getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the downloaded APK's own package is installed at that version or newer. */
    private boolean isDownloadedApkInstalled() {
        PackageInfo archive = downloadedApkInfo();
        if (archive == null) return false;
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(archive.packageName, 0);
            return installed.versionCode >= archive.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // Clean up any leftover APK from a previous session if SmartTube is installed.
        if (installedVersion(this, SMART_TUBE_PKG) != null
                || installedVersion(this, SMART_TUBE_PKG_BETA) != null) {
            deleteDownloadedApk(this);
        }
        refreshInstalledStatus();
        fetchReleaseInfo();
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onActionClicked();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installLaunched) {
            installLaunched = false;
            if (isDownloadedApkInstalled()) {
                deleteDownloadedApk(this);
                setStatus("Installed. Downloaded APK deleted.");
            } else {
                setStatus("Installer was opened. If install completed, the APK will be deleted automatically.");
            }
            refreshInstalledStatus();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#101014"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(48), dp(48), dp(48));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("SmartTube Downloader");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, layoutParams());

        root.addView(vspace(dp(24)));

        releaseView = infoText("Checking latest SmartTube release…");
        root.addView(releaseView, layoutParams());

        installedView = infoText("");
        root.addView(installedView, layoutParams());

        statusView = infoText("");
        statusView.setTextColor(Color.parseColor("#B0BEC5"));
        root.addView(statusView, layoutParams());

        root.addView(vspace(dp(16)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        root.addView(progressBar, layoutParams());

        progressLabel = infoText("");
        progressLabel.setGravity(Gravity.CENTER);
        root.addView(progressLabel, layoutParams());

        root.addView(vspace(dp(24)));

        actionButton = new Button(this);
        actionButton.setText("Download & Install");
        actionButton.setTextSize(22);
        actionButton.setFocusable(true);
        actionButton.setFocusableInTouchMode(true);
        actionButton.setMinHeight(dp(72));
        actionButton.setEnabled(false);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(actionButton, btnLp);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout.LayoutParams layoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private Space vspace(int px) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px));
        return s;
    }

    private TextView infoText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#E0E0E0"));
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private int dp(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    private void setTextOnUi(final TextView v, final String s) {
        ui.post(new Runnable() {
            @Override public void run() { v.setText(s); }
        });
    }

    private void setStatus(final String s) {
        setTextOnUi(statusView, s);
    }

    private void refreshInstalledStatus() {
        final String s = installedVersion(this, SMART_TUBE_PKG);
        final String b = installedVersion(this, SMART_TUBE_PKG_BETA);
        final String text;
        if (s == null && b == null) {
            text = "SmartTube: not installed";
        } else {
            StringBuilder sb = new StringBuilder("SmartTube installed:");
            if (s != null) sb.append("\nStable ").append(s);
            if (b != null) sb.append("\nBeta ").append(b);
            text = sb.toString();
        }
        setTextOnUi(installedView, text);
    }

    /**
     * 1) Fetch the newest SmartTube release from the GitHub API — stable or
     * pre-release, whichever is newest — and pick its _universal.apk asset.
     */
    private void fetchReleaseInfo() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONArray releases = getJsonArray(RELEASES_URL);
                    String tag = "";
                    String name = "";
                    boolean prerelease = false;
                    String url = null;
                    String assetName = null;
                    long size = 0;
                    if (releases != null) {
                        for (int r = 0; r < releases.length() && url == null; r++) {
                            JSONObject release = releases.optJSONObject(r);
                            if (release == null) continue;
                            JSONArray assets = release.optJSONArray("assets");
                            if (assets == null) continue;
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject a = assets.optJSONObject(i);
                                if (a == null) continue;
                                String n = a.optString("name", "");
                                if (n.toLowerCase(Locale.US).endsWith("_universal.apk")) {
                                    url = a.optString("browser_download_url", null);
                                    assetName = n;
                                    size = a.optLong("size", 0);
                                    tag = release.optString("tag_name", "");
                                    name = release.optString("name", tag);
                                    prerelease = release.optBoolean("prerelease", false);
                                    break;
                                }
                            }
                        }
                    }
                    if (url == null || url.isEmpty()) {
                        setTextOnUi(releaseView, "No universal APK found in the latest release.");
                        setStatus("Could not find a _universal.apk asset. Please try again later.");
                        return;
                    }
                    downloadUrl = url;
                    assetSize = size;
                    final String info = "Newest build: " + name + " (" + tag + ")"
                            + (prerelease ? " — pre-release" : " — stable") + "\n"
                            + assetName + " — " + humanSize(size);
                    setTextOnUi(releaseView, info);
                    ui.post(new Runnable() {
                        @Override public void run() { actionButton.setEnabled(true); }
                    });
                    setStatus("Ready.");
                } catch (Exception e) {
                    setTextOnUi(releaseView, "Could not reach GitHub.");
                    setStatus("Network error: " + safeMessage(e)
                            + "\nCheck your network connection and try again.");
                }
            }
        }).start();
    }

    private void onActionClicked() {
        if (downloading) return;
        if (downloadUrl == null) {
            setStatus("Release info not loaded yet — please wait.");
            return;
        }
        downloading = true;
        ui.post(new Runnable() {
            @Override public void run() {
                actionButton.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                progressLabel.setText("");
            }
        });
        setStatus("Downloading…");
        final String url = downloadUrl;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    downloadFile(url);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progressBar.setVisibility(View.INVISIBLE);
                            progressLabel.setText("Download complete.");
                            actionButton.setEnabled(true);
                        }
                    });
                    launchInstaller();
                } catch (Exception e) {
                    deleteDownloadedApk(MainActivity.this);
                    setStatus("Download failed: " + safeMessage(e));
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progressBar.setVisibility(View.INVISIBLE);
                            actionButton.setEnabled(true);
                        }
                    });
                } finally {
                    downloading = false;
                }
            }
        }).start();
    }

    /** 4) Download the APK with progress. */
    private void downloadFile(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code);
            }
            long expected = conn.getContentLengthLong();
            if (expected <= 0) expected = assetSize;
            in = conn.getInputStream();
            File target = apkFile(this);
            out = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            long lastPost = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                long now = System.currentTimeMillis();
                if (now - lastPost > 200) {
                    lastPost = now;
                    final long t = total;
                    final long e = expected;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (e > 0) {
                                int pct = (int) (t * 100 / e);
                                progressBar.setProgress(pct);
                                progressLabel.setText(pct + "%  (" + humanSize(t)
                                        + " / " + humanSize(e) + ")");
                            } else {
                                progressLabel.setText(humanSize(t) + " downloaded…");
                            }
                        }
                    });
                }
            }
            out.flush();
            final long t = total;
            final long e = expected;
            ui.post(new Runnable() {
                @Override public void run() {
                    progressBar.setProgress(100);
                    progressLabel.setText(e > 0
                            ? "100%  (" + humanSize(t) + ")"
                            : humanSize(t) + " downloaded.");
                }
            });
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 5) Hand the APK to the system package installer via a content:// URI. */
    private void launchInstaller() {
        File apk = apkFile(this);
        if (!apk.exists()) {
            setStatus("Downloaded file is missing — cannot install.");
            return;
        }
        Uri uri = ApkFileProvider.getUriForFile(this, apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            installLaunched = true;
            startActivity(intent);
            setStatus("Installer opened — approve the install, then return here.");
        } catch (ActivityNotFoundException e) {
            installLaunched = false;
            setStatus("No installer app found on this device.");
        }
    }

    private JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code);
            }
            in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return new JSONObject(sb.toString());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray getJsonArray(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code);
            }
            in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return new JSONArray(sb.toString());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return String.format(Locale.US, "%.1f %s", v, units[u]);
    }

    static String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
