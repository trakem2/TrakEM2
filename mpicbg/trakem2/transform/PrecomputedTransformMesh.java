package mpicbg.trakem2.transform;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.SimilarityModel2D;

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
	final protected float scale;
	
	public PrecomputedTransformMesh(
			final CoordinateTransform t,
			final int numX,
			final float width,
			final float height,
			final float scale )
	{
		super( t, numX, width, height );
		
		this.scale = scale;
		
		final int scaledWidth = ( int )Math.ceil( scale * width );
		final int scaledHeight = ( int )Math.ceil( scale * height );
		
		final FloatProcessor o = new FloatProcessor( scaledWidth, scaledHeight );
		final float[] oPixels = ( float[] )o.getPixels();
		final FloatProcessor m = new FloatProcessor( scaledWidth, scaledHeight );
		final float[] mPixels = ( float[] )m.getPixels();
		for ( int i = 0; i < mPixels.length; ++i )
			mPixels[ i ] = 1;
		
		final SimilarityModel2D sm = new SimilarityModel2D();
		sm.set( scale, 0, 0, 0 );
		final CoordinateTransformList ctl = new CoordinateTransformList();
		ctl.add( sm.createInverse() );
		ctl.add( t );
		ctl.add( sm );
		final TransformMesh tmScaled = new TransformMesh( ctl, numX, scaledWidth, scaledHeight );
		final TransformMeshMapping mapping = new TransformMeshMapping( tmScaled );
		
		final FloatProcessor tm = ( FloatProcessor )mapping.createMappedImageInterpolated( m );
		final float[] tmPixels = ( float[] )tm.getPixels();
		new ImagePlus( "mask", tm ).show();
		
		/* prepare itx */
		for ( int i = 0; i < oPixels.length; ++i )
			oPixels[ i ] = ( int )( i % o.getWidth() ) / scale;
		itx = ( FloatProcessor )mapping.createMappedImageInterpolated( o );
		final float[] itxPixels = ( float[] )itx.getPixels();
		for ( int i = 0; i < itxPixels.length; ++i )
			if ( tmPixels[ i ] == 0 ) itxPixels[ i ] = Float.NaN;
		new ImagePlus( "itx", itx ).show();
		
		/* prepare ity */
		for ( int i = 0; i < oPixels.length; ++i )
			oPixels[ i ] = ( int )( i / o.getWidth() ) / scale;
		ity = ( FloatProcessor )mapping.createMappedImageInterpolated( o );
		final float[] ityPixels = ( float[] )ity.getPixels();
		for ( int i = 0; i < ityPixels.length; ++i )
			if ( tmPixels[ i ] == 0 ) ityPixels[ i ] = Float.NaN;
		new ImagePlus( "ity", ity ).show();
		
		final FloatProcessor io = new FloatProcessor( tmScaled.boundingBox.width, tmScaled.boundingBox.height );
		final float[] ioPixels = ( float[] )io.getPixels();
		
		/* prepare tx */
		for ( int i = 0; i < ioPixels.length; ++i )
			ioPixels[ i ] = ( int )( i % io.getWidth() + tmScaled.boundingBox.x ) / scale;
		tx = ( FloatProcessor )mapping.createInverseMappedImageInterpolated( io );
		new ImagePlus( "tx", tx ).show();
		
		/* prepare ty */
		for ( int i = 0; i < ioPixels.length; ++i )
			ioPixels[ i ] = ( int )( i / io.getWidth() + tmScaled.boundingBox.y ) / scale;
		ty = ( FloatProcessor )mapping.createInverseMappedImageInterpolated( io );
		new ImagePlus( "ty", ty ).show();
	}
	
	public PrecomputedTransformMesh(
			final CoordinateTransform t,
			final int numX,
			final float width,
			final float height )
	{
		this( t, numX, width, height, 2 * numX / width );
	}
	
	@Override
	final public void applyInPlace( final float[] location )
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";
		
		final float xt = location[ 0 ] * scale;
		final float yt = location[ 1 ] * scale;
		
		if ( xt >= 0 && xt < tx.getWidth() && yt >= 0 && yt < tx.getHeight() )
		{
			final float x = ( float )tx.getInterpolatedPixel( xt, yt );
			final float y = ( float )ty.getInterpolatedPixel( xt, yt );
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
		
		final float xt = location[ 0 ] * scale;
		final float yt = location[ 1 ] * scale;
		
		if ( xt >= 0 && xt < itx.getWidth() && yt >= 0 && yt < itx.getHeight() )
		{
			final float x = ( float )itx.getInterpolatedPixel( xt, yt );
			final float y = ( float )ity.getInterpolatedPixel( xt, yt );
			location[ 0 ] = x;
			location[ 1 ] = y;
		}
		else
			location[ 0 ] = location[ 1 ] = Float.NaN;
	}
}
