/**
 *
 */
package lenscorrection;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.gui.GenericDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import lenscorrection.Distortion_Correction.BasicParam;
import lenscorrection.Distortion_Correction.PointMatchCollectionAndAffine;
import mpicbg.ij.SIFT;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.trakem2.align.AbstractAffineTile2D;
import mpicbg.trakem2.align.Align;
import mpicbg.trakem2.align.RegularizedAffineLayerAlignment.Param;
import mpicbg.trakem2.transform.CoordinateTransform;

/**
 * Methods collection to be called from the GUI for alignment tasks.
 *
 */
final public class DistortionCorrectionTask
{
	static public class CorrectDistortionFromSelectionParam extends BasicParam
	{
		public int firstLayerIndex;
		public int lastLayerIndex;
		public boolean clearTransform = false;
		public boolean visualize = false;
		public boolean tilesAreInPlace = false;

		/**
		 * Minimal absolute number of inliers
		 */
		public int minNumInliers = 20;

		public boolean multipleHypotheses = true;

		/**
		 * Ignore identity transform up to a given tolerance
		 */
		public boolean rejectIdentity = true;
		public float identityTolerance = 5.0f;

		public int desiredModelIndex = 1;
		public int maxIterationsOptimize = 2000;
		public int maxPlateauwidthOptimize = 200;

		public int maxNumThreadsSift = Runtime.getRuntime().availableProcessors();

		/**
		 * Regularization for approximate alignment
		 */
		public boolean regularize = false;
		public int regularizerIndex = 0;
		public double lambdaRegularize = 0.01;

		public CorrectDistortionFromSelectionParam()
		{
			sift.fdSize = 4;
			expectedModelIndex = 0;
			desiredModelIndex = 0;
			minInlierRatio = 0;
		}

		public boolean setupSIFT( final String title )
		{
			/* SIFT */
			final GenericDialog gdSIFT = new GenericDialog( title + "SIFT parameters" );

			SIFT.addFields( gdSIFT, sift );

			gdSIFT.addMessage( "Local Descriptor Matching:" );
			gdSIFT.addNumericField( "closest/next_closest_ratio :", rod, 2 );

			gdSIFT.addMessage( "Miscellaneous:" );
			gdSIFT.addNumericField( "feature_extraction_threads :", maxNumThreadsSift, 0 );

			gdSIFT.showDialog();

			if ( gdSIFT.wasCanceled() )
				return false;

			SIFT.readFields( gdSIFT, sift );

			rod = ( float )gdSIFT.getNextNumber();
			maxNumThreadsSift = ( int )gdSIFT.getNextNumber();

			return true;
		}


		public boolean setup( final Selection selection )
		{
			if ( !setupSIFT( "Distortion Correction: " ) )
				return false;

			/* Geometric filters */

			final GenericDialog gd = new GenericDialog( "Distortion Correction: Geometric filters" );

			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
			gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
			gd.addChoice( "expected_transformation :", Param.modelStrings, Param.modelStrings[ expectedModelIndex ] );
			gd.addCheckbox( "test_multiple_hypotheses", multipleHypotheses );
			gd.addCheckbox( "ignore constant background", rejectIdentity );
			gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
			gd.addCheckbox( "tiles are rougly in place", tilesAreInPlace );

			gd.showDialog();

			if ( gd.wasCanceled() )
				return false;

			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			minNumInliers = ( int )gd.getNextNumber();
			expectedModelIndex = gd.getNextChoiceIndex();
			multipleHypotheses = gd.getNextBoolean();
			rejectIdentity = gd.getNextBoolean();
			identityTolerance = ( float )gd.getNextNumber();
			tilesAreInPlace = gd.getNextBoolean();

			final GenericDialog gdOptimize = new GenericDialog( "Distortion Correction: Montage Optimization" );
			gdOptimize.addChoice( "desired_transformation :", modelStrings, modelStrings[ desiredModelIndex ] );
			gdOptimize.addCheckbox( "regularize_model", regularize );
			gdOptimize.addMessage( "Optimization:" );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsOptimize, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthOptimize, 0 );

