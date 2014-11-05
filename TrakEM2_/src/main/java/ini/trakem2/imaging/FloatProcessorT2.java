package ini.trakem2.imaging;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.utils.Utils;

import java.awt.image.ColorModel;
import java.lang.reflect.Field;

public class FloatProcessorT2 extends FloatProcessor {

	static private Field fmin, fmax, ffixedScale;

	static {
		try {
			fmin = FloatProcessor.class.getDeclaredField("min");
			fmin.setAccessible(true);
			fmax = FloatProcessor.class.getDeclaredField("max");
			fmax.setAccessible(true);
			ffixedScale = FloatProcessor.class.getDeclaredField("fixedScale");
			ffixedScale.setAccessible(true);
		} catch (Exception e) { e.printStackTrace(); }
	}

	public final void setMinMax(final double min, final double max) {
		try {
			fmin.set(this, (float)min);
			fmax.set(this, (float)max);
			ffixedScale.set(this, true);
			super.minMaxSet = true;
		} catch (Exception e) { e.printStackTrace(); }
	}

	/** Set pixels and change image dimensions if width and height are different than the current. */
	public final void setPixels(final int width, final int height, final float[] pixels) {
		this.width = width;
		this.height = height;
		this.roiX = 0;
		this.roiY = 0;
		this.roiWidth = width;
		this.roiHeight = height;
		super.setPixels(pixels);
	}

	public final void resizeInPlace(final int w, final int h) {
		double min = getMin();
		double max = getMax();
		this.setPixels(w, h, (float[])super.resize(w, h).getPixels());
		// set min,max again, since super.getPixels removes them
		setMinMax(min, max);
	}
	
	/**
	 * Resizes this {@link FloatProcessorT2} instance by a factor of two by
	 * picking simply all pixels at even coordinates.  The primary use for this
	 * method is downsampling after the image was smoothed with a respective
	 * Gaussian (e.g. &sigma;=sqrt(0.75) to go from 0.5 to 0.5).
	 */
	public final void halfSizeInPlace() {
		double min = getMin();
		double max = getMax();
		
		final int width2 = width + width;
		final int wb = ( width + 1 ) / 2;
		final int hb = ( height + 1 ) / 2;
		final int nb = hb * wb;
		
		final float[] aPixels = ( float[] )getPixels();
		final float[] bPixels = new float[ nb ];
		
		for ( int ya = 0, yb = 0; yb < nb; ya += width2, yb += wb )
		{
			for ( int xa = 0, xb = 0; xb < wb; xa += 2, ++xb )
			{
				bPixels[ yb + xb ] = aPixels[ ya + xa ];
			}
		}
		
		setPixels( wb, hb, bPixels );
		
		// set min,max again, since super.getPixels removes them
		setMinMax(min, max);
	}

	public FloatProcessorT2(final int width, final int height, final float[] pixels, final ColorModel cm) {
		super(width, height, null, cm);
		if (pixels!=null && width*height!=pixels.length)
			throw new IllegalArgumentException("width*height!=pixels.length");
		this.width = width;
		this.height = height;
		setPixels(pixels); //this.pixels = pixels;
		this.cm = cm;
		resetRoi();
		//if (pixels!=null)
		//	findMinAndMax();
	}
	public FloatProcessorT2(final int width, final int height, final float[] pixels, final ColorModel cm, final double min, final double max) {
		this(width, height, pixels, cm);
		setMinMax(min, max);
	}
	public FloatProcessorT2(final int width, final int height, final double min, final double max) {
		this(width, height, new float[width*height], null);
		setMinMax(min, max);
	}
	public FloatProcessorT2(final int width, final int height) {
		this(width, height, 0, 0);
	}
	public FloatProcessorT2(final int width, final int height, final byte[] pix, final double min, final double max) {
		this(width, height, new float[width*height], null, min, max);
		final float[] pixels = (float[])getPixels(); // I luv pointlessly private fields
		for (int i=0; i<pix.length; i++) {
			pixels[i] = (pix[i]&0xff);
		}
	}
	public FloatProcessorT2(final ColorProcessor cp, final int channel) {
		this(cp.getWidth(), cp.getHeight(), 0, 255);
		final int[] c = (int[])cp.getPixels();
		int bitmask = 0;
		int shift = 0;
		switch (channel) {
			case 0: bitmask = 0x00ff0000; shift = 16; break; // red
			case 1: bitmask = 0x0000ff00; shift =  8; break; // green
			case 2: bitmask = 0x000000ff; break; // blue
		}
		final float[] pixels = (float[])this.getPixels(); // I luv pointlessly private fields
		for (int i=0; i<pixels.length; i++) pixels[i] = ((c[i] & bitmask)>>shift);
		super.setMinAndMax(0, 255); // we know them
	}
	public FloatProcessorT2(final FloatProcessor fp) {
		this(fp.getWidth(), fp.getHeight(), (float[])fp.getPixels(), fp.getColorModel(), fp.getMin(), fp.getMax());
	}
	public FloatProcessorT2(final ByteProcessor bp) {
		this((FloatProcessor)bp.convertToFloat());
		super.setMinAndMax(0, 255); // to avoid looking for it when min,max are perfectly known.
	}

