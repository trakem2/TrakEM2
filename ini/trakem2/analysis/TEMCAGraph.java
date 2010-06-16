package ini.trakem2.analysis;

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
import ini.trakem2.utils.Utils;

//This class is specific to the Harvard TEMCA project -- we care only about how treelines connect to other treelines, and we know that
// every treeline has a parent, whose name we care about. 
public final class TEMCAGraph {

	protected Project project;
	protected Set<GraphEdge> edges = new HashSet<GraphEdge>();
	// If A synapses onto B twice, this should not count as a convergence; rather, a convergence is when A --> B and C --> B. Use this HashMap to keep a count of unique pre- and post-synaptic object pairs.
	protected HashMap<Displayable, HashSet<GraphEdge>> uniqueTargetEdges = new HashMap<Displayable, HashSet<GraphEdge>>();
	protected HashMap<String, String> physio_cells = new HashMap<String, String>(); // name, color
	
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
		final Collection<Connector> connectors = (Collection<Connector>) (Collection) p.getRootLayerSet().getZDisplayables(Connector.class);
		physio_cells.put("10blue", "blue");
		physio_cells.put("11green", "green");
		physio_cells.put("13green", "green");
		physio_cells.put("14green", "green");
//		physio_cells.put("15orange", "blue"); // the astrocyte
		physio_cells.put("16blue", "blue");
		physio_cells.put("17orange", "orange");
		physio_cells.put("18green", "green");
		physio_cells.put("19orange", "orange");
		physio_cells.put("20red", "red");
		physio_cells.put("21green", "green");
		physio_cells.put("23orange", "orange");
		physio_cells.put("45white", "grey");
		
		for (Connector con : connectors) {
			Set<Displayable> origins = con.getOrigins(Treeline.class);
			if (origins.isEmpty()) continue;
			// else, add all targets
			for (Set<Displayable> targets : con.getTargets(Treeline.class)) {
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
	
	protected boolean isPhysio(String name) {
		return physio_cells.containsKey(name);
	}
	
	// N.b. 'convergencesOnly' is somewhat of a misnomer -- this will
	// also output hits from tuned cell to tuned cell, even if the post-synaptic tuned cell is not otherwise converged upon.
	// TODO break this out into a separate file, say ProjectGraph.java
	public void logDotGraph(Display display) {
		GenericDialog gd = new GenericDialog("Merge many (davi-experimenting)");
		boolean convergences_only = false;
		boolean hide_inhib = false;
		boolean show_all_physio = true;
		boolean add_to_selection = false;
		boolean restrict_by_user = false;
		String restrict_by_user_name = null;
		Set<String> user_names = null;
		FSLoader loader = null;
		gd.addCheckbox("Convergences only?", convergences_only);
		gd.addCheckbox("Hide inhibitory?", hide_inhib);
		gd.addCheckbox("Show all physiologically characterized cells?", show_all_physio);
		gd.addCheckbox("Add connections to selection?", add_to_selection);
		if (project.getLoader() instanceof FSLoader && ((FSLoader) project.getLoader()).userIDRangesPresent()) {
			loader = (FSLoader) project.getLoader();
			 user_names = loader.getUserNames();
			if (null != user_names) {
				gd.addCheckbox("Restrict connections by user?", restrict_by_user);
				final String[] choices = user_names.toArray(new String[0]);
				// final String[] choices = (String[]) ht_user_id_ranges.keySet().toArray();
				Arrays.sort(choices);
				final int cur_choice = loader.getCurrentUserName().equals("_system") ? 0 : Arrays.binarySearch(choices, loader.getCurrentUserName()); // ASSUMPTION: shouldn't be able to get here if which_user_id_range is null or not in choices
				gd.addChoice("User", choices, loader.getCurrentUserName());
			}
		}
		gd.showDialog();
		if (gd.wasCanceled()) return;
		convergences_only = gd.getNextBoolean();
		hide_inhib = gd.getNextBoolean();
		show_all_physio = gd.getNextBoolean();
		add_to_selection = gd.getNextBoolean();
		if (null != user_names) {
			restrict_by_user = gd.getNextBoolean();
			restrict_by_user_name = gd.getNextChoice();
		}
		
		
		Utils.log2("digraph t2 {");
		if (show_all_physio) { // TODO if show_all_physio is false, still output the cell name and color if the cell is in the graph anyway
			for (String cell_name : physio_cells.keySet()) {
				String cell_color = physio_cells.get(cell_name);
				Utils.log2("\t\"" + cell_name + "\" [color=" + cell_color + "\";");
			}
		}
		for (GraphEdge ge : edges) {
			if (convergences_only && uniqueTargetEdges.get(ge.target).size() > 1 || 
					!convergences_only || 
					(show_all_physio && isPhysio(ge.originParentName()) && isPhysio(ge.targetParentName()))) {
				if ((!hide_inhib || !ge.targetParentName().startsWith("inhib"))) {
					if (restrict_by_user) {
						String connector_user_name = loader.userNameFromID(ge.connector.getId());
						if (!connector_user_name.equals(restrict_by_user_name)) {
							continue;
						}
					}
					Utils.log2("\t\"" + ge.originParentName() + "\" -> \"" + ge.targetParentName() + "\";");
					if (add_to_selection) {
						display.getSelection().add(ge.connector);
					}
				}
			}
		}
		Utils.log2("}");
	}
}