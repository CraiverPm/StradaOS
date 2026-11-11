package com.craiver.canprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CAN Probe: descubre de dónde saca la pantalla los datos del carro (puertas, RPM, velocidad…).
 * 1) Escanea: apps del fabricante (manifiesto + textos del código), propiedades del sistema, Settings, servicios.
 * 2) Vigila en vivo: todos los avisos (broadcasts) candidatos + cambios en propiedades y Settings, con marcas
 *    («abrí la puerta», «aceleré») para relacionar cada cambio con lo que pasó en el carro.
 * 3) Guarda y comparte el reporte.
 */
public class Probe extends Activity {

    // L = texto de izquierda a derecha dentro de una frase en árabe
    static String L(String s) { return "⁦" + s + "⁩"; }

    static final int BG = 0xFF101317, CARD = 0xFF1B2027, TEXT = 0xFFECEFF3, MUTED = 0xFF9AA3AE, ACCENT = 0xFFE5392B, OK = 0xFF2E9D5B;
    static final String[] PKG_WORDS = {"can", "car", "mcu", "syu", "microntek", "mtc", "vehicle", "hiworld", "raise", "xbs",
            "fyt", "topway", "hct", "autochips", "atc", "nwd", "launcher", "obd", "climate", "aircon", "tpms", "radar",
            "carinfo", "carsetting", "setting", "service", "ui"};
    // nombres que se han visto en pantallas chinas; se vigilan además de los encontrados
    static final String[] KNOWN_ACTIONS = {"com.microntek.canbusdisplay", "com.microntek.CANBUS", "com.microntek.carinfo",
            "com.syu.canbus", "com.syu.ms.canbus", "com.android.canbus", "com.android.canbus.data", "com.hct.can.info",
            "com.ts.can.CAN_INFO", "com.nwd.can.info", "com.hiworld.can.info", "com.autochips.canbus", "android.canbus.action",
            "com.car.canbus.data", "com.canbus.data", "com.can.data", "com.xbs.canbus", "com.fyt.canbus"};

    private Handler ui;
    private TextView status, live;
    private Button scanBtn, monBtn, saveBtn;
    private final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat FN = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private float dp;

    private volatile boolean scanning, monitoring;
    private final Set<String> actions = new LinkedHashSet<String>();
    private File reportFile, eventsFile;
    private Writer events;
    private BroadcastReceiver receiver;
    private ContentObserver observer;
    private PowerManager.WakeLock wake;
    private final Map<String, String> props = new HashMap<String, String>();
    private final Map<String, String> settings = new HashMap<String, String>();
    private int eventCount;

    /** Tabla en vivo: clave → último valor, veces que cambió, hora del último cambio. */
    private static class Row { String value; int changes; long at; }
    private final Map<String, Row> rows = new LinkedHashMap<String, Row>();
    private boolean redrawQueued;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Handler(Looper.getMainLooper());
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    // ── interfaz ──

