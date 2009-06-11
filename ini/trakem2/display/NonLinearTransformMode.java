package ini.trakem2.display;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.graphics.ManualNonLinearTransformSource;
import ini.trakem2.utils.Utils;
import java.util.ArrayList;
import java.util.Collection;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.awt.geom.AffineTransform;

public class NonLinearTransformMode implements Mode {

	private Display display;
	private ManualNonLinearTransformSource gs;

	public NonLinearTransformMode(final Display display, final Collection<Displayable> selected) {
		this.display = display;
		this.gs = new ManualNonLinearTransformSource(this, selected);
	}

	public NonLinearTransformMode(final Display display) {
		this(display, display.getSelection().getSelected());
	}

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return false; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	private Collection<Point> points = new ArrayList<Point>();

	private Point p_clicked = null;

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);

		// find if clicked on a point
		p_clicked = null;
		for (Point p : points) {
			if (Math.sqrt(Math.pow(p.x - x_p, 2) + Math.pow(p.y - y_p, 2)) <= 8) {
				p_clicked = p;
				break;
			}
		}

		if (me.isShiftDown()) {
			if (null == p_clicked) {
				// add one
				p_clicked = new Point(x_p, y_p);
				points.add(p_clicked);
			} else if (Utils.isControlDown(me)) {
				// remove it
				points.remove(p_clicked);
				p_clicked = null;
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);
	}

	public void srcRectUpdated(Rectangle srcRect, double magnification) {
		// TODO update all
	}
	public void magnificationUpdated(Rectangle srcRect, double magnification) {
		// TODO update all
	}

	public void redoOneStep() {}

	public void undoOneStep() {}

	public boolean isDragging() {
		return true; // TODO
	}

	public boolean apply() { return true; }
	public boolean cancel() { return true; }

	public Rectangle getRepaintBounds() { return display.getSelection().getLinkedBox(); } // TODO

	public void paint(Graphics2D g, Rectangle srcRect, double magnification) {
		AffineTransform original = g.getTransform();
		g.setTransform(new AffineTransform());
		for (Point p : points) {
			Utils.drawPoint(g, srcRect.x + (int)(p.x/magnification), srcRect.y + (int)(p.y/magnification));
		}
		g.setTransform(original);
	}
}

