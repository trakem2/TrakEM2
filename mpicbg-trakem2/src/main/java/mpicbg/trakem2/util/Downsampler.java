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
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
final public class Downsampler
{
	final static public class Entry
	{
		final public int width;
		final public int height;
		final public byte[][] data;
		
		public Entry( final int width, final int height, final byte[][] data )
		{
			this.width = width;
			this.height = height;
			this.data = data;
		}
		
		public Entry( final int width, final int height, final int channels )
		{
			this( width, height, new byte[ channels ][ width * height ] );
		}
	}
	
	final static public class Pair< A, B >
	{
		final public A a;
		final public B b;
		
		public Pair( final A a, final B b )
		{
			this.a = a;
			this.b = b;
		}
	}
	
	final static private int averageByte( final int i1, final int i2, final int i3, final int i4, final byte[] data )
	{
		return (
				( data[ i1 ] & 0xff ) +
				( data[ i2 ] & 0xff ) +
				( data[ i3 ] & 0xff ) +
				( data[ i4 ] & 0xff ) ) / 4;
	}
	
	final static private int averageShort( final int i1, final int i2, final int i3, final int i4, final short[] data )
	{
		return (
				( data[ i1 ] & 0xffff ) +
				( data[ i2 ] & 0xffff ) +
				( data[ i3 ] & 0xffff ) +
				( data[ i4 ] & 0xffff ) ) / 4;
	}
	
	final static private float averageFloat( final int i1, final int i2, final int i3, final int i4, final float[] data )
	{
		return (
				data[ i1 ] +
				data[ i2 ] +
				data[ i3 ] +
				data[ i4 ] ) / 4;
	}
	
	final static private int averageColorRed( final int rgb1, final int rgb2, final int rgb3, final int rgb4 )
	{
		return (
			( ( rgb1 >> 16 ) & 0xff ) +
			( ( rgb2 >> 16 ) & 0xff ) +
			( ( rgb3 >> 16 ) & 0xff ) +
			( ( rgb4 >> 16 ) & 0xff ) ) / 4;
	}
	
	final static private int averageColorGreen( final int rgb1, final int rgb2, final int rgb3, final int rgb4 )
	{
		return (
			( ( rgb1 >> 8 ) & 0xff ) +
			( ( rgb2 >> 8 ) & 0xff ) +
			( ( rgb3 >> 8 ) & 0xff ) +
			( ( rgb4 >> 8 ) & 0xff ) ) / 4;
	}
	
	final static private int averageColorBlue( final int rgb1, final int rgb2, final int rgb3, final int rgb4 )
	{
		return (
			( rgb1 & 0xff ) +
			( rgb2 & 0xff ) +
			( rgb3 & 0xff ) +
			( rgb4 & 0xff ) ) / 4;
	}
	
	final static private int averageColor( final int i1, final int i2, final int i3, final int i4, final int[] data )
	{
		final int rgb1 = data[ i1 ];
		final int rgb2 = data[ i2 ];
		final int rgb3 = data[ i3 ];
		final int rgb4 = data[ i4 ];
		
		final int red = averageColorRed( rgb1, rgb2, rgb3, rgb4 );
		final int green = averageColorGreen( rgb1, rgb2, rgb3, rgb4 );
		final int blue = averageColorBlue( rgb1, rgb2, rgb3, rgb4 );
		return ( ( ( ( 0xff000000 | red ) << 8 ) | green ) << 8 ) | blue;
	}
	
	final static private int andByte( final int i1, final int i2, final int i3, final int i4, final byte[] data )
	{
		return (
				data[ i1 ] &
				data[ i2 ] &
				data[ i3 ] &
				data[ i4 ] & 0xff );
	}
	
