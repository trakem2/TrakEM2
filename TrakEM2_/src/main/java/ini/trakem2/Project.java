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

package ini.trakem2;


import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Bucket;
import ini.trakem2.display.Connector;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Dissector;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Stack;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.persistence.DBLoader;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.tree.DNDTree;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.LayerTree;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.TemplateTree;
import ini.trakem2.tree.Thing;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** The top-level class in control. */
public class Project extends DBObject {

	static {
		try {
			//UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			if (IJ.isLinux()) {
				UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
				if (null != IJ.getInstance()) javax.swing.SwingUtilities.updateComponentTreeUI(IJ.getInstance());
				//if ("albert".equals(System.getProperty("user.name"))) UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
			}
		} catch (Exception e) {
			Utils.log("Failed to set System Look and Feel");
		}
	}


	static final private Vector<PlugInSource> PLUGIN_SOURCES = new Vector<PlugInSource>();

	static private class PlugInSource implements Comparable<PlugInSource> {
		String menu;
		Class<?> c;
		String title;
		PlugInSource(String menu, Class<?> c, String title) {
			this.menu = menu;
			this.c = c;
			this.title = title;
		}
		public int compareTo(PlugInSource ob) {
			return ob.title.compareTo(this.title);
		}
	}

	static {
		// Search for plugins under fiji/plugins directory jar files
		new Thread() { public void run() { try {
			setPriority(Thread.NORM_PRIORITY);
			setContextClassLoader(ij.IJ.getClassLoader());
			final String plugins_dir = Utils.fixDir(ij.Menus.getPlugInsPath());
			synchronized (PLUGIN_SOURCES) {
			for (String name : new File(plugins_dir).list()) {
				File f = new File(name);
				if (f.isHidden() || !name.toLowerCase().endsWith(".jar")) continue;
				JarFile jar = new JarFile(plugins_dir + name);
				JarEntry entry = null;
				for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
					JarEntry je = en.nextElement();
					if (je.getName().endsWith(".trakem2")) {
						entry = je;
						break;
					}
				}
				if (entry == null) continue;
				// Parse:
				BufferedReader br = new BufferedReader(new InputStreamReader(jar.getInputStream(entry)));
				try {
					while (true) {
						String line = br.readLine();
						if (null == line) break;
						if (line.startsWith("#")) continue;
						// tokenize:
						//  - from start to first comma is the menu
						//  - from first comma to last comma is the title
						//  - from last comma to end is the class
						//  The above allows for commas to be inside the title
						int fc = line.indexOf(',');
						if (-1 == fc) continue;
						int lc = line.lastIndexOf(',');
						if (-1 == lc) continue;
						String menu = line.substring(0, fc).trim();
						if (!menu.equals("Project Tree") && !menu.equals("Display")) continue;
						String classname = line.substring(lc+1).trim();
						try {
							Class.forName(classname);
						} catch (ClassNotFoundException cnfe) {
							Utils.log2("TPlugIn class not found: " + classname);
							continue;
						}
						int fq = line.indexOf('"', fc);
						if (-1 == fq) continue;
						int lq = line.lastIndexOf('"', lc);
						if (-1 == lq) continue;
						String title = line.substring(fq+1, lq).trim();
						try {
							PLUGIN_SOURCES.add(new PlugInSource(menu, Class.forName(classname), title));
							Utils.log2("Found plugin for menu " + menu + " titled " + title + " for class " + classname);
						} catch (ClassNotFoundException cnfe) {
							Utils.log("Could not find TPlugIn class " + classname);
						}
					}
				} finally {
					br.close();
				}
			}}
		} catch (Throwable t) {
			Utils.log("ERROR while parsing TrakEM2 plugins:");
			IJError.print(t);
		}}}.start();
	}

	/** Map of title keys vs TPlugin instances. */
	private Map<PlugInSource,TPlugIn> plugins = null;

	/** Create plugin instances for this project. */
	synchronized private Map<PlugInSource,TPlugIn> createPlugins() {
		final Map<PlugInSource,TPlugIn> m = Collections.synchronizedMap(new TreeMap<PlugInSource,TPlugIn>());
		synchronized (PLUGIN_SOURCES) {
			for (PlugInSource source : PLUGIN_SOURCES) {
				try {
					m.put(source, (TPlugIn)source.c.newInstance());
				} catch (Exception e) {
					Utils.log("ERROR initializing plugin!\nParsed tokens: [" + source.menu + "][" + source.title + "][" + source.c.getName() + "]");
					IJError.print(e);
				}
			}
		}
		return m;
	}

	synchronized public TreeMap<String,TPlugIn> getPlugins(final String menu) {
		final TreeMap<String,TPlugIn> m = new TreeMap<String,TPlugIn>();
		if (null == plugins) plugins = createPlugins(); // to be created the first time it's asked for
		for (Map.Entry<PlugInSource,TPlugIn> e : plugins.entrySet()) {
			if (e.getKey().menu.equals(menu)) m.put(e.getKey().title, e.getValue());
		}
		return m;
	}

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

	/** The table of unique TemplateThing types; the key is the type (String). */
	private final Map<String,TemplateThing> ht_unique_tt = Collections.synchronizedMap(new HashMap<String,TemplateThing>());

	private LayerTree layer_tree = null;

	private String title = "Project";

	private final HashMap<String,String> ht_props = new HashMap<String,String>();
	
	private int mipmaps_mode = Loader.DEFAULT_MIPMAPS_MODE;

	/** The constructor used by the static methods present in this class. */
	private Project(Loader loader) {
		super(loader);
		ControlWindow.getInstance(); // init
		this.loader = loader;
		this.project = this; // for the superclass DBObject
		loader.addToDatabase(this);
	}

	/** Constructor used by the Loader to find projects. These projects contain no loader. */
	public Project(long id, String title) {
		super(null, id);
		ControlWindow.getInstance(); // init
		this.title = title;
		this.project = this;
	}

	private ScheduledFuture<?> autosaving = null;

	private void restartAutosaving() {
		// cancel current autosaving if it's running
		if (null != autosaving) try {
			autosaving.cancel(true);
		} catch (Throwable t) { IJError.print(t); }
		//
		final int interval_in_minutes = getProperty("autosaving_interval", 0);
		if (0 == interval_in_minutes) return;
		// else, relaunch
		this.autosaving = FSLoader.autosaver.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				try {
					if (loader.hasChanges()) {
						Bureaucrat.createAndStart(new Worker.Task("auto-saving") {
							@Override
							public void exec() {
								Project.this.save();
							}
						}, Project.this).join();
					}
				} catch (Throwable e) {
					Utils.log("*** Autosaver failed:");
					IJError.print(e);
				}
			}
		}, interval_in_minutes * 60, interval_in_minutes * 60, TimeUnit.SECONDS);
	}

	static public Project getProject(final String title) {
		for (final Project pr : al_open_projects) {
			if (pr.title.equals(title)) return pr;
		}
		return null;
	}

	/** Return a copy of the list of all open projects. */
	static public ArrayList<Project> getProjects() {
		return new ArrayList<Project>(al_open_projects);
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
			Utils.showMessage("Can't talk to database (null list of projects).");
			loader.destroy();
			return null;
		}
		Project project = null;
		if (0 == projects.length) {
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
		for (final Project p : al_open_projects) {
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
		synchronized (project.ht_unique_tt) {
			project.ht_unique_tt.clear();
			project.ht_unique_tt.putAll(template_root.getUniqueTypes(new HashMap<String,TemplateThing>()));
		}
		// create the project Thing, to be root of the whole user Thing tree (and load all its objects)
		HashMap<Long,Displayable> hs_d = new HashMap<Long,Displayable>(); // to collect all created displayables, and  then reassign to the proper layers.
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
			root_layer_thing = loader.getRootLayerThing(project, project.root_pt, Project.layer_set_template, Project.layer_template);
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

	/** Creates a new project to be based on .xml and image files, not a database.
	 * Images are left where they are, keeping the path to them.
	 * If the arg equals 'blank', then no template is asked for;
	 * if template_root is not null that is used; else, a template file is asked for.
	 * 
	 * @param arg Either "blank", "amira", "stack" or null. "blank" will generate a default template tree; "amira" will ask for importing an Amira file; "stack" will ask for importing an image stack (single multi-image file, like multi-TIFF).
	 * @param template_root May be null, in which case a template DTD or XML file will be asked for, unless {@param arg} equals "blank".
	 * @param storage_folder If null, a dialog asks for it.
	 */
	static public Project newFSProject(String arg, TemplateThing template_root, String storage_folder) {
		return newFSProject(arg, template_root, storage_folder, true);
	}
	static public Project newFSProject(String arg, TemplateThing template_root, String storage_folder, boolean autocreate_one_layer) {
		if (Utils.wrongImageJVersion()) return null;
		FSLoader loader = null;
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
				if (IJ.isWindows()) dir_project = dir_project.replace('\\', '/');
			}
			loader = new FSLoader(dir_project);

			Project project = createNewProject(loader, !("blank".equals(arg) || "amira".equals(arg)), template_root);

			// help the helpless users:
			if (autocreate_one_layer && null != project && ControlWindow.isGUIEnabled()) {
				Utils.log2("Creating automatic Display.");
				// add a default layer
				Layer layer = new Layer(project, 0, 1, project.layer_set);
				project.layer_set.add(layer);
				project.layer_tree.addLayer(project.layer_set, layer);
				layer.recreateBuckets();
				Display.createDisplay(project, layer);
			}
			try {
				Thread.sleep(200); // waiting cheaply for asynchronous swing calls
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}

			if ("amira".equals(arg) || "stack".equals(arg)) {
				// forks into a task thread
				loader.importStack(project.layer_set.getLayer(0), null, true);
			}

			project.restartAutosaving();

			return project;
		} catch (Exception e) {
			IJError.print(e);
			if (null != loader) loader.destroy();
		}
		return null;
	}

	static public Project openFSProject(final String path) {
		return openFSProject(path, true);
	}

	/** Opens a project from an .xml file. If the path is null it'll be asked for.
	 *  Only one project may be opened at a time.
	 */
	@SuppressWarnings("unchecked")
	synchronized static public Project openFSProject(final String path, final boolean open_displays) {
		if (Utils.wrongImageJVersion()) return null;
		final FSLoader loader = new FSLoader();
		final Object[] data = loader.openFSProject(path, open_displays);
		if (null == data) {
			loader.destroy();
			return null;
		}
		final TemplateThing root_tt = (TemplateThing)data[0];
		final ProjectThing root_pt = (ProjectThing)data[1];
		final LayerThing root_lt = (LayerThing)data[2];
		final HashMap<ProjectThing,Boolean> ht_pt_expanded = (HashMap<ProjectThing,Boolean>)data[3];

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
			Hashtable<Object,Object> ht_exp = (Hashtable<Object,Object>) f.get(project.project_tree);
			for (Map.Entry<ProjectThing,Boolean> entry : ht_pt_expanded.entrySet()) {
				ProjectThing pt = entry.getKey();
				Boolean expanded = entry.getValue();
				//project.project_tree.expandPath(new TreePath(project.project_tree.findNode(pt, project.project_tree).getPath()));
				// WARNING the above is wrong in that it will expand the whole thing, not just set the state of the node!!
				// So the ONLY way to do it is to start from the child-most leafs of the tree, and apply the expanding to them upward. This is RIDICULOUS, how can it be so broken
				// so, hackerous:
				DefaultMutableTreeNode nd = DNDTree.findNode(pt, project.project_tree);
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
						Utils.log2("C set to false");
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
				// SO: WAIT TILL THE END OF TIME!
				new Thread() { public void run() {
					try {
					Thread.sleep(4000); // ah, the pain in my veins. I can't take this shitty setup anymore.
					javax.swing.SwingUtilities.invokeAndWait(new Runnable() { public void run() {
						project.getLoader().setChanged(false);
						Utils.log2("D set to false");
					}});
					project.getTemplateTree().updateUILater(); // repainting to fix gross errors in tree rendering
					project.getProjectTree().updateUILater();  // idem
					} catch (Exception ie) {}
				}}.start();
			} else {
				// help the helpless users
				Display.createDisplay(project, project.layer_set.getLayer(0));
			}
		}
		
		project.restartAutosaving();

		return project;
	}

	static private Project createNewProject(Loader loader, boolean ask_for_template) {
		return createNewProject(loader, ask_for_template, null);
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
		synchronized (project.ht_unique_tt) {
			project.ht_unique_tt.clear();
			project.ht_unique_tt.putAll(template_root.getUniqueTypes(new HashMap<String,TemplateThing>()));
		}
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
			project.root_lt = new LayerThing(Project.layer_set_template, project, project.layer_set);
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

	/** Save the project regardless of what getLoader().hasChanges() reports. */
	public String save() {
		Thread.yield(); // let it repaint the log window
		XMLOptions options = new XMLOptions();
		options.overwriteXMLFile = true;
		options.export_images = false;
		options.patches_dir = null;
		options.include_coordinate_transform = true;
		String path = loader.save(this, options);
		if (null != path) restartAutosaving();
		return path;
	}

	/** This is not the saveAs used from the menus; this one is meant for programmatic access. */
	public String saveAs(String xml_path, boolean overwrite) throws IllegalArgumentException {
		if (null == xml_path) throw new IllegalArgumentException("xml_path cannot be null.");
		XMLOptions options = new XMLOptions();
		options.overwriteXMLFile = overwrite;
		options.export_images = false;
		options.patches_dir = null;
		options.include_coordinate_transform = true;
		String path = loader.saveAs(xml_path, options);
		if (null != path) restartAutosaving();
		return path;
	}

	/** Save an XML file that is stripped of coordinate transforms,
	 * and merely refers to them by the 'ct_id' attribute of each 't2_patch' element;
	 * this method will NOT overwrite the XML file but save into a new one,
	 * which is chosen from a file dialog. */
	public String saveWithoutCoordinateTransforms() {
		XMLOptions options = new XMLOptions();
		options.overwriteXMLFile = false;
		options.export_images = false;
		options.include_coordinate_transform = false;
		options.patches_dir = null;
		return loader.saveAs(this, options);
	}

	public boolean destroy() {
		if (null == loader) {
			return true;
		}
		if (loader.hasChanges() && !getBooleanProperty("no_shutdown_hook")) { // DBLoader always returns false
			if (ControlWindow.isGUIEnabled()) {
				final YesNoDialog yn = ControlWindow.makeYesNoDialog("TrakEM2", "There are unsaved changes in project " + title + ". Save them?");
				if (yn.yesPressed()) {
					save();
				}
			} else {
				Utils.log2("WARNING: closing project '" + title  + "' with unsaved changes.");
			}
		}
		try {
			if (null != autosaving) autosaving.cancel(true);
		} catch (Throwable t) {}
		al_open_projects.remove(this);
		// flush all memory
		if (null != loader) { // the last project is destroyed twice for some reason, if several are open. This is a PATCH
			loader.destroy(); // and disconnect
			loader = null;
		}
		if (null != layer_set) layer_set.destroy();
		ControlWindow.remove(this); // AFTER loader.destroy() call.
		if (null != template_tree) template_tree.destroy();
		if (null != project_tree) project_tree.destroy();
		if (null != layer_tree) layer_tree.destroy();
		Polyline.flushTraceCache(this);
		this.template_tree = null; // flag to mean: we're closing
		// close all open Displays
		Display.close(this);
		Search.removeTabs(this);
		synchronized (ptcache) { ptcache.clear(); }
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
			ProjectToolbar.setTool(ProjectToolbar.PENCIL); // this should go elsewhere, in display issues.
			return new Profile(this, "profile", 0, 0);
		} else if (type.equals("pipe")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Pipe(this, "pipe", 0, 0);
		} else if (type.equals("polyline")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Polyline(this, "polyline");
		} else if (type.equals("area_list")) {
			ProjectToolbar.setTool(ProjectToolbar.BRUSH);
			return new AreaList(this, "area_list", 0, 0);
		} else if (type.equals("treeline")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Treeline(this, "treeline");
		} else if (type.equals("areatree")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new AreaTree(this, "areatree");
		} else if (type.equals("ball")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Ball(this, "ball", 0, 0);
		} else if (type.equals("connector")) {
			ProjectToolbar.setTool(ProjectToolbar.PEN);
			return new Connector(this, "connector");
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
	static public boolean isBasicType(final String type) {
		return isProjectType(type)
		    || isLayerSetType(type)
		    || isLayerType(type)
		;
	}

	static public boolean isProjectType(String type) {
		type = type.toLowerCase();
		return type.equals("profile_list");
	}

	static public boolean isLayerSetType(String type) {
		type = type.toLowerCase().replace(' ', '_');
		return type.equals("area_list")
		    || type.equals("pipe")
		    || type.equals("ball")
		    || type.equals("polyline")
		    || type.equals("dissector")
		    || type.equals("stack")
		    || type.equals("treeline")
		    || type.equals("areatree")
		    || type.equals("connector")
		;
	}

	static public boolean isLayerType(String type) {
		type = type.toLowerCase().replace(' ', '_');
		return type.equals("patch")
		    || type.equals("profile")
		    || type.equals("layer")
		    || type.equals("layer_set") // for XML
		    || type.equals("label")
		;
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
		Enumeration<?> e = root.depthFirstEnumeration();
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
		layer_tree.selectNode(layer);
	}
	/** Find the node in any tree with a Thing that contains the given Displayable, and set it selected/highlighted, deselecting everything else first. */
	public void select(final Displayable d) {
		if (d.getClass() == LayerSet.class) select(d, layer_tree);
		else {
			ProjectThing pt = findProjectThing(d); // from cache: one linear search less
			if (null != pt) DNDTree.selectNode(pt, project_tree);
		}
	}

	private final void select(final Object ob, final DNDTree tree) {
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

	private final Map<Object,ProjectThing> ptcache = new HashMap<Object, ProjectThing>();
	
	/** Find a ProjectThing that contains the given object. */
	public ProjectThing findProjectThing(final Object ob) {
		ProjectThing pt;
		synchronized (ptcache) { pt = ptcache.get(ob); }
		if (null == pt) {
			pt = (ProjectThing) root_pt.findChild(ob);
			if (null != ob) synchronized (ptcache) { ptcache.put(ob, pt); }
		}
		return pt;
	}

	public void decache(final Object ob) {
		synchronized (ptcache) {
			ptcache.remove(ob);
		}
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
			ProjectThing thing = findProjectThing(d);
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

	public String getMeaningfulTitle2(final Displayable d) {
		final ProjectThing thing = findProjectThing(d);
		if (null == thing) return d.getTitle(); // happens if there is no associated node

		if (!thing.getType().equals(d.getTitle())) {
			return new StringBuilder(!thing.getType().equals(d.getTitle()) ? d.getTitle() + " [" : "[").append(thing.getType()).append(']').toString();
		}

		// Else, search upstream for a ProjectThing whose name differs from its type
		Thing parent = (ProjectThing)thing.getParent();
		while (null != parent) {
			String type = parent.getType();
			Object ob = parent.getObject();
			if (ob.getClass() == Project.class) break;
			if (!ob.equals(type)) {
				return ob.toString() + " [" + thing.getType() + "]";
			}
			parent = parent.getParent();
		}
		if (d.getTitle().equals(thing.getType())) return "[" + thing.getType() + "]";
		return d.getTitle() + " [" + thing.getType() + "]";
	}

	/** Searches upstream in the Project tree for things that have a user-defined name, stops at the first and returns it along with all the intermediate ones that only have a type and not a title, appended. */
	public String getMeaningfulTitle(final Displayable d) {
		ProjectThing thing = findProjectThing(d);
		if (null == thing) return d.getTitle(); // happens if there is no associated node
		String title = new StringBuilder(!thing.getType().equals(d.getTitle()) ? d.getTitle() + " [" : "[").append(thing.getType()).append(' ').append('#').append(d.getId()).append(']').toString();

		if (!thing.getType().equals(d.getTitle())) {
			return title;
		}

		ProjectThing parent = (ProjectThing)thing.getParent();
		StringBuilder sb = new StringBuilder(title);
		while (null != parent) {
			Object ob = parent.getObject();
			if (ob.getClass() == Project.class) break;
			String type = parent.getType();
			if (!ob.equals(type)) { // meaning, something else was typed in as a title
				sb.insert(0, new StringBuilder(ob.toString()).append(' ').append('[').append(type).append(']').append('/').toString());
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
		ProjectThing thing = findProjectThing(d);
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

	static public String getType(final Class<?> c) {
		if (AreaList.class == c) return "area_list";
		if (DLabel.class == c) return "label";
		String name = c.getName().toLowerCase();
		int i = name.lastIndexOf('.');
		if (-1 != i) name = name.substring(i+1);
		return name;
	}

	/** Returns the proper TemplateThing for the given type, complete with children and attributes if any. */
	public TemplateThing getTemplateThing(String type) {
		return ht_unique_tt.get(type);
	}

	/** Returns a list of existing unique types in the template tree
	 * (thus the 'project' type is not included, nor the label).
	 * The basic types are guaranteed to be present even if there are no instances in the template tree.
	 * As a side effect, this method populates the HashMap of unique TemplateThing types. */
	public String[] getUniqueTypes() {
		synchronized (ht_unique_tt) {
			// ensure the basic types (pipe, ball, profile, profile_list) are present
			if (!ht_unique_tt.containsKey("profile")) ht_unique_tt.put("profile", new TemplateThing("profile"));
			if (!ht_unique_tt.containsKey("profile_list")) {
				TemplateThing tpl = new TemplateThing("profile_list");
				tpl.addChild((TemplateThing) ht_unique_tt.get("profile"));
				ht_unique_tt.put("profile_list", tpl);
			}
			if (!ht_unique_tt.containsKey("pipe")) ht_unique_tt.put("pipe", new TemplateThing("pipe"));
			if (!ht_unique_tt.containsKey("polyline")) ht_unique_tt.put("polyline", new TemplateThing("polyline"));
			if (!ht_unique_tt.containsKey("treeline")) ht_unique_tt.put("treeline", new TemplateThing("treeline"));
			if (!ht_unique_tt.containsKey("areatree")) ht_unique_tt.put("areatree", new TemplateThing("areatree"));
			if (!ht_unique_tt.containsKey("connector")) ht_unique_tt.put("connector", new TemplateThing("connector"));
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
	}

	/** Remove a unique type from the HashMap. Basic types can't be removed. */
	public boolean removeUniqueType(String type) {
		if (null == type || isBasicType(type)) return false;
		synchronized (ht_unique_tt) {
			return null != ht_unique_tt.remove(type);
		}
	}

	public boolean typeExists(String type) {
		return ht_unique_tt.containsKey(type);
	}

	/** Returns false if the type exists already. */
	public boolean addUniqueType(TemplateThing tt) {
		synchronized (ht_unique_tt) {
			if (ht_unique_tt.containsKey(tt.getType())) return false;
			ht_unique_tt.put(tt.getType(), tt);
		}
		return true;
	}

	public boolean updateTypeName(String old_type, String new_type) {
		synchronized (ht_unique_tt) {
			if (ht_unique_tt.containsKey(new_type)) {
				Utils.showMessage("Can't rename type '" + old_type + "' : a type named '"+new_type+"' already exists!");
				return false;
			}
			ht_unique_tt.put(new_type, ht_unique_tt.remove(old_type));
			return true;
		}
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

	@Override
	public void exportXML(final StringBuilder sb, final String indent, final XMLOptions options) {
		Utils.logAll("ERROR: cannot call Project.exportXML(StringBuilder, String, ExportOptions) !!");
		throw new UnsupportedOperationException("Cannot call Project.exportXML(StringBuilder, String, Object)");
	}

	/** Export the main trakem2 tag wrapping four hierarchies (the project tag, the ProjectTree, and the Top Level LayerSet the latter including all Displayable objects) and a list of displays. */
	public void exportXML(final java.io.Writer writer, final String indent, final XMLOptions options) throws Exception {
		Utils.showProgress(0);
		// 1 - opening tag
		writer.write(indent);
		writer.write("<trakem2>\n");
		final String in = indent + "\t";
		// 2,3 - export the project itself
		exportXML2(writer, in, options);
		// 4 - export LayerSet hierarchy of Layer, LayerSet and Displayable objects
		layer_set.exportXML(writer, in, options);
		// 5 - export Display objects
		Display.exportXML(this, writer, in, options);
		// 6 - closing tag
		writer.write("</trakem2>\n");
	}

	// A separate method to ensure that sb_body instance is garbage collected.
	private final void exportXML2(final java.io.Writer writer, final String in, final XMLOptions options) throws Exception {
		final StringBuilder sb_body = new StringBuilder();
		// 2 - the project itself
		sb_body.append(in).append("<project \n")
		       .append(in).append("\tid=\"").append(id).append("\"\n")
		       .append(in).append("\ttitle=\"").append(title).append("\"\n");
		loader.insertXMLOptions(sb_body, in + "\t");
		// Write properties, with the additional property of the image_resizing_mode
		final HashMap<String,String> props = new HashMap<String, String>(ht_props);
		props.put("image_resizing_mode", Loader.getMipMapModeName(mipmaps_mode));
		for (final Map.Entry<String, String> e : props.entrySet()) {
			sb_body.append(in).append('\t').append(e.getKey()).append("=\"").append(e.getValue()).append("\"\n");
		}
		sb_body.append(in).append(">\n");
		// 3 - export ProjectTree abstract hierarchy (skip the root since it wraps the project itself)
		project_tree.getExpandedStates(options.expanded_states);
		if (null != root_pt.getChildren()) {
			final String in2 = in + "\t";
			for (final ProjectThing pt : root_pt.getChildren()) {
				pt.exportXML(sb_body, in2, options);
			}
		}
		sb_body.append(in).append("</project>\n");
		writer.write(sb_body.toString());
	}

	/** Export a complete DTD listing to export the project as XML. */
	public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
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
		for (String key : ht_props.keySet()) {
			sb_header.append(indent).append("<!ATTLIST project ").append(key).append(" NMTOKEN #REQUIRED>\n");
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
		Stack.exportDTD( sb_header, hs, indent );
		Treeline.exportDTD(sb_header, hs, indent);
		AreaTree.exportDTD(sb_header, hs, indent);
		Connector.exportDTD(sb_header, hs, indent);
		Displayable.exportDTD(sb_header, hs, indent); // the subtypes of all Displayable types
		// 4 - export Display
		Display.exportDTD(sb_header, hs, indent);
		// all the above could be done with reflection, automatically detecting the presence of an exportDTD method.
		// CoordinateTransforms
		mpicbg.trakem2.transform.DTD.append( sb_header, hs, indent );
	}

	/** Returns the String to be used as Document Type of the XML file, generated from the name of the root template thing.*/
	public String getDocType() {
		//TemplateThing root_tt = (TemplateThing)((DefaultMutableTreeNode)((DefaultTreeModel)template_tree.getModel()).getRoot()).getUserObject();
		return "trakem2_" + root_tt.getType();
	}

	/** Returns a user-understandable name for the given class. */
	static public String getName(final Class<?> c) {
		String name = c.getName();
		name = name.substring(name.lastIndexOf('.') + 1);
		if (name.equals("DLabel")) return "Label";
		else if (name.equals("Patch")) return "Image";
		//else if (name.equals("Pipe")) return "Tube";
		//else if (name.equals("Ball")) return "Sphere group"; // TODO revise consistency with XML templates and so on
		else return name;
	}

	public String getInfo() {
		StringBuilder sb = new StringBuilder("Project id: ");
		sb.append(this.id).append("\nProject name: ").append(this.title)
		  .append("\nTrees:\n")
		  .append(project_tree.getInfo()).append("\n")
		  .append(layer_tree.getInfo())
		;
		return sb.toString();
	}

	static public Project findProject(Loader loader) {
		for (final Project pro : al_open_projects) {
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
	public Project createSubproject(final Rectangle roi, final Layer first, final Layer last, final boolean ignore_hidden_patches) {
		try {
			// The order matters.
			final Project pr = new Project(new FSLoader(this.getLoader().getStorageFolder()));
			pr.id = this.id;
			// copy properties
			pr.title = this.title;
			pr.ht_props.putAll(this.ht_props);
			// copy template
			pr.root_tt = this.root_tt.clone(pr, true);
			pr.template_tree = new TemplateTree(pr, pr.root_tt);
			synchronized (pr.ht_unique_tt) {
				pr.ht_unique_tt.clear();
				pr.ht_unique_tt.putAll(root_tt.getUniqueTypes(new HashMap<String,TemplateThing>()));
			}
			TemplateThing project_template = new TemplateThing("project");
			project_template.addChild(pr.root_tt);
			pr.ht_unique_tt.put("project", project_template);
			// create the layers templates
			pr.createLayerTemplates();
			// copy LayerSet and all involved Displayable objects
			// (A two-step process to provide the layer_set pointer and all Layer pointers to the ZDisplayable to copy and crop.)
			pr.layer_set = (LayerSet)this.layer_set.clone(pr, first, last, roi, false, true, ignore_hidden_patches);
			LayerSet.cloneInto(this.layer_set, first, last, pr, pr.layer_set, roi, true);
			// create layer tree
			pr.root_lt = new LayerThing(Project.layer_set_template, pr, pr.layer_set);
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

			// Regenerate mipmaps (blocks GUI from interaction other than navigation)
			pr.loader.regenerateMipMaps(pr.layer_set.getDisplayables(Patch.class));

			pr.restartAutosaving();

			return pr;

		} catch (Exception e) { e.printStackTrace(); }
		return null;
	}

	public void parseXMLOptions(final HashMap<String,String> ht_attributes) {
		((FSLoader)this.project.getLoader()).parseXMLOptions(ht_attributes);
		//
		String mipmapsMode = ht_attributes.remove("image_resizing_mode");
		this.mipmaps_mode = null == mipmapsMode ? Loader.DEFAULT_MIPMAPS_MODE : Loader.getMipMapModeIndex(mipmapsMode);
		//
		// all keys that remain are properties
		ht_props.putAll(ht_attributes);
		for (Map.Entry<String,String> prop : ht_attributes.entrySet()) {
			Utils.log2("parsed: " + prop.getKey() + "=" + prop.getValue());
		}
	}
	public HashMap<String,String> getPropertiesCopy() {
		return new HashMap<String,String>(ht_props);
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

	public int getProperty(final String key, final int default_value) {
		try {
			final String s = ht_props.get(key);
			if (null == s) return default_value;
			return Integer.parseInt(s);
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
	private final boolean addBox(final GenericDialog gd, final Class<?> c) {
		final String name = Project.getName(c);
		final boolean link = "true".equals(ht_props.get(name.toLowerCase() + "_nolinks"));
		gd.addCheckbox(name, link);
		return link;
	}
	private final void setLinkProp(final boolean before, final boolean after, final Class<?> c) {
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
		boolean nolink_segmentations = "true".equals(ht_props.get("segmentations_nolinks"));
		gd.addCheckbox("Segmentations", nolink_segmentations);
		gd.addMessage("Currently linked objects will remain so\nunless explicitly unlinked.");
		boolean dissector_zoom = "true".equals(ht_props.get("dissector_zoom"));
		gd.addCheckbox("Zoom-invariant markers for Dissector", dissector_zoom);
		gd.addChoice("Image_resizing_mode: ", Loader.MIPMAP_MODES.values().toArray(new String[Loader.MIPMAP_MODES.size()]), Loader.getMipMapModeName(mipmaps_mode));
		gd.addChoice("mipmaps format:", FSLoader.MIPMAP_FORMATS, FSLoader.MIPMAP_FORMATS[loader.getMipMapFormat()]);
		boolean layer_mipmaps = "true".equals(ht_props.get("layer_mipmaps"));
		gd.addCheckbox("Layer_mipmaps", layer_mipmaps);
		boolean keep_mipmaps = "true".equals(ht_props.get("keep_mipmaps"));
		gd.addCheckbox("Keep_mipmaps_when_deleting_images", keep_mipmaps); // coping with the fact that thee is no Action context ... there should be one in the Worker thread.
		int bucket_side = (int)getProperty("bucket_side", Bucket.MIN_BUCKET_SIZE);
		gd.addNumericField("Bucket side length: ", bucket_side, 0, 6, "pixels");
		boolean no_shutdown_hook = "true".equals(ht_props.get("no_shutdown_hook"));
		gd.addCheckbox("No_shutdown_hook to save the project", no_shutdown_hook);
		int n_undo_steps = getProperty("n_undo_steps", 32);
		gd.addSlider("Undo steps", 32, 200, n_undo_steps);
		boolean flood_fill_to_image_edge = "true".equals(ht_props.get("flood_fill_to_image_edge"));
		gd.addCheckbox("AreaList_flood_fill_to_image_edges", flood_fill_to_image_edge);
		int look_ahead_cache = (int)getProperty("look_ahead_cache", 0);
		gd.addNumericField("Look_ahead_cache:", look_ahead_cache, 0, 6, "layers");
		int autosaving_interval = getProperty("autosaving_interval", 10); // default: every 10 minutes
		gd.addNumericField("Autosave every:", autosaving_interval, 0, 6, "minutes");
		int n_mipmap_threads = getProperty("n_mipmap_threads", 1);
		gd.addSlider("Number of threads for mipmaps", 1, n_mipmap_threads, n_mipmap_threads);
		int meshResolution = getProperty("mesh_resolution", 32);
		gd.addSlider("Default mesh resolution for images", 1, 512, meshResolution);
		//
		gd.showDialog();
		//
		if (gd.wasCanceled()) return;
		setLinkProp(link_labels, gd.getNextBoolean(), DLabel.class);

		boolean nolink_segmentations2 = gd.getNextBoolean();
		if (nolink_segmentations) {
			if (!nolink_segmentations2) ht_props.remove("segmentations_nolinks");
		} else if (nolink_segmentations2) ht_props.put("segmentations_nolinks", "true");

		if (adjustProp("dissector_zoom", dissector_zoom, gd.getNextBoolean())) {
			Display.repaint(layer_set); // TODO: should repaint nested LayerSets as well
		}
		this.mipmaps_mode = Loader.getMipMapModeIndex(gd.getNextChoice());

		final int new_mipmap_format = gd.getNextChoiceIndex();
		final int old_mipmap_format = loader.getMipMapFormat();
		if (new_mipmap_format != old_mipmap_format) {
			YesNoDialog yn = new YesNoDialog("MipMaps format", "Changing mipmaps format to '" + FSLoader.MIPMAP_FORMATS[new_mipmap_format] + "'requires regenerating all mipmaps. Proceed?");
			if (yn.yesPressed()) {
				if (loader.setMipMapFormat(new_mipmap_format)) {
					loader.updateMipMapsFormat(old_mipmap_format, new_mipmap_format);
				}
			}
		}

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
		n_undo_steps = (int)gd.getNextNumber();
		if (n_undo_steps < 0) n_undo_steps = 0;
		setProperty("n_undo_steps", Integer.toString(n_undo_steps));
		adjustProp("flood_fill_to_image_edge", flood_fill_to_image_edge, gd.getNextBoolean());
		double d_look_ahead_cache = gd.getNextNumber();
		if (!Double.isNaN(d_look_ahead_cache) && d_look_ahead_cache >= 0) {
			setProperty("look_ahead_cache", Integer.toString((int)d_look_ahead_cache));
			if (0 == d_look_ahead_cache) {
				Display.clearColumnScreenshots(this.layer_set);
			} else {
				Utils.logAll("WARNING: look-ahead cache is incomplete.\n  Expect issues when editing objects, adding new ones, and the like.\n  Use \"Project - Flush image cache\" to fix any lack of refreshing issues you encounter.");
			}
		} else {
			Utils.log2("Ignoring invalid 'look ahead cache' value " + d_look_ahead_cache);
		}
		double autosaving_interval2 = gd.getNextNumber();
		if (((int)(autosaving_interval2)) == autosaving_interval) {
			// do nothing
		} else if (autosaving_interval2 < 0 || Double.isNaN(autosaving_interval)) {
			Utils.log("IGNORING invalid autosaving interval: " + autosaving_interval2);
		} else {
			setProperty("autosaving_interval", Integer.toString((int)autosaving_interval2));
			restartAutosaving();
		}
		int n_mipmap_threads2 = (int)Math.max(1, gd.getNextNumber());
		if (n_mipmap_threads != n_mipmap_threads2) {
			setProperty("n_mipmap_threads", Integer.toString(n_mipmap_threads2));
			// WARNING: this does it for a static service, affecting all projects!
			FSLoader.restartMipMapThreads(n_mipmap_threads2);
		}
		int meshResolution2 = (int)gd.getNextNumber();
		if (meshResolution != meshResolution2) {
			if (meshResolution2 > 0) {
				setProperty("mesh_resolution", Integer.toString(meshResolution2));
			} else {
				Utils.log("WARNING: ignoring invalid mesh resolution value " + meshResolution2);
			}
		}
	}

	/** Return the Universal Near-Unique Id of this project, which may be null for non-FSLoader projects. */
	public String getUNUId() {
		return loader.getUNUId();
	}

	/** Removes an object from this Project. */
	public final boolean remove(final Displayable d) {
		final Set<Displayable> s = new HashSet<Displayable>();
		s.add(d);
		return removeAll(s);
	}

	/** Calls Project.removeAll(col, null) */
	public final boolean removeAll(final Set<Displayable> col) {
		return removeAll(col, null);
	}
	/** Remove any set of Displayable objects from the Layer, LayerSet and Project Tree as necessary.
	 *  ASSUMES there aren't any nested LayerSet objects in @param col. */
	public final boolean removeAll(final Set<Displayable> col, final DefaultMutableTreeNode top_node) {
		// 0. Sort into Displayable and ZDisplayable
		final Set<ZDisplayable> zds = new HashSet<ZDisplayable>();
		final List<Displayable> ds = new ArrayList<Displayable>();
		for (final Displayable d : col) {
			if (d instanceof ZDisplayable) {
				zds.add((ZDisplayable)d);
			} else {
				ds.add(d);
			}
		}
		
		// Displayable:
		// 1. First the Profile from the Project Tree, one by one,
		//    while creating a map of Layer vs Displayable list to remove in that layer:
		final HashMap<Layer,Set<Displayable>> ml = new HashMap<Layer,Set<Displayable>>();
		for (final Iterator<Displayable> it = ds.iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (d.getClass() == Profile.class) {
				if (!project_tree.remove(false, findProjectThing(d), null)) { // like Profile.remove2
					Utils.log("Could NOT delete " + d);
					continue;
				}
				it.remove(); // remove the Profile
				continue;
			}
			// The map of Layer vs Displayable list
			Set<Displayable> l = ml.get(d.getLayer());
			if (null == l) {
				l = new HashSet<Displayable>();
				ml.put(d.getLayer(), l);
			}
			l.add(d);
		}
		// 2. Then the rest, in bulk:
		if (ml.size() > 0) {
			for (final Map.Entry<Layer,Set<Displayable>> e : ml.entrySet()) {
				e.getKey().removeAll(e.getValue());
			}
		}
		// 3. Stacks
		if (zds.size() > 0) {
			final Set<ZDisplayable> stacks = new HashSet<ZDisplayable>();
			for (final Iterator<ZDisplayable> it = zds.iterator(); it.hasNext(); ) {
				final ZDisplayable zd = it.next();
				if (zd.getClass() == Stack.class) {
					it.remove();
					stacks.add(zd);
				}
			}
			layer_set.removeAll(stacks);
		}
		
		// 4. ZDisplayable: bulk removal
		if (zds.size() > 0) {
			// 1. From the Project Tree:
			Set<Displayable> not_removed = project_tree.remove(zds, top_node);
			// 2. Then only those successfully removed, from the LayerSet:
			zds.removeAll(not_removed);
			layer_set.removeAll(zds);
		}

		// TODO
		return true;
		
	}
	
	/** For undo purposes. */
	public void resetRootProjectThing(final ProjectThing pt, final HashMap<Thing,Boolean> ptree_exp) {
		this.root_pt = pt;
		project_tree.reset(ptree_exp);
	}
	/** For undo purposes. */
	public void resetRootTemplateThing(final TemplateThing tt, final HashMap<Thing,Boolean> ttree_exp) {
		this.root_tt = tt;
		template_tree.reset(ttree_exp);
	}
	/** For undo purposes. */
	public void resetRootLayerThing(final LayerThing lt, final HashMap<Thing,Boolean> ltree_exp) {
		this.root_lt = lt;
		layer_tree.reset(ltree_exp);
	}

	public TemplateThing getRootTemplateThing() {
		return root_tt;
	}
	
	public LayerThing getRootLayerThing() {
		return root_lt;
	}

	public Bureaucrat saveTask(final String command) {
		return Bureaucrat.createAndStart(new Worker.Task("Saving") {
			public void exec() {
				if (command.equals("Save")) {
					save();
				} else if (command.equals("Save as...")) {
					XMLOptions options = new XMLOptions();
					options.overwriteXMLFile = false;
					options.export_images = false;
					options.include_coordinate_transform = true;
					options.patches_dir = null;
					// Will open a file dialog
					loader.saveAs(project, options);
					restartAutosaving();
					//
				} else if (command.equals("Save as... without coordinate transforms")) {
					YesNoDialog yn = new YesNoDialog("WARNING",
							"You are about to save an XML file that lacks the information for the coordinate transforms of each image.\n"
						  + "These transforms are referred to with the attribute 'ct_id' of each 't2_patch' entry in the XML document,\n"
						  + "and the data for the transform is stored in an individual file under the folder 'trakem2.cts/'.\n"
						  + " \n"
						  + "It is advised to keep a complete XML file with all coordinate transforms included along with this new copy.\n"
						  + "Please check NOW that you have such a complete XML copy.\n"
						  + " \n"
						  + "Proceed?");
					if (!yn.yesPressed()) return;
					saveWithoutCoordinateTransforms();
					//
				} else if (command.equals("Delete stale files...")) {
					setTaskName("Deleting stale files");
					GenericDialog gd = new GenericDialog("Delete stale files");
					gd.addMessage(
							"You are about to remove all files under the folder 'trakem2.cts/' which are not referred to from the\n"
						  + "currently loaded project. If you have sibling XML files whose 't2_patch' entries (the images) refer,\n"
						  + "via 'ct_id' attributes, to coordinate transforms in 'trakem2.cts/' that this current XML doesn't,\n"
						  + "they may be LOST FOREVER. Unless you have a version of the XML file with the coordinate transforms\n"
						  + "written in it, as can be obtained by using the 'Project - Save' command.\n"
						  + " \n"
						  + "The same is true for the .zip files that store alpha masks, under folder 'trakem2.masks/'\n"
						  + "and which are referred to from the 'alpha_mask_id' attribute of 't2_patch' entries.\n"
						  + " \n"
						  + "Do you have such complete XML file? Check *NOW*.\n"
						  + " \n"
						  + "Proceed with deleting:"
							);
					gd.addCheckbox("Delete stale coordinate transform files", true);
					gd.addCheckbox("Delete stale alpha mask files", true);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					project.getLoader().deleteStaleFiles(gd.getNextBoolean(), gd.getNextBoolean());
				}
			}
		}, project);
	}
	
	/** The mode (aka algorithmic approach) used to generate mipmaps, which defaults to {@link Loader#DEFAULT_MIPMAPS_MODE}. */
	public int getMipMapsMode() {
		return this.mipmaps_mode;
	}
	
	/** @see #getMipMapsMode() */
	public void setMipMapsMode(int mode) {
		this.mipmaps_mode = mode;
	}
}
