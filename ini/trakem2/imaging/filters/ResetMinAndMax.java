package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;

import java.util.Map;

public class ResetMinAndMax implements IFilter
{
	public ResetMinAndMax() {}
	
	public ResetMinAndMax(Map<String, String> params) {}
	
	@Override
	public ImageProcessor process(ImageProcessor ip) {
		ip.resetMinAndMax();
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent).append("<t2_filter class=\"")
			.append(getClass().getName())
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		return null != o && o.getClass() == ResetMinAndMax.class;
	}
}
