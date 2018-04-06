package mpicbg.trakem2.transform;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.PixelGrabber;
import java.util.List;

import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;
import mpicbg.trakem2.util.Pair;

public class ExportBestFlatImage
{
	
	public final List<Patch> patches;
	public final Rectangle finalBox;
	public final int backgroundValue;
	public final double scale;
	public final double largest_possibly_needed_area;
	public final double max_possible_area;
	public final double scaleUP;
	
	protected final Loader loader;

	/**
     * Class to manage the creation of an ImageProcessor containing a flat 8-bit or RGB image for use in e.g. extracting features,
     * and optionally do so along with its alpha mask as another ImageProcessor.
     * 
     * If the image is smaller than 0.5 GB, then the AWT system in Loader.getFlatAWTImage will be used, with the quality flag as true.
     * Otherwise, mipmaps will be used to generate an image with ExportUnsignedByte.makeFlatImage.
     * 
     * If mipmaps are not available, then an up to 2GB image will be generated with ExportUnsignedShort (which respects pixel intensity mappings)
     * and then Gaussian-downsampled to the requested dimensions.
     * 
     * This method is as safe as it gets, regarding the many caveats of alpha masks, min-max, and the 2GB array indexing limit of java-8.
     * 
     * @param patches
     * @param finalBox
     * @param backgroundValue
     * @param scale
     */
	public ExportBestFlatImage(
			final List< Patch > patches,
			final Rectangle finalBox,
			final int backgroundValue,
			final double scale)
	{
		this.patches = patches;
		this.finalBox = finalBox;
		this.backgroundValue = backgroundValue;
		this.scale = scale;
		
		this.loader = patches.get(0).getProject().getLoader();
    	
    	// Determine the scale corresponding to the calculated max_area,
    	// with a correction factor to make sure width * height never go above pow(2, 31)
    	// (Only makes sense, and will only be used, if area is smaller than max_area.)
		this.largest_possibly_needed_area = ((double)finalBox.width) * ((double)finalBox.height);
    	this.max_possible_area = Math.min( this.largest_possibly_needed_area, Math.pow(2, 31) );
    	this.scaleUP = Math.min(1.0, Math.sqrt( this.max_possible_area / this.largest_possibly_needed_area ) ) - Math.max( 1.0 / finalBox.width, 1.0 / finalBox.height );
    }
	
	/**
	 * @return Whether an AWT image can be used: the requested area must be smaller than 0.5 GB,
	 * so that with the quality flag, the interim image is smaller than 2 GB (2x larger on the side).
	 */
	public boolean canUseAWTImage() {
		return (((long)finalBox.width) * ((long)finalBox.height)) < Math.pow( 2, 29 ) && loader.isMipMapsRegenerationEnabled(); // smaller than 0.5 GB: so up to 2 GB with quality flag on
	}
	
	/**
	 * @return Whether the requested image fits in an array up to 2 GB in size.
	 */
	public boolean isSmallerThan2GB() {
		return finalBox.width * scale * finalBox.height * scale <= Math.pow(2, 31);
	}
	
	public void printInfo() {
		System.out.println( "###\nExportBestFlatImage dimensions and quality scale " );
    	System.out.println( "srcRect w,h: " + finalBox.width + ", " + finalBox.height );
    	System.out.println( "area: " + largest_possibly_needed_area );
    	System.out.println( "max_area: " + max_possible_area );
    	System.out.println( "scale: " + scale );
    	System.out.println( "scaleUP: " + scaleUP );
	}
	
	protected FloatProcessor convertToFloat( final ShortProcessor sp )
	{	
		final short[] pixS = (short[]) sp.getPixels();
		loader.releaseToFit( pixS.length * 4 );
		final float[] pixF = new float[pixS.length];
		
		for ( int i=0; i<pixS.length; ++i) {
			pixF[i] = pixS[i] & 0xffff;
		}

		return new FloatProcessor( sp.getWidth(), sp.getHeight(), pixF );
	}
	
	public double computeSigma( final int width, final int height ) {
		final double max_dimension_source = Math.max( width, height );
    	final double max_dimension_target = Math.max( ( int ) (finalBox.width  * scale ),
    												  ( int ) (finalBox.height * scale ) );
    	final double s = 0.5; // same sigma for source and target
    	final double sigma = s * max_dimension_source / max_dimension_target - s * s ;
    	
    	return sigma;
	}
	
	/**
	 * Gaussian-downsample to the target dimensions
	 * @param ip
	 * @return
	 */
	protected FloatProcessor gaussianDownsampled( final FloatProcessor ip )
	{
		loader.releaseToFit( ( (float[])ip.getPixels() ).length * 2 );

    	// Gaussian-downsample
    	final double sigma = computeSigma( ip.getWidth(), ip.getHeight() );

    	Utils.log("Gaussian downsampling. If this is slow, check the number of threads in the plugin preferences.");
    	new GaussianBlur().blurFloat( ip, sigma, sigma, 0.0002 );

    	ip.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR );

