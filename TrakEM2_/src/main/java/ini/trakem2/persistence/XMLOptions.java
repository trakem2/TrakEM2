package ini.trakem2.persistence;

import ini.trakem2.display.Patch;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.Thing;

import java.util.HashMap;

import mpicbg.trakem2.transform.CoordinateTransform;

public class XMLOptions
{
	/** Is filled up when reading the expanded states of the {@link ProjectTree} nodes. */
	public final HashMap<Thing,Boolean> expanded_states = new HashMap<Thing,Boolean>();
	
	/** Whether to overwrite the XML file or not. */
	public boolean overwriteXMLFile = false;
	
	/** Specifies whether images must be saved. Will be saved in {@link #patches_dir}. */
	public boolean export_images = false;
	
	/** Is used if {@link Patch} images have to be exported to a new directory. */
	public String patches_dir = null;
	
	/** If true, then {@link Patch#exportXML(StringBuilder, String, XMLOptions)} will write
	 * the @{link {@link CoordinateTransform}} XML into the XML file. */
	public boolean include_coordinate_transform = true;
	
	public XMLOptions() {}
}
