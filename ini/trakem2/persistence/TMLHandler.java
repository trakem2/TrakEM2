/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.persistence;

import ij.IJ;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.io.File;

import ini.trakem2.tree.*;
import ini.trakem2.display.*;
import ini.trakem2.utils.*;
import ini.trakem2.Project;

import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.InputSource;
import org.xml.sax.Attributes;

import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;

/** Creates the project objects from an XML file (TrakEM2 Markup Language Handler). */
public class TMLHandler extends DefaultHandler {

	private LayerThing root_lt = null;
	private ProjectThing root_pt = null;
	private TemplateThing root_tt = null;
	private TemplateThing project_tt = null;
	private TemplateThing template_layer_thing = null;
	private TemplateThing template_layer_set_thing = null;
	private Project project = null;
	private LayerSet layer_set = null;
	private final FSLoader loader;
	private boolean skip = false;
	private String base_dir;
	private String xml_path;
	/** Stores the object and its String with coma-separated links, to construct links when all objects exist. */
	private HashMap ht_links = new HashMap();

	private Thing current_thing = null;
	private ArrayList al_open = new ArrayList();
	private ArrayList al_layers = new ArrayList();
	private ArrayList al_layer_sets = new ArrayList();
	private ArrayList al_displays = new ArrayList(); // contains HashMap instances with display data.
	/** To accumulate Displayable types for relinking and assigning their proper layer. */
	private HashMap ht_displayables = new HashMap();
	private HashMap ht_zdispl = new HashMap();
	private HashMap ht_oid_pt = new HashMap();
	private HashMap ht_pt_expanded = new HashMap();
	private Ball last_ball = null;
	private AreaList last_area_list = null;
	private Dissector last_dissector = null;
	private Patch last_patch = null;
	private Displayable last_displayable = null;
	private CoordinateTransformList last_ct_list = null;
	private boolean open_displays = true;


	/** @param path The XML file that contains the project data in XML format.
	 *  @param loader The FSLoader for the project.
	 */
	public TMLHandler(final String path, FSLoader loader) {
		this.loader = loader;
		this.base_dir = path.substring(0, path.lastIndexOf(File.separatorChar) + 1);
		this.xml_path = path;
		final TemplateThing[] tt = DTDParser.extractTemplate(path);
		if (null == tt) {
			Utils.log("TMLHandler: can't read DTD in file " + path);
			loader = null;
			return;
		}
		// debug:
		/*
		Utils.log("ROOTS #####################");
		for (int k=0; k < tt.length; k++) {
			Utils.log2("tt root " + k + ": " + tt[k]);
		}
		*/
		this.root_tt = tt[0];
		// create LayerThing templates
		this.template_layer_thing = new TemplateThing("layer");
		this.template_layer_set_thing = new TemplateThing("layer set");
		this.template_layer_set_thing.addChild(this.template_layer_thing);
		this.template_layer_thing.addChild(this.template_layer_set_thing);
		// create Project template
		this.project_tt = new TemplateThing("project");
		project_tt.addChild(this.root_tt);
		project_tt.addAttribute("title", "Project");
	}

	public boolean isUnreadable() {
		return null == loader;
	}

