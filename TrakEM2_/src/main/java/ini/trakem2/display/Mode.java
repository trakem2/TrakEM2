package ini.trakem2.display;

import ini.trakem2.display.graphics.GraphicsSource;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;

public interface Mode {

	public GraphicsSource getGraphicsSource();
	public boolean canChangeLayer();
	public boolean canZoom();
	public boolean canPan();
	public boolean isDragging();
	public void undoOneStep();
	public void redoOneStep();

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification);
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old);
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r);

	public void srcRectUpdated(Rectangle srcRect, double magnification);
	public void magnificationUpdated(Rectangle srcRect, double magnification);

	public boolean apply();
	public boolean cancel();

	public Rectangle getRepaintBounds();
}
