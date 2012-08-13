/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

import ij.process.ByteProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Connector;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Dissector;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Node;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Stack;
import ini.trakem2.display.Tag;
import ini.trakem2.display.Taggable;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.imaging.filters.IFilter;
import ini.trakem2.tree.DTDParser;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.Thing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ReconstructArea;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import mpicbg.models.TransformList;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.InvertibleCoordinateTransform;
import mpicbg.trakem2.transform.InvertibleCoordinateTransformList;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Creates the project objects from an XML file (TrakEM2 Markup Language Handler). */
public class TMLHandler extends DefaultHandler {

	private LayerThing root_lt = null;
	private ProjectThing root_pt = null;
	private TemplateThing root_tt = null;
	private TemplateThing project_tt = null;
	private TemplateThing template_layer_thing = null;
	private TemplateThing template_layer_set_thing = null;
	private Project project = null;
	private final FSLoader loader;
	private boolean skip = false;
	//private String base_dir;
	//private String xml_path;
	/** Stores the object and its String with coma-separated links, to construct links when all objects exist. */
	final private HashMap<Displayable,String> ht_links = new HashMap<Displayable,String>();

	final private ArrayList<Thing> al_open = new ArrayList<Thing>();
	final private ArrayList<Layer> al_layers = new ArrayList<Layer>();
	final private ArrayList<LayerSet> al_layer_sets = new ArrayList<LayerSet>();
	final private ArrayList<HashMap<String,String>> al_displays = new ArrayList<HashMap<String,String>>(); // contains HashMap instances with display data.
	/** To accumulate Displayable types for relinking and assigning their proper layer. */
	final private HashMap<Long,Displayable> ht_displayables = new HashMap<Long,Displayable>();
	final private HashMap<Long,ZDisplayable> ht_zdispl = new HashMap<Long,ZDisplayable>();
	final private HashMap<Long,ProjectThing> ht_oid_pt = new HashMap<Long,ProjectThing>();
	final private HashMap<ProjectThing,Boolean> ht_pt_expanded = new HashMap<ProjectThing,Boolean>();
	private Ball last_ball = null;
	private AreaList last_area_list = null;
	private long last_area_list_layer_id = -1;
	private Dissector last_dissector = null;
	private Stack last_stack = null;
	private Patch last_patch = null;
	private CoordinateTransform last_ct = null;
	private InvertibleCoordinateTransform last_ict = null;
	private final ArrayList<IFilter> last_patch_filters = new ArrayList<IFilter>(); 
	private Treeline last_treeline = null;
	private AreaTree last_areatree = null;
	private Connector last_connector = null;
	private Tree<?> last_tree = null;
	final private LinkedList<Taggable> taggables = new LinkedList<Taggable>();
	private ReconstructArea reca = null;
	private Node<?> last_root_node = null;
	final private LinkedList<Node<?>> nodes = new LinkedList<Node<?>>();
	final private Map<Long,List<Node<?>>> node_layer_table = new HashMap<Long,List<Node<?>>>();
	final private Map<Tree<?>,Node<?>> tree_root_nodes = new HashMap<Tree<?>,Node<?>>();
	final private Map<Color,Collection<Node<?>>> node_colors = new HashMap<Color,Collection<Node<?>>>();
	private StringBuilder last_treeline_data = null;
	private Displayable last_displayable = null;
	private StringBuilder last_annotation = null;
	final private ArrayList< TransformList< Object > > ct_list_stack = new ArrayList< TransformList< Object > >();
	private boolean open_displays = true;
	final private LinkedList<Runnable> legacy = new LinkedList<Runnable>();


