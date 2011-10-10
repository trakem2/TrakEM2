package mpicbg.trakem2.transform;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;

import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TranslationModel2D;

public class ExportUnsignedShortLayer
{
	static protected class PatchIntensityRange
	{
		final public Patch patch;
		final public double a, min, max;
		
		PatchIntensityRange( final Patch patch )
		{
			this.patch = patch;
			a = patch.getMax() - patch.getMin();
			final ImageProcessor ip = patch.getImageProcessor();
			ip.resetMinAndMax();
			min = ( ip.getMin() - patch.getMin() ) / a;
			max = ( ip.getMax() - patch.getMin() ) / a;
			ip.setMinAndMax( patch.getMin(), patch.getMax() );
		}
	}
	
	static protected class PatchTransform
	{
		final PatchIntensityRange pir;
		final CoordinateTransform ct;
		
		PatchTransform( final PatchIntensityRange pir )
		{
			this.pir = pir;
			final CoordinateTransform ctp = pir.patch.getCoordinateTransform();
			if ( ctp == null )
			{
				final AffineModel2D affine = new AffineModel2D();
				affine.set( pir.patch.getAffineTransform() );
				ct = affine;
			}
			else
			{
				final Rectangle box = pir.patch.getCoordinateTransformBoundingBox();
				final AffineTransform at = pir.patch.getAffineTransformCopy();
				at.translate( -box.x, -box.y );
				final AffineModel2D affine = new AffineModel2D();
				affine.set( at );
				
				final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
				ctl.add( ctp );
				ctl.add( affine );
				
				ct = ctl;
			}
		}
	}
	
	final static protected ShortProcessor mapIntensities( final PatchIntensityRange pir, final double min, final double max )
	{
		final double a = 65535.0 / ( max - min );
		final ImageProcessor source = pir.patch.getImageProcessor();
		final short[] targetPixels = new short[ source.getWidth() * source.getHeight() ];
		for ( int i = 0; i < targetPixels.length; ++i )
		{
			targetPixels[ i ] = ( short )Math.max( 0, Math.min( 65535, Math.round( ( ( source.getf( i ) - pir.patch.getMin() ) / pir.a - min ) * a ) ) );
		}
		final ShortProcessor target = new ShortProcessor( source.getWidth(), source.getHeight(), targetPixels, null );
		target.setMinAndMax( -min * a, ( 1.0 - min ) * a );
		return target;
	}
	
	final static protected void map( final PatchTransform pt, final double x, final double y, final ShortProcessor mappedIntensities, final ShortProcessor target )
	{
		final TranslationModel2D t = new TranslationModel2D();
		t.set( ( float )-x, ( float )-y );
		
		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		ctl.add( pt.ct );
		ctl.add( t );
		
		final CoordinateTransformMesh mesh = new CoordinateTransformMesh( ctl, pt.pir.patch.getMeshResolution(), pt.pir.patch.getOWidth(), pt.pir.patch.getOHeight() );
		
		final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( mesh );
		
		mappedIntensities.setInterpolationMethod( ImageProcessor.BILINEAR );
		if ( pt.pir.patch.hasAlphaMask() )
		{
			final ByteProcessor alpha = pt.pir.patch.getProject().getLoader().fetchImageMask( pt.pir.patch );
			alpha.setInterpolationMethod( ImageProcessor.BILINEAR );
			mapping.map( mappedIntensities, alpha, target );
		}
		else
		{
			mapping.mapInterpolated( mappedIntensities, target );
		}
	}
	
	final static public void export( final Layer layer, final int tileWidth, final int tileHeight )
	{
		/* calculate intensity transfer */
		final ArrayList< Displayable > patches = layer.getDisplayables( Patch.class );
		final ArrayList< PatchIntensityRange > patchIntensityRanges = new ArrayList< PatchIntensityRange >();
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for ( final Displayable d : patches )
		{
			final Patch patch = ( Patch )d;
			final PatchIntensityRange pir = new PatchIntensityRange( patch );
			if ( pir.min < min )
				min = pir.min;
			if ( pir.max > max )
				max = pir.max;
			patchIntensityRanges.add( pir );
		}
		
		/* render tiles */
		/* TODO Do not render them into a stack but save them as files */
		
		final ImageStack stack = new ImageStack( tileWidth, tileHeight );
		ImagePlus imp = null;
		final double minI = -min * 65535.0 / ( max - min );
		final double maxI = ( 1.0 - min ) * 65535.0 / ( max - min );
		final int nc = ( int )Math.ceil( layer.getLayerWidth() / tileWidth );
		final int nr = ( int )Math.ceil( layer.getLayerHeight() / tileHeight );
		for ( int r = 0; r < nr; ++r )
		{
			final int y0 = r * tileHeight;
			for ( int c = 0; c < nc; ++c )
			{
				final int x0 = c * tileWidth;
				final Rectangle box = new Rectangle( x0, y0, tileWidth, tileHeight );
				final ShortProcessor sp = new ShortProcessor( tileWidth, tileHeight );
				sp.setMinAndMax( minI, maxI );
				for ( final PatchIntensityRange pir : patchIntensityRanges )
				{
					if ( pir.patch.getBoundingBox().intersects( box ) )
						map( new PatchTransform( pir ), x0, y0, mapIntensities( pir, min, max ), sp );
				}
				stack.addSlice( r + ", " + c , sp );
				if ( stack.getSize() == 1 )
				{
					imp = new ImagePlus( "tiles", stack );
					imp.show();
				}
				imp.updateAndDraw();
				imp.setSlice( stack.getSize() );
			}
		}
	}
}
