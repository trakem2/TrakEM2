/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
