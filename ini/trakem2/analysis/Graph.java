package ini.trakem2.analysis;

import ij.gui.GenericDialog;
import ij.text.TextWindow;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Connector;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.utils.Utils;

import java.awt.Point;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.SwingUtilities;

public class Graph {


	static public final <T extends Displayable> Map<String,StringBuilder> extractGraph(final LayerSet ls, final Set<Class<T>> only) {
		
		final StringBuilder sif = new StringBuilder(4096),
							xml = new StringBuilder(4096).append("<graph>\n"),
							names = new StringBuilder(4096);
		
		final Set<Displayable> seen = new HashSet<Displayable>();

		for (final Connector con : ls.getAll(Connector.class)) {
			Set<Displayable> origins = con.getOrigins();
			if (origins.isEmpty()) {
				Utils.log("Graph: ignoring connector without origins: #" + con.getId());
				continue;
			}
			List<Set<Displayable>> target_lists = con.getTargets();
			if (target_lists.isEmpty()) {
				Utils.log("Graph: ignoring connector without targets: #" + con.getId());
				continue;
			}
			for (final Displayable origin : origins) {
				if (Thread.currentThread().isInterrupted()) return null;
				if (null != only && !only.contains(origin.getClass())) continue;
				seen.add(origin);
				for (final Set<Displayable> targets : target_lists) {
					for (final Displayable target : targets) {
						if (null != only && !only.contains(target.getClass())) continue;
						sif.append(origin.getId()).append(" pd ").append(target.getId()).append('\n');
						xml.append('\t').append("<edge cid=\"").append(con.getId()).append("\" origin=\"").append(origin.getId()).append("\" target=\"").append(target.getId()).append("\" />\n");
						seen.add(target);
					}
				}
			}
		}

		xml.append("</graph>\n");

		for (final Displayable d : seen) {
			names.append(d.getId()).append('\t').append(d.getProject().getMeaningfulTitle(d)).append('\n');
		}

		final Map<String,StringBuilder> m = new HashMap<String,StringBuilder>();
		m.put("sif", sif);
		m.put("xml", xml);
		m.put("names", names);
		return m;
	}

	/** Extract the graph based on connectors; leave @param only null to include all types. */
	static public final <T extends Displayable> void extractAndShowGraph(final LayerSet ls, final Set<Class<T>> only) {
		final Map<String,StringBuilder> m = Graph.extractGraph(ls, only);
		if (null == m) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			new TextWindow("Graph", m.get("xml").toString(), 500, 500);
			TextWindow tw = new TextWindow("SIF", m.get("sif").toString(), 500, 500);
			Point p = tw.getLocation();
			tw.setLocation(p.x + 50, p.y + 50);
			tw = new TextWindow("Names", m.get("names").toString(), 500, 500);
			tw.setLocation(p.x + 100, p.y + 100);
		}});
	}

	/** Shows a dialog to pick which classes is one interested in. */
	static public final void extractAndShowGraph(final LayerSet ls) {
		GenericDialog gd = new GenericDialog("Graph elements");
		Class<Displayable>[] c = new Class[]{AreaList.class, AreaTree.class, Ball.class, Connector.class, Patch.class, Pipe.class, Polyline.class, Profile.class, DLabel.class, Treeline.class};
		String[] types = new String[]{"AreaList", "AreaTree", "Ball", "Connector", "Image", "Pipe", "Polyline", "Profile", "Text", "Treeline"};
		boolean[] states = new boolean[]{true, true, false, false, false, false, true, true, false, true};
		assert(c.length == types.length && types.length == states.length);
		for (int i=0; i<c.length; i++) {
			if (ZDisplayable.class.isAssignableFrom(c[i])) {
				if (!ls.contains(c[i])) states[i] = false;
			} else if (!ls.containsDisplayable(c[i])) states[i] = false;
		}
		gd.addCheckboxGroup(types.length, 1, types, states, new String[]{"Include only:"});
		gd.showDialog();
		if (gd.wasCanceled()) return;
		HashSet<Class<Displayable>> only = new HashSet<Class<Displayable>>();
		for (int i=0; i<types.length; i++) {
			if (gd.getNextBoolean()) only.add(c[i]);
		}
		Graph.extractAndShowGraph(ls, only);
	}
}