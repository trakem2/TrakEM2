package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;

import java.util.Map;

/** Smooth with a Gaussian. */
public class GaussianBlur implements IFilter
{
	protected double sigmaX = 2, sigmaY = 2, accuracy = 0.002;
	
	public GaussianBlur() {}

	public GaussianBlur(final double sigmaX, final double sigmaY, final double accuracy) {
		this.sigmaX = sigmaX;
		this.sigmaY = sigmaY;
		this.accuracy = accuracy;
	}

	public GaussianBlur(Map<String,String> params) {
		try {
			this.sigmaX = Double.parseDouble(params.get("sigmax"));
			this.sigmaY= Double.parseDouble(params.get("sigmay"));
			this.accuracy = Double.parseDouble(params.get("accuracy"));
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create Smooth filter!", nfe);
		}
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		ij.plugin.filter.GaussianBlur g = new ij.plugin.filter.GaussianBlur();
		g.blurGaussian(ip, sigmaX, sigmaY, accuracy);
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" sigmax=\"").append(sigmaX)
			.append("\" sigmay=\"").append(sigmaY)
			.append("\" accuracy=\"").append(accuracy)
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == GaussianBlur.class) {
			final GaussianBlur g = (GaussianBlur)o;
			return sigmaX == g.sigmaX && sigmaY == g.sigmaY && accuracy == g.accuracy;
		}
		return false;
	}
}
