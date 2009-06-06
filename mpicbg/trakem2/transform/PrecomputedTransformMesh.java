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

import ij.ImagePlus;
import ij.process.FloatProcessor;
import mpicbg.models.CoordinateTransform;

/**
 * Speeds up direct
 * {@linkplain InvertibleCoordinateTransform coordinate transformations}
 * by holding pre-computed transformation maps in memory.
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1b
 */
public class PrecomputedTransformMesh extends TransformMesh
{
	final protected FloatProcessor tx, ty, itx, ity;
	
	public PrecomputedTransformMesh(
			final CoordinateTransform t,
			final int numX,
			final float width,
			final float height )
	{
		super( t, numX, width, height );
		
		final FloatProcessor o = new FloatProcessor( ( int )Math.ceil( width ), ( int )Math.ceil( height ) );
		final float[] oPixels = ( float[] )o.getPixels();
		final FloatProcessor m = new FloatProcessor( ( int )Math.ceil( width ), ( int )Math.ceil( height ) );
		final float[] mPixels = ( float[] )m.getPixels();
		for ( int i = 0; i < oPixels.length; ++i )
			mPixels[ i ] = 1;
		
		final TransformMeshMapping mapping = new TransformMeshMapping( this );
		
		final FloatProcessor tm = ( FloatProcessor )mapping.createMappedImageInterpolated( m );
		final float[] tmPixels = ( float[] )tm.getPixels();
		new ImagePlus( "mask", tm ).show();
		
		/* prepare itx */
		for ( int i = 0; i < oPixels.length; ++i )
			oPixels[ i ] = i % o.getWidth();
		itx = ( FloatProcessor )mapping.createMappedImageInterpolated( o );
		final float[] itxPixels = ( float[] )itx.getPixels();
		for ( int i = 0; i < itxPixels.length; ++i )
			if ( tmPixels[ i ] == 0 ) itxPixels[ i ] = Float.NaN;
		new ImagePlus( "itx", itx ).show();
		
		/* prepare ity */
		for ( int i = 0; i < oPixels.length; ++i )
			oPixels[ i ] = i / o.getWidth();
		ity = ( FloatProcessor )mapping.createMappedImageInterpolated( o );
		final float[] ityPixels = ( float[] )ity.getPixels();
		for ( int i = 0; i < ityPixels.length; ++i )
			if ( tmPixels[ i ] == 0 ) ityPixels[ i ] = Float.NaN;
		new ImagePlus( "ity", ity ).show();
		
		final FloatProcessor io = new FloatProcessor( boundingBox.width, boundingBox.height );
		final float[] ioPixels = ( float[] )io.getPixels();
		
		/* prepare tx */
		for ( int i = 0; i < ioPixels.length; ++i )
			ioPixels[ i ] = i % io.getWidth() + boundingBox.x;
		tx = ( FloatProcessor )mapping.createInverseMappedImageInterpolated( io );
		new ImagePlus( "tx", tx ).show();
		
		/* prepare ty */
		for ( int i = 0; i < ioPixels.length; ++i )
			ioPixels[ i ] = i / io.getWidth() + boundingBox.y;
		ty = ( FloatProcessor )mapping.createInverseMappedImageInterpolated( io );
		new ImagePlus( "ty", ty ).show();
	}
	
	@Override
	final public void applyInPlace( final float[] location )
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";
		
		if ( location[ 0 ] >= 0 && location[ 0 ] < tx.getWidth() && location[ 1 ] >= 0 && location[ 1 ] < tx.getHeight() )
		{
			final float x = ( float )tx.getInterpolatedPixel( location[ 0 ], location[ 1 ] );
			final float y = ( float )ty.getInterpolatedPixel( location[ 0 ], location[ 1 ] );
			location[ 0 ] = x - boundingBox.x;
			location[ 1 ] = y - boundingBox.y;
		}
		else
			location[ 0 ] = location[ 1 ] = Float.NaN;
	}
	
	@Override
	final public void applyInverseInPlace( final float[] location )
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";
		
		if ( location[ 0 ] >= 0 && location[ 0 ] < itx.getWidth() && location[ 1 ] >= 0 && location[ 1 ] < itx.getHeight() )
		{
			final float x = ( float )itx.getInterpolatedPixel( location[ 0 ], location[ 1 ] );
			final float y = ( float )ity.getInterpolatedPixel( location[ 0 ], location[ 1 ] );
			location[ 0 ] = x;
			location[ 1 ] = y;
		}
		else
			location[ 0 ] = location[ 1 ] = Float.NaN;
	}
}
