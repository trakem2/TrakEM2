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
package ini.trakem2.display.inspect;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public class ShapeProxy implements Shape
{
	private Shape s;
	
	/** Create a new proxy for {@code s}. */
	public ShapeProxy(final Shape s) {
		this.s = s;
	}
	
	/** Replace the wrapped {@link Shape} with {@code s}.
	 * 
	 * @param s
	 */
	public final void set(final Shape s) {
		this.s = s;
	}
	
	@Override
	public final Rectangle getBounds() {
		return s.getBounds();
	}

	@Override
	public final Rectangle2D getBounds2D() {
		return s.getBounds2D();
	}

	@Override
	public final boolean contains(double x, double y) {
		return s.contains(x, y);
	}

	@Override
	public final boolean contains(Point2D p) {
		return s.contains(p);
	}

	@Override
	public final boolean intersects(double x, double y, double w, double h) {
		return s.intersects(x, y, w, h);
	}

	@Override
	public final boolean intersects(Rectangle2D r) {
		return s.intersects(r);
	}

	@Override
	public final boolean contains(double x, double y, double w, double h) {
		return s.contains(x, y, w, h);
	}

	@Override
	public final boolean contains(Rectangle2D r) {
		return s.contains(r);
	}

	@Override
	public final PathIterator getPathIterator(AffineTransform at) {
		return s.getPathIterator(at);
	}

	@Override
	public final PathIterator getPathIterator(AffineTransform at, double flatness) {
		return s.getPathIterator(at, flatness);
	}
}
