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
import java.awt.Rectangle;
import java.util.*;
import java.awt.geom.AffineTransform;

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
		gd.addCheckbox("Propagate last transform: ", true);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		final int i_first = gd.getNextChoiceIndex();
		final int i_start = layer.getParent().indexOf(layer);
		final int i_last = gd.getNextChoiceIndex();
		final boolean propagate = gd.getNextBoolean();
		switch (kind) {
			case GLOBAL_MINIMIZATION:
				// TODO
				break;
			case LAYER_SIFT:
				return registerLayers(layer.getParent(), i_first, i_start, i_last, propagate);
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
				System.out.println( "cached is null in Registration.java:277" );
				box1 = layer1.getMinimalBoundingBox(Patch.class);
				System.out.println( "layer1.getminimalBoundingBox succeeded with " + box1 );
				imp1 = layer1.getProject().getLoader().getFlatImage(layer1, box1, sp.scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
				System.out.println( "layer1.getProject().getLoader().getFlatImage succeeded with " + imp1 );				
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

	static public class SIFTParameters {
		Project project = null;
		// filled with default values
		public float scale = 1.0f;
		public int steps = 3;
		public float initial_sigma = 1.6f;
		public int fdsize = 8;
		public int fdbins = 8;
		public int min_size = 64;
		public int max_size = 1024;
		/** Maximal initial drift of landmark relative to its matching landmark in the other image, to consider when searching.  Also used as increment for epsilon when there was no sufficient model found.*/
		public float min_epsilon = 2.0f;
		public float max_epsilon = 100.0f;
		/** Minimal percent of good landmarks found */
		public float min_inlier_ratio = 0.05f;

		public SIFTParameters(Project project) {
			this.project = project;
		}

		public void print() {
			Utils.log2(new StringBuffer("SIFTParameters:\n")
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
			gd.addSlider("scale (%):", 1, 100, scale*100);
			gd.addNumericField("steps_per_scale_octave :", steps, 0);
			gd.addNumericField("initial_gaussian_blur :", initial_sigma, 2);
			gd.addNumericField("feature_descriptor_size :", fdsize, 0);
			gd.addNumericField("feature_descriptor_orientation_bins :", fdbins, 0);
			gd.addNumericField("minimum_image_size :", min_size, 0);
			gd.addNumericField("maximum_image_size :", max_size, 0);
			gd.addNumericField("minimal_alignment_error :", min_epsilon, 2);
			gd.addNumericField("maximal_alignment_error :", max_epsilon, 2);
			gd.addNumericField("minimal_inlier_ratio :", min_inlier_ratio, 2);
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
			this.min_inlier_ratio = (float)gd.getNextNumber();

			// debug:
			print();

			return true;
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
	 * NOT IMPLEMENTED YET (aka exists as a plugin only, needs porting)
	 *
	 */
	static public void registerTilesSIFT(final Layer[] layer, final boolean overlapping_only) {
		// for each layer
		//    1 - find potential tile correspondences (all, or overlapping_only)
		//        - if no matches, then
		//            - if overlapping_only, preserve relative position to neighboring tiles
		//            - else ? (Discard tile somehow)
		//    2 - apply optimizer
		Utils.log("registerTilesSIFT: not implemented yet.");
	}
}
