/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005 Albert Cardona and Rodney Douglas.

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

package ini.trakem2;


import ij.IJ;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.io.DirectoryChooser;
import ini.trakem2.display.*;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.DBLoader;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.tree.DTDParser;
import ini.trakem2.tree.DNDTree;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.LayerTree;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.TemplateTree;
import ini.trakem2.tree.Thing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.vector.Compare;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import javax.swing.tree.*;
import javax.swing.JTree;
import java.awt.Rectangle;

/** The top-level class in control. */
public class Project extends DBObject {

	/* // using virtual frame buffer instead, since the trees are needed
	public static final boolean headless = isHeadless();

	private static boolean isHeadless() {
		return Boolean.parseBoolean(System.getProperty("java.awt.headless"));
	}
	*/

	/** Keep track of all open projects. */
	static private ArrayList<Project> al_open_projects = new ArrayList<Project>();

	private Loader loader;

	private TemplateTree template_tree = null;

	private ProjectTree project_tree = null;

	/** The root Thing that holds the project. */
	private ProjectThing root_pt;

	/** The root LayerThing of the LayerTree. */
	private LayerThing root_lt;

	/** The root TemplateThing of the TemplateTree. */
	private TemplateThing root_tt;

	/** The root LayerSet that holds the layers. */
	private LayerSet layer_set;

	static private TemplateThing layer_template = null;
	static private TemplateThing layer_set_template = null;

	/** The hashtable of unique TemplateThing types; the key is the type (String). */
	private HashMap<String,TemplateThing> ht_unique_tt = null;

	private LayerTree layer_tree = null;

	private String title = "Project"; // default  // TODO should be an attribute in the ProjectThing that holds it

	private final HashMap<String,String> ht_props = new HashMap<String,String>();

	/** Intercept ImageJ menu commands if the front image is a FakeImagePlus. */
	static private final ImageJCommandListener command_listener = new ImageJCommandListener();

	/** Universal near-unique id for this project, consisting of:
	 *  <creation-time>.<storage-folder-hashcode>.<username-hashcode> */
	private String unuid = null;

	/** The constructor used by the static methods present in this class. */
	private Project(Loader loader) {
		super(loader);
		this.loader = loader;
		this.project = this; // for the superclass DBObject
		loader.addToDatabase(this);
	}

	/** Constructor used by the Loader to find projects. These projects contain no loader. */
	public Project(long id, String title) {
		super(null, id);
		this.title = title;
		this.project = this;
	}

	static public Project getProject(final String title) {
		for (final Project pr : al_open_projects) {
			if (pr.title.equals(title)) return pr;
		}
		return null;
	}

	/** Return a copy of the list of all open projects. */
	static public ArrayList<Project> getProjects() {
		return (ArrayList<Project>)al_open_projects.clone();
	}

	/** Create a new PostgreSQL-based TrakEM2 project. */
	static public Project newDBProject() {
		if (Utils.wrongImageJVersion()) return null;
		// create
		DBLoader loader = new DBLoader();
		// check connection settings
		if (!loader.isReady()) return null;
		// check connection
		if (!loader.isConnected()) {
			Utils.showMessage("Can't talk to database.");
			return null;
		}
		return createNewProject(loader, true);
	}

	/** Open a TrakEM2 project from the database. Queries the database for existing projects and if more than one, asks which one to open. */
	static public Project openDBProject() {
		if (Utils.wrongImageJVersion()) return null;
		DBLoader loader = new DBLoader();
		if (!loader.isReady()) return null;
		// check connection
		if (!loader.isConnected()) {
			Utils.showMessage("Can't talk to database.");
			loader.destroy();
			return null;
		}
		// query the database for existing projects
		Project[] projects = loader.getProjects();
		if (null == projects) {
			Utils.showMessage("Can't talk to database (null list).");
			loader.destroy();
			return null;
		}
		Project project = null;
		if (null == projects) {
			Utils.showMessage("Can't fetch list of projects.");
			loader.destroy();
			return null;
		} else if (0 == projects.length) {
			Utils.showMessage("No projects in this database.");
			loader.destroy();
			return null; 
		} else if (1 == projects.length) {
			project = projects[0];
		} else {
			// ask to choose one
			String[] titles = new String[projects.length];
			for (int i=0; i<projects.length; i++) {
				titles[i] = projects[i].title;
			}
			GenericDialog gd = new GenericDialog("Choose");
			gd.addMessage("Choose project to open:");
			gd.addChoice("project: ", titles, titles[titles.length -1]);
			gd.showDialog();
			if (gd.wasCanceled()) {
				loader.destroy();
				return null;
			}
			project = projects[gd.getNextChoiceIndex()];
		}
		// check if the selected project is open already
		Iterator it = al_open_projects.iterator();
		while (it.hasNext()) {
			Project p = (Project)it.next();
			if (loader.isIdenticalProjectSource(p.loader) && p.id == project.id && p.title.equals(project.title)) {
				Utils.showMessage("A project with title " + p.title + " and id " + p.id + " from the same database is already open.");
				loader.destroy();
				return null;
			}
		}

		// now, open the selected project

		// assign loader
		project.loader = loader;
		// grab the XML template
		TemplateThing template_root = loader.getTemplateRoot(project);
		if (null == template_root) {
			Utils.showMessage("Failed to retrieve the template tree.");
			project.destroy();
			return null;
		}
		project.template_tree = new TemplateTree(project, template_root);
		project.ht_unique_tt = template_root.getUniqueTypes(new HashMap<String,TemplateThing>());
		// create the project Thing, to be root of the whole user Thing tree (and load all its objects)
		HashMap hs_d = new HashMap(); // to collect all created displayables, and  then reassign to the proper layers.
		try {
			// create a template for the project Thing
			TemplateThing project_template = new TemplateThing("project");
			project.ht_unique_tt.put("project", project_template);
			project_template.addChild(template_root);
			project.root_pt = loader.getRootProjectThing(project, template_root, project_template, hs_d);
			// restore parent/child and attribute ownership and values (now that all Things exist)
			project.root_pt.setup();
		} catch (Exception e) {
			Utils.showMessage("Failed to retrieve the Thing tree for the project.");
			IJError.print(e); 
			project.destroy();
			return null;
		}
		// create the user objects tree
		project.project_tree = new ProjectTree(project, project.root_pt);
		// restore the expanded state of each node
		loader.restoreNodesExpandedState(project);

		// create the layers templates
		project.createLayerTemplates();
		// fetch the root layer thing and the root layer set (will load all layers and layer sets, with minimal contents of patches; gets the basic objects -profile, pipe, etc.- from the project.root_pt). Will open all existing displays for each layer.
		LayerThing root_layer_thing = null;
		try {
			root_layer_thing = loader.getRootLayerThing(project, project.root_pt, project.layer_set_template, project.layer_template);
			if (null == root_layer_thing) {
				project.destroy();
				Utils.showMessage("Could not retrieve the root layer thing.");
				return null;
			}
			// set the child/parent relationships now that everything exists
			root_layer_thing.setup();
			project.layer_set = (LayerSet)root_layer_thing.getObject();
			if (null == project.layer_set) {
				project.destroy();
				Utils.showMessage("Could not retrieve the root layer set.");
				return null;
			}
			project.layer_set.setup(); // set the active layer to each ZDisplayable

			// debug:
			//Utils.log2("$$$ root_lt: " + root_layer_thing + "   ob: " + root_layer_thing.getObject().getClass().getName() + "\n children: " + ((LayerSet)root_layer_thing.getObject()).getLayers().size());

			project.layer_tree = new LayerTree(project, root_layer_thing);
			project.root_lt = root_layer_thing;
		} catch (Exception e) {
			Utils.showMessage("Failed to retrieve the Layer tree for the project.");
			IJError.print(e);
			project.destroy();
			return null;
		}

		// if all when well, register as open:
		al_open_projects.add(project);
		// create the project control window, containing the trees in a double JSplitPane
		ControlWindow.add(project, project.template_tree, project.project_tree, project.layer_tree);
		// now open the displays that were stored for later, if any:
		Display.openLater();

		return project;
	}


