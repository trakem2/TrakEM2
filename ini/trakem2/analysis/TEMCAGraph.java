package ini.trakem2.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JOptionPane;

import ij.gui.GenericDialog;
import ini.trakem2.Project;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Treeline;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.Utils;

//This class is specific to the Harvard TEMCA project -- we care only about how treelines connect to other treelines, and we know that
// every treeline has a parent, whose name we care about. 
public final class TEMCAGraph {

	protected Project project;
	protected Set<GraphEdge> edges = new HashSet<GraphEdge>();
	// If A synapses onto B twice, this should not count as a convergence; rather, a convergence is when A --> B and C --> B. Use this HashMap to keep a count of unique pre- and post-synaptic object pairs.
	protected HashMap<Displayable, HashSet<GraphEdge>> uniqueTargetEdges = new HashMap<Displayable, HashSet<GraphEdge>>();
	
	class GraphEdge {
		Treeline origin, target;
		Connector connector;
		GraphEdge(Treeline o, Treeline t, Connector c) {
			this.origin = o;
			this.target = t;
			this.connector = c;
		}
		String originParentName() {
			return project.getProjectTree().getRightTitleForGraph(this.origin);
		}
		String targetParentName() {
			return project.getProjectTree().getRightTitleForGraph(this.target);
		}
	}
	
	public TEMCAGraph(Project p) {
		this.project = p;
	}
	
	private void analyzeGraph(boolean core_only) {
		Collection<Connector> connectors = (Collection<Connector>) (Collection) project.getRootLayerSet().getZDisplayables(Connector.class);
		
		for (Connector con : connectors) {
			if (core_only && !isCore(con)) continue;
			Set<Displayable> origins = con.getOrigins(Treeline.class);
			if (core_only) {
				Set<Displayable> core_origins = new HashSet<Displayable>();
				for (Displayable origin_d : origins) {
					if (isCore(origin_d)) core_origins.add(origin_d);
				}
				origins = core_origins;
			}
			if (origins.isEmpty()) continue;
			// else, add all targets
			ArrayList<Set<Displayable>> target_sets = (ArrayList) con.getTargets(Treeline.class);
			if (core_only) {
				ArrayList<Set<Displayable>> core_target_sets = new ArrayList<Set<Displayable>>();
				for (Set<Displayable> target_set : target_sets) {
					HashSet<Displayable> core_target_set = new HashSet<Displayable>();
					for (Displayable target_d : target_set) {
						if (isCore(target_d)) core_target_set.add(target_d);
					}
					core_target_sets.add(core_target_set);
				}
				target_sets = core_target_sets;
			}
			for (Set<Displayable> targets : target_sets) {
				for (Displayable t : targets) {
					for (Displayable o : origins) {
						GraphEdge ge = new GraphEdge((Treeline) o, (Treeline) t, con);
						edges.add(ge);
						HashSet<GraphEdge> utes;
						if (!uniqueTargetEdges.containsKey(t)) {
							utes = new HashSet<GraphEdge>();
							utes.add(ge);
							uniqueTargetEdges.put(t, utes);
						} else {
							utes =  uniqueTargetEdges.get(t);
							boolean isUnique = true;
							for (GraphEdge ute : utes) {
								if (ge.origin == ute.origin && ge.target == ute.target) {
									isUnique = false;
									continue;
								}
							}
							if (isUnique) {
								utes.add(ge);
								uniqueTargetEdges.put(t, utes);
							}
						}
					}
				}
			}
		}
	}
	private static String physioOrnamentFromColor(String color) {
		return " [style=filled fillcolor=" + color + " color=" + color + " shape=box]";
	}
	
	private static String inhibOrnamentFromColor(String color) {
		return " [style=filled fillcolor=" + color + " color=" + color + " shape=box]";
	}
	
	private static String inhibFragmentOrnamentFromColor(String color) {
		return " [color=" + color + "]";
	}
	
	private static String pyrOrnamentFromColor(String color) {
		return " [style=filled fillcolor=pink color=" + color + " shape=box]";
	}
	
	private static String pyrFragmentOrnamentFromColor(String color) {
		return " [color=" + color + "]";
	}
	
	private static String apicalOrnamentFromColor(String color){
		return " [style=filled fillcolor=pink color=" + color + " shape=box]";
	}
	
