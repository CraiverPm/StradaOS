package android.graphics;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
public class Canvas {
    final Graphics2D g;
    public Canvas(Bitmap b) {
        g = b.img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
    Color col(Paint p) { return new Color((p.color >> 16) & 255, (p.color >> 8) & 255, p.color & 255, p.alpha); }
    Stroke stroke(Paint p) {
        return new BasicStroke(p.width, p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : p.cap == Paint.Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT,
            p.join == Paint.Join.ROUND ? BasicStroke.JOIN_ROUND : p.join == Paint.Join.BEVEL ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER);
    }
    public void drawColor(int c) { g.setColor(new Color(c)); g.fillRect(-10, -10, 100000, 100000); }
    public void drawPath(Path path, Paint p) {
        g.setColor(col(p));
        if (p.style == Paint.Style.FILL) g.fill(path.p); else { g.setStroke(stroke(p)); g.draw(path.p); }
    }
    public void drawText(String s, float x, float y, Paint p) {
        g.setFont(p.font()); g.setColor(col(p));
        GlyphVector gv = p.font().createGlyphVector(g.getFontRenderContext(), s);
        Shape sh = gv.getOutline(x, y);
        if (p.style == Paint.Style.STROKE) { g.setStroke(stroke(p)); g.draw(sh); } else g.fill(sh);
    }
    /** Texto sobre una línea: cada letra en su punto y con el ángulo del tramo. */
    public void drawTextOnPath(String s, Path path, float hOff, float vOff, Paint p) {
        java.util.List<float[]> pts = new java.util.ArrayList<float[]>();
        float[] c = new float[6];
        for (PathIterator it = path.p.getPathIterator(null); !it.isDone(); it.next()) { it.currentSegment(c); pts.add(new float[]{c[0], c[1]}); }
        Font f = p.font();
        GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), s);
        g.setColor(col(p));
        for (int i = 0; i < s.length(); i++) {
            Point2D gp = gv.getGlyphPosition(i);
            float adv = gv.getGlyphMetrics(i).getAdvance();
            float d = hOff + (float) gp.getX() + adv / 2;
            float acc = 0;
            for (int k = 1; k < pts.size(); k++) {
                float[] a = pts.get(k - 1), b = pts.get(k);
                float sl = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (acc + sl >= d || k == pts.size() - 1) {
                    float t = sl == 0 ? 0 : (d - acc) / sl;
                    double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                    AffineTransform at = new AffineTransform();
                    at.translate(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
                    at.rotate(ang);
                    at.translate(-adv / 2 - gp.getX(), vOff);
                    Shape sh = at.createTransformedShape(gv.getGlyphOutline(i));
                    if (p.style == Paint.Style.STROKE) { g.setStroke(stroke(p)); g.draw(sh); } else g.fill(sh);
                    break;
                }
                acc += sl;
            }
        }
    }
}
