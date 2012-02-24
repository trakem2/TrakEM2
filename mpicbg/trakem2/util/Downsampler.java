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
	
	
	/**
	 * Convenience call for abstract {@link ImageProcessor}.  Do not use if you
	 * know the type of the processor to save the time for type checking.
	 * 
	 * @param a
	 * @return
	 */
	final static public ImageProcessor downsample( final ImageProcessor a )
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
	
	
	final static public Pair< ByteProcessor, byte[][] > downsampleByteAlpha( final Pair< ByteProcessor, byte[][] > a )
	{
		return downsampleByteAlpha( a.a, a.b[ 1 ] );
	}
	
	final static private void byteAlphaLoop(
			final byte[] aPixels,
			final byte[] bPixels,
			final byte[] aAlpha,
			final byte[] bAlpha,
			final int nb,
			final int wa2,
			final int wb,
			final int wa )
	{
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
				
				bPixels[ ybxb ] = ( byte )averageByte( yaxa, yaxa1, ya1xa, ya1xa1, aPixels );
				bAlpha[ ybxb ] = ( byte )averageByte( yaxa, yaxa1, ya1xa, ya1xa1, aAlpha );
			}
		}
	}
	
	final static public Pair< ByteProcessor, byte[][] > downsampleByteAlpha( final ByteProcessor a, final byte[] aAlpha )
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
		final byte[] bAlpha = new byte[ bPixels.length ];
		
		byteAlphaLoop( aPixels, bPixels, aAlpha, bAlpha, nb, wa2, wb, wa );
		
		return new Pair< ByteProcessor, byte[][] >( b, new byte[][]{ bPixels, bAlpha } );
	}
	
	
	
	final static public Pair< ShortProcessor, byte[][] > downsampleShortAlpha( final Pair< ShortProcessor, byte[][] > a )
	{
		return downsampleShortAlpha( a.a, a.b[ 1 ] );
	}
	
	final static public Pair< ShortProcessor, byte[][] > downsampleShortAlpha( final ShortProcessor a, byte[] aAlpha )
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
		final byte[] bAlpha = new byte[ bPixels.length ];
		
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
				
				final int s = (
						( aPixels[ yaxa ] & 0xffff ) +
						( aPixels[ yaxa1 ] & 0xffff ) +
						( aPixels[ ya1xa ] & 0xffff ) +
						( aPixels[ ya1xa1 ] & 0xffff ) ) / 4;
				bPixels[ ybxb ] = ( short )s;
				bBytes[ ybxb ] = ( byte )( ( int )( ( s - min ) * scale + 0.5 ) );
				final int sAlpha = (
						( aAlpha[ yaxa ] & 0xff ) +
						( aAlpha[ yaxa1 ] & 0xff ) +
						( aAlpha[ ya1xa ] & 0xff ) +
						( aAlpha[ ya1xa1 ] & 0xff ) ) / 4;
				bAlpha[ ybxb ] = ( byte )sAlpha;
			}
		}
		return new Pair< ShortProcessor, byte[][] >( b, new byte[][]{ bBytes, bAlpha } );
	}
	
	
	final static public Pair< FloatProcessor, byte[][] > downsampleFloatAlpha( final Pair< FloatProcessor, byte[][] > a )
	{
		return downsampleFloatAlpha( a.a, a.b[ 1 ] );
	}
	
	final static public Pair< FloatProcessor, byte[][] > downsampleFloatAlpha( final FloatProcessor a, final byte[] aAlpha )
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
		final byte[] bAlpha = new byte[ bPixels.length ];
		
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
				
				final float s = (
						( aPixels[ yaxa ] ) +
						( aPixels[ yaxa1 ] ) +
						( aPixels[ ya1xa ] ) +
						( aPixels[ ya1xa1 ] ) ) / 4;
				bPixels[ ybxb ] = s;
				bBytes[ ybxb ] = ( byte )( ( int )( ( s - min ) * scale + 0.5 ) );
				final int sAlpha = (
						( aAlpha[ yaxa ] & 0xff ) +
						( aAlpha[ yaxa1 ] & 0xff ) +
						( aAlpha[ ya1xa ] & 0xff ) +
						( aAlpha[ ya1xa1 ] & 0xff ) ) / 4;
				bAlpha[ ybxb ] = ( byte )sAlpha;
			}
		}
		return new Pair< FloatProcessor, byte[][] >( b, new byte[][]{ bBytes, bAlpha } );
	}
	
	
	final static public Pair< ColorProcessor, byte[][] > downsampleColorAlpha( final Pair< ColorProcessor, byte[][] > a )
	{
		return downsampleColorAlpha( a.a, a.b[ 3 ] );
	}
	
	final static public Pair< ColorProcessor, byte[][] > downsampleColorAlpha( final ColorProcessor a, final byte[] aAlpha )
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
		final byte[] bAlpha = new byte[ bPixels.length ];
		
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
				
				final int red = (
						( ( rgb1 >> 16 ) & 0xff ) +
						( ( rgb2 >> 16 ) & 0xff ) +
						( ( rgb3 >> 16 ) & 0xff ) +
						( ( rgb4 >> 16 ) & 0xff ) ) / 4;
				final int green = (
						( ( rgb1 >> 8 ) & 0xff ) +
						( ( rgb2 >> 8 ) & 0xff ) +
						( ( rgb3 >> 8 ) & 0xff ) +
						( ( rgb4 >> 8 ) & 0xff ) ) / 4;
				final int blue = (
						( rgb1 & 0xff ) +
						( rgb2 & 0xff ) +
						( rgb3 & 0xff ) +
						( rgb4 & 0xff ) ) / 4;
				
				bPixels[ ybxb ] = ( ( ( ( 0xff000000 | red ) << 8 ) | green ) << 8 ) | blue;
				
				rBytes[ ybxb ] = ( byte )red;
				gBytes[ ybxb ] = ( byte )green;
				bBytes[ ybxb ] = ( byte )blue;
				
				final int sAlpha = (
						( aAlpha[ yaxa ] & 0xff ) +
						( aAlpha[ yaxa1 ] & 0xff ) +
						( aAlpha[ ya1xa ] & 0xff ) +
						( aAlpha[ ya1xa1 ] & 0xff ) ) / 4;
				bAlpha[ ybxb ] = ( byte )sAlpha;
			}
		}
		return new Pair< ColorProcessor, byte[][] >( b, new byte[][]{ rBytes, gBytes, bBytes, bAlpha } );
	}
	
	
	
	final static public Triple< ByteProcessor, byte[][], byte[] > downsampleByteAlphaOutside( final ByteProcessor a, final byte[] aAlpha, final byte[] aOutside )
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
		final byte[] bAlpha = new byte[ bPixels.length ];
		final byte[] bOutside = new byte[ bPixels.length ];
		
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
				
				final int s = (
						( aPixels[ yaxa ] & 0xff ) +
						( aPixels[ yaxa1 ] & 0xff ) +
						( aPixels[ ya1xa ] & 0xff ) +
						( aPixels[ ya1xa1 ] & 0xff ) ) / 4;
				bPixels[ ybxb ] = ( byte )s;
				final int sOutside = (
						aOutside[ yaxa ] &
						aOutside[ yaxa1 ] &
						aOutside[ ya1xa ] &
						aOutside[ ya1xa1 ] & 0xff );
				if ( sOutside == 0xff )
				{
					final int sAlpha = (
							( aAlpha[ yaxa ] & 0xff ) +
							( aAlpha[ yaxa1 ] & 0xff ) +
							( aAlpha[ ya1xa ] & 0xff ) +
							( aAlpha[ ya1xa1 ] & 0xff ) ) / 4;
					bAlpha[ ybxb ] = ( byte )sAlpha;
					bOutside[ ybxb ] = -1;
				}
				else
					bAlpha[ ybxb ] = 0;
			}
		}
		return new Triple< ByteProcessor, byte[][], byte[] >( b, new byte[][]{ bPixels, bAlpha }, bOutside );
	}
	
	
}
