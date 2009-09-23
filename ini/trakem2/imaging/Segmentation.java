package ini.trakem2.imaging;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
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
import java.awt.event.MouseEvent;
import java.util.ArrayList;

import levelsets.ij.ImageContainer;
import levelsets.ij.ImageProgressContainer;
import levelsets.ij.StateContainer;
import levelsets.algorithm.LevelSetImplementation;
import levelsets.algorithm.ActiveContours;
import levelsets.algorithm.FastMarching;
import levelsets.algorithm.Coordinate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import plugin.Lasso;


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
		/** In pixels, of the the underlying image to copy around the mouse. May be enlarged by shift+scrollwheel with PENCIL tool on a selected AreaList. */
		public int width = 100,
		           height = 100;

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

		public void resizeArea(final int sign, final double magnification) {
			double inc = (int)( (10 * sign) / magnification);
			this.width += inc;
			this.height += inc;
			Utils.log2("fmp w,h: " + width + ", " + height);
		}
	}

	static public final FastMarchingParam fmp = new FastMarchingParam();

	static public Thread fastMarching(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w, final Runnable post_task) {
		Bureaucrat burro = Bureaucrat.create(new Worker.Task("Fast marching") { public void exec() {
			// Capture image as large as the fmp width,height centered on x_p_w,y_p_w
			Rectangle box = new Rectangle(x_p_w - Segmentation.fmp.width/2, y_p_w - Segmentation.fmp.height/2, Segmentation.fmp.width, Segmentation.fmp.height);
			Utils.log2("fmp box is " + box);
			ImagePlus imp = (ImagePlus) layer.grab(box, 1.0, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
			// Bandpass filter
			if (fmp.apply_bandpass_filter) {
				IJ.run(imp, "Bandpass Filter...", "filter_large=" + fmp.low_frequency_threshold  + " filter_small=" + fmp.high_frequency_threshold + " suppress=None tolerance=5" + (fmp.autoscale_after_filtering ? " autoscale" : "") + (fmp.saturate_when_autoscaling ? " saturate" : ""));
			}
			// Setup seed point
			PointRoi roi = new PointRoi(box.width/2, box.height/2);
			imp.setRoi(roi);
			Utils.log2("imp: " + imp);
			Utils.log2("proi: " + imp.getRoi() + "    " + Utils.toString(new int[]{x_p_w - srcRect.x, y_p_w - srcRect.y}));
			// Setup state
			ImageContainer ic = new ImageContainer(imp);
			StateContainer state = new StateContainer();
			state.setROI(roi, ic.getWidth(), ic.getHeight(), ic.getImageCount(), imp.getCurrentSlice());
			state.setExpansionToInside(false);
			// Run FastMarching
			final FastMarching fm = new FastMarching(ic, null, state, true, fmp.fm_grey, fmp.fm_dist, fmp.apply_grey_value_erosion);
			final int max_iterations = fmp.max_iterations;
			final int iter_inc = fmp.iter_inc;
			for (int i=0; i<max_iterations; i++) {
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
				if (!fm.step(iter_inc)) break;
			}
			// Extract ROI
			setTaskName("Adding area");
			final ArrayList<Coordinate> vc = fm.getStateContainer().getXYZ(false);
			if (0 == vc.size()) {
				Utils.log("No area growth.");
				return;
			}
			final Area area = new Area();
			Coordinate first = vc.remove(0);
			final Rectangle r = new Rectangle(first.x, first.y, 1, 1);

			int count = 0;
			// Scan and add line-wise
			for (final Coordinate c : vc) {
				count++;
				if (c.y == r.y && c.x == r.x + 1) {
					// same line:
					r.width += 1;
					continue;
				} else {
					// add previous one
					area.add(new Area(r));
				}
				// start new line:
				r.x = c.x;
				r.y = c.y;
				r.width = 1;
				if (0 == count % 1024 && Thread.currentThread().isInterrupted()) {
					return;
				}
			}
			// add last:
			area.add(new Area(r));

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
			at.translate(box.x, box.y);
			// ... and then to AreaList coordinates:
			try {
				at.preConcatenate(ali.getAffineTransform().createInverse());
			} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
			// Add Area to AreaList at Layer layer:
			ali.addArea(layer.getId(), area.createTransformedArea(at));
			ali.calculateBoundingBox();

			Display.repaint(layer);
		}}, layer.getProject());
		if (null != post_task) burro.addPostTask(post_task);
		burro.goHaveBreakfast();
		return burro;
	}

	static public class BlowCommander {
		BlowRunner br = null;
		final ExecutorService dispatcher = Executors.newFixedThreadPool(1);
		final Runnable post_task;

		public BlowCommander(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w, final Runnable post_task) throws Exception {
			this.post_task = post_task;
			dispatcher.submit(new Runnable() {
				public void run() {
					// Creation in the contex of the ExecutorService thread, so 'imp' will be local to it
					try {
						br = new BlowRunner(ali, layer, srcRect, x_p_w, y_p_w);
					} catch (Throwable t) {
						IJError.print(t);
						dispatcher.shutdownNow();
					}
				}
			});
		}
		public void mouseDragged(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {
			dispatcher.submit(new Runnable() {
				public void run() {
					try {
						// Move relative to starting point
						br.moveBlow(x_p - x_d, y_p - y_d);
					} catch (Throwable t) {
						IJError.print(t);
						mouseReleased(me, x_p, y_p, x_d_old, y_d_old, x_d, y_d);
					}
				}
			});
		}
		public void mouseReleased(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
			dispatcher.submit(new Runnable() {
				public void run() {
					// Stop accepting tasks
					dispatcher.shutdown();
					// Add the roi to the AreaList
					try {
						Utils.log2("calling finish");
						br.finish();
					} catch (Throwable t) {
						IJError.print(t);
					}
					// Execute post task if any
					if (null != post_task) {
						try {
							post_task.run();
						} catch (Throwable t) {
							IJError.print(t);
						}
					}
				}
			});
		}
	}

	static public class BlowRunner {
		final Rectangle box;
		final ImagePlus imp;
		final Lasso lasso;
		final Layer layer;
		final AreaList ali;

		public BlowRunner(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w) throws Exception {
			this.ali = ali;
			this.layer = layer;
			// Capture image as large as the fmp width,height centered on x_p_w,y_p_w
			this.box = new Rectangle(x_p_w - Segmentation.fmp.width/2, y_p_w - Segmentation.fmp.height/2, Segmentation.fmp.width, Segmentation.fmp.height);
			Utils.log2("fmp box is " + box);
			this.imp = (ImagePlus) layer.grab(box, 1.0, Patch.class, 0xffffffff, Layer.IMAGEPLUS, ImagePlus.GRAY8);
			// Bandpass filter
			if (fmp.apply_bandpass_filter) {
				IJ.run(imp, "Bandpass Filter...", "filter_large=" + fmp.low_frequency_threshold  + " filter_small=" + fmp.high_frequency_threshold + " suppress=None tolerance=5" + (fmp.autoscale_after_filtering ? " autoscale" : "") + (fmp.saturate_when_autoscaling ? " saturate" : ""));
			}

			lasso = new Lasso(imp, Lasso.BLOW, box.width/2, box.height/2, false);
		}
		public void moveBlow(int dx, int dy) throws Exception {
			lasso.moveBlow(box.width/2 + dx, box.height/2 + dy);
			// extract ROI
			Roi roi = imp.getRoi();
			if (null == roi) Display.getFront().getCanvas().getFakeImagePlus().setRoi(roi); // can't set to null? Java, gimme a break
			else {
				Roi sroi = new ShapeRoi(roi);
				Rectangle b = sroi.getBounds();
				sroi.setLocation(box.x + b.x, box.y + b.y);
				Display.getFront().getCanvas().getFakeImagePlus().setRoi(sroi);
			}
		}
		public void finish() throws Exception {
			Roi roi = imp.getRoi();
			if (null == roi) return;
			ShapeRoi sroi = new ShapeRoi(roi);
			Rectangle b = sroi.getBounds();
			sroi.setLocation(box.x + b.x, box.y + b.y);
			ali.add(layer.getId(), sroi);
		}
	}

	static public BlowCommander blowRoi(final AreaList ali, final Layer layer, final Rectangle srcRect, final int x_p_w, final int y_p_w, final Runnable post_task) throws Exception {
		return new BlowCommander(ali, layer, srcRect, x_p_w, y_p_w, post_task);
	}

}
