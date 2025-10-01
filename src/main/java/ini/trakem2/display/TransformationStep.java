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
package ini.trakem2.display;

import ini.trakem2.utils.History;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TransformationStep implements History.Step<Displayable> {
	final HashMap<Displayable,AffineTransform> ht;
	TransformationStep(final HashMap<Displayable,AffineTransform> ht) {
		this.ht = ht;
	}
	public List<Displayable> remove(final long id) {
		final List<Displayable> al = new ArrayList<Displayable>();
		for (Iterator<Displayable> it = ht.keySet().iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (d.getId() == id) {
				it.remove();
			}
			al.add(d);
		}
		return al;
	}
	public boolean isEmpty() {
		return ht.isEmpty();
	}
	public boolean isIdentical(final History.Step<?> step) {
		if (step.getClass() != TransformationStep.class) return false;
		final HashMap<Displayable,AffineTransform> m = ((TransformationStep)step).ht;
		// cheap test:
		if (m.size() != this.ht.size()) return false;
		// Check each:
		for (final Map.Entry<Displayable,AffineTransform> e : m.entrySet()) {
			final AffineTransform aff = this.ht.get(e.getKey());
			if (null == aff) return false; // at least one Displayable is missing
			if (!aff.equals(e.getValue())) return false; // at least one Displayable has a different AffineTransform
		}
		return true;
	}
}
