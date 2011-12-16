package ini.trakem2.imaging.filters;

import java.awt.image.IndexColorModel;
import java.util.Map;

import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.utils.Utils;

public class LUTOrange extends LUTCustom
{
	public LUTOrange() {
		super(1, 0.5f, 0);
	}
	
	public LUTOrange(Map<String,String> params) {
		super(params);
	}

	/*
	@Override
	public ImageProcessor process(ImageProcessor ip) {
		if (ip instanceof ColorProcessor) {
			Utils.log("Ignoring " + getClass().getSimpleName() + " filter for RGB image");
			return ip;
		}
		byte[] s1 = new byte[256];
		byte[] s2 = new byte[256];
		for (int i=0; i<256; ++i) {
			s1[i] = (byte)i;
			s2[i] = (byte)(i/2);
		}
		ip.setColorModel(new IndexColorModel(8, 256, s1, s2, new byte[256]));
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" />\n").toString();
	}
	*/
}

