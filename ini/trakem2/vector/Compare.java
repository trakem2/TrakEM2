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

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ij.measure.Calibration;
import ij.io.SaveDialog;
import ij.io.OpenDialog;

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

import java.io.*;


public class Compare {

	static public final int TRANS_ROT = 0;
	static public final int TRANS_ROT_SCALE = 1;
	static public final int TRANS_ROT_SCALE_SHEAR = 2;

	static private JLabel label = null;
	static private JTabbedPane tabs = null;
	static private JFrame frame = null;
	static private Hashtable<JScrollPane,Chain> ht_tabs = null;
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
		gd.addCheckbox("Chain_branches", true);

		gd.showDialog();
		if (gd.wasCanceled()) return null;

		boolean ignore_orientation = gd.getNextBoolean();
		boolean ignore_calibration = gd.getNextBoolean();
		boolean mirror = gd.getNextBoolean();
		boolean chain_branches = gd.getNextBoolean();

		if (all.length > 1) {
			int choice = gd.getNextChoiceIndex();
			if (0 == choice) {
				ref = all;
			} else {
				ref = new Project[1];
				ref[0] = all[choice-1];
			}
		}

		// check that the calibration units are the same
		if (!ignore_calibration) {
			String unit1 = pipe.getLayerSet().getCalibration().getUnit();
			for (int i=0; i<ref.length; i++) {
				if (!matchUnits(unit1, ref[i].getRootLayerSet().getCalibration().getUnit(), ref[i].getTitle())) {
					return null;
				}
			}
		}

		return findSimilar(pipe, ref, ignore_orientation, ignore_calibration, mirror, chain_branches, true);
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
		gd.addMessage(pipe.getProject().getShortMeaningfulTitle(pipe));

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
		// check if none found
		for (int i=0; i<3; i++) {
			if (-1 == s[i]) s[i] = 0;
			if (-1 == t[i]) t[i] = 0;
		}

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
			// TODO something is wrong here when there are more than 2 projects involved.
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
				for (int i=0; i<3; i++) {
					if (-1 == s[i]) s[i] = 0;
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

		gd.addCheckbox("skip insertion/deletion strings at ends when scoring", false);
		gd.addNumericField("maximum_ignorable consecutive muts in endings: ", 5, 0);
		//gd.addNumericField("minimum_percentage that must remain: ", 1.0, 2);
		gd.addSlider("minimum_percentage that must remain: ", 1, 100, 100);
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(0), new Component[]{(Component)gd.getNumericFields().get(0), (Component)gd.getNumericFields().get(1)}, null);

		final String[] transforms = {"translate and rotate",
			                     "translate, rotate and scale",
					     "translate, rotate, scale and shear"};
		gd.addChoice("Transform_type: ", transforms, transforms[2]);
		gd.addCheckbox("Chain_branches", true);
		gd.addCheckbox("Score mutations only", false);
		gd.addCheckbox("Substring matching", false);
		gd.addCheckbox("Direct (no reverse matches)", true);
		gd.addNumericField("Point interdistance (calibrated; zero means auto): ", 0, 2);

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
		float min_chunk = (float)gd.getNextNumber() / 100;
		if (skip_ends) {
			if (max_mut < 0) max_mut = 0;
			if (min_chunk <= 0) skip_ends = false;
			if (min_chunk > 1) min_chunk = 1;
		}

		int transform_type = gd.getNextChoiceIndex();
		boolean chain_branches = gd.getNextBoolean();
		boolean score_mut = gd.getNextBoolean();
		boolean substring_matching = gd.getNextBoolean();
		boolean direct = gd.getNextBoolean();
		double delta = gd.getNextNumber();
		if (Double.isNaN(delta) || delta < 0) {
			Utils.log("Nonsense delta: " + delta);
			return null;
		}

		// check that the Calibration units are the same
		if (!matchUnits(pipe.getLayerSet().getCalibration().getUnit(), all[iproject].getRootLayerSet().getCalibration().getUnit(), all[iproject].getTitle())) {
			return null;
		}

