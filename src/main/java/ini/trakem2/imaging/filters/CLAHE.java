package ini.trakem2.imaging.filters;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.Map;

import mpicbg.ij.clahe.Flat;

public class CLAHE implements IFilter
{
	protected int blockRadius = 63,
	              bins = 255;
	protected float slope = 3;
	protected boolean fast = true;

	public CLAHE() {}
	
	public CLAHE(boolean fast, int blockRadius, int bins, float slope) {
		this.fast = fast;
		this.blockRadius = blockRadius;
		this.bins = bins;
		this.slope = slope;
	}
	
	public CLAHE(Map<String,String> params) {
		try {
			this.fast = Boolean.parseBoolean(params.get("fast"));
			this.blockRadius = Integer.parseInt(params.get("blockradius"));
			this.bins = Integer.parseInt(params.get("bins"));
			this.slope = Float.parseFloat(params.get("slope"));
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create CLAHE filter!", nfe);
		}
	}
	
	@Override
	public ImageProcessor process(ImageProcessor ip) {
		if (fast) {
			Flat.getFastInstance().run(new ImagePlus("", ip), blockRadius, bins, slope, null, false);
		} else {
			Flat.getInstance().run(new ImagePlus("", ip), blockRadius, bins, slope, null, false);
		}
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" fast=\"").append(fast)
			.append("\" blockradius=\"").append(blockRadius)
			.append("\" bins=\"").append(bins)
			.append("\" slope=\"").append(slope)
			.append("\" />\n").toString();
	}

	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == CLAHE.class) {
			final CLAHE c = (CLAHE)o;
			return bins == c.bins && blockRadius == c.blockRadius && slope == c.slope && fast == c.fast;
		}
		return false;
	}
}
