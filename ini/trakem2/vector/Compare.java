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
import java.awt.Dimension;
import java.awt.event.*;
import java.awt.Container;
import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.event.*;
import javax.swing.table.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

public class Compare {

	static private JLabel label = null;
	static private JTabbedPane tabs = null;
	static private JFrame frame = null;
	static private Hashtable<JScrollPane,Displayable> ht_tabs = new Hashtable<JScrollPane,Displayable>();

	static public final Bureaucrat findSimilar(final Pipe pipe) {
		Set projects = ControlWindow.getProjects();
		Project[] all = new Project[projects.size()];
		all = (Project[])projects.toArray(all); // does not use 'all', only to read class
		Project[] ref = null;
		GenericDialog gd = new GenericDialog("Choose");
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
		gd.addCheckbox("Ignore calibration", false);

		gd.showDialog();
		if (gd.wasCanceled()) return null;

		boolean ignore_orientation = gd.getNextBoolean();
		boolean ignore_calibration = gd.getNextBoolean();

		if (all.length > 1) {
			int choice = gd.getNextChoiceIndex();
			if (0 == choice) {
				ref = all;
			} else {
				ref = new Project[1];
				ref[0] = all[choice-1];
			}
		}
		return findSimilar(pipe, ref, ignore_orientation, ignore_calibration);
	}

	/** Compare the given pipe with other pipes in the given standard project. WARNING: the calibrations will work ONLY IF all pipes found to compare with come from LayerSets which have the same units of measurement! For example, all in microns. */
	static public final Bureaucrat findSimilar(final Pipe pipe, final Project[] ref, final boolean ignore_orientation, final boolean ignore_calibration) {
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
			return;
		}

		Calibration cal = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibrationCopy() : null);

		final VectorString3D vs = pipe.asVectorString3D();
		/* 1 */ if (!ignore_calibration && null != cal) vs.calibrate(cal);
		/* 2 */ final double delta = vs.getAverageDelta(); // after calibration!
		/* 3 */ vs.resample(delta);
		/* 4 */ if (ignore_orientation) vs.relative();

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
				if (ed.distance() < ed_rev.distance()) {
					match[k] = new Match(p[k], ed, score);
				} else {
					match[k] = new Match(p[k], ed_rev, score_rev);
				}

			} catch (Exception e) {
				new IJError(e);
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
		addTab(pipe, v_obs, v_eds, v_scores);

				} catch (Exception e) {
					new IJError(e);
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

	static private final void addTab(final Displayable displ, final Vector<Displayable> v_obs, final Vector<Editions> v_eds, final Vector<Double> v_scores) {
		makeGUI();
		ComparatorTableModel model = new ComparatorTableModel(v_obs, v_eds, v_scores);
		JTable table = new JTable(model);
		table.addMouseListener(new ComparatorTableListener());
		JScrollPane jsp = new JScrollPane(table);
		ht_tabs.put(jsp, displ); // before adding the tab, so that the listener can find it
		tabs.addTab(displ.getProject().findProjectThing(displ).getTitle(), jsp);
		tabs.setSelectedComponent(jsp);
		frame.pack();
	}

	static private final void makeGUI() {
		if (null == frame) {
			frame = new JFrame("Comparator");
			frame.addWindowListener(new WindowAdapter() {
				public void windowClosing() {
					ht_tabs.clear();
					label = null;
					tabs = null;
					frame = null;
					ht_tabs = null;
				}
			});
			tabs = new JTabbedPane();
			tabs.setPreferredSize(new Dimension(350,250));
			ChangeListener tabs_listener =  new ChangeListener() {
				public void stateChanged(ChangeEvent ce) {
					if (null == frame || null == ht_tabs || null == tabs || null == label) return; // the event fires during instantiation ... pffff!
					Object ob = tabs.getSelectedComponent();
					if (null == ob) return;
					Displayable displ = ht_tabs.get(ob);
					if (null == displ) return;
					label.setText(displ.getProject().toString() + ": " + displ.getProject().findProjectThing(displ).getTitle() + " " + Project.getName(displ.getClass()));
				}
			};
			JPanel all = new JPanel();
			label = new JLabel("None compared.");
			label.addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					if (2 != me.getClickCount()) return;
					Displayable displ = ht_tabs.get(tabs.getSelectedComponent());
					Display.setFront(displ.getLayer(), displ);
				}
			});
			BoxLayout bl = new BoxLayout(all, BoxLayout.Y_AXIS);
			JPanel plabel = new JPanel();
			plabel.setBorder(new LineBorder(Color.black, 1, true));
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
		ComparatorTableModel(Vector<Displayable> v_obs, Vector<Editions> v_eds, Vector<Double> v_scores) {
			super();
			this.v_obs = v_obs;
			this.v_eds = v_eds;
			this.v_scores = v_scores;
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
					return d.getProject().findProjectThing(d).getTitle();
				case 2: return Utils.cutNumber(Math.floor(v_eds.get(row).getSimilarity() * 10000) / 100, 2) + " %";
				case 3: return Utils.cutNumber(v_scores.get(row).doubleValue(), 2);
				default: return "";
			}
		}
		public Displayable getDisplayableAt(int row) {
			return (Displayable)v_obs.get(row);
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
	}

	static private class ComparatorTableListener extends MouseAdapter {
		public void mousePressed(MouseEvent me) {
			if (2 != me.getClickCount()) return;
			Object source = me.getSource();
			JTable table = (JTable)source;
			Object ob = ((ComparatorTableModel)table.getModel()).getDisplayableAt(table.rowAtPoint(me.getPoint()));
			if (ob instanceof Displayable) {
				Displayable displ = (Displayable)ob;
				Display.setFront(displ.getLayer(), displ);
			} else {
				Utils.log2("Comparator: unhandable table selection: " + ob);
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
		}
		frame = null;
	}
}
