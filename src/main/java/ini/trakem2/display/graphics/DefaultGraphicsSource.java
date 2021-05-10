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
package ini.trakem2.display.graphics;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Paintable;
import ini.trakem2.utils.ProjectToolbar;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

/** Handles default mode, i.e. just plain images without any transformation handles of any kind. */
public class DefaultGraphicsSource implements GraphicsSource {

	/** Returns the list given as argument without any modification. */
	public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
		return ds;
	}

	/** Paints bounding boxes of selected objects as pink and active object as white. */
	public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
		if (ProjectToolbar.getToolId() >= ProjectToolbar.PENCIL) { // PENCIL == SPARE2
			return;
		}
		g.setColor(Color.pink);
		Displayable active = display.getActive();
		final Rectangle bbox = new Rectangle();
		for (final Displayable d : display.getSelection().getSelected()) {
			d.getBoundingBox(bbox);
			if (d == active) {
				g.setColor(Color.white);
				//g.drawPolygon(d.getPerimeter());
				g.drawRect(bbox.x, bbox.y, bbox.width, bbox.height);
				g.setColor(Color.pink);
			} else {
				//g.drawPolygon(d.getPerimeter());
				g.drawRect(bbox.x, bbox.y, bbox.width, bbox.height);
			}
		}
	}
}
