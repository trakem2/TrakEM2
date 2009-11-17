/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2009 Albert Cardona.

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

package ini.trakem2.display;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;

import ini.trakem2.imaging.LayerStack;
import ini.trakem2.Project;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import ini.trakem2.vector.VectorString3D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.BasicStroke;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.TreeMap;

import javax.vecmath.Point3f;

// Ideally, this class would use a linked list of node points, where each node could have a list of branches, which would be in themselves linked lists of nodes and so on.
// That would make sense, and would make re-rooting and removing nodes (with their branches) trivial and fast.
// In practice, I want to reuse Polyline's semiautomatic tracing and thus I am using Polylines for each slab.

/** A sequence of points ordered in a set of connected branches. */
public class Treeline extends ZDisplayable {

	protected Branch root;

	private final class Slab extends Polyline {
		Slab(Project project, LayerSet layer_set) {
			super(project, -1, Treeline.this.title, 0, 0, Treeline.this.alpha, true, Treeline.this.color, false, Treeline.this.at);
			this.layer_set = layer_set;
		}
		/** For cloning. */
		private Slab(Project project, long id, String title, double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
			super(project, id, title, width, height, alpha, visible, color, locked, at);
		}

		@Override
		public void updateBucket() {} // disabled

		@Override
		public void repaint(boolean repaint_navigator) {} // disabled

		/** Copies the pointer to the LayerSet too. @param copy_id is ignored; ids are always -1, the Slab is a transient object. */
		@Override
		public Slab clone(Project pr, boolean copy_id) {
			Slab copy = new Slab(pr, -1, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, this.at);
			// The data:
			copy.n_points = n_points;
			copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
			copy.p_layer = (long[])this.p_layer.clone();
			copy.layer_set = layer_set;
			// don't add to database
			return copy;
		}

		/** Both inclusive. */
		@Override
		public Slab sub(int start, int end) {
			Slab sub = new Slab(project, -1, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, this.at);
			sub.n_points = end - start + 1;
			sub.p[0] = Utils.copy(this.p[0], start, sub.n_points);
			sub.p[1] = Utils.copy(this.p[1], start, sub.n_points);
			sub.p_layer = new long[sub.n_points];
			System.arraycopy(this.p_layer, start, sub.p_layer, 0, sub.n_points);

			Utils.log2(sub.p_layer);

			return sub;
		}
	}

	/** A branch only holds the first point if it doesn't have any parent. */
	private final class Branch {

		final Branch parent;

		HashMap<Integer,ArrayList<Branch>> branches = null;

		final Slab pline;

		/** The branch to avoid will be the new parent of this branch.
		 *  If avoid is null, then the returned new Branch is the new root. */
		Branch reRoot(final int index, final Branch avoid) {
			// Split pline in two segments:
			Slab s0 = null,
			     s1 = null;
			if (index > 0) {
				s0 = pline.sub(0, index-1);
				s0.reverse();
				Utils.log2("Created s0 of length " + s0.n_points);
			}
			if (index < pline.n_points -1) {
				s1 = pline.sub(index, pline.n_points -1);
				Utils.log2("Created s1 of length " + s1.n_points);
			}
			// Determine longest segment: from 0 to index, or from index to end
			// The longest segment becomes the root branch
			Branch root,
			       left = null,
			       right = null;
			if (index > pline.n_points - index +1) {
				root = left = new Branch(avoid, s0);
				if (null != s1) {
					right = new Branch(root, s1);
					root.add(right, 0);
				}
			} else {
				root = right = new Branch(avoid, s1);
				if (null != s0) {
					left = new Branch(root, s0);
					root.add(left, 0);
				}
			}
			// Add child branches as clones, except the branch to avoid
			if (null != branches) {
				for (Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
					int i = e.getKey();
					ArrayList<Branch> bs = e.getValue();
					if (i < index) {
						if (null != left) {
							for (Branch b : bs) {
								if (avoid == b) continue;
								left.add(b.clone(project, left), index - i -1);
							}
						}
					} else {
						if (null != right) {
							for (Branch b : bs) {
								if (avoid == b) continue;
								right.add(b.clone(project, right), i);
							}
						}
					}
				}
			}
			// Add parent as branches at last point of left
			if (null != parent) {
				// Search at what index was this branch set as a child
				int i = -1;
				out: for (Map.Entry<Integer,ArrayList<Branch>> e : parent.branches.entrySet()) {
					for (Branch b : e.getValue()) {
						if (b == this) {
							i = e.getKey();
							break out;
						}
					}
				}
				// Reroot parent at index i, and add it to the left at what was the 0 point:
				left.add(parent.reRoot(i, this), left.pline.n_points -1);
			}

			// Provide a layer_set pointer to all new branches
			root.setLayerSet(Treeline.this.layer_set);

			return root;
		}

