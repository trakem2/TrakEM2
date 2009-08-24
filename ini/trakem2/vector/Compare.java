/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona.

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
import ini.trakem2.tree.Thing;
import mpi.fruitfly.general.MultiThreading;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.PointMatch;
import mpicbg.models.AffineModel3D;
import mpicbg.models.TranslationModel3D;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.io.SaveDialog;
import ij.io.OpenDialog;
import ij.io.FileSaver;
import ij.gui.Plot;
import ij.io.DirectoryChooser;
import ij.ImagePlus;
import ij.process.ByteProcessor;

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
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.*;
import java.awt.geom.AffineTransform;
import java.awt.Rectangle;
import java.awt.image.IndexColorModel;

import javax.vecmath.Color3f;
import javax.vecmath.Tuple3d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;
import javax.vecmath.Matrix4d;
import javax.media.j3d.Transform3D;

import ij3d.Content;

import java.io.*;

import lineage.LineageClassifier;

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

	static public final Bureaucrat findSimilar(final Line3D pipe) {
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

	static public final Bureaucrat findSimilarWithAxes(final Line3D pipe) {
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
		gd.addMessage(pipe.getProject().getShortMeaningfulTitle((ZDisplayable)pipe));

		ArrayList<ZDisplayable> pipes = pipe.getLayerSet().getZDisplayables(Line3D.class, true);
		final String[] pipe_names = new String[pipes.size()];

		final String[][] presets = {{"medial lobe", "dorsal lobe", "peduncle"}};
		final String[] preset_names = new String[]{"X - 'medial lobe', Y - 'dorsal lobe', Z - 'peduncle'"};
		/* 0 */ gd.addChoice("Presets: ", preset_names, preset_names[0]);
		final Choice cpre = (Choice)gd.getChoices().get(0);

		final ArrayList<ZDisplayable> pipes_ref = all[iother].getRootLayerSet().getZDisplayables(Line3D.class, true);
		final String[] pipe_names_ref = new String[pipes_ref.size()];
		final Object[] holder = new Object[]{pipe_names_ref};

		// automatically find for the first preset
		final Line3D[] query_axes = findXYZAxes(presets[0], pipe);
		final int[] s = findXYZAxes(query_axes, pipes, pipe_names);
		final int[] t = findFirstXYZAxes(presets[0], pipes_ref, pipe_names_ref);

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
		final String[] options = new String[all.length];
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
					if (all[i].toString().equals(project_name)) {
						project = all[i];
						break;
					}
				}
				if (null == project) return;
				pipes_ref.clear();
				pipes_ref.addAll(project.getRootLayerSet().getZDisplayables(Line3D.class, true));
				String[] pipe_names_ref = new String[pipes_ref.size()];
				holder[0] = pipe_names_ref;
				int[] s = findFirstXYZAxes(presets[cpre.getSelectedIndex()], pipes_ref, pipe_names_ref);
				for (int i=0; i<3; i++) {
					if (-1 == s[i]) s[i] = 0;
					ref[i].removeAll();
					for (int k=0; k<pipe_names_ref.length; k++) {
						ref[i].add(pipe_names_ref[k]);
					}
					if (0 != s[i]) ref[i].select(s[i]);
					else ref[i].select(0);
				}
			}
		});

		gd.addCheckbox("skip insertion/deletion strings at ends when scoring", false);
		gd.addNumericField("maximum_ignorable consecutive muts in endings: ", 2, 0);
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
		gd.addChoice("Sort by: ", distance_types, distance_types[3]);

		//////

		gd.showDialog();
		if (gd.wasCanceled()) return null;

		// ok, ready
		int ipresets = gd.getNextChoiceIndex();

		Line3D[] axes = new Line3D[]{
			(Line3D)pipes.get(gd.getNextChoiceIndex()),
			(Line3D)pipes.get(gd.getNextChoiceIndex()),
			(Line3D)pipes.get(gd.getNextChoiceIndex())
		};

		int iproject = gd.getNextChoiceIndex();

		Line3D[] axes_ref = new Line3D[]{
			(Line3D)pipes_ref.get(gd.getNextChoiceIndex()),
			(Line3D)pipes_ref.get(gd.getNextChoiceIndex()),
			(Line3D)pipes_ref.get(gd.getNextChoiceIndex())
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
		final int distance_type = gd.getNextChoiceIndex();

		// check that the Calibration units are the same
		if (!matchUnits(pipe.getLayerSet().getCalibration().getUnit(), all[iproject].getRootLayerSet().getCalibration().getUnit(), all[iproject].getTitle())) {
			return null;
		}

		return findSimilarWithAxes(pipe, axes, axes_ref, pipes_ref, skip_ends, max_mut, min_chunk, transform_type, chain_branches, true, score_mut, substring_matching, direct, delta, distance_type);
	}

	/** Finds the first three X,Y,Z axes as specified by the names in preset. */
	static private int[] findFirstXYZAxes(final String[] preset, final ArrayList<ZDisplayable> pipes, final String[] pipe_names) {
		final int[] s = new int[]{-1, -1, -1};
		int next = 0;
		for (ZDisplayable zd : pipes) {
			pipe_names[next] = zd.getProject().getShortMeaningfulTitle(zd);
			if (-1 != s[0] && -1 != s[1] && -1 != s[2]) {
				 // Already all found, just filling names
				next++;
				continue;
			}
			final String name = pipe_names[next].toLowerCase();
			if (-1 != name.indexOf(preset[0])) s[0] = next;
			else if (-1 != name.indexOf(preset[1])) s[1] = next;
			else if (-1 != name.indexOf(preset[2])) s[2] = next;
			next++;
		}
		return s;
	}

	/** Find the indices of the axes pipes in the pipes array list, and fills in the pipe_names array. */
	static private int[] findXYZAxes(final Line3D[] axes, final ArrayList<ZDisplayable> pipes, final String[] pipe_names) {
		final int[] s = new int[]{-1, -1, -1};
		int next = 0;
		for (ZDisplayable pipe : pipes) {
			pipe_names[next] = pipe.getProject().getShortMeaningfulTitle(pipe);
			if (pipe == axes[0]) s[0] = next;
			else if (pipe == axes[1]) s[1] = next;
			else if (pipe == axes[2]) s[2] = next;

			next++;
		}
		return s;
	}

	static private void trySetAsAxis(final Line3D[] axes, final String[] preset, final ProjectThing pt) {
		final Object ob = pt.getObject();
		if (null == ob || !(ob instanceof Line3D)) return;
		final Line3D pipe = (Line3D)ob;
		final String title = pipe.getProject().getShortMeaningfulTitle(pt, (ZDisplayable)pipe).toLowerCase();
		//Utils.log2("title is " + title);
		if (-1 != title.indexOf(preset[0])) axes[0] = pipe;
		else if (-1 != title.indexOf(preset[1])) axes[1] = pipe;
		else if (-1 != title.indexOf(preset[2])) axes[2] = pipe;
	}

	/** Finds the 3 pipes named according to the presets (such as "medial lobe", "dorsal lobe" and "peduncle"), that are structurally closest to the query_pipe in the Project Tree. In this fashion, if there are more than one hemisphere, the correct set of axes will be found for the query pipe.*/
	static public Line3D[] findXYZAxes(final String[] preset, final Line3D query_pipe) {
		if (preset.length < 3) {
			Utils.log2("findXYZAxes: presets must be of length 3");
			return null;
		}
		final Line3D[] axes = new Line3D[3];
		// Step up level by level looking for pipes with the given preset names
		final Project project = query_pipe.getProject();
		ProjectThing last = project.findProjectThing((ZDisplayable)query_pipe);
		trySetAsAxis(axes, preset, last);
		final String type = last.getType();
		do {
			final ProjectThing parent = (ProjectThing)last.getParent();
			if (null == parent) return axes;
			for (ProjectThing child : parent.getChildren()) {
				if (child == last) continue; // already searched
				HashSet<ProjectThing> al = child.findChildrenOfTypeR(new HashSet<ProjectThing>(), type);
				for (ProjectThing pt : al) {
					trySetAsAxis(axes, preset, pt);
				}
				if (null != axes[0] && null != axes[1] && null != axes[2]) return axes;
			}
			last = parent;
		} while (true);
	}

	/** Generate calibrated origin of coordinates. */
	static public Object[] obtainOrigin(final Line3D[] axes, final int transform_type, final Vector3d[] o_ref) {
		// pipe's axes
		final VectorString3D[] vs = new VectorString3D[3];
		for (int i=0; i<3; i++) vs[i] = axes[i].asVectorString3D();

		final Calibration cal = (null != axes[0].getLayerSet() ? axes[0].getLayerSet().getCalibration() : null);
		// 1 - calibrate
		if (null != cal) {
			for (int i=0; i<3; i++) vs[i].calibrate(cal);
		}
		// 2 - resample (although it's done before transforming, it's only for aesthetic purposes: it doesn't matter, won't ever go into dynamic programming machinery)
		double delta = 0;
		for (int i=0; i<3; i++) delta += vs[i].getAverageDelta();
		delta /= 3;
		for (int i=0; i<3; i++) vs[i].resample(delta);

		// return origin vectors for pipe's project
		final Vector3d[] o = VectorString3D.createOrigin(vs[0], vs[1], vs[2], transform_type, o_ref); // requires resampled vs

		return new Object[]{vs, o};
	}

	/** Compare pipe to all pipes in pipes_ref, by first transforming to match both sets of axes. */
	static public final Bureaucrat findSimilarWithAxes(final Line3D pipe, final Line3D[] axes, final Line3D[] axes_ref, final ArrayList<ZDisplayable> pipes_ref, final boolean skip_ends, final int max_mut, final float min_chunk, final int transform_type, final boolean chain_branches, final boolean show_gui, final boolean score_mut, final boolean substring_matching, final boolean direct, final double delta, final int distance_type) {
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

		// NEW style: bring query brain to reference brain space.

		final Calibration cal1 = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibration() : null);
		final Calibration cal2 = pipes_ref.get(0).getLayerSet().getCalibration();

		// obtain origin vectors for reference project
		Object[] pack2 = obtainOrigin(axes_ref, transform_type, null); // calibrates axes
		VectorString3D[] vs_axes_ref = (VectorString3D[])pack2[0];
		Vector3d[] o2 = (Vector3d[])pack2[1];

		final Transform3D M_ref = Compare.createTransform(o2);

		// obtain axes origin vectors for pipe's project
		Object[] pack1 = obtainOrigin(axes, transform_type, o2); // calibrates axes
		VectorString3D[] vs_axes = (VectorString3D[])pack1[0];
		Vector3d[] o1 = (Vector3d[])pack1[1];

		final Transform3D M_query = Compare.createTransform(o1);

		// The transfer transform: from query axes to reference axes: M_ref * M_query^{-1}
		final Transform3D T = new Transform3D(M_ref);
		T.mulInverse(M_query); // in place

		// Transform the query axes only (the reference ones stay the same)
		for (int i=0; i<3; i++) {
			// axes are already calibrated
			//Utils.log2("Axis " + i + " Length before transforming: " + vs_axes[i].computeLength());
			vs_axes[i].transform(T);
			//Utils.log2("Axis " + i + " Length after transforming: " + vs_axes[i].computeLength());
		}

		// If chain_branches, the query must also be split into as many branches as it generates.
		// Should generate a tab for each potential branch? Or better, a tab but not with a table but with a list of labels, one per potential branch, and underneath as many tables in more tabs?

		// the queries to do, according to how many different chains the query pipe is part of
		final QueryHolder qh = new QueryHolder(cal1, cal2, T);

		ArrayList<Chain> chains_ref;

		if (chain_branches) {
			// create all chained ref branches
			chains_ref = createPipeChains(pipes_ref.get(0).getProject().getRootProjectThing(), pipes_ref.get(0).getLayerSet()); // TODO WARNING: if the parent has more than one pipe, this will query them all!

			// add all possible query chains, starting at the parent of the chosen pipe
			for (Chain chain : createPipeChains((ProjectThing)pipe.getProject().findProjectThing(pipe).getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain, 0 == delta ? chain.vs.getAverageDelta() : delta);
			}
		} else {
			// no branching: single query of one single-pipe chain
			Chain ch = new Chain(pipe);
			qh.addQuery(ch, 0 == delta ? ch.vs.getAverageDelta() : delta);
			// just add a single-pipe chain for each ref pipe
			chains_ref = new ArrayList<Chain>();
			for (ZDisplayable zd : pipes_ref) {
				chains_ref.add(new Chain((Line3D)zd));
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
			qh.sortMatches(new ChainMatchComparator(distance_type));
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
		return Bureaucrat.createAndStart(worker, p);
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
		return createPipeChains(root_pt, ls, null);
	}

	static public ArrayList<Chain> createPipeChains(final ProjectThing root_pt, final LayerSet ls, String regex_exclude) throws Exception {
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		Pattern exclude = null;
		if (null != regex_exclude) {
			exclude = Pattern.compile(regex_exclude, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		}
		appendAndFork(root_pt, null, null, chains, ls, exclude);
		return chains;
	}

	/** Recursive. */
	static private void appendAndFork(final ProjectThing parent, Chain chain, HashSet<ProjectThing> hs_c_done, final ArrayList<Chain> chains, final LayerSet ls, final Pattern exclude) throws Exception {

		if (null != exclude && exclude.matcher(parent.getTitle()).matches()) {
			Utils.logAll("Excluding node " + parent + " with title " + parent.getTitle() + ", and all its children nodes.");
			return;
		}

		final ArrayList children = parent.getChildren();
		if (null == children) return;

		if (null == hs_c_done) hs_c_done = new HashSet<ProjectThing>();

		for (Iterator it = children.iterator(); it.hasNext(); ) {
			ProjectThing child = (ProjectThing)it.next();
			if (hs_c_done.contains(child)) continue;
			hs_c_done.add(child);

			if (child.getObject() instanceof Line3D) {
				Line3D pipe = (Line3D)child.getObject();
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
					ArrayList child_pipes = c.findChildrenOfType(Line3D.class);
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
						appendAndFork(c, ca, hs_c_done, chains, ls, exclude);
					}
				}
				// pipe wrapping ProjectThing objects cannot have any children
				continue;
			}

			// if it does not have direct pipe children, cut chain - but keep inspecting
			if (0 == child.findChildrenOfType(Line3D.class).size()) {
				chain = null;
			}

			// inspect others down the unvisited tree nodes
			appendAndFork(child, chain, hs_c_done, chains, ls, exclude);
		}
	}

	/** Represents a list of concatenated pipes, where each pipe is parent of the next within the ProjectTree. */
	static public class Chain {
		final ArrayList<Line3D> pipes = new ArrayList<Line3D>();
		public VectorString3D vs; // the complete path of chained pipes
		private Chain() {}
		public Chain(Line3D root) {
			this.pipes.add(root);
			this.vs = root.asVectorString3D();
		}
		final public void append(Line3D p) throws Exception {
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
			StringBuffer sb = new StringBuffer("len: ");
			sb.append(pipes.size()).append("   ");
			for (Line3D p : pipes) sb.append('#').append(p.getId()).append(' ');
			return sb.toString();
		}
		final public String getTitle() {
			final StringBuffer sb = new StringBuffer(pipes.get(0).getProject().getTitle());
			sb.append(' ');
			for (Line3D p : pipes) sb.append(' ').append('#').append(p.getId());
			return sb.toString();
		}
		final public String getCellTitle() {
			Line3D root = pipes.get(0);
			String mt = root.getProject().getShortMeaningfulTitle((ZDisplayable)root);
			if (1 == pipes.size()) return mt;
			//else, chain the ids of the rest
			final StringBuffer sb = new StringBuffer(mt);
			for (int i=1; i<pipes.size(); i++) sb.append(' ').append('#').append(pipes.get(i).getId());
			return sb.toString();
		}
		/** Returns max 10 chars, solely the name of the parent's parent node of the root pipe (aka the [lineage] containing the [branch]) or the id if too long. Intended for the 10-digit limitation in the problem in .dis files for Phylip. */
		final public String getShortCellTitle() {
			Line3D root = pipes.get(0);
			ProjectThing pt = root.getProject().findProjectThing((ZDisplayable)root);
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
		final public Line3D getRoot() {
			return pipes.get(0);
		}
		/** Show centered, set visible and select. */
		final public void showCentered2D(boolean shift_down) {
			Rectangle b = null;
			Display display = Display.getFront();
			for (Line3D line3d : pipes) {
				ZDisplayable p = (ZDisplayable)line3d;
				if (null == b) b = p.getBoundingBox();
				else b.add(p.getBoundingBox());
				p.setVisible(true);
				display.select(p, shift_down);
			}
			display.select((ZDisplayable)pipes.get(0), shift_down); // the root as active
			display.getCanvas().showCentered(b);
		}
	}


	/** Contains all que query chains created from the single pipe selected for matching against another reference project, their matches with the chains made from the reference project, and some general data such as the transforms and the axes. */
	static private class QueryHolder {

		final ArrayList<Chain> queries = new ArrayList<Chain>();

		Calibration cal1;
		Calibration cal2;
		// The transfer transform: from query space to reference space
		Transform3D T;

		final Hashtable<Chain,ArrayList<ChainMatch>> matches = new Hashtable<Chain,ArrayList<ChainMatch>>();

		VectorString3D[] vs_axes = null,
			         vs_axes_ref = null;

		boolean relative = false;

		// For Moving Least Squares:
		ArrayList<Tuple3d> so, ta;
		Class model_class;

		// these chains are kept only calibrated, NOT transformed. Because each query will resample it to its own delta, and then transform it.
		final ArrayList<Chain> chains_ref = new ArrayList<Chain>();

		/** Constructor for Transform3D computed from the mushroom body axes. */
		QueryHolder(Calibration cal1, Calibration cal2, Transform3D T) {
			this.cal1 = cal1;
			this.cal2 = cal2;
			this.T = T;
		}

		/** Moving Least Squares constructor. */
		QueryHolder(Calibration cal1, Calibration cal2, Map<String,Tuple3d> source, Map<String,Tuple3d> target, Class model_class) throws Exception {
			this.cal1 = cal1;
			this.cal2 = cal2;
			this.T = null; // not used
			this.so = new ArrayList<Tuple3d>();
			this.ta = new ArrayList<Tuple3d>();
			if (!extractMatches(source, target, so, ta)) throw new Exception("No matches found between fiducial sets!");
			this.model_class = model_class;
		}

		/** Will calibrate and transform the chain's VectorString3D. */
		final void addQuery(final Chain chain, final double delta) throws Exception {
			VectorString3D vs = chain.vs;
			// Order is important:
			// 1 - calibrate: bring to user-space microns, whatever
			if (null != cal1) vs.calibrate(cal1);
			// 2 - transform into reference
			if (null != T) vs.transform(T);
			else if (null != so && null != ta) {
				ArrayList<VectorString3D> lvs = new ArrayList<VectorString3D>();
				lvs.add(vs);
				chain.vs = transferVectorStrings(lvs, so, ta, model_class).get(0);
				vs = chain.vs; // changed pointer
				if (null != cal2) chain.vs.setCalibration(cal2); // set, not apply. The MLS transform brought the query into the reference space.
				                  // this step is not strictly necessary, it's just for completeness.
			}
			// 3 - resample, within reference space
			vs.resample(delta);

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

		/** Returns a resampled and transformed copy of the pipe's VectorString3D. */
		final VectorString3D makeVS2(final Chain ref, final double delta) {
			return asVS2((VectorString3D)ref.vs.clone(), delta);
		}
		/** Returns a resampled and transformed copy of the pipe's VectorString3D. */
		final VectorString3D makeVS2(final Line3D ref, final double delta) {
			return asVS2(ref.asVectorString3D(), delta);
		}

		final private VectorString3D asVS2(final VectorString3D vs, final double delta) {
			if (null != cal2 && !vs.isCalibrated()) vs.calibrate(cal2);
			vs.resample(delta);
			return vs;
		}

		/** Bring Chain q (query) to reference space. */
		final VectorString3D makeVS1(final Chain q, final double delta) throws Exception {
			return asVS1((VectorString3D)q.vs.clone(), delta);
		}
		/** Bring Chain q (query) to reference space. Will resample after transforming it, to its own average delta. */
		final VectorString3D makeVS1(final Chain q)  throws Exception {
			return asVS1((VectorString3D)q.vs.clone(), 0);
		}

		/** Returns a resampled and transformed copy of the pipe's VectorString3D. */
		final VectorString3D makeVS1(final Line3D q, final double delta) throws Exception {
			return asVS1(q.asVectorString3D(), delta);
		}

		final private VectorString3D asVS1(VectorString3D vs, final double delta) throws Exception {
			if (null != cal1 && !vs.isCalibrated()) vs.calibrate(cal1);
			//Utils.log2("VS1: Length before transforming: " + vs.computeLength());
			if (null != T) vs.transform(T);
			else if (null != so && null != ta) {
				ArrayList<VectorString3D> lvs = new ArrayList<VectorString3D>();
				lvs.add(vs);
				vs = transferVectorStrings(lvs, so, ta, model_class).get(0);
				vs.setCalibration(cal2); // the MLS transform brought vs into reference space, hence cal2.
			}
			//Utils.log2("VS1: Length after transforming: " + vs.computeLength());
			// Resample after transforming, of course!
			vs.resample(0 == delta ? vs.getAverageDelta() : delta);
			return vs;
		}

		/** For each list of matches corresponding to each query chain, sort the matches by physical distance. */
		void sortMatches(Comparator comp) {
			for (ArrayList<ChainMatch> list : matches.values()) {
				Collections.sort(list, comp);
			}
		}
		/** Sort first by distance_type_1, then pick the best score, double it, and resort all that remain by distance_type_2. */
		void sortMatches(int distance_type_1, int distance_type_2, int min_matches) {
			for (Map.Entry<Chain,ArrayList<ChainMatch>> e : matches.entrySet()) {
				Chain query = e.getKey();
				List<ChainMatch> list = e.getValue();
				Compare.sortMatches(list, distance_type_1, distance_type_2, min_matches);
			}
		}

		/** Returns all pipes involved in the query chains. */
		HashSet<Line3D> getAllQueriedLine3Ds() {
			final HashSet<Line3D> hs = new HashSet<Line3D>();
			for (Chain c : queries) {
				hs.addAll(c.pipes);
			}
			return hs;
		}
		Hashtable<Line3D,VectorString3D> getAllQueried() throws Exception {
			Hashtable<Line3D,VectorString3D> ht = new Hashtable<Line3D,VectorString3D>();
			for (Chain c : queries) {
				double delta = c.vs.getDelta();
				for (Line3D p : c.pipes) {
					VectorString3D vs = p.asVectorString3D();
					if (null != cal1) vs.calibrate(cal1);
					if (null != T) vs.transform(T); // to reference space
					else if (null != so && null != ta) {
						ArrayList<VectorString3D> lvs = new ArrayList<VectorString3D>();
						lvs.add(vs);
						vs = transferVectorStrings(lvs, so, ta, model_class).get(0);
						if (null != cal2) vs.setCalibration(cal2); // the MLS transform brought vs into reference space, hence cal2.
					}
					vs.resample(delta);
					ht.put(p, vs);
				}
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

		void remove(final Displayable d) {
			// from the queries (and thus the tabs as well)
			for (Iterator<Chain> i = queries.iterator(); i.hasNext(); ) {
				Chain chain = i.next();
				if (chain.pipes.contains(d)) {
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
			Utils.updateComponent(frame);
		}

		/** Remove all queries and refs that belong to the given project. */
		void remove(Project project) {
			// from the queries (and thus the tabs as well)
			for (Iterator<Chain> i = queries.iterator(); i.hasNext(); ) {
				Chain chain = i.next();
				if (chain.pipes.get(0).getProject() == project) {
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
					if (i.next().ref.pipes.get(0).getProject() == project) {
						i.remove();
					}
				}
			}
			Utils.updateComponent(frame);
		}
	}

	/** Sort and crop matches list to min_number or the appropriate number of entries. */
	static private void sortMatches(final List<ChainMatch> list, final int distance_type_1, final int distance_type_2, final int min_number) {

		final boolean debug = false;

		// -1  - If combined score indices, ok:
		if (COMBINED_SCORE_INDICES == distance_type_1) {
			// Compute indices for each parameter
			

			final int[] params = new int[]{LEVENSHTEIN, AVG_PHYS_DIST, CUM_PHYST_DIST, STD_DEV, PROXIMITY}; // DISSIMILARITY distorts all badly

			// An array with items in the same order as argument ChainMatch list.
			final float[] indices = new float[list.size()];

			// debug: store them all
			Hashtable<ChainMatch,ArrayList<Integer>> ind = null;
			if (debug) ind = new Hashtable<ChainMatch,ArrayList<Integer>>();

			for (int i = 0; i<params.length; i++) {
				// copy list
				ArrayList<ChainMatch> li = new ArrayList<ChainMatch>(list);
				// sort
				Collections.sort(li, new ChainMatchComparator(params[i]));
				// Assign index to each
				for (int k=0; k<indices.length;  k++) {
					ChainMatch cm = list.get(k);
					int index = li.indexOf(cm);
					indices[k] += index;

					// debug:
					if (debug) {
						ArrayList<Integer> al = ind.get(cm);
						if (null == al) {
							al = new ArrayList<Integer>();
							ind.put(cm, al);
						}
						al.add(index);
					}
				}
			}

			ChainMatch[] cm = list.toArray(new ChainMatch[0]);
			M.quicksort(indices, cm);

			list.clear();
			for (int i=0; i<cm.length; i++) list.add(cm[i]);

			if (debug) {
				// Debug: print first 10
				for (int i=0; i<10 && i<cm.length; i++) {
					if (null != cm[i].ref) Utils.log2(((Pipe)cm[i].ref.getRoot()).getProject().getShortMeaningfulTitle((Pipe)cm[i].ref.getRoot()) + "     " + indices[i]);
					Utils.log2("     " + Utils.toString(ind.get(cm[i])));
				}
			}

			// don't re-sort
			return;
		}


		// 0 - Sort by first distance type:
		Collections.sort(list, new ChainMatchComparator(distance_type_1));
		// 9 == None
		if (9 == distance_type_2) {
			// Do not re-sort.
			return;
		}

		// 1 - take top score and scale it to set a roof
		double roof = list.get(0).phys_dist * 1.5;
		// 3 - Filter all values up to the roof, allowing for at least min_number entries to be left in the list:
		int count = 0;
		for (Iterator<ChainMatch> it = list.iterator(); it.hasNext(); ) {
			ChainMatch cm = it.next();
			count++;
			if (cm.phys_dist > roof && count > min_number) {
				it.remove();
			}
		}
		// 4 - Resort remaining matches by second distance type:
		Collections.sort(list, new ChainMatchComparator(distance_type_2));
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
		double proximity_mut; // unitless value: cummulative distance of only mutation pairs relative to query sequence length  ## TODO not unitless, this is the same as the average
		double tortuosity_ratio;

		String title = null;


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
			this.tortuosity_ratio = stats[9];
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
		/** Sort by the given distance type. */
		final int distance_type;
		ChainMatchComparator(final int distance_type) {
			this.distance_type = distance_type;
		}
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
			//
			double val = 0;

			switch (distance_type) {
				case COMBINED:
					val = cm1.score - cm2.score; // inverse, larger is better. All others smaller is better.
					break;
				case LEVENSHTEIN: // Levenshtein
					val = cm2.ed.getDistance() - cm1.ed.getDistance();
					break;
				case DISSIMILARITY: // Dissimilarity
					val = cm1.seq_sim - cm2.seq_sim; // INVERTED ORDER because each would need an inversion: 1 - cmX.seq_sim
					break;
				case AVG_PHYS_DIST: // average physical distance between mutation pairs only
					val = cm2.phys_dist - cm1.phys_dist;
					break;
				case MEDIAN_PHYS_DIST: // median physical distance between all pairs
					val = cm2.median - cm1.median;
					break;
				case CUM_PHYST_DIST: // cummulative physical distance between all pairs
					val = cm2.cum_phys_dist - cm1.cum_phys_dist;
					break;
				case STD_DEV: // stdDev of distances between mutation pairs only
					val = cm2.stdDev - cm1.stdDev;
					break;
				case PROXIMITY: // cummulative distance relative to largest physical length of the two sequences
					val = cm2.proximity - cm1.proximity;
					break;
				case PROXIMITY_MUT: // cummulative distance of mutation pairs relative to largest physical length of the two sequences
					val = cm2.proximity_mut - cm1.proximity_mut;
					break;
			}

			if (val > 0) return -1;
			if (val < 0) return 1;
			return 0; // same
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
			Line3D pipe = qh.getAllQueriedLine3Ds().iterator().next();
			LayerSet ls = pipe.getLayerSet();
			Calibration cal1 = ls.getCalibration();
			this.common = new LayerSet(pipe.getProject(), pipe.getProject().getLoader().getNextId(), "Common", 10, 10, 0, 0, 0, ls.getLayerWidth() * cal1.pixelWidth, ls.getLayerHeight() * cal1.pixelHeight, false, 2, new AffineTransform());
			Calibration cal = new Calibration();
			cal.setUnit(cal1.getUnit()); // homogeneous on all axes
			this.common.setCalibration(cal);
		}
		/** Shows the matched chain in 3D. */
		public void show3D(Chain query, Chain ref) throws Exception {
			reset();
			if (!query_shows) showAxesAndQueried();
			VectorString3D vs = qh.makeVS2(ref, query.vs.getDelta()); // was: makeVS
			// The LayerSet is that of the Line3D being queried, not the given pipe to show which belongs to the reference project (and thus not to the queried pipe project)
			String title = ref.getCellTitle();
			if (Display3D.contains(common, title)) return;
			Display3D.addMesh(common, vs, title, ref.getColor());
		}
		public void showFull3D(Chain query, Chain ref) throws Exception {
			reset();
			if (!query_shows) {
				showAxes(query.getRoot().getColor());
				showNode3D(query, true);
			}
			showNode3D(ref, false);
		}
		public void showNearby(Chain query) throws Exception {
			GenericDialog gd = new GenericDialog("Distance");
			Calibration cal = query.vs.getCalibrationCopy();
			gd.addNumericField("radius (" + (null != cal ? cal.getUnits() : "pixels") + "):", 5, 1);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final double radius = gd.getNextNumber();
			if (Double.isNaN(radius) || radius <= 0) {
				Utils.log("Cannot use radius: " + radius);
				return;
			}
			reset();
			if (!query_shows) showAxesAndQueried();
			ArrayList<ChainMatch> matches = qh.matches.get(query);
			final VectorString3D vs_query = qh.makeVS1(query);
			for (ChainMatch match : matches) {
				VectorString3D vs_ref = qh.makeVS2(match.ref, query.vs.getDelta());
				if (vs_query.isNear(vs_ref, radius)) {
					Display3D.addMesh(common, vs_ref, match.ref.getCellTitle(), match.ref.getColor());
				}
			}
		}
		void showNode3D(Chain chain, boolean as_query) throws Exception {
			Line3D root = chain.getRoot();
			ProjectThing pt = (ProjectThing)root.getProject().findProjectThing((ZDisplayable)root).getParent();
			HashSet hs = pt.findChildrenOfTypeR(Line3D.class);
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				Line3D p = (Line3D)((ProjectThing)it.next()).getObject();
				String title = p.getProject().getShortMeaningfulTitle((ZDisplayable)p);
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
		public void showAxesAndQueried() throws Exception {
			reset();
			Color qcolor = null;
			final Hashtable<Line3D,VectorString3D> queried = qh.getAllQueried();
			for (Iterator it = queried.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				ZDisplayable p = (ZDisplayable)entry.getKey();
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
		public void showInterpolated(Editions ed, Chain query, Chain ref) throws Exception {
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
		return findBestMatch(vs1, vs2, delta, skip_ends, max_mut, min_chunk, COMBINED, direct, substring_matching, 1, 1, 1);
	}

	static protected final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, final int distance_type, final boolean direct, final boolean substring_matching, final double wi, final double wd, final double wm) {

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
					              matchDirect(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm)
						    : matchFwdRev(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm);
				if (null == best) best = ob;
				else if (((Double)ob[1]).doubleValue() > ((Double)best[1]).doubleValue()) best = ob;
			}
			return best;
		} else {
			if (direct) {
				return matchDirect(vs1, vs2, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm);
			} else {
				return matchFwdRev(vs1, vs2, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm);
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
	static public final int COMBINED_SCORE_INDICES = 10;

	static private final String[] distance_types = {"Levenshtein", "Dissimilarity", "Average physical distance", "Median physical distance", "Cummulative physical distance", "Standard deviation of correspondences only", "Combined SLM", "Proximity", "Proximity of mutation pairs", "Standard deviation of all pairs", "Combined score indices"};

	// Weights as empirically approximated with some lineages, with S. Preibisch ( see Test_Scoring.java )
	// Old: from 3D affine transform registration from 4 points
	// static public final double[] W = new double[]{1.3345290383577453, -0.0012626693452889859, -0.012764729437173508, -0.13344076489951817};
	// New 20090809: from 8-point moving least squares registration:
	static public final double[] W = new double[]{0.3238955445631255, -0.001738441643315311, -0.03506078734289302, 0.7148869480636044};

	static public final double score(final double seq_sim, final double levenshtein, final double median_phys_dist, final double[] w) {
		//       S                L                    M
		return seq_sim * w[0] + levenshtein * w[1] + median_phys_dist * w[2] + w[3];
	}

	/** Zero is best; gets bad towards positive infinite -- except for COMBINED, where the larger the better. */
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

	static private final Object[] matchDirect(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, int distance_type, final double wi, final double wd, final double wm) {
		// Levenshtein is unfortunately not commutative: must try both
		final Editions ed1 = new Editions(vs1, vs2, delta, false, wi, wd, wm);
		double score1 = getScore(ed1, skip_ends, max_mut, min_chunk, distance_type);
		final Editions ed2 = new Editions(vs2, vs1, delta, false, wi, wd, wm);
		double score2 = getScore(ed2, skip_ends, max_mut, min_chunk, distance_type);
		return score1 < score2 ?
			new Object[]{ed1, score1}
		      : new Object[]{ed2, score2};
	}

	// Match in all possible ways
	static private final Object[] matchFwdRev(final VectorString3D vs1, final VectorString3D vs2, double delta, boolean skip_ends, int max_mut, float min_chunk, int distance_type, final double wi, final double wd, final double wm) {

		final VectorString3D vs1rev = vs1.makeReversedCopy();
		final VectorString3D vs2rev = vs2.makeReversedCopy();

		final Editions[] ed = new Editions[4];

		// vs1 vs2
		ed[0] = new Editions(vs1, vs2, delta, false, wi, wd, wm);
		// vs1rev vs2rev
		ed[1] = new Editions(vs1rev, vs2rev, delta, false, wi, wd, wm);
		// vs1 vs2rev
		ed[2] = new Editions(vs1, vs2rev, delta, false, wi, wd, wm);
		// vs1rev vs2
		ed[3] = new Editions(vs1rev, vs2, delta, false, wi, wd, wm);

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
	static public final Bureaucrat findSimilar(final Line3D pipe, final Project[] ref, final boolean ignore_orientation, final boolean ignore_calibration, final boolean mirror, final boolean chain_branches, final boolean show_gui) {
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

		final QueryHolder qh = new QueryHolder(cal, null, null);
		ArrayList<Chain> chains_ref = new ArrayList<Chain>();

		final ArrayList<VectorString3D> reversed_queries = new ArrayList<VectorString3D>();

		if (chain_branches) {
			// collect the full set of ref pipes from each project
			for (int i=0; i<ref.length; i++) {
				for (Chain c : createPipeChains(ref[i].getRootProjectThing(), ref[i].getRootLayerSet())) {
					if (c.getRoot().equals(pipe)) continue; // skip!
					chains_ref.add(c);
				}
			}
			// add all possible query chains, starting at the parent of the chosen pipe
			for (Chain chain : createPipeChains((ProjectThing)pipe.getProject().findProjectThing(pipe).getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain, chain.vs.getAverageDelta()); // calibrates it
				if (ignore_orientation) {
					if (mirror) chain.vs.mirror(VectorString3D.X_AXIS);
					chain.vs.relative();
				}
				reversed_queries.add(chain.vs.makeReversedCopy());
			}
		} else {
			// no branching: single query of one single-pipe chain
			Chain chain = new Chain(pipe);
			qh.addQuery(chain, chain.vs.getAverageDelta()); // calibrates it
			reversed_queries.add(chain.vs.makeReversedCopy());
			for (int i=0; i<ref.length; i++) {
				for (ZDisplayable zd : ref[i].getRootLayerSet().getZDisplayables(Line3D.class, true)) {
					if (zd == pipe) continue; // skip!
					chains_ref.add(new Chain((Line3D)zd));
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
			qh.sortMatches(ignore_orientation ? new ChainMatchComparatorSim() : new ChainMatchComparator(MEDIAN_PHYS_DIST));
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
		return Bureaucrat.createAndStart(worker, p);
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
			frame = ControlWindow.createJFrame("Comparator");
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
				case 5: return null != qh.cal2 ? "Median (" + qh.cal2.getUnits() + ")" : "Median";
				case 6: return null != qh.cal2 ? "Avg Dist (" + qh.cal2.getUnits() + ")" : "Avg Dist";
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
				case 4: return Utils.cutNumber(cm.get(row).ed.getDistance(), 2); // Levenhtein
				case 5: return Utils.cutNumber(cm.get(row).median, 2);
				case 6: return Utils.cutNumber(cm.get(row).phys_dist, 2); // average
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
			if (-1 == row) return;
			final Chain ref = model.cm.get(row).ref;

			if (2 == me.getClickCount()) {
				try {
					if (me.isShiftDown()) {
						model.vis.show3D(model.query, ref);
					} else {
						ref.showCentered2D(false);
					}
				} catch (Exception e) {
					IJError.print(e);
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
						try {
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
						} catch (Exception e) { IJError.print(e); }
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

	/** Compare all to all parameters. */
	static public class CATAParameters {
		public double delta = 1;
		public boolean skip_ends = false;
		public int max_mut = 5;
		public float min_chunk = 0.5f;
		public boolean score_mut_only = false;
		public int transform_type = 3;
		public boolean chain_branches = true;
		public final String[][] presets = {{"medial lobe", "dorsal lobe", "peduncle"}};
		public String[] preset = presets[0];
		public final String[] preset_names = new String[]{"X - 'medial lobe', Y - 'dorsal lobe', Z - 'peduncle'"};
		public final String[] formats = {"ggobi XML", ".csv", "Phylip .dis"};
		public String format = formats[2];
		public int distance_type = 2;
		public int distance_type_2 = 9;
		public int min_matches = 10;
		public boolean normalize = false;
		public boolean direct = true;
		public boolean substring_matching = false;
		public String regex = "";
		public boolean with_source = false;
		public double plot_max_x = 270, plot_max_y = 30;
		public int plot_width = 700, plot_height = 400;
		public boolean cut_uneven_ends = true;
		public int envelope_type = 2;

		public CATAParameters() {}

		public boolean setup(final boolean to_file, final String regex, final boolean plot, final boolean condense) {
			final GenericDialog gd = new GenericDialog("All to all");
			gd.addMessage("Choose a point interdistance to resample to, or 0 for the average of all.");
			gd.addNumericField("point_interdistance: ", delta, 2);
			gd.addCheckbox("skip insertion/deletion strings at ends when scoring", skip_ends);
			gd.addNumericField("maximum_ignorable consecutive muts in endings: ", max_mut, 0);
			gd.addNumericField("minimum_percentage that must remain: ", min_chunk, 2);
			gd.addCheckbox("Score mutations only", score_mut_only);
			Utils.addEnablerListener((Checkbox)gd.getCheckboxes().get(0), new Component[]{(Component)gd.getNumericFields().get(0), (Component)gd.getNumericFields().get(1)}, null);

			final String[] transforms = {"translate and rotate",
						     "translate, rotate and scale",
						     "translate, rotate, scale and shear",
						     "moving least squares",
						     "relative",
						     "direct"};
			gd.addChoice("Transform_type: ", transforms, transforms[transform_type]);
			gd.addCheckbox("Chain_branches", chain_branches);

			gd.addChoice("Presets: ", preset_names, preset_names[0]);
			gd.addMessage("");
			gd.addChoice("Scoring type: ", distance_types, distance_types[distance_type]);
			final String[] distance_types2 = {"Levenshtein", "Dissimilarity", "Average physical distance", "Median physical distance", "Cummulative physical distance", "Standard deviation", "Combined SLM", "Proximity", "Proximity of mutation pairs", "None"}; // CAREFUL when adding more entries: index 9 is used as None for sortMatches and as a conditional.
			gd.addChoice("Resort scores by: ", distance_types2, distance_types2[distance_type_2]);
			gd.addNumericField("Min_matches: ", min_matches, 0);
			if (to_file) {
				gd.addChoice("File format: ", formats, formats[2]);
			}
			gd.addCheckbox("normalize", normalize);
			gd.addCheckbox("direct", direct);
			gd.addCheckbox("substring_matching", substring_matching);
			gd.addStringField("regex: ", null != regex ? regex : "");
			if (plot) {
				gd.addNumericField("plot_width: ", plot_width, 0);
				gd.addNumericField("plot_height: ", plot_height, 0);
				gd.addNumericField("plot_max_x: ", plot_max_x, 2);
				gd.addNumericField("plot_max_y: ", plot_max_y, 2);
			}
			if (condense) {
				gd.addCheckbox("cut_uneven_ends", cut_uneven_ends);
				final String[] env = {"1 std dev", "2 std dev", "3 std dev", "average", "maximum"};
				gd.addChoice("envelope", env, env[envelope_type]);
			}

			//////

			gd.showDialog();
			if (gd.wasCanceled()) return false;

			delta = gd.getNextNumber();
			skip_ends = gd.getNextBoolean();
			max_mut = (int)gd.getNextNumber();
			min_chunk = (float)gd.getNextNumber();
			score_mut_only = gd.getNextBoolean();
			if (skip_ends) {
				if (max_mut < 0) max_mut = 0;
				if (min_chunk <= 0) skip_ends = false;
				if (min_chunk > 1) min_chunk = 1;
			}
			transform_type = gd.getNextChoiceIndex();
			chain_branches = gd.getNextBoolean();
			preset = presets[gd.getNextChoiceIndex()];

			distance_type = gd.getNextChoiceIndex();
			distance_type_2 = gd.getNextChoiceIndex();
			min_matches = (int) gd.getNextNumber();
			if (min_matches < 0) {
				Utils.log("Using 0 min_matches!");
				min_matches = 0;
			}

			format = formats[0];
			if (to_file) format = gd.getNextChoice().trim();

			normalize = gd.getNextBoolean();
			direct = gd.getNextBoolean();
			substring_matching = gd.getNextBoolean();

			this.regex = gd.getNextString();
			if (0 == this.regex.length()) this.regex = null;

			if (plot) {
				plot_width = (int)gd.getNextNumber();
				plot_height = (int)gd.getNextNumber();
				plot_max_x = gd.getNextNumber();
				plot_max_y = gd.getNextNumber();
			}
			if (condense) {
				cut_uneven_ends = gd.getNextBoolean();
				envelope_type = gd.getNextChoiceIndex();
			}

			return true;
		}
	}

	static public final Object[] gatherChains(final Project[] p, final CATAParameters cp) throws Exception {
		return gatherChains(p, cp, null);
	}

	/** Gather chains for all projects considering the cp.regex, and transforms all relative to the reference Project p[0].
	 *  Will ignore any for which a match exists in @param ignore. */
	static public final Object[] gatherChains(final Project[] p, final CATAParameters cp, final String[] ignore) throws Exception {
		String regex_exclude = null;
		if (null != ignore) {
			StringBuilder sb = new StringBuilder();
			for (String ig : ignore) {
				sb.append(ig).append('|');
			}
			sb.setLength(sb.length() -1);
			regex_exclude = sb.toString();
		}
		// gather all chains
		final ArrayList[] p_chains = new ArrayList[p.length]; // to keep track of each project's chains
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		for (int i=0; i<p.length; i++) { // for each project:
			if (null == cp.regex) {
				p_chains[i] = createPipeChains(p[i].getRootProjectThing(), p[i].getRootLayerSet(), regex_exclude);
			} else {
				// Search (shallow) for cp.regex matches
				for (ProjectThing pt : p[i].getRootProjectThing().findChildren(cp.regex, true)) {
					final ArrayList<Chain> ac = createPipeChains(pt, p[i].getRootLayerSet(), regex_exclude);
					if (null == p_chains[i]) p_chains[i] = ac;
					else p_chains[i].addAll(ac);
				}
				if (null == p_chains[i]) p_chains[i] = new ArrayList<Chain>(); // empty
			}
			chains.addAll(p_chains[i]);
			// calibrate
			final Calibration cal = p[i].getRootLayerSet().getCalibrationCopy();
			for (Chain chain : (ArrayList<Chain>)p_chains[i]) chain.vs.calibrate(cal);
		}
		final int n_chains = chains.size();

		// register all, or relative
		if (4 == cp.transform_type) {
			// '4' means relative
			// compute global average delta
			if (0 == cp.delta) {
				for (Chain chain : chains) {
					cp.delta += ( chain.vs.getAverageDelta() / n_chains );
				}
			}
			Utils.log2("Using delta: " + cp.delta);

			for (Chain chain : chains) {
				chain.vs.resample(cp.delta, cp.with_source); // BEFORE making it relative
				chain.vs.relative();
			}
		} else {
			if (3 == cp.transform_type) {
				// '3' means moving least squares computed from 3D landmarks
				Utils.log2("Moving Least Squares Registration based on common fiducial points");
				// Find fiducial points
				HashMap<Project,Map<String,Tuple3d>> fiducials = new HashMap<Project,Map<String,Tuple3d>>();
				for (Project pr : p) {
					Set<ProjectThing> fids = pr.getRootProjectThing().findChildrenOfTypeR("fiducial_points");
					if (null == fids || 0 == fids.size()) {
						Utils.log2("No fiducial points found in project: " + pr);
						return null;
					}
					fiducials.put(pr, Compare.extractPoints(fids.iterator().next())); // the first fiducial group
				}
				// Register all VectorString3D relative to the first project:
				final List<VectorString3D> lvs = new ArrayList<VectorString3D>();
				final Calibration cal2 = p[0].getRootLayerSet().getCalibrationCopy();
				for (Chain chain : chains) {
					Project pr = chain.pipes.get(0).getProject();
					if (pr == p[0]) continue; // first project is reference, no need to transform.
					lvs.clear();
					lvs.add(chain.vs);
					chain.vs = transferVectorStrings(lvs, fiducials.get(pr), fiducials.get(p[0])).get(0);
					// Set (but do not apply!) the calibration of the reference project
					chain.vs.setCalibration(cal2);
				}
			} else if (cp.transform_type < 3) {
				// '0', '1' and '2' involve a 3D affine computed from the 3 axes
				// no need //VectorString3D[][] vs_axes = new VectorString3D[p.length][];
				Vector3d[][] o = new Vector3d[p.length][];
				for (int i=0; i<p.length; i++) {
					// 1 - find pipes to work as axes for each project
					ArrayList<ZDisplayable> pipes = p[i].getRootLayerSet().getZDisplayables(Line3D.class, true);
					String[] pipe_names = new String[pipes.size()];
					for (int k=0; k<pipes.size(); k++) {
						pipe_names[k] = p[i].getMeaningfulTitle(pipes.get(k));
					}
					int[] s = findFirstXYZAxes(cp.preset, pipes, pipe_names);

					// if axes are -1, forget it: not found
					if (-1 == s[0] || -1 == s[1] || -1 == s[2]) {
						Utils.log("Can't find axes for project " + p[i]);
						o = null;
						return null;
					}

					// obtain axes and origin
					Object[] pack = obtainOrigin(new Line3D[]{(Line3D)pipes.get(s[0]),
									     (Line3D)pipes.get(s[1]),
									     (Line3D)pipes.get(s[2])},
									     cp.transform_type,
									     o[0]); // will be null for the first, which will then be non-null and act as the reference for the others.

					// no need //vs_axes[i] = (VectorString3D[])pack[0];
					o[i] = (Vector3d[])pack[1];
				}
				/* // OLD WAY
				// match the scales to make the largest be 1.0
				final double scaling_factor = VectorString3D.matchOrigins(o, transform_type);
				Utils.log2("matchOrigins scaling factor: " + scaling_factor + " for transform_type " + transform_type);
				*/
				// transform all except the first (which acts as reference)
				final Transform3D M_ref = Compare.createTransform(o[0]);
				for (int i=1; i<p.length; i++) {
					Vector3d trans = new Vector3d(-o[i][3].x, -o[i][3].y, -o[i][3].z);
					final Transform3D M_query = Compare.createTransform(o[i]);
					// The transfer T transform: from query space to reference space.
					final Transform3D T = new Transform3D(M_ref);
					T.mulInverse(M_query);
					for (Chain chain : (ArrayList<Chain>)p_chains[i]) {
						chain.vs.transform(T); // in place
					}
				}
			}
			// else, direct

			// compute global average delta, after correcting calibration and transformation
			if (0 == cp.delta) {
				for (Chain chain : chains) {
					cp.delta += ( chain.vs.getAverageDelta() / n_chains );
				}
			}
			Utils.log2("Using delta: " + cp.delta);

			// After calibration and transformation, resample all to the same delta
			for (Chain chain : chains) chain.vs.resample(cp.delta, cp.with_source);
		}

		return new Object[]{chains, p_chains};
	}

	/** Gets pipes for all open projects, and generates a matrix of dissimilarities, which gets passed on to the Worker thread and also to a file, if desired.
	 *
	 * @param to_file Whether to save the results to a file and popup a save dialog for it or not. In any case the results are stored in the worker's load, which you can retrieve like:
	 *     Bureaucrat bu = Compare.compareAllToAll(true, null, null);
	 *     Object result = bu.getWorker().getResult();
	 *     float[][] scores = (float[][])result[0];
	 *     ArrayList<Compare.Chain> chains = (ArrayList<Compare.Chain>)result[1];
	 *
	 */
	static public Bureaucrat compareAllToAll(final boolean to_file, final String regex, final String[] ignore) {

		// gather all open projects
		final Project[] p = Project.getProjects().toArray(new Project[0]);

		final Worker worker = new Worker("Comparing all to all") {
			public void run() {
				startedWorking();
				try {

		final CATAParameters cp = new CATAParameters();
		if (!cp.setup(to_file, regex, false, false)) {
			finishedWorking();
			return;
		}


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

		Object[] ob = gatherChains(p, cp, ignore);
		final ArrayList<Chain> chains = (ArrayList<Chain>)ob[0];
		final ArrayList[] p_chains = (ArrayList[])ob[1]; // to keep track of each project's chains
		ob = null;
		if (null == chains) {
			finishedWorking();
			return;
		}

		final int n_chains = chains.size();

		// compare all to all
		final VectorString3D[] vs = new VectorString3D[n_chains];
		for (int i=0; i<n_chains; i++) vs[i] = chains.get(i).vs;
		final float[][] scores = Compare.scoreAllToAll(vs, cp.distance_type, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.direct, cp.substring_matching, this);

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
		if (cp.normalize) {
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
		if (cp.format.equals(cp.formats[0])) {
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
		} else if (cp.format.equals(cp.formats[1])) {
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
		} else if (cp.format.equals(cp.formats[2])) {
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
		return Bureaucrat.createAndStart(worker, p);
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

	/** Creates a transform with the 4 given vectors: X, Y, Z and translation of origin. */
	static public Transform3D createTransform(final Vector3d[] o) {
		return new Transform3D(new Matrix4d(
			// X        Y        Z        Trans
			   o[0].x, o[1].x, o[2].x, o[3].x,
			   o[0].y, o[1].y, o[2].y, o[3].y,
			   o[0].z, o[1].z, o[2].z, o[3].z,
			   0,       0,       0,       1));
	}

	/**
	 * @param reference_project If null, then the first one found in the Project.getProjects() lists is used.
	 * @param regex A String (can be null) to filter objects by, to limit what gets processed.
	 *              If regex is not null, then only ProjectThing nodes with the matching regex are analyzed (shallow: none of their children are questioned, but pipes will be built from them all).
	 * @param generate_plots Whether to generate the variability plots at all.
	 * @param show_plots If generate_plots, whether to show the plots in a stack image window or to save them.
	 * @param show_3D Whether to show any 3D data.
	 * @param show_condensed_3D If show_3D, whether to show the condensed vector strings, i.e. the "average" pipes.
	 * @param show_sources_3D If show_3D, whether to show the source pipes from which the condensed vector string was generated.
	 * @param show_envelope_3D If show_3D, whether to generate the variability envelope.
	 * @param envelope_alpha If show_envelope_3D, the envelope takes an alpha value between 0 (total transparency) and 1 (total opacity)
	 * @param show_axes_3D If show_3D, whether to display the reference axes as well.
	 * @param heat_map If show_3D, whether to color the variability with a Fire LUT.
	 *                 If not show_condensed_3D, then the variability is shown in color-coded 3D spheres placed at the entry point to the neuropile.
	 * @param map_condensed If not null, all VectorString3D are put into this map.
	 * */
	static public Bureaucrat variabilityAnalysis(final Project reference_project, final String regex,
			                             final boolean generate_plots, final boolean show_plots,
						     final boolean show_3D, final boolean show_condensed_3D, final boolean show_sources_3D,
						     final boolean show_envelope_3D, final float envelope_alpha,
						     final boolean show_axes_3D, final boolean heat_map,
						     final Map<String,VectorString3D> map_condensed) {
		// gather all open projects
		final Project[] p = Project.getProjects().toArray(new Project[0]);
		// make the reference_project be the first in the array
		if (null != reference_project && reference_project != p[0]) {
			for (int i=0; i<p.length; i++) {
				if (reference_project == p[i]) {
					p[i] = p[0];
					p[0] = reference_project;
					break;
				}
			}
		}

		final Worker worker = new Worker("Comparing all to all") {
			public void run() {
				startedWorking();
				try {

		Utils.log2("Asking for CATAParameters...");

		final CATAParameters cp = new CATAParameters();
		if (!cp.setup(false, regex, true, true)) { // no regex used so far.
			finishedWorking();
			return;
		}
		cp.with_source = true; // so source points are stored in VectorString3D for each resampled and interpolated point

		String plot_dir = null;
		if (generate_plots && !show_plots) {
			// Save plots
			DirectoryChooser dc = new DirectoryChooser("Choose plots directory");
			plot_dir = dc.getDirectory();
			if (null == plot_dir) {
				finishedWorking();
				return;
			}
			if (IJ.isWindows()) plot_dir = plot_dir.replace('\\', '/');
			if (plot_dir.endsWith("/")) plot_dir += "/";
		}

		Utils.log2("Gathering chains...");

		Object[] ob = gatherChains(p, cp); // will transform them as well to the reference found in the first project in the p array
		ArrayList<Chain> chains = (ArrayList<Chain>)ob[0];
		final ArrayList[] p_chains = (ArrayList[])ob[1]; // to keep track of each project's chains
		ob = null;
		if (null == chains) {
			finishedWorking();
			return;
		}

		Utils.log2("Collecting bundles...");

		HashMap<Project,HashMap<String,VectorString3D>> axes = new HashMap<Project,HashMap<String,VectorString3D>>();

		// Sort out into groups by unique names of lineage bundles
		final HashMap<String,ArrayList<Chain>> bundles = new HashMap<String,ArrayList<Chain>>();
		for (Chain chain : chains) {
			String title = chain.getCellTitle();
			final String t = title.toLowerCase();
			// ignore:
			if (-1 != t.indexOf("unknown")) continue;
			if (-1 != t.indexOf("peduncle")
			 || -1 != t.indexOf("medial lobe")
			 || -1 != t.indexOf("dorsal lobe")) {
				Project pr = chain.pipes.get(0).getProject();
				HashMap<String,VectorString3D> m = axes.get(pr);
				if (null == m) {
					m = new HashMap<String,VectorString3D>();
					axes.put(pr, m);
				}
				m.put(t, chain.vs);
				continue;
			}
			if (0 == t.indexOf("lineage") || 0 == t.indexOf("branch")) continue; // unnamed
			if (0 == t.indexOf('[') || 0 == t.indexOf('#')) continue; // unnamed

			// DEBUG:
			//if (! (title.startsWith("DPLd") || title.startsWith("BAmv1")) ) continue;

			Utils.log("Accepting " + title);

			title = title.substring(0, title.indexOf(' '));

			ArrayList<Chain> bc = bundles.get(title); // lineage bundle instance chains
			if (null == bc) {
				bc = new ArrayList<Chain>();
				bundles.put(title, bc);
			}
			bc.add(chain);
		}

		Utils.log2("Found " + bundles.size() + " bundles.");

		chains = null;

		final HashMap<String,VectorString3D> condensed = new HashMap<String,VectorString3D>();

		Utils.log2("Condensing each bundle...");

		// Condense each into a single VectorString3D
		for (Map.Entry<String,ArrayList<Chain>> entry : bundles.entrySet()) {
			ArrayList<Chain> bc = entry.getValue();
			if (bc.size() < 2) {
				Utils.log2("Skipping single: " + entry.getKey());
				continue;
			}
			VectorString3D[] vs = new VectorString3D[bc.size()];
			for (int i=0; i<vs.length; i++) vs[i] = bc.get(i).vs;
			VectorString3D c = condense(cp, vs, this);
			c.setCalibration(p[0].getRootLayerSet().getCalibrationCopy());
			condensed.put(entry.getKey(), c);
			if (this.hasQuitted()) return;
		}

		// Store:
		if (null != map_condensed) {
			map_condensed.putAll(condensed);
		}

		if (generate_plots) {
			Utils.log2("Plotting stdDev for each condensed bundle...");

			// Gather source for each, compute stdDev at each point and make a plot with it
			//  X axis: from first to last point
			//  Y axis: the stdDev at each point, computed from the group of points that contribute to each
			for (Map.Entry<String,VectorString3D> e : condensed.entrySet()) {
				final String name = e.getKey();
				final VectorString3D c = e.getValue();
				final Plot plot = makePlot(cp, name, c);
				//FAILS//plot.addLabel(10, cp.plot_height-5, name); // must be added after setting size
				if (show_plots) plot.show();
				else if (null != plot_dir) new FileSaver(plot.getImagePlus()).saveAsPng(plot_dir + name.replace('/', '-') + ".png");
			}
		}

		if (show_3D) {
			HashMap<String,Color> heat_table = new HashMap<String,Color>();

			if (heat_map || show_envelope_3D) {
				// Create a Fire LUT
				ImagePlus lutimp = new ImagePlus("lut", new ByteProcessor(4,4));
				IJ.run(lutimp, "Fire", "");
				IndexColorModel icm = (IndexColorModel) lutimp.getProcessor().getColorModel();
				byte[] reds = new byte[256];
				byte[] greens = new byte[256];
				byte[] blues = new byte[256];
				icm.getReds(reds);
				icm.getGreens(greens);
				icm.getBlues(blues);

				List<String> names = new ArrayList<String>(bundles.keySet());
				Collections.sort(names);
				for (String name : names) {
					VectorString3D vs_merged = condensed.get(name);
					if (null == vs_merged) {
						Utils.logAll("WARNING could not find a condensed pipe for " + name);
						continue;
					}
					final double[] stdDev = vs_merged.getStdDevAtEachPoint();
					//double avg = 0;
					//for (int i=0; i<stdDev.length; i++) avg += stdDev[i];
					//avg = avg/stdDev.length;
					Arrays.sort(stdDev);
					// The median stdDev should be more representative
					double median = stdDev[stdDev.length/2];

					// scale between 0 and 30 to get a Fire LUT color:
					int index = (int)((median / 30) * 255);
					if (index > 255) index = 255;
					Color color = new Color(0xff & reds[index], 0xff & greens[index], 0xff & blues[index]);

					Utils.log2(new StringBuilder(name).append('\t').append(median)
									  .append('\t').append(reds[index])
									  .append('\t').append(greens[index])
									  .append('\t').append(blues[index]).toString());

					heat_table.put(name, color);
				}
			}

			LayerSet common_ls = new LayerSet(p[0], -1, "Common", 10, 10, 0, 0, 0, 512, 512, false, 2, new AffineTransform());
			final Display3D d3d = Display3D.getDisplay(common_ls);

			float env_alpha = envelope_alpha;
			if (env_alpha < 0) {
				Utils.log2("WARNING envelope_alpha is invalid: " + envelope_alpha + "\n    Using 0.4f instead");
				env_alpha = 0.4f;
			} else if (env_alpha > 1) env_alpha = 1.0f;

			for (String name : bundles.keySet()) {
				ArrayList<Chain> bc = bundles.get(name);
				VectorString3D vs_merged = condensed.get(name);
				if (null == vs_merged) {
					Utils.logAll("WARNING: could not find a condensed vs for " + name);
					continue;
				}
				if (show_sources_3D) {
					for (Chain chain : bc) d3d.addMesh(common_ls, chain.vs, chain.getCellTitle(), Color.gray);
				}
				if (show_condensed_3D) {
					d3d.addMesh(common_ls, vs_merged, name + "-condensed", heat_map ? heat_table.get(name) : Color.red);
				}
				if (show_envelope_3D) {
					d3d.addMesh(common_ls, vs_merged, name + "-envelope", heat_map ? heat_table.get(name) : Color.red, makeEnvelope(cp, vs_merged), env_alpha);
				} else if (heat_map) {
					// Show spheres in place of envelopes, at the starting tip (neuropile entry point)
					double x = vs_merged.getPoints(0)[0];
					double y = vs_merged.getPoints(1)[0];
					double z = vs_merged.getPoints(2)[0];
					double r = 10;

					Color color = heat_table.get(name);
					if (null == color) {
						Utils.logAll("WARNING: heat table does not have a color for " + name);
						continue;
					}

					Content sphere = d3d.getUniverse().addMesh(ij3d.Mesh_Maker.createSphere(x, y, z, r), new Color3f(heat_table.get(name)), name + "-sphere", 1);
				}
			}

			if (show_axes_3D) {
				for (int i=0; i<p.length; i++) {
					for (Map.Entry<String,VectorString3D> e : axes.get(p[i]).entrySet()) {
						d3d.addMesh(common_ls, e.getValue(), e.getKey() + "-" + i, Color.gray);
					}
				}
			}

		}

		Utils.log2("Done.");

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, p[0]);
	}

	static public Plot makePlot(final CATAParameters cp, final String name, final VectorString3D c) {
		final double[] stdDev = c.getStdDevAtEachPoint();
		final double[] index = new double[stdDev.length];
		for (int i=0; i<index.length; i++) index[i] = i;
		Utils.log2("name is " + name);
		Utils.log2("c is " + c);
		Utils.log2("cp is " + cp);
		Utils.log2("stdDev is " + stdDev);
		Utils.log2("c.getCalibrationCopy() is " + c.getCalibrationCopy());
		Utils.log2("c.getDelta() is " + c.getDelta());
		Calibration cal = c.getCalibrationCopy();
		if (null == cal) Utils.log2("WARNING null calibration!");
		final Plot plot = new Plot(name, name + " -- Point index (delta: " + Utils.cutNumber(c.getDelta(), 2) + " " + (null == cal ? "pixels" : cal.getUnits()) + ")", "Std Dev", index, stdDev);
		plot.setLimits(0, cp.plot_max_x, 0, cp.plot_max_y);
		plot.setSize(cp.plot_width, cp.plot_height);
		plot.setLineWidth(2);
		return plot;
	}

	/** From a condensed VectorString3D, create the radius at each point. */
	static public double[] makeEnvelope(final CATAParameters cp, final VectorString3D c) {
		if (cp.envelope_type <= 0) { // defensive programming
			// one std dev
			return c.getStdDevAtEachPoint();
		}

		final double[] width = new double[c.length()];

		if (cp.envelope_type < 3) { // 1 or 2
			// two or three std dev
			final double[] std = c.getStdDevAtEachPoint();
			final int f = cp.envelope_type + 1; // so: 2 or 3
			for (int i=0; i<std.length; i++) {
				width[i] = f * std[i];
			}
		} else if (3 == cp.envelope_type) {
			// average distance from condensed to all sources
			int i=0;
			for (ArrayList<Point3d> ap : c.getSource()) {
				double sum = 0;
				for (Point3d p : ap) sum += c.distance(i, p);
				width[i] = sum / ap.size();
				i++;
			}
		} else if (4 == cp.envelope_type) {
			// max distance from condensed to all sources
			int i=0;
			for (ArrayList<Point3d> ap : c.getSource()) {
				double max = 0;
				for (Point3d p : ap) max = Math.max(max, c.distance(i, p));
				width[i] = max;
				i++;
			}
		}

		return width;
	}

	static private final class Cell<T> {
		final T t1, t2;
		Cell(final T t1, final T t2) {
			this.t1 = t1;
			this.t2 = t2;
		}
		public final boolean equals(final Object ob1, final Object ob2) {
			final Cell cell1 = (Cell)ob1;
			final Cell cell2 = (Cell)ob2;
			return (cell1.t1 == cell2.t1 && cell1.t2 == cell2.t2)
			    || (cell2.t1 == cell1.t1 && cell2.t2 == cell1.t2);
		}
	}

	/** Do an all-to-all distance matrix of the given vs, then do a neighbor joining, do a weighted merge of the two VectorString3D being merged, and then finally output the resulting condensed unique VectorString3D with its source array full with all points that make each point in it. Expects VectorString3D which are already calibrated and transformed. */
	static public VectorString3D condense(final CATAParameters cp, final VectorString3D[] vs, final Worker worker) throws Exception {
		// Trivial case 1:
		if (1 == vs.length) return vs[0];

		// Estimate delta
		if (0 == cp.delta) {
			for (int i=0; i<vs.length; i++) {
				cp.delta += vs[i].getAverageDelta();
			}
			cp.delta /= vs.length;
		}
		// Resample all:
		for (int i=0; i<vs.length; i++) vs[i].resample(cp.delta, true);

		// Trivial case 2:
		try {
			if (2 == vs.length) VectorString3D.createInterpolatedPoints(new Editions(vs[0], vs[1], cp.delta, false), 0.5f);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}

		// Else, do neighbor joining
		final float[][] scores = Compare.scoreAllToAll(vs, cp.distance_type, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.direct, cp.substring_matching, worker);
		final HashMap<Compare.Cell<VectorString3D>,Float> table = new HashMap<Compare.Cell<VectorString3D>,Float>();
		// Input the half matrix only into the table, since it's mirrored. And without the diagonal of zeros:
		for (int i=1; i<scores.length; i++) {
			for (int j=0; j<i; j++) {
				table.put(new Cell(vs[i], vs[j]), scores[i][j]);
			}
		}

		final HashSet<VectorString3D> remaining = new HashSet<VectorString3D>();
		for (VectorString3D v : vs) remaining.add(v);

		while (table.size() > 0) {
			if (null != worker && worker.hasQuitted()) {
				return null;
			}
			// find smallest value
			float min = Float.MAX_VALUE;
			Cell<VectorString3D> cell = null;
			for (Map.Entry<Cell<VectorString3D>,Float> e : table.entrySet()) {
				final float f = e.getValue();
				if (f < min) {
					min = f;
					cell = e.getKey();
				}
			}
			// pop cells from table
			// done below//table.remove(cell);
			for (Iterator<Cell<VectorString3D>> it =  table.keySet().iterator(); it.hasNext(); ) {
				final Cell<VectorString3D> c = it.next();
				if (c.t1 == cell.t1 || c.t2 == cell.t2
				 || c.t2 == cell.t1 || c.t1 == cell.t2) {
					it.remove();
				}
			}
			// pop the two merged VectorString3D
			remaining.remove(cell.t1);
			remaining.remove(cell.t2);

			// merge, weighted by number of sources of each
			final double alpha = (double)(cell.t1.getNSources()) / (double)(cell.t1.getNSources() + cell.t2.getNSources()); // in createInterpolated, the alpha is the opposite of what one would think: a 0.2 alpha means 0.8 for the first and 0.2 for the second. So alpha should be 1-alpha
			final Editions eds = new Editions(cell.t1, cell.t2, cp.delta, false);
			VectorString3D vs_merged = null;
			if (cp.cut_uneven_ends) {
				// crop ends to eliminate strings of insertions or deletions sparsed by strings of max cp.max_mut mutations inside
				// (This reduces or eliminates variability noise caused by unequal sequence length)
				final int[][] editions = eds.getEditions();
				int first = 0;
				int last = editions.length-1;
				int n_mut = 0;
				for (int i=0; i<last; i++) {
					if (Editions.MUTATION == editions[i][0]) {
						n_mut++;
						if (n_mut > cp.max_mut) {
							first = i - n_mut + 1;
							break;
						}
					}
				}
				n_mut = 0; // reset
				for (int i=last; i>first; i--) {
					if (Editions.MUTATION == editions[i][0]) {
						n_mut++;
						if (n_mut > cp.max_mut) {
							last = i + n_mut - 1;
							break;
						}
					}
				}
				vs_merged = VectorString3D.createInterpolatedPoints(eds, alpha, first, last);
			} else {
				vs_merged = VectorString3D.createInterpolatedPoints(eds, alpha);
			}
			vs_merged.resample(cp.delta, true);

			// add a new cell for each possible comparison with all other unique vs
			for (VectorString3D v : remaining) {
				Object[] ob = findBestMatch(vs_merged, v, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.distance_type, cp.direct, cp.substring_matching);
				Editions ed = (Editions)ob[0];
				float score = (float)getScore(ed, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.distance_type);
				table.put(new Cell(vs_merged, v), score);
			}

			// add the new VectorString3D
			remaining.add(vs_merged);
		}

		// Only the last vs_merged should remain:

		// test:
		if (1 != remaining.size()) {
			Utils.log2("WARNING: remaining.size() == " + remaining.size());
		}

		return remaining.iterator().next();
	}

	/** Iterates all tabs, removing those that contain queries from the given project. */
	static public void removeProject(final Project project) {
		if (null == ht_tabs) return;
		// avoid concurrent modifications
		HashSet<Map.Entry<JScrollPane,Chain>> set = new HashSet<Map.Entry<JScrollPane,Chain>>();
		set.addAll(ht_tabs.entrySet());
		for (Iterator<Map.Entry<JScrollPane,Chain>> it = set.iterator(); it.hasNext(); ) {
			Map.Entry<JScrollPane,Chain> e = it.next();
			JScrollPane jsp = e.getKey();
			JTable table = (JTable)jsp.getViewport().getView();
			QueryHolderTableModel qt = (QueryHolderTableModel)table.getModel();
			// uff!
			qt.qh.remove(project);
			Chain chain = e.getValue();
			if (0 == table.getRowCount() || chain.pipes.get(0).getProject() == project) {
				ht_tabs.remove(jsp);
				tabs.remove(jsp);
			}
		}
	}

	/** Transform all points of all VectorString3D in vs using a Moving Least Squares Transform defined by the pairing of points in source to those in target.
	 *  In short, bring source into target. */
	static public List<VectorString3D> transferVectorStrings(final List<VectorString3D> vs, final List<Tuple3d> source, final List<Tuple3d> target, final Class model_class) throws Exception {
		if (source.size() != target.size()) {
			Utils.log2("Could not generate a MovingLeastSquaresTransform: different number of source and target points.");
			return null;
		}
		if (source.size() < 1 || target.size() < 1) {
			Utils.log2("Cannot transform with less than one point correspondence!");
			return null;
		}

		// 1 - Create the MovingLeastSquaresTransform from the point matches

		ArrayList<PointMatch> pm = new ArrayList<PointMatch>();
		for (final Iterator<Tuple3d> si = source.iterator(), ti = target.iterator(); si.hasNext(); ) {
			Tuple3d sp = si.next();
			Tuple3d tp = ti.next();
			pm.add(new PointMatch(new mpicbg.models.Point(new float[]{(float)sp.x, (float)sp.y, (float)sp.z}), new mpicbg.models.Point(new float[]{(float)tp.x, (float)tp.y, (float)tp.z}), 1));
		}

		MovingLeastSquaresTransform mls = new MovingLeastSquaresTransform();
		mls.setModel(model_class);
		mls.setMatches(pm);

		final float[] point = new float[3];

		// 1.1 - Test: transfer source points
		/*
		for (final Iterator<Tuple3d> si = source.iterator(), ti = target.iterator(); si.hasNext(); ) {
			Tuple3d sp = si.next();
			point[0] = (float) sp.x;
			point[1] = (float) sp.y;
			point[2] = (float) sp.z;
			mls.applyInPlace(point);

			Tuple3d tp = ti.next();
			Utils.log2("== target: " + (float)tp.x + ", " + (float)tp.y + ", " + (float)tp.z +
				   "\n o source: " + (float)sp.x + ", " + (float)sp.y + ", " + (float)sp.z +

				   "\n   source: " + point[0] + ", " + point[1] + ", " + point[2]);
		}
		*/

		// 2 - Transfer each VectorString3D in vs with mls
		final List<VectorString3D> vt = new ArrayList<VectorString3D>();
		for (final VectorString3D vi : vs) {
			// The points of the VectorString3D:
			final double[] x = vi.getPoints(0);
			final double[] y = vi.getPoints(1);
			final double[] z = vi.getPoints(2);
			// Empty arrays to fill with the points to transfer:
			final double[] tx = new double[x.length];
			final double[] ty = new double[x.length];
			final double[] tz = new double[x.length];
			// Transfer point by point:
			for (int i=0; i<x.length; i++) {
				point[0] = (float)x[i];
				point[1] = (float)y[i];
				point[2] = (float)z[i];
				mls.applyInPlace(point);
				tx[i] = point[0];
				ty[i] = point[1];
				tz[i] = point[2];
			}
			try {
				vt.add(new VectorString3D(tx, ty, tz, vi.isClosed()));
			} catch (Exception e) {}
		}

		return vt;
	}

	/** Filer both maps, looking for matches, and put them into empty lists so and ta. */
	static private boolean extractMatches(final Map<String,Tuple3d> source, final Map<String,Tuple3d> target,
			                     final List<Tuple3d> so, final List<Tuple3d> ta) {
		if (null == source || null == target || null == so || null == ta) return false;
		so.clear();
		ta.clear();
		for (final Map.Entry<String,Tuple3d> e : target.entrySet()) {
			final Tuple3d point = source.get(e.getKey());
			if (null != point) {
				so.add(point);
				ta.add(e.getValue());
			}
		}
		if (0 == so.size()) {
			Utils.log2("No points in common!");
			return false;
		}
		return true;
	}

	/** Transfer vs via a moving least squares transform by matching source named points into equally named target named points. 
	 *  If no points in common, returns null. */
	static public List<VectorString3D> transferVectorStrings(final List<VectorString3D> vs, final Map<String,Tuple3d> source, final Map<String,Tuple3d> target) throws Exception {
		if (null == source || null == target) return null;
		final List<Tuple3d> so = new ArrayList<Tuple3d>();
		final List<Tuple3d> ta = new ArrayList<Tuple3d>();
		for (final Map.Entry<String,Tuple3d> e : target.entrySet()) {
			final Tuple3d point = source.get(e.getKey());
			if (null != point) {
				so.add(point);
				ta.add(e.getValue());
			}
		}
		if (0 == so.size()) {
			Utils.log2("No points in common!");
			return null;
		}
		Utils.log2("Found points in common: " + so.size());
		return transferVectorStrings(vs, so, ta, AffineModel3D.class);
	}

	static public List<VectorString3D> transferVectorStrings(final List<VectorString3D> vs, final ProjectThing source_fiduciary, final ProjectThing target_fiduciary) throws Exception {
		return transferVectorStrings(vs, extractPoints(source_fiduciary), extractPoints(target_fiduciary));
	}

	/** Extracts the list of fiduciary points from the fiducial parent and, if their name is different than "ball", adds their title as key and their first ball as a fiduciary point value of the returned map. The map is never null but could be empty.
	 * The values are calibrated. */
	static public Map<String,Tuple3d> extractPoints(final ProjectThing fiducial) {
		if (!fiducial.getType().equals("fiducial_points")) {
			Utils.log("Can only extract points from 'fiducial_points' type.");
			return null;
		}
		ArrayList<ProjectThing> fiducials = fiducial.getChildren();
		if (null == fiducials || 0 == fiducials.size()) {
			Utils.log("No fiducial points can be extracted from " + fiducial);
			return null;
		}
		final Map<String,Tuple3d> fide = new HashMap<String,Tuple3d>();
		for (final ProjectThing child : fiducials) {
			ArrayList<ProjectThing> balls = child.findChildrenOfType("ball");
			if (null == balls || 0 == balls.size()) {
				Utils.log2("Ignoring empty fiducial " + child);
				continue;
			}
			// pick the first one only
			final Ball ball = (Ball) balls.get(0).getObject();
			final String title = child.getType();
			final double[][] b = ball.getWorldBalls(); // calibrated
			if (b.length > 0) {
				// get the first one only
				fide.put(title.toLowerCase(), new Point3d(b[0][0], b[0][1], b[0][2]));
				Utils.log2("Found fiducial point " + title.toLowerCase());
			}
		}
		return fide;
	}

	static public final Bureaucrat findSimilarWithFiducials(final Line3D pipe) {
		return Bureaucrat.createAndStart(new Worker("Comparing...") {
			public void run() {
				startedWorking();
				try {

		// Check that a sister or a sister of a parent of the pipe is of type fiducial_points
		ProjectThing ptpipe = pipe.getProject().findProjectThing(pipe);
		if (null == ptpipe) {
			Utils.log("Pipe is not within the ProjectTree hierarchy of objects: can't compare!");
			return;
		}
		ProjectThing node = ptpipe;
		search: while (true) {
			// 1 - check if any sisters of the node are of type fiducial_points
			ProjectThing parent = (ProjectThing) node.getParent();
			if (null == parent || null == parent.getChildren()) {
				Utils.log("Could not find fiducials for pipe " + pipe);
				return;
			}
			for (ProjectThing sister : parent.getChildren()) {
				if (sister.getType().equals("fiducial_points")) {
					// found!
					node = sister;
					break search;
				}
			}
			node = parent;
			// continue upstream search until found or parent is null
		}
		//
		final ArrayList<Project> projects = Project.getProjects();
		// Find all ProjectThing of type "fiducial points" in all projects, and get the chains of pipes for each.
		ArrayList<ProjectThing> fiducials = new ArrayList<ProjectThing>();
		for (final Project pr : projects) {
			Set<ProjectThing> fids = pr.getRootProjectThing().findChildrenOfTypeR("fiducial_points");
			if (null == fids || 0 == fids.size()) {
				Utils.log2("No fiducial points found in project: " + pr);
				return;
			}
			Set<Thing> parents = new HashSet<Thing>();
			for (ProjectThing pt : fids) {
				if (parents.contains(pt.getParent())) {
					Utils.log("Ignoring fiducials " + pt + " : there's already one for the same parent ProjectThing.");
					continue;
				}
				parents.add(pt.getParent());
				fiducials.add(pt);
			}
		}
		if (1 == fiducials.size()) {
			Utils.log("Can't identify " + pipe + ": found only one set of fiducials: it's own!");
			return;
		}
		// Remove the pipe's fiducial_points
		fiducials.remove(node);
		//
		final String[] sf = new String[fiducials.size()];
		int next = 0;
		for (ProjectThing pt : fiducials) {
			sf[next++] = pt.getProject() + " -- " + pt + " #" + pt.getId();
		}
		GenericDialog gd = new GenericDialog("Identify with fiducials");
		gd.addMessage("Identify: " + pipe.toString());
		gd.addMessage("Using object fiducials: " + node + " #" + node.getId());
		gd.addChoice("Reference fiducials: ", sf, sf[0]);
		gd.addMessage("Choose a point interdistance to resample to, or 0 for the average of all.");
		gd.addNumericField("point_interdistance: ", 1, 2);
		gd.addCheckbox("skip insertion/deletion strings at ends when scoring", false);
		gd.addNumericField("maximum_ignorable consecutive muts in endings: ", 5, 0);
		gd.addNumericField("minimum_percentage that must remain: ", 0.5, 2);
		final String[] transforms = {"translation",
					     "rigid",
					     "similarity",
					     "affine"};
		gd.addChoice("Transform_type: ", transforms, transforms[3]);
		gd.addCheckbox("Chain_branches", true);
		gd.addChoice("Scoring type: ", distance_types, distance_types[2]);

		final String[] distance_types2 = {"Levenshtein", "Dissimilarity", "Average physical distance", "Median physical distance", "Cummulative physical distance", "Standard deviation", "Combined SLM", "Proximity", "Proximity of mutation pairs", "None"};
		gd.addChoice("Resort scores by: ", distance_types2, distance_types2[0]);
		gd.addNumericField("Min_matches: ", 10, 0);
		gd.addCheckbox("normalize", false);
		gd.addCheckbox("direct", true);
		gd.addCheckbox("score_mutations_only", false);
		gd.addCheckbox("substring_matching", false);

		gd.showDialog();
		if (gd.wasCanceled()) return;

		final int i_ref_fiducials = gd.getNextChoiceIndex();

		ProjectThing ref_fiducials = fiducials.get(i_ref_fiducials);

		final double delta = gd.getNextNumber();
		boolean skip_ends_ = gd.getNextBoolean();
		int max_mut_ = (int)gd.getNextNumber();
		float min_chunk_ = (float)gd.getNextNumber();
		if (skip_ends_) {
			if (max_mut_ < 0) max_mut_ = 0;
			if (min_chunk_ <= 0) skip_ends_ = false;
			if (min_chunk_ > 1) min_chunk_ = 1;
		}
		final boolean skip_ends = skip_ends_;
		final int max_mut = max_mut_;
		final float min_chunk = min_chunk_;
		final int transform_type = gd.getNextChoiceIndex();
		final boolean chain_branches = gd.getNextBoolean();
		final int distance_type = gd.getNextChoiceIndex();
		final int distance_type_2 = gd.getNextChoiceIndex();
		int min_matches = (int) gd.getNextNumber();
		if (min_matches < 0) {
			Utils.log("Using 0 min_matches!");
			min_matches = 0;
		}
		final boolean normalize = gd.getNextBoolean();
		final boolean direct = gd.getNextBoolean();
		final boolean score_mut = gd.getNextBoolean();
		final boolean substring_matching = gd.getNextBoolean();

		// Ready to start

		Map<String,Tuple3d> fid_query = extractPoints(node);
		Map<String,Tuple3d> fid_ref   = extractPoints(ref_fiducials);

		final Calibration cal1 = (null != pipe.getLayerSet() ? pipe.getLayerSet().getCalibration() : null);
		final Calibration cal2 = ref_fiducials.getProject().getRootLayerSet().getCalibration();

		Class model_class = AffineModel3D.class;
		switch (transform_type) {
			case 0:
				model_class = TranslationModel3D.class;
				break;
			/* // Don't exist yet:
			case 1:
				model_class = RigidModel3D.class;
				break;
			case 2:
				model_class = SimilarityModel3D.class;
				break;
			*/
			case 3:
			default:
				Utils.log2("Using AffineModel3D for the Moving Least Squares transform.");
				model_class = AffineModel3D.class;
				break;
		}

		final QueryHolder qh = new QueryHolder(cal1, cal2, fid_query, fid_ref, model_class);

		// Find chains for each reference fiducial ProjectThing

		ArrayList<ProjectThing> children = ref_fiducials.getParent().getChildren();
		if (null == children) {
			Utils.log("No chains for fiducial set " + ref_fiducials + " #" + ref_fiducials.getId());
			return;
		}

		ArrayList<Chain> ref_chains = new ArrayList<Chain>();

		if (chain_branches) {
			// create all chained ref branches
			for (ProjectThing sister : children) {
				if (ref_fiducials == sister) continue;
				ref_chains.addAll(createPipeChains(sister, sister.getProject().getRootLayerSet()));
			}
			// add all possible query chains, starting at the parent of the chosen pipe
			for (Chain chain : createPipeChains((ProjectThing) ptpipe.getParent(), pipe.getLayerSet())) {
				qh.addQuery(chain, 0 == delta ? chain.vs.getAverageDelta() : delta);
			}
		} else {
			// just add a single-pipe chain for each ref pipe
			for (ProjectThing child : children) {
				for (ProjectThing pt : child.findChildrenOfTypeR(new HashSet<ProjectThing>(), "pipe")) {
					ZDisplayable zd = (ZDisplayable) pt.getObject();
					if (null != zd) ref_chains.add(new Chain((Line3D)zd));
				}
			}
			// no branching: single query of one single-pipe chain
			Chain ch = new Chain(pipe);
			qh.addQuery(ch, 0 == delta ? ch.vs.getAverageDelta() : delta);
		}

		// set and calibrate all ref chains
		qh.setReferenceChains(ref_chains);

		final int n_ref_chains = qh.chains_ref.size();
		final QueryMatchList[] qm = new QueryMatchList[qh.queries.size()];
		int ne = 0;
		for (Chain query : qh.queries) qm[ne++] = new QueryMatchList(query, n_ref_chains);

		final int n_proc = Runtime.getRuntime().availableProcessors();

		ThreadPoolExecutor exec = (ThreadPoolExecutor) Executors.newFixedThreadPool(n_proc);

		ArrayList<Future<Boolean>> jobs = new ArrayList<Future<Boolean>>();

		int kk = 0;
		for (final Chain ref : qh.chains_ref) {
			final int k = kk;
			kk++;
			jobs.add(exec.submit(new Callable<Boolean>() {
				public Boolean call() {
					// match it againts all queries
					int next = 0;
					for (Chain query : qh.queries) {
						VectorString3D vs1 = query.vs;
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
					return true;
				}
			}));
		}
		for (Future fu : jobs) {
			fu.get(); // locks until done
		}

		qh.addMatches(qm);

		// Double sorting:
		qh.sortMatches(distance_type, distance_type_2, min_matches);

		qh.createGUI(null, null);

		exec.shutdown();

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		}, pipe.getProject());
	}

	/** Reliability analysis of pipe comparisons: compares all to all,
	 *  recording the score position of homonimous pipes in other projects.
	 *
	 *  The reference project to which all other project objects are
	 *  registered to is the first opened project, not the currently active
	 *  ControlWindow tab!
	 *
	 *  For each pipe in a brain, score against all other brains in which
	 *  that pipe name exists, and record the score position within that
	 *  brain.
	 */
	static public final Bureaucrat reliabilityAnalysis(final String[] ignore) {
		return reliabilityAnalysis(ignore, true, true, 1, 1, 1, 1);
	}

	static public final Bureaucrat reliabilityAnalysis(final String[] ignore, final boolean output_arff, final boolean show_dialog, final double delta, final double wi, final double wd, final double wm) {
		// gather all open projects
		final Project[] p = Project.getProjects().toArray(new Project[0]);

		final Worker worker = new Worker("Reliability by name") {
			public void run() {
				startedWorking();
				try {

		final CATAParameters cp = new CATAParameters();
		cp.delta = delta;
		if (show_dialog && !cp.setup(false, null, false, false)) {
			finishedWorking();
			return;
		}

		Object[] ob = gatherChains(p, cp, ignore);
		final ArrayList<Chain> chains = (ArrayList<Chain>)ob[0];
		final ArrayList[] p_chains = (ArrayList[])ob[1]; // to keep track of each project's chains
		ob = null;
		if (null == chains) {
			finishedWorking();
			return;
		}

		// For each pipe in a brain:
		//    - score against all other brains in which that pipe name exists,
		//    - record the score position within that brain.
		//
		final ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

		// for each individual lineage:
		final TreeMap<String,ArrayList<Integer>> indices = new TreeMap<String,ArrayList<Integer>>();
		final ArrayList<CITuple> cin = new ArrayList<CITuple>();

		// for each family:
		final TreeMap<String,ArrayList<Integer>> indices_f = new TreeMap<String,ArrayList<Integer>>();
		final ArrayList<CITuple> cin_f = new ArrayList<CITuple>();


		final ArrayList<Future> fus = new ArrayList<Future>();

		// For neural network analysis:
		final StringBuilder arff = output_arff ? new StringBuilder("@RELATION Lineages\n\n") : null;
		if (output_arff) {
			arff.append("@ATTRIBUTE APD NUMERIC\n");
			arff.append("@ATTRIBUTE CPD NUMERIC\n");
			arff.append("@ATTRIBUTE STD NUMERIC\n");
			arff.append("@ATTRIBUTE MPD NUMERIC\n");
			arff.append("@ATTRIBUTE PM NUMERIC\n");
			arff.append("@ATTRIBUTE LEV NUMERIC\n");
			arff.append("@ATTRIBUTE SIM NUMERIC\n");
			arff.append("@ATTRIBUTE PRX NUMERIC\n");
			arff.append("@ATTRIBUTE PRM NUMERIC\n");
			arff.append("@ATTRIBUTE LR NUMERIC\n"); // length ratio: len(query) / len(ref)
			arff.append("@ATTRIBUTE TR NUMERIC\n");
			arff.append("@ATTRIBUTE CLASS {false,true}\n");

			arff.append("\n@DATA\n");
		}


		// Count number of times when decision tree says it's good, versus number of times when it should be good

		final AtomicInteger obs_good = new AtomicInteger(0); // observed
		final AtomicInteger obs_wrong = new AtomicInteger(0); // observed wrong
		final AtomicInteger exp_good = new AtomicInteger(0); // expected
		final AtomicInteger obs_bad_classified_good_ones = new AtomicInteger(0);
		final AtomicInteger obs_well_classified_bad_ones = new AtomicInteger(0);
		final AtomicInteger not_found = new AtomicInteger(0); // inc by one when a lineage to compare is not found at all in the brain that works as reference
		final AtomicInteger already_classified = new AtomicInteger(0);


		// All possible pairs of projects, with repetition (it's not the same, although the pipe pairwise comparison itself will be.)
		for (int _i=0; _i<p_chains.length; _i++) {
			final int i = _i;

			Utils.log2("Project " + p[i] + " has " + p_chains[i].size() + " chains.");

			for (int _j=0; _j<p_chains.length; _j++) {
				final int j = _j;

				// skip same project (would have a score of zero, identical.)
				if (i == j) continue;


				final String[] titles_j = new String[p_chains[j].size()];
				int next = 0;
				for (final Chain cj : (ArrayList<Chain>) p_chains[j]) {
					String t = cj.getCellTitle();
					titles_j[next++] = t.substring(0, t.indexOf(' '));
				}

				// families:
				TreeSet<String> ts_families = new TreeSet<String>();
				for (int f=0; f<titles_j.length; f++) {
					// extract family name from title: read the first continuous string of capital letters
					String title = titles_j[f];
					int u = 0;
					for (; u<title.length(); u++) {
						if (!Character.isUpperCase(title.charAt(u))) break;
					}
					ts_families.add(title.substring(0, u));
				}
				final ArrayList<String> families = new ArrayList<String>(ts_families);


				fus.add(exec.submit(new Callable() { public Object call() {

				// All chains of one project to all chains of the other:
				for (final Chain chain : (ArrayList<Chain>) p_chains[i]) {
					final VectorString3D vs1 = chain.vs;
					// Prepare title
					String title = chain.getCellTitle();
					title = title.substring(0, title.indexOf(' '));
					// check if the other project j contains a chain of name chain.getCellTitle() up to the space.
					int title_index = -1;
					for (int k=0; k<titles_j.length; k++) {
						if (title.equals(titles_j[k])) {
							title_index = k;
							break;
						}
					}
					if (-1 == title_index) {
						Utils.log2(title + " not found in project " + p[j]);
						not_found.incrementAndGet();
						continue;
					}

					// should be there:
					exp_good.incrementAndGet();


					ArrayList<ChainMatch> list = new ArrayList<ChainMatch>();

					// extract family name from title: read the first continuous string of capital letters
					int u = 0;
					for (; u<title.length(); u++) {
						if (!Character.isUpperCase(title.charAt(u))) break;
					}
					final String family_name = title.substring(0, u);


					String last_classify = null;


					int g = 0;
					for (final Chain cj : (ArrayList<Chain>) p_chains[j]) {
						final VectorString3D vs2 = cj.vs;
						final Object[] ob = findBestMatch(vs1, vs2, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.distance_type, cp.direct, cp.substring_matching, wi, wd, wm);
						final Editions ed = (Editions)ob[0];
						double[] stats = ed.getStatistics(cp.skip_ends, cp.max_mut, cp.min_chunk, cp.score_mut_only);
						float prop_len = ((float)vs1.length()) / vs2.length();
						ChainMatch cm = new ChainMatch(cj, null, ed, stats, prop_len, score(ed.getSimilarity(), ed.getDistance(), stats[3], Compare.W));
						cm.title = titles_j[g];
						list.add(cm);
						g++;


						// for neural network training: ARFF format
						/*
						synchronized (arff) {
							// The parameters from stats array
							for (int p=0; p<stats.length; p++) {
								arff.append(stats[p]).append(',');
							}
							// The sequence lengths ratio:
							arff.append(vs1.length() / (float)vs2.length()).append(',');
							// And finally the result: good or bad, for lineage and for family:
							arff.append(title.equals(cm.title)).append('-').append(cm.title.startsWith(family_name)).append('\n');
						}
						*/

						// from decision tree: is it good?
						double[] param = new double[10];
						for (int p=0; p<stats.length; p++) param[p] = stats[p];
						param[9] = vs1.length() / (float)vs2.length();
						try {
							if (LineageClassifier.classify(param)) {
								if (null != last_classify) {
									Utils.log2("ALREADY CLASSIFIED " + title + " as " + last_classify + "  (now: " + cm.title + " )");
									already_classified.incrementAndGet();
								}

								last_classify = cm.title;

								if (title.equals(cm.title)) {
									obs_good.incrementAndGet();
								} else {
									Utils.log2("WRONG CLASSIFICATION of " + title + " as " + cm.title);
									obs_wrong.incrementAndGet();
								}
							} else {
								if (title.equals(cm.title)) {
									obs_bad_classified_good_ones.incrementAndGet();
								} else {
									obs_well_classified_bad_ones.incrementAndGet();
								}
							}
						} catch (Exception ee) {
							IJError.print(ee);
						}
					}

					// sort scores:
					Compare.sortMatches(list, cp.distance_type, cp.distance_type_2, cp.min_matches);

					if (output_arff) {
						// Take top 8 and put them into training set for WEKA in arff format
						for (int h=0; h<8; h++) {
							ChainMatch cm = list.get(h);
							StringBuilder sb = new StringBuilder();
							sb.append(cm.phys_dist).append(',')
							  .append(cm.cum_phys_dist).append(',')
							  .append(cm.stdDev).append(',')
							  .append(cm.median).append(',')
							  .append(cm.prop_mut).append(',')
							  .append(cm.ed.getDistance()).append(',')
							  .append(cm.seq_sim).append(',')
							  .append(cm.proximity).append(',')
							  .append(cm.proximity_mut).append(',')
							  .append(cm.prop_len).append(',')
							  .append(cm.tortuosity_ratio).append(',')
							  .append(title.equals(cm.title)).append('\n'); // append('-').append(cm.title.startsWith(family_name)).append('\n');
							synchronized (arff) {
								arff.append(sb);
							}
						}
					}

					// record scoring index
					int f = 0;

					boolean found_specific = false;
					boolean found_family = false;


					for (ChainMatch cm : list) {
						// Exact match: for each individual lineage
						if (!found_specific && title.equals(cm.title)) {
							synchronized (indices) {
								ArrayList<Integer> al = indices.get(title);
								if (null == al) {
									al = new ArrayList<Integer>();
									indices.put(title, al);
									cin.add(new CITuple(title, chain, al)); // so I can keep a list of chains sorted by name
								}
								al.add(f);
							}
							found_specific = true;
						}
						if (!found_family && cm.title.startsWith(family_name)) {
							synchronized (indices_f) {
								ArrayList<Integer> al = indices_f.get(family_name);
								if (null == al) {
									al = new ArrayList<Integer>();
									indices_f.put(family_name, al);
									cin_f.add(new CITuple(family_name, chain, al));
								}
								al.add(f);
							}
							found_family = true;
						}

						if (found_specific && found_family) {
							break;
						}

						//
						f++;
					}
					if (!found_specific) {
						Utils.log2("NOT FOUND any match for " + title + " within a list of size " + list.size() + ", in project " + chain.getRoot().getProject());
					}
				}
				return null;
				}}));
			}
		}

		for (Future fu : fus) {
			try { fu.get(); } catch (Exception e) { IJError.print(e); }
		}
		exec.shutdownNow();

		LineageClassifier.flush(); // so stateful ... it's a sin.

		// export ARFF for neural network training
		if (output_arff) {
			Utils.saveToFile(new File(System.getProperty("user.dir") + "/lineages.arff"), arff.toString());
		}


		// Show the results from indices map

		StringBuilder sb = new StringBuilder();

		TreeMap<Integer,Integer> sum = new TreeMap<Integer,Integer>(); // scoring index vs count of occurrences

		TreeMap<Integer,Integer> sum_f = new TreeMap<Integer,Integer>(); // best scoring index of best family member vs count of ocurrences

		// From collected data, several kinds of results:
		// - a list of how well each chain scores: its index position in the sorted list of scores of one to many.
		// - a list of how well each chain scores relative to family: the lowest (best) index position of a lineage of the same family in the sorted list of scores.

		sb.append("List of scoring indices for each (starting at index 1, aka best possible score):\n");
		for (final CITuple ci : cin) {
			// sort indices in place
			Collections.sort(ci.list);
			// count occurrences of each scoring index
			int last = 0; // lowest possible index
			int count = 1;
			for (int i : ci.list) {
				if (last == i) count++;
				else {
					sb.append(ci.title).append(' ').append(last+1).append(' ').append(count).append('\n');
					// reset
					last = i;
					count = 1;
				}
				// global count of occurrences
				final Integer oi = new Integer(i);
				sum.put(oi, (sum.containsKey(oi) ? sum.get(oi) : 0) + 1);
			}
			if (0 != count) sb.append(ci.title).append(' ').append(last+1).append(' ').append(count).append('\n');

			// find the very-off ones:
			if (last > 6) {
				Utils.log2("BAD index " + last + " for chain " + ci.title  + " " + ci.chain.getRoot() + " of project " + ci.chain.getRoot().getProject());
			}
		}
		sb.append("===============================\n");

		/// family-wise:
		for (final CITuple ci : cin_f) {
			// sort indices in place
			Collections.sort(ci.list);
			// count occurrences of each scoring index
			int last = 0; // lowest possible index
			int count = 1;
			for (int i : ci.list) {
				if (last == i) count++;
				else {
					// reset
					last = i;
					count = 1;
				}
				// global count of occurrences
				final Integer oi = new Integer(i);
				sum_f.put(oi, (sum_f.containsKey(oi) ? sum_f.get(oi) : 0) + 1);
			}
		}
		sb.append("===============================\n");


		// - a summarizing histogram that collects how many 1st, how many 2nd, etc. in total, normalized to total number of one-to-many matches performed (i.e. the number of scoring indices recorded.)

		sb.append("Global count of index ocurrences:\n");
		for (Map.Entry<Integer,Integer> e : sum.entrySet()) {
			sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		}
		sb.append("===============================\n");


		// - the percent of first score being the correct one:
		double first = 0;
		double first_5 = 0;
		double all = 0;
		for (Map.Entry<Integer,Integer> e : sum.entrySet()) {
			int k = e.getKey();
			int a = e.getValue();

			all += a;

			if (0 == k) first = a;

			if (k < 5) first_5 += a;
		}

		// STORE
		this.result = new double[]{
			first / all,  // Top one ratio
			first_5 / all // Top 5 ratio
		};

		sb.append("Global count of index occurrences family-wise:\n");
		for (Map.Entry<Integer,Integer> e : sum_f.entrySet()) {
			sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
		}
		sb.append("===============================\n");

		// - a summarizing histogram of how well each chain scores (4/4, 3/4, 2/4, 1/4, 0/4 only for those that have 4 homologous members.)
		// Must consider that there are 5 projects taken in pairs with repetition.

		sb.append("A summarizing histogram of how well each chain scores, for those that have 4 homologous members. It's the number of 1st scores (zeroes) versus the total number of scores:\n");
		// First, classify them in having 4, 3, 2, 1
			// For 5 brains:  5! / (5-2)! = 5 * 4 = 20   --- 5 elements taken in groups of 2, where order matters
			// For 4 brains:  4! / (4-2)! = 4 * 3 = 12
			// For 3 brains:  3! / (3-2)! = 3 * 2 = 6;
		
		TreeMap<Integer,ArrayList<String>> hsc = new TreeMap<Integer,ArrayList<String>>();

		for (final CITuple ci : cin) {
			final int size = ci.list.size();
			ArrayList<String> al = hsc.get(size);
			if (null == al) {
				al = new ArrayList<String>();
				hsc.put(size, al);
			}
			// Count the number of 0s -- top scoring
			int count = 0;
			for (Integer i : ci.list) {
				if (0 == i) count++;
				else break;
			}
			al.add(new StringBuffer(ci.title).append(" =").append(count).append('/').append(ci.list.size()).append('\n').toString());
		}
		// Then just print:
		for (Map.Entry<Integer,ArrayList<String>> e : hsc.entrySet()) {
			sb.append("For ").append(e.getKey()).append(" matches:\n");
			for (String s : e.getValue()) sb.append(s);
		}

		sb.append("=========================\n");

		// Family-wise, count the number of zeros per family:
		sb.append("Number of top scoring per family:\n");
		TreeMap<String,String> family_scores = new TreeMap<String,String>();
		for (final CITuple ci : cin_f) {
			int count = 0;
			for (Integer i : ci.list) {
				if (0 == i) count++;
				else break; // ci.list is sorted
			}
			family_scores.put(ci.title, new StringBuilder().append(ci.title).append(" =").append(count).append('/').append(ci.list.size()).append('\n').toString());
		}
		// Now print sorted by family name:
		for (String s : family_scores.values()) {
			sb.append(s);
		}

		sb.append("=========================\n");

		// Keep in mind it should all be repeated for 0.5 micron delta, 0.6, 0.7 ... up to 5 or 10 (until the histogram starts getting worse.) The single value with which the graph coould be made is the % of an index of 1, and of an index of 2.
		//
		// TODO


		sb.append("Decision tree:\n");
		sb.append("Expected good matches: " + exp_good.get() + "\n");
		sb.append("Observed good matches: " + obs_good.get() + "\n");
		sb.append("Observed bad matches: " + obs_wrong.get() + "\n");
		sb.append("Observed well classified bad ones: " + obs_well_classified_bad_ones.get() + "\n");
		sb.append("Observed bad classified good ones: " + obs_bad_classified_good_ones.get() + "\n");
		sb.append("Not found, so skipped: " + not_found.get() + "\n");
		sb.append("Already classified: " + already_classified.get() + "\n");

		sb.append("=========================\n");


		if (output_arff) {
			Utils.log(sb.toString());
		} else {
			Utils.log2(sb.toString());
		}


				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, p);
	}

	private static final class CITuple {
		String title;
		Chain chain;
		ArrayList<Integer> list;
		CITuple(String t, Chain c, ArrayList<Integer> l) {
			title = t;
			chain = c;
			list = l;
		}
	}


	// Graph data generation: 
	//  - X axis: resampling distance, from 0.4 to 10 microns, in increments of 0.1 microns.
	//  - Y axis: weights for deletion and insertion: from 0 to 10, in increments of 0.1
	//  - Z1 axis: the percentage of properly scored first lineages (currently 75%)
	//  - Z2 axis: the percentage of the good one being within top 5 (currently 99%)


	static public final Bureaucrat reliabilityAnalysisSpaceExploration(final String[] ignore) {

		final double MIN_DELTA = 0.4;
		final double MAX_DELTA = 10;
		final double INC_DELTA = 0.1;

		final double MIN_WEIGHT = 0;
		final double MAX_WEIGHT = 10;
		final double INC_WEIGHT = 0.1;

		return Bureaucrat.createAndStart(new Worker.Task("Space Exploration") { public void exec() {

		File f = new File(System.getProperty("user.dir") + "/lineage_space_exploration.data");
		DataOutputStream dos = null;
		
		try {
			dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));

			for (double delta = MIN_DELTA; delta <= (MAX_DELTA + INC_DELTA/2); delta += INC_DELTA) {
				for (double weight = MIN_WEIGHT; weight <= (MAX_WEIGHT + INC_WEIGHT/2); weight += INC_WEIGHT) {

					Bureaucrat b = Compare.reliabilityAnalysis(ignore, false, false, delta, weight, weight, 1); // WM = 1
					b.join();

					double[] result = (double[]) b.getWorker().getResult();

					dos.writeDouble(delta);
					dos.writeChar('\t');
					dos.writeDouble(weight);
					dos.writeChar('\t');
					dos.writeDouble(result[0]);
					dos.writeChar('\t');
					dos.writeDouble(result[1]);
					dos.writeChar('\n');

					dos.flush(); // so I get to see something before the whole giant buffer is full

					Utils.log2("===========================\n\n");
					Utils.log2("delta: " + delta + " weight: " + weight + " top_one: " + result[0] + " top_5: " + result[1]);
					Utils.log2("===========================\n\n");
				}
			}

			dos.flush();
			dos.close();
		} catch (Exception e) {
			try { dos.close(); } catch (Exception ee) { ee.printStackTrace(); }
		}

		}}, Project.getProjects().toArray(new Project[0]));
	}
}
