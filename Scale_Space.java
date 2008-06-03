import static mpi.fruitfly.math.General.*;

import mpi.fruitfly.general.*;
import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.*;

import imagescience.transform.*;
import imagescience.image.Image;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

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

import ini.trakem2.imaging.FloatProcessorT2;


public class Scale_Space implements PlugIn, KeyListener
{
	// steps
	private static String steps_str = "3";
	private static int steps = 3;

	// initial sigma
	private static String initial_sigma_str = "1.6";
	private static float initial_sigma = 1.6f;

	public void run( String args )
	{
		if ( IJ.versionLessThan( "1.37i" ) ) return;

		final ImagePlus imp = WindowManager.getCurrentImage();
		if ( imp == null )  { System.out.println( "There are no images open" ); return; }

		GenericDialog gd = new GenericDialog( "Scale space" );
		gd.addStringField( "Steps per gaussian octave:", steps_str, 2 );
		gd.addStringField( "Initial gaussian blur (Sigma):", initial_sigma_str, 2 );
		final Vector< TextField > tfs = gd.getStringFields();
		tfs.get( 0 ).addKeyListener( this );
		gd.showDialog();

		if (gd.wasCanceled()) return;

		steps_str = gd.getNextString();
		initial_sigma_str = gd.getNextString();
		
		Affine a = new Affine();

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

		ImageProcessor ip;
		
		ip = imp.getProcessor().convertToFloat();
		FloatArray2D fa = ImageArrayConverter.ImageToFloatArray2D( ip );
		ImageFilter.enhance( fa, 1.0f );
		
		fa = ImageFilter.computeGaussianFastMirror( fa, ( float )Math.sqrt( initial_sigma * initial_sigma - 0.25 ) );
		
		/*
		FloatArray2DScaleOctave so = new FloatArray2DScaleOctave(
				fa,
				steps,
				initial_sigma );
		*/
		FloatArray2DSIFT sift = new FloatArray2DSIFT(
				4,
				8 );
		
		long start_time = System.currentTimeMillis();
		System.out.print( "initializing scale space ..." );
		sift.init( fa, steps, initial_sigma, 32, Math.max( fa.width, fa.height ) );
		System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
		ImageStack stackScaleSpace = new ImageStack( imp.getWidth(), imp.getHeight() );
		ImageStack stackDoG = new ImageStack( imp.getWidth(), imp.getHeight() );
		//ImageStack stackGradientAmplitude = new ImageStack( imp.getWidth(), imp.getHeight() );
		//ImageStack stackGradientOrientation = new ImageStack( imp.getWidth(), imp.getHeight() );
		
		FloatArray2DScaleOctave[] sos = sift.getOctaves();
		for ( int o = 0; o < sos.length; ++o )
		{
			FloatArray2DScaleOctave so = sos[ o ];
			start_time = System.currentTimeMillis();
			System.out.print( "building scale octave ..." );
			so.build();
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
			
			FloatArray2DScaleOctaveDoGDetector dog = new FloatArray2DScaleOctaveDoGDetector();
		
			start_time = System.currentTimeMillis();
			System.out.print( "identifying difference of gaussian extrema ..." );
			dog.run( so );
			System.out.println( " took " + ( System.currentTimeMillis() - start_time ) + "ms" );
		
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
				FloatProcessor fp = new FloatProcessorT2( ls.width, ls.height );
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, ls );
				fp.setMinAndMax( 0.0, 1.0 );
				//ImageProcessor ipl = fp.convertToRGB();
				ImageProcessor ipl = fp.duplicate();
				ImageArrayConverter.FloatArrayToFloatProcessor( fp, ds );
				fp.setMinAndMax( -1.0, 1.0 );
				ImageProcessor ipd = fp.convertToRGB();
			
				// draw DoG detections
				ipl.setLineWidth( ( int )( initial_sigma * ( 1 + ( float )i / steps ) * os ) );
				ipl.setColor( Color.green );
				ipd.setLineWidth( ( int )( initial_sigma * ( 1 + ( float )i / steps ) * os ) );
				ipd.setColor( Color.green );
				
				Vector< float[] > candidates = dog.getCandidates();
				for ( float[] c : candidates )
				{
					if ( i == ( int )Math.round( c[ 2 ] ) )
					{
						ipl.drawDot(
								( int )Math.round( ( float )os * c[ 0 ] ),
								( int )Math.round( ( float )os * c[ 1 ] ) );
						ipd.drawDot(
								( int )Math.round( ( float )os * c[ 0 ] ),
								( int )Math.round( ( float )os * c[ 1 ] ) );
					}	
				}
				
				stackScaleSpace.addSlice( null, ipl );
				stackDoG.addSlice( null, ipd );
				
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
				FloatProcessor fp = new FloatProcessorT2( d[ i ].width, d[ i ].height , 0, 0);
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
		ImagePlus impScaleSpace = new ImagePlus( "Scales", stackScaleSpace );
		ImagePlus impDoG = new ImagePlus( "Differences of Scales", stackDoG );
		//ImagePlus impGradientAmplitude = new ImagePlus( "Gradient amplitudes of Scales", stackGradientAmplitude );
		//ImagePlus impGradientOrientation = new ImagePlus( "Gradient orientations of Scales", stackGradientOrientation );
		impScaleSpace.show();
		impDoG.show();
		//impGradientAmplitude.show();
		//impGradientOrientation.show();
		
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