		private void setLayerSet(final LayerSet layer_set) {
			pline.setLayerSet(layer_set);
			if (null == branches) return;
			for (ArrayList<Branch> bs : branches.values()) {
				for (Branch b : bs) {
					b.setLayerSet(layer_set); }}
		}

		Branch clone(final Project project, final Branch parent_copy) {
			final Branch copy = new Branch(parent_copy, null == this.pline ? null : this.pline.clone(project, true));
			if (null != branches) {
				copy.branches = new HashMap<Integer,ArrayList<Branch>>();
				for (Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
					ArrayList<Branch> a = new ArrayList<Branch>();
					for (Branch b : e.getValue()) {
						a.add(b.clone(project, copy));
					}
					copy.branches.put(e.getKey(), a);
				}
			}
			return copy;
		}

		Branch(Branch parent, Slab pline) {
			this.parent = parent;
			this.pline = pline;
		}

		Branch(Branch parent, double first_x, double first_y, long layer_id, Project project, LayerSet layer_set) {
			this.parent = parent;
			// Create a new Slab with an invalid id -1:
			// TODO 
			//   - each Slab could have its own bounding box, to avoid iterating all
			// Each Slab has its own AffineTransform -- passing it in the constructor merely sets its values
			this.pline = new Slab(project, layer_set);
			this.pline.addPoint((int)first_x, (int)first_y, layer_id, 1.0);
		}

		/** From XML  -- WARNING this reader is fragile, expects EXACT text as generated by exportXML, except for the indentation. */
		Branch(final Branch parent, final String s) {
			this.parent = parent;
			// Could parse the map first, but its not needed so far.
			// 1 - Parse the slab
			final int first = s.indexOf('(');
			final int last = s.indexOf(')', first+1);
			final String[] coords = s.substring(first+1, last).split(" ");
			this.pline = new Slab(getProject(), getLayerSet());
			for (int i=0; i<coords.length; i+=3) {
				this.pline.appendPoint(Integer.parseInt(coords[i]),
						       Integer.parseInt(coords[i+1]),
						       Long.parseLong(coords[i+2]));
			}
			// 2 - parse the branches
			final int ibranches = s.indexOf(":branches", last+1);
			if (-1 != ibranches) {
				final int len = s.length();
				int open = s.indexOf('{', ibranches + 9);
				while (-1 != open) {
					int end = open + 1; // don't read the first curly bracket
					int level = 1;
					for(; end < len; end++) {
						switch (s.charAt(end)) {
							case '{': level++; break;
							case '}': level--; break;
						}
						if (0 == level) break;
					}
					// Extract branch index
					int openbranch = s.indexOf('{', open+1);
					add(new Branch(this, s.substring(open, end)), Integer.parseInt(s.substring(open+1, openbranch-1))); // would need to trim() to ensure parseInt doesn't fail. I'm running on lots of assumptions.

					open = s.indexOf('{', end+1);
				}
			}
		}

		/** Create a sub-branch at index i, with new point x,y,layer_id.
		 *  @return the new child Branch. */
		final Branch fork(int i, double x, double y, long layer_id) {
			return add(new Branch(this, x, y, layer_id, getProject(), getLayerSet()), i);
		}

		final Branch add(Branch child, int i) {
			if (null == branches) branches = new HashMap<Integer,ArrayList<Branch>>();
			ArrayList<Branch> list = branches.get(i);
			if (null == list) {
				list = new ArrayList<Branch>();
				branches.put(i, list);
			}
			list.add(child);
			return child;
		}

