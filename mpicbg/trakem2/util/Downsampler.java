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
				final int s = (
						( aPixels[ ya + xa ] & 0xff ) +
						( aPixels[ ya + xa1 ] & 0xff ) +
						( aPixels[ ya1 + xa ] & 0xff ) +
						( aPixels[ ya1 + xa1 ] & 0xff ) ) / 4;
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
				final int s = (
						( aPixels[ ya + xa ] & 0xffff ) +
						( aPixels[ ya + xa1 ] & 0xffff ) +
						( aPixels[ ya1 + xa ] & 0xffff ) +
						( aPixels[ ya1 + xa1 ] & 0xffff ) ) / 4;
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
				final float s = (
						aPixels[ ya + xa ] +
						aPixels[ ya + xa1 ] +
						aPixels[ ya1 + xa ] +
						aPixels[ ya1 + xa1 ] ) / 4;
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
				
				final int rgb1 = aPixels[ ya + xa ];
				final int rgb2 = aPixels[ ya + xa1 ];
				final int rgb3 = aPixels[ ya1 + xa ];
				final int rgb4 = aPixels[ ya1 + xa1 ];
				
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
				
				bPixels[ yb + xb ] = ( ( ( ( 0xff000000 | red ) << 8 ) | green ) << 8 ) | blue;
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
		final int wa = a.a.getWidth();
		final int ha = a.a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ByteProcessor b = new ByteProcessor( wb, hb );
		
		final byte[] aPixels = a.b[ 0 ];
		final byte[] bPixels = ( byte[] )b.getPixels();
		final byte[] aAlpha = a.b[ 1 ];
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
						( aPixels[ yaxa ] & 0xff ) +
						( aPixels[ yaxa1 ] & 0xff ) +
						( aPixels[ ya1xa ] & 0xff ) +
						( aPixels[ ya1xa1 ] & 0xff ) ) / 4;
				bPixels[ ybxb ] = ( byte )s;
				final int sAlpha = (
						( aAlpha[ yaxa ] & 0xff ) +
						( aAlpha[ yaxa1 ] & 0xff ) +
						( aAlpha[ ya1xa ] & 0xff ) +
						( aAlpha[ ya1xa1 ] & 0xff ) ) / 4;
				bAlpha[ ybxb ] = ( byte )sAlpha;
			}
		}
		return new Pair< ByteProcessor, byte[][] >( b, new byte[][]{ bPixels, bAlpha } );
	}
	
	final static public Pair< ShortProcessor, byte[][] > downsampleShortAlpha( final Pair< ShortProcessor, byte[][] > a )
	{
		final int wa = a.a.getWidth();
		final int ha = a.a.getHeight();
		final int wa2 = wa + wa;
		
		final double min = a.a.getMin();
		final double max = a.a.getMax();
		final double scale = 255.0 / ( max - min );
		
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ShortProcessor b = new ShortProcessor( wb, hb );
		b.setMinAndMax( min, max );
		
		final short[] aPixels = ( short[] )a.a.getPixels();
		final byte[] aAlpha = a.b[ 1 ];
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
		final int wa = a.a.getWidth();
		final int ha = a.a.getHeight();
		final int wa2 = wa + wa;
		
		final double min = a.a.getMin();
		final double max = a.a.getMax();
		final double scale = 255.0 / ( max - min );
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final FloatProcessor b = new FloatProcessor( wb, hb );
		b.setMinAndMax( min, max );
		
		final float[] aPixels = ( float[] )a.a.getPixels();
		final byte[] aAlpha = a.b[ 1 ];
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
		final int wa = a.a.getWidth();
		final int ha = a.a.getHeight();
		final int wa2 = wa + wa;
		
		final int wb = wa / 2;
		final int hb = ha / 2;
		final int nb = hb * wb;
		
		final ColorProcessor b = new ColorProcessor( wb, hb );
		
		final int[] aPixels = ( int[] )a.a.getPixels();
		final byte[] aAlpha = a.b[ 1 ];
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
		return new Pair< ColorProcessor, byte[][] >( b, new byte[][]{ bBytes, bAlpha } );
	}
	
	
}
