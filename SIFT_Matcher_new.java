//package mpi.fruitfly.registration;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.FloatArray2DScaleOctave;
import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.TRModel2D;
import mpi.fruitfly.registration.Match;
import mpi.fruitfly.registration.ImageFilter;

import imagescience.transforms.*;
import imagescience.images.Image;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.Vector;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import java.io.*;


public class SIFT_Matcher_new implements PlugIn, KeyListener
{
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
	private static int steps = 3;
	// initial sigma
	private static float initial_sigma = 1.6f;
	// background colour
	private static double bg = 0.0;
	// feature descriptor size
	private static int fdsize = 8;
	// feature descriptor orientation bins
	private static int fdbins = 8;
	// size restrictions for scale octaves, use octaves < max_size and > min_size only
	private static int min_size = 64;
	private static int max_size = 1024;
	// minimal allowed alignment error in px
	private static float min_epsilon = 2.0f;
	// maximal allowed alignment error in px
	private static float max_epsilon = 100.0f;
	private static float inlier_ratio = 0.05f;
	
	/**
	 * Set true to double the size of the image by linear interpolation to
	 * ( with * 2 + 1 ) * ( height * 2 + 1 ).  Thus we can start identifying
	 * DoG extrema with $\sigma = INITIAL_SIGMA / 2$ like proposed by
	 * \citet{Lowe04}.
	 * 
	 * This is useful for images scmaller than 1000px per side only. 
	 */ 
	private static boolean upscale = false;
	private static float scale = 1.0f;
	
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
		gd.addNumericField( "steps_per_scale_octave :", steps, 0 );
		gd.addNumericField( "initial_gaussian_blur :", initial_sigma, 2 );
		gd.addNumericField( "feature_descriptor_size :", fdsize, 0 );
		gd.addNumericField( "feature_descriptor_orientation_bins :", fdbins, 0 );
		gd.addNumericField( "minimum_image_size :", min_size, 0 );
		gd.addNumericField( "maximum_image_size :", max_size, 0 );
		gd.addNumericField( "minimal_alignment_error :", min_epsilon, 2 );
		gd.addNumericField( "maximal_alignment_error :", max_epsilon, 2 );
		gd.addNumericField( "inlier_ratio :", inlier_ratio, 2 );
		gd.addNumericField( "background_color :", bg, 2 );
		gd.addChoice( "interpolation_scheme :", schemes, schemes[ scheme ] );
		gd.addCheckbox( "upscale_image_first", upscale );
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
		bg = ( double )gd.getNextNumber();
		scheme = gd.getNextChoiceIndex();
		upscale = gd.getNextBoolean();
		if ( upscale ) scale = 2.0f;
		else scale = 1.0f;
		
		//adjust = gd.getNextBoolean();
		//antialias = gd.getNextBoolean();

		Affine a = new Affine();
		
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
		
		ImageProcessor ip1;
		ImageProcessor ip2;
		ImageProcessor ip3;
		
		Vector< FloatArray2DSIFT.Feature > fs1;
		Vector< FloatArray2DSIFT.Feature > fs2;

		ip2 = stack.getProcessor( 1 ).convertToFloat();
		
		AffineTransform at = new AffineTransform();
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( ip2 );
		ImageFilter.enhance( fa, 1.0f );
		
