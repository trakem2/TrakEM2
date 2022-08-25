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
package ini.trakem2.imaging.filters;

import ij.measure.Measurements;
import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.util.Map;

/** Uses the @{link {@link ContrastEnhancer#stretchHistogram(ImageProcessor, double, ImageStatistics)} function. */
public class EnhanceContrast implements IFilter
{
	/** Percent of saturated pixels to leave outside the min, max range. */
	protected double s = 0.4;
	
	public EnhanceContrast() {}
	
	public EnhanceContrast(Map<String,String> params) {}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		// Will not alter ip, no need to duplicate
		new ContrastEnhancer().stretchHistogram(ip, s, ImageStatistics.getStatistics(ip, Measurements.MIN_MAX, null));
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" s=\"").append(s)
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == EnhanceContrast.class) {
			return ((EnhanceContrast)o).s == this.s;
		}
		return false;
	}
}
