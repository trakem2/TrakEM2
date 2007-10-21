import ini.trakem2.display.*;
import ini.trakem2.*;
import ini.trakem2.imaging.Registration;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;

import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.Model;
import mpi.fruitfly.registration.TModel2D;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.registration.Tile;
import mpi.fruitfly.registration.PointMatch;
import mpi.fruitfly.registration.Point;
import mpi.fruitfly.analysis.FitLine;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Vector;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.Rectangle;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Point2D;

import java.awt.geom.AffineTransform;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

import java.io.*;


public class SIFT_Align_LayerSet implements PlugIn, KeyListener
{
	private static final String[] dimensions = {
		"translation",
		"translation and rotation" };
	private static int dimension = 1;
	
	// steps
	private static int steps = 3;
	// initial sigma
	private static float initial_sigma = 1.6f;
	// feature descriptor size
	private static int fdsize = 8;
	// feature descriptor orientation bins
	private static int fdbins = 8;
	// size restrictions for scale octaves, use octaves < max_size and > min_size only
	private static int min_size = 64;
	private static int max_size = 512;
	// minimal allowed alignment error in px
	private static float min_epsilon = 1.0f;
	// maximal allowed alignment error in px
	private static float max_epsilon = 10.0f;
	// minimal allowed alignment error in px (across sections)
	private static float cs_min_epsilon = 1.0f;
	// maximal allowed alignment error in px (across sections)
	private static float cs_max_epsilon = 30.0f;
	private static float inlier_ratio = 0.05f;
	private static float scale = 1.0f;
	
	final static private DecimalFormat decimalFormat = new DecimalFormat();
	final static private DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();
	
	/**
	 * downscale a grey scale float image using gaussian blur
	 */
	static ImageProcessor downScale( FloatProcessor ip, float s )
	{
		FloatArray2D g = ImageArrayConverter.ImageToFloatArray2D( ip );

		float sigma = ( float )Math.sqrt( 0.25 / s / s - 0.25 );
		float[] kernel = ImageFilter.createGaussianKernel1D( sigma, true );
		
		long start_time = System.currentTimeMillis();
		System.out.print( "Scaling image by " + s + " => gaussian blur with sigma " + sigma + " ..." );
		g = ImageFilter.convolveSeparable( g, kernel, kernel );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );

