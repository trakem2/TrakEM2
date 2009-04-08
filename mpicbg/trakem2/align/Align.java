/**
 * 
 */
package mpicbg.trakem2.align;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.FSLoader;

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
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

public class Align
{
	static public class Param implements Serializable
	{	
		private static final long serialVersionUID = -2247163691721712461L;

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
		public float minInlierRatio = 0.2f;
		
		/**
		 * Implemeted transformation models for choice
		 */
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
		public int expectedModelIndex = 1;
		public int desiredModelIndex = 1;
		
		public Param()
		{
			sift.fdSize = 8;
		}
		
		public void addFields( final GenericDialog gd )
		{
			SIFT.addFields( gd, sift );
			
			gd.addNumericField( "closest/next_closest_ratio :", rod, 2 );
			
			gd.addMessage( "Geometric Consensus Filter:" );
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "inlier_ratio :", minInlierRatio, 2 );
			gd.addChoice( "expected_transformation :", modelStrings, modelStrings[ expectedModelIndex ] );
			
			gd.addMessage( "Alignment:" );
			gd.addChoice( "desired_transformation :", modelStrings, modelStrings[ desiredModelIndex ] );
		}
		
		public boolean readFields( final GenericDialog gd )
		{
			SIFT.readFields( gd, sift );
			
			rod = ( float )gd.getNextNumber();
			
			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			expectedModelIndex = gd.getNextChoiceIndex();
			desiredModelIndex = gd.getNextChoiceIndex();
			
			return !gd.invalidNumber();
		}
	
		public boolean setup( final String title )
		{
			final GenericDialog gd = new GenericDialog( title );
			
			addFields( gd );
			
			do
			{
				gd.showDialog();
				if ( gd.wasCanceled() ) return false;
			}			
			while ( !readFields( gd ) );
			
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
			p.expectedModelIndex = expectedModelIndex;
			p.desiredModelIndex = desiredModelIndex;
			
			return p;
		}
		
		/**
		 * Check if two parameter sets are equal.  So far, this method ignores
		 * the parameter {@link #desiredModelIndex} which defines the
		 * transformation class to be used for {@link Tile} alignment.  This
		 * makes sense for the current use in {@link PointMatch} serialization
		 * but might be missleading for other applications.
		 * 
		 * TODO Think about this.
		 * 
		 * @param p
		 * @return
		 */
		public boolean equals( Param p )
		{
			return
				sift.equals( p.sift ) &&
				( rod == p.rod ) &&
				( maxEpsilon == p.maxEpsilon ) &&
				( minInlierRatio == p.minInlierRatio ) &&
				( expectedModelIndex == p.expectedModelIndex );
//			&& ( desiredModelIndex == p.desiredModelIndex );
		}
	}
	
	final static public Param param = new Param();
	
	static public class ParamOptimize extends Param
	{
		private static final long serialVersionUID = 970673723211054580L;

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
		public void addFields( final GenericDialog gd )
		{
			super.addFields( gd );
			
			gd.addNumericField( "maximal_iterations :", maxIterations, 0 );
			gd.addNumericField( "maximal_plateauwidth :", maxPlateauwidth, 0 );
		}
		
		@Override
		public boolean readFields( final GenericDialog gd )
		{
			super.readFields( gd );
			
			maxIterations = ( int )gd.getNextNumber();
			maxPlateauwidth = ( int )gd.getNextNumber();
			
			return !gd.invalidNumber();
		}
		
		@Override
		final public boolean setup( final String title )
		{
			final GenericDialog gd = new GenericDialog( title );
			
			addFields( gd );
			
			do
			{
				gd.showDialog();
				if ( gd.wasCanceled() ) return false;
			}			
			while ( !readFields( gd ) );
			
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
			p.expectedModelIndex = expectedModelIndex;
			
			p.desiredModelIndex = desiredModelIndex;
			p.maxIterations = maxIterations;
			p.maxPlateauwidth = maxPlateauwidth;
			
			return p;
		}
		
		public boolean equals( ParamOptimize p )
		{
			return
				super.equals( p ) &&
				( maxIterations == p.maxIterations ) &&
				( maxPlateauwidth == p.maxPlateauwidth );
		}
	}
	
	final static public ParamOptimize paramOptimize = new ParamOptimize();
	
	final static private class Features implements Serializable
	{
		private static final long serialVersionUID = 2689219384710526198L;
		
		FloatArray2DSIFT.Param p;
		ArrayList< Feature > features;
		Features( final FloatArray2DSIFT.Param p, final ArrayList< Feature > features )
		{
			this.p = p;
			this.features = features;
		}
	}
	
	final static private class PointMatches implements Serializable
	{
		private static final long serialVersionUID = -2564147268101223484L;
		
		Param p;
		ArrayList< PointMatch > pointMatches;
		PointMatches( final Param p, final ArrayList< PointMatch > pointMatches )
		{
			this.p = p;
			this.pointMatches = pointMatches;
		}
	}
	
	/**
	 * Extracts {@link Feature SIFT-features} from a {@link List} of
	 * {@link AbstractAffineTile2D Tiles} and saves them to disk.
	 */
	final static protected class ExtractFeaturesThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