	final static public ByteProcessor downsampleByteProcessor( final ByteProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ByteProcessor b = new ByteProcessor( wb, hb );
		
		final byte[] aPixels = ( byte[] )a.getPixels();
		final byte[] bPixels = ( byte[] )b.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int s = averageByte(
						ya + xa,
						ya + xa1,
						ya1 + xa,
						ya1 + xa1,
						aPixels );
				bPixels[ yb + xb ] = ( byte )s;
			}
		}
		
		return b;
	}
	
	
	final static public ByteProcessor downsampleByteProcessor( ByteProcessor a, final int level )
	{
		for ( int i = 0; i < level; ++i )
			a = downsampleByteProcessor( a );
		
		return a;
	}
	
	
	final static public ShortProcessor downsampleShortProcessor( final ShortProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ShortProcessor b = new ShortProcessor( wb, hb );
		
		final short[] aPixels = ( short[] )a.getPixels();
		final short[] bPixels = ( short[] )b.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int s = averageShort(
						ya + xa,
						ya + xa1,
						ya1 + xa,
						ya1 + xa1,
						aPixels );
				bPixels[ yb + xb ] = ( short )s;
			}
		}
		
		return b;
	}
	
	
	final static public FloatProcessor downsampleFloatProcessor( FloatProcessor a, final int level )
	{
		for ( int i = 0; i < level; ++i )
			a = downsampleFloatProcessor( a );
		
		return a;
	}
	
	
	final static public FloatProcessor downsampleFloatProcessor( final FloatProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final FloatProcessor b = new FloatProcessor( wb, hb );
		
		final float[] aPixels = ( float[] )a.getPixels();
		final float[] bPixels = ( float[] )b.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final float s = averageFloat(
						ya + xa,
						ya + xa1,
						ya1 + xa,
						ya1 + xa1,
						aPixels );
				bPixels[ yb + xb ] = s;
			}
		}
		
		return b;
	}
	
	final static public ColorProcessor downsampleColorProcessor( final ColorProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ColorProcessor b = new ColorProcessor( wb, hb );
		
		final int[] aPixels = ( int[] )a.getPixels();
		final int[] bPixels = ( int[] )b.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				bPixels[ yb + xb ] = averageColor( ya + xa, ya + xa1, ya1 + xa, ya1 + xa1, aPixels );
			}
		}
		
		return b;
	}
	
	final static public ColorProcessor downsampleColorProcessor( ColorProcessor a, final int level )
	{
		for ( int i = 0; i < level; ++i )
			a = downsampleColorProcessor( a );
		
		return a;
	}
	
	
	/**
	 * Convenience call for abstract {@link ImageProcessor}.  Do not use if you
	 * know the type of the processor to save the time for type checking.
	 * 
	 * @param a
	 * @return
	 */
	final static public ImageProcessor downsampleImageProcessor( final ImageProcessor a )
	{
		if ( ByteProcessor.class.isInstance( a ) )
			return downsampleByteProcessor( ( ByteProcessor )a );
		else if ( ShortProcessor.class.isInstance( a ) )
			return downsampleShortProcessor( ( ShortProcessor )a );
		if ( FloatProcessor.class.isInstance( a ) )
			return downsampleFloatProcessor( ( FloatProcessor )a );
		if ( ColorProcessor.class.isInstance( a ) )
			return downsampleColorProcessor( ( ColorProcessor )a );
		else
			return null;
	}
	
	
	/**
	 * Convenience call for abstract {@link ImageProcessor}.  Do not use if you
	 * know the type of the processor to save the time for type checking.
	 * 
	 * @param a
	 * @param level pyramid level in a power of 2 scale pyramid
	 * @return
	 */
	final static public ImageProcessor downsampleImageProcessor(ImageProcessor a, final int level )
	{
		for ( int i = 0; i < level; ++i )
			a = downsampleImageProcessor( a );
		
		return a;
	}
	
	
	/**
	 * Create a downsampled version of a {@link ShortProcessor} and the
	 * mapping of its [min,max] range into an unsigned byte array.
	 * 
	 * @param a
	 * @return
	 * 	Pair.a downsampled {@link ShortProcessor}
	 *  Pair.b mapped into unsigned byte
	 */
	final static public Pair< ShortProcessor, byte[] > downsampleShort( final ShortProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final double min = a.getMin();
		final double max = a.getMax();
		final double scale = 255.0 / ( max - min );
		
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ShortProcessor b = new ShortProcessor( wb, hb );
		b.setMinAndMax( min, max );
		
		final short[] aPixels = ( short[] )a.getPixels();
		final short[] bPixels = ( short[] )b.getPixels();
		final byte[] bBytes = new byte[ bPixels.length ];
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int yaxa = ya + xa;
				final int yaxa1 = ya + xa1;
				final int ya1xa = ya1 + xa;
				final int ya1xa1 = ya1 + xa1;
				final int ybxb = yb + xb;
				
				final int s = averageShort( yaxa, yaxa1, ya1xa, ya1xa1, aPixels );
				bPixels[ ybxb ] = ( short )s;
				final int sb = ( int )( ( s - min ) * scale + 0.5 );
				bBytes[ ybxb ] = ( byte )( sb < 0 ? 0 : sb > 255 ? 255 : sb );
			}
		}
		return new Pair< ShortProcessor, byte[] >( b, bBytes );
	}
	
	
	/**
	 * Create a downsampled version of a {@link FloatProcessor} and the
	 * mapping of its [min,max] range into an unsigned byte array.
	 * 
	 * @param a
	 * @return
	 * 	Pair.a downsampled {@link FloatProcessor}
	 *  Pair.b mapped into unsigned byte
	 */
	final static public Pair< FloatProcessor, byte[] > downsampleFloat( final FloatProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final double min = a.getMin();
		final double max = a.getMax();
		final double scale = 255.0 / ( max - min );
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final FloatProcessor b = new FloatProcessor( wb, hb );
		b.setMinAndMax( min, max );
		
		final float[] aPixels = ( float[] )a.getPixels();
		final float[] bPixels = ( float[] )b.getPixels();
		final byte[] bBytes = new byte[ bPixels.length ];
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int yaxa = ya + xa;
				final int yaxa1 = ya + xa1;
				final int ya1xa = ya1 + xa;
				final int ya1xa1 = ya1 + xa1;
				final int ybxb = yb + xb;
				
				final float s = averageFloat( yaxa, yaxa1, ya1xa, ya1xa1, aPixels );
				bPixels[ ybxb ] = s;
				final int sb = ( int )( ( s - min ) * scale + 0.5 );
				bBytes[ ybxb ] = ( byte )( sb < 0 ? 0 : sb > 255 ? 255 : sb );
			}
		}
		return new Pair< FloatProcessor, byte[] >( b, bBytes );
	}
	
	
	/**
	 * Create a downsampled version of a {@link ColorProcessor} and the
	 * mapping of its red, green and blue channels into three unsigned byte
	 * arrays.
	 * 
	 * @param a
	 * @return
	 * 	Pair.a downsampled {@link ColorProcessor}
	 *  Pair.b red, green, blue channels as byte[] each 
	 */
	final static public Pair< ColorProcessor, byte[][] > downsampleColor( final ColorProcessor a )
	{
		final int wa = a.getWidth();
		final int ha = a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ColorProcessor b = new ColorProcessor( wb, hb );
		
		final int[] aPixels = ( int[] )a.getPixels();
		final int[] bPixels = ( int[] )b.getPixels();
		final byte[] rBytes = new byte[ bPixels.length ];
		final byte[] gBytes = new byte[ bPixels.length ];
		final byte[] bBytes = new byte[ bPixels.length ];
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int yaxa = ya + xa;
				final int yaxa1 = ya + xa1;
				final int ya1xa = ya1 + xa;
				final int ya1xa1 = ya1 + xa1;
				final int ybxb = yb + xb;
				
				final int rgb1 = aPixels[ yaxa ];
				final int rgb2 = aPixels[ yaxa1 ];
				final int rgb3 = aPixels[ ya1xa ];
				final int rgb4 = aPixels[ ya1xa1 ];
				
				final int red = averageColorRed( rgb1, rgb2, rgb3, rgb4 );
				final int green = averageColorGreen( rgb1, rgb2, rgb3, rgb4 );
				final int blue = averageColorBlue( rgb1, rgb2, rgb3, rgb4 );
				
				bPixels[ ybxb ] = ( ( ( ( 0xff000000 | red ) << 8 ) | green ) << 8 ) | blue;
				
				rBytes[ ybxb ] = ( byte )red;
				gBytes[ ybxb ] = ( byte )green;
				bBytes[ ybxb ] = ( byte )blue;
			}
		}
		return new Pair< ColorProcessor, byte[][] >( b, new byte[][]{ rBytes, gBytes, bBytes } );
	}
	
	
	/**
	 * Called from a single method below but when not separated into its own method,
	 * inlining does not happen and execution time is doubled.  I beg for introducing
	 * inline as a keyword to Java.  This magic doesn't make code readable at all.
	 * 
	 * @param sOutside
	 * @param yaxa
	 * @param yaxa1
	 * @param ya1xa
	 * @param ya1xa1
	 * @param ybxb
	 * @param aAlphaPixels
	 * @param bAlphaPixels
	 * @param bOutsidePixels
	 */
	final static private void combineAlphaAndOutside( final int sOutside, final int yaxa, final int yaxa1, final int ya1xa, final int ya1xa1, final int ybxb, final byte[] aAlphaPixels, final byte[] bAlphaPixels, final byte[] bOutsidePixels )
	{
		if ( sOutside == 0xff )
		{
			final int sAlpha = averageByte( yaxa, yaxa1, ya1xa, ya1xa1, aAlphaPixels );
			bAlphaPixels[ ybxb ] = ( byte )sAlpha;
			bOutsidePixels[ ybxb ] = -1;
		}
		else
		{
			bAlphaPixels[ ybxb ] = 0;
			bOutsidePixels[ ybxb ] = 0;
		}
	}
	
	/**
	 * Combine an alpha and outside mask into a downsampled alpha and outside
	 * mask.  Those pixels not fully covered in the outside mask are set to 0,
	 * all others to their interpolated value.
	 * 
	 * @param aAlpha
	 * @param aOutside
	 * @return
	 */
	final static public Pair< ByteProcessor, ByteProcessor > downsampleAlphaAndOutside( final ByteProcessor aAlpha, final ByteProcessor aOutside )
	{
		final int wa = aAlpha.getWidth();
		final int ha = aAlpha.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ByteProcessor bAlpha = new ByteProcessor( wb, hb );
		final ByteProcessor bOutside = new ByteProcessor( wb, hb );
		
		final byte[] aAlphaPixels = ( byte[] )aAlpha.getPixels();
		final byte[] aOutsidePixels = ( byte[] )aOutside.getPixels();
		final byte[] bAlphaPixels = ( byte[] )bAlpha.getPixels();
		final byte[] bOutsidePixels = ( byte[] )bOutside.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int yaxa = ya + xa;
				final int yaxa1 = ya + xa1;
				final int ya1xa = ya1 + xa;
				final int ya1xa1 = ya1 + xa1;
				final int ybxb = yb + xb;
				
				final int sOutside = andByte( yaxa, yaxa1, ya1xa, ya1xa1, aOutsidePixels );
				combineAlphaAndOutside( sOutside, yaxa, yaxa1, ya1xa, ya1xa1, ybxb, aAlphaPixels, bAlphaPixels, bOutsidePixels );
			}
		}
		return new Pair< ByteProcessor, ByteProcessor >( bAlpha, bOutside );
	}
	
	/**
	 * Downsample and outside mask.  Those pixels not fully covered in the
	 * outside mask are set to 0, all others to 255.
	 * 
	 * @param aOutside
	 * @return
	 */
	final static public ByteProcessor downsampleOutside( final ByteProcessor aOutside )
	{
		final int wa = aOutside.getWidth();
		final int ha = aOutside.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ByteProcessor bOutside = new ByteProcessor( wb, hb );
		
		final byte[] aOutsidePixels = ( byte[] )aOutside.getPixels();
		final byte[] bOutsidePixels = ( byte[] )bOutside.getPixels();
		
		for ( int ya = 0, yb = 0; yb < nb; ya += wa2, yb += wb )
		{
			final int ya1 = ya + wa;
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				final int xa1 = xa + 1;
				final int sOutside = andByte( ya + xa, ya + xa1, ya1 + xa, ya1 + xa1, aOutsidePixels );
				
				if ( sOutside == 0xff )
					bOutsidePixels[ yb + xb ] = -1;
				else
					bOutsidePixels[ yb + xb ] = 0;
			}
		}
		return bOutside;
	}
}