	/** Creates a new project to be based on .xml and image files, not a database. Images are left where they are, keeping the path to them. If the arg equals 'blank', then no template is asked for. */
	static public Project newFSProject(String arg) {
		return newFSProject(arg, null);
	}

	static public Project newFSProject(String arg, TemplateThing template_root) {
		return newFSProject(arg, null, null);
	}

	/** Creates a new project to be based on .xml and image files, not a database. Images are left where they are, keeping the path to them. If the arg equals 'blank', then no template is asked for; if template_root is not null that is used; else, a template file is asked for. */
	static public Project newFSProject(String arg, TemplateThing template_root, String storage_folder) {
		if (Utils.wrongImageJVersion()) return null;
		try {
			String dir_project = storage_folder;
			if (null == dir_project || !new File(dir_project).isDirectory()) {
				DirectoryChooser dc = new DirectoryChooser("Select storage folder");
				dir_project = dc.getDirectory();
				if (null == dir_project) return null; // user cancelled dialog
				if (!Loader.canReadAndWriteTo(dir_project)) {
					Utils.showMessage("Can't read/write to the selected storage folder.\nPlease check folder permissions.");
					return null;
				}
			}
			FSLoader loader = new FSLoader(dir_project);
			if (!loader.isReady()) return null;
			Project project = createNewProject(loader, !("blank".equals(arg) || "amira".equals(arg)), template_root);

			// help the helpless users:
			if (null != project && ControlWindow.isGUIEnabled()) {
				Utils.log2("Creating automatic Display.");
				// add a default layer
				Layer layer = new Layer(project, 0, 1, project.layer_set);
				project.layer_set.add(layer);
				project.layer_tree.addLayer(project.layer_set, layer);
				Display.createDisplay(project, layer);
			}
			try {
				Thread.sleep(200); // waiting cheaply for asynchronous swing calls
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}

			if (arg.equals("amira") || arg.equals("stack")) {
				// forks into a task thread
				loader.importStack(project.layer_set.getLayer(0), null, true);
			}

			return project;
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	static public Project openFSProject(final String path) {
		return openFSProject(path, true);
	}

	/** Opens a project from an .xml file. If the path is null it'll be asked for.
	 *  Only one project may be opened at a time.
	 */
	synchronized static public Project openFSProject(final String path, final boolean open_displays) {
		if (Utils.wrongImageJVersion()) return null;
		final FSLoader loader = new FSLoader();
		final Object[] data = loader.openFSProject(path, open_displays);
		if (null == data) {
			return null;
		}
		//Macro.setOptions("xml_path=" + loader.getProjectXMLPath()); // TODO gets overwritten by the file dialog, but still, the value is the same. Only the key is different.
		final TemplateThing root_tt = (TemplateThing)data[0];
		final ProjectThing root_pt = (ProjectThing)data[1];
		final LayerThing root_lt = (LayerThing)data[2];
		final HashMap ht_pt_expanded = (HashMap)data[3];

		final Project project = (Project)root_pt.getObject();
		project.createLayerTemplates();
		project.template_tree = new TemplateTree(project, root_tt);
		project.root_tt = root_tt;
		project.root_pt= root_pt;
		project.project_tree = new ProjectTree(project, project.root_pt);
		project.layer_tree = new LayerTree(project, root_lt);
		project.root_lt = root_lt;
		project.layer_set = (LayerSet)root_lt.getObject();

		// if all when well, register as open:
		al_open_projects.add(project);
		// create the project control window, containing the trees in a double JSplitPane
		ControlWindow.add(project, project.template_tree, project.project_tree, project.layer_tree);

		// debug: print the entire root project tree
		//project.root_pt.debug("");

		// set ProjectThing nodes expanded state, now that the trees exist
		try {
			java.lang.reflect.Field f = JTree.class.getDeclaredField("expandedState");
			f.setAccessible(true);
			Hashtable ht_exp = (Hashtable)f.get(project.project_tree);
			for (Iterator it = ht_pt_expanded.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				ProjectThing pt = (ProjectThing)entry.getKey();
				Boolean expanded = (Boolean)entry.getValue();
				//project.project_tree.expandPath(new TreePath(project.project_tree.findNode(pt, project.project_tree).getPath()));
				// WARNING the above is wrong in that it will expand the whole thing, not just set the state of the node!!
				// So the ONLY way to do it is to start from the child-most leafs of the tree, and apply the expanding to them upward. This is RIDICULOUS, how can it be so broken
				// so, hackerous:
				DefaultMutableTreeNode nd = project.project_tree.findNode(pt, project.project_tree);
				//if (null == nd) Utils.log2("null node for " + pt);
				//else Utils.log2("path: " + new TreePath(nd.getPath()));
				if (null == nd) {
					Utils.log2("Can't find node for " + pt);
				} else {
					ht_exp.put(new TreePath(nd.getPath()), expanded);
				}
			}
			project.project_tree.updateUILater(); // very important!!
		} catch (Exception e) {
			IJError.print(e);
		}
		// open any stored displays
		if (open_displays) {
			final Bureaucrat burro = Display.openLater();
			if (null != burro) {
				final Runnable ru = new Runnable() {
					public void run() {
						// wait until the Bureaucrat finishes
						try { burro.join(); } catch (InterruptedException ie) {}
						// restore to non-changes (crude, but works)
						project.loader.setChanged(false);
					}
				};
				new Thread() {
					public void run() {
						setPriority(Thread.NORM_PRIORITY);
						// avoiding "can't call invokeAndWait from the EventDispatch thread" error
						try {
							javax.swing.SwingUtilities.invokeAndWait(ru);
						} catch (Exception e) {
							Utils.log2("ERROR: " + e);
						}
					}
				}.start();
			} else {
				// help the helpless users
				Display.createDisplay(project, project.layer_set.getLayer(0));
			}
		}
		return project;
	}

	static private Project createNewProject(Loader loader, boolean ask_for_template) {
		return createNewProject(loader, ask_for_template, null);
	}

	static private Project createNewSubProject(Project source, Loader loader) {
		return createNewProject(loader, false, source.root_tt, true);
	}

	static private Project createNewProject(Loader loader, boolean ask_for_template, TemplateThing template_root) {
		return createNewProject(loader, ask_for_template, template_root, false);
	}

	static private Project createNewProject(Loader loader, boolean ask_for_template, TemplateThing template_root, boolean clone_ids) {
		Project project = new Project(loader);
		// ask for an XML properties file that defines the Thing objects that can be created
		// (the XML file will be parsed into a TemplateTree filled with TemplateThing objects)
		//Utils.log2("ask_for_template: " + ask_for_template);
		if (ask_for_template) template_root = project.loader.askForXMLTemplate(project);
		if (null == template_root) {
			template_root = new TemplateThing("anything");
		} else if (clone_ids) {
			// the given template_root belongs to another project from which we are cloning
			template_root = template_root.clone(project, true);
		} // else, use the given template_root as is.
		// create tree
		project.template_tree = new TemplateTree(project, template_root);
		project.root_tt = template_root;
		// collect unique TemplateThing instances
		project.ht_unique_tt = template_root.getUniqueTypes(new HashMap<String,TemplateThing>());
		// add all TemplateThing objects to the database, recursively
		if (!clone_ids) template_root.addToDatabase(project);
		// else already done when cloning the root_tt

		// create a non-database bound template for the project Thing
		TemplateThing project_template = new TemplateThing("project");
		project.ht_unique_tt.put("project", project_template);
		project_template.addChild(template_root);
		// create the project Thing, to be root of the whole project thing tree
		try {
			project.root_pt= new ProjectThing(project_template, project, project);
		} catch (Exception e) { IJError.print(e); }
		// create the user objects tree
		project.project_tree = new ProjectTree(project, project.root_pt);
		// create the layer's tree
		project.createLayerTemplates();
		project.layer_set = new LayerSet(project, "Top Level", 0, 0, null, 2048, 2048); // initialized with default values, and null parent to signal 'root'
		try {
			project.root_lt = new LayerThing(project.layer_set_template, project, project.layer_set);
			project.layer_tree = new LayerTree(project, project.root_lt);
		} catch (Exception e) {
			project.remove();
			IJError.print(e);
		}
		// create the project control window, containing the trees in a double JSplitPane
		ControlWindow.add(project, project.template_tree, project.project_tree, project.layer_tree); // beware that this call is asynchronous, dispatched by the SwingUtilities.invokeLater to avoid havok with Swing components.
		// register
		al_open_projects.add(project);

		return project;
	}


	public void setTempLoader(Loader loader) {
		if (null == this.loader) {
			this.loader = loader;
		} else {
			Utils.log2("Project.setTempLoader: already have one.");
		}
	}

	public final Loader getLoader() {
		return loader;
	}

	public String getType() {
		return "project";
	}

	public String save() {
		Thread.yield(); // let it repaint the log window
		String path = loader.save(this); // TODO: put in a bkgd task, and show a progress bar
		return path;
	}

	public String saveAs(String xml_path, boolean overwrite) {
		return loader.saveAs(xml_path, overwrite);
	}

	public boolean destroy() {
		if (loader.hasChanges() && !getBooleanProperty("no_shutdown_hook")) { // DBLoader always returns false
			if (ControlWindow.isGUIEnabled()) {
				final YesNoDialog yn = ControlWindow.makeYesNoDialog("TrakEM2", "There are unsaved changes in project " + title + ". Save them?");
				if (yn.yesPressed()) {
					loader.save(this);
				}
			} else {
				Utils.log2("WARNING: closing project '" + title  + "' with unsaved changes.");
			}
		}
		al_open_projects.remove(this);
		// flush all memory
		if (null != loader) { // the last project is destroyed twice for some reason, if several are open. This is a PATCH
			loader.destroy(); // and disconnect
			loader = null;
		}
		ControlWindow.remove(this);
		if (null != template_tree) template_tree.destroy();
		if (null != project_tree) project_tree.destroy();
		if (null != layer_tree) layer_tree.destroy();
		Polyline.flushTraceCache(this);
		Compare.removeProject(this);
		this.template_tree = null; // flag to mean: we're closing
		// close all open Displays
		Display.close(this);
		return true;
	}

	public boolean isBeingDestroyed() {
		return null == template_tree;
	}

	/** Remove the project from the database and release memory. */
	public void remove() {
		removeFromDatabase();
		destroy();
	}

	/** Remove the project from the database and release memory. */
	public boolean remove(boolean check) {
		if (!Utils.check("Delete the project " + toString() + " from the database?")) return false;
		removeFromDatabase();
		destroy();
		return true;
	}

	public void setTitle(String title) {
		if (null == title) return;
		this.title = title;
		ControlWindow.updateTitle(this);
		loader.updateInDatabase(this, "title");
	}

	public String toString() {
		if (null == title || title.equals("Project")) {
			try {
				return loader.makeProjectName(); // can't use this.id, because the id system is project-centric and thus all FSLoader projects would have the same id.
			} catch (Exception e) { Utils.log2("Swing again."); }
		}
		return title;
	}

	public String getTitle() {
		return title;
	}

	public TemplateTree getTemplateTree() {
		return template_tree;
	}

	public LayerTree getLayerTree() {
		return layer_tree;
	}

	public ProjectTree getProjectTree() {
		return project_tree;
	}

	/** Make an object of the type the TemplateThing can hold. */
	public Object makeObject(final TemplateThing tt) {
		final String type = tt.getType();
		if (type.equals("profile")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN); // this should go elsewhere, in display issues.
			return new Profile(this, "profile", 0, 0);
		} else if (type.equals("pipe")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Pipe(this, "pipe", 0, 0);
		} else if (type.equals("polyline")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Polyline(this, "polyline");
		} else if (type.equals("area_list")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN); // may need adjustment ...
			return new AreaList(this, "area_list", 0, 0);
		} else if (type.equals("ball")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Ball(this, "ball", 0, 0);
		} else if (type.equals("dissector")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Dissector(this, "dissector", 0, 0);
		} else if (type.equals("label")) {
			return new DLabel(this, "  ", 0, 0); // never used so far
		} else {
			// just the name, for the abstract ones
			return type;
		}
	}

