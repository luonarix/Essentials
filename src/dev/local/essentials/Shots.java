package dev.local.essentials;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Writes captures to the public folders the user asked for:
 * Pictures/Essentials and Movies/Essentials.
 */
final class Shots {

    static final String IMG_DIR = "Pictures/Essentials";
    static final String VID_DIR = "Movies/Essentials";

    private Shots() {}

    private static String stamp(String ext) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
        return "Essentials_" + f.format(new java.util.Date()) + ext;
    }

    /** Saves a PNG. Returns a human readable path, or null. */
    static String saveImage(Context c, Bitmap bmp) {
        String name = stamp(".png");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, IMG_DIR);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            ContentResolver cr = c.getContentResolver();
            Uri uri = null;
            try {
                uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) return null;
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return IMG_DIR + "/" + name;
            } catch (Exception e) {
                return null;
            }
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Essentials");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, name);
            FileOutputStream out = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()},
                    new String[]{"image/png"}, null);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies a finished mp4 into Movies/Essentials. */
    static String saveVideo(Context c, File src) {
        if (src == null || !src.exists()) return null;
        String name = stamp(".mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, VID_DIR);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver cr = c.getContentResolver();
            Uri uri;
            try {
                uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) return null;
                copy(new FileInputStream(src), os);
                os.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                src.delete();
                return VID_DIR + "/" + name;
            } catch (Exception e) {
                return null;
            }
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "Essentials");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File dst = new File(dir, name);
            copy(new FileInputStream(src), new FileOutputStream(dst));
            src.delete();
            MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return dst.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
