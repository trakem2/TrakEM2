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
			.append("\" />").toString();
	}
}
