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
import ij.gui.GenericDialog;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.VectorData;
import ini.trakem2.parallel.ExecutorProvider;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Spring;
import mpicbg.models.SpringMesh;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.Transforms;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.align.concurrent.BlockMatchPairCallable;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.util.Triple;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ElasticLayerAlignment
{
	final static public class Param extends mpicbg.trakem2.align.AbstractLayerAlignmentParam implements Serializable
	{
		private static final long serialVersionUID = 3366971916160734613L;

		public boolean isAligned = false;
		
		public float layerScale = 0.1f;
		public float minR = 0.6f;
		public float maxCurvatureR = 10f;
		public float rodR = 0.9f;
		public int searchRadius = 200;
		public int blockRadius = -1;
		
		public boolean useLocalSmoothnessFilter = true;
		public int localModelIndex = 1;
		public float localRegionSigma = searchRadius;
		public float maxLocalEpsilon = searchRadius / 2;
		public float maxLocalTrust = 3;
		
		public int resolutionSpringMesh = 16;
		public float stiffnessSpringMesh = 0.1f;
		public float dampSpringMesh = 0.9f;
		public float maxStretchSpringMesh = 2000.0f;
		public int maxIterationsSpringMesh = 1000;
		public int maxPlateauwidthSpringMesh = 200;
		public boolean useLegacyOptimizer = true;
		
		public boolean setup( final Rectangle box )
		{
			/* Block Matching */
			if ( blockRadius < 0 )
			{
				blockRadius = box.width / resolutionSpringMesh / 2;
			}
			final GenericDialog gdBlockMatching = new GenericDialog( "Elastically align layers: Block Matching parameters" );
			
			gdBlockMatching.addMessage( "Block Matching:" );
			/* TODO suggest isotropic resolution for this parameter */
			gdBlockMatching.addNumericField( "layer_scale :", layerScale, 2 );
			gdBlockMatching.addNumericField( "search_radius :", searchRadius, 0, 6, "px" );
			gdBlockMatching.addNumericField( "block_radius :", blockRadius, 0, 6, "px" );
			/* TODO suggest a resolution that matches searchRadius */
			gdBlockMatching.addNumericField( "resolution :", resolutionSpringMesh, 0 );
			
			gdBlockMatching.addMessage( "Correlation Filters:" );
			gdBlockMatching.addNumericField( "minimal_PMCC_r :", minR, 2 );
			gdBlockMatching.addNumericField( "maximal_curvature_ratio :", maxCurvatureR, 2 );
			gdBlockMatching.addNumericField( "maximal_second_best_r/best_r :", rodR, 2 );
			
			gdBlockMatching.addMessage( "Local Smoothness Filter:" );
			gdBlockMatching.addCheckbox( "use_local_smoothness_filter", useLocalSmoothnessFilter );
			gdBlockMatching.addChoice( "approximate_local_transformation :", Param.modelStrings, Param.modelStrings[ localModelIndex ] );
			gdBlockMatching.addNumericField( "local_region_sigma:", localRegionSigma, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (absolute):", maxLocalEpsilon, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (relative):", maxLocalTrust, 2 );
			
			gdBlockMatching.addMessage( "Miscellaneous:" );
			gdBlockMatching.addCheckbox( "layers_are_pre-aligned", isAligned );
			gdBlockMatching.addNumericField( "test_maximally :", maxNumNeighbors, 0, 6, "layers" );
			
			gdBlockMatching.showDialog();
			
			if ( gdBlockMatching.wasCanceled() )
				return false;
			
			layerScale = ( float )gdBlockMatching.getNextNumber();
			searchRadius = ( int )gdBlockMatching.getNextNumber();
			blockRadius = ( int )gdBlockMatching.getNextNumber();
			resolutionSpringMesh = ( int )gdBlockMatching.getNextNumber();
			minR = ( float )gdBlockMatching.getNextNumber();
			maxCurvatureR = ( float )gdBlockMatching.getNextNumber();
			rodR = ( float )gdBlockMatching.getNextNumber();
			useLocalSmoothnessFilter = gdBlockMatching.getNextBoolean();
			localModelIndex = gdBlockMatching.getNextChoiceIndex();
			localRegionSigma = ( float )gdBlockMatching.getNextNumber();
			maxLocalEpsilon = ( float )gdBlockMatching.getNextNumber();
			maxLocalTrust = ( float )gdBlockMatching.getNextNumber();
			isAligned = gdBlockMatching.getNextBoolean();
			maxNumNeighbors = ( int )gdBlockMatching.getNextNumber();
			
			
			if ( !isAligned )
			{
				if ( !setupSIFT( "Elastically align layers: " ) )
					return false;
				
				/* Geometric filters */
				
				final GenericDialog gd = new GenericDialog( "Elastically align layers: Geometric filters" );
				
				gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
				gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
				gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
				gd.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ expectedModelIndex ] );
				gd.addCheckbox( "ignore constant background", rejectIdentity );
				gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
				gd.addNumericField( "give_up_after :", maxNumFailures, 0, 6, "failures" );
				
				gd.showDialog();
				
				if ( gd.wasCanceled() )
					return false;
				
				maxEpsilon = ( float )gd.getNextNumber();
				minInlierRatio = ( float )gd.getNextNumber();
				minNumInliers = ( int )gd.getNextNumber();
				expectedModelIndex = gd.getNextChoiceIndex();
				rejectIdentity = gd.getNextBoolean();
				identityTolerance = ( float )gd.getNextNumber();
				maxNumFailures = ( int )gd.getNextNumber();
			}
			
			
			/* Optimization */
			final GenericDialog gdOptimize = new GenericDialog( "Elastically align layers: Optimization" );
			
			gdOptimize.addMessage( "Approximate Optimizer:" );
			gdOptimize.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ desiredModelIndex ] );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsOptimize, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthOptimize, 0 );
			
			gdOptimize.addMessage( "Spring Mesh:" );
			gdOptimize.addNumericField( "stiffness :", stiffnessSpringMesh, 2 );
			gdOptimize.addNumericField( "maximal_stretch :", maxStretchSpringMesh, 2, 6, "px" );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsSpringMesh, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthSpringMesh, 0 );
			gdOptimize.addCheckbox("use_legacy_optimizer :", useLegacyOptimizer);
			
			gdOptimize.showDialog();
			
			if ( gdOptimize.wasCanceled() )
				return false;
			
			desiredModelIndex = gdOptimize.getNextChoiceIndex();
			maxIterationsOptimize = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthOptimize = ( int )gdOptimize.getNextNumber();
			
			stiffnessSpringMesh = ( float )gdOptimize.getNextNumber();
			maxStretchSpringMesh = ( float )gdOptimize.getNextNumber();
			maxIterationsSpringMesh = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthSpringMesh = ( int )gdOptimize.getNextNumber();
			useLegacyOptimizer = gdOptimize.getNextBoolean();
			
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
				final float maxEpsilon,
				final int maxIterationsOptimize,
				final int maxNumFailures,
				final int maxNumNeighbors,
				final int maxNumThreads,
				final int maxPlateauwidthOptimize,
				final float minInlierRatio,
				final int minNumInliers,
				final boolean multipleHypotheses,
				final boolean rejectIdentity,
				final boolean visualize,
				
				final int blockRadius,
				final float dampSpringMesh,
				final float layerScale,
				final int localModelIndex,
				final float localRegionSigma,
				final float maxCurvatureR,
				final int maxIterationsSpringMesh,
				final float maxLocalEpsilon,
				final float maxLocalTrust,
				final int maxPlateauwidthSpringMesh,
				final boolean useLegacyOptimizer,
				final float maxStretchSpringMesh,
				final float minR,
				final int resolutionSpringMesh,
				final float rodR,
				final int searchRadius,
				final float stiffnessSpringMesh,
				final boolean useLocalSmoothnessFilter )
		{
			super(
					SIFTfdBins,
					SIFTfdSize,
					SIFTinitialSigma,
					SIFTmaxOctaveSize,
					SIFTminOctaveSize,
					SIFTsteps,
					clearCache,
					maxNumThreadsSift,
					rod,
					desiredModelIndex,
					expectedModelIndex,
					identityTolerance,
					maxEpsilon,
					maxIterationsOptimize,
					maxNumFailures,
					maxNumNeighbors,
					maxNumThreads,
					maxPlateauwidthOptimize,
					minInlierRatio,
					minNumInliers,
					multipleHypotheses,
					rejectIdentity,
					visualize );
			
			this.isAligned = isAligned;
			this.blockRadius = blockRadius;
			this.dampSpringMesh = dampSpringMesh;
			this.layerScale = layerScale;
			this.localModelIndex = localModelIndex;
			this.localRegionSigma = localRegionSigma;
			this.maxCurvatureR = maxCurvatureR;
			this.maxIterationsSpringMesh = maxIterationsSpringMesh;
			this.maxLocalEpsilon = maxLocalEpsilon;
			this.maxLocalTrust = maxLocalTrust;
			this.maxPlateauwidthSpringMesh = maxPlateauwidthSpringMesh;
			this.useLegacyOptimizer = useLegacyOptimizer;
			this.maxStretchSpringMesh = maxStretchSpringMesh;
			this.minR = minR;
			this.resolutionSpringMesh = resolutionSpringMesh;
			this.rodR = rodR;
			this.searchRadius = searchRadius;
			this.stiffnessSpringMesh = stiffnessSpringMesh;
			this.useLocalSmoothnessFilter = useLocalSmoothnessFilter;
		}
		
		@Override
		public Param clone()
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
					maxEpsilon,
					maxIterationsOptimize,
					maxNumFailures,
					maxNumNeighbors,
					maxNumThreads,
					maxPlateauwidthOptimize,
					minInlierRatio,
					minNumInliers,
					multipleHypotheses,
					rejectIdentity,
					visualize,
					
					blockRadius,
					dampSpringMesh,
					layerScale,
					localModelIndex,
					localRegionSigma,
					maxCurvatureR,
					maxIterationsSpringMesh,
					maxLocalEpsilon,
					maxLocalTrust,
					maxPlateauwidthSpringMesh,
					useLegacyOptimizer,
					maxStretchSpringMesh,
					minR,
					resolutionSpringMesh,
					rodR,
					searchRadius,
					stiffnessSpringMesh,
					useLocalSmoothnessFilter );
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
	 * 
	 * @param param
	 * @param layerRange
	 * @param fixedLayers
	 * @param emptyLayers
	 * @param box
	 * @param filter
	 * @throws Exception
	 */
	@SuppressWarnings( "deprecation" )
	final public void exec(
			final Param param,
			final Project project,
			final List< Layer > layerRange,
			final Set< Layer > fixedLayers,
			final Set< Layer > emptyLayers,
			final Rectangle box,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Filter< Patch > filter ) throws Exception
	{
        ExecutorService service = ExecutorProvider.getExecutorService(1.0f);
		
		
		/* create tiles and models for all layers */
		final ArrayList< Tile< ? > > tiles = new ArrayList< Tile< ? > >();
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			switch ( param.desiredModelIndex )
			{
			case 0:
				tiles.add( new Tile< TranslationModel2D >( new TranslationModel2D() ) );
				break;
			case 1:
				tiles.add( new Tile< RigidModel2D >( new RigidModel2D() ) );
				break;
			case 2:
				tiles.add( new Tile< SimilarityModel2D >( new SimilarityModel2D() ) );
				break;
			case 3:
				tiles.add( new Tile< AffineModel2D >( new AffineModel2D() ) );
				break;
			case 4:
				tiles.add( new Tile< HomographyModel2D >( new HomographyModel2D() ) );
				break;
			default:
				return;
			}
		}
		
		/* collect all pairs of slices for which a model could be found */
		final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > pairs =
                new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >();
		
		
		if ( !param.isAligned )
		{
		    preAlignStack(param, project, layerRange, box, filter, pairs);
		}
		else
		{
			for ( int i = 0; i < layerRange.size(); ++i )
			{
				final int range = Math.min( layerRange.size(), i + param.maxNumNeighbors + 1 );
				
				for ( int j = i + 1; j < range; ++j )
				{
					pairs.add(new Triple< Integer, Integer, AbstractModel< ? > >(
                            i, j, new TranslationModel2D() ) );
				}
			}
		}
		
		/* Elastic alignment */
		
		/* Initialization */
		final TileConfiguration initMeshes = new TileConfiguration();
		
		final int meshWidth = ( int )Math.ceil( box.width * param.layerScale );
		final int meshHeight = ( int )Math.ceil( box.height * param.layerScale );
		
		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( layerRange.size() );
		for ( int i = 0; i < layerRange.size(); ++i )
        {
			meshes.add(
					new SpringMesh(
							param.resolutionSpringMesh,
							meshWidth,
							meshHeight,
							param.stiffnessSpringMesh,
							param.maxStretchSpringMesh * param.layerScale,
							param.dampSpringMesh ) );
        }
		
		//final int blockRadius = Math.max( 32, meshWidth / p.resolutionSpringMesh / 2 );
		final int blockRadius = Math.max( 16, mpicbg.util.Util.roundPos( param.layerScale * param.blockRadius ) );
		
		Utils.log( "effective block radius = " + blockRadius );

        final ArrayList<Future<BlockMatchPairCallable.BlockMatchResults>> futures =
                new ArrayList<Future<BlockMatchPairCallable.BlockMatchResults>>(pairs.size());

		
		for ( final Triple< Integer, Integer, AbstractModel< ? > > pair : pairs )
		{
			/* free memory */
			project.getLoader().releaseAll();
			
			final SpringMesh m1 = meshes.get( pair.a );
			final SpringMesh m2 = meshes.get( pair.b );


			final ArrayList< Vertex > v1 = m1.getVertices();
			final ArrayList< Vertex > v2 = m2.getVertices();

			final Layer layer1 = layerRange.get( pair.a );
			final Layer layer2 = layerRange.get( pair.b );

			final boolean layer1Fixed = fixedLayers.contains(layer1);
			final boolean layer2Fixed = fixedLayers.contains(layer2);


			if ( !( layer1Fixed && layer2Fixed ) )
			{
                BlockMatchPairCallable bmpc = new BlockMatchPairCallable(
                        pair,
                        layerRange,
                        layer1Fixed, layer2Fixed,
                        filter,
                        param,
                        v1, v2,
                        box);
                futures.add(service.submit(bmpc));
            }
        }

        for (final Future<BlockMatchPairCallable.BlockMatchResults> future : futures)
        {
            final BlockMatchPairCallable.BlockMatchResults results = future.get();
            final Collection<PointMatch> pm12 = results.pm12, pm21 = results.pm21;
            final Triple<Integer, Integer, AbstractModel<?>> pair = results.pair;
            final Tile< ? > t1 = tiles.get( pair.a );
            final Tile< ? > t2 = tiles.get( pair.b );
            final SpringMesh m1 = meshes.get( pair.a );
            final SpringMesh m2 = meshes.get( pair.b );
            final float springConstant  = 1.0f / ( pair.b - pair.a );
            final boolean layer1Fixed = results.layer1Fixed;
            final boolean layer2Fixed = results.layer2Fixed;

            if (layer1Fixed)
            {
                initMeshes.fixTile( t1 );
            }
            else
            {
                if ( param.useLocalSmoothnessFilter )
                {
                    Utils.log( pair.a + " > " + pair.b + ": " + pm12.size() +
                            " candidates passed local smoothness filter." );
                }
                else
                {
                    Utils.log( pair.a + " > " + pair.b + ": found " + pm12.size() +
                            " correspondences." );
                }

                for ( final PointMatch pm : pm12 )
                {
                    final Vertex p1 = ( Vertex )pm.getP1();
                    final Vertex p2 = new Vertex( pm.getP2() );
                    p1.addSpring( p2, new Spring( 0, springConstant ) );
                    m2.addPassiveVertex( p2 );
                }

                /*
                * adding Tiles to the initialing TileConfiguration, adding a Tile
                * multiple times does not harm because the TileConfiguration is
                * backed by a Set.
                */
                if ( pm12.size() > pair.c.getMinNumMatches() )
                {
                    initMeshes.addTile( t1 );
                    initMeshes.addTile( t2 );
                    t1.connect( t2, pm12 );
                }
            }


            if ( layer2Fixed )
                initMeshes.fixTile( t2 );
            else
            {
                if ( param.useLocalSmoothnessFilter )
                {
                    Utils.log( pair.a + " < " + pair.b + ": " + pm21.size() +
                            " candidates passed local smoothness filter." );
                }
                else
                {
                    Utils.log( pair.a + " < " + pair.b + ": found " + pm21.size() +
                            " correspondences." );
                }

                for ( final PointMatch pm : pm21 )
                {
                    final Vertex p1 = ( Vertex )pm.getP1();
                    final Vertex p2 = new Vertex( pm.getP2() );
                    p1.addSpring( p2, new Spring( 0, springConstant ) );
                    m1.addPassiveVertex( p2 );
                }

                /*
                * adding Tiles to the initialing TileConfiguration, adding a Tile
                * multiple times does not harm because the TileConfiguration is
                * backed by a Set.
                */
                if ( pm21.size() > pair.c.getMinNumMatches() )
                {
                    initMeshes.addTile( t1 );
                    initMeshes.addTile( t2 );
                    t2.connect( t1, pm21 );
                }
            }

            Utils.log( pair.a + " <> " + pair.b + " spring constant = " + springConstant );

        }

        /* pre-align by optimizing a piecewise linear model */
		initMeshes.optimize(
				param.maxEpsilon * param.layerScale,
				param.maxIterationsSpringMesh,
				param.maxPlateauwidthSpringMesh );
		for ( int i = 0; i < layerRange.size(); ++i )
			meshes.get( i ).init( tiles.get( i ).getModel() );

		/* optimize the meshes */
		try
		{
			final long t0 = System.currentTimeMillis();
			Utils.log( "Optimizing spring meshes..." );
			
			if ( param.useLegacyOptimizer )
			{
				Utils.log( "  ...using legacy optimizer...");
				SpringMesh.optimizeMeshes2(
						meshes,
						param.maxEpsilon * param.layerScale,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh,
						param.visualize );
			}
			else
			{
				SpringMesh.optimizeMeshes(
						meshes,
						param.maxEpsilon * param.layerScale,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh,
						param.visualize );
			}

			Utils.log("Done optimizing spring meshes. Took " + (System.currentTimeMillis() - t0) + " ms");
			
		}
		catch ( final NotEnoughDataPointsException e )
		{
			Utils.log( "There were not enough data points to get the spring mesh optimizing." );
			e.printStackTrace();
			return;
		}
		
		/* translate relative to bounding box */
		for ( final SpringMesh mesh : meshes )
		{
			for ( final PointMatch pm : mesh.getVA().keySet() )
			{
				final Point p1 = pm.getP1();
				final Point p2 = pm.getP2();
				final float[] l = p1.getL();
				final float[] w = p2.getW();
				l[ 0 ] = l[ 0 ] / param.layerScale + box.x;
				l[ 1 ] = l[ 1 ] / param.layerScale + box.y;
				w[ 0 ] = w[ 0 ] / param.layerScale + box.x;
				w[ 1 ] = w[ 1 ] / param.layerScale + box.y;
			}
		}
		
		/* free memory */
		project.getLoader().releaseAll();
		
		final Layer first = layerRange.get( 0 );
		final List< Layer > layers = first.getParent().getLayers();

        final LayerSet ls = first.getParent();
        Area infArea = AreaUtils.infiniteArea();
        final List<VectorData> vectorData = new ArrayList<VectorData>();
        for (final Layer layer : ls.getLayers()) {
            vectorData.addAll(
                    Utils.castCollection(layer.getDisplayables(VectorData.class, false, true),
                            VectorData.class, true));
        }
        vectorData.addAll(Utils.castCollection(ls.getZDisplayables(VectorData.class, true),
                VectorData.class, true));

		/* transfer layer transform into patch transforms and append to patches */
		if ( propagateTransformBefore || propagateTransformAfter )
		{
			if ( propagateTransformBefore )
			{
				final MovingLeastSquaresTransform2 mlt = makeMLST2( meshes.get( 0 ).getVA().keySet() );
				final int firstLayerIndex = first.getParent().getLayerIndex( first.getId() );
				for ( int i = 0; i < firstLayerIndex; ++i )
                {
					applyTransformToLayer( layers.get( i ), mlt, filter );
                    for (final VectorData vd : vectorData)
                    {
                        vd.apply(layers.get(i), infArea, mlt);
                    }
                }

			}
			if ( propagateTransformAfter )
			{
				final Layer last = layerRange.get( layerRange.size() - 1 );
				final MovingLeastSquaresTransform2 mlt = makeMLST2( meshes.get( meshes.size() - 1 ).getVA().keySet() );
				final int lastLayerIndex = last.getParent().getLayerIndex( last.getId() );
				for ( int i = lastLayerIndex + 1; i < layers.size(); ++i )
                {
                    applyTransformToLayer( layers.get( i ), mlt, filter );
                    for (final VectorData vd : vectorData)
                    {
                        vd.apply(layers.get(i), infArea, mlt);
                    }
                }
			}
		}
		for ( int l = 0; l < layerRange.size(); ++l )
		{
			IJ.showStatus( "Applying transformation to patches ..." );
			IJ.showProgress( 0, layerRange.size() );
			
			final Layer layer = layerRange.get( l );
			
			final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
			mlt.setModel( AffineModel2D.class );
			mlt.setAlpha( 2.0f );
			mlt.setMatches( meshes.get( l ).getVA().keySet() );
			
			applyTransformToLayer( layer, mlt, filter );

            for (final VectorData vd : vectorData)
            {
                vd.apply(layer, infArea, mlt);
            }
					
			if ( Thread.interrupted() )
			{
				Utils.log( "Interrupted during applying transformations to patches.  No all patches have been updated.  Re-generate mipmaps manually." );
			}
			
			IJ.showProgress( l + 1, layerRange.size() );
		}
		
		/* update patch mipmaps */
		final int firstLayerIndex;
		final int lastLayerIndex;
		
		if ( propagateTransformBefore )
			firstLayerIndex = 0;
		else
		{
			firstLayerIndex = first.getParent().getLayerIndex( first.getId() );
		}
		if ( propagateTransformAfter )
			 lastLayerIndex = layers.size() - 1;
		else
		{
			final Layer last = layerRange.get( layerRange.size() - 1 );
			lastLayerIndex = last.getParent().getLayerIndex( last.getId() );
		}
		
		for ( int i = firstLayerIndex; i <= lastLayerIndex; ++i )
		{
			final Layer layer = layers.get( i );
			if ( !( emptyLayers.contains( layer ) || fixedLayers.contains( layer ) ) )
			{
				for ( final Patch patch : AlignmentUtils.filterPatches( layer, filter ) )
					patch.updateMipMaps();
			}
		}
		
		Utils.log( "Done." );
	}
	
	final static protected MovingLeastSquaresTransform2 makeMLST2( final Set< PointMatch > matches ) throws Exception
	{
		final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
		mlt.setModel( AffineModel2D.class );
		mlt.setAlpha( 2.0f );
		mlt.setMatches( matches );
		return mlt;
	}
	
	final static protected void applyTransformToLayer( final Layer layer, final MovingLeastSquaresTransform2 mlt, final Filter< Patch > filter ) throws InterruptedException
	{
		/*
		 * Setting a transformation to a patch can take some time because
		 * the new bounding box needs to be estimated which requires the
		 * TransformMesh to be generated and all vertices iterated.
		 * 
		 * Therefore multithreading.
		 */
		final List< Patch > patches = AlignmentUtils.filterPatches( layer, filter );
		
		final ArrayList< Thread > applyThreads = new ArrayList< Thread >( p.maxNumThreads );
		final AtomicInteger ai = new AtomicInteger( 0 );
		for ( int t = 0; t < p.maxNumThreads; ++t )
		{
			final Thread thread = new Thread(
					new Runnable()
					{
						@Override
						final public void run()
						{
							try
							{
								for ( int i = ai.getAndIncrement(); i < patches.size() && !Thread.interrupted(); i = ai.getAndIncrement() )
									mpicbg.trakem2.align.Util.applyLayerTransformToPatch( patches.get( i ), mlt.copy() );
							}
							catch ( final Exception e )
							{
								e.printStackTrace();
							}
						}
					} );
			applyThreads.add( thread );
			thread.start();
		}
		
		for ( final Thread thread : applyThreads )
			thread.join();
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 *
	 * @param layerRange
	 * @param fixedLayers
	 * @param propagateTransformBefore
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 * @throws Exception
	 */
	final public void exec(
			final Project project,
			final List< Layer > layerRange,
			final Set< Layer > fixedLayers,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{	
		Rectangle box = null;
		final HashSet< Layer > emptyLayers = new HashSet< Layer >();
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
		
		exec( p.clone(), project, layerRange, fixedLayers, emptyLayers, box, propagateTransformBefore, propagateTransformAfter, filter );
	}

    private void preAlignStack(final Param param, final Project project,
                               final List<Layer> layerRange, final Rectangle box,
                               final Filter<Patch> filter,
                               final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > pairs)
    {
        final double scale = Math.min( 1.0, Math.min( ( double )param.ppm.sift.maxOctaveSize / ( double )box.width, ( double )param.ppm.sift.maxOctaveSize / ( double )box.height ) );

        /* extract and save features, overwrite cached files if requested */
        try
        {
            AlignmentUtils.extractAndSaveLayerFeatures( layerRange, box, scale, filter, param.ppm.sift, param.ppm.clearCache, param.ppm.maxNumThreadsSift );
        }
        catch ( final Exception e )
        {
            return;
        }

        /* match and filter feature correspondences */
        int numFailures = 0;

        final double pointMatchScale = param.layerScale / scale;

        for ( int i = 0; i < layerRange.size(); ++i )
        {
            final ArrayList< Thread > threads = new ArrayList< Thread >( param.maxNumThreads );

            final int sliceA = i;
            final Layer layerA = layerRange.get( i );
            final int range = Math.min( layerRange.size(), i + param.maxNumNeighbors + 1 );

            final String layerNameA = layerName( layerA );

            for ( int j = i + 1; j < range; )
J:            {
                final int numThreads = Math.min( param.maxNumThreads, range - j );
                final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > models =
                        new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >( numThreads );

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

                            Utils.log( "matching " + layerNameB + " -> " + layerNameA + "..." );

                            ArrayList< PointMatch > candidates = null;
                            if ( !param.ppm.clearCache )
                                candidates = mpicbg.trakem2.align.Util.deserializePointMatches(
                                        project, param.ppm, "layer", layerB.getId(), layerA.getId() );

                            if ( null == candidates )
                            {
                                final ArrayList< Feature > fs1 = mpicbg.trakem2.align.Util.deserializeFeatures(
                                        project, param.ppm.sift, "layer", layerA.getId() );
                                final ArrayList< Feature > fs2 = mpicbg.trakem2.align.Util.deserializeFeatures(
                                        project, param.ppm.sift, "layer", layerB.getId() );
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
                                        project, param.ppm, "layer", layerB.getId(), layerA.getId(), candidates ) )
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

                            boolean modelFound;
                            boolean again = false;
                            try
                            {
                                do
                                {
                                    again = false;
                                    modelFound = model.filterRansac(
                                            candidates,
                                            inliers,
                                            1000,
                                            param.maxEpsilon * param.layerScale,
                                            param.minInlierRatio,
                                            param.minNumInliers,
                                            3 );
                                    if ( modelFound && param.rejectIdentity )
                                    {
                                        final ArrayList< Point > points = new ArrayList< Point >();
                                        PointMatch.sourcePoints( inliers, points );
                                        if ( Transforms.isIdentity( model, points, param.identityTolerance *  param.layerScale ) )
                                        {
                                            IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
                                            candidates.removeAll( inliers );
                                            inliers.clear();
                                            again = true;
                                        }
                                    }
                                }
                                while ( again );
                            }
                            catch ( final NotEnoughDataPointsException e )
                            {
                                modelFound = false;
                            }

                            if ( modelFound )
                            {
                                Utils.log( layerNameB + " -> " + layerNameA + ": " + inliers.size() + " corresponding features with an average displacement of " + ( PointMatch.meanDistance( inliers ) / param.layerScale ) + "px identified." );
                                Utils.log( "Estimated transformation model: " + model );
                                models.set( ti, new Triple< Integer, Integer, AbstractModel< ? > >( sliceA, sliceB, model ) );
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
                    final Triple< Integer, Integer, AbstractModel< ? > > pair = models.get( t );
                    if ( pair == null )
                    {
                        if ( ++numFailures > param.maxNumFailures )
                        {
                            break J;
                        }
                    }
                    else
                    {
                        numFailures = 0;
                        pairs.add( pair );
                    }
                }
            }
        }
    }


	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param propagateTransformBefore
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final HashSet< Layer > fixedLayers = new HashSet< Layer >();
		
		if ( ref - firstIn >= 0 )
			fixedLayers.add( layerRange.get( ref - firstIn ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerSet.getProject(), layerRange, fixedLayers, propagateTransformBefore, propagateTransformAfter, fov, filter );
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
		if ( firstIn < lastIn )
			exec( layerSet, firstIn, lastIn, ref, false, propagateTransform, fov, filter );
		else
			exec( layerSet, firstIn, lastIn, ref, propagateTransform, false, fov, filter );
	}
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param ref1
	 * @param ref2
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref1,
			final int ref2,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final HashSet< Layer > fixedLayers = new HashSet< Layer >();
		
		if ( ref1 - first >= 0 )
			fixedLayers.add( layerRange.get( ref1 - first ) );
		if ( ref2 - first >= 0 )
			fixedLayers.add( layerRange.get( ref2 - first ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerSet.getProject(), layerRange, fixedLayers, propagateTransformBefore, propagateTransformAfter, fov, filter );
	}
}