	/** Returns true if the type is 'patch', 'layer', 'layer_set', 'profile', 'profile_list' 'pipe'. */
	static public boolean isBasicType(String type) {
		if (isProjectType(type)) return true;
		if (isLayerType(type)) return true;
		return false;
	}

	static public boolean isProjectType(String type) {
		type = type.toLowerCase();
		if (type.equals("profile_list")) return true;
		return false;
	}

	static public boolean isLayerType(String type) {
		type = type.toLowerCase().replace(' ', '_');
		if (type.equals("patch")) return true;
		if (type.equals("area_list")) return true;
		if (type.equals("label")) return true;
		if (type.equals("profile")) return true;
		if (type.equals("pipe")) return true;
		if (type.equals("polyline")) return true;
		if (type.equals("ball")) return true;
		if (type.equals("layer")) return true;
		if (type.equals("layer set")) return true; // for XML ...
		return false;
	}

	/** Remove the ProjectThing that contains the given object, which will remove the object itself as well. */
	public boolean removeProjectThing(Object object, boolean check) {
		return removeProjectThing(object, check, false, 0);
	}

	/** Remove the ProjectThing that contains the given object, which will remove the object itself as well. */
	public boolean removeProjectThing(Object object, boolean check, boolean remove_empty_parents, int levels) {
		if (levels < 0) {
			Utils.log2("Project.removeProjectThing: levels must be zero or above.");
			return false;
		}
		// find the Thing
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)project_tree.getModel().getRoot();
		Enumeration e = root.depthFirstEnumeration();
		DefaultMutableTreeNode node = null;
		while (e.hasMoreElements()) {
			node = (DefaultMutableTreeNode)e.nextElement();
			Object ob = node.getUserObject();
			if (ob instanceof ProjectThing && ((ProjectThing)ob).getObject() == object) {
				if (check && !Utils.check("Remove " + object.toString() + "?")) return false;
				// remove the ProjectThing, its object and the node that holds it.
				project_tree.remove(node, false, remove_empty_parents, levels);
				return true;
			} // the above could be done more generic with a Thing.contains(Object), but I want to make sure that the object is contained by a ProjectThing and nothing else.
		}
		// not found:
		return false;
	}


	/** Find the node in the layer tree with a Thing that contains the given object, and set it selected/highlighted, deselecting everything else first. */
	public void select(final Layer layer) {
		select(layer, layer_tree);
	}
	/** Find the node in any tree with a Thing that contains the given Displayable, and set it selected/highlighted, deselecting everything else first. */
	public void select(final Displayable d) {
		if (d.getClass() == LayerSet.class) select(d, layer_tree);
		else select(d, project_tree);
	}

	private final void select(final Object ob, final JTree tree) {
		// Find the Thing that contains the object
		final Thing root_thing = (Thing)((DefaultMutableTreeNode)tree.getModel().getRoot()).getUserObject();
		final Thing child_thing = root_thing.findChild(ob);
		// find the node that contains the Thing, and select it
		DNDTree.selectNode(child_thing, tree);
	}

	/** Find the ProjectThing instance with the given id. */
	public ProjectThing find(final long id) {
		// can't be the Project itself
		return root_pt.findChild(id);
	}

	public DBObject findById(final long id) {
		if (this.id == id) return this;
		DBObject dbo = layer_set.findById(id);
		if (null != dbo) return dbo;
		dbo = root_pt.findChild(id); // could call findObject(id), but all objects must exist in layer sets anyway.
		if (null != dbo) return dbo;
		return (DBObject)root_tt.findChild(id);
	}

	/** Find a LayerThing that contains the given object. */
	public LayerThing findLayerThing(final Object ob) {
		final Object lob = root_lt.findChild(ob);
		return null != lob ? (LayerThing)lob : null;
	}

	/** Find a ProjectThing that contains the given object. */
	public ProjectThing findProjectThing(final Object ob) {
		final Object pob = root_pt.findChild(ob);
		return null != pob ? (ProjectThing)pob : null;
	}

	public ProjectThing getRootProjectThing() {
		return root_pt;
	}

	public LayerSet getRootLayerSet() {
		return layer_set;
	}

	/** Returns the title of the enclosing abstract node in the ProjectTree.*/
	public String getParentTitle(final Displayable d) {
		try {
			ProjectThing thing = (ProjectThing)this.root_pt.findChild(d);
			ProjectThing parent = (ProjectThing)thing.getParent();
			if (d instanceof Profile) {
				parent = (ProjectThing)parent.getParent(); // skip the profile_list
			}
			if (null == parent) Utils.log2("null parent for " + d);
			if (null != parent && null == parent.getObject()) {
				Utils.log2("null ob for parent " + parent + " of " + d);
			}
			return parent.getObject().toString(); // the abstract thing should be enclosing a String object
		} catch (Exception e) { IJError.print(e); return null; }
	}

	/** Searches upstream in the Project tree for things that have a user-defined name, stops at the first and returns it along with all the intermediate ones that only have a type and not a title, appended. */
	public String getMeaningfulTitle(final Displayable d) {
		ProjectThing thing = (ProjectThing)this.root_pt.findChild(d);
		if (null == thing) return d.getTitle(); // happens if there is no associated node
		String title = new StringBuffer(!thing.getType().equals(d.getTitle()) ? d.getTitle() + " [" : "[").append(thing.getType()).append(' ').append('#').append(d.getId()).append(']').toString();

		if (!thing.getType().equals(d.getTitle())) {
			return title;
		}

		ProjectThing parent = (ProjectThing)thing.getParent();
		StringBuffer sb = new StringBuffer(title);
		while (null != parent) {
			Object ob = parent.getObject();
			if (ob.getClass() == Project.class) break;
			String type = parent.getType();
			if (!ob.equals(type)) { // meaning, something else was typed in as a title
				sb.insert(0, new StringBuffer(ob.toString()).append(' ').append('[').append(type).append(']').append('/').toString());
				//title =  ob.toString() + " [" + type + "]/" + title;
				break;
			}
			sb.insert(0, '/');
			sb.insert(0, type);
			//title = type + "/" + title;
			parent = (ProjectThing)parent.getParent();
		}
		//return title;
		return sb.toString();
	}

	/** Returns the first upstream user-defined name and type, and the id of the displayable tagged at the end.
	 *  If no user-defined name is found, then the type is prepended to the id.
	 */
	public String getShortMeaningfulTitle(final Displayable d) {
		ProjectThing thing = (ProjectThing)this.root_pt.findChild(d);
		if (null == thing) return d.getTitle(); // happens if there is no associated node
		return getShortMeaningfulTitle(thing, d);
	}
	public String getShortMeaningfulTitle(final ProjectThing thing, final Displayable d) {
		if (thing.getObject() != d) {
			return thing.toString();
		}
		ProjectThing parent = (ProjectThing)thing.getParent();
		String title = "#" + d.getId();
		while (null != parent) {
			Object ob = parent.getObject();
			String type = parent.getType();
			if (!ob.equals(type)) { // meaning, something else was typed in as a title
				title =  ob.toString() + " [" + type + "] " + title;
				break;
			}
			parent = (ProjectThing)parent.getParent();
		}
		// if nothing found, prepend the type
		if ('#' == title.charAt(0)) title = Project.getName(d.getClass()) + " " + title;
		return title;
	}

	static public String getType(final Class c) {
		if (AreaList.class == c) return "area_list";
		if (DLabel.class == c) return "label";
		return c.getName().toLowerCase();
	}

	/** Returns the proper TemplateThing for the given type, complete with children and attributes if any. */
	public TemplateThing getTemplateThing(String type) {
		return ht_unique_tt.get(type);
	}

	/** Returns a list of existing unique types in the template tree (thus the 'project' type is not included, nor the label). The basic types are guaranteed to be present even if there are no instances in the template tree. */
	public String[] getUniqueTypes() {
		// ensure the basic types (pipe, ball, profile, profile_list) are present
		if (!ht_unique_tt.containsKey("profile")) ht_unique_tt.put("profile", new TemplateThing("profile"));
		if (!ht_unique_tt.containsKey("profile_list")) {
			TemplateThing tpl = new TemplateThing("profile_list");
			tpl.addChild((TemplateThing) ht_unique_tt.get("profile"));
			ht_unique_tt.put("profile_list", tpl);
		}
		if (!ht_unique_tt.containsKey("pipe")) ht_unique_tt.put("pipe", new TemplateThing("pipe"));
		if (!ht_unique_tt.containsKey("polyline")) ht_unique_tt.put("polyline", new TemplateThing("polyline"));
		if (!ht_unique_tt.containsKey("ball")) ht_unique_tt.put("ball", new TemplateThing("ball"));
		if (!ht_unique_tt.containsKey("area_list")) ht_unique_tt.put("area_list", new TemplateThing("area_list"));
		if (!ht_unique_tt.containsKey("dissector")) ht_unique_tt.put("dissector", new TemplateThing("dissector"));
		// this should be done automagically by querying the classes in the package ... but java can't do that without peeking into the .jar .class files. Buh.

		TemplateThing project_tt = ht_unique_tt.remove("project");
		/* // debug
		for (Iterator it = ht_unique_tt.keySet().iterator(); it.hasNext(); ) {
			Utils.log2("class: " + it.next().getClass().getName());
		} */
		final String[] ut = new String[ht_unique_tt.size()];
		ht_unique_tt.keySet().toArray(ut);
		ht_unique_tt.put("project", project_tt);
		Arrays.sort(ut);
		return ut;
	}

	/** Remove a unique type from the HashMap. Basic types can't be removed. */
	public boolean removeUniqueType(String type) {
		if (null == type || isBasicType(type)) return false;
		return null != ht_unique_tt.remove(type);
	}

	public boolean typeExists(String type) {
		return ht_unique_tt.containsKey(type);
	}

	/** Returns false if the type exists already. */
	public boolean addUniqueType(TemplateThing tt) {
		if (null == ht_unique_tt) this.ht_unique_tt = new HashMap();
		if (ht_unique_tt.containsKey(tt.getType())) return false;
		ht_unique_tt.put(tt.getType(), tt);
		return true;
	}

	public boolean updateTypeName(String old_type, String new_type) {
		if (ht_unique_tt.containsKey(new_type)) {
			Utils.showMessage("Can't rename type '" + old_type + "' : a type named '"+new_type+"' already exists!");
			return false;
		}
		ht_unique_tt.put(new_type, ht_unique_tt.remove(old_type));
		return true;
	}

	private void createLayerTemplates() {
		if (null == layer_template) {
			layer_template = new TemplateThing("layer");
			layer_set_template = new TemplateThing("layer_set");
			layer_set_template.addChild(layer_template);
			layer_template.addChild(layer_set_template); // adding a new instance to keep parent/child relationships clean
			// No need, there won't ever be a loop so far WARNING may change in the future.
		}
	}

	/** Export the main trakem2 tag wrapping four hierarchies (the project tag, the ProjectTree, and the Top Level LayerSet the latter including all Displayable objects) and a list of displays. */
	public void exportXML(final java.io.Writer writer, String indent, Object any) throws Exception {
		StringBuffer sb_body = new StringBuffer();
		// 1 - opening tag
		sb_body.append(indent).append("<trakem2>\n");
		String in = indent + "\t";
		// 2 - the project itself
		sb_body.append(in).append("<project \n")
		       .append(in).append("\tid=\"").append(id).append("\"\n")
		       .append(in).append("\ttitle=\"").append(title).append("\"\n");
		loader.insertXMLOptions(sb_body, in + "\t");
		for (Iterator it = ht_props.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry prop = (Map.Entry)it.next();
			sb_body.append(in).append('\t').append((String)prop.getKey()).append("=\"").append((String)prop.getValue()).append("\"\n");
		}
		sb_body.append(in).append(">\n");
		// 3 - export ProjectTree abstract hierachy (skip the root since it wraps the project itself)
		if (null != root_pt.getChildren()) {
			String in2 = in + "\t";
			for (Iterator it = root_pt.getChildren().iterator(); it.hasNext(); ) {
				((ProjectThing)it.next()).exportXML(sb_body, in2, any);
			}
		}
		sb_body.append(in).append("</project>\n");
		writer.write(sb_body.toString());
		sb_body.setLength(0);
		sb_body = null;
		// 4 - export LayerSet hierarchy of Layer, LayerSet and Displayable objects
		layer_set.exportXML(writer, in, any);
		// 5 - export Display objects
		Display.exportXML(this, writer, in, any);
		// 6 - closing tag
		writer.write("</trakem2>\n");
	}

	/** Export a complete DTD listing to export the project as XML. */
	public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		// 1 - TrakEM2 tag that encloses all hierarchies
		sb_header.append(indent).append("<!ELEMENT ").append("trakem2 (project,t2_layer_set,t2_display)>\n");
		// 2 - export user-defined templates
		//TemplateThing root_tt = (TemplateThing)((DefaultMutableTreeNode)((DefaultTreeModel)template_tree.getModel()).getRoot()).getUserObject();
		sb_header.append(indent).append("<!ELEMENT ").append("project (").append(root_tt.getType()).append(")>\n");
		sb_header.append(indent).append("<!ATTLIST project id NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ATTLIST project unuid NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ATTLIST project title NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ATTLIST project preprocessor NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ATTLIST project mipmaps_folder NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ATTLIST project storage_folder NMTOKEN #REQUIRED>\n");
		for (Iterator it = ht_props.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry prop = (Map.Entry)it.next();
			sb_header.append(indent).append("<!ATTLIST project ").append((String)prop.getKey()).append(" NMTOKEN #REQUIRED>\n");
		}
		root_tt.exportDTD(sb_header, hs, indent);
		// 3 - export all project objects DTD in the Top Level LayerSet
		Layer.exportDTD(sb_header, hs, indent);
		LayerSet.exportDTD(sb_header, hs, indent);
		Ball.exportDTD(sb_header, hs, indent);
		DLabel.exportDTD(sb_header, hs, indent);
		Patch.exportDTD(sb_header, hs, indent);
		Pipe.exportDTD(sb_header, hs, indent);
		Polyline.exportDTD(sb_header, hs, indent);
		Profile.exportDTD(sb_header, hs, indent);
		AreaList.exportDTD(sb_header, hs, indent);
		Dissector.exportDTD(sb_header, hs, indent);
		Displayable.exportDTD(sb_header, hs, indent); // the subtypes of all Displayable types
		// 4 - export Display
		Display.exportDTD(sb_header, hs, indent);
		// all the above could be done with reflection, automatically detecting the presence of an exportDTD method.
	}

	/** Returns the String to be used as Document Type of the XML file, generated from the name of the root template thing.*/
	public String getDocType() {
		//TemplateThing root_tt = (TemplateThing)((DefaultMutableTreeNode)((DefaultTreeModel)template_tree.getModel()).getRoot()).getUserObject();
		return "trakem2_" + root_tt.getType();
	}

	/** Find an instance containing the given tree. */
	static public Project getInstance(final DNDTree ob) {
		for (Iterator it = al_open_projects.iterator(); it.hasNext(); ) {
			Project project = (Project)it.next();
			if (project.layer_tree.equals(ob)) return project;
			if (project.project_tree.equals(ob)) return project;
			if (project.template_tree.equals(ob)) return project;
		}
		return null;
	}

	/** Returns a user-understandable name for the given class. */
	static public String getName(final Class c) {
		String name = c.getName();
		name = name.substring(name.lastIndexOf('.') + 1);
		if (name.equals("DLabel")) return "Label";
		else if (name.equals("Patch")) return "Image";
		//else if (name.equals("Pipe")) return "Tube";
		//else if (name.equals("Ball")) return "Sphere group"; // TODO revise consistency with XML templates and so on
		else return name;
	}

	public String getInfo() {
		StringBuffer sb = new StringBuffer("Project id: ");
		sb.append(this.id).append("\nProject name: ").append(this.title)
		  .append("\nTrees:\n")
		  .append(project_tree.getInfo()).append("\n")
		  .append(layer_tree.getInfo())
		;
		return sb.toString();
	}

	static public Project findProject(Loader loader) {
		for (Iterator it = al_open_projects.iterator(); it.hasNext(); ) {
			Project pro = (Project)it.next();
			if (pro.getLoader() == loader) return pro;
		}
		return null;
	}

	private boolean input_disabled = false;

	/** Tells the displays concerning this Project to accept/reject input. */
	public void setReceivesInput(boolean b) {
		this.input_disabled = !b;
		Display.setReceivesInput(this, b);
	}

	public boolean isInputEnabled() {
		return !input_disabled;
	}

	/** Create a new subproject for the given layer range and ROI.
	*   Create a new Project using the given project as template. This means the DTD of the given project is copied, as well as the storage and mipmaps folders; everything else is empty in the new project. */
	public Project createSubproject(final Rectangle roi, final Layer first, final Layer last) {
		try {
			// The order matters.
			final Project pr = new Project(new FSLoader(this.getLoader()));
			pr.id = this.id;
			// copy properties
			pr.title = this.title;
			pr.ht_props.putAll(this.ht_props);
			// copy template
			pr.root_tt = this.root_tt.clone(pr, true);
			pr.template_tree = new TemplateTree(pr, pr.root_tt);
			pr.ht_unique_tt = root_tt.getUniqueTypes(new HashMap());
			TemplateThing project_template = new TemplateThing("project");
			project_template.addChild(pr.root_tt);
			pr.ht_unique_tt.put("project", project_template);
			// create the layers templates
			pr.createLayerTemplates();
			// copy LayerSet and all involved Displayable objects
			pr.layer_set = (LayerSet)this.layer_set.clone(pr, first, last, roi, false, true);
			// create layer tree
			pr.root_lt = new LayerThing(pr.layer_set_template, pr, pr.layer_set);
			pr.layer_tree = new LayerTree(pr, pr.root_lt);
			// add layer nodes to the layer tree (solving chicken-and-egg problem)
			pr.layer_set.updateLayerTree();
			// copy project tree
			pr.root_pt = this.root_pt.subclone(pr);
			pr.project_tree = new ProjectTree(pr, pr.root_pt);
			// not copying node expanded state.
			// register
			al_open_projects.add(pr);
			// add to gui:
			ControlWindow.add(pr, pr.template_tree, pr.project_tree, pr.layer_tree);

			// Above, the id of each object is preserved from this project into the subproject.

			// The abstract structure should be copied in full regardless, without the basic objects
			// included if they intersect the roi.

			return pr;

		} catch (Exception e) { e.printStackTrace(); }
		return null;
	}

	public void parseXMLOptions(final HashMap ht_attributes) {
		((FSLoader)this.project.getLoader()).parseXMLOptions(ht_attributes);
		// all keys that remain are properties
		ht_props.putAll(ht_attributes);
		for (Iterator it = ht_props.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry prop = (Map.Entry)it.next();
			Utils.log2("parsed: " + prop.getKey() + "=" + prop.getValue());
		}
	}
	public HashMap<String,String> getPropertiesCopy() {
		return (HashMap<String,String>)ht_props.clone();
	}
	/** Returns null if not defined. */
	public String getProperty(final String key) {
		return ht_props.get(key);
	}
	/** Returns the default value if not defined, or if not a number or not parsable as a number. */
	public float getProperty(final String key, final float default_value) {
		try {
			final String s = ht_props.get(key);
			if (null == s) return default_value;
			final float num = Float.parseFloat(s);
			if (Float.isNaN(num)) return default_value;
			return num;
		} catch (NumberFormatException nfe) {
			IJError.print(nfe);
		}
		return default_value;
	}
	public boolean getBooleanProperty(final String key) {
		return "true".equals(ht_props.get(key));
	}
	public void setProperty(final String key, final String value) {
		if (null == value) ht_props.remove(key);
		else ht_props.put(key, value);
	}
	private final boolean addBox(final GenericDialog gd, final Class c) {
		final String name = Project.getName(c);
		final boolean link = "true".equals(ht_props.get(name.toLowerCase() + "_nolinks"));
		gd.addCheckbox(name, link);
		return link;
	}
	private final void setLinkProp(final boolean before, final boolean after, final Class c) {
		if (before) {
			if (!after) ht_props.remove(Project.getName(c).toLowerCase()+"_nolinks");
		} else if (after) {
			ht_props.put(Project.getName(c).toLowerCase()+"_nolinks", "true");
		}
		// setting to false would have no meaning, so the link prop is removed
	}
	/** Returns true if there were any changes. */
	private final boolean adjustProp(final String prop, final boolean before, final boolean after) {
		if (before) {
			if (!after) ht_props.remove(prop);
		} else if (after) {
			ht_props.put(prop, "true");
		}
		return before != after;
	}
	public void adjustProperties() {
		// should be more generic, but for now it'll do
		GenericDialog gd = new GenericDialog("Properties");
		gd.addMessage("Ignore image linking for:");
		boolean link_labels = addBox(gd, DLabel.class);
		boolean link_arealist = addBox(gd, AreaList.class);
		boolean link_pipes = addBox(gd, Pipe.class);
		boolean link_polylines = addBox(gd, Polyline.class);
		boolean link_balls = addBox(gd, Ball.class);
		boolean link_dissectors = addBox(gd, Dissector.class);
		boolean dissector_zoom = "true".equals(ht_props.get("dissector_zoom"));
		gd.addCheckbox("Zoom-invariant markers for Dissector", dissector_zoom);
		boolean no_color_cues = "true".equals(ht_props.get("no_color_cues"));
		gd.addCheckbox("Paint_color_cues", !no_color_cues);
		gd.addMessage("Currently linked objects\nwill remain so unless\nexplicitly unlinked.");
		String current_mode = ht_props.get("image_resizing_mode");
		// Forbid area averaging: doesn't work, and it's not faster than gaussian.
		if (Utils.indexOf(current_mode, Loader.modes) >= Loader.modes.length) current_mode = Loader.modes[3]; // GAUSSIAN
		gd.addChoice("Image_resizing_mode: ", Loader.modes, null == current_mode ? Loader.modes[3] : current_mode);
		int current_R = (int)(100 * ini.trakem2.imaging.StitchingTEM.DEFAULT_MIN_R); // make the float a percent
		try {
			String scR = ht_props.get("min_R");
			if (null != scR) current_R = (int)(Double.parseDouble(scR) * 100);
		} catch (Exception nfe) {
			IJError.print(nfe);
		}
		gd.addSlider("min_R: ", 0, 100, current_R);

		boolean layer_mipmaps = "true".equals(ht_props.get("layer_mipmaps"));
		gd.addCheckbox("Layer_mipmaps", layer_mipmaps);
		boolean keep_mipmaps = "true".equals(ht_props.get("keep_mipmaps"));
		gd.addCheckbox("Keep_mipmaps_when_deleting_images", keep_mipmaps); // coping with the fact that thee is no Action context ... there should be one in the Worker thread.
		int bucket_side = (int)getProperty("bucket_side", Bucket.MIN_BUCKET_SIZE);
		gd.addNumericField("Bucket side length: ", bucket_side, 0);
		boolean no_shutdown_hook = "true".equals(ht_props.get("no_shutdown_hook"));
		gd.addCheckbox("No_shutdown_hook to save the project", no_shutdown_hook);
		//
		gd.showDialog();
		//
		if (gd.wasCanceled()) return;
		setLinkProp(link_labels, gd.getNextBoolean(), DLabel.class);
		setLinkProp(link_arealist, gd.getNextBoolean(), AreaList.class);
		setLinkProp(link_pipes, gd.getNextBoolean(), Pipe.class);
		setLinkProp(link_polylines, gd.getNextBoolean(), Polyline.class);
		setLinkProp(link_balls, gd.getNextBoolean(), Ball.class);
		setLinkProp(link_dissectors, gd.getNextBoolean(), Dissector.class);
		if (adjustProp("dissector_zoom", dissector_zoom, gd.getNextBoolean())) {
			Display.repaint(layer_set); // TODO: should repaint nested LayerSets as well
		}
		if (adjustProp("no_color_cues", no_color_cues, !gd.getNextBoolean())) {
			Display.repaint(layer_set);
		}
		setProperty("image_resizing_mode", Loader.modes[gd.getNextChoiceIndex()]);
		setProperty("min_R", new Float((float)gd.getNextNumber() / 100).toString());
		boolean layer_mipmaps2 = gd.getNextBoolean();
		if (adjustProp("layer_mipmaps", layer_mipmaps, layer_mipmaps2)) {
			if (layer_mipmaps && !layer_mipmaps2) {
				// TODO
				// 1 - ask first
				// 2 - remove all existing images from layer.mipmaps folder
			} else if (!layer_mipmaps && layer_mipmaps2) {
				// TODO
				// 1 - ask first
				// 2 - create de novo all layer mipmaps in a background task
			}
		}
		adjustProp("keep_mipmaps", keep_mipmaps, gd.getNextBoolean());
		Utils.log2("keep_mipmaps: " + getBooleanProperty("keep_mipmaps"));
		//
		bucket_side = (int)gd.getNextNumber();
		if (bucket_side > Bucket.MIN_BUCKET_SIZE) {
			setProperty("bucket_side", Integer.toString(bucket_side));
			layer_set.recreateBuckets(true);
		}
		adjustProp("no_shutdown_hook", no_shutdown_hook, gd.getNextBoolean());
	}

	/** Return the Universal Near-Unique Id of this project, which may be null for non-FSLoader projects. */
	public String getUNUId() {
		return loader.getUNUId();
	}
}
