package ini.trakem2.display;

import ini.trakem2.utils.M;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;

final public class VectorDataTransform {
	final public Layer layer;
	final public List<ROITransform> transforms = new ArrayList<ROITransform>();
	public VectorDataTransform(final Layer layer) {
		this.layer = layer;
	}
	public class ROITransform {
		public mpicbg.models.CoordinateTransform ct;
		public Area roi;
		/** A coordinate transform @param ct that applies to the @param roi Area only. */
		public ROITransform(final Area roi, final mpicbg.models.CoordinateTransform ct) {
			this.roi = roi;
			this.ct = ct;
		}
	}
	/** Add a coordinate transform @param ct that applies to the @param roi Area only;
	 *  ASSUMES all rois added do not overlap. */
	public void add(final Area roi, final mpicbg.models.CoordinateTransform ct) {
		transforms.add(new ROITransform(roi, ct));
	}

	/** Returns a copy whose roi and ct are local to the affine transform of @param d. */
	public VectorDataTransform makeLocalTo(final Displayable d) throws Exception {
		final VectorDataTransform local = new VectorDataTransform(this.layer);
		final AffineTransform inverse = d.at.createInverse();
		for (final ROITransform rt : transforms) {
			local.add(rt.roi.createTransformedArea(inverse), M.wrap(d.at, rt.ct, inverse));
		}
		return local;
	}
}
