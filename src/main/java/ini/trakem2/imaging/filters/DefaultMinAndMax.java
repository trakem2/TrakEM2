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

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.Map;

/**
 * Sets the minimum to zero and the maximum to the maximum supported, or 255 for FloatProcessor
 * and any other unknown {@link ImageProcessor}.
 * 
 * @author Albert Cardona
 *
 */
public class DefaultMinAndMax implements IFilter
{
	public DefaultMinAndMax() {}
	
	public DefaultMinAndMax(Map<String,String> params) {}

	@Override
	public ImageProcessor process(final ImageProcessor ip) {
		ip.setMinAndMax(0, ShortProcessor.class == ip.getClass() ? 65535 : 255);
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		return null != o && o.getClass() == DefaultMinAndMax.class;
	}
}
