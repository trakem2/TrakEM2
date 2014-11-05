package ini.trakem2.imaging.filters;

import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.utils.Utils;

import java.awt.image.IndexColorModel;
import java.util.Map;

public class LUTCustom implements IFilter
{
	protected float r = 1, g = 1, b = 1;
	
	public LUTCustom() {}
	
	/**
	 * @param r Between [0, 1]
	 * @param g Between [0, 1]
	 * @param b Between [0, 1]
	 */
	public LUTCustom(float r, float g, float b) {
		this.r = r;
		this.g = g;
		this.b = b;
		fix();
	}
	
	private void fix() {
		if (r < 0) r = 0;
		if (r > 1) r = 1;
		if (g < 0) g = 0;
		if (g > 1) g = 1;
		if (b < 0) b = 0;
		if (b > 1) b = 1;
	}

	public LUTCustom(Map<String,String> params) {
		try {
			this.r = Float.parseFloat(params.get("r"));
			this.g = Float.parseFloat(params.get("g"));
			this.b = Float.parseFloat(params.get("b"));
			fix();
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create LUTCustom filter!", nfe);
		}
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		if (ip instanceof ColorProcessor) {
			Utils.log("Ignoring " + getClass().getSimpleName() + " filter for RGB image");
			return ip;
		}
		byte[] s1 = new byte[256];
		byte[] s2 = new byte[256];
		byte[] s3 = new byte[256];
		for (int i=0; i<256; ++i) {
			s1[i] = (byte)(int)(i * r);
			s2[i] = (byte)(int)(i * g);
			s3[i] = (byte)(int)(i * b);
			Utils.log2(i + ": r, g, b " + s1[i] + ", " + s2[i] + ", " + s3[i]);
		}
		ip.setColorModel(new IndexColorModel(8, 256, s1, s2, s3));
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" r=\"").append(r)
			.append("\" g=\"").append(g)
			.append("\" b=\"").append(b)
			.append("\" />\n").toString();
	}

	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o instanceof LUTCustom) {
			final LUTCustom l = (LUTCustom)o;
			return r == l.r && g == l.g && b == l.b;
		}
		return false;
	}
}

