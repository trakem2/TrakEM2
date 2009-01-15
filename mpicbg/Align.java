/**
 * 
 */
package mpicbg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.gui.GenericDialog;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;

public class Align
{
	static public class Param
	{	
		final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();

		/**
		 * Closest/next closest neighbour distance ratio
		 */
		public float rod = 0.92f;
		
		/**
		 * Maximal allowed alignment error in px
		 */
		public float maxEpsilon = 100.0f;
		
		/**
		 * Inlier/candidates ratio
		 */
		public float minInlierRatio = 0.05f;
		
		/**
		 * Implemeted transformation models for choice
		 */
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
		public int modelIndex = 1;
		
		public Param()
		{
			sift.fdSize = 8;
		}
	
		public boolean setup( final String title )
		{
			final GenericDialog gd = new GenericDialog( title );
			
			gd.addMessage( "Scale Invariant Interest Point Detector:" );
			gd.addNumericField( "initial_gaussian_blur :", sift.initialSigma, 2, 6, "px" );
			gd.addNumericField( "steps_per_scale_octave :", sift.steps, 0 );
			gd.addNumericField( "minimum_image_size :", sift.minOctaveSize, 0, 6, "px" );
			gd.addNumericField( "maximum_image_size :", sift.maxOctaveSize, 0, 6, "px" );
			
			gd.addMessage( "Feature Descriptor:" );
			gd.addNumericField( "feature_descriptor_size :", sift.fdSize, 0 );
			gd.addNumericField( "feature_descriptor_orientation_bins :", sift.fdBins, 0 );
			gd.addNumericField( "closest/next_closest_ratio :", rod, 2 );
			
			gd.addMessage( "Geometric Consensus Filter:" );
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "inlier_ratio :", minInlierRatio, 2 );
			gd.addChoice( "expected_transformation :", modelStrings, modelStrings[ modelIndex ] );
			
			gd.showDialog();
			
			if ( gd.wasCanceled() ) return false;
						
			sift.initialSigma = ( float )gd.getNextNumber();
			sift.steps = ( int )gd.getNextNumber();
			sift.minOctaveSize = ( int )gd.getNextNumber();
			sift.maxOctaveSize = ( int )gd.getNextNumber();
			
			sift.fdSize = ( int )gd.getNextNumber();
			sift.fdBins = ( int )gd.getNextNumber();
			rod = ( float )gd.getNextNumber();
			
			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			modelIndex = gd.getNextChoiceIndex();
				
			return true;
		}
		
