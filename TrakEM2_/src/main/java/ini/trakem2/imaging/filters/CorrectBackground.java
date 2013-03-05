package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.imaging.IntegralHistogram2d;

import java.util.Map;

public class CorrectBackground implements IFilter {

	/** Radius for integral median filter */
	protected int medianRadius = 512;
	
	/** Number of histogram bins. RAM requirements scale relative to this value: choose wisely. Test first. */
	protected int nBins = 32;
	
	/** Whether to process the approximated median filter with a Gaussian */
	protected boolean postGaussian = true;
	
	/** Sigma of the Gaussian */
	protected float sigma = 5;
	
	public CorrectBackground() {}
	
	public CorrectBackground(final int radius, final float sigma) {
		this.medianRadius = radius;
		this.sigma = sigma;
	}
	
	public CorrectBackground(Map<String,String> params) {
		try {
			this.medianRadius = Integer.parseInt(params.get("medianradius"));
			this.nBins = Integer.parseInt(params.get("nbins"));
			this.postGaussian = Boolean.parseBoolean(params.get("postgaussian"));
			this.sigma = Float.parseFloat(params.get("sigma"));
		} catch (Exception e) {
			throw new IllegalArgumentException("Cound not create CorrectBackground filter!", e);
		}
	}
	
	@Override
	public ImageProcessor process(final ImageProcessor ip) {
		final ShortProcessor sp;
		try {
			sp = (ShortProcessor)ip;
		} catch (ClassCastException cce) {
			System.out.println("CorrectBackground supports 16-bit images only!");
			return ip;
		}
		
		final double min = ip.getMin(),
		              max = ip.getMax();

		final long[] hist = IntegralHistogram2d.integralHistogram2d((short[])sp.getPixels(), sp.getWidth(), sp.getHeight(), nBins, min, max);
		final short[] median = IntegralHistogram2d.median(sp.getWidth(), sp.getHeight(), hist, nBins, min, max, this.medianRadius);
		
		if (this.postGaussian) {
			ij.plugin.filter.GaussianBlur g = new ij.plugin.filter.GaussianBlur();
			g.blurGaussian(new ShortProcessor(sp.getWidth(), sp.getHeight(), median, null), this.sigma, this.sigma, 0.02);
		}

		// Approximate mean image value (within min-max range) from the histogram present in the last set of nBins in the integral histogram
		final double binInc = (max - min + 1) / nBins;
		double sum = 0;
		long count = 0;
		for (int i=hist.length-nBins, j=0; j<nBins; ++i, ++j) {
			sum += hist[i] * (min + (j * binInc));
			count += hist[i];
		}
		// TODO error the sum of hist[-16] to hist[-1] doesn't add up to width*height !!!
		final double mean = sum / count; // can't use median.length or p.length, there is an erroneous mismatch
		
		final short[] p = (short[]) sp.getPixels();
		
		// Divide image by median (which plays the role of an approximated flat-field brightness image)
		// and multiply by the mean.
		// The pixels of the provided image
		for (int i=0; i<p.length; ++i) {
			final double m = median[i] & 0xffff;
			p[i] = (short)(((p[i]&0xffff) / m) * mean);
		}
		
		return ip;
	}
	
	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" medianRadius=\"").append(medianRadius)
			.append("\" nBins=\"").append(nBins)
			.append("\" postGaussian=\"").append(postGaussian)
			.append("\" sigma=\"").append(sigma).append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == getClass()) {
			final CorrectBackground cb = (CorrectBackground)o;
			return cb.medianRadius == medianRadius
					&& cb.nBins == nBins
					&& cb.sigma == sigma
					&& cb.postGaussian == postGaussian;
		}
		return false;
	}
}
