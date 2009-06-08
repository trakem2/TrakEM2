package ini.trakem2.imaging;

import ij.IJ;
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
import java.awt.Polygon;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.Checkbox;
import java.awt.Component;

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
		public boolean apply_bandpass_filter = true;
		public int low_frequency_threshold = 1000;
		public int high_frequency_threshold = 5;
		public boolean autoscale_after_filtering = true;
		public boolean saturate_when_autoscaling = true;
		public boolean apply_grey_value_erosion = true;

		public boolean setup() {
			GenericDialog gd = new GenericDialog("Fast Marching Options");
			gd.addMessage("Fast Marching:");
			gd.addNumericField("Grey value threshold", fm_grey, 0);
			gd.addNumericField("Distance threshold", fm_dist, 2);
			gd.addNumericField("Max iterations", max_iterations, 0);
			gd.addNumericField("Iterations inc", iter_inc, 0);
			gd.addMessage("Bandpass filter:");
			gd.addCheckbox("Enable bandpass filter", apply_bandpass_filter);
	                gd.addNumericField("Filter_Large Structures Down to", low_frequency_threshold, 0, 4, "pixels");
	                gd.addNumericField("Filter_Small Structures Up to", high_frequency_threshold, 0, 4, "pixels");
	                gd.addCheckbox("Autoscale After Filtering", autoscale_after_filtering);
	                gd.addCheckbox("Saturate Image when Autoscaling", saturate_when_autoscaling);
			final Component[] c = {
				(Component)gd.getNumericFields().get(gd.getNumericFields().size()-2),
				(Component)gd.getNumericFields().get(gd.getNumericFields().size()-1),
				(Component)gd.getCheckboxes().get(gd.getCheckboxes().size()-2),
				(Component)gd.getCheckboxes().get(gd.getCheckboxes().size()-1)
			};
			Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size()-3), c, null);
			gd.addMessage("Grey value erosion filter:");
			gd.addCheckbox("Enable grey value erosion filter", apply_grey_value_erosion);
			gd.showDialog();
			if (gd.wasCanceled()) return false;

			// FastMarching:
			fm_grey = (int) gd.getNextNumber();
			fm_dist = gd.getNextNumber();
			max_iterations = (int)gd.getNextNumber();
			iter_inc = (int)gd.getNextNumber();

			// Bandpass filter:
			apply_bandpass_filter = gd.getNextBoolean();
			low_frequency_threshold = (int) gd.getNextNumber();
			high_frequency_threshold = (int) gd.getNextNumber();
			autoscale_after_filtering = gd.getNextBoolean();
			saturate_when_autoscaling = gd.getNextBoolean();

			// Grey value erosion:
			apply_grey_value_erosion = gd.getNextBoolean();
			return true;
		}
	}

	static public final FastMarchingParam fmp = new FastMarchingParam();

	static public Thread fastMarching(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w) {
		return Bureaucrat.createAndStart(new Worker.Task("Fast marching") { public void exec() {
			// Capture image as large as the visible field in the Display, defined by srcRect
			ImagePlus imp = (ImagePlus) layer.grab(srcRect, 1.0, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
			// Bandpass filter
			if (fmp.apply_bandpass_filter) {
				IJ.run(imp, "Bandpass Filter...", "filter_large=" + fmp.low_frequency_threshold  + " filter_small=" + fmp.high_frequency_threshold + " suppress=None tolerance=5" + (fmp.autoscale_after_filtering ? " autoscale" : "") + (fmp.saturate_when_autoscaling ? " saturate" : ""));
			}
			// Setup seed point
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
			FastMarching fm = new FastMarching(ic, null, state, true, fmp.fm_grey, fmp.fm_dist, fmp.apply_grey_value_erosion);
			int max_iterations = fmp.max_iterations;
			int iter_inc = fmp.iter_inc;
			for (int i=0; i<max_iterations; i++) {
				if (!fm.step(iter_inc)) break;
			}
			// Extract ROI
			setTaskName("Adding area");
			final Area area = new Area();
			Rectangle r = new Rectangle(0, 0, 1, 1);
			// Takes FOREVER
			for (final Coordinate c : fm.getStateContainer().getXYZ(false)) {
				r.x = c.x;
				r.y = c.y;
				area.add(new Area(r));
			}
			/*
			// Trying from the image mask: JUST AS SLOW
			final byte[] b = (byte[]) fm.getStateContainer().getIPMask()[0].getPixels();
			final int w = imp.getWidth();
			for (int i=0; i<b.length; i++) {
				if (0 == b[i]) {
					r.x = i%w;
					r.y = i/w;
					area.add(new Area(r));
				}
			}
			*/
			/* // DOESN'T FILL?
			// Trying to just get the contour, and then filling holes
			for (final Coordinate c : fm.getStateContainer().getXYZ(true)) {
				r.x = c.x;
				r.y = c.y;
				area.add(new Area(r));
			}
			Polygon pol = new Polygon();
			final float[] coords = new float[6];
			for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
				switch (pit.currentSegment(coords)) {
					case PathIterator.SEG_MOVETO:
					case PathIterator.SEG_LINETO:
						pol.addPoint((int)coords[0], (int)coords[1]);
						break;
					case PathIterator.SEG_CLOSE:
						area.add(new Area(pol));
						// prepare next:
						pol = new Polygon();
						break;
					default:
						Utils.log2("WARNING: unhandled seg type.");
						break;
				}
				pit.next();
				if (pit.isDone()) {
					break;
				}
			}
			*/

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
