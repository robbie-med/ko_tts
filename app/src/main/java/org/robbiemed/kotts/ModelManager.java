package org.robbiemed.kotts;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads and verifies the Supertonic 3 model. Nothing ships in the APK: files come
 * straight from Hugging Face, pinned to a commit and checked against SHA-256.
 *
 * "full" is Supertone's original fp32 release. "compact" swaps in int8 builds of the
 * text encoder and vector estimator (the vocoder stays fp32: quantizing it wrecks the audio).
 *
 * DownloadManager writes into download/. Once every file checks out, the folder is renamed to
 * model/ and the download records are dropped. DownloadManager deletes the file a record points
 * at whenever that record goes away (cleanup, "clear downloads", remove()), so a model left
 * where it was downloaded can vanish while the app still calls it installed.
 */
public final class ModelManager {
    private ModelManager() { }

    static final String ST_COMMIT = "724fb5abbf5502583fb520898d45929e62f02c0b";
    static final String ST_BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/" + ST_COMMIT + "/";
    /** int8 text encoder + vector estimator, built by tools/quantize.py from the files above. */
    static final String COMPACT_BASE = "https://huggingface.co/robbiemed/supertonic-3-int8/resolve/38303b5434210a3bbd1fa287ffce287e10a943b0/";

    /** The compact build needs a host for its two int8 files; until COMPACT_BASE is live, offer full only. */
    public static boolean compactAvailable() { return BuildConfig.COMPACT_READY; }

    public static final String[] VOICES = {"F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"};
    private static final String[] VOICE_SHA = {
            "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2",
            "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6",
            "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab",
            "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b",
            "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d",
            "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b",
            "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50",
            "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b",
            "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303",
            "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2"};

    public static final String COMPACT = "compact", FULL = "full";
    public static final int COMPACT_MB = 190, FULL_MB = 401;

    static final class F {
        final String path, url, sha;
        F(String base, String path, String sha) { this(base, path, sha, path); }
        F(String base, String path, String sha, String remote) { this.path = path; this.url = base + remote; this.sha = sha; }
    }

    static List<F> files(String variant) {
        boolean compact = COMPACT.equals(variant);
        List<F> l = new ArrayList<>();
        l.add(new F(ST_BASE, "onnx/tts.json", "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"));
        l.add(new F(ST_BASE, "onnx/unicode_indexer.json", "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"));
        l.add(new F(ST_BASE, "onnx/duration_predictor.onnx", "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"));
        l.add(new F(ST_BASE, "onnx/vocoder.onnx", "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"));
        if (compact) {
            // These two live at the root of the int8 repo.
            l.add(new F(COMPACT_BASE, "onnx/text_encoder.onnx", "d32a22d345ecbc288b5fc121ad0aa27bd4708f9617b6c7372410836f6e02db71", "text_encoder.onnx"));
            l.add(new F(COMPACT_BASE, "onnx/vector_estimator.onnx", "9c6408bdf36ef1fa534a93baac7f828c0abb5b1b87b792153497603fcf0f5763", "vector_estimator.onnx"));
        } else {
            l.add(new F(ST_BASE, "onnx/text_encoder.onnx", "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"));
            l.add(new F(ST_BASE, "onnx/vector_estimator.onnx", "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"));
        }
        for (int k = 0; k < VOICES.length; k++) l.add(new F(ST_BASE, "voice_styles/" + VOICES[k] + ".json", VOICE_SHA[k]));
        return l;
    }

    private static File base(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext != null ? ext : c.getFilesDir();
    }

    /** The installed model. Nothing here is known to DownloadManager. */
    public static File dir(Context c) { return new File(base(c), "model"); }

    /** Where DownloadManager writes; emptied as soon as the files are verified. */
    private static File staging(Context c) { return new File(base(c), "download"); }

    private static File marker(Context c) { return new File(dir(c), "installed"); }

    /** Installed variant, or null. The marker only counts if every model file is still there. */
    public static String installed(Context c) {
        migrate(c);
        if (!marker(c).exists()) return null;
        String variant = Prefs.str(c, Prefs.ST_VARIANT, FULL);
        return complete(dir(c), variant) ? variant : null;
    }

    /** True when a model was installed but some of its files have since disappeared. */
    public static boolean missing(Context c) { return marker(c).exists() && installed(c) == null; }

    private static boolean complete(File d, String variant) {
        for (F f : files(variant)) {
            if (new File(d, f.path).length() == 0) return false; // also 0 when the file doesn't exist
        }
        return true;
    }

