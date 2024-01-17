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
package ini.trakem2.display.graphics;

import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.Patch;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

public class TestGraphicsSource implements GraphicsSource {

	/** Replaces all Patch instances by a smiley face. */
	public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
		final ArrayList<Paintable> a = new ArrayList<Paintable>();
		for (final Paintable p : ds) {
			if (p instanceof Patch) {
				final Paintable pa = new Paintable() {
					public void paint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers) {
						Patch patch = (Patch)p;
						Rectangle r = patch.getBoundingBox();
						g.setColor(Color.magenta);
						g.fillRect(r.x, r.y, r.width, r.height);
						g.setColor(Color.green);
						g.fillOval(r.x + r.width/3, r.y + r.height/3, r.width/10, r.width/10);
						g.fillOval(r.x + 2 * (r.width/3), r.y + r.height/3, r.width/10, r.width/10);
						g.fillOval(r.x + r.width/3, r.y + 2*(r.height/3), r.width/3, r.height/6);
					}
					public void prePaint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers) {
						this.paint(g, srcRect, magnification, active, channels, active_layer, layers);
					}
				};
				a.add(pa);
			} else {
				a.add(p);
			}
		}
		return a;
	}

	/** Does nothing. */
	public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {}
}
