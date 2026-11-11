package com.stradaos.launcher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;

/**
 * Carátula de respaldo para cuando la fuente no la manda: el Bluetooth del celular (AVRCP solo envía textos), apps
 * de CarPlay o reproductores que no ponen la imagen. Se busca, en orden:
 *  1. la dirección de imagen que dio la app (MediaSession con «ART_URI»),
 *  2. la música guardada en la pantalla o en una USB (carátula incrustada en el MP3, la del álbum o cover.jpg),
 *  3. internet, por artista y título (iTunes y, si no, Deezer), si está activado en Ajustes.
 * Lo encontrado se guarda en la caché (memoria y disco) para no volver a buscarlo. Nunca reemplaza la carátula que
 * manda la propia app: solo se muestra cuando esa no existe.
 */
public class ArtFinder {

    public interface Callback { void onArt(String key, Bitmap art, String from); }

    private static final int MAX = 500;                    // lado máximo en px (menos memoria en pantallas viejas)
    private static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getRowBytes() * b.getHeight(); }
    };
    private static final HashMap<String, Long> misses = new HashMap<String, Long>();
    private static final HashMap<String, String> froms = new HashMap<String, String>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile String wanted;
    private static Thread worker;
    private static String[] pending;                       // {key, title, artist, album, uri}
    private static Callback pendingCb;
    private static boolean useHttp;                        // el cifrado no funcionó en esta pantalla
    static volatile String lastInfo = "—";

    /** Clave de una canción: artista + título normalizados. */
    static String key(String title, String artist) {
        if (title == null) return null;
        return norm(artist == null ? "" : artist) + "|" + norm(title);
    }

    /** Lo ya encontrado para esta canción (sin buscar), o null. */
    static Bitmap cached(String key) { return key == null ? null : mem.get(key); }
    static String from(String key) { return key == null ? null : froms.get(key); }

    /** Busca en segundo plano (si ya hay otra búsqueda, gana la última). */
    static synchronized void find(Context c, String title, String artist, String album, String uri, boolean online, Callback cb) {
        final String k = key(title, artist);
        if (k == null) return;
        Bitmap m = mem.get(k);
        if (m != null) { cb.onArt(k, m, froms.get(k)); return; }
        Long miss = misses.get(k);
        if (miss != null && System.currentTimeMillis() - miss < 6 * 3600 * 1000L) return;   // no encontrada hace poco
        wanted = k;
        pending = new String[]{k, title, artist, album, uri, online ? "1" : "0"};
        pendingCb = cb;
        if (worker == null) {
            final Context app = c.getApplicationContext();
            worker = new Thread(new Runnable() { public void run() { loop(app); } }, "caratulas");
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private static void loop(Context c) {
        while (true) {
            String[] job;
            Callback cb;
            synchronized (ArtFinder.class) {
                job = pending; cb = pendingCb;
                pending = null; pendingCb = null;
                if (job == null) { worker = null; return; }
            }
            final String k = job[0];
            String[] from = new String[1];
            Bitmap b = null;
            try { b = search(c, job[1], job[2], job[3], job[4], "1".equals(job[5]), from); }
            catch (Throwable e) { lastInfo = "error: " + e; }
            if (b == null) {
                synchronized (ArtFinder.class) { misses.put(k, System.currentTimeMillis()); }
                if (!lastInfo.startsWith("error")) lastInfo = "no encontrada: " + job[2] + " — " + job[1];
                continue;
            }
            mem.put(k, b);
            froms.put(k, from[0]);
            lastInfo = from[0] + ": " + job[2] + " — " + job[1];
            final Bitmap fb = b;
            final String ff = from[0];
            final Callback fcb = cb;
            ui.post(new Runnable() { public void run() { fcb.onArt(k, fb, ff); } });
        }
    }

    private static Bitmap search(Context c, String title, String artist, String album, String uri, boolean online, String[] from) throws Exception {
        String k = key(title, artist);
        File f = diskFile(c, k);
        if (f.exists()) {
            Bitmap b = decode(readFile(f));
            if (b != null) {
                String src = c.getSharedPreferences("art_from", 0).getString(k, "guardada");
                from[0] = src;
                f.setLastModified(System.currentTimeMillis());
                return b;
            }
        }
        Bitmap b = null;
        if (uri != null && uri.length() > 0) {
            b = fromUri(c, uri);
            if (b != null) from[0] = "app";
        }
        if (b == null && !stale(k)) {
            b = fromLibrary(c, title, artist);
            if (b != null) from[0] = "memoria";
        }
        if (b == null && online && artist != null && artist.trim().length() > 0 && !stale(k)) {
            b = fromItunes(title, artist);
            if (b != null) from[0] = "internet (iTunes)";
            else if (!stale(k)) {
                b = fromDeezer(title, artist);
                if (b != null) from[0] = "internet (Deezer)";
            }
        }
        if (b != null && !"app".equals(from[0])) save(c, k, b, from[0]);
        return b;
    }

    /** Ya se pidió otra canción: no seguir buscando esta. */
    private static boolean stale(String k) { String w = wanted; return w != null && !w.equals(k); }

    // ── 1. dirección de la app ──

    private static Bitmap fromUri(Context c, String uri) {
        try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) return decode(Net.get(uri, 10000));
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) return null;
            try { return decode(readAll(in)); } finally { in.close(); }
        } catch (Throwable e) { return null; }
    }

    // ── 2. música guardada ──

    private static boolean canRead(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean libraryAllowed(Context c) { return canRead(c); }

    private static Bitmap fromLibrary(Context c, String title, String artist) {
        if (!canRead(c) || title == null) return null;
        ContentResolver cr = c.getContentResolver();
        Cursor q = null;
        long id = -1, albumId = -1;
        String path = null;
        try {
            q = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ARTIST},
                    MediaStore.Audio.Media.TITLE + "=? COLLATE NOCASE", new String[]{title.trim()}, null);
            if (q == null) return null;
            String na = artist == null ? null : norm(artist);
            int n = 0;
            while (q.moveToNext()) {
                n++;
                String a = q.getString(3);
                boolean artistOk = na == null || na.length() == 0 || (a != null && sameArtist(norm(a), na));
                if (artistOk || id < 0) {
                    id = q.getLong(0); albumId = q.getLong(1); path = q.getString(2);
                    if (artistOk && na != null) break;
                }
            }
            if (n > 1 && (artist == null || artist.trim().length() == 0)) return null;   // varias con ese título y sin artista: no adivinar
        } catch (Throwable e) {
            return null;
        } finally {
            if (q != null) q.close();
        }
        if (id < 0) return null;
        Uri song = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        // a) carátula incrustada en el archivo (MP3, M4A, FLAC…)
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(c, song);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) { Bitmap b = decode(pic); if (b != null) return b; }
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        // b) carátula del álbum que guarda Android
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                Bitmap b = cr.loadThumbnail(song, new android.util.Size(MAX, MAX), null);
                if (b != null) return b;
            } else if (albumId >= 0) {
                Cursor a = cr.query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Albums.ALBUM_ART},
                        MediaStore.Audio.Albums._ID + "=?", new String[]{String.valueOf(albumId)}, null);
                if (a != null) {
                    try {
                        if (a.moveToFirst() && a.getString(0) != null) {
                            Bitmap b = decode(readFile(new File(a.getString(0))));
                            if (b != null) return b;
                        }
                    } finally { a.close(); }
                }
            }
        } catch (Throwable ignored) { }
        // c) imagen de la carpeta (cover.jpg, folder.jpg…)
        try {
            File dir = path != null ? new File(path).getParentFile() : null;
            File[] fs = dir != null ? dir.listFiles() : null;
            if (fs != null) {
                String[] names = {"cover", "folder", "front", "albumart", "album", "albumartsmall"};
                for (String want : names)
                    for (File x : fs) {
                        String n = x.getName().toLowerCase(Locale.US);
                        if ((n.equals(want + ".jpg") || n.equals(want + ".jpeg") || n.equals(want + ".png")) && x.length() < 8 * 1024 * 1024) {
                            Bitmap b = decode(readFile(x));
                            if (b != null) return b;
                        }
                    }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ── 3. internet ──

    private static byte[] get(String httpsUrl) throws Exception {
        if (!useHttp) {
            try { return Net.get(httpsUrl, 10000); }
            catch (javax.net.ssl.SSLException e) { useHttp = true; }
        }
        return Net.get(httpsUrl.replaceFirst("^https://", "http://"), 10000);
    }

    private static Bitmap fromItunes(String title, String artist) {
        try {
            String q = URLEncoder.encode(artist + " " + title, "UTF-8");
            JSONArray rs = new JSONObject(new String(get("https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + q), "UTF-8")).optJSONArray("results");
            if (rs == null) return null;
            String nt = norm(title), na = norm(artist);
            for (int i = 0; i < rs.length(); i++) {
                JSONObject o = rs.getJSONObject(i);
                if (!sameTitle(norm(o.optString("trackName")), nt) || !sameArtist(norm(o.optString("artistName")), na)) continue;
                String art = o.optString("artworkUrl100", "");
                if (art.length() == 0) continue;
                Bitmap b = decode(get(art.replace("100x100bb", "600x600bb")));
                if (b == null) b = decode(get(art));
                if (b != null) return b;
            }
        } catch (Throwable e) { lastInfo = "iTunes: " + e.getClass().getSimpleName(); }
        return null;
    }

    private static Bitmap fromDeezer(String title, String artist) {
        try {
            String q = URLEncoder.encode("artist:\"" + artist + "\" track:\"" + title + "\"", "UTF-8");
            JSONArray rs = new JSONObject(new String(get("https://api.deezer.com/search?limit=8&q=" + q), "UTF-8")).optJSONArray("data");
            if (rs == null || rs.length() == 0) {
                q = URLEncoder.encode(artist + " " + title, "UTF-8");
                rs = new JSONObject(new String(get("https://api.deezer.com/search?limit=8&q=" + q), "UTF-8")).optJSONArray("data");
            }
            if (rs == null) return null;
            String nt = norm(title), na = norm(artist);
            for (int i = 0; i < rs.length(); i++) {
                JSONObject o = rs.getJSONObject(i);
                JSONObject ar = o.optJSONObject("artist"), al = o.optJSONObject("album");
                if (ar == null || al == null) continue;
                if (!sameTitle(norm(o.optString("title")), nt) || !sameArtist(norm(ar.optString("name")), na)) continue;
                String art = al.optString("cover_big", al.optString("cover_medium", ""));
                if (art.length() == 0) continue;
                Bitmap b = decode(get(art));
                if (b != null) return b;
            }
        } catch (Throwable e) { lastInfo = "Deezer: " + e.getClass().getSimpleName(); }
        return null;
    }

    // ── comparación de textos ──

    /** minúsculas, sin tildes, sin lo que va entre paréntesis/corchetes ni «feat.», solo letras y números. */
    static String norm(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        x = x.replaceAll("['’´`]", "").replace("&", " y ");   // «Don't» = «Dont»; «&» = «y»
        x = x.replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", " ");
        x = x.replaceAll("\\s(feat|ft|featuring|con|with)\\.?\\s.*$", " ");
        x = x.replaceAll("\\s-\\s.*(remaster|version|versión|live|en vivo|edit|mix).*$", " ");
        x = x.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return x;
    }

    private static boolean sameTitle(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return false;
        if (a.equals(b)) return true;
        int m = Math.min(a.length(), b.length());
        return m >= 4 && (a.startsWith(b) || b.startsWith(a));
    }

    /** Coincide si uno contiene al otro o comparten el primer artista («Bad Bunny, Jhay Cortez» ~ «Bad Bunny»). */
    private static boolean sameArtist(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return false;
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        String fa = a.split(" (y|and|x|e) ")[0], fb = b.split(" (y|and|x|e) ")[0];
        return fa.length() >= 3 && (fa.equals(fb) || a.startsWith(fb) || b.startsWith(fa));
    }

    // ── imágenes y caché en disco ──

    private static Bitmap decode(byte[] data) {
        if (data == null || data.length < 100) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= MAX && o.outHeight / (sample * 2) >= MAX) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        int big = Math.max(b.getWidth(), b.getHeight());
        if (big > MAX) {
            Bitmap s = Bitmap.createScaledBitmap(b, b.getWidth() * MAX / big, b.getHeight() * MAX / big, true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    private static File diskFile(Context c, String k) {
        File d = new File(c.getCacheDir(), "caratulas");
        d.mkdirs();
        return new File(d, Integer.toHexString(k.hashCode()) + "_" + k.length() + ".jpg");
    }

    private static void save(Context c, String k, Bitmap b, String from) {
        try {
            File f = diskFile(c, k);
            FileOutputStream os = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 88, os);
            os.close();
            c.getSharedPreferences("art_from", 0).edit().putString(k, from).apply();
            trim(f.getParentFile());
        } catch (Throwable ignored) { }
    }

    /** Caché en disco de ~15 MB (unas 300 carátulas): se borran las más viejas. */
    private static void trim(File d) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total < 15L * 1024 * 1024) return;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            public int compare(File a, File b) { long x = a.lastModified() - b.lastModified(); return x > 0 ? 1 : x < 0 ? -1 : 0; }
        });
        for (File f : fs) { if (total < 12L * 1024 * 1024) break; total -= f.length(); f.delete(); }
    }

    static void clearCache(Context c) {
        mem.evictAll();
        synchronized (ArtFinder.class) { misses.clear(); }
        froms.clear();
        File[] fs = new File(c.getCacheDir(), "caratulas").listFiles();
        if (fs != null) for (File f : fs) f.delete();
        c.getSharedPreferences("art_from", 0).edit().clear().apply();
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        InputStream in = new java.io.FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 10 * 1024 * 1024) throw new java.io.IOException("imagen demasiado grande");
        }
        return out.toByteArray();
    }
}
