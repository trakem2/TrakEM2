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

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.models.AffineModel2D;
import mpicbg.models.PointMatch;
import mpicbg.models.TransformMesh;
import mpicbg.util.Util;

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

	final static private class MapTriangleThread extends Thread
	{
		final private AtomicInteger i;
		final private List< AffineModel2D > triangles;
		final private TransformMesh transform;
		final ImageProcessorWithMasks source, target;
		MapTriangleThread(
				final AtomicInteger i,
				final List< AffineModel2D > triangles,
				final TransformMesh transform,
				final ImageProcessorWithMasks source,
				final ImageProcessorWithMasks target )
		{
			this.i = i;
			this.triangles = triangles;
			this.transform = transform;
			this.source = source;
			this.target = target;
		}

		@Override
		final public void run()
		{
			int k = i.getAndIncrement();
			while ( !isInterrupted() && k < triangles.size() )
			{
				if ( source.mask == null )
					mapTriangle( transform, triangles.get( k ), source.ip, target.ip, target.outside );
				else
					mapTriangle( transform, triangles.get( k ), source.ip, source.mask, target.ip, target.mask, target.outside );
				k = i.getAndIncrement();
			}
		}
	}

	final static private class MapTriangleInterpolatedThread extends Thread
	{
		final private AtomicInteger i;
		final private List< AffineModel2D > triangles;
		final private TransformMesh transform;
		final ImageProcessorWithMasks source, target;
		MapTriangleInterpolatedThread(
				final AtomicInteger i,
				final List< AffineModel2D > triangles,
				final TransformMesh transform,
				final ImageProcessorWithMasks source,
				final ImageProcessorWithMasks target )
		{
			this.i = i;
			this.triangles = triangles;
			this.transform = transform;
			this.source = source;
			this.target = target;
		}

		@Override
		final public void run()
		{
			int k = i.getAndIncrement();
			while ( !isInterrupted() && k < triangles.size() )
			{
				if ( source.mask == null )
					mapTriangleInterpolated( transform, triangles.get( k ), source.ip, target.ip, target.outside );
				else
					mapTriangleInterpolated( transform, triangles.get( k ), source.ip, source.mask, target.ip, target.mask, target.outside );
				k = i.getAndIncrement();
			}
		}
	}

	final static private class MapShortAlphaTriangleThread extends Thread
	{
		final private AtomicInteger i;
		final private List< AffineModel2D > triangles;
		final private TransformMesh transform;
		final ShortProcessor source, target;
		final ByteProcessor alpha;
		MapShortAlphaTriangleThread(
				final AtomicInteger i,
				final List< AffineModel2D > triangles,
				final TransformMesh transform,
				final ShortProcessor source,
				final ByteProcessor alpha,
				final ShortProcessor target )
		{
			this.i = i;
			this.triangles = triangles;
			this.transform = transform;
			this.source = source;
			this.alpha = alpha;
			this.target = target;
		}

		@Override
		final public void run()
		{
			int k = i.getAndIncrement();
			while ( !isInterrupted() && k < triangles.size() )
			{
				mapShortAlphaTriangle( transform, triangles.get( k ), source, alpha, target );
				k = i.getAndIncrement();
			}
		}
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
		final double[] min = new double[ 2 ];
		final double[] max = new double[ 2 ];
		calculateBoundingBox( pm, min, max );

		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );

		final double[] a = pm.get( 0 ).getP2().getW();
		final double ax = a[ 0 ];
		final double ay = a[ 1 ];
		final double[] b = pm.get( 1 ).getP2().getW();
		final double bx = b[ 0 ];
		final double by = b[ 1 ];
		final double[] c = pm.get( 2 ).getP2().getW();
		final double cx = c[ 0 ];
		final double cy = c[ 1 ];
		final double[] t = new double[ 2 ];
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
					catch ( final Exception e )
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
		final double[] min = new double[ 2 ];
		final double[] max = new double[ 2 ];
		calculateBoundingBox( pm, min, max );

		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );

		final double[] a = pm.get( 0 ).getP2().getW();
		final double ax = a[ 0 ];
		final double ay = a[ 1 ];
		final double[] b = pm.get( 1 ).getP2().getW();
		final double bx = b[ 0 ];
		final double by = b[ 1 ];
		final double[] c = pm.get( 2 ).getP2().getW();
		final double cx = c[ 0 ];
		final double cy = c[ 1 ];
		final double[] t = new double[ 2 ];
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
					catch ( final Exception e )
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
		final double[] min = new double[ 2 ];
		final double[] max = new double[ 2 ];
		calculateBoundingBox( pm, min, max );

		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );

		final double[] a = pm.get( 0 ).getP2().getW();
		final double ax = a[ 0 ];
		final double ay = a[ 1 ];
		final double[] b = pm.get( 1 ).getP2().getW();
		final double bx = b[ 0 ];
		final double by = b[ 1 ];
		final double[] c = pm.get( 2 ).getP2().getW();
		final double cx = c[ 0 ];
		final double cy = c[ 1 ];
		final double[] t = new double[ 2 ];
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
					catch ( final Exception e )
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
		final double[] min = new double[ 2 ];
		final double[] max = new double[ 2 ];
		calculateBoundingBox( pm, min, max );

		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );

		final double[] a = pm.get( 0 ).getP2().getW();
		final double ax = a[ 0 ];
		final double ay = a[ 1 ];
		final double[] b = pm.get( 1 ).getP2().getW();
		final double bx = b[ 0 ];
		final double by = b[ 1 ];
		final double[] c = pm.get( 2 ).getP2().getW();
		final double cx = c[ 0 ];
		final double cy = c[ 1 ];
		final double[] t = new double[ 2 ];
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
					catch ( final Exception e )
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


	final static protected void mapShortAlphaTriangle(
			final TransformMesh m,
			final AffineModel2D ai,
			final ShortProcessor source,
			final ByteProcessor alpha,
			final ShortProcessor target )
	{
		final int w = target.getWidth() - 1;
		final int h = target.getHeight() - 1;
		final ArrayList< PointMatch > pm = m.getAV().get( ai );
		final double[] min = new double[ 2 ];
		final double[] max = new double[ 2 ];
		calculateBoundingBox( pm, min, max );

		final int minX = Math.max( 0, Util.roundPos( min[ 0 ] ) );
		final int minY = Math.max( 0, Util.roundPos( min[ 1 ] ) );
		final int maxX = Math.min( w, Util.roundPos( max[ 0 ] ) );
		final int maxY = Math.min( h, Util.roundPos( max[ 1 ] ) );

		final double[] a = pm.get( 0 ).getP2().getW();
		final double ax = a[ 0 ];
		final double ay = a[ 1 ];
		final double[] b = pm.get( 1 ).getP2().getW();
		final double bx = b[ 0 ];
		final double by = b[ 1 ];
		final double[] c = pm.get( 2 ).getP2().getW();
		final double cx = c[ 0 ];
		final double cy = c[ 1 ];
		final double[] t = new double[ 2 ];
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
					catch ( final Exception e )
					{
						//e.printStackTrace( System.err );
						continue;
					}
					final int is = source.getPixelInterpolated( t[ 0 ], t[ 1 ] );
					final int it = target.get( x, y );
					final double f = alpha.getPixelInterpolated( t[ 0 ], t[ 1 ] ) / 255.0;
					final double v = it + f  * ( is - it );
					target.set( x, y, ( int )Math.max(  0, Math.min( 65535, Math.round( v ) ) ) );
				}
			}
		}
	}


	final public void map( final ImageProcessorWithMasks source, final ImageProcessorWithMasks target, final int numThreads )
	{
		target.outside = new ByteProcessor( target.getWidth(), target.getHeight() );
		final HashMap< AffineModel2D, ArrayList< PointMatch >> av = transform.getAV();
		if ( numThreads > 1 )
		{
			final ArrayList< AffineModel2D > triangles = new ArrayList< AffineModel2D >( av.keySet() );
			final AtomicInteger i = new AtomicInteger( 0 );
			final ArrayList< Thread > threads = new ArrayList< Thread >( numThreads );
			for ( int k = 0; k < numThreads; ++k )
			{
				final Thread mtt = new MapTriangleThread( i, triangles, transform, source, target );
				threads.add( mtt );
				mtt.start();
			}
			for ( final Thread mtt : threads )
			{
				try
				{
					mtt.join();
				}
				catch ( final InterruptedException e )
				{}
			}
		}
		else if ( source.mask == null )
		{
			for ( final AffineModel2D triangle : av.keySet() )
			{
				mapTriangle( transform, triangle, source.ip, target.ip, target.outside );
			}
		}
		else
		{
			for ( final AffineModel2D triangle : av.keySet() )
			{
				mapTriangle( transform, triangle, source.ip, source.mask, target.ip, target.mask, target.outside );
			}
		}
	}

	final public void mapInterpolated( final ImageProcessorWithMasks source, final ImageProcessorWithMasks target, final int numThreads )
	{
		target.outside = new ByteProcessor( target.getWidth(), target.getHeight() );
		source.ip.setInterpolationMethod( ImageProcessor.BILINEAR );
		if ( source.mask != null )
		{
			source.mask.setInterpolationMethod( ImageProcessor.BILINEAR );
		}
		final HashMap< AffineModel2D, ArrayList< PointMatch >> av = transform.getAV();
		if ( numThreads > 1 )
		{
			final ArrayList< AffineModel2D > triangles = new ArrayList< AffineModel2D >( av.keySet() );
			final AtomicInteger i = new AtomicInteger( 0 );
			final ArrayList< Thread > threads = new ArrayList< Thread >( numThreads );
			for ( int k = 0; k < numThreads; ++k )
			{
				final Thread mtt = new MapTriangleInterpolatedThread( i, triangles, transform, source, target );
				threads.add( mtt );
				mtt.start();
			}
			for ( final Thread mtt : threads )
			{
				try
				{
					mtt.join();
				}
				catch ( final InterruptedException e )
				{}
			}
		}
		else if ( source.mask == null )
		{
			for ( final AffineModel2D triangle : av.keySet() )
			{
				mapTriangleInterpolated( transform, triangle, source.ip, target.ip, target.outside );
			}
		}
		else
		{
			for ( final AffineModel2D triangle : av.keySet() )
			{
				mapTriangleInterpolated( transform, triangle, source.ip, source.mask, target.ip, target.mask, target.outside );
			}
		}
	}

	final public void map(
			final ImageProcessorWithMasks source,
			final ImageProcessorWithMasks target )
	{
		map( source, target, Runtime.getRuntime().availableProcessors() );
	}

	final public void mapInterpolated(
			final ImageProcessorWithMasks source,
			final ImageProcessorWithMasks target )
	{
		mapInterpolated( source, target, Runtime.getRuntime().availableProcessors() );
	}


	/**
	 * Render source into target using alpha composition.
	 * Interpolation is specified by the interpolation methods
	 * set in source and alpha.
	 *
	 * @param source
	 * @param alpha
	 * @param target
	 * @param numThreads
	 */
	final public void map(
			final ShortProcessor source,
			final ByteProcessor alpha,
			final ShortProcessor target,
			final int numThreads )
	{
		final List< AffineModel2D > l = new ArrayList< AffineModel2D >();
		l.addAll( transform.getAV().keySet() );
		final AtomicInteger i = new AtomicInteger( 0 );
		final ArrayList< Thread > threads = new ArrayList< Thread >( numThreads );
		for ( int k = 0; k < numThreads; ++k )
		{
			final Thread mtt = new MapShortAlphaTriangleThread( i, l, transform, source, alpha, target );
			threads.add( mtt );
			mtt.start();
		}
		for ( final Thread mtt : threads )
		{
			try
			{
				mtt.join();
			}
			catch ( final InterruptedException e ) {}
		}
	}


	/**
	 * Render source into master using alpha composition.
	 * Interpolation is specified by the interpolation methods
	 * set in source and alpha.
	 *
	 * @param source
	 * @param alpha
	 * @param target
	 */
	final public void map(
			final ShortProcessor source,
			final ByteProcessor alpha,
			final ShortProcessor target )
	{
		map( source, alpha, target, Runtime.getRuntime().availableProcessors() );
	}
}
