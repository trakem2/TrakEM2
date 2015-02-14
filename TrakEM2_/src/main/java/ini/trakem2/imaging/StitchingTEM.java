/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.registration.CrossCorrelation2D;
import mpi.fruitfly.registration.ImageFilter;
import mpicbg.imglib.algorithm.fft.PhaseCorrelationPeak;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.align.AbstractAffineTile2D;
import mpicbg.trakem2.align.Align;
import mpicbg.trakem2.align.AlignTask;
import mpicbg.trakem2.align.TranslationTile2D;


/** Given:
 *  - list of images
 *  - grid width
 *  - known percent overlap
 *
 *  This class will attempt to find the optimal montage,
 *  by applying phase-correlation, and/or cross-correlation, and/or SIFT-based correlation) between neighboring images.
 *
 *  The method is oriented to images acquired with Transmission Electron Microscopy,
 *  where the acquisition of each image elastically deforms the sample, and thus
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

	static public final int SUCCESS = 3;
	static public final int TOP_BOTTOM = 4;
	static public final int LEFT_RIGHT = 5;

	static public final float DEFAULT_MIN_R = 0.4f;


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
	 * Rotation of the images is NOT considered by the TOP_LEFT_RULE (phase- and cross-correlation),
	 * but it can be for the FREE_RULE (SIFT).
	 *
	 * @return A new Runnable task, or null if the initialization didn't pass the tests (all tiles have to have the same dimensions, for example).
	 */
	static public Runnable stitch(
			final Patch[] patch,
			final int grid_width,
			final double default_bottom_top_overlap,
			final double default_left_right_overlap,
			final boolean optimize,
			PhaseCorrelationParam param)
	{
		// check preconditions
		if (null == patch || grid_width < 1) {
			return null;
		}
		if (patch.length < 2) {
			return null;
		}

		if (null == param) {
			// Launch phase correlation dialog
			param = new PhaseCorrelationParam();
			param.setup(patch[0]);
		}

		// compare Patch dimensions: later code needs all to be equal
		final Rectangle b0 = patch[0].getBoundingBox(null);
		for (int i=1; i<patch.length; i++) {
			final Rectangle bi = patch[i].getBoundingBox(null);
			if (bi.width != b0.width || bi.height != b0.height) {
				Utils.log2("Stitching: dimensions' missmatch.\n\tAll images MUST have the same dimensions."); // i.e. all Patches, actually (meaning, all Patch objects must have the same width and the same height).
				return null;
			}
		}

		Utils.log2("patch layer: " + patch[0].getLayer());
		patch[0].getLayerSet().addTransformStep(patch[0].getLayer());

		return StitchingTEM.stitchTopLeft(patch, grid_width, default_bottom_top_overlap, default_left_right_overlap, optimize, param);
	}
	/**
	 * Stitch array of patches with upper left rule
	 *
	 * @param patch
	 * @param grid_width
	 * @param default_bottom_top_overlap
	 * @param default_left_right_overlap
	 * @param optimize
	 * @return
	 */
	static private Runnable stitchTopLeft(
			final Patch[] patch,
			final int grid_width,
			final double default_bottom_top_overlap,
			final double default_left_right_overlap,
			final boolean optimize,
			final PhaseCorrelationParam param)
	{
		return new Runnable()
		{
			@Override
            public void run() {

				try {
					final int LEFT = 0, TOP = 1;

					int prev_i = 0;
					int prev = LEFT;

					double[] R1=null,
					R2=null;


					// for minimization:
					final ArrayList<AbstractAffineTile2D<?>> al_tiles = new ArrayList<AbstractAffineTile2D<?>>();
					// first patch-tile:
					final TranslationModel2D first_tile_model = new TranslationModel2D();
					//first_tile_model.getAffine().setTransform( patch[ 0 ].getAffineTransform() );
					first_tile_model.set( (float) patch[0].getAffineTransform().getTranslateX(),
							(float) patch[0].getAffineTransform().getTranslateY());
					al_tiles.add(new TranslationTile2D(first_tile_model, patch[0]));

					for (int i=1; i<patch.length; i++) {
						if (Thread.currentThread().isInterrupted()) {
							return;
						}

						// boundary checks: don't allow displacements beyond these values
						final double default_dx = default_left_right_overlap;
						final double default_dy = default_bottom_top_overlap;

						// for minimization:
						AbstractAffineTile2D<?> tile_left = null;
						AbstractAffineTile2D<?> tile_top = null;
						final TranslationModel2D tile_model = new TranslationModel2D();
						//tile_model.getAffine().setTransform( patch[ i ].getAffineTransform() );
						tile_model.set( (float) patch[i].getAffineTransform().getTranslateX(),
								(float) patch[i].getAffineTransform().getTranslateY());
						final AbstractAffineTile2D<?> tile = new TranslationTile2D(tile_model, patch[i]);
						al_tiles.add(tile);

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
							R1 = correlate(patch[prev_i], patch[i], param.overlap, param.cc_scale, TOP_BOTTOM, default_dx, default_dy, param.min_R);
							R2 = null;
							tile_top = al_tiles.get(i - grid_width);
						} else {
							// the one on the left
							R2 = correlate(patch[prev_i], patch[i], param.overlap, param.cc_scale, LEFT_RIGHT, default_dx, default_dy, param.min_R);
							tile_left = al_tiles.get(i - 1);
							// the one above
							if (i - grid_width > -1) {
								R1 = correlate(patch[i - grid_width], patch[i], param.overlap, param.cc_scale, TOP_BOTTOM, default_dx, default_dy, param.min_R);
								tile_top = al_tiles.get(i - grid_width);
							} else {
								R1 = null;
							}
						}

						// boundary limits: don't move by more than the small dimension of the stripe
						final int max_abs_delta; // TODO: only the dx for left (and the dy for top) should be compared and found to be smaller or equal; the other dimension should be unbounded -for example, for manually acquired, grossly out-of-grid tiles.

						final Rectangle box = new Rectangle();
						final Rectangle box2 = new Rectangle();
						// check and apply: falls back to default overlaps when getting bad results
						if (TOP == prev) {
							if (SUCCESS == R1[2]) {
								// trust top
								if (optimize) addMatches(tile_top, tile, R1[0], R1[1]);
								else {
									patch[i - grid_width].getBoundingBox(box);
									patch[i].setLocation(box.x + R1[0], box.y + R1[1]);
								}
							} else {
								final Rectangle b2 = patch[i - grid_width].getBoundingBox(null);
								// don't move: use default overlap
								if (optimize) addMatches(tile_top, tile, 0, b2.height - default_bottom_top_overlap);
								else {
									patch[i - grid_width].getBoundingBox(box);
									patch[i].setLocation(box.x, box.y + b2.height - default_bottom_top_overlap);
								}
							}
						} else { // LEFT
							// the one on top, if any
							if (i - grid_width > -1) {
								if (SUCCESS == R1[2]) {
									// top is good
									if (SUCCESS == R2[2]) {
										// combine left and top
										if (optimize) {
											addMatches(tile_left, tile, R2[0], R2[1]);
											addMatches(tile_top, tile, R1[0], R1[1]);
										} else {
											patch[i-1].getBoundingBox(box);
											patch[i - grid_width].getBoundingBox(box2);
											patch[i].setLocation((box.x + R1[0] + box2.x + R2[0]) / 2, (box.y + R1[1] + box2.y + R2[1]) / 2);
										}
									} else {
										// use top alone
										if (optimize) addMatches(tile_top, tile, R1[0], R1[1]);
										else {
											patch[i - grid_width].getBoundingBox(box);
											patch[i].setLocation(box.x + R1[0], box.y + R1[1]);
										}
									}
								} else {
									// ignore top
									if (SUCCESS == R2[2]) {
										// use left alone
										if (optimize) addMatches(tile_left, tile, R2[0], R2[1]);
										else {
											patch[i-1].getBoundingBox(box);
											patch[i].setLocation(box.x + R2[0], box.y + R2[1]);
										}
									} else {
										patch[prev_i].getBoundingBox(box);
										patch[i - grid_width].getBoundingBox(box2);
										// left not trusted, top not trusted: use a combination of defaults for both
										if (optimize) {
											addMatches(tile_left, tile, box.width - default_left_right_overlap, 0);
											addMatches(tile_top, tile, 0, box2.height - default_bottom_top_overlap);
										} else {
											patch[i].setLocation(box.x + box.width - default_left_right_overlap, box2.y + box2.height - default_bottom_top_overlap);
										}
									}
								}
							} else if (SUCCESS == R2[2]) {
								// use left alone (top not applicable in top row)
								if (optimize) addMatches(tile_left, tile, R2[0], R2[1]);
								else {
									patch[i-1].getBoundingBox(box);
									patch[i].setLocation(box.x + R2[0], box.y + R2[1]);
								}
							} else {
								patch[prev_i].getBoundingBox(box);
								// left not trusted, and top not applicable: use default overlap with left tile
								if (optimize) addMatches(tile_left, tile, box.width - default_left_right_overlap, 0);
								else {
									patch[i].setLocation(box.x + box.width - default_left_right_overlap, box.y);
								}
							}
						}

						if (!optimize) Display.repaint(patch[i].getLayer(), patch[i], null, 0, false);
						Utils.log2(i + ": Done patch " + patch[i]);
					}

					if (optimize) {

						final ArrayList<AbstractAffineTile2D<?>> al_fixed_tiles = new ArrayList<AbstractAffineTile2D<?>>();
						// Add locked tiles as fixed tiles, if any:
                                                for (int i=0; i<patch.length; i++) {
                                                        if (patch[i].isLocked2()) al_fixed_tiles.add(al_tiles.get(i));
                                                }
                                                if (al_fixed_tiles.isEmpty()) {
							// When none, add the first one as fixed
                                                        al_fixed_tiles.add(al_tiles.get(0));
                                                }

						// Optimize iteratively tile configuration by removing bad matches
						optimizeTileConfiguration(al_tiles, al_fixed_tiles, param);


						for ( final AbstractAffineTile2D< ? > t : al_tiles )
							t.getPatch().setAffineTransform( t.getModel().createAffine() );

					}

					// Remove or hide disconnected tiles
					if(param.hide_disconnected || param.remove_disconnected)
					{
						final List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( al_tiles );
						final List< AbstractAffineTile2D< ? > > interestingTiles;

						// find largest graph

						Set< Tile< ? > > largestGraph = null;
						for ( final Set< Tile< ? > > graph : graphs )
							if ( largestGraph == null || largestGraph.size() < graph.size() )
								largestGraph = graph;

						Utils.log("Size of largest stitching graph = " + largestGraph.size());

						interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
						for ( final Tile< ? > t : largestGraph )
							interestingTiles.add( ( AbstractAffineTile2D< ? > )t );

						if ( param.hide_disconnected )
							for ( final AbstractAffineTile2D< ? > t : al_tiles )
								if ( !interestingTiles.contains( t ) )
									t.getPatch().setVisible( false );
						if ( param.remove_disconnected )
							for ( final AbstractAffineTile2D< ? > t : al_tiles )
								if ( !interestingTiles.contains( t ) )
									t.getPatch().remove( false );
					}


					Display.repaint(patch[0].getLayer(), null, 0, true); // all

					//
				} catch (final Exception e) {
					IJError.print(e);
				}
			}
		};
	}

	/** dx, dy is the position of t2 relative to the 0,0 of t1. */
	static private final void addMatches(final AbstractAffineTile2D<?> t1, final AbstractAffineTile2D<?> t2, final double dx, final double dy) {
		final Point p1 = new Point(new double[]{0, 0});
		final Point p2 = new Point(new double[]{dx, dy});
		t1.addMatch(new PointMatch(p2, p1, 1.0f));
		t2.addMatch(new PointMatch(p1, p2, 1.0f));
		t1.addConnectedTile(t2);
		t2.addConnectedTile(t1);
	}


	static public ImageProcessor makeStripe(final Patch p, final Roi roi, final double scale) {
		return makeStripe(p, roi, scale, false);
	}