    private int px(float v) { return Math.round(v * dp); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        return g;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setBackground(round(color, 12));
        btn.setPadding(px(14), px(10), px(14), px(10));
        btn.setOnClickListener(l);
        return btn;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(px(16), px(16), px(16), px(12));

        root.addView(text("فاحص الكان باص · " + L("CAN Probe"), 22, TEXT, true));
        TextView hint = text("١) افحص الشاشة  ٢) ابدأ المراقبة واعمل حاجات في العربية ودوس على العلامة اللي تناسبها  ٣) احفظ وابعت التقرير", 13, MUTED, false);
        hint.setPadding(0, px(4), 0, px(10));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scanBtn = button("١. افحص الشاشة", ACCENT, new View.OnClickListener() { public void onClick(View v) { startScan(false); } });
        monBtn = button("٢. ابدأ المراقبة", 0xFF2D3540, new View.OnClickListener() { public void onClick(View v) { toggleMonitor(); } });
        saveBtn = button("٣. احفظ وابعت", 0xFF2D3540, new View.OnClickListener() { public void onClick(View v) { saveAndShare(); } });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.setMarginEnd(px(8));
        row.addView(scanBtn, bl);
        row.addView(monBtn, new LinearLayout.LayoutParams(bl));
        row.addView(saveBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);

        status = text("جاهز. ابدأ بالفحص.", 14, TEXT, false);
        status.setBackground(round(CARD, 12));
        status.setPadding(px(12), px(10), px(12), px(10));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = px(10);
        root.addView(status, sl);

        // علامات: يدوس عليها وقت ما يعمل الحاجة في العربية
        TextView mt = text("علامات (دوس عليها في نفس لحظة ما تعمل الحاجة):", 13, MUTED, false);
        mt.setPadding(0, px(10), 0, px(6));
        root.addView(mt);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout marks = new LinearLayout(this);
        String[] labels = {"فتحت باب السواق", "قفلت الباب", "فتحت الشنطة", "قفلت الشنطة", "دورت الموتور", "دوست بنزين",
                "العربية ماشية", "وقفت", "حطيت ريفرس", "فرامل اليد", "حزام الأمان", "شغلت النور", "شغلت التكييف",
                "غيرت الحرارة", "طفيت الموتور", "علامة"};
        for (final String lb : labels) {
            Button m = button(lb, 0xFF2D3540, new View.OnClickListener() { public void onClick(View v) { mark(lb); } });
            m.setTextSize(13);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.setMarginEnd(px(6));
            marks.addView(m, ml);
        }
        hs.addView(marks);
        root.addView(hs);

        TextView lt = text("اللي بيتغير دلوقتي (الأحدث فوق):", 13, MUTED, false);
        lt.setPadding(0, px(10), 0, px(6));
        root.addView(lt);
        ScrollView sc = new ScrollView(this);
        sc.setBackground(round(0xFF0B0D10, 12));
        live = text("", 12, 0xFFB9F6CA, false);
        live.setTypeface(Typeface.MONOSPACE);
        live.setTextDirection(View.TEXT_DIRECTION_LTR);
        live.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        live.setPadding(px(10), px(10), px(10), px(10));
        sc.addView(live);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { public void run() { status.setText(s); } });
    }

    // ── archivos ──

    static File outDir(Context c) {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CanProbe");
        if (d.isDirectory() || d.mkdirs()) {
            File t = new File(d, ".w");
            try { if (t.createNewFile() || t.exists()) { t.delete(); return d; } } catch (Throwable ignored) { }
        }
        File f = c.getExternalFilesDir(null);
        if (f == null) f = c.getFilesDir();
        return f;
    }

    // ── 1) escaneo ──

    private void startScan(final boolean thenMonitor) {
        if (scanning) return;
        scanning = true;
        scanBtn.setEnabled(false);
        setStatus("بيفحص الشاشة… ممكن ياخد دقيقة أو اتنين.");
        new Thread(new Runnable() {
            public void run() {
                String summary;
                try {
                    summary = scan();
                } catch (Throwable t) {
                    summary = "حصلت مشكلة في الفحص: " + L(String.valueOf(t));
                }
                final String fs = summary;
                ui.post(new Runnable() {
                    public void run() {
                        scanning = false;
                        scanBtn.setEnabled(true);
                        scanBtn.setText("١. افحص تاني");
                        status.setText(fs);
                        if (thenMonitor) startMonitor();
                    }
                });
            }
        }).start();
    }

    private String scan() throws Exception {
        File dir = outDir(this);
        reportFile = new File(dir, "report-" + FN.format(new Date()) + ".txt");
        Writer w = new OutputStreamWriter(new FileOutputStream(reportFile), "UTF-8");
        PackageManager pm = getPackageManager();

        w.write("CAN Probe report · " + new Date() + "\n\n== DEVICE ==\n");
        w.write("manufacturer=" + Build.MANUFACTURER + "\nbrand=" + Build.BRAND + "\nmodel=" + Build.MODEL + "\ndevice=" + Build.DEVICE
                + "\nboard=" + Build.BOARD + "\nhardware=" + Build.HARDWARE + "\nproduct=" + Build.PRODUCT + "\ndisplay=" + Build.DISPLAY
                + "\nfingerprint=" + Build.FINGERPRINT + "\nandroid=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
        w.write("automotive feature=" + pm.hasSystemFeature("android.hardware.type.automotive") + "\n");

        setStatus("بيقرا خصائص النظام…");
        String getprop = exec("getprop", 8000);
        w.write("\n== GETPROP (all) ==\n" + getprop + "\n");
        props.clear();
        props.putAll(parseProps(getprop));

        setStatus("بيقرا إعدادات النظام…");
        settings.clear();
        settings.putAll(readSettings());
        w.write("\n== SETTINGS (system/global/secure) ==\n");
        for (Map.Entry<String, String> e : new TreeMap<String, String>(settings).entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");

        // apps de inicio (el launcher original muestra los datos del CAN: su código dice de dónde los saca)
        Set<String> home = new LinkedHashSet<String>();
        Intent hi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(hi, 0)) home.add(ri.activityInfo.packageName);
        w.write("\n== HOME APPS ==\n" + home + "\n");

        List<PackageInfo> all = pm.getInstalledPackages(0);
        List<String> names = new ArrayList<String>();
        for (PackageInfo p : all) names.add(p.packageName + ((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "  [system]" : "") + "  " + p.applicationInfo.sourceDir);
        Collections.sort(names);
        w.write("\n== ALL PACKAGES (" + names.size() + ") ==\n");
        for (String n : names) w.write(n + "\n");

        List<PackageInfo> cands = new ArrayList<PackageInfo>();
        for (PackageInfo p : all) {
            if (p.packageName.equals(getPackageName())) continue;
            String l = p.packageName.toLowerCase(Locale.US);
            boolean sys = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (home.contains(p.packageName)) { cands.add(p); continue; }
            if (l.startsWith("com.google.") || l.startsWith("com.android.providers") || l.startsWith("android.")) continue;
            if (Strings.hasAny(l, PKG_WORDS) && (sys || l.contains("can") || l.contains("car") || l.contains("mcu"))) cands.add(p);
        }
        // el launcher original primero
        Collections.sort(cands, new Comparator<PackageInfo>() {
            public int compare(PackageInfo a, PackageInfo b) { return Boolean.compare(!home.contains(a.packageName), !home.contains(b.packageName)); }
        });

        Set<String> manifestActions = new LinkedHashSet<String>();
        Strings strings = new Strings();
        List<String> vendor = new ArrayList<String>();
        int i = 0;
        for (PackageInfo p : cands) {
            i++;
            setStatus("بيفحص التطبيقات " + i + "/" + cands.size() + ": " + L(p.packageName));
            w.write("\n== APP " + p.packageName + " ==\nsource=" + p.applicationInfo.sourceDir + "\nversion=" + p.versionName + "\n");
            for (String line : Axml.read(new File(p.applicationInfo.sourceDir))) {
                w.write(line + "\n");
                int k = line.indexOf("action=");
                if (k > 0) manifestActions.add(line.substring(k + 7).trim());
            }
            Strings one = new Strings();
            if (cands.size() > 25 && !home.contains(p.packageName)) one.budget = 40L << 20;
            one.scanApk(new File(p.applicationInfo.sourceDir));
            w.write("-- broadcast-like strings (" + one.actions.size() + ")\n");
            for (String s : one.actions) w.write("   " + s + "\n");
            w.write("-- field-like strings (" + one.fields.size() + ")\n");
            for (String s : one.fields) w.write("   " + s + "\n");
            w.write("-- device paths\n");
            for (String s : one.paths) w.write("   " + s + "\n");
            strings.actions.addAll(one.actions);
            strings.fields.addAll(one.fields);
            strings.paths.addAll(one.paths);
            String l = p.packageName.toLowerCase(Locale.US);
            for (String v : new String[]{"syu", "microntek", "fyt", "hct", "nwd", "autochips", "atc", "hiworld", "raise", "xbs", "topway", "ts."})
                if (l.contains(v)) vendor.add(p.packageName);
        }

        // librerías del fabricante en /system/framework (a veces el SDK del CAN vive ahí)
        setStatus("بيفحص ملفات النظام…");
        File[] fw = new File("/system/framework").listFiles();
        if (fw != null) for (File f : fw) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".jar") && Strings.hasAny(n, new String[]{"can", "car", "mcu", "syu", "vehicle", "microntek", "fyt"})) {
                Strings one = new Strings();
                one.scanFile(f);
                w.write("\n== FRAMEWORK " + f + " ==\n");
                for (String s : one.actions) w.write("   " + s + "\n");
                for (String s : one.fields) w.write("   field " + s + "\n");
                strings.actions.addAll(one.actions);
                strings.fields.addAll(one.fields);
            }
        }

        w.write("\n== SERVICE LIST ==\n" + exec("service list", 8000));
        w.write("\n== /dev ==\n" + exec("ls -l /dev", 5000));
        w.write("\n== /proc/tty/drivers ==\n" + exec("cat /proc/tty/drivers", 3000));
        w.write("\n== /sys/class/tty (names) ==\n" + exec("ls /sys/class/tty", 3000));
        w.write("\n== DEVICE PATHS FOUND IN CODE ==\n");
        for (String s : strings.paths) w.write(s + "  " + (new File(s).canRead() ? "[readable]" : "") + "\n");

        // proveedores de contenido de las apps candidatas
        w.write("\n== CONTENT PROVIDERS ==\n");
        for (PackageInfo p : cands) {
            try {
                PackageInfo pi = pm.getPackageInfo(p.packageName, PackageManager.GET_PROVIDERS);
                if (pi.providers == null) continue;
                for (android.content.pm.ProviderInfo pr : pi.providers) {
                    w.write(p.packageName + " " + pr.authority + " exported=" + pr.exported + " read=" + pr.readPermission + "\n");
                    if (pr.authority == null || !pr.exported) continue;
                    for (String auth : pr.authority.split(";")) w.write("   " + queryProvider("content://" + auth) + "\n");
                }
            } catch (Throwable t) { w.write(p.packageName + " ! " + t + "\n"); }
        }

        // logcat del sistema (si la pantalla lo deja leer)
        String log = exec("logcat -d -v time -t 6000", 10000);
        w.write("\n== LOGCAT (filtered) ==\n");
        int logLines = 0;
        for (String line : log.split("\n")) {
            String l = line.toLowerCase(Locale.US);
            if (Strings.hasAny(l, new String[]{"can", "mcu", "door", "rpm", "speed", "vehicle", "carinfo", "canbus", "obd"})) { w.write(line + "\n"); logLines++; }
        }

        // acciones a vigilar: primero las del manifiesto, luego las del código, luego las conocidas
        actions.clear();
        for (String a : manifestActions) if (worthWatching(a)) actions.add(a);
        for (String a : strings.actions) if (worthWatching(a)) actions.add(a);
        actions.addAll(Arrays.asList(KNOWN_ACTIONS));
        if (actions.size() > 3000) {
            List<String> l = new ArrayList<String>(actions);
            actions.clear();
            actions.addAll(l.subList(0, 3000));
        }
        w.write("\n== WATCHED ACTIONS (" + actions.size() + ") ==\n");
        for (String a : actions) w.write(a + "\n");
        w.write("\n== ALL FIELD-LIKE STRINGS ==\n");
        for (String s : strings.fields) w.write(s + "\n");
        w.close();

        StringBuilder sum = new StringBuilder();
        sum.append("تم الفحص ✓\n");
        sum.append("الشاشة: ").append(L(Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.HARDWARE)).append("\n");
        sum.append("اللانشر الأصلي: ").append(L(home.toString())).append("\n");
        if (!vendor.isEmpty()) sum.append("تطبيقات المصنّع: ").append(L(vendor.toString())).append("\n");
        sum.append("تطبيقات اتفحصت: ").append(cands.size()).append(" · إشارات هنراقبها: ").append(actions.size())
           .append(" · أسماء بيانات: ").append(strings.fields.size()).append("\n");
        sum.append(logLines > 0 ? "سجل النظام متاح ✓" : "سجل النظام مقفول (عادي)").append("\n");
        sum.append("الخطوة الجاية: دوس «ابدأ المراقبة».");
        return sum.toString();
    }

    private boolean worthWatching(String a) {
        if (a == null || a.length() < 5 || a.length() > 120) return false;
        String l = a.toLowerCase(Locale.US);
        if (l.startsWith("android.") || l.startsWith("com.android.internal") || l.startsWith("com.google.") || l.startsWith("java.") || l.startsWith("javax.")) {
            return Strings.hasAny(l, new String[]{"can", "mcu", "car", "door", "rpm", "vehicle"});
        }
        return true;
    }

    private String queryProvider(String uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(Uri.parse(uri), null, null, null, null);
            if (c == null) return uri + " → null";
            StringBuilder sb = new StringBuilder(uri + " → cols=" + Arrays.toString(c.getColumnNames()) + " rows=" + c.getCount());
            int n = 0;
            while (c.moveToNext() && n++ < 20) {
                sb.append("\n      ");
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String v;
                    try { v = c.getString(i); } catch (Throwable t) { v = "?"; }
                    sb.append(c.getColumnName(i)).append('=').append(v).append(' ');
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return uri + " ! " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) c.close();
        }
    }

    private Map<String, String> readSettings() {
        Map<String, String> m = new HashMap<String, String>();
        for (String table : new String[]{"system", "global", "secure"}) {
            Cursor c = null;
            try {
                c = getContentResolver().query(Uri.parse("content://settings/" + table), new String[]{"name", "value"}, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) m.put(table + "/" + c.getString(0), c.getString(1));
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        return m;
    }

    static Map<String, String> parseProps(String out) {
        Map<String, String> m = new HashMap<String, String>();
        for (String line : out.split("\n")) {
            int a = line.indexOf("]: [");
            if (line.startsWith("[") && a > 0 && line.endsWith("]")) m.put(line.substring(1, a), line.substring(a + 4, line.length() - 1));
        }
        return m;
    }

    static String exec(String cmd, long timeoutMs) {
        final StringBuilder sb = new StringBuilder();
        try {
            final Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd + " 2>&1"});
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null && sb.length() < 3000000) {
                            synchronized (sb) { sb.append(line).append('\n'); }
                        }
                    } catch (Throwable ignored) { }
                }
            });
            t.start();
            t.join(timeoutMs);
            p.destroy();
        } catch (Throwable e) {
            sb.append("! ").append(e);
        }
        synchronized (sb) { return sb.toString(); }
    }

    // ── 2) vigilancia en vivo ──

    private void toggleMonitor() {
        if (monitoring) stopMonitor();
        else if (actions.isEmpty()) startScan(true);
        else startMonitor();
    }

    private void startMonitor() {
        try {
            eventsFile = new File(outDir(this), "events-" + FN.format(new Date()) + ".txt");
            events = new FileWriter(eventsFile, true);
        } catch (Throwable t) {
            events = null;
        }
        monitoring = true;
        eventCount = 0;
        monBtn.setText("وقف المراقبة");
        monBtn.setBackground(round(OK, 12));
        PowerManager pmr = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pmr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "canprobe:monitor");
        wake.acquire(4 * 60 * 60 * 1000L);

        receiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent in) { onBroadcast(in); }
        };
        // varios filtros de hasta 500 acciones (más estable que uno gigante)
        List<String> list = new ArrayList<String>(actions);
        int sticky = 0;
        for (int i = 0; i < list.size(); i += 500) {
            IntentFilter f = new IntentFilter();
            for (String a : list.subList(i, Math.min(list.size(), i + 500))) f.addAction(a);
            try {
                Intent st = registerReceiver(receiver, f);
                if (st != null) { sticky++; onBroadcast(st); }
            } catch (Throwable t) {
                log("ERR", "register", String.valueOf(t));
            }
        }

        observer = new ContentObserver(ui) {
            public void onChange(boolean self, Uri uri) { pollSettings(); }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://settings/system"), true, observer);
            getContentResolver().registerContentObserver(Uri.parse("content://settings/global"), true, observer);
            getContentResolver().registerContentObserver(Uri.parse("content://settings/secure"), true, observer);
        } catch (Throwable ignored) { }

        // propiedades: se revisan cada segundo y se anota lo que cambia
        new Thread(new Runnable() {
            public void run() {
                while (monitoring) {
                    Map<String, String> now = parseProps(exec("getprop", 3000));
                    for (Map.Entry<String, String> e : now.entrySet()) {
                        String old = props.get(e.getKey());
                        if (old == null || !old.equals(e.getValue())) {
                            if (old != null || !props.isEmpty()) log("PROP", e.getKey(), e.getValue());
                            props.put(e.getKey(), e.getValue());
                        }
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
            }
        }).start();

        setStatus("المراقبة شغالة ✓ بنسمع " + actions.size() + " إشارة + خصائص النظام + الإعدادات.\n"
                + "دلوقتي: افتح الباب واقفله، دوّر الموتور ودوس بنزين، اتحرك شوية، شغّل النور والتكييف.\n"
                + "ودوس على العلامة المناسبة في نفس اللحظة.");
        log("INFO", "monitor", "started · actions=" + actions.size() + " sticky=" + sticky);
    }

    private void stopMonitor() {
        monitoring = false;
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Throwable ignored) { }
        try { if (observer != null) getContentResolver().unregisterContentObserver(observer); } catch (Throwable ignored) { }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) { }
        receiver = null;
        observer = null;
        log("INFO", "monitor", "stopped · events=" + eventCount);
        try { if (events != null) events.flush(); } catch (Throwable ignored) { }
        monBtn.setText("٢. ابدأ المراقبة");
        monBtn.setBackground(round(0xFF2D3540, 12));
        setStatus("وقفت المراقبة. اتسجل " + eventCount + " حدث. دوس «احفظ وابعت».");
    }

    private void pollSettings() {
        Map<String, String> now = readSettings();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String old = settings.get(e.getKey());
            String v = e.getValue() == null ? "null" : e.getValue();
            if (old == null || !old.equals(v)) {
                log("SET", e.getKey(), v);
                settings.put(e.getKey(), v);
            }
        }
    }

    private void onBroadcast(Intent in) {
        StringBuilder sb = new StringBuilder();
        if (in.getData() != null) sb.append("data=").append(in.getData()).append(' ');
        Bundle ex = in.getExtras();
        if (ex != null) dumpBundle(ex, sb, 0);
        log("BC", in.getAction(), sb.length() == 0 ? "(no extras)" : sb.toString().trim());
    }

    private void dumpBundle(Bundle b, StringBuilder sb, int depth) {
        try {
            for (String k : new java.util.TreeSet<String>(b.keySet())) {
                Object v = b.get(k);
                sb.append(k).append('=').append(fmt(v, depth)).append(' ');
            }
        } catch (Throwable t) {
            sb.append("(unreadable: ").append(t.getClass().getSimpleName()).append(')');
        }
    }

    private String fmt(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof Bundle && depth < 2) { StringBuilder sb = new StringBuilder("{"); dumpBundle((Bundle) v, sb, depth + 1); return sb.append('}').toString(); }
        if (v instanceof byte[]) {
            byte[] a = (byte[]) v;
            StringBuilder sb = new StringBuilder("hex[");
            for (int i = 0; i < Math.min(a.length, 64); i++) sb.append(String.format(Locale.US, "%02X", a[i] & 0xFF)).append(i < a.length - 1 ? " " : "");
            return sb.append(a.length > 64 ? "…" : "").append(']').toString();
        }
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof double[]) return Arrays.toString((double[]) v);
        if (v instanceof boolean[]) return Arrays.toString((boolean[]) v);
        if (v instanceof String[]) return Arrays.toString((String[]) v);
        if (v instanceof Object[]) return Arrays.deepToString((Object[]) v);
        return v.getClass().getSimpleName().equals("String") ? v.toString() : v + ":" + v.getClass().getSimpleName();
    }

    private void mark(String label) {
        log("MARK", "----------", ">>> " + label + " <<<");
    }

    private synchronized void log(String src, String key, String value) {
        long t = System.currentTimeMillis();
        String line = TS.format(new Date(t)) + "  " + src + "  " + key + "  " + value;
        eventCount++;
        try { if (events != null) { events.write(line + "\n"); events.flush(); } } catch (Throwable ignored) { }
        String rk = "MARK".equals(src) ? "MARK" : src + "  " + key;
        Row r = rows.get(rk);
        if (r == null) { r = new Row(); rows.put(rk, r); }
        if (!value.equals(r.value)) r.changes++;
        r.value = value;
        r.at = t;
        if (!redrawQueued) {
            redrawQueued = true;
            ui.postDelayed(new Runnable() { public void run() { redraw(); } }, 250);
        }
    }

    private synchronized void redraw() {
        redrawQueued = false;
        List<Map.Entry<String, Row>> l = new ArrayList<Map.Entry<String, Row>>(rows.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Row>>() {
            public int compare(Map.Entry<String, Row> a, Map.Entry<String, Row> b) { return Long.compare(b.getValue().at, a.getValue().at); }
        });
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("events: ").append(eventCount).append("\n\n");
        int n = 0;
        for (Map.Entry<String, Row> e : l) {
            if (n++ >= 80) break;
            Row r = e.getValue();
            String v = r.value.length() > 300 ? r.value.substring(0, 300) + "…" : r.value;
            sb.append(now - r.at < 3000 ? "● " : "  ").append(TS.format(new Date(r.at)).substring(0, 8)).append("  ×").append(r.changes)
              .append("  ").append(e.getKey()).append("\n      ").append(v).append("\n");
        }
        live.setText(sb.toString());
    }

    // ── 3) guardar y compartir ──

    private void saveAndShare() {
        if (reportFile == null) { setStatus("اعمل الفحص الأول."); return; }
        try { if (events != null) events.flush(); } catch (Throwable ignored) { }
        final List<File> files = new ArrayList<File>();
        files.add(reportFile);
        if (eventsFile != null && eventsFile.exists()) files.add(eventsFile);

        // copia a cualquier memoria USB conectada
        StringBuilder usb = new StringBuilder();
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            if (v.getName().equals("emulated") || v.getName().equals("self") || !v.canWrite()) continue;
            for (File f : files) {
                try {
                    File d = new File(v, "CanProbe");
                    d.mkdirs();
                    copy(f, new File(d, f.getName()));
                    if (usb.indexOf(v.getName()) < 0) usb.append(v.getName()).append(' ');
                } catch (Throwable ignored) { }
            }
        }
        setStatus("اتحفظ في: " + L(reportFile.getParent()) + (usb.length() > 0 ? "\nواتنسخ على الفلاشة: " + L(usb.toString().trim()) : "")
                + "\nابعتلي الملفين " + L(reportFile.getName()) + (files.size() > 1 ? " و " + L(eventsFile.getName()) : ""));

        try {
            Intent s = new Intent(files.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_SUBJECT, "CAN Probe report");
            if (files.size() > 1) {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                for (File f : files) uris.add(Files.uri(f));
                s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                s.putExtra(Intent.EXTRA_STREAM, Files.uri(files.get(0)));
            }
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "ابعت التقرير"));
        } catch (Throwable t) {
            // sin apps para compartir: queda el archivo en Download/CanProbe
        }
    }

    private static void copy(File a, File b) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    @Override
    protected void onDestroy() {
        if (monitoring) stopMonitor();
        try { if (events != null) events.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
