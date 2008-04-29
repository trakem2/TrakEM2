import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Displayable;
import ini.trakem2.utils.Utils;
import ini.trakem2.imaging.Registration;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.plugin.PlugIn;
import ij.Macro;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 *    - That the project is on a FSLoader
 *    - That the project may already be open
 *
 *  Example of usage:
 *
 *  $ java -Xmx1000m -classpath .:../../ij.jar:./plugins/TrakEM2_.jar Microcube_Maker /path/to/project.xml 123 456 78.9 987 654 87.0 0.5 true /path/to/stack.tif
 *
 *  Example of usage as a plugin:
 *
 *  java -Xmx512m -classpath /home/albert/Applications/ImageJ/ij.jar:/home/albert/Applications/ImageJ/plugins/TrakEM2_.jar -Dplugins.dir=/home/albert/Applications/ImageJ ij.ImageJ -eval "run(\"Microcube Maker\", \"/home/albert/Desktop/ministack4-mm.xml 520 300 0 1020 800 150 1.0 true /home/albert/temp/test-mc/result.tif\");"
 *
 */
public class Microcube_Maker implements PlugIn {

	public void run(String arg) {
		if (null == arg || 0 == arg.trim().length()) {
			arg = Macro.getOptions();
		}
		p("Called as plugin with args:\n\t" + arg);
		main(arg.split(" "));
	}

	static public void main(String[] arg) {
		try {
			makeMicrocube(arg);
		} catch (Exception e) {
			p("ERROR: could not create microcube stack file.");
			e.printStackTrace();
		}
	}

	static private final void makeMicrocube(final String[] arg) {
		if (arg.length < 10) {
			p("Example usage: java -Xmx1000m -classpath .:../../ij.jar:./plugins/TrakEM2_.jar Microcube_Maker /path/to/project.xml 123 456 78.9 987 654 87.0 0.5 true /path/to/stack.tif \nArguments are: <project path> <x1> <y1> <z1> <x2> <y2> <z2> <scale> <re-register layers: true|false> <path_to_stack>");
			return;
		}

		Project project = null;
		boolean was_open = false;

		try {
			// parse args
			String path = arg[0].trim();
			if (FSLoader.isURL(path)) {
				// ok, we'll see if it an be opened
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
			final boolean align = Boolean.parseBoolean(arg[8].trim().toLowerCase());

			ControlWindow.setGUIEnabled(false);

			// check if the project is already open
			project = FSLoader.getOpenProject(path);
			if (null == project) {
				project = Project.openFSProject(path);
			} else {
				was_open = true;
			}
			if (null == project) {
				p("Could not open TrakEM2 project at path " + path);
				return;
			}
			// define ROI
			int x = (int)(x1 < x2 ? x1 : x2);
			int y = (int)(y1 < y2 ? y1 : y2);
			int w = (int)Math.abs(x1 < x2 ? x2 - x1 : x1 - x2);
			int h = (int)Math.abs(y1 < y2 ? y2 - y1 : y1 - y2);
			final Rectangle roi = new Rectangle(x, y, w, h);
			// define first and last layer
			LayerSet ls = project.getRootLayerSet();
			Layer la1 = ls.getNearestLayer(z1); // WARNING: Calibration
			Layer la2 = ls.getNearestLayer(z2);
			if (z1 > z2) {
				Layer tmp = la1;
				la1 = la2;
				la2 = tmp;
			}

			// Re-register, with proper enlarged sizes and subsequent crop to ensure there are no black areas in the downloaded microcube.
			// One way to do it: BEST way, since the focus remains within the selected stack. Potential problem: if the slices don't match at all due to excessive deformations. But it's hard.
			//  - first cut stack
			//  - register
			//  - calculate enlarged box, and crop that from the original for each layer, but putting in the proper transforms.
			// Another way to do it:
			//  - cut a bit more than will be needed
			//  - register
			//  - calculate enlarged box, crop that from the original - because black areas may still creep in
			//  Yet another way:
			//  - create a subproject from a double-side size area, centered on desired area
			//  - run the layer registration that will shift tile positions as well
			//  - bring in any other patches not originally included, to fill up any black areas
			//  - grab stack.
			//
			//  Reality: need same ids for images to avoid duplicating cache and to reuse the same feature serialized files. So, hacking time:

			Object ob;

			if (!align) {
				ob = ls.grab(ls.indexOf(la1), ls.indexOf(la2), roi, scale, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
			} else {
				// prepare enlarged roi: pad outwards by one tile width
				final int padding = (int)la1.getDisplayables(Patch.class).get(0).getWidth();
				final Rectangle roi2 = (Rectangle)roi.clone();
				roi2.x -= padding;
				roi2.y -= padding;
				roi2.width += padding * 2;
				roi2.height += padding * 2;
				p("roi: " + roi);
				p("padding: " + padding);
				p("roi2: " + roi2);

				// 1 - Make a subproject
				Project sub = ls.getProject().createSubproject(roi, la1, la2);
				LayerSet sub_ls = sub.getRootLayerSet();
				// 2 - register all tiles freely and optimally
				final Layer[] sub_la = new Layer[sub_ls.size()];
				sub_ls.getLayers().toArray(sub_la);
				final Thread task = Registration.registerTilesSIFT(sub_la, true);
				if (null != task) try { task.join(); } catch (Exception e) { e.printStackTrace(); }
				// 3 - prepare roi for cropping
				roi.x -= roi2.x;
				roi.y -= roi2.y;
				// 4 - grab microcube
				ob = sub_ls.grab(0, sub_la.length-1, roi, scale, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
				sub.destroy();
			}

			// Store
			if (null != ob && ob instanceof ImagePlus) {
				p("Ob is: " + ob);
				ImagePlus imp = ((ImagePlus)ob);
				imp.show();
				if (imp.getStackSize() > 1) {
					new FileSaver(imp).saveAsTiffStack(arg[9]);
				} else {
					new FileSaver(imp).saveAsTiff(arg[9]);
				}
				p("Microcube saved successfully to " + arg[9]);
			} else {
				p("Could not save microcube from " + ob);
				return;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		// done!
		if (!was_open) {
			project.destroy();
			System.exit(0);
		}
	}

	static private final void p(final String msg) {
		System.out.println(msg);
	}
}
