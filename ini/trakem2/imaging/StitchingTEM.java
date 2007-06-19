/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006, 2007 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

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

import ij.gui.Roi;
import ij.process.ImageProcessor;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.ImagePlus;

import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Transform;
import ini.trakem2.display.Display;
import ini.trakem2.ControlWindow;

import mpi.fruitfly.registration.PhaseCorrelation2D;
import mpi.fruitfly.registration.CrossCorrelation2D;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.math.datastructures.FloatArray2D;

import java.awt.Rectangle;
import java.awt.Point;

import java.awt.image.BufferedImage;


/** Given:
 *  - list of images
 *  - grid width
 *  - known percent overlap
 *
 *  This class will return the x,y of each image in the optimal montage,
 *  by applying cross correlation between neighboring images.
 *  
 *  The method is oriented to images acquired with Transmission Electron Miscrospy,
 *  where the acquistion of each image elastically deforms the sample, and thus
 *  earlier images are regarded as better than later ones.
 *
 *  It is assumed that images were acquired from 0 to n, as in the grid.
 *
 */
public class StitchingTEM {

	static public final int WORKING = -1;
	static public final int DONE = 0;
	static public final int ERROR = 1;
	static public final int INTERRUPTED = 2;

	private int flag = WORKING;
	private boolean quit = false;


	static private final int SUCCESS = 3;
	static public final int TOP_BOTTOM = 4;
	static public final int LEFT_RIGHT = 5;

	public StitchingTEM() {}

	/** Returns the same Patch instances with their coordinates modified; the top-left image is assumed to be the first one, and thus serves as reference; so, after the first image, coordinates are ignored for each specific Patch.
	 *
	 * The cross-correlation is done always with the image to the left, and on its absence, with the image above.
	 * If the cross-correlation returns values that would mean a depart larger than the known percent overlap, the image will be cross-correlated with its second option (i.e. the one above in almost all cases). In the absence of a second option to compare with, the percent overlap is applied as the least damaging option in the montage.
	 *
	 * The Patch[] array is unidimensional to allow for missing images in the last row not to interfere.
	 *
	 * The stitching runs in a separate thread; interested parties can query the status of the processing by calling the method getStatus().
	 *
	 * The scale is used to make the images smaller when doing cross-correlation. If larger than 1, it gets put back to 1.
	 *
	 * Rotation of the images is NOT considered by the cross correlation.
	 * 
	 * All this method does is translate patches along the X and Y axes, nothing else.
	 *
	 * @return A new Thread in which the work is done, or null if the initialization didn't pass the tests (all tiles have to have the same dimensions, for example).
	 */
	public Thread stitch(final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap, final boolean use_masks) {
		// start
		this.flag = WORKING;
		// check preconditions
		if (null == patch || grid_width < 1 || percent_overlap <= 0) {
			flag = ERROR;
			return null;
		}
		if (patch.length < 2) {
			flag = DONE;
			return null;
		}

		// compare Patch dimensions: later code needs all to be equal
		double w = patch[0].getWidth();
		double h = patch[0].getHeight();
		for (int i=1; i<patch.length; i++) {
			if (patch[i].getWidth() != w || patch[i].getHeight() != h) {
				flag = ERROR;
				Utils.log2("Stitching: dimensions' missmatch.\n\tAll images MUST have the same dimensions."); // i.e. all Patches, actually (meaning, all Patch objects must have the same width and the same height).
				return null;
			}
		}

		final StitchingTEM st = this;
		Thread thread = new Thread() {
			public void run() {
				StitchingTEM.stitch(st, patch, grid_width, percent_overlap, (scale > 1 ? 1 : scale), default_bottom_top_overlap, default_left_right_overlap);
			}
		};
		thread.start();
		return thread;
	}

