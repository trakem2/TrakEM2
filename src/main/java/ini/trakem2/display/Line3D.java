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

import java.awt.Color;
import java.util.List;

import org.jogamp.vecmath.Point3f;

import ini.trakem2.Project;
import ini.trakem2.vector.VectorString3D;

/** By definition, only ZDisplayable objects may implement Line3D. */
public interface Line3D {

	public VectorString3D asVectorString3D();
	@Override
	public String toString();
	public Project getProject();
	public LayerSet getLayerSet();
	public long getId();
	public int length();
	public Color getColor();
	public List<Point3f> generateTriangles(double scale, int parallels, int resample);
}