		return findSimilarWithAxes(pipe, axes, axes_ref, pipes_ref, skip_ends, max_mut, min_chunk, transform_type, chain_branches, true, score_mut, substring_matching, direct, delta);
	}

	static private int[] findXYZAxes(final String[] presets, final ArrayList<ZDisplayable> pipes, final String[] pipe_names) {
		int[] s = new int[]{-1, -1, -1};
		int next = 0;
		for (ZDisplayable zd : pipes) {
			pipe_names[next] = zd.getProject().getShortMeaningfulTitle(zd);
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
	static public Object[] obtainOrigin(final Pipe[] axes, final int transform_type) {
		// pipe's axes
		final VectorString3D[] vs = new VectorString3D[3];
		for (int i=0; i<3; i++) vs[i] = axes[i].asVectorString3D();

		final Calibration cal = (null != axes[0].getLayerSet() ? axes[0].getLayerSet().getCalibration() : null);
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
		final Vector3d[] o = VectorString3D.createOrigin(vs[0], vs[1], vs[2], transform_type); // requires resampled vs

		return new Object[]{vs, o};
	}

	/** Compare pipe to all pipes in pipes_ref, by first transforming to match both sets of axes. */
	static public final Bureaucrat findSimilarWithAxes(final Pipe pipe, final Pipe[] axes, final Pipe[] axes_ref, final ArrayList<ZDisplayable> pipes_ref, final boolean skip_ends, final int max_mut, final float min_chunk, final int transform_type, final boolean chain_branches, final boolean show_gui, final boolean score_mut, final boolean substring_matching, final boolean direct, final double delta) {
		Worker worker = new Worker("Comparing pipes...") {
			public void run() {
				startedWorking();
				try {

		if (axes.length < 3 || axes_ref.length < 3) {
			Utils.log("Need three axes for each.");
			quit();
			return;
		}
		if (pipe.length() < 1) {
			Utils.log("Query pipe has less than 2 points");
			quit();
			return;
		}

		// obtain axes origin vectors for pipe's project
		Object[] pack1 = obtainOrigin(axes, transform_type); // calibrates axes

		VectorString3D[] vs_axes = (VectorString3D[])pack1[0];
		Vector3d[] o1 = (Vector3d[])pack1[1];

		// obtain origin vectors for reference project
		Object[] pack2 = obtainOrigin(axes_ref, transform_type); // calibrates axes

		VectorString3D[] vs_axes_ref = (VectorString3D[])pack2[0];
		Vector3d[] o2 = (Vector3d[])pack2[1];


		// fix axes according to the transform type
		final double scaling_factor = VectorString3D.matchOrigins(o1, o2, transform_type);
		Utils.log2("matchOrigins scaling factor: " + scaling_factor + " for transform_type " + transform_type);

		// obtain transformation for query axes
		final Calibration cal1 = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibration() : null);
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
		final Calibration cal2 = pipes_ref.get(0).getLayerSet().getCalibration();

		// transform the reference axes themselves
		for (int i=0; i<3; i++) {
			// Axes are already calibrated
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
			// create all chained ref branches
			chains_ref = createPipeChains(pipes_ref.get(0).getProject().getRootProjectThing(), pipes_ref.get(0).getLayerSet()); // TODO WARNING: if the parent has more than one pipe, this will query them all!

			// add all possible query chains, starting at the parent of the chosen pipe
			for (Chain chain : createPipeChains((ProjectThing)pipe.getProject().findProjectThing(pipe).getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain);
			}
		} else {
			// no branching: single query of one single-pipe chain
			qh.addQuery(new Chain(pipe));
			// just add a single-pipe chain for each ref pipe
			chains_ref = new ArrayList<Chain>();
			for (ZDisplayable zd : pipes_ref) {
				chains_ref.add(new Chain((Pipe)zd));
			}
		}

		// set and calibrate them all
		qh.setReferenceChains(chains_ref);
		chains_ref = null;

		// each thread handles a ref pipe, which is to be matched against all queries
		final int n_ref_chains = qh.chains_ref.size();
		final QueryMatchList[] qm = new QueryMatchList[qh.queries.size()];
		int ne = 0;
		for (Chain query : qh.queries) qm[ne++] = new QueryMatchList(query, n_ref_chains);

		final Thread[] threads = new Thread[1]; //MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread(new Runnable() {
				final public void run() {
				////
		for (int k = ai.getAndIncrement(); k < n_ref_chains; k = ai.getAndIncrement()) {
			try {
				// obtain a calibrated ref chain, to be uniquely processed by this thread
				final Chain ref = qh.chains_ref.get(k);
				// match it against all queries
				int next = 0;
				for (Chain query : qh.queries) {
					final VectorString3D vs1 = query.vs;
					final double delta1 = 0 == delta ? vs1.getDelta() : delta; // WARNING unchecked delta value
					final VectorString3D vs2 = qh.makeVS2(ref, delta1);
					final Object[] ob = findBestMatch(vs1, vs2, delta1, skip_ends, max_mut, min_chunk, 1, direct, substring_matching);
					final Editions ed = (Editions)ob[0];
					//qh.addMatch(query, ref, ed, seq_sim, ed.getPhysicalDistance(skip_ends, max_mut, min_chunk));

					final float prop_len = substring_matching ?
								  1.0f
								: ((float)vs1.length()) / vs2.length();

					final double[] stats = ed.getStatistics(skip_ends, max_mut, min_chunk, score_mut);
					qm[next++].cm[k] = new ChainMatch(query, ref, ed, stats, prop_len, score(ed.getSimilarity(), ed.getDistance(), ed.getStatistics(skip_ends, max_mut, min_chunk, false)[3], Compare.W));
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
		// put result into the Worker
		this.result = qm;

		if (show_gui) {
			// add to the GUI (will sort them by phys_dist)
			qh.addMatches(qm);
			qh.sortMatches(new ChainMatchComparator());
			qh.createGUI(vs_axes, vs_axes_ref);
		}


				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		Project other = pipes_ref.get(0).getProject();
		Project[] p;
		if (other.equals(pipe.getProject())) p = new Project[]{other};
		else p = new Project[]{other, pipe.getProject()};
		Bureaucrat burro = new Bureaucrat(worker, p);
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
				if (!pipe.getLayerSet().equals(ls) || pipe.length() < 2) continue; // not from the same LayerSet, maybe from a nested one.
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

	/** Represents a list of concatenated pipes, where each pipe is parent of the next within the ProjectTree. */
	static public class Chain {
		final ArrayList<Pipe> pipes = new ArrayList<Pipe>();
		public VectorString3D vs; // the complete path of chained pipes
		private Chain() {}
		public Chain(Pipe root) {
			this.pipes.add(root);
			this.vs = root.asVectorString3D();
		}
		final public void append(Pipe p) throws Exception {
			//if (pipes.contains(p)) throw new Exception("Already contains pipe #" + p.getId());
			pipes.add(p);
			vs = vs.chain(p.asVectorString3D());
		}
		public final Chain duplicate() {
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
		final public String getTitle() {
			final StringBuffer sb = new StringBuffer(pipes.get(0).getProject().getTitle());
			sb.append(' ');
			for (Pipe p : pipes) sb.append(' ').append('#').append(p.getId());
			return sb.toString();
		}
		final public String getCellTitle() {
			Pipe root = pipes.get(0);
			String mt = root.getProject().getShortMeaningfulTitle(root);
			if (1 == pipes.size()) return mt;
			//else, chain the ids of the rest
			final StringBuffer sb = new StringBuffer(mt);
			for (int i=1; i<pipes.size(); i++) sb.append(' ').append('#').append(pipes.get(i).getId());
			return sb.toString();
		}
		/** Returns max 10 chars, solely the name of the parent's parent node of the root pipe (aka the [lineage] containing the [branch]) or the id if too long. Intended for the 10-digit limitation in the problem in .dis files for Phylip. */
		final public String getShortCellTitle() {
			Pipe root = pipes.get(0);
			ProjectThing pt = root.getProject().findProjectThing(root);
			String short_title = null;
			// investigate the [branch] title
			pt = (ProjectThing)pt.getParent(); // the [branch]
			String title = pt.getTitle();
			if (!title.equals(pt.getType())) short_title = title; // the [branch] was named
			// investigate the lineage title
			if (null == short_title) {
				pt = (ProjectThing)pt.getParent(); // the [lineage]
				title = pt.getTitle();
				if (!title.equals(pt.getType())) short_title = title; // the [lineage] was named
			}
			// check length
			if (null != short_title && short_title.length() > 10) {
				short_title = null; // too long!
			}
			// else fall back to unique id
			if (null == short_title) {
				short_title = Long.toString(root.getId());
				if (short_title.length() <= 8) short_title = "id" + short_title;
			}
			while (short_title.length() > 10) {
				short_title = short_title.substring(1);
			}
			return short_title;
		}
		/** Returns the color of the root pipe. */
		final public Color getColor() {
			return pipes.get(0).getColor();
		}
		final public Pipe getRoot() {
			return pipes.get(0);
		}
		/** Show centered, set visible and select. */
		final public void showCentered2D(boolean shift_down) {
			Rectangle b = null;
			Display display = Display.getFront();
			for (Pipe p : pipes) {
				if (null == b) b = p.getBoundingBox();
				else b.add(p.getBoundingBox());
				p.setVisible(true);
				display.select(p, shift_down);
			}
			display.select(pipes.get(0), shift_down); // the root as active
			display.getCanvas().showCentered(b);
		}
	}


	/** Contains all que query chains created from the single pipe selected for matching against another reference project, their matches with the chains made from the reference project, and some general data such as the transforms and the axes. */
	static private class QueryHolder {

		final ArrayList<Chain> queries = new ArrayList<Chain>();

		Calibration cal1;
		Vector3d trans1;
		Transform3D rot1;

		Calibration cal2;
		Vector3d trans2;
		Transform3D rot2;

		final Hashtable<Chain,ArrayList<ChainMatch>> matches = new Hashtable<Chain,ArrayList<ChainMatch>>();

		VectorString3D[] vs_axes = null,
			         vs_axes_ref = null;

		boolean relative = false;

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
			if (null != trans1) vs.translate(trans1);
			if (null != rot1) vs.transform(rot1); // contains scale and shear as well, but not translation
			// Store all
			queries.add(chain);
		}

		final void addMatch(final ChainMatch cm) {
			ArrayList<ChainMatch> al = matches.get(cm.query);
			if (null == al) {
				al = new ArrayList<ChainMatch>();
				matches.put(cm.query, al);
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
		/*
		final VectorString3D makeVS(final Chain ref, final double delta) {
			final VectorString3D vs = (VectorString3D)ref.vs.clone();
			vs.resample(delta);
			if (null != trans2) vs.translate(trans2);
			if (null != rot2) vs.transform(rot2);
			return vs;
		}
		*/

		/* WARNING should be the same as above, but it's not. The only difference is the check for calibraiton, which looks correct (the vs is already calibrated).
		 * */
		final VectorString3D makeVS2(final Chain ref, final double delta) {
			return bringTo((VectorString3D)ref.vs.clone(), delta, cal2, trans2, rot2);
		}

		final VectorString3D makeVS1(final Chain q, final double delta) {
			return bringTo((VectorString3D)q.vs.clone(), delta, cal1, trans1, rot1);
		}

		/** Returns a resampled and transformed copy of the pipe's VectorString3D. */
		final VectorString3D makeVS2(final Pipe ref, final double delta) {
			return bringTo(ref.asVectorString3D(), delta, cal2, trans2, rot2);
		}

		/** Returns a resampled and transformed copy of the pipe's VectorString3D. */
		final VectorString3D makeVS1(final Pipe q, final double delta) {
			return bringTo(q.asVectorString3D(), delta, cal1, trans1, rot1);
		}

		final VectorString3D bringTo(final VectorString3D vs, final double delta, final Calibration cal, final Vector3d trans, final Transform3D rot) {
			if (null != cal && !vs.isCalibrated()) vs.calibrate(cal);
			vs.resample(delta);
			if (null != trans) vs.translate(trans);
			if (null != rot) vs.transform(rot);
			return vs;
		}

		/** For each list of matches corresponding to each query chain, sort the matches by physical distance. */
		void sortMatches(Comparator comp) {
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
				if (null != trans1) vs.translate(trans1);
				if (null != rot1) vs.transform(rot1);
				ht.put(p, vs);
			}
			return ht;
		}
		void addMatches(final QueryMatchList[] qm) {
			// add all matches
			for (int i=0; i<qm.length; i++) {
				for (int k=0; k<qm[i].cm.length; k++) {
					addMatch(qm[i].cm[k]);
				}
			}
		}
		// One table entry per query chain
		void createGUI(final VectorString3D[] vs_axes, final VectorString3D[] vs_axes_ref) {
			makeGUI();
			this.vs_axes = vs_axes;
			this.vs_axes_ref = vs_axes_ref;
			for (Chain query : queries) {
				QueryHolderTableModel qt = new QueryHolderTableModel(this, new Visualizer(this, vs_axes, vs_axes_ref), query, matches.get(query));
				JTable table = new JTable(qt);
				table.addMouseListener(new QueryHolderTableListener());
				table.addKeyListener(kl);
				JScrollPane jsp = new JScrollPane(table);
				ht_tabs.put(jsp, query);
				tabs.addTab(query.getCellTitle(), jsp);
				tabs.setSelectedComponent(jsp); // sets the label
			}
		}

		void remove(Displayable d) {
			// from the queries (and thus the tabs as well)
			for (Iterator<Chain> i = queries.iterator(); i.hasNext(); ) {
				Chain chain = i.next();
				if (chain.equals(d)) {
					Component comp = findTab(chain);
					if (null != comp) {
						ht_tabs.remove(comp);
						tabs.remove(comp);
					}
					i.remove();
					matches.remove(chain);
				}
			}
			// from the matches list of each query chain
			for (ArrayList<ChainMatch> acm : matches.values()) {
				for (Iterator<ChainMatch> i = acm.iterator(); i.hasNext(); ) {
					if (i.next().ref.pipes.contains(equals(d))) {
						i.remove();
					}
				}
			}
		}
	}

	static private Component findTab(Chain chain) {
		for (Iterator it = ht_tabs.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			if (entry.getValue().equals(chain)) return (Component)entry.getKey();
		}
		return null;
	}

	/** Represents the scored match between any two Chain objects. */
	static private class ChainMatch {
		Chain query;
		Chain ref;
		Editions ed;
		double score; // combined score, made from several of the parameters below (S, L and M as of 20080823)
		double seq_sim; // similarity measure made of num 1 - ((num insertions + num deletions) / max (len1, len2)).
		double phys_dist; // average distance between mutation pair interdistances
		double cum_phys_dist; // cummulative physical distance
		double stdDev; // between mutation pairs
		double median; // of matched mutation pair interdistances
		double prop_mut; // the proportion of mutation pairs relative to the length of the queried sequence
		float prop_len; // the proportion of length of query sequence versus reference sequence
		double proximity; // unitless value: cummulative distance of pairs relative to query sequence length
		double proximity_mut; // unitless value: cummulative distance of only mutation pairs relative to query sequence length
		ChainMatch(final Chain query, final Chain ref, final Editions ed, final double[] stats, final float prop_len, final double score) {
			this.query = query;
			this.ref = ref;
			this.ed = ed;
			this.phys_dist = stats[0];
			this.cum_phys_dist = stats[1];
			this.stdDev = stats[2];
			this.median = stats[3];
			this.prop_mut = stats[4];
			this.prop_len = prop_len;
			this.score = score; // combined
			this.seq_sim = stats[6];
			this.proximity = stats[7];
			this.proximity_mut = stats[8];
		}
	}

	static private class QueryMatchList {
		Chain query;
		ChainMatch[] cm;
		QueryMatchList(final Chain query, final int n_ref_chains) {
			this.query = query;
			this.cm = new ChainMatch[n_ref_chains];
		}
	}

	static private class ChainMatchComparator implements Comparator {
		public int compare(final Object ob1, final Object ob2) {
			ChainMatch cm1 = (ChainMatch)ob1;
			ChainMatch cm2 = (ChainMatch)ob2;
			// select for smallest physical distance of the center of mass
			// double val = cm1.phys_dist - cm2.phys_dist;
			/*
			final double val = cm1.median - cm2.median;
			if (val < 0) return -1; // m1 is closer
			if (val > 0) return 1; // m1 is further away
			return 0; // same distance
			*/

			// Select the largest score
			final double val = cm1.score - cm2.score;
			if (val > 0) return -1;
			if (val < 0) return 1;
			return 0; // same distance
		}
	}
	static private class ChainMatchComparatorSim implements Comparator {
		public int compare(final Object ob1, final Object ob2) {
			ChainMatch cm1 = (ChainMatch)ob1;
			ChainMatch cm2 = (ChainMatch)ob2;
			// select for largest score
			double val = cm1.score - cm2.score;
			if (val < 0) return 1; // m2 is more similar
			if (val > 0) return -1; // m2 is less similar
			return 0; // same
		}
	}

	static private class Visualizer {
		QueryHolder qh;
		LayerSet common; // calibrated to queried pipe space, which is now also the space of all others.
		boolean query_shows = false;
		VectorString3D[] vs_axes, vs_axes_ref;

		Visualizer(QueryHolder qh, VectorString3D[] vs_axes, VectorString3D[] vs_axes_ref) {
			this.qh = qh;
			this.vs_axes = vs_axes;
			this.vs_axes_ref = vs_axes_ref;
			// create common LayerSet space
			Pipe pipe = qh.getAllQueriedPipes().iterator().next();
			LayerSet ls = pipe.getLayerSet();
			Calibration cal1 = ls.getCalibration();
			this.common = new LayerSet(pipe.getProject(), pipe.getProject().getLoader().getNextId(), "Common", 10, 10, 0, 0, 0, ls.getLayerWidth() * cal1.pixelWidth, ls.getLayerHeight() * cal1.pixelHeight, false, 2, new AffineTransform());
			Calibration cal = new Calibration();
			cal.setUnit(cal1.getUnit()); // homogeneous on all axes
			this.common.setCalibration(cal);
		}
		/** Shows the matched chain in 3D. */
		public void show3D(Chain query, Chain ref) {
			reset();
			if (!query_shows) showAxesAndQueried();
			VectorString3D vs = qh.makeVS2(ref, query.vs.getDelta()); // was: makeVS
			// The LayerSet is that of the Pipe being queried, not the given pipe to show which belongs to the reference project (and thus not to the queried pipe project)
			String title = ref.getCellTitle();
			if (Display3D.contains(common, title)) return;
			Display3D.addMesh(common, vs, title, ref.getColor());
		}
		public void showFull3D(Chain query, Chain ref) {
			reset();
			if (!query_shows) {
				showAxes(query.getRoot().getColor());
				showNode3D(query, true);
			}
			showNode3D(ref, false);
		}
		public void showNearby(Chain query) {
			reset();
			if (!query_shows) showAxesAndQueried();
			ArrayList<ChainMatch> matches = qh.matches.get(query);
			final VectorString3D vs_query = qh.makeVS1(query, query.vs.getDelta());
			final double radius = vs_query.computeLength() * 2;
			for (ChainMatch match : matches) {
				VectorString3D vs_ref = qh.makeVS2(match.ref, query.vs.getDelta());
				if (vs_query.isNear(vs_ref, radius)) {
					Display3D.addMesh(common, vs_ref, match.ref.getTitle(), match.ref.getColor());
				}
			}
		}
		void showNode3D(Chain chain, boolean as_query) {
			Pipe root = chain.getRoot();
			ProjectThing pt = (ProjectThing)root.getProject().findProjectThing(root).getParent();
			HashSet hs = pt.findChildrenOfTypeR("pipe");
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				Pipe p = (Pipe)((ProjectThing)it.next()).getObject();
				String title = p.getProject().getShortMeaningfulTitle(p);
				if (Display3D.contains(common, title)) continue; // add only any missing ones
				VectorString3D vs;
				if (as_query) {
					vs = qh.makeVS1(p, chain.vs.getDelta());
				} else { // as ref
					vs = qh.makeVS2(p, chain.vs.getDelta());
				}
				Display3D.addMesh(common, vs, title, p.getColor());
			}
		}
		public void showAxesAndQueried() {
			reset();
			Color qcolor = null;
			final Hashtable<Pipe,VectorString3D> queried = qh.getAllQueried();
			for (Iterator it = queried.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				Pipe p = (Pipe)entry.getKey();
				// if already there, ignore request
				String title = p.getProject().getShortMeaningfulTitle(p);
				if (Display3D.contains(common, title)) continue;
				VectorString3D vs = (VectorString3D)entry.getValue();
				if (null == qcolor) qcolor = p.getColor();
				Display3D.addMesh(common, vs, title, qcolor);
			}
			showAxes(qcolor);
		}
		void showAxes(Color qcolor) {
			if (null != vs_axes) {
				Color color = Color.pink.equals(qcolor) ? Color.red : qcolor;

				Display3D.addMesh(common, vs_axes[0], "X query", color);
				Display3D.addMesh(common, vs_axes[1], "Y query", color);
				Display3D.addMesh(common, vs_axes[2], "Z query", color);
			}
			if (null != vs_axes_ref) {
				Display3D.addMesh(common, vs_axes_ref[0], "X ref", Color.pink);
				Display3D.addMesh(common, vs_axes_ref[1], "Y ref", Color.pink);
				Display3D.addMesh(common, vs_axes_ref[2], "Z ref", Color.pink);
			}
			query_shows = true;
		}
		public void showInterpolated(Editions ed, Chain query, Chain ref) {
			reset();
			if (!query_shows) showAxesAndQueried();
			String title = "Av. " + query.getTitle() + " - " + ref.getTitle();
			// if already there, ignore request
			if (Display3D.contains(common, title)) return;
			VectorString3D vs = VectorString3D.createInterpolatedPoints(ed, 0.5f);
			Display3D.addMesh(common, vs, title, Utils.mix(query.getColor(), ref.getColor()));
		}
		private void reset() {
			if (null == Display3D.getDisplay(common)) query_shows = false;
		}
	}

	static protected final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk) {
		return findBestMatch(vs1, vs2, delta, skip_ends, max_mut, min_chunk, COMBINED, false, false);
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
	 * @param direct Whether to test vs1 against vs2 only, or to try all 4 possible combinations of reversed versus non-reversed and pick the best.
	 * */
	static protected final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, final int distance_type, final boolean direct, final boolean substring_matching) {

		if (substring_matching) {
			// identify shorter chain
			final VectorString3D shorter = vs1.length() < vs2.length() ? vs1 : vs2;
			final VectorString3D longer  = vs1 == shorter ? vs2 : vs1;
			
			// iterate matching of shorter string inside longer string:
			// (so that the match is always between two equally long strings)
			// aaaaaaaa   : 8 elements
			// bbbbb      : 5 elements
			//  bbbbb      --- total 4 matches to try
			//   bbbbb
			//    bbbbb
			//
			final int max_offset = longer.length() - shorter.length() + 1;
			Object[] best = null;
			for (int k=0; k<max_offset; k++) {
				final VectorString3D longer_sub = longer.substring(k, k+shorter.length());
				//Utils.log2("substring_matching lengths: shorter, longer : " + shorter.length() + ", " + longer_sub.length());
				final Object[] ob = direct ?
					              matchDirect(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type)
						    : matchFwdRev(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type);
				if (null == best) best = ob;
				else if (((Double)ob[1]).doubleValue() > ((Double)best[1]).doubleValue()) best = ob;
			}
			return best;
		} else {
			if (direct) {
				return matchDirect(vs1, vs2, delta, skip_ends, max_mut, min_chunk, distance_type);
			} else {
				return matchFwdRev(vs1, vs2, delta, skip_ends, max_mut, min_chunk, distance_type);
			}
		}
	}

	static public final int LEVENSHTEIN = 0;
	static public final int DISSIMILARITY = 1;
	static public final int AVG_PHYS_DIST = 2;
	static public final int MEDIAN_PHYS_DIST = 3;
	static public final int CUM_PHYST_DIST = 4;
	static public final int STD_DEV = 5;
	static public final int COMBINED = 6;
	static public final int PROXIMITY = 7;
	static public final int PROXIMITY_MUT = 8;
	static public final int STD_DEV_ALL = 9;

	// Weights as empirically approximated with some lineages, with S. Preibisch ( see Test_Scoring.java )
	static public final double[] W = new double[]{1.3345290383577453, -0.0012626693452889859, -0.012764729437173508, -0.13344076489951817};

	static public final double score(final double seq_sim, final double levenshtein, final double median_phys_dist, final double[] w) {
		//       S                L                    M
		return seq_sim * w[0] + levenshtein * w[1] + median_phys_dist * w[2] + w[3];
	}

	/** Zero is best; gets bad towards positive infinite. */
	static private final double getScore(Editions ed, boolean skip_ends, int max_mut, float min_chunk, int distance_type) {
		switch (distance_type) {
			case LEVENSHTEIN: // Levenshtein
				return ed.getDistance();
			case DISSIMILARITY: // Dissimilarity
				return 1 - ed.getSimilarity(skip_ends, max_mut, min_chunk);
			case AVG_PHYS_DIST: // average physical distance between mutation pairs only
				return ed.getPhysicalDistance(skip_ends, max_mut, min_chunk, true);
			case MEDIAN_PHYS_DIST: // median physical distance between all pairs
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[3]; // 3 is median
			case CUM_PHYST_DIST: // cummulative physical distance between all pairs
				return ed.getPhysicalDistance(skip_ends, max_mut, min_chunk, false);
			case STD_DEV: // stdDev of distances between mutation pairs only
				return ed.getStdDev(skip_ends, max_mut, min_chunk);
			case STD_DEV_ALL: // stdDev of distances between all pairs
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[2];
			case COMBINED: // combined score
				return 1 / score(ed.getSimilarity(), ed.getDistance(), ed.getStatistics(skip_ends, max_mut, min_chunk, false)[3], Compare.W);
			case PROXIMITY: // cummulative distance relative to largest physical length of the two sequences
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[7]; // 7 is proximity
			case PROXIMITY_MUT: // cummulative distance of mutation pairs relative to largest physical length of the two sequences
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[8]; // 8 is proximity
		}
		return Double.NaN;
	}

	static private final Object[] matchDirect(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, int distance_type) {
		// Levenshtein is unfortunately not commutative: must try both
		final Editions ed1 = new Editions(vs1, vs2, delta, false);
		double score1 = getScore(ed1, skip_ends, max_mut, min_chunk, distance_type);
		final Editions ed2 = new Editions(vs2, vs1, delta, false);
		double score2 = getScore(ed2, skip_ends, max_mut, min_chunk, distance_type);
		return score1 < score2 ?
			new Object[]{ed1, score1}
		      : new Object[]{ed2, score2};
	}

	// Match in all possible ways
	static private final Object[] matchFwdRev(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, int distance_type) {

		final VectorString3D vs1rev = vs1.makeReversedCopy();
		final VectorString3D vs2rev = vs2.makeReversedCopy();

		final Editions[] ed = new Editions[4];

		// vs1 vs2
		ed[0] = new Editions(vs1, vs2, delta, false);
		// vs1rev vs2rev
		ed[1] = new Editions(vs1rev, vs2rev, delta, false);
		// vs1 vs2rev
		ed[2] = new Editions(vs1, vs2rev, delta, false);
		// vs1rev vs2
		ed[3] = new Editions(vs1rev, vs2, delta, false);

		//double best_score1 = 0;
		double best_score = Double.MAX_VALUE; // worst possible

		Editions best_ed = null;
		for (int i=0; i<ed.length; i++) {
			double score = getScore(ed[i], skip_ends, max_mut, min_chunk, distance_type);
			if (score < best_score) {
				best_ed = ed[i];
				best_score = score;
				//best_score1 = score1;
			}
		}
		//Utils.log2("score, score1: " + best_score + ", " + best_score1);

		// now test also starting from the middle of the longest mutation chunk of the best matching
		try {
			Editions ed_center = best_ed.recreateFromCenter(max_mut);
			// is null if no chunks were found
			if (null != ed_center) {
				double score_center = getScore(ed_center, skip_ends, max_mut, min_chunk, distance_type);
				if (score_center < best_score) {
					best_ed = ed_center;
					best_score = score_center;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return new Object[]{best_ed, new Double(best_score)};
	}

	static private boolean matchUnits(String unit1, String unit2, String project_title) {
		if (unit1.equals(unit2)) return true;
		// else, record problem
		Utils.log("WARNING: the calibration units of the queried pipe (" + unit1 + ") does not match with that of the reference project '" + project_title + "' (" + unit2 + ").");
		return true;
	}

	/** Compare the given pipe with other pipes in the given standard project(s). WARNING: the calibrations will work ONLY IF all pipes found to compare with come from LayerSets which have the same units of measurement! For example, all in microns. */
	static public final Bureaucrat findSimilar(final Pipe pipe, final Project[] ref, final boolean ignore_orientation, final boolean ignore_calibration, final boolean mirror, final boolean chain_branches, final boolean show_gui) {
		final Worker worker = new Worker("Comparing pipes...") {
			public void run() {
				startedWorking();
				try {
		if (pipe.length() < 2) {
			Utils.log("Query pipe has less than 2 points!");
			quit();
			return;
		}

		Utils.log2("Will search into " + ref.length + " projects.");

		Calibration cal = null;
		if (!ignore_calibration) cal = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibration() : null);

		final QueryHolder qh = new QueryHolder(cal, null, null,
				                       null, null, null);
		ArrayList<Chain> chains_ref = new ArrayList<Chain>();

		final ArrayList<VectorString3D> reversed_queries = new ArrayList<VectorString3D>();

		if (chain_branches) {
			// collect the full set of ref pipes from each project
			for (int i=0; i<ref.length; i++) {
				for (Chain c : createPipeChains(ref[i].getRootProjectThing(), ref[i].getRootLayerSet())) {
					if (c.getRoot().equals(pipe)) continue; // slip!
					chains_ref.add(c);
				}
			}
			// add all possible query chains, starting at the parent of the chosen pipe
			for (Chain chain : createPipeChains((ProjectThing)pipe.getProject().findProjectThing(pipe).getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain); // calibrates it
				if (ignore_orientation) {
					if (mirror) chain.vs.mirror(VectorString3D.X_AXIS);
					chain.vs.relative();
				}
				reversed_queries.add(chain.vs.makeReversedCopy());
			}
		} else {
			// no branching: single query of one single-pipe chain
			Chain chain = new Chain(pipe);
			qh.addQuery(chain); // calibrates it
			reversed_queries.add(chain.vs.makeReversedCopy());
			for (int i=0; i<ref.length; i++) {
				for (ZDisplayable zd : ref[i].getRootLayerSet().getZDisplayables(Pipe.class)) {
					if (zd.equals(pipe)) continue; // skip!
					chains_ref.add(new Chain((Pipe)zd));
				}
			}
		}

		qh.setReferenceChains(chains_ref);
		chains_ref = null; // == qh.chains_ref

		// each thread handles a ref pipe, which is to be matched against all queries and reversed queries
		final int n_ref_chains = qh.chains_ref.size();
		final QueryMatchList[] qm = new QueryMatchList[qh.queries.size()];
		int ne = 0;
		for (Chain query : qh.queries) qm[ne++] = new QueryMatchList(query, n_ref_chains);

		final Thread[] threads = MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0);

		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread(new Runnable() {
				public void run() {
				////
		for (int k = ai.getAndIncrement(); k < n_ref_chains; k = ai.getAndIncrement()) {
			try {

				// obtain a ref chain, to be uniquely processed by this thread
				final Chain ref = qh.chains_ref.get(k);
				final Calibration cal2 = ref.getRoot().getLayerSet().getCalibration();
				if (!ignore_calibration) ref.vs.calibrate(cal2);
				for (int q=qh.queries.size()-1; q>-1; q--) {
					Chain query = qh.queries.get(q);
					VectorString3D vs1 = query.vs; // calibrated
					VectorString3D vs1_rev = reversed_queries.get(q); // calibrated
					double delta1 = vs1.getDelta();
					//
					VectorString3D vs2 = qh.makeVS2(ref, delta1);// was: makeVS // makes resampled copy
					if (ignore_orientation) {
						if (mirror) vs2.mirror(VectorString3D.X_AXIS);
						vs2.relative();
					}

					Editions ed;
					double score;
					if (ignore_orientation) {
						ed = new Editions(vs1, vs2, delta1, false);
						score = ed.getSimilarity();
						Editions ed_rev = new Editions(vs1_rev, vs2, delta1, false);
						double score_rev = ed_rev.getSimilarity();
						// the higher the better
						if (score_rev > score) {
							ed = ed_rev;
							score = score_rev;
							query.vs = vs1_rev; //swap!
						}
					} else {
						Object[] ob = findBestMatch(vs1, vs2, delta1, false, 0, 1);
						ed = (Editions)ob[0];
						score = ((Double)ob[1]).doubleValue();
					}
					//qh.addMatch(query, ref, ed, score, ed.getPhysicalDistance(false, 0, 1));
					double[] stats = ed.getStatistics(false, 0, 1, false);
					float prop_len = ((float)vs1.length()) / vs2.length();
					qm[q].cm[k] = new ChainMatch(query, ref, ed, stats, prop_len, score(ed.getSimilarity(), ed.getDistance(), ed.getStatistics(false, 0, 0, false)[3], Compare.W));
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
		// put result into the Worker
		this.result = qm;

		if (show_gui) {
			// Now, sort matches by physical distance.
			qh.addMatches(qm);
			qh.relative = ignore_orientation;
			qh.sortMatches(ignore_orientation ? new ChainMatchComparatorSim() : new ChainMatchComparator());
			qh.createGUI(null, null);
		}


				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		HashSet hsp = new HashSet();
		hsp.add(pipe.getProject());
		for (int i=0; i<ref.length; i++) hsp.add(ref[i]);
		Project[] p = (Project[])hsp.toArray(new Project[0]);
		Bureaucrat burro = new Bureaucrat(worker, p);
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
				if (0 == ht_tabs.size()) label.setText("[ -- empty -- ]");
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
			if (null == ht_tabs) ht_tabs = new Hashtable<JScrollPane,Chain>();
			tabs = new JTabbedPane();
			tabs.setPreferredSize(new Dimension(800,500));
			// a listener to change the label text when the tab is selected
			ChangeListener tabs_listener =  new ChangeListener() {
				public void stateChanged(ChangeEvent ce) {
					if (null == frame || null == ht_tabs || null == tabs || null == label) return; // the event fires during instantiation ... pffff!
					Object ob = tabs.getSelectedComponent();
					if (null == ob) return;
					Chain query = ht_tabs.get(ob);
					if (null == query) return;
					label.setText(query.getRoot().getProject().toString() + ": " + query.getCellTitle());
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
					ht_tabs.get(tabs.getSelectedComponent()).showCentered2D(me.isShiftDown());
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

	static public void remove(Displayable displ) {
		if (null == frame) return;
		for (int i=tabs.getTabCount()-1; i>-1; i--) {
			Container jsp = (Container)tabs.getComponentAt(0);
			JTable table = (JTable)jsp.getComponent(i);
			QueryHolderTableModel model = (QueryHolderTableModel)table.getModel();
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
				case 2: return "Score";
				case 3: return "Seq Sim"; // sequence similarity
				case 4: return "Lev Dist";
				case 5: return null != qh.cal2 ? "Dist (" + qh.cal2.getUnits() + ")" : "Dist";
				case 6: return "Median";
				case 7: return null != qh.cal2 ? "Cum Dist (" + qh.cal2.getUnits() + ")" : "Cum Dist";
				case 8: return "Std Dev";
				case 9: return "Prop Mut";
				case 10: return "Prop Lengths";
				case 11: return "Proximity";
				case 12: return "Prox Mut";
				default: return "";
			}
		}
		public int getRowCount() { return cm.size(); }
		public int getColumnCount() { return 13; }
		public Object getValueAt(int row, int col) {
			switch (col) {
				case 0: return cm.get(row).ref.getRoot().getProject();
				case 1: return cm.get(row).ref.getCellTitle();
				case 2: return Utils.cutNumber(cm.get(row).score, 2); // combined score
				case 3: return Utils.cutNumber(cm.get(row).seq_sim * 100, 2) + " %"; // 1 - seq_sim, to convert from dissimilarity to similarity
				case 4: return Utils.cutNumber(cm.get(row).ed.getDistance(), 2);
				case 5: return Utils.cutNumber(cm.get(row).phys_dist, 2);
				case 6: return Utils.cutNumber(cm.get(row).median, 2);
				case 7: return Utils.cutNumber(cm.get(row).cum_phys_dist, 2);
				case 8: return Utils.cutNumber(cm.get(row).stdDev, 2);
				case 9: return Utils.cutNumber(cm.get(row).prop_mut * 100, 2) + " %"; // the proportion of mutations, relative to the query sequence length
				case 10: return Utils.cutNumber(cm.get(row).prop_len * 100, 2) + " %"; // the proportion of lengths = len(query) / len(ref)
				case 11: return Utils.cutNumber(cm.get(row).proximity, 2);
				case 12: return Utils.cutNumber(cm.get(row).proximity_mut, 2);
				default: return "";
			}
		}
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		public void setValueAt(Object value, int row, int col) {} // ignore
		public void remove(Displayable d) { qh.remove(d); }
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
					model.vis.show3D(model.query, ref);
				} else {
					ref.showCentered2D(false);
				}
				return;
			}

			if (Utils.isPopupTrigger(me)) {
				// select row under mouse
				table.getSelectionModel().setSelectionInterval(row, row);
				final int[] sel = table.getSelectedRows();
				JPopupMenu popup = new JPopupMenu();
				final String show3D = "Show match in 3D";
				final String interp3D = "Show interpolated in 3D";
				final String showfull3D = "Show full node in 3D";
				final String showCentered = "Show centered in 2D";
				final String showAxes = "Show axes and queried";
				final String showNearby = "Show all nearby";

				// TODO: need better
				//  - to show the matched chains alone
				//  - to show the full matched nodes

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
							model.vis.show3D(model.query, ref);
						} else if (command.equals(showCentered)) {
							ref.showCentered2D(0 != (ae.getModifiers()  & ActionEvent.SHIFT_MASK));
						} else if (command.equals(showAxes)) {
							model.vis.showAxesAndQueried();
						} else if (command.equals(showfull3D)) {
							model.vis.showFull3D(model.query, ref);
						} else if (command.equals(showNearby)) {
							model.vis.showNearby(model.query);
						}
					}
				};
				JMenuItem item;
				item = new JMenuItem(show3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(interp3D); popup.add(item); item.addActionListener(listener);
				if (model.qh.relative) item.setEnabled(false);
				item = new JMenuItem(showfull3D); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showCentered); popup.add(item); item.addActionListener(listener);
				item = new JMenuItem(showAxes); popup.add(item); item.addActionListener(listener);
				if (null == model.qh.vs_axes) item.setEnabled(false);
				item = new JMenuItem(showNearby); popup.add(item); item.addActionListener(listener);

				popup.show(table, me.getX(), me.getY());
			}
		}
	}


	/** Gets pipes for all open projects, and generates a matrix of dissimilarities, which gets passed on to the Worker thread and also to a file, if desired.
	 *
	 * @param to_file Whether to save the results to a file and popup a save dialog for it or not. In any case the results are stored in the worker's load, which you can retrieve like:
	 *     Bureaucrat bu = Compare.compareAllToAll(true);
	 *     Object result = bu.getWorker().getResult();
	 *     float[][] scores = (float[][])result[0];
	 *     ArrayList<Compare.Chain> chains = (ArrayList<Compare.Chain>)result[1];
	 * @param normalize Whether to normalize the score values so that the maximum value is 1 and the minimum is 0, or not.
	 */
	static public Bureaucrat compareAllToAll(final boolean to_file) {
		final GenericDialog gd = new GenericDialog("All to all");
		gd.addMessage("Choose a point interdistance to resample to, or 0 for the average of all.");
		gd.addNumericField("point interdistance: ", 0, 2);
		gd.addCheckbox("skip insertion/deletion strings at ends when scoring", true);
		gd.addNumericField("maximum_ignorable consecutive muts in endings: ", 5, 0);
		gd.addNumericField("minimum_percentage that must remain: ", 0.5, 2);
		Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(0), new Component[]{(Component)gd.getNumericFields().get(0), (Component)gd.getNumericFields().get(1)}, null);

		final String[] transforms = {"translate and rotate",
			                     "translate, rotate and scale",
					     "translate, rotate, scale and shear",
					     "relative",
					     "direct"};
		gd.addChoice("Transform_type: ", transforms, transforms[2]);
		gd.addCheckbox("Chain_branches", true);

		final String[][] presets = {{"medial lobe", "dorsal lobe", "peduncle"}};
		final String[] preset_names = new String[]{"X - 'medial lobe', Y - 'dorsal lobe', Z - 'peduncle'"};
		gd.addChoice("Presets: ", preset_names, preset_names[0]);
		gd.addMessage("");
		String[] distance_types = {"Levenshtein", "Dissimilarity", "Average physical distance", "Median physical distance", "Cummulative physical distance", "Standard deviation", "Combined SLM", "Proximity", "Proximity of mutation pairs"};
		gd.addChoice("Scoring type: ", distance_types, distance_types[3]);
		final String[] formats = {"ggobi XML", ".csv", "Phylip .dis"};
		if (to_file) {
			gd.addChoice("File format: ", formats, formats[2]);
		}
		gd.addCheckbox("normalize", false);
		gd.addCheckbox("direct", true);
		gd.addCheckbox("substring_matching", true);

		//////

		gd.showDialog();
		if (gd.wasCanceled()) return null;


		// gather all open projects
		final Project[] p = Project.getProjects().toArray(new Project[0]);

		final Worker worker = new Worker("Comparing all to all") {
			public void run() {
				startedWorking();
				try {

		double delta = gd.getNextNumber();
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
		String[] preset = presets[gd.getNextChoiceIndex()];

		int distance_type = gd.getNextChoiceIndex();

		String format = formats[0];
		if (to_file) format = gd.getNextChoice().trim();

		boolean normalize = gd.getNextBoolean();
		boolean direct = gd.getNextBoolean();
		boolean substring_matching = gd.getNextBoolean();

		String filename = null,
		       dir = null;

		if (to_file) {
			SaveDialog sd = new SaveDialog("Save matrix", OpenDialog.getDefaultDirectory(), null, ".csv");
			filename = sd.getFileName();
			if (null == filename) {
				finishedWorking();
				return;
			}
			dir = sd.getDirectory().replace('\\', '/');
			if (!dir.endsWith("/")) dir += "/";
		}


		// gather all chains
		final ArrayList[] p_chains = new ArrayList[p.length]; // to keep track of each project's chains
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		for (int i=0; i<p.length; i++) {
			p_chains[i] = createPipeChains(p[i].getRootProjectThing(), p[i].getRootLayerSet());
			chains.addAll(p_chains[i]);
			// calibrate
			Calibration cal = p[i].getRootLayerSet().getCalibrationCopy();
			for (Chain chain : (ArrayList<Chain>)p_chains[i]) chain.vs.calibrate(cal);
		}
		final int n_chains = chains.size();

		// compute global average delta
		if (0 == delta) {
			for (Chain chain : chains) {
				delta += ( chain.vs.getAverageDelta() / n_chains );
			}
		}
		Utils.log2("Using delta: " + delta);

		// register all, or relative
		if (3 == transform_type) {
			for (Chain chain : chains) {
				chain.vs.resample(delta); // BEFORE making it relative
				chain.vs.relative();
			}
		} else {
			if (transform_type < 3) {
				// no need //VectorString3D[][] vs_axes = new VectorString3D[p.length][];
				Vector3d[][] o = new Vector3d[p.length][];
				for (int i=0; i<p.length; i++) {
					// 1 - find pipes to work as axes for each project
					ArrayList<ZDisplayable> pipes = p[i].getRootLayerSet().getZDisplayables(Pipe.class);
					String[] pipe_names = new String[pipes.size()];
					for (int k=0; k<pipes.size(); k++) {
						pipe_names[k] = p[i].getMeaningfulTitle(pipes.get(k));
					}
					int[] s = findXYZAxes(preset, pipes, pipe_names);

					// if axes are -1, forget it: not found
					if (-1 == s[0] || -1 == s[1] || -1 == s[2]) {
						Utils.log("Can't find axes for project " + p[i]);
						o = null;
						finishedWorking();
						return;
					}

					// obtain axes and origin
					Object[] pack = obtainOrigin(new Pipe[]{(Pipe)pipes.get(s[0]),
									     (Pipe)pipes.get(s[1]),
									     (Pipe)pipes.get(s[2])},
									     transform_type);

					// no need //vs_axes[i] = (VectorString3D[])pack[0];
					o[i] = (Vector3d[])pack[1];
				}
				// match the scales to make the largest be 1.0
				final double scaling_factor = VectorString3D.matchOrigins(o, transform_type);
				Utils.log2("matchOrigins scaling factor: " + scaling_factor + " for transform_type " + transform_type);
				// transform all
				for (int i=0; i<p.length; i++) {
					Vector3d trans = new Vector3d(-o[i][3].x, -o[i][3].y, -o[i][3].z);
					Transform3D rot = VectorString3D.createOriginRotationTransform(o[i]);
					for (Chain chain : (ArrayList<Chain>)p_chains[i]) {
						chain.vs.translate(trans);
						chain.vs.transform(rot);
					}
				}
			}
			// else, direct

			// After calibration and transformation, resample all to the same delta
			for (Chain chain : chains) chain.vs.resample(delta);
		}

		// compare all to all
		final VectorString3D[] vs = new VectorString3D[n_chains];
		for (int i=0; i<n_chains; i++) vs[i] = chains.get(i).vs;
		final float[][] scores = Compare.scoreAllToAll(vs, distance_type, delta, skip_ends, max_mut, min_chunk, direct, substring_matching, this);

		if (null == scores) {
			finishedWorking();
			return;
		}

		// store matrix and chains into the worker
		this.result = new Object[]{scores, chains};

		// write to file
		if (!to_file) {
			finishedWorking();
			return;
		}

		File f = new File(dir + filename);
		final OutputStreamWriter dos = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(f)), "8859_1"); // encoding in Latin 1 (for macosx not to mess around

		// Normalize matrix to largest value of 1.0
		if (normalize) {
			float max = 0;
			for (int i=0; i<scores.length; i++) { // traverse half matrix ony: it's mirrored
				for (int j=i; j<scores[0].length; j++) {
					if (scores[i][j] > max) max = scores[i][j];
				}
			}
			for (int i=0; i<scores.length; i++) {
				for (int j=i; j<scores[0].length; j++) {
					scores[i][j] = scores[j][i] /= max;
				}
			}
		}

		// write chain titles, with project prefix
		if (format.equals(formats[0])) {
			// as csv:
			try {
				StringBuffer[] titles = new StringBuffer[n_chains];
				int next = 0;
				for (int i=0; i<p.length; i++) {
					String prefix = Utils.getCharacter(i+1);
					dos.write("\"\""); //empty upper left corner
					for (Chain chain : (ArrayList<Chain>)p_chains[i]) {
						dos.write(",");
						titles[next] = new StringBuffer().append('\"').append(prefix).append(' ').append(chain.getCellTitle()).append('\"');
						dos.write(titles[next].toString());
						next++;
					}
				}
				dos.write("\n");
				for (int i=0; i<n_chains; i++) {
					StringBuffer line = new StringBuffer();
					line.append(titles[i]);
					for (int j=0; j<n_chains; j++) line.append(',').append(scores[i][j]);
					line.append('\n');
					dos.write(line.toString());
				}
				dos.flush();
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else if (format.equals(formats[1])) {
			// as XML:
			try {
				StringBuffer sb = new StringBuffer("<?xml version=\"1.0\"?>\n<!DOCTYPE ggobidata SYSTEM \"ggobi.dtd\">\n");
				sb.append("<ggobidata count=\"2\">\n");

				sb.append("<data name=\"Pipe Chains\">\n");
				sb.append("<description />\n");
				sb.append("<variables count=\"0\">\n</variables>\n"); // ggobi: what a crappy XML parser it has
				sb.append("<records count=\"").append(chains.size()).append("\" glyph=\"fr 1\" color=\"3\">\n");
				int next = 0;
				for (int i=0; i<p.length; i++) {
					String prefix = Utils.getCharacter(i+1);
					String color = new StringBuffer("color=\"").append(i+1).append('\"').toString();
					for (Chain chain : (ArrayList<Chain>)p_chains[i]) {
						sb.append("<record id=\"").append(next+1).append("\" label=\"").append(prefix).append(' ').append(chain.getCellTitle()).append("\" ").append(color).append("></record>\n");
						next++;
					}
				}
				sb.append("</records>\n</data>\n");

				sb.append("<data name=\"distances\">\n");
				sb.append("<description />\n");
				sb.append("<variables count=\"1\">\n<realvariable name=\"D\" />\n</variables>\n");
				sb.append("<records count=\"").append(n_chains*(n_chains-1)).append("\" glyph=\"fr 1\" color=\"0\">\n");
				for (int i=0; i<n_chains; i++) {
					for (int j=0; j<n_chains; j++) {
						if (i == j) continue;
						sb.append("<record source=\"").append(i+1).append("\" destination=\"").append(j+1).append("\">").append(scores[i][j]).append("</record>\n");
					}
				}
				sb.append("</records>\n</data>\n");

				sb.append("</ggobidata>");

				dos.write(sb.toString());
				dos.flush();

			} catch (Exception e) {
				e.printStackTrace();
			}
		} else if (format.equals(formats[2])) {
			// as Phylip .dis
			try {
				// collect different projects
				final ArrayList<Project> projects = new ArrayList<Project>();
				for (Chain chain : chains) {
					Project p = chain.getRoot().getProject();
					if (!projects.contains(p)) projects.add(p);
				}
				final HashSet names = new HashSet();
				final StringBuffer sb = new StringBuffer();
				sb.append(scores.length).append('\n');
				dos.write(sb.toString());
				for (int i=0; i<scores.length; i++) {
					sb.setLength(0);
					String title = chains.get(i).getShortCellTitle().replace(' ', '_').replace('\t', '_').replace('[', '-').replace(']', '-');
					// Crop title to 6 chars
					if (title.length() > 8) {
						String title2 = title.substring(0, 8);
						Utils.log2("Cropping " + title + " to " + title2);
						title = title2;
					}
					int k = 1;
					String name = title;
					// Prepend a project char identifier
					String project_name = "";
					if (projects.size() > 1) {
						project_name = Utils.getCharacter(projects.indexOf(chains.get(i).getRoot().getProject()) + 1).toLowerCase();
						name = project_name + title;
					}
					// Append a char index when name is used multiple times (mostly because of branching, and other reasons)
					if (names.contains(name)) {
						names.remove(name);
						names.add(name + "a");
						name += "a";
						Utils.log2("Contained name " + name + ", thus set to " + name + "a");
					} else if (names.contains(name + "a")) name += "a"; // so the 'while' will find it
					while (names.contains(name)) {
						k++;
						name = project_name + title + Utils.getCharacter(k).toLowerCase();
					}
					names.add(name);
					//
					final int len = 12;
					sb.append(name);
					for (int j=len - name.length(); j>0; j--) sb.append(' '); // pad with spaces up to len
					int count = 0;
					for (int j=0; j<scores[0].length; j++) {
						sb.append(' ').append(scores[i][j]);
						count++;
						if (7 == count && j < scores[0].length-1) {
							sb.append('\n');
							count = 0;
							while (++count < len) sb.append(' ');
							sb.append(' ');
							count = 0;
						}
					}
					sb.append('\n');
					dos.write(sb.toString());
				}
				dos.flush();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		dos.close();


				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					finishedWorking();
				}
			}
		};
		Bureaucrat burro = new Bureaucrat(worker, p);
		burro.goHaveBreakfast();
		return burro;
	}

	/** Returns the half matrix of scores, with values copied from one half matrix to the other, and a diagonal of zeros.
	 * @param distance_type ranges from 0 to 5, and includes: 0=Levenshtein, 1=Dissimilarity, 2=Average physical distance, 3=Median physical distance, 4=Cummulative physical distance and 5=Standard deviation. */
	static public float[][] scoreAllToAll(final VectorString3D[] vs, final int distance_type, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk, final boolean direct, final boolean substring_matching, final Worker worker) {
		final float[][] scores = new float[vs.length][vs.length];


		final AtomicInteger ai = new AtomicInteger(0);

		final Thread[] threads = MultiThreading.newThreads();
		for (int ithread=0; ithread<threads.length; ithread++) {
			threads[ithread] = new Thread() { public void run() {
				////

		for (int i=ai.getAndIncrement(); i<vs.length; i=ai.getAndIncrement()) {
			final VectorString3D vs1 = vs[i];
			for (int j=i+1; j<vs.length; j++) {
				if (null != worker && worker.hasQuitted()) return;
				final Object[] ob = findBestMatch(vs[i], vs[j], delta, skip_ends, max_mut, min_chunk, distance_type, direct, substring_matching); // TODO should add 'distance_type' as well for the selection of the best match when not direct.
				/*
				switch (distance_type) {
					case 0: // Levenshtein
						scores[i][j] = (float)((Editions)ob[0]).getDistance();
						break;
					case 1: // dissimilarity
						scores[i][j] = (float)((Double)ob[1]).doubleValue();
						break;
					case 2: // average physical distance between mutation pairs
						scores[i][j] = (float)((Editions)ob[0]).getPhysicalDistance(skip_ends, max_mut, min_chunk, true);
						break;
					case 3: // median physical distance between mutation pairs
						scores[i][j] = (float)((Editions)ob[0]).getStatistics(skip_ends, max_mut, min_chunk, false)[3]; // 3 is median
						break;
					case 4: // cummulative physical distance between mutation pairs
						scores[i][j] = (float)((Editions)ob[0]).getPhysicalDistance(skip_ends, max_mut, min_chunk, false);
						break;
					case 5: // stdDev of distances between mutation pairs
						scores[i][j] = (float)((Editions)ob[0]).getStdDev(skip_ends, max_mut, min_chunk);
						break;
				}
				*/

				final Editions ed = (Editions)ob[0];
				scores[i][j] = (float)getScore(ed, skip_ends, max_mut, min_chunk, distance_type);


				// mirror value
				scores[j][i] = scores[i][j];
			}
		}

			////
			}};
		}
		MultiThreading.startAndJoin(threads);

		if (null != worker && worker.hasQuitted()) return null;

		return scores;
	}
}
