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
 */
public class Microcube_Maker implements PlugIn {


	public void run(String arg) {
		main(new String[]{arg});
	}

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
			p("Example usage: java -Xmx1000m -classpath .:../../ij.jar:./plugins/TrakEM2_.jar Microcube_Maker /path/to/project.xml 123 456 78.9 987 654 87.0 0.5 true /path/to/stack.tif \nArguments are: <project path> <x1> <y1> <z1> <x2> <y2> <z2> <scale> <re-register layers: true|false> <path_to_stack>");
			return;
		}

		// parse args
		String path = arg[0];
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
		final boolean align = Boolean.parseBoolean(arg[8]);

		ControlWindow.setGUIEnabled(false);

		// check if the project is already open
		Project project = FSLoader.getOpenProject(path);
		if (null == project) {
			project = Project.openFSProject(path);
		}
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

		// TODO: re-register, with proper enlarged sizes and subsequent crop to ensure there are no black areas in the downloaded microcube.
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

			// 1 - Make a new LayerSet
			LayerSet copy_ls = new LayerSet(project, "copy", 0, 0, null, roi2.width, roi2.height);
			List<Layer> original_la = ls.getLayers().subList(ls.indexOf(la1), ls.indexOf(la2)+1);
			for (Layer la : original_la) {
				// 1.2 - add empty copies of the selected layers
				Layer copy_la = new Layer(project, la.getZ(), la.getThickness(), copy_ls);
				copy_ls.addSilently(copy_la);
				for (Displayable d : la.getDisplayables(Patch.class, roi2)) {
					// 1.3 - add directly the patches that intersect the roi
					Patch p = (Patch)d; // so much for generics, can't cast to ArrayList<Patch>
					Patch clone = new Patch(project, d.getId(), d.getTitle(), d.getWidth(), d.getHeight(), p.getType(), false, p.getMin(), p.getMax(), d.getAffineTransformCopy());
					copy_la.addSilently(clone); // tmp clone that has the same project and id
				}
			}
			// 2 - register all tiles freely and optimally
			final Layer[] sub_la = new Layer[copy_ls.size()];
			copy_ls.getLayers().toArray(sub_la);
			final Thread task = Registration.registerTilesSIFT(sub_la, true);
			if (null != task) try { task.join(); } catch (Exception e) { e.printStackTrace(); }

			// 3 - prepare roi for cropping
			roi2.x += padding;
			roi2.y += padding;
			roi2.width = roi.width;
			roi2.height = roi.height;

			// 4 - grab microcube
			ob = copy_ls.grab(0, sub_la.length, roi2, scale, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
		}

		// Store
		if (null != ob && ob instanceof ImagePlus) {
			p("Ob is: " + ob);
			((ImagePlus)ob).show();
			new FileSaver((ImagePlus)ob).saveAsTiff(arg[9]);
			p("Microcube saved successfully to " + arg[9]);
		} else {
			p("Could not save microcube from " + ob);
			return;
		}

		// done!
		project.destroy();
	}

	static private final void p(final String msg) {
		System.out.println(msg);
	}
}
