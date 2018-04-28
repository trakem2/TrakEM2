package ini.trakem2.imaging.filters;

import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.utils.Utils;

import java.awt.image.IndexColorModel;
import java.util.Map;

public class LUTYellow implements IFilter
{
	public LUTYellow() {}
	
	public LUTYellow(Map<String,String> params) {}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		if (ip instanceof ColorProcessor) {
			Utils.log("Ignoring " + getClass().getSimpleName() + " filter for RGB image");
			return ip;
		}
		byte[] s = new byte[256];
		for (int i=0; i<256; ++i) s[i] = (byte)i;
		ip.setColorModel(new IndexColorModel(8, 256, s, s, new byte[256]));
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
		return null != o && o.getClass() == getClass();
	}
}

