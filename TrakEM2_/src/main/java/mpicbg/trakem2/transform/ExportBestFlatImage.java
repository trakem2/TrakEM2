package mpicbg.trakem2.transform;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.PixelGrabber;
import java.util.List;

import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.ByteProcessor;
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
	public final double area;
	public final double max_area;
	public final double scaleUP;
	
	protected final Loader loader;
	protected final long arraySize;

	/**
     * Class to manage the creation of a FloatProcessor containing a flat 8-bit image for use in e.g. extracting features,
     * and optionally do so along with its alpha mask, also as a FloatProcessor.
     * 
     * If the image is smaller than 0.5 GB, then the AWT system in Loader.getFlatAWTImage will be used, with the quality flag as true.
     * Otherwise, mipmaps will be used to generate an image with ExportUnsignedByte.makeFlatImageFloat or ExportUnsignedShort.makeFlatImage.
     * 
     * If mipmaps are not available, then an up to 2GB image will be generated with ExportUnsignedByte and then Gaussian-downsampled to the requested dimensions.
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
    	this.arraySize = finalBox.width * finalBox.height;
    	
    	// Determine the largest size to work with
    	this.area = ((double)finalBox.width) * ((double)finalBox.height);
    	this.max_area = Math.min( this.area, Math.pow(2, 31) );

    	// Determine the scale corresponding to the calculated max_area,
    	// with a correction factor to make sure width * height never go above pow(2, 31)
    	this.scaleUP = Math.min(1.0, Math.sqrt( this.max_area / this.area ) ) - Math.max( 1.0 / finalBox.width, 1.0 / finalBox.height );
	}
	
	public boolean canUseAWTImage() {
		return arraySize < Math.pow( 2, 29 ) && loader.isMipMapsRegenerationEnabled(); // 0.5 GB
	}
	
	public boolean isSmallerThan2GB() {
		return finalBox.width * scale * finalBox.height * scale > Math.pow(2, 31);
	}
	
	public void printInfo() {
		System.out.println( "###\nExportBestFlatImage dimensions and quality scale " );
    	System.out.println( "srcRect w,h: " + finalBox.width + ", " + finalBox.height );
    	System.out.println( "area: " + area );
    	System.out.println( "max_area: " + max_area );
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
	
	protected double computeSigma( final int width, final int height ) {
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
		loader.releaseAll();

    	// Gaussian-downsample
    	final double sigma = computeSigma( ip.getWidth(), ip.getHeight() );

    	Utils.log("Gaussian downsampling. If this is slow, check the number of threads in the plugin preferences.");
    	new GaussianBlur().blurFloat( ip, sigma, sigma, 0.0002 );

    	ip.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR );

    	return (FloatProcessor) ip.resize( ( int ) Math.ceil( finalBox.width * scale ) );
	}
	
	protected Image createAWTImage( final int type )
	{
		return loader.getFlatAWTImage( patches.get(0).getLayer(), finalBox, scale, -1, type,
    				Patch.class, patches, true, Color.black, null );
	}

	/**
	 * 
     * @return null when the dimensions make the array larger than 2GB, or the image otherwise.
    */
	public FloatProcessor makeFlatGrayImage()
	{
		if ( canUseAWTImage() ) {
			return (FloatProcessor) new ByteProcessor( createAWTImage( ImagePlus.GRAY8 ) ).convertToFloat();
		}
		
		if ( !isSmallerThan2GB() ) {
			Utils.log("Cannot create an image larger than 2 GB.");
			return null;
		}
		
		if ( loader.isMipMapsRegenerationEnabled() )
		{
			// Use mipmaps directly: they are already Gaussian-downsampled
			// (TODO waste: generates an alpha mask that is then not used)
			return ExportUnsignedByte.makeFlatImageFloat( patches, finalBox, 0, scale ).a;
		}
		
		// Else: no mipmaps
		
		printInfo();
    	
    	// Generate an image at the upper scale
    	// using ExportUnsignedShort which works without mipmaps and allows not generating an alpha
		// and then Gaussian downsampling to the target dimensions

    	return gaussianDownsampled( convertToFloat( ExportUnsignedShort.makeFlatImage( patches, finalBox, 0, scaleUP, false ).a ) );
	}
	
	/**
	 * @return Both the image and its alpha mask, as FloatProcessor instances, or null when dimensions make the array larger than 2GB.
	 */
	public Pair<FloatProcessor, FloatProcessor> makeFlatGrayImageAndAlpha()
	{
		if ( canUseAWTImage() ) {
			final Image img = createAWTImage( ImagePlus.COLOR_RGB );
			final int width = img.getWidth(null);
			final int height = img.getHeight(null);
			final int[] pixels = new int[width * height];
			
			PixelGrabber pg = new PixelGrabber(img, 0, 0, width, height, pixels, 0, width);
			try {
				pg.grabPixels();
			} catch (InterruptedException e){};
			
			final float[] grey = new float[pixels.length];
			final float[] alpha = new float[pixels.length];
			
			for (int i=0; i< pixels.length; ++i) {
				final int p = pixels[i];
				alpha[i] =   (p & 0xff000000) >> 24;
				grey[i] =  (((p & 0x00ff0000) >> 16)
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
			return ExportUnsignedByte.makeFlatImageFloat( patches, finalBox, 0, scale );
		}
		
		// Else: no mipmaps
		
		printInfo();

		// Generate an image at the upper scale
		// using ExportUnsignedShort which works without mipmaps
		// and then Gaussian downsampling to the target dimensions

		// Double width, not double height: 2 images
		loader.releaseAll();
		
		final Pair<ShortProcessor, ByteProcessor> pair = ExportUnsignedShort.makeFlatImage( patches, finalBox, 0, scaleUP, true );
		return new Pair<FloatProcessor, FloatProcessor>(
				gaussianDownsampled( (FloatProcessor) pair.a.convertToFloat() ),
				gaussianDownsampled( (FloatProcessor) pair.b.convertToFloat() ) );
	}
}
