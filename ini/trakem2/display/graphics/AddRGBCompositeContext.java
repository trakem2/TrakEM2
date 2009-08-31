/**
 * 
 */
package ini.trakem2.display.graphics;

import ini.trakem2.utils.Utils;

import java.awt.CompositeContext;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * @author saalfeld
 *
 */
public class AddRGBCompositeContext implements CompositeContext
{

	/* (non-Javadoc)
	 * @see java.awt.CompositeContext#compose(java.awt.image.Raster, java.awt.image.Raster, java.awt.image.WritableRaster)
	 */
	public void compose(Raster src, Raster dstIn, WritableRaster dstOut)
    {
      Rectangle srcRect = src.getBounds();
      Rectangle dstInRect = dstIn.getBounds();
      Rectangle dstOutRect = dstOut.getBounds();
      
      Utils.log2( "srcRect: " + srcRect );
      Utils.log2( "dstInRect: " + dstInRect );
      Utils.log2( "dstOutRect: " + dstOutRect );

      int w = Math.min(Math.min(srcRect.width, dstOutRect.width),
                       dstInRect.width);
      int h = Math.min(Math.min(srcRect.height, dstOutRect.height),
                       dstInRect.height);

      Object srcPix = null, dstPix = null, rpPix = null;
      
      final int s = ( w + 1 ) * ( h + 1 );
      final int[] srcPixels = new int[ s ];
      src.getPixels( 0, 0, w, h, srcPixels );
      
      final int[] dstInPixels = new int[ s ];
      dstIn.getPixels( 0, 0, w, h, dstInPixels );
      
      final int[] dstOutPixels = new int[ s ];
      dstOut.getPixels( 0, 0, w, h, dstOutPixels );
      
      for ( int i = 0; i < dstOutPixels.length; ++i )
      {
    	  final int srcPixel = srcPixels[ i ];
    	  final int dstInPixel = dstInPixels[ i ];
    	  
    	  final int srcA = srcPixel >> 24;
    	  final int srcR = ( srcPixel >> 16 ) & 0xff;
    	  final int srcG = ( srcPixel >> 8 ) & 0xff;
    	  final int srcB = srcPixel & 0xff;
    	  
    	  final int dstInA = dstInPixel >> 24;
    	  final int dstInR = ( dstInPixel >> 16 ) & 0xff;
    	  final int dstInG = ( dstInPixel >> 8 ) & 0xff;
    	  final int dstInB = dstInPixel & 0xff;
    	  
    	  final int dstOutA = dstInA;
    	  final int dstOutR = Math.min( 255, dstInR + srcR );
    	  final int dstOutG = Math.min( 255, dstInG + srcG );
    	  final int dstOutB = Math.min( 255, dstInB + srcB );
    	  
    	  dstOutPixels[ i ] = ( dstOutA << 24 ) | ( dstOutR << 16 ) | ( dstOutG << 8 ) | dstOutB;
    	  
    	  dstOut.setPixels( 0, 0, w, h, dstOutPixels );
      }
    }


	/* (non-Javadoc)
	 * @see java.awt.CompositeContext#dispose()
	 */
	public void dispose()
	{
	// TODO Auto-generated method stub

	}

}