		/** Paint recursively into branches. */
		final void paint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer, final Stroke branch_stroke, final boolean no_color_cues, final double current_z) {
			this.pline.paint(g, magnification, active, channels, active_layer);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				final int i = e.getKey();
				final Point2D.Double po1 = pline.transformPoint(pline.p[0][i], pline.p[1][i]);
				final double z = layer_set.getLayer(pline.p_layer[i]).getZ();
				boolean paint_link = true;
				Color c = getColor();
				if (z < current_z) {
					if (no_color_cues) paint_link = false;
					else c = Color.red;
				}
				else if (z == current_z) {}  // c = Treeline.this.color
				else if (no_color_cues) paint_link = false;
				else c = Color.blue;

				for (final Branch b : e.getValue()) {
					b.paint(g, magnification, active, channels, active_layer, branch_stroke, no_color_cues, current_z);
					// Paint from i in this.pline to 0 in b.pline
					if (paint_link) {
						final Point2D.Double po2 = pline.transformPoint(b.pline.p[0][0], b.pline.p[1][0]);
						g.setColor(c);
						Stroke st = g.getStroke();
						if (null != branch_stroke) g.setStroke(branch_stroke);
						g.drawLine((int)po1.x, (int)po1.y, (int)po2.x, (int)po2.y);
						g.setStroke(st); // restore
					}
				}
			}
		}
		final boolean intersects(final Area area, final double z_first, final double z_last) {
			if (null != pline && pline.intersects(area, z_first, z_last)) return true;
			if (null == branches) return false;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					if (b.intersects(area, z_first, z_last)) {
						return true;
					}
				}
			}
			return false;
		}
		final boolean linkPatches() {
			boolean must_lock = null != pline && pline.linkPatches();
			if (null == branches) return must_lock;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					must_lock = must_lock || b.linkPatches();
				}
			}
			return must_lock;
		}
		/** Return min_x, min_y, max_x, max_y of all nested Slab. */
		final double[] calculateDataBoundingBox(double[] m) {
			if (null == pline) return m;
			final double[] mp = pline.calculateDataBoundingBox();
			if (null == m) {
				m = mp;
			} else {
				m[0] = Math.min(m[0], mp[0]);
				m[1] = Math.min(m[1], mp[1]);
				m[2] = Math.max(m[2], mp[2]);
				m[3] = Math.max(m[3], mp[3]);
			}
			if (null == branches) return m;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					m = b.calculateDataBoundingBox(m);
				}
			}
			return m;
		}
		/** Subtract x,y from all points of all nested Slab. */
		final void subtract(final double min_x, final double min_y) {
			if (null == pline) return;
			for (int i=0; i<pline.n_points; i++) {
				pline.p[0][i] -= min_x;
				pline.p[1][i] -= min_y;
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.subtract(min_x, min_y);
				}
			}
		}
		/** Return the lowest Z Layer of all nested Slab. */
		final Layer getFirstLayer() {
			Layer first = pline.getFirstLayer();
			if (null == branches) return first;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					final Layer la = b.getFirstLayer();
					if (la.getZ() < first.getZ()) first = la;
				}
			}
			return first;
		}

		final void setAffineTransform(AffineTransform at) {
			pline.setAffineTransform(at);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.setAffineTransform(at);
				}
			}
		}

		/** Returns the Slab for which x_l,y_l is closest to either its 0 or its N-1 point in 3D space.
		 *  List[0] = Branch
		 *  List[1] = double[2] with distance to 0 and to n_points-1 */
		final List findClosestEndPoint(final double x_l, final double y_l, final long layer_id) {
			Branch bmin = null;
			double[] dmin = null;
			double min = Double.MAX_VALUE;
			for (final Branch b : getAllBranches()) {
				final double[] d = b.pline.sqDistanceToEndPoints(x_l, y_l, layer_id);
				if (null == dmin || d[1] < min || d[0] < min) {
					min = Math.min(d[0], d[1]);
					dmin = d;
					bmin = b;
				}
			}
			return Arrays.asList(new Object[]{bmin, dmin});
		}
		/** Returns the branch that got the new point and its index in it.
		 *  List[0] = Branch
		 *  List[1] = Integer */
		final List addPoint(final double x_l, final double y_l, final long layer_id, final double mag) {
			List list = findClosestSegment(x_l, y_l, layer_id, mag);
			int index = -1;
			Branch bmin = null;
			if (list.size() > 0) {
				bmin = (Branch) list.get(0);
				index = ((Integer)list.get(1)).intValue();
			}
			if (-1 == index) {
				list = findClosestEndPoint(x_l, y_l, layer_id);
				bmin = (Branch) list.get(0);
				index = bmin.pline.addPoint((int)x_l, (int)y_l, layer_id, mag);
			} else {
				index++; // insert after
				bmin.pline.insertPoint(index, (int)x_l, (int)y_l, layer_id);
			}
			if (null != bmin.branches) {
				// shift branches!
				final HashMap<Integer,ArrayList<Branch>> m = new HashMap<Integer,ArrayList<Branch>>(bmin.branches);
				bmin.branches.clear();
				for (Map.Entry<Integer,ArrayList<Branch>> e : m.entrySet()) {
					int i = e.getKey();
					bmin.branches.put(e.getKey() + (index > i ? 0 : 1), e.getValue());
				}
			}
			return Arrays.asList(new Object[]{bmin, index});
		}
		final void removePoint(final int i) {
			pline.removePoint(i);
			// shift all branches if it wasn't the last!
			if (null != branches && i != pline.n_points -2) {
				// shift all branches!
				final HashMap<Integer,ArrayList<Branch>> m = new HashMap<Integer,ArrayList<Branch>>(branches);
				branches.clear();
				for (Map.Entry<Integer,ArrayList<Branch>> e : m.entrySet()) {
					final int k = e.getKey();
					branches.put(k - (k < i ? 0 : 1), e.getValue());
				}
			}
		}
		final public String toString() {
			StringBuilder sb = new StringBuilder("Branch n_points=").append(pline.n_points)
				.append(" first=").append(pline.p[0][0]).append(',').append(pline.p[1][0]);
			if (pline.n_points > 1) sb.append(" last=").append(pline.p[0][pline.n_points-1]).append(',').append(pline.p[1][pline.n_points-1]);
			return sb.toString();

		}
		final List<Branch> getAllBranches() {
			final ArrayList<Branch> all = new ArrayList<Branch>();
			getAllBranches(all);
			return all;
		}
		/** Ordered depth-first. */
		final void getAllBranches(final List<Branch> all) {
			all.add(this);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.getAllBranches(all);
				}
			}
		}
		/** Depth-first search.
		 *  List[0] = Branch
		 *  List[1] = Integer */
		final List findPoint(final int x_pl, final int y_pl, final long layer_id, final double magnification) {
			final ArrayList pi = new ArrayList();
			findPoint(x_pl, y_pl, layer_id, magnification, pi);
			return pi;
		}
		/** Depth-first search. */
		final private void findPoint(final int x_pl, final int y_pl, final long layer_id, final double magnification, final List pi) {
			final int i = pline.findPoint(x_pl, y_pl, layer_id, magnification);
			if (-1 != i) {
				pi.add(this);
				pi.add(i);
				return;
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.findPoint(x_pl, y_pl, layer_id, magnification, pi);
				}
			}
		}
		final List findNearestPoint(final int x_pl, final int y_pl, final long layer_id) {
			final TreeMap<Double,List> m = new TreeMap<Double,List>();
			findNearestPoint(x_pl, y_pl, layer_id, m);
			return m.get(m.firstKey());
		}
		final private void findNearestPoint(final int x_pl, final int y_pl, final long layer_id, final TreeMap<Double,List> m) {
			final int i = Displayable.findNearestPoint(pline.p, pline.n_points, x_pl, y_pl);
			if (-1 != i) {
				ArrayList pi = new ArrayList();
				pi.add(this);
				pi.add(i);
				m.put(Math.pow(x_pl - pline.p[0][i], 2) + Math.pow(y_pl - pline.p[1][i], 2), pi);
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.findNearestPoint(x_pl, y_pl, layer_id, m);
				}
			}
		}
		/** Finds the closest segment to x_l,y_l that has a point in layer_id. */
		final List findClosestSegment(final double x_l, final double y_l, final long layer_id, final double mag) {
			final ArrayList pi = new ArrayList();
			findClosestSegment(x_l, y_l, layer_id, mag, pi);
			return pi;
		}
		final void findClosestSegment(final double x_l, final double y_l, final long layer_id, final double mag, final List pi) {
			final int i = pline.findClosestSegment((int)x_l, (int)y_l, layer_id, mag);
			if (-1 !=  i && (layer_id == pline.p_layer[i] || (i != (pline.n_points -1) && layer_id == pline.p_layer[i+1]))) {
				// The 'if' above doesn't comply with the docs for this fn but almost.
				pi.add(this);
				pi.add(i);
				return;
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.findClosestSegment(x_l, y_l, layer_id, mag, pi);
				}
			}
		}

		final void exportXML(StringBuffer sb_body, String indent) {
			sb_body.append("{:slab (");
			for (int i=0; i<pline.n_points; i++) {
				sb_body.append((int)pline.p[0][i]).append(' ')
				       .append((int)pline.p[1][i]).append(' ')
				       .append(pline.p_layer[i]).append(' ');
			}
			sb_body.setLength(sb_body.length()-1); // remove last space
			sb_body.append(')');
			if (null != branches) {
				sb_body.append('\n').append(indent);
				if (null != parent) sb_body.append("   ");
				sb_body.append(" :branches\n");
				String in = indent + "\t";
				for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
					for (final Branch b : e.getValue()) {
						sb_body.append(in).append('{').append(e.getKey()).append(' ');
						b.exportXML(sb_body, in);
						sb_body.append("}\n");
					}
				}
				// remove last indent
				sb_body.setLength(sb_body.length()-1);
			}
			sb_body.append('}');
		}

		/** Takes a continuous list of Point3f and returns a list with 0,1,1,2,2,3,3,4   n-1,n-1,n. */
		final private List asPairwise(final List list) {
			final ArrayList l = new ArrayList();
			for (int i=1; i<list.size(); i++) {
				l.add(list.get(i-1));
				l.add(list.get(i));
			}
			return l;
		}

		final void generateTriangles(final List list, final double scale, final int parallels, final int resample, final Calibration cal) {
			if (null == parent) {
				list.addAll(asPairwise(pline.generateTriangles(scale, parallels, resample)));
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				final int i = e.getKey();
				final Point2D.Double po = pline.transformPoint(pline.p[0][i], pline.p[1][i]);
				final float x = (float) (po.x * scale * resample * cal.pixelWidth);
				final float y = (float) (po.y * scale * resample * cal.pixelHeight);
				final float z = (float) (pline.layer_set.getLayer(pline.p_layer[i]).getZ() * scale * resample * cal.pixelWidth);

				for (final Branch b : e.getValue()) {
					b.pline.setLayerSet(pline.layer_set); // needed to retrieve Z coord of layers.
					List l = asPairwise(b.pline.generateTriangles(scale, parallels, resample, cal));
					l.add(0, l.get(0));
					l.add(0, new Point3f(x, y, z));
					list.addAll(l);
					b.generateTriangles(list, scale, parallels, resample, cal);
				}
			}
		}
	}

	public Treeline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
	}

	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	public Treeline(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at, final Branch root_source) {
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.root = root_source;
		this.root.setAffineTransform(this.at);
	}

	/** To reconstruct from XML. */
	public void parse(final StringBuilder sb) {
		this.root = new Branch(null, Utils.trim(sb));
	}

	final public void paint(Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (null == root) {
			setupForDisplay();
			if (null == root) return;
		}

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		final BasicStroke DASHED_STROKE = new BasicStroke(1/(float)magnification, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3, new float[]{ 6, 4, 2, 4 }, 0);

		root.paint(g, magnification, active, channels, active_layer, DASHED_STROKE, "true".equals(project.getProperty("no_color_cues")), active_layer.getZ());

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	synchronized protected void calculateBoundingBox(final boolean adjust_position) {
		// Call calculateDataBoundingBox for each Branch and find out absolute min,max. All points are local to this TreeLine AffineTransform.
		if (null == root) return;
		final double[] m = root.calculateDataBoundingBox(null);
		if (null == m) return;

		this.width = m[2] - m[0];  // max_x - min_x;
		this.height = m[3] - m[1]; // max_y - min_y;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			root.subtract(m[0], m[1]);
			this.at.translate(m[0], m[1]) ; // (min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			root.setAffineTransform(this.at);
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		layer_set.updateBucket(this);
	}

	public void repaint() {
		repaint(true);
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// TODO
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		return null == root ? false
				    : root.intersects(area, z_first, z_last);
	}

	public Layer getFirstLayer() {
		if (null == root) return null;
		return root.getFirstLayer();
	}

	public boolean linkPatches() {
		if (null == root) return false;
		return root.linkPatches();
	}

	public Treeline clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		return new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at, root.clone(project, null));
	}

	public boolean isDeletable() {
		return null == root || null == root.pline;
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN != ProjectToolbar.getToolId()) {
			return;
		}
		final long layer_id = Display.getFrontLayer(this.project).getId();
		// transform the x_p, y_p to the local coordinates
		int x_pl = x_p;
		int y_pl = y_p;
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_pl = (int)po.x;
			y_pl = (int)po.y;
		}

		if (null != root) {
			Branch branch = null;
			int i = -1;
			List pi = root.findPoint(x_pl, y_pl, layer_id, mag);
			if (2 == pi.size()) {
				branch = (Branch)pi.get(0);
				i = ((Integer)pi.get(1)).intValue();
			}
			if (me.isShiftDown() && Utils.isControlDown(me)) {
				if (-1 == i) {
					pi = root.findNearestPoint(x_pl, y_pl, layer_id);
					branch = (Branch)pi.get(0);
					i = ((Integer)pi.get(1)).intValue();
				}
				// Remove point, and associated branches
				if (-1 != i && layer_id == branch.pline.p_layer[i]) {
					if (null != branch.branches) {
						branch.branches.remove(i);
					}
					branch.removePoint(i);
					repaint(false); // keep larger size for repainting, will call calculateBoundingBox on mouseRelesed
					active = null;
					index = -1;
				}
				// In any case, terminate
				return;
			}
			if (-1 != i) {
				if (me.isShiftDown()) {
					// Create new branch at point, with local coordinates
					active = branch.fork(i, x_pl, y_pl, layer_id);
					index = 0;
					return;
				}
				// Setup point i to be dragged
				index = i;
				active = branch;
				return;
			} else {
				// Add new point
				// Find the point closest to any other starting or ending point in all branches
				List list = root.addPoint(x_pl, y_pl, layer_id, mag);
				active = (Branch)list.get(0);
				index = ((Integer)list.get(1)).intValue();
				repaint(true);
				return;
			}
		} else {
			root = new Branch(null, x_pl, y_pl, layer_id, project, layer_set);
			active = root;
			index = 0;
		}
	}

	private Branch active = null;
	private int index = -1;

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (null == active) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}
		active.pline.dragPoint(index, x_d - x_d_old, y_d - y_d_old);
		repaint(false);
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			repaint(true); //needed at least for the removePoint
		}

		if (-1 == index || null == active) return;

		active.pline.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
		repaint();

		active = null;
		index = -1;
	}

	/** Call super and propagate to all branches. */
	public void setAffineTransform(AffineTransform at) {
		super.setAffineTransform(at);
		if (null != root) root.setAffineTransform(at);
	}

	/** Call super and propagate to all branches. */
	public void preTransform(final AffineTransform affine, final boolean linked) {
		super.preTransform(affine, linked);
		if (null != root) root.setAffineTransform(this.at);
	}

	/** Exports to type t2_treeline. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_treeline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_treeline (").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2);
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_treeline\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		super.restXML(sb_body, in, any);
		sb_body.append(indent).append(">\n");
		if (null != root) {
			sb_body.append(in);
			root.exportXML(sb_body, in);
			sb_body.append('\n');
		}
		sb_body.append(indent).append("</t2_treeline>\n");
	}

	public List generateTriangles(double scale, int parallels, int resample) {
		ArrayList list = new ArrayList();
		root.generateTriangles(list, scale, parallels, resample, layer_set.getCalibrationCopy());
		return list;
	}

	@Override
	final Class getInternalDataPackageClass() {
		return DPTreeline.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPTreeline(this);
	}

	static private final class DPTreeline extends Displayable.DataPackage {
		final Branch root;
		DPTreeline(final Treeline tline) {
			super(tline);
			this.root = null == tline.root ? null : tline.root.clone(tline.project, null);
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Treeline tline = (Treeline)d;
			tline.root = null == this.root ? null : this.root.clone(tline.project, null);
			return true;
		}
	}

	/** Reroots at the point closest to the x,y,layer_id world coordinate. */
	synchronized public void reRoot(double x, double y, long layer_id) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = po.x;
			y = po.y;
		}
		List pi = root.findNearestPoint((int)x, (int)y, layer_id);
		Branch branch = (Branch)pi.get(0);
		int i = ((Integer)pi.get(1)).intValue();

		Utils.log2("point was: " + x + ", " + y + ", " + layer_id);
		Utils.log2("Rerooting at index " + i + " for branch of length " + branch.pline.n_points);

		root = branch.reRoot(i, null);
	}
}
