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
package mpicbg.trakem2.transform;

import java.util.ArrayList;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.PointMatch;
import mpicbg.models.TransformMesh;
import mpicbg.util.Util;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

/**
 * Specialized {@link mpicbg.ij.TransformMapping} for Patches, that is, rendering
 * the image, outside mask and mask in one go instead three.
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class TransformMeshMappingWithMasks< T extends TransformMesh > extends mpicbg.ij.TransformMeshMapping< T >
{
	final static public class ImageProcessorWithMasks
	{
		final public ImageProcessor ip;
		public ByteProcessor outside = null;
		public ImageProcessor mask = null;
		
		public ImageProcessorWithMasks( final ImageProcessor ip, final ImageProcessor mask, final ByteProcessor outside )
		{
			this.ip = ip;
			if ( outside != null )
			{
				if ( ip.getWidth() == outside.getWidth() && ip.getHeight() == outside.getHeight() )
					this.outside = outside;
				else
					System.err.println( "ImageProcessorWithMasks: ip and outside mask differ in size, setting outside = null" );
			}
			if ( mask != null )
			{
				if ( ip.getWidth() == mask.getWidth() && ip.getHeight() == mask.getHeight() )
					this.mask = mask;
				else
					System.err.println( "ImageProcessorWithMasks: ip and mask differ in size, setting mask = null" );
			}		
		}
		
		final public int getWidth(){ return ip.getWidth(); }
		final public int getHeight(){ return ip.getHeight(); }
	}
	
	public TransformMeshMappingWithMasks( final T t )
	{
		super( t );
	}
	
	final static protected void mapTriangle(
			final TransformMesh m, 
			final AffineModel2D ai,
			final ImageProcessor source,
			final ImageProcessor target,
			final ByteProcessor targetOutside )
	{
		final int w = target.getWidth() - 1;
		final int h = target.getHeight() - 1;
		final ArrayList< PointMatch > pm = m.getAV().get( ai );
		final float[] min = new float[ 2 ];
		final float[] max = new float[ 2 ];
		calculateBoundingBox( pm, min, max );
		
		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );
		
		final float[] a = pm.get( 0 ).getP2().getW();
		final float ax = a[ 0 ];
		final float ay = a[ 1 ];
		final float[] b = pm.get( 1 ).getP2().getW();
		final float bx = b[ 0 ];
		final float by = b[ 1 ];
		final float[] c = pm.get( 2 ).getP2().getW();
		final float cx = c[ 0 ];
		final float cy = c[ 1 ];
		final float[] t = new float[ 2 ];
		for ( int y = minY; y <= maxY; ++y )
		{
			for ( int x = minX; x <= maxX; ++x )
			{
				if ( isInTriangle( ax, ay, bx, by, cx, cy, x, y ) )
				{
					t[ 0 ] = x;
					t[ 1 ] = y;
					try
					{
						ai.applyInverseInPlace( t );
					}
					catch ( Exception e )
					{
						//e.printStackTrace( System.err );
						continue;
					}
					target.set( x, y, source.getPixel( ( int )( t[ 0 ] + 0.5f ), ( int )( t[ 1 ] + 0.5f ) ) );
					targetOutside.set( x, y, 0xff );
				}
			}
		}
	}
	
	final static protected void mapTriangleInterpolated(
			final TransformMesh m, 
			final AffineModel2D ai,
			final ImageProcessor source,
			final ImageProcessor target,
			final ByteProcessor targetOutside )
	{
		final int w = target.getWidth() - 1;
		final int h = target.getHeight() - 1;
		final ArrayList< PointMatch > pm = m.getAV().get( ai );
		final float[] min = new float[ 2 ];
		final float[] max = new float[ 2 ];
		calculateBoundingBox( pm, min, max );
		
		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );
		
		final float[] a = pm.get( 0 ).getP2().getW();
		final float ax = a[ 0 ];
		final float ay = a[ 1 ];
		final float[] b = pm.get( 1 ).getP2().getW();
		final float bx = b[ 0 ];
		final float by = b[ 1 ];
		final float[] c = pm.get( 2 ).getP2().getW();
		final float cx = c[ 0 ];
		final float cy = c[ 1 ];
		final float[] t = new float[ 2 ];
		for ( int y = minY; y <= maxY; ++y )
		{
			for ( int x = minX; x <= maxX; ++x )
			{
				if ( isInTriangle( ax, ay, bx, by, cx, cy, x, y ) )
				{
					t[ 0 ] = x;
					t[ 1 ] = y;
					try
					{
						ai.applyInverseInPlace( t );
					}
					catch ( Exception e )
					{
						//e.printStackTrace( System.err );
						continue;
					}
					target.set( x, y, source.getPixelInterpolated( t[ 0 ], t[ 1 ] ) );
					targetOutside.set( x, y, 0xff );
				}
			}
		}
	}
	
	
	final static protected void mapTriangle(
			final TransformMesh m, 
			final AffineModel2D ai,
			final ImageProcessor source,
			final ImageProcessor sourceMask,
			final ImageProcessor target,
			final ImageProcessor targetMask,
			final ByteProcessor targetOutside )
	{
		final int w = target.getWidth() - 1;
		final int h = target.getHeight() - 1;
		final ArrayList< PointMatch > pm = m.getAV().get( ai );
		final float[] min = new float[ 2 ];
		final float[] max = new float[ 2 ];
		calculateBoundingBox( pm, min, max );
		
		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );
		
		final float[] a = pm.get( 0 ).getP2().getW();
		final float ax = a[ 0 ];
		final float ay = a[ 1 ];
		final float[] b = pm.get( 1 ).getP2().getW();
		final float bx = b[ 0 ];
		final float by = b[ 1 ];
		final float[] c = pm.get( 2 ).getP2().getW();
		final float cx = c[ 0 ];
		final float cy = c[ 1 ];
		final float[] t = new float[ 2 ];
		for ( int y = minY; y <= maxY; ++y )
		{
			for ( int x = minX; x <= maxX; ++x )
			{
				if ( isInTriangle( ax, ay, bx, by, cx, cy, x, y ) )
				{
					t[ 0 ] = x;
					t[ 1 ] = y;
					try
					{
						ai.applyInverseInPlace( t );
					}
					catch ( Exception e )
					{
						//e.printStackTrace( System.err );
						continue;
					}
					target.set( x, y, source.getPixel( ( int )( t[ 0 ] + 0.5f ), ( int )( t[ 1 ] + 0.5f ) ) );
					targetOutside.set( x, y, 0xff );
					targetMask.set( x, y, sourceMask.getPixel( ( int )( t[ 0 ] + 0.5f ), ( int )( t[ 1 ] + 0.5f ) ) );
				}
			}
		}
	}
	
	final static protected void mapTriangleInterpolated(
			final TransformMesh m, 
			final AffineModel2D ai,
			final ImageProcessor source,
			final ImageProcessor sourceMask,
			final ImageProcessor target,
			final ImageProcessor targetMask,
			final ByteProcessor targetOutside )
	{
		final int w = target.getWidth() - 1;
		final int h = target.getHeight() - 1;
		final ArrayList< PointMatch > pm = m.getAV().get( ai );
		final float[] min = new float[ 2 ];
		final float[] max = new float[ 2 ];
		calculateBoundingBox( pm, min, max );
		
		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );
		
		final float[] a = pm.get( 0 ).getP2().getW();
		final float ax = a[ 0 ];
		final float ay = a[ 1 ];
		final float[] b = pm.get( 1 ).getP2().getW();
		final float bx = b[ 0 ];
		final float by = b[ 1 ];
		final float[] c = pm.get( 2 ).getP2().getW();
		final float cx = c[ 0 ];
		final float cy = c[ 1 ];
		final float[] t = new float[ 2 ];
		for ( int y = minY; y <= maxY; ++y )
		{
			for ( int x = minX; x <= maxX; ++x )
			{
				if ( isInTriangle( ax, ay, bx, by, cx, cy, x, y ) )
				{
					t[ 0 ] = x;
					t[ 1 ] = y;
					try
					{
						ai.applyInverseInPlace( t );
					}
					catch ( Exception e )
					{
						//e.printStackTrace( System.err );
						continue;
					}
					target.set( x, y, source.getPixelInterpolated( t[ 0 ], t[ 1 ] ) );
					targetOutside.set( x, y, 0xff );
					targetMask.set( x, y, sourceMask.getPixelInterpolated( t[ 0 ], t[ 1 ] ) );
				}
			}
		}
	}
	
	final public void map( final ImageProcessorWithMasks source, final ImageProcessorWithMasks target )
	{
		target.outside = new ByteProcessor( target.getWidth(), target.getHeight() );
		
		if ( source.mask == null )
		{
			final Set< AffineModel2D > s = transform.getAV().keySet();
			for ( final AffineModel2D ai : s )
				mapTriangle( transform, ai, source.ip, target.ip, target.outside );
		}
		else
		{
			final Set< AffineModel2D > s = transform.getAV().keySet();
			for ( final AffineModel2D ai : s )
				mapTriangle( transform, ai, source.ip, source.mask, target.ip, target.mask, target.outside );
		}
	}
	
	final public void mapInterpolated( final ImageProcessorWithMasks source, final ImageProcessorWithMasks target )
	{
		target.outside = new ByteProcessor( target.getWidth(), target.getHeight() );
		
		if ( source.mask == null )
		{
			final Set< AffineModel2D > s = transform.getAV().keySet();
			for ( final AffineModel2D ai : s )
				mapTriangleInterpolated( transform, ai, source.ip, target.ip, target.outside );
		}
		else
		{
			final Set< AffineModel2D > s = transform.getAV().keySet();
			for ( final AffineModel2D ai : s )
				mapTriangleInterpolated( transform, ai, source.ip, source.mask, target.ip, target.mask, target.outside );
		}
	}
}
