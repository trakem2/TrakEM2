package ini.trakem2.imaging;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
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

	/** Returns a new, blank FloatProcessor with the specified width and height. */
	public ImageProcessor createProcessor(final int width, final int height) {
		ImageProcessor ip2 = new FloatProcessorT2(width, height, new float[width*height], getColorModel());
		return ip2;
	}
	public void findMinAndMax() {
		Utils.log2("FPT2.findMinAndMax called");
		super.findMinAndMax();
	}
}
