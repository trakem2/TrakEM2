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
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Patch.PatchImage;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.ij.SIFT;
import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Spring;
import mpicbg.models.SpringMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.align.Align.ParamOptimize;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.util.Triple;
import mpicbg.util.Util;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ElasticMontage
{
	final static public class Param implements Serializable
	{
        private static final long serialVersionUID = 8017492269521223930L;

        public ParamOptimize po = new ParamOptimize();
		{
			po.maxEpsilon = 25.0f;
			po.minInlierRatio = 0.0f;
			po.minNumInliers = 12;
			po.expectedModelIndex = 0;
			po.desiredModelIndex = 0;
			po.rejectIdentity = true;
			po.identityTolerance = 5.0f;
		}

		public boolean isAligned = false;

		public boolean tilesAreInPlace = true;

		/**
		 * Block matching
		 */
		public double bmScale = 0.5f;
		public float bmMinR = 0.5f;
		public float bmMaxCurvatureR = 10f;
		public float bmRodR = 0.9f;
		public int bmSearchRadius = 25;
		public int bmBlockRadius = -1;

		public boolean bmUseLocalSmoothnessFilter = false;
		public int bmLocalModelIndex = 1;
		public float bmLocalRegionSigma = bmSearchRadius;
		public float bmMaxLocalEpsilon = bmSearchRadius / 2;
		public float bmMaxLocalTrust = 3;

		/**
		 * Spring mesh
		 */
		public double springLengthSpringMesh = 100;
		public double stiffnessSpringMesh = 0.1f;
		public double dampSpringMesh = 0.9f;
		public double maxStretchSpringMesh = 2000.0f;
		public int maxIterationsSpringMesh = 1000;
		public int maxPlateauwidthSpringMesh = 200;
		public boolean useLegacyOptimizer = true;

		/**
		 * Visualize spring mesh optimization
		 */
		public boolean visualize = false;

		/**
		 * Change this in case you want to limit the number of parallel threads to a specific number.
		 */
		public int maxNumThreads = Runtime.getRuntime().availableProcessors();

		public boolean setup()
		{
			/* Block Matching */
			if ( bmBlockRadius < 0 )
			{
				bmBlockRadius = Util.roundPos( springLengthSpringMesh / 2 );
			}
			final GenericDialog gdBlockMatching = new GenericDialog( "Elastic montage: Block Matching and Spring Meshes" );

			gdBlockMatching.addMessage( "Block Matching:" );
			gdBlockMatching.addNumericField( "patch_scale :", bmScale, 2 );
			gdBlockMatching.addNumericField( "search_radius :", bmSearchRadius, 0, 6, "px" );
			gdBlockMatching.addNumericField( "block_radius :", bmBlockRadius, 0, 6, "px" );

			gdBlockMatching.addMessage( "Correlation Filters:" );
			gdBlockMatching.addNumericField( "minimal_PMCC_r :", bmMinR, 2 );
			gdBlockMatching.addNumericField( "maximal_curvature_ratio :", bmMaxCurvatureR, 2 );
			gdBlockMatching.addNumericField( "maximal_second_best_r/best_r :", bmRodR, 2 );

			gdBlockMatching.addMessage( "Local Smoothness Filter:" );
			gdBlockMatching.addCheckbox( "use_local_smoothness_filter", bmUseLocalSmoothnessFilter );
			gdBlockMatching.addChoice( "approximate_local_transformation :", ParamOptimize.modelStrings, ParamOptimize.modelStrings[ bmLocalModelIndex ] );
			gdBlockMatching.addNumericField( "local_region_sigma:", bmLocalRegionSigma, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (absolute):", bmMaxLocalEpsilon, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (relative):", bmMaxLocalTrust, 2 );

			gdBlockMatching.addMessage( "Montage :" );
			gdBlockMatching.addCheckbox( "tiles_are_pre-montaged", isAligned );

			gdBlockMatching.showDialog();

			if ( gdBlockMatching.wasCanceled() )
				return false;

			bmScale = gdBlockMatching.getNextNumber();
			bmSearchRadius = ( int )gdBlockMatching.getNextNumber();
			bmBlockRadius = ( int )gdBlockMatching.getNextNumber();
			bmMinR = ( float )gdBlockMatching.getNextNumber();
			bmMaxCurvatureR = ( float )gdBlockMatching.getNextNumber();
			bmRodR = ( float )gdBlockMatching.getNextNumber();

			bmUseLocalSmoothnessFilter = gdBlockMatching.getNextBoolean();
			bmLocalModelIndex = gdBlockMatching.getNextChoiceIndex();
			bmLocalRegionSigma = ( float )gdBlockMatching.getNextNumber();
			bmMaxLocalEpsilon = ( float )gdBlockMatching.getNextNumber();
			bmMaxLocalTrust = ( float )gdBlockMatching.getNextNumber();

			isAligned = gdBlockMatching.getNextBoolean();


			final GenericDialog gdSpringMesh = new GenericDialog( "Elastic montage: Spring Meshes" );

			/* TODO suggest a resolution that matches maxEpsilon */
			gdSpringMesh.addNumericField( "spring_length :", springLengthSpringMesh, 2, 6, "px" );
			gdSpringMesh.addNumericField( "stiffness :", stiffnessSpringMesh, 2 );
			gdSpringMesh.addNumericField( "maximal_stretch :", maxStretchSpringMesh, 2, 6, "px" );
			gdSpringMesh.addNumericField( "maximal_iterations :", maxIterationsSpringMesh, 0 );
			gdSpringMesh.addNumericField( "maximal_plateauwidth :", maxPlateauwidthSpringMesh, 0 );
			gdSpringMesh.addCheckbox( "use_legacy_optimizer :", useLegacyOptimizer );


			gdSpringMesh.showDialog();

			if ( gdSpringMesh.wasCanceled() )
				return false;

			springLengthSpringMesh = gdSpringMesh.getNextNumber();
			stiffnessSpringMesh = gdSpringMesh.getNextNumber();
			maxStretchSpringMesh = gdSpringMesh.getNextNumber();
			maxIterationsSpringMesh = ( int )gdSpringMesh.getNextNumber();
			maxPlateauwidthSpringMesh = ( int )gdSpringMesh.getNextNumber();
			useLegacyOptimizer = gdSpringMesh.getNextBoolean();

			if ( isAligned )
				po.desiredModelIndex = 3;
			else
			{
				if ( !po.setup( "Elastic montage : SIFT based pre-montage" ) )
					return false;

				final GenericDialog gdSIFT = new GenericDialog( "Elastic montage : SIFT based pre-montage: Miscellaneous" );
				gdSIFT.addCheckbox( "tiles_are_roughly_in_place", tilesAreInPlace );
				gdSIFT.showDialog();
				if ( gdSIFT.wasCanceled() )
					return false;

				tilesAreInPlace = gdSIFT.getNextBoolean();
			}

			return true;
		}

		@Override
		public Param clone()
		{
			final Param clone = new Param();
			clone.po = po.clone();
			clone.tilesAreInPlace = tilesAreInPlace;

			clone.isAligned = isAligned;

			clone.bmScale = bmScale;
			clone.bmMinR = bmMinR;
			clone.bmMaxCurvatureR = bmMaxCurvatureR;
			clone.bmRodR = bmRodR;

			clone.bmUseLocalSmoothnessFilter = bmUseLocalSmoothnessFilter;
			clone.bmLocalModelIndex = bmLocalModelIndex;
			clone.bmLocalRegionSigma = bmLocalRegionSigma;
			clone.bmMaxLocalEpsilon = bmMaxLocalEpsilon;
			clone.bmMaxLocalTrust = bmMaxLocalTrust;

			clone.springLengthSpringMesh = springLengthSpringMesh;
			clone.stiffnessSpringMesh = stiffnessSpringMesh;
			clone.dampSpringMesh = dampSpringMesh;
			clone.maxStretchSpringMesh = maxStretchSpringMesh;
			clone.maxIterationsSpringMesh = maxIterationsSpringMesh;
			clone.maxPlateauwidthSpringMesh = maxPlateauwidthSpringMesh;
			clone.useLegacyOptimizer = useLegacyOptimizer;

			clone.visualize = visualize;

			clone.maxNumThreads = maxNumThreads;

			return clone;
		}
	}

	final static Param p = new Param();

	final static public Param setup()
	{
		return p.setup() ? p.clone() : null;
	}


	final static private String patchName( final Patch patch )
	{
		return new StringBuffer( "patch `" )
			.append( patch.getTitle() )
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
	final static protected void extractAndSaveFeatures(
			final List< AbstractAffineTile2D< ? > > tiles,
			final FloatArray2DSIFT.Param siftParam,
			final boolean clearCache ) throws Exception
	{
		final ExecutorService exec = Executors.newFixedThreadPool( p.maxNumThreads );

		/* extract features for all slices and store them to disk */
		final AtomicInteger counter = new AtomicInteger( 0 );
		final ArrayList< Future< ArrayList< Feature > > > siftTasks = new ArrayList< Future< ArrayList< Feature > > >();

		for ( int i = 0; i < tiles.size(); ++i )
		{
			final int tileIndex = i;
			siftTasks.add(
					exec.submit( new Callable< ArrayList< Feature > >()
					{
						@Override
						public ArrayList< Feature > call()
						{
							final AbstractAffineTile2D< ? > tile = tiles.get( tileIndex );

							final String patchName = patchName( tile.getPatch() );

							IJ.showProgress( counter.getAndIncrement(), tiles.size() - 1 );

							ArrayList< Feature > fs = null;
							if ( !clearCache )
								fs = mpicbg.trakem2.align.Util.deserializeFeatures( tile.getPatch().getProject(), siftParam, null, tile.getPatch().getId() );

							if ( null == fs )
							{
								final FloatArray2DSIFT sift = new FloatArray2DSIFT( siftParam );
								final SIFT ijSIFT = new SIFT( sift );
								fs = new ArrayList< Feature >();
								final ByteProcessor ip = tile.createMaskedByteImage();
								ijSIFT.extractFeatures( ip, fs );
								Utils.log( fs.size() + " features extracted for " + patchName );

								if ( !mpicbg.trakem2.align.Util.serializeFeatures( tile.getPatch().getProject(), siftParam, null, tile.getPatch().getId(), fs ) )
									Utils.log( "FAILED to store serialized features for " + patchName );
							}
							else
								Utils.log( fs.size() + " features loaded for " + patchName );

							return fs;
						}
					} ) );
		}

		/* join */
		for ( final Future< ArrayList< Feature > > fu : siftTasks )
			fu.get();

		siftTasks.clear();
		exec.shutdown();
	}

	final static protected FloatProcessor scaleByte( final ByteProcessor bp )
	{
		final FloatProcessor fp = new FloatProcessor( bp.getWidth(), bp.getHeight() );
		final byte[] bytes = ( byte[] )bp.getPixels();
		final float[] floats = ( float[] )fp.getPixels();
		for ( int i = 0; i < bytes.length; ++i )
			floats[ i ] = ( bytes[ i ] & 0xff ) / 255.0f;

		return fp;
	}


	final public void exec(
			final List< Patch > patches,
			final Set< Patch > fixedPatches ) throws Exception
	{
		/* make sure that passed patches are ok */
		if ( patches.size() < 2 )
		{
			Utils.log( "Elastic montage requires at least 2 patches to be montaged.  You passed me " + patches.size() );
			return;
		}
		final Project project = patches.get( 0 ).getProject();
		for ( final Patch patch : patches )
		{
			if ( patch.getProject() != project )
			{
				Utils.log( "Elastic montage requires all patches to be member of a single project.  You passed me patches from several projects." );
				return;
			}
		}
		for ( final Patch patch : fixedPatches )
		{
			if ( patch.getProject() != project )
			{
				Utils.log( "Elastic montage requires all fixed patches to be member of a single project.  You passed me fixed patches from several projects." );
				return;
			}
		}

		final Param param = setup();
		if ( param == null )
			return;
		else
			exec( param, patches, fixedPatches );
	}


	@SuppressWarnings( "deprecation" )
	final public void exec(
			final Param param,
			final List< Patch > patches,
			final Set< Patch > fixedPatches ) throws Exception
	{
		/* free memory */
		patches.get( 0 ).getProject().getLoader().releaseAll();

		/* create tiles and models for all patches */
		final ArrayList< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final ArrayList< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( param.po, patches, fixedPatches, tiles, fixedTiles );

		if ( !param.isAligned )
		{
			Align.alignTiles( param.po, tiles, fixedTiles, param.tilesAreInPlace, param.maxNumThreads );

			/* Apply the estimated affine transform to patches */
			for ( final AbstractAffineTile2D< ? > t : tiles )
				t.getPatch().setAffineTransform( t.createAffine() );

			Display.update();
		}

		/* generate tile pairs for all by now overlapping tiles */
		final ArrayList< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D<?>[] >();
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );

		/* check if there was any pair */
		if ( tilePairs.size() == 0 )
		{
			Utils.log( "Elastic montage could not find any overlapping patches after pre-montaging." );
			return;
		}

		Utils.log( tilePairs.size() + " pairs of patches will be block-matched..." );

		/* make pairwise global models local */
		final ArrayList< Triple< AbstractAffineTile2D< ? >, AbstractAffineTile2D< ? >, InvertibleCoordinateTransform > > pairs =
			new ArrayList< Triple< AbstractAffineTile2D< ? >, AbstractAffineTile2D< ? >, InvertibleCoordinateTransform > >();

		/*
		 * The following casting madness is necessary to get this code compiled
		 * with Sun/Oracle Java 6 which otherwise generates an inconvertible
		 * type exception.
		 *
		 * TODO Remove as soon as this bug is fixed in Sun/Oracle javac.
		 */
		for ( final AbstractAffineTile2D< ? >[] pair : tilePairs )
		{
			final AbstractAffineModel2D< ? > m;
			switch ( param.po.desiredModelIndex )
			{
			case 0:
				final TranslationModel2D t = ( TranslationModel2D )( Object )pair[ 1 ].getModel().createInverse();
				t.concatenate( ( TranslationModel2D )( Object )pair[ 0 ].getModel() );
				m = t;
				break;
			case 1:
				final RigidModel2D r = ( RigidModel2D )( Object )pair[ 1 ].getModel().createInverse();
				r.concatenate( ( RigidModel2D )( Object )pair[ 0 ].getModel() );
				m = r;
				break;
			case 2:
				final SimilarityModel2D s = ( SimilarityModel2D )( Object )pair[ 1 ].getModel().createInverse();
				s.concatenate( ( SimilarityModel2D )( Object )pair[ 0 ].getModel() );
				m = s;
				break;
			case 3:
				final AffineModel2D a = ( AffineModel2D )( Object )pair[ 1 ].getModel().createInverse();
				a.concatenate( ( AffineModel2D )( Object )pair[ 0 ].getModel() );
				m = a;
				break;
			default:
				m = null;
			}
			pairs.add( new Triple< AbstractAffineTile2D< ? >, AbstractAffineTile2D< ? >, InvertibleCoordinateTransform >( pair[ 0 ], pair[ 1 ], m ) );
		}


		/* Elastic alignment */

		/* Initialization */
		final double springTriangleHeightTwice = 2 * Math.sqrt( 0.75 * param.springLengthSpringMesh * param.springLengthSpringMesh );

		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( tiles.size() );
		final HashMap< AbstractAffineTile2D< ? >, SpringMesh > tileMeshMap = new HashMap< AbstractAffineTile2D< ? >, SpringMesh >();
		for ( final AbstractAffineTile2D< ? > tile : tiles )
		{
			final double w = tile.getWidth();
			final double h = tile.getHeight();
			final int numX = Math.max( 2, ( int )Math.ceil( w / param.springLengthSpringMesh ) + 1 );
			final int numY = Math.max( 2, ( int )Math.ceil( h / springTriangleHeightTwice ) + 1 );
			final double wMesh = ( numX - 1 ) * param.springLengthSpringMesh;
			final double hMesh = ( numY - 1 ) * springTriangleHeightTwice;

			final SpringMesh mesh = new SpringMesh(
							numX,
							numY,
							wMesh,
							hMesh,
							param.stiffnessSpringMesh,
							param.maxStretchSpringMesh * param.bmScale,
							param.dampSpringMesh );
			meshes.add( mesh );
			tileMeshMap.put( tile, mesh );
		}

//		final int blockRadius = Math.max( 32, Util.roundPos( param.springLengthSpringMesh / 2 ) );
		final int blockRadius = Math.max( Util.roundPos( 16 / param.bmScale ), param.bmBlockRadius );

		/** TODO set this something more than the largest error by the approximate model */
		final int searchRadius = param.bmSearchRadius;

		final AbstractModel< ? > localSmoothnessFilterModel = mpicbg.trakem2.align.Util.createModel( param.bmLocalModelIndex );


		for ( final Triple< AbstractAffineTile2D< ? >, AbstractAffineTile2D< ? >, InvertibleCoordinateTransform > pair : pairs )
		{
			final AbstractAffineTile2D< ? > t1 = pair.a;
			final AbstractAffineTile2D< ? > t2 = pair.b;

			final SpringMesh m1 = tileMeshMap.get( t1 );
			final SpringMesh m2 = tileMeshMap.get( t2 );

			final ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
			final ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

			final ArrayList< Vertex > v1 = m1.getVertices();
			final ArrayList< Vertex > v2 = m2.getVertices();

			final String patchName1 = patchName( t1.getPatch() );
			final String patchName2 = patchName( t2.getPatch() );

			final PatchImage pi1 = t1.getPatch().createTransformedImage();
			if ( pi1 == null )
			{
				Utils.log( "Patch `" + patchName1 + "' failed generating a transformed image.  Skipping..." );
				continue;
			}
			final PatchImage pi2 = t2.getPatch().createTransformedImage();
			if ( pi2 == null )
			{
				Utils.log( "Patch `" + patchName2 + "' failed generating a transformed image.  Skipping..." );
				continue;
			}

			final FloatProcessor fp1 = ( FloatProcessor )pi1.target.convertToFloat();
			final ByteProcessor mask1 = pi1.getMask();
			final FloatProcessor fpMask1 = mask1 == null ? null : scaleByte( mask1 );

			final FloatProcessor fp2 = ( FloatProcessor )pi2.target.convertToFloat();
			final ByteProcessor mask2 = pi2.getMask();
			final FloatProcessor fpMask2 = mask2 == null ? null : scaleByte( mask2 );

			if ( !fixedTiles.contains( t1 ) )
			{
				BlockMatching.matchByMaximalPMCC(
						fp1,
						fp2,
						fpMask1,
						fpMask2,
						param.bmScale,
						pair.c,
						blockRadius,
						blockRadius,
						searchRadius,
						searchRadius,
						param.bmMinR,
						param.bmRodR,
						param.bmMaxCurvatureR,
						v1,
						pm12,
						new ErrorStatistic( 1 ) );

				if ( param.bmUseLocalSmoothnessFilter )
				{
					Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondence candidates." );
					localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, param.bmLocalRegionSigma, param.bmMaxLocalEpsilon, param.bmMaxLocalTrust );
					Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': " + pm12.size() + " candidates passed local smoothness filter." );
				}
				else
				{
					Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondences." );
				}
			}
			else
			{
				Utils.log( "Skipping fixed patch `" + patchName1 + "'." );
			}

//			/* <visualisation> */
//			//			final List< Point > s1 = new ArrayList< Point >();
//			//			PointMatch.sourcePoints( pm12, s1 );
//			//			final ImagePlus imp1 = new ImagePlus( i + " >", ip1 );
//			//			imp1.show();
//			//			imp1.setOverlay( BlockMatching.illustrateMatches( pm12 ), Color.yellow, null );
//			//			imp1.setRoi( Util.pointsToPointRoi( s1 ) );
//			//			imp1.updateAndDraw();
//			/* </visualisation> */

			if ( !fixedTiles.contains( t2 ) )
			{
				BlockMatching.matchByMaximalPMCC(
						fp2,
						fp1,
						fpMask2,
						fpMask1,
						param.bmScale,
						pair.c.createInverse(),
						blockRadius,
						blockRadius,
						searchRadius,
						searchRadius,
						param.bmMinR,
						param.bmRodR,
						param.bmMaxCurvatureR,
						v2,
						pm21,
						new ErrorStatistic( 1 ) );

				if ( param.bmUseLocalSmoothnessFilter )
				{
					Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': found " + pm21.size() + " correspondence candidates." );
					localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, param.bmLocalRegionSigma, param.bmMaxLocalEpsilon, param.bmMaxLocalTrust );
					Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': " + pm21.size() + " candidates passed local smoothness filter." );
				}
				else
				{
					Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': found " + pm21.size() + " correspondences." );
				}
			}
			else
			{
				Utils.log( "Skipping fixed patch `" + patchName2 + "'." );
			}

			/* <visualisation> */
			//			final List< Point > s2 = new ArrayList< Point >();
			//			PointMatch.sourcePoints( pm21, s2 );
			//			final ImagePlus imp2 = new ImagePlus( i + " <", ip2 );
			//			imp2.show();
			//			imp2.setOverlay( BlockMatching.illustrateMatches( pm21 ), Color.yellow, null );
			//			imp2.setRoi( Util.pointsToPointRoi( s2 ) );
			//			imp2.updateAndDraw();
			/* </visualisation> */

			for ( final PointMatch pm : pm12 )
			{
				final Vertex p1 = ( Vertex )pm.getP1();
				final Vertex p2 = new Vertex( pm.getP2() );
				p1.addSpring( p2, new Spring( 0, 1.0f ) );
				m2.addPassiveVertex( p2 );
			}

			for ( final PointMatch pm : pm21 )
			{
				final Vertex p1 = ( Vertex )pm.getP1();
				final Vertex p2 = new Vertex( pm.getP2() );
				p1.addSpring( p2, new Spring( 0, 1.0f ) );
				m1.addPassiveVertex( p2 );
			}
		}

		/* initialize */
		for ( final Map.Entry< AbstractAffineTile2D< ? >, SpringMesh > entry : tileMeshMap.entrySet() )
			entry.getValue().init( entry.getKey().getModel() );

		/* optimize the meshes */
		try
		{
			final long t0 = System.currentTimeMillis();
			IJ.log( "Optimizing spring meshes..." );

			if ( param.useLegacyOptimizer )
			{
				Utils.log( "  ...using legacy optimizer...");
				SpringMesh.optimizeMeshes2(
						meshes,
						param.po.maxEpsilon,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh,
						param.visualize );
			}
			else
			{
				SpringMesh.optimizeMeshes(
						meshes,
						param.po.maxEpsilon,
						param.maxIterationsSpringMesh,
						param.maxPlateauwidthSpringMesh,
						param.visualize );
			}
			IJ.log( "Done optimizing spring meshes. Took " + ( System.currentTimeMillis() - t0 ) + " ms" );

		}
		catch ( final NotEnoughDataPointsException e )
		{
			Utils.log( "There were not enough data points to get the spring mesh optimizing." );
			e.printStackTrace();
			return;
		}

		/* apply */
		for ( final Map.Entry< AbstractAffineTile2D< ? >, SpringMesh > entry : tileMeshMap.entrySet() )
		{
			final AbstractAffineTile2D< ? > tile = entry.getKey();
			if ( !fixedTiles.contains( tile ) )
			{
				final Patch patch = tile.getPatch();
				final SpringMesh mesh = entry.getValue();
				final Set< PointMatch > matches = mesh.getVA().keySet();
				Rectangle box = patch.getCoordinateTransformBoundingBox();

				/* compensate for existing coordinate transform bounding box */
				for ( final PointMatch pm : matches )
				{
					final Point p1 = pm.getP1();
					final double[] l = p1.getL();
					l[ 0 ] += box.x;
					l[ 1 ] += box.y;
				}

				final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
				mlt.setModel( AffineModel2D.class );
				mlt.setAlpha( 2.0f );
				mlt.setMatches( matches );

				patch.appendCoordinateTransform( mlt );
				box = patch.getCoordinateTransformBoundingBox();

				patch.getAffineTransform().setToTranslation( box.x, box.y );
				patch.updateInDatabase( "transform" );
				patch.updateBucket();

				patch.updateMipMaps();
			}
		}

		Utils.log( "Done." );
	}
}
