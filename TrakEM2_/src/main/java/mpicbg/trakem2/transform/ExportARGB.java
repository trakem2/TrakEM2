package mpicbg.trakem2.transform;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.Arrays;
import java.util.List;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.MipMapImage;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.trakem2.util.Pair;

public class ExportARGB {
	
	static public final Pair< ColorProcessor, ByteProcessor > makeFlatImageARGB(
			final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale, final boolean use_mipmaps)
	{
		return use_mipmaps ? makeFlatImageARGBFromMipMaps( patches, roi, backgroundValue, scale )
				           : makeFlatImageARGBFromOriginals( patches, roi, backgroundValue, scale );
	}

	/**
	 * 
	 * Returns nonsense or throws an Exception if mipmaps are not available.
	 * Limited to 2GB arrays for the final image.
	 * 
	 * @param patches
	 * @param roi
	 * @param backgroundValue
	 * @param scale
	 * @return
	 */
	static public final Pair< ColorProcessor, ByteProcessor > makeFlatImageARGBFromMipMaps(
			final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale)
	{
		final ColorProcessor target = new ColorProcessor((int)(roi.width * scale), (int)(roi.height * scale));
		target.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ByteProcessor targetMask = new ByteProcessor( target.getWidth(), target.getHeight() );
		targetMask.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ImageProcessorWithMasks targets = new ImageProcessorWithMasks( target, targetMask, null );

		final Loader loader = patches.get(0).getProject().getLoader();

		for (final Patch patch : patches) {
			
			// MipMap image, already including any coordinate transforms and the alpha mask (if any), by definition.
			final MipMapImage mipMap = loader.fetchImage(patch, scale);
			
			final ColorProcessor fp = new ColorProcessor( mipMap.image );
			final ByteProcessor alpha;
			
			if ( patch.hasAlphaChannel() ) {
				alpha = new ByteProcessor( fp.getWidth(), fp.getHeight(), fp.getChannel( 4 ) );
			} else {
				// The default: full opacity
				alpha = new ByteProcessor( fp.getWidth(), fp.getHeight() );
				Arrays.fill( ( byte[] )alpha.getPixels(), (byte)255 );
			}

			// The affine to apply to the MipMap.image
			final AffineTransform atc = new AffineTransform();
			atc.scale( scale, scale );
			atc.translate( -roi.x, -roi.y );
			
			final AffineTransform at = new AffineTransform();
			at.preConcatenate( atc );
			at.concatenate( patch.getAffineTransform() );
			at.scale( mipMap.scaleX, mipMap.scaleY );
			
			final AffineModel2D aff = new AffineModel2D();
			aff.set( at );
			
			final CoordinateTransformMesh mesh = new CoordinateTransformMesh( aff, patch.getMeshResolution(), fp.getWidth(), fp.getHeight() );
			final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( mesh );
			
			fp.setInterpolationMethod( ImageProcessor.BILINEAR );
			alpha.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR ); // no interpolation
			
			mapping.mapInterpolated( new ImageProcessorWithMasks( fp, alpha, null), targets );
		}
		
		return new Pair< ColorProcessor, ByteProcessor >( target, targetMask );
	}
	
	/**
	 * Limited to 2GB arrays for the requested image.
	 * 
	 * @param patches
	 * @param roi
	 * @param backgroundValue
	 * @param scale
	 * @return
	 */
	static public final Pair< ColorProcessor, ByteProcessor > makeFlatImageARGBFromOriginals(
			final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale)
	{
		final ColorProcessor target = new ColorProcessor((int)(roi.width * scale), (int)(roi.height * scale));
		target.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ByteProcessor targetMask = new ByteProcessor( target.getWidth(), target.getHeight() );
		targetMask.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ImageProcessorWithMasks targets = new ImageProcessorWithMasks( target, targetMask, null );

		for (final Patch patch : patches) {
			final Patch.PatchImage pai = patch.createTransformedImage();
			final ColorProcessor fp = (ColorProcessor) pai.target.convertToRGB();
			final ByteProcessor alpha;
						
			if ( patch.hasAlphaChannel() ) {
				alpha = pai.mask;
				// what about the pai.outside?
			} else {
				// The default: full opacity
				alpha = new ByteProcessor( fp.getWidth(), fp.getHeight() );
				Arrays.fill( ( byte[] )alpha.getPixels(), (byte)255 );
			}

			// The affine to apply
			final AffineTransform atc = new AffineTransform();
			atc.scale( scale, scale );
			atc.translate( -roi.x, -roi.y );
			
			final AffineTransform at = new AffineTransform();
			at.preConcatenate( atc );
			at.concatenate( patch.getAffineTransform() );
			
			final AffineModel2D aff = new AffineModel2D();
			aff.set( at );
			
			final CoordinateTransformMesh mesh = new CoordinateTransformMesh( aff, patch.getMeshResolution(), fp.getWidth(), fp.getHeight() );
			final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( mesh );
			
			fp.setInterpolationMethod( ImageProcessor.BILINEAR );
			alpha.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR ); // no interpolation
			
			mapping.mapInterpolated( new ImageProcessorWithMasks( fp, alpha, null), targets );
		}
		
		return new Pair< ColorProcessor, ByteProcessor >( target, targetMask );
	}
}
