package ini.trakem2.imaging;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.PointRoi;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;

import levelsets.ij.ImageContainer;
import levelsets.ij.ImageProgressContainer;
import levelsets.ij.StateContainer;
import levelsets.algorithm.LevelSetImplementation;
import levelsets.algorithm.ActiveContours;
import levelsets.algorithm.FastMarching;
import levelsets.algorithm.Coordinate;

public class Segmentation {

	static public class FastMarchingParam {
		public int fm_grey = 50;
		public double fm_dist = 0.5;
		public int max_iterations = 1000;
		public int iter_inc = 100;

		public boolean setup() {
			GenericDialog gd = new GenericDialog("Fast Marching Options");
			gd.addNumericField("Grey value threshold", fm_grey, 0);
			gd.addNumericField("Distance threshold", fm_dist, 2);
			gd.addNumericField("Max iterations", max_iterations, 0);
			gd.addNumericField("Iterations inc", iter_inc, 0);
			gd.showDialog();
			if (gd.wasCanceled()) return false;
			fm_grey = (int) gd.getNextNumber();
			fm_dist = gd.getNextNumber();
			max_iterations = (int)gd.getNextNumber();
			iter_inc = (int)gd.getNextNumber();
			return true;
		}
	}

	static public final FastMarchingParam fmp = new FastMarchingParam();

	static public Thread fastMarching(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w) {
		return Bureaucrat.createAndStart(new Worker.Task("Fast marching") { public void exec() {
			// Capture image as large as the visible field in the Display, defined by srcRect
			ImagePlus imp = (ImagePlus) layer.grab(srcRect, 1.0, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
			PointRoi roi = new PointRoi(x_p_w - srcRect.x, y_p_w - srcRect.y);
			imp.setRoi(roi);
			Utils.log2("imp: " + imp);
			Utils.log2("proi: " + imp.getRoi() + "    " + Utils.toString(new int[]{x_p_w - srcRect.x, y_p_w - srcRect.y}));
			// Setup state
			ImageContainer ic = new ImageContainer(imp);
			StateContainer state = new StateContainer();
			state.setROI(roi, ic.getWidth(), ic.getHeight(), ic.getImageCount(), imp.getCurrentSlice());
			state.setExpansionToInside(false);
			// Run FastMarching
			FastMarching fm = new FastMarching(ic, null, state, true, fmp.fm_grey, fmp.fm_dist);
			int max_iterations = fmp.max_iterations;
			int iter_inc = fmp.iter_inc;
			for (int i=0; i<max_iterations; i++) {
				if (!fm.step(iter_inc)) break;
			}
			// Extract ROI
			setTaskName("Adding area");
			final Area area = new Area();
			Rectangle r = new Rectangle();
			for (final Coordinate c : fm.getStateContainer().getXYZ(false)) {
				r.setRect(c.x, c.y, 1, 1);
				area.add(new Area(r));
			}
			// Bring Area to World coordinates...
			AffineTransform at = new AffineTransform();
			at.translate(srcRect.x, srcRect.y);
			// ... and then to AreaList coordinates:
			try {
				at.preConcatenate(ali.getAffineTransform().createInverse());
			} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
			// Add Area to AreaList at Layer layer:
			ali.addArea(layer.getId(), area.createTransformedArea(at));
			ali.calculateBoundingBox();

			Display.repaint(layer);
		}}, layer.getProject());
	}
}
