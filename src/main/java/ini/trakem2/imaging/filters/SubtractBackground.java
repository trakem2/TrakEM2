/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
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

import ij.plugin.filter.BackgroundSubtracter;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.util.Map;

/** Subtract background with the rolling-ball algorithm. */
public class SubtractBackground implements IFilter
{
	protected int radius = 50;
	
	public SubtractBackground() {}

	public SubtractBackground(final int radius) {
		this.radius = radius;
	}

	public SubtractBackground(Map<String,String> params) {
		String s = params.get("radius");
		if (null != s) {
			try {
				this.radius = Integer.parseInt(s);
				return;
			} catch (NumberFormatException nfe) {}
		}
		throw new IllegalArgumentException("Could not create filter " + getClass().getName() + ": invalid or undefined radius!");
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		BackgroundSubtracter bs = new BackgroundSubtracter();
		if (ip instanceof ColorProcessor) {
			bs.subtractRGBBackround((ColorProcessor)ip, radius);
		} else {
			bs.subtractBackround(ip, radius);
		}
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"")
			.append(getClass().getName())
			.append("\" radius=\"").append(radius).append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == getClass()) {
			return ((SubtractBackground)o).radius == radius;
		}
		return false;
	}
}
