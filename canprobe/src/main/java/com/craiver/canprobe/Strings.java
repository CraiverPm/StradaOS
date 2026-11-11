package com.craiver.canprobe;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Como el comando «strings»: saca los textos de un APK (classes*.dex, librerías .so) y de los .vdex/.odex
 * junto a él (en Android 9 el código de las apps del sistema suele estar ahí). De ahí salen los nombres de los
 * avisos (broadcasts) que manda el servicio del CAN y los nombres de los campos (puerta, rpm, velocidad…).
 */
public class Strings {

    static final Pattern ACTION = Pattern.compile("^[a-zA-Z][\\w]*(\\.[\\w]+){2,}$");
    static final String[] CAR_WORDS = {"can", "car", "mcu", "door", "rpm", "speed", "vehicle", "engine", "fuel", "radar",
            "park", "climate", "aircon", "air_", ".ac", "temp", "mileage", "odometer", "gear", "brake", "seatbelt", "belt",
            "trunk", "hood", "light", "tpms", "tire", "tyre", "voltage", "battery", "steer", "wheel", "reverse", "illum",
            "acc", "info", "state", "status", "report", "changed", "update", "data", "bus", "obd"};
    static final String[] FIELD_WORDS = {"door", "rpm", "speed", "engine", "fuel", "mileage", "odometer", "gear", "brake",
            "seatbelt", "belt", "handbrake", "trunk", "hood", "light", "radar", "tpms", "tire", "tyre", "voltage", "battery",
            "steer", "outside", "outtemp", "water", "coolant", "oil", "range", "consum", "trip", "window", "lock", "ac_",
            "aircon", "climate", "fan", "wind", "defrost", "reverse", "canbus", "can_", "obd"};

    public final Set<String> actions = new LinkedHashSet<String>();
    public final Set<String> fields = new LinkedHashSet<String>();
    public final Set<String> paths = new LinkedHashSet<String>();
    long budget = 120L << 20;   // máx. bytes a leer por app

    public void scanApk(File apk) {
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements() && budget > 0) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if ((n.startsWith("classes") && n.endsWith(".dex")) || (n.startsWith("lib/") && n.endsWith(".so"))) {
                    InputStream in = z.getInputStream(e);
                    scan(in);
                    in.close();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
        }
        // código precompilado junto al APK (oat/arm/X.vdex, X.odex)
        File dir = apk.getParentFile();
        if (dir != null) scanDir(dir, 3);
    }

    private void scanDir(File dir, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (budget <= 0) return;
            String n = f.getName();
            if (f.isDirectory() && depth > 0) scanDir(f, depth - 1);
            else if (n.endsWith(".vdex") || n.endsWith(".odex") || n.endsWith(".dex")) {
                try { InputStream in = new FileInputStream(f); scan(in); in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    public void scanFile(File f) {
        try {
            if (f.getName().endsWith(".jar") || f.getName().endsWith(".apk")) { scanApk(f); return; }
            InputStream in = new FileInputStream(f); scan(in); in.close();
        } catch (Throwable ignored) { }
    }

    private void scan(InputStream raw) throws Exception {
        InputStream in = new BufferedInputStream(raw, 65536);
        StringBuilder cur = new StringBuilder();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0 && budget > 0) {
            budget -= n;
            for (int i = 0; i < n; i++) {
                int c = buf[i] & 0xFF;
                if (c >= 0x20 && c < 0x7F) {
                    if (cur.length() < 160) cur.append((char) c);
                } else {
                    if (cur.length() >= 4) take(cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() >= 4) take(cur.toString());
    }

    private void take(String s) {
        String l = s.toLowerCase(Locale.US);
        if ((s.startsWith("/dev/") || s.startsWith("/sys/") || s.startsWith("/proc/")) && s.length() < 80) {
            if (paths.size() < 400) paths.add(s);
            return;
        }
        if (ACTION.matcher(s).matches() && s.length() <= 100) {
            if (actions.size() < 4000 && hasAny(l, CAR_WORDS)) actions.add(s);
            return;
        }
        if (s.length() <= 40 && s.indexOf(' ') < 0 && hasAny(l, FIELD_WORDS) && fields.size() < 1500) fields.add(s);
    }

    static boolean hasAny(String l, String[] words) {
        for (String w : words) if (l.contains(w)) return true;
        return false;
    }
}
