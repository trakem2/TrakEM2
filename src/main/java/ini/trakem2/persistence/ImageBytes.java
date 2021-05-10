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
package ini.trakem2.persistence;

/** Represent an image with byte arrays:
 * 1 channel: grey
 * 2 channels: grey + alpha
 * 3 channels: RGB
 * 4 channels: RGBA
 * 
 * @author Albert Cardona
 *
 */
public final class ImageBytes
{
	public final byte[][] c;
	public final int width, height;
	
	public ImageBytes(final byte[][] c, final int width, final int height) {
		this.c = c;
		this.width = width;
		this.height = height;
	}
}
