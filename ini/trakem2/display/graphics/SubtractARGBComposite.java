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
public class SubtractARGBComposite implements Composite
{

	static private SubtractARGBComposite instance = new SubtractARGBComposite();

	final private float alpha;

	public static SubtractARGBComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new SubtractARGBComposite( alpha );
	}

	private SubtractARGBComposite()
	{
		this.alpha = 1.0f;
	}

	private SubtractARGBComposite( final float alpha )
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
						
						dstInPixel[ 0 ] = Math.max( 0, Math.round( dstInPixel[ 0 ] - srcPixel[ 0 ] * srcAlpha ) );
						dstInPixel[ 1 ] = Math.max( 0, Math.round( dstInPixel[ 1 ] - srcPixel[ 1 ] * srcAlpha ) );
						dstInPixel[ 2 ] = Math.max( 0, Math.round( dstInPixel[ 2 ] - srcPixel[ 2 ] * srcAlpha ) );
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
