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
import ini.trakem2.tree.ProjectThing;
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
import java.awt.Rectangle;

import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;
import javax.media.j3d.Transform3D;

public class Compare {

	static public final int TRANS_ROT = 0;
	static public final int TRANS_ROT_SCALE = 1;
	static public final int TRANS_ROT_SCALE_SHEAR = 2;

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
		/* 1 */ gd.addChoice("X_source: ", pipe_names, pipe_names[s[0]]);
		/* 2 */ gd.addChoice("Y_source: ", pipe_names, pipe_names[s[1]]);
		/* 3 */ gd.addChoice("Z_source: ", pipe_names, pipe_names[s[2]]);

		gd.addMessage("Reference project:");
		String[] options = new String[all.length];
		for (int i=0; i<all.length; i++) {
			options[i] = all[i].toString();
		}
		/* 4 */ gd.addChoice("Project: ", options, options[iother]);
		/* 5 */ gd.addChoice("X_ref: ", pipe_names_ref, pipe_names_ref[t[0]]);
		/* 6 */ gd.addChoice("Y_ref: ", pipe_names_ref, pipe_names_ref[t[1]]);
		/* 7 */ gd.addChoice("Z_ref: ", pipe_names_ref, pipe_names_ref[t[2]]);

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

		gd.addCheckbox("skip insertion/deletion strings at ends when scoring", true);
		gd.addNumericField("maximum_ignorable consecutive muts in endings: ", 5, 0);
		gd.addNumericField("minimum_percentage that must remain: ", 0.5, 2);
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(0), new Component[]{(Component)gd.getNumericFields().get(0), (Component)gd.getNumericFields().get(1)}, null);

		final String[] transforms = {"translate and rotate",
			                     "translate, rotate and scale",
					     "translate, rotate, scale and shear"};
		gd.addChoice("Transform_type: ", transforms, transforms[2]);
		gd.addCheckbox("Chain_branches", true);

		//////

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

		boolean skip_ends = gd.getNextBoolean();
		int max_mut = (int)gd.getNextNumber();
		float min_chunk = (float)gd.getNextNumber();
		if (skip_ends) {
			if (max_mut < 0) max_mut = 0;
			if (min_chunk <= 0) skip_ends = false;
			if (min_chunk > 1) min_chunk = 1;
		}

		int transform_type = gd.getNextChoiceIndex();
		boolean chain_branches = gd.getNextBoolean();

		return findSimilarWithAxes(pipe, axes, axes_ref, pipes_ref, skip_ends, max_mut, min_chunk, transform_type, chain_branches);
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
	static private Object[] obtainOrigin(final Pipe[] axes, final int transform_type) {
		// pipe's axes
		VectorString3D[] vs = new VectorString3D[3];
		for (int i=0; i<3; i++) vs[i] = axes[i].asVectorString3D();

		Calibration cal = (null != axes[0].getLayerSet() ? axes[0].getLayerSet().getCalibrationCopy() : null);
		// 1 - calibrate
		if (null != cal) {
			for (int i=0; i<3; i++) vs[i].calibrate(cal);
		}
		// 2 - resample
		double delta = 0;
		for (int i=0; i<3; i++) delta += vs[i].getAverageDelta();
		delta /= 3;
		for (int i=0; i<3; i++) vs[i].resample(delta);

		// return origin vectors for pipe's project
		Vector3d[] o = VectorString3D.createOrigin(vs[0], vs[1], vs[2], transform_type); // requires resampled vs

		return new Object[]{vs, o};
	}

	/** Compare pipe to all pipes in pipes_ref, by first transforming to match both sets of axes. */
	static public final Bureaucrat findSimilarWithAxes(final Pipe pipe, final Pipe[] axes, final Pipe[] axes_ref, final ArrayList<ZDisplayable> pipes_ref, final boolean skip_ends, final int max_mut, final float min_chunk, final int transform_type, final boolean chain_branches) {
		if (axes.length < 3 || axes_ref.length < 3) {
			Utils.log("Need three axes for each.");
			return null;
		}
		Worker worker = new Worker("Comparing pipes...") {
			public void run() {
				startedWorking();
				try {

		// obtain axes origin vectors for pipe's project
		Object[] pack1 = obtainOrigin(axes, transform_type); // calibrates axes

		VectorString3D[] vs_axes = (VectorString3D[])pack1[0];
		Vector3d[] o1 = (Vector3d[])pack1[1];

		// obtain origin vectors for reference project
		Object[] pack2 = obtainOrigin(axes_ref, transform_type); // calibrates axes

		VectorString3D[] vs_axes_ref = (VectorString3D[])pack2[0];
		Vector3d[] o2 = (Vector3d[])pack2[1];


		// fix axes according to the transform type
		VectorString3D.matchOrigins(o1, o2, transform_type);

		// obtain transformation for query axes
		final Calibration cal1 = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibrationCopy() : null);
		final Vector3d trans1 = new Vector3d(-o1[3].x, -o1[3].y, -o1[3].z); 
		final Transform3D rot1 = VectorString3D.createOriginRotationTransform(o1);

		// transform the axes themselves (already calibrated)
		for (int i=0; i<3; i++) {
			vs_axes[i].translate(trans1);
			vs_axes[i].transform(rot1);
		}

		// obtain transformation for the ref axes
		final Vector3d trans2 = new Vector3d(-o2[3].x, -o2[3].y, -o2[3].z);
		final Transform3D rot2 = VectorString3D.createOriginRotationTransform(o2);
		final Calibration cal2 = pipes_ref.get(0).getLayerSet().getCalibrationCopy();

		// transform the reference axes themselves
		for (int i=0; i<3; i++) {
			vs_axes_ref[i].translate(trans2);
			vs_axes_ref[i].transform(rot2);
		}

		// If chain_branches, the query must also be split into as many branches as it generates.
		// Should generate a tab for each potential branch? Or better, a tab but not with a table but with a list of labels, one per potential branch, and underneath as many tables in more tabs?

		// the queries to do, according to how many different chains the query pipe is part of
		final QueryHolder qh = new QueryHolder(cal1, trans1, rot1,
				                       cal2, trans2, rot2);
		ArrayList<Chain> chains_ref;
		if (chain_branches) {
			for (Chain chain : createPipeChains((ProjectThing)pipe.getProject().findProjectThing(pipe).getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain);
			}
			chains_ref = createPipeChains(pipe.getProject().getRootProjectThing(), pipe.getLayerSet());
		} else {
			// no branching
			qh.addQuery(new Chain(pipe));
			chains_ref = new ArrayList<Chain>();
			for (ZDisplayable zd : pipes_ref) {
				chains_ref.add(new Chain((Pipe)zd));
			}
		}

		// set and calibrate them all
		qh.setReferenceChains(chains_ref);
		chains_ref = null;

		// query chains are both calibrated and transformed
		// ref chains are so far NOT calibrated neither transformed

		// each thread handles a ref pipe, which is to be matched against all queries
		final int n_ref_chains = qh.chains_ref.size();

		final Thread[] threads = new Thread[1]; //MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread(new Runnable() {
				public void run() {
				////
		for (int k = ai.getAndIncrement(); k < n_ref_chains; k = ai.getAndIncrement()) {
			try {
				// obtain a calibrated ref chain, to be uniquely processed by this thread
				final Chain ref = qh.chains_ref.get(k);
				// match it against all queries
				for (Chain query : qh.queries) {
					VectorString3D vs1 = query.vs;
					double delta1 = vs1.getDelta();
					VectorString3D vs2 = qh.makeVS(ref, delta1);
					Object[] ob = findBestMatch(vs1, vs2, delta1, skip_ends, max_mut, min_chunk);
					double score = ((Double)ob[1]).doubleValue();
					Editions ed = (Editions)ob[0];
					qh.addMatch(query, ref, ed, score, ed.getPhysicalDistance(skip_ends, max_mut, min_chunk));
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


		// done!
		// Now, sort matches by physical distance.
		qh.sortMatches();

		// add to the GUI

		//TODO

		// order by distance and show in a table
		//addTab(pipe, v_obs, v_eds, v_scores, v_phys_dist, new Visualizer(pipe, vs_pipe, delta1, cal2, trans2, rot2, vs_axes, vs_axes_ref));


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

	/** For a given pipe, create a VectorString3D for each possible path, as determined by the Project Tree structure and the parent/child relationships.
	 *  A pipe is considered a potential branch when it is contained in an abstract child at the same tree level that the pipe itself in the tree. So:
	 *  - lineage
	 *      - branch 1
	 *          -pipe 1
	 *          - branch 2
	 *              - pipe 2
	 *          - branch 3
	 *              - pipe 3
	 *
	 *  Results in an ArrayList with:
	 *    VS 1
	 *    VS 1 + 2
	 *    VS 1 + 3
	 *
	 *  An so on, recursively from the given pipe's parent.
	 */
	static public ArrayList<Chain> createPipeChains(final ProjectThing root_pt, final LayerSet ls) throws Exception {
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		appendAndFork(root_pt, null, null, chains, ls);
		return chains;
	}

	/** Recursive. */
	static private void appendAndFork(final ProjectThing parent, Chain chain, HashSet<ProjectThing> hs_c_done, final ArrayList<Chain> chains, final LayerSet ls) throws Exception {
		final ArrayList children = parent.getChildren();
		if (null == children) return;

		if (null == hs_c_done) hs_c_done = new HashSet<ProjectThing>();

		for (Iterator it = children.iterator(); it.hasNext(); ) {
			ProjectThing child = (ProjectThing)it.next();
			if (hs_c_done.contains(child)) continue;
			hs_c_done.add(child);

			if (child.getType().equals("pipe")) {
				Pipe pipe = (Pipe)child.getObject();
				if (!pipe.getLayerSet().equals(ls)) continue; // not from the same LayerSet, maybe from a nested one.
				if (null == chain) {
					chain = new Chain(pipe);
					chains.add(chain);
				} else {
					chain.append(pipe);
				}
				// find other children in the parent who contain children with child pipes
				boolean first = true;
				final Chain base = chain.duplicate();
				for (Iterator cc = children.iterator(); cc.hasNext(); ) {
					ProjectThing c = (ProjectThing)cc.next();
					if (hs_c_done.contains(c)) continue; // already visited
					// c is at the same tree level as child (which contains a pipe directly)
					ArrayList child_pipes = c.findChildrenOfType("pipe");
					if (child_pipes.size() > 0) {
						Chain ca;
						if (first) {
							// just append
							ca = chain;
							first = false;
						} else {
							// otherwise make a copy to branch out
							ca = base.duplicate(); // can't duplicate from chain itself, because it would have the previous child added to it.
							chains.add(ca);
						}
						appendAndFork(c, ca, hs_c_done, chains, ls);
					}
				}
				// pipe wrapping ProjectThing objects cannot have any children
				continue;
			}

			// if it does not have direct pipe children, cut chain - but keep inspecting
			if (0 == child.findChildrenOfType("pipe").size()) {
				chain = null;
			}

			// inspect others down the unvisited tree nodes
			appendAndFork(child, chain, hs_c_done, chains, ls);
		}
	}

	static public class Chain {
		final ArrayList<Pipe> pipes = new ArrayList<Pipe>();
		VectorString3D vs;
		private Chain() {}
		Chain(Pipe root) {
			this.pipes.add(root);
			this.vs = root.asVectorString3D();
		}
		void append(Pipe p) throws Exception {
			//if (pipes.contains(p)) throw new Exception("Already contains pipe #" + p.getId());
			pipes.add(p);
			vs = vs.chain(p.asVectorString3D());
		}
		Chain duplicate() {
			Chain chain = new Chain();
			chain.pipes.addAll(this.pipes);
			chain.vs = (VectorString3D)this.vs.clone();
			return chain;
		}
		public String toString() {
			/*
			StringBuffer sb = new StringBuffer("root: #");
			sb.append(pipes.get(0).getId()).append("  len: ").append(pipes.size());
			sb.append("\tchain: ");
			for (Pipe p : pipes) sb.append('#').append(p.getId()).append(' ');
			return sb.toString();
			*/
			StringBuffer sb = new StringBuffer("len: ");
			sb.append(pipes.size()).append("   ");
			for (Pipe p : pipes) sb.append('#').append(p.getId()).append(' ');
			return sb.toString();
		}
		public String getTitle() {
			final StringBuffer sb = new StringBuffer(pipes.get(0).getProject().getTitle());
			sb.append(' ');
			for (Pipe p : pipes) sb.append(' ').append('#').append(p.getId());
			return sb.toString();
		}
		public String getShortTitle() {
			final StringBuffer sb = new StringBuffer();
			for (Pipe p : pipes) sb.append('#').append(p.getId()).append(' ');
			sb.setLength(sb.length()-1);
			return sb.toString();
		}
		/** Returns the color of the root pipe. */
		public Color getColor() {
			return pipes.get(0).getColor();
		}
		public Pipe getRoot() {
			return pipes.get(0);
		}
		public void showCentered2D() {
			Rectangle b = null;
			for (Pipe p : pipes) {
				if (null == b) b = p.getBoundingBox();
				else b.add(p.getBoundingBox());
				p.setVisible(true);
			}
			Display.showCentered(pipes.get(0).getFirstLayer(), pipes.get(0), false, false);
			Display.getFront().getCanvas().showCentered(b);
		}
	}


	static private class QueryHolder {

		final ArrayList<Chain> queries = new ArrayList<Chain>();

		Calibration cal1;
		Vector3d trans1;
		Transform3D rot1;

		Calibration cal2;
		Vector3d trans2;
		Transform3D rot2;

		final Hashtable<Chain,ArrayList<ChainMatch>> matches = new Hashtable<Chain,ArrayList<ChainMatch>>();

		// these chains are kept only calibrated, NOT transformed. Because each query will resample it to its own delta, and then transform it.
		final ArrayList<Chain> chains_ref = new ArrayList<Chain>();

		QueryHolder(Calibration cal1, Vector3d trans1, Transform3D rot1, Calibration cal2, Vector3d trans2, Transform3D rot2) {
			this.cal1 = cal1;
			this.trans1 = trans1;
			this.rot1 = rot1;

			this.cal2 = cal2;
			this.trans2 = trans2;
			this.rot2 = rot2;
		}

		/** Will calibrate and transform the chain's VectorString3D. */
		final void addQuery(final Chain chain) {
			final VectorString3D vs = chain.vs;
			// Order is important:
			// 1 - calibrate
			if (null != cal1) vs.calibrate(cal1);
			// 2 - resample
			double delta = vs.getAverageDelta();
			vs.resample(delta);
			// 3 - transform to axes
			vs.translate(trans1);
			vs.transform(rot1);
			// Store all
			queries.add(chain);
		}

		// one thread at a time may access this method
		synchronized final void addMatch(final Chain query, final Chain ref, final Editions ed, final double score, final double phys_dist) {
			final ChainMatch cm = new ChainMatch();
			cm.ref = ref;
			cm.ed = ed;
			cm.score = score;
			cm.phys_dist = phys_dist;
			ArrayList<ChainMatch> al = matches.get(query);
			if (null == al) {
				al = new ArrayList<ChainMatch>();
				matches.put(query, al);
			}
			al.add(cm);
		}

		/** Will calibrate them all to cal2. */
		void setReferenceChains(final ArrayList<Chain> chains_ref) {
			this.chains_ref.addAll(chains_ref);
			if (null == cal2) return;
			for (Chain c : chains_ref) {
				c.vs.calibrate(cal2);
			}
		}

		/** Returns a resampled and transformed copy of the ref VectorString3D. */
		final VectorString3D makeVS(final Chain ref, final double delta) {
			final VectorString3D vs = (VectorString3D)vs.clone();
			vs.resample(delta);
			vs.translate(trans2);
			vs.transform(rot2);
			return vs;
		}

		/** For each list of matches corresponding to each query chain, sort the matches by physical distance. */
		void sortMatches() {
			final ChainMatchComparator comp = new ChainMatchComparator();
			for (ArrayList<ChainMatch> list : matches.values()) {
				Collections.sort(list, comp);
			}
		}
		/** Returns all pipes involved in the query chains. */
		HashSet<Pipe> getAllQueriedPipes() {
			final HashSet<Pipe> hs = new HashSet<Pipe>();
			for (Chain c : queries) {
				hs.addAll(c.pipes);
			}
			return hs;
		}
		Hashtable<Pipe,VectorString3D> getAllQueried() {
			Hashtable<Pipe,VectorString3D> ht = new Hashtable<Pipe,VectorString3D>();
			for (Pipe p : getAllQueriedPipes()) {
				VectorString3D vs = p.asVectorString3D();
				if (null != cal1) vs.calibrate(cal1);
				vs.translate(trans1);
				vs.transform(rot1);
				ht.put(p, vs);
			}
			return ht;
		}
	}

	static private class ChainMatch {
		Chain ref;
		Editions ed;
		double phys_dist;
		double score;
	}

	static private class ChainMatchComparator implements Comparator {
		public int compare(final Object ob1, final Object ob2) {
			ChainMatch cm1 = (ChainMatch)ob1;
			ChainMatch cm2 = (ChainMatch)ob2;
			// select for smallest physical distance of the center of mass
			double val = cm1.phys_dist - cm2.phys_dist;
			if (val < 0) return -1; // m1 is closer
			if (val > 0) return 1; // m1 is further away
			return 0; // same distance
		}
	}

	static private class Visualizer {
		QueryHolder qh;
		LayerSet common; // calibrated to queried pipe space, which is now also the space of all others.
		boolean query_shows = false;
		VectorString3D[] vs_axes, vs_axes_ref;
		final Hashtable<Pipe,VectorString3D> queried;

		Visualizer(QueryHolder qh, VectorString3D[] vs_axes, VectorString3D[] vs_axes_ref) {
			this.qh = qh;
			this.vs_axes = vs_axes;
			this.vs_axes_ref = vs_axes_ref;
			// create common LayerSet space
			Pipe pipe = qh.getAllQueriedPipes().iterator().next();
			LayerSet ls = pipe.getLayerSet();
			Calibration cal1 = ls.getCalibrationCopy();
			this.common = new LayerSet(pipe.getProject(), pipe.getProject().getLoader().getNextId(), "Common", 10, 10, 0, 0, 0, ls.getLayerWidth() * cal1.pixelWidth, ls.getLayerHeight() * cal1.pixelHeight, false, false, new AffineTransform());
			Calibration cal = new Calibration();
			cal.setUnit(cal1.getUnit()); // homogeneous on all axes
			this.common.setCalibration(cal);
		}
		/** TODO: should show in full the two sets of pipes derived from the root pipe of each chain,
		 *  and not just the reference chain. */
		public void show(Chain query, Chain ref) {
			reset();
			if (!query_shows) showAxesAndQueried();
			VectorString3D vs = qh.makeVS(ref, query.vs.getDelta());
			// The LayerSet is that of the Pipe being queried, not the given pipe to show which belongs to the reference project (and thus not to the queried pipe project)
			Display3D.addMesh(common, vs, ref.getTitle(), ref.getColor());
		}
		public void showAxesAndQueried() {
			reset();
			Color qcolor = null;
			for (Iterator it = queried.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				Pipe p = (Pipe)entry.getKey();
				VectorString3D vs = (VectorString3D)entry.getValue();
				if (null == qcolor) qcolor = p.getColor();
				Display3D.addMesh(common, vs, p.getProject().getMeaningfulTitle(p), qcolor);
			}

			Color color = Color.pink.equals(qcolor) ? Color.red : qcolor;

			Display3D.addMesh(common, vs_axes[0], "X query", color);
			Display3D.addMesh(common, vs_axes[1], "Y query", color);
			Display3D.addMesh(common, vs_axes[2], "Z query", color);

			Display3D.addMesh(common, vs_axes_ref[0], "X ref", Color.pink);
			Display3D.addMesh(common, vs_axes_ref[1], "Y ref", Color.pink);
			Display3D.addMesh(common, vs_axes_ref[2], "Z ref", Color.pink);

			query_shows = true;
		}
		public void showInterpolated(Editions ed, Chain query, Chain ref) {
			reset();
			if (!query_shows) showAxesAndQueried();
			VectorString3D vs = VectorString3D.createInterpolatedPoints(ed, 0.5f);
			Display3D.addMesh(common, vs, "Av. " + query.getTitle() + " - " + ref.getTitle(), Utils.mix(query.getColor(), ref.getColor()));
		}
		private void reset() {
			if (null == Display3D.getDisplay(common)) query_shows = false;
		}
	}

	/** Since comparing two sequences starting from one end or starting from the other
	 *  is not the same at all, this method performs the match starting first from one
	 *  end and then from the other.
	 *  Then it performs a match starting from the middle of the longest stretches of
	 *  pure mutations detected in the best of the matches above.
	 *  Also, since strings may be reversed, the test against the reversed one is done as well.
	 *
	 * vs1 			vs2
	 * vs1.reversed()	vs2.reversed()
	 *
	 * vs1 			vs2.reversed()
	 * vs1.reversed()	vs2
	 *
	 * ASSUMES both VectorString3D are open.
	 *
	 * */
	static private final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk) {

		final VectorString3D vs1rev = vs1.makeReversedCopy();
		final VectorString3D vs2rev = vs2.makeReversedCopy();

		Editions[] ed = new Editions[4];

		// vs1 vs2
		ed[0] = new Editions(vs1, vs2, delta, false);
		// vs1rev vs2rev
		ed[1] = new Editions(vs1rev, vs2rev, delta, false);
		// vs1 vs2rev
		ed[2] = new Editions(vs1, vs2rev, delta, false);
		// vs1rev vs2
		ed[3] = new Editions(vs1rev, vs2, delta, false);

		double best_score = 0;
		double best_score1 = 0;

		int best_i = 0;
		for (int i=0; i<ed.length; i++) {
			double score1 = ed[i].getSimilarity2();
			double score = ed[i].getSimilarity2(skip_ends, max_mut, min_chunk);
			if (score > best_score) {
				best_i = i;
				best_score = score;
				best_score1 = score1;
			}
		}
		//Utils.log2("score, score1: " + best_score + ", " + best_score1);

		Editions best_ed = ed[best_i];

		// now test also starting from the middle of the longest mutation chunk of the best matching
		try {
			Editions ed_center = best_ed.recreateFromCenter(max_mut);
			double score_center = ed_center.getSimilarity(skip_ends, max_mut, min_chunk);
			if (score_center > best_score) {
				best_ed = ed_center;
				best_score = score_center;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return new Object[]{best_ed, new Double(best_score)};
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
		addTab(pipe, v_obs, v_eds, v_scores, null, null);

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
		double phys_dist;
		Match(Displayable displ, Editions ed, double score) {
			this.displ = displ;
			this.ed = ed;
			this.score = score;
		}
		Match(Displayable displ, double phys_dist, Editions ed, double score) {
			this(displ, ed, score);
			this.phys_dist = phys_dist;
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
			return 0; // equal
		}
	}

	static private class OrderByDistance implements Comparator {
		public int compare(Object obm1, Object obm2) {
			Match m1 = (Match)obm1;
			Match m2 = (Match)obm2; // I hate java
			// select for smallest physical distance of the center of mass
			double val = m1.phys_dist - m2.phys_dist;
			if (val < 0) return -1; // m1 is closer
			if (val > 0) return 1; // m1 is further away
			return 0; // same distance
		}
	}

	static private final void addTab(final Displayable displ, final Vector<Displayable> v_obs, final Vector<Editions> v_eds, final Vector<Double> v_scores, final Vector<Double> v_phys_dist, final Visualizer vis) {
		makeGUI();
		ComparatorTableModel model = new ComparatorTableModel(v_obs, v_eds, v_scores, v_phys_dist, vis);
		JTable table = new JTable(model);
		table.addMouseListener(new ComparatorTableListener());
		table.addKeyListener(kl);
		/* // WOULD HAVE to create my own sorter: can't sort numerically even (it's bit sort), plus the getValueAt(int row, int col) gets messed up - no proper backend data update. But to create my own sorter, I need a TableSorter class which is new 1.6.0, i..e would have tobe very convolutedly generated.
		if (ij.IJ.isJava16()) {
			try {
				java.lang.reflect.Method msort = JTable.class.getMethod("setAutoCreateRowSorter", new Class[]{Boolean.TYPE});
				msort.invoke(table, new Object[]{Boolean.TRUE});
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		*/
		JScrollPane jsp = new JScrollPane(table);
		ht_tabs.put(jsp, displ); // before adding the tab, so that the listener can find it
		tabs.addTab(displ.getProject().findProjectThing(displ).getTitle(), jsp);
		tabs.setSelectedComponent(jsp);
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
			tabs.addChangeListener(tabs_listener); // to avoid firing it during instantiation, must be added last
			ij.gui.GUI.center(frame);
		}
		frame.setVisible(true);
		frame.toFront();
	}

	static private class ComparatorTableModel extends AbstractTableModel {
		private Vector<Displayable> v_obs;
		/** Keeps pointers to the VectorString3D instances and the Editions itself for further (future) processing, such as creating averaged paths. */
		private Vector<Editions> v_eds;
		private Vector<Double> v_scores;
		private Vector<Double> v_phys_dist;
		private Visualizer vis;
		ComparatorTableModel(Vector<Displayable> v_obs, Vector<Editions> v_eds, Vector<Double> v_scores, Vector<Double> v_phys_dist, Visualizer vis) {
			super();
			this.v_obs = v_obs;
			this.v_eds = v_eds;
			this.v_scores = v_scores;
			this.v_phys_dist = v_phys_dist;
			this.vis = vis;
		}
		public Visualizer getVisualizer() { return vis; }
		public String getColumnName(int col) {
			switch (col) {
				case 0: return "Project";
				case 1: return "Match";
				case 2: return "Similarity";
				case 3: return "Lev Dist";
				case 4: return "Dist (" + vis.cal2.getUnits() + ")";
				default: return "";
			}
		}
		public int getRowCount() { return v_obs.size(); }
		public int getColumnCount() {
			if (null != v_phys_dist) return 5;
			return 4;
		}
		public Object getValueAt(int row, int col) {
			switch (col) {
				case 0:
					return v_obs.get(row).getProject().toString();
				case 1:
					Displayable d = v_obs.get(row);
					//return d.getProject().findProjectThing(d).getTitle();
					return d.getProject().getMeaningfulTitle(d);
				case 2: return Utils.cutNumber(Math.floor(v_scores.get(row).doubleValue() * 10000) / 100, 2) + " %";
				case 3: return Utils.cutNumber(v_eds.get(row).getDistance(), 2);
				case 4: return Utils.cutNumber(v_phys_dist.get(row).doubleValue(), 2);
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
		public void mousePressed(final MouseEvent me) {
			final Object source = me.getSource();
			final JTable table = (JTable)source;
			final ComparatorTableModel model = (ComparatorTableModel)table.getModel();

			final int row = table.rowAtPoint(me.getPoint());
			final Object ob = model.getDisplayableAt(row);
			Displayable displ_ = null;

			if (ob instanceof Displayable) {
				displ_ = (Displayable)ob;
			} else {
				Utils.log2("Comparator: unhandable table selection: " + ob);
				return;
			}
			final Displayable displ = displ_;

			if (2 == me.getClickCount()) {
				if (me.isShiftDown()) {
					model.visualize(row);
				} else {
					Display.showCentered(displ.getLayer(), displ, true, me.isShiftDown());
				}
				return;
			}

			if (Utils.isPopupTrigger(me)) {
				final int[] sel = table.getSelectedRows();
				if (0 == sel.length) return;
				JPopupMenu popup = new JPopupMenu();
				final String show3D = "Show in 3D";
				final String interp3D = "Show interpolated in 3D";
				final String showCentered = "Show centered in 2D";
				final String showAxes = "Show axes and queried";

				ActionListener listener = new ActionListener() {
					public void actionPerformed(ActionEvent ae) {
						final String command = ae.getActionCommand();
						if (command.equals(interp3D)) {
							// for now, use the first selected only
							try {
								Editions ed = model.getEditionsAt(sel[0]);
								Pipe match = (Pipe)model.getDisplayableAt(sel[0]);
								model.getVisualizer().showInterpolated(ed, match);
							} catch (Exception e) {
								IJError.print(e);
							}
							return;
						} else if (command.equals(show3D)) {
							model.visualize(row);
						} else if (command.equals(showCentered)) {
							Display.showCentered(displ.getLayer(), displ, true, me.isShiftDown());
						} else if (command.equals(showAxes)) {
							model.getVisualizer().showAxesAndQueried();
						}
					}
				};
				JMenuItem item;
				item = new JMenuItem(show3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(interp3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showCentered); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showAxes); popup.add(item); item.addActionListener(listener);

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


	static private class QueryHolderTableModel extends AbstractTableModel {
		QueryHolder qh;
		Chain query;
		ArrayList<ChainMatch> cm;
		Visualizer vis;

		QueryHolderTableModel(QueryHolder qh, Visualizer vis, Chain query, ArrayList<ChainMatch> cm) {
			this.qh = qh;
			this.vis = vis;
			this.cm = cm;
			this.query = query;
		}
		public Visualizer getVisualizer() { return vis; }
		public String getColumnName(int col) {
			switch (col) {
				case 0: return "Project";
				case 1: return "Match";
				case 2: return "Similarity";
				case 3: return "Lev Dist";
				case 4: return "Dist (" + qh.cal2.getUnits() + ")";
				default: return "";
			}
		}
		public int getRowCount() { return cm.size(); }
		public int getColumnCount() { return 5; }
		public Object getValueAt(int row, int col) {
			switch (col) {
				case 0: return query.getRoot().getProject();
				case 1: return cm.get(row).ref.getShortTitle();
				case 2: return Utils.cutNumber(Math.floor(cm.get(row).score * 10000) / 100, 2) + " %";
				case 3: return Utils.cutNumber(cm.get(row).ed.getDistance(), 2);
				case 4: return Utils.cutNumber(cm.get(row).phys_dist, 2);
				default: return "";
			}
		}
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		public void setValueAt(Object value, int row, int col) {}
	}

	static private class QueryHolderTableListener extends MouseAdapter {
		public void mousePressed(final MouseEvent me) {
			final Object source = me.getSource();
			final JTable table = (JTable)source;
			final QueryHolderTableModel model = (QueryHolderTableModel)table.getModel();

			final int row = table.rowAtPoint(me.getPoint());
			final Chain ref = model.cm.get(row).ref;

			if (2 == me.getClickCount()) {
				if (me.isShiftDown()) {
					model.vis.show(model.query, ref);
				} else {
					ref.showCentered2D();
				}
				return;
			}

			if (Utils.isPopupTrigger(me)) {
				final int[] sel = table.getSelectedRows();
				if (0 == sel.length) return;
				JPopupMenu popup = new JPopupMenu();
				final String show3D = "Show in 3D";
				final String interp3D = "Show interpolated in 3D";
				final String showCentered = "Show centered in 2D";
				final String showAxes = "Show axes and queried";

				ActionListener listener = new ActionListener() {
					public void actionPerformed(ActionEvent ae) {
						final String command = ae.getActionCommand();
						if (command.equals(interp3D)) {
							// for now, use the first selected only
							try {
								model.vis.showInterpolated(model.cm.get(sel[0]).ed, model.query, ref);
							} catch (Exception e) {
								IJError.print(e);
							}
							return;
						} else if (command.equals(show3D)) {
							model.vis.show(model.query, ref);
						} else if (command.equals(showCentered)) {
							ref.showCentered2D();
						} else if (command.equals(showAxes)) {
							model.vis.showAxesAndQueried();
						}
					}
				};
				JMenuItem item;
				item = new JMenuItem(show3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(interp3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showCentered); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showAxes); popup.add(item); item.addActionListener(listener);

				popup.show(table, me.getX(), me.getY());
			}
		}
	}
}