	/** returns 4 objects packed in an array:
	 <pre>
	 [0] = root TemplateThing
	 [1] = root ProjectThing (contains Project instance)
	 [2] = root LayerThing (contains the top-level LayerSet)
	 [3] = expanded states of all ProjectThing objects
	 </pre>
	 * <br />
	 * Also, triggers the reconstruction of links and assignment of Displayable objects to their layer.
	 */
	public Object[] getProjectData(final boolean open_displays) {
		if (null == project) return null;
		this.open_displays = open_displays;
		// 1 - Reconstruct links using ht_links
		// Links exist between Displayable objects.
		for (Iterator it = ht_displayables.values().iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			Object olinks = ht_links.get(d);
			if (null == olinks) continue; // not linked
			String[] links = ((String)olinks).split(",");
			Long lid = null;
			for (int i=0; i<links.length; i++) {
				try {
					lid = new Long(links[i]);
				} catch (NumberFormatException nfe) {
					Utils.log2("Ignoring incorrectly formated link '" + links[i] + "' for ob " + d);
					continue;
				}
				Displayable partner = (Displayable)ht_displayables.get(lid);
				if (null != partner) d.link(partner); //, false);
				else Utils.log("TMLHandler: can't find partner with id=" + links[i] + " for Displayable with id=" + d.getId());
			}
		}

		// 2 - Add Displayable objects to ProjectThing that can contain them
		for (Iterator it = ht_oid_pt.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Long lid = (Long)entry.getKey();
			ProjectThing pt = (ProjectThing)entry.getValue();
			Object od = ht_displayables.get(lid);
			//Utils.log("==== processing: Displayable [" + od + "]  vs. ProjectThing [" + pt + "]");
			if (null != od) {
				ht_displayables.remove(lid);
				pt.setObject(od);
			} else {
				Utils.log("#### Failed to find a Displayable for ProjectThing " + pt + " #####");
			}
		}

		// debug:
		/*
		for (Iterator it = al_layer_sets.iterator(); it.hasNext(); ) {
			LayerSet ls = (LayerSet)it.next();
			Utils.log2("ls #id " + ls.getId() + "  size: " +ls.getLayers().size());
		}
		*/

		// 3 - Assign a layer pointer to ZDisplayable objects
		for (Iterator it = ht_zdispl.values().iterator(); it.hasNext(); ) {
			ZDisplayable zd = (ZDisplayable)it.next();
			//zd.setLayer((Layer)zd.getLayerSet().getLayers().get(0));
			zd.setLayer(zd.getLayerSet().getLayer(0));
		}

		// 3 - Create displays for later
		HashMap ht_lid = new HashMap();
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			ht_lid.put(new Long(layer.getId()), layer);
		}
		for (Iterator it = al_displays.iterator(); it.hasNext(); ){
			HashMap ht_attributes = (HashMap)it.next();
			Object ob = ht_attributes.get("layer_id");
			if (null != ob) {
				Object lob = ht_lid.get(new Long((String)ob));
				if (null != lob)  {
					new Display(project, Long.parseLong((String)ht_attributes.get("id")), (Layer)lob, ht_attributes);
				}
			}
		}

		// debug:
		//root_tt.debug("");
		//root_pt.debug("");
		//root_lt.debug("");

