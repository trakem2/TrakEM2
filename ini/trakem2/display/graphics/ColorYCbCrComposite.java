/**
 * 
 */
package ini.trakem2.display.graphics;

import java.awt.*;
import java.awt.image.*;

import mpicbg.util.Matrix3x3;

/**
 * @author saalfeld
 * 
 */
public class ColorYCbCrComposite implements Composite
{
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

		return new CompositeContext()
		{

			public void compose( Raster src, Raster dstIn, WritableRaster dstOut )
			{
				final int[] srcPixel = new int[ 4 ];
				final int[] dstInPixel = new int[ 4 ];
				
				final float[] srcYCbCr = new float[ 3 ];
				final float[] dstInYCbCr = new float[ 3 ];
				
				for ( int x = 0; x < dstOut.getWidth(); x++ )
				{
					for ( int y = 0; y < dstOut.getHeight(); y++ )
					{
						src.getPixel( x, y, srcPixel );
						dstIn.getPixel( x, y, dstInPixel );
						
						rgb2ycbcr( srcPixel, srcYCbCr );
						rgb2ycbcr( dstInPixel, dstInYCbCr );
						
						final float srcAlpha = srcPixel[ 3 ] / 255.0f * alpha;
						final float dstAlpha = 1.0f - srcAlpha;
						
						dstInYCbCr[ 0 ] = dstInYCbCr[ 0 ];
						dstInYCbCr[ 1 ] = srcYCbCr[ 1 ] * srcAlpha + dstInYCbCr[ 1 ] * dstAlpha;
						dstInYCbCr[ 2 ] = srcYCbCr[ 2 ] * srcAlpha + dstInYCbCr[ 2 ] * dstAlpha;
						
						ycbcr2rgb( dstInYCbCr, dstInPixel );
						dstInPixel[ 3 ] = 255;
						
						dstOut.setPixel( x, y, dstInPixel );
					}
				}
			}

			public void dispose()
			{}
		};

	}

}
