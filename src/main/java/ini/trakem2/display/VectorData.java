/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
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
