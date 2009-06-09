package ini.trakem2.display.mode;

import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.graphics.DefaultGraphicsSource;
import java.awt.event.MouseEvent;

public class DefaultMode implements Mode {

	final private DefaultGraphicsSource gs = new DefaultGraphicsSource();

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return true; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
	}
}
