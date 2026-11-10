package android.graphics;
import java.awt.image.BufferedImage;
public class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    public final BufferedImage img;
    private Bitmap(int w, int h) { img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); }
    public static Bitmap createBitmap(int w, int h, Config c) { return new Bitmap(w, h); }
    public int getWidth() { return img.getWidth(); }
    public int getHeight() { return img.getHeight(); }
}
