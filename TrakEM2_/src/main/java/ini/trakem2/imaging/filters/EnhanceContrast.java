package ini.trakem2.imaging.filters;

import ij.measure.Measurements;
import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.util.Map;

/** Uses the @{link {@link ContrastEnhancer#stretchHistogram(ImageProcessor, double, ImageStatistics)} function. */
public class EnhanceContrast implements IFilter
{
	/** Percent of saturated pixels to leave outside the min, max range. */
	protected double s = 0.4;
	
	public EnhanceContrast() {}
	
	public EnhanceContrast(Map<String,String> params) {}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		// Will not alter ip, no need to duplicate
		new ContrastEnhancer().stretchHistogram(ip, s, ImageStatistics.getStatistics(ip, Measurements.MIN_MAX, null));
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" s=\"").append(s)
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == EnhanceContrast.class) {
			return ((EnhanceContrast)o).s == this.s;
		}
		return false;
	}
}