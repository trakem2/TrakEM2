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

import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;

import java.util.Map;

public class RankFilter implements IFilter
{
	protected double radius = 2;
	/** See {@link RankFilters}. */
	protected int type = RankFilters.MEDIAN;
	
	public RankFilter() {}

	/**
	 * @param radius The radius around every pixel to get values from for the specific algorithm {@code type}.
	 * @param type Any of the types in {@link RankFilters} such as {@link RankFilters#MEDIAN}, {@link RankFilters#DESPECKLE}, etc.
	 */
	public RankFilter(double radius, int type) {
		this.radius = radius;
	}
	
	public RankFilter(Map<String,String> params) {
		try {
			this.radius = Double.parseDouble(params.get("radius"));
			this.type = Integer.parseInt(params.get("type"));
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Cannot create RankFilter!", nfe);
		}
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		RankFilters rf = new RankFilters();
		rf.rank(ip, radius, RankFilters.MEDIAN);
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" radius=\"").append(radius)
			.append("\" type=\"").append(type)
			.append("\" />\n").toString();
	}
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == getClass()) {
			final RankFilter r = (RankFilter)o;
			return type == r.type && radius == r.radius;
		}
		return false;
	}
}
