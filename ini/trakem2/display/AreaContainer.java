package ini.trakem2.display;

import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.Rectangle;
import java.util.List;

public interface AreaContainer {
	public List<Area> getAreas(Layer layer, Rectangle box);
	/** May have the side effect of updating the buckets of the containing container of this Displayable.
	 *  @return Whether this Displayable's bounding box was modified. */
	public boolean calculateBoundingBox(Layer layer);
}
