package ini.trakem2.display;

import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.Rectangle;
import java.util.List;

public interface AreaContainer {
	public List<Area> getAreas(Layer layer, Rectangle box);
	public boolean calculateBoundingBox(Layer layer);
}
