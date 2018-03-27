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
import ij.process.ShortProcessor;
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
		final int width = (int)(roi.width * scale);
		final int height = (int)(roi.height * scale);
		// Process the three channels separately in order to use proper alpha composition
		final ShortProcessor[] target = new ShortProcessor[3];
		for (int i=0; i<3; ++i) {
			target[i] = new ShortProcessor( width, height );
			target[i].setInterpolationMethod( ImageProcessor.BILINEAR );
		}
		final ByteProcessor targetMask = new ByteProcessor( width, height );
		targetMask.setInterpolationMethod( ImageProcessor.BILINEAR );

		final Loader loader = patches.get(0).getProject().getLoader();

		for (final Patch patch : patches) {
			
			// MipMap image, already including any coordinate transforms and the alpha mask (if any), by definition.
			final MipMapImage mipMap = loader.fetchImage(patch, scale);
			
			/// DEBUG: is there an alpha channel at all?
			//new ij.ImagePlus("alpha of " + patch.getTitle(), new ByteProcessor( mipMap.image.getWidth(null), mipMap.image.getHeight(null), new ColorProcessor( mipMap.image ).getChannel( 4 ))).show();
			// Yes, there is, even though the mipmap images have the alpha pre-multiplied
			
			// Work-around strange bug that makes mipmap-loaded images paint with 7-bit depth instead of 8-bit depth
			final BufferedImage bi = new BufferedImage(mipMap.image.getWidth(null), mipMap.image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			final Graphics2D g2d = bi.createGraphics();
			g2d.drawImage(mipMap.image, 0, 0, null);
			g2d.dispose();
			
			final int[] pix = extractARGBIntArray(bi);
			bi.flush();
			
			// DEBUG: does the BufferedImage have the alpha channel?
			//{
			//	final byte[] aa = new byte[pix.length];
			//	for (int i=0; i<aa.length; ++i) aa[i] = (byte)((pix[i] & 0xff000000) >> 24);
			//	new ij.ImagePlus("alpha of BI of " + patch.getTitle(), new ByteProcessor(bi.getWidth(), bi.getHeight(), aa)).show();
			//}
			// YES: the alpha, containing the outside too. All fine.
			
			final ByteProcessor alpha;
			final ShortProcessor[] rgb = new ShortProcessor[3];
			
			if ( patch.hasAlphaChannel() ) {
				// The mipMap has the alpha channel in it, even if the alpha is pre-multiplied as well onto the images.
				final byte[]  a = new byte[pix.length];
				final short[] r = new short[pix.length],
					          g = new short[pix.length],
					          b = new short[pix.length];
				for (int i=0; i<a.length; ++i) {
					a[i] = (byte )((pix[i] & 0xff000000) >> 24);
					r[i] = (short)((pix[i] & 0x00ff0000) >> 16);
					g[i] = (short)((pix[i] & 0x0000ff00) >>  8);
					b[i] = (short)( pix[i] & 0x000000ff       );
				}
				alpha = new ByteProcessor(bi.getWidth(), bi.getHeight(), a);
				rgb[0] = new ShortProcessor(bi.getWidth(), bi.getHeight(), r, null);
				rgb[1] = new ShortProcessor(bi.getWidth(), bi.getHeight(), g, null);
				rgb[2] = new ShortProcessor(bi.getWidth(), bi.getHeight(), b, null);
			} else {
				final byte[]  a = new byte[pix.length];
				final short[] r = new short[pix.length],
					          g = new short[pix.length],
					          b = new short[pix.length];
				for (int i=0; i<a.length; ++i) {
					a[i] = (byte)255; // full opacity
					r[i] = (short)((pix[i] & 0x00ff0000) >> 16);
					g[i] = (short)((pix[i] & 0x0000ff00) >>  8);
					b[i] = (short)( pix[i] & 0x000000ff       );
				}
				alpha = new ByteProcessor(bi.getWidth(), bi.getHeight(), a);
				rgb[0] = new ShortProcessor(bi.getWidth(), bi.getHeight(), r, null);
				rgb[1] = new ShortProcessor(bi.getWidth(), bi.getHeight(), g, null);
				rgb[2] = new ShortProcessor(bi.getWidth(), bi.getHeight(), b, null);
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
			
			final CoordinateTransformMesh mesh = new CoordinateTransformMesh( aff, patch.getMeshResolution(), bi.getWidth(), bi.getHeight() );
			final TransformMeshMappingWithMasks< CoordinateTransformMesh > mapping = new TransformMeshMappingWithMasks< CoordinateTransformMesh >( mesh );
			
			alpha.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR ); // no interpolation
			for (int i=0; i<3; ++i) {
				rgb[i].setInterpolationMethod( ImageProcessor.BILINEAR );
				mapping.map(rgb[i], alpha, target[i]); // with interpolation and alpha composition
				mapping.map(alpha, targetMask); // without interpolation
			}
		}
		
		final int[] pix = new int[width * height];
		final short[] rp = (short[]) target[0].getPixels();
		final short[] gp = (short[]) target[1].getPixels();
		final short[] bp = (short[]) target[2].getPixels();
		
		for (int i=0; i<pix.length; ++i) {
			int r = Math.min(Math.max(rp[i], 0), 255),
			    g = Math.min(Math.max(gp[i], 0), 255),
			    b = Math.min(Math.max(bp[i], 0), 255);
			pix[i] = (r << 16) | (g << 8) | b;
		}
		
		return new Pair< ColorProcessor, ByteProcessor >( new ColorProcessor(width, height, pix), targetMask );
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
		final ByteProcessor outsideMask = new ByteProcessor( target.getWidth(), target.getHeight() );
		outsideMask.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ImageProcessorWithMasks targets = new ImageProcessorWithMasks( target, targetMask, outsideMask );

		for (final Patch patch : patches) {
			final Patch.PatchImage pai = patch.createTransformedImage();
			final ColorProcessor fp = (ColorProcessor) pai.target.convertToRGB();
			final ByteProcessor alpha;
			final ByteProcessor outside;
			
			System.out.println("IMAGE:" + patch.getTitle());
			System.out.println("mask: " + pai.mask);
			System.out.println("outside: " + pai.outside);
			
			if ( null == pai.mask ) {
				alpha = new ByteProcessor( fp.getWidth(), fp.getHeight() );
				Arrays.fill( ( byte[] )alpha.getPixels(), (byte)255 ); // fully opaque
			} else {
				alpha = pai.mask;
			}
			
			if ( null == pai.outside ) {
				outside = new ByteProcessor( fp.getWidth(), fp.getHeight() );
				Arrays.fill( ( byte[] )outside.getPixels(), (byte)0 ); // fully transparent
			} else {
				outside = pai.outside;
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
			
			mapping.mapInterpolated( new ImageProcessorWithMasks( fp, alpha, outside ), targets );
		}
		
		return new Pair< ColorProcessor, ByteProcessor >( target, targetMask );
	}
}
