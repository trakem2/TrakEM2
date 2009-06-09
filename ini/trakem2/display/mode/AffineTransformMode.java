package ini.trakem2.display.mode;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.Selection;
import ini.trakem2.display.graphics.GraphicsSource;
import java.util.Collection;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;

public class AffineTransformMode implements Mode {

	private final ATGS atgs = new ATGS();

	private class ATGS implements GraphicsSource {
		public Collection<? extends Paintable> asPaintable(final Collection<? extends Paintable> ds) {
			return ds;
		}
		public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			display.getSelection().paint(g, srcRect, magnification);
		}
	}

	public GraphicsSource getGraphicsSource() {
		return atgs;
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
