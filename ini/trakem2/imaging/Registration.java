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
import mpi.fruitfly.registration.Feature;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.registration.PointMatch;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.registration.Tile;
import mpi.fruitfly.registration.Model;
import mpi.fruitfly.registration.TModel2D;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.analysis.FitLine;

import ini.trakem2.Project;
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
import java.awt.geom.Point2D;
import java.awt.Rectangle;
import java.util.*;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.Serializable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accessor methods to Stephan Preibisch's FFT-based registration,
 * and to Stephan Saalfeld's SIFT-based registration.
 * <pre>
Preibisch's registration:
- returns angles between 0 and 180, perfectly reciprocal (img1 to img2 equals img2 to img1)
- is non-reciprocal (but almos) for translations (must choose between best)
- will only work reliably if there is at least 50% overlap between any two images to register


SIFT consumes plenty of memory:
 - in extracting features:
    - ImageArrayConverter.ImageToFloatArray2D:
        - makes a new FloatProcessor (so the original pointers can be set to null)
        - makes a new FloatArray2D from the FloatProcessor
    - FloatArray2DSIFT:
        - makes a new FloatArray2DScaleOctaveDoGDetector
        - makes a new float[] for the descriptorMask
    - FloatArray2DSIFT.init:
        - makes a new FloatArray2DScaleOctave
        - makes one new ImageFilter.createGaussianKernel1D for each step
        - makes one new FloatArray2DScaleOctave for each octave
    - ImageFilter.computeGaussianFastMirror: duplicates the FloatArray2D

    In all, the above relates to the input image width and height as:
     area = width * height
     size = area * sizeof(float) * 2
     plus a factorial factor for the octaves of aprox 1.5,
     plus another 1.5 for all the small arrays created on the meanwhile:

     size = area * 8 * 2 * 3 = area * 48  (aprox.)
</pre>
 * */
public class Registration {

	static public final int GLOBAL_MINIMIZATION = 0;
	static public final int LAYER_SIFT = 1;

	static public Bureaucrat registerLayers(final Layer layer, final int kind) {
		if (layer.getParent().size() <= 1) {
			Utils.showMessage("There is only one layer.");
			return null;
		}
		final GenericDialog gd = new GenericDialog("Choose first and last");
		gd.addMessage("Choose first and last layers to register:");
		Utils.addLayerRangeChoices(layer, gd); /// $#%! where are my lisp macros
		switch (kind) {
			case LAYER_SIFT:
				gd.addCheckbox("Propagate last transform: ", true);
				break;
			case GLOBAL_MINIMIZATION:
				gd.addCheckbox("Tiles are rougly registered: ", false);
				break;
		}
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		final int i_first = gd.getNextChoiceIndex();
		final int i_start = layer.getParent().indexOf(layer);
		final int i_last = gd.getNextChoiceIndex();
		final boolean option = gd.getNextBoolean();
		switch (kind) {
			case GLOBAL_MINIMIZATION:
				List<Layer> lla = layer.getParent().getLayers().subList(i_first, i_last +1);
				Layer[] la = new Layer[lla.size()];
				la = lla.toArray(la);
				return registerTilesSIFT(la, option);
			case LAYER_SIFT:
				return registerLayers(layer.getParent(), i_first, i_start, i_last, option);
		}
		return null;
	}

	/** Register a subset of consecutive layers of the LayerSet, starting at 'start' (which is unmodified)
	 *  and proceeding both towards first and towards last.
	 *  If @param propagate is true, the last transform is applied to all other subsequent, non-included layers .
	 *  @return The Bureaucrat thread in charge of the task, or null if the parameters are invalid.
	 */
	static public Bureaucrat registerLayers(final LayerSet layer_set, final int first, final int start, final int last, final boolean propagate) {
		// check preconditions
		if (null == layer_set || first > start || first > last || start > last || last >= layer_set.size()) {
			Utils.log2("Registration.registerLayers: invalid parameters: " + layer_set + ", first: " + first + ", start: " + start + ", last" + last);
			return null;
		}
		// outside the Worker thread, so that the dialog can be controled with Macro.setOptions
		// if the calling Thread's name starts with "Run$_"
		final Registration.SIFTParameters sp = new Registration.SIFTParameters(layer_set.getProject());
		if (!sp.setup()) return null;

		final Worker worker = new Worker("Registering stack slices") {
			public void run() {
				startedWorking();

		boolean massive_mode = layer_set.getProject().getLoader().getMassiveMode();
		layer_set.getProject().getLoader().setMassiveMode(true); // should be done with startLargeUpdate
		try {
			// build lists (Layers are consecutive)
			final List<Layer> list1 = new ArrayList<Layer>();
			list1.addAll(layer_set.getLayers().subList(first, start+1)); // endings are exclusive
			Collections.reverse(list1);
			final List<Layer> list2 = new ArrayList<Layer>();
			list2.addAll(layer_set.getLayers().subList(start, last+1)); // kludge because subList ends up removing stuff from the main Layer list!

			// remove empty layers
			for (Iterator it = list1.iterator(); it.hasNext(); ) {
				Layer la = (Layer)it.next();
				if (0 == la.getDisplayables(Patch.class).size()) {
					it.remove();
					Utils.log2("FORE: Removing from registration list layer with no images " + la.getProject().findLayerThing(la).getTitle() + " -- " + la);
				}
			}
			for (Iterator it = list2.iterator(); it.hasNext(); ) {
				Layer la = (Layer)it.next();
				if (0 == la.getDisplayables(Patch.class).size()) {
					it.remove();
					Utils.log2("AFT: Removing from registration list layer with no images " + la.getProject().findLayerThing(la).getTitle() + " -- " + la);
				}
			}
			// there must be some way to make inner anonymous methods or something to avoid duplicating code like this.


			// iterate in pairs
			final Layer layer_start = (Layer)list2.get(0); // even if there is only one element, list2 will contain the starting layer as the first element. Should be equivalent to layer_set.get(start)
			// check assumptions
			if (0 == layer_start.count(Patch.class)) {
				Utils.log("Registration of layers: ERROR: the starting layer is empty.");
				finishedWorking();
				return;
			}
			// prune empty layers (so they are ignored)
			checkLayerList(list1);
			checkLayerList(list2);

			if (list1.size() <= 1 && list2.size() <= 1) {
				finishedWorking();
				return;
			}

			// ensure proper snapshots
			layer_set.setMinimumDimensions();

			//
			final Rectangle box = layer_start.getMinimalBoundingBox(Patch.class);
			final ImagePlus imp = layer_start.getProject().getLoader().getFlatImage(layer_start, box, sp.scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
			final Object[] ob1 = processLayerList(list1, imp, box, sp, propagate, this);
			final Object[] ob2 = processLayerList(list2, imp, box, sp, propagate, this);

			// transfer the last affine transform to the remaining layers
			if (propagate) {
				if (0 != first || first != start) {
					if (null == ob1) {
						// can't propagate
						Utils.log2("Can't propagate towards the first layer in the layer set.");
					} else {
						// propagate from first towards zero
						AffineTransform at1 = (AffineTransform)ob1[0];
						for (Iterator it = layer_set.getLayers().subList(0, first).iterator(); it.hasNext(); ){
							// preconcatenate the transform to every Patch in the Layer
							((Layer)it.next()).apply(Patch.class, at1);
						}
					}
				}
				if (layer_set.size() -1 != last) {
					if (null == ob2) {
						// can't propagate
						Utils.log2("Can't propagate towards the last layer in the layer set");
					} else {
						AffineTransform at2 = (AffineTransform)ob2[0];
						// propagate towards the last slice
						for (Iterator it = layer_set.getLayers().subList(last+1, layer_set.size()).iterator(); it.hasNext(); ) {
							// preconcatenate the transform to every Patch in the Layer
							((Layer)it.next()).apply(Patch.class, at2);
						}
					}
				}
			}

			// trim and polish:
			layer_set.setMinimumDimensions();

		} catch (Exception e) {
			new IJError(e);
		}
		layer_set.getProject().getLoader().setMassiveMode(massive_mode);

				finishedWorking();
			}
		};
		// watcher thread
		final Bureaucrat burro = new Bureaucrat(worker, layer_set.getProject());
		burro.goHaveBreakfast();
		return burro;
	}
	static private void checkLayerList(final List list) {
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			Layer la = (Layer)it.next();
			if (0 == la.count(Patch.class)) it.remove();
		}
		// TODO: check that there aren't any elements linking any two consecutive layers together.
	}
	static private Object[] processLayerList(final List list, final ImagePlus imp_first, final Rectangle box_first, final Registration.SIFTParameters sp, final boolean propagate, final Worker worker) {
		// check preconditions
		if (list.size() <= 1 || worker.hasQuitted()) return null;
		//
		Object[] result = null;
		// if i == 1:
		result = registerSIFT((Layer)list.get(0), (Layer)list.get(1), new Object[]{imp_first, box_first, null, null}, sp);
		// else:
		for (int i=2; i<list.size(); i++) {
			if (worker.hasQuitted()) return null;
			final Layer la1 = (Layer)list.get(i-1);
			final Layer la2 = (Layer)list.get(i);
			result = registerSIFT(la1, la2, null, sp);
			// !@#$% TODO this needs fine-tuning
			la1.getProject().getLoader().releaseToFit(Loader.MIN_FREE_BYTES * 20);

			// debug: at least we get chunks done
			if (!ControlWindow.isGUIEnabled() && System.getProperty("user.name").equals("cardona")) {
				la1.getProject().save();
			}
		}

		//Loader.runGCAll();
		return result;
	}

