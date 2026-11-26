package org.paperwork.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public final class CameraFileProvider extends ContentProvider {
    private static final String AUTHORITY = "org.paperwork.mobile.camera";

    static Uri uri(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    static File captureFile(Context context, String name) {
        return new File(new File(context.getCacheDir(), "camera-captures"), name);
    }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || path.size() != 1
                || !path.get(0).matches("[0-9a-f-]{36}\\.jpg")) throw new FileNotFoundException();
        File candidate = captureFile(getContext(), path.get(0));
        try {
            File cameraArea = new File(getContext().getCacheDir().getCanonicalFile(), "camera-captures");
            if (!candidate.getCanonicalFile().equals(new File(cameraArea, path.get(0))) || !candidate.isFile()) {
                throw new FileNotFoundException();
            }
        } catch (IOException error) {
            throw new FileNotFoundException();
        }
        return candidate;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri uri) {
        try {
            file(uri);
            return "image/jpeg";
        } catch (FileNotFoundException invalid) {
            return null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File photo = file(uri);
            String[] fields = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(fields, 1);
            Object[] row = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(fields[i])) row[i] = photo.getName();
                if (OpenableColumns.SIZE.equals(fields[i])) row[i] = photo.length();
            }
            result.addRow(row);
            return result;
        } catch (FileNotFoundException invalid) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
