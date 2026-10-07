package org.adskip.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Read-only, non-exported URI provider for verified APKs in a single private directory. */
public final class UpdateApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || getContext() == null
                || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || segments.size() != 1 || !segments.get(0).matches("[0-9a-f]{64}\\.apk"))
            throw new FileNotFoundException("Unknown update URI");
        File root = new File(getContext().getCacheDir(), "verified-updates");
        File file = new File(root, segments.get(0));
        try {
            if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()) || !file.isFile())
                throw new FileNotFoundException("Missing update");
        } catch (java.io.IOException error) { throw new FileNotFoundException("Invalid update path"); }
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "adskip-android-universal.apk";
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
