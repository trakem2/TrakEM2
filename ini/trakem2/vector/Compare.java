/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2006, 2007 Albert Cardona.

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

package ini.trakem2.vector;

import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.display.*;
import ini.trakem2.utils.*;
import mpi.fruitfly.general.MultiThreading;

import ij.gui.GenericDialog;
import ij.measure.Calibration;

import java.awt.Color;
import java.awt.Component;
import java.awt.Checkbox;
import java.awt.Dimension;
import java.awt.event.*;
import java.awt.Container;
import java.awt.Choice;
import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.event.*;
import javax.swing.table.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.awt.geom.AffineTransform;

import javax.vecmath.Vector3d;
import javax.media.j3d.Transform3D;

public class Compare {

	static private JLabel label = null;
	static private JTabbedPane tabs = null;
	static private JFrame frame = null;
	static private Hashtable<JScrollPane,Displayable> ht_tabs = null;
	static private KeyListener kl = null;

	private Compare() {}

	static public final Bureaucrat findSimilar(final Pipe pipe) {
		ArrayList<Project> pro = Project.getProjects();
		Project[] all = new Project[pro.size()];
		pro.toArray(all);
		Project[] ref = null;
		GenericDialog gd = new GenericDialog("Identify");
		if (all.length > 1) {
			gd.addMessage("Choose a project to search into");
			String[] options = new String[all.length + 1];
			options[0] = "[-- ALL --]";
			for (int i=0; i<all.length; i++) {
				options[i+1] = all[i].toString();
			}
			gd.addChoice("Project: ", options, options[0]);
		} else {
			ref = new Project[1];
			ref[0] = all[0];
		}
		gd.addCheckbox("Ignore orientation", true);
		gd.addCheckbox("Mirror", false);
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(0), new Component[]{(Component)gd.getCheckboxes().get(1)}, null);
		gd.addCheckbox("Ignore calibration", false);
		gd.showDialog();
		if (gd.wasCanceled()) return null;

		boolean ignore_orientation = gd.getNextBoolean();
		boolean ignore_calibration = gd.getNextBoolean();
		boolean mirror = gd.getNextBoolean();

