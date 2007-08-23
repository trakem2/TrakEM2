//package mpi.fruitfly.registration;

import static mpi.fruitfly.math.General.*;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.FloatArray2DSIFT;
//import mpi.fruitfly.registration.RoiList;
import mpi.fruitfly.registration.FloatArray2DScaleOctave;
import mpi.fruitfly.registration.FloatArray2DScaleOctaveDoGDetector;
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


public class SIFT_Test implements PlugIn, KeyListener
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
	// maximal image size
	private static int max_size = 1024;
	// minimal image size
	private static int min_size = 64;
	private static double scale = 1.0;
	// minimal allowed alignment error in px
	private static float min_epsilon = 2.0f;
	// maximal allowed alignment error in px
	private static float max_epsilon = 100.0f;
	
	private static float inlier_ratio = 0.05f;

	
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
	 * draws a rotated square with center point  center, having size and orientation
	 */
	static void drawSquare( ImageProcessor ip, double[] o, double scale, double orient )
	{
	    double sin = Math.sin( orient );
	    double cos = Math.cos( orient );
	    
	    int[] x = new int[ 6 ];
	    int[] y = new int[ 6 ];
	    

	    x[ 0 ] = ( int )( o[ 0 ] + ( sin - cos ) * scale );
	    y[ 0 ] = ( int )( o[ 1 ] - ( sin + cos ) * scale );
	    
	    x[ 1 ] = ( int )o[ 0 ];
	    y[ 1 ] = ( int )o[ 1 ];
	    
	    x[ 2 ] = ( int )( o[ 0 ] + ( sin + cos ) * scale );
	    y[ 2 ] = ( int )( o[ 1 ] + ( sin - cos ) * scale );
	    x[ 3 ] = ( int )( o[ 0 ] - ( sin - cos ) * scale );
	    y[ 3 ] = ( int )( o[ 1 ] + ( sin + cos ) * scale );
	    x[ 4 ] = ( int )( o[ 0 ] - ( sin + cos ) * scale );
	    y[ 4 ] = ( int )( o[ 1 ] - ( sin - cos ) * scale );
	    x[ 5 ] = x[ 0 ];
	    y[ 5 ] = y[ 0 ];
	    
	    ip.drawPolygon( new Polygon( x, y, x.length ) );
	}


	public void run( String args )
	{
		if ( IJ.versionLessThan( "1.37i" ) ) return;

		final ImagePlus imp = WindowManager.getCurrentImage();
		if ( imp == null )  { System.err.println( "There are no images open" ); return; }

		final GenericDialog gd = new GenericDialog( "Test SIFT" );
		
		gd.addNumericField("steps_per_scale_octave :", steps, 0);
		gd.addNumericField("initial_gaussian_blur :", initial_sigma, 2);
		gd.addNumericField("feature_descriptor_size :", fdsize, 0);
		gd.addNumericField("feature_descriptor_orientation_bins :", fdbins, 0);
		gd.addNumericField("minimum_image_size :", min_size, 0);
		gd.addNumericField("maximum_image_size :", max_size, 0);
		gd.addNumericField("minimal_alignment_error :", min_epsilon, 2);
		gd.addNumericField("maximal_alignment_error :", max_epsilon, 2);
		gd.addNumericField("inlier_ratio :", inlier_ratio, 2);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		steps = (int)gd.getNextNumber();
		initial_sigma = (float)gd.getNextNumber();
		fdsize = (int)gd.getNextNumber();
		fdbins = (int)gd.getNextNumber();
		min_size = (int)gd.getNextNumber();
		max_size = (int)gd.getNextNumber();
		min_epsilon = (float)gd.getNextNumber();
		max_epsilon = (float)gd.getNextNumber();
		inlier_ratio = (float)gd.getNextNumber();
		
		
		ImageProcessor ip1 = imp.getProcessor().convertToFloat();
		ImageProcessor ip2 = imp.getProcessor().convertToRGB();
		
		Vector< FloatArray2DSIFT.Feature > fs1;
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( ip1 );
		fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
		
		long start_time = System.currentTimeMillis();
		System.out.print( "processing SIFT ..." );
		sift.init( fa, steps, initial_sigma, min_size, max_size );
		
		
		FloatArray2DScaleOctave[] sos = sift.getOctaves();
		for ( int o = 0; o < sos.length; ++o )
		{
			FloatArray2DScaleOctave so = sos[ o ];
			start_time = System.currentTimeMillis();
			System.out.print( "building scale octave ..." );
			so.build();
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
			
			int os = ( int )Math.pow( 2, o );
			
			FloatArray2DScaleOctaveDoGDetector dog = new FloatArray2DScaleOctaveDoGDetector();
		
			start_time = System.currentTimeMillis();
			System.out.print( "identifying difference of gaussian extrema ..." );
			dog.run( so );
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
			// draw DoG detections
			ip2.setLineWidth( 1 );
			ip2.setColor( Color.green );
			
			Vector< float[] > candidates = dog.getCandidates();
			for ( float[] c : candidates )
			{
				ip2.drawDot(
						( int )Math.round( ( float )os * c[ 0 ] ),
						( int )Math.round( ( float )os * c[ 1 ] ) );
			}
		}
		
		
		/*
		fs1 = sift.run( max_size );
		Collections.sort( fs1 );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
		System.out.println( fs1.size() + " features identified and processed" );
		
		ip2.setLineWidth( 1 );
		ip2.setColor( Color.red );
		for ( FloatArray2DSIFT.Feature f : fs1 )
		{
			drawSquare( ip2, new double[]{ f.location[ 0 ], f.location[ 1 ] }, ( double )f.scale / 2, ( double )f.orientation );
		}
		*/
		
		
		
		ImagePlus imp1 = new ImagePlus( imp.getTitle() + " Features ", ip2 );
		imp1.show();
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
