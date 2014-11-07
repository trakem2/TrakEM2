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

import mpicbg.util.Util;

/**
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1b
 */
public class DifferenceARGBComposite implements Composite
{
	static private interface Composer
	{
		public void compose( final int[] src, final int[] dst, final float alpha );
	}
	final static private class ARGB2ARGB implements Composer
	{
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			final float srcAlpha = src[ 3 ] / 255.0f * alpha;
			
			dst[ 0 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 0 ] - src[ 0 ] * srcAlpha ) ) ) );
			dst[ 1 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 1 ] - src[ 1 ] * srcAlpha ) ) ) );
			dst[ 2 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 2 ] - src[ 2 ] * srcAlpha ) ) ) );
			dst[ 3 ] = 255;
		}
	}
	final static private class RGB2ARGB implements Composer
	{
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			dst[ 0 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 0 ] - src[ 0 ] * alpha ) ) ) );
			dst[ 1 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 1 ] - src[ 1 ] * alpha ) ) ) );
			dst[ 2 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 2 ] - src[ 2 ] * alpha ) ) ) );
			dst[ 3 ] = 255;
		}
	}
	final static private class Gray2ARGB implements Composer
	{
		final public void compose( final int[] src, final int[] dst, final float alpha )
		{
			dst[ 0 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 0 ] - src[ 0 ] * alpha ) ) ) );
			dst[ 1 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 1 ] - src[ 0 ] * alpha ) ) ) );
			dst[ 2 ] = Math.max( 0, Math.min( 255, Util.round( Math.abs( dst[ 2 ] - src[ 0 ] * alpha ) ) ) );
			dst[ 3 ] = 255;
		}
	}
	
	static private DifferenceARGBComposite instance = new DifferenceARGBComposite();

	final private float alpha;

	public static DifferenceARGBComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new DifferenceARGBComposite( alpha );
	}

	private DifferenceARGBComposite()
	{
		this.alpha = 1.0f;
	}

	private DifferenceARGBComposite( final float alpha )
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
