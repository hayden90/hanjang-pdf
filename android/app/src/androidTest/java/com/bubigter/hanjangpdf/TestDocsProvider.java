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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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

    /** 첫 쓰기 실패를 이미 낸 이름(put으로 다시 넣으면 지움). */
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    /**
     * 고장 흉내(8-4 판 1 지적 1·2 시험): 이름에 "noread_"가 있으면 읽기 열기가 실패하고(쓰기는 됨),
     * "failw1_"가 있으면 처음 한 번의 쓰기는 "wt"처럼 파일을 비운 뒤 첫 바이트를 쓸 때 실패한다(읽기 전용으로 열어 줌).
     */
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        boolean writing = mode.contains("w");
        if (!writing && f.getName().contains("noread_")) throw new FileNotFoundException("시험용 읽기 실패: " + f.getName());
        if (writing && f.getName().contains("failw1_") && FAILED.add(f.getName())) {
            if (mode.contains("t")) {
                try { new FileOutputStream(f).close(); } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
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
        if ("sha".equals(method)) return sha(arg);
        if (!"put".equals(method)) return null;
        try {
            Uri u = Uri.parse("content://com.bubigter.hanjangpdf.testdocs/" + arg);
            File f = file(u);
            FAILED.remove(f.getName());
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

    /** call("sha", 이름): 제공자 쪽에서 파일 SHA-256과 길이를 잰다(읽기 실패 흉내 파일도 시험이 확인할 수 있게). */
    private Bundle sha(String name) {
        Bundle b = new Bundle();
        try {
            File f = file(Uri.parse("content://com.bubigter.hanjangpdf.testdocs/" + name));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            b.putString("sha", sb.toString());
            b.putLong("len", f.length());
        } catch (Exception e) {
            b.putString("sha", "error:" + e);
        }
        return b;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return file(uri).delete() ? 1 : 0; } catch (FileNotFoundException e) { return 0; }
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