	/** Use phase correlation: much faster, very accurate. */
	static private void stitch(final StitchingTEM st, final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap) {
		try {
			final int LEFT = 0, TOP = 1;

			int prev_i = 0;
			int prev = LEFT;

			double[] R1=null,
				 R2=null;

			for (int i=1; i<patch.length; i++) {
				if (st.quit) {
					return;
				}

				// boundary checks: don't allow displacements beyond these values
				final double default_dx = default_left_right_overlap;
				final double default_dy = default_bottom_top_overlap;

				// stitch with the one above if starting row
				if (0 == i % grid_width) {
					prev_i = i - grid_width;
					prev = TOP;
				} else {
					prev_i = i -1;
					prev = LEFT;
				}
				if (TOP == prev) {
					// compare with top only
					R1 = st.correlate(patch[prev_i], patch[i], percent_overlap, scale, TOP_BOTTOM, default_dx, default_dy);
					R2 = null;
				} else {
					// the one on the left
					R2 = st.correlate(patch[prev_i], patch[i], percent_overlap, scale, LEFT_RIGHT, default_dx, default_dy);
					// the one above
					if (i - grid_width > -1) {
						R1 = st.correlate(patch[i - grid_width], patch[i], percent_overlap, scale, TOP_BOTTOM, default_dx, default_dy);
					} else {
						R1 = null;
					}
				}

				// boundary limits: don't move by more than the small dimension of the stripe
				int max_abs_delta; // TODO: only the dx for left (and the dy for top) should be compared and found to be smaller or equal; the other dimension should be unbounded -for example, for manually acquired, grossly out-of-grid tiles.

				final Transform t = patch[i].getTransform();

				// check and apply: falls back to default overlaps when getting bad results
				if (TOP == prev) {
					if (SUCCESS == R1[2]) {
						// trust top
						t.x = R1[0];
						t.y = R1[1];
					} else {
						// don't move: use default overlap
						t.x = patch[prev_i].getX();
						t.y = patch[prev_i].getY() + patch[i - grid_width].getHeight() - default_bottom_top_overlap;
					}
				} else { // LEFT
					// the one on top, if any
					if (i - grid_width > -1) {
						if (SUCCESS == R1[2]) {
							// top is good
							if (SUCCESS == R2[2]) {
								// combine left and top
								t.x = (R1[0] + R2[0]) / 2;
								t.y = (R1[1] + R2[1]) / 2;
							} else {
								// use top alone
								t.x = R1[0];
								t.y = R1[1];
							}
						} else {
							// ignore top
							if (SUCCESS == R2[2]) {
								// use left alone
								t.x = R2[0];
								t.y = R2[1];
							} else {
								// left not trusted, top not trusted: use a combination of defaults for both
								t.x = patch[prev_i].getX() + patch[prev_i].getWidth() - default_left_right_overlap;
								t.y = patch[i - grid_width].getY() + patch[i - grid_width].getHeight() - default_bottom_top_overlap;
							}
						}
					} else if (SUCCESS == R2[2]) {
						// use left alone (top not applicable in top row)
						t.x = R2[0];
						t.y = R2[1];
					} else {
						// left not trusted, and top not applicable: use default overlap with left tile
						t.x = patch[prev_i].getX() + patch[prev_i].getWidth() - default_left_right_overlap;
						t.y = patch[prev_i].getY();
					}
				}

				// apply (and repaint)
				if (ControlWindow.isGUIEnabled()) { // in a proper design, this would happen automatically when the transform is applied to the Patch
					Rectangle box = patch[i].getBoundingBox();
					patch[i].setTransform(t);
					box.add(patch[i].getBoundingBox());
					Display.repaint(patch[i].getLayer(), box, 1);
				} else {
					patch[i].setTransform(t);
				}

				Utils.log2(i + ": Done patch " + patch[i]);
			}

			//
			st.flag = DONE;
		} catch (Exception e) {
			new IJError(e);
			st.flag = ERROR;
		}
	}