		return new Object[]{root_tt, root_pt, root_lt, ht_pt_expanded};
	}

	private int counter = 0;

	public void startElement(String namespace_URI, String local_name, String qualified_name, Attributes attributes) throws SAXException {
		if (null == loader) return;

		//Utils.log2("startElement: " + qualified_name);
		this.counter++;
		Utils.showStatus("Loading " + counter, false);

		try {
			// failsafe:
			qualified_name = qualified_name.toLowerCase();

			HashMap ht_attributes = new HashMap();
			for (int i=attributes.getLength() -1; i>-1; i--) {
				ht_attributes.put(attributes.getQName(i).toLowerCase(), attributes.getValue(i));
			}
			// get the id, which whenever possible it's the id of the encapsulating Thing object. The encapsulated object id is the oid
			// The type is specified by the qualified_name
			Thing thing = null;
			if (0 == qualified_name.indexOf("t2_")) {
				if (qualified_name.equals("t2_display")) {
					if (open_displays) al_displays.add(ht_attributes); // store for later, until the layers exist
				} else {
					// a Layer, LayerSet or Displayable object
					thing = makeLayerThing(qualified_name, ht_attributes);
					if (null != thing) {
						if (null == root_lt && thing.getObject() instanceof LayerSet) {
							root_lt = (LayerThing)thing;
						}
					}
				}
			} else if (qualified_name.equals("project")) {
				if (null != this.root_pt) {
					Utils.log("WARNING: more than one project definitions.");
					return;
				}
				// Create the project
				this.project = new Project(Long.parseLong((String)ht_attributes.remove("id")), (String)ht_attributes.remove("title"));
				this.project.setTempLoader(this.loader); // temp, but will be the same anyway
				this.project.parseXMLOptions(ht_attributes);
				this.project.addToDatabase(); // register id
				// Add all unique TemplateThing types to the project
				for (Iterator it = root_tt.getUniqueTypes(new HashMap()).values().iterator(); it.hasNext(); ) {
					this.project.addUniqueType((TemplateThing)it.next());
				}
				this.project.addUniqueType(this.project_tt);
				this.root_pt = new ProjectThing(this.project_tt, this.project, this.project);
				this.root_pt.addAttribute("title", ht_attributes.get("title"));
				// Add a project pointer to all template things
				this.root_tt.addToDatabase(this.project);
				thing = root_pt;
			} else if (qualified_name.startsWith("ict_transform")) {
				makeCoordinateTransform(qualified_name, ht_attributes);
			} else if (!qualified_name.equals("trakem2")) {
				// Any abstract object
				thing = makeProjectThing(qualified_name, ht_attributes);
			}
			if (null != thing) {
				// get the previously open thing and add this new_thing to it as a child
				int size = al_open.size();
				if (size > 0) {
					Thing parent = (Thing)al_open.get(size -1);
					parent.addChild(thing);
					//Utils.log2("Adding child " + thing + " to parent " + parent);
				}
				// add the new thing as open
				al_open.add(thing);
			}
		} catch (Exception e) {
			IJError.print(e);
			skip = true;
		}
	}
	public void endElement(String namespace_URI, String local_name, String qualified_name) {
		if (null == loader) return;
		if (skip) {
			skip = false; // reset
			return;
		}
		String orig_qualified_name = qualified_name;
		//Utils.log2("endElement: " + qualified_name);
		// iterate over all open things and find the one that matches the qualified_name, and set it closed (pop it out of the list):
		qualified_name = qualified_name.toLowerCase().trim();
		if (0 == qualified_name.indexOf("t2_")) {
			qualified_name = qualified_name.substring(3);
		}

		for (int i=al_open.size() -1; i>-1; i--) {
			Thing thing = (Thing)al_open.get(i);
			if (thing.getType().toLowerCase().equals(qualified_name)) {
				al_open.remove(thing);
				break;
			}
		}

		// terminate non-single clause objects
		if (orig_qualified_name.equals("t2_area_list")) {
			last_area_list.__endReconstructing();
			last_area_list = null;
		} else if (orig_qualified_name.equals("t2_patch")) {
			last_patch = null;
		} else if (orig_qualified_name.equals("ict_transform")) {
			last_ct_list = null;
		} else if (orig_qualified_name.equals("t2_dissector")) {
			last_dissector = null;
		} else if (orig_qualified_name.equals("t2_ball")) {
			last_ball = null;
		}

		if (orig_qualified_name.equals("t2_link")) {
			last_propertiestable = last_displayable; // the parent of the link, so now it can accept properties, if any
		} else {
			last_displayable = null;
			last_propertiestable = null;
		}
	}
	public void characters(char[] c, int start, int length) {}

	public void fatalError(SAXParseException e) {
		Utils.log("Fatal error: column=" + e.getColumnNumber() + " line=" + e.getLineNumber());
	}
	public void skippedEntity(String name) {
		Utils.log("SAX Parser has skipped: " + name);
	}
	public void notationDeclaration(String name, String publicId, String systemId) {
		Utils.log("Notation declaration: " + name + ", " + publicId + ", " + systemId);
	}
	public void warning(SAXParseException e) {
		Utils.log("SAXParseException : " + e);
	}

	private ProjectThing makeProjectThing(String type, HashMap ht_attributes) {
		try {
			type = type.toLowerCase();

			// debug:
			//Utils.log2("TMLHander.makeProjectThing for type=" + type);

			long id = -1;
			Object sid = ht_attributes.get("id");
			if (null != sid) {
				id = Long.parseLong((String)sid);
				ht_attributes.remove("id");
			}
			long oid = -1;
			Object soid = ht_attributes.get("oid");
			if (null != soid) {
				oid = Long.parseLong((String)soid);
				ht_attributes.remove("oid");
			}
			Boolean expanded = new Boolean(false); // default: collapsed
			Object eob = ht_attributes.get("expanded");
			if (null != eob) {
				expanded = new Boolean((String)eob);
				ht_attributes.remove("expanded");
			}
			// abstract object, including profile_list
			HashMap ht_attr = new HashMap();
			for (Iterator it = ht_attributes.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				String key = (String)entry.getKey();
				String data = (String)entry.getValue();
				ht_attr.put(key, new ProjectAttribute(this.project, -1, key, data));
				//Utils.log2("putting key=[" + key + "]=" + data);
			}
			ProjectThing pt = new ProjectThing(this.project.getTemplateThing(type), this.project, id, type, null, ht_attr);
			pt.addToDatabase();
			ht_pt_expanded.put(pt, expanded);
			// store the oid vs. pt relationship to fill in the object later.
			if (-1 != oid) {
				ht_oid_pt.put(new Long(oid), pt);
			}
			return pt;
		} catch (Exception e) {
			IJError.print(e);
		}
		// default:
		return null;
	}

	private void addToLastOpenLayer(Displayable d) {
		// find last open layer
		for (int i = al_layers.size() -1; i>-1; i--) {
			((Layer)al_layers.get(i)).addSilently(d);
			break;
		}
	}
	private void addToLastOpenLayerSet(ZDisplayable zd) {
		// find last open layer set
		for (int i = al_layer_sets.size() -1; i>-1; i--) {
			((LayerSet)al_layer_sets.get(i)).addSilently(zd);
			break;
		}
	}

	private LayerThing makeLayerThing(String type, HashMap ht_attributes) {
		try {
			type = type.toLowerCase();
			if (0 == type.indexOf("t2_")) {
				type = type.substring(3);
			}
			long id = -1;
			Object sid = ht_attributes.get("id");
			if (null != sid) id = Long.parseLong((String)sid);
			long oid = -1;
			Object soid = ht_attributes.get("oid");
			if (null != soid) oid = Long.parseLong((String)soid);

			if (type.equals("profile")) {
				Profile profile = new Profile(this.project, oid, ht_attributes, ht_links);
				profile.addToDatabase();
				ht_displayables.put(new Long(oid), profile);
				addToLastOpenLayer(profile);
				last_displayable = profile;
				return null;
			} else if (type.equals("pipe")) {
				Pipe pipe = new Pipe(this.project, oid, ht_attributes, ht_links);
				pipe.addToDatabase();
				ht_displayables.put(new Long(oid), pipe);
				ht_zdispl.put(new Long(oid), pipe);
				last_displayable = pipe;
				addToLastOpenLayerSet(pipe);
				return null;
			} else if (type.equals("polyline")) {
				Polyline pline = new Polyline(this.project, oid, ht_attributes, ht_links);
				pline.addToDatabase();
				last_displayable = pline;
				ht_displayables.put(new Long(oid), pline);
				ht_zdispl.put(new Long(oid), pline);
				addToLastOpenLayerSet(pline);
				return null;
			} else if (type.equals("path")) {
				last_area_list.__addPath((String)ht_attributes.get("d"));
				return null;
			} else if (type.equals("area")) {
				last_area_list.__endReconstructing();
				last_area_list.__startReconstructing(new Long((String)ht_attributes.get("layer_id")).longValue());
				return null;
			} else if (type.equals("area_list")) {
				AreaList area = new AreaList(this.project, oid, ht_attributes, ht_links);
				area.addToDatabase(); // why? This looks like an onion
				last_area_list = area;
				last_displayable = area;
				ht_displayables.put(new Long(oid), area);
				ht_zdispl.put(new Long(oid), area);
				addToLastOpenLayerSet(area);
				return null;
			} else if (type.equals("ball_ob")) {
				// add a ball to the last open Ball
				if (null != last_ball) {
					last_ball.addBall(Double.parseDouble((String)ht_attributes.get("x")),
								   Double.parseDouble((String)ht_attributes.get("y")),
								   Double.parseDouble((String)ht_attributes.get("r")),
								   Long.parseLong((String)ht_attributes.get("layer_id")));
				}
				return null;
			} else if (type.equals("ball")) {
				Ball ball = new Ball(this.project, oid, ht_attributes, ht_links);
				ball.addToDatabase();
				last_ball = ball;
				last_displayable = ball;
				ht_displayables.put(new Long(oid), ball);
				ht_zdispl.put(new Long(oid), ball);
				addToLastOpenLayerSet(ball);
				return null;
			} else if (type.equals("dd_item")) {
				if (null != last_dissector) {
					last_dissector.addItem(Integer.parseInt((String)ht_attributes.get("tag")),
							       Integer.parseInt((String)ht_attributes.get("radius")),
							       (String)ht_attributes.get("points"));
				}
			} else if (type.equals("label")) {
				DLabel label = new DLabel(project, oid, ht_attributes, ht_links);
				label.addToDatabase();
				ht_displayables.put(new Long(oid), label);
				addToLastOpenLayer(label);
				last_displayable = label;
				return null;
			} else if (type.equals("patch")) {
				Patch patch = new Patch(project, oid, ht_attributes, ht_links);
				patch.addToDatabase();
				ht_displayables.put(new Long(oid), patch);
				addToLastOpenLayer(patch);
				last_patch = patch;
				last_displayable = patch;
				return null;
			} else if (type.equals("dissector")) {
				Dissector dissector = new Dissector(this.project, oid, ht_attributes, ht_links);
				dissector.addToDatabase();
				last_dissector = dissector;
				last_displayable = dissector;
				ht_displayables.put(new Long(oid), dissector);
				ht_zdispl.put(new Long(oid), dissector);
				addToLastOpenLayerSet(dissector);
			} else if (type.equals("layer")) {
				// find last open LayerSet, if any
				for (int i = al_layer_sets.size() -1; i>-1; i--) {
					LayerSet set = (LayerSet)al_layer_sets.get(i);
					Layer layer = new Layer(project, oid, ht_attributes);
					layer.addToDatabase();
					set.addSilently(layer);
					al_layers.add(layer);
					Object ot = ht_attributes.get("title");
					return new LayerThing(template_layer_thing, project, -1, (null == ot ? null : (String)ot), layer, null, null);
				}
			} else if (type.equals("layer_set")) {
				LayerSet set = new LayerSet(project, oid, ht_attributes, ht_links);
				last_displayable = set;
				set.addToDatabase();
				ht_displayables.put(new Long(oid), set);
				al_layer_sets.add(set);
				addToLastOpenLayer(set);
				Object ot = ht_attributes.get("title");
				return new LayerThing(template_layer_set_thing, project, -1, (null == ot ? null : (String)ot), set, null, null);
			} else if (type.equals("calibration")) {
				// find last open LayerSet if any
				for (int i = al_layer_sets.size() -1; i>-1; i--) {
					LayerSet set = (LayerSet)al_layer_sets.get(i);
					set.restoreCalibration(ht_attributes);
					return null;
				}
			} else if (type.equals("prop")) {
				// Add property to last created Displayable
				if (null != last_displayable) {
					last_displayable.setProperty((String)ht_attributes.get("key"), (String)ht_attributes.get("value"));
				}
			} else {
				Utils.log2("TMLHandler Unknown type: " + type);
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		// default:
		return null;
	}

	private void makeCoordinateTransform(String type, HashMap ht_attributes) {
		try {
			type = type.toLowerCase();
			CoordinateTransform ct = null;

			if (type.equals("ict_transform")) {
				ct = (CoordinateTransform) Class.forName((String)ht_attributes.get("class")).newInstance();
				ct.init((String)ht_attributes.get("data"));
			} else if (type.equals("ict_transform_list")) {
				last_ct_list = new CoordinateTransformList();
				ct = last_ct_list;
			}

			if (null != ct) {
				// Add it to the last CoordinateTransformList or, if absent, to the last Patch:
				if (null != last_ct_list) last_ct_list.add(ct);
				else if (null != last_patch) last_patch.setCoordinateTransformSilently(ct);
			}
		} catch (Exception e) {
			IJError.print(e);
		}
	}
}
