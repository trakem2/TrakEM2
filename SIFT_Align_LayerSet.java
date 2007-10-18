import ini.trakem2.display.*;
import ini.trakem2.*;

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
		inlier_ratio = ( float )gd.getNextNumber();
		String dimension_str = gd.getNextChoice();
		
		ArrayList< Layer > layers = set.getLayers();
		ArrayList< Vector< FloatArray2DSIFT.Feature > > featureSets = new ArrayList< Vector< FloatArray2DSIFT.Feature > >();
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		long start_time;
		
		for ( Layer layer : layers )
		{
			// first, intra-layer alignment
			// TODO involve the correlation techniques
			ArrayList< Patch > patches = layer.getDisplayables( Patch.class );
			ArrayList< Tile > tiles = new ArrayList< Tile >();
			
			ImagePlus imp;
			
			// extract SIFT-features in all patches
			// TODO store the feature sets on disk, each of them might be in the magnitude of 10MB large
			for ( Patch patch : patches )
			{
				imp = patch.getProject().getLoader().fetchImagePlus( patch );
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
				
				if ( dimension_str == "translation" )
					model = new TModel2D();
				else
					model = new TRModel2D();
				
				model.getAffine().setTransform( patch.getAffineTransform() ); 
				Tile tile = new Tile( ( float )fa.width, ( float )fa.height, model );
				tiles.add( tile );
				featureSets.add( fs );
			}
			
			// identify correspondences
			int num_patches = patches.size();
			for ( int i = 0; i < num_patches; ++i )
			{
				Patch current_patch = patches.get( i );
				Tile current_tile = tiles.get( i );
				for ( int j = i + 1; j < num_patches; ++j )
				{
					Patch other_patch = patches.get( j );
					Tile other_tile = tiles.get( j );
					if ( current_patch.intersects( other_patch ) )
					{
						start_time = System.currentTimeMillis();
						System.out.print( "identifying correspondences using brute force ..." );
						Vector< Match > correspondences = FloatArray2DSIFT.createMatches(
									featureSets.get( i ),
									featureSets.get( j ),
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
			for ( Tile tile : tiles )
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
			for ( Tile tile : tiles ) tile.update();
			
//			boolean changed = true;
//			while ( changed )
//			{
//				changed = false;
//				for ( int i = 0; i < num_patches; ++i )
//				{
//					Tile tile = tiles.get( i );
//					if ( tile == fixed ) continue;
//					tile.update();
//					
//					if ( tile.diceBetterModel( 100000, 1.0f ) )
//					{
//						patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
//						
//						double od = 0.0;
//						for ( Tile t : tiles )
//						{
//							t.update();
//							od += t.getDistance();
//						}						
//						od /= tiles.size();
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
//					tiles,
//					0,
//					0,
//					Double.MAX_VALUE,
//					"/home/saalfeld" );
//#############################################################################

			double od = Double.MAX_VALUE;
			double d = Double.MAX_VALUE;
			int iteration = 1;
			int cc = 0;
			while ( cc < 10 )
			{
				for ( int i = 0; i < num_patches; ++i )
				{
					Tile tile = tiles.get( i );
					if ( tile == fixed ) continue;
					tile.update();
					tile.minimizeModel();
					tile.update();
					patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
					//IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
					
//#############################################################################
//					// show each single update step
//					double cd = 0.0;
//					for ( Tile t : tiles )
//					{
//						t.update();
//						cd += t.getDistance();
//					}						
//					cd /= tiles.size();
//					Display.update( set );
//					drawAndSaveIterationSnapshot(
//							layer,
//							tiles,
//							iteration,
//							i,
//							cd,
//							"/home/saalfeld" );
//#############################################################################
					
				}
				double cd = 0.0;
				for ( Tile t : tiles )
				{
					t.update();
					cd += t.getDistance();
				}						
				cd /= tiles.size();
				d = Math.abs( od - cd );
				od = cd;
				IJ.showStatus( "displacement: " + decimalFormat.format( od ) + " after " + iteration + " iterations");
				
				cc = d < 0.00025 ? cc + 1 : 0;
				
				// repaint all Displays showing a Layer of the edited LayerSet
				Display.update( set );
				

//#############################################################################
//				drawAndSaveIterationSnapshot(
//						layer,
//						tiles,
//						iteration,
//						-1,
//						od,
//						"D:/Benutzer/Stephan/Eigene Dateien/diploma" );
//#############################################################################
			
				
				++iteration;
			}
			
			// repaint all Displays showing a Layer of the edited LayerSet
			Display.update( set );
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