			gdOptimize.showDialog();

			if ( gdOptimize.wasCanceled() )
				return false;

			desiredModelIndex = gdOptimize.getNextChoiceIndex();
			regularize = gdOptimize.getNextBoolean();
			maxIterationsOptimize = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthOptimize = ( int )gdOptimize.getNextNumber();

			if ( regularize )
			{
				final GenericDialog gdRegularize = new GenericDialog( "Distortion Correction: Montage Regularization" );

				gdRegularize.addChoice( "regularizer :", modelStrings, modelStrings[ regularizerIndex ] );
				gdRegularize.addNumericField( "lambda :", lambdaRegularize, 2 );

				gdRegularize.showDialog();

				if ( gdRegularize.wasCanceled() )
					return false;

				regularizerIndex = gdRegularize.getNextChoiceIndex();
				lambdaRegularize = gdRegularize.getNextNumber();
			}

			final GenericDialog gdLens = new GenericDialog( "Distortion Correction: Lens Distortion" );

			gdLens.addMessage( "Lens Model :" );
			gdLens.addNumericField( "power_of_polynomial_kernel :", dimension, 0 );
			gdLens.addNumericField( "lambda :", lambda, 6 );

			gdLens.addMessage( "Apply Distortion Correction :" );

			Utils.addLayerRangeChoices( selection.getLayer(), gdLens );
			gdLens.addCheckbox( "clear_present_transforms", clearTransform );
			gdLens.addCheckbox( "visualize_distortion_model", visualize );

			gdLens.showDialog();
			if ( gdLens.wasCanceled() )
				return false;

			dimension = ( int )gdLens.getNextNumber();
			lambda = ( double )gdLens.getNextNumber();
			firstLayerIndex = gdLens.getNextChoiceIndex();
			lastLayerIndex = gdLens.getNextChoiceIndex();
			clearTransform = gdLens.getNextBoolean();
			visualize = gdLens.getNextBoolean();

			return true;
		}

