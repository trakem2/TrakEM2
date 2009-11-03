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
public class AddARGBComposite implements Composite
{

	static private AddARGBComposite instance = new AddARGBComposite();

	final private float alpha;

	public static AddARGBComposite getInstance( final float alpha )
	{
		if ( alpha == 1.0f ) { return instance; }
		return new AddARGBComposite( alpha );
	}

	private AddARGBComposite()
	{
		this.alpha = 1.0f;
	}

	private AddARGBComposite( final float alpha )
	{
		this.alpha = alpha;
	}

	public CompositeContext createContext( ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints )
	{
		if ( srcColorModel.hasAlpha() )
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
							
							dstInPixel[ 0 ] = Math.min( 255, Math.round( srcPixel[ 0 ] * srcAlpha + dstInPixel[ 0 ] ) );
							dstInPixel[ 1 ] = Math.min( 255, Math.round( srcPixel[ 1 ] * srcAlpha + dstInPixel[ 1 ] ) );
							dstInPixel[ 2 ] = Math.min( 255, Math.round( srcPixel[ 2 ] * srcAlpha + dstInPixel[ 2 ] ) );
							dstInPixel[ 3 ] = 255;
							
							dstOut.setPixel( x, y, dstInPixel );
						}
					}
				}
	
				public void dispose()
				{}
			};
		}
		else
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
							
							dstInPixel[ 0 ] = Math.min( 255, Math.round( srcPixel[ 0 ] + dstInPixel[ 0 ] ) );
							dstInPixel[ 1 ] = Math.min( 255, Math.round( srcPixel[ 1 ] + dstInPixel[ 1 ] ) );
							dstInPixel[ 2 ] = Math.min( 255, Math.round( srcPixel[ 2 ] + dstInPixel[ 2 ] ) );
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

}
