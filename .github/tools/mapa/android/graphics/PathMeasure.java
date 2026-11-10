package android.graphics;
import java.awt.geom.PathIterator;
public class PathMeasure {
    float len;
    public void setPath(Path path, boolean closed) {
        len = 0; float[] c = new float[6]; float lx = 0, ly = 0;
        for (PathIterator it = path.p.getPathIterator(null); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_LINETO) len += Math.hypot(c[0] - lx, c[1] - ly);
            lx = c[0]; ly = c[1];
        }
    }
    public float getLength() { return len; }
}
