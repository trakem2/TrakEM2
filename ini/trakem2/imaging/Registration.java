/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt)

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.imaging;

import static mpi.fruitfly.math.General.*;
import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.TRModel;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;

import ini.trakem2.display.*;
import ini.trakem2.utils.*;
import ini.trakem2.persistence.Loader;
import ini.trakem2.ControlWindow;
import ini.trakem2.imaging.StitchingTEM;

import ij.ImagePlus;
import ij.process.*;
import ij.gui.GenericDialog;
import ij.gui.Roi;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;
import java.awt.geom.AffineTransform;

/**
 * Accessor methods to Stephan Preibisch's FFT-based registration implementation.
 * 
 * Preibisch's registration:
 * - returns angles between 0 and 180, perfectly reciprocal (img1 to img2 equals img2 to img1)
 * - is non-reciprocal (but almos) for translations (must choose between best)
 * - will only work reliably if there is at least 50% overlap between any two images to register
 *
 *
 * */
public class Registration {

	/** minimal allowed alignment error in px (pixels) */
	private static float min_epsilon = 2.0f;
	/** maximal allowed alignment error in px (pixels) */
	private static float max_epsilon = 100.0f;
	/** feature descriptor size */
	private static int fdsize = 8;
	/** feature descriptor orientation bins */
	private static int fdbins = 8;
	/** ASK */
	private static int steps = 3;

