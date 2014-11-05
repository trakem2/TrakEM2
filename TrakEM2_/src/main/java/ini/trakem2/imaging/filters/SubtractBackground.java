package ini.trakem2.imaging.filters;

import ij.plugin.filter.BackgroundSubtracter;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.util.Map;

/** Subtract background with the rolling-ball algorithm. */
public class SubtractBackground implements IFilter
{
	protected int radius = 50;
	
	public SubtractBackground() {}

	public SubtractBackground(final int radius) {
		this.radius = radius;
	}

	public SubtractBackground(Map<String,String> params) {
		String s = params.get("radius");
		if (null != s) {
			try {
				this.radius = Integer.parseInt(s);
				return;
			} catch (NumberFormatException nfe) {}
		}
		throw new IllegalArgumentException("Could not create filter " + getClass().getName() + ": invalid or undefined radius!");
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		BackgroundSubtracter bs = new BackgroundSubtracter();
		if (ip instanceof ColorProcessor) {
			bs.subtractRGBBackround((ColorProcessor)ip, radius);
		} else {
			bs.subtractBackround(ip, radius);
		}
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"")
			.append(getClass().getName())
			.append("\" radius=\"").append(radius).append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == getClass()) {
			return ((SubtractBackground)o).radius == radius;
		}
		return false;
	}
}