		if ( upscale )
		{
			FloatArray2D fat = new FloatArray2D( fa.width * 2 - 1, fa.height * 2 - 1 ); 
			FloatArray2DScaleOctave.upsample( fa, fat );
			fa = fat;
			fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 1.0 ) );
		}
		else
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
			ImageFilter.enhance( fa, 1.0f );
			
			if ( upscale )
			{
				FloatArray2D fat = new FloatArray2D( fa.width * 2 - 1, fa.height * 2 - 1 ); 
				FloatArray2DScaleOctave.upsample( fa, fat );
				fa = fat;
				fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 1.0 ) );
			}
			else
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
			drawEllipse( ip1, evec1, o1, ev1, vis_scale / scale );
			drawEllipse( ip3, evec2, o2, ev2, vis_scale / scale );

			ip1.setLineWidth( 2 );
			ip3.setLineWidth( 2 );
			for ( Match m : correspondences )
			{
				ip1.drawDot( ( int )Math.round( vis_scale / scale * m.p1[ 0 ] ), ( int )Math.round( vis_scale / scale * m.p1[ 1 ] ) );
				ip3.drawDot( ( int )Math.round( vis_scale / scale * m.p2[ 0 ] ), ( int )Math.round( vis_scale / scale * m.p2[ 1 ] ) );
			}

			TRModel2D model = null;
			float epsilon = 0.0f;
			if ( correspondences.size() > TRModel2D.MIN_SET_SIZE )
			{
				ev1[ 0 ] = Math.sqrt( ev1[ 0 ] );
				ev1[ 1 ] = Math.sqrt( ev1[ 1 ] );
				ev2[ 0 ] = Math.sqrt( ev2[ 0 ] );
				ev2[ 1 ] = Math.sqrt( ev2[ 1 ] );

				double r1 = Double.MAX_VALUE;
				double r2 = Double.MAX_VALUE;
				
				// TODO empirical evaluation of the convergance of inliers and epsilon
//				PrintStream f = System.out;
//				try
//				{
//					f = new PrintStream( new FileOutputStream( "inliers.dat", false ) );
//				}
//				catch ( FileNotFoundException e )
//				{
//					System.err.println( "File inliers.dat not found for writing." );
//				}
				
				int highest_num_inliers = 0;
				int convergence_count = 0;
				do
				{
					epsilon += min_epsilon;
					//System.out.println( "Estimating model for epsilon = " + epsilon );
					// 1000 iterations lead to a probability of < 0.01% that only bad data values were found
					model = TRModel2D.estimateModel(
							correspondences,			//!< point correspondences
							1000,						//!< iterations
							epsilon * scale,			//!< maximal alignment error for a good point pair when fitting the model
							inlier_ratio );				//!< minimal partition (of 1.0) of inliers
							//0 );
							
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
						//f.println( epsilon + " " + ( float )model.getInliers().size() / ( float )correspondences.size() );
					}
				}
				while (
						( model == null || convergence_count < 4 ||	( Math.max( r1, r2 ) > 2.0 ) ) &&
						epsilon < max_epsilon );
//				while ( model == null || epsilon < max_epsilon );				
//				f.close();
			}

			if ( model != null )
			{
				
				Match.covariance( model.getInliers(), cov1, cov2, o1, o2, ev1, ev2, evec1, evec2 );

				ip1.setLineWidth( 1 );
				ip3.setLineWidth( 1 );
				ip1.setColor( Color.green );
				ip3.setColor( Color.green );
				drawEllipse( ip1, evec1, o1, ev1, vis_scale / scale );
				drawEllipse( ip3, evec2, o2, ev2, vis_scale / scale );
				ip1.setLineWidth( 2 );
				ip3.setLineWidth( 2 );
				for ( Match m : model.getInliers() )
				{
					ip1.drawDot( ( int )Math.round( vis_scale / scale * m.p1[ 0 ] ), ( int )Math.round( vis_scale / scale * m.p1[ 1 ] ) );
					ip3.drawDot( ( int )Math.round( vis_scale / scale * m.p2[ 0 ] ), ( int )Math.round( vis_scale / scale * m.p2[ 1 ] ) );
				}

				IJ.log( "Model with epsilon <= " + epsilon + " for " + model.getInliers().size() + " inliers found." );
				IJ.log( "  Affine transform: " + model.affine.toString() );
				//IJ.log( "  Translation: ( " + ( tr[ 0 ] / scale ) + ", " + ( tr[ 1 ] / scale ) + " )" );
				//IJ.log( "  Rotation:	" + tr[ 2 ] + " = " + ( tr[ 2 ] / Math.PI * 180.0f ) + "deg" );
				//IJ.log( "  Pivot:	   ( " + ( tr[ 3 ] / scale ) + ", " + ( tr[ 4 ] / scale ) + " )" );

				/**
				 * append the estimated transformation model
				 * 
				 * TODO the current rotation assumes the origin (0,0) of the
				 * image in the image's "center"
				 * ( width / 2 - 1.0, height / 2 - 1.0 ).  This is, because we
				 * use imagescience.jar for transformation and they do so...
				 * Think about using an other transformation class, focusing on
				 * better interpolation schemes ( Lanczos would be great ).
				 */
				AffineTransform at_current = new AffineTransform( model.affine );
				double[] m = new double[ 6 ];
				at_current.getMatrix( m );
				m[ 4 ] /= scale;
				m[ 5 ] /= scale;
				at_current.setTransform( m[ 0 ], m[ 1 ], m[ 2 ], m[ 3 ], m[ 4 ], m[ 5 ] );
				
				double hw = ( double )imp.getWidth() / 2.0 - 1.0;
				double hh = ( double )imp.getHeight() / 2.0 - 1.0;
				
				at.translate(
						-hw,
						-hh );
				at.concatenate( at_current );
				at.translate(
						hw,
						hh );
				
				
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
