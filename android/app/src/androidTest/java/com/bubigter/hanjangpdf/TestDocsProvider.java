package com.bubigter.hanjangpdf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 시험 전용 '다른 앱' 문서 제공자. content://com.bubigter.hanjangpdf.testdocs/<이름> 을 시험 APK의 filesDir/docs/<이름>으로
 * 연다(읽기·쓰기·"wt"). 시험 APK 쪽 프로세스에서 돌아 앱과 다른 앱처럼 주고받는다.
 * 시험 APK 프로세스에는 Kotlin 런타임이 없어 Java로 쓴다.
 */
public class TestDocsProvider extends ContentProvider {
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException(String.valueOf(name));
        File dir = new File(getContext().getFilesDir(), "docs");
        dir.mkdirs();
        return new File(dir, name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "application/pdf"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if ("r".equals(mode) && !f.exists()) throw new FileNotFoundException(f.getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = file(uri);
            c.addRow(new Object[] {f.getName(), f.length()});
        } catch (FileNotFoundException e) {
            // 빈 결과
        }
        return c;
    }

    /**
     * call("put", 이름, {asset, write}): 시험 APK의 asset(없으면 빈 파일)을 그 이름으로 넣고, 한장PDF에 읽기(write면 쓰기도) 권한을 준다
     * (실제 다른 앱이 FLAG_GRANT_WRITE_URI_PERMISSION으로 넘길 때처럼 권한 기록이 남는다).
     */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"put".equals(method)) return null;
        try {
            Uri u = Uri.parse("content://com.bubigter.hanjangpdf.testdocs/" + arg);
            File f = file(u);
            String asset = extras.getString("asset");
            try (InputStream in = asset == null ? new ByteArrayInputStream(new byte[0]) : getContext().getAssets().open(asset);
                 OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (extras.getBoolean("write")) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContext().grantUriPermission("com.bubigter.hanjangpdf", u, flags);
            return new Bundle();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return file(uri).delete() ? 1 : 0; } catch (FileNotFoundException e) { return 0; }
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