    	return (FloatProcessor) ip.resize( ( int ) Math.ceil( finalBox.width * scale ) );
	}
	
	/**
	 * Create a java.awt.Image using the Loader.getFlatAWTImage method, using 'true' for the quality flag,
	 * which means than an image 2x larger on each side will be generated and then scaled down by area averaging.
	 * Uses mipmaps, which are Gaussian-downsampled already.
	 * 
	 *  @param type Either ImagePlus.GRAY8 or ImagePlus.COLOR_RGB
	 */
	protected Image createAWTImage( final int type )
	{
		return loader.getFlatAWTImage( patches.get(0).getLayer(), finalBox, scale, -1, type,
    				Patch.class, patches, true, Color.black, null );
	}
	
	public Pair<ColorProcessor, ByteProcessor> makeFlatColorImage()
	{
		printInfo();
		
		if ( canUseAWTImage() ) { // less than 0.5 GB array size
			final ColorProcessor cp = new ColorProcessor( createAWTImage( ImagePlus.COLOR_RGB ) );
			final ByteProcessor alpha = new ByteProcessor( cp.getWidth(), cp.getHeight(), cp.getChannel( 4 ) );
			return new Pair<ColorProcessor, ByteProcessor>( cp, alpha );
		}
		
		if ( !isSmallerThan2GB() ) {
			Utils.log("Cannot create an image larger than 2 GB.");
			return null;
		}
		
		if ( loader.isMipMapsRegenerationEnabled() )
		{
			return ExportARGB.makeFlatImageARGBFromMipMaps( patches, finalBox, 0, scale );
		}
		
		// No mipmaps: create an image as large as possible, then downsample it
		final Pair<ColorProcessor, ByteProcessor> pair = ExportARGB.makeFlatImageARGBFromOriginals( patches, finalBox, 0, scaleUP );
		
		final double sigma = computeSigma( pair.a.getWidth(), pair.a.getHeight());
		new GaussianBlur().blurGaussian( pair.a, sigma, sigma, 0.0002 );
		new GaussianBlur().blurGaussian( pair.b, sigma, sigma, 0.0002 );
		return pair;
	}

	/**
	 * 
     * @return null when the dimensions make the array larger than 2GB, or the image otherwise.
    */
	public ByteProcessor makeFlatGrayImage()
	{
		printInfo();
		
		if ( canUseAWTImage() ) {
			return new ByteProcessor( createAWTImage( ImagePlus.GRAY8 ) );
		}
		
		if ( !isSmallerThan2GB() ) {
			Utils.log("Cannot create an image larger than 2 GB.");
			return null;
		}
		
		if ( loader.isMipMapsRegenerationEnabled() )
		{
			// Use mipmaps directly: they are already Gaussian-downsampled
			// (TODO waste: generates an alpha mask that is then not used)
			return ExportUnsignedByte.makeFlatImageFromMipMaps( patches, finalBox, 0, scale ).a;
		}
		
		// Else: no mipmaps
    	
    	// Generate an image at the upper scale
    	// using ExportUnsignedShort which works without mipmaps and allows not generating an alpha
		// and then Gaussian downsampling to the target dimensions

    	return gaussianDownsampled(
    			convertToFloat(
    					ExportUnsignedShort.makeFlatImage( patches, finalBox, 0, scaleUP, false ).a
    			)
    		   ).convertToByteProcessor();
	}
	
	/**
	 * @return Return null when dimensions make the array larger than 2GB.
	 */
	public Pair<ByteProcessor, ByteProcessor> makeFlatGrayImageAndAlpha()
	{		
		printInfo();

		if ( canUseAWTImage() ) {
			final Image img = createAWTImage( ImagePlus.COLOR_RGB ); // In color to preserve the alpha channel present in mipmaps
			final int width = img.getWidth(null);
			final int height = img.getHeight(null);
			final int[] pixels = new int[width * height];
			
			PixelGrabber pg = new PixelGrabber(img, 0, 0, width, height, pixels, 0, width);
			try {
				pg.grabPixels();
			} catch (InterruptedException e){};
			
			final byte[] grey =  new byte[pixels.length];
			final byte[] alpha = new byte[pixels.length];
			
			for (int i=0; i< pixels.length; ++i) {
				final int p = pixels[i];
				alpha[i] = (byte) ((p & 0xff000000) >> 24);
				grey[i] =  (byte)((((p & 0x00ff0000) >> 16)
                                 + ((p & 0x0000ff00) >>  8)
                                 +  (p & 0x000000ff       ) ) / 3f);
			}
			
			return new Pair<ByteProcessor, ByteProcessor>(
					new ByteProcessor(width, height, grey, null ),
					new ByteProcessor( width, height, alpha, null ) );
		}
		
		if ( !isSmallerThan2GB() ) {
			Utils.log("Cannot create an image larger than 2 GB.");
			return null;
		}
		
		if ( loader.isMipMapsRegenerationEnabled() )
		{
			// Use mipmaps directly: they are already Gaussian-downsampled
			return ExportUnsignedByte.makeFlatImageFromMipMaps( patches, finalBox, 0, scale );
		}
		
		// Else: no mipmaps


		// Generate an image at the upper scale
		// using ExportUnsignedShort which works without mipmaps and considers the pixel intensities and min, max of each Patch
		// and then Gaussian downsampling to the target dimensions

		// Double width, not double height: 2 images
		loader.releaseAll();
		
		final Pair<ShortProcessor, ByteProcessor> pair = ExportUnsignedShort.makeFlatImage( patches, finalBox, 0, scaleUP, true );
		
		loader.releaseAll();
		
		// Try to dereference memory as soon as possible to minimize use
		FloatProcessor fp = pair.a.convertToFloatProcessor();
		pair.a.setPixels( null );
		FloatProcessor gd = gaussianDownsampled( fp );
		fp.setPixels( null );
		fp = null;
		final ByteProcessor bp = gd.convertToByteProcessor( true );
		gd.setPixels( null );
		gd = null;
		
		FloatProcessor alpha = pair.b.convertToFloatProcessor();
		pair.b.setPixels( null );
		FloatProcessor alpha_gd = gaussianDownsampled( alpha );
		alpha.setPixels( null );
		alpha = null;
		final ByteProcessor alpha_bp = alpha_gd.convertToByteProcessor( true );
		alpha_gd.setPixels( null );
		alpha_gd = null;
		
		return new Pair<ByteProcessor, ByteProcessor>( bp, alpha_bp );
	}
	
	/**
	 * While the data is in the 8-bit range, the format is as a FloatProcessor.
	 */
	public Pair<FloatProcessor, FloatProcessor> makeFlatFloatGrayImageAndAlpha()
	{		
		printInfo();

		if ( canUseAWTImage() ) {
			final Image img = createAWTImage( ImagePlus.COLOR_RGB ); // In color to preserve the alpha channel present in mipmaps
			final int width = img.getWidth(null);
			final int height = img.getHeight(null);
			final int[] pixels = new int[width * height];
			
			PixelGrabber pg = new PixelGrabber(img, 0, 0, width, height, pixels, 0, width);
			try {
				pg.grabPixels();
			} catch (InterruptedException e){};
			
			final float[] grey =  new float[pixels.length];
			final float[] alpha = new float[pixels.length];
			
			for (int i=0; i< pixels.length; ++i) {
				final int p = pixels[i];
				alpha[i] = ((p & 0xff000000) >> 24);
				grey[i] = (((p & 0x00ff0000) >> 16)
                         + ((p & 0x0000ff00) >>  8)
                         +  (p & 0x000000ff       ) ) / 3f;
			}
			
			return new Pair<FloatProcessor, FloatProcessor>(
					new FloatProcessor(width, height, grey, null ),
					new FloatProcessor( width, height, alpha, null ) );
		}
		
		if ( !isSmallerThan2GB() ) {
			Utils.log("Cannot create an image larger than 2 GB.");
			return null;
		}
		
		if ( loader.isMipMapsRegenerationEnabled() )
		{
			// Use mipmaps directly: they are already Gaussian-downsampled
			final Pair<ByteProcessor, ByteProcessor> pair = ExportUnsignedByte.makeFlatImageFromMipMaps( patches, finalBox, 0, scale );
			return new Pair<FloatProcessor, FloatProcessor>(
					pair.a.convertToFloatProcessor(),
					pair.b.convertToFloatProcessor() );
		}
		
		// Else: no mipmaps


		// Generate an image at the upper scale
		// using ExportUnsignedShort which works without mipmaps and considers the pixel intensities and min, max of each Patch
		// and then Gaussian downsampling to the target dimensions

		// Double width, not double height: 2 images
		loader.releaseAll();
		
		
		// TODO problem: takes a long time to map the intensities
		// Instead, it should map images to 8-bit and make a flat image from that
		final Pair<ShortProcessor, ByteProcessor> pair = ExportUnsignedShort.makeFlatImage( patches, finalBox, 0, scaleUP, true );
		
		loader.releaseAll();
		
		// Convert image channel to 8-bit range, scaled
		// Unpacked to dereference as much memory as possible as soon as possible
		FloatProcessor fp = pair.a.convertToFloatProcessor();
		pair.a.setPixels( null ); // clear memory
		FloatProcessor gd = gaussianDownsampled( fp );
		fp.setPixels( null );
		fp = null;
		gd.findMinAndMax();
		final double min = gd.getMin(),
			         max = gd.getMax(),
			         scale = 256.0 / (max - min + 1);
		final float[] pix = (float[])gd.getPixels();
		double value;
		for (int i=0; i<pix.length; i++) {
			value = pix[i] - min;
			if (value < 0) value = 0;
			value = value * scale;
			if (value > 255) value = 255;
			pix[i] = (float) value;
		}
		
		FloatProcessor alpha_fp = pair.b.convertToFloatProcessor();
		pair.b.setPixels( null );
		FloatProcessor alpha_gd = gaussianDownsampled( alpha_fp );
		alpha_fp.setPixels( null );
		alpha_fp = null;
		
		return new Pair<FloatProcessor, FloatProcessor>( gd, alpha_gd );
	}
}
