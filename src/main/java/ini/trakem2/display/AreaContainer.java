/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
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

import ij.measure.ResultsTable;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.List;

public interface AreaContainer {
	public List<Area> getAreas(Layer layer, Rectangle box);
	/** May have the side effect of updating the buckets of the containing container of this Displayable.
	 *  @return Whether this Displayable's bounding box was modified. */
	public boolean calculateBoundingBox(Layer layer);
	
	public ResultsTable measureAreas(ResultsTable rt);
}
