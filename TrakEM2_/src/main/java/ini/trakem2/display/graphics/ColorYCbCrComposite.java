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
package ini.trakem2.display.graphics;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

import mpicbg.util.Matrix3x3;
import mpicbg.util.Util;

/**
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1b
 */
public class ColorYCbCrComposite implements Composite
{
	static private interface Composer
	{
		public void compose( final int[] src, final int[] dst, final float alpha );
	}
	final static private class ARGB2ARGB implements Composer
	{
		final float[] srcYCbCr = new float[ 3 ];
		final float[] dstYCbCr = new float[ 3 ];
		
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			rgb2ycbcr( src, srcYCbCr );
			rgb2ycbcr( dst, dstYCbCr );
			
			final float srcAlpha = src[ 3 ] / 255.0f * alpha;
			final float dstAlpha = 1.0f - srcAlpha;
			
			dstYCbCr[ 1 ] = srcYCbCr[ 1 ] * srcAlpha + dstYCbCr[ 1 ] * dstAlpha;
			dstYCbCr[ 2 ] = srcYCbCr[ 2 ] * srcAlpha + dstYCbCr[ 2 ] * dstAlpha;
			
			ycbcr2rgb( dstYCbCr, dst );
			dst[ 3 ] = 255;
		}
	}
	final static private class RGB2ARGB implements Composer
	{
		final float[] srcYCbCr = new float[ 3 ];
		final float[] dstYCbCr = new float[ 3 ];
		
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			rgb2ycbcr( src, srcYCbCr );
			rgb2ycbcr( dst, dstYCbCr );
			
			final float dstAlpha = 1.0f - alpha;
			
			dstYCbCr[ 1 ] = srcYCbCr[ 1 ] * alpha + dstYCbCr[ 1 ] * dstAlpha;
			dstYCbCr[ 2 ] = srcYCbCr[ 2 ] * alpha + dstYCbCr[ 2 ] * dstAlpha;
			
			ycbcr2rgb( dstYCbCr, dst );
			dst[ 3 ] = 255;
		}
	}
	final static private class Gray2ARGB implements Composer
	{
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			final float dstAlpha = 1.0f - alpha;
			
			final float l = 0.299f * dst[ 0 ] + 0.587f * dst[ 1 ] + 0.114f * dst[ 2 ];
			
			dst[ 0 ] = Math.max( 0, Math.min( 255, Util.round( l * alpha + dst[ 0 ] * dstAlpha ) ) );
			dst[ 1 ] = Math.max( 0, Math.min( 255, Util.round( l * alpha + dst[ 1 ] * dstAlpha ) ) );
			dst[ 2 ] = Math.max( 0, Math.min( 255, Util.round( l * alpha + dst[ 2 ] * dstAlpha ) ) );
			dst[ 3 ] = 255;
		}
	}
	
	final static float[] rgb2ycbcr = new float[]{
			0.299f, 0.587f, 0.114f,
			-0.168736f, -0.331264f, 0.5f,
			0.5f, -0.418688f, -0.081312f };
	
	final static float[] ycbcr2rgb = rgb2ycbcr.clone();
	static { try{ Matrix3x3.invert( ycbcr2rgb ); } catch ( Exception e ){} }
	
	static private ColorYCbCrComposite instance = new ColorYCbCrComposite();

	final private float alpha;

	public static ColorYCbCrComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new ColorYCbCrComposite( alpha );
	}

	private ColorYCbCrComposite()
	{
		this.alpha = 1.0f;
	}
	
	/**
	 * Transforms RGB into YCbCr
	 * @param rgb r[0,255], g[0,255], b[0,255], ...
	 * @param ycbcr y[0,1.0], cb[-0.5,0.5], cr[-0.5,0.5], ...
	 */
	final static private void rgb2ycbcr( final int[] rgb, final float[] ycbcr )
	{
		final float r = rgb[ 0 ] / 255.0f;
		final float g = rgb[ 1 ] / 255.0f;
		final float b = rgb[ 2 ] / 255.0f;
		
		ycbcr[ 0 ] = rgb2ycbcr[ 0 ] * r + rgb2ycbcr[ 1 ] * g + rgb2ycbcr[ 2 ] * b;
		ycbcr[ 1 ] = rgb2ycbcr[ 3 ] * r + rgb2ycbcr[ 4 ] * g + rgb2ycbcr[ 5 ] * b;
		ycbcr[ 2 ] = rgb2ycbcr[ 6 ] * r + rgb2ycbcr[ 7 ] * g + rgb2ycbcr[ 8 ] * b;
	}
	
	/**
	 * Transforms YCbCr into RGB
	 * @param ycbcr y[0,1.0], cb[-0.5,0.5], cr[-0.5,0.5], ...
	 * @param rgb r[0,255], g[0,255], b[0,255], ...
	 */
	final static private void ycbcr2rgb( final float[] ycbcr, final int[] rgb )
	{
		final float r = ycbcr2rgb[ 0 ] * ycbcr[ 0 ] + ycbcr2rgb[ 1 ] * ycbcr[ 1 ] + ycbcr2rgb[ 2 ] * ycbcr[ 2 ];
		final float g = ycbcr2rgb[ 3 ] * ycbcr[ 0 ] + ycbcr2rgb[ 4 ] * ycbcr[ 1 ] + ycbcr2rgb[ 5 ] * ycbcr[ 2 ];
		final float b = ycbcr2rgb[ 6 ] * ycbcr[ 0 ] + ycbcr2rgb[ 7 ] * ycbcr[ 1 ] + ycbcr2rgb[ 8 ] * ycbcr[ 2 ];
		
		rgb[ 0 ] = Math.max( 0, Math.min( 255, Math.round( r * 255 ) ) );
		rgb[ 1 ] = Math.max( 0, Math.min( 255, Math.round( g * 255 ) ) );
		rgb[ 2 ] = Math.max( 0, Math.min( 255, Math.round( b * 255 ) ) );
	}

	private ColorYCbCrComposite( final float alpha )
	{
		this.alpha = alpha;
	}

	public CompositeContext createContext( ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints )
	{
		final Composer c;
		if ( srcColorModel.getNumColorComponents() > 1 )
		{
			if ( srcColorModel.hasAlpha() )
				c = new ARGB2ARGB();
			else
				c = new RGB2ARGB();
		}
		else
			c = new Gray2ARGB();
		
		return new CompositeContext()
		{
			private Composer composer = c;
			public void compose( Raster src, Raster dstIn, WritableRaster dstOut )
			{
				final int[] srcPixel = new int[ 4 ];
				final int[] dstInPixel = new int[ 4 ];
				
				for ( int x = 0; x < dstOut.getWidth(); x++ )
				{
					for ( int y = 0; y < dstOut.getHeight(); y++ )
					{
						src.getPixel( x, y, srcPixel );
						dstIn.getPixel( x, y, dstInPixel );
						
						composer.compose( srcPixel, dstInPixel, alpha );
						
						dstOut.setPixel( x, y, dstInPixel );
					}
				}
			}

			public void dispose()
			{}
		};
	}
}
