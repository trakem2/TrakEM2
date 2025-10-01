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
package ini.trakem2.display.graphics;

import ini.trakem2.display.Display;
import ini.trakem2.display.Paintable;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

public interface GraphicsSource {

	/** Takes as list of Paintable and returns the same or another list with perhaps a different number of Paintable objects, or some replaced by new Paintable instances. */
	public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds);

	/** The canvas will call this method after painting everything else. */
	public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification);
}
