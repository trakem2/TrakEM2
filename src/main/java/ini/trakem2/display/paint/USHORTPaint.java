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
package ini.trakem2.display.paint;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;

public final class USHORTPaint implements Paint
{
	private final short[] value;
	private final ComponentColorModel ccm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), new int[]{16}, false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);

	public USHORTPaint(final short value) {
		this.value = new short[]{value};
	}

	/** Will alter the value for this instance and for all {@link USHORTPaintContext} instances
	 * returned from {@link USHORTPaint#createContext(ColorModel, Rectangle, Rectangle2D, AffineTransform, RenderingHints)}. */
	public void setValue(final short value) {
		this.value[0] = value;
	}

	@Override
	public int getTransparency() {
		return Transparency.OPAQUE;
	}

	/** Return a new {@link USHORTPaintContext} that shares the value and ccm fields with this instance. */
	@Override
	public PaintContext createContext(
			ColorModel cm, Rectangle deviceBounds,
			Rectangle2D userBounds,
			AffineTransform xform,
			RenderingHints hints) {
		return new USHORTPaintContext(this.ccm, this.value);
	}

	public ComponentColorModel getComponentColorModel() {
		return this.ccm;
	}
}
