import ini.trakem2.display.*;
import ini.trakem2.*;
import ini.trakem2.imaging.Registration;
import ini.trakem2.utils.Utils;
import ini.trakem2.persistence.FSLoader;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;

import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.Feature;
import mpi.fruitfly.registration.Model;
import mpi.fruitfly.registration.TModel2D;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;
import mpi.fruitfly.registration.Tile;
import mpi.fruitfly.registration.PointMatch;
import mpi.fruitfly.registration.Point;
import mpi.fruitfly.analysis.FitLine;
import mpi.fruitfly.general.MultiThreading;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Vector;
import java.util.List;
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


import java.util.concurrent.atomic.AtomicInteger;


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
	private static float cs_max_epsilon = 50.0f;
	private static float min_inlier_ratio = 0.05f;
	private static float scale = 1.0f;
	/**
	 * true if the layer is roughly aligned
	 * that means, topology and present overlapping will be incorporated
	 */
	private static boolean is_prealigned = false;
	
	final static private DecimalFormat decimalFormat = new DecimalFormat();
	final static private DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();


	
	private class Observer
	{
		public int i;			// iteration
		public double v;		// value
		public double d;		// first derivative
		public double m;		// mean
		public double var;		// variance
		public double std;		// standard-deviation
		
		public void add( double new_value )
		{
			if ( i == 0 )
			{
				i = 1;
				v = new_value;
				d = 0.0;
				m = v;
				var = 0.0;
				std = 0.0;
			}
			else
			{
				d = new_value - v;
				v = new_value;
				m = ( v + m * ( double )i++ ) / ( double )i;
				double tmp = v - m;
				var += tmp * tmp / ( double )i;
				std = Math.sqrt( var );
			}
		}
	}
	
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
	
	private void drawAndSaveIterationSnapshot(
			Layer layer,
			List< Tile > tiles,
			int iteration,
			int current,
			double od,
			String path )
	{
		int img_left = 0;
		int img_top = 0;
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
				false );	// low quality
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
	
	/**
	 * Identify point correspondences from two sets of tiles, patches and SIFT-features.
	 * 
	 * @note List< List< Feature > > should work but doesn't.
	 *  Java "generics" are the crappiest bullshit I have ever seen.
	 *  They should hire a linguist!
	 * 
	 * @param tiles1
	 * @param patches1
	 * @param tiles2
	 * @param patches2
	 */
	private void identifyCrossLayerCorrespondences(
			List< Tile > tiles1,
			List< Patch > patches1,
			List< Vector< Feature > > featureSets1,
			List< Tile > tiles2,
			List< Patch > patches2,
			List< Vector< Feature > > featureSets2,
			boolean is_prealigned )
	{
		int num_patches2 = patches2.size();
		int num_patches1 = patches1.size();
		for ( int i = 0; i < num_patches2; ++i )
		{
			Patch current_patch = patches2.get( i );
			Tile current_tile = tiles2.get( i );
			for ( int j = 0; j < num_patches1; ++j )
			{
				Patch other_patch = patches1.get( j );
				Tile other_tile = tiles1.get( j );
				if ( !is_prealigned || current_patch.intersects( other_patch ) )
				{
					long start_time = System.currentTimeMillis();
					System.out.print( "identifying correspondences using brute force ..." );
					Vector< PointMatch > candidates = FloatArray2DSIFT.createMatches(
								featureSets2.get( i ),
								featureSets1.get( j ),
								1.25f,
								null,
								Float.MAX_VALUE );
					System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
					
					IJ.log( "Tiles " + i + " and " + j + " have " + candidates.size() + " potentially corresponding features." );
					
					final Vector< PointMatch > inliers = new Vector< PointMatch >();
					
					TRModel2D mo = TRModel2D.estimateBestModel(
							candidates,
							inliers,
							cs_min_epsilon,
							cs_max_epsilon,
							min_inlier_ratio);

					if ( mo != null )
					{
						IJ.log( inliers.size() + " of them are good." );
						current_tile.connect( other_tile, inliers );								
					}
					else
					{
						IJ.log( "None of them is good." );
					}
				}
			}
		}
	}
	
	/**
	 * Interconnect disconnected graphs with synthetically added correspondences.
	 * This implies the tiles to be prealigned.
	 * 
	 * May fail if the disconnected graphs do not overlap at all.
	 * 
	 * @param graphs
	 * @param tiles
	 * @param patches
	 */
	private void connectDisconnectedGraphs(
			List< ArrayList< Tile > > graphs,
			List< Tile > tiles,
			List< Patch > patches )
	{
		/**
		 * We have to trust the given alignment.  Try to add synthetic
		 * correspondences to disconnected graphs having overlapping
		 * tiles.
		 */
		System.out.println( "Synthetically connecting graphs using the given alignment." );
		
		ArrayList< Tile > empty_tiles = new ArrayList< Tile >();
		for ( ArrayList< Tile > graph : graphs )
		{
			if ( graph.size() == 1 )
			{
				/**
				 *  This is a single unconnected tile.
				 */
				empty_tiles.add( graph.get( 0 ) );
			}
		}
		for ( ArrayList< Tile > graph : graphs )
		{
			for ( Tile tile : graph )
			{
				boolean is_empty = empty_tiles.contains( tile );
				Patch patch = patches.get( tiles.indexOf( tile ) );
				final Rectangle r = patch.getBoundingBox();
				// check this patch against each patch of the other graphs
				for ( ArrayList< Tile > other_graph : graphs )
				{
					if ( other_graph.equals( graph ) ) continue;
					for ( Tile other_tile : other_graph )
					{
						Patch other_patch = patches.get( tiles.indexOf( other_tile ) );
						if ( patch.intersects( other_patch ) )
						{
							/**
							 * TODO get a proper intersection polygon instead
							 *   of the intersection of bounding boxes.
							 *   
							 *   - Where to add the faked nails then?
							 */
							final Rectangle rp = other_patch.getBoundingBox().intersection( r );
							int xp1 = rp.x;
							int yp1 = rp.y;
							int xp2 = rp.x + rp.width;
							int yp2 = rp.y + rp.height;
							Point2D.Double dcp1 = patch.inverseTransformPoint( xp1, yp1 );
							Point2D.Double dcp2 = patch.inverseTransformPoint( xp2, yp2 );
							Point2D.Double dp1 = other_patch.inverseTransformPoint( xp1, yp1 );
							Point2D.Double dp2 = other_patch.inverseTransformPoint( xp2, yp2 );
							Point cp1 = new Point(
									new float[]{ ( float )dcp1.x, ( float )dcp1.y } );
							Point cp2 = new Point(
									new float[]{ ( float )dcp2.x, ( float )dcp2.y } );
							Point p1 = new Point(
									new float[]{ ( float )dp1.x, ( float )dp1.y } );
							Point p2 = new Point(
									new float[]{ ( float )dp2.x, ( float )dp2.y } );
							ArrayList< PointMatch > a1 = new ArrayList<PointMatch>();
							a1.add( new PointMatch( cp1, p1, 1.0f ) );
							a1.add( new PointMatch( cp2, p2, 1.0f ) );
							tile.addMatches( a1 );
							ArrayList< PointMatch > a2 = new ArrayList<PointMatch>();
							if ( is_empty )
							{
								/**
								 * very low weight instead of 0.0
								 * 
								 * TODO nothing could lead to disconntected graphs that were
								 *   connected by one empty tile only...
								 */
								a2.add( new PointMatch( p1, cp1, 0.1f ) );
								a2.add( new PointMatch( p2, cp2, 0.1f ) );
							}
							else
							{
								a2.add( new PointMatch( p1, cp1, 1.0f ) );
								a2.add( new PointMatch( p2, cp2, 1.0f ) );
							}
							other_tile.addMatches( a2 );
							
							// and tell them that they are connected now
							tile.addConnectedTile( other_tile );
							other_tile.addConnectedTile( tile );
						}
					}
				}
			}							
		}
	}
	
	/**
	 * minimize the overall displacement of a set of tiles, propagate the
	 * estimated models to a corresponding set of patches and redraw
	 * 
	 * @param tiles
	 * @param patches
	 * @param fixed_tiles do not touch these tiles
	 * @param update_this
	 * 
	 * TODO revise convergence check
	 *   particularly for unguided minimization, it is hard to identify
	 *   convergence due to presence of local minima
	 *   
	 *   Johannes Schindelin suggested to start from a good guess, which is
	 *   e.g. the propagated unoptimized pose of a tile relative to its
	 *   connected tile that was already identified during RANSAC
	 *   correpondence check.  Thank you, Johannes, great hint!
	 */
	private void minimizeAll(
			List< Tile > tiles,
			List< Patch > patches,
			List< Tile > fixed_tiles,
			Object update_this,
			float max_error )
	{
		int num_patches = patches.size();
//		boolean changed = true;
//		while ( changed )
//		{
//			changed = false;
//			for ( int i = 0; i < num_patches; ++i )
//			{
//				Tile tile = tiles2.get( i );
//				if ( tile == fixed ) continue;
//				tile.update();
//				
//				if ( tile.diceBetterModel( 100000, 1.0f ) )
//				{
//					patches2.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
//					
//					double od = 0.0;
//					for ( Tile t : tiles2 )
//					{
//						t.update();
//						od += t.getDistance();
//					}						
//					od /= tiles2.size();
//					
//					IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
//					
//					changed = true;
//				}					
//			}
//			
//			// repaint all Displays showing a Layer of the edited LayerSet
//			Display.update( set );
//		}
		
//#############################################################################
//		drawAndSaveIterationSnapshot(
//				layer,
//				tiles2,
//				0,
//				0,
//				Double.MAX_VALUE,
//				"/home/saalfeld" );
//#############################################################################
//#############################################################################
//		if ( update_this.getClass() == LayerSet.class )
//			drawAndSaveIterationSnapshot(
//				( ( LayerSet )update_this ).getLayer( 1 ),
//				tiles,
//				0,
//				-1,
//				0,
//				"/home/saalfeld" );
//#############################################################################


		double od = Double.MAX_VALUE;
		double dd = Double.MAX_VALUE;
		double min_d = Double.MAX_VALUE;
		double max_d = Double.MIN_VALUE;
		int iteration = 1;
		int cc = 0;
		double[] dall = new double[100];
		int next = 0;
		//while ( cc < 10 )
		
//		TODO empirical evaluation of the convergence
//#############################################################################
//		PrintStream f = System.out;
//		try
//		{
//			f = new PrintStream( new FileOutputStream( "minimize.dat", false ) );
//		}
//		catch ( FileNotFoundException e )
//		{
//			System.err.println( "File minimize.dat not found for writing." );
//		}
		Observer observer = new Observer();
//#############################################################################
		
		while ( next < 100000 )  // safety check
//		while ( next < 10000 )  // safety check
		{
			for ( int i = 0; i < num_patches; ++i )
			{
				Tile tile = tiles.get( i );
				if ( fixed_tiles.contains( tile ) ) continue;
				tile.update();
				tile.minimizeModel();
				tile.update();
				patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
				//IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
				
//#############################################################################
//				// show each single update step
//				double cd = 0.0;
//				for ( Tile t : tiles2 )
//				{
//					t.update();
//					cd += t.getDistance();
//				}						
//				cd /= tiles2.size();
//				Display.update( set );
//				drawAndSaveIterationSnapshot(
//						layer,
//						tiles2,
//						iteration,
//						i,
//						cd,
//						"/home/saalfeld" );
//#############################################################################
				
			}
			double cd = 0.0;
			min_d = Double.MAX_VALUE;
			max_d = Double.MIN_VALUE;
			for ( Tile t : tiles )
			{
				t.update();
				double d = t.getDistance();
				if ( d < min_d ) min_d = d;
				if ( d > max_d ) max_d = d;
				cd += d;
			}
			cd /= tiles.size();
			dd = Math.abs( od - cd );
			od = cd;
			IJ.showStatus( "displacement: " + decimalFormat.format( od ) + " [" + decimalFormat.format( min_d ) + "; " + decimalFormat.format( max_d ) + "] after " + iteration + " iterations");
			
			observer.add( od );			
//			f.println( observer.i + " " + observer.v + " " + observer.d + " " + observer.m + " " + observer.std );
			
			//cc = d < 0.00025 ? cc + 1 : 0;
			cc = dd < 0.001 ? cc + 1 : 0;

			if (dall.length  == next) {
				double[] dall2 = new double[dall.length + 100];
				System.arraycopy(dall, 0, dall2, 0, dall.length);
				dall = dall2;
			}
			dall[next++] = dd;
			
			// cut the last 'n'
			if (next > 100) { // wait until completing at least 'n' iterations
				double[] dn = new double[100];
				System.arraycopy(dall, dall.length - 100, dn, 0, 100);
				// fit curve
				double[] ft = FitLine.fitLine(dn);
				// ft[1] StdDev
				// ft[2] m (slope)
				//if ( Math.abs( ft[ 1 ] ) < 0.001 )

				// TODO revise convergence check or start from better guesses
				if ( od < max_error && ft[ 2 ] >= 0.0 )
				{
					System.out.println( "Exiting at iteration " + next + " with slope " + decimalFormat.format( ft[ 2 ] ) );
					break;
				}

			}

			// repaint all Displays showing those patches
			if ( update_this.getClass() == Layer.class )
			{
				Display.update( ( Layer )update_this );
//#############################################################################
//				drawAndSaveIterationSnapshot(
//						( Layer )update_this,
//						tiles,
//						iteration,
//						-1,
//						od,
//						"/home/saalfeld" );
//#############################################################################
			}
			else if ( update_this.getClass() == LayerSet.class )
			{
				Display.update( ( LayerSet )update_this );
//#############################################################################
//				drawAndSaveIterationSnapshot(
//						( ( LayerSet )update_this ).getLayer( 1 ),
//						tiles,
//						iteration,
//						-1,
//						od,
//						"/home/saalfeld" );
//#############################################################################

			}

			
			++iteration;
		}
//		f.close();
		
		System.out.println( "Successfully optimized configuration of " + tiles.size() + " tiles:" );
		System.out.println( "  average displacement: " + decimalFormat.format( od ) + "px" );
		System.out.println( "  minimal displacement: " + decimalFormat.format( min_d ) + "px" );
		System.out.println( "  maximal displacement: " + decimalFormat.format( max_d ) + "px" );
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
		
		final LayerSet set = front.getLayer().getParent();
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
		gd.addNumericField( "inlier_ratio :", min_inlier_ratio, 2 );
		gd.addChoice( "transformations_to_be_optimized :", dimensions, dimensions[ dimension ] );
		gd.addCheckbox( "layers_are_roughly_prealigned_already", is_prealigned );
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
		min_inlier_ratio = ( float )gd.getNextNumber();
		final String dimension_str = gd.getNextChoice();
		is_prealigned = gd.getNextBoolean();


		// ask for SIFT parameters
		final Registration.SIFTParameters sp_gross_interlayer = new Registration.SIFTParameters(set.getProject());
		if (!sp_gross_interlayer.setup()) return;

		// start:

		final ArrayList< Layer > layers = set.getLayers();
		final ArrayList< Vector< Feature > > featureSets1 = new ArrayList< Vector< Feature > >();
		final ArrayList< Vector< Feature > > featureSets2 = new ArrayList< Vector< Feature > >();


		final ArrayList< Patch > patches1 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles1 = new ArrayList< Tile >();
		final ArrayList< Patch > patches2 = new ArrayList< Patch >();
		final ArrayList< Tile > tiles2 = new ArrayList< Tile >();

		final Thread[] threads = MultiThreading.newThreads();
		final FloatArray2DSIFT[] sift = new FloatArray2DSIFT[ threads.length ];
		for ( int k=0; k < threads.length; k++ )
			sift[ k ] = new FloatArray2DSIFT( fdsize, fdbins );
		
		// roughly, we expect about 1000 features per 512x512 image
		final long feature_size = (long)((max_size * max_size) / (512 * 512) * 1000 * sift[0].getFeatureObjectSize() * 1.5);

		final ArrayList< Tile > all_tiles = new ArrayList< Tile >();
		final ArrayList< Patch > all_patches = new ArrayList< Patch >();

		final ArrayList< Tile > fixed_tiles = new ArrayList< Tile >();

		Layer previous_layer = null;

		// the storage folder for serialized features
		final FSLoader loader = (FSLoader)set.getProject().getLoader();
		String xml_file_path = loader.getProjectXMLPath();
		File xfile = new File(xml_file_path);
		String parent_dir = xfile.getParent().replace('\\', '/');
		if (!parent_dir.endsWith("/")) parent_dir += "/";
		String storage_folder_ = parent_dir + xfile.getName() + ".features.ser/";
		File sdir = new File(storage_folder_);
		if (!sdir.exists()) {
			try {
				sdir.mkdir();
			} catch (Exception e) {
				storage_folder_ = null; // can't store
			}
		}
		final String storage_folder = storage_folder_;


		for ( Layer layer : layers )
		{
			final ArrayList< Tile > layer_fixed_tiles = new ArrayList< Tile >();

			IJ.log( "###############\nStarting layer " + ( set.indexOf( layer ) + 1 ) + " of " + set.size() + "\n###############" );

			// ignore empty layers
			if ( !layer.contains( Patch.class ) )
			{
				IJ.log( "Ignoring empty layer." );
				continue;
			}

			if ( null != previous_layer )
			{
				featureSets1.clear();
				featureSets1.addAll( featureSets2 );

				patches1.clear();
				patches1.addAll( patches2 );

				tiles1.clear();
				tiles1.addAll( tiles2 );
			}

			patches2.clear();
			featureSets2.clear();
			tiles2.clear();
			
			patches2.addAll( layer.getDisplayables( Patch.class ) );

			// extract SIFT-features in all patches
			// TODO store the feature sets on disk, each of them might be in the magnitude of 10MB large

			final AtomicInteger ai = new AtomicInteger( 0 ); // from 0 to patches2.length
			final int num_pa2 = patches2.size();

			final Patch[] pa2 = new Patch[ num_pa2 ];
			patches2.toArray( pa2 );
			final Vector[] fsets = new Vector[ num_pa2 ];
			final Tile[] tls = new Tile[ num_pa2 ];

//			//#################################################################
//			// single threaded version
//			
//			for ( int k = 0; k < num_pa2; ++k )
//			{
//				System.out.println( "k is " + k );
//				Patch patch = pa2[ k ];
//				if ( null == patch ) System.out.println( "patch is null" );
//				System.out.println( "patch is " + patch );
//				
//				final ImagePlus imp = patch.getProject().getLoader().fetchImagePlus( patch );
//				if ( null == imp ) System.out.println( "imp is null" );
//				FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( imp.getProcessor().convertToByte( true ) );
//			
//				set.getProject().getLoader().releaseToFit( imp.getWidth() * imp.getHeight() * 96L + feature_size );
//				
////				ImagePlus imp = null;
////				boolean exception = true;
////				FloatArray2D fa = null;
////				
////				while ( exception || fa == null )
////				{
////					try
////					{
////						imp = patch.getProject().getLoader().fetchImagePlus( patch );
////						if ( null == imp ) System.out.println( "imp is null" );
////						ImageProcessor tmp = imp.getProcessor();
////						if ( tmp == null ) System.out.println( "imageprocessor is null" );
////					
////						set.getProject().getLoader().releaseToFit( imp.getWidth() * imp.getHeight() * 96L + feature_size );
////				
////						fa = ImageArrayConverter.ImageToFloatArray2D( imp.getProcessor().convertToByte( true ) );
////						exception = false;
////					}
////					catch ( Exception e )
////					{
////						exception = true;
////						System.out.println( "Exception happened..." );
////					}
////				}
//				
//				if ( null == fa ) System.out.println( "fa is null" );
//				ImageFilter.enhance( fa, 1.0f );
//				fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
//				if ( null == fa ) System.out.println( "fa is null" );
//				
//				long start_time = System.currentTimeMillis();
//				System.out.print( "processing SIFT ..." );
//				sift[ 0 ].init( fa, steps, initial_sigma, min_size, max_size );
//				Vector< Feature > fs = sift[ 0 ].run( max_size );
//				Collections.sort( fs );
//				System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
//				
//				System.out.println( fs.size() + " features identified and processed" );
//				
//				Model model;
//				
//				if ( dimension_str.equals( "translation" ) )
//					model = new TModel2D();
//				else
//					model = new TRModel2D();
//				
//				model.getAffine().setTransform( patch.getAffineTransform() );
//				Tile tile = new Tile( ( float )fa.width, ( float )fa.height, model );
//				
//				//tiles2.add( tile );
//				tls[ k ] = tile;
//			
//				//featureSets2.add( fs );
//				fsets[ k ] = fs;
//			}
//			//#################################################################

			//#################################################################
			// multi threaded version
			
			for (int ithread = 0; ithread < threads.length; ++ithread)
			{
				final int si = ithread;
				threads[ ithread ] = new Thread( new Runnable()
				{
					public void run()
					{
						for ( int k = ai.getAndIncrement(); k < num_pa2; k = ai.getAndIncrement() )
						{

				System.out.println("k is " + k);
				Patch patch = pa2[ k ];
				if (null == patch) System.out.println("patch is null");
				System.out.println("patch is " + patch);
				
				final ImagePlus imp = patch.getProject().getLoader().fetchImagePlus( patch );
				if (null == imp) System.out.println("imp is null");
				
				FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( imp.getProcessor().convertToByte( true ) );
				if (null == fa) System.out.println("fa is null");
				
				set.getProject().getLoader().releaseToFit(imp.getWidth() * imp.getHeight() * 96L + feature_size);

				ImageFilter.enhance( fa, 1.0f );
				fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
				if (null == fa) System.out.println("fa is null");
				
				long start_time = System.currentTimeMillis();
				System.out.print( "processing SIFT ..." );

				Vector< Feature > fs = loader.retrieve( patch, storage_folder );
				if (null == fs)
				{
					sift[si].init( fa, steps, initial_sigma, min_size, max_size );
					fs = sift[si].run( max_size );
					loader.store( patch, fs, storage_folder );
				}
				Collections.sort( fs );
				System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
				
				System.out.println( fs.size() + " features identified and processed" );
				
				Model model;
				
				if ( dimension_str.equals( "translation" ) )
					model = new TModel2D();
				else
					model = new TRModel2D();
				
				model.getAffine().setTransform( patch.getAffineTransform() );
				Tile tile = new Tile( ( float )fa.width, ( float )fa.height, model );
				
				//tiles2.add( tile );
				tls[ k ] = tile;

				//featureSets2.add( fs );
				fsets[ k ] = fs;

						}
					}
				} );
			}
        	
			MultiThreading.startAndJoin(threads);

			//#################################################################


			for ( int k = 0; k < num_pa2; k++ )
			{
				if ( fsets[ k ] == null ) System.out.println( "Feature set " + k + " is null." );
				featureSets2.add( fsets[ k ] );
				tiles2.add( tls[ k ] );
			}

			// identify correspondences and inspect connectivity
			int num_patches = patches2.size();
			for ( int i = 0; i < num_patches; ++i )
			{
				Patch current_patch = patches2.get( i );
				Tile current_tile = tiles2.get( i );
				for ( int j = i + 1; j < num_patches; ++j )
				{
					Patch other_patch = patches2.get( j );
					Tile other_tile = tiles2.get( j );
					if ( !is_prealigned || current_patch.intersects( other_patch ) )
					{
						long start_time = System.currentTimeMillis();
						System.out.print( "Tiles " + i + " and " + j + ": identifying correspondences using brute force ..." );
						Vector< PointMatch > correspondences = FloatArray2DSIFT.createMatches(
									featureSets2.get( i ),
									featureSets2.get( j ),
									1.25f,
									null,
									Float.MAX_VALUE );
						System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
						
						IJ.log( "Tiles " + i + " and " + j + " have " + correspondences.size() + " potentially corresponding features." );
						
						final Vector< PointMatch > inliers = new Vector< PointMatch >();
						
						TRModel2D model = TRModel2D.estimateBestModel(
								correspondences,
								inliers,
								min_epsilon,
								max_epsilon,
								min_inlier_ratio );
						
						if ( model != null ) // that implies that inliers is not empty
							current_tile.connect( other_tile, inliers );
					}
				}
			}
			
			// identify connected graphs
			ArrayList< ArrayList< Tile > > graphs = Tile.identifyConnectedGraphs( tiles2 );
			System.out.println( graphs.size() + " graphs detected." );
			
			if ( is_prealigned && graphs.size() > 1 )
			{
				/**
				 * We have to trust the given alignment.  Try to add synthetic
				 * correspondences to disconnected graphs having overlapping
				 * tiles.
				 */
				System.out.println( "Synthetically connecting graphs using the given alignment." );
				
				this.connectDisconnectedGraphs( graphs, tiles2, patches2 );
				
				/**
				 * check the connectivity graphs again.  Hopefully there is
				 * only one graph now.  If not, we still have to fix one tile
				 * per graph, regardless if it is only one or several oth them.
				 */
				graphs = Tile.identifyConnectedGraphs( tiles2 );
				System.out.println( graphs.size() + " graphs detected after synthetic connection." );
			}
			
			// fix one tile per graph, meanwhile update the tiles
			for ( ArrayList< Tile > graph : graphs )
			{
				Tile fixed = null;
				int max_num_matches = 0;
				for ( Tile tile : graph )
				{
					tile.update();
					int num_matches = tile.getNumMatches();
					if ( max_num_matches < num_matches )
					{
						max_num_matches = num_matches;
						fixed = tile;
					}
				}
				layer_fixed_tiles.add( fixed );
			}
			
			// update all tiles, for error and distance correction
			for ( Tile tile : tiles2 ) tile.update();
			
			// optimize the pose of all tiles in the current layer
			minimizeAll( tiles2, patches2, layer_fixed_tiles, layer, max_epsilon );
			
			// repaint all Displays showing a Layer of the edited LayerSet
			Display.update( set );

			// store for global minimization
			all_tiles.addAll( tiles2 );
			all_patches.addAll( patches2 );

			if ( null != previous_layer )
			{
				/**
				 * Coarse registration
				 * 
				 * TODO Think about re-using the correspondences identified
				 *  during coarse registration for the tiles.  That introduces
				 *  the following issues:
				 *  
				 *  - coordinate transfer of snapshot-coordinates to
				 *    layer-coordinates in both layers
				 *  - identification of the closest tiles in both layers
				 *    (whose centers are closest to the layer-coordinate of the
				 *    detection)
				 *  - appropriate weight for the correspondence
				 *  - if this is the sole correpondence of a tile, minimization
				 *    as well as model estimation of higher order models than
				 *    translation will fail because of missing information
				 *    -> How to handle this, how to handle this for
				 *       graph-connectivity?
				 */
				
				/**
				 * returns an Object[] with
				 *   [0] AffineTransform that transforms layer towards previous_layer
				 *   [1] bounding box of previous_layer in world coordinates
				 *   [2] bounding box of layer in world coordinates
				 *   [3] true correspondences with p1 in layer and p2 in previous_layer,
				 *       both in the local coordinate frames defined by box1 and box2 and
				 *       scaled with sp_gross_interlayer.scale
				 */
				Object[] ob = Registration.registerSIFT( previous_layer, layer, null, sp_gross_interlayer );
				int original_max_size = sp_gross_interlayer.max_size;
				float original_max_epsilon = sp_gross_interlayer.max_epsilon;
				while (null == ob || null == ob[0]) {
					int next_max_size = sp_gross_interlayer.max_size;
					float next_max_epsilon = sp_gross_interlayer.max_epsilon;
					// need to recurse up both the max size and the maximal alignment error
					if (next_max_epsilon < 300) {
						next_max_epsilon += 100;
					}
					Rectangle rfit1 = previous_layer.getMinimalBoundingBox(Patch.class);
					Rectangle rfit2 = layer.getMinimalBoundingBox(Patch.class);
					if (next_max_size < rfit1.width || next_max_size < rfit1.height
					 || next_max_size < rfit2.width || next_max_size < rfit2.height) {
						next_max_size += 1024;
					} else {
						// fail completely
						Utils.log2("FAILED to align layers " + set.indexOf(previous_layer) + " and " + set.indexOf(layer));
						// Need to fall back to totally unguided double-layer registration
						// TODO
						//
						break;
					}
					sp_gross_interlayer.max_size = next_max_size;
					sp_gross_interlayer.max_epsilon = next_max_epsilon;
					ob = Registration.registerSIFT(previous_layer, layer, null, sp_gross_interlayer);
				}
				// fix back modified parameters
				sp_gross_interlayer.max_size = original_max_size;
				sp_gross_interlayer.max_epsilon = original_max_epsilon;

				if ( null != ob && null != ob[ 0 ] )
				{
					// defensive programming ... ;)
					AffineTransform at = ( AffineTransform )ob[ 0 ];
					Rectangle previous_layer_box = ( Rectangle )ob[ 1 ];
					Rectangle layer_box = ( Rectangle )ob[ 2 ];
					Vector< PointMatch > inliers = ( Vector< PointMatch > )ob[ 3 ];
					
					/**
					 * Find the closest tiles in both layers for each of the
					 * inliers and append a correponding nail to it
					 */
					for ( PointMatch inlier : inliers )
					{
						// transfer the coordinates to actual world coordinates 
						float[] previous_layer_coords = inlier.getP2().getL();
						previous_layer_coords[ 0 ] = previous_layer_coords[ 0 ] / sp_gross_interlayer.scale + previous_layer_box.x;
						previous_layer_coords[ 1 ] = previous_layer_coords[ 1 ] / sp_gross_interlayer.scale + previous_layer_box.y;
						
						float[] layer_coords = inlier.getP1().getL();
						layer_coords[ 0 ] = layer_coords[ 0 ] / sp_gross_interlayer.scale + layer_box.x;
						layer_coords[ 1 ] = layer_coords[ 1 ] / sp_gross_interlayer.scale + layer_box.y;
						
						// find the tile whose center is closest to the points in previous_layer
						Tile previous_layer_closest_tile = null;
						float previous_layer_min_d = Float.MAX_VALUE;
						for ( Tile tile : tiles1 )
						{
							tile.update();
							float[] tw = tile.getWC();
							float dx = tw[ 0 ] - previous_layer_coords[ 0 ];
							dx *= dx;
							float dy = tw[ 1 ] - previous_layer_coords[ 1 ];
							dy *= dy;
							
							float d = ( float )Math.sqrt( dx + dy );
							if ( d < previous_layer_min_d )
							{
								previous_layer_min_d = d;
								previous_layer_closest_tile = tile;
							}
						}
						
						System.out.println( "Tile " + tiles1.indexOf( previous_layer_closest_tile ) + " is closest in previous layer:" );
						System.out.println( "  distance: " + previous_layer_min_d );
						
						
						// find the tile whose center is closest to the points in layer
						Tile layer_closest_tile = null;
						float layer_min_d = Float.MAX_VALUE;
						for ( Tile tile : tiles2 )
						{
							tile.update();
							float[] tw = tile.getWC();
							float dx = tw[ 0 ] - layer_coords[ 0 ];
							dx *= dx;
							float dy = tw[ 1 ] - layer_coords[ 1 ];
							dy *= dy;
							
							float d = ( float )Math.sqrt( dx + dy );
							if ( d < layer_min_d )
							{
								layer_min_d = d;
								layer_closest_tile = tile;
							}
						}
						
						System.out.println( "Tile " + tiles2.indexOf( layer_closest_tile ) + " is closest in layer:" );
						System.out.println( "  distance: " + layer_min_d );
						
						if ( previous_layer_closest_tile != null && layer_closest_tile != null )
						{
//							System.out.println( "world coordinates in previous layer: " + previous_layer_coords[ 0 ] + ", " + previous_layer_coords[ 1 ] );
//							System.out.println( "world coordinates in layer: " + layer_coords[ 0 ] + ", " + layer_coords[ 1 ] );
							
							// transfer the world coordinates to local tile coordinates
							previous_layer_closest_tile.getModel().applyInverseInPlace( previous_layer_coords );
							layer_closest_tile.getModel().applyInverseInPlace( layer_coords );
							
//							System.out.println( "local coordinates in previous layer: " + previous_layer_coords[ 0 ] + ", " + previous_layer_coords[ 1 ] );
//							System.out.println( "local coordinates in layer: " + layer_coords[ 0 ] + ", " + layer_coords[ 1 ] );
							
							// create PointMatch for both tiles
							Point previous_layer_point = new Point( previous_layer_coords );
							Point layer_point = new Point( layer_coords );
							
							previous_layer_closest_tile.addMatch(
									new PointMatch(
											previous_layer_point,
											layer_point,
											inlier.getWeight() / sp_gross_interlayer.scale ) );
							layer_closest_tile.addMatch(
									new PointMatch(
											layer_point,
											previous_layer_point,
											inlier.getWeight() / sp_gross_interlayer.scale ) );
							
							previous_layer_closest_tile.addConnectedTile( layer_closest_tile );
							layer_closest_tile.addConnectedTile( previous_layer_closest_tile );
						}
					}
					
					TRModel2D model = new TRModel2D();
					model.getAffine().setTransform( at );
					for ( Tile tile : tiles2 )
						( ( TRModel2D )tile.getModel() ).preConcatenate( model );
				
					// repaint all Displays showing a Layer of the edited LayerSet
					Display.update( layer );
				}
				this.identifyCrossLayerCorrespondences(
						tiles1,
						patches1,
						featureSets1,
						tiles2,
						patches2,
						featureSets2,
						( null != ob && null != ob[ 0 ] ) );
				
				// check the connectivity graphs
				ArrayList< Tile > both_layer_tiles = new ArrayList< Tile >();
				both_layer_tiles.addAll( tiles1 );
				both_layer_tiles.addAll( tiles2 );
				graphs = Tile.identifyConnectedGraphs( both_layer_tiles );
				
				System.out.println( graphs.size() + " cross-section graphs detected." );
				
//				if ( graphs.size() > 1 && ( null != ob && null != ob[ 0 ] ) )
//				{
//					/**
//					 * We have to trust the given alignment.  Try to add synthetic
//					 * correspondences to disconnected graphs having overlapping
//					 * tiles.
//					 */
//					ArrayList< Patch > both_layer_patches = new ArrayList< Patch >();
//					both_layer_patches.addAll( patches1 );
//					both_layer_patches.addAll( patches2 );
//					this.connectDisconnectedGraphs( graphs, both_layer_tiles, both_layer_patches );
//					
//					/**
//					 * check the connectivity graphs again.  Hopefully there is
//					 * only one graph now.  If not, we still have to fix one tile
//					 * per graph, regardless if it is only one or several of them.
//					 */
//					graphs = Tile.identifyConnectedGraphs( tiles2 );
//					System.out.println( graphs.size() + " cross-section graphs detected after synthetic connection." );
//				}				
			}

			previous_layer = layer;
		}
	
		// find the global nail
		/**
		 * One tile per connected graph has to be fixed to make the problem
		 * solvable, otherwise it is ill defined and has an infinite number
		 * of solutions.
		 */
		
		ArrayList< ArrayList< Tile > > graphs = Tile.identifyConnectedGraphs( all_tiles );
		System.out.println( graphs.size() + " global graphs detected." );
		
		fixed_tiles.clear();
		// fix one tile per graph, meanwhile update the tiles
		for ( ArrayList< Tile > graph : graphs )
		{
			Tile fixed = null;
			int max_num_matches = 0;
			for ( Tile tile : graph )
			{
				tile.update();
				int num_matches = tile.getNumMatches();
				if ( max_num_matches < num_matches )
				{
					max_num_matches = num_matches;
					fixed = tile;
				}
			}
			fixed_tiles.add( fixed );
		}
		
		// again, for error and distance correction
		for ( Tile tile : all_tiles ) tile.update();

		// global minimization
		minimizeAll( all_tiles, all_patches, fixed_tiles, set, cs_max_epsilon );

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
