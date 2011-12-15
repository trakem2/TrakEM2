package ini.trakem2.imaging.filters;

import java.util.Map;

import ij.process.ImageProcessor;

/** Smooth with a Gaussian. */
public class GaussianBlur implements IFilter
{
	protected double sigmaX = 2, sigmaY = 2, accuracy = 0.02;
	
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
}