	/** Return FloatProcessor */
	static public ImageProcessor makeStripe(final Patch p, final Roi roi, final float scale) {
		final ImagePlus imp = p.getProject().getLoader().fetchImagePlus(p, false);
		ImageProcessor ip = imp.getProcessor();
		// compare and adjust
		if (ip.getWidth() != (int)p.getWidth() || ip.getHeight() != (int)p.getHeight()) {
			ip = ip.resize((int)p.getWidth(), (int)p.getHeight());
			Utils.log2("resizing stripe for patch: " + p);
		}
		// cut
		final Rectangle rb = roi.getBounds();
		if (ip.getWidth() != rb.width || ip.getHeight() != rb.height) {
			ip.setRoi(roi);
			ip = ip.crop();
		}
		// scale
		if (scale < 1) ip = ip.resize((int)(ip.getWidth() * scale)); // scale mantaining aspect ratio

		// return a FloatProcessor
		if (imp.getType() != ImagePlus.GRAY32) return ip.convertToFloat();
		return ip;
	}

	public int getStatus() {
		return flag;
	}

	public void quit() {
		this.quit = true;
		this.flag = INTERRUPTED;
	}

	/** Returns the x,y position of the moving Patch plus a third value of 1 if successful, 0 if defaults where used (when both phase-correlation and cross-correlation fail).
	 * @param scale For optimizing the speed of phase- and cross-correlation.
	 * @param percent_overlap The minimum chunk of adjacent images to compare with, will automatically and gradually increase to 100% if no good matches are found.
	 * @Return a double[4] array containing:
	 * 	- x2: absolute X position of the second Patch
	 * 	- y2: absolute Y position of the second Patch
	 * 	- flag: ERROR or SUCCESS
	 * 	- R: cross-correlation coefficient
	 */
	static public double[] correlate(final Patch base, final Patch moving, final float percent_overlap, final float scale, final int direction, final double default_dx, final double default_dy) {
		PhaseCorrelation2D pc = null;
		double R = -2;
		final int limit = 5; // number of peaks to check in the PhaseCorrelation results
		final float min_R = 0.5f;
		// Iterate until PhaseCorrelation correlation coeficient R is over 0.5, or there's no more
		// image overlap to feed
		ImageProcessor ip1, ip2;
		final int w1 = (int)base.getWidth(),
			  h1 = (int)base.getHeight(),
			  w2 = (int)moving.getWidth(),
			  h2 = (int)moving.getHeight();
		Roi roi1=null,
		    roi2=null;
		float overlap = percent_overlap;
		double x2 = default_dx,
		       y2 = default_dy;
		do {
			// create rois for the stripes
			switch(direction) {
				case TOP_BOTTOM:
					roi1 = new Roi(0, h1 - (int)(h1 * overlap), w1, (int)(h1 * overlap)); // bottom
					roi2 = new Roi(0, 0, w2, (int)(h2 * overlap)); // top
					break;
				case LEFT_RIGHT:
					roi1 = new Roi(w1 - (int)(w1 * overlap), 0, (int)(w1 * overlap), h1); // right
					roi2 = new Roi(0, 0, (int)(w2 * overlap), h2); // left
					break;
			}
			Utils.log2("roi1: " + roi1);
			Utils.log2("roi2: " + roi2);
			ip1 = makeStripe(base, roi1, scale);
			ip2 = makeStripe(moving, roi2, scale);
			//new ImagePlus("roi1", ip1).show();
			//new ImagePlus("roi2", ip2).show();
			pc = new PhaseCorrelation2D(ip1, ip2, limit, true, false, false); // with windowing
			final Point shift = pc.computePhaseCorrelation();
			final PhaseCorrelation2D.CrossCorrelationResult result = pc.getResult();
			Utils.log2("overlap: " + overlap + " R: " + result.R + " shift: " + shift + " x2,y2: " + x2 + ", " + y2);
			if (result.R >= min_R) {
				// success
				int success = SUCCESS;
				switch(direction) {
					case TOP_BOTTOM:
						// boundary checks:
						if (shift.y/scale > default_dy) success = ERROR;
						x2 = base.getX() + shift.x/scale;
						y2 = base.getY() + roi1.getBounds().y + shift.y/scale;
						break;
					case LEFT_RIGHT:
						// boundary checks:
						if (shift.x/scale > default_dx) success = ERROR;
						x2 = base.getX() + roi1.getBounds().x + shift.x/scale;
						y2 = base.getY() + shift.y/scale;
						break;
				}
				Utils.log2("R: " + result.R + " shift: " + shift + " x2,y2: " + x2 + ", " + y2);
				return new double[]{x2, y2, success, result.R};
			}
			//new ImagePlus("roi1", ip1.duplicate()).show();
			//new ImagePlus("roi2", ip2.duplicate()).show();
			//try { Thread.sleep(1000000000); } catch (Exception e) {}
			// increase for next iteration
			overlap += 0.10; // increments of 10%
		} while (R < min_R && overlap < 1.0);

		// Phase-correlation failed, fall back to cross-correlation with a safe overlap
		overlap = percent_overlap * 2;
		if (overlap > 1.0f) overlap = 1.0f;
		switch(direction) {
			case TOP_BOTTOM:
				roi1 = new Roi(0, h1 - (int)(h1 * overlap), w1, (int)(h1 * overlap)); // bottom
				roi2 = new Roi(0, 0, w2, (int)(h2 * overlap)); // top
				break;
			case LEFT_RIGHT:
				roi1 = new Roi(w1 - (int)(w1 * overlap), 0, (int)(w1 * overlap), h1); // right
				roi2 = new Roi(0, 0, (int)(w2 * overlap), h2); // left
				break;
		}
		ip1 = makeStripe(base, roi1, scale);
		ip2 = makeStripe(moving, roi2, scale);

		// gaussian blur them befoe cross-correlation
		ip1.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip1.getPixels(), ip1.getWidth(), ip1.getHeight()), 1f).data);
		ip2.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip2.getPixels(), ip2.getWidth(), ip2.getHeight()), 1f).data);


		//new ImagePlus("CC roi1", ip1).show();
		//new ImagePlus("CC roi2", ip2).show();
		final CrossCorrelation2D cc = new CrossCorrelation2D(ip1, ip2, false);
		double[] cc_result = null;
		switch (direction) {
			case TOP_BOTTOM:
				cc_result = cc.computeCrossCorrelationMT(0.9, 0.3, false);
				break;
			case LEFT_RIGHT:
				cc_result = cc.computeCrossCorrelationMT(0.3, 0.9, false);
				break;
		}
		if (cc_result[2] > min_R/2) { //accepting if R is above half the R accepted for Phase Correlation
			// success
			int success = SUCCESS;
			switch(direction) {
				case TOP_BOTTOM:
					// boundary checks:
					if (cc_result[1]/scale > default_dy) success = ERROR;
					x2 = base.getX() + cc_result[0]/scale;
					y2 = base.getY() + roi1.getBounds().y + cc_result[1]/scale;
					break;
				case LEFT_RIGHT:
					// boundary checks:
					if (cc_result[0]/scale > default_dx) success = ERROR;
					x2 = base.getX() + roi1.getBounds().x + cc_result[0]/scale;
					y2 = base.getY() + cc_result[1]/scale;
					break;
			}
			Utils.log2("CC R: " + cc_result[2] + " dx, dy: " + cc_result[0] + ", " + cc_result[1]);
			//Utils.log2("\trois: \t" + roi1 + "\n\t\t" + roi2);
			return new double[]{x2, y2, success, cc_result[2]};
		}

		// else both failed: return default values
		Utils.log2("Using default");
		return new double[]{default_dx, default_dy, ERROR, 0};
	}
}
