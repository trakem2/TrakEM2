package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.Map;

/**
 * Sets the minimum to zero and the maximum to the maximum supported, or 255 for FloatProcessor
 * and any other unknown {@link ImageProcessor}.
 * 
 * @author Albert Cardona
 *
 */
public class DefaultMinAndMax implements IFilter
{
	public DefaultMinAndMax() {}
	
	public DefaultMinAndMax(Map<String,String> params) {}

	@Override
	public ImageProcessor process(final ImageProcessor ip) {
		ip.setMinAndMax(0, ShortProcessor.class == ip.getClass() ? 65535 : 255);
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
		return null != o && o.getClass() == DefaultMinAndMax.class;
	}
}