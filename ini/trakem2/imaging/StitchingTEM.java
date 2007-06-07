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
import ij.ImagePlus;

import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Transform;
import ini.trakem2.display.Display;
import ini.trakem2.ControlWindow;

import java.awt.Rectangle;

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
	 */
	public void stitch(final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap, final boolean use_masks) {
		// start
		this.flag = WORKING;
		// check preconditions
		if (null == patch || grid_width < 1 || percent_overlap <= 0) {
			flag = ERROR;
			return;
		}
		if (patch.length < 2) {
			flag = DONE;
			return;
		}

		// compare Patch dimensions: later code needs all to be equal
		double w = patch[0].getWidth();
		double h = patch[0].getHeight();
		for (int i=1; i<patch.length; i++) {
			if (patch[i].getWidth() != w || patch[i].getHeight() != h) {
				flag = ERROR;
				Utils.log2("Stitching: dimensions' missmatch.\n\tAll images MUST have the same dimensions."); // i.e. all Patches, actually (meaning, all Patch objects must have the same width and the same height).
				return;
			}
		}

		final StitchingTEM st = this;
		new Thread() {
			public void run() {
				StitchingTEM.stitch(st, patch, grid_width, percent_overlap, (scale > 1 ? 1 : scale), default_bottom_top_overlap, default_left_right_overlap, use_masks);
			}
		}.start();
	}

	/** Assumes all files have the same dimensions. */
	static private void stitch(final StitchingTEM st, final Patch[] patch, final int grid_width, final float percent_overlap, final float scale, final double default_bottom_top_overlap, final double default_left_right_overlap, final boolean use_masks) {
		try {
			final int LEFT = 0, TOP = 1;

			int prev_i = 0;
			int prev = LEFT;

			final int w = (int)patch[0].getWidth(); // the ImagePlus will be resized if it has different dimensions than the enclosing Patch
			final int h = (int)patch[0].getHeight();
			// fix overlap to be at most 2 times larger than the user's demand (the algorithm favors low overlap)
			float percent_overlap2 = percent_overlap * 2;
			if (percent_overlap2 > 1.0f) percent_overlap2 = 1.0f;

			// base stripe double width or height than moving stripe
			final Roi roi_top = new Roi(0, 0, w, (int)(h * percent_overlap));
			final Roi roi_left = new Roi(0, 0, (int)(w * percent_overlap), h);
			final Roi roi_right = new Roi(w - (int)(w * percent_overlap2), 0, (int)(w * percent_overlap2), h);
			final Roi roi_bottom = new Roi(0, h - (int)(h * percent_overlap2), w, (int)(h * percent_overlap2));


			//debug:
			/*
			Utils.log2("top: " + roi_top.toString());
			Utils.log2("left: " + roi_left.toString());
			Utils.log2("right: " + roi_right.toString());
			Utils.log2("bottom: " + roi_bottom.toString());
			*/

			ByteProcessor bp1, bp2, bp3=null, bp4=null;



			for (int i=1; i<patch.length; i++) {
				if (st.quit) {
					return;
				}
				// stitch with the one above if starting row
				if (0 == i % grid_width) {
					prev_i = i - grid_width;
					prev = TOP;
				} else {
					prev_i = i -1;
					prev = LEFT;
				}
				// create a stripe image for involved parties
				if (TOP == prev) {
					// compare with top only
					bp1 = makeStripe(patch[prev_i], roi_bottom, scale, use_masks);
					bp2 = makeStripe(patch[i], roi_top, scale, use_masks);
				} else {
					// the one on the left
					bp1 = makeStripe(patch[prev_i], roi_right, scale, use_masks);
					bp2 = makeStripe(patch[i], roi_left, scale, use_masks);
					// the one above
					if (i - grid_width > -1) {
						bp3 = makeStripe(patch[i - grid_width], roi_bottom, scale, use_masks);
						bp4 = makeStripe(patch[i], roi_top, scale, use_masks);
					}
				}

				// correlate both stripes
				final double[] cc = Stitching.crossCorrelate(bp1, bp2);
				final double[] cc_top = (LEFT == prev && (i - grid_width) > -1) ?  Stitching.crossCorrelate(bp3, bp4) : null;
				bp3 = null; // flag as releasable
				bp4 = null;


				final double dx = cc[0] / scale;
				final double dy = cc[1] / scale; // python unpacking is missed

				// debug:
				Utils.log("i: " + i + " dx,dy,val: " + (int)dx + ", " + (int)dy  + ", " + cc[2]);

				// boundary limits: don't move by more than the small dimension of the stripe
				int max_abs_delta; // TODO: only the dx for left (and the dy for top) should be compared and found to be smaller or equal; the other dimension should be unbounded -for example, for manually acquired, grossly out-of-grid tiles.

				final Transform t = patch[i].getTransform();

				// check and apply: falls back to default overlaps when getting bad results
				if (TOP == prev) {
					max_abs_delta = roi_bottom.getBounds().height; // the small dimension of the ROI of the base stripe
					if (Math.abs(dx) > max_abs_delta || Math.abs(dy) > max_abs_delta) {
						// don't move: use default overlap
						t.x = patch[prev_i].getX();
						t.y = patch[prev_i].getY() + patch[i - grid_width].getHeight() - default_bottom_top_overlap;
					} else {
						// trust top
						t.x = patch[prev_i].getX() + dx;
						t.y = patch[prev_i].getY() + roi_bottom.getBounds().y + dy;
					}
				} else { // LEFT
					max_abs_delta = roi_right.getBounds().width; // the small dimension of the ROI of the base stripe

					boolean left_trusted = true;
					if (Math.abs(dx) > max_abs_delta || Math.abs(dy) > max_abs_delta) {
						// ignore left
						left_trusted = false;
					}
					// the one on top, if any
					if (i - grid_width > -1) {
						final double dx2 = cc_top[0] / scale;
						final double dy2 = cc_top[1] / scale;
						max_abs_delta = roi_bottom.getBounds().height; // the small dimension of the ROI of the base stripe
						if (Math.abs(dx2) > max_abs_delta || Math.abs(dy2) > max_abs_delta) {
							// ignore top
							if (left_trusted) {
								// use left alone
								t.x = patch[prev_i].getX() + roi_right.getBounds().x + dx;
								t.y = patch[prev_i].getY() + dy;
							} else {
								// left not trusted, top not trusted: use a combination of defaults for both
								t.x = patch[prev_i].getX() + patch[prev_i].getWidth() - default_left_right_overlap;
								t.y = patch[i - grid_width].getY() + patch[i - grid_width].getHeight() - default_bottom_top_overlap;
							}
						} else {
							// top is good
							if (left_trusted) {
								// combine left and top
								t.x = (patch[prev_i].getX() + roi_right.getBounds().x + dx
								     + patch[i - grid_width].getX() + dx2) / 2;
								t.y = (patch[prev_i].getY() + dy
								     + patch[i - grid_width].getY() + roi_bottom.getBounds().y + dy2) / 2;
							} else {
								// use top alone
								t.x = patch[i - grid_width].getX() + dx2;
								t.y = patch[i - grid_width].getY() + roi_bottom.getBounds().y + dy2;
							}
							//
							Utils.log2("\ttop: " + (int)dx + ", " + (int)dy + ", " + cc_top[2]);
						}
					} else if (left_trusted) {
						// use left alone
						t.x = patch[prev_i].getX() + roi_right.getBounds().x + dx;
						t.y = patch[prev_i].getY() + dy;
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

	/** Takes into account that the Patch dimensions may differ from its image dimensions, so that the returned stripe is transformed to the Patch's transform. Rotations are NOT considered.*/
	static public ByteProcessor makeStripe(final Patch p, final Roi roi, final float scale, /*final*/ boolean use_masks) {
		final ImagePlus imp = p.getProject().getLoader().fetchImagePlus(p, false);
		ImageProcessor ip = imp.getProcessor();
		// compare and adjust
		if (ip.getWidth() != (int)p.getWidth() || ip.getHeight() != (int)p.getHeight()) {
			ip = ip.resize((int)p.getWidth(), (int)p.getHeight());
			Utils.log2("resizing stripe for patch: " + p);
		}
		// cut
		ip.setRoi(roi);
		ip = ip.crop();
		// scale
		if (scale < 1) ip = ip.resize((int)(ip.getWidth() * scale)); // scale mantaining aspect ratio
		// to GRAY8
		if (imp.getType() != ImagePlus.GRAY8) ip = ip.convertToByte(true); // with scaling
		// enhance contrast savagely (works great if the contrast has been homogenized first for all images.) Even the 'noise' (blank section) looks good!
		//
		//
		//use_masks = false; // TEMPORARY
		//
		//
		if (use_masks) {
			//ij.plugin.filter.GaussianBlur.blurGaussian(ip, 2,2,0.01);
			// create a mask of the stripe and use it instead
			int threshold = ip.getAutoThreshold();
			byte[] pix = (byte[])ip.getPixels();
			for (int i=0; i<pix.length; i++) {
				if ((pix[i]&0xff) < threshold) pix[i] = 0; // black byte
				else pix[i] = -1; // white byte
			}
			// despeckle
			ImagePlus tmp = new ImagePlus("d", ip);
			ij.plugin.filter.PlugInFilter plugin = new ij.plugin.filter.RankFilters();
			plugin.setup("despeckle", tmp);
			plugin.run(ip);
			ip = tmp.getProcessor(); // in case it has changed

			// debug:
			//tmp.show();
		}
		//new ImagePlus(p.getTitle(), ip).show();

		return (ByteProcessor)ip;
	}

	public int getStatus() {
		return flag;
	}

	public void quit() {
		this.quit = true;
		this.flag = INTERRUPTED;
	}
}
