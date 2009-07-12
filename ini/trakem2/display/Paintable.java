package ini.trakem2.display;

import java.awt.Graphics2D;

public interface Paintable {

	public void prePaint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer);
	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer);
}
