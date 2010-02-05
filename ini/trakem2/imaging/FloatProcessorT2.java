package ini.trakem2.imaging;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ColorProcessor;
import ij.process.ByteProcessor;
import java.awt.image.ColorModel;
import ini.trakem2.utils.Utils;
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
		 float val;
		 final int size = width*height;
		 for (int i=0; i<size; i++) {
			 val = f[i] + 0.5f;
			 if (val < 0f) val = 0f;
			 if (val > 255f) val = 255f;
			 b[i] = (byte)val;
		 }
		 return b;
	}
	
	/** Return the float array of pixels as a byte array, cropping (no scaling). */
	public final int[] getRGBPixels() {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		float val;
		final int size = width*height;
		for (int i=0; i<size; i++) {
			val = f[i] + 0.5f;
			if (val < 0f) val = 0f;
			if (val > 255f) val = 255f;
			final byte b = (byte)val;
			rgb[i] = (b<<16) + (b<<8) + b;
		}
		return rgb;
	}
	/** Return the float array of pixels as a byte array, cropping (no scaling).
	 *  It's your problem to ensure alpha has same length as width*height. */
	public final int[] getARGBPixels(final byte[] alpha) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		float val;
		final int size = width*height;
		for (int i=0; i<size; i++) {
			val = f[i] + 0.5f;
			if (val < 0f) val = 0f;
			if (val > 255f) val = 255f;
			final int b = (int)val;
			rgb[i] = ((alpha[i]&0xff)<<24) | (b<<16) | (b<<8) | b;
		}
		return rgb;
	}
	/** Return the float array of pixels as a byte array, cropping (no scaling).
	 *  It's your problem to ensure alpha and outside have same length as width*height. */
	public final int[] getARGBPixels(final byte[] alpha, final byte[] outside) {
		final float[] f = getFloatPixels();
		final int[] rgb = new int[f.length];
		float val;
		final int size = width*height;
		for (int i=0; i<size; i++) {
			val = f[i] + 0.5f;
			if (val < 0f) val = 0f;
			if (val > 255f) val = 255f;
			final int b = (int)val;
			rgb[i] = ( (outside[i]&0xff) != 255  ? 0 : ((alpha[i]&0xff)<<24) ) | (b<<16) | (b<<8) | b;
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
		for (int i=0; i<size; i++) {
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
		for (int i=0; i<size; i++) {
			val = (int)(f[i] - min);
			if (val < 0) val = 0;
			val = (int)(val*scale + 0.5f);
			if (val > 255) val = 255;
			rgb[i] = ( ((int)(outside[i]+0.5f)) == 255 ? (((int)(alpha[i]+0.5f))<<24) : 0 ) + (val<<16) + (val<<8) + val;
		}
		return rgb;
	}
}
