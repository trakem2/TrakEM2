package ini.trakem2.display;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

public interface Paintable {

	public void prePaint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers);
	public void paint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers);
}