	// run up the project hierarchy looking for string "core" in a containing ProjectThing's title; if found, return true.
	// derived from Project.getMeaningfulTitle(d)
	private boolean isCore(Displayable d) {
		ProjectThing thing = (ProjectThing)project.getRootProjectThing().findChild(d);
		if (null == thing) {
			Utils.log("WARNING: " + d.getTitle() + " missing associated ProjectThing, id='" + Long.toString(d.getId()) + "'");
			return false;
		}

		if (thing.getTitle().contains("core")) return true;

		ProjectThing parent = (ProjectThing)thing.getParent();
		while (null != parent) {
			Object ob = parent.getObject();
			if (ob.getClass() == Project.class) return false;
			if (parent.getTitle().contains("core")) return true;
			parent = (ProjectThing)parent.getParent();
		}
		
		Utils.log("WARNING: TEMCAGraph.isCore() reached code that should be inaccessible");
		return false;
	}
	// N.b. 'convergencesOnly' is somewhat of a misnomer -- this will
	// also output hits from tuned cell to tuned cell, even if the post-synaptic tuned cell is not otherwise converged upon.
	// TODO break this out into a separate file, say ProjectGraph.java
	public void logDotGraph(Display display) {
		GenericDialog gd = new GenericDialog("Analyze TEMCA graph");
		boolean core_only = true;
		boolean convergences_only = true;
		boolean hide_inhib = false;
		boolean show_all_physio = true;
		boolean add_to_selection = false;
		boolean restrict_by_user = false;
		boolean add_only_physio_cons_to_sel = false;
		boolean add_only_non_physio_cons_to_sel = false;
		
		String restrict_by_user_name = null;
		Set<String> user_names = null;
		FSLoader loader = null;
		gd.addCheckbox("Core only?", convergences_only);
		gd.addCheckbox("Convergences only?", convergences_only);
		gd.addCheckbox("Hide inhibitory?", hide_inhib);
		gd.addCheckbox("Show all physiologically characterized cells?", show_all_physio);
		gd.addCheckbox("Add connections to selection?", add_to_selection);
		final String[] selection_list = {"No limit", "Physiologically characterized cells", "Non-physiologically characterized objects"};
		gd.addChoice("Limit selection addition by postsynaptic type:", selection_list, "No limit");
		if (project.getLoader() instanceof FSLoader && ((FSLoader) project.getLoader()).userIDRangesPresent()) {
			loader = (FSLoader) project.getLoader();
			 user_names = loader.getUserNames();
			if (null != user_names) {
				gd.addCheckbox("Restrict connections by user?", restrict_by_user);
				final String[] user_name_list = user_names.toArray(new String[0]);
				// final String[] choices = (String[]) ht_user_id_ranges.keySet().toArray();
				Arrays.sort(user_name_list);
				final int cur_choice = loader.getCurrentUserName().equals("_system") ? 0 : Arrays.binarySearch(user_name_list, loader.getCurrentUserName()); // ASSUMPTION: shouldn't be able to get here if which_user_id_range is null or not in choices
				gd.addChoice("User", user_name_list, loader.getCurrentUserName());
			}
		}
		gd.showDialog();
		if (gd.wasCanceled()) return;
		core_only = gd.getNextBoolean();
		analyzeGraph(core_only);
		convergences_only = gd.getNextBoolean();
		hide_inhib = gd.getNextBoolean();
		show_all_physio = gd.getNextBoolean();
		add_to_selection = gd.getNextBoolean();
		if (add_to_selection) {
			int limit = gd.getNextChoiceIndex();
			if (1 == limit) {
				add_only_physio_cons_to_sel = true;
			} else if (2 == limit) {
				add_only_non_physio_cons_to_sel = true;
			}
		}
		if (null != user_names) {
			restrict_by_user = gd.getNextBoolean();
			restrict_by_user_name = gd.getNextChoice();
		}
		

		HashMap<String, String> physio_cells = new HashMap<String, String>(); // name, color
		physio_cells.put("10blue", "blue");
		physio_cells.put("11green", "green");
		physio_cells.put("13green", "green");
		physio_cells.put("14green", "green");
//		physio_cells.put("15orange", "orange"); // the astrocyte
		physio_cells.put("16blue", "blue");
		physio_cells.put("17orange", "orange");
		physio_cells.put("18green", "green");
		physio_cells.put("19orange", "orange");
		physio_cells.put("20red", "red");
		physio_cells.put("21green", "green");
		physio_cells.put("22red", "red");
		physio_cells.put("23orange", "orange");
		physio_cells.put("45white", "grey");
		
		

		HashMap<String, String> dot_nodes = new HashMap<String, String>(); // name, ornament
		
		long n_physio=0;
		long n_pyr=0; 
		long n_pyr_frag=0; 
		long n_inhib=0; 
		long n_inhib_frag=0; 
		long n_apical=0;
		
		if (show_all_physio) {
			for (String cell_name : physio_cells.keySet()) {
				dot_nodes.put(cell_name, physioOrnamentFromColor(physio_cells.get(cell_name)));
				n_physio++;
			}
		}
		
		ArrayList<String> edge_lines = new ArrayList<String>();
		for (GraphEdge ge : edges) {
			if (convergences_only && uniqueTargetEdges.get(ge.target).size() > 1 || 
					!convergences_only || 
					(show_all_physio && physio_cells.containsKey(ge.originParentName()) && physio_cells.containsKey(ge.targetParentName()))) {
				if ((!hide_inhib || !ge.targetParentName().startsWith("inhib"))) {
					if (restrict_by_user) {
						String connector_user_name = loader.userNameFromID(ge.connector.getId());
						if (!connector_user_name.equals(restrict_by_user_name)) {
							continue;
						}
					}
					String origin_parent_name = ge.originParentName();
					String target_parent_name = ge.targetParentName();
					String edge_line = "\t\"" + origin_parent_name + "\" -> \"" + target_parent_name + "\"";
					if (physio_cells.containsKey(origin_parent_name)) {
						edge_line = edge_line + " [color=" + physio_cells.get(origin_parent_name) + "]";
						if (!dot_nodes.containsKey(origin_parent_name)) { // in case show_all_physio is false, still output node as a physio cell
							dot_nodes.put(origin_parent_name, physioOrnamentFromColor(physio_cells.get(origin_parent_name)));
						}
					} 
					edge_line = edge_line + ";";
					edge_lines.add(edge_line);
					
					if (!dot_nodes.containsKey(target_parent_name)) {
						if (target_parent_name.startsWith("inhib_dendrite"))	{
							dot_nodes.put(target_parent_name, inhibFragmentOrnamentFromColor("grey"));
							n_inhib_frag++;
						} else if (target_parent_name.startsWith("inhib"))	{
							dot_nodes.put(target_parent_name, inhibOrnamentFromColor("grey"));
							n_inhib++;
						} else if (target_parent_name.startsWith("pyr")) {
							dot_nodes.put(target_parent_name, pyrOrnamentFromColor("magenta"));
							n_pyr++;
						} else if (target_parent_name.startsWith("dendrite")) {
							dot_nodes.put(target_parent_name, pyrFragmentOrnamentFromColor("magenta"));
							n_pyr_frag++;
						} else if (target_parent_name.startsWith("apical")) {
							dot_nodes.put(target_parent_name, apicalOrnamentFromColor("magenta"));
							n_apical++;
						} else {
							dot_nodes.put(target_parent_name, "");
							Utils.log("WARNING: TEMCAGraph.logDotGraph encountered uncategorized node '" + target_parent_name + "'");
						}
					}
					
					if (add_to_selection) {
						if ((!add_only_physio_cons_to_sel && !add_only_non_physio_cons_to_sel) ||
							(add_only_physio_cons_to_sel && physio_cells.containsKey(target_parent_name)) || 
							(add_only_non_physio_cons_to_sel && !physio_cells.containsKey(target_parent_name))) {
								display.getSelection().add(ge.connector);
						}
					}
				}
			}
		}
		
		Utils.log2("/* logDotGraph:\n\tn_physio=" + n_physio + "\n\tn_pyr=" + n_pyr + "\n\tn_pyr_frag=" + n_pyr_frag + "\n\tn_inhib=" + n_inhib + "\n\tn_inhib_frag=" + n_inhib_frag + "\n\tn_apical=" + n_apical + "\n*/");
		
		Utils.log2("digraph t2 {");
		
		for (String dot_node_name : dot_nodes.keySet()) {
			String ornament = dot_nodes.get(dot_node_name);
			Utils.log2("\t\"" + dot_node_name + "\"" + ornament + ";");
		}
		for (String edge_line : edge_lines) {
			Utils.log2(edge_line);
		}
		Utils.log2("}");
	}
}