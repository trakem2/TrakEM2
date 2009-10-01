/**
 * 
 */
package ini.trakem2.display.graphics;

import java.awt.*;
import java.awt.image.*;

/**
 * @author saalfeld
 * 
 */
public class MultiplyARGBComposite implements Composite
{

	static private MultiplyARGBComposite instance = new MultiplyARGBComposite();

	final private float alpha;

	public static MultiplyARGBComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new MultiplyARGBComposite( alpha );
	}

	private MultiplyARGBComposite()
	{
		this.alpha = 1.0f;
	}

	private MultiplyARGBComposite( final float alpha )
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
				
				for ( int x = 0; x < dstOut.getWidth(); x++ )
				{
					for ( int y = 0; y < dstOut.getHeight(); y++ )
					{
						src.getPixel( x, y, srcPixel );
						dstIn.getPixel( x, y, dstInPixel );
						
						final float srcAlpha = srcPixel[ 3 ] / 255.0f * alpha;
						final float dstAlpha = 1.0f - srcAlpha;
						
						dstInPixel[ 0 ] = Math.max( 0, Math.min( 255, Math.round( ( srcAlpha * srcPixel[ 0 ] * dstInPixel[ 0 ] ) / 255.0f + dstInPixel[ 0 ] * dstAlpha ) ) );
						dstInPixel[ 1 ] = Math.max( 0, Math.min( 255, Math.round( ( srcAlpha * srcPixel[ 1 ] * dstInPixel[ 1 ] ) / 255.0f + dstInPixel[ 1 ] * dstAlpha ) ) );
						dstInPixel[ 2 ] = Math.max( 0, Math.min( 255, Math.round( ( srcAlpha * srcPixel[ 2 ] * dstInPixel[ 2 ] ) / 255.0f + dstInPixel[ 2 ] * dstAlpha ) ) );
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
