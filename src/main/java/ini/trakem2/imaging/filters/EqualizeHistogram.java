package ini.trakem2.imaging.filters;

import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;

import java.util.Map;

public class EqualizeHistogram implements IFilter
{
	public EqualizeHistogram() {}
	
	public EqualizeHistogram(Map<String,String> params) {}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		new ContrastEnhancer().equalize(ip);
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" />\n").toString();
	}
	
	@Override
	public boolean equals(final Object o) {
		return null != o && o.getClass() == EqualizeHistogram.class;
	}
}