		public Param clone()
		{
			Param p = new Param();
			
			p.sift.initialSigma = this.sift.initialSigma;
			p.sift.steps = this.sift.steps;
			p.sift.minOctaveSize = this.sift.minOctaveSize;
			p.sift.maxOctaveSize = this.sift.maxOctaveSize;
			p.sift.fdSize = this.sift.fdSize;
			p.sift.fdBins = this.sift.fdBins;
					
			p.rod = rod;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.modelIndex = modelIndex;
			
			return p;
		}
	}
	
	static public class ParamOptimize extends Param
	{
		/**
		 * Maximal number of iteration allowed for the optimizer.
		 */
		public int maxIterations = 2000;
		
		/**
		 * Maximal number of iterations allowed to not change the parameter to
		 * be optimized.
		 */
		public int maxPlateauwidth = 200;
		
		@Override
		final public boolean setup( final String title )
		{
			final GenericDialog gd = new GenericDialog( title );
			
			gd.addMessage( "Scale Invariant Interest Point Detector:" );
			gd.addNumericField( "initial_gaussian_blur :", sift.initialSigma, 2, 6, "px" );
			gd.addNumericField( "steps_per_scale_octave :", sift.steps, 0 );
			gd.addNumericField( "minimum_image_size :", sift.minOctaveSize, 0, 6, "px" );
			gd.addNumericField( "maximum_image_size :", sift.maxOctaveSize, 0, 6, "px" );
			
			gd.addMessage( "Feature Descriptor:" );
			gd.addNumericField( "feature_descriptor_size :", sift.fdSize, 0 );
			gd.addNumericField( "feature_descriptor_orientation_bins :", sift.fdBins, 0 );
			gd.addNumericField( "closest/next_closest_ratio :", rod, 2 );
			
			gd.addMessage( "Geometric Consensus Filter:" );
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "inlier_ratio :", minInlierRatio, 2 );
			gd.addChoice( "expected_transformation :", modelStrings, modelStrings[ modelIndex ] );
			
			gd.addMessage( "Optimizer:" );
			gd.addNumericField( "maximal_iterations :", maxIterations, 0 );
			gd.addNumericField( "maximal_plateauwidth :", maxPlateauwidth, 0 );
			
			gd.showDialog();
			
			if ( gd.wasCanceled() ) return false;
						
			sift.initialSigma = ( float )gd.getNextNumber();
			sift.steps = ( int )gd.getNextNumber();
			sift.minOctaveSize = ( int )gd.getNextNumber();
			sift.maxOctaveSize = ( int )gd.getNextNumber();
			
			sift.fdSize = ( int )gd.getNextNumber();
			sift.fdBins = ( int )gd.getNextNumber();
			rod = ( float )gd.getNextNumber();
			
			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			modelIndex = gd.getNextChoiceIndex();
			
			maxIterations = ( int )gd.getNextNumber();
			maxPlateauwidth = ( int )gd.getNextNumber();
			
			return true;
		}
		
		@Override
		final public ParamOptimize clone()
		{
			ParamOptimize p = new ParamOptimize();
			
			p.sift.initialSigma = this.sift.initialSigma;
			p.sift.steps = this.sift.steps;
			p.sift.minOctaveSize = this.sift.minOctaveSize;
			p.sift.maxOctaveSize = this.sift.maxOctaveSize;
			p.sift.fdSize = this.sift.fdSize;
			p.sift.fdBins = this.sift.fdBins;
					
			p.rod = rod;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.modelIndex = modelIndex;
			p.maxIterations = maxIterations;
			p.maxPlateauwidth = maxPlateauwidth;
			
			return p;
		}
	}
	
	/**
	 * Extracts a {@link Collection} of {@link Feature SIFT-features} from a
	 * {@link List} of {@link AbstractAffineTile2D Tiles} and feeds them
	 * into a {@link HashMap} that links each {@link AbstractAffineTile2D Tile}
	 * and its {@link Collection Feature-collection}.
	 *
	 */
	static public class ExtractFeaturesThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
		final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;
		
		public ExtractFeaturesThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
				final HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this.p = p;
			this.tiles = tiles;
			this.tileFeatures = tileFeatures;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
		}
		
		final public void run()
		{
			FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			SIFT ijSIFT = new SIFT( sift );

			for ( int i = ai.getAndIncrement(); i < tiles.size(); i = ai.getAndIncrement() )
			{
				AbstractAffineTile2D< ? > tile = tiles.get( i );
				Collection< Feature > features = new ArrayList< Feature >();
				long s = System.currentTimeMillis();
				ijSIFT.extractFeatures( tile.createMaskedByteImage(), features );
				tileFeatures.put( tile, features );
				IJ.log( features.size() + " features extracted in tile " + i + " \"" + tile.getPatch().getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
				IJ.showProgress( ap.getAndIncrement(), steps );
			}
		}
	}
	
	static public class MatchFeaturesAndFindModelThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
		final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected List< AbstractAffineTile2D< ? >[] > tilePairs;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;
		
		public MatchFeaturesAndFindModelThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
				final HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures,
				final List< AbstractAffineTile2D< ? >[] > tilePairs,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this.p = p;
			this.tiles = tiles;
			this.tileFeatures = tileFeatures;
			this.tilePairs = tilePairs;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
		}
		
		final public void run()
		{
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
			final List< PointMatch > inliers = new ArrayList< PointMatch >();
				
			for ( int i = ai.getAndIncrement(); i < tilePairs.size(); i = ai.getAndIncrement() )
			{
				candidates.clear();
				inliers.clear();
				
				final AbstractAffineTile2D< ? >[] tilePair = tilePairs.get( i );
				long s = System.currentTimeMillis();
				
				FeatureTransform.matchFeatures(
					tileFeatures.get( tilePair[ 0 ] ),
					tileFeatures.get( tilePair[ 1 ] ),
					candidates,
					p.rod );

				AbstractAffineModel2D< ? > model;
				switch ( p.modelIndex )
				{
				case 0:
					model = new TranslationModel2D();
					break;
				case 1:
					model = new RigidModel2D();
					break;
				case 2:
					model = new SimilarityModel2D();
					break;
				case 3:
					model = new AffineModel2D();
					break;
				default:
					return;
				}
	
				boolean modelFound;
				try
				{
					modelFound = model.filterRansac(
							candidates,
							inliers,
							1000,
							p.maxEpsilon,
							p.minInlierRatio,
							3 * model.getMinNumMatches(),
							3 );
				}
				catch ( NotEnoughDataPointsException e )
				{
					modelFound = false;
				}
				
				if ( modelFound )
				{
					IJ.log( "Model found for tiles \"" + tilePair[ 0 ].getPatch().getTitle() + "\" and \"" + tilePair[ 1 ].getPatch().getTitle() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + model.getCost() + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					tilePair[ 0 ].connect( tilePair[ 2 ], inliers );
					tilePair[ 0 ].clearVirtualMatches();
					tilePair[ 1 ].clearVirtualMatches();
				}
				else
					IJ.log( "No model found for tiles " + tilePair[ 0 ] + " and " + tilePair[ 1 ] + "." );
				IJ.showProgress( ap.getAndIncrement(), steps );
			}
		}
	}
		
	/**
	 * Align a set of overlapping {@link AbstractAffineTile2D tiles} using
	 * the following procedure:
	 * 
	 * <ol>
	 * <li>Extract {@link Feature SIFT-features} from all
	 * {@link AbstractAffineTile2D tiles}.</li>
	 * <li>Establish {@link PointMatch point-correspondences} from
	 * consistent sets of {@link Feature feature} matches among overlapping
	 * tiles.</li>
	 * <li>Globally align the tile configuration.</li>
	 * </ol>
	 * 
	 * Both
	 * {@link SIFT#extractFeatures(ij.process.ImageProcessor, Collection) feature extraction}
	 * and {@link FeatureTransform#matchFeatures(Collection, Collection, List, float) matching}
	 * are executed in multiple {@link Thread Threads}, with the number of
	 * {@link Thread Threads} being a parameter of the method.
	 * 
	 * @param p
	 * @param tiles
	 * @param numThreads
	 */
	final static public void alignTiles(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final int numThreads )
	{
		connectTiles( p, tiles, numThreads );
		optimizeTileConfiguration( p, tiles );
	}
	
	/**
	 * Align a set of {@link AbstractAffineTile2D tiles} that are
	 * interconnected by {@link PointMatch point-correspondences}.
	 */
	final static public void optimizeTileConfiguration(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles )
	{
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles( tiles );
		
		ArrayList< Set< Tile< ?  > > > graphs = Tile.identifyConnectedGraphs( tiles );
		for ( Set< Tile< ?  > > graph : graphs )
			tc.fixTile( graph.iterator().next() );
		
		try
		{
			tc.optimize( p.maxEpsilon, p.maxIterations, p.maxPlateauwidth );
		}
		catch ( Exception e ) { IJ.error( e.getMessage() + " " + e.getStackTrace() ); }
	}
	
	
	final static protected void pairwiseAlign(
			AbstractAffineTile2D< ? > tile,
			Set< AbstractAffineTile2D< ? > > visited )
	{
		visited.add( tile );
		for ( Tile< ? > t : tile.getConnectedTiles() )
		{
			// TODO continue here ...
		}
	}
	
		
	final static public void pairwiseAlignTileConfiguration(
			final List< AbstractAffineTile2D< ? > > tiles )
	{
		
	}
	
	
	/**
	 * 
	 * @param p
	 * @param tiles
	 * @param numThreads
	 */
	final static public void connectTiles(
			final Param p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final int numThreads )
	{
		final HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures = new HashMap< AbstractAffineTile2D< ? >, Collection< Feature > >();
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		for ( int a = 0; a < tiles.size(); ++a )
		{
			for ( int b = a + 1; b < tiles.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tiles.get( a );
				final AbstractAffineTile2D< ? > tb = tiles.get( b );
				if ( ta.intersects( tb ) )
				{
					tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
					/**
					 * TODO
					 *   Create virtual connections among overlapping
					 *   tiles if required by the user.  These connections
					 *   will be removed as soon as a model was found that
					 *   connects a tile to another one sufficiently.
					 * 
					 * TODO
					 *   Is this valid for two disconnected graphs of
					 *   tiles?
					 */
				}
			}
		}
		final AtomicInteger ai = new AtomicInteger( 0 );
		final AtomicInteger ap = new AtomicInteger( 0 );
		final int steps = tiles.size() + tilePairs.size();
		final List< ExtractFeaturesThread > extractFeaturesThreads = new ArrayList< ExtractFeaturesThread >();
		final List< MatchFeaturesAndFindModelThread > matchFeaturesAndFindModelThreads = new ArrayList< MatchFeaturesAndFindModelThread >();
		
		/** Extract Features */
		for ( int i = 0; i < numThreads; ++i )
		{
			final ExtractFeaturesThread thread = new ExtractFeaturesThread( p.clone(), tiles, tileFeatures, ai, ap, steps );
			extractFeaturesThreads.add( thread );
			thread.start();
		}
		try
		{
			for ( final ExtractFeaturesThread thread : extractFeaturesThreads )
				thread.join();
		}
		catch ( InterruptedException e )
		{
			IJ.error( "Feature extraction failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
			return;
		}
		
		/** Establish correspondences */
		ai.set( 0 );
		for ( int i = 0; i < numThreads; ++i )
		{
			MatchFeaturesAndFindModelThread thread = new MatchFeaturesAndFindModelThread( p.clone(), tiles, tileFeatures, tilePairs, ai, ap, steps );
			matchFeaturesAndFindModelThreads.add( thread );
			thread.start();
		}
		try
		{
			for ( final MatchFeaturesAndFindModelThread thread : matchFeaturesAndFindModelThreads )
				thread.join();
		}
		catch ( InterruptedException e )
		{
			IJ.error( "Establishing feature correspondences failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
			return;
		}
	}
}
