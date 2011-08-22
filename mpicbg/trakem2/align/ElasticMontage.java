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
import java.util.Collection;
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
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.util.Util;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class ElasticMontage extends AbstractElasticAlignment
{
	final static protected class Param implements Serializable
	{	
		/**
		 * 
		 */
		private static final long serialVersionUID = 685811752558724564L;
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
		
		public boolean tilesAreInPlace = true;
		
		/**
		 * Block matching
		 */
		public float bmScale = 0.33f;
		public float bmMinR = 0.8f;
		public float bmMaxCurvatureR = 3f;
		public float bmRodR = 0.8f;
		
		/**
		 * Spring mesh
		 */
		public float springLengthSpringMesh = 100;
		public float stiffnessSpringMesh = 0.1f;
		public float dampSpringMesh = 0.6f;
		public float maxStretchSpringMesh = 2000.0f;
		public int maxIterationsSpringMesh = 1000;
		public int maxPlateauwidthSpringMesh = 200;
		
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
			final GenericDialog gdSIFT = new GenericDialog( "Elastic montage: SIFT based pre-montage" );
			po.addFields( gdSIFT );
			gdSIFT.addMessage( "Miscellaneous:" );
			gdSIFT.addCheckbox( "tiles are roughly in place", tilesAreInPlace );
			gdSIFT.showDialog();
			if ( gdSIFT.wasCanceled() )
				return false;
			
			po.readFields( gdSIFT );
			tilesAreInPlace = gdSIFT.getNextBoolean();
			
			/* Block Matching */
			final GenericDialog gdBlockMatching = new GenericDialog( "Elastic montage: Block Matching and Spring Meshes" );
			gdBlockMatching.addMessage( "Block Matching:" );
			
			gdBlockMatching.addNumericField( "patch_scale :", bmScale, 2 );
			gdBlockMatching.addNumericField( "minimal_PMCC_r :", bmMinR, 2 );
			gdBlockMatching.addNumericField( "maximal_curvature_ratio :", bmMaxCurvatureR, 2 );
			gdBlockMatching.addNumericField( "maximal_second_best_r/best_r :", bmRodR, 2 );
			
			/* TODO suggest a resolution that matches maxEpsilon */
			gdBlockMatching.addMessage( "Spring Mesh:" );
			gdBlockMatching.addNumericField( "spring_length :", springLengthSpringMesh, 2, 6, "px" );
			gdBlockMatching.addNumericField( "stiffness :", stiffnessSpringMesh, 2 );
			gdBlockMatching.addNumericField( "maximal_stretch :", maxStretchSpringMesh, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_iterations :", maxIterationsSpringMesh, 0 );
			gdBlockMatching.addNumericField( "maximal_plateauwidth :", maxPlateauwidthSpringMesh, 0 );
			
			gdBlockMatching.showDialog();
			
			if ( gdBlockMatching.wasCanceled() )
				return false;
			
			bmScale = ( float )gdBlockMatching.getNextNumber();
			bmMinR = ( float )gdBlockMatching.getNextNumber();
			bmMaxCurvatureR = ( float )gdBlockMatching.getNextNumber();
			bmRodR = ( float )gdBlockMatching.getNextNumber();
			
			springLengthSpringMesh = ( float )gdBlockMatching.getNextNumber();
			stiffnessSpringMesh = ( float )gdBlockMatching.getNextNumber();
			maxStretchSpringMesh = ( float )gdBlockMatching.getNextNumber();
			maxIterationsSpringMesh = ( int )gdBlockMatching.getNextNumber();
			maxPlateauwidthSpringMesh = ( int )gdBlockMatching.getNextNumber();
			
			return true;
		}
		
		@Override
		public Param clone()
		{
			final Param p = new Param();
			p.po = po.clone();
			p.tilesAreInPlace = tilesAreInPlace;
			
			p.bmScale = bmScale;
			p.bmMinR = bmMinR;
			p.bmMaxCurvatureR = bmMaxCurvatureR;
			p.bmRodR = bmRodR;
			
			p.springLengthSpringMesh = springLengthSpringMesh;
			p.stiffnessSpringMesh = stiffnessSpringMesh;
			p.dampSpringMesh = dampSpringMesh;
			p.maxStretchSpringMesh = maxStretchSpringMesh;
			p.maxIterationsSpringMesh = maxIterationsSpringMesh;
			p.maxPlateauwidthSpringMesh = maxPlateauwidthSpringMesh;
			
			p.visualize = visualize;
			
			p.maxNumThreads = maxNumThreads;
			
			return p;
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
		for ( Future< ArrayList< Feature > > fu : siftTasks )
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
			final List< Patch > fixedPatches ) throws Exception
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
		
		final Param p = setup();
		if ( p == null )
			return;
		else
			exec( p, patches, fixedPatches );
	}
	

	final public void exec(
			final Param p,
			final List< Patch > patches,
			final List< Patch > fixedPatches ) throws Exception
	{
		/* free memory */
		patches.get( 0 ).getProject().getLoader().releaseAll();
		
		/* create tiles and models for all patches */
		final List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( p.po, patches, fixedPatches, tiles, fixedTiles );
		Align.alignTiles( p.po, tiles, fixedTiles, p.tilesAreInPlace, p.maxNumThreads );
		
		/* Apply the estimated affine transform to patches */
		for ( final AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.createAffine() );
		
		Display.update();
		
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
			switch ( p.po.desiredModelIndex )
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
		final float springTriangleHeightTwice = 2 * ( float )Math.sqrt( 0.75f * p.springLengthSpringMesh * p.springLengthSpringMesh );
		
		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( tiles.size() );
		final HashMap< AbstractAffineTile2D< ? >, SpringMesh > tileMeshMap = new HashMap< AbstractAffineTile2D< ? >, SpringMesh >();
		for ( final AbstractAffineTile2D< ? > tile : tiles )
		{
			final double w = tile.getWidth();
			final double h = tile.getWidth();
			final int numX = Math.max( 2, ( int )Math.ceil( w / p.springLengthSpringMesh ) + 1 );
			final int numY = Math.max( 2, ( int )Math.ceil( h / springTriangleHeightTwice ) + 1 );
			final float wMesh = ( numX - 1 ) * p.springLengthSpringMesh;
			final float hMesh = ( numY - 1 ) * springTriangleHeightTwice;
			
			final SpringMesh mesh = new SpringMesh(
							numX,
							numY,
							wMesh,
							hMesh,
							p.stiffnessSpringMesh,
							p.maxStretchSpringMesh * p.bmScale,
							p.dampSpringMesh );
			meshes.add( mesh );
			tileMeshMap.put( tile, mesh );
		}
		
		final int blockRadius = Math.max( 32, Util.roundPos( p.springLengthSpringMesh / 2 ) );
		
		/** TODO set this something more than the largest error by the approximate model */
		final int searchRadius = ( int )Math.round( p.po.maxEpsilon );
		
		for ( final Triple< AbstractAffineTile2D< ? >, AbstractAffineTile2D< ? >, InvertibleCoordinateTransform > pair : pairs )
		{
			final AbstractAffineTile2D< ? > t1 = pair.a;
			final AbstractAffineTile2D< ? > t2 = pair.b;

			final SpringMesh m1 = tileMeshMap.get( t1 );
			final SpringMesh m2 = tileMeshMap.get( t2 );

			ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
			ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

			final Collection< Vertex > v1 = m1.getVertices();
			final Collection< Vertex > v2 = m2.getVertices();
			
			final String patchName1 = patchName( t1.getPatch() );
			final String patchName2 = patchName( t2.getPatch() );
			
			PatchImage pi1 = t1.getPatch().createTransformedImage();
			if ( pi1 == null )
			{
				Utils.log( "Patch `" + patchName1 + "' failed generating a transformed image.  Skipping..." );
				continue;
			}
			PatchImage pi2 = t2.getPatch().createTransformedImage();
			if ( pi2 == null )
			{
				Utils.log( "Patch `" + patchName2 + "' failed generating a transformed image.  Skipping..." );
				continue;
			}
			
			FloatProcessor fp1 = ( FloatProcessor )pi1.target.convertToFloat();
			ByteProcessor mask1 = pi1.getMask();
			FloatProcessor fpMask1 = mask1 == null ? null : scaleByte( mask1 );
			
			FloatProcessor fp2 = ( FloatProcessor )pi2.target.convertToFloat();
			ByteProcessor mask2 = pi1.getMask();
			FloatProcessor fpMask2 = mask2 == null ? null : scaleByte( mask2 );
						
			BlockMatching.matchByMaximalPMCC(
					fp1,
					fp2,
					fpMask1,
					fpMask2,
					p.bmScale,
					pair.c,
					blockRadius,
					blockRadius,
					searchRadius,
					searchRadius,
					p.bmMinR,
					p.bmRodR,
					p.bmMaxCurvatureR,
					v1,
					pm12,
					new ErrorStatistic( 1 ) );

			IJ.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondences." );

//			/* <visualisation> */
//			//			final List< Point > s1 = new ArrayList< Point >();
//			//			PointMatch.sourcePoints( pm12, s1 );
//			//			final ImagePlus imp1 = new ImagePlus( i + " >", ip1 );
//			//			imp1.show();
//			//			imp1.setOverlay( BlockMatching.illustrateMatches( pm12 ), Color.yellow, null );
//			//			imp1.setRoi( Util.pointsToPointRoi( s1 ) );
//			//			imp1.updateAndDraw();
//			/* </visualisation> */

			BlockMatching.matchByMaximalPMCC(
					fp2,
					fp1,
					fpMask2,
					fpMask1,
					p.bmScale,
					pair.c.createInverse(),
					blockRadius,
					blockRadius,
					searchRadius,
					searchRadius,
					p.bmMinR,
					p.bmRodR,
					p.bmMaxCurvatureR,
					v2,
					pm21,
					new ErrorStatistic( 1 ) );

			IJ.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondences." );

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
		for ( Map.Entry< AbstractAffineTile2D< ? >, SpringMesh > entry : tileMeshMap.entrySet() )
			entry.getValue().init( entry.getKey().getModel() );
		
		/* optimize the meshes */
		try
		{
			long t0 = System.currentTimeMillis();
			IJ.log( "Optimizing spring meshes..." );
			
			SpringMesh.optimizeMeshes(
					meshes,
					p.po.maxEpsilon,
					p.maxIterationsSpringMesh,
					p.maxPlateauwidthSpringMesh,
					p.visualize );

			IJ.log( "Done optimizing spring meshes. Took " + ( System.currentTimeMillis() - t0 ) + " ms" );
			
		}
		catch ( NotEnoughDataPointsException e )
		{
			Utils.log( "There were not enough data points to get the spring mesh optimizing." );
			e.printStackTrace();
			return;
		}
		
		/* apply */
		for ( Map.Entry< AbstractAffineTile2D< ? >, SpringMesh > entry : tileMeshMap.entrySet() ) 
		{
			final AbstractAffineTile2D< ? > tile = entry.getKey();
			final Patch patch = tile.getPatch();
			final SpringMesh mesh = entry.getValue();
			final Set< PointMatch > matches = mesh.getVA().keySet();
			Rectangle box = patch.getCoordinateTransformBoundingBox();
			
			/* compensate for existing coordinate transform bounding box */
			for ( final PointMatch pm : matches )
			{
				final Point p1 = pm.getP1();
				final float[] l = p1.getL();
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
		
		Utils.log( "Done." );
	}
}
