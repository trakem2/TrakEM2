package ini.trakem2.display;

import java.awt.geom.Area;

/** An interface to represent Displayable objects that are not images. */
public interface VectorData {
	/** Applies the 2D transform @ict (which is expected to operate on the
	 * world coordinates version of the data contained here) only to the
	 * data that falls within the @param roi (in world coords), and then
	 * recomputes the bounding box and affine transform (to a translation
	 * or identity).
	 * Does not consider links.
	 *
	 * @param la Only data at this Layer may be transformed.
	 * @param roi Only data inside this world-coordinates Area may be transformed.
	 * @param ict The transform to apply to the data that is in @param la and within @param roi. */
	public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception;

	public boolean apply(final VectorDataTransform vdt) throws Exception;
}
