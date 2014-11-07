package ini.trakem2.display;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.util.HashMap;

public class Overlay {

	private HashMap<Shape,OverlayShape> oshapes = null;

	/** Add a new Shape to be painted above all other elements in the canvas.
	 *  The color and stroke may be null, defaulting to Color.yellow and a line of width 1. */
	synchronized public void add(Shape shape, Color color, Stroke stroke) {
		add(shape, color, stroke, false, false, 1.0f);
	}
	synchronized public void add(Shape shape, Color color, Stroke stroke, boolean as_XOR_color) {
		add(shape, color, stroke, false, as_XOR_color, 1.0f);
	}
	synchronized public void add(Shape shape, Color color, Stroke stroke, boolean fill, boolean as_XOR_color, float alpha) {
		if (null == shape) return;
		if (null == oshapes) oshapes = new HashMap<Shape,OverlayShape>();
		oshapes.put(shape, new OverlayShape(shape, color, stroke, fill, as_XOR_color, alpha));
	}

	synchronized public void remove(Shape shape) {
		if (null == oshapes || null == shape) return;
		oshapes.remove(shape);
	}

	synchronized public void clear() { oshapes = null; }

	public void paint(Graphics2D g, Rectangle srcRect, double mag) {
		if (null == oshapes) return;
		AffineTransform original = g.getTransform();
		// Clear transform, so stroke is magnification-invariant
		g.setTransform(new AffineTransform());
		// Compute pan/zoom transform
		AffineTransform sm = new AffineTransform();
		sm.scale(mag, mag);
		sm.translate(-srcRect.x, -srcRect.y);

		// Ensure stroke of line thickness 1
		g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

		for (OverlayShape o : oshapes.values()) {
			o.paint(g, sm);
		}

		// Restore
		g.setTransform(original);
	}

	private class OverlayShape {
		Shape shape;
		Color color;
		Stroke stroke;
		boolean fill;
		boolean as_XOR_color;
		float alpha;
		OverlayShape(Shape shape, Color color, Stroke stroke, boolean fill, boolean as_XOR_color, float alpha) {
			this.shape = shape;
			this.color = color;
			this.stroke = stroke;
			this.fill = fill;
			this.as_XOR_color = as_XOR_color;
			this.alpha = alpha;
		}
		void paint(Graphics2D g, AffineTransform sm) {
			Composite c = g.getComposite();
			if (as_XOR_color) {
				g.setXORMode(null == color ? Color.yellow : color);
			} else {
				g.setColor(null == color ? Color.yellow : color);
				if (alpha < 1.0f && alpha > 0.0f) {
					g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
				}
			}
			Stroke s = null;
			if (null != stroke) {
				s = g.getStroke();
				g.setStroke(stroke);
			}
			if (fill) g.fill(sm.createTransformedShape(shape));
			else g.draw(sm.createTransformedShape(shape));
			if (null != stroke) g.setStroke(s);
			if (null != c) g.setComposite(c);
		}
	}
}