	/** Makes a snapshot with the Patch objects in both layers at the given scale, and rotates/translates all Displayable elements in the second Layer relative to the first.
	 *
	 * @return the AffineTransform only for now. I will eventually figure out a way to do safe caching with no loss of precision, carrying along the AffineTransform.
	 */
	static public Object[] registerSIFT(final Layer layer1, final Layer layer2, Object[] cached, final Registration.SIFTParameters sp) {
		try {
			// prepare flat images for each layer
			Rectangle box1 = null;
			ImagePlus imp1 = null;
			Vector<Feature> fs1 = null;
			AffineTransform at_accum = null;
			if (null == cached) {
				Utils.log2( "cached is null in Registration.java:277" );
				box1 = layer1.getMinimalBoundingBox(Patch.class);
				Utils.log2( "layer1.getminimalBoundingBox succeeded with " + box1 );
				imp1 = layer1.getProject().getLoader().getFlatImage(layer1, box1, sp.scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
				Utils.log2( "layer1.getProject().getLoader().getFlatImage succeeded with " + imp1 );				
			} else {
				imp1 = (ImagePlus)cached[0];
				box1 = (Rectangle)cached[1];
				fs1 = (Vector<Feature>)cached[2];
				at_accum = (AffineTransform)cached[3];
			}
			Rectangle box2 = layer2.getMinimalBoundingBox(Patch.class);
			ImagePlus imp2 = layer2.getProject().getLoader().getFlatImage(layer2, box2, sp.scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);

			FloatProcessor fp1 = (FloatProcessor)imp1.getProcessor().convertToFloat();
			FloatProcessor fp2 = (FloatProcessor)imp2.getProcessor().convertToFloat();
			if (null == cached) { // created locally, flushed locally since there's no caching
				Loader.flush(imp1);
				imp1 = null;
			}
			Loader.flush(imp2); // WARNING this may have to be removed if caching is enabled
			imp2 = null;

			// ready to start
			Object[] result = Registration.registerSIFT(fp1, fp2, fs1, sp);

			if (null != result) {
				// use the returned AffineTransform to adjust all Patch objects in layer2
				// The transform is the same for a part as for the whole.
				// Since the flat image was done considering the tranforms of each tile,
				// the returned transform simply needs to be preconcatenated to the tile's:
				AffineTransform at = (AffineTransform)result[2];
				// correct for the difference in position of flat image boxes

				final AffineTransform atap = new AffineTransform();
				// so that 0,0 of each image is the same, which is what SIFT expects
				atap.translate(box1.x, box1.y);
				atap.concatenate(at);
				atap.translate(-box2.x, -box2.y);

				// apply accumulated transform
				if (null != at_accum) at.preConcatenate(at_accum);
				// preconcatenate the transform to every Patch in the Layer
				layer2.apply(Patch.class, atap);

				Utils.log2("Registered layer " + layer2 + " to " + layer1);
				
				Vector< PointMatch > inliers = ( Vector< PointMatch > )result[ 3 ];

				// cleanup
				result = null; // contains the fs1 and fs2
					// TODO the above is temporary; need to figure out how to properly cache

				return new Object[]{ atap, box1, box2, inliers };
			} else {
				// fall back to phase-correlation
				Utils.log2("Registration.registerSIFT for Layers: falling back to phase-correlation");
				Utils.log2("\t--- Not yet implemented");
			}

			// repaint the second Layer, if it is showing in any Display:
			// no need // Display.repaint(layer2, null, 0);

		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	/** Registers the second image relative to the first. Returns an array of:
	 * - the set of features for the first image
	 * - the set of features for the second image
	 * - the AffineTransform defining the registration of the second image relative to the first.
	 *
	 * The given @param fs1 may be null, in which case it will be generated from the first ImagePlus. It is here so that caching is possible.
	 * @param initial_sigma is adjustable, so that high magnification steps can be skipped for noisy or highly variable datasets, which show most similarity at coarser, lower magnification levels.
	 *
	 * Returns null if the model is not significant.
	 */
	static public Object[] registerSIFT(
			FloatProcessor ip1,
			FloatProcessor ip2,
			Vector < Feature > fs1,
			final Registration.SIFTParameters sp )
	{
		// ensure enough memory space (in bytes: area * 48 * 2)
		// times 2, ... !@#$%
		long size = 2 * (ip1.getWidth() * ip1.getHeight() * 48L + ip2.getWidth() * ip2.getHeight() * 48L);
		Utils.log2("size is: " + size);
		while (!sp.project.getLoader().releaseToFit(size) && size > 10000000) { // 10 Mb
			size = (size / 3) * 2; // if it fails, at least release as much as possible
			Utils.log2("size is: " + size);
			// size /= 1.5  was NOT failing at the compiler level! Uh?
		}
		// prepare both sets of features
		if (null == fs1) fs1 = getSIFTFeatures(ip1, sp);
		ip1 = null;
		//Loader.runGCAll(); // cleanup
		final Vector<Feature> fs2 = getSIFTFeatures( ip2, sp );
		// free all those temporary arrays
		ip2 = null;
		//Loader.runGCAll();
		// compare in the order that image2 should be moved relative to imag1
		final Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches( fs2, fs1, 1.5f, null, Float.MAX_VALUE );
		
		final Vector< PointMatch > inliers = new Vector< PointMatch >();

		TRModel2D model = TRModel2D.estimateBestModel(
				candidates,
				inliers,
				sp.min_epsilon,
				sp.max_epsilon,
				sp.min_inlier_ratio );
		
		final AffineTransform at = new AffineTransform();

		if (model != null) {
			// debug
			Utils.log2( "inliers: " + inliers.size() + "  corresp: " + candidates.size());
			// images may have different sizes
			/**
			 * TODO Different sizes are no problem as long as the top left
			 * corner is at the fixed position (x0:0.0f, y0:0.0f).  If this is not the
			 * case, translate the image relative to this position.  That is,
			 * translate the pivot point of the rotation and translate the translation
			 * vector itself.
			 */
			// rotation origin at the top left corner of the image (0.0f, 0.0f) of the image.
			AffineTransform at_current = new AffineTransform( model.getAffine() );
			double[] m = new double[ 6 ];
			at_current.getMatrix( m );
			m[ 4 ] /= sp.scale;
			m[ 5 ] /= sp.scale;
			at_current.setTransform( m[ 0 ], m[ 1 ], m[ 2 ], m[ 3 ], m[ 4 ], m[ 5 ] );
			at.concatenate( at_current );
		} else {
			Utils.log("No sufficient model found, keeping original transformation for " + ip2);
			return null;
		}

		return new Object[]{fs1, fs2, at, inliers};
	}

	/** Returns a sorted list of the SIFT features extracted from the given ImagePlus. */
	final static public Vector<Feature> getSIFTFeatures(ImageProcessor ip, final Registration.SIFTParameters sp) {
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D(ip.convertToFloat());
		ip = null; // enable GC
		ImageFilter.enhance( fa, 1.0f ); // done in place
		fa = ImageFilter.computeGaussianFastMirror(
				fa,
				(float)Math.sqrt(sp.initial_sigma * sp.initial_sigma - 0.25));
		final FloatArray2DSIFT sift = new FloatArray2DSIFT(sp.fdsize, sp.fdbins);
		sift.init(fa, sp.steps, sp.initial_sigma, sp.min_size, sp.max_size);
		fa = null; // enableGC
		final Vector<Feature> fs = sift.run(sp.max_size);
		Collections.sort(fs);
		return fs;
	}

	/** Will cross-correlate slices in a separate Thread; leaves the given slice untouched.
	 * @param base_slice is the reference slice from which the stack will be registered, i.e. it won't be affected in any way.
	 */
	static public Bureaucrat registerStackSlices(final Patch base_slice) {
		// find linked images in different layers and register them
		// 
		// setup parameters. Put outside the Worker so the dialog is controlable from a Macro.setOptions(...) if the Thread's name that calls this method starts with the string "Run$_"
		final Registration.SIFTParameters sp = new Registration.SIFTParameters(base_slice.getProject());
		if (!sp.setup()) {
			return null;
		}

		final Worker worker = new Worker("Registering stack slices") {
			public void run() {
				try {
					startedWorking();
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
	static private void correlateSlices(final Patch slice, final HashSet hs_done, final Worker worker, final Registration.SIFTParameters sp, final Vector<Feature> fs_slice) {
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
			Registration.correlateSlices(p, hs_done, worker, sp, null != result ? (Vector<Feature>)result[1] : null); // I give it the feature set of the moving patch, which in this call will serve as base
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
			result = registerWithSIFTLandmarks((Patch)al_chain.get(i-1), (Patch)al_chain.get(i), sp, null == result ? null : (Vector<Feature>)result[1]);
		}
	}

	/** The @param fs_base is the vector of features of the base Patch, and can be null -in which case it will be computed. */
	static public Object[] registerWithSIFTLandmarks(final Patch base, final Patch moving, final Registration.SIFTParameters sp, final Vector<Feature> fs_base) {

		Utils.log2("processing layer " + moving.getLayer().getParent().indexOf(moving.getLayer()));

		FloatProcessor fp1 = (FloatProcessor)StitchingTEM.makeStripe(base, sp.scale, true, true);
		FloatProcessor fp2 = (FloatProcessor)StitchingTEM.makeStripe(moving, sp.scale, true, true);

		final Object[] result = Registration.registerSIFT(fp1, fp2, fs_base, sp);

		// enable garbage collection!
		fp1 = null;
		fp2 = null;
		// no hope. The recursion prevents from lots of memory from ever being released.
		// MWAHAHA so I made a non-recursive smart-ass version.
		// It is somewhat disturbing that each SIFT match at max_size 1600 was using nearly 400 Mb, and all of them were NOT released because of the recursion.
		//Loader.runGCAll();
		//base.getProject().getLoader().releaseToFit(Loader.MIN_FREE_BYTES * 20);

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

	static public class SIFTParameters implements Serializable {
		transient Project project = null; // not in serialized object
		// filled with default values
		public float scale = 1.0f;
		public int steps = 3;
		public float initial_sigma = 1.6f;
		public int fdsize = 8;
		public int fdbins = 8;
		/** size restrictions for scale octaves, use octaves < max_size and > min_size only */
		public int min_size = 64;
		public int max_size = 1024;
		/** Maximal initial drift of landmark relative to its matching landmark in the other image, to consider when searching.  Also used as increment for epsilon when there was no sufficient model found.*/
		public float min_epsilon = 2.0f;
		public float max_epsilon = 100.0f;
		/** Minimal percent of good landmarks found */
		public float min_inlier_ratio = 0.05f;

		/** A message to show within the dialog, at the top, for information purposes. */
		public String msg = null;
		/** Minimal allowed alignment error in px (across sections) */
		public float cs_min_epsilon = 1.0f;
		/** Maximal allowed alignment error in px (across sections) */
		public float cs_max_epsilon = 50.0f;
		/** 0 means only translation, 1 means both translation and rotation. */
		public int dimension = 1;
		/** Whether to show options for cross-layer registration or not in the dialog. */
		public boolean cross_layer = false;
		public boolean layers_prealigned = false;

		/** Plain constructor for Serialization to work properly. */
		public SIFTParameters() {}

		public SIFTParameters(Project project) {
			this.project = project;
		}

		public SIFTParameters(Project project, String msg, boolean cross_layer) {
			this(project);
			this.msg = msg;
			this.cross_layer = cross_layer;
		}

		public void print() {
			Utils.log2(new StringBuffer("SIFTParameters:\n")
				   .append(null != msg ? "\tmsg:" + msg + "\n" : "")
				   .append("\tscale: ").append(scale).append('\n')
				   .append("\tsteps per scale octave: ").append(steps).append('\n')
				   .append("\tinitial gaussian blur: ").append(initial_sigma).append('\n') 
				   .append("\tfeature descriptor size: ").append(fdsize).append('\n')
				   .append("\tfeature descriptor orientation bins: ").append(fdbins).append('\n')
				   .append("\tminimum image size: ").append(min_size).append('\n')
				   .append("\tmaximum image size: ").append(max_size).append('\n')
				   .append("\tminimal alignment error: ").append(min_epsilon).append('\n')
				   .append("\tmaximal alignment error: ").append(max_epsilon).append('\n')
				   .append("\tminimal inlier ratio: ").append(min_inlier_ratio)
				   .toString());
		}

		public boolean setup() {
			final GenericDialog gd = new GenericDialog("Options");
			if (null != msg) gd.addMessage(msg);
			gd.addSlider("scale (%):", 1, 100, scale*100);
			gd.addNumericField("steps_per_scale_octave :", steps, 0);
			gd.addNumericField("initial_gaussian_blur :", initial_sigma, 2);
			gd.addNumericField("feature_descriptor_size :", fdsize, 0);
			gd.addNumericField("feature_descriptor_orientation_bins :", fdbins, 0);
			gd.addNumericField("minimum_image_size :", min_size, 0);
			gd.addNumericField("maximum_image_size :", max_size, 0);
			gd.addNumericField("minimal_alignment_error :", min_epsilon, 2);
			gd.addNumericField("maximal_alignment_error :", max_epsilon, 2);
			if (cross_layer) {
				gd.addNumericField("cs_min_epsilon :", cs_min_epsilon, 2);
				gd.addNumericField("cs_max_epsilon :", cs_max_epsilon, 2);
				gd.addCheckbox("layers_are_prealigned :", layers_prealigned);
			}
			gd.addNumericField("minimal_inlier_ratio :", min_inlier_ratio, 2);
			final String[] regtype = new String[]{"translation only", "translation and rotation"};
			gd.addChoice("registration_type :", regtype, regtype[dimension]);
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
			if (cross_layer) {
				this.cs_min_epsilon = (float)gd.getNextNumber();
				this.cs_max_epsilon = (float)gd.getNextNumber();
				this.layers_prealigned = gd.getNextBoolean();
			}
			this.min_inlier_ratio = (float)gd.getNextNumber();
			this.dimension = gd.getNextChoiceIndex();

			// debug:
			print();

			return true;
		}
		/** Returns the size in bytes of a Feature object. */
		public final long getFeatureObjectSize() {
			return FloatArray2DSIFT.getFeatureObjectSize(fdsize, fdbins);
		}
		/** Compare parameters relevant for creating features and return true if all coincide. */
		public final boolean sameFeatures(final Registration.SIFTParameters sp) {
			if (max_size == sp.max_size
			 && scale == sp.scale
			 && min_size == sp.min_size
			 && fdbins == sp.fdbins
			 && fdsize == sp.fdsize
			 && initial_sigma == sp.initial_sigma
			 && steps == sp.steps) {
				return true;
			}
			return false;
		}
	}

	static private void correlate(final Patch base, final Patch moving, final float scale) {
		Utils.log2("Correlating #" + moving.getId() + " to #" + base.getId());

		// test rotation first TODO

		final double[] pc = StitchingTEM.correlate(base, moving, 1f, scale, StitchingTEM.TOP_BOTTOM, 0, 0);
		if (pc[2] != StitchingTEM.SUCCESS) {
			// R is too low to be trusted
			Utils.log("Bad R coefficient, skipping " + moving);
			return; // don't move
		}
		Utils.log2("BASE: x, y " + base.getX() + " , " + base.getY() + "\n\t pc x,y: " + pc[0] + ", " + pc[1]);
		double x2 = base.getX() + pc[0];
		double y2 = base.getY() + pc[1];

		Rectangle box = moving.getBoundingBox();

		if (ControlWindow.isGUIEnabled()) {
			moving.translate(x2 - box.x, y2 - box.y); // considers links
			box.add(moving.getBoundingBox());
			Display.repaint(moving.getLayer(), box, 1);
		} else {
			moving.translate(x2 - box.x, y2 - box.y); // considers links
		}
		Utils.log("--- Done correlating target #" + moving.getId() + "  to base #" + base.getId());
	}

	/** Single-threaded: one layer after the other, to avoid memory saturation. The feature finding with SIFT is multithreaded.
	 * If position_as_hint is true, then each tile will only try to find feature correspondences
	 * with overlapping tiles.
	 *
	 * Assumes all layers belong to the same project, and are CONTINUOUS in Z space.
	 *
	 * Will show a dialog for SIFT parameters setup.
	 *
	 * Ported from Stephan Saalfeld's SIFT_Align_LayerSet.java plugin
	 *
	 */
	static public Bureaucrat registerTilesSIFT(final Layer[] layer, final boolean overlapping_only) {
		if (null == layer || 0 == layer.length) return null;

		final Worker worker_ = new Worker("Free tile registration") {
			public void run() {
				startedWorking();
				try {

		final Worker worker = this; // J jate java

		final LayerSet set = layer[0].getParent();

		final String[] dimensions = { "translation", "translation and rotation" };
		int dimension_ = 1;

		// Parameters for tile-to-tile registration
		final SIFTParameters sp = new SIFTParameters(set.getProject(), "Options for tile-to-tile registration", false);
		sp.steps = 3;
		sp.initial_sigma = 1.6f;
		sp.fdsize = 8;
		sp.fdbins = 8;
		sp.min_size = 64;
		sp.max_size = 512;
		sp.min_epsilon = 1.0f;
		sp.max_epsilon = 10.0f;
		sp.cs_min_epsilon = 1.0f;
		sp.cs_max_epsilon = 50.0f;
		sp.min_inlier_ratio = 0.05f;
		sp.scale = 1.0f;
		sp.layers_prealigned = overlapping_only;

		// Simple setup
		GenericDialog gds = new GenericDialog("Setup");
		gds.addNumericField("maximum_image_size :", sp.max_size, 0);
		gds.addNumericField("maximal_alignment_error :", sp.max_epsilon, 2);
		gds.addCheckbox("Layers_are_roughly_prealigned", sp.layers_prealigned);
		gds.addCheckbox("Advanced setup", false);
		gds.showDialog();
		if (gds.wasCanceled()) {
			finishedWorking();
			return;
		}
		sp.max_size = (int)gds.getNextNumber();
		sp.max_epsilon = (float)gds.getNextNumber();
		sp.layers_prealigned = gds.getNextBoolean();
		boolean advanced_setup = gds.getNextBoolean();

		if (advanced_setup) {
			if (!sp.setup()) {
				finishedWorking();
				return;
			}
		}

		// for inter-layer registration
		final Registration.SIFTParameters sp_gross_interlayer = new Registration.SIFTParameters(set.getProject(), "Options for coarse layer registration", true);
		if (advanced_setup) {
			if (!sp_gross_interlayer.setup()) {
				finishedWorking();
				return;
			}
		}

		// start:

		final ArrayList< Layer > layers = new ArrayList< Layer >();
		for (int k=0; k<layer.length; k++) {
			layers.add( layer[k] );
		}
		final ArrayList< Vector< Feature > > featureSets1 = new ArrayList< Vector< Feature > >();
		final ArrayList< Vector< Feature > > featureSets2 = new ArrayList< Vector< Feature > >();
		Vector<Feature>[] fsets1=null, fsets2=null;


		final ArrayList< Patch > patches1 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles1 = new ArrayList< Tile >();
		final ArrayList< Patch > patches2 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles2 = new ArrayList< Tile >();

		final ArrayList< Tile > all_tiles = new ArrayList< Tile >();
		final ArrayList< Patch > all_patches = new ArrayList< Patch >();

		final ArrayList< Tile > fixed_tiles = new ArrayList< Tile >();

		Layer previous_layer = null;

		// the storage folder for serialized features
		final Loader loader = set.getProject().getLoader();
		String storage_folder_ = loader.getStorageFolder() + "features.ser/";
		File sdir = new File(storage_folder_);
		if (!sdir.exists()) {
			try {
				sdir.mkdir();
			} catch (Exception e) {
				storage_folder_ = null; // can't store
			}
		}
		final String storage_folder = storage_folder_;


		for ( Layer layer : layers )
		{
			if (hasQuitted()) return;
			final ArrayList< Tile > layer_fixed_tiles = new ArrayList< Tile >();

			Utils.log( "###############\nStarting layer " + ( set.indexOf( layer ) + 1 ) + " of " + set.size() + "\n###############" );

			// ignore empty layers
			if ( !layer.contains( Patch.class ) )
			{
				Utils.log( "Ignoring empty layer." );
				continue;
			}

			if ( null != previous_layer )
			{
				featureSets1.clear();
				featureSets1.addAll( featureSets2 );
				fsets1 = fsets2;

				patches1.clear();
				patches1.addAll( patches2 );

				tiles1.clear();
				tiles1.addAll( tiles2 );
			}

			patches2.clear();
			featureSets2.clear();
			tiles2.clear();

			ArrayList tmp = new ArrayList();
			tmp.addAll(layer.getDisplayables( Patch.class ));
			patches2.addAll( tmp ); // I hate generics. Incovertible types? Not at all!

			// extract SIFT-features in all patches
			//  (multi threaded version)
			// "generic array creation" error ? //fsets2 = new Vector<Feature>[ patches2.size() ];
			fsets2 = (Vector<Feature>[])new Vector[ patches2.size() ];
			final Tile[] tls = new Tile[ fsets2.length ];
			Registration.generateTilesAndFeatures(patches2, tls, fsets2, sp, storage_folder, worker);

			if (hasQuitted()) return;

			//#################################################################


			for ( int k = 0; k < fsets2.length; k++ )
			{
				featureSets2.add( fsets2[ k ] );
				tiles2.add( tls[ k ] );
			}

			// identify correspondences and inspect connectivity
			Registration.connectTiles(patches2, tiles2, /*featureSets2*/ fsets2, sp, storage_folder, worker);

			// identify connected graphs
			ArrayList< ArrayList< Tile > > graphs = Tile.identifyConnectedGraphs( tiles2 );
			Utils.log2( graphs.size() + " graphs detected." );
			
			if ( sp.layers_prealigned && graphs.size() > 1 )
			{
				/**
				 * We have to trust the given alignment.  Try to add synthetic
				 * correspondences to disconnected graphs having overlapping
				 * tiles.
				 */
				Utils.log2( "Synthetically connecting graphs using the given alignment." );
				
				Registration.connectDisconnectedGraphs( graphs, tiles2, patches2 );
				
				/**
				 * check the connectivity graphs again.  Hopefully there is
				 * only one graph now.  If not, we still have to fix one tile
				 * per graph, regardless if it is only one or several oth them.
				 */
				graphs = Tile.identifyConnectedGraphs( tiles2 );
				Utils.log2( graphs.size() + " graphs detected after synthetic connection." );
			}
			
			// fix one tile per graph, meanwhile update the tiles
			layer_fixed_tiles.addAll(Registration.pickFixedTiles(graphs));

			// update all tiles, for error and distance correction
			for ( Tile tile : tiles2 ) tile.update();
			
			// optimize the pose of all tiles in the current layer
			Registration.minimizeAll( tiles2, patches2, layer_fixed_tiles, set, sp.max_epsilon, worker );
			
			// repaint all Displays showing a Layer of the edited LayerSet
			Display.update( set );

			// store for global minimization
			all_tiles.addAll( tiles2 );
			all_patches.addAll( patches2 );

			if ( null != previous_layer )
			{
				/**
				 *  1 - Coarse registration
				 * 
				 * TODO Think about re-using the correspondences identified
				 *  during coarse registration for the tiles.  That introduces
				 *  the following issues:
				 *  
				 *  - coordinate transfer of snapshot-coordinates to
				 *    layer-coordinates in both layers
				 *  - identification of the closest tiles in both layers
				 *    (whose centers are closest to the layer-coordinate of the
				 *    detection)
				 *  - appropriate weight for the correspondence
				 *  - if this is the sole correpondence of a tile, minimization
				 *    as well as model estimation of higher order models than
				 *    translation will fail because of missing information
				 *    -> How to handle this, how to handle this for
				 *       graph-connectivity?
				 */
				
				/**
				 * returns an Object[] with
				 *   [0] AffineTransform that transforms layer towards previous_layer
				 *   [1] bounding box of previous_layer in world coordinates
				 *   [2] bounding box of layer in world coordinates
				 *   [3] true correspondences with p1 in layer and p2 in previous_layer,
				 *       both in the local coordinate frames defined by box1 and box2 and
				 *       scaled with sp_gross_interlayer.scale
				 */
				Object[] ob = Registration.registerSIFT( previous_layer, layer, null, sp_gross_interlayer );
				int original_max_size = sp_gross_interlayer.max_size;
				float original_max_epsilon = sp_gross_interlayer.max_epsilon;
				while (null == ob || null == ob[0]) {
					int next_max_size = sp_gross_interlayer.max_size;
					float next_max_epsilon = sp_gross_interlayer.max_epsilon;
					// need to recurse up both the max size and the maximal alignment error
					if (next_max_epsilon < 300) {
						next_max_epsilon += 100;
					}
					Rectangle rfit1 = previous_layer.getMinimalBoundingBox(Patch.class);
					Rectangle rfit2 = layer.getMinimalBoundingBox(Patch.class);
					if (next_max_size < rfit1.width || next_max_size < rfit1.height
					 || next_max_size < rfit2.width || next_max_size < rfit2.height) {
						next_max_size += 1024;
					} else {
						// fail completely
						Utils.log2("FAILED to align layers " + set.indexOf(previous_layer) + " and " + set.indexOf(layer));
						// Need to fall back to totally unguided double-layer registration
						// TODO
						//
						break;
					}
					sp_gross_interlayer.max_size = next_max_size;
					sp_gross_interlayer.max_epsilon = next_max_epsilon;
					ob = Registration.registerSIFT(previous_layer, layer, null, sp_gross_interlayer);
				}
				// fix back modified parameters
				sp_gross_interlayer.max_size = original_max_size;
				sp_gross_interlayer.max_epsilon = original_max_epsilon;

				if ( null != ob && null != ob[ 0 ] )
				{
					// defensive programming ... ;)
					AffineTransform at = ( AffineTransform )ob[ 0 ];
					Rectangle previous_layer_box = ( Rectangle )ob[ 1 ];
					Rectangle layer_box = ( Rectangle )ob[ 2 ];
					Vector< PointMatch > inliers = ( Vector< PointMatch > )ob[ 3 ];
					
					/**
					 * Find the closest tiles in both layers for each of the
					 * inliers and append a correponding nail to it
					 */
					for ( PointMatch inlier : inliers )
					{
						// transfer the coordinates to actual world coordinates 
						float[] previous_layer_coords = inlier.getP2().getL();
						previous_layer_coords[ 0 ] = previous_layer_coords[ 0 ] / sp_gross_interlayer.scale + previous_layer_box.x;
						previous_layer_coords[ 1 ] = previous_layer_coords[ 1 ] / sp_gross_interlayer.scale + previous_layer_box.y;
						
						float[] layer_coords = inlier.getP1().getL();
						layer_coords[ 0 ] = layer_coords[ 0 ] / sp_gross_interlayer.scale + layer_box.x;
						layer_coords[ 1 ] = layer_coords[ 1 ] / sp_gross_interlayer.scale + layer_box.y;
						
						// find the tile whose center is closest to the points in previous_layer
						Tile previous_layer_closest_tile = null;
						float previous_layer_min_d = Float.MAX_VALUE;
						for ( Tile tile : tiles1 )
						{
							tile.update();
							float[] tw = tile.getWC();
							float dx = tw[ 0 ] - previous_layer_coords[ 0 ];
							dx *= dx;
							float dy = tw[ 1 ] - previous_layer_coords[ 1 ];
							dy *= dy;
							
							float d = ( float )Math.sqrt( dx + dy );
							if ( d < previous_layer_min_d )
							{
								previous_layer_min_d = d;
								previous_layer_closest_tile = tile;
							}
						}
						
						Utils.log2( "Tile " + tiles1.indexOf( previous_layer_closest_tile ) + " is closest in previous layer:" );
						Utils.log2( "  distance: " + previous_layer_min_d );
						
						
						// find the tile whose center is closest to the points in layer
						Tile layer_closest_tile = null;
						float layer_min_d = Float.MAX_VALUE;
						for ( Tile tile : tiles2 )
						{
							tile.update();
							float[] tw = tile.getWC();
							float dx = tw[ 0 ] - layer_coords[ 0 ];
							dx *= dx;
							float dy = tw[ 1 ] - layer_coords[ 1 ];
							dy *= dy;
							
							float d = ( float )Math.sqrt( dx + dy );
							if ( d < layer_min_d )
							{
								layer_min_d = d;
								layer_closest_tile = tile;
							}
						}
						
						Utils.log2( "Tile " + tiles2.indexOf( layer_closest_tile ) + " is closest in layer:" );
						Utils.log2( "  distance: " + layer_min_d );
						
						if ( previous_layer_closest_tile != null && layer_closest_tile != null )
						{
//							Utils.log2( "world coordinates in previous layer: " + previous_layer_coords[ 0 ] + ", " + previous_layer_coords[ 1 ] );
//							Utils.log2( "world coordinates in layer: " + layer_coords[ 0 ] + ", " + layer_coords[ 1 ] );
							
							// transfer the world coordinates to local tile coordinates
							previous_layer_closest_tile.getModel().applyInverseInPlace( previous_layer_coords );
							layer_closest_tile.getModel().applyInverseInPlace( layer_coords );
							
//							Utils.log2( "local coordinates in previous layer: " + previous_layer_coords[ 0 ] + ", " + previous_layer_coords[ 1 ] );
//							Utils.log2( "local coordinates in layer: " + layer_coords[ 0 ] + ", " + layer_coords[ 1 ] );
							
							// create PointMatch for both tiles
							mpi.fruitfly.registration.Point previous_layer_point = new mpi.fruitfly.registration.Point( previous_layer_coords );
							mpi.fruitfly.registration.Point layer_point = new mpi.fruitfly.registration.Point( layer_coords );
							
							previous_layer_closest_tile.addMatch(
									new PointMatch(
											previous_layer_point,
											layer_point,
											inlier.getWeight() / sp_gross_interlayer.scale ) );
							layer_closest_tile.addMatch(
									new PointMatch(
											layer_point,
											previous_layer_point,
											inlier.getWeight() / sp_gross_interlayer.scale ) );
							
							previous_layer_closest_tile.addConnectedTile( layer_closest_tile );
							layer_closest_tile.addConnectedTile( previous_layer_closest_tile );
						}
					}
					
					TRModel2D model = new TRModel2D();
					model.getAffine().setTransform( at );
					for ( Tile tile : tiles2 )
						( ( TRModel2D )tile.getModel() ).preConcatenate( model );
				
					// repaint all Displays showing a Layer of the edited LayerSet
					Display.update( layer );
				}
				Registration.identifyCrossLayerCorrespondences(
						tiles1,
						patches1,
						fsets1 /*featureSets1*/,
						tiles2,
						patches2,
						fsets2 /*featureSets2*/,
						( null != ob && null != ob[ 0 ] ),
						sp,
						storage_folder,
						worker);
				
				// check the connectivity graphs
				ArrayList< Tile > both_layer_tiles = new ArrayList< Tile >();
				both_layer_tiles.addAll( tiles1 );
				both_layer_tiles.addAll( tiles2 );
				graphs = Tile.identifyConnectedGraphs( both_layer_tiles );
				
				Utils.log2( graphs.size() + " cross-section graphs detected." );
				
//				if ( graphs.size() > 1 && ( null != ob && null != ob[ 0 ] ) )
//				{
//					/**
//					 * We have to trust the given alignment.  Try to add synthetic
//					 * correspondences to disconnected graphs having overlapping
//					 * tiles.
//					 */
//					ArrayList< Patch > both_layer_patches = new ArrayList< Patch >();
//					both_layer_patches.addAll( patches1 );
//					both_layer_patches.addAll( patches2 );
//					this.connectDisconnectedGraphs( graphs, both_layer_tiles, both_layer_patches );
//					
//					/**
//					 * check the connectivity graphs again.  Hopefully there is
//					 * only one graph now.  If not, we still have to fix one tile
//					 * per graph, regardless if it is only one or several of them.
//					 */
//					graphs = Tile.identifyConnectedGraphs( tiles2 );
//					Utils.log2( graphs.size() + " cross-section graphs detected after synthetic connection." );
//				}
			}

			previous_layer = layer;
		}
	
		// find the global nail
		/**
		 * One tile per connected graph has to be fixed to make the problem
		 * solvable, otherwise it is ill defined and has an infinite number
		 * of solutions.
		 */
		
		ArrayList< ArrayList< Tile > > graphs = Tile.identifyConnectedGraphs( all_tiles );
		Utils.log2( graphs.size() + " global graphs detected." );
		
		fixed_tiles.clear();
		// fix one tile per graph, meanwhile update the tiles
		fixed_tiles.addAll(Registration.pickFixedTiles(graphs));

		// again, for error and distance correction
		for ( Tile tile : all_tiles ) tile.update();

		// global minimization
		Registration.minimizeAll( all_tiles, all_patches, fixed_tiles, set, sp.cs_max_epsilon, worker );

		// update selection internals in all open Displays
		Display.updateSelection( Display.getFront() );

		// repaint all Displays showing a Layer of the edited LayerSet
		Display.update( set );


				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					finishedWorking();
				}
			}};

		Bureaucrat burro = new Bureaucrat(worker_, layer[0].getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	/**
	 * Interconnect disconnected graphs with synthetically added correspondences.
	 * This implies the tiles to be prealigned.
	 * 
	 * May fail if the disconnected graphs do not overlap at all.
	 * 
	 * @param graphs
	 * @param tiles
	 * @param patches
	 */
	static private final void connectDisconnectedGraphs(
			final List< ArrayList< Tile > > graphs,
			final List< Tile > tiles,
			final List< Patch > patches )
	{
		/**
		 * We have to trust the given alignment.  Try to add synthetic
		 * correspondences to disconnected graphs having overlapping
		 * tiles.
		 */
		Utils.log2( "Synthetically connecting graphs using the given alignment." );
		
		ArrayList< Tile > empty_tiles = new ArrayList< Tile >();
		for ( ArrayList< Tile > graph : graphs )
		{
			if ( graph.size() == 1 )
			{
				/**
				 *  This is a single unconnected tile.
				 */
				empty_tiles.add( graph.get( 0 ) );
			}
		}
		for ( ArrayList< Tile > graph : graphs )
		{
			for ( Tile tile : graph )
			{
				boolean is_empty = empty_tiles.contains( tile );
				Patch patch = patches.get( tiles.indexOf( tile ) );
				final Rectangle r = patch.getBoundingBox();
				// check this patch against each patch of the other graphs
				for ( ArrayList< Tile > other_graph : graphs )
				{
					if ( other_graph.equals( graph ) ) continue;
					for ( Tile other_tile : other_graph )
					{
						Patch other_patch = patches.get( tiles.indexOf( other_tile ) );
						if ( patch.intersects( other_patch ) )
						{
							/**
							 * TODO get a proper intersection polygon instead
							 *   of the intersection of bounding boxes.
							 *   
							 *   - Where to add the faked nails then?
							 */
							final Rectangle rp = other_patch.getBoundingBox().intersection( r );
							int xp1 = rp.x;
							int yp1 = rp.y;
							int xp2 = rp.x + rp.width;
							int yp2 = rp.y + rp.height;
							Point2D.Double dcp1 = patch.inverseTransformPoint( xp1, yp1 );
							Point2D.Double dcp2 = patch.inverseTransformPoint( xp2, yp2 );
							Point2D.Double dp1 = other_patch.inverseTransformPoint( xp1, yp1 );
							Point2D.Double dp2 = other_patch.inverseTransformPoint( xp2, yp2 );
							mpi.fruitfly.registration.Point cp1 = new mpi.fruitfly.registration.Point(
									new float[]{ ( float )dcp1.x, ( float )dcp1.y } );
							mpi.fruitfly.registration.Point cp2 = new mpi.fruitfly.registration.Point(
									new float[]{ ( float )dcp2.x, ( float )dcp2.y } );
							mpi.fruitfly.registration.Point p1 = new mpi.fruitfly.registration.Point(
									new float[]{ ( float )dp1.x, ( float )dp1.y } );
							mpi.fruitfly.registration.Point p2 = new mpi.fruitfly.registration.Point(
									new float[]{ ( float )dp2.x, ( float )dp2.y } );
							ArrayList< PointMatch > a1 = new ArrayList<PointMatch>();
							a1.add( new PointMatch( cp1, p1, 1.0f ) );
							a1.add( new PointMatch( cp2, p2, 1.0f ) );
							tile.addMatches( a1 );
							ArrayList< PointMatch > a2 = new ArrayList<PointMatch>();
							if ( is_empty )
							{
								/**
								 * very low weight instead of 0.0
								 * 
								 * TODO nothing could lead to disconntected graphs that were
								 *   connected by one empty tile only...
								 */
								a2.add( new PointMatch( p1, cp1, 0.1f ) );
								a2.add( new PointMatch( p2, cp2, 0.1f ) );
							}
							else
							{
								a2.add( new PointMatch( p1, cp1, 1.0f ) );
								a2.add( new PointMatch( p2, cp2, 1.0f ) );
							}
							other_tile.addMatches( a2 );
							
							// and tell them that they are connected now
							tile.addConnectedTile( other_tile );
							other_tile.addConnectedTile( tile );
						}
					}
				}
			}							
		}
	}

	static private class TrendObserver
	{
		public int i;			// iteration
		public double v;		// value
		public double d;		// first derivative
		public double m;		// mean
		public double var;		// variance
		public double std;		// standard-deviation
		
		public void add( double new_value )
		{
			if ( i == 0 )
			{
				i = 1;
				v = new_value;
				d = 0.0;
				m = v;
				var = 0.0;
				std = 0.0;
			}
			else
			{
				d = new_value - v;
				v = new_value;
				m = ( v + m * ( double )i++ ) / ( double )i;
				double tmp = v - m;
				var += tmp * tmp / ( double )i;
				std = Math.sqrt( var );
			}
		}
	}

	/**
	 * minimize the overall displacement of a set of tiles, propagate the
	 * estimated models to a corresponding set of patches and redraw
	 * 
	 * @param tiles
	 * @param patches
	 * @param fixed_tiles do not touch these tiles
	 * @param update_this
	 * 
	 * TODO revise convergence check
	 *   particularly for unguided minimization, it is hard to identify
	 *   convergence due to presence of local minima
	 *   
	 *   Johannes Schindelin suggested to start from a good guess, which is
	 *   e.g. the propagated unoptimized pose of a tile relative to its
	 *   connected tile that was already identified during RANSAC
	 *   correpondence check.  Thank you, Johannes, great hint!
	 */
	static private final void minimizeAll(
			final List< Tile > tiles,
			final List< Patch > patches,
			final List< Tile > fixed_tiles,
			final LayerSet set,
			float max_error,
			Worker worker)
	{
		int num_patches = patches.size();

		double od = Double.MAX_VALUE;
		double dd = Double.MAX_VALUE;
		double min_d = Double.MAX_VALUE;
		double max_d = Double.MIN_VALUE;
		int iteration = 1;
		int cc = 0;
		double[] dall = new double[100];
		int next = 0;
		// debug:
		//TrendObserver observer = new TrendObserver();
		
		while ( next < 100000 )  // safety check
		{
			if (worker.hasQuitted()) return;
			for ( int i = 0; i < num_patches; ++i )
			{
				Tile tile = tiles.get( i );
				if ( fixed_tiles.contains( tile ) ) continue;
				tile.update();
				tile.minimizeModel();
				tile.update();
				patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
			}
			double cd = 0.0;
			min_d = Double.MAX_VALUE;
			max_d = Double.MIN_VALUE;
			for ( Tile t : tiles )
			{
				t.update();
				double d = t.getDistance();
				if ( d < min_d ) min_d = d;
				if ( d > max_d ) max_d = d;
				cd += d;
			}
			cd /= tiles.size();
			dd = Math.abs( od - cd );
			od = cd;
			if (0 == next % 100) Utils.showStatus( "displacement: " + Utils.cutNumber( od, 3) + " [" + Utils.cutNumber( min_d, 3 ) + "; " + Utils.cutNumber( max_d, 3 ) + "] after " + iteration + " iterations");
			
			//observer.add( od );			
			//Utils.log( observer.i + " " + observer.v + " " + observer.d + " " + observer.m + " " + observer.std );
			
			//cc = d < 0.00025 ? cc + 1 : 0;
			cc = dd < 0.001 ? cc + 1 : 0;

			if (dall.length  == next) {
				double[] dall2 = new double[dall.length + 100];
				System.arraycopy(dall, 0, dall2, 0, dall.length);
				dall = dall2;
			}
			dall[next++] = dd;
			
			// cut the last 'n'
			if (next > 100) { // wait until completing at least 'n' iterations
				double[] dn = new double[100];
				System.arraycopy(dall, dall.length - 100, dn, 0, 100);
				// fit curve
				double[] ft = FitLine.fitLine(dn);
				// ft[1] StdDev
				// ft[2] m (slope)
				//if ( Math.abs( ft[ 1 ] ) < 0.001 )

				// TODO revise convergence check or start from better guesses
				if ( od < max_error && ft[ 2 ] >= 0.0 )
				{
					Utils.log2( "Exiting at iteration " + next + " with slope " + Utils.cutNumber( ft[ 2 ], 3 ) );
					break;
				}

			}

			if (0 == next % 100) Display.update( set );

			++iteration;
		}

		Display.update( set );
		
		Utils.log( "Successfully optimized configuration of " + tiles.size() + " tiles:" );
		Utils.log( "  average displacement: " + Utils.cutNumber( od, 3 ) + "px" );
		Utils.log( "  minimal displacement: " + Utils.cutNumber( min_d, 3 ) + "px" );
		Utils.log( "  maximal displacement: " + Utils.cutNumber( max_d, 3 ) + "px" );
	}

	/**
	 * Identify point correspondences from two sets of tiles, patches and SIFT-features.
	 * 
	 * @note List< List< Feature > > should work but doesn't.
	 *  Java "generics" are the crappiest bullshit I have ever seen.
	 *  They should hire a linguist!
	 * 
	 * @param tiles1
	 * @param patches1
	 * @param tiles2
	 * @param patches2
	 */
	static private final void identifyCrossLayerCorrespondences(
			List< Tile > tiles1,
			List< Patch > patches1,
			Vector<Feature>[] fsets1, //List< Vector< Feature > > featureSets1,
			List< Tile > tiles2,
			List< Patch > patches2,
			Vector<Feature>[] fsets2, //List< Vector< Feature > > featureSets2,
			boolean is_prealigned,
			final SIFTParameters sp,
			final String storage_folder,
			Worker worker)
	{
		int num_patches2 = patches2.size();
		int num_patches1 = patches1.size();
		for ( int i = 0; i < num_patches2; ++i )
		{
			if (worker.hasQuitted()) return;
			final Patch current_patch = patches2.get( i );
			final Tile current_tile = tiles2.get( i );
			final Vector<Feature> fsi = (null == fsets2[i] ? Registration.deserialize(current_patch, storage_folder, sp) : fsets2[i]);
			for ( int j = 0; j < num_patches1; ++j )
			{
				if (worker.hasQuitted()) return;
				Patch other_patch = patches1.get( j );
				Tile other_tile = tiles1.get( j );
				if ( !is_prealigned || current_patch.intersects( other_patch ) )
				{
					long start_time = System.currentTimeMillis();
					System.out.print( "identifying correspondences using brute force ..." );
					Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches(
								fsi, //featureSets2.get( i ),
								(null == fsets1[j] ? Registration.deserialize(other_patch, storage_folder, sp) : fsets1[j]), //featureSets1.get( j ),
								1.25f,
								null,
								Float.MAX_VALUE );
					Utils.log2( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
					
					Utils.log( "Tiles " + i + " and " + j + " have " + candidates.size() + " potentially corresponding features." );
					
					final Vector< PointMatch > inliers = new Vector< PointMatch >();
					
					TRModel2D mo = TRModel2D.estimateBestModel(
							candidates,
							inliers,
							sp.cs_min_epsilon,
							sp.cs_max_epsilon,
							sp.min_inlier_ratio);

					if ( mo != null )
					{
						Utils.log( inliers.size() + " of them are good." );
						current_tile.connect( other_tile, inliers );								
					}
					else
					{
						Utils.log( "None of them is good." );
					}
				}
			}
		}
	}

	/** Generates a Tile and a Vector of Features for each given patch, and puts them into the given arrays,
	 *  in the same order.
	 *  Multithreaded, runs in as many available CPU cores as possible.
	 *  Will write to fsets only in the event that features cannot be serialized to disk.
	 */
	static private void generateTilesAndFeatures(final ArrayList<Patch> patches, final Tile[] tls, final Vector[] fsets, final SIFTParameters sp, final String storage_folder, final Worker worker) {
		final Thread[] threads = MultiThreading.newThreads();
		// roughly, we expect about 1000 features per 512x512 image
		final long feature_size = (long)((sp.max_size * sp.max_size) / (512 * 512) * 1000 * FloatArray2DSIFT.getFeatureObjectSize(sp.fdsize, sp.fdbins) * 1.5);
		final AtomicInteger ai = new AtomicInteger( 0 ); // from 0 to patches2.length
		final int num_pa = patches.size();
		final Patch[] pa = new Patch[ num_pa ];
		patches.toArray( pa );

		final Loader loader = pa[0].getProject().getLoader();

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			final int si = ithread;
			threads[ ithread ] = new Thread(new Runnable() {
				public void run() {
					final FloatArray2DSIFT sift = new FloatArray2DSIFT(sp.fdsize, sp.fdbins);
					for (int k = ai.getAndIncrement(); k < num_pa; k = ai.getAndIncrement()) {
						if (worker.hasQuitted()) return;
						//
						final Patch patch = pa[k];
						// Extract features
						Vector<Feature> fs = Registration.deserialize(patch, storage_folder, sp);
						if (null == fs) {
							final ImageProcessor ip = patch.getImageProcessor();
							FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D(ip.convertToByte(true));
							loader.releaseToFit(ip.getWidth() * ip.getHeight() * 96L + feature_size);
							ImageFilter.enhance(fa, 1.0f);
							fa = ImageFilter.computeGaussianFastMirror(fa, (float)Math.sqrt(sp.initial_sigma * sp.initial_sigma - 0.25));
							sift.init(fa, sp.steps, sp.initial_sigma, sp.min_size, sp.max_size);
							fs = sift.run(sp.max_size);
							Collections.sort(fs);
							// store in the array only if serialization fails, such as when impossible to write to disk
							if (!Registration.serialize(patch, fs, sp, storage_folder)) {
								fsets[k] = fs;
							} else fsets[k] = null;
						} else {
							// don't store
							fsets[k] = null;
						}
						Utils.log2(fs.size() + " features");
						// Create Tile
						Model model;
						if (0 == sp.dimension) model = new TModel2D(); // translation only
						else model = new TRModel2D(); // both translation and rotation
						model.getAffine().setTransform(patch.getAffineTransform());
						tls[k] = new Tile((float)patch.getWidth(), (float)patch.getHeight(), model);

					}
				}
			});
		}
		MultiThreading.startAndJoin(threads);
	}

	/** Test all to all or all to overlapping only, and make appropriate connections between tiles. */
	static private void connectTiles(final ArrayList<Patch> patches, final ArrayList<Tile> tiles, Vector<Feature>[] fsets, final SIFTParameters sp, final String storage_folder, final Worker worker) {
		// TODO: multithread this, but careful to synchronize current_tile.connect method
		final int num_patches = patches.size();
		for ( int i = 0; i < num_patches; ++i )
		{
			if (worker.hasQuitted()) return;
			final Patch current_patch = patches.get( i );
			final Tile current_tile = tiles.get( i );
			final Vector<Feature> fsi = (null == fsets[i] ? Registration.deserialize(current_patch, storage_folder, sp) : fsets[i]);
			for ( int j = i + 1; j < num_patches; ++j )
			{
				if (worker.hasQuitted()) return;
				final Patch other_patch = patches.get( j );
				final Tile other_tile = tiles.get( j );
				if ( !sp.layers_prealigned || current_patch.intersects( other_patch ) )
				{
					long start_time = System.currentTimeMillis();
					System.out.print( "Tiles " + i + " and " + j + ": identifying correspondences using brute force ..." );
					Vector< PointMatch > correspondences = FloatArray2DSIFT.createMatches(
								fsi, // featureSets.get( i ),
								(null == fsets[j] ? Registration.deserialize(other_patch, storage_folder, sp) : fsets[j]), //featureSets.get( j ),
								1.25f,
								null,
								Float.MAX_VALUE );
					Utils.log2( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
					
					Utils.log( "Tiles " + i + " and " + j + " have " + correspondences.size() + " potentially corresponding features." );
					
					final Vector< PointMatch > inliers = new Vector< PointMatch >();

					TRModel2D model = TRModel2D.estimateBestModel(
							correspondences,
							inliers,
							sp.min_epsilon,
							sp.max_epsilon,
							sp.min_inlier_ratio );
					
					if ( model != null ) // that implies that inliers is not empty
						current_tile.connect( other_tile, inliers );
				}
			}
		}
	}

	/** Will find a fixed tile for each graph, and Will also update each tile.
	 *  Returns the array of fixed tiles, at one per graph.
	 */
	static private ArrayList<Tile> pickFixedTiles(ArrayList<ArrayList<Tile>> graphs) {
		final ArrayList<Tile> fixed_tiles = new ArrayList<Tile>();
		// fix one tile per graph, meanwhile update the tiles
		for (ArrayList<Tile> graph : graphs) {
			Tile fixed = null;
			int max_num_matches = 0;
			for (Tile tile : graph) {
				tile.update();
				int num_matches = tile.getNumMatches();
				if (max_num_matches < num_matches) {
					max_num_matches = num_matches;
					fixed = tile;
				}
			}
			fixed_tiles.add(fixed);
		}
		return fixed_tiles;
	}

	/** Freely register all-to-all the given set of patches; optionally provide a fixed Patch. */
	static public Bureaucrat registerTilesSIFT(final HashSet<Patch> hs_patches, final Patch fixed) {
		if (null == hs_patches || hs_patches.size() < 2) return null;

		final LayerSet set = hs_patches.iterator().next().getLayerSet();
		final Worker worker_ = new Worker("Free tile registration") {
			public void run() {
				startedWorking();
				try {
		//////
		final Worker worker = this; // J jate java
		final SIFTParameters sp = new SIFTParameters(set.getProject());
		if (!sp.setup()) {
			finishedWorking();
			return;
		}

		// the storage folder for serialized features
		String storage_folder = set.getProject().getLoader().getStorageFolder() + "features.ser/";
		File sdir = new File(storage_folder);
		if (!sdir.exists()) {
			try {
				sdir.mkdir();
			} catch (Exception e) {
				storage_folder = null; // can't store
			}
		}

		// java noise: filling datastructures
		final ArrayList<Patch> patches = new ArrayList<Patch>();
		patches.addAll(hs_patches);
		Tile[] tls = new Tile[patches.size()];
		Vector[] fsets = new Vector[tls.length];

		Registration.generateTilesAndFeatures(patches, tls, fsets, sp, storage_folder, worker);

		// java noise: filling datastructures
		final ArrayList<Tile> tiles = new ArrayList<Tile>();
		//ArrayList<Vector<Feature>> featureSets = new ArrayList<Vector<Feature>>();
		for (int k=0; k<tls.length; k++) {
			tiles.add(tls[k]);
			//featureSets.add(fsets[k]);
		}

		Registration.connectTiles(patches, tiles, /*featureSets*/ fsets, sp, storage_folder, worker);

		//featureSets = null;

		ArrayList<ArrayList<Tile>> graphs = Tile.identifyConnectedGraphs(tiles);
		Utils.log2(graphs.size() + " graphs detected.");

		final ArrayList<Tile> fixed_tiles = new ArrayList<Tile>();
		int i = patches.indexOf(fixed);

		if (null != fixed && -1 != i && 1 == graphs.size()) {
			fixed_tiles.add(tls[i]);
		} else {
			// find one tile per graph to nail down
			fixed_tiles.addAll(Registration.pickFixedTiles(graphs));
		}

		// again, for error and distance correction
		for ( Tile tile : tiles ) tile.update();

		// global minimization
		Registration.minimizeAll(tiles, patches, fixed_tiles, patches.get(0).getLayerSet(), sp.cs_max_epsilon, worker);

		//////
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					finishedWorking();
				}
			}};

		Bureaucrat burro = new Bureaucrat(worker_, set.getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	/** A tuple. */
	static final class Features implements Serializable {
		Registration.SIFTParameters sp;
		Vector<Feature> v;
		Features(final Registration.SIFTParameters sp, final Vector<Feature> v) {
			this.sp = sp;
			this.v = v;
		}
	}

	static private boolean serialize(final Patch p, final Vector<Feature> v, final Registration.SIFTParameters sp, final String storage_folder) {
		if (null == storage_folder) return false;
		final Features fe = new Features(sp, v);
		return p.getProject().getLoader().serialize(fe, new StringBuffer(storage_folder).append("features_").append(p.getId()).append(".ser").toString());
	}

	/** Retrieve the features only if saved with the exact same relevant SIFT parameters. */
	static private Vector<Feature> deserialize(final Patch p, final String storage_folder, final Registration.SIFTParameters sp) {
		if (null == storage_folder) return null;
		final Object ob = p.getProject().getLoader().deserialize(new StringBuffer(storage_folder).append("features_").append(p.getId()).append(".ser").toString());
		if (null != ob) {
			try {
				final Features fe = (Features)ob;
				if (sp.sameFeatures(fe.sp) && null != fe.v) {
					return fe.v;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}

}
