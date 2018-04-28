package ini.trakem2.display;

import ini.trakem2.display.graphics.DefaultGraphicsSource;
import ini.trakem2.display.graphics.GraphicsSource;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Collection;

public class DefaultMode implements Mode {

	final private DefaultGraphicsSource gs = new DefaultGraphicsSource();
	final private Display display;

	public DefaultMode(final Display display) {
		this.display = display;
	}

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return true; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	private boolean dragging = false;

	public boolean isDragging() {
		return dragging;
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		final Collection<Displayable> sel = display.getSelection().getSelected();
		dragging = false; //reset
		for (final Displayable d : sel) {
			if (d.contains(x_p, y_p)) {
				dragging = true;
				break;
			}
		}
		final Collection<Displayable> affected = display.getSelection().getAffected();
		if (display.getLayerSet().prepareStep(affected)) {
			display.getLayerSet().addTransformStep(affected);
		}
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		int dx = x_d - x_d_old;
		int dy = y_d - y_d_old;

		if (dragging) execDrag(me, dx, dy);
	}
	private void execDrag(MouseEvent me, int dx, int dy) {
		if (0 == dx && 0 == dy) return;
		// drag all selected and linked
		display.getSelection().translate(dx, dy);
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// Record current state for selected Displayable set, if there was any change:
		final int dx = x_r - x_p;
		final int dy = y_r - y_p;
		if (0 != dx || 0 != dy) {
			display.getLayerSet().addTransformStep(display.getSelection().getAffected()); // all selected and their links: i.e. all that will change
		}

		dragging = false;
	}

	public void undoOneStep() {
		display.getLayerSet().undoOneStep();
		Display.repaint(display.getLayerSet());
	}

	public void redoOneStep() {
		display.getLayerSet().redoOneStep();
		Display.repaint(display.getLayerSet());
	}

	public boolean apply() { return true; } // already done
	public boolean cancel() { return true; } // nothing to cancel

	public Rectangle getRepaintBounds() {
		return display.getSelection().getLinkedBox();
	}

	public void srcRectUpdated(Rectangle srcRect, double magnification) {}
	public void magnificationUpdated(Rectangle srcRect, double magnification) {}
}
