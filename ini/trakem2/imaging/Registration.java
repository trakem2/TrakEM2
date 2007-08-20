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
import ij.ImagePlus;
import ij.process.*;

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
	 */
	static public Object[] registerSIFT(final ImageProcessor ip1, final ImageProcessor ip2, Vector <FloatArray2DSIFT.Feature> fs1, final float initial_sigma, final int min_size, final int max_size, final float scale) {
		// prepare both sets of features
		if (null == fs1) fs1 = getSIFTFeatures(ip1, initial_sigma, min_size, max_size);
		final Vector<FloatArray2DSIFT.Feature> fs2 = getSIFTFeatures(ip2, initial_sigma, min_size, max_size);
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

		TRModel model = null;
		final float[] tr = new float[5];
		float epsilon = 0.0f;
		if (correspondences.size() > model.MIN_SET_SIZE) {
			ev1[0] = Math.sqrt(ev1[0]);
			ev1[1] = Math.sqrt(ev1[1]);
			ev2[0] = Math.sqrt(ev2[0]);
			ev2[1] = Math.sqrt(ev2[1]);

			double r1 = Double.MAX_VALUE;
			double r2 = Double.MAX_VALUE;
			
			int highest_num_inliers = 0;
			int convergence_count = 0;
			do {
				epsilon += min_epsilon;
				//System.out.println("Estimating model for epsilon = " + epsilon);
				// 1000 iterations lead to a probability of < 0.01% that only bad data values were found
				model = TRModel.estimateModel(
						correspondences,			//!< point correspondences
						1000,						//!< iterations
						epsilon * (float)scale,	//!< maximal alignment error for a good point pair when fitting the model
						//0.1f,						//!< minimal partition (of 1.0) of inliers
						0.05f,						//!< minimal partition (of 1.0) of inliers
						tr							//!< model as float array (TrakEM style)
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
			} while ((model == null || convergence_count < 5 || (Math.max(r1, r2) > 2.0))
			     && epsilon < max_epsilon);
		}

		final AffineTransform at = new AffineTransform();

		if (model != null)
		{
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
			at.rotate( tr[2], tr[3] / scale, tr[4] / scale );
			at.translate(tr[0] / scale, tr[1] / scale);
		} else {
			Utils.log("No sufficient model found, keeping original transformation for " + ip2);
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
}
