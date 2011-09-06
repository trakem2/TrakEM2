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
				final double s = (
						aPixels[ ya + xa ] +
						aPixels[ ya + xa1 ] +
						aPixels[ ya1 + xa ] +
						aPixels[ ya1 + xa1 ] ) / 4;
				bPixels[ yb + xb ] = ( float )s;
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

}
