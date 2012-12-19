/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package mpicbg.trakem2.align;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AbstractModel;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.Transforms;
import mpicbg.models.TranslationModel2D;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class RegularizedAffineLayerAlignment extends AbstractElasticAlignment
{
	final static protected class Param implements Serializable
	{
		private static final long serialVersionUID = 6389009534364599550L;

		final public ParamPointMatch ppm = new ParamPointMatch();
		{
			ppm.sift.fdSize = 8;
		}
		
		public boolean isAligned = false;
		
		/**
		 * Maximal accepted alignment error in px
		 */
		public float maxEpsilon = 200.0f;
		
		/**
		 * Inlier/candidates ratio
		 */
		public float minInlierRatio = 0.0f;
		
		/**
		 * Minimal absolute number of inliers
		 */
		public int minNumInliers = 12;
		
		/**
		 * Transformation models for choice
		 */
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
		public int expectedModelIndex = 3;
		public boolean multipleHypotheses = false;
		
		/**
		 * Regularization
		 */
		public boolean regularize = false;
		public int regularizerIndex = 1;
		public float lambda = 0.1f;
		
		/**
		 * Ignore identity transform up to a given tolerance
		 */
		public boolean rejectIdentity = false;
		public float identityTolerance = 5.0f;
		
		/**
		 * Maximal number of consecutive sections to be tested for an alignment model
		 */
		public int maxNumNeighbors = 1;
		
		/**
		 * Maximal number of consecutive slices for which no model could be found
		 */
		public int maxNumFailures = 3;
		
		public int desiredModelIndex = 3;
		public int maxIterationsOptimize = 1000;
		public int maxPlateauwidthOptimize = 200;
		
		public boolean visualize = false;
		
		public int maxNumThreads = Runtime.getRuntime().availableProcessors();
		
		public boolean setup( final Rectangle box )
		{
			/* SIFT */
			final GenericDialog gdSIFT = new GenericDialog( "Align layers: SIFT parameters" );
			
			SIFT.addFields( gdSIFT, ppm.sift );
			
			gdSIFT.addMessage( "Local Descriptor Matching:" );
			gdSIFT.addNumericField( "closest/next_closest_ratio :", ppm.rod, 2 );
			
			gdSIFT.addMessage( "Miscellaneous:" );
			gdSIFT.addCheckbox( "clear_cache", ppm.clearCache );
			gdSIFT.addNumericField( "feature_extraction_threads :", ppm.maxNumThreadsSift, 0 );
			
			gdSIFT.showDialog();
			
			if ( gdSIFT.wasCanceled() )
				return false;
			
			SIFT.readFields( gdSIFT, ppm.sift );
			
			ppm.rod = ( float )gdSIFT.getNextNumber();
			ppm.clearCache = gdSIFT.getNextBoolean();
			ppm.maxNumThreadsSift = ( int )gdSIFT.getNextNumber();
			
				
			/* Geometric filters */
			
			final GenericDialog gd = new GenericDialog( "Align layers: Geometric filters" );
			
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
			gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
			gd.addChoice( "expected_transformation :", Param.modelStrings, Param.modelStrings[ expectedModelIndex ] );
			gd.addCheckbox( "test_multiple_hypotheses", multipleHypotheses );
			gd.addCheckbox( "ignore constant background", rejectIdentity );
			gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
			gd.addMessage( "Layer neighbor range:" );
			gd.addNumericField( "test_maximally :", maxNumNeighbors, 0, 6, "layers" );
			gd.addNumericField( "give_up_after :", maxNumFailures, 0, 6, "failures" );
			
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
			maxNumNeighbors = ( int )gd.getNextNumber();
			maxNumFailures = ( int )gd.getNextNumber();
			
			final GenericDialog gdOptimize = new GenericDialog( "Align layers: Optimization" );
			gdOptimize.addChoice( "desired_transformation :", modelStrings, modelStrings[ desiredModelIndex ] );
			gdOptimize.addCheckbox( "regularize model", regularize );
			gdOptimize.addMessage( "Optimization:" );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsOptimize, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthOptimize, 0 );
			//gdOptimize.addCheckbox( "filter outliers", filterOutliers );
			//gdOptimize.addNumericField( "mean_factor :", meanFactor, 2 );
			
			gdOptimize.showDialog();
			
			if ( gdOptimize.wasCanceled() )
				return false;
			
			desiredModelIndex = gdOptimize.getNextChoiceIndex();
			regularize = gdOptimize.getNextBoolean();
			maxIterationsOptimize = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthOptimize = ( int )gdOptimize.getNextNumber();
			
			if ( regularize )
			{
				final GenericDialog gdRegularize = new GenericDialog( "Align layers: Regularization" );
				
				gdRegularize.addChoice( "regularizer :", modelStrings, modelStrings[ regularizerIndex ] );
				gdRegularize.addNumericField( "lambda :", lambda, 2 );
				
				gdRegularize.showDialog();
				
				if ( gdRegularize.wasCanceled() )
					return false;
				
				regularizerIndex = gdRegularize.getNextChoiceIndex();
				lambda = ( float )gdRegularize.getNextNumber();
			}
			
			return true;
		}
		
		public Param() {}
		
		public Param(
				final int SIFTfdBins,
				final int SIFTfdSize,
				final float SIFTinitialSigma,
				final int SIFTmaxOctaveSize,
				final int SIFTminOctaveSize,
				final int SIFTsteps,
				
				final boolean clearCache,
				final int maxNumThreadsSift,
				final float rod,
				
				final int desiredModelIndex,
				final int expectedModelIndex,
				final float identityTolerance,
				final boolean isAligned,
				final float lambda,
				final float maxEpsilon,
				final int maxIterationsOptimize,
				final int maxNumFailures,
				final int maxNumNeighbors,
				final int maxNumThreads,
				final int maxPlateauwidthOptimize,
				final float minInlierRatio,
				final int minNumInliers,
				final boolean multipleHypotheses,
				final boolean regularize,
				final int regularizerIndex,
				final boolean rejectIdentity,
				final boolean visualize )
		{
			ppm.sift.fdBins = SIFTfdBins;
			ppm.sift.fdSize = SIFTfdSize;
			ppm.sift.initialSigma = SIFTinitialSigma;
			ppm.sift.maxOctaveSize = SIFTmaxOctaveSize;
			ppm.sift.minOctaveSize = SIFTminOctaveSize;
			ppm.sift.steps = SIFTsteps;
			
			ppm.clearCache = clearCache;
			ppm.maxNumThreadsSift = maxNumThreadsSift;
			ppm.rod = rod;
			
			this.desiredModelIndex = desiredModelIndex;
			this.expectedModelIndex = expectedModelIndex;
			this.identityTolerance = identityTolerance;
			this.isAligned = isAligned;
			this.lambda = lambda;
			this.maxEpsilon = maxEpsilon;
			this.maxIterationsOptimize = maxIterationsOptimize;
			this.maxNumFailures = maxNumFailures;
			this.maxNumNeighbors = maxNumNeighbors;
			this.maxNumThreads = maxNumThreads;
			this.maxPlateauwidthOptimize = maxPlateauwidthOptimize;
			this.minInlierRatio = minInlierRatio;
			this.minNumInliers = minNumInliers;
			this.multipleHypotheses = multipleHypotheses;
			this.regularize = regularize;
			this.regularizerIndex = regularizerIndex;
			this.rejectIdentity = rejectIdentity;
			this.visualize = visualize;
		}
		
		@Override
		final public Param clone()
		{
			return new Param(
					ppm.sift.fdBins,
					ppm.sift.fdSize,
					ppm.sift.initialSigma,
					ppm.sift.maxOctaveSize,
					ppm.sift.minOctaveSize,
					ppm.sift.steps,
					
					ppm.clearCache,
					ppm.maxNumThreadsSift,
					ppm.rod,
					
					desiredModelIndex,
					expectedModelIndex,
					identityTolerance,
					isAligned,
					lambda,
					maxEpsilon,
					maxIterationsOptimize,
					maxNumFailures,
					maxNumNeighbors,
					maxNumThreads,
					maxPlateauwidthOptimize,
					minInlierRatio,
					minNumInliers,
					multipleHypotheses,
					regularize,
					regularizerIndex,
					rejectIdentity,
					visualize );
		}
	}
	
	final static Param p = new Param();

	final static private String layerName( final Layer layer )
	{
		return new StringBuffer( "layer z=" )
			.append( String.format( "%.3f", layer.getZ() ) )
			.append( " `" )
			.append( layer.getTitle() )
			.append( "'" )
			.toString();
		
	}
	
	/**
	 * Extract SIFT features and save them into the project folder.
	 * 
	 * @param layerSet the layerSet that contains all layers
	 * @param layerRange the list of layers to be aligned
	 * @param box a rectangular region of interest that will be used for alignment
	 * @param scale scale factor <= 1.0
	 * @param filter a name based filter for Patches (can be null)
	 * @param p SIFT extraction parameters
	 * @throws Exception
	 */
	final static protected void extractAndSaveLayerFeatures(
			final List< Layer > layerRange,
			final Rectangle box,
			final double scale,
			final Filter< Patch > filter,
			final FloatArray2DSIFT.Param siftParam,
			final boolean clearCache ) throws ExecutionException, InterruptedException
	{
		final ExecutorService exec = Executors.newFixedThreadPool( p.ppm.maxNumThreadsSift );
		
		/* extract features for all slices and store them to disk */
		final AtomicInteger counter = new AtomicInteger( 0 );
		final ArrayList< Future< ArrayList< Feature > > > siftTasks = new ArrayList< Future< ArrayList< Feature > > >();
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final int layerIndex = i;
			final Rectangle finalBox = box;
			siftTasks.add(
					exec.submit( new Callable< ArrayList< Feature > >()
					{
						@Override
						public ArrayList< Feature > call()
						{
							final Layer layer = layerRange.get( layerIndex );
							
							final String layerName = layerName( layer );
							
							IJ.showProgress( counter.getAndIncrement(), layerRange.size() - 1 );
							
							final List< Patch > patches = filterPatches( layer, filter );
							
							ArrayList< Feature > fs = null;
							if ( !clearCache )
								fs = mpicbg.trakem2.align.Util.deserializeFeatures( layer.getProject(), siftParam, "layer", layer.getId() );
							
							if ( null == fs )
							{
								/* free memory */
								layer.getProject().getLoader().releaseAll();
								
								final FloatArray2DSIFT sift = new FloatArray2DSIFT( siftParam );
								final SIFT ijSIFT = new SIFT( sift );
								fs = new ArrayList< Feature >();
								final ImageProcessor ip = layer.getProject().getLoader().getFlatImage( layer, finalBox, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches, true ).getProcessor();
								ijSIFT.extractFeatures( ip, fs );
								Utils.log( fs.size() + " features extracted for " + layerName );
								
								if ( !mpicbg.trakem2.align.Util.serializeFeatures( layer.getProject(), siftParam, "layer", layer.getId(), fs ) )
									Utils.log( "FAILED to store serialized features for " + layerName );
							}
							else
								Utils.log( fs.size() + " features loaded for " + layerName );
							
							return fs;
						}
					} ) );
		}
		
		/* join */
		try
		{
			for ( final Future< ArrayList< Feature > > fu : siftTasks )
				fu.get();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Feature extraction interrupted." );
			siftTasks.clear();
			exec.shutdown();
			throw e;
		}
		catch ( final ExecutionException e )
		{
			Utils.log( "Execution exception during feature extraction." );
			siftTasks.clear();
			exec.shutdown();
			throw e;
		}
		
		siftTasks.clear();
		exec.shutdown();
	}
	
	/**
	 * 
	 * @param param
	 * @param layerRange
	 * @param fixedLayers
	 * @param emptyLayers
	 * @param box
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 * @throws Exception
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	final public void exec(
			final Param param,
			final List< Layer > layerRange,
			final List< Layer > fixedLayers,
			final List< Layer > emptyLayers,
			final Rectangle box,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final double scale = Math.min( 1.0, Math.min( ( double )param.ppm.sift.maxOctaveSize / ( double )box.width, ( double )param.ppm.sift.maxOctaveSize / ( double )box.height ) );
		
		
		/* create tiles and models for all layers */
		final ArrayList< Tile< ? > > tiles = new ArrayList< Tile< ? > >();
		final AbstractAffineModel2D< ? > m = ( AbstractAffineModel2D< ? > )Util.createModel( param.desiredModelIndex );
		final AbstractAffineModel2D< ? > r = ( AbstractAffineModel2D< ? > )Util.createModel( param.regularizerIndex );
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			if ( param.regularize )
				tiles.add( new Tile( new InterpolatedAffineModel2D( m.copy(), r.copy(), param.lambda ) ) );
			else
				tiles.add( new Tile( m.copy() ) );
		}
		
		/* collect all pairs of slices for which a model could be found */
		final ArrayList< Triple< Integer, Integer, Collection< PointMatch> > > pairs = new ArrayList< Triple< Integer, Integer, Collection< PointMatch > > >();
		
		
		/* extract and save features, overwrite cached files if requested */
		try
		{
			extractAndSaveLayerFeatures( layerRange, box, scale, filter, param.ppm.sift, param.ppm.clearCache );
		}
		catch ( final Exception e )
		{
			return;
		}
		
		/* match and filter feature correspondences */
		int numFailures = 0;
		
		final double pointMatchScale = 1.0 / scale;
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final ArrayList< Thread > threads = new ArrayList< Thread >( param.maxNumThreads );

			final int sliceA = i;
			final Layer layerA = layerRange.get( i );
			final int range = Math.min( layerRange.size(), i + param.maxNumNeighbors + 1 );
			
			final String layerNameA = layerName( layerA );
			
