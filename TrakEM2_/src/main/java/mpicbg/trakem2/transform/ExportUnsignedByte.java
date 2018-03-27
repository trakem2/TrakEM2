package mpicbg.trakem2.transform;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.MipMapImage;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.trakem2.util.Pair;

public class ExportUnsignedByte
{
	/** Works only when mipmaps are available, returning nonsense otherwise. */
	static public final Pair< ByteProcessor, ByteProcessor > makeFlatImage(final List<Patch> patches, final Rectangle roi, final double backgroundValue, final double scale)
	{
		final ByteProcessor target = new ByteProcessor((int)(roi.width * scale), (int)(roi.height * scale));
		target.setInterpolationMethod( ImageProcessor.BILINEAR );
		final ByteProcessor targetMask = new ByteProcessor( target.getWidth(), target.getHeight() );
		targetMask.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR );

		final Loader loader = patches.get(0).getProject().getLoader();

		for (final Patch patch : patches) {
			// MipMap image, already including any coordinate transforms and the alpha mask (if any), by definition.
			final MipMapImage mipMap = loader.fetchImage(patch, scale);

			// Place the mipMap data into FloatProcessors
			final ByteProcessor fp; // new ByteProcessor(mipMap.image.getWidth( null ), mipMap.image.getHeight( null ));
			final ByteProcessor alpha;
			
			// Transfer pixels to a grey image (avoids incorrect readings for ARGB images that end up cropping down to 7-bit)
			final BufferedImage bi = new BufferedImage(mipMap.image.getWidth(null), mipMap.image.getHeight(null), BufferedImage.TYPE_BYTE_GRAY);
			final Graphics2D g = bi.createGraphics();
			g.drawImage(mipMap.image, 0, 0, null);
			g.dispose();
			
			// Extract pixels from grey image and copy into fp
			fp = new ByteProcessor( bi );

			// Extract the alpha channel from the mipmap, if any
			if ( patch.hasAlphaChannel() )
			{
				alpha = new ColorProcessor( mipMap.image ).getChannel( 4, null );
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
			
			mapping.map( fp, alpha, target, targetMask );
		}
		
		return new Pair< ByteProcessor, ByteProcessor >( target, targetMask );
	}
}
