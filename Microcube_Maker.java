import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.persistence.Loader;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;

import ij.ImagePlus;
import ij.io.FileSaver;

import java.awt.Rectangle;
import java.io.File;

/** Albert Cardona 2007
 *  
 *  Takes as arguments:
 *  - the file path to a TrakEM2 XML project
 *  - the top-left 3D point of the microcube, as x1 y1 z1
 *  - the bottom-right point of the microcube, as x2 y2 z2
 *  - the scale
 *  - boolean to re-register stack
 *  - file path to tif stack to be generated (will be overwritten if it exists)
 *
 *  (in total, 10 arguments)
 *
 *  Layers are taken from the Z coordinates of the points.
 *  If a layer is not found for the exact given Z, it will get the nearest layer to that Z.
 *
 *  Assumptions:
 *    - That the microcube stack will fit in RAM memory.
 *    - That the desired outcome is 8-bit gray
 *
 *  Example of usage:
 *
 *  $ java -Xmx1000m -classpath .:../../ij.jar:./plugins/TrakEM2_.jar Microcube_Maker /path/to/project.xml 123 456 78.9 987 654 87.0 0.5 true /path/to/stack.tif
 *
 */
public class Microcube_Maker {

	static public void main(String[] arg) {
		try {
			makeMicrocube(arg);
		} catch (Exception e) {
			p("ERROR: could not create microcube stack file.");
			e.printStackTrace();
		}

		//System.exit(0); // some windowing component remains open, keeping java alive.
	}

	static private final void makeMicrocube(final String[] arg) {
		if (arg.length < 10) {
			p("Usage: java -Xmx1000m -classpath .:../../ij.jar:./plugins/TrakEM2_.jar Microcube_Maker /path/to/project.xml 123 456 78.9 987 654 87.0 0.5 true /path/to/stack.tif \nArguments are: <project path> <x1> <y1> <z1> <x2> <y2> <z2> <scale> <re-register layers> <path_to_stack>");
			return;
		}

		// parse args
		String path = arg[0];
		if (Utils.isURL(path)) {
			// ok, we'll see
		} else if (!new File(path).exists()) {
			p("Project XML file path is invalid or not found: " + path);
			return;
		}
		double x1, y1, z1,
		       x2, y2, z2,
		       scale;
		try {
			x1 = Double.parseDouble(arg[1]);
			y1 = Double.parseDouble(arg[2]);
			z1 = Double.parseDouble(arg[3]);
			x2 = Double.parseDouble(arg[4]);
			y2 = Double.parseDouble(arg[5]);
			z2 = Double.parseDouble(arg[6]);
			scale = Double.parseDouble(arg[7]);

		} catch (NumberFormatException nfe) {
			p("Improper numerical argument for a coordinate.");
			nfe.printStackTrace();
			return;
		}
		final boolean reregister = Boolean.parseBoolean(arg[8]);

		// open project
		ControlWindow.setGUIEnabled(false);
		final Project project = Project.openFSProject(path);
		if (null == project) {
			p("Could not open TrakEM2 project at path " + path);
			return;
		}
		// define ROI
		int x = (int)(x1 < x2 ? x1 : x2);
		int y = (int)(y1 < y2 ? y1 : y2);
		int w = (int)Math.abs(x1 - x2);
		int h = (int)Math.abs(y1 - y2);
		final Rectangle roi = new Rectangle(x, y, w, h);
		// define first and last layer
		LayerSet ls = project.getRootLayerSet();
		Layer la1 = ls.getNearestLayer(z1);
		Layer la2 = ls.getNearestLayer(z2);
		if (z1 > z2) {
			Layer tmp = la1;
			la1 = la2;
			la2 = tmp;
		}

		// grab microcube
		Object ob = ls.grab(ls.indexOf(la1), ls.indexOf(la2), roi, scale, Patch.class, Layer.IMAGEPLUS, ImagePlus.GRAY8);
		if (null != ob && ob instanceof ImagePlus) {
			p("Ob is: " + ob);
			((ImagePlus)ob).show();
			new FileSaver((ImagePlus)ob).saveAsTiff(arg[9]);
			p("Microcube saved successfully to " + arg[9]);
		} else {
			p("Could not save microcube from " + ob);
			return;
		}
		
		// TODO: re-register, with proper enlarged sizes and subsequent crop to ensure there are no black areas in the downloaded microcube.
		// One way to do it:
		//  - first cut stack
		//  - register
		//  - calculate enlarged box, and crop that from the original for each layer.
		// Another way to do it:
		//  - cut a bit more than will be needed
		//  - register
		//  - calculate enlarged box, crop that from the original - because black areas may still creep in

		// done!
		project.destroy();
	}

	static private final void p(final String msg) {
		System.out.println(msg);
	}
}