	/** Returns a new, blank FloatProcessor with the specified width and height. */
	public ImageProcessor createProcessor(final int width, final int height) {
		ImageProcessor ip2 = new FloatProcessorT2(width, height, new float[width*height], getColorModel());
		return ip2;
	}
	public void findMinAndMax() {
		Utils.printCaller(this, 15);
		Utils.log2("FPT2.findMinAndMax called");
		super.findMinAndMax();
	}

	public final float[] getFloatPixels() { return (float[])getPixels(); } // I luv pointlessly private fields

	/** Return the float array of pixels as a byte array, cropping (no scaling). */
	public final byte[] getBytePixels() {
		 final float[] f = getFloatPixels();
		 final byte[] b = new byte[f.length];
		 int val;
		 final int size = width*height;
		 for (int i=0; i<size; ++i) {
			 val = (int)(f[i] + 0.5f);
			 if (val < 0) val = 0;
			 if (val > 255) val = 255;
			 b[i] = (byte)val;
		 }
		 return b;
	}
	
	public final byte[] getScaledBytePixels() {
		 final float[] f = getFloatPixels();
		 final byte[] b = new byte[f.length];
		 int val;
		 final int size = width*height;
		 final double min = getMin();
		 final double scale = 255 / (getMax() - min + 1);
		 //
		 for (int i=0; i<size; ++i) {
			 val = (int)((f[i] - min) * scale + 0.5);
			 if (val < 0) val = 0;
			 if (val > 255) val = 255;
			 b[i] = (byte)val;
		 }
		 return b;
	}
	
	/** Return the float array of pixels as a byte array, cropping (no scaling). */
	public final int[] getRGBPixels() {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		int val;
		final int size = width*height;
		for (int i=0; i<size; ++i) {
			val = (int)(f[i] + 0.5f);
			if (val < 0) val = 0;
			if (val > 255) val = 255;
			rgb[i] = 0xff000000 | (val<<16) | (val<<8) | val;
		}
		return rgb;
	}
	/** Return the float array of pixels as a byte array, cropping (no scaling).
	 *  It's your problem to ensure alpha has same length as width*height. */
	public final int[] getARGBPixels(final byte[] alpha) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		int val;
		final int size = width*height;
		for (int i=0; i<size; ++i) {
			val = (int)(f[i] + 0.5f);
			if (val < 0) val = 0;
			if (val > 255) val = 255;
			rgb[i] = ((alpha[i]&0xff)<<24) | (val<<16) | (val<<8) | val;
		}
		return rgb;
	}
	/** Return the float array of pixels as a byte array, cropping (no scaling).
	 *  It's your problem to ensure alpha and outside have same length as width*height. */
	public final int[] getARGBPixels(final byte[] alpha, final byte[] outside) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		int val;
		final int size = width*height;
		for (int i=0; i<size; ++i) {
			val = (int)(f[i] + 0.5f);
			if (val < 0) val = 0;
			if (val > 255) val = 255;
			rgb[i] = ( (outside[i]&0xff) != 255 ? 0 : ((alpha[i]&0xff)<<24) ) | (val<<16) | (val<<8) | val;
		}
		return rgb;
	}

	public final void debugMinMax(String msg) {
		try {
			Utils.log(msg + "\n\tmin, max: " + fmin.get(this) +", " + fmax.get(this) + " minMaxSet: " + super.minMaxSet);
		} catch (Throwable t) { ini.trakem2.utils.IJError.print(t); }

	}

	/** Return the float array of pixels as a byte array, with scaling.
	 *  It's your problem to ensure alpha has same length as width*height. */
	public final int[] getARGBPixels(final float[] alpha) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		int val;
		final int size = width*height;

		final float min = (float)getMin();
		final float max = (float)getMax();
		final float scale = 256.0f/(max-min+1);
		for (int i=0; i<size; ++i) {
			val = (int)(f[i] - min);
			if (val < 0) val = 0;
			val = (int)(val*scale + 0.5f);
			if (val > 255) val = 255;
			rgb[i] = (((int)(alpha[i]+0.5f))<<24) + (val<<16) + (val<<8) + val;
		}
		return rgb;
	}
	/** Return the float array of pixels as a byte array, cropping (no scaling).
	 *  It's your problem to ensure alpha and outside have same length as width*height. */
	public final int[] getARGBPixels(final float[] alpha, final float[] outside) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		int val;
		final int size = width*height;
		final float min = (float)getMin();
		final float max = (float)getMax();
		final float scale = 256.0f/(max-min+1);
		for (int i=0; i<size; ++i) {
			val = (int)(f[i] - min);
			if (val < 0) val = 0;
			val = (int)(val*scale + 0.5f);
			if (val > 255) val = 255;
			rgb[i] = ( ((int)(outside[i]+0.5f)) == 255 ? (((int)(alpha[i]+0.5f))<<24) : 0 ) + (val<<16) + (val<<8) + val;
		}
		return rgb;
	}
}
