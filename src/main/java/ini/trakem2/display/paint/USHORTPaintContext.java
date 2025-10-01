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
package ini.trakem2.display.paint;

import java.awt.PaintContext;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

final class USHORTPaintContext implements PaintContext
{
	private final ComponentColorModel ccm;
	
	private WritableRaster raster;
	private final short[] value;
	
	USHORTPaintContext(final ComponentColorModel ccm, final short[] value) {
		this.value = value;
		this.ccm = ccm;
	}

	@Override
	public final Raster getRaster(final int x, final int y, final int w, final int h) {
		if (null == raster || raster.getWidth() != w || raster.getHeight() != h) {
			raster = ccm.createCompatibleWritableRaster(w, h);
		}
		final int lenY = y+h;
		final int lenX = x+w;
		for (int j=y; j<lenY; ++j) {
			for (int i=x; i<lenX; ++i) {
				raster.setDataElements(i-x, j-y, value);
			}
		}
		return raster;
	}

	@Override
	public final ColorModel getColorModel() {
		return ccm;
	}

	@Override
	public final void dispose() {
		raster = null;
	}
}
