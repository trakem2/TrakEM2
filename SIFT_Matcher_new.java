//package mpi.fruitfly.registration;

import static mpi.fruitfly.math.General.*;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.FloatArray2DSIFT;
//import mpi.fruitfly.registration.RoiList;
import mpi.fruitfly.registration.TRModel;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;

import imagescience.transforms.*;
import imagescience.images.Image;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.Vector;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.*;


public class SIFT_Matcher_new implements PlugIn, KeyListener
{
	private static final int min_size = 64;
	private static final String[] schemes = {
		"nearest neighbor",
		"linear",
		"cubic convolution",
		"cubic B-spline",
		"cubic O-MOMS",
		"quintic B-spline"
		};
	private static int scheme = 5;

	// steps
	private static String steps_str = "3";
	private static int steps = 3;

	// initial sigma
	private static String initial_sigma_str = "1.6";
	private static float initial_sigma = 1.6f;

	// background colour
	private static String bg_str = "0.0";
	private static double bg = 0.0;

	// feature descriptor size
	private static String fdsize_str = "8";
	private static int fdsize = 8;

	// feature descriptor orientation bins
	private static String fdbins_str = "8";
	private static int fdbins = 8;

	// scale factor for the image
	private static String max_size_str = "1024";
	private static int max_size = 1024;
	private static double scale = 1.0;

	// minimal allowed alignment error in px
	private static String min_epsilon_str = "2.0";
	private static float min_epsilon = 2.0f;

	// maximal allowed alignment error in px
	private static String max_epsilon_str = "100.0";
	private static float max_epsilon = 100.0f;

	
	private static boolean adjust = false;
	private static boolean antialias = true;

