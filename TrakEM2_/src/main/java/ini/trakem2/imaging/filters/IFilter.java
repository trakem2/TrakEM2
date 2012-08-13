package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;

public interface IFilter
{
	/** Execute the filter, returning possibly a new ImageProcessor, or the same as given. */
	public ImageProcessor process(ImageProcessor ip);
	
	/** Create an XML representation of this Filter. */
	public String toXML(String indent);
}
