package ini.trakem2.display.mode;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.graphics.ManualNonLinearTransformSource;
import java.util.Collection;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;

public class NonLinearTransformMode implements Mode {

	private Display display;
	private ManualNonLinearTransformSource gs;

	public NonLinearTransformMode(final Display display, final Collection<Displayable> col) {
		this.display = display;
		this.gs = new ManualNonLinearTransformSource(col);
	}

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return false; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
	}
}

