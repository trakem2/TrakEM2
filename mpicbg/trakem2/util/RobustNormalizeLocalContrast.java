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
package mpicbg.trakem2.util;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.ij.integral.BlockStatistics;
import mpicbg.ij.plugin.RemoveOutliers;
import mpicbg.util.Util;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class RobustNormalizeLocalContrast
{
	static protected interface PixelSetter
	{
		public void setf( int i, float value );
	}
	
	final static protected class FloatSetter implements PixelSetter
	{
		final protected ImageProcessor ip;
		
		public FloatSetter( final ImageProcessor ip )
		{
			this.ip = ip;
		}
		
		public void setf( final int i, final float value )
		{
			ip.setf( i, value );
		}
	}
	
	final static protected class ByteSetter implements PixelSetter
	{
		final protected ByteProcessor ip;
		
		public ByteSetter( final ByteProcessor ip )
		{
			this.ip = ip;
		}
		
		public void setf( final int i, final float value )
		{
			ip.set( i, Math.max(  0, Math.min( 255, Util.roundPos( value ) ) ) );
		}
	}
	
	final static protected class ShortSetter implements PixelSetter
	{
		final protected ShortProcessor ip;
		
		public ShortSetter( final ShortProcessor ip )
		{
			this.ip = ip;
		}
		
		public void setf( final int i, final float value )
		{
			ip.set( i, Math.max(  0, Math.min( 65535, Util.roundPos( value ) ) ) );
		}
	}
	
	final public static void run(
			final ImageProcessor ip,
			final int scaleLevel,
			final int brx1,
			final int bry1,
			final float stds1,
			final int brx2,
			final int bry2,
			final float stds2 )
	{
		final PixelSetter setter;
		if ( ByteProcessor.class.isInstance( ip ) )
			setter = new ByteSetter( ( ByteProcessor )ip );
		else if ( ShortProcessor.class.isInstance( ip ) )
			setter = new ShortSetter( ( ShortProcessor )ip );
		else
			setter = new FloatSetter( ip );
		
		final int scale = ( int )Util.pow( 2, scaleLevel );
		final int scale2 = scale / 2;
		final double[] f = new double[ scale ];
		for ( int i = 0; i < scale; ++i )
			f[ i ] = ( i + 0.5 ) / scale;
		
		final int sbrx1 = brx1 / scale;
		final int sbry1 = bry1 / scale;
		final int sbrx2 = brx2 / scale;
		final int sbry2 = bry2 / scale;
		
		final int ipWidth = ip.getWidth();
		final int ipHeight = ip.getHeight();
		
		final double ipMin = ip.getMin();
		final double ipLength = ip.getMax() - ipMin;
		
		FloatProcessor ipScaled = ( FloatProcessor )ip.convertToFloat();
		for ( int i = 0; i < scaleLevel; ++i )
			ipScaled = Downsampler.downsampleFloatProcessor( ipScaled );
		
		final FloatProcessor std = ipScaled;
		
//		new ImagePlus("downsampled", std.duplicate() ).show();
		
		RemoveOutliers.run( std, sbrx1, sbry1, stds1 );
		
//		new ImagePlus("outlier-filtered", std.duplicate() ).show();
		
		final BlockStatistics bs = new BlockStatistics( std );
		bs.mean( sbrx2, sbry2 );
		
//		new ImagePlus("mean", std.duplicate() ).show();
		
		final FloatProcessor mean = ( FloatProcessor )std.duplicate();
		bs.std( sbrx2, sbry2 );
		
//		new ImagePlus("std", std.duplicate() ).show();
		
		final int w = mean.getWidth();
		final int h = mean.getHeight();
		
		final ExecutorService exec = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		final ArrayList< Future< ? > > tasks = new ArrayList< Future< ? > >();
		
		/* the big inside */
		for ( int y = 1; y < h; ++y )
		{
			final int ya = y - 1;
			final int yb = y;
			
			tasks.add( exec.submit( new Runnable()
			{
				final public void run()
				{
					for ( int x = 1; x < w; ++x )
					{
						final int xa = x - 1;
						
						final float meanA = mean.getf( xa, ya );
						final float meanB = mean.getf( x, ya );
						final float meanC = mean.getf( xa, yb );
						final float meanD = mean.getf( x, yb );
						
						final float stdA = std.getf( xa, ya );
						final float stdB = std.getf( x, ya );
						final float stdC = std.getf( xa, yb );
						final float stdD = std.getf( x, yb );
						
						int ys = yb * scale - scale2;
						final int xss = x * scale - scale2;
						for ( int yi = 0; yi < scale; ++ys, ++yi )
							for ( int xs = xss, xi = 0; xi < scale; ++xs, ++xi )
							{
								final double meanAB = meanA * f[ xi ] + meanB * ( 1.0 - f[ xi ] );
								final double meanCD = meanC * f[ xi ] + meanD * ( 1.0 - f[ xi ] );
								final double meanABCD = meanAB * f[ yi ] + meanCD * ( 1.0 - f[ yi ] );
								
								final double stdAB = stdA * f[ xi ] + stdB * ( 1.0 - f[ xi ] );
								final double stdCD = stdC * f[ xi ] + stdD * ( 1.0 - f[ xi ] );
								final double stdABCD = stdAB * f[ yi ] + stdCD * ( 1.0 - f[ yi ] );
								
								final double d = stds2 * stdABCD;
								final double min = meanABCD - d;
								
								final int i = ys * ipWidth + xs;
								setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
							}
					}
				}
			} ) );
		}
		
		for ( Future< ? > task : tasks )
		{
			try
			{
				task.get();
			}
			catch ( InterruptedException e )
			{
				exec.shutdownNow();
				return;
			}
			catch ( ExecutionException e )
			{
				exec.shutdownNow();
				return;
			}
		}
		
		tasks.clear();
		exec.shutdown();
		
		/* top and bottom */
		for ( int x = 1; x < w; ++x )
		{
			final int xa = x - 1;
			
			final float meanA = mean.getf( xa, 0 );
			final float meanB = mean.getf( x, 0 );
			
			final float meanC = mean.getf( xa, h - 1 );
			final float meanD = mean.getf( x, h - 1 );
			
			final float stdA = std.getf( xa, 0 );
			final float stdB = std.getf( x, 0 );
			
			final float stdC = std.getf( xa, h - 1 );
			final float stdD = std.getf( x, h - 1 );
			
			/* top */
			final int xss = x * scale - scale2;
			for ( int ys = 0; ys < scale2; ++ys )
				for ( int xs = xss, xi = 0; xi < scale; ++xs, ++xi )
				{
					final double meanAB = meanA * f[ xi ] + meanB * ( 1.0 - f[ xi ] );
					
					final double stdAB = stdA * f[ xi ] + stdB * ( 1.0 - f[ xi ] );
					
					final double d = stds2 * stdAB;
					final double min = meanAB - d;
					
					final int i = ys * ipWidth + xs;
					setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
				}
			
			/* bottom */
			for ( int ys = h * scale - scale2; ys < ipHeight; ++ys )
				for ( int xs = xss, xi = 0; xi < scale; ++xs, ++xi )
				{
					final double meanCD = meanC * f[ xi ] + meanD * ( 1.0 - f[ xi ] );
					
					final double stdCD = stdC * f[ xi ] + stdD * ( 1.0 - f[ xi ] );
					
					final double d = stds2 * stdCD;
					final double min = meanCD - d;
					
					final int i = ys * ipWidth + xs;
					setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
				}
		}
		
		final int xss = w * scale - scale2;
		
		/* left and right */
		for ( int y = 1; y < h; ++y )
		{
			final int ya = y - 1;
			
			final float meanA = mean.getf( 0, ya );
			final float meanB = mean.getf( 0, y );
			final float meanC = mean.getf( w - 1, ya );
			final float meanD = mean.getf( w - 1, y );
			
			final float stdA = std.getf( 0, ya );
			final float stdB = std.getf( 0, y );
			final float stdC = std.getf( w - 1, ya );
			final float stdD = std.getf( w - 1, y );
			
			int ys = y * scale - scale2;
			for ( int yi = 0; yi < scale; ++ys, ++yi )
			{
				/* left */
				for ( int xs = 0; xs < scale2; ++xs )
				{
					final double meanAB = meanA * f[ yi ] + meanB * ( 1.0 - f[ yi ] );
					
					final double stdAB = stdA * f[ yi ] + stdB * ( 1.0 - f[ yi ] );
					
					final double d = stds2 * stdAB;
					final double min = meanAB - d;
					
					final int i = ys * ipWidth + xs;
					setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
				}
				
				/* right */
				for ( int xs = xss; xs < ipWidth; ++xs )
				{
					final double meanCD = meanC * f[ yi ] + meanD * ( 1.0 - f[ yi ] );
					
					final double stdCD = stdC * f[ yi ] + stdD * ( 1.0 - f[ yi ] );
					
					final double d = stds2 * stdCD;
					final double min = meanCD - d;
					
					final int i = ys * ipWidth + xs;
					setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
				}
			}
		}
		
		/* corners */
		
		final float meanA = mean.getf( 0, 0 );
		final float meanB = mean.getf( w - 1, 0 );
		final float meanC = mean.getf( 0, h - 1 );
		final float meanD = mean.getf( w - 1, h - 1 );
		
		final float stdA = std.getf( 0, 0 );
		final float stdB = std.getf( w - 1, 0 );
		final float stdC = std.getf( 0, h - 1 );
		final float stdD = std.getf( w - 1, h - 1 );
		
		for ( int ys = 0; ys < scale2; ++ys )
		{
			for ( int xs = 0; xs < scale2; ++xs )
			{
				final double d = stds2 * stdA;
				final double min = meanA - d;
				
				final int i = ys * ipWidth + xs;
				setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
			}
		
			for ( int xs = xss; xs < ipWidth; ++xs )
			{
				final double d = stds2 * stdB;
				final double min = meanB - d;
				
				final int i = ys * ipWidth + xs;
				setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
			}
		}
		
		for ( int ys = h * scale - scale2; ys < ipHeight; ++ys )
		{
			for ( int xs = 0; xs < scale2; ++xs )
			{
				final double d = stds2 * stdC;
				final double min = meanC - d;
				
				final int i = ys * ipWidth + xs;
				setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
			}
		
			for ( int xs = xss; xs < ipWidth; ++xs )
			{
				final double d = stds2 * stdD;
				final double min = meanD - d;
				
				final int i = ys * ipWidth + xs;
				setter.setf(  i, ( float )( ( ip.getf( i ) - min ) / 2 / d * ipLength + ipMin ) );
			}
		}
		
//		new ImagePlus( "processed", ip.duplicate() ).show();
	}
}
