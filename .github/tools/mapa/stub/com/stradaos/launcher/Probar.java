package com.stradaos.launcher;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Banco de pruebas del mapa sin conexión fuera de Android (las clases android.graphics de esta carpeta imitan las
 * reales con Java2D). Uso: java Probar mapa.map salida.png lat lon zoom [teselas por lado] [claro|oscuro] [tamaño]
 */
public class Probar {
    public static void main(String[] a) throws Exception {
        OfflineMap m = OfflineMap.open(new File(a[0]));
        System.out.println("Mapa: " + m.describe() + " · área " + m.minLat + "," + m.minLon + " → " + m.maxLat + "," + m.maxLon);
        if (a.length < 5) return;
        double lat = Double.parseDouble(a[2]), lon = Double.parseDouble(a[3]);
        int z = Integer.parseInt(a[4]), n = a.length > 5 ? Integer.parseInt(a[5]) : 3;
        boolean light = a.length > 6 && a[6].startsWith("c");
        int size = a.length > 7 ? Integer.parseInt(a[7]) : 256;
        int cx = (int) Math.floor(OfflineMap.lonX(lon, z)), cy = (int) Math.floor(OfflineMap.latY(lat, z));
        BufferedImage out = new BufferedImage(n * size, n * size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int x = cx - n / 2 + i, y = cy - n / 2 + j;
            long t = System.currentTimeMillis();
            android.graphics.Bitmap b = m.render(z, x, y, size, light, 1);
            System.out.println("tesela " + z + "/" + x + "/" + y + ": " + (System.currentTimeMillis() - t) + " ms");
            g.drawImage(b.img, i * size, j * size, null);
        }
        System.out.println("total " + (System.currentTimeMillis() - t0) + " ms");
        javax.imageio.ImageIO.write(out, "png", new File(a[1]));
    }
}