//	static public ImageProcessor makeStripe(final Patch p, final double scale, final boolean ignore_patch_transform) {
//		return makeStripe(p, null, scale, ignore_patch_transform);
//	}

	/** @return FloatProcessor.
	 * @param ignore_patch_transform will prevent resizing of the ImageProcessor in the event of the Patch having a transform different than identity. */
	// TODO 2: there is a combination of options that ends up resulting in the actual ImageProcessor of the Patch being returned as is, which is DANGEROUS because it can potentially result in changes in the data.
	static public ImageProcessor makeStripe(final Patch p, final Roi roi, final double scale, final boolean ignore_patch_transform) {


		ImagePlus imp = null;
		ImageProcessor ip = null;
		final Loader loader =  p.getProject().getLoader();
		// check if using mipmaps and if there is a file for it. If there isn't, most likely this method is being called in an import sequence as grid procedure.
		if (loader.isMipMapsRegenerationEnabled() && loader.checkMipMapFileExists(p, scale))
		{

			// Read the transform image from the patch (this way we avoid the JPEG artifacts)
			final Patch.PatchImage pai = p.createTransformedImage();
			pai.target.setMinAndMax( p.getMin(), p.getMax() );

			Image image = pai.target.createImage(); //p.getProject().getLoader().fetchImage(p, scale);

			// check that dimensions are correct. If anything, they'll be larger
			//Utils.log2("patch w,h " + p.getWidth() + ", " + p.getHeight() + " fetched image w,h: " + image.getWidth(null) + ", " + image.getHeight(null));
			if (Math.abs(p.getWidth() * scale - image.getWidth(null)) > 0.001 || Math.abs(p.getHeight() * scale - image.getHeight(null)) > 0.001) {
				image = image.getScaledInstance((int)(p.getWidth() * scale), (int)(p.getHeight() * scale), Image.SCALE_AREA_AVERAGING); // slow but good quality. Makes an RGB image, but it doesn't matter.
				//Utils.log2("   resizing, now image w,h: " + image.getWidth(null) + ", " + image.getHeight(null));
			}
			try {
				imp = new ImagePlus("s", image);
				ip = imp.getProcessor();
				//imp.show();
			} catch (final Exception e) {
				IJError.print(e);
			}
			// cut
			if (null != roi) {
				// scale ROI!
				Rectangle rb = roi.getBounds();
				final Roi roi2 = new Roi((int)(rb.x * scale), (int)(rb.y * scale), (int)(rb.width * scale), (int)(rb.height * scale));
				rb = roi2.getBounds();
				if (ip.getWidth() != rb.width || ip.getHeight() != rb.height) {
					ip.setRoi(roi2);
					ip = ip.crop();
				}
			}
			//Utils.log2("scale: " + scale + "  ip w,h: " + ip.getWidth() + ", " + ip.getHeight());
		} else {


			final Patch.PatchImage pai = p.createTransformedImage();
			pai.target.setMinAndMax( p.getMin(), p.getMax() );

			ip = pai.target;
			imp = new ImagePlus("", ip);


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
				ip.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip.getPixels(), ip.getWidth(), ip.getHeight()), Math.sqrt(0.25 / scale / scale - 0.25)).data); // scaling with area averaging is the same as a gaussian of sigma 0.5/scale and then resize with nearest neightbor So this line does the gaussian, and line below does the neares-neighbor scaling
				ip = ip.resize((int)(ip.getWidth() * scale)); // scale maintaining aspect ratio
			}
		}

		//Utils.log2("makeStripe: w,h " + ip.getWidth() + ", " + ip.getHeight());

		// return a FloatProcessor
		if (imp.getType() != ImagePlus.GRAY32) return ip.convertToFloat();

		return ip;
	}




	 /** @param scale For optimizing the speed of phase- and cross-correlation.<br />
	 * @param percent_overlap The minimum chunk of adjacent images to compare with, will automatically and gradually increase to 100% if no good matches are found.<br />
	 * @return a double[4] array containing:<br />
	 * 	- x2: relative X position of the second Patch<br />
	 * 	- y2: relative Y position of the second Patch<br />
	 * 	- flag: ERROR or SUCCESS<br />
	 * 	- R: cross-correlation coefficient<br />
	 */
	static public double[] correlate(final Patch base, final Patch moving, final float percent_overlap, final double scale, final int direction, final double default_dx, final double default_dy, final double min_R) {
		//PhaseCorrelation2D pc = null;
		final double R = -2;
		//final int limit = 5; // number of peaks to check in the PhaseCorrelation results
		//final float min_R = 0.40f; // minimum R for phase-correlation to be considered good
					// half this min_R will be considered good for cross-correlation
		// Iterate until PhaseCorrelation correlation coefficient R is over 0.5, or there's no more
		// image overlap to feed
		//Utils.log2("min_R: " + min_R);
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
			ip1.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip1.getPixels(), ip1.getWidth(), ip1.getHeight()), 1.0).data);
			ip2.setPixels(ImageFilter.computeGaussianFastMirror(new FloatArray2D((float[])ip2.getPixels(), ip2.getWidth(), ip2.getHeight()), 1.0).data);
			//

			final ImagePlus imp1 = new ImagePlus( "", ip1 );
			final ImagePlus imp2 = new ImagePlus( "", ip2 );

			final PhaseCorrelationCalculator t = new PhaseCorrelationCalculator(imp1, imp2);
			final PhaseCorrelationPeak peak = t.getPeak();

			final double resultR = peak.getCrossCorrelationPeak();
			final int[] peackPostion = peak.getPosition();
			final java.awt.Point shift = new java.awt.Point(peackPostion[0], peackPostion[1]);

			//pc = new PhaseCorrelation2D(ip1, ip2, limit, true, false, false); // with windowing
			//final java.awt.Point shift = pc.computePhaseCorrelation();
			//final PhaseCorrelation2D.CrossCorrelationResult result = pc.getResult();

			//Utils.log2("overlap: " + overlap + " R: " + resultR + " shift: " + shift + " dx,dy: " + dx + ", " + dy);
			if (resultR >= min_R) {
				// success
				final int success = SUCCESS;
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
				//Utils.log2("R: " + resultR + " shift: " + shift + " dx,dy: " + dx + ", " + dy);
				return new double[]{dx, dy, success, resultR};
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
		final double scale_cc = scale / 3.0f;
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
			final int success = SUCCESS;
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
			//Utils.log2("CC R: " + cc_result[2] + " dx, dy: " + cc_result[0] + ", " + cc_result[1]);
			//Utils.log2("\trois: \t" + roi1 + "\n\t\t" + roi2);
			return new double[]{dx, dy, success, cc_result[2]};
		}

		// else both failed: return default values
		//Utils.log2("Using default");
		return new double[]{default_dx, default_dy, ERROR, 0};


		/// ABOVE: boundary checks don't work if default_dx,dy are zero! And may actually be harmful in anycase
	}

	/** Figure out from which direction is the dragged object approaching the object being overlapped. 0=left, 1=top, 2=right, 3=bottom. This method by Stephan Nufer. */
	static private int getClosestOverlapLocation(final Patch dragging_ob, final Patch overlapping_ob) {
		final Rectangle x_rect = dragging_ob.getBoundingBox();
		final Rectangle y_rect = overlapping_ob.getBoundingBox();
		final Rectangle overlap = x_rect.intersection(y_rect);
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

	/**
	 * For each Patch, find who overlaps with it and perform a phase correlation or cross-correlation with it;
	 *  then consider all successful correlations as links and run the optimizer on it all.
	 *  ASSUMES the patches have only TRANSLATION in their affine transforms--will warn you about it.*/
	static public Bureaucrat montageWithPhaseCorrelationTask(final Collection<Patch> col) {
		if (null == col || col.size() < 1) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Montage with phase-correlation") {
			@Override
            public void exec() {
				montageWithPhaseCorrelation(col);
			}
		}, col.iterator().next().getProject());
	}

	static public void montageWithPhaseCorrelation(final Collection<Patch> col) {
		final PhaseCorrelationParam param = new PhaseCorrelationParam();
		if (!param.setup(col.iterator().next())) {
			return;
		}
		AlignTask.transformPatchesAndVectorData(col, new Runnable() { @Override
        public void run() {
			montageWithPhaseCorrelation(col, param);
		}});
	}

	static public Bureaucrat montageWithPhaseCorrelationTask(final List<Layer> layers) {
		if (null == layers || layers.size() < 1) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Montage layer 1/" + layers.size()) {
			@Override
            public void exec() {
				montageWithPhaseCorrelation(layers, this);
			}
		}, layers.get(0).getProject());
	}

	/**
	 *
	 * @param layers
	 * @param worker Optional, the {@link Worker} running this task.
	 */
	static public void montageWithPhaseCorrelation(final List<Layer> layers, final Worker worker) {
		final PhaseCorrelationParam param = new PhaseCorrelationParam();
		final Collection<Displayable> col = layers.get(0).getDisplayables(Patch.class);
		if (!param.setup(col.size() > 0 ? (Patch)col.iterator().next() : null)) {
			return;
		}
		final int i = 1;
		for (final Layer la : layers) {
			if (Thread.currentThread().isInterrupted() || (null != worker && worker.hasQuitted())) return;
			if (null != worker) worker.setTaskName("Montage layer " + i + "/" + layers.size());
			final Collection<Patch> patches = (Collection<Patch>) (Collection) la.getDisplayables(Patch.class);
			AlignTask.transformPatchesAndVectorData(patches, new Runnable() { @Override
            public void run() {
				montageWithPhaseCorrelation(patches, param);
			}});
		}
	}

	/**
	 * Phase correlation parameters class
	 *
	 */
	static public class PhaseCorrelationParam
	{
		public double cc_scale = 0.25;
		public float overlap = 0.1f;
		public boolean hide_disconnected = false;
		public boolean remove_disconnected = false;
		public double mean_factor = 2.5;
		public double min_R = 0.3;

		public PhaseCorrelationParam(
				final double cc_scale,
				final float overlap,
				final boolean hide_disconnected,
				final boolean remove_disconnected,
				final double mean_factor,
				final double min_R)
		{
			this.cc_scale = cc_scale;
			this.overlap = overlap;
			this.hide_disconnected = hide_disconnected;
			this.remove_disconnected = remove_disconnected;
			this.mean_factor = mean_factor;
			this.min_R = min_R;
		}

		/**
		 * Empty constructor
		 */
		public PhaseCorrelationParam() {}

		/** Run setup on a Patch of the layer, if any. */
		public boolean setup(final Layer layer) {
			final Collection<Displayable> p = layer.getDisplayables(Patch.class);
			final Patch patch = p.isEmpty() ? null : (Patch)p.iterator().next();
			// !@#$%%^^
			return setup(patch);
		}

		/**
		 * Returns false when canceled.
		 * @param ref is an optional Patch from which to estimate an appropriate image scale
		 * 			at which to perform the phase correlation, for performance reasons.
		 * */
		public boolean setup(final Patch ref)
		{
			final GenericDialog gd = new GenericDialog("Montage with phase correlation");
			if (overlap < 0) overlap = 0.1f;
			else if (overlap > 1) overlap = 1;
			gd.addSlider("tile_overlap (%): ", 1, 100, overlap * 100);
			int sc = (int)cc_scale * 100;
			if (null != ref) {
				// Estimate scale from ref Patch dimensions
				final int w = ref.getOWidth();
				final int h = ref.getOHeight();
				sc = (int)((512.0 / (w > h ? w : h)) * 100); // guess a scale so that image is 512x512 aprox
			}
			if (sc <= 0) sc = 25;
			else if (sc > 100) sc = 100;
			gd.addSlider("scale (%):", 1, 100, sc);
			gd.addNumericField( "max/avg displacement threshold: ", mean_factor, 2 );
			gd.addNumericField("regression threshold (R):", min_R, 2);
			gd.addCheckbox("hide disconnected", false);
			gd.addCheckbox("remove disconnected", false);
			gd.showDialog();
			if (gd.wasCanceled()) return false;

			overlap = (float)gd.getNextNumber() / 100f;
			cc_scale = gd.getNextNumber() / 100.0;
			mean_factor = gd.getNextNumber();
			min_R = gd.getNextNumber();
			hide_disconnected = gd.getNextBoolean();
			remove_disconnected = gd.getNextBoolean();

			return true;
		}
	}

	/**
	 * Perform montage based on phase correlation
	 * @param col collection of patches
	 * @param param phase correlation parameters
	 */
	static public void montageWithPhaseCorrelation(final Collection<Patch> col, final PhaseCorrelationParam param)
	{
		if (null == col || col.size() < 1) return;
		final ArrayList<Patch> al = new ArrayList<Patch>(col);
		final ArrayList<AbstractAffineTile2D<?>> tiles = new ArrayList<AbstractAffineTile2D<?>>();
		final ArrayList<AbstractAffineTile2D<?>> fixed_tiles = new ArrayList<AbstractAffineTile2D<?>>();

		for (final Patch p : al) {
			// Pre-check: just a warning
			final int aff_type = p.getAffineTransform().getType();
			switch (p.getAffineTransform().getType()) {
				case AffineTransform.TYPE_IDENTITY:
				case AffineTransform.TYPE_TRANSLATION:
					// ok
					break;
				default:
					Utils.log2("WARNING: patch with a non-translation transform: " + p);
					break;
			}
			// create tiles
			final TranslationTile2D tile = new TranslationTile2D(new TranslationModel2D(), p);
			tiles.add(tile);
			if (p.isLocked2()) {
				Utils.log("Added fixed (locked) tile " + p);
				fixed_tiles.add(tile);
			}
		}
		// Get acceptable values
		double cc_scale = param.cc_scale;
		if (cc_scale < 0 || cc_scale > 1) {
			Utils.log("Unacceptable cc_scale of " + param.cc_scale + ". Using 1 instead.");
			cc_scale = 1;
		}
		float overlap = param.overlap;
		if (overlap < 0 || overlap > 1) {
			Utils.log("Unacceptable overlap of " + param.overlap + ". Using 1 instead.");
			overlap = 1;
		}



		for (int i=0; i<al.size(); i++) {
			final Patch p1 = al.get(i);
			final Rectangle r1 = p1.getBoundingBox();
			// find overlapping, add as connections
			for (int j=i+1; j<al.size(); j++) {
				if (Thread.currentThread().isInterrupted()) return;
				final Patch p2 = al.get(j);
				final Rectangle r2 = p2.getBoundingBox();
				if (r1.intersects(r2)) {
					// Skip if it's a diagonal overlap
					final int dx = Math.abs(r1.x - r2.x);
					final int dy = Math.abs(r1.y - r2.y);
					if (dx > r1.width/2 && dy > r1.height/2) {
						// skip diagonal match
						Utils.log2("Skipping diagonal overlap between " + p1 + " and " + p2);
						continue;
					}

					p1.getProject().getLoader().releaseToFit((long)(p1.getWidth() * p1.getHeight() * 25));

					final double[] R;
					if (1 == overlap) {
						R = correlate(p1, p2, overlap, cc_scale, TOP_BOTTOM, 0, 0, param.min_R );
						if (SUCCESS == R[2]) {
							addMatches(tiles.get(i), tiles.get(j), R[0], R[1]);
						}
					} else {
						switch (getClosestOverlapLocation(p1, p2)) {
							case 0: // p1 overlaps p2 from the left
								R = correlate(p1, p2, overlap, cc_scale, LEFT_RIGHT, 0, 0, param.min_R);
								if (SUCCESS == R[2]) {
									addMatches(tiles.get(i), tiles.get(j), R[0], R[1]);
								}
								break;
							case 1: // p1 overlaps p2 from the top
								R = correlate(p1, p2, overlap, cc_scale, TOP_BOTTOM, 0, 0, param.min_R);
								if (SUCCESS == R[2]) {
									addMatches(tiles.get(i), tiles.get(j), R[0], R[1]);
								}
								break;
							case 2: // p1 overlaps p2 from the right
								R = correlate(p2, p1, overlap, cc_scale, LEFT_RIGHT, 0, 0, param.min_R);
								if (SUCCESS == R[2]) {
									addMatches(tiles.get(j), tiles.get(i), R[0], R[1]);
								}
								break;
							case 3: // p1 overlaps p2 from the bottom
								R = correlate(p2, p1, overlap, cc_scale, TOP_BOTTOM, 0, 0, param.min_R);
								if (SUCCESS == R[2]) {
									addMatches(tiles.get(j), tiles.get(i), R[0], R[1]);
								}
								break;
							default:
								Utils.log("Unknown overlap direction!");
								continue;
						}
					}
				}
			}
		}

		if (param.remove_disconnected || param.hide_disconnected) {
			for (final Iterator<AbstractAffineTile2D<?>> it = tiles.iterator(); it.hasNext(); ) {
				final AbstractAffineTile2D<?> t = it.next();
				if (null != t.getMatches() && t.getMatches().isEmpty()) {
					if (param.hide_disconnected) t.getPatch().setVisible(false);
					else if (param.remove_disconnected) t.getPatch().remove(false);
					it.remove();
				}
			}
		}

		// Optimize tile configuration by removing bad matches
		optimizeTileConfiguration(tiles, fixed_tiles, param);

		for ( final AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );

		try { Display.repaint(al.get(0).getLayer()); } catch (final Exception e) {}
	}

	/**
	 * Optimize tile configuration by removing bad matches
	 *
	 * @param tiles complete list of tiles
	 * @param fixed_tiles list of fixed tiles
	 * @param param phase correlation parameters
	 */
	public static void optimizeTileConfiguration(
			final ArrayList<AbstractAffineTile2D<?>> tiles,
			final ArrayList<AbstractAffineTile2D<?>> fixed_tiles,
			final PhaseCorrelationParam param)
	{
		// Run optimization
		if (fixed_tiles.isEmpty()) fixed_tiles.add(tiles.get(0));
		// with default parameters
		boolean proceed = true;
		while ( proceed )
		{
			Align.optimizeTileConfiguration( new Align.ParamOptimize(), tiles, fixed_tiles );

			/* get all transfer errors */
			final ErrorStatistic e = new ErrorStatistic( tiles.size() + 1 );

			for ( final AbstractAffineTile2D< ? > t : tiles )
				t.update();

			for ( final AbstractAffineTile2D< ? > t : tiles )
			{
				for ( final PointMatch p : t.getMatches() )
				{
					e.add( p.getDistance() );
				}
			}

			/* remove the worst if there is one */
			if ( e.max > param.mean_factor * e.mean )
			{
A:				for ( final AbstractAffineTile2D< ? > t : tiles )
				{
					for ( final PointMatch p : t.getMatches() )
					{
						if ( p.getDistance() >= e.max )
						{
							final Tile< ? > o = t.findConnectedTile( p );
							t.removeConnectedTile( o );
							o.removeConnectedTile( t );
							//Utils.log2( "Removing bad match from configuration, error = " + e.max );
							break A;
						}
					}
				}
			}
			else
				proceed = false;
		}
	}

}
