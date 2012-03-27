import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.imaging.FastIntegralImage;
import mpicbg.trakem2.util.Downsampler;
import mpicbg.trakem2.util.Downsampler.Pair;
import mpicbg.util.Timer;

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

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class DownsamplerTest
{
	final static int n = 20;

	final private static void testShort( ShortProcessor ipShort )
	{
		final double min = ipShort.getMin();
		final double max = ipShort.getMax();
		
		while( ipShort.getWidth() > 32 )
		{
			ipShort = Downsampler.downsampleShortProcessor( ipShort );
			ipShort.setMinAndMax( min, max );
		}
	}
	
	final private static void testFloat( FloatProcessor ipFloat )
	{
		final double min = ipFloat.getMin();
		final double max = ipFloat.getMax();
		
		while( ipFloat.getWidth() > 32 )
		{
			ipFloat = Downsampler.downsampleFloatProcessor( ipFloat );
			ipFloat.setMinAndMax( min, max );
		}
	}
	
	final private static void testByte( ByteProcessor ipByte )
	{
		while( ipByte.getWidth() > 32 )
			ipByte = Downsampler.downsampleByteProcessor( ipByte );
	}
	
	final private static void testColor( ColorProcessor ipColor )
	{
		while( ipColor.getWidth() > 32 )
			ipColor = Downsampler.downsampleColorProcessor( ipColor );
	}
	
	/**
	 * Test downsampling the pyramid of a short image + byte alpha channel
	 * including the byte mapping of the short doing the alpha channel in a
	 * separate loop.
	 * 
	 * @param ba
	 */
	final private static void testShortAlphaIndependently( Pair< ShortProcessor, byte[] > ba, ByteProcessor alpha )
	{
		while( ba.a.getWidth() > 32 )
		{
			ba = Downsampler.downsampleShort( ba.a );
			alpha = Downsampler.downsampleByteProcessor( alpha );
//			new ImagePlus( "pixels " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "pixels to byte " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 0 ], null ) ).show();
//			new ImagePlus( "alpha " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 1 ], null ) ).show();
		}
	}
	
	/**
	 * Test downsampling the pyramid of a short image + byte alpha channel
	 * including the byte mapping of the short all in separate loops.
	 * 
	 * @param ba
	 */
	final private static void testShortAlphaByteMappingIndependently( ShortProcessor sp, ByteProcessor alpha )
	{
		ByteProcessor bp;
		while( sp.getWidth() > 32 )
		{
			sp = Downsampler.downsampleShortProcessor( sp );
			bp = ( ByteProcessor )sp.convertToByte( true );
			alpha = Downsampler.downsampleByteProcessor( alpha );
//			new ImagePlus( "pixels " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "pixels to byte " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 0 ], null ) ).show();
//			new ImagePlus( "alpha " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 1 ], null ) ).show();
		}
	}
	
	/**
	 * Test downsampling the pyramid of a short image + byte alpha channel
	 * including the byte mapping of the short doing the alpha channel in a
	 * separate loop.
	 * 
	 * @param ba
	 */
	final private static void testFloatAlphaIndependently( Pair< FloatProcessor, byte[] > ba, ByteProcessor alpha )
	{
		while( ba.a.getWidth() > 32 )
		{
			ba = Downsampler.downsampleFloat( ba.a );
			alpha = Downsampler.downsampleByteProcessor( alpha );
//			new ImagePlus( "pixels " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "pixels to byte " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 0 ], null ) ).show();
//			new ImagePlus( "alpha " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 1 ], null ) ).show();
		}
	}
	
	/**
	 * Test downsampling the pyramid of a short image + byte alpha channel
	 * including the byte mapping of the short doing the alpha channel in a
	 * separate loop.
	 * 
	 * @param ba
	 */
	final private static void testColorAlphaIndependently( Pair< ColorProcessor, byte[][] > ba, ByteProcessor alpha )
	{
		while( ba.a.getWidth() > 32 )
		{
			ba = Downsampler.downsampleColor( ba.a );
			alpha = Downsampler.downsampleByteProcessor( alpha );
//			new ImagePlus( "pixels " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "pixels to byte " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 0 ], null ) ).show();
//			new ImagePlus( "alpha " + ba.a.getWidth(), new ByteProcessor( ba.a.getWidth(), ba.a.getHeight(), ba.b[ 1 ], null ) ).show();
		}
	}
	
	
	final private static void testAlphaOutside( Pair< ByteProcessor, ByteProcessor > ba )
	{
		while( ba.a.getWidth() > 32 )
		{
			ba = Downsampler.downsampleAlphaAndOutside( ba.a, ba.b );
//			new ImagePlus( "alpha " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "outside " + ba.b.getWidth(), ba.b ).show();
		}
	}
	
	final private static void testOutside( ByteProcessor a )
	{
		while( a.getWidth() > 32 )
		{
			a = Downsampler.downsampleOutside( a );
//			new ImagePlus( "alpha " + ba.a.getWidth(), ba.a ).show();
//			new ImagePlus( "outside " + ba.b.getWidth(), ba.b ).show();
		}
	}
	
	
	final private static void testByteIntegral( ByteProcessor ipByte )
	{
		while( ipByte.getWidth() > 32 )
		{
			int w = ipByte.getWidth(),
			    h = ipByte.getHeight();
			long[] l = FastIntegralImage.longIntegralImage((byte[])ipByte.getPixels(), w, h);
			ipByte = new ByteProcessor(
					w/2, h/2,
					FastIntegralImage.scaleAreaAverage(l, w+1, h+1, w/2, h/2),
					null);
		}
	}
	
	
	/**
	 * @param args
	 */
	public static void main( final String[] args )
	{
		new ImageJ();
		final Timer timer = new Timer();
		
		final ImagePlus imp = new ImagePlus( "/home/saalfeld/tmp/fetter-example.tif" );
		//final ImagePlus imp = new ImagePlus( "/home/albert/Desktop/t2/fetter-example.tif" );
		//final ImagePlus imp = new ImagePlus( "/home/saalfeld/Desktop/norway.jpg" );
		imp.show();
		
		final ImageProcessor ip = imp.getProcessor().duplicate();
		
		System.out.println( "short" );
		final ShortProcessor ipShort = ( ShortProcessor )ip.convertToShort( false );
		
		for ( int i = 0; i < n; ++i )
		{
			timer.start();
			testShort( ipShort );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "downsampleShort() + downsampleByteProcessor() (independent short with byte mapping + alpha)" );
		
		for ( int i = 0; i < n; ++i )
		{
			final Pair< ShortProcessor, byte[] > ba = new Pair< ShortProcessor, byte[] >( ipShort, ( byte[] )ipShort.convertToByte( true ).getPixels() );
			final ByteProcessor alpha = new ByteProcessor( ipShort.getWidth(), ipShort.getHeight(), ( byte[] )ipShort.convertToByte( true ).getPixels(), null );
			timer.start();
			testShortAlphaIndependently( ba, alpha );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "downsampleShortProcessor() + convertToByte() + downsampleByteProcessor() (independent short + byte mapping + alpha)" );
		
		for ( int i = 0; i < n; ++i )
		{
			final ByteProcessor alpha = new ByteProcessor( ipShort.getWidth(), ipShort.getHeight(), ( byte[] )ipShort.convertToByte( true ).getPixels(), null );
			timer.start();
			testShortAlphaByteMappingIndependently( ipShort, alpha );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "float" );
		final FloatProcessor ipFloat = ( FloatProcessor )ip.convertToFloat();
		
		for ( int i = 0; i < n; ++i )
		{
			timer.start();
			testFloat( ipFloat );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "independent float with byte mapping + alpha" );
		
		for ( int i = 0; i < n; ++i )
		{
			final Pair< FloatProcessor, byte[] > ba = new Pair< FloatProcessor, byte[] >( ipFloat, ( byte[] )ipShort.convertToByte( true ).getPixels() );
			final ByteProcessor alpha = new ByteProcessor( ipShort.getWidth(), ipShort.getHeight(), ( byte[] )ipShort.convertToByte( true ).getPixels(), null );
			timer.start();
			testFloatAlphaIndependently( ba, alpha );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "byte" );
		final ByteProcessor ipByte = ( ByteProcessor )ip.convertToByte( true );
		
		for ( int i = 0; i < n; ++i )
		{
			timer.start();
			testByte( ipByte );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "2 x byte" );
		final ByteProcessor ipByte2 = ( ByteProcessor )ipByte.duplicate();
		
		for ( int i = 0; i < n; ++i )
		{
			timer.start();
			testByte( ipByte );
			testByte( ipByte2 );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		
		System.out.println( "color" );
		final ColorProcessor ipColor = ( ColorProcessor )ip.convertToRGB();
		
		for ( int i = 0; i < n; ++i )
		{
			timer.start();
			testColor( ipColor );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "independent color with byte mapping + alpha" );
		
		for ( int i = 0; i < n; ++i )
		{
			final byte[][] rgb = new byte[ 4 ][ ipColor.getWidth() * ipColor.getHeight() ];
			ipColor.getRGB( rgb[ 0 ], rgb[ 1 ], rgb[ 2 ] );
			final Pair< ColorProcessor, byte[][] > ba = new Pair< ColorProcessor, byte[][] >( ipColor, rgb );
			final ByteProcessor alpha = new ByteProcessor( ipShort.getWidth(), ipShort.getHeight(), ( byte[] )ipShort.convertToByte( true ).getPixels(), null );
			timer.start();
			testColorAlphaIndependently( ba, alpha );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "alpha + outside" );
		
		for ( int i = 0; i < n; ++i )
		{
			ByteProcessor outside = new ByteProcessor( ipByte.getWidth(), ipByte.getHeight() );
			outside.setRoi( new OvalRoi( 100, 100, ipByte.getWidth() - 200, ipByte.getHeight() - 200 ) );
			outside.setValue( 255 );
			outside.fill(outside.getMask());
			final Pair< ByteProcessor, ByteProcessor > ba = new Pair< ByteProcessor, ByteProcessor >( ipByte, outside );
			timer.start();
			testAlphaOutside( ba );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		System.out.println( "outside" );
		
		for ( int i = 0; i < n; ++i )
		{
			ByteProcessor outside = new ByteProcessor( ipByte.getWidth(), ipByte.getHeight() );
			outside.setRoi( new OvalRoi( 100, 100, ipByte.getWidth() - 200, ipByte.getHeight() - 200 ) );
			outside.setValue( 255 );
			outside.fill(outside.getMask());
			timer.start();
			testOutside( outside );
			final long t = timer.stop();
			System.out.println( i + ": " + t  + "ms" );
		}
		
		
//		System.out.println( "byte integral" );
//		final ByteProcessor ipByteI = ( ByteProcessor )ipShort.convertToByte( true );
//		
//		for ( int i = 0; i < 10; ++i )
//		{
//			timer.start();
//			testByteIntegral( ipByteI );
//			final long t = timer.stop();
//			System.out.println( i + ": " + t  + "ms" );
//		}
	}
}