	/** @param path The XML file that contains the project data in XML format.
	 *  @param loader The FSLoader for the project.
	 *  Expects the path with '/' as folder separator char.
	 */
	public TMLHandler(final String path, FSLoader loader) {
		this.loader = loader;
		//this.base_dir = path.substring(0, path.lastIndexOf('/') + 1); // not File.separatorChar: TrakEM2 uses '/' always
		//this.xml_path = path;
		final TemplateThing[] tt;
		try {
			tt = DTDParser.extractTemplate(path);
			if (null == tt) {
				Utils.log("TMLHandler: can't read DTD in file " + path);
				loader = null;
				return;
			}
		} catch (Exception e) {
			loader = null;
			IJError.print(e);
			return;
		}
		/*
		// debug:
		Utils.log2("ROOTS #####################");
		for (int k=0; k < tt.length; k++) {
			Utils.log2("tt root " + k + ": " + tt[k]);
		}
		*/
		this.root_tt = tt[0];

		// There should only be one root. There may be more than one
		// when objects in the DTD are declared but do not exist in a
		// TemplateThing hierarchy, such as ict_* and iict_*.
		// Find the first proper root:
		if (tt.length > 1) {
			final Pattern icts = Pattern.compile("^i{1,2}ct_transform.*$");
			this.root_tt = null;
			for (int k=0; k<tt.length; k++) {
				if (icts.matcher(tt[k].getType()).matches()) {
					continue;
				}
				this.root_tt = tt[k];
				break;
			}
		}
		// TODO the above should be a better filtering rule in the DTDParser.

		// create LayerThing templates
		this.template_layer_thing = new TemplateThing("layer");
		this.template_layer_set_thing = new TemplateThing("layer set");
		this.template_layer_set_thing.addChild(this.template_layer_thing);
		this.template_layer_thing.addChild(this.template_layer_set_thing);
		// create Project template
		this.project_tt = new TemplateThing("project");
		project_tt.addChild(this.root_tt);
		//TODO//project_tt.addAttribute("title", "Project");
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
		for (final Displayable d : ht_displayables.values()) {
			String olinks = ht_links.get(d);
			if (null == olinks) continue; // not linked
			String[] links = olinks.split(",");
			Long lid = null;
			for (int i=0; i<links.length; i++) {
				try {
					lid = new Long(links[i]);
				} catch (NumberFormatException nfe) {
					Utils.log2("Ignoring incorrectly formated link '" + links[i] + "' for ob " + d);
					continue;
				}
				Displayable partner = ht_displayables.get(lid);
				if (null != partner) d.link(partner, false);
				else Utils.log("TMLHandler: can't find partner with id=" + links[i] + " for Displayable with id=" + d.getId());
			}
		}

		// 1.2 - Reconstruct linked properties
		for (final Map.Entry<Displayable,Map<Long,Map<String,String>>> lpe : all_linked_props.entrySet()) {
			final Displayable origin = lpe.getKey();
			for (final Map.Entry<Long,Map<String,String>> e : lpe.getValue().entrySet()) {
				final Displayable target = ht_displayables.get(e.getKey());
				if (null == target) {
					Utils.log("Setting linked properties for origin " + origin.getId() + ":\n\t* Could not find target displayable #" + e.getKey());
					continue;
				}
				origin.setLinkedProperties(target, e.getValue());
			}
		}

		// 2 - Add Displayable objects to ProjectThing that can contain them
		for (final Map.Entry<Long,ProjectThing> entry : ht_oid_pt.entrySet()) {
			ProjectThing pt = entry.getValue();
			Object od = ht_displayables.remove(entry.getKey());
			//Utils.log("==== processing: Displayable [" + od + "]  vs. ProjectThing [" + pt + "]");
			if (null != od) {
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
		for (final ZDisplayable zd : ht_zdispl.values()) {
			//zd.setLayer((Layer)zd.getLayerSet().getLayers().get(0));
			zd.setLayer(zd.getLayerSet().getLayer(0));
		}

		// 4 - Assign layers to Treeline nodes
		for (final Layer la : al_layers) {
			final List<Node<?>> list = node_layer_table.remove(la.getId());
			if (null == list) continue;
			for (final Node<?> nd : list) nd.setLayer(la);
		}
		if (!node_layer_table.isEmpty()) {
			Utils.log("ERROR: node_layer_table is not empty!");
		}
		// 5 - Assign root nodes to Treelines, now that all nodes have a layer
		for (final Map.Entry<Tree<?>,Node<?>> e : tree_root_nodes.entrySet()) {
			if (null == e.getValue()) {
				//Utils.log2("Ignoring, applies to new Treeline format only.");
				continue;
			}
			// Can't compile with <?>
			e.getKey().setRoot((Node)e.getValue()); // will generate node caches of each Treeline
		}
		tree_root_nodes.clear();
		// Assign colors to nodes
		for (final Map.Entry<Color,Collection<Node<?>>> e : node_colors.entrySet()) {
			for (final Node<?> nd : e.getValue()) {
				nd.setColor(e.getKey());
			}
		}
		node_colors.clear();

		// 6 - Run legacy operations
		for (final Runnable r : legacy) {
			r.run();
		}

		try {

			// Create a table with all layer ids vs layer instances:
			final HashMap<Long,Layer> ht_lids = new HashMap<Long,Layer>();
			for (final Layer layer : al_layers) {
				ht_lids.put(new Long(layer.getId()), layer);
			}

			// Spawn threads to recreate buckets, starting from the subset of displays to open
			int n = Runtime.getRuntime().availableProcessors();
			switch (n) {
				case 1:
					break;
				case 2:
				case 3:
				case 4:
					n--;
					break;
				default:
					n -= 2;
					break;
			}
			final ExecutorService exec = Utils.newFixedThreadPool(n, "TMLHandler-recreateBuckets");

			final Set<Long> dlids = new HashSet<Long>();
			final LayerSet layer_set = (LayerSet) root_lt.getObject();

			final List<Future<?>> fus = new ArrayList<Future<?>>();
			final List<Future<?>> fus2 = new ArrayList<Future<?>>();

			for (final HashMap<String,String> ht_attributes : al_displays) {
				String ob = ht_attributes.get("layer_id");
				if (null == ob) continue;
				final Long lid = new Long(ob);
				dlids.add(lid);
				final Layer la = ht_lids.get(lid);
				if (null == la) {
					ht_lids.remove(lid);
					continue;
				}
				// to open later:
				new Display(project, Long.parseLong(ht_attributes.get("id")), la, ht_attributes);
				fus.add(exec.submit(new Runnable() { public void run() {
					la.recreateBuckets();
				}}));
			}

			fus.add(exec.submit(new Runnable() { public void run() {
				layer_set.recreateBuckets(false); // only for ZDisplayable
			}}));

			// Ensure launching:
			if (dlids.isEmpty() && layer_set.size() > 0) {
				dlids.add(layer_set.getLayer(0).getId());
			}

			final List<Layer> layers = layer_set.getLayers();
			for (final Long lid : new HashSet<Long>(dlids)) {
				fus.add(exec.submit(new Runnable() { public void run() {
					int start = layer_set.indexOf(layer_set.getLayer(lid.longValue()));
					int next = start + 1;
					int prev = start -1;
					while (next < layer_set.size() || prev > -1) {
						if (prev > -1) {
							final Layer lprev = layers.get(prev);
							synchronized (dlids) {
								if (dlids.add(lprev.getId())) { // returns true if not there already
									fus2.add(exec.submit(new Runnable() { public void run() {
										lprev.recreateBuckets();
									}}));
								}
							}
							prev--;
						}
						if (next < layers.size()) {
							final Layer lnext = layers.get(next);
							synchronized (dlids) {
								if (dlids.add(lnext.getId())) { // returns true if not there already
									fus2.add(exec.submit(new Runnable() { public void run() {
										lnext.recreateBuckets();
									}}));
								}
							}
							next++;
						}
					}
					Utils.log2("done recreateBuckets chunk");
				}}));
			}
			Utils.wait(fus);
			exec.submit(new Runnable() { public void run() {
				Utils.log2("waiting for TMLHandler fus...");
				Utils.wait(fus2);
				Utils.log2("done waiting TMLHandler fus.");
				exec.shutdown();
			}});
		} catch (Throwable t) {
			IJError.print(t);
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
		if (0 == counter % 100) { // davi-experimenting: don't talk so much when you have > 600,000 patches to load!
			Utils.showStatus("Loading " + counter, false);
		}
		try {
			// failsafe:
			qualified_name = qualified_name.toLowerCase();

			final HashMap<String,String> ht_attributes = new HashMap<String,String>();
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
				this.project = new Project(Long.parseLong(ht_attributes.remove("id")), ht_attributes.remove("title"));
				this.project.setTempLoader(this.loader); // temp, but will be the same anyway
				this.project.parseXMLOptions(ht_attributes);
				this.project.addToDatabase(); // register id
				String title = ht_attributes.get("title");
				if (null != title) this.project.setTitle(title);
				// Add all unique TemplateThing types to the project
				for (Iterator<TemplateThing> it = root_tt.getUniqueTypes(new HashMap<String,TemplateThing>()).values().iterator(); it.hasNext(); ) {
					this.project.addUniqueType(it.next());
				}
				this.project.addUniqueType(this.project_tt);
				this.root_pt = new ProjectThing(this.project_tt, this.project, this.project);
				// Add a project pointer to all template things
				this.root_tt.addToDatabase(this.project);
				thing = root_pt;
			} else if (qualified_name.startsWith("ict_transform")||qualified_name.startsWith("iict_transform")) {
				makeCoordinateTransform(qualified_name, ht_attributes);
			} else if (!qualified_name.equals("trakem2")) {
				// Any abstract object
				thing = makeProjectThing(qualified_name, ht_attributes);
			}
			if (null != thing) {
				// get the previously open thing and add this new_thing to it as a child
				int size = al_open.size();
				if (size > 0) {
					Thing parent = al_open.get(size -1);
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
			Thing thing = al_open.get(i);
			if (thing.getType().toLowerCase().equals(qualified_name)) {
				al_open.remove(i);
				break;
			}
		}

		if (null != last_annotation && null != last_displayable) {
			last_displayable.setAnnotation(last_annotation.toString().trim().replaceAll("&lt;", "<"));
			last_annotation = null;
		}

		// terminate non-single clause objects
		if (orig_qualified_name.equals("t2_node")) {
			// Remove one node from the stack
			nodes.removeLast();
			taggables.removeLast();
		} else if (orig_qualified_name.equals("t2_connector")) {
			if (null != last_connector) {
				tree_root_nodes.put(last_connector, last_root_node);
				last_root_node = null;
				last_connector = null;
				last_tree = null;
				nodes.clear();
			}
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_area_list")) {
			last_area_list = null;
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_area")) {
			if (null != reca) {
				if (null != last_area_list) {
					last_area_list.addArea(last_area_list_layer_id, reca.getArea()); // it's local
				} else {
					((AreaTree.AreaNode)nodes.getLast()).setData(reca.getArea());
				}
				reca = null;
			}
		} else if (orig_qualified_name.equals("ict_transform_list")) {
			ct_list_stack.remove( ct_list_stack.size() - 1 );
		} else if (orig_qualified_name.equals("t2_patch")) {
			if (last_patch_filters.size() > 0) {
				last_patch.setFilters(last_patch_filters.toArray(new IFilter[last_patch_filters.size()]));
			}
			if (null != last_ct) {
				last_patch.setCoordinateTransformSilently(last_ct);
				last_ct = null;
			} else if (!last_patch.checkCoordinateTransformFile()) {
				Utils.log("ERROR: could not find a file for the coordinate transform #" + last_patch.getCoordinateTransformId() + " of Patch #" + last_patch.getId());
			}
			if (!last_patch.checkAlphaMaskFile()) {
				Utils.log("ERROR: could not find a file for the alpha mask #" + last_patch.getAlphaMaskId() + " of Patch #" + last_patch.getId());
			}
			last_patch = null;
			last_patch_filters.clear();
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_ball")) {
			last_ball = null;
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_dissector")) {
			last_dissector = null;
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_treeline")) {
			if (null != last_treeline) {
				// old format:
				if (null == last_root_node && null != last_treeline_data && last_treeline_data.length() > 0) {
					last_root_node = parseBranch(Utils.trim(last_treeline_data));
					last_treeline_data = null;
				}
				// new
				tree_root_nodes.put(last_treeline, last_root_node);
				last_root_node = null;
				// always:
				last_treeline = null;
				last_tree = null;
				nodes.clear();
			}
			last_displayable = null;
		} else if (orig_qualified_name.equals("t2_areatree")) {
			if (null != last_areatree) {
				tree_root_nodes.put(last_areatree, last_root_node);
				last_root_node = null;
				last_areatree = null;
				last_tree = null;
				nodes.clear(); // the absence of this line would have made the nodes list grow with all nodes of all areatrees, which is ok but consumes memory
			}
			last_displayable = null;
		} else if (orig_qualified_name.equals( "t2_stack" )) {
			if (null != last_ict) {
				last_stack.setInvertibleCoordinateTransformSilently(last_ict);
				last_ict = null;
			}
			last_stack = null;
			last_displayable = null;
		} else if (in(orig_qualified_name, all_displayables)) {
			last_displayable = null;
		}
	}

	static private final String[] all_displayables = new String[]{"t2_area_list", "t2_patch", "t2_pipe", "t2_polyline", "t2_ball", "t2_label", "t2_dissector", "t2_profile", "t2_stack", "t2_treeline", "t2_areatree", "t2_connector"};

	private final boolean in(final String s, final String[] all) {
		for (int i=all.length-1; i>-1; i--) {
			if (s.equals(all[i])) return true;
		}
		return false;
	}

	public void characters(char[] c, int start, int length) {
		if (null != last_treeline) {
			// for old format:
			last_treeline_data.append(c, start, length);
		} else if (null != last_annotation) {
			last_annotation.append(c, start, length);
		}
	}

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

	private ProjectThing makeProjectThing(String type, final HashMap<String,String> ht_attributes) {
		try {
			type = type.toLowerCase();

			// debug:
			//Utils.log2("TMLHander.makeProjectThing for type=" + type);

			long id = -1;
			final String sid = ht_attributes.remove("id");
			if (null != sid) {
				id = Long.parseLong(sid);
			}
			long oid = -1;
			final String soid = ht_attributes.remove("oid");
			if (null != soid) {
				oid = Long.parseLong(soid);
			}
			Boolean expanded = new Boolean(false); // default: collapsed
			Object eob = ht_attributes.remove("expanded");
			if (null != eob) {
				expanded = new Boolean((String)eob);
			}
			String title = ht_attributes.remove("title");
			
			TemplateThing tt = this.project.getTemplateThing(type);
			if (null == tt) {
				Utils.log("No template for type " + type);
				return null;
			}
			ProjectThing pt = new ProjectThing(tt, this.project, id, null == title ? type : title, null);
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
		for (int i = al_layers.size() -1; i>-1;) {
			al_layers.get(i).addSilently(d);
			break;
		}
	}
	private void addToLastOpenLayerSet(ZDisplayable zd) {
		// find last open layer set
		for (int i = al_layer_sets.size() -1; i>-1;) {
			al_layer_sets.get(i).addSilently(zd);
			break;
		}
	}

	final private Map<Displayable,Map<Long,Map<String,String>>> all_linked_props = new HashMap<Displayable,Map<Long,Map<String,String>>>();

	private void putLinkedProperty(final Displayable origin, final HashMap<String,String> ht_attributes) {
		final String stid = ht_attributes.get("target_id");
		if (null == stid) {
			Utils.log2("Can't setLinkedProperty to null target id for origin Displayable " + origin.getId());
			return;
		}
		Long target_id;
		try {
			target_id = Long.parseLong(stid);
		} catch (NumberFormatException e) {
			Utils.log2("Unparseable target_id: " + stid + ", for origin " + origin.getId());
			return;
		}
		String key = ht_attributes.get("key");
		String value = ht_attributes.get("value");
		if (null == key || null == value) {
			Utils.log("Skipping linked property for Displayable " + origin.getId() + ": null key or value");
			return;
		}
		key = key.trim();
		value = value.trim();
		if (0 == key.length() || 0 == value.length()) {
			Utils.log("Skipping linked property for Displayable " + origin.getId() + ": empty key or value");
			return;
		}
		Map<Long,Map<String,String>> linked_props = all_linked_props.get(origin);
		if (null == linked_props) {
			linked_props = new HashMap<Long,Map<String,String>>();
			all_linked_props.put(origin, linked_props);
		}
		Map<String,String> p = linked_props.get(target_id);
		if (null == p) {
			p = new HashMap<String,String>();
			linked_props.put(target_id, p);
		}
		p.put(key, value);
	}

	private LayerThing makeLayerThing(String type, final HashMap<String,String> ht_attributes) {
		try {
			type = type.toLowerCase();
			if (0 == type.indexOf("t2_")) {
				type = type.substring(3);
			}
			//long id = -1;
			//final String sid = ht_attributes.get("id");
			//if (null != sid) id = Long.parseLong(sid);
			long oid = -1;
			final String soid = ht_attributes.get("oid");
			if (null != soid) oid = Long.parseLong(soid);

			if (type.equals("node")) {
				if (null == last_tree) {
					throw new NullPointerException("Can't create a node for null last_tree!");
				}
				final Node<?> node = last_tree.newNode(ht_attributes);
				taggables.add(node);
				// Put node into the list of nodes with that layer id, to update to proper Layer pointer later
				final long ndlid = Long.parseLong(ht_attributes.get("lid"));
				List<Node<?>> list = node_layer_table.get(ndlid);
				if (null == list) {
					list = new ArrayList<Node<?>>();
					node_layer_table.put(ndlid, list);
				}
				list.add(node);
				// Set node as root node or add as child to last node in the stack
				if (null == last_root_node) {
					last_root_node = node;
				} else {
					final String sconf = ht_attributes.get("c");
					nodes.getLast().add((Node)node, null == sconf ? Node.MAX_EDGE_CONFIDENCE : Byte.parseByte(sconf));
				}
				// color?
				final String scolor = ht_attributes.get("color");
				if (null != scolor) {
					final Color color = Utils.getRGBColorFromHex(scolor);
					Collection<Node<?>> nodes = node_colors.get(color);
					if (null == nodes) {
						nodes = new ArrayList<Node<?>>();
						node_colors.put(color, nodes);
					}
					nodes.add(node);
				}
				// Put node into stack of nodes (to be removed on closing the tag)
				nodes.add(node);
			} else if (type.equals("profile")) {
				Profile profile = new Profile(this.project, oid, ht_attributes, ht_links);
				profile.addToDatabase();
				ht_displayables.put(oid, profile);
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
			} else if (type.equals("connector")) {
				final Connector con = new Connector(this.project, oid, ht_attributes, ht_links);
				if (ht_attributes.containsKey("origin")) {
					legacy.add(new Runnable() {
						public void run() {
							con.readLegacyXML(al_layer_sets.get(al_layer_sets.size()-1), ht_attributes, ht_links);
						}
					});
				}
				con.addToDatabase();
				last_connector = con;
				last_tree = con;
				last_displayable = con;
				ht_displayables.put(new Long(oid), con);
				ht_zdispl.put(new Long(oid), con);
				addToLastOpenLayerSet(con);
				return null;
			} else if (type.equals("path")) {
				if (null != reca) {
					reca.add(ht_attributes.get("d"));
					return null;
				}
				return null;
			} else if (type.equals("area")) {
				reca = new ReconstructArea();
				if (null != last_area_list) {
					last_area_list_layer_id = Long.parseLong(ht_attributes.get("layer_id"));
				}
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
			} else if (type.equals("tag")) {
				Taggable t = taggables.getLast();
				if (null != t) {
					Object ob = ht_attributes.get("key");
					int keyCode = KeyEvent.VK_T; // defaults to 't'
					if (null != ob) keyCode = (int)((String)ob).toUpperCase().charAt(0); // KeyEvent.VK_U is char U, not u
					Tag tag = al_layer_sets.get(al_layer_sets.size()-1).putTag(ht_attributes.get("name"), keyCode);
					if (null != tag) t.addTag(tag); // could be null if name is not found
				}
			} else if (type.equals("ball_ob")) {
				// add a ball to the last open Ball
				if (null != last_ball) {
					last_ball.addBall(Double.parseDouble(ht_attributes.get("x")),
								   Double.parseDouble(ht_attributes.get("y")),
								   Double.parseDouble(ht_attributes.get("r")),
								   Long.parseLong(ht_attributes.get("layer_id")));
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
			} else if (type.equals("stack")) {
				Stack stack = new Stack(this.project, oid, ht_attributes, ht_links);
				stack.addToDatabase();
				last_stack = stack;
				last_displayable = stack;
				ht_displayables.put(new Long(oid), stack);
				ht_zdispl.put( new Long(oid), stack );
				addToLastOpenLayerSet( stack );
			} else if (type.equals("treeline")) {
				Treeline tline = new Treeline(this.project, oid, ht_attributes, ht_links);
				tline.addToDatabase();
				last_treeline = tline;
				last_tree = tline;
				last_treeline_data = new StringBuilder();
				last_displayable = tline;
				ht_displayables.put(oid, tline);
				ht_zdispl.put(oid, tline);
				addToLastOpenLayerSet(tline);
			} else if (type.equals("areatree")) {
				AreaTree art = new AreaTree(this.project, oid, ht_attributes, ht_links);
				art.addToDatabase();
				last_areatree = art;
				last_tree = art;
				last_displayable = art;
				ht_displayables.put(oid, art);
				ht_zdispl.put(oid, art);
				addToLastOpenLayerSet(art);
			} else if (type.equals("dd_item")) {
				if (null != last_dissector) {
					last_dissector.addItem(Integer.parseInt(ht_attributes.get("tag")),
							       Integer.parseInt(ht_attributes.get("radius")),
							       ht_attributes.get("points"));
				}
			} else if (type.equals("label")) {
				DLabel label = new DLabel(project, oid, ht_attributes, ht_links);
				label.addToDatabase();
				ht_displayables.put(new Long(oid), label);
				addToLastOpenLayer(label);
				last_displayable = label;
				return null;
			} else if (type.equals("annot")) {
				last_annotation = new StringBuilder();
				return null;
			} else if (type.equals("patch")) {
				Patch patch = new Patch(project, oid, ht_attributes, ht_links);
				patch.addToDatabase();
				ht_displayables.put(new Long(oid), patch);
				addToLastOpenLayer(patch);
				last_patch = patch;
				last_displayable = patch;
				checkAlphaMasks(patch);
				return null;
			} else if (type.equals("filter")) {
				last_patch_filters.add(newFilter(ht_attributes));
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
				for (int i = al_layer_sets.size() -1; i>-1;) {
					LayerSet set = al_layer_sets.get(i);
					Layer layer = new Layer(project, oid, ht_attributes);
					layer.addToDatabase();
					set.addSilently(layer);
					al_layers.add(layer);
					Object ot = ht_attributes.get("title");
					return new LayerThing(template_layer_thing, project, -1, (null == ot ? null : (String)ot), layer, null);
				}
			} else if (type.equals("layer_set")) {
				LayerSet set = new LayerSet(project, oid, ht_attributes, ht_links);
				last_displayable = set;
				set.addToDatabase();
				ht_displayables.put(new Long(oid), set);
				al_layer_sets.add(set);
				addToLastOpenLayer(set);
				Object ot = ht_attributes.get("title");
				return new LayerThing(template_layer_set_thing, project, -1, (null == ot ? null : (String)ot), set, null);
			} else if (type.equals("calibration")) {
				// find last open LayerSet if any
				for (int i = al_layer_sets.size() -1; i>-1;) {
					LayerSet set = al_layer_sets.get(i);
					set.restoreCalibration(ht_attributes);
					return null;
				}
			} else if (type.equals("prop")) {
				// Add property to last created Displayable
				if (null != last_displayable) {
					last_displayable.setProperty(ht_attributes.get("key"), ht_attributes.get("value"));
				}
			} else if (type.equals("linked_prop")) {
				// Add linked property to last created Displayable. Has to wait until the Displayable ids have been resolved to instances.
				if (null != last_displayable) {
					putLinkedProperty(last_displayable, ht_attributes);
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

	/** 
	 * Backwards compatibility for alpha masks:
	 * create the file path as it was before, and see if the file exists.
	 * If it does, set it to the patch as the alpha mask.
	 */
	private void checkAlphaMasks(Patch patch) {
		if (0 == patch.getAlphaMaskId()) {
			final File f = new File(patch.getImageFilePath());
			final String path = new StringBuilder(loader.getMasksFolder()).append(FSLoader.createIdPath(Long.toString(patch.getId()), f.getName(), ".zip")).toString();
			if (new File(path).exists()) {
				try {
					if (patch.setAlphaMask((ByteProcessor)loader.openImagePlus(path).getProcessor().convertToByte(false))) {
						// On success, queue the file for deletion when saving the XML file
						loader.markStaleFileForDeletionUponSaving(path);
						Utils.log("Upgraded alpha mask for patch #" + patch.getId());
					} else {
						Utils.log("ERROR: failed to upgrade alpha mask for patch #" + patch.getId());
					}
				} catch (Exception e) {
					Utils.log("FAILED to restore alpha mask for patch #" + patch.getId() + ":");
					IJError.print(e);
				}
			}
		}
	}

	final private IFilter newFilter(final HashMap<String, String> ht_attributes) {
		try {
			return (IFilter) Class.forName(ht_attributes.remove("class")).getConstructor(Map.class).newInstance(ht_attributes);
		} catch (Exception e) {
			throw new RuntimeException("Could not create filter for Patch #" + last_patch.getId(), e);
		}
	}

	final private void makeCoordinateTransform( String type, final HashMap<String,String> ht_attributes )
	{
		try
		{
			type = type.toLowerCase();
			
			if ( type.equals( "ict_transform" ) )
			{
				final CoordinateTransform ct = ( CoordinateTransform )Class.forName( ht_attributes.get( "class" ) ).newInstance();
				ct.init( ht_attributes.get( "data" ) );
				if ( ct_list_stack.isEmpty() )
				{
					if ( last_patch != null )
						last_ct = ct;
				}
				else
				{
					ct_list_stack.get( ct_list_stack.size() - 1 ).add( ct );
				}
			}
			else if ( type.equals( "iict_transform" ) )
			{
				final InvertibleCoordinateTransform ict = ( InvertibleCoordinateTransform )Class.forName( ht_attributes.get( "class" ) ).newInstance();
				ict.init( ht_attributes.get( "data" ) );
				if ( ct_list_stack.isEmpty() )
				{
					if ( last_patch != null )
						last_ct = ict;
					else if ( last_stack != null )
						last_ict = ict;
				}
				else
				{
					ct_list_stack.get( ct_list_stack.size() - 1 ).add( ict );
				}
			}
			else if ( type.equals( "ict_transform_list" ) )
			{
				final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
				if ( ct_list_stack.isEmpty() )
				{
					if ( last_patch != null )
						last_ct = ctl;
				}
				else
					ct_list_stack.get( ct_list_stack.size() - 1 ).add( ctl );
				ct_list_stack.add( ( TransformList )ctl );
			}
			else if ( type.equals( "iict_transform_list" ) )
			{
				final InvertibleCoordinateTransformList< InvertibleCoordinateTransform > ictl = new InvertibleCoordinateTransformList< InvertibleCoordinateTransform >();
				if ( ct_list_stack.isEmpty() )
				{
					if ( last_patch != null )
						last_ct = ictl;
					else if ( last_stack != null )
						last_ict = ictl;
				}
				else
					ct_list_stack.get( ct_list_stack.size() - 1 ).add( ictl );
				ct_list_stack.add( ( TransformList )ictl );
			}
		}
		catch ( Exception e ) { IJError.print(e); }
	}

	private final Node<Float> parseBranch(final String s) {
		// 1 - Parse the slab
		final int first = s.indexOf('(');
		final int last = s.indexOf(')', first+1);
		final String[] coords = s.substring(first+1, last).split(" ");
		Node<Float> prev = null;
		final List<Node<Float>> nodes = new ArrayList<Node<Float>>();
		for (int i=0; i<coords.length; i+=3) {
			long lid = Long.parseLong(coords[i+2]);
			Node<Float> nd = new Treeline.RadiusNode(Float.parseFloat(coords[i]), Float.parseFloat(coords[i+1]), null);
			nd.setData(0f);
			nodes.add(nd);
			// Add to node_layer_table for later assignment of a Layer object to the node
			List<Node<?>> list = node_layer_table.get(lid);
			if (null == list) {
				list = new ArrayList<Node<?>>();
				node_layer_table.put(lid, list);
			}
			list.add(nd);
			//
			if (null == prev) prev = nd;
			else {
				prev.add(nd, Node.MAX_EDGE_CONFIDENCE);
				prev = nd; // new parent
			}
		}
		// 2 - Parse the branches
		final int ibranches = s.indexOf(":branches", last+1);
		if (-1 != ibranches) {
			final int len = s.length();
			int open = s.indexOf('{', ibranches + 9);
			while (-1 != open) {
				int end = open + 1; // don't read the first curly bracket
				int level = 1;
				for(; end < len; end++) {
					switch (s.charAt(end)) {
						case '{': level++; break;
						case '}': level--; break;
					}
					if (0 == level) break;
				}
				// Add the root node of the new branch to the node at branch index
				int openbranch = s.indexOf('{', open+1);
				int branchindex = Integer.parseInt(s.substring(open+1, openbranch-1));
				nodes.get(branchindex).add(parseBranch(s.substring(open, end)), Node.MAX_EDGE_CONFIDENCE);
				open = s.indexOf('{', end+1);
			}
		}
		return nodes.get(0);
	}
}