//		final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;
		
		public ExtractFeaturesThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
//				final HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this.p = p;
			this.tiles = tiles;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
		}
		
		@Override
		final public void run()
		{
			FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			SIFT ijSIFT = new SIFT( sift );

			for ( int i = ai.getAndIncrement(); i < tiles.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				AbstractAffineTile2D< ? > tile = tiles.get( i );
				Collection< Feature > features = deserializeFeatures( p, tile );
				if ( features == null )
				{
					features = new ArrayList< Feature >();
					long s = System.currentTimeMillis();
					ijSIFT.extractFeatures( tile.createMaskedByteImage(), features );
					IJ.log( features.size() + " features extracted in tile " + i + " \"" + tile.getPatch().getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
					if ( !serializeFeatures( p, tile, features ) )
						IJ.log( "Saving features failed for tile \"" + tile.getPatch() + "\"" );
				}
				else
				{
					IJ.log( features.size() + " features loaded for tile " + i + " \"" + tile.getPatch().getTitle() + "\"." );
				}
				IJ.showProgress( ap.getAndIncrement(), steps );				
			}
		}
	}
		
	
	final static protected class MatchFeaturesAndFindModelThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
		//final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected List< AbstractAffineTile2D< ? >[] > tilePairs;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;
		
		public MatchFeaturesAndFindModelThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
				final List< AbstractAffineTile2D< ? >[] > tilePairs,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this.p = p;
			this.tiles = tiles;
			this.tilePairs = tilePairs;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
		}
		
		@Override
		final public void run()
		{
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
				
			for ( int i = ai.getAndIncrement(); i < tilePairs.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				candidates.clear();
				final AbstractAffineTile2D< ? >[] tilePair = tilePairs.get( i );
				
				Collection< PointMatch > inliers = deserializePointMatches( p, tilePair[ 0 ], tilePair[ 1 ] );
				
				if ( inliers == null )
				{
					inliers = new ArrayList< PointMatch >();
					
					long s = System.currentTimeMillis();
					
					FeatureTransform.matchFeatures(
						fetchFeatures( p, tilePair[ 0 ] ),
						fetchFeatures( p, tilePair[ 1 ] ),
						candidates,
						p.rod );
	
					final AbstractAffineModel2D< ? > model;
					switch ( p.expectedModelIndex )
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
						IJ.log( "Model found for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + model.getCost() + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					else
						IJ.log( "No model found for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\"" );
					
					if ( !serializePointMatches( p, tilePair[ 0 ], tilePair[ 1 ], inliers ) )
						IJ.log( "Saving point matches failed for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\"" );
					
				}
				else
					IJ.log( "Point matches for tiles \"" + tilePair[ 0 ].getPatch().getTitle() + "\" and \"" + tilePair[ 1 ].getPatch().getTitle() + "\" fetched from disk cache" );
				
				if ( inliers != null && inliers.size() > 0 )
				{
					tilePair[ 0 ].connect( tilePair[ 1 ], inliers );
					tilePair[ 0 ].clearVirtualMatches();
					tilePair[ 1 ].clearVirtualMatches();
				}
				
				IJ.showProgress( ap.getAndIncrement(), steps );
			}
		}
	}
	
	final static protected boolean serializeFeatures( final Param p, AbstractAffineTile2D< ? > t, final Collection< Feature > f )
	{
		final ArrayList< Feature > list = new ArrayList< Feature >();
		list.addAll( f );
		final Patch patch = t.getPatch();
		final Loader loader = patch.getProject().getLoader();
		final Features fe = new Features( p.sift, list );
		return loader.serialize( fe, new StringBuffer( loader.getUNUIdFolder() ).append( "features.ser/" )
			.append( FSLoader.createIdPath( Long.toString( patch.getId() ), "features", ".ser" ) ).toString() );
	}

	/**
	 * Retrieve the features only if saved with the exact same relevant SIFT parameters.
	 */
	final static protected Collection< Feature > deserializeFeatures( final Param p, final AbstractAffineTile2D< ? > t )
	{
		final Patch patch = t.getPatch();
		final Loader loader = patch.getProject().getLoader();

		final Object ob = loader.deserialize( new StringBuffer( loader.getUNUIdFolder() ).append( "features.ser/" )
			.append( FSLoader.createIdPath( Long.toString( patch.getId() ), "features", ".ser" ) ).toString() );
		if ( null != ob )
		{
			try
			{
				final Features fe = ( Features )ob;
				if ( p.sift.equals( fe.p ) && null != fe.p )
				{
					return fe.features;
				}
			}
			catch ( Exception e )
			{
				e.printStackTrace();
			}
		}
		return null;
	}
	
	
	final static protected Collection< Feature > fetchFeatures(
			final Param p,
			final AbstractAffineTile2D< ? > t )
	{
		Collection< Feature > features = deserializeFeatures( p, t );
		if ( features == null )
		{
			final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			final SIFT ijSIFT = new SIFT( sift );
			features = new ArrayList< Feature >();
			long s = System.currentTimeMillis();
			ijSIFT.extractFeatures( t.createMaskedByteImage(), features );
			IJ.log( features.size() + " features extracted in tile \"" + t.getPatch().getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
			if ( !serializeFeatures( p, t, features ) )
				IJ.log( "Saving features failed for tile: " + t.getPatch() );
		}
		return features;
	}
	

	/**
	 * Save a {@link Collection} of {@link PointMatch PointMatches} two-sided.
	 * Creates two serialization files which is desperately required to clean
	 * up properly invalid serializations on change of a {@link Patch}.
	 * 
	 * @param p
	 * @param t1
	 * @param t2
	 * @param m
	 * @return
	 */
	final static protected boolean serializePointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2,
			final Collection< PointMatch > m )
	{
		final ArrayList< PointMatch > list = new ArrayList< PointMatch >();
		list.addAll( m );
		final ArrayList< PointMatch > tsil = PointMatch.flip( m );
		final Patch p1 = t1.getPatch();
		final Patch p2 = t2.getPatch();
		final Loader loader = p1.getProject().getLoader();
		return
			loader.serialize(
				new PointMatches( p, list ),
				new StringBuffer( loader.getUNUIdFolder() ).append( "pointmatches.ser/" ).append( FSLoader.createIdPath( Long.toString( p1.getId() ) + "_" + Long.toString( p2.getId() ), "pointmatches", ".ser" ) ).toString() ) &&
			loader.serialize(
				new PointMatches( p, tsil ),
				new StringBuffer( loader.getUNUIdFolder() ).append( "pointmatches.ser/" ).append( FSLoader.createIdPath( Long.toString( p2.getId() ) + "_" + Long.toString( p1.getId() ), "pointmatches", ".ser" ) ).toString() );
	}
	
	
	final static protected Collection< PointMatch > deserializePointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2 )
	{
		final Patch p1 = t1.getPatch();
		final Patch p2 = t2.getPatch();
		final Loader loader = p1.getProject().getLoader();
		
		final Object ob = loader.deserialize( new StringBuffer( loader.getUNUIdFolder() ).append( "pointmatches.ser/" )
				.append( FSLoader.createIdPath( Long.toString( p1.getId() ) + "_" + Long.toString( p2.getId() ), "pointmatches", ".ser" ) ).toString() );
		
		if ( null != ob )
		{
			try
			{
				final PointMatches pm = ( PointMatches )ob;
				if ( p.equals( pm.p ) && null != pm.p )
				{
					return pm.pointMatches;
				}
			}
			catch ( Exception e )
			{
				e.printStackTrace();
			}
		}
		return null;
	}
	
	
	/**
	 * Fetch a {@link Collection} of corresponding
	 * {@link Feature SIFT-features}.  Both {@link Feature SIFT-features} and
	 * {@linkplain PointMatch corresponding points} are cached to disk.
	 * 
	 * @param p
	 * @param t1
	 * @param t2
	 * @return
	 *   <dl>
	 *     <dt>null</dt><dd>if matching failed for some reasons</dd>
	 *     <dt>empty {@link Collection}</dt><dd>if there was no consistent set
	 *       of {@link PointMatch matches}</dd>
	 *     <dt>{@link Collection} of {@link PointMatch PointMatches}</dt>
	 *       <dd>if there was a consistent set of {@link PointMatch
	 *         PointMatches}</dd>
	 *   </dl>
	 */
	final static protected Collection< PointMatch > fetchPointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2 )
	{
		Collection< PointMatch > pointMatches = deserializePointMatches( p, t1, t2 );
		if ( pointMatches == null )
		{
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
			final List< PointMatch > inliers = new ArrayList< PointMatch >();
				
			long s = System.currentTimeMillis();
			FeatureTransform.matchFeatures(
					fetchFeatures( p, t1 ),
					fetchFeatures( p, t2 ),
					candidates,
					p.rod );

			final AbstractAffineModel2D< ? > model;
			switch ( p.expectedModelIndex )
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
				return null;
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
				IJ.log( "Model found for tiles \"" + t1.getPatch() + "\" and \"" + t2.getPatch().getTitle() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + model.getCost() + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
			}
			else
				IJ.log( "No model found for tiles " + t1 + " and " + t2 + "." );
			
			if ( !serializePointMatches( p, t1, t2, pointMatches ) )
				IJ.log( "Saving point matches failed for tile \"" + t1.getPatch() + "\" and tile \"" + t2.getPatch() + "\"" );
		}
		return pointMatches;
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
			final List< AbstractAffineTile2D< ? > > fixedTiles,
			final int numThreads )
	{
		final ArrayList< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D<?>[] >();
		AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		connectTilePairs( p, tiles, tilePairs, numThreads );
		optimizeTileConfiguration( p, tiles, fixedTiles );
	}
	
	/**
	 * Align a set of {@link AbstractAffineTile2D tiles} that are
	 * interconnected by {@link PointMatch point-correspondences}.
	 */
	final static public void optimizeTileConfiguration(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles )
	{
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles( tiles );
		
		ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tiles );
		for ( Set< Tile< ? > > graph : graphs )
		{
			boolean pleaseFix = true;
			if ( fixedTiles != null )
				for ( final Tile< ? > t : fixedTiles )
					if ( graph.contains( t ) )
					{
						pleaseFix = false;
					}
			if ( pleaseFix )
				tc.fixTile( graph.iterator().next() );
		}
		for ( final Tile< ? > t : fixedTiles )
			tc.fixTile( t );
		
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
			if ( visited.contains( t ) ) continue;
			pairwiseAlign( ( AbstractAffineTile2D< ? > )t, visited );
			// TODO Actually do it ...
		}
	}
	
		
	final static public void pairwiseAlignTileConfiguration(
			final List< AbstractAffineTile2D< ? > > tiles )
	{
		// TODO Implement it
	}
	
	
	/**
	 * Connect a {@link List} of {@link AbstractAffineTile2D Tiles} by
	 * geometrically consistent {@link Feature SIFT-feature} correspondences.
	 * 
	 * @param p
	 * @param tiles
	 * @param numThreads
	 */
	final static public void connectTilePairs(
			final Param p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs,
			final int numThreads )
	{
		final AtomicInteger ai = new AtomicInteger( 0 );
		final AtomicInteger ap = new AtomicInteger( 0 );
		final int steps = tiles.size() + tilePairs.size();
		final List< ExtractFeaturesThread > extractFeaturesThreads = new ArrayList< ExtractFeaturesThread >();
		final List< MatchFeaturesAndFindModelThread > matchFeaturesAndFindModelThreads = new ArrayList< MatchFeaturesAndFindModelThread >();
		
		/** Extract and save Features */
		for ( int i = 0; i < numThreads; ++i )
		{
			final ExtractFeaturesThread thread = new ExtractFeaturesThread( p.clone(), tiles, ai, ap, steps );
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
			IJ.log( "Feature extraction failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
			return;
		}
		
		/** Establish correspondences */
		ai.set( 0 );
		for ( int i = 0; i < numThreads; ++i )
		{
			MatchFeaturesAndFindModelThread thread = new MatchFeaturesAndFindModelThread( p.clone(), tiles, tilePairs, ai, ap, steps );
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
			IJ.log( "Establishing feature correspondences failed.\n" + e.getMessage() + "\n" + e.getStackTrace() );
			return;
		}
	}
	
	
	/**
	 * 
	 * @param p 
	 * @param patches
	 * @param fixedPatches 
	 * @param tiles will contain the generated
	 *   {@link AbstractAffineTile2D Tiles}
	 * @param fixedTiles will contain the {@link AbstractAffineTile2D Tiles}
	 *   corresponding to the {@link Patch Patches} in fixedPatches
	 */
	final static public void tilesFromPatches(
			final Param p,
			final List< ? extends Patch > patches,
			final List< ? extends Patch > fixedPatches,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles )
	{
		for ( final Patch patch : patches )
		{
			final AbstractAffineTile2D< ? > t;
			switch ( p.desiredModelIndex )
			{
			case 0:
				t = new TranslationTile2D( patch );
				break;
			case 1:
				t = new RigidTile2D( patch );
				break;
			case 2:
				t = new SimilarityTile2D( patch );
				break;
			case 3:
				t = new AffineTile2D( patch );
				break;
			default:
				return;
			}
			tiles.add( t );
			if ( ( fixedPatches != null && fixedPatches.contains( patch ) ) || patch.isLocked() )
				fixedTiles.add( t );
		}
	}
	
	
	/**
	 * Align a selection of {@link Patch patches} in a Layer.
	 * 
	 * @param layer
	 */
	final static public void alignSelectedPatches( Selection selection, final int numThreads )
	{
		final List< Patch > patches = new ArrayList< Patch >();
		for ( final Displayable d : selection.getSelected() )
			if ( d instanceof Patch ) patches.add(  ( Patch )d );
		
		if ( patches.size() < 2 ) return;
		
		if ( !paramOptimize.setup( "Align selected patches" ) ) return;
		
		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = selection.getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		tilesFromPatches( paramOptimize, patches, fixedPatches, tiles, fixedTiles );
		
		alignTiles( paramOptimize, tiles, fixedTiles, numThreads );
		
		for ( AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
	}
	
	
	/**
	 * Align all {@link Patch patches} in a Layer.
	 * 
	 * @param layer
	 */
	final static public void alignLayer( final Layer layer, final int numThreads )
	{
		if ( !paramOptimize.setup( "Align patches in layer" ) ) return;
		
		List< Displayable > displayables = layer.getDisplayables( Patch.class );
		List< Patch > patches = new ArrayList< Patch >();
		for ( Displayable d : displayables )
			patches.add( ( Patch )d );
		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		tilesFromPatches( paramOptimize, patches, null, tiles, fixedTiles );
		
		alignTiles( paramOptimize, tiles, fixedTiles, numThreads );
		
		for ( AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
	}
	
	
	final static public void alignLayersLinearly( final List< Layer > layers, final int numThreads )
	{
		param.sift.maxOctaveSize = 1600;
		
		if ( !param.setup( "Align layers linearly" ) ) return;
		
		final Rectangle box = layers.get( 0 ).getParent().getMinimalBoundingBox( Patch.class );
		final float scale = Math.min(  1.0f, Math.min( ( float )param.sift.maxOctaveSize / ( float )box.width, ( float )param.sift.maxOctaveSize / ( float )box.height ) );
		final Param p = param.clone();
		p.maxEpsilon *= scale;
		
		final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
		final SIFT ijSIFT = new SIFT( sift );
		
		Rectangle box1 = null;
		Rectangle box2 = null;
		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		List< PointMatch > candidates = new ArrayList< PointMatch >();
		List< PointMatch > inliers = new ArrayList< PointMatch >();
		
		AffineTransform a = new AffineTransform();
		
		int i = 0;
		for ( Layer l : layers )
		{
			long s = System.currentTimeMillis();
			
			features1.clear();
			features1.addAll( features2 );
			features2.clear();
			
			final Rectangle box3 = l.getMinimalBoundingBox( Patch.class );
			
			if ( box3 == null ) continue;
			
			box1 = box2;
			box2 = box3;
			
			ijSIFT.extractFeatures(
					l.getProject().getLoader().getFlatImage( l, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, true ).getProcessor(),
					features2 );
			IJ.log( features2.size() + " features extracted in layer \"" + l.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
			
			if ( features1.size() > 0 )
			{
				s = System.currentTimeMillis();
				
				candidates.clear();
				
				FeatureTransform.matchFeatures(
					features2,
					features1,
					candidates,
					p.rod );

				final AbstractAffineModel2D< ? > model;
				switch ( p.expectedModelIndex )
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
					IJ.log( "Model found for layer \"" + l.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					final AffineTransform b = new AffineTransform();
					b.translate( box1.x, box1.y );
					b.scale( 1.0f / scale, 1.0f / scale );
					b.concatenate( model.createAffine() );
					b.scale( scale, scale );
					b.translate( -box2.x, -box2.y);
					
					a.concatenate( b );
					l.apply( Displayable.class, a );
					Display.repaint( l );
				}
				else
					IJ.log( "No model found for layer \"" + l.getTitle() + "\" and its predecessor." );
			}
			IJ.showProgress( ++i, layers.size() );	
		}
	}
}
