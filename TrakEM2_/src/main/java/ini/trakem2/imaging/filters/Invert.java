package ini.trakem2.imaging.filters;

import java.util.Map;

import ij.process.ImageProcessor;

public class Invert implements IFilter
{
	public Invert() {}
	
	public Invert(Map<String, String> params) {}
	
	@Override
	public ImageProcessor process(ImageProcessor ip) {
		ip.invert();
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
		return null != o && o.getClass() == Invert.class;
	}
}
