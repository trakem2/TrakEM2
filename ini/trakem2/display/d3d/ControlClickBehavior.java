package ini.trakem2.display.d3d;

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

import java.awt.event.MouseEvent;

import javax.vecmath.Point3d;

/** A class to provide the behavior on control-clicking on
content in the 3D viewer.  This will attempt to center
the front TrakEM2 Display on the clicked point */
public class ControlClickBehavior extends InteractiveBehavior {

	protected Image3DUniverse universe;
	protected LayerSet ls;

	public ControlClickBehavior(Image3DUniverse univ, LayerSet ls) {
		super(univ);
		this.universe = univ;
		this.ls = ls;
	}

	public void doProcess(MouseEvent e) {
		if(!e.isControlDown() ||
				e.getID() != MouseEvent.MOUSE_PRESSED) {
			super.doProcess(e);
			return;
		}
		Picker picker = universe.getPicker();
		Content content = picker.getPickedContent(e.getX(),e.getY());
		if(content==null)
			return;
		Point3d p = picker.getPickPointGeometry(content,e);
		if(p==null) {
			Utils.log("No point was found on content "+content);
			return;
		}
		Display display = Display.getFront(ls.getProject());
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
		Calibration cal = ls.getCalibration();
		if(cal==null) {
			Utils.log("No calibration information was found for the LayerSet");
			return;
		}
		double scaledZ = p.z/cal.pixelWidth;
		Layer l = ls.getNearestLayer(scaledZ);
		if(l==null) {
			Utils.log("No layer was found nearest to "+scaledZ);
			return;
		}
		Coordinate<?> coordinate = new Coordinate<Object>(p.x/cal.pixelWidth,p.y/cal.pixelHeight,l,null);
		display.center(coordinate);
	}
}
