package com.craiver.canprobe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lee el AndroidManifest.xml binario de un APK y devuelve, por componente, las acciones de sus intent-filter,
 * los permisos y las autoridades de proveedores. Con eso se sabe qué órdenes acepta el servicio del CAN.
 */
public class Axml {

    /** Una línea por dato: "receiver com.x.Y  action=com.x.DOOR" etc. */
    public static List<String> read(File apk) {
        List<String> out = new ArrayList<String>();
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return out;
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            parse(bo.toByteArray(), out);
        } catch (Throwable t) {
            out.add("! manifest: " + t);
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void parse(byte[] d, List<String> out) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        String[] strings = new String[0];
        String component = null, compName = null;
        int pos = 8;   // cabecera del XML
        while (pos + 8 <= d.length) {
            int type = b.getShort(pos) & 0xFFFF;
            int headerSize = b.getShort(pos + 2) & 0xFFFF;
            int size = b.getInt(pos + 4);
            if (size <= 0 || pos + size > d.length) break;
            if (type == 0x0001) {
                strings = stringPool(b, pos);
            } else if (type == 0x0102) {
                int nameIdx = b.getInt(pos + 20);
                String tag = str(strings, nameIdx);
                int attrStart = b.getShort(pos + 24) & 0xFFFF;
                int attrSize = b.getShort(pos + 26) & 0xFFFF;
                int attrCount = b.getShort(pos + 28) & 0xFFFF;
                String name = null, authorities = null, permission = null, shared = null, process = null;
                int a0 = pos + 16 + attrStart;
                for (int i = 0; i < attrCount; i++) {
                    int at = a0 + i * attrSize;
                    String an = str(strings, b.getInt(at + 4));
                    int raw = b.getInt(at + 8);
                    int dataType = b.get(at + 15) & 0xFF;
                    int data = b.getInt(at + 16);
                    String v = raw >= 0 ? str(strings, raw) : dataType == 0x03 ? str(strings, data) : dataType == 0x12 ? (data != 0 ? "true" : "false") : String.valueOf(data);
                    if ("name".equals(an)) name = v;
                    else if ("authorities".equals(an)) authorities = v;
                    else if ("permission".equals(an)) permission = v;
                    else if ("sharedUserId".equals(an)) shared = v;
                    else if ("process".equals(an)) process = v;
                }
                if ("manifest".equals(tag) && shared != null) out.add("sharedUserId " + shared);
                if ("activity".equals(tag) || "receiver".equals(tag) || "service".equals(tag) || "provider".equals(tag) || "activity-alias".equals(tag)) {
                    component = tag;
                    compName = name;
                    StringBuilder sb = new StringBuilder(tag + " " + name);
                    if (permission != null) sb.append("  permission=").append(permission);
                    if (authorities != null) sb.append("  authorities=").append(authorities);
                    if (process != null) sb.append("  process=").append(process);
                    out.add(sb.toString());
                } else if ("action".equals(tag) && component != null) {
                    out.add("  " + component + " " + compName + "  action=" + name);
                } else if ("uses-permission".equals(tag) || "permission".equals(tag)) {
                    out.add(tag + " " + name);
                }
            }
            pos += size;
            if (headerSize == 0) break;
        }
    }

    private static String str(String[] s, int i) { return i >= 0 && i < s.length ? s[i] : null; }

    private static String[] stringPool(ByteBuffer b, int pos) {
        int count = b.getInt(pos + 8);
        int flags = b.getInt(pos + 16);
        int stringsStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        String[] r = new String[count];
        for (int i = 0; i < count; i++) {
            int off = pos + stringsStart + b.getInt(pos + 28 + i * 4);
            try {
                if (utf8) {
                    int p = off;
                    if ((b.get(p) & 0x80) != 0) p += 2; else p += 1;   // largo en UTF-16
                    int len = b.get(p) & 0xFF;
                    if ((len & 0x80) != 0) { len = ((len & 0x7F) << 8) | (b.get(p + 1) & 0xFF); p += 2; } else p += 1;
                    byte[] bs = new byte[len];
                    for (int k = 0; k < len; k++) bs[k] = b.get(p + k);
                    r[i] = new String(bs, Charset.forName("UTF-8"));
                } else {
                    int len = b.getShort(off) & 0xFFFF;
                    int p = off + 2;
                    if ((len & 0x8000) != 0) { len = ((len & 0x7FFF) << 16) | (b.getShort(off + 2) & 0xFFFF); p += 2; }
                    char[] cs = new char[len];
                    for (int k = 0; k < len; k++) cs[k] = b.getChar(p + k * 2);
                    r[i] = new String(cs);
                }
            } catch (Throwable t) {
                r[i] = null;
            }
        }
        return r;
    }
}
