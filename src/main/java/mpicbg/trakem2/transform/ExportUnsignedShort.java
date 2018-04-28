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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.util.Pair;
import mpicbg.trakem2.util.Triple;

public class ExportUnsignedShort
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
			ct = pir.patch.getFullCoordinateTransform();
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
	
	final static protected void map( final PatchTransform pt, final double x, final double y, final ShortProcessor mappedIntensities, final ShortProcessor target)
	{
		map( pt, x, y, Double.NaN, mappedIntensities, target);
	}

	final static protected void map( final PatchTransform pt, final double x, final double y, final double scale, final ShortProcessor mappedIntensities, final ShortProcessor target )
	{
		map( pt, x, y, Double.NaN, mappedIntensities, target, null );
	}
		
	final static protected void map( final PatchTransform pt, final double x, final double y, final double scale, final ShortProcessor mappedIntensities, final ShortProcessor target, final ByteProcessor alphaTarget)
		{
		final TranslationModel2D t = new TranslationModel2D();
		t.set( -x, -y );

		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		ctl.add( pt.ct );
		if ( !Double.isNaN( scale ) ) {
			final AffineModel2D s = new AffineModel2D();
			s.set(scale, 0, 0, scale, 0, 0);
			ctl.add( s );
		}
		ctl.add( t );

		final CoordinateTransformMesh mesh = new CoordinateTransformMesh( ctl, pt.pir.patch.getMeshResolution(), pt.pir.patch.getOWidth(), pt.pir.patch.getOHeight() );

		final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( mesh );

		ByteProcessor alpha = null;
		
		mappedIntensities.setInterpolationMethod( ImageProcessor.BILINEAR );

		if ( pt.pir.patch.hasAlphaMask() )
		{
			alpha = pt.pir.patch.getAlphaMask();
			alpha.setInterpolationMethod( ImageProcessor.BILINEAR );
			mapping.map( mappedIntensities, alpha, target );
		}
		else
		{
			mapping.mapInterpolated( mappedIntensities, target );
		}
		
		// If alphaTarget is present, repeat the mapping but just for the alpha channel
		if ( null != alphaTarget )
		{
			if ( null == alpha )
			{
				// Simulate full alpha: no transparency
				alpha = new ByteProcessor( pt.pir.patch.getOWidth(), pt.pir.patch.getOHeight() );
				alpha.setInterpolationMethod( ImageProcessor.BILINEAR );
				final byte[] as = ( byte[] )alpha.getPixels();
				for ( int i=0; i<as.length; ++i) {
					as[i] = (byte)255;
				}
			}
			
			mapping.mapInterpolated( alpha, alphaTarget);
		}
	}

	final static public void exportTEST( final Layer layer, final int tileWidth, final int tileHeight )
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

		//ij.IJ.log("min, max: " + min + ", " + max + ",    minI, maxI: " + minI + ", " + maxI);

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
				if ( null == imp && stack.getSize() > 1 )
				{
					imp = new ImagePlus( "tiles", stack );
					imp.show();
				}
				if (null != imp) {
					imp.setSlice( stack.getSize() );
					imp.updateAndDraw();
				}
			}
		}
		if (null == imp) {
			new ImagePlus( "tiles", stack ).show(); // single-slice, non-StackWindow
		}
	}

	/** Create constant size tiles that carpet the areas of the {@code layer} where there are images;
	 * these tiles are returned in a lazy sequence of {@link Callable} objects that create a tripled
	 * consisting of the {@link ShortProcessor} and the X and Y pixel coordinates of that tile.
	 *
	 * @param layer The layer to export images for
	 * @param tileWidth The width of the tiles to export
	 * @param tileHeight
	 * @return A lazy sequence of {@link Callable} instances, each holding a {@link Triple} that specifies the ShortProcessor,
	 * the X and the Y (both in world pixel uncalibrated coordinates).
	 */
	final static public Iterable<Callable<ExportedTile>> exportTiles( final Layer layer, final int tileWidth, final int tileHeight, final boolean visible_only )
	{
		final ArrayList< Displayable > patches = layer.getDisplayables( Patch.class, visible_only );
		// If the Layer lacks images, return an empty sequence.
		if ( patches.isEmpty() )
		{
			return Collections.emptyList();
		}

		/* calculate intensity transfer */
		final ArrayList< PatchIntensityRange > patchIntensityRanges = new ArrayList< PatchIntensityRange >();
		double min_ = Double.MAX_VALUE;
		double max_ = -Double.MAX_VALUE;
		for ( final Displayable d : patches )
		{
			final Patch patch = ( Patch )d;
			final PatchIntensityRange pir = new PatchIntensityRange( patch );
			if ( pir.min < min_ )
				min_ = pir.min;
			if ( pir.max > max_ )
				max_ = pir.max;
			patchIntensityRanges.add( pir );
		}

		final double min = min_;
		final double max = max_;

		/* Create lazy sequence that creates Callable instances. */

		final Rectangle box = layer.getMinimalBoundingBox( Patch.class, visible_only )
		                      .intersection( layer.getParent().get2DBounds() );
		final int nCols = ( int )Math.ceil( box.width / (double)tileWidth );
		final int nRows = ( int )Math.ceil( box.height / (double)tileHeight );
		final double minI = -min * 65535.0 / ( max - min );
		final double maxI = ( 1.0 - min ) * 65535.0 / ( max - min );

		//ij.IJ.log("min, max: " + min + ", " + max + ",    minI, maxI: " + minI + ", " + maxI);

		return new Iterable<Callable<ExportedTile>>()
		{
			@Override
			public Iterator<Callable<ExportedTile>> iterator() {
				return new Iterator<Callable<ExportedTile>>() {
					// Internal state
					private int row = 0,
					            col = 0,
					            x0 = box.x,
					            y0 = box.y;
					private final ArrayList< PatchIntensityRange > ps = new ArrayList< PatchIntensityRange >();

					{
						// Constructor body. Prepare to be able to answer "hasNext()"
						findNext();
					}

					private final void findNext() {
						// Iterate until finding a tile that intersects one or more patches
						ps.clear();
						while (true)
						{
							if (nRows == row) {
								// End of domain
								break;
							}

							x0 = box.x + col * tileWidth;
							y0 = box.y + row * tileHeight;
							final Rectangle tileBounds = new Rectangle( x0, y0, tileWidth, tileHeight );

							for ( final PatchIntensityRange pir : patchIntensityRanges )
							{
								if ( pir.patch.getBoundingBox().intersects( tileBounds ) )
								{
									ps.add( pir );
								}
							}

							// Prepare next iteration
							col += 1;
							if (nCols == col) {
								col = 0;
								row += 1;
							}

							if ( ps.size() > 0 )
							{
								// Ready for next iteration
								break;
							}
						}
					}

					@Override
					public boolean hasNext()
					{
						return ps.size() > 0;
					}

					@Override
					public Callable<ExportedTile> next()
					{
						// Capture state locally
						final ArrayList< PatchIntensityRange > pirs = new ArrayList< PatchIntensityRange >( ps );
						final int x = x0;
						final int y = y0;
						// Advance
						findNext();

						return new Callable<ExportedTile>()
						{

							@Override
							public ExportedTile call()
									throws Exception {
								final ShortProcessor sp = new ShortProcessor( tileWidth, tileHeight );
								sp.setMinAndMax( minI, maxI );

								for ( final PatchIntensityRange pir : pirs )
								{
									map( new PatchTransform( pir ), x, y, mapIntensities( pir, min, max ), sp );
								}

								return new ExportedTile( sp, x, y, minI, maxI );
							}
						};
					}

					@Override
					public void remove()
					{
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}

	/** Create a flat image into which Patch instances are transferred considering their min and max values.
	 *
	 * @param patches
	 * @param roi
	 * @return
	 */
	static public final ShortProcessor makeFlatImage(final List<Patch> patches, final Rectangle roi) {
		return makeFlatImage(patches, roi, 0, Double.NaN);
	}
	
	static public final ShortProcessor makeFlatImage(final List<Patch> patches, final Rectangle roi, final double backgroundValue) {
		return makeFlatImage(patches, roi, backgroundValue, Double.NaN);
	}

	/**
	 * 
	 * @param patches
	 * @param roi
	 * @param backgroundValue
	 * @param scale Ignored when NaN.
	 * @return
	 */
	static public final ShortProcessor makeFlatImage(final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale) {
		return makeFlatImage( patches, roi, backgroundValue, scale, false ).a;
	}

	static public final Pair< ShortProcessor, ByteProcessor > makeFlatImage(final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale, final boolean makeAlphaMask) {
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

		final double minI = -min * 65535.0 / ( max - min );
		final double maxI = ( 1.0 - min ) * 65535.0 / ( max - min );

		final ShortProcessor sp;
		
		if ( Double.isNaN( scale ) ) {
			sp = new ShortProcessor( roi.width, roi.height );
		} else {
			sp = new ShortProcessor( (int)(roi.width * scale + 0.5), (int)(roi.height * scale + 0.5) );
		}

		sp.setMinAndMax( minI, maxI );
		if (0 != backgroundValue) {
			sp.setValue(backgroundValue);
			sp.setRoi(0, 0, roi.width, roi.height);
			sp.fill();
		}
		
		final ByteProcessor alphaTarget = makeAlphaMask ? new ByteProcessor( sp.getWidth(), sp.getHeight() ) : null;

		for ( final PatchIntensityRange pir : patchIntensityRanges )
		{
			map( new PatchTransform( pir ), roi.x, roi.y, scale, mapIntensities( pir, min, max ), sp, alphaTarget );
		}

		return new Pair< ShortProcessor, ByteProcessor >( sp, alphaTarget );
	}

	/**
	 *  Returns a stack of ShortProcessor, with dimensions as in the {@code roi}.
	 * @param layers
	 * @param roi
	 * @param backgroundValue
	 * @return
	 */
	static public final ImageStack makeFlatImageStack(final List<Layer> layers, final Rectangle roi, final double backgroundValue) {
		final ImageStack stack = new ImageStack(roi.width, roi.height);
		for (final Layer layer : layers) {
			stack.addSlice("", makeFlatImage((List<Patch>)(List)layer.getDisplayables(Patch.class, true), roi, backgroundValue));
		}
		return stack;
	}
}
