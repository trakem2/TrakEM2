package mpicbg.trakem2.transform;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
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
	
	static public final int[] extractARGBIntArray( final Image img )
	{
		final int[] pix = new int[img.getWidth(null) * img.getHeight(null)];
		PixelGrabber pg = new PixelGrabber( img, 0, 0, img.getWidth(null), img.getHeight(null), pix, 0, img.getWidth(null) );
		try {
			pg.grabPixels();
		} catch (InterruptedException ie) {}
		return pix;
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
			
			// Work-around strange bug that makes mipmap-loaded images paint with 7-bit depth instead of 8-bit depth
			final BufferedImage bi = new BufferedImage(mipMap.image.getWidth(null), mipMap.image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			final Graphics2D g = bi.createGraphics();
			g.drawImage(mipMap.image, 0, 0, null);
			g.dispose();
			
			final int[] pix = extractARGBIntArray(bi);
			final ColorProcessor fp = new ColorProcessor( mipMap.image.getWidth(null), mipMap.image.getHeight(null), pix );
			final ByteProcessor alpha;
			
			bi.flush();
			
			if ( patch.hasAlphaChannel() ) {
				// ERROR TODO: for some reason, the alpha channel is not present in the mipmap? Neither in mipMap.image nor in bi.
				// TODO: likely explanation is that mipmaps have the alpha pre-multiplied.
				//       Mipmaps should not be used for this when there are alpha masks (either a proper alpha mask or an outside from a CoordinateTransform).
				final byte[] pixa = new byte[pix.length];
				for (int i=0; i<pixa.length; ++i) pixa[i] = (byte)((pix[i] & 0xff000000) >> 24);
				alpha = new ByteProcessor( fp.getWidth(), fp.getHeight(), pixa ); // fp.getChannel( 4 ) does not include the alpha for some reason
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
				// One of the two must be non-null, or both are non-null
				if (null == pai.outside) alpha = pai.mask;
				else if (null == pai.mask) alpha = pai.outside;
				else {
					// combine
					final byte[] pm = (byte[]) pai.mask.getPixels(),
							     po = (byte[]) pai.mask.getPixels(),
							     pmo = new byte[pm.length];
					for (int i=0; i<pm.length; ++i) {
						// Pick the largest alpha value
						pmo[i] = (pm[i] & 0xff) > (po[i] & 0xff) ? pm[i] : po[i];
					}
					alpha = new ByteProcessor(fp.getWidth(), fp.getHeight(), pmo);
				}
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
