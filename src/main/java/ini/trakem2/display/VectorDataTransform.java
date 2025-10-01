/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2025 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