		if (all.length > 1) {
			int choice = gd.getNextChoiceIndex();
			if (0 == choice) {
				ref = all;
			} else {
				ref = new Project[1];
				ref[0] = all[choice-1];
			}
		}
		return findSimilar(pipe, ref, ignore_orientation, ignore_calibration, mirror);

	}

	static public final Bureaucrat findSimilarWithAxes(final Pipe pipe) {
		final ArrayList<Project> pro = Project.getProjects();
		if (pro.size() < 2) {
			Utils.log("Compare.findSimilarWithAxes needs at least 2 open projects.");
			return null;
		}
		final int iother = 0 == pro.indexOf(pipe.getProject()) ? 1 : 0;
		final Project[] all = new Project[pro.size()];
		pro.toArray(all);
		GenericDialog gd = new GenericDialog("Indentify with axes");
		gd.addMessage("Will search for a match to:");
		gd.addMessage(pipe.getProject().getMeaningfulTitle(pipe));

		ArrayList<ZDisplayable> pipes = pipe.getLayerSet().getZDisplayables(Pipe.class);
		final String[] pipe_names = new String[pipes.size()];

		final String[][] presets = {{"medial lobe", "dorsal lobe", "peduncle"}};
		final String[] preset_names = new String[]{"X - 'medial lobe', Y - 'dorsal lobe', Z - 'peduncle'"};
		/* 0 */ gd.addChoice("Presets: ", preset_names, preset_names[0]);
		final Choice cpre = (Choice)gd.getChoices().get(0);

		final ArrayList<ZDisplayable> pipes_ref = all[iother].getRootLayerSet().getZDisplayables(Pipe.class);
		final String[] pipe_names_ref = new String[pipes_ref.size()];
		final Object[] holder = new Object[]{pipe_names_ref};

		// automatically find for the first preset
		int[] s = findXYZAxes(presets[0], pipes, pipe_names);
		int[] t = findXYZAxes(presets[0], pipes_ref, pipe_names_ref);

		gd.addMessage("Source project \"" + pipe.getProject().getTitle() + ":\"");
		/* 1 */ gd.addChoice("X: ", pipe_names, pipe_names[s[0]]);
		/* 2 */ gd.addChoice("Y: ", pipe_names, pipe_names[s[1]]);
		/* 3 */ gd.addChoice("Z: ", pipe_names, pipe_names[s[2]]);

		gd.addMessage("Reference project:");
		String[] options = new String[all.length];
		for (int i=0; i<all.length; i++) {
			options[i] = all[i].toString();
		}
		/* 4 */ gd.addChoice("Project: ", options, options[iother]);
		/* 5 */ gd.addChoice("X: ", pipe_names_ref, pipe_names_ref[t[0]]);
		/* 6 */ gd.addChoice("Y: ", pipe_names_ref, pipe_names_ref[t[1]]);
		/* 7 */ gd.addChoice("Z: ", pipe_names_ref, pipe_names_ref[t[2]]);

		// refresh reference project choices
		final Choice project_choice = (Choice)gd.getChoices().get(4);
		final Choice[] ref = new Choice[3];
		ref[0] = (Choice)gd.getChoices().get(5);
		ref[1] = (Choice)gd.getChoices().get(6);
		ref[2] = (Choice)gd.getChoices().get(7);
		project_choice.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				String project_name = (String)ie.getItem();
				Project project = null;
				for (int i=0; i<all.length; i++) {
					if (all[i].getTitle().equals(project_name)) {
						project = all[i];
						break;
					}
				}
				if (null == project) return;
				pipes_ref.clear();
				pipes_ref.addAll(project.getRootLayerSet().getZDisplayables(Pipe.class));
				String[] pipe_names_ref = new String[pipes_ref.size()];
				holder[0] = pipe_names_ref;
				int[] s = findXYZAxes(presets[cpre.getSelectedIndex()], pipes_ref, pipe_names_ref);
				int ix = ref[0].getSelectedIndex();
				int iy = ref[1].getSelectedIndex();
				int iz = ref[2].getSelectedIndex();
				for (int i=0; i<3; i++) {
					int index = ref[i].getSelectedIndex();
					ref[i].removeAll();
					for (int k=0; k<pipe_names_ref.length; k++) {
						ref[i].add(pipe_names_ref[k]);
					}
					if (0 != s[i]) ref[i].select(s[i]);
					else ref[i].select(index); // previous one
				}
			}
		});

		gd.showDialog();
		if (gd.wasCanceled()) return null;

		// ok, ready
		int ipresets = gd.getNextChoiceIndex();

		Pipe[] axes = new Pipe[]{
			(Pipe)pipes.get(gd.getNextChoiceIndex()),
			(Pipe)pipes.get(gd.getNextChoiceIndex()),
			(Pipe)pipes.get(gd.getNextChoiceIndex())
		};

		int iproject = gd.getNextChoiceIndex();

		Pipe[] axes_ref = new Pipe[]{
			(Pipe)pipes_ref.get(gd.getNextChoiceIndex()),
			(Pipe)pipes_ref.get(gd.getNextChoiceIndex()),
			(Pipe)pipes_ref.get(gd.getNextChoiceIndex())
		};

		return findSimilarWithAxes(pipe, axes, axes_ref, pipes_ref);
	}

	static private int[] findXYZAxes(String[] presets, ArrayList<ZDisplayable> pipes, String[] pipe_names) {
		int[] s = new int[3];
		int next = 0;
		for (ZDisplayable zd : pipes) {
			pipe_names[next] = zd.getProject().getMeaningfulTitle(zd);
			final String lc = pipe_names[next].toLowerCase();
			if (lc.contains(presets[0])) {
				s[0] = next;
			} else if (lc.contains(presets[1])) {
				s[1] = next;
			} else if (lc.contains(presets[2])) {
				s[2] = next;
			}
			next++;
		}
		return s;
	}

	/** Generate calibrated origin of coordinates. */
	static private Vector3d[] obtainOrigin(final Pipe[] axes) {
		// pipe's axes
		VectorString3D v1 = axes[0].asVectorString3D();
		VectorString3D v2 = axes[1].asVectorString3D();
		VectorString3D v3 = axes[2].asVectorString3D();
		Calibration cal = (null != axes[0].getLayerSet() ? axes[0].getLayerSet().getCalibrationCopy() : null);
		if (null != cal) {
			v1.calibrate(cal);
			v2.calibrate(cal);
			v3.calibrate(cal);
		}
		// resample
		double delta = (v1.getAverageDelta() + v2.getAverageDelta() + v3.getAverageDelta()) / 3;
		v1.resample(delta);
		v2.resample(delta);
		v3.resample(delta);

		// return origin vectors for pipe's project
		return VectorString3D.createOrigin(v1, v2, v3); // requires resampled vs
	}

	/** Compare pipe to all pipes in pipes_ref, by first transforming to match both sets of axes. */
	static public final Bureaucrat findSimilarWithAxes(final Pipe pipe, final Pipe[] axes, final Pipe[] axes_ref, final ArrayList<ZDisplayable> pipes_ref) {
		if (axes.length < 3 || axes_ref.length < 3) {
			Utils.log("Need three axes for each.");
			return null;
		}
		Worker worker = new Worker("Comparing pipes...") {
			public void run() {
				startedWorking();
				try {

		// obtain axes origin vectors for pipe's project
		Vector3d[] o1 = obtainOrigin(axes);

		// obtain origin vectors for reference project
		Vector3d[] o2 = obtainOrigin(axes_ref);

		// bring query pipe to common space
		final VectorString3D vs_pipe = pipe.asVectorString3D();
		// step 1 - calibrate
		Calibration cal1 = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibrationCopy() : null);
		if (null != cal1) vs_pipe.calibrate(cal1);
		// step 2 - resample
		final double delta1 = vs_pipe.getAverageDelta();
		vs_pipe.resample(delta1);
		// step 3 - transform to axes
		vs_pipe.translate(new Vector3d(-o1[3].x, -o1[3].y, -o1[3].z));
		Transform3D rot1 = VectorString3D.createOriginRotationTransform(o1);
		vs_pipe.transform(rot1);
		// reversed copy of query pipe
		final VectorString3D vs_pipe_reversed = vs_pipe.makeReversedCopy();

		// bring all pipes in the reference project to common space
		final Vector3d trans2 = new Vector3d(-o2[3].x, -o2[3].y, -o2[3].z);
		final Transform3D rot2 = VectorString3D.createOriginRotationTransform(o2);

		// match pipe against all
		final Pipe[] p = new Pipe[pipes_ref.size()];
		final Match[] match = new Match[p.length];
		pipes_ref.toArray(p);
		final Calibration cal2 = (null != p[0].getLayerSet() ? p[0].getLayerSet().getCalibrationCopy() : null);

		final Thread[] threads = new Thread[1]; //MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread(new Runnable() {
				public void run() {
				////
		for (int k = ai.getAndIncrement(); k < p.length; k = ai.getAndIncrement()) {
			try {
				// the other
				VectorString3D vs2 = p[k].asVectorString3D();
				// step 1 - calibrate
				if (null != cal2) vs2.calibrate(cal2);
				// step 2 - resample 
				vs2.resample(delta1);
				// step 3 - transform to axes
				vs2.translate(trans2);
				vs2.transform(rot2);

				// test vs1 against vs2
				Editions ed = new Editions(vs_pipe, vs2, delta1, false);
				double score = ed.getDistance();
				Utils.log2("Score: " + score + "  similarity: " + ed.getSimilarity());

				// test vs1 reversed against vs2
				Editions ed_rev = new Editions(vs_pipe_reversed, vs2, delta1, false);
				double score_rev = ed_rev.getDistance();
				Utils.log2("Score (reversed): " + score_rev + "  similarity: " + ed_rev.getSimilarity());

				// choose smallest distance
				if (ed.getDistance() < ed_rev.getDistance()) {
					match[k] = new Match(p[k], ed, score);
				} else {
					match[k] = new Match(p[k], ed_rev, score_rev);
				}

			} catch (Exception e) {
				IJError.print(e);
			}
		}
				////
				}
			});
		}
		MultiThreading.startAndJoin(threads);

		Arrays.sort(match, new OrderMatch());

		final Vector<Displayable> v_obs = new Vector<Displayable>(match.length);
		final Vector<Editions> v_eds = new Vector<Editions>(match.length);
		final Vector<Double> v_scores = new Vector<Double>(match.length);
		for (int i=0; i<match.length; i++) {
			v_obs.add(match[i].displ);
			v_eds.add(match[i].ed);
			v_scores.add(match[i].score);
		}

		// order by distance and show in a table
		addTab(pipe, v_obs, v_eds, v_scores, new Visualizer(pipe, vs_pipe, delta1, cal2, trans2, rot2));


		/* // debug: show the query among all others
		Display3D.addMesh(pipe.getLayerSet(), vs_pipe, pipe.getTitle(), Color.green);
		for (int i=0; i<v_obs.size(); i++) {
			Pipe pe = (Pipe)v_obs.get(i);
			VectorString3D vspe = pe.asVectorString3D();
			vspe.calibrate(cal2);
			vspe.resample(delta1);
			vspe.translate(trans2);
			vspe.transform(rot2);
			Display3D.addMesh(pipe.getLayerSet(), vspe, pe.getTitle(), Color.yellow);
		}
		*/

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		Bureaucrat burro = new Bureaucrat(worker, pipe.getProject());
		burro.goHaveBreakfast();
		return burro;
	}


	static private class Visualizer {
		Pipe queried;
		VectorString3D vs_queried; // the queried pipe
		double delta1;
		Calibration cal2;
		Vector3d trans2;
		Transform3D rot2;
		LayerSet common; // calibrated to queried pipe space, which is now also the space of all others.
		boolean vs_pipe_shows = false;

		Visualizer(Pipe queried, VectorString3D vs_queried, double delta1, Calibration cal2, Vector3d trans2, Transform3D rot2) {
			this.queried = queried;
			this.vs_queried = vs_queried;
			LayerSet ls = queried.getLayerSet();
			Calibration cal1 = ls.getCalibrationCopy();
			this.common = new LayerSet(queried.getProject(), queried.getProject().getLoader().getNextId(), "Common", 10, 10, 0, 0, 0, ls.getLayerWidth() * cal1.pixelWidth, ls.getLayerHeight() * cal1.pixelHeight, false, false, new AffineTransform());
			Calibration cal = new Calibration();
			cal.setUnit(cal1.getUnit()); // homogeneous on all axes
			this.common.setCalibration(cal);
			this.delta1 = delta1;
			this.cal2 = cal2;
			this.trans2 = trans2;
			this.rot2 = rot2;
		}
		public void show(Pipe pipe) {
			if (!vs_pipe_shows) {
				Display3D.addMesh(common, vs_queried, queried.getProject().getMeaningfulTitle(queried), queried.getColor());
				vs_pipe_shows = true;
			}
			VectorString3D vspe = pipe.asVectorString3D();
			vspe.calibrate(cal2);
			vspe.resample(delta1);
			vspe.translate(trans2);
			vspe.transform(rot2);
			// The LayerSet is that of the Pipe being queried, not the given pipe to show which belongs to the reference project (and thus not to the queried pipe project)
			Display3D.addMesh(common, vspe, pipe.getProject().getMeaningfulTitle(pipe), pipe.getColor());
		}
	}

	/** Compare the given pipe with other pipes in the given standard project. WARNING: the calibrations will work ONLY IF all pipes found to compare with come from LayerSets which have the same units of measurement! For example, all in microns. */
	static public final Bureaucrat findSimilar(final Pipe pipe, final Project[] ref, final boolean ignore_orientation, final boolean ignore_calibration, final boolean mirror) {
		final Worker worker = new Worker("Comparing pipes...") {
			public void run() {
				startedWorking();
				try {

		final ArrayList al = new ArrayList();
		Utils.log2("Will search into " + ref.length + " projects.");
		for (int i=0; i<ref.length; i++) {
			Utils.log2("will search into project " + ref[i]);
			al.addAll(ref[i].getRootLayerSet().get(Pipe.class));
		}

		// remove the self
		al.remove(pipe);

		if (0 == al.size()) {
			Utils.log("No other pipes found to compare with.");
			finishedWorking();
			return;
		}

		Calibration cal = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibrationCopy() : null);

		final VectorString3D vs = pipe.asVectorString3D();
		/* 1 */ if (!ignore_calibration && null != cal) vs.calibrate(cal);
		/* 2 */ final double delta = vs.getAverageDelta(); // after calibration!
		/* 3 */ vs.resample(delta);
		/* 4 */ 
		if (ignore_orientation) {
			if (mirror) vs.mirror(VectorString.X_AXIS);
			vs.relative();
		}

		final VectorString3D vs_reverse = vs.makeReversedCopy();

		// array of pipes to test 'vs' and 'vs_reverse' against:
		final Pipe[] p = new Pipe[al.size()];
		final Match[] match = new Match[p.length];
		al.toArray(p);

		final Thread[] threads = MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread(new Runnable() {
				public void run() {
				////
		for (int k = ai.getAndIncrement(); k < p.length; k = ai.getAndIncrement()) {
			try {
				VectorString3D v = p[k].asVectorString3D();
				Calibration cal = (null != p[k].getLayerSet() ? p[k].getLayerSet().getCalibrationCopy() : null);
				/* 1 */ if (!ignore_calibration && null != cal) v.calibrate(cal);
				/* 2 */ v.resample(delta);
				/* 3 */ if (ignore_orientation) v.relative(); // vectors of consecutive differences

				// test 'vs'
				Editions ed = new Editions(vs, v, delta, false);
				double score = ed.getDistance();
				Utils.log2("Score: " + score + "  similarity: " + ed.getSimilarity());

				// test 'vs_reverse'
				Editions ed_rev = new Editions(vs_reverse, v, delta, false);
				double score_rev = ed_rev.getDistance();
				Utils.log2("Score (reversed): " + score_rev + "  similarity: " + ed_rev.getSimilarity());

				// choose smallest distance
				if (ed.getDistance() < ed_rev.getDistance()) {
					match[k] = new Match(p[k], ed, score);
				} else {
					match[k] = new Match(p[k], ed_rev, score_rev);
				}

			} catch (Exception e) {
				IJError.print(e);
			}
		}
				////
				}
			});
		}
		MultiThreading.startAndJoin(threads);

		Arrays.sort(match, new OrderMatch());

		final Vector<Displayable> v_obs = new Vector<Displayable>(match.length);
		final Vector<Editions> v_eds = new Vector<Editions>(match.length);
		final Vector<Double> v_scores = new Vector<Double>(match.length);
		for (int i=0; i<match.length; i++) {
			v_obs.add(match[i].displ);
			v_eds.add(match[i].ed);
			v_scores.add(match[i].score);
		}

		// order by distance and show in a table
		addTab(pipe, v_obs, v_eds, v_scores, null);

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		Bureaucrat burro = new Bureaucrat(worker, pipe.getProject());
		burro.goHaveBreakfast();
		return burro;
	}

	static private class Match {
		Displayable displ;
		Editions ed;
		double score;
		Match(Displayable displ, Editions ed, double score) {
			this.displ = displ;
			this.ed = ed;
			this.score = score;
		}
	}

	static private class OrderMatch implements Comparator {
		public int compare(Object obm1, Object obm2) {
			Match m1 = (Match)obm1;
			Match m2 = (Match)obm2; // I hate java
			// select for largest score
			double val = m1.score - m2.score;
			//double val = m1.ed.getDistance() - m2.ed.getDistance();
			if (val < 0) return -1; // m1 is smaller
			if (val > 0) return 1; // m1 is larger
			if (val < 0) return 1;
			return 0; // equal
		}
	}

	static private final void addTab(final Displayable displ, final Vector<Displayable> v_obs, final Vector<Editions> v_eds, final Vector<Double> v_scores, final Visualizer vis) {
		makeGUI();
		ComparatorTableModel model = new ComparatorTableModel(v_obs, v_eds, v_scores, vis);
		JTable table = new JTable(model);
		table.addMouseListener(new ComparatorTableListener());
		table.addKeyListener(kl);
		JScrollPane jsp = new JScrollPane(table);
		ht_tabs.put(jsp, displ); // before adding the tab, so that the listener can find it
		tabs.addTab(displ.getProject().findProjectThing(displ).getTitle(), jsp);
		tabs.setSelectedComponent(jsp);
		frame.pack();
	}

	static private void tryCloseTab(KeyEvent ke) {
		switch (ke.getKeyCode()) {
			case KeyEvent.VK_W:
				if (!ke.isControlDown()) return;
				int ntabs = tabs.getTabCount();
				if (0 == ntabs) {
					destroy();
					return;
				}
				int sel = tabs.getSelectedIndex();
				ht_tabs.remove(tabs.getComponentAt(sel));
				tabs.remove(sel);
				return;
			default:
				return;
		}
	}

	static private final void makeGUI() {
		if (null == frame) {
			frame = new JFrame("Comparator");
			frame.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					destroy();
				}
			});
			if (null == ht_tabs) ht_tabs = new Hashtable<JScrollPane,Displayable>();
			tabs = new JTabbedPane();
			tabs.setPreferredSize(new Dimension(350,250));
			// a listener to change the label text when the tab is selected
			ChangeListener tabs_listener =  new ChangeListener() {
				public void stateChanged(ChangeEvent ce) {
					if (null == frame || null == ht_tabs || null == tabs || null == label) return; // the event fires during instantiation ... pffff!
					Object ob = tabs.getSelectedComponent();
					if (null == ob) return;
					Displayable displ = ht_tabs.get(ob);
					if (null == displ) return;
					label.setText(displ.getProject().toString() + ": " + displ.getProject().getMeaningfulTitle(displ) + " [" + Project.getName(displ.getClass()) + "]");
				}
			};
			kl = new KeyAdapter() {
				public void keyPressed(KeyEvent ke) {
					tryCloseTab(ke);
				}
			};
			tabs.addKeyListener(kl);
			JPanel all = new JPanel();
			label = new JLabel("None compared.");
			label.addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					if (2 != me.getClickCount()) return;
					Displayable displ = ht_tabs.get(tabs.getSelectedComponent());
					Display.showCentered(displ.getLayer(), displ, true, me.isShiftDown());
				}
			});
			BoxLayout bl = new BoxLayout(all, BoxLayout.Y_AXIS);
			JPanel plabel = new JPanel();
			plabel.setBorder(new LineBorder(Color.black, 1, true));
			plabel.setMinimumSize(new Dimension(400, 50));
			plabel.setMaximumSize(new Dimension(1000, 50));
			plabel.add(label);
			all.setLayout(bl);
			all.add(plabel);
			all.add(tabs);
			frame.getContentPane().add(all);
			frame.pack();
			tabs.addChangeListener(tabs_listener); // to avoid firing it during instantiation
		}
		ij.gui.GUI.center(frame);
		frame.setVisible(true);
		frame.toFront();
	}

	static private class ComparatorTableModel extends AbstractTableModel {
		private Vector<Displayable> v_obs;
		/** Keeps pointers to the VectorString3D instances and the Editions itself for further (future) processing, such as creating averaged paths. */
		private Vector<Editions> v_eds;
		private Vector<Double> v_scores;
		private Visualizer vis;
		ComparatorTableModel(Vector<Displayable> v_obs, Vector<Editions> v_eds, Vector<Double> v_scores, Visualizer vis) {
			super();
			this.v_obs = v_obs;
			this.v_eds = v_eds;
			this.v_scores = v_scores;
			this.vis = vis;
		}
		public String getColumnName(int col) {
			switch (col) {
				case 0: return "Project";
				case 1: return "Match";
				case 2: return "Similarity";
				case 3: return "Distance";
				default: return "";
			}
		}
		public int getRowCount() { return v_obs.size(); }
		public int getColumnCount() { return 4; }
		public Object getValueAt(int row, int col) {
			switch (col) {
				case 0:
					return v_obs.get(row).getProject().toString();
				case 1:
					Displayable d = v_obs.get(row);
					//return d.getProject().findProjectThing(d).getTitle();
					return d.getProject().getMeaningfulTitle(d);
				case 2: return Utils.cutNumber(Math.floor(v_eds.get(row).getSimilarity() * 10000) / 100, 2) + " %";
				case 3: return Utils.cutNumber(v_scores.get(row).doubleValue(), 2);
				default: return "";
			}
		}
		public Displayable getDisplayableAt(int row) {
			return v_obs.get(row);
		}
		public Editions getEditionsAt(int row) {
			return v_eds.get(row);
		}
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		public void setValueAt(Object value, int row, int col) {
			// nothing
			//fireTableCellUpdated(row, col);
		}
		public boolean remove(Displayable displ) {
			int i = v_obs.indexOf(displ);
			if (-1 != i) {
				v_obs.remove(i);
				v_eds.remove(i);
				v_scores.remove(i);
				return true;
			}
			return false;
		}
		public boolean contains(Object ob) {
			return v_obs.contains(ob);
		}
		public void visualize(int row) {
			if (null == this.vis) return;
			vis.show((Pipe)v_obs.get(row));
		}
	}

	static private class ComparatorTableListener extends MouseAdapter {
		public void mousePressed(MouseEvent me) {
			final Object source = me.getSource();
			final JTable table = (JTable)source;
			final ComparatorTableModel model = (ComparatorTableModel)table.getModel();
			if (2 == me.getClickCount()) {
				int row = table.rowAtPoint(me.getPoint());
				Object ob = model.getDisplayableAt(row);
				Displayable displ = null;
				if (ob instanceof Displayable) {
					displ = (Displayable)ob;
				} else {
					Utils.log2("Comparator: unhandable table selection: " + ob);
					return;
				}
				if (me.isShiftDown()) {
					model.visualize(row);
				} else {
					Display.showCentered(displ.getLayer(), displ, true, me.isShiftDown());
				}
				return;
			}
			if (Utils.isPopupTrigger(me)) {
				if (true) {
					Utils.log2("WAIT a couple of days.");
					return;
				}

				final int[] sel = table.getSelectedRows();
				JPopupMenu popup = new JPopupMenu();
				final String interp3D = "Show interpolated in 3D";
				ActionListener listener = new ActionListener() {
					public void actionPerformed(ActionEvent ae) {
						final String command = ae.getActionCommand();
						if (command.equals(interp3D)) {
							// for now, use the first selected only
							try {
								Editions ed = model.getEditionsAt(sel[0]);
								VectorString3D vs = VectorString3D.createInterpolated(ed, 0.5);
								Pipe master = (Pipe)ht_tabs.get((JScrollPane)tabs.getSelectedComponent());
								Pipe match = (Pipe)model.getDisplayableAt(sel[0]);

								Display3D.addMesh(master.getLayerSet(), vs, "Interpolated #" + master.getId() + " + #" + match.getId(), master.getColor());

							} catch (Exception e) {
								IJError.print(e);
							}
						}
					}
				};
				JMenuItem item = new JMenuItem(interp3D); popup.add(item); item.addActionListener(listener);
				if (0 == sel.length) item.setEnabled(false);
				popup.show(table, me.getX(), me.getY());
			}
		}
	}

	static public void remove(Displayable displ) {
		if (null == frame) return;
		for (int i=tabs.getTabCount()-1; i>-1; i--) {
			Container jsp = (Container)tabs.getComponentAt(0);
			JTable table = (JTable)jsp.getComponent(i);
			ComparatorTableModel model = (ComparatorTableModel)table.getModel();
			model.remove(displ);
		}
	}

	static public void destroy() {
		if (null != frame) {
			frame.setVisible(false);
			frame.dispose();
			ht_tabs.clear();
			label = null;
			tabs = null;
			frame = null;
			ht_tabs = null;
			kl = null;
		}
		frame = null;
	}
}