	/** Makes a snapshot with the Patch objects in both layers at the given scale, and rotates/translates all Displayable elements in the second Layer relative to the first. */
	static public boolean registerLayers(final Layer layer1, final Layer layer2, final double max_rot, final double max_displacement, final double scale, final boolean ignore_squared_angles, final boolean enhance_edges) {
		if (scale <= 0) return false;
		try {
			// get minimal enclosing boxes
			Rectangle box1 = layer1.getMinimalBoundingBox(Patch.class);
			if (null == box1) return false;
			Rectangle box2 = layer2.getMinimalBoundingBox(Patch.class);
			if (null == box2) return false;
			// get flat grayscale images, scaled
			ImagePlus imp1 = layer1.getProject().getLoader().getFlatImage(layer1, box1, scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
			ImagePlus imp2 = layer2.getProject().getLoader().getFlatImage(layer2, box2, scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
			// ready to start
			//
			//
			// TODO
			//
			//
		} catch (Exception e) {
			new IJError(e);
			return false;
		}
		return true;
	}

	/** Registers the second image relative to the first. Returns an array of:
	 * - the set of features for the first image
	 * - the set of features for the second image
	 * - the AffineTransform defining the registration of the second image relative to the first.
	 *
	 * The given @param fs1 may be null, in which case it will be generated from the first ImagePlus.
	 * @param initial_sigma is adjustable, so that high magnification steps can be skipped for noisy or highly variable datasets, which show most similarity at coarser, lower magnifiation levels.
	 *
	 * Returns null if the model is not significant.
	 */
	static public Object[] registerSIFT(final ImageProcessor ip1, final ImageProcessor ip2, Vector <FloatArray2DSIFT.Feature> fs1, final Registration.SIFTParameters sp) {
		// prepare both sets of features
		if (null == fs1) fs1 = getSIFTFeatures(ip1, sp.initial_sigma, sp.min_size, sp.max_size);
		final Vector<FloatArray2DSIFT.Feature> fs2 = getSIFTFeatures(ip2, sp.initial_sigma, sp.min_size, sp.max_size);
		// compare
		final Vector<Match> correspondences = FloatArray2DSIFT.createMatches(fs1, fs2, 1.5f, null, Float.MAX_VALUE);
		/** From Stephan Saalfeld:
		 * We want to assure, that the model does not fit to a local spatial
		 * subset of all matches only, because this signalizes a good local
		 * alignment but bad global results.
		 *
		 * Therefore, we compare the Eigenvalues of the covariance matrix of
		 * all points to those of the inliers matching the model.  That is, we
		 * compare the variance of those point sets.  A low variance of the
		 * inliers compared to those of all matches signifies a very local
		 * subset.
		 */

		double[] ev1 = new double[2];
		double[] ev2 = new double[2];
		double[] cov1 = new double[3];
		double[] cov2 = new double[3];
		double[] o1 = new double[2];
		double[] o2 = new double[2];
		double[] evec1 = new double[4];
		double[] evec2 = new double[4];
		Match.covariance(correspondences, cov1, cov2, o1, o2, ev1, ev2, evec1, evec2);
		// above: the mighty C++ programmer! What a piece of risky code!
		// TODO replace this as well as the iteration with increasing epsilon by a reliable robust maximal inlier set estimation to be found

		TRModel model = null;
		final float[] tr = new float[5];
		float epsilon = 0.0f;
		if (correspondences.size() > TRModel.MIN_SET_SIZE) {
			ev1[0] = Math.sqrt(ev1[0]);
			ev1[1] = Math.sqrt(ev1[1]);
			ev2[0] = Math.sqrt(ev2[0]);
			ev2[1] = Math.sqrt(ev2[1]);

			double r1 = Double.MAX_VALUE;
			double r2 = Double.MAX_VALUE;
			
			int highest_num_inliers = 0;
			int convergence_count = 0;
			do {
				epsilon += sp.min_epsilon;
				//System.out.println("Estimating model for epsilon = " + epsilon);
				// 1000 iterations lead to a probability of < 0.01% that only bad data values were found
				model = TRModel.estimateModel(
						correspondences,      //!< point correspondences
						1000,                 //!< iterations
						epsilon * sp.scale,   //!< maximal alignment error for a good point pair when fitting the model
						sp.inlier_ratio,      //!< minimal inlier ratio required for a model to be accepted
						tr                    //!< model as float array (TrakEM style)
						);

				// compare the standard deviation of inliers and matches
				if (model != null) {
					int num_inliers = model.getInliers().size();
					if (num_inliers <= highest_num_inliers) {
						++convergence_count; }
					else {
						convergence_count = 0;
						highest_num_inliers = num_inliers;
					}
					double[] evi1 = new double[2];
					double[] evi2 = new double[2];
					double[] covi1 = new double[3];
					double[] covi2 = new double[3];
					double[] oi1 = new double[2];
					double[] oi2 = new double[2];
					double[] eveci1 = new double[4];
					double[] eveci2 = new double[4];
					Match.covariance(model.getInliers(), covi1, covi2, oi1, oi2, evi1, evi2, eveci1, eveci2);

					evi1[0] = Math.sqrt(evi1[0]);
					evi1[1] = Math.sqrt(evi1[1]);
					evi2[0] = Math.sqrt(evi2[0]);
					evi2[1] = Math.sqrt(evi2[1]);

					double r1x = evi1[0] / ev1[0];
					double r1y = evi1[1] / ev1[1];
					double r2x = evi2[0] / ev2[0];
					double r2y = evi2[1] / ev2[1];

					r1x = r1x < 1.0 ? 1.0 / r1x : r1x;
					r1y = r1y < 1.0 ? 1.0 / r1y : r1y;
					r2x = r2x < 1.0 ? 1.0 / r2x : r2x;
					r2y = r2y < 1.0 ? 1.0 / r2y : r2y;

					r1 = (r1x + r1y) / 2.0;
					r2 = (r2x + r2y) / 2.0;
					r1 = Double.isNaN(r1) ? Double.MAX_VALUE : r1;
					r2 = Double.isNaN(r2) ? Double.MAX_VALUE : r2;

					//System.out.println("deviation ratio: " + r1 + ", " + r2 + ", max = " + Math.max(r1, r2));
					//f.println(epsilon + " " + (float)model.getInliers().size() / (float)correspondences.size());
				}
			} while ((model == null || convergence_count < 4 || (Math.max(r1, r2) > 2.0))
			     && epsilon < sp.max_epsilon);
		}

		final AffineTransform at = new AffineTransform();

		if (model != null)
		{
			// debug
			Utils.log2("epsilon: " + epsilon + "  inliers: " + model.getInliers().size() + "  corresp: " + correspondences.size());
			// images may have different sizes
			/**
			 * TODO Different sizes are no problem as long as the top left
			 * corner is at the fixed position (x0:0.0f, y0:0.0f).  If this is not the
			 * case, translate the image relative to this position.  That is,
			 * translate the pivot point of the rotation and translate the translation
			 * vector itself.
			 */
			//final double xdiff = ip1.getWidth() - ip2.getWidth();
			//final double ydiff = ip1.getHeight() - ip2.getHeight();
//			// assumes rotation origin at the center of the image.
//			at.rotate( tr[2],
//				  (tr[3] - ip2.getWidth() / 2.0f) / scale + 0.5f,
//				  (tr[4] - ip2.getHeight() / 2.0f) / scale + 0.5f);
			
			// rotation origin at the top left corner of the image (0.0f, 0.0f) of the image.
			at.rotate( tr[2], tr[3] / sp.scale, tr[4] / sp.scale );
			at.translate(tr[0] / sp.scale, tr[1] / sp.scale);
		} else {
			Utils.log("No sufficient model found, keeping original transformation for " + ip2);
			return null;
		}

		return new Object[]{fs1, fs2, at};
	}

	/** Returns a sorted list of the SIFT features extracted from the given ImagePlus. */
	final static public Vector<FloatArray2DSIFT.Feature> getSIFTFeatures(final ImageProcessor ip, final float initial_sigma, final int min_size, final int max_size) {
		final FloatArray2D fa = ImageFilter.computeGaussianFastMirror(
				ImageArrayConverter.ImageToFloatArray2D(ip.convertToFloat()),
				(float)Math.sqrt(initial_sigma * initial_sigma - 0.25));
		final FloatArray2DSIFT sift = new FloatArray2DSIFT(fdsize, fdbins);
		sift.init(fa, steps, initial_sigma, min_size, max_size);
		final Vector<FloatArray2DSIFT.Feature> fs = sift.run(max_size);
		Collections.sort(fs);
		return fs;
	}

	/** Will cross-correlate slices in a separate Thread; leaves the given slice untouched.
	 * @param base_slice is the reference slice from which the stack will be registered, i.e. it won't be affected in any way.
	 */
	static public Bureaucrat registerStackSlices(final Patch base_slice) {
		// find linked images in different layers and register them
		// 
		// setup parameters
		final Registration.SIFTParameters sp = new Registration.SIFTParameters();
		sp.setup();

		final Worker worker = new Worker("Registering stack slices") {
			public void run() {
				startedWorking();
				try {
					correlateSlices(base_slice, new HashSet(), this, sp/*, null*/); // using non-recursive version
					// ensure there are no negative numbers in the x,y
					base_slice.getLayer().getParent().setMinimumDimensions();
				} catch (Exception e) {
					new IJError(e);
				}
				finishedWorking();
			}
		};
		// watcher thread
		final Bureaucrat burro = new Bureaucrat(worker, base_slice.getProject());
		burro.goHaveBreakfast();
		return burro;
	}
	/** Recursive into linked images in other layers. */
	static private void correlateSlices(final Patch slice, final HashSet hs_done, final Worker worker, final Registration.SIFTParameters sp, final Vector<FloatArray2DSIFT.Feature> fs_slice) {
		if (hs_done.contains(slice)) return;
		hs_done.add(slice);
		// iterate over all Patches directly linked to the given slice
		// recursive version: has memory releasing problems.
		HashSet hs = slice.getLinked(Patch.class);
		Utils.log2("@@@ size: " + hs.size());
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			if (worker.hasQuitted()) return;
			final Patch p = (Patch)it.next();
			if (hs_done.contains(p)) continue;
			// skip linked images within the same layer
			if (p.getLayer().equals(slice.getLayer())) continue;
			// ensure there are no negative numbers in the x,y
			slice.getLayer().getParent().setMinimumDimensions();
			// go
			final Object[] result = Registration.registerWithSIFTLandmarks(slice, p, sp, fs_slice);
			// enable GC:
			if (null != result) {
				result[0] = null;
				result[2] = null;
			}
			Registration.correlateSlices(p, hs_done, worker, sp, null != result ? (Vector<FloatArray2DSIFT.Feature>)result[1] : null); // I give it the feature set of the moving patch, which in this call will serve as base
		}
	}
	/** Non-recursive version (for the processing; the assembly of the stack chain is recursive). */
	static private void correlateSlices(final Patch slice, final HashSet hs_done, final Worker worker, final Registration.SIFTParameters sp) {
		// non-recursive version: build the chain first. Assumes there are only two chains max.
		final ArrayList al_chain1 = new ArrayList();
		final ArrayList al_chain2 = new ArrayList();
		for (Iterator it = slice.getLinked(Patch.class).iterator(); it.hasNext(); ) {
			if (0 == al_chain1.size()) {
				al_chain1.add(slice);
				al_chain1.add(it.next());
				buildChain(al_chain1);
				continue;
			}
			if (0 == al_chain2.size()) {
				al_chain2.add(slice);
				al_chain2.add(it.next());
				buildChain(al_chain2);
				continue;
			}
			break; // only two max; a Patch of a stack should never have more than two Patch linked to it
		}
		if (al_chain1.size() >= 2) processChain(al_chain1, sp, worker);
		if (al_chain2.size() >= 2) processChain(al_chain2, sp, worker);
	}

	/** Take the last one of the chain, inspect its linked Patches, add the one that is not yet included, continue building. Recursive. */
	static private void buildChain(final ArrayList al_chain) {
		final Patch p = (Patch)al_chain.get(al_chain.size()-1);
		for (Iterator it = p.getLinked(Patch.class).iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (al_chain.contains(ob)) continue;
			else {
				al_chain.add(ob);
				buildChain(al_chain);
				break;
			}
		}
	}
	static private void processChain(final ArrayList al_chain, final Registration.SIFTParameters sp, final Worker worker) {
		Object[] result = null;
		for (int i=1; i<al_chain.size(); i++) {
			if (worker.hasQuitted()) return;
			result = registerWithSIFTLandmarks((Patch)al_chain.get(i-1), (Patch)al_chain.get(i), sp, null == result ? null : (Vector<FloatArray2DSIFT.Feature>)result[1]);
		}
	}

	/** The @param fs_base is the vector of features of the base Patch, and can be null -in which case it will be computed. */
	static private Object[] registerWithSIFTLandmarks(final Patch base, final Patch moving, final Registration.SIFTParameters sp, final Vector<FloatArray2DSIFT.Feature> fs_base) {

		Utils.log2("processing layer " + moving.getLayer().getParent().indexOf(moving.getLayer()));

		ImageProcessor ip1 = StitchingTEM.makeStripe(base, sp.scale, true, true);
		ImageProcessor ip2 = StitchingTEM.makeStripe(moving, sp.scale, true, true);

		final Object[] result = Registration.registerSIFT(ip1, ip2, fs_base, sp);

		// enable garbage collection!
		ip1 = null;
		ip2 = null;
		// no hope. The recursion prevents from lots of memory from ever being released.
		// MWAHAHA so I made a non-recursive smart-ass version.
		// It is somewhat disturbing that each SIFT match at max_size 1600 was using nearly 400 Mb, and all of them were NOT released because of the recursion.
		Loader.runGC();
		base.getProject().getLoader().releaseMemory();

		if (null != result) {
			AffineTransform at_moving = moving.getAffineTransform();
			at_moving.setToIdentity(); // be sure to CLEAR it totally
			// set to the given result
			at_moving.setTransform((AffineTransform)result[2]);
			// pre-apply the base's transform
			at_moving.preConcatenate(base.getAffineTransform());

			at_moving = null;

			if (ControlWindow.isGUIEnabled()) {
				Rectangle box = moving.getBoundingBox();
				box.add(moving.getBoundingBox());
				Display.repaint(moving.getLayer(), box, 1);

				box = null;
			}
		} else {
			// failed, fall back to phase-correlation
			Utils.log2("Automatic landmark detection failed, falling back to phase-correlation.");
			Registration.correlate(base, moving, sp.scale);
		}
		return result;
	}

	static private class SIFTParameters {
		// filled with default values
		float scale = 1.0f;
		int steps = 3;
		float initial_sigma = 1.6f;
		int fdsize = 8;
		int fdbins = 8;
		int min_size = 64;
		int max_size = 1024;
		/** Maximal initial drift of landmark relative to its matching landmark in the other image, to consider when searching.  Also used as increment for epsilon when there was no sufficient model found.*/
		float min_epsilon = 2.0f;
		float max_epsilon = 100.0f;
		/** Minimal percent of good landmarks found */
		float inlier_ratio = 0.05f;

		boolean setup() {
			final GenericDialog gd = new GenericDialog("Options");
			gd.addSlider("scale (%):", 1, 100, scale*100);
			gd.addNumericField("steps_per_scale_octave :", steps, 0);
			gd.addNumericField("initial_gaussian_blur :", initial_sigma, 2);
			gd.addNumericField("feature_descriptor_size :", fdsize, 0);
			gd.addNumericField("feature_descriptor_orientation_bins :", fdbins, 0);
			gd.addNumericField("minimum_image_size :", min_size, 0);
			gd.addNumericField("maximum_image_size :", max_size, 0);
			gd.addNumericField("minimal_alignment_error :", min_epsilon, 2);
			gd.addNumericField("maximal_alignment_error :", max_epsilon, 2);
			gd.addNumericField("inlier_ratio :", inlier_ratio, 2);
			gd.showDialog();
			if (gd.wasCanceled()) return false;
			this.scale = (float)gd.getNextNumber() / 100;
			this.steps = (int)gd.getNextNumber();
			this.initial_sigma = (float)gd.getNextNumber();
			this.fdsize = (int)gd.getNextNumber();
			this.fdbins = (int)gd.getNextNumber();
			this.min_size = (int)gd.getNextNumber();
			this.max_size = (int)gd.getNextNumber();
			this.min_epsilon = (float)gd.getNextNumber();
			this.max_epsilon = (float)gd.getNextNumber();
			this.inlier_ratio = (float)gd.getNextNumber();

			return true;
		}
	}

	static private void correlate(final Patch base, final Patch moving, final float scale) {
		Utils.log2("Correlating #" + moving.getId() + " to #" + base.getId());

		// test rotation first TODO

		final double[] pc = StitchingTEM.correlate(base, moving, 1f, scale, StitchingTEM.TOP_BOTTOM, 0, 0);
		if (pc[3] < 0.25f) {
			// R is too low to be trusted
			Utils.log("Bad R coefficient, skipping " + moving);
			// set the moving to the same position as the base
			pc[0] = base.getX();
			pc[1] = base.getY();
		}
		Utils.log2("BASE: x, y " + base.getX() + " , " + base.getY() + "\n\t pc x,y: " + pc[0] + ", " + pc[1]);
		if (ControlWindow.isGUIEnabled()) {
			Rectangle box = moving.getBoundingBox();
			moving.setLocation(pc[0], pc[1]);
			box.add(moving.getBoundingBox());
			Display.repaint(moving.getLayer(), box, 1);
		} else {
			moving.setLocation(pc[0], pc[1]);
		}
		Utils.log("--- Done correlating target #" + moving.getId() + "  to base #" + base.getId());
	}
}
