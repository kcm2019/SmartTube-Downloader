package com.kurt.smarttubedownloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Minimal read-only provider that serves the downloaded APK from the app's
 * own download directory. URI form: content://AUTHORITY/apk/<filename>.
 * A custom provider is used instead of a support library because this app
 * is built against framework APIs only (API 25 target).
 */
public class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.kurt.smarttubedownloader.fileprovider";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    public static Uri getUriForFile(Context ctx, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("apk")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("bad authority");
        }
        List<String> segs = uri.getPathSegments();
        if (segs.size() != 2 || !"apk".equals(segs.get(0))) {
            throw new FileNotFoundException("bad path");
        }
        File root;
        try {
            root = MainActivity.downloadDir(getContext()).getCanonicalFile();
            File f = new File(root, segs.get(1)).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + File.separator)) {
                throw new FileNotFoundException("outside root");
            }
            if (!f.isFile()) {
                throw new FileNotFoundException("missing");
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("io error");
        }
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
