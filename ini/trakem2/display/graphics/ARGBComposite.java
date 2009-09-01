/**
 * 
 */
package ini.trakem2.display.graphics;

import ini.trakem2.utils.Utils;

import java.awt.*;
import java.awt.image.*;

/**
 * @author saalfeld
 * 
 */
public class ARGBComposite implements Composite
{

	static private ARGBComposite instance = new ARGBComposite();

	final private float alpha;

	public static ARGBComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new ARGBComposite( alpha );
	}

	private ARGBComposite()
	{
		this.alpha = 1.0f;
	}

	private ARGBComposite( final float alpha )
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
						
						dstInPixel[ 0 ] = Math.round( srcPixel[ 0 ] * srcAlpha + dstInPixel[ 0 ] * dstAlpha );
						dstInPixel[ 1 ] = Math.round( srcPixel[ 1 ] * srcAlpha + dstInPixel[ 1 ] * dstAlpha );
						dstInPixel[ 2 ] = Math.round( srcPixel[ 2 ] * srcAlpha + dstInPixel[ 2 ] * dstAlpha );
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
