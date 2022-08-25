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

import java.awt.Image;

/**
 * <p>A MipMap is a container for an {@link Image} and its corresponding
 * inverse <em>x</em>/<em>y</em> scale relative to an original {@link Image}
 * it was generated from.</p>
 * 
 * <p>In a scale pyramid as used for mipmap generation, the inverse scale for
 * both <em>x</em> and <em>y</em> is usually<p>
 * 
 * <p><em>s</em> = 2<sup>level</sup></p>
 * 
 * <p>Note that the exact scale cannot be inferred from the sizes of the mipmap
 * {@link Image} and its origin because the number of pixels is an integer in
 * both {@link Image Images}.
 *
 * @author Stephan Saalfeld saalfeld@mpi-cbg.de
 */
public final class MipMapImage
{
	final public Image image;
	final public double scaleX, scaleY;
	
	public MipMapImage( final Image image, final double scaleX, final double scaleY )
	{
		this.image = image;
		this.scaleX = scaleX;
		this.scaleY = scaleY;
	}
}