    /**
     * Up to 0.1.0 the model stayed in supertonic-3/, where DownloadManager had put it and could
     * delete it. Move it to dir() and drop all of this app's download records. Runs once.
     */
    private static void migrate(Context c) {
        if (Prefs.get(c).getBoolean("migrated_dl", false)) return;
        synchronized (ModelManager.class) {
            if (Prefs.get(c).getBoolean("migrated_dl", false)) return;
            File old = new File(base(c), "supertonic-3");
            if (new File(old, "installed").exists() && !dir(c).exists() && !old.renameTo(dir(c))) {
                Log.w("KoTTS", "couldn't move " + old + "; will retry");
                return;
            }
            DownloadManager dm = dm(c);
            List<Long> all = new ArrayList<>();
            try (Cursor cur = dm.query(new DownloadManager.Query())) {
                while (cur != null && cur.moveToNext()) all.add(cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
            }
            long[] ids = new long[all.size()];
            for (int k = 0; k < ids.length; k++) ids[k] = all.get(k);
            if (ids.length > 0) dm.remove(ids);
            deleteTree(old); // only an unfinished old download is left in it
            Prefs.get(c).edit().remove("dl_ids").putBoolean("migrated_dl", true).apply();
            Log.i("KoTTS", "migrated model to " + dir(c) + ", dropped " + ids.length + " download records");
        }
    }

    private static DownloadManager dm(Context c) { return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE); }

    // ---- downloading ----

    public static final class Progress {
        public final long done, total;
        public final boolean running, failed;
        Progress(long done, long total, boolean running, boolean failed) {
            this.done = done; this.total = total; this.running = running; this.failed = failed;
        }
    }

    public static void start(Context c, String variant) {
        cancel(c);
        deleteTree(dir(c));
        File d = staging(c);
        DownloadManager dm = dm(c);
        StringBuilder ids = new StringBuilder();
        String title = c.getString(R.string.dl_title);
        for (F f : files(variant)) {
            File out = new File(d, f.path);
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(f.url))
                    .setTitle(title)
                    .setDescription(f.path)
                    .setDestinationUri(Uri.fromFile(out))
                    .setNotificationVisibility(f.path.endsWith("vector_estimator.onnx")
                            ? DownloadManager.Request.VISIBILITY_VISIBLE
                            : DownloadManager.Request.VISIBILITY_HIDDEN);
            if (ids.length() > 0) ids.append(',');
            ids.append(dm.enqueue(r));
        }
        Prefs.get(c).edit().putString("dl_ids", ids.toString()).putString("dl_variant", variant).apply();
    }

    public static void cancel(Context c) {
        long[] ids = ids(c);
        if (ids.length > 0) dm(c).remove(ids);
        Prefs.get(c).edit().remove("dl_ids").apply();
        deleteTree(staging(c));
    }

    public static void delete(Context c) {
        cancel(c);
        deleteTree(dir(c));
    }

    private static long[] ids(Context c) {
        String s = Prefs.get(c).getString("dl_ids", "");
        if (s == null || s.isEmpty()) return new long[0];
        String[] p = s.split(",");
        long[] out = new long[p.length];
        for (int k = 0; k < p.length; k++) out[k] = Long.parseLong(p[k]);
        return out;
    }

    /** Null when no download is pending. */
    public static Progress progress(Context c) {
        migrate(c);
        long[] ids = ids(c);
        if (ids.length == 0) return null;
        DownloadManager dm = dm(c);
        long done = 0, total = 0;
        int ok = 0;
        boolean failed = false;
        try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(ids))) {
            int seen = 0;
            while (cur != null && cur.moveToNext()) {
                seen++;
                int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long t = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                done += Math.max(0, cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                total += Math.max(0, t);
                if (st == DownloadManager.STATUS_SUCCESSFUL) ok++;
                if (st == DownloadManager.STATUS_FAILED) failed = true;
            }
            if (seen < ids.length) failed = true; // a download was removed from the system queue
        }
        return new Progress(done, total, ok < ids.length && !failed, failed);
    }

    /**
     * Called when every download finished: checks hashes, moves the files out of DownloadManager's
     * reach, checks they arrived, and only then marks the model installed.
     */
    public static synchronized boolean finish(Context c) {
        Progress p = progress(c);
        if (p == null || p.running || p.failed) return false;
        String variant = Prefs.str(c, "dl_variant", FULL);
        File s = staging(c), d = dir(c);
        for (F f : files(variant)) {
            if (!f.sha.equals(sha256(new File(s, f.path)))) return fail(c, f.path);
        }
        // Move first, then drop the records: removing a record deletes the file it points at.
        deleteTree(d);
        if (!s.renameTo(d)) return fail(c, d.getName());
        long[] ids = ids(c);
        if (ids.length > 0) dm(c).remove(ids);
        if (!complete(d, variant)) return fail(c, d.getName());
        try (FileOutputStream o = new FileOutputStream(marker(c))) {
            o.write(variant.getBytes());
        } catch (Exception e) {
            return fail(c, "installed");
        }
        Prefs.get(c).edit().remove("dl_ids").remove("dl_error").putString(Prefs.ST_VARIANT, variant).apply();
        Log.i("KoTTS", "installed " + variant + " model in " + d + ", dropped " + ids.length + " download records");
        return true;
    }

    private static boolean fail(Context c, String what) {
        long[] ids = ids(c);
        if (ids.length > 0) dm(c).remove(ids);
        Prefs.get(c).edit().remove("dl_ids").putString("dl_error", what).apply();
        deleteTree(staging(c));
        return false;
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