		ImageArrayConverter.FloatArrayToFloatProcessor( ip, g );
		//ip.setInterpolate( false );
		return ip.resize( ( int )( s * ip.getWidth() ) );
	}
	
	final private TRModel2D estimateModel( Vector< Match > correspondences )
	{
		TRModel2D model = null;
		float epsilon = 0.0f;
		if ( correspondences.size() > TRModel2D.MIN_SET_SIZE )
		{
			int highest_num_inliers = 0;
			int convergence_count = 0;
			do
			{
				epsilon += min_epsilon;
				// 1000 iterations lead to a probability of < 0.01% that only bad data values were found for inlier_ratio = 0.1
				model = TRModel2D.estimateModel(
						correspondences,			//!< point correspondences
						1000,						//!< iterations
						epsilon * scale,			//!< maximal alignment error for a good point pair when fitting the model
						inlier_ratio );				//!< minimal partition (of 1.0) of inliers
						
				// compare the standard deviation of inliers and matches
				if ( model != null )
				{
					int num_inliers = model.getInliers().size();
					if ( num_inliers <= highest_num_inliers )
					{
						++convergence_count;
					}
					else
					{
						convergence_count = 0;
						highest_num_inliers = num_inliers;
					}
					
				}
			}
			while (
					( model == null || convergence_count < 4 ) &&
					epsilon < max_epsilon );
		}
		return model;
	}
	
	private void drawAndSaveIterationSnapshot(
			Layer layer,
			ArrayList< Tile > tiles,
			int iteration,
			int current,
			double od,
			String path )
	{
		int img_left = 0;
		int img_top = 0;
		//int img_width = 7428;
		//int img_height = 7339;
		float img_scale = 600.0f / ( float )layer.getLayerHeight();
		
		ImagePlus flat_section = ControlWindow.getActive().getLoader().getFlatImage(
				layer,
				new Rectangle( img_left, img_top, ( int )layer.getLayerWidth(), ( int )layer.getLayerHeight() ),
				img_scale,
				0xffffffff,
				ImagePlus.GRAY8,
				Patch.class,
				null,
				//true );	// high quality
				false );	// flow quality
		ImageProcessor ip = flat_section.getProcessor().convertToRGB();
		ip.setAntialiasedText( true );
		ip.setColor( Color.white );
		ip.setFont( new Font( "Arial", Font.PLAIN, 20 ) );
		
		if ( od < Double.MAX_VALUE )
		{
			ip.setJustification( ImageProcessor.LEFT_JUSTIFY );
			ip.drawString(
					"  :  e = " + decimalFormat.format( od ),
					//( int )( img_width * img_scale - 144 ),
					( int )( 64 ),
					( int )( layer.getLayerHeight() * img_scale - 8 ) );
		}
		ip.setJustification( ImageProcessor.RIGHT_JUSTIFY );
		String id_str = "";
		if ( current >= 0 )
		{
			id_str = iteration + "_" + current;
			ip.drawString(
					iteration + " : " + current,
					( int )( 64 ),
					( int )( layer.getLayerHeight() * img_scale - 8 ) );
		}
		else
		{
			id_str = "" + iteration;
			ip.drawString(
					id_str,
					( int )( 64 ),
					( int )( layer.getLayerHeight() * img_scale - 8 ) );
		}
		
		// draw correspondences
		if ( current >= 0 )
		{
			Tile tile = tiles.get( current );
			for ( PointMatch match : tile.getMatches() )
			{
				float[] w1 = match.getP1().getW();
				float[] w2 = match.getP2().getW();
				
				ip.setLineWidth( 1 );
				ip.setColor( Color.red );
				ip.drawLine(
						( int )Math.round( w1[ 0 ] * img_scale ),
						( int )Math.round( w1[ 1 ] * img_scale ),
						( int )Math.round( w2[ 0 ] * img_scale ),
						( int )Math.round( w2[ 1 ] * img_scale ) );
				
				ip.setLineWidth( 3 );
				ip.setColor( Color.green );
				ip.drawDot(
						( int )Math.round( w1[ 0 ] * img_scale ),
						( int )Math.round( w1[ 1 ] * img_scale ) );
				ip.drawDot(
						( int )Math.round( w2[ 0 ] * img_scale ),
						( int )Math.round( w2[ 1 ] * img_scale ) );
			}
		}
		else
		{
			for ( Tile tile : tiles )
			{
				for ( PointMatch match : tile.getMatches() )
				{
					float[] w1 = match.getP1().getW();
					float[] w2 = match.getP2().getW();
					
					ip.setLineWidth( 1 );
					ip.setColor( Color.red );
					ip.drawLine(
							( int )Math.round( w1[ 0 ] * img_scale ),
							( int )Math.round( w1[ 1 ] * img_scale ),
							( int )Math.round( w2[ 0 ] * img_scale ),
							( int )Math.round( w2[ 1 ] * img_scale ) );
					
					ip.setLineWidth( 3 );
					ip.setColor( Color.green );
					ip.drawDot(
							( int )Math.round( w1[ 0 ] * img_scale ),
							( int )Math.round( w1[ 1 ] * img_scale ) );
					ip.drawDot(
							( int )Math.round( w2[ 0 ] * img_scale ),
							( int )Math.round( w2[ 1 ] * img_scale ) );
				}
			}
		}
		
		flat_section.setProcessor( null, ip );
		flat_section.updateAndDraw();
		
		new ij.io.FileSaver( flat_section ).saveAsTiff(
				path + "/anim." + id_str + ".tif" );
	}

	public void run( String args )
	{
		if ( IJ.versionLessThan( "1.37i" ) ) return;
		
		Display front = Display.getFront();
		if ( front == null )
		{
			System.err.println( "no open displays" );
			return;
		}
		
		LayerSet set = front.getLayer().getParent();
		if ( set == null )
		{
			System.err.println( "no open layer-set" );
			return;
		}
		
		decimalFormatSymbols.setGroupingSeparator( ',' );
		decimalFormatSymbols.setDecimalSeparator( '.' );
		decimalFormat.setDecimalFormatSymbols( decimalFormatSymbols );
		decimalFormat.setMaximumFractionDigits( 3 );
		decimalFormat.setMinimumFractionDigits( 3 );
		
		GenericDialog gd = new GenericDialog( "Align stack" );
		gd.addNumericField( "steps_per_scale_octave :", steps, 0 );
		gd.addNumericField( "initial_gaussian_blur :", initial_sigma, 2 );
		gd.addNumericField( "feature_descriptor_size :", fdsize, 0 );
		gd.addNumericField( "feature_descriptor_orientation_bins :", fdbins, 0 );
		gd.addNumericField( "minimum_image_size :", min_size, 0 );
		gd.addNumericField( "maximum_image_size :", max_size, 0 );
		gd.addNumericField( "minimal_alignment_error :", min_epsilon, 2 );
		gd.addNumericField( "maximal_alignment_error :", max_epsilon, 2 );
		gd.addNumericField( "cs_minimal_alignment_error :", cs_min_epsilon, 2 );
		gd.addNumericField( "cs_maximal_alignment_error :", cs_max_epsilon, 2 );
		gd.addNumericField( "inlier_ratio :", inlier_ratio, 2 );
		gd.addChoice( "transformations_to_be_optimized :", dimensions, dimensions[ dimension ] );
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		steps = ( int )gd.getNextNumber();
		initial_sigma = ( float )gd.getNextNumber();
		fdsize = ( int )gd.getNextNumber();
		fdbins = ( int )gd.getNextNumber();
		min_size = ( int )gd.getNextNumber();
		max_size = ( int )gd.getNextNumber();
		min_epsilon = ( float )gd.getNextNumber();
		max_epsilon = ( float )gd.getNextNumber();
		cs_min_epsilon = ( float )gd.getNextNumber();
		cs_max_epsilon = ( float )gd.getNextNumber();
		inlier_ratio = ( float )gd.getNextNumber();
		String dimension_str = gd.getNextChoice();
		
		final ArrayList< Layer > layers = set.getLayers();
		final ArrayList< Vector< FloatArray2DSIFT.Feature > > featureSets1 = new ArrayList< Vector< FloatArray2DSIFT.Feature > >();
		final ArrayList< Vector< FloatArray2DSIFT.Feature > > featureSets2 = new ArrayList< Vector< FloatArray2DSIFT.Feature > >();

		final ArrayList< Patch > patches1 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles1 = new ArrayList< Tile >();
		final ArrayList< Patch > patches2 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles2 = new ArrayList< Tile >();

		// works as a service
		final FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		// roughly, we expect about 1000 features per 512x512 image
		final long feature_size = (long)((max_size * max_size) / (512 * 512) * 1000 * sift.getFeatureObjectSize() * 1.5);

		long start_time;

		final ArrayList< Tile > all_tiles = new ArrayList< Tile >();
		final ArrayList< Patch > all_patches = new ArrayList< Patch >();

		Layer previous_layer = null;

		final Registration.SIFTParameters sp_gross_interlayer = new Registration.SIFTParameters(set.getProject(), true);
		//sp_gross_interlayer.setup();

		for ( Layer layer : layers )
		{

			// ignore empty layers
			if (!layer.contains(Patch.class)) continue;

			if (null != previous_layer) {
				featureSets1.clear();
				featureSets1.addAll(featureSets2);
				featureSets2.clear();
				//
				patches1.clear();
				patches1.addAll(patches2);
				tiles1.clear();
				tiles1.addAll(tiles2);
			}

			patches2.clear();
			patches2.addAll(layer.getDisplayables( Patch.class ));
			tiles2.clear();

			ImagePlus imp;

			// extract SIFT-features in all patches
			// TODO store the feature sets on disk, each of them might be in the magnitude of 10MB large
			for ( Patch patch : patches2 )
			{
				imp = patch.getProject().getLoader().fetchImagePlus( patch );
				set.getProject().getLoader().releaseToFit(imp.getWidth() * imp.getHeight() * 96L + feature_size);

				FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( imp.getProcessor().convertToByte( true ) );
				ImageFilter.enhance( fa, 1.0f );
				fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
				
				start_time = System.currentTimeMillis();
				System.out.print( "processing SIFT ..." );
				sift.init( fa, steps, initial_sigma, min_size, max_size );
				Vector< FloatArray2DSIFT.Feature > fs = sift.run( max_size );
				Collections.sort( fs );
				System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
				
				System.out.println( fs.size() + " features identified and processed" );
				
				Model model;
				
				if ( dimension_str.equals("translation") )
					model = new TModel2D();
				else
					model = new TRModel2D();
				
				model.getAffine().setTransform( patch.getAffineTransform() );
				Tile tile = new Tile( ( float )fa.width, ( float )fa.height, model );
				tiles2.add( tile );
				featureSets2.add( fs );
			}

			// identify correspondences
			int num_patches = patches2.size();
			for ( int i = 0; i < num_patches; ++i )
			{
				Patch current_patch = patches2.get( i );
				Tile current_tile = tiles2.get( i );
				ArrayList< Integer > intersecting_tiles = new ArrayList< Integer >();
				for ( int j = i + 1; j < num_patches; ++j )
				{
					Patch other_patch = patches2.get( j );
					Tile other_tile = tiles2.get( j );
					if ( current_patch.intersects( other_patch ) )
					{
						intersecting_tiles.add( new Integer( j ) );
						start_time = System.currentTimeMillis();
						System.out.print( "identifying correspondences using brute force ..." );
						Vector< Match > correspondences = FloatArray2DSIFT.createMatches(
									featureSets2.get( i ),
									featureSets2.get( j ),
									1.25f,
									null,
									Float.MAX_VALUE );
						System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
						
						IJ.log( "Tiles " + i + " and " + j + " have " + correspondences.size() + " potentially corresponding features." );
						
						TRModel2D model = estimateModel( correspondences );
						
						if ( model != null )
						{
							IJ.log( model.getInliers().size() + " of them are good." );
							ArrayList< PointMatch > matches = PointMatch.fromMatches( model.getInliers() );
							current_tile.addMatches( matches );
							other_tile.addMatches( PointMatch.flip( matches ) );
						}
						else
						{
							IJ.log( "None of them are good." );
						}
					}
				}
				// if there are no features at all, then use two synthetic features
				// (two nails) with each intersecting patch
				if (0 == current_tile.getNumMatches()) {
					final Rectangle r = current_patch.getBoundingBox();
					for (Integer j : intersecting_tiles) {
						Patch p = patches2.get( j.intValue() );
						Tile tile = tiles2.get( j.intValue() );
						Rectangle rp = p.getBoundingBox().intersection(r);
						int xp1 = rp.x;
						int yp1 = rp.y;
						int xp2 = rp.x + rp.width;
						int yp2 = rp.y + rp.height;
						Point2D.Double dcp1 = current_patch.inverseTransformPoint(xp1, yp1);
						Point2D.Double dcp2 = current_patch.inverseTransformPoint(xp2, yp2);
						Point2D.Double dp1 = p.inverseTransformPoint(xp1, yp1);
						Point2D.Double dp2 = p.inverseTransformPoint(xp2, yp2);
						Point cp1 = new Point(new float[]{(float)dcp1.x, (float)dcp1.y});
						Point cp2 = new Point(new float[]{(float)dcp2.x, (float)dcp2.y});
						Point p1 = new Point(new float[]{(float)dp1.x, (float)dp1.y});
						Point p2 = new Point(new float[]{(float)dp2.x, (float)dp2.y});
						ArrayList< PointMatch > a1 = new ArrayList<PointMatch>();
						a1.add(new PointMatch( cp1, p1, 1.0f ));
						a1.add(new PointMatch( cp2, p2, 1.0f ));
						current_tile.addMatches(a1);
						ArrayList< PointMatch > a2 = new ArrayList<PointMatch>();
						a2.add(new PointMatch( p1, cp1, 0.0f ));
						a2.add(new PointMatch( p2, cp2, 0.0f ));
						tile.addMatches(a2);
					}
				}
			}

//			featureSets.clear();
//			System.gc();
//			System.gc();
//			System.gc();
//			System.gc();

			/**
			 * One tile per connected graph has to be fixed to make the problem
			 * solvable, otherwise it is ill defined and has an infinite number
			 * of solutions.
			 * 
			 * TODO Identify the connected graphs.  Currently, we assume all
			 *   tiles to be connected and fix the tile with the highest number
			 *   of correspondences
			 */
			Tile fixed = null;
			int max_num_matches = 0;
			
			// apply each tiles transformation to its correspondences
			// and find the one with the highest number of matches
			for ( Tile tile : tiles2 )
			{
				tile.update();
				int num_matches = tile.getNumMatches();
				if ( max_num_matches < num_matches )
				{
					max_num_matches = num_matches;
					fixed = tile;
				}
			}
			// again, for error and distance correction
			for ( Tile tile : tiles2 ) tile.update();
			
//			boolean changed = true;
//			while ( changed )
//			{
//				changed = false;
//				for ( int i = 0; i < num_patches; ++i )
//				{
//					Tile tile = tiles2.get( i );
//					if ( tile == fixed ) continue;
//					tile.update();
//					
//					if ( tile.diceBetterModel( 100000, 1.0f ) )
//					{
//						patches2.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
//						
//						double od = 0.0;
//						for ( Tile t : tiles2 )
//						{
//							t.update();
//							od += t.getDistance();
//						}						
//						od /= tiles2.size();
//						
//						IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
//						
//						changed = true;
//					}					
//				}
//				
//				// repaint all Displays showing a Layer of the edited LayerSet
//				Display.update( set );
//			}
			
//#############################################################################
//			drawAndSaveIterationSnapshot(
//					layer,
//					tiles2,
//					0,
//					0,
//					Double.MAX_VALUE,
//					"/home/saalfeld" );
//#############################################################################

			double od = Double.MAX_VALUE;
			double d = Double.MAX_VALUE;
			int iteration = 1;
			int cc = 0;
			double[] dall = new double[100];
			int next = 0;
			//while ( cc < 10 )
			while ( true )
			{
				for ( int i = 0; i < num_patches; ++i )
				{
					Tile tile = tiles2.get( i );
					if ( tile == fixed ) continue;
					tile.update();
					tile.minimizeModel();
					tile.update();
					patches2.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
					//IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
					
//#############################################################################
//					// show each single update step
//					double cd = 0.0;
//					for ( Tile t : tiles2 )
//					{
//						t.update();
//						cd += t.getDistance();
//					}						
//					cd /= tiles2.size();
//					Display.update( set );
//					drawAndSaveIterationSnapshot(
//							layer,
//							tiles2,
//							iteration,
//							i,
//							cd,
//							"/home/saalfeld" );
//#############################################################################
					
				}
				double cd = 0.0;
				for ( Tile t : tiles2 )
				{
					t.update();
					cd += t.getDistance();
				}						
				cd /= tiles2.size();
				d = Math.abs( od - cd );
				od = cd;
				IJ.showStatus( "displacement: " + decimalFormat.format( od ) + " after " + iteration + " iterations");
				//cc = d < 0.00025 ? cc + 1 : 0;
				cc = d < 0.001 ? cc + 1 : 0;

				if (dall.length  == next) {
					double[] dall2 = new double[dall.length + 100];
					System.arraycopy(dall, 0, dall2, 0, dall.length);
					dall = dall2;
				}
				dall[next++] = d;
				// cut the last 'n'
				if (next > 100) { // wait until completing at least 'n' iterations
					double[] dn = new double[100];
					System.arraycopy(dall, dall.length - 100, dn, 0, 100);
					// fit curve
					double[] ft = FitLine.fitLine(dn);
					// ft[1] StdDev
					// ft[2] m (slope)
					if (Math.abs(ft[1]) < 0.001) {
						System.out.println("Exiting at iteration " + next + " with slope " + ft[1]);
						break;
					}
				}


				// repaint all Displays showing a Layer of the edited LayerSet
				Display.update( layer );
				

//#############################################################################
//				drawAndSaveIterationSnapshot(
//						layer,
//						tiles2,
//						iteration,
//						-1,
//						od,
//						"D:/Benutzer/Stephan/Eigene Dateien/diploma" );
//#############################################################################
			
				
				++iteration;
			}

			// repaint all Displays showing a Layer of the edited LayerSet
			Display.update( set );

			// store for global minimization
			all_tiles.addAll(tiles2);
			all_patches.addAll(patches2);

			if (null != previous_layer) {
				// coarse registration
				Object[] ob = Registration.registerSIFT(previous_layer, layer, null, sp_gross_interlayer);
				AffineTransform at = (AffineTransform)ob[0];
				TRModel2D model = new TRModel2D();
				model.getAffine().setTransform(at);
				for (Tile tile : tiles2) {
					((TRModel2D)tile.getModel()).preConcatenate(model);
				}

				// identify corresponding matches across layers using tiles1 and tiles2
				int num_patches2 = patches2.size();
				int num_patches1 = patches2.size();
				for ( int i = 0; i < num_patches2; ++i )
				{
					Patch current_patch = patches2.get( i );
					Tile current_tile = tiles2.get( i );
					for ( int j = 0; j < num_patches1; ++j )
					{
						Patch other_patch = patches1.get( j );
						Tile other_tile = tiles1.get( j );
						if ( current_patch.intersects( other_patch ) )
						{
							start_time = System.currentTimeMillis();
							System.out.print( "identifying correspondences using brute force ..." );
							Vector< Match > correspondences = FloatArray2DSIFT.createMatches(
										featureSets2.get( i ),
										featureSets1.get( j ),
										1.25f,
										null,
										Float.MAX_VALUE );
							System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
							
							IJ.log( "Tiles " + i + " and " + j + " have " + correspondences.size() + " potentially corresponding features." );
							
							TRModel2D mo = estimateModel( correspondences );

							if ( mo != null )
							{
								IJ.log( mo.getInliers().size() + " of them are good." );
								ArrayList< PointMatch > matches = PointMatch.fromMatches( mo.getInliers() );
								current_tile.addMatches( matches );
								other_tile.addMatches( PointMatch.flip( matches ) );
							}
							else
							{
								IJ.log( "None of them are good." );
							}
						}
					}
				}

			}

			previous_layer = layer;
		}
	
		// find the global nail
		/**
		 * One tile per connected graph has to be fixed to make the problem
		 * solvable, otherwise it is ill defined and has an infinite number
		 * of solutions.
		 * 
		 * TODO Identify the connected graphs.  Currently, we assume all
		 *   tiles to be connected and fix the tile with the highest number
		 *   of correspondences
		 */
		Tile fixed = null;
		int max_num_matches = 0;

		// apply each tiles transformation to its correspondences
		// and find the one with the highest number of matches
		for ( Tile tile : all_tiles )
		{
			tile.update();
			int num_matches = tile.getNumMatches();
			if ( max_num_matches < num_matches )
			{
				max_num_matches = num_matches;
				fixed = tile;
			}
		}
		// again, for error and distance correction
		for ( Tile tile : all_tiles ) tile.update();

		// global minimization

		double od = Double.MAX_VALUE;
		double d = Double.MAX_VALUE;
		int iteration = 1;
		int cc = 0;
		double[] dall = new double[100];
		int next = 0;
		//while ( cc < 10 )
		int all_num_patches = all_patches.size();
		while ( true )
		{
			for ( int i = 0; i < all_num_patches; ++i )
			{
				Tile tile = all_tiles.get( i );
				if ( tile == fixed ) continue;
				tile.update();
				tile.minimizeModel();
				tile.update();
				all_patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
				//IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
				
//#############################################################################
//					// show each single update step
//					double cd = 0.0;
//					for ( Tile t : tiles2 )
//					{
//						t.update();
//						cd += t.getDistance();
//					}						
//					cd /= tiles2.size();
//					Display.update( set );
//					drawAndSaveIterationSnapshot(
//							layer,
//							all_tiles,
//							iteration,
//							i,
//							cd,
//							"/home/saalfeld" );
//#############################################################################
				
			}
			double cd = 0.0;
			for ( Tile t : all_tiles )
			{
				t.update();
				cd += t.getDistance();
			}						
			cd /= all_tiles.size();
			d = Math.abs( od - cd );
			od = cd;
			IJ.showStatus( "displacement: " + decimalFormat.format( od ) + " after " + iteration + " iterations");
			//cc = d < 0.00025 ? cc + 1 : 0;
			cc = d < 0.001 ? cc + 1 : 0;

			if (dall.length  == next) {
				double[] dall2 = new double[dall.length + 100];
				System.arraycopy(dall, 0, dall2, 0, dall.length);
				dall = dall2;
			}
			dall[next++] = d;
			// cut the last 'n'
			if (next > 100) { // wait until completing at least 'n' iterations
				double[] dn = new double[100];
				System.arraycopy(dall, dall.length - 100, dn, 0, 100);
				// fit curve
				double[] ft = FitLine.fitLine(dn);
				// ft[1] StdDev
				// ft[2] m (slope)
				if (Math.abs(ft[1]) < 0.001) {
					System.out.println("Exiting at iteration " + next + " with slope " + ft[1]);
					break;
				}
			}

			// repaint all Displays showing a Layer of the edited LayerSet
			Display.update( set );


//#############################################################################
//				drawAndSaveIterationSnapshot(
//						layer,
//						all_tiles,
//						iteration,
//						-1,
//						od,
//						"D:/Benutzer/Stephan/Eigene Dateien/diploma" );
//#############################################################################
		
			
			++iteration;
		}


		// update selection internals in all open Displays
		Display.updateSelection( front );

		// repaint all Displays showing a Layer of the edited LayerSet
		Display.update( set );
	}

	public void keyPressed(KeyEvent e)
	{
		if (
				( e.getKeyCode() == KeyEvent.VK_F1 ) &&
				( e.getSource() instanceof TextField ) )
		{
		}
		else if ( e.getKeyCode() == KeyEvent.VK_ESCAPE )
		{
			return;
		}
	}

	public void keyReleased(KeyEvent e) { }

	public void keyTyped(KeyEvent e) { }
}
