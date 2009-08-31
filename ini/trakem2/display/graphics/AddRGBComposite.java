/**
 * 
 */
package ini.trakem2.display.graphics;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;

/**
 * @author saalfeld
 *
 */
public class AddRGBComposite implements Composite
{

	static private AddRGBComposite instance = new AddRGBComposite();
	
	static public AddRGBComposite getInstance() {
		return instance;
	}
	
	private AddRGBComposite(){}
	
	/* (non-Javadoc)
	 * @see java.awt.Composite#createContext(java.awt.image.ColorModel, java.awt.image.ColorModel, java.awt.RenderingHints)
	 */
	public CompositeContext createContext( ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints )
	{
		return new AddRGBCompositeContext();
	}

}
