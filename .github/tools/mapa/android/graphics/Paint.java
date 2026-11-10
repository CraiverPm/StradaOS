package android.graphics;
import java.awt.Font;
import java.awt.font.FontRenderContext;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2;
    public enum Style { FILL, STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    int color = 0xFF000000; float width = 1, textSize = 12; Style style = Style.FILL; boolean bold; int alpha = 255;
    Cap cap = Cap.BUTT; Join join = Join.MITER;
    public Paint() { }
    public Paint(int flags) { }
    public Paint(Paint o) { color = o.color; width = o.width; textSize = o.textSize; style = o.style; bold = o.bold; alpha = o.alpha; cap = o.cap; join = o.join; }
    public void setColor(int c) { color = c; alpha = (c >>> 24); }
    public void setAlpha(int a) { alpha = a; }
    public void setStyle(Style s) { style = s; }
    public void setStrokeWidth(float w) { width = w; }
    public void setStrokeCap(Cap c) { cap = c; }
    public void setStrokeJoin(Join j) { join = j; }
    public void setTextSize(float s) { textSize = s; }
    public float getTextSize() { return textSize; }
    public void setTypeface(Typeface t) { bold = t != null; }
    Font font() { return new Font("DejaVu Sans", bold ? Font.BOLD : Font.PLAIN, Math.max(1, Math.round(textSize))).deriveFont(textSize); }
    public float measureText(String s) { return (float) font().getStringBounds(s, new FontRenderContext(null, true, true)).getWidth(); }
}
