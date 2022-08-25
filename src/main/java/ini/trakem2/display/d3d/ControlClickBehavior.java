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
package ini.trakem2.display.d3d;

import java.awt.event.MouseEvent;

import org.scijava.vecmath.Point3d;

import ij.measure.Calibration;
import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.behaviors.InteractiveBehavior;
import ij3d.behaviors.Picker;
import ini.trakem2.display.Coordinate;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.utils.Utils;

/** A class to provide the behavior on control-clicking on
content in the 3D viewer.  This will attempt to center
the front TrakEM2 Display on the clicked point */
public class ControlClickBehavior extends InteractiveBehavior {

	protected Image3DUniverse universe;
	protected LayerSet ls;

	public ControlClickBehavior(final Image3DUniverse univ, final LayerSet ls) {
		super(univ);
		this.universe = univ;
		this.ls = ls;
	}

	@Override
	public void doProcess(final MouseEvent e) {
		if(!e.isControlDown() ||
				e.getID() != MouseEvent.MOUSE_PRESSED) {
			super.doProcess(e);
			return;
		}
		final Picker picker = universe.getPicker();
		final Content content = picker.getPickedContent(e.getX(),e.getY());
		if(content==null)
			return;
		final Point3d p = picker.getPickPointGeometry(content,e);
		if(p==null) {
			Utils.log("No point was found on content "+content);
			return;
		}
		final Display display = Display.getFront(ls.getProject());
		if(display==null) {
			// If there's no Display, just return...
			return;
		}
		if (display.getLayerSet() != ls) {
			Utils.log("The LayerSet instances do not match");
			return;
		}
		if(ls==null) {
			Utils.log("No LayerSet was found for the Display");
			return;
		}
		final Calibration cal = ls.getCalibration();
		if(cal==null) {
			Utils.log("No calibration information was found for the LayerSet");
			return;
		}
		final double scaledZ = p.z/cal.pixelWidth;
		final Layer l = ls.getNearestLayer(scaledZ);
		if(l==null) {
			Utils.log("No layer was found nearest to "+scaledZ);
			return;
		}
		final Coordinate<?> coordinate = new Coordinate<Object>(p.x/cal.pixelWidth,p.y/cal.pixelHeight,l,null);
		display.center(coordinate);
	}
}
