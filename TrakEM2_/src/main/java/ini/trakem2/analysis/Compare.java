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

package ini.trakem2.analysis;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij3d.Content;
import ini.trakem2.Project;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Line3D;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import ini.trakem2.vector.Editions;
import ini.trakem2.vector.VectorString3D;

import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.IndexColorModel;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.media.j3d.Transform3D;
import javax.vecmath.Color3f;
import javax.vecmath.Matrix3d;
import javax.vecmath.Matrix4d;
import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;

import mpi.fruitfly.general.MultiThreading;
import mpicbg.models.AffineModel3D;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.PointMatch;

public class Compare {

	static public final int TRANS_ROT = 0;
	static public final int TRANS_ROT_SCALE = 1;
	static public final int TRANS_ROT_SCALE_SHEAR = 2;

	private Compare() {}


	/** Finds the first three X,Y,Z axes as specified by the names in preset. */
	static private int[] findFirstXYZAxes(final String[] preset, final ArrayList<ZDisplayable> pipes, final String[] pipe_names) {
		final int[] s = new int[]{-1, -1, -1};
		int next = 0;
		for (final ZDisplayable zd : pipes) {
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
		final Vector3d[] o = Compare.createOrigin(vs[0], vs[1], vs[2], transform_type, o_ref); // requires resampled vs

		return new Object[]{vs, o};
	}

	/** Returns an array of 4 Vector3d: the three unit vectors in the same order as the vector strings, and the origin of coordinates.
	 *
	 * Expects:
	 *   X, Y, Z
	 *   where Z is the one to trust the most, Y the second most trusted, and X only for orientation.
	 *   ZY define the plane, the direction of the perpendicular of which is given by the X.
	 *
	 *   @return normalized vectors for transform_type == Compare.TRANS_ROT, otherwise NOT normalized.
	 */
	static public Vector3d[] createOrigin(final VectorString3D x, final VectorString3D y, final VectorString3D z, final int transform_type) {
		Utils.log2("WARNING TODO shouldn't be using this method ever");
		return createOrigin(x, y, z, transform_type, null);
	}
	static public Vector3d[] createOrigin(final VectorString3D x, final VectorString3D y, final VectorString3D z, final int transform_type, final Vector3d[] o_ref) {
		// Aproximate an origin of coordinates
		final VectorString3D[] vs = new VectorString3D[]{z, y, x};
		final ArrayList<Point3d> ps = new ArrayList<Point3d>();
		final int[] dir = new int[]{1, 1, 1};

		for (int i=0; i<vs.length; i++) {
			for (int k=i+1; k<vs.length; k++) {
				double min_dist = Double.MAX_VALUE;
				int ia=0, ib=0;
				for (int a=0; a<vs[i].length(); a++) {
					for (int b=0; b<vs[k].length(); b++) {
						final double d = VectorString3D.distance(vs[i], a, vs[k], b);
						if (d < min_dist) {
							min_dist = d;
							ia = a;
							ib = b;
						}
					}
				}
				ps.add(new Point3d((vs[i].getPoint(0, ia) + vs[k].getPoint(0, ib))/2,
						  (vs[i].getPoint(1, ia) + vs[k].getPoint(1, ib))/2,
						  (vs[i].getPoint(2, ia) + vs[k].getPoint(2, ib))/2));
				// determine orientation of the VectorString3D relative to the origin
				if (ia > vs[i].length()/2) dir[i] = -1;
				if (ib > vs[k].length()/2) dir[k] = -1;
				// WARNING: we don't check for the case where it contradicts
			}
		}

		final Vector3d origin = new Vector3d();
		final int len = ps.size();
		for (final Point3d p : ps) {
			p.x /= len;
			p.y /= len;
			p.z /= len;
		}
		for (final Point3d p : ps) origin.add(p);

		// aproximate a vector for each axis
		final Vector3d vz = z.sumVector();
		final Vector3d vy = y.sumVector();
		final Vector3d vx = x.sumVector();

		// adjust orientation, so vectors point away from the origin towards the other end of the vectorstring
		vz.scale(dir[0]);
		vy.scale(dir[1]);
		vx.scale(dir[2]);

		/*
		Utils.log2("dir[0]=" + dir[0]);
		Utils.log2("dir[1]=" + dir[1]);
		Utils.log2("dir[2]=" + dir[2]);
		*/

		Vector3d v1 = vx,
			 v2 = vy;
        final Vector3d v3 = vz;


		// TRANS_ROT:
		//     - peduncle rules (vz), and the others are cross products of it
		//     - query axes vectors made of same length as reference
		//
		// TRANS_ROT_SCALE:
		//     - peduncle rules (vz), and the others are cross products of it
		//
		// TRANS_ROT_SCALE_SHEAR:
		//     - use axes vectors as they are, but normalized to make the longest be 1.0


		/* // old way: MB space
		if (Compare.TRANS_ROT == transform_type) {
			vx.normalize();
			vy.normalize();
			vz.normalize();
		}
		*/

		if (Compare.TRANS_ROT == transform_type || Compare.TRANS_ROT_SCALE == transform_type) {
			// 1 - compute MEDIAL vector: perpendicular to the plane made by peduncle and dorsal lobe
			final Vector3d vc_medial = new Vector3d();
			vc_medial.cross(vz, vy);
			/* // OLD WAY
			// check orientation:
			Vector3d vc_med = new Vector3d(vc_medial);
			vc_med.normalize();
			Vector3d vx_norm = new Vector3d(vx);
			vx_norm.normalize();
			vc_med.add(vx_norm); // adding the actual medial lobe vector
			// if the sum is smaller, then it means it should be inverted (it was the other side)
			if (vc_med.length() < vx_norm.length()) {
				vc_medial.scale(-1);
				Utils.log2("Mirroring X axis");
			}
			*/

			// 2 - compute DORSAL vector: perpedicular to the plane made by v1 and vc_medial
			final Vector3d vc_dorsal = new Vector3d();
			vc_dorsal.cross(vz, vc_medial);
			// check orientation
			final Vector3d vc_dor = new Vector3d(vc_dorsal);
			vc_dor.add(vy);
			// if the sum is smaller, invert
			if (vc_dor.length() < vy.length()) {
				vc_dorsal.scale(-1);
				Utils.log("Mirroring Y axis");
			}

			/*
			if (Compare.TRANS_ROT == transform_type) {
				// just in case, for rounding issues
				vc_medial.normalize();
				vc_dorsal.normalize();
			}
			*/

			v1 = vc_medial;
			v2 = vc_dorsal;
			//v3 = vz; // already done, the peduncle

			if (Compare.TRANS_ROT == transform_type && null != o_ref) {
				// Scale each query axis to length of the reference one
				// so that there are no scaling differences.
				v1.normalize();		v1.scale(o_ref[0].length());
				v2.normalize();		v2.scale(o_ref[1].length());
				v3.normalize();		v3.scale(o_ref[2].length());
			}

		}
		// else if (Compare.TRANS_ROT_SCALE_SHEAR == transform_type)
			// use AS THEY ARE

		return new Vector3d[]{
			v1, // X axis : medial lobe
			v2, // Y axis : dorsal lobe
			v3, // Z axis : peduncle
			origin     // x,y,z origin of coordinates
		};
	}

	static private VectorString3D makeVSFromP(final Vector3d p, final Vector3d origin) throws Exception {
		final double[] x1 = new double[20];
		final double[] y1 = new double[20];
		final double[] z1 = new double[20];
		final double K = 10;
		x1[0] = p.x * K;
		y1[0] = p.y * K;
		z1[0] = p.z * K;
		for (int i=1; i<x1.length; i++) {
			x1[i] = p.x * K + x1[i-1];
			y1[i] = p.y * K + y1[i-1];
			z1[i] = p.z * K + z1[i-1];
		}
		for (int i=0; i<x1.length; i++) {
			x1[i] += origin.x;
			y1[i] += origin.y;
			z1[i] += origin.z;
		}
		return new VectorString3D(x1, y1, z1, false);
	}

	static public void testCreateOrigin(final LayerSet ls, final VectorString3D vs1, final VectorString3D vs2, final VectorString3D vs3) {
		try {
			// create vectors
			final double delta = (vs1.getAverageDelta() + vs2.getAverageDelta() + vs3.getAverageDelta()) / 3;
			vs1.resample(delta);
			vs2.resample(delta);
			vs3.resample(delta);
			//
			final Vector3d[] o = createOrigin(vs1, vs2, vs3, Compare.TRANS_ROT);
			Display3D.addMesh(ls, makeVSFromP(o[0], o[3]), "v1", Color.green);
			Display3D.addMesh(ls, makeVSFromP(o[1], o[3]), "v2", Color.orange);
			Display3D.addMesh(ls, makeVSFromP(o[2], o[3]), "v3", Color.red);
			System.out.println("v1:" + o[0]);
			System.out.println("v2:" + o[1]);
			System.out.println("v3:" + o[2]);

			// create matrix:
			final Matrix3d rotm = new Matrix3d(
					o[0].x, o[1].x, o[2].x,
					o[0].y, o[1].y, o[2].y,
					o[0].z, o[1].z, o[2].z
			);
			final Transform3D rot = new Transform3D(rotm, new Vector3d(), 1.0);
			rot.invert();
			// DOESN'T WORK // Transform3D trans =  new Transform3D(new Matrix3d(1, 0, 0, 0, 1, 0, 0, 0, 1), new Vector3d(-o[3].x, -o[3].y, -o[3].z), 1.0);

			System.out.println("o3: " + o[3].toString());

			// test:
			for (int i=0; i<3; i++) {
				o[i].x += o[3].x;
				o[i].y += o[3].y;
				o[i].z += o[3].z;
			}

			for (int i=0; i<3; i++) {
				o[i].sub(o[3]); // can't use translation matrix: doesn't work
				//trans.transform(o[i]);
				rot.transform(o[i]);
			}

			System.out.println("v1:" + o[0]); // expect: 1, 0, 0
			System.out.println("v2:" + o[1]); // expect: 0, 1, 0
			System.out.println("v3:" + o[2]); // expect: 0, 0, 1



		} catch (final Exception e) {
			e.printStackTrace();
		}
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

	static public ArrayList<Chain> createPipeChains(final ProjectThing root_pt, final LayerSet ls, final String regex_exclude) throws Exception {
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		Pattern exclude = null;
		if (null != regex_exclude) {
			exclude = Pattern.compile(regex_exclude, Pattern.DOTALL);
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

		final ArrayList<ProjectThing> children = parent.getChildren();
		if (null == children) return;

		if (null == hs_c_done) hs_c_done = new HashSet<ProjectThing>();

		for (final ProjectThing child : children) {
			if (hs_c_done.contains(child)) continue;
			if (null != exclude && exclude.matcher(child.getTitle()).matches()) {
				Utils.log2("Excluding child " + child + " with title " + child.getTitle());
				continue;
			}
			hs_c_done.add(child);

			if (child.getObject() instanceof Line3D) {
				final Line3D pipe = (Line3D)child.getObject();
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
				for (final ProjectThing c : children) {
					if (hs_c_done.contains(c)) continue; // already visited
					// c is at the same tree level as child (which contains a pipe directly)
					final ArrayList<Line3D> child_pipes = c.findChildrenOfType(Line3D.class);
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
		public String title = null;
		private Chain() {}
		public Chain(final Line3D root) {
			this.pipes.add(root);
			this.vs = root.asVectorString3D();
		}
		final public void append(final Line3D p) throws Exception {
			//if (pipes.contains(p)) throw new Exception("Already contains pipe #" + p.getId());
			pipes.add(p);
			vs = vs.chain(p.asVectorString3D());
		}
		public final Chain duplicate() {
			final Chain chain = new Chain();
			chain.pipes.addAll(this.pipes);
			chain.vs = (VectorString3D)this.vs.clone();
			return chain;
		}
		@Override
        public String toString() {
			final StringBuffer sb = new StringBuffer("len: ");
			sb.append(pipes.size()).append("   ");
			for (final Line3D p : pipes) sb.append('#').append(p.getId()).append(' ');
			return sb.toString();
		}
		final public String getTitle() {
			if (null != title) return title;
			final StringBuffer sb = new StringBuffer(pipes.get(0).getProject().getTitle());
			sb.append(' ');
			for (final Line3D p : pipes) sb.append(' ').append('#').append(p.getId());
			return sb.toString();
		}
		final public String getCellTitle() {
			if (null != title) return title;
			final Line3D root = pipes.get(0);
			final String mt = root.getProject().getShortMeaningfulTitle((ZDisplayable)root);
			if (1 == pipes.size()) return mt;
			//else, chain the ids of the rest
			final StringBuffer sb = new StringBuffer(mt);
			for (int i=1; i<pipes.size(); i++) sb.append(' ').append('#').append(pipes.get(i).getId());
			return sb.toString();
		}
		/** Returns max 10 chars, solely the name of the parent's parent node of the root pipe (aka the [lineage] containing the [branch]) or the id if too long. Intended for the 10-digit limitation in the problem in .dis files for Phylip. */
		final public String getShortCellTitle() {
			if (null != title) return title;
			final Line3D root = pipes.get(0);
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
		final public void showCentered2D(final boolean shift_down) {
			Rectangle b = null;
			final Display display = Display.getFront();
			for (final Line3D line3d : pipes) {
				final ZDisplayable p = (ZDisplayable)line3d;
				if (null == b) b = p.getBoundingBox();
				else b.add(p.getBoundingBox());
				p.setVisible(true);
				display.select(p, shift_down);
			}
			display.select((ZDisplayable)pipes.get(0), shift_down); // the root as active
			display.getCanvas().showCentered(b);
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
				final ArrayList<ChainMatch> li = new ArrayList<ChainMatch>(list);
				// sort
				Collections.sort(li, new ChainMatchComparator(params[i]));
				// Assign index to each
				for (int k=0; k<indices.length;  k++) {
					final ChainMatch cm = list.get(k);
					final int index = li.indexOf(cm);
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

			final ChainMatch[] cm = list.toArray(new ChainMatch[0]);
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
		final double roof = list.get(0).phys_dist * 1.5;
		// 3 - Filter all values up to the roof, allowing for at least min_number entries to be left in the list:
		int count = 0;
		for (final Iterator<ChainMatch> it = list.iterator(); it.hasNext(); ) {
			final ChainMatch cm = it.next();
			count++;
			if (cm.phys_dist > roof && count > min_number) {
				it.remove();
			}
		}
		// 4 - Resort remaining matches by second distance type:
		Collections.sort(list, new ChainMatchComparator(distance_type_2));
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
		double prop_len; // the proportion of length of query sequence versus reference sequence
		double proximity; // unitless value: cummulative distance of pairs relative to query sequence length
		double proximity_mut; // unitless value: cummulative distance of only mutation pairs relative to query sequence length  ## TODO not unitless, this is the same as the average
		double tortuosity_ratio;

		String title = null;


		ChainMatch(final Chain query, final Chain ref, final Editions ed, final double[] stats, final double score) {
			this.query = query;
			this.ref = ref;
			this.ed = ed;
			this.phys_dist = stats[0];
			this.cum_phys_dist = stats[1];
			this.stdDev = stats[2];
			this.median = stats[3];
			this.prop_mut = stats[4];
			this.score = score; // combined
			this.seq_sim = stats[6];
			this.proximity = stats[7];
			this.proximity_mut = stats[8];
			this.prop_len = stats[9];
			this.tortuosity_ratio = stats[10];
		}
	}

	static private class ChainMatchComparator implements Comparator<ChainMatch> {
		/** Sort by the given distance type. */
		final int distance_type;
		ChainMatchComparator(final int distance_type) {
			this.distance_type = distance_type;
		}
		@Override
        public int compare(final ChainMatch cm1, final ChainMatch cm2) {
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

	static protected final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk) {
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
	 *
	 * Uses 1.1 weights for costs of insertion and deletion, as proven better by the parameter exploration.
	 * Uses weight of 1 for cost of mutation.
	 * */
	static protected final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk, final int distance_type, final boolean direct, final boolean substring_matching) {
		return findBestMatch(vs1, vs2, delta, skip_ends, max_mut, min_chunk, COMBINED, direct, substring_matching, 1.1, 1.1, 1);
	}

	static public final Object[] findBestMatch(final VectorString3D vs1, final VectorString3D vs2, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk, final int distance_type, final boolean direct, final boolean substring_matching, final double wi, final double wd, final double wm) {

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
			final int shorter_len = shorter.length();
			final int max_offset = longer.length() - shorter_len + 1; // when of equal length, the loop runs once.
			Object[] best = null;
			for (int k=0; k<max_offset; k++) {
				final VectorString3D longer_sub = longer.substring(k, k + shorter_len);
				//Utils.log2("#######");
				//Utils.log2(k + " delta of shorter: " + shorter.getDelta());
				//Utils.log2(k + " substring_matching lengths: shorter, longer_sub : " + shorter.length() + ", " + longer_sub.length());
				//Utils.log2(k + " shorter is" + shorter + " of length " + shorter.length());
				//Utils.log2(k + " longer_sub is " + longer_sub + " made from " + longer + " with first,last: " + k + ", " + (k + shorter_len));
				final Object[] ob = direct ?
					              matchDirect(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm)
						    : matchFwdRev(shorter, longer_sub, delta, skip_ends, max_mut, min_chunk, distance_type, wi, wd, wm);
				if (null == best) best = ob;
				else {
					final double dob = ((Double)ob[1]).doubleValue();     // values generated in getScore
					final double dbest = ((Double)best[1]).doubleValue();
					// Includes DISSIMILARITY, since getScore does (1 - similarity)
					// and also COMBINED, since getScore does 1 / score
					if (dob < dbest) best = ob;
				}
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

	/** Zero is best; gets bad towards positive infinite -- including for DISSIMILARITY (1 - similarity) and COMBINED (1 / score). */
	static private final double getScore(final Editions ed, final boolean skip_ends, final int max_mut, final float min_chunk, final int distance_type) {
		switch (distance_type) {
			case LEVENSHTEIN: // Levenshtein
				return ed.getDistance();
			case DISSIMILARITY: // Dissimilarity
				// To make smaller values better, subtract from 1
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
				// To make smaller values better, make inverse
				return 1 / score(ed.getSimilarity(), ed.getDistance(), ed.getStatistics(skip_ends, max_mut, min_chunk, false)[3], Compare.W);
			case PROXIMITY: // cummulative distance relative to largest physical length of the two sequences
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[7]; // 7 is proximity
			case PROXIMITY_MUT: // cummulative distance of mutation pairs relative to largest physical length of the two sequences
				return ed.getStatistics(skip_ends, max_mut, min_chunk, false)[8]; // 8 is proximity
		}
		return Double.NaN;
	}

	static private final Object[] matchDirect(final VectorString3D vs1, final VectorString3D vs2, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk, final int distance_type, final double wi, final double wd, final double wm) {
		// Levenshtein is unfortunately not commutative: must try both
		// (Levenshtein is commutative, but the resampling I'm using makes it not be so)
		final Editions ed1 = new Editions(vs1, vs2, delta, false, wi, wd, wm);
		final double score1 = getScore(ed1, skip_ends, max_mut, min_chunk, distance_type);
		final Editions ed2 = new Editions(vs2, vs1, delta, false, wi, wd, wm);
		final double score2 = getScore(ed2, skip_ends, max_mut, min_chunk, distance_type);
		return score1 < score2 ?
			new Object[]{ed1, score1}
		      : new Object[]{ed2, score2};
	}

	// Match in all possible ways
	static private final Object[] matchFwdRev(final VectorString3D vs1, final VectorString3D vs2, final double delta, final boolean skip_ends, final int max_mut, final float min_chunk, final int distance_type, final double wi, final double wd, final double wm) {

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
			final double score = getScore(ed[i], skip_ends, max_mut, min_chunk, distance_type);
			if (score < best_score) {
				best_ed = ed[i];
				best_score = score;
				//best_score1 = score1;
			}
		}
		//Utils.log2("score, score1: " + best_score + ", " + best_score1);

		// now test also starting from the middle of the longest mutation chunk of the best matching
		try {
			final Editions ed_center = best_ed.recreateFromCenter(max_mut);
			// is null if no chunks were found
			if (null != ed_center) {
				final double score_center = getScore(ed_center, skip_ends, max_mut, min_chunk, distance_type);
				if (score_center < best_score) {
					best_ed = ed_center;
					best_score = score_center;
				}
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}

		return new Object[]{best_ed, new Double(best_score)};
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
		public double plot_max_x = 200, plot_max_y = 20;
		public int plot_width = 600, plot_height = 400;
		public boolean cut_uneven_ends = true;
		public int envelope_type = 2;
		public double delta_envelope = 1;

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
				gd.addNumericField("delta envelope:", delta_envelope, 1);
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

			this.regex = gd.getNextString().trim();
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
				delta_envelope = gd.getNextNumber();
				Utils.log2("delta_envelope has been set to " + delta_envelope);
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
			final StringBuilder sb = new StringBuilder();
			for (final String ig : ignore) {
				sb.append("(.*").append(ig).append(".*)|");
			}
			sb.setLength(sb.length() -1);
			regex_exclude = sb.toString();
		}

		Utils.logAll("Compare/gatherChains: using ignore string: " + regex_exclude);
		Utils.logAll("Compare/gatherChains: using regex: " + cp.regex);

		// gather all chains
		final ArrayList[] p_chains = new ArrayList[p.length]; // to keep track of each project's chains
		final ArrayList<Chain> chains = new ArrayList<Chain>();
		for (int i=0; i<p.length; i++) { // for each project:
			if (null == cp.regex) {
				p_chains[i] = createPipeChains(p[i].getRootProjectThing(), p[i].getRootLayerSet(), regex_exclude);
			} else {
				// Search (shallow) for cp.regex matches
				for (final ProjectThing pt : p[i].getRootProjectThing().findChildren(cp.regex, regex_exclude, true)) {
					final ArrayList<Chain> ac = createPipeChains(pt, p[i].getRootLayerSet(), regex_exclude);
					if (null == p_chains[i]) p_chains[i] = ac;
					else p_chains[i].addAll(ac);
				}
				if (null == p_chains[i]) p_chains[i] = new ArrayList<Chain>(); // empty
			}
			chains.addAll(p_chains[i]);
			// calibrate
			final Calibration cal = p[i].getRootLayerSet().getCalibrationCopy();
			for (final Chain chain : (ArrayList<Chain>)p_chains[i]) chain.vs.calibrate(cal);
		}
		final int n_chains = chains.size();

		// register all, or relative
		if (4 == cp.transform_type) {
			// '4' means relative
			// compute global average delta
			if (0 == cp.delta) {
				for (final Chain chain : chains) {
					cp.delta += ( chain.vs.getAverageDelta() / n_chains );
				}
			}
			Utils.log2("Using delta: " + cp.delta);

			for (final Chain chain : chains) {
				chain.vs.resample(cp.delta, cp.with_source); // BEFORE making it relative
				chain.vs.relative();
			}
		} else {
			if (3 == cp.transform_type) {
				// '3' means moving least squares computed from 3D landmarks
				Utils.log2("Moving Least Squares Registration based on common fiducial points");
				// Find fiducial points, if any
				final HashMap<Project,Map<String,Tuple3d>> fiducials = new HashMap<Project,Map<String,Tuple3d>>();
				for (final Project pr : p) {
					final Set<ProjectThing> fids = pr.getRootProjectThing().findChildrenOfTypeR("fiducial_points");
					if (null == fids || 0 == fids.size()) {
						Utils.log("No fiducial points found in project: " + pr);
					} else {
						fiducials.put(pr, Compare.extractPoints(fids.iterator().next())); // the first fiducial group
					}
				}
				if (!fiducials.isEmpty()) {
					// Register all VectorString3D relative to the first project:
					final List<VectorString3D> lvs = new ArrayList<VectorString3D>();
					final Calibration cal2 = p[0].getRootLayerSet().getCalibrationCopy();
					for (final Chain chain : chains) {
						final Project pr = chain.pipes.get(0).getProject();
						if (pr == p[0]) continue; // first project is reference, no need to transform.
						lvs.clear();
						lvs.add(chain.vs);
						chain.vs = transferVectorStrings(lvs, fiducials.get(pr), fiducials.get(p[0])).get(0);
						// Set (but do not apply!) the calibration of the reference project
						chain.vs.setCalibration(cal2);
					}
				}
			} else if (cp.transform_type < 3) {
				// '0', '1' and '2' involve a 3D affine computed from the 3 axes
				// no need //VectorString3D[][] vs_axes = new VectorString3D[p.length][];
				Vector3d[][] o = new Vector3d[p.length][];
				for (int i=0; i<p.length; i++) {
					// 1 - find pipes to work as axes for each project
					final ArrayList<ZDisplayable> pipes = p[i].getRootLayerSet().getZDisplayables(Line3D.class, true);
					final String[] pipe_names = new String[pipes.size()];
					for (int k=0; k<pipes.size(); k++) {
						pipe_names[k] = p[i].getMeaningfulTitle(pipes.get(k));
					}
					final int[] s = findFirstXYZAxes(cp.preset, pipes, pipe_names);

					// if axes are -1, forget it: not found
					if (-1 == s[0] || -1 == s[1] || -1 == s[2]) {
						Utils.log("Can't find axes for project " + p[i]);
						o = null;
						return null;
					}

					// obtain axes and origin
					final Object[] pack = obtainOrigin(new Line3D[]{(Line3D)pipes.get(s[0]),
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
					final Vector3d trans = new Vector3d(-o[i][3].x, -o[i][3].y, -o[i][3].z);
					final Transform3D M_query = Compare.createTransform(o[i]);
					// The transfer T transform: from query space to reference space.
					final Transform3D T = new Transform3D(M_ref);
					T.mulInverse(M_query);
					for (final Chain chain : (ArrayList<Chain>)p_chains[i]) {
						chain.vs.transform(T); // in place
					}
				}
			}
			// else, direct

			// compute global average delta, after correcting calibration and transformation
			if (0 == cp.delta) {
				for (final Chain chain : chains) {
					cp.delta += ( chain.vs.getAverageDelta() / n_chains );
				}
			}
			Utils.log2("Using delta: " + cp.delta);

			// After calibration and transformation, resample all to the same delta
			for (final Chain chain : chains) chain.vs.resample(cp.delta, cp.with_source);
		}

		return new Object[]{chains, p_chains};
	}

	static public Bureaucrat compareAllToAll(final boolean to_file, final String regex,
			                         final String[] ignore, final Project[] projects) {
		return compareAllToAll(to_file, regex, ignore, projects, false, false, 0, null);
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
	static public Bureaucrat compareAllToAll(final boolean to_file, final String regex,
			                         final String[] ignore, final Project[] projects,
						 final boolean crop, final boolean from_end, final int max_n_elements,
						 final String outgroup) {

		// gather all open projects
		final Project[] p = null == projects ? Project.getProjects().toArray(new Project[0])
			                             : projects;

		final Worker worker = new Worker("Comparing all to all") {
			@Override
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
			final SaveDialog sd = new SaveDialog("Save matrix", OpenDialog.getDefaultDirectory(), null, ".csv");
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

		// crop chains if desired
		if (crop) {
			for (final Chain chain : chains) {
				if (from_end) {
					final int start = chain.vs.length() - max_n_elements;
					if (start > 0) {
						chain.vs = chain.vs.substring(start, chain.vs.length());
						chain.vs.resample(cp.delta, cp.with_source); // BEFORE making it relative
					}
				} else {
					if (max_n_elements < chain.vs.length()) {
						chain.vs = chain.vs.substring(0, max_n_elements);
						chain.vs.resample(cp.delta, cp.with_source); // BEFORE making it relative
					}
				}
			}
		}

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

		final File f = new File(dir + filename);
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
				final StringBuffer[] titles = new StringBuffer[n_chains];
				int next = 0;
				for (int i=0; i<p.length; i++) {
					final String prefix = Utils.getCharacter(i+1);
					dos.write("\"\""); //empty upper left corner
					for (final Chain chain : (ArrayList<Chain>)p_chains[i]) {
						dos.write(",");
						titles[next] = new StringBuffer().append('\"').append(prefix).append(' ').append(chain.getCellTitle()).append('\"');
						dos.write(titles[next].toString());
						next++;
					}
				}
				dos.write("\n");
				for (int i=0; i<n_chains; i++) {
					final StringBuffer line = new StringBuffer();
					line.append(titles[i]);
					for (int j=0; j<n_chains; j++) line.append(',').append(scores[i][j]);
					line.append('\n');
					dos.write(line.toString());
				}
				dos.flush();
			} catch (final Exception e) {
				e.printStackTrace();
			}
		} else if (cp.format.equals(cp.formats[1])) {
			// as XML:
			try {
				final StringBuffer sb = new StringBuffer("<?xml version=\"1.0\"?>\n<!DOCTYPE ggobidata SYSTEM \"ggobi.dtd\">\n");
				sb.append("<ggobidata count=\"2\">\n");

				sb.append("<data name=\"Pipe Chains\">\n");
				sb.append("<description />\n");
				sb.append("<variables count=\"0\">\n</variables>\n"); // ggobi: what a crappy XML parser it has
				sb.append("<records count=\"").append(chains.size()).append("\" glyph=\"fr 1\" color=\"3\">\n");
				int next = 0;
				for (int i=0; i<p.length; i++) {
					final String prefix = Utils.getCharacter(i+1);
					final String color = new StringBuffer("color=\"").append(i+1).append('\"').toString();
					for (final Chain chain : (ArrayList<Chain>)p_chains[i]) {
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

			} catch (final Exception e) {
				e.printStackTrace();
			}
		} else if (cp.format.equals(cp.formats[2])) {
			// as Phylip .dis
			try {
				// collect different projects
				final ArrayList<Project> projects = new ArrayList<Project>();
				for (final Chain chain : chains) {
					final Project p = chain.getRoot().getProject();
					if (!projects.contains(p)) projects.add(p);
				}
				final HashSet names = new HashSet();
				final StringBuffer sb = new StringBuffer();
				sb.append(scores.length).append('\n');
				dos.write(sb.toString());

				// unique ids, since phylip cannot handle long names
				final AtomicInteger ids = new AtomicInteger(0);
				final File ftags = new File(dir + filename + ".tags");
				final OutputStreamWriter dostags = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(ftags)), "8859_1"); // encoding in Latin 1 (for macosx not to mess around


				for (int i=0; i<scores.length; i++) {
					sb.setLength(0);
					//String title = chains.get(i).getShortCellTitle().replace(' ', '_').replace('\t', '_').replace('[', '-').replace(']', '-');
					final int id = ids.incrementAndGet();
					final String sid = Utils.getCharacter(id);
					String name = chains.get(i).getShortCellTitle();
					// If sid.length() > 10 chars, trouble!
					if (sid.length() > 10) {
						Utils.log2("Ignoring " + name + " : id longer than 10 chars: " + id);
						continue;
					}
					final int k = 1;
					// Prepend a project char identifier to the name
					String project_name = "";
					if (projects.size() > 1) {
						project_name = Utils.getCharacter(projects.indexOf(chains.get(i).getRoot().getProject()) + 1).toLowerCase();
						name = project_name + name;
					}
					dostags.write(new StringBuilder().append(sid).append('\t').append(name).append('\n').toString());

					if (null != outgroup && -1 != name.indexOf(outgroup)) {
						Utils.logAll("Outgroup 0-based index is " + id + ", with id " + sid + ", with name " + name);
					}

					//
					final int len = 12;
					sb.append(sid);
					for (int j=len - sid.length(); j>0; j--) sb.append(' '); // pad with spaces up to len
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
				dostags.flush();
				dostags.close();
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}

		dos.close();


				} catch (final Exception e) {
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
			threads[ithread] = new Thread() { @Override
            public void run() {
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
	 * @param source_color_table Which colors to give to the pipes of which Project.
	 * @param show_envelope_3D If show_3D, whether to generate the variability envelope.
	 * @param envelope_alpha If show_envelope_3D, the envelope takes an alpha value between 0 (total transparency) and 1 (total opacity)
	 * @param delta_envelope The delta to resample the envelope to. When smaller than or equal to 1, no envelope resampling occurs.
	 * @param show_axes_3D If show_3D, whether to display the reference axes as well.
	 * @param heat_map If show_3D, whether to color the variability with a Fire LUT.
	 *                 If not show_condensed_3D, then the variability is shown in color-coded 3D spheres placed at the entry point to the neuropile.
	 * @param map_condensed If not null, all VectorString3D are put into this map.
	 * @param projects The projects to use.
	 * */
	static public Bureaucrat variabilityAnalysis(final Project reference_project, final String regex,
						     final String[] ignore,
						     final boolean show_cata_dialog,
						     final boolean generate_plots, final boolean show_plots, final String plot_dir_,
						     final boolean show_3D, final boolean show_condensed_3D, final boolean show_sources_3D,
						     final Map<Project,Color> sources_color_table,
						     final boolean show_envelope_3D, final float envelope_alpha, final double delta_envelope, final int envelope_type,
						     final boolean show_axes_3D, final boolean heat_map,
						     final Map<String,VectorString3D> map_condensed,
						     final Project[] projects) {
		// gather all open projects
		final Project[] p = null == projects ? Project.getProjects().toArray(new Project[0])
			                             : projects;

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
			@Override
            public void run() {
				startedWorking();
				try {

		Utils.log2("Asking for CATAParameters...");

		final CATAParameters cp = new CATAParameters();
		cp.regex = regex;
		cp.delta_envelope = delta_envelope;
		cp.envelope_type = envelope_type;
		if (show_cata_dialog && !cp.setup(false, regex, true, true)) {
			finishedWorking();
			return;
		}
		cp.with_source = true; // so source points are stored in VectorString3D for each resampled and interpolated point


		// Store a series of results, depending on options
		final HashMap<String, Display3D> results = new HashMap<String, Display3D>();


		String plot_dir = plot_dir_;
		if (generate_plots && !show_plots) {
			// Save plots
			if (null == plot_dir) {
				final DirectoryChooser dc = new DirectoryChooser("Choose plots directory");
				plot_dir = dc.getDirectory();
				if (null == plot_dir) {
					finishedWorking();
					return;
				}
			}
			if (IJ.isWindows()) plot_dir = plot_dir.replace('\\', '/');
			if (!plot_dir.endsWith("/")) plot_dir += "/";
		}

		Utils.log2("Gathering chains...");

		// Gather chains that do not match the ignore regexes
		Object[] ob = gatherChains(p, cp, ignore); // will transform them as well to the reference found in the first project in the p array
		ArrayList<Chain> chains = (ArrayList<Chain>)ob[0];
		final ArrayList[] p_chains = (ArrayList[])ob[1]; // to keep track of each project's chains
		ob = null;
		if (null == chains) {
			finishedWorking();
			return;
		}

		Utils.log2("Collecting bundles...");

		final HashMap<Project,HashMap<String,VectorString3D>> axes = new HashMap<Project,HashMap<String,VectorString3D>>();

		// Sort out into groups by unique names of lineage bundles
		final HashMap<String,ArrayList<Chain>> bundles = new HashMap<String,ArrayList<Chain>>();
		for (final Chain chain : chains) {
			String title = chain.getCellTitle();
			final String t = title.toLowerCase();
			/* // Commented out non-general code
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
			*/

			/* // Commented out non-general code
			if (0 == t.indexOf("lineage") || 0 == t.indexOf("branch")) continue; // unnamed
			*/
			if (0 == t.indexOf('[') || 0 == t.indexOf('#')) continue; // unnamed

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


		if (null != cp.regex && show_axes_3D && axes.size() < 3) {
			// Must find the Mushroom Body lobes separately
			final String cp_regex = cp.regex;
			cp.regex = "mb";
			final Object[] o = gatherChains(p, cp, ignore);
			final ArrayList<Chain> lobes = (ArrayList<Chain>)o[0];
			Utils.logAll("Found " + lobes.size() + " chains for lobes");
			for (final Chain chain : lobes) {
				final String t = chain.getCellTitle().toLowerCase();
				if (-1 != t.indexOf("peduncle")
				 || -1 != t.indexOf("medial lobe")
				 || -1 != t.indexOf("dorsal lobe")) {
					Utils.logAll("adding " + t);
					final Project pr = chain.pipes.get(0).getProject();
					HashMap<String,VectorString3D> m = axes.get(pr);
					if (null == m) {
						m = new HashMap<String,VectorString3D>();
						axes.put(pr, m);
					}
					m.put(t, chain.vs);
					continue;
				}
			}
			cp.regex = cp_regex;
		} else {
			Utils.logAll("Not: cp.regex = " + cp.regex + "  show_axes_3D = " + show_axes_3D + "  axes.size() = " + axes.size());
		}

		final HashMap<String,VectorString3D> condensed = new HashMap<String,VectorString3D>();

		Utils.log2("Condensing each bundle...");

		// Condense each into a single VectorString3D
		for (final Map.Entry<String,ArrayList<Chain>> entry : bundles.entrySet()) {
			final ArrayList<Chain> bc = entry.getValue();
			if (bc.size() < 2) {
				Utils.log2("Skipping single: " + entry.getKey());
				continue;
			}
			final VectorString3D[] vs = new VectorString3D[bc.size()];
			for (int i=0; i<vs.length; i++) vs[i] = bc.get(i).vs;
			final VectorString3D c = condense(cp, vs, this);
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
			for (final Map.Entry<String,VectorString3D> e : condensed.entrySet()) {
				final String name = e.getKey();
				final VectorString3D c = e.getValue();
				final Plot plot = makePlot(cp, name, c);
				//FAILS//plot.addLabel(10, cp.plot_height-5, name); // must be added after setting size
				if (show_plots) plot.show();
				else if (null != plot_dir) new FileSaver(plot.getImagePlus()).saveAsPng(plot_dir + name.replace('/', '-') + ".png");
			}
		}

		if (show_3D) {
			final HashMap<String,Color> heat_table = new HashMap<String,Color>();

			if (heat_map || show_envelope_3D) {
				// Create a Fire LUT
				final ImagePlus lutimp = new ImagePlus("lut", new ByteProcessor(4,4));
				IJ.run(lutimp, "Fire", "");
				final IndexColorModel icm = (IndexColorModel) lutimp.getProcessor().getColorModel();
				final byte[] reds = new byte[256];
				final byte[] greens = new byte[256];
				final byte[] blues = new byte[256];
				icm.getReds(reds);
				icm.getGreens(greens);
				icm.getBlues(blues);

				final List<String> names = new ArrayList<String>(bundles.keySet());
				Collections.sort(names);

				// find max stdDev
				double max = 0;
				final HashMap<String,Double> heats = new HashMap<String,Double>();
				for (final String name : names) {
					final VectorString3D vs_merged = condensed.get(name);
					if (null == vs_merged) {
						Utils.logAll("WARNING could not find a condensed pipe for " + name);
						continue;
					}
					final double[] stdDev = vs_merged.getStdDevAtEachPoint();
					//double avg = 0;
					//for (int i=0; i<stdDev.length; i++) avg += stdDev[i];
					//avg = avg/stdDev.length;
					Arrays.sort(stdDev);
					final double median = stdDev[stdDev.length/2]; // median is more representative than average
					if (max < median) max = median;
					heats.put(name, median);
				}

				for (final Map.Entry<String,Double> e : heats.entrySet()) {
					final String name = e.getKey();
					final double median = e.getValue();
					// scale between 0 and max to get a Fire LUT color:
					int index = (int)((median / max) * 255);
					if (index > 255) index = 255;
					final Color color = new Color(0xff & reds[index], 0xff & greens[index], 0xff & blues[index]);

					Utils.log2(new StringBuilder(name).append('\t').append(median)
									  .append('\t').append(reds[index])
									  .append('\t').append(greens[index])
									  .append('\t').append(blues[index]).toString());

					heat_table.put(name, color);
				}
			}

			final LayerSet common_ls = new LayerSet(p[0], -1, "Common", 10, 10, 0, 0, 0, 512, 512, false, 2, new AffineTransform());
			final Display3D d3d = Display3D.get(common_ls);

			float env_alpha = envelope_alpha;
			if (env_alpha < 0) {
				Utils.log2("WARNING envelope_alpha is invalid: " + envelope_alpha + "\n    Using 0.4f instead");
				env_alpha = 0.4f;
			} else if (env_alpha > 1) env_alpha = 1.0f;

			for (final String name : bundles.keySet()) {
				final ArrayList<Chain> bc = bundles.get(name);
				final VectorString3D vs_merged = condensed.get(name);
				if (null == vs_merged) {
					Utils.logAll("WARNING: could not find a condensed vs for " + name);
					continue;
				}
				if (show_sources_3D) {
					if (null != sources_color_table) {
						final HashSet<String> titles = new HashSet<String>();
						for (final Chain chain : bc) {
							final Color c = sources_color_table.get(chain.getRoot().getProject());
							final String title = chain.getCellTitle();
							String t = title;
							int i = 2;
							while (titles.contains(t)) {
								t = title + "-" + i;
								i += 1;
							}
							titles.add(t);
							Display3D.addMesh(common_ls, chain.vs, t, null != c ? c : Color.gray);
						}
					} else {
						for (final Chain chain : bc) Display3D.addMesh(common_ls, chain.vs, chain.getCellTitle(), Color.gray);
					}
				}
				if (show_condensed_3D) {
					Display3D.addMesh(common_ls, vs_merged, name + "-condensed", heat_map ? heat_table.get(name) : Color.red);
				}
				if (show_envelope_3D) {
					double[] widths = makeEnvelope(cp, vs_merged);
					if (cp.delta_envelope > 1) {
						vs_merged.addDependent(widths);
						vs_merged.resample(cp.delta_envelope);
						widths = vs_merged.getDependent(0);
					}
					Display3D.addMesh(common_ls, vs_merged, name + "-envelope", heat_map ? heat_table.get(name) : Color.red, widths, env_alpha);
				} else if (heat_map) {
					// Show spheres in place of envelopes, at the starting tip (neuropile entry point)
					final double x = vs_merged.getPoints(0)[0];
					final double y = vs_merged.getPoints(1)[0];
					final double z = vs_merged.getPoints(2)[0];
					final double r = 10;

					final Color color = heat_table.get(name);
					if (null == color) {
						Utils.logAll("WARNING: heat table does not have a color for " + name);
						continue;
					}

					final Content sphere = d3d.getUniverse().addMesh(ij3d.Mesh_Maker.createSphere(x, y, z, r), new Color3f(heat_table.get(name)), name + "-sphere", 1);
				}
			}

			if (show_axes_3D) {
				for (int i=0; i<p.length; i++) {
					final Map<String,VectorString3D> m = axes.get(p[i]);
					if (null == m) {
						Utils.log2("No axes found for project " + p[i]);
						continue;
					}
					for (final Map.Entry<String,VectorString3D> e : m.entrySet()) {
						Display3D.addMesh(common_ls, e.getValue(), e.getKey() + "-" + i, Color.gray);
					}
				}
			}

			results.put("d3d", Display3D.get(common_ls));

		}

		this.result = results;
		Utils.log2("Done.");

				} catch (final Exception e) {
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
		if (null == stdDev) return null;
		final double[] index = new double[stdDev.length];
		for (int i=0; i<index.length; i++) index[i] = i;
		Utils.log2("name is " + name);
		Utils.log2("c is " + c);
		Utils.log2("cp is " + cp);
		Utils.log2("stdDev is " + stdDev);
		Utils.log2("c.getCalibrationCopy() is " + c.getCalibrationCopy());
		Utils.log2("c.getDelta() is " + c.getDelta());
		final Calibration cal = c.getCalibrationCopy();
		if (null == cal) Utils.log2("WARNING null calibration!");
		final Plot plot = new Plot(name, name + " -- Point index (delta: " + Utils.cutNumber(c.getDelta(), 2) + " " + (null == cal ? "pixels" : cal.getUnits()) + ")", "Std Dev", index, stdDev);
		plot.setLimits(0, cp.plot_max_x, 0, cp.plot_max_y);
		plot.setSize(cp.plot_width, cp.plot_height);
		plot.setLineWidth(2);
		return plot;
	}

	/** From a condensed VectorString3D, create the radius at each point.
	 *  The maximum provides the maximum radius from the condensed VectorString3D
	 *  at @param c point-wise to the source points that generated it.
	 *  Realize that the thickness of source VectorString3Ds is not considered,
	 *  only their points.
	 */
	static public double[] makeEnvelope(final CATAParameters cp, final VectorString3D c) {
		if (cp.envelope_type <= 0) { // defensive programming
			// one std dev
			return c.getStdDevAtEachPoint();
		}

		final double[] width = new double[c.length()];

		if (cp.envelope_type < 3) { // 1 == 2std, 2 == 3std
			// two or three std dev
			final double[] std = c.getStdDevAtEachPoint();
			final int f = cp.envelope_type + 1; // so: 2 or 3
			for (int i=0; i<std.length; i++) {
				width[i] = f * std[i];
			}
		} else if (3 == cp.envelope_type) {
			// average distance from condensed to all sources
			int i=0;
			for (final ArrayList<Point3d> ap : c.getSource()) {
				double sum = 0;
				for (final Point3d p : ap) sum += c.distance(i, p);
				width[i] = sum / ap.size();
				i++;
			}
		} else if (4 == cp.envelope_type) {
			// max distance from condensed to all sources
			int i=0;
			for (final ArrayList<Point3d> ap : c.getSource()) {
				double max = 0;
				for (final Point3d p : ap) max = Math.max(max, c.distance(i, p));
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
		} catch (final Exception e) {
			IJError.print(e);
			return null;
		}

		// Else, do neighbor joining
		final float[][] scores = Compare.scoreAllToAll(vs, cp.distance_type, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.direct, cp.substring_matching, worker);
		final HashMap<Compare.Cell<VectorString3D>,Float> table = new HashMap<Compare.Cell<VectorString3D>,Float>();
		// Input the half matrix only into the table, since it's mirrored. And without the diagonal of zeros:
		for (int i=1; i<scores.length; i++) {
			for (int j=0; j<i; j++) {
				table.put(new Cell<VectorString3D>(vs[i], vs[j]), scores[i][j]);
			}
		}

		final HashSet<VectorString3D> remaining = new HashSet<VectorString3D>();
		for (final VectorString3D v : vs) remaining.add(v);

		while (table.size() > 0) {
			if (null != worker && worker.hasQuitted()) {
				return null;
			}
			// find smallest value
			float min = Float.MAX_VALUE;
			Cell<VectorString3D> cell = null;
			for (final Map.Entry<Cell<VectorString3D>,Float> e : table.entrySet()) {
				final float f = e.getValue();
				if (f < min) {
					min = f;
					cell = e.getKey();
				}
			}
			// pop cells from table
			// done below//table.remove(cell);
			for (final Iterator<Cell<VectorString3D>> it =  table.keySet().iterator(); it.hasNext(); ) {
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
			for (final VectorString3D v : remaining) {
				final Object[] ob = findBestMatch(vs_merged, v, cp.delta, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.distance_type, cp.direct, cp.substring_matching);
				final Editions ed = (Editions)ob[0];
				final float score = (float)getScore(ed, cp.skip_ends, cp.max_mut, cp.min_chunk, cp.distance_type);
				table.put(new Cell<VectorString3D>(vs_merged, v), score);
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

	/** Transform all points of all VectorString3D in vs using a Moving Least Squares Transform defined by the pairing of points in source to those in target.
	 *  In short, bring source into target. */
	static public List<VectorString3D> transferVectorStrings(final List<VectorString3D> vs, final List<Tuple3d> source, final List<Tuple3d> target, final Class<AffineModel3D> model_class) throws Exception {
		if (source.size() != target.size()) {
			Utils.log2("Could not generate a MovingLeastSquaresTransform: different number of source and target points.");
			return null;
		}
		if (source.size() < 1 || target.size() < 1) {
			Utils.log2("Cannot transform with less than one point correspondence!");
			return null;
		}

		// 1 - Create the MovingLeastSquaresTransform from the point matches

		final ArrayList<PointMatch> pm = new ArrayList<PointMatch>();
		for (final Iterator<Tuple3d> si = source.iterator(), ti = target.iterator(); si.hasNext(); ) {
			final Tuple3d sp = si.next();
			final Tuple3d tp = ti.next();
			pm.add(new PointMatch(new mpicbg.models.Point(new double[]{sp.x, sp.y, sp.z}), new mpicbg.models.Point(new double[]{tp.x, tp.y, tp.z}), 1));
		}

		final MovingLeastSquaresTransform mls = new MovingLeastSquaresTransform();
		mls.setModel(model_class);
		mls.setMatches(pm);

		final double[] point = new double[3];

		// 1.1 - Test: transfer source points
		/*
		for (final Iterator<Tuple3d> si = source.iterator(), ti = target.iterator(); si.hasNext(); ) {
			Tuple3d sp = si.next();
			point[0] = (double) sp.x;
			point[1] = (double) sp.y;
			point[2] = (double) sp.z;
			mls.applyInPlace(point);

			Tuple3d tp = ti.next();
			Utils.log2("== target: " + (double)tp.x + ", " + (double)tp.y + ", " + (double)tp.z +
				   "\n o source: " + (double)sp.x + ", " + (double)sp.y + ", " + (double)sp.z +

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
				point[0] = x[i];
				point[1] = y[i];
				point[2] = z[i];
				mls.applyInPlace(point);
				tx[i] = point[0];
				ty[i] = point[1];
				tz[i] = point[2];
			}
			try {
				vt.add(new VectorString3D(tx, ty, tz, vi.isClosed()));
			} catch (final Exception e) {}
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
		final ArrayList<ProjectThing> fiducials = fiducial.getChildren();
		if (null == fiducials || 0 == fiducials.size()) {
			Utils.log("No fiducial points can be extracted from " + fiducial);
			return null;
		}
		final Map<String,Tuple3d> fide = new HashMap<String,Tuple3d>();
		for (final ProjectThing child : fiducials) {
			final Ball ball;
			final String title;
			if (child.getType().equals("ball")) {
				// Method 1: use the ball title as the fiducial type
				ball = (Ball) child.getObject();
				title = ball.getTitle();
			} else {
				// Method 2: use the ball's parent type as the fiducial type
				final ArrayList<ProjectThing> balls = child.findChildrenOfType("ball");
				if (null == balls || 0 == balls.size()) {
					Utils.log2("Ignoring empty fiducial " + child);
					continue;
				}
				// pick the first one only
				ball = (Ball) balls.get(0).getObject();
				title = child.getType();
			}
			final double[][] b = ball.getWorldBalls(); // calibrated
			if (b.length > 0) {
				// get the first one only
				fide.put(title.toLowerCase(), new Point3d(b[0][0], b[0][1], b[0][2]));
				Utils.log2("Found fiducial point " + title.toLowerCase());
			}
		}
		return fide;
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
	 *
	 *  Uses default weights of 1.1 for costs of insertion and of deletion,
	 *  and weight of 1 for cost of mutation. Chosen from parameter exploration results.
	 */
	static public final Bureaucrat reliabilityAnalysis(final String[] ignore) {
		return reliabilityAnalysis(ignore, true, true, true, 1, 1.1, 1.1, 1);
	}

	static public final Bureaucrat reliabilityAnalysis(final String[] ignore, final boolean output_arff, final boolean weka_classify, final boolean show_dialog, final double delta, final double wi, final double wd, final double wm) {
		// gather all open projects
		final Project[] p = Project.getProjects().toArray(new Project[0]);

		final Worker worker = new Worker("Reliability by name") {
			@Override
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
		final AtomicInteger exp_bad = new AtomicInteger(0);
		final AtomicInteger obs_bad_classified_good_ones = new AtomicInteger(0);
		final AtomicInteger obs_well_classified_bad_ones = new AtomicInteger(0);
		final AtomicInteger not_found = new AtomicInteger(0); // inc by one when a lineage to compare is not found at all in the brain that works as reference
		final AtomicInteger already_classified = new AtomicInteger(0);

		Method classify_ = null;
		if (weka_classify) {
			try {
				classify_ = Class.forName("lineage.LineageClassifier").getDeclaredMethod("classify", new Class[]{double[].class});
			} catch (final Exception e) {
				IJError.print(e);
			}
		}
		final Method classify = classify_;

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
					final String t = cj.getCellTitle();
					titles_j[next++] = t.substring(0, t.indexOf(' '));
				}

				// families:
				final TreeSet<String> ts_families = new TreeSet<String>();
				for (int f=0; f<titles_j.length; f++) {
					// extract family name from title: read the first continuous string of capital letters
					final String title = titles_j[f];
					int u = 0;
					for (; u<title.length(); u++) {
						if (!Character.isUpperCase(title.charAt(u))) break;
					}
					ts_families.add(title.substring(0, u));
				}
				final ArrayList<String> families = new ArrayList<String>(ts_families);


				fus.add(exec.submit(new Callable() { @Override
                public Object call() {

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
						if (weka_classify) not_found.incrementAndGet();
						continue;
					}

					// should be there:
					if (weka_classify) {
						exp_good.incrementAndGet();
						exp_bad.addAndGet(titles_j.length - 1);
					}


					final ArrayList<ChainMatch> list = new ArrayList<ChainMatch>();

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
						final double[] stats = ed.getStatistics(cp.skip_ends, cp.max_mut, cp.min_chunk, cp.score_mut_only);
						final ChainMatch cm = new ChainMatch(cj, null, ed, stats, score(ed.getSimilarity(), ed.getDistance(), stats[3], Compare.W));
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

						if (weka_classify) {

							// from decision tree: is it good?
							final double[] param = new double[11];
							for (int p=0; p<stats.length; p++) param[p] = stats[p];
							try {
								if (((Boolean)classify.invoke(null, param)).booleanValue()) {
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
							} catch (final Exception ee) {
								IJError.print(ee);
							}
						}
					}

					// sort scores:
					Compare.sortMatches(list, cp.distance_type, cp.distance_type_2, cp.min_matches);

					if (output_arff) {
						// Take top 8 and put them into training set for WEKA in arff format
						for (int h=0; h<8; h++) {
							final ChainMatch cm = list.get(h);
							final StringBuilder sb = new StringBuilder();
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


					for (final ChainMatch cm : list) {
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

		for (final Future fu : fus) {
			try { fu.get(); } catch (final Exception e) { IJError.print(e); }
		}
		exec.shutdownNow();

		if (weka_classify) {
			// so stateful ... it's a sin.
			try {
				Class.forName("lineage.LineageClassifier").getDeclaredMethod("flush", new Class[]{}).invoke(null, new Object[]{});
			} catch (final Exception e) {
				IJError.print(e);
			}
		}

		// export ARFF for neural network training
		if (output_arff) {
			Utils.saveToFile(new File(System.getProperty("user.dir") + "/lineages.arff"), arff.toString());
		}


		// Show the results from indices map

		final StringBuilder sb = new StringBuilder();

		final TreeMap<Integer,Integer> sum = new TreeMap<Integer,Integer>(); // scoring index vs count of occurrences

		final TreeMap<Integer,Integer> sum_f = new TreeMap<Integer,Integer>(); // best scoring index of best family member vs count of ocurrences

		final TreeMap<String,TreeMap<Integer,Integer>> sum_fw = new TreeMap<String,TreeMap<Integer,Integer>>(); // scoring index vs count of ocurrences, within each family

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
			for (final int i : ci.list) {
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

				// Same thing but not for all lineages, but only for lineages within a family:
				// extract family name from title: read the first continuous string of capital letters
				int u = 0;
				for (; u<ci.title.length(); u++) {
					if (!Character.isUpperCase(ci.title.charAt(u))) break;
				}
				final String family_name = ci.title.substring(0, u);
				TreeMap<Integer,Integer> sfw = sum_fw.get(family_name);
				if (null == sfw) {
					sfw = new TreeMap<Integer,Integer>();
					sum_fw.put(family_name, sfw);
				}
				sfw.put(oi, (sfw.containsKey(oi) ? sfw.get(oi) : 0) + 1);
			}
			if (0 != count) sb.append(ci.title).append(' ').append(last+1).append(' ').append(count).append('\n');

			// find the very-off ones:
			if (last > 6) {
				Utils.log2("BAD index " + last + " for chain " + ci.title  + " " + ci.chain.getRoot() + " of project " + ci.chain.getRoot().getProject());
			}
		}
		sb.append("===============================\n");

		/// family score:
		for (final CITuple ci : cin_f) {
			// sort indices in place
			Collections.sort(ci.list);
			// count occurrences of each scoring index
			int last = 0; // lowest possible index
			int count = 1;
			for (final int i : ci.list) {
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

		//
		{
			sb.append("Global count of index ocurrences:\n");
			int total = 0;
			int top2 = 0;
			int top5 = 0;
			for (final Map.Entry<Integer,Integer> e : sum.entrySet()) {
				sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
				total += e.getValue();
				if (e.getKey() < 2) top2 += e.getValue();
				if (e.getKey() < 5) top5 += e.getValue();
			}
			sb.append("total: ").append(total).append('\n');
			sb.append("top1: ").append( sum.get(sum.firstKey()) / (float)total).append('\n');
			sb.append("top2: ").append( top2 / (float)total).append('\n');
			sb.append("top5: ").append( top5 / (float) total).append('\n');

			sb.append("===============================\n");
		}

		sb.append("Family-wise count of index ocurrences:\n");
		for (final Map.Entry<String,TreeMap<Integer,Integer>> fe : sum_fw.entrySet()) {
			int total = 0;
			int top5 = 0;
			for (final Map.Entry<Integer,Integer> e : fe.getValue().entrySet()) {
				sb.append(fe.getKey()).append(' ').append(e.getKey()).append(' ').append(e.getValue()).append('\n');
				total += e.getValue();
				if (e.getKey() < 5) top5 += e.getValue();
			}
			sb.append("total: ").append(total).append('\n');
			sb.append("top1: ").append( fe.getValue().get(fe.getValue().firstKey()) / (float)total).append('\n');
			sb.append("top5: ").append( top5 / (float)total).append('\n');
		}
		sb.append("===============================\n");


		// - the percent of first score being the correct one:
		double first = 0;
		double first_5 = 0;
		double all = 0;
		for (final Map.Entry<Integer,Integer> e : sum.entrySet()) {
			final int k = e.getKey();
			final int a = e.getValue();

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
		for (final Map.Entry<Integer,Integer> e : sum_f.entrySet()) {
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

		final TreeMap<Integer,ArrayList<String>> hsc = new TreeMap<Integer,ArrayList<String>>();

		for (final CITuple ci : cin) {
			final int size = ci.list.size();
			ArrayList<String> al = hsc.get(size);
			if (null == al) {
				al = new ArrayList<String>();
				hsc.put(size, al);
			}
			// Count the number of 0s -- top scoring
			int count = 0;
			for (final Integer i : ci.list) {
				if (0 == i) count++;
				else break;
			}
			al.add(new StringBuffer(ci.title).append(" =").append(count).append('/').append(ci.list.size()).append('\n').toString());
		}
		// Then just print:
		for (final Map.Entry<Integer,ArrayList<String>> e : hsc.entrySet()) {
			sb.append("For ").append(e.getKey()).append(" matches:\n");
			for (final String s : e.getValue()) sb.append(s);
		}

		sb.append("=========================\n");

		// Family-wise, count the number of zeros per family:
		sb.append("Number of top scoring per family:\n");
		final TreeMap<String,String> family_scores = new TreeMap<String,String>();
		for (final CITuple ci : cin_f) {
			int count = 0;
			for (final Integer i : ci.list) {
				if (0 == i) count++;
				else break; // ci.list is sorted
			}
			family_scores.put(ci.title, new StringBuilder().append(ci.title).append(" =").append(count).append('/').append(ci.list.size()).append('\n').toString());
		}
		// Now print sorted by family name:
		for (final String s : family_scores.values()) {
			sb.append(s);
		}

		sb.append("=========================\n");

		// Keep in mind it should all be repeated for 0.5 micron delta, 0.6, 0.7 ... up to 5 or 10 (until the histogram starts getting worse.) The single value with which the graph coould be made is the % of an index of 1, and of an index of 2.
		//
		// TODO

		if (weka_classify) {
			sb.append("Decision tree:\n");
			sb.append("Expected good matches: " + exp_good.get() + "\n");
			sb.append("Expected bad matches: " + exp_bad.get() + "\n");
			sb.append("Observed good matches: " + obs_good.get() + "\n");
			sb.append("Observed bad matches: " + obs_wrong.get() + "\n");
			sb.append("Observed well classified bad ones: " + obs_well_classified_bad_ones.get() + "\n");
			sb.append("Observed bad classified good ones: " + obs_bad_classified_good_ones.get() + "\n");
			sb.append("Not found, so skipped: " + not_found.get() + "\n");
			sb.append("Already classified: " + already_classified.get() + "\n");

			sb.append("=========================\n");
		}


		if (output_arff) {
			Utils.log(sb.toString());
		} else {
			Utils.log2(sb.toString());
		}


				} catch (final Exception e) {
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
		CITuple(final String t, final Chain c, final ArrayList<Integer> l) {
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
		final double MAX_DELTA = 20;
		final double INC_DELTA = 0.1;

		final double MIN_WEIGHT = 0;
		final double MAX_WEIGHT = 2;
		final double INC_WEIGHT = 0.1;

		return Bureaucrat.createAndStart(new Worker.Task("Space Exploration") { @Override
        public void exec() {

		final File f = new File(System.getProperty("user.dir") + "/lineage_space_exploration.data");
		OutputStreamWriter dos = null;

		try {
			dos = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(f)), "8859_1"); // encoding in Latin1

			for (double delta = MIN_DELTA; delta <= (MAX_DELTA + INC_DELTA/2); delta += INC_DELTA) {
				for (double weight = MIN_WEIGHT; weight <= (MAX_WEIGHT + INC_WEIGHT/2); weight += INC_WEIGHT) {

					final Bureaucrat b = Compare.reliabilityAnalysis(ignore, false, false, false, delta, weight, weight, 1); // WM = 1
					b.join();

					final double[] result = (double[]) b.getWorker().getResult();

					final StringBuilder sb = new StringBuilder();
					sb.append(delta).append('\t')
					  .append(weight).append('\t')
					  .append(result[0]).append('\t')
					  .append(result[1]).append('\n');

					dos.write(sb.toString());


					dos.flush(); // so I get to see something before the whole giant buffer is full

					Utils.log2("===========================\n\n");
					//Utils.log2("delta: " + delta + " weight: " + weight + " top_one: " + result[0] + " top_5: " + result[1]);
					Utils.log2(sb.toString());
					Utils.log2("===========================\n\n");
				}
			}

			dos.flush();
			dos.close();
		} catch (final Exception e) {
			try { dos.close(); } catch (final Exception ee) { ee.printStackTrace(); }
		}

		}}, Project.getProjects().toArray(new Project[0]));
	}
}
