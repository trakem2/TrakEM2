//package mpi.fruitfly.registration;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.math.*;
import mpi.fruitfly.registration.FloatArray2DSIFT;
import mpi.fruitfly.registration.FloatArray2DScaleOctave;
import mpi.fruitfly.registration.FloatArray2DScaleOctaveDoGDetector;
import mpi.fruitfly.registration.ImageFilter;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.util.Collections;
import java.util.Vector;
import java.awt.Color;
import java.awt.Polygon;
import java.awt.TextField;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;


public class SIFT_Test implements PlugIn, KeyListener
{
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
	private static int max_size = 1024;

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

	
	/**
	 * draws a rotated square with center point  center, having size and orientation
	 */
	static void drawSquare( ImageProcessor ip, double[] o, double scale, double orient )
	{
		scale /= 2;
		
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
		
		gd.addNumericField( "steps_per_scale_octave :", steps, 0 );
		gd.addNumericField( "initial_gaussian_blur :", initial_sigma, 2 );
		gd.addNumericField( "feature_descriptor_size :", fdsize, 0 );
		gd.addNumericField( "feature_descriptor_orientation_bins :", fdbins, 0 );
		gd.addNumericField( "minimum_image_size :", min_size, 0 );
		gd.addNumericField( "maximum_image_size :", max_size, 0 );
		gd.addCheckbox( "upscale_image_first", upscale );
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		steps = ( int )gd.getNextNumber();
		initial_sigma = ( float )gd.getNextNumber();
		fdsize = ( int )gd.getNextNumber();
		fdbins = ( int )gd.getNextNumber();
		min_size = ( int )gd.getNextNumber();
		max_size = ( int )gd.getNextNumber();
		upscale = gd.getNextBoolean();
		if ( upscale ) scale = 2.0f;
		
		
		ImageProcessor ip1 = imp.getProcessor().convertToFloat();
		ImageProcessor ip2 = imp.getProcessor().duplicate().convertToRGB();
		
		Vector< FloatArray2DSIFT.Feature > fs1;
		
		FloatArray2DSIFT sift = new FloatArray2DSIFT( fdsize, fdbins );
		
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( ip1 );
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
		fs1 = sift.run( max_size );
		Collections.sort( fs1 );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
		System.out.println( fs1.size() + " features identified and processed" );
		
//#############################################################################
		
		FloatArray2DScaleOctave[] sos = sift.getOctaves();
		for ( int o = 0; o < sos.length; ++o )
		{
			FloatArray2DScaleOctave so = sos[ o ];
			
			FloatArray2D[] l = so.getL();
			FloatArray2D[] d = so.getD();

			for ( int i = 0; i < steps; ++i )
			{
				FloatArray2D ls = l[ i ];
				FloatArray2D ds = d[ i ];
				int os;
				for ( int oi = o; oi > 0; --oi )
				{
					os = ( int )Math.pow( 2, oi - 1 );
					int w = imp.getWidth();
					int h = imp.getHeight();
					for ( os = oi; os > 1; --os )
					{
						w = w / 2 + w % 2;
						h = h / 2 + h % 2;
					}
					//System.out.println( "o: " + o + ", w: " + w + ", h: " + h );
					FloatArray2D ld = new FloatArray2D( w, h );
					FloatArray2D dd = new FloatArray2D( w, h );
					FloatArray2DScaleOctave.upsample( ls, ld );
					FloatArray2DScaleOctave.upsample( ds, dd );
					ls = ld;
					ds = dd;
				}
				os = ( int )Math.pow( 2, o );
				FloatProcessor fp = new FloatProcessor( ls.width, ls.height );
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, ls );
				fp.setMinAndMax( 0.0, 1.0 );
				//ImageProcessor ipl = fp.convertToRGB();
				ImageProcessor ipl = fp.duplicate();
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, ds );
				fp.setMinAndMax( -1.0, 1.0 );
				ImageProcessor ipd = fp.convertToRGB();
			
				// draw DoG detections
				
				ipl.setLineWidth( 1 );
				ipl.setColor( Color.red );
				for ( FloatArray2DSIFT.Feature f : fs1 )
				{
					int ol = General.ldu( ( int )( f.scale / initial_sigma ) );
				    int sl = ( int )Math.round( steps * ( Math.log( f.scale / Math.pow( 2.0, ol ) / initial_sigma ) ) / Math.log( 2.0 ) );
				    if ( sl >= steps )
				    {
				        ++ol;
				        sl = sl % steps;
				    }

					if ( ol <= o && sl <= i )
						drawSquare( ipl, new double[]{ f.location[ 0 ] / scale, f.location[ 1 ] / scale }, fdsize * ( double )f.scale / scale, ( double )f.orientation );
				}
				
				/*
				FloatArray2D[] gradients = so.getL1( i );
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, gradients[ 0 ] );
				stackGradientAmplitude.addSlice( null, fp );
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, gradients[ 1 ] );
				stackGradientOrientation.addSlice( null, fp );
				*/
			}
			
			/*
			for ( int i = 0; i < d.length; ++i )
			{
				FloatProcessor fp = new FloatProcessor( d[ i ].width, d[ i ].height );
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, d[ i ] );
				fp.setMinAndMax( -255.0, 255.0 );
				ImageProcessor ipl = fp.convertToRGB();
				
				// draw DoG detections
				ipl.setLineWidth( 2 );
				ipl.setColor( Color.green );
				
				Vector< float[] > candidates = dog.getCandidates();
				for ( float[] c : candidates )
				{
					if ( i == ( int )Math.round( c[ 2 ] ) )
						ipl.drawDot( ( int )Math.round( c[ 0 ] ), ( int )Math.round( c[ 1 ] ) );
				}
				
				stackDoG.addSlice( null, ipl );
				
				
				//stackDoG.addSlice( null, fp );			
			}
			*/
		}
		
//#############################################################################
		
		
		ip2.setLineWidth( 1 );
		ip2.setColor( Color.red );
		for ( FloatArray2DSIFT.Feature f : fs1 )
		{
			System.out.println( f.location[ 0 ] + " " + f.location[ 1 ] + " " + f.scale + " " + f.orientation );
			drawSquare( ip2, new double[]{ f.location[ 0 ] / scale, f.location[ 1 ] / scale }, fdsize * ( double )f.scale / scale, ( double )f.orientation );
		}
	
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