		@Override
		public CorrectDistortionFromSelectionParam clone()
		{
			final CorrectDistortionFromSelectionParam p = new CorrectDistortionFromSelectionParam();
			p.sift.set( sift );
			p.dimension = dimension;
			p.expectedModelIndex = expectedModelIndex;
			p.lambda = lambda;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.rod = rod;
			p.tilesAreInPlace = tilesAreInPlace;
			p.firstLayerIndex = firstLayerIndex;
			p.lastLayerIndex = lastLayerIndex;
			p.clearTransform = clearTransform;
			p.visualize = visualize;

			p.desiredModelIndex = desiredModelIndex;
			p.identityTolerance = identityTolerance;
			p.lambdaRegularize = lambdaRegularize;
			p.maxIterationsOptimize = maxIterationsOptimize;
			p.maxNumThreadsSift = maxNumThreadsSift;
			p.maxPlateauwidthOptimize = maxPlateauwidthOptimize;
			p.minNumInliers = minNumInliers;
			p.multipleHypotheses = multipleHypotheses;
			p.regularize = regularize;
			p.regularizerIndex = regularizerIndex;
			p.rejectIdentity = rejectIdentity;

			return p;
		}
	}


	/**
	 * Sets a {@link CoordinateTransform} to a list of patches.
	 */
	final static protected class SetCoordinateTransformThread extends Thread
	{
		final protected List< Patch > patches;
		final protected CoordinateTransform transform;
		final protected AtomicInteger ai;

		public SetCoordinateTransformThread(
				final List< Patch > patches,
				final CoordinateTransform transform,
				final AtomicInteger ai )
		{
			this.patches = patches;
			this.transform = transform;
			this.ai = ai;
		}

		@Override
		final public void run()
		{
			for ( int i = ai.getAndIncrement(); i < patches.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				final Patch patch = patches.get( i );
//				Utils.log( "Setting transform \"" + transform + "\" for patch \"" + patch.getTitle() + "\"." );
				patch.setCoordinateTransform( transform );
				patch.updateMipMaps();
				patch.getProject().getLoader().decacheImagePlus( patch.getId() );

				IJ.showProgress( i, patches.size() );
			}
		}
	}

	final static protected void setCoordinateTransform(
			final List< Patch > patches,
			final CoordinateTransform transform,
			final int numThreads )
	{
		final AtomicInteger ai = new AtomicInteger( 0 );
		final List< SetCoordinateTransformThread > threads = new ArrayList< SetCoordinateTransformThread >();

		for ( int i = 0; i < numThreads; ++i )
		{
			final SetCoordinateTransformThread thread = new SetCoordinateTransformThread( patches, transform, ai );
			threads.add( thread );
			thread.start();
		}
		try
		{
			for ( final Thread thread : threads )
				thread.join();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Setting CoordinateTransform failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
		}
	}


	/**
	 * Appends a {@link CoordinateTransform} to a list of patches.
	 */
	final static protected class AppendCoordinateTransformThread extends Thread
	{
		final protected List< Patch > patches;
		final protected CoordinateTransform transform;
		final protected AtomicInteger ai;

		public AppendCoordinateTransformThread(
				final List< Patch > patches,
				final CoordinateTransform transform,
				final AtomicInteger ai )
		{
			this.patches = patches;
			this.transform = transform;
			this.ai = ai;
		}

		@Override
		final public void run()
		{
			for ( int i = ai.getAndIncrement(); i < patches.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				final Patch patch = patches.get( i );
				patch.appendCoordinateTransform( transform );
				patch.updateMipMaps();

				IJ.showProgress( i, patches.size() );
			}
		}
	}

	final static protected void appendCoordinateTransform(
			final List< Patch > patches,
			final CoordinateTransform transform,
			final int numThreads )
	{
		final AtomicInteger ai = new AtomicInteger( 0 );
		final List< AppendCoordinateTransformThread > threads = new ArrayList< AppendCoordinateTransformThread >();

		for ( int i = 0; i < numThreads; ++i )
		{
			final AppendCoordinateTransformThread thread = new AppendCoordinateTransformThread( patches, transform, ai );
			threads.add( thread );
			thread.start();
		}
		try
		{
			for ( final Thread thread : threads )
				thread.join();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Appending CoordinateTransform failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
		}
	}

	final static public CorrectDistortionFromSelectionParam correctDistortionFromSelectionParam = new CorrectDistortionFromSelectionParam();

	final static public Bureaucrat correctDistortionFromSelectionTask ( final Selection selection )
	{
		final Worker worker = new Worker("Distortion Correction", false, true) {
			@Override
			public void run() {
				startedWorking();
				try {
					correctDistortionFromSelection( selection );
					Display.repaint(selection.getLayer());
				} catch (final Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
			@Override
			public void cleanup() {
				if (!selection.isEmpty())
					selection.getLayer().getParent().undoOneStep();
			}
		};
		return Bureaucrat.createAndStart( worker, selection.getProject() );
	}


	final static public void run( final CorrectDistortionFromSelectionParam p, final List< Patch > patches, final Displayable active, final Layer layer, final Worker worker )
	{
		/* no multiple inheritance, so p cannot be an Align.ParamOptimize, working around legacy by copying data into one ... */
		final Align.ParamOptimize ap = new Align.ParamOptimize();
		ap.sift.set( p.sift );
		ap.desiredModelIndex = p.desiredModelIndex;
		ap.expectedModelIndex = p.expectedModelIndex;
		ap.maxEpsilon = p.maxEpsilon;
		ap.minInlierRatio = p.minInlierRatio;
		ap.rod = p.rod;
		ap.identityTolerance = p.identityTolerance;
		ap.lambda = p.lambdaRegularize;
		ap.maxIterations = p.maxIterationsOptimize;
		ap.maxPlateauwidth = p.maxPlateauwidthOptimize;
		ap.minNumInliers = p.minNumInliers;
		ap.regularize = p.regularize;
		ap.regularizerModelIndex = p.regularizerIndex;
		ap.rejectIdentity = p.rejectIdentity;

		/** Get all patches that will be affected. */
		final List< Patch > allPatches = new ArrayList< Patch >();
		for ( final Layer l : layer.getParent().getLayers().subList( p.firstLayerIndex, p.lastLayerIndex + 1 ) )
			for ( final Displayable d : l.getDisplayables( Patch.class ) )
				allPatches.add( ( Patch )d );

		/** Unset the coordinate transforms of all patches if desired. */
		if ( p.clearTransform )
		{
			if ( worker != null )
				worker.setTaskName( "Clearing present transforms" );

			setCoordinateTransform( allPatches, null, Runtime.getRuntime().availableProcessors() );
			Display.repaint();
		}

		if ( worker != null )
			worker.setTaskName( "Establishing SIFT correspondences" );

		final List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		final List< Patch > fixedPatches = new ArrayList< Patch >();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		Align.tilesFromPatches( ap, patches, fixedPatches, tiles, fixedTiles );

		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();

		if ( p.tilesAreInPlace )
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		else
			AbstractAffineTile2D.pairTiles( tiles, tilePairs );

		AbstractAffineTile2D< ? > fixedTile = null;
		if ( fixedTiles.size() > 0 )
			fixedTile = fixedTiles.get(0);
		else
			fixedTile = tiles.get(0);

		Align.connectTilePairs( ap, tiles, tilePairs, p.maxNumThreadsSift, p.multipleHypotheses );


		/** Shift all local coordinates into the original image frame */
		for ( final AbstractAffineTile2D< ? > tile : tiles )
		{
			final Rectangle box = tile.getPatch().getCoordinateTransformBoundingBox();
			for ( final PointMatch m : tile.getMatches() )
			{
				final double[] l = m.getP1().getL();
				final double[] w = m.getP1().getW();
				l[ 0 ] += box.x;
				l[ 1 ] += box.y;
				w[ 0 ] = l[ 0 ];
				w[ 1 ] = l[ 1 ];
			}
		}

		if ( Thread.currentThread().isInterrupted() ) return;

		final List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( tiles );
		if ( graphs.size() > 1 )
			Utils.log( "Could not interconnect all images with correspondences.  " );

		final List< AbstractAffineTile2D< ? > > interestingTiles;

		/** Find largest graph. */
		Set< Tile< ? > > largestGraph = null;
		for ( final Set< Tile< ? > > graph : graphs )
			if ( largestGraph == null || largestGraph.size() < graph.size() )
				largestGraph = graph;

		interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		for ( final Tile< ? > t : largestGraph )
			interestingTiles.add( ( AbstractAffineTile2D< ? > )t );

		if ( Thread.currentThread().isInterrupted() ) return;

		Utils.log( "Estimating lens model:" );

		/* initialize with pure affine */
		Align.optimizeTileConfiguration( ap, interestingTiles, fixedTiles );

		/* measure the current error */
		double e = 0;
		int n = 0;
		for ( final AbstractAffineTile2D< ? > t : interestingTiles )
			for ( final PointMatch pm : t.getMatches() )
			{
				e += pm.getDistance();
				++n;
			}
		e /= n;

		double dEpsilon_i = 0;
		double epsilon_i = e;
		double dEpsilon_0 = 0;
		NonLinearTransform lensModel = null;

		Utils.log( "0: epsilon = " + e );

		/* Store original point locations */
		final HashMap< Point, Point > originalPoints = new HashMap< Point, Point >();
		for ( final AbstractAffineTile2D< ? > t : interestingTiles )
			for ( final PointMatch pm : t.getMatches() )
				originalPoints.put( pm.getP1(), pm.getP1().clone() );

		/* ad hoc conditions to terminate iteration:
		 * small improvement ( 1/1000) relative to first iteration
		 * less than 20 iterations
		 * at least 2 iterations */
		for ( int i = 1; i < 20 && ( i < 2 || dEpsilon_i <= dEpsilon_0 / 1000 ); ++i )
		{
			if ( Thread.currentThread().isInterrupted() ) return;

			/* Some data shuffling for the lens correction interface */
			final List< PointMatchCollectionAndAffine > matches = new ArrayList< PointMatchCollectionAndAffine >();
			for ( final AbstractAffineTile2D< ? >[] tilePair : tilePairs )
			{
				final AffineTransform a = tilePair[ 0 ].createAffine();
				a.preConcatenate( tilePair[ 1 ].getModel().createInverseAffine() );
				final Collection< PointMatch > commonMatches = new ArrayList< PointMatch >();
				tilePair[ 0 ].commonPointMatches( tilePair[ 1 ], commonMatches );
				final Collection< PointMatch > originalCommonMatches = new ArrayList< PointMatch >();
				for ( final PointMatch pm : commonMatches )
					originalCommonMatches.add( new PointMatch(
							originalPoints.get( pm.getP1() ),
							originalPoints.get( pm.getP2() ) ) );
				matches.add( new PointMatchCollectionAndAffine( a, originalCommonMatches ) );
			}

			if ( worker != null )
				worker.setTaskName( "Estimating lens distortion correction" );

			lensModel = Distortion_Correction.createInverseDistortionModel(
		    		matches,
		    		p.dimension,
		    		p.lambda,
		    		( int )fixedTile.getWidth(),
		    		( int )fixedTile.getHeight() );

			/* update local points */
			for ( final AbstractAffineTile2D< ? > t : interestingTiles )
				for ( final PointMatch pm : t.getMatches() )
				{
					final Point currentPoint = pm.getP1();
					final Point originalPoint = originalPoints.get( currentPoint );
					final double[] l = currentPoint.getL();
					final double[] lo = originalPoint.getL();
					l[ 0 ] = lo[ 0 ];
					l[ 1 ] = lo[ 1 ];
					lensModel.applyInPlace( l );
				}

			/* re-optimize */
			Align.optimizeTileConfiguration( ap, interestingTiles, fixedTiles );

			/* measure the current error */
			e = 0;
			n = 0;
			for ( final AbstractAffineTile2D< ? > t : interestingTiles )
				for ( final PointMatch pm : t.getMatches() )
				{
					e += pm.getDistance();
					++n;
				}
			e /= n;

			dEpsilon_i = e - epsilon_i;
			epsilon_i = e;
			if ( i == 1 ) dEpsilon_0 = dEpsilon_i;

			Utils.log( i + ": epsilon = " + e );
			Utils.log( i + ": delta epsilon = " + dEpsilon_i );
		}

		if ( lensModel != null )
		{
			if ( p.visualize )
			{
				if ( Thread.currentThread().isInterrupted() ) return;

				if ( worker != null )
					worker.setTaskName( "Visualizing lens distortion correction" );

				lensModel.visualizeSmall( p.lambda );
			}

			if ( worker != null )
				worker.setTaskName( "Applying lens distortion correction" );

			appendCoordinateTransform( allPatches, lensModel, Runtime.getRuntime().availableProcessors() );

			Utils.log( "Done." );
		}
		else
			Utils.log( "No lens model found." );
	}

	final static public void run( final CorrectDistortionFromSelectionParam p, final List< Patch > patches, final Displayable active, final Layer layer )
	{
		run( p, patches, active, layer, null );
	}

	final static public Bureaucrat correctDistortionFromSelection( final Selection selection )
	{
		final List< Patch > patches = new ArrayList< Patch >();
		for ( final Displayable d : Display.getFront().getSelection().getSelected() )
			if ( d instanceof Patch ) patches.add( ( Patch )d );

		if ( patches.size() < 2 )
		{
			Utils.log("No images in the selection.");
			return null;
		}

		final Worker worker = new Worker( "Lens correction" )
		{
			@Override
			final public void run()
			{
				try
				{
					startedWorking();

					if ( !correctDistortionFromSelectionParam.setup( selection ) ) return;

					DistortionCorrectionTask.run( correctDistortionFromSelectionParam.clone(), patches, selection.getActive(), selection.getLayer(), null );

					Display.repaint();
				}
				catch ( final Exception e ) { IJError.print( e ); }
				finally { finishedWorking(); }
			}
		};

		return Bureaucrat.createAndStart( worker, selection.getProject() );
	}
}