J:			for ( int j = i + 1; j < range; )
			{
				final int numThreads = Math.min( param.maxNumThreads, range - j );
				final ArrayList< Triple< Integer, Integer, Collection< PointMatch > > > models =
					new ArrayList< Triple< Integer, Integer, Collection< PointMatch > > >( numThreads );
				
				for ( int k = 0; k < numThreads; ++k )
					models.add( null );
				
				for ( int t = 0;  t < numThreads && j < range; ++t, ++j )
				{
					final int ti = t;
					final int sliceB = j;
					final Layer layerB = layerRange.get( j );
					
					final String layerNameB = layerName( layerB );
					
					final Thread thread = new Thread()
					{
						@Override
						public void run()
						{
							IJ.showProgress( sliceA, layerRange.size() - 1 );
							
//							Utils.log( "matching " + layerNameB + " -> " + layerNameA + "..." );
						
							ArrayList< PointMatch > candidates = null;
							if ( !param.ppm.clearCache )
								candidates = mpicbg.trakem2.align.Util.deserializePointMatches(
										layerB.getProject(), param.ppm, "layer", layerB.getId(), layerA.getId() );
							
							if ( null == candidates )
							{
								final ArrayList< Feature > fs1 = mpicbg.trakem2.align.Util.deserializeFeatures(
										layerA.getProject(), param.ppm.sift, "layer", layerA.getId() );
								final ArrayList< Feature > fs2 = mpicbg.trakem2.align.Util.deserializeFeatures(
										layerB.getProject(), param.ppm.sift, "layer", layerB.getId() );
								candidates = new ArrayList< PointMatch >( FloatArray2DSIFT.createMatches( fs2, fs1, param.ppm.rod ) );
								
								/* scale the candidates */
								for ( final PointMatch pm : candidates )
								{
									final Point p1 = pm.getP1();
									final Point p2 = pm.getP2();
									final float[] l1 = p1.getL();
									final float[] w1 = p1.getW();
									final float[] l2 = p2.getL();
									final float[] w2 = p2.getW();
									
									l1[ 0 ] *= pointMatchScale;
									l1[ 1 ] *= pointMatchScale;
									w1[ 0 ] *= pointMatchScale;
									w1[ 1 ] *= pointMatchScale;
									l2[ 0 ] *= pointMatchScale;
									l2[ 1 ] *= pointMatchScale;
									w2[ 0 ] *= pointMatchScale;
									w2[ 1 ] *= pointMatchScale;
									
								}
								
								if ( !mpicbg.trakem2.align.Util.serializePointMatches(
										layerB.getProject(), param.ppm, "layer", layerB.getId(), layerA.getId(), candidates ) )
								Utils.log( "Could not store point match candidates for layers " + layerNameB + " and " + layerNameA + "." );
							}
		
							AbstractModel< ? > model;
							switch ( param.expectedModelIndex )
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
							case 4:
								model = new HomographyModel2D();
								break;
							default:
								return;
							}
							
							final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
							
							boolean again = false;
							int nHypotheses = 0;
							try
							{
								do
								{
									again = false;
									final ArrayList< PointMatch > inliers2 = new ArrayList< PointMatch >();
									final boolean modelFound = model.filterRansac(
												candidates,
												inliers2,
												1000,
												param.maxEpsilon,
												param.minInlierRatio,
												param.minNumInliers,
												3 );
									if ( modelFound )
									{
										candidates.removeAll( inliers2 );
										
										if ( param.rejectIdentity )
										{
											final ArrayList< Point > points = new ArrayList< Point >();
											PointMatch.sourcePoints( inliers2, points );
											if ( Transforms.isIdentity( model, points, param.identityTolerance ) )
											{
												IJ.log( "Identity transform for " + inliers2.size() + " matches rejected." );
												again = true;
											}
											else
											{
												++nHypotheses;
												inliers.addAll( inliers2 );
												again = param.multipleHypotheses;
											}
										}
										else
										{
											++nHypotheses;
											inliers.addAll( inliers2 );
											again = param.multipleHypotheses;
										}
									}
								}
								while ( again );
							}
							catch ( final NotEnoughDataPointsException e ) {}
							
							if ( nHypotheses > 0 && param.multipleHypotheses )
							{
								try
								{
										model.fit( inliers );
										PointMatch.apply( inliers, model );
								}
								catch ( final NotEnoughDataPointsException e ) {}
								catch ( final IllDefinedDataPointsException e )
								{
									nHypotheses = 0;
								}
							}
							
							if ( nHypotheses > 0 )
							{								
								Utils.log( layerNameB + " -> " + layerNameA + ": " + inliers.size() + " corresponding features with an average displacement of " + ( PointMatch.meanDistance( inliers ) ) + "px identified." );
								Utils.log( "Estimated transformation model: " + model + ( param.multipleHypotheses ? ( " from " + nHypotheses + " hypotheses" ) : "" ) );
								models.set( ti, new Triple< Integer, Integer, Collection< PointMatch > >( sliceA, sliceB, inliers ) );
							}
							else
							{
								Utils.log( layerNameB + " -> " + layerNameA + ": no correspondences found." );
								return;
							}
						}
					};
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
					Utils.log( "Establishing feature correspondences interrupted." );
					for ( final Thread thread : threads )
						thread.interrupt();
					try
					{
						for ( final Thread thread : threads )
							thread.join();
					}
					catch ( final InterruptedException f ) {}
					return;
				}
				
				threads.clear();
				
				/* collect successfully matches pairs and break the search on gaps */
				for ( int t = 0; t < models.size(); ++t )
				{
					final Triple< Integer, Integer, Collection< PointMatch > > pair = models.get( t );
					if ( pair == null )
					{
						if ( ++numFailures > param.maxNumFailures )
							break J;
					}
					else
					{
						numFailures = 0;
						pairs.add( pair );
					}
				}
			}
		}
		
		
		/* Optimization */
		final TileConfiguration tileConfiguration = new TileConfiguration();
		
		for ( final Triple< Integer, Integer, Collection< PointMatch > > pair : pairs )
		{
			final Tile< ? > t1 = tiles.get( pair.a );
			final Tile< ? > t2 = tiles.get( pair.b );
			
			tileConfiguration.addTile( t1 );
			tileConfiguration.addTile( t2 );
			t2.connect( t1, pair.c );
		}
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final Layer layer = layerRange.get( i );
			if ( fixedLayers.contains( layer ) )
				tileConfiguration.fixTile( tiles.get( i ) );
		}
		
		final List< Tile< ? >  > nonPreAlignedTiles = tileConfiguration.preAlign();
		
		
		IJ.log( "pre-aligned all but " + nonPreAlignedTiles.size() + " tiles" );
		
		tileConfiguration.optimize(
				param.maxEpsilon,
				param.maxIterationsOptimize,
				param.maxPlateauwidthOptimize );
		
		Utils.log( new StringBuffer( "Successfully optimized configuration of " ).append( tiles.size() ).append( " tiles:" ).toString() );
		Utils.log( "  average displacement: " + String.format( "%.3f", tileConfiguration.getError() ) + "px" );
		Utils.log( "  minimal displacement: " + String.format( "%.3f", tileConfiguration.getMinError() ) + "px" );
		Utils.log( "  maximal displacement: " + String.format( "%.3f", tileConfiguration.getMaxError() ) + "px" );
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final AffineTransform b = new AffineTransform();
			b.translate( box.x, box.y );