	/**
	 * draw an arbitrarily rotated ellipse
	 * @param double c[] contains the eigenvector for x
	 */
	static void drawEllipse( ImageProcessor ip, double[] evec, double[] o, double[] e, double scale )
	{
		//System.out.println( "eigenvector: ( " + evec[ 0 ] + ", " + evec[ 1 ] + ")" );
		//System.out.println( "			 ( " + evec[ 2 ] + ", " + evec[ 3 ] + ")" );
		//System.out.println( "eigenvalues: ( " + e[ 0 ] + ", " + e[ 1 ] + ")" );
		int num_keys = 36;
		int[] x_keys = new int[ num_keys + 1 ];
		int[] y_keys = new int[ num_keys + 1 ];
		for ( int i = 0; i < num_keys; ++i )
		{
			double r = ( double )i * 2 * Math.PI / ( double )num_keys;
			double x = Math.sin( r ) * Math.sqrt( Math.abs( e[ 0 ] ) );
			double y = Math.cos( r ) * Math.sqrt( Math.abs( e[ 1 ] ) );
			x_keys[ i ] = ( int )( scale * ( x * evec[ 0 ] + y * evec[ 2 ] + o[ 0 ] ) );
			y_keys[ i ] = ( int )( scale * ( x * evec[ 1 ] + y * evec[ 3 ] + o[ 1 ] ) );
			//System.out.println( "keypoint: ( " + x_keys[ i ] + ", " + y_keys[ i ] + ")" );
		}
		x_keys[ num_keys ] = x_keys[ 0 ];
		y_keys[ num_keys ] = y_keys[ 0 ];
		ip.drawPolygon( new Polygon( x_keys, y_keys, num_keys + 1 ) );
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

	public void run( String args )
	{
		if ( IJ.versionLessThan( "1.37i" ) ) return;

		final ImagePlus imp = WindowManager.getCurrentImage();
		if ( imp == null )  { System.err.println( "There are no images open" ); return; }

		GenericDialog gd = new GenericDialog( "Align stack" );
		gd.addStringField( "Steps per scale octave:", steps_str, 2 );
		gd.addStringField( "Initial gaussian blur (Sigma):", initial_sigma_str, 2 );
		gd.addStringField( "Feature descriptor size:", fdsize_str, 2 );
		gd.addStringField( "Feature descriptor orientation bins:", fdbins_str, 2 );
		gd.addStringField( "Maximum image size:", max_size_str, 2 );
		gd.addStringField( "Minimal alignment error [px]:", min_epsilon_str, 2 );
		gd.addStringField( "Maximal alignment error [px]:", max_epsilon_str, 2 );
		final Vector< TextField > tfs = gd.getStringFields();
		tfs.get( 0 ).addKeyListener( this );
		gd.addPanel( new Panel(),GridBagConstraints.WEST, new Insets( 0,0,0,0 ) );
		gd.addChoice( "Interpolation scheme:",schemes, schemes[ scheme ] );
		gd.addStringField( "Background value:", bg_str );
		//gd.addCheckbox( " Adjust size to fit result", adjust );
		//gd.addCheckbox( " Anti-alias borders", antialias );
		gd.showDialog();

		if (gd.wasCanceled()) return;

		steps_str = gd.getNextString();
		initial_sigma_str = gd.getNextString();
		fdsize_str = gd.getNextString();
		fdbins_str = gd.getNextString();
		max_size_str = gd.getNextString();
		min_epsilon_str = gd.getNextString();
		max_epsilon_str = gd.getNextString();
		scheme = gd.getNextChoiceIndex();
		bg_str = gd.getNextString();
		//adjust = gd.getNextBoolean();
		//antialias = gd.getNextBoolean();

		Affine a = new Affine();
		
		try
		{
			bg = Double.parseDouble( bg_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid background value" );
		}
		a.background( bg );
		
		try
		{
			steps = Integer.parseInt( steps_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid steps value." );
		}

		try
		{
			initial_sigma = Float.parseFloat( initial_sigma_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid Sigma value." );
		}
		if ( initial_sigma < 0.5 )
			throw new IllegalArgumentException( "Invalid Sigma value, must not be smaller than 0.5)." );

		try
		{
			fdsize = Integer.parseInt( fdsize_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid feature descriptor size value" );
		}

		try
		{
			fdbins = Integer.parseInt( fdbins_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid feature descriptor bins value" );
		}

		try
		{
			max_size = Integer.parseInt( max_size_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid image max_size value" );
		}

		try
		{
			min_epsilon = Float.parseFloat( min_epsilon_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid minimal error value" );
		}

		try
		{
			max_epsilon = Float.parseFloat( max_epsilon_str );
		}
		catch ( Exception e )
		{
			throw new IllegalArgumentException( "Invalid maximal error value" );
		}

		int ischeme = Affine.NEAREST;
		switch ( scheme )
		{
		case 0:
			ischeme = Affine.NEAREST;
			break;
		case 1:
			ischeme = Affine.LINEAR;
			break;
		case 2:
			ischeme = Affine.CUBIC;
			break;
		case 3:
			ischeme = Affine.BSPLINE3;
			break;
		case 4:
			ischeme = Affine.OMOMS3;
			break;
		case 5:
			ischeme = Affine.BSPLINE5;
			break;
		}

		ImageStack stack = imp.getStack();
		ImageStack stackAligned = new ImageStack( stack.getWidth(), stack.getHeight() );
		
		float vis_scale = 256.0f / imp.getWidth();
		
		ImageStack stackInfo = new ImageStack(
				Math.round( vis_scale * stack.getWidth() ),
				Math.round( vis_scale * stack.getHeight() ) );
		stackAligned.addSlice( null, stack.getProcessor( 1 ) );
		ImagePlus impAligned = new ImagePlus( "Aligned 1 of " + stack.getSize(), stackAligned );
		impAligned.show();
		ImagePlus impInfo = null;
		
		//scale = ( ( float )max_size / ( float )stack.getWidth() );
		
		ImageProcessor ip1;
		ImageProcessor ip2;
		ImageProcessor ip3;
		
		Vector< FloatArray2DSIFT.Feature > fs1;
		Vector< FloatArray2DSIFT.Feature > fs2;

		ip2 = stack.getProcessor( 1 ).convertToFloat();
		
//		imp1.setProcessor(null, imp1.getProcessor().resize((int)(0.5 * imp1.getWidth())));

		AffineTransform at = new AffineTransform();
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( ip2 );
		fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
		
		long start_time = System.currentTimeMillis();
		System.out.print( "processing SIFT ..." );
		sift.init( fa, steps, initial_sigma, min_size, max_size );
		fs2 = sift.run( max_size );
		Collections.sort( fs2 );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
		System.out.println( fs2.size() + " features identified and processed" );
		
		/*
		try
		{
			PrintStream f = new PrintStream( new FileOutputStream( "size.dat", true ) );
			float[] h = sift.featureSizeHistogram( fs2, 0.0f, 127.0f, 197 );
			for ( float b : h )
			{
				f.println( b );
			}
			f.close();
		}
		catch ( FileNotFoundException e )
		{
			System.err.println( "File size.dat not found for writing." );
		}
		
		try
		{
			PrintStream f = new PrintStream( new FileOutputStream( "size.all.dat", true ) );
			for ( FloatArray2DSIFT.Feature g : fs2 )
			{
				f.println( g.scale );
			}
			f.close();
		}
		catch ( FileNotFoundException e )
		{
			System.err.println( "File size.all.dat not found for writing." );
		}
		*/
		
		// downscale ip2 to width=256px for visualisation purposes
		ip2 = downScale( ( FloatProcessor )ip2, vis_scale );
		
		for ( int i = 1; i < stack.getSize(); ++i )
		{
			ip1 = ip2;
			ip2 = stack.getProcessor( i + 1 ).convertToFloat();
			fa = ImageArrayConverter.ImageToFloatArray2D( ip2 );
			fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
			
			fs1 = fs2;
			
			start_time = System.currentTimeMillis();
			System.out.print( "processing SIFT ..." );
			sift.init( fa, steps, initial_sigma, min_size, max_size );
			fs2 = sift.run( max_size);
			Collections.sort( fs2 );
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
			
			System.out.println( fs2.size() + " features identified and processed");
			
			start_time = System.currentTimeMillis();
			System.out.print( "identifying correspondences using brute force ..." );
			Vector< Match > correspondences =
				FloatArray2DSIFT.createMatches( fs1, fs2, 1.5f, null, Float.MAX_VALUE );
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
			
			IJ.log( correspondences.size() + " potentially corresponding features identified" );
			
			/**
			 * We want to assure, that the model does not fit to a local spatial
			 * subset of all matches only, because this signalizes a good local
			 * alignment but bad global results.
			 *
			 * Therefore, we compare the Eigenvalues of the covariance matrix of
			 * all points to those of the inliers matching the model.  That is, we
			 * compare the variance of those point sets.  A low variance of the
			 * inliers compared to those of all matches signifies a very local
			 * subset.
			 */

			double[] ev1 = new double[ 2 ];
			double[] ev2 = new double[ 2 ];
			double[] cov1 = new double[ 3 ];
			double[] cov2 = new double[ 3 ];
			double[] o1 = new double[ 2 ];
			double[] o2 = new double[ 2 ];
			double[] evec1 = new double[ 4 ];
			double[] evec2 = new double[ 4 ];
			Match.covariance( correspondences, cov1, cov2, o1, o2, ev1, ev2, evec1, evec2 );
			

			
			/**
			 * draw standard-deviation ellipse of all identified correspondences and
			 * all the correspondences
			 */
			
			vis_scale = 256.0f / ip2.getWidth();
			
			// downscale ip2 to width=256px for visualisation purposes
			ip2 = downScale( ( FloatProcessor )ip2, vis_scale );
			
			ip1 = ip1.convertToRGB();
			ip3 = ip2.convertToRGB();
			ip1.setLineWidth( 1 );
			ip3.setLineWidth( 1 );
			ip1.setColor( Color.red );
			ip3.setColor( Color.red );
//			System.out.println( "Mean P1" );
//			System.out.println( "  x0 = " + o1[ 0 ] );
//			System.out.println( "  y0 = " + o1[ 1 ] );
//			System.out.println( "Covariance P1" );
//			System.out.println( "  xx = " + cov1[ 0 ] );
//			System.out.println( "  xy = " + cov1[ 1 ] );
//			System.out.println( "  yy = " + cov1[ 2 ] );
			drawEllipse( ip1, evec1, o1, ev1, vis_scale );
			drawEllipse( ip3, evec2, o2, ev2, vis_scale );

			ip1.setLineWidth( 2 );
			ip3.setLineWidth( 2 );
			for ( Match m : correspondences )
			{
				ip1.drawDot( ( int )Math.round( vis_scale * m.p1[ 0 ] ), ( int )Math.round( vis_scale * m.p1[ 1 ] ) );
				ip3.drawDot( ( int )Math.round( vis_scale * m.p2[ 0 ] ), ( int )Math.round( vis_scale * m.p2[ 1 ] ) );
			}

			TRModel model = null;
			float[] tr = new float[ 5 ];
			float epsilon = 0.0f;
			if ( correspondences.size() > model.MIN_SET_SIZE )
			{
				ev1[ 0 ] = Math.sqrt( ev1[ 0 ] );
				ev1[ 1 ] = Math.sqrt( ev1[ 1 ] );
				ev2[ 0 ] = Math.sqrt( ev2[ 0 ] );
				ev2[ 1 ] = Math.sqrt( ev2[ 1 ] );

				double r1 = Double.MAX_VALUE;
				double r2 = Double.MAX_VALUE;
				
				/*
				PrintStream f = System.out;
				try
				{
					f = new PrintStream( new FileOutputStream( "inliers.dat", false ) );
				}
				catch ( FileNotFoundException e )
				{
					System.err.println( "File inliers.dat not found for writing." );
				}
				*/
				
				int highest_num_inliers = 0;
				int convergence_count = 0;
				do
				{
					epsilon += min_epsilon;
					//System.out.println( "Estimating model for epsilon = " + epsilon );
					// 1000 iterations lead to a probability of < 0.01% that only bad data values were found
					model = TRModel.estimateModel(
							correspondences,			//!< point correspondences
							1000,						//!< iterations
							epsilon * ( float )scale,	//!< maximal alignment error for a good point pair when fitting the model
							//0.1f,						//!< minimal partition (of 1.0) of inliers
							0.05f,						//!< minimal partition (of 1.0) of inliers
							tr							//!< model as float array (TrakEM style)
							);
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
						
						double[] evi1 = new double[ 2 ];
						double[] evi2 = new double[ 2 ];
						double[] covi1 = new double[ 3 ];
						double[] covi2 = new double[ 3 ];
						double[] oi1 = new double[ 2 ];
						double[] oi2 = new double[ 2 ];
						double[] eveci1 = new double[ 4 ];
						double[] eveci2 = new double[ 4 ];
						Match.covariance( model.getInliers(), covi1, covi2, oi1, oi2, evi1, evi2, eveci1, eveci2 );

						evi1[ 0 ] = Math.sqrt( evi1[ 0 ] );
						evi1[ 1 ] = Math.sqrt( evi1[ 1 ] );
						evi2[ 0 ] = Math.sqrt( evi2[ 0 ] );
						evi2[ 1 ] = Math.sqrt( evi2[ 1 ] );

						double r1x = evi1[ 0 ] / ev1[ 0 ];
						double r1y = evi1[ 1 ] / ev1[ 1 ];
						double r2x = evi2[ 0 ] / ev2[ 0 ];
						double r2y = evi2[ 1 ] / ev2[ 1 ];

						r1x = r1x < 1.0 ? 1.0 / r1x : r1x;
						r1y = r1y < 1.0 ? 1.0 / r1y : r1y;
						r2x = r2x < 1.0 ? 1.0 / r2x : r2x;
						r2y = r2y < 1.0 ? 1.0 / r2y : r2y;

						r1 = ( r1x + r1y ) / 2.0;
						r2 = ( r2x + r2y ) / 2.0;
						r1 = Double.isNaN( r1 ) ? Double.MAX_VALUE : r1;
						r2 = Double.isNaN( r2 ) ? Double.MAX_VALUE : r2;

						//System.out.println( "deviation ratio: " + r1 + ", " + r2 + ", max = " + Math.max( r1, r2 ) );
						//f.println( epsilon + " " + ( float )model.inliers.size() / ( float )correspondences.size() );
					}
				}
				while (
						( model == null || convergence_count < 5 ||	( Math.max( r1, r2 ) > 2.0 ) ) &&
						epsilon < max_epsilon );
				//while ( model == null || epsilon < max_epsilon );
				
				//f.close();
			}

			if ( model != null )
			{
				
				Match.covariance( model.getInliers(), cov1, cov2, o1, o2, ev1, ev2, evec1, evec2 );

				ip1.setLineWidth( 1 );
				ip3.setLineWidth( 1 );
				ip1.setColor( Color.green );
				ip3.setColor( Color.green );
				drawEllipse( ip1, evec1, o1, ev1, vis_scale );
				drawEllipse( ip3, evec2, o2, ev2, vis_scale );
				ip1.setLineWidth( 2 );
				ip3.setLineWidth( 2 );
				for ( Match m : model.getInliers() )
				{
					ip1.drawDot( ( int )Math.round( vis_scale * m.p1[ 0 ] ), ( int )Math.round( vis_scale * m.p1[ 1 ] ) );
					ip3.drawDot( ( int )Math.round( vis_scale * m.p2[ 0 ] ), ( int )Math.round( vis_scale * m.p2[ 1 ] ) );
				}

				IJ.log( "Model with epsilon <= " + epsilon + " for " + model.getInliers().size() + " inliers found." );
				IJ.log( "  Translation: ( " + ( tr[ 0 ] / scale ) + ", " + ( tr[ 1 ] / scale ) + " )" );
				IJ.log( "  Rotation:	" + tr[ 2 ] + " = " + ( tr[ 2 ] / Math.PI * 180.0f ) + "°" );
				IJ.log( "  Pivot:	   ( " + ( tr[ 3 ] / scale ) + ", " + ( tr[ 4 ] / scale ) + " )" );

				/**
				 * append the estimated transformation model
				 * @todo the current rotation assumes the origin (0,0) of the
				 * image in the image's center.  This is because we use
				 * imagescience.jar for transformation and they do so...
				 * Think about using an other transformation class, focusing on
				 * better interpolation schemes ( Lanczos would be great ).
				 */
				at.rotate(
						tr[ 2 ],
						( tr[ 3 ] - imp.getWidth() / 2.0f ) / scale + 0.5f,
						( tr[ 4 ] - imp.getHeight() / 2.0f ) / scale + 0.5f );
				at.translate( tr[ 0 ] / scale, tr[ 1 ] / scale  );
			}
			else
			{
				IJ.log( "No sufficient model found, keeping original transformation." );
			}
			double[] m = new double[ 6 ];
			at.getMatrix( m );

			Image img = Image.wrap( new ImagePlus( "new_layer", stack.getProcessor( i + 1 ) ) );

			Image imgAligned = a.run(
					img,
					new double[][]
					{ { m[ 0 ], m[ 2 ], 0, m[ 4 ] },
					  { m[ 1 ], m[ 3 ], 0, m[ 5 ] },
					  { 0,	  0,	  1, 0 },
					  { 0, 0, 0, 1 } },
					  ischeme,
					  adjust,
					  antialias );
			ImagePlus impAlignedSlice = imgAligned.imageplus();
			stackAligned.addSlice( null, impAlignedSlice.getProcessor() );
			IJ.log("stackInfo.dimensions: " + stackInfo.getWidth() + ", " + stackInfo.getHeight());
			IJ.log("ip dimensions: " + ip1.getWidth() + ", " + ip1.getHeight());
			ImageProcessor tmp;
			tmp = ip1.createProcessor(stackInfo.getWidth(), stackInfo.getHeight());
			tmp.insert(ip1, 0, 0);
			stackInfo.addSlice( null, tmp); // fixing silly 1 pixel size missmatches
			tmp = ip3.createProcessor(stackInfo.getWidth(), stackInfo.getHeight());
			tmp.insert(ip3, 0, 0);
			stackInfo.addSlice( null, tmp);
			impAligned.setStack( "Aligned " + stackAligned.getSize() + " of " + stack.getSize(), stackAligned );
			impAligned.updateAndDraw();
			if ( i == 1 )
			{
				impInfo = new ImagePlus( "Alignment info", stackInfo );
				impInfo.show();
			}
			impInfo.setStack( "Alignment info", stackInfo );
			impInfo.updateAndDraw();
			
//			displayMatches( matches, imp1, imp2 );
//			displayFeatures( fp1.getFeatures(), imp1 );
//			displayFeatures( fp2.getFeatures(), imp2 );
		}
	}

	public void keyPressed(KeyEvent e)
	{
		if (
				( e.getKeyCode() == KeyEvent.VK_F1 ) &&
				( e.getSource() instanceof TextField) )
		{
		}
	}

	public void keyReleased(KeyEvent e) { }

	public void keyTyped(KeyEvent e) { }
}
