package ini.trakem2.imaging;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ColorProcessor;
import java.awt.image.ColorModel;
import ini.trakem2.utils.Utils;
import java.lang.reflect.Field;

public class FloatProcessorT2 extends FloatProcessor {

	static private Field fmin;
	static private Field fmax;

	static {
		try {
			fmin = FloatProcessor.class.getDeclaredField("min");
			fmin.setAccessible(true);
			fmax = FloatProcessor.class.getDeclaredField("max");
			fmax.setAccessible(true);
		} catch (Exception e) { e.printStackTrace(); }
	}

	private final void setMinMax(final double min, final double max) {
		try {
			fmin.set(this, (float)min);
			fmax.set(this, (float)max);
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
		this.setPixels(w, h, (float[])super.resize(w, h).getPixels());
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
	}

	/** Returns a new, blank FloatProcessor with the specified width and height. */
	public ImageProcessor createProcessor(final int width, final int height) {
		ImageProcessor ip2 = new FloatProcessorT2(width, height, new float[width*height], getColorModel());
		return ip2;
	}
	public void findMinAndMax() {
		Utils.log2("FPT2.findMinAndMax called");
		super.findMinAndMax();
	}

	public final float[] getFloatPixels() { return (float[])getPixels(); } // I luv pointlessly private fields
}