//			b.scale( 1.0f / scale, 1.0f / scale );
			b.concatenate( ( ( Affine2D< ? > )tiles.get( i ).getModel() ).createAffine() );
//			b.scale( scale, scale );
			b.translate( -box.x, -box.y);
			
			AlignTask.transformPatchesAndVectorData( filterPatches( layerRange.get( i ), filter ), b );
			Display.repaint( layerRange.get( i ) );
		}
			
		Utils.log( "Done." );
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 *
	 * @param layerRange
	 * @param fixedLayers
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 * @throws Exception
	 */
	final public void exec(
			final List< Layer > layerRange,
			final List< Layer > fixedLayers,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{	
		Rectangle box = null;
		final ArrayList< Layer > emptyLayers = new ArrayList< Layer >();
		for ( final Iterator< Layer > it = layerRange.iterator(); it.hasNext(); )
		{
			/* remove empty layers */
			final Layer la = it.next();
			if ( !la.contains( Patch.class, true ) )
			{
				emptyLayers.add( la );
//				it.remove();
			}
			else
			{
				/* accumulate boxes */
				if ( null == box ) // The first layer:
					box = la.getMinimalBoundingBox( Patch.class, true );
				else
					box = box.union( la.getMinimalBoundingBox( Patch.class, true ) );
			}
		}
		
		if ( box == null )
			box = new Rectangle();
		
		if ( fov != null )
			box = box.intersection( fov );
		
		if ( box.width <= 0 || box.height <= 0 )
		{
			Utils.log( "Bounding box empty." );
			return;
		}
		
		if ( layerRange.size() == emptyLayers.size() )
		{
			Utils.log( "All layers in range are empty!" );
			return;
		}
		
		/* do not work if there is only one layer selected */
		if ( layerRange.size() - emptyLayers.size() < 2 )
		{
			Utils.log( "All except one layer in range are empty!" );
			return;
		}

		if ( !p.setup( box ) ) return;
		
		exec( p.clone(), layerRange, fixedLayers, emptyLayers, box, propagateTransform, fov, filter );
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param ref
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final ArrayList< Layer > fixedLayers = new ArrayList< Layer >();
		
		if ( ref - firstIn >= 0 )
			fixedLayers.add( layerRange.get( ref - firstIn ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerRange, fixedLayers, propagateTransform, fov, filter );
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param ref1
	 * @param ref2
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref1,
			final int ref2,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final ArrayList< Layer > fixedLayers = new ArrayList< Layer >();
		
		if ( ref1 - firstIn >= 0 )
			fixedLayers.add( layerRange.get( ref1 - firstIn ) );
		if ( ref2 - firstIn >= 0 )
			fixedLayers.add( layerRange.get( ref2 - firstIn ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerRange, fixedLayers, propagateTransform, fov, filter );
	}
}
