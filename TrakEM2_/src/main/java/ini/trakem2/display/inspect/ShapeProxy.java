package ini.trakem2.display.inspect;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public class ShapeProxy implements Shape
{
	private Shape s;
	
	/** Create a new proxy for {@param s}. */
	public ShapeProxy(final Shape s) {
		this.s = s;
	}
	
	/** Replace the wrapped {@link Shape} with {@param s}.
	 * 
	 * @param s
	 */
	public final void set(final Shape s) {
		this.s = s;
	}
	
	@Override
	public final Rectangle getBounds() {
		return s.getBounds();
	}

	@Override
	public final Rectangle2D getBounds2D() {
		return s.getBounds2D();
	}

	@Override
	public final boolean contains(double x, double y) {
		return s.contains(x, y);
	}

	@Override
	public final boolean contains(Point2D p) {
		return s.contains(p);
	}

	@Override
	public final boolean intersects(double x, double y, double w, double h) {
		return s.intersects(x, y, w, h);
	}

	@Override
	public final boolean intersects(Rectangle2D r) {
		return s.intersects(r);
	}

	@Override
	public final boolean contains(double x, double y, double w, double h) {
		return s.contains(x, y, w, h);
	}

	@Override
	public final boolean contains(Rectangle2D r) {
		return s.contains(r);
	}

	@Override
	public final PathIterator getPathIterator(AffineTransform at) {
		return s.getPathIterator(at);
	}

	@Override
	public final PathIterator getPathIterator(AffineTransform at, double flatness) {
		return s.getPathIterator(at, flatness);
	}
}