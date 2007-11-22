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
import ij.gui.GenericDialog;

import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Selection;
import ini.trakem2.ControlWindow;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.persistence.Loader;

import mpi.fruitfly.registration.PhaseCorrelation2D;
import mpi.fruitfly.registration.CrossCorrelation2D;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.math.General;
import mpi.fruitfly.registration.Tile;
import mpi.fruitfly.registration.Optimize;
import mpi.fruitfly.registration.PointMatch;
import mpi.fruitfly.registration.Point;
import mpi.fruitfly.registration.TModel2D;

import java.awt.Rectangle;
import java.awt.Image;
import java.awt.image.BufferedImage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.awt.geom.AffineTransform;


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

	static public final int TOP_LEFT_RULE = 0;
	static public final int NETWORK_RULE = 1;

	static public void addStitchingRuleChoice(GenericDialog gd) {
		final String[] rules = new String[]{"Top left" /*, "Free"*/};
		gd.addChoice("stitching_rule: ", rules, rules[0]);
	}

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
	public Thread stitch(final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap, final boolean use_masks, final int stitching_rule) {
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
		//double w = patch[0].getWidth();
		//double h = patch[0].getHeight();
		Rectangle b0 = patch[0].getBoundingBox(null);
		for (int i=1; i<patch.length; i++) {
			Rectangle bi = patch[i].getBoundingBox(null);
			//if (patch[i].getWidth() != w || patch[i].getHeight() != h)
			if (bi.width != b0.width || bi.height != b0.height) {
				flag = ERROR;
				Utils.log2("Stitching: dimensions' missmatch.\n\tAll images MUST have the same dimensions."); // i.e. all Patches, actually (meaning, all Patch objects must have the same width and the same height).
				return null;
			}
		}

		final StitchingTEM st = this;
		Thread thread = new Thread() {
			public void run() {
				switch (stitching_rule) {
				case StitchingTEM.TOP_LEFT_RULE:
					StitchingTEM.stitchTopLeft(st, patch, grid_width, percent_overlap, (scale > 1 ? 1 : scale), default_bottom_top_overlap, default_left_right_overlap);
					break;
				}
			}
		};
		thread.start();
		return thread;
	}

	static private void stitchTopLeft(final StitchingTEM st, final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap) {
		try {
			final int LEFT = 0, TOP = 1;

			int prev_i = 0;
			int prev = LEFT;

			double[] R1=null,
				 R2=null;

			// for minimization:
			ArrayList<Tile> al_tiles = new ArrayList<Tile>();
			// first patch-tile:
			al_tiles.add(new Tile((float)patch[0].getWidth(), (float)patch[0].getHeight(), new TModel2D()));

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

				// target x,y
				double tx = 0,
				       ty = 0;

				// check and apply: falls back to default overlaps when getting bad results
				if (TOP == prev) {
					if (SUCCESS == R1[2]) {
						// trust top
						tx = R1[0];
						ty = R1[1];
					} else {
						final Rectangle b1 = patch[prev_i].getBoundingBox(null);
						final Rectangle b2 = patch[i - grid_width].getBoundingBox(null);
						// don't move: use default overlap
						tx = b1.x;
						ty = b1.y + b2.height - default_bottom_top_overlap;
					}
				} else { // LEFT
					// the one on top, if any
					if (i - grid_width > -1) {
						if (SUCCESS == R1[2]) {
							// top is good
							if (SUCCESS == R2[2]) {
								// combine left and top
								tx = (R1[0] + R2[0]) / 2;
								ty = (R1[1] + R2[1]) / 2;
							} else {
								// use top alone
								tx = R1[0];
								ty = R1[1];
							}
						} else {
							// ignore top
							if (SUCCESS == R2[2]) {
								// use left alone
								tx = R2[0];
								ty = R2[1];
							} else {
								final Rectangle b1 = patch[prev_i].getBoundingBox(null);
								final Rectangle b2 = patch[i - grid_width].getBoundingBox(null);
								// left not trusted, top not trusted: use a combination of defaults for both
								tx = b1.x + b1.width - default_left_right_overlap;
								ty = b2.y + b2.height - default_bottom_top_overlap;
							}
						}
					} else if (SUCCESS == R2[2]) {
						// use left alone (top not applicable in top row)
						tx = R2[0];
						ty = R2[1];
					} else {
						final Rectangle b1 = patch[prev_i].getBoundingBox(null);
						// left not trusted, and top not applicable: use default overlap with left tile
						tx = b1.x + b1.width - default_left_right_overlap;
						ty = b1.y;
					}
				}

				// for global minimization:
				Tile prev_tile = al_tiles.get(prev_i);
				Tile tile = new Tile((float)patch[i].getWidth(), (float)patch[i].getHeight(), new TModel2D());
				al_tiles.add(tile);
				mpi.fruitfly.registration.Point p1 = new mpi.fruitfly.registration.Point(new float[]{0f, 0f});
				mpi.fruitfly.registration.Point p2 = new mpi.fruitfly.registration.Point(new float[]{(float)tx, (float)ty});
				prev_tile.addMatch(new PointMatch(p1, p2, 1.0f));
				tile.addMatch(new PointMatch(p2, p1, 1.0f));
				prev_tile.addConnectedTile(tile);
				tile.addConnectedTile(prev_tile);

				// apply (and repaint)
				/* // nopt anymore, for minimization may be screwed
				if (ControlWindow.isGUIEnabled()) {
					Rectangle box = patch[i].getBoundingBox();
					patch[i].translate(tx, ty);
					box.add(patch[i].getBoundingBox());
					Display.repaint(patch[i].getLayer(), box, 1);
				} else {
					patch[i].translate(tx, ty);
				}
				*/

				Utils.log2(i + ": Done patch " + patch[i]);
			}

			ArrayList<Patch> al_patches = new ArrayList<Patch>();
			for (int i=0; i<patch.length; i++) al_patches.add(patch[i]);
			ArrayList<Tile> al_fixed_tiles = new ArrayList<Tile>();
			al_fixed_tiles.add(al_tiles.get(0));

			// ready for montage-wise minimization
			Optimize.minimizeAll(al_tiles, al_patches, al_fixed_tiles, 50);
			Display.repaint(patch[0].getLayer(), null, 0, true); // all

			//
			st.flag = DONE;
		} catch (Exception e) {
			new IJError(e);
			st.flag = ERROR;
		}
	}

	static public ImageProcessor makeStripe(final Patch p, final Roi roi, final float scale) {
		return makeStripe(p, roi, scale, false, false);
	}

	static public ImageProcessor makeStripe(final Patch p, final float scale, final boolean quality, final boolean ignore_patch_transform) {
		return makeStripe(p, null, scale, quality, ignore_patch_transform);
	}

	/** @return FloatProcessor.
	 * @param ignore_patch_transform will prevent resizing of the ImageProcessor in the event of the Patch having a transform different than identity. */ // TODO param 'quality' is being ignored // TODO 2: there is a combination of options that ends up resulting in the actual ImageProcessor of the Patch being returned as is, which is DANGEROUS because it can potentially result in changes in the data.
	static public ImageProcessor makeStripe(final Patch p, final Roi roi, final float scale, boolean quality, boolean ignore_patch_transform) {
		ImagePlus imp = null;
		ImageProcessor ip = null;
		Loader loader =  p.getProject().getLoader();
		// check if using mipmaps and if there is a file for it. If there isn't, most likely this method is being called in an import sequence as grid procedure.
		if (loader.isMipMapsEnabled() && loader.checkMipMapExists(p, scale)) {
			Image image = p.getProject().getLoader().fetchImage(p, scale);
			// check that dimensions are correct. If anything, they'll be larger
			//Utils.log2("patch w,h " + p.getWidth() + ", " + p.getHeight() + " fetched image w,h: " + image.getWidth(null) + ", " + image.getHeight(null));
			if (Math.abs(p.getWidth() * scale - image.getWidth(null)) > 0.001 || Math.abs(p.getHeight() * scale - image.getHeight(null)) > 0.001) {
				image = image.getScaledInstance((int)(p.getWidth() * scale), (int)(p.getHeight() * scale), Image.SCALE_AREA_AVERAGING); // slow but good quality. Makes an RGB image, but it doesn't matter.
				//Utils.log2("   resizing, now image w,h: " + image.getWidth(null) + ", " + image.getHeight(null));
			}
			try {
				imp = new ImagePlus("s", image);
				ip = imp.getProcessor();
			} catch (Exception e) {
				new IJError(e);
			}
			// cut
			if (null != roi) {
				// scale ROI!
				Rectangle rb = roi.getBounds();
				Roi roi2 = new Roi((int)(rb.x * scale), (int)(rb.y * scale), (int)(rb.width * scale), (int)(rb.height * scale));
				rb = roi2.getBounds();
				if (ip.getWidth() != rb.width || ip.getHeight() != rb.height) {
					ip.setRoi(roi2);
					ip = ip.crop();
				}
			}
			//Utils.log2("scale: " + scale + "  ip w,h: " + ip.getWidth() + ", " + ip.getHeight());
		} else {
			imp = loader.fetchImagePlus(p, false);
			ip = imp.getProcessor();
			// compare and adjust
			if (!ignore_patch_transform && p.getAffineTransform().getType() != AffineTransform.TYPE_TRANSLATION) { // if it's not only a translation:
				final Rectangle b = p.getBoundingBox();
				ip = ip.resize(b.width, b.height);
				//Utils.log2("resizing stripe for patch: " + p);
				// the above is only meant to correct for improperly acquired images at the microscope, the scale only.
			}
			// cut
			if (null != roi) {
				final Rectangle rb = roi.getBounds();
				if (ip.getWidth() != rb.width || ip.getHeight() != rb.height) {
					ip.setRoi(roi);
					ip = ip.crop();
				}
			}
			// scale
			if (scale < 1) {
				p.getProject().getLoader().releaseToFit((long)(ip.getWidth() * ip.getHeight() * 4 * 1.2)); // floats have 4 bytes, plus some java peripherals correction factor
				ip = ip.convertToFloat();
				ip.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip.getPixels(), ip.getWidth(), ip.getHeight()), (float)Math.sqrt(0.25 / scale / scale - 0.25)).data); // scaling with area averaging is the same as a gaussian of sigma 0.5/scale and then resize with nearest neightbor So this line does the gaussian, and line below does the neares-neighbor scaling
				ip = ip.resize((int)(ip.getWidth() * scale)); // scale mantaining aspect ratio
			}
		}

		//Utils.log2("makeStripe: w,h " + ip.getWidth() + ", " + ip.getHeight());

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
	 * 	- x2: relative X position of the second Patch
	 * 	- y2: relative Y position of the second Patch
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
		final Rectangle b1 = base.getBoundingBox(null);
		final Rectangle b2 = moving.getBoundingBox(null);
		final int w1 = b1.width,
			  h1 = b1.height,
			  w2 = b2.width,
			  h2 = b2.height;
		Roi roi1=null,
		    roi2=null;
		float overlap = percent_overlap;
		double dx = default_dx,
		       dy = default_dy;
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
			//Utils.log2("roi1: " + roi1);
			//Utils.log2("roi2: " + roi2);
			ip1 = makeStripe(base, roi1, scale); // will apply the transform if necessary
			ip2 = makeStripe(moving, roi2, scale);
			//new ImagePlus("roi1", ip1).show();
			//new ImagePlus("roi2", ip2).show();
			ip1.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip1.getPixels(), ip1.getWidth(), ip1.getHeight()), 1f).data);
			ip2.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip2.getPixels(), ip2.getWidth(), ip2.getHeight()), 1f).data);
			//
			pc = new PhaseCorrelation2D(ip1, ip2, limit, true, false, false); // with windowing
			final java.awt.Point shift = pc.computePhaseCorrelation();
			final PhaseCorrelation2D.CrossCorrelationResult result = pc.getResult();
			Utils.log2("overlap: " + overlap + " R: " + result.R + " shift: " + shift + " dx,dy: " + dx + ", " + dy);
			if (result.R >= min_R) {
				// success
				int success = SUCCESS;
				switch(direction) {
					case TOP_BOTTOM:
						// boundary checks:
						//if (shift.y/scale > default_dy) success = ERROR;
						dx = shift.x/scale;
						dy = roi1.getBounds().y + shift.y/scale;
						break;
					case LEFT_RIGHT:
						// boundary checks:
						//if (shift.x/scale > default_dx) success = ERROR;
						dx = roi1.getBounds().x + shift.x/scale;
						dy = shift.y/scale;
						break;
				}
				Utils.log2("R: " + result.R + " shift: " + shift + " dx,dy: " + dx + ", " + dy);
				return new double[]{dx, dy, success, result.R};
			}
			//new ImagePlus("roi1", ip1.duplicate()).show();
			//new ImagePlus("roi2", ip2.duplicate()).show();
			//try { Thread.sleep(1000000000); } catch (Exception e) {}
			// increase for next iteration
			overlap += 0.10; // increments of 10%
		} while (R < min_R && Math.abs(overlap - 1.0f) < 0.001f);

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
		// use one third of the size used for phase-correlation though! Otherwise, it may take FOREVER
		float scale_cc = (float)(scale / 3f);
		ip1 = makeStripe(base, roi1, scale_cc);
		ip2 = makeStripe(moving, roi2, scale_cc);

		// gaussian blur them before cross-correlation
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
					//if (cc_result[1]/scale_cc > default_dy) success = ERROR;
					dx = cc_result[0]/scale_cc;
					dy = roi1.getBounds().y + cc_result[1]/scale_cc;
					break;
				case LEFT_RIGHT:
					// boundary checks:
					//if (cc_result[0]/scale_cc > default_dx) success = ERROR;
					dx = roi1.getBounds().x + cc_result[0]/scale_cc;
					dy = cc_result[1]/scale_cc;
					break;
			}
			Utils.log2("CC R: " + cc_result[2] + " dx, dy: " + cc_result[0] + ", " + cc_result[1]);
			//Utils.log2("\trois: \t" + roi1 + "\n\t\t" + roi2);
			return new double[]{dx, dy, success, cc_result[2]};
		}

		// else both failed: return default values
		Utils.log2("Using default");
		return new double[]{default_dx, default_dy, ERROR, 0};


		/// ABOVE: boundary checks don't work if default_dx,dy are zero! And may actually be harmful in anycase
	}

	/** Works only for Patch instances at the moment. */
	static public Bureaucrat snap(final Displayable d, final Display display) {
		final Worker worker = new Worker("Snapping") {
			public void run() {
				startedWorking();
				try {
	

		Utils.log2("snapping...");
		// snap patches only
		if (null == d || !(d instanceof Patch)) return;
		//Utils.log("Snapping " + d);
		ArrayList al = d.getLayer().getIntersecting(d, Patch.class);
		if (null == al || 0 == al.size()) return;
		// remove from the intersecting group those Patch objects that are linked in the same layer (those linked that do not intersect simply return false on the al.remove(..) )
		HashSet hs_linked = d.getLinkedGroup(new HashSet());
		//Utils.log2("linked patches: " + hs_linked.size());
		Layer layer = d.getLayer();
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Displayable dob = (Displayable)it.next();
			if (Patch.class.equals(dob.getClass()) && dob.getLayer().equals(layer)) {
				al.remove(dob);
			}
		}
		// dragged Patch
		final Patch p_dragged = (Patch)d;

		// start:
		double[] best_pc = null;
		Patch base = null;
		try {
			//  make a reasonable guess for the scale
			float cc_scale = (float)(512.0 / (p_dragged.getWidth() > p_dragged.getHeight() ? p_dragged.getWidth() : p_dragged.getHeight()));
			if (cc_scale > 1.0f) cc_scale = 1.0f;
			//
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				base = (Patch)it.next();
				final double[] pc = StitchingTEM.correlate(base, p_dragged, 1f, cc_scale, StitchingTEM.TOP_BOTTOM, 0, 0);
				if (null == best_pc) best_pc = pc;
				else {
					// compare R: choose largest
					if (pc[3] > best_pc[3]) {
						best_pc = pc;
					}
				}
			}
		} catch (Exception e) {
			new IJError(e);
			return;
		}
		// now, relocate the Patch
		double dx = base.getX() + best_pc[0];
		double dy = base.getY() + best_pc[1];
		Rectangle box = p_dragged.getLinkedBox(true);
		//Utils.log2("box is " + box);
		p_dragged.translate(dx, dy, true);
		Rectangle r = p_dragged.getLinkedBox(true);
		//Utils.log2("dragged box is " + r);
		box.add(r);
		Selection selection = display.getSelection();
		if (selection.contains(p_dragged)) {
			//Utils.log2("going to update selection");
			Display.updateSelection(display);
		}
		Display.repaint(p_dragged.getLayer().getParent()/*, box*/);
		Utils.log2("Done snapping.");

				} catch (Exception e) {
					new IJError(e);
				}
				finishedWorking();
			}
		};
		Bureaucrat burro = new Bureaucrat(worker, d.getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	/** Figure out from which direction is the dragged object approaching the object being overlapped. 0=left, 1=top, 2=right, 3=bottom. This method by Stephan Nufer. */
	/*
	private int getClosestOverlapLocation(Patch dragging_ob, Patch overlapping_ob) {
		Rectangle x_rect = dragging_ob.getBoundingBox();
		Rectangle y_rect = overlapping_ob.getBoundingBox();
		Rectangle overlap = x_rect.intersection(y_rect);
		if (overlap.width / (double)overlap.height > 1) {
			// horizontal stitch
			if (y_rect.y < x_rect.y) return 3;
			else return 1;
		} else {
			// vertical stitch
			if (y_rect.x < x_rect.x) return 2;
			else return 0;
		}
	}
	*/
}
