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
import ij.gui.GenericDialog;

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
import java.awt.Dimension;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.TreeMap;

import javax.vecmath.Point3f;

// Ideally, this class would use a linked list of node points, where each node could have a list of branches, which would be in themselves linked lists of nodes and so on.
// That would make sense, and would make re-rooting and removing nodes (with their branches) trivial and fast.
// In practice, I want to reuse Polyline's semiautomatic tracing and thus I am using Polylines for each slab.

/** A sequence of points ordered in a set of connected branches. */
public class Treeline extends ZDisplayable {

	/** Maximum possible confidence in an edge (ranges from 0 to 5, inclusive).*/
	static public final byte MAX_EDGE_CONFIDENCE = 5;

	static private float last_radius = -1;

	static private final Comparator<Layer> COMP_LAYERS = new Comparator<Layer>() {
		public final int compare(final Layer l1, final Layer l2) {
			if (l1 == l2) return 0; // the same layer
			if (l1.getZ() < l2.getZ()) return -1;
			return 1; // even if same Z, prefer the second
		}
		public final boolean equals(Object ob) { return this == ob; }
	};

	private final TreeMap<Layer,Set<Node>> node_layer_map = new TreeMap<Layer,Set<Node>>(COMP_LAYERS);

	private final Set<Node> end_nodes = new HashSet<Node>();

	private Node root = null;

	public Treeline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase() */
	public Treeline(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at, final Node root) {
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.root = root;
	}

	final private Set<Node> getNodesToPaint(final Layer active_layer) {
		// Determine which layers to paint
		final Set<Node> nodes;
		if (layer_set.color_cues) {
			nodes = new HashSet<Node>();
			if (-1 == layer_set.n_layers_color_cue) {
				// All layers
				for (final Set<Node> ns : node_layer_map.values()) nodes.addAll(ns);
			} else {
				// Just a range
				int i = layer_set.indexOf(active_layer);
				int first = i - layer_set.n_layers_color_cue;
				int last = i + layer_set.n_layers_color_cue;
				if (first < 0) first = 0;
				if (last >= layer_set.size()) last = layer_set.size() -1;
				for (final Layer la : layer_set.getLayers(first, last)) {
					Set<Node> ns = node_layer_map.get(la);
					if (null != ns) nodes.addAll(ns);
				}
			}
		} else {
			// Just the active layer
			nodes = node_layer_map.get(active_layer);
		}
		return nodes;
	}

	final public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		paint(g, srcRect, magnification, active, channels, active_layer, layer_set.paint_arrows);
	}
	final public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final boolean with_arrows) {
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

		AffineTransform gt = null;

		Stroke stroke = null;

		synchronized (node_layer_map) {
			// Determine which layers to paint
			final Set<Node> nodes = getNodesToPaint(active_layer);
			if (null != nodes) {
				Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
				Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

				// Clear transform and stroke
				gt = g.getTransform();
				g.setTransform(DisplayCanvas.DEFAULT_AFFINE);
				stroke = g.getStroke();
				g.setStroke(DisplayCanvas.DEFAULT_STROKE);
				for (final Node nd : nodes) {
					if (nd == marked) {
						if (null == MARKED_CHILD) createMarks();
						Composite c = g.getComposite();
						g.setXORMode(Color.green);
						float[] fps = new float[]{nd.x, nd.y};
						this.at.transform(fps, 0, fps, 0, 1);
						AffineTransform aff = new AffineTransform();
						aff.translate((fps[0] - srcRect.x) * magnification, (fps[1] - srcRect.y) * magnification);
						g.fill(aff.createTransformedShape(active ? MARKED_PARENT : MARKED_CHILD));
						g.setComposite(c);
					}
					nd.paintSlabs(g, active_layer, active, srcRect, magnification, nodes, this.at, this.color, with_arrows, layer_set.paint_edge_confidence_boxes);
					if (active && active_layer == nd.la) nd.paintHandle(g, srcRect, magnification, this);
				}

				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, text_antialias);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, render_quality);
			}
		}

		// restore
		if (null != gt) {
			g.setTransform(gt);
			g.setStroke(stroke);
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	protected void calculateBoundingBox(final boolean adjust_position) {
		if (null == root) return;
		Rectangle box = null;
		synchronized (node_layer_map) {
			for (final Collection<Node> nodes : node_layer_map.values()) {
				for (final Node nd : nodes) {
					if (null == box) box = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
					else box.add((int)nd.x, (int)nd.y);
				}
			}
		}

		this.width = box.width;
		this.height = box.height;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (final Collection<Node> nodes : node_layer_map.values()) {
				for (final Node nd : nodes) {
					nd.translate(-box.x, -box.y); }}
			this.at.translate(box.x, box.y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		if (null != layer_set) layer_set.updateBucket(this);
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
		Display.repaint(layer_set, this, box, 10, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// TODO
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		if (null == root) return false;
		synchronized (node_layer_map) {
			// Area to local coords
			try {
				final Area a = area.createTransformedArea(this.at.createInverse());
				// find layers between z_first and z_last
				for (final Map.Entry<Layer,Set<Node>> e : node_layer_map.entrySet()) {
					final double z = e.getKey().getZ();
					if (z >= z_first && z <= z_last) {
						for (final Node nd : e.getValue()) {
							if (a.contains(nd.x, nd.y)) return true;
						}
					}
				}
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return false;
	}

	public Layer getFirstLayer() {
		if (null == root) return null;
		synchronized (node_layer_map) {
			return node_layer_map.firstKey();
		}
	}

	public boolean linkPatches() {
		if (null == root) return false;
		boolean must_lock = false;
		synchronized (node_layer_map) {
			for (final Map.Entry<Layer,Set<Node>> e : node_layer_map.entrySet()) {
				final Layer la = e.getKey();
				for (final Node nd : e.getValue()) {
					for (final Displayable d : la.find(Patch.class, (int)nd.x, (int)nd.y, true)) {
						link(d);
						if (d.locked) must_lock = true;
					}
				}
			}
		}
		if (must_lock && !locked) {
			setLocked(true);
			return true;
		}
		return false;
	}

	public Treeline clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		Treeline tline =  new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at, root.clone());
		tline.addToDatabase();
		return tline;
	}

	public boolean isDeletable() {
		return null == root;
	}

	/** Exports to type t2_treeline. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_treeline";
		if (hs.contains(type)) return;
		hs.add(type);
		hs.add("t2_node");
		sb_header.append(indent).append("<!ELEMENT t2_node EMPTY>\n");
		sb_header.append(indent).append(TAG_ATTR1).append("t2_node x").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node y").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node lid").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node r").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node c").append(TAG_ATTR2)
		;
		sb_header.append(indent).append("<!ELEMENT t2_treeline (t2_node,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_treeline\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		super.restXML(sb_body, in, any);
		sb_body.append(indent).append(">\n");
		if (null != root) exportXML(in, sb_body, root);
		sb_body.append(indent).append("</t2_treeline>\n");
	}

	/** One day, java will get tail-call optimization (i.e. no more stack overflow errors) and I will laugh at this function. */
	static private void exportXML(final String indent_base, final StringBuffer sb, final Node root) {
		// Simulating recursion
		//
		// write depth-first, closing as children get written
		final LinkedList<Node> list = new LinkedList<Node>();
		list.add(root);
		final Map<Node,Integer> table = new HashMap<Node,Integer>();

		while (!list.isEmpty()) {
			Node node = list.getLast();
			if (null == node.children) {
				// Processing end point
				dataNodeXML(getIndents(indent_base, list.size()), sb, node);
				list.removeLast();
				continue;
			} else {
				final Integer ii = table.get(node);
				if (null == ii) {
					// Never yet processed a child, add first
					dataNodeXML(getIndents(indent_base, list.size()), sb, node);
					table.put(node, 0);
					list.add(node.children[0]);
					continue;
				} else {
					final int i = ii.intValue();
					// Are there any more children to process?
					if (i == node.children.length -1) {
						// No more children to process
						closeNodeXML(getIndents(indent_base, list.size()), sb);
						list.removeLast();
						table.remove(node);
						continue;
					} else {
						// Process the next child
						list.add(node.children[i+1]);
						table.put(node, i+1);
					}
				}
			}
		}
	}
	static private StringBuffer getIndents(String base, int more) {
		final StringBuffer sb = new StringBuffer(base.length() + more);
		sb.append(base);
		while (more > 0) {
			sb.append(' ');
			more--;
		}
		return sb;
	}
	static private final void dataNodeXML(final StringBuffer indent, final StringBuffer sb, final Node node) {
		sb.append(indent)
		  .append("<t2_node x=\"").append(node.x)
		  .append("\" y=\"").append(node.y)
		  .append("\" lid=\"").append(node.la.getId())
		  .append("\" r=\"").append(node.r)
		;
		if (null != node.parent) sb.append("\" c=\"").append(node.parent.getConfidence(node));
		if (null == node.children) sb.append("\" />\n");
		else sb.append("\">\n");
	}
	static private final void closeNodeXML(final StringBuffer indent, final StringBuffer sb) {
		sb.append(indent).append("</t2_node>\n");
	}

	/** @return a CustomLineMesh.PAIRWISE list for a LineMesh. */
	public List generateTriangles(double scale_, int parallels, int resample) {
		if (null == root) return null;
		final ArrayList list = new ArrayList();
		//root.generateTriangles(list, scale, parallels, resample, layer_set.getCalibrationCopy());

		// Simulate recursion
		final LinkedList<Node> todo = new LinkedList<Node>();
		todo.add(root);

		final float scale = (float)scale_;
		final Calibration cal = layer_set.getCalibration();
		final float pixelWidthScaled = (float) cal.pixelWidth * scale;
		final float pixelHeightScaled = (float) cal.pixelHeight * scale;
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		final float[] fps = new float[2];
		final Map<Node,Point3f> points = new HashMap<Node,Point3f>();

		// A few performance tests are needed:
		// 1 - if the map caching of points helps or recomputing every time is cheaper than lookup
		// 2 - if removing no-longer-needed points from the map helps lookup or overall slows down
		//
		// The method, by the way, is very parallelizable: each is independent.

		boolean go = true;
		while (go) {
			final Node node = todo.removeFirst();
			// Add children to todo list if any
			if (null != node.children) {
				for (final Node nd : node.children) todo.add(nd);
			}
			go = !todo.isEmpty();
			// Get node's 3D coordinate
			Point3f p = points.get(node);
			if (null == p) {
				fps[0] = node.x;
				fps[1] = node.y;
				this.at.transform(fps, 0, fps, 0, 1);
				p = new Point3f(fps[0] * pixelWidthScaled,
						fps[1] * pixelHeightScaled,
						(float)node.la.getZ() * pixelWidthScaled * sign);
				points.put(node, p);
			}
			if (null != node.parent) {
				// Create a line to the parent
				list.add(points.get(node.parent));
				list.add(p);
				if (go && node.parent != todo.getFirst().parent) {
					// node.parent point no longer needed (last child just processed)
					points.remove(node.parent);
				}
			}
		}
		return list;
	}

	public List generateMesh(double scale_, int parallels, int resample) {
		// TODO
		// Construct a mesh made of straight tubes for each edge, and balls of the same ending diameter on the nodes.
		// With some cleverness, such meshes could be welded together by merging the nearest vertices on the ball
		// surfaces, or by cleaving the surface where the diameter of the tube cuts it.
		// A tougher problem is where tubes cut each other, but perhaps if the resulting mesh is still non-manifold, it's ok.

		return null;
	}

	/** Testing for performance, 100 iterations:
	 * A: 3307  (current, with clearing of table on the fly)
	 * B: 4613  (without clearing table)
	 * C: 4012  (without point caching)
	 *
	 * Although in short runs (10 iterations) A can get very bad:
	 * (first run of 10)
	 * A: 664
	 * B: 611
	 * C: 196
	 * (second run of 10)
	 * A: 286
	 * B: 314
	 * C: 513  <-- gets worse !?
	 *
	 * Differences are not so huge in any case.
	 */
	static final public void testMeshGenerationPerformance(int n_iterations) {
		// test 3D mesh generation

		Layer la = Display.getFrontLayer();
		java.util.Random rnd = new java.util.Random(67779);
		Node root = new Node(rnd.nextFloat(), rnd.nextFloat(), la, 0);
		Node parent = root;
		for (int i=0; i<10000; i++) {
			Node child = new Node(rnd.nextFloat(), rnd.nextFloat(), la, 0);
			parent.add(child, MAX_EDGE_CONFIDENCE);
			if (0 == i % 100) {
				// add a branch of 100 nodes
				Node pa = parent;
				for (int k = 0; k<100; k++) {
					Node ch = new Node(rnd.nextFloat(), rnd.nextFloat(), la, 0);
					pa.add(ch, MAX_EDGE_CONFIDENCE);
					pa = ch;
				}
			}
			parent = child;
		}

		final AffineTransform at = new AffineTransform(1, 0, 0, 1, 67, 134);

		final ArrayList list = new ArrayList();

		final LinkedList<Node> todo = new LinkedList<Node>();

		final float scale = 0.345f;
		final Calibration cal = la.getParent().getCalibration();
		final float pixelWidthScaled = (float) cal.pixelWidth * scale;
		final float pixelHeightScaled = (float) cal.pixelHeight * scale;
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		final Map<Node,Point3f> points = new HashMap<Node,Point3f>();

		// A few performance tests are needed:
		// 1 - if the map caching of points helps or recomputing every time is cheaper than lookup
		// 2 - if removing no-longer-needed points from the map helps lookup or overall slows down

		long t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {
			// A -- current method
			points.clear();
			todo.clear();
			todo.add(root);
			list.clear();
			final float[] fps = new float[2];
			
			boolean go = true;
			while (go) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				go = !todo.isEmpty();
				// Get node's 3D coordinate
				Point3f p = points.get(node);
				if (null == p) {
					fps[0] = node.x;
					fps[1] = node.y;
					at.transform(fps, 0, fps, 0, 1);
					p = new Point3f(fps[0] * pixelWidthScaled,
							fps[1] * pixelHeightScaled,
							(float)node.la.getZ() * pixelWidthScaled * sign);
					points.put(node, p);
				}
				if (null != node.parent) {
					// Create a line to the parent
					list.add(points.get(node.parent));
					list.add(p);
					if (go && node.parent != todo.getFirst().parent) {
						// node.parent point no longer needed (last child just processed)
						points.remove(node.parent);
					}
				}
			}
		}
		System.out.println("A: " + (System.currentTimeMillis() - t0));


		t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {

			points.clear();
			todo.clear();
			todo.add(root);
			list.clear();
			final float[] fps = new float[2];

			// Simpler method, not clearing no-longer-used nodes from map
			while (!todo.isEmpty()) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				// Get node's 3D coordinate
				Point3f p = points.get(node);
				if (null == p) {
					fps[0] = node.x;
					fps[1] = node.y;
					at.transform(fps, 0, fps, 0, 1);
					p = new Point3f(fps[0] * pixelWidthScaled,
							fps[1] * pixelHeightScaled,
							(float)node.la.getZ() * pixelWidthScaled * sign);
					points.put(node, p);
				}
				if (null != node.parent) {
					// Create a line to the parent
					list.add(points.get(node.parent));
					list.add(p);
				}
			}
		}
		System.out.println("B: " + (System.currentTimeMillis() - t0));

		t0 = System.currentTimeMillis();
		for (int i=0; i<n_iterations; i++) {

			todo.clear();
			todo.add(root);
			list.clear();

			// Simplest method: no caching in a map
			final float[] fp = new float[4];
			while (!todo.isEmpty()) {
				final Node node = todo.removeFirst();
				// Add children to todo list if any
				if (null != node.children) {
					for (final Node nd : node.children) todo.add(nd);
				}
				if (null != node.parent) {
					// Create a line to the parent
					fp[0] = node.x;
					fp[1] = node.y;
					fp[2] = node.parent.x;
					fp[3] = node.parent.y;
					at.transform(fp, 0, fp, 0, 2);
					list.add(new Point3f(fp[2] * pixelWidthScaled,
							     fp[3] * pixelHeightScaled,
							     (float)node.parent.la.getZ() * pixelWidthScaled * sign));
					list.add(new Point3f(fp[0] * pixelWidthScaled,
							     fp[1] * pixelHeightScaled,
							     (float)node.la.getZ() * pixelWidthScaled * sign));
				}
			}
		}
		System.out.println("C: " + (System.currentTimeMillis() - t0));
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
		final Node root;
		DPTreeline(final Treeline tline) {
			super(tline);
			this.root = null == tline.root ? null : tline.root.clone();
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Treeline tline = (Treeline)d;
			if (null != this.root) {
				tline.root = this.root.clone();
				tline.clearCache();
				tline.cacheSubtree(tline.root.getSubtreeNodes());
			}
			return true;
		}
	}

	/** Reroots at the point closest to the x,y,layer_id world coordinate.
	 *  @return true on success. */
	synchronized public boolean reRoot(float x, float y, Layer layer, double magnification) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			// Search within the nodes in layer
			Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No node at " + x + ", " + y + ", " + layer);
				return false;
			}
			nodes = null;
			// Find a node near the coordinate
			Node nd = findNode(x, y, layer, magnification);
			if (null == nd) {
				Utils.log("No node near " + x + ", " + y + ", " + layer);
				return false;
			}

			nd.setRoot();
			this.root = nd;
			return true;
		}
	}

	/** Split the Treeline into new Treelines at the point closest to the x,y,layer world coordinate.
	 *  @return null if no node was found near the x,y,layer point with precision dependent on magnification. */
	synchronized public List<Treeline> splitNear(float x, float y, Layer layer, double magnification) {
		try {
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x, y);
				x = (float)po.x;
				y = (float)po.y;
			}
			synchronized (node_layer_map) {
				// Search within the nodes in layer
				Set<Node> nodes = node_layer_map.get(layer);
				if (null == nodes || nodes.isEmpty()) {
					Utils.log("No nodes at " + x + ", " + y + ", " + layer);
					return null;
				}
				nodes = null;
				// Find a node near the coordinate
				Node nd = findNode(x, y, layer, magnification);
				if (null == nd) {
					Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
					return null;
				}
				if (null == nd.parent) {
					Utils.log("Cannot split at a root point!");
					return null;
				}
				// Cache the children of 'nd'
				Collection<Node> subtree_nodes = nd.getSubtreeNodes();
				// Remove all children nodes of found node 'nd' from the Treeline cache arrays:
				removeNode(nd, subtree_nodes);
				// Set the found node 'nd' as a new root: (was done by removeNode/Node.remove anyway)
				nd.parent = null;
				// With the found nd, now a root, create a new Treeline
				Treeline tline = new Treeline(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, (AffineTransform)this.at.clone(), nd);
				tline.addToDatabase();
				// ... and fill its cache arrays
				tline.cacheSubtree(subtree_nodes); // includes nd itself
				// Recompute bounds -- TODO: must translate the second properly, or apply the transforms and then recalculate bounding boxes and transforms.
				this.calculateBoundingBox(true);
				tline.calculateBoundingBox(true);
				// Done!
				return Arrays.asList(new Treeline[]{this, tline});
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	private void cacheSubtree(final Collection<Node> nodes) {
		cache(nodes, end_nodes, node_layer_map);
	}
	private void clearCache() {
		end_nodes.clear();
		node_layer_map.clear();
		last_added = null;
		last_edited = null;
		marked = null;
	}

	/** Take @param nodes and add them to @param end_nodes and @param node_layer_map as appropriate. */
	static private void cache(final Collection<Node> nodes, final Collection<Node> end_nodes, final Map<Layer,Set<Node>> node_layer_map) {
		for (final Node child : nodes) {
			if (null == child.children) end_nodes.add(child);
			Set<Node> nds = node_layer_map.get(child.la);
			if (null == nds) {
				nds = new HashSet<Node>();
				node_layer_map.put(child.la, nds);
			}
			nds.add(child);
		}
	}

	/** Returns true if the given point falls within a certain distance of any of the treeline segments,
	 *  where a segment is defined as the line between a clicked point and the next. */
	@Override
	public boolean contains(final Layer layer, final int x, final int y) {
		if (null == root) return false;
		synchronized (node_layer_map) {
			final Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return false;
			Display front = Display.getFront();
			float radius = 10;
			if (null != front) {
				double mag = front.getCanvas().getMagnification();
				radius = (float)(10 / mag);
				if (radius < 2) radius = 2;
			}
			final Point2D.Double po = inverseTransformPoint(x, y);
			radius *= radius;

			for (final Node nd : nodes) {
				if (nd.isNear((float)po.x, (float)po.y, radius)) return true;
			}
		}
		return false;
	}

	public Node getRoot() {
		return root;
	}

	/** Can only have one parent, so there aren't cyclic graphs. */
	static public class Node {
		private Node parent = null;
		public Node getParent() { return parent; }

		private float x, y, r;
		public float getX() { return x; }
		public float getY() { return y; }
		public float getRadius() { return r; }

		private Layer la;
		public Layer getLayer() { return la; }

		private Node[] children = null;
		public List<Node> getChildrenNodes() {
			final ArrayList<Node> a = new ArrayList<Node>();
			if (null == children) return a;
			for (int i=0; i<children.length; i++) a.add(children[i]);
			return a;
		}
		/** @return a map of child node vs edge confidence to that child. */
		public Map<Node,Byte> getChildren() {
			final HashMap<Node,Byte> m = new HashMap<Node,Byte>();
			if (null == children) return m;
			for (int i=0; i<children.length; i++) m.put(children[i], confidence[i]);
			return m;
		}

		/** The confidence value of the edge towards a child;
		 *  in other words, how much this node can be trusted.
		 *  Defaults to MAX_EDGE_CONFIDENCE for full trust, and 0 for none. */
		private byte[] confidence = null;
		public List<Byte> getEdgeConfidence() {
			final ArrayList<Byte> a = new ArrayList<Byte>();
			if (null == children) return a;
			for (int i=0; i<children.length; i++) a.add(confidence[i]);
			return a;
		}

		public String toString() {
			return new StringBuilder("{:x ").append(x).append(" :y ").append(y).append(" :r ").append(r).append(" :layer ").append(la.getId()).append('}').toString();
		}

		/** @param parent The parent Node, which has an edge of a certain confidence value towards this Node.
		 *  @param x The X in local coordinates.
		 *  @param y The Y in local coordinates.
		 *  @param layer The Layer where the point represented by this Node sits.
		 *  @param r The radius, in local pixel dimensions (follows the X scaling of the Treeline affine). */
		public Node(final float x, final float y, final Layer la, final float r) {
			this.x = x;
			this.y = y;
			this.la = la;
			this.r = r;
		}
		/** To reconstruct from XML, without a layer. */
		public Node(final HashMap attr) {
			this.x = Float.parseFloat((String)attr.get("x"));
			this.y = Float.parseFloat((String)attr.get("y"));
			this.r = Float.parseFloat((String)attr.get("r"));
			this.la = null;
		}
		public void setLayer(final Layer la) {
			this.la = la;
		}
		/** Returns -1 when not added (e.g. if child is null). */
		synchronized public final int add(final Node child, final byte confidence) {
			if (null == child) return -1;
			if (null != child.parent) {
				Utils.log("WARNING: tried to add a node that already had a parent!");
				return -1;
			}
			if (null != children) {
				for (final Node nd : children) {
					if (nd == child) {
						Utils.log("WARNING: tried to add a node to a parent that already had the node as a child!");
						return -1;
					}
				}
			}
			enlargeArrays(1);
			this.children[children.length-1] = child;
			this.confidence[children.length-1] = confidence;
			child.parent = this;
			return children.length -1;
		}
		synchronized public final boolean remove(final Node child) {
			if (null == children) {
				Utils.log("WARNING: tried to remove a child from a childless node!");
				return false; // no children!
			}
			// find its index
			int k = -1;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) {
					k = i;
					break;
				}
			}
			if (-1 == k) {
				Utils.log("Not a child!");
				return false; // not a child!
			}

			child.parent = null;

			if (1 == children.length) {
				children = null;
				confidence = null;
				return true;
			}

			// Else, rearrange arrays:
			final Node[] ch = new Node[children.length-1];
			final byte[] co = new byte[children.length-1];
			System.arraycopy(children, 0, ch, 0, k);
			System.arraycopy(confidence, 0, co, 0, k);
			System.arraycopy(children, k+1, ch, k, children.length - k -1);
			System.arraycopy(confidence, k+1, co, k, children.length - k -1);
			children = ch;
			confidence = co;
			return true;
		}
		private final void enlargeArrays(final int n_more) {
			if (null == children) {
				children = new Node[n_more];
				confidence = new byte[n_more];
			} else {
				final Node[] ch = new Node[children.length + n_more];
				System.arraycopy(children, 0, ch, 0, children.length);
				final byte[] co = new byte[children.length + n_more];
				System.arraycopy(confidence, 0, co, 0, children.length);
				children = ch;
				confidence = co;
			}
		}
		/** Paint this node, and edges to parent and children varies according to whether they are included in the to_paint list. */
		final void paintSlabs(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint, final AffineTransform aff, final Color tline_color, final boolean with_arrows, final boolean with_confidence_boxes) {
			// Since this method is called, this node is to be painted and by definition is inside the Set to_paint.
			if (null != children) {
				final double actZ = active_layer.getZ();
				final double thisZ = this.la.getZ();
				//Which edge color?
				Color local_edge_color = tline_color;
				if (active_layer == this.la) {} // default color
				else if (actZ > thisZ) {
					local_edge_color = Color.red;
				} else if (actZ < thisZ) local_edge_color = Color.blue;

				final float[] fps = new float[children.length + children.length];
				fps[0] = this.x;
				fps[1] = this.y;
				aff.transform(fps, 0, fps, 0, 1);

				// To screen coords:
				final int x = (int)((fps[0] - srcRect.x) * magnification);
				final int y = (int)((fps[1] - srcRect.y) * magnification);

				synchronized (this) {
					// Transform points
					for (int i=0, k=0; i<children.length; i++, k+=2) {
						fps[k] = children[i].x;
						fps[k+1] = children[i].y;
					}
					aff.transform(fps, 0, fps, 0, children.length);
					//
					for (int i=0, k=0; i<children.length; i++, k+=2) {
						final Node child = children[i];
						// To screen coords:
						final int chx = (int)((fps[k] - srcRect.x) * magnification);
						final int chy = (int)((fps[k+1] - srcRect.y) * magnification);
						if (!to_paint.contains(child)) {
							// Paint proximal half edge to the child
							g.setColor(local_edge_color);
							g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
							//if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
						} else {
							// Paint full edge, but perhaps in two halfs of different colors
							if ((child.la == this.la && this.la == active_layer)      // in treeline color
							  || (thisZ < actZ && child.la.getZ() < actZ)    // in red
							  || (thisZ > actZ && child.la.getZ() > actZ)) { // in blue
								// Full edge in local color
								g.setColor(local_edge_color);
								g.drawLine((int)x, (int)y, (int)chx, (int)chy);
								if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
							} else {
								if (this.la == active_layer) {
									// Proximal half in this color
									g.setColor(local_edge_color);
									g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
									// Distal either red or blue:
									Color c = local_edge_color;
									// If other towards higher Z:
									if (actZ < child.la.getZ()) c = Color.blue;
									// If other towards lower Z:
									else if (actZ > child.la.getZ()) c = Color.red;
									//
									g.setColor(c);
									g.drawLine((int)(x + (chx - x)/2), (int)(y + (chy - y)/2), (int)chx, (int)chy);
									if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
								} else if (child.la == active_layer) {
									// Distal half in the Displayable color
									g.setColor(tline_color);
									g.drawLine((int)(x + (chx - x)/2), (int)(y + (chy - y)/2), (int)chx, (int)chy);
									if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
									// Proximal half in either red or blue:
									g.setColor(local_edge_color);
									g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
								} else if (thisZ < actZ && actZ < child.la.getZ()) {
									// proximal half in red
									g.setColor(Color.red);
									g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
									// distal half in blue
									g.setColor(Color.blue);
									g.drawLine((int)(x + (chx - x)/2), (int)(y + (chy - y)/2), (int)chx, (int)chy);
									if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
								} else if (thisZ > actZ && actZ > child.la.getZ()) {
									// proximal half in blue
									g.setColor(Color.blue);
									g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
									// distal half in red
									g.setColor(Color.red);
									g.drawLine((int)(x + (chx - x)/2), (int)(y + (chy - y)/2), (int)chx, (int)chy);
									if (with_arrows) g.fill(M.createArrowhead(x, y, chx, chy, magnification));
								}
							}
						}
						if (active && with_confidence_boxes && (active_layer == this.la || active_layer == child.la || (thisZ < actZ && actZ < child.la.getZ()))) {
							// Draw confidence half-way through the edge
							String s = Integer.toString(confidence[i]&0xff);
							Dimension dim = Utils.getDimensions(s, g.getFont());
							g.setColor(Color.white);
							int xc = (int)(x + (chx - x)/2);
							int yc = (int)(y + (chy - y)/2);  // y + 0.5*chy - 0.5y = (y + chy)/2
							g.fillRect(xc, yc, dim.width+2, dim.height+2);
							g.setColor(Color.black);
							g.drawString(s, xc+1, yc+dim.height+1);
						}
					}
				}
			}
		}
		/** Paint in the context of offscreen space, without transformations. */
		final void paintHandle(final Graphics2D g, final Rectangle srcRect, final double magnification, final Treeline tline) {
			Point2D.Double po = tline.transformPoint(this.x, this.y);
			float x = (float)((po.x - srcRect.x) * magnification);
			float y = (float)((po.y - srcRect.y) * magnification);

			// paint the node as a draggable point
			if (null == parent) {
				// As origin
				g.setColor(Color.magenta);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("S", (int)x -3, (int)y + 4); // TODO ensure Font is proper
			} else if (null == children) {
				// as end point
				g.setColor(Color.white);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("e", (int)x -4, (int)y + 3); // TODO ensure Font is proper
			} else if (1 == children.length) {
				// as a slab: no branches
				g.setColor(Color.orange);
				g.drawRect((int)x - 2, (int)y - 2, 5, 5);
				g.setColor(Color.black);
				g.drawRect((int)x - 1, (int)y - 1, 3, 3);
				g.setColor(Color.orange);
				g.fillRect((int)x, (int)y, 1, 1);
			} else {
				// As branch point
				g.setColor(Color.yellow);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("Y", (int)x -4, (int)y + 4); // TODO ensure Font is proper
			}
		}

		/** Returns the nodes belonging to the subtree of this node, including the node itself as the root.
		 *  Non-recursive, avoids potential stack overflow. */
		final List<Node> getSubtreeNodes() {
			final List<Node> nodes = new ArrayList<Node>();
			final LinkedList<Node> todo = new LinkedList<Node>();
			todo.add(this);

			while (!todo.isEmpty()) {
				// Grab one node from the todo list and add it
				Node nd = todo.removeFirst();
				nodes.add(nd);
				// Then add all its children to the todo list
				if (null != nd.children) {
					for (final Node child : nd.children) todo.add(child);
				}
			}

			return nodes;
		}

		/** Only this node, not any of its children. */
		final void translate(final float dx, final float dy) {
			x += dx;
			y += dy;
		}

		/** Returns a recursive copy of this Node subtree, where the copy of this Node is the root.
		 * Non-recursive to avoid stack overflow. */
		final public Node clone() {
			// todo list containing packets of a copied node and the lists of original children and confidence to clone into it
			final LinkedList<Object[]> todo = new LinkedList<Object[]>();
			final Node root = new Node(x, y, la, r);
			if (null != this.children) {
				todo.add(new Object[]{root, this.children, this.confidence});
			}
			while (!todo.isEmpty()) {
				final Object[] o = todo.removeFirst();
				final Node copy = (Node)o[0];
				final Node[] original_children = (Node[])o[1];
				copy.confidence = (byte[])((byte[])o[2]).clone();
				copy.children = new Node[original_children.length];
				for (int i=0; i<original_children.length; i++) {
					final Node ochild = original_children[i];
					copy.children[i] = new Node(ochild.x, ochild.y, ochild.la, ochild.r);
					copy.children[i].parent = copy;
					if (null != ochild.children) {
						todo.add(new Object[]{copy.children[i], ochild.children, ochild.confidence});
					}
				}
			}
			return root;
		}

		/** Check if this point or the edges to its children are closer to xx,yy than radius, in the 2D plane only. */
		final boolean isNear(final float xx, final float yy, final float sqradius) {
			if (null == children) return sqradius > (Math.pow(xx - x, 2) + Math.pow(yy - y, 2));
			// Else, check children:
			for (int i=0; i<children.length; i++) {
				if (sqradius > M.distancePointToSegmentSq(xx, yy, 0, // point
						                          x, y, 0,  // origin of edge
									  (children[i].x - x)/2, (children[i].y - y)/2, 0)) // end of half-edge to child
				{
					return true;
				}
			}
			// Check to parent's half segment
			return null != parent && sqradius > M.distancePointToSegmentSq(xx, yy, 0, // point
										       x, y, 0, // origin of edge
										       (x - parent.x)/2, (y - parent.y)/2, 0); // end of half-edge to parent
		}
		final boolean hasChildren() {
			return null != children && children.length > 0;
		}
		final int getChildrenCount() {
			if (null == children) return 0;
			return children.length;
		}
		/** Assumes this is NOT a graph with cycles. Non-recursive to avoid stack overflows. */
		final void setRoot() {
			// Works, but can be done in one pass TODO
			//
			// Find first the list of nodes from this node to the current root
			// and then proceed in reverse direction!

			final LinkedList<Node> path = new LinkedList<Node>();
			path.add(this);
			Node parent = this.parent;
			while (null != parent) {
				path.addFirst(parent);
				parent = parent.parent;
			}
			Node newchild = path.removeFirst();
			for (final Node nd : path) {
				// Made nd the parent of newchild (was the opposite)
				// 1 - Find out the confidence of the edge to the child node:
				byte conf = MAX_EDGE_CONFIDENCE;
				for (int i=0; i<newchild.children.length; i++) {
					if (nd == newchild.children[i]) {
						conf = newchild.confidence[i];
						break;
					}
				}
				// 2 - Remove the child node from the parent's child list
				newchild.remove(nd);
				// 3 - Reverse: add newchild to nd (newchild was parent of nd)
				newchild.parent = null;
				nd.add(newchild, conf);
				// 4 - Prepare next step
				newchild = nd;
			}
			// As root:
			this.parent = null;

			// TODO Below, it should work, but it doesn't (?)
			// It results in all touched nodes not having a parent (all appear as 'S')
			/*

			Node child = this;
			Node parent = this.parent;
			while (null != parent) {
				// 1 - Find out the confidence of the edge to the child node:
				byte conf = MAX_EDGE_CONFIDENCE;
				for (int i=0; i<parent.children.length; i++) {
					if (child == parent.children[i]) {
						conf = parent.confidence[i];
						break;
					}
				}
				// 2 - Remove the child node from the parent's child list
				parent.remove(child);
				// 3 - Cache the parent's parent, since it will be overwriten in the next step
				Node pp = parent.parent;
				// 4 - Add the parent as a child of the child, with the same edge confidence
				parent.parent = null; // so it won't be refused
				child.add(parent, conf);
				// 5 - prepare next step
				child = parent;
				parent = pp;
			}
			// Make this node the root node
			this.parent = null;
			*/
		}
		final boolean setConfidence(final Node child, final byte conf) {
			if (null == children) return false;
			if (conf < 0 || conf > MAX_EDGE_CONFIDENCE) return false;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) {
					confidence[i] = conf;
					return true;
				}
			}
			return false;
		}
		final boolean adjustConfidence(final Node child, final int inc) {
			if (null == children) return false;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) {
					byte conf = (byte)((confidence[i]&0xff) + inc);
					if (conf < 0 || conf > MAX_EDGE_CONFIDENCE) return false;
					confidence[i] = conf;
					return true;
				}
			}
			return false;
		}
		/** Returns -1 if not a child of this node. */
		final byte getConfidence(final Node child) {
			if (null == children) return -1;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) return confidence[i];
			}
			return -1;
		}
		final int indexOf(final Node child) {
			if (null == children) return -1;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) return i;
			}
			return -1;
		}
		final Node findPreviousBranchOrRootPoint() {
			if (null == this.parent) return null;
			Node parent = this.parent;
			while (true) {
				if (1 == parent.children.length) {
					if (null == parent.parent) return parent; // the root
					parent = parent.parent;
					continue;
				}
				return parent;
			}
		}
		/** Assumes there aren't any cycles. */
		final Node findNextBranchOrEndPoint() {
			Node child = this;
			while (true) {
				if (null == child.children || child.children.length > 1) return child;
				child = child.children[0];
			}
		}
	}

	private Coordinate<Node> createCoordinate(final Node nd) {
		if (null == nd) return null;
		double x = nd.x;
		double y = nd.y;
		if (!this.at.isIdentity()) {
			double[] dps = new double[]{x, y};
			this.at.transform(dps, 0, dps, 0, 1);
			x = dps[0];
			y = dps[1];
		}
		return new Coordinate<Node>(x, y, nd.la, nd);
	}

	public Coordinate<Node> findPreviousBranchOrRootPoint(float x, float y, Layer layer, double magnification) {
		Node nd = findNodeNear(x, y, layer, magnification);
		if (null == nd) return null;
		return createCoordinate(nd.findPreviousBranchOrRootPoint());
	}
	/** If the node found near x,y,layer is a branch point, returns it; otherwise the next down
	 *  the chain; on reaching an end point, returns it. */
	public Coordinate<Node> findNextBranchOrEndPoint(float x, float y, Layer layer, double magnification) {
		Node nd = findNodeNear(x, y, layer, magnification);
		if (null == nd) return null;
		return createCoordinate(nd.findNextBranchOrEndPoint());
	}

	public Coordinate<Node> getLastEdited() {
		return createCoordinate(last_edited);
	}
	public Coordinate<Node> getLastAdded() {
		return createCoordinate(last_added);
	}

	/** Find an edge near the world coords x,y,layer with precision depending upon magnification,
	 *  and adjust its confidence to @param confidence.
	 *  @return the node whose parent edge is altered, or null if none found. */
	protected Node setEdgeConfidence(byte confidence, float x, float y, Layer layer, double magnification) {
		synchronized (node_layer_map) {
			Node nearest = findNodeConfidenceBox(x, y, layer, magnification);
			if (null == nearest) return null;

			if (nearest.parent.setConfidence(nearest, confidence)) return nearest;
			return null;
		}
	}

	protected Node adjustEdgeConfidence(int inc, float x, float y, Layer layer, double magnification) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			Node nearest = findNode(x, y, layer, magnification);
			if (null == nearest) nearest = findNodeConfidenceBox(x, y, layer, magnification);
			if (null != nearest && null != nearest.parent && nearest.parent.adjustConfidence(nearest, inc)) return nearest;
			return null;
		}
	}

	/** Find the node whose confidence box for the parent edge is closest to x,y,layer, if any.  */
	private Node findNodeConfidenceBox(float x, float y, Layer layer, double magnification) {
		final Set<Node> nodes = node_layer_map.get(layer);
		if (null == nodes) return null;

		Point2D.Double po = inverseTransformPoint(x, y);
		x = (float)po.x;
		y = (float)po.y;

		float radius = (float)(10 / magnification);
		if (radius < 2) radius = 2;
		radius *= radius; // squared

		float min_sq_dist = Float.MAX_VALUE;
		Node nearest = null;
		for (final Node nd : nodes) {
			if (null == nd.parent) continue;
			float d = (float)(Math.pow((nd.parent.x + nd.x)/2 - x, 2) + Math.pow((nd.parent.y + nd.y)/2 - y, 2));
			if (d < min_sq_dist && d < radius) {
				min_sq_dist = d;
				nearest = nd;
			}
		}
		return nearest;
	}

	/** Find a node in @param layer near the local coords lx,ly, with precision depending on magnification.  */
	public Node findNode(final float lx, final float ly, final Layer layer, final double magnification) {
		synchronized (node_layer_map) {
			final Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			double d = (10.0D / magnification);
			if (d < 2) d = 2;
			float min_dist = Float.MAX_VALUE;
			Node nd = null;
			for (final Node node : nodes) {
				float dist = Math.abs(node.x - lx) + Math.abs(node.y - ly);
				if (dist < min_dist) {
					min_dist = dist;
					nd = node;
				}
			}
			return min_dist < d ? nd : null;
		}
	}

	/** Find the spatially closest node, in calibrated coords. */
	public Node findNearestNode(final float lx, final float ly, final Layer layer) {
		synchronized (node_layer_map) {
			final Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			return findNearestNode(lx, ly, (float)layer.getZ(), layer.getParent().getCalibration(), nodes);
		}
	}

	static private Node findNearestNode(float lx, float ly, float lz, final Calibration cal, final Collection<Node> nodes) {
		if (null == nodes) return null;
		// A distance map would help here
		final float pixelWidth = (float) cal.pixelWidth;
		final float pixelHeight = (float) cal.pixelHeight;
		Node nearest = null;
		float sqdist = Float.MAX_VALUE;
		for (final Node nd : nodes) {
			final float d = (float) (Math.pow(pixelWidth * (nd.x - lx), 2) + Math.pow(pixelHeight * (nd.y -ly), 2) + Math.pow(pixelWidth * (nd.la.getZ() - lz), 2));
			if (d < sqdist) {
				sqdist = d;
				nearest = nd;
			}
		}
		return nearest;
	}

	/** Find the spatially closest node, in calibrated coords. */
	public Node findNearestEndNode(final float lx, final float ly, final Layer layer) {
		synchronized (node_layer_map) {
			return findNearestNode(lx, ly, (float)layer.getZ(), layer.getParent().getCalibration(), end_nodes);
		}
	}

	public boolean insertNode(final Node parent, final Node child, final Node in_between, final byte confidence) {
		synchronized (node_layer_map) {
			byte b = parent.getConfidence(child);
			parent.remove(child);
			parent.add(in_between, b);
			in_between.add(child, confidence);
			// cache
			Set<Node> nodes = node_layer_map.get(in_between.la);
			if (null == nodes) {
				nodes = new HashSet<Node>();
				node_layer_map.put(in_between.la, nodes);
			}
			nodes.add(in_between);
			// If child was in end_nodes, remains there

			last_added = in_between;
			return true;
		}
	}

	/** Considering only the set of consecutive layers currently painted, find a point near an edge
	 *  with accurancy depending upon magnification.
	 *  @return null if none of the edges is close enough, or an array of parent and child describing the edge. */
	public Node[] findNearestEdge(float x_pl, float y_pl, Layer layer, double magnification) {
		if (null == root) return null;
		// Don't traverse all, just look into nodes currently being painted according to layer_set.n_layers_color_cue
		final Set<Node> nodes = getNodesToPaint(layer);
		if (null == nodes) return null;
		//
		double d = (10.0D / magnification);
		if (d < 2) d = 2;
		double min_dist = Float.MAX_VALUE;
		Node[] ns = new Node[2]; // parent and child
		//
		for (final Node node : nodes) {
			if (null == node.children) continue;
			// Examine if the point is closer to the 2D-projected edge than any other so far:
			// TODO it's missing edges with parents beyond the set of painted layers,
			//      and it's doing edges to children beyond the set of painted layers.
			for (final Node child : node.children) {
				double dist = M.distancePointToSegment(x_pl, y_pl,
								       node.x, node.y,
								       child.x, child.y);
				if (dist < min_dist && dist < d) {
					min_dist = dist;
					ns[0] = node;
					ns[1] = child;
				}
			}
		}
		if (null == ns[0]) return null;
		return ns;
	}

	public boolean addNode(final Node parent, final Node child, final byte confidence) {
		try {

		synchronized (node_layer_map) {
			Set<Node> nodes = node_layer_map.get(child.la);
			if (null == nodes) {
				nodes = new HashSet<Node>();
				node_layer_map.put(child.la, nodes);
			}
			if (nodes.add(child)) {
				if (null != parent) {
					if (!parent.hasChildren() && !end_nodes.remove(parent)) {
						Utils.log("WARNING: parent wasn't in end_nodes list!");
					}
					parent.add(child, confidence);
				}
				if (null == child.children && !end_nodes.add(child)) {
					Utils.log("WARNING: child was already in end_nodes list!");
				}
				cacheSubtree(child.getSubtreeNodes());

				last_added = child;

				return true;
			} else if (0 == nodes.size()) {
				node_layer_map.remove(child.la);
			}
			return false;
		}

		} finally {
			//Utils.log2("new node: " + child + " with parent: " + parent);
			//Utils.log2("layers with nodes: " + node_layer_map.size() + ", child.la = " + child.la + ", nodes:" + node_layer_map.get(child.la).size());
		}
	}

	/** If the tree is a cyclic graph, it may destroy all. */
	public void removeNode(final Node node) {
		synchronized (node_layer_map) {
			removeNode(node, node.getSubtreeNodes());
		}
	}

	private void removeNode(final Node node, final Collection<Node> subtree_nodes) {
		// if not an end-point, update cached lists
		if (null != node.children) {
			Utils.log2("Removing children of node " + node);
			for (final Node nd : subtree_nodes) { // includes the node itself
				node_layer_map.get(nd.la).remove(nd);
				if (null == nd.children && !end_nodes.remove(nd)) {
					Utils.log2("WARNING: node to remove doesn't have any children but wasn't in end_nodes list!");
				}
			}
		} else {
			Utils.log2("Just removing node " + node);
			end_nodes.remove(node);
			node_layer_map.get(node.la).remove(node);
		}
		if (null != node.parent) {
			if (1 == node.parent.getChildrenCount()) {
				end_nodes.add(node.parent);
				Utils.log2("removeNode: added parent to set of end_nodes");
			}
			// Finally, remove from parent node
			node.parent.remove(node);
		}
	}

	/** Join all given Treelines by using the first one as the receiver, and all the others as the ones to be merged into the receiver.
	 *  Requires each Treeline to have a non-null marked Node; otherwise, returns false. */
	public boolean canJoin(final List<Treeline> tlines) {
		if (null == marked) {
			Utils.log("No marked node in to-be parent Treeline " + this);
			return false;
		}
		boolean quit = false;
		for (final Treeline tl : tlines) {
			if (this == tl) continue;
			if (null == tl.marked) {
				Utils.log("No marked node in to-be child treeline " + tl);
				quit = true;
			}
		}
		return !quit;
	}

	/*  Requires each Treeline to have a non-null marked Node; otherwise, returns false. */
	public boolean join(final List<Treeline> tlines) {
		if (!canJoin(tlines)) return false;
		// All Treeline in tlines have a marked node

		final AffineTransform at_inv;
		try {
			at_inv = this.at.createInverse();
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}

		for (final Treeline tl : tlines) {
			if (this == tl) continue;
			tl.marked.setRoot();
			// transform nodes from there to here
			final AffineTransform aff = new AffineTransform(tl.at); // 1 - to world coords
			aff.preConcatenate(at_inv);		// 2 - to this local coords
			final float[] fps = new float[2];
			for (final Node nd : tl.marked.getSubtreeNodes()) {
				fps[0] = nd.x;
				fps[1] = nd.y;
				aff.transform(fps, 0, fps, 0, 1);
				nd.x = fps[0];
				nd.y = fps[1];
			}
			addNode(this.marked, tl.marked, MAX_EDGE_CONFIDENCE);
			// Remove from tl pointers
			tl.root = null; // stolen!
			tl.marked = null;
			// Remove from tl cache
			tl.node_layer_map.clear();
			tl.end_nodes.clear();
		}

		calculateBoundingBox(true);

		// Don't clear this.marked

		return true;
	}

	private Node findNodeNear(float x, float y, final Layer layer, final double magnification) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			// Search within the nodes in layer
			Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No nodes at " + x + ", " + y + ", " + layer);
				return null;
			}
			nodes = null;
			// Find a node near the coordinate
			Node nd = findNode(x, y, layer, magnification);
			if (null == nd) {
				Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
				return null;
			}
			return nd;
		}
	}

	public boolean markNear(float x, float y, final Layer layer, final double magnification) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			// Search within the nodes in layer
			Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No nodes at " + x + ", " + y + ", " + layer);
				return false;
			}
			nodes = null;
			// Find a node near the coordinate
			marked = findNode(x, y, layer, magnification);
			if (null == marked) {
				Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
				return false;
			}
			return true;
		}
	}
	public boolean unmark() {
		if (null != marked) {
			marked = null;
			return true;
		}
		return false;
	}

	/** The Node double-clicked on, for join operations. */
	private Node marked = null;
	/** The Node clicked on, for mouse operations. */
	private Node active = null;
	/** The last added node */
	private Node last_added = null;
	/** The last edited node, which will be the last added as well until some other node is edited. */
	private Node last_edited = null;

	static private Polygon MARKED_PARENT, MARKED_CHILD;

	static private final void createMarks() {
		MARKED_PARENT = new Polygon(new int[]{0, -1, -2, -4, -18, -18, -4, -2, -1},
					    new int[]{0, -2, -3, -4, -4, 4, 4, 3, 2}, 9);
		MARKED_CHILD = new Polygon(new int[]{0, 10, 12, 12, 22, 22, 12, 12, 10},
					   new int[]{0, 10, 10, 4, 4, -4, -4, -10, -10}, 9);
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN != ProjectToolbar.getToolId()) {
			return;
		}
		final Layer layer = Display.getFrontLayer(this.project);

		if (null != root) {
			// transform the x_p, y_p to the local coordinates
			int x_pl = x_p;
			int y_pl = y_p;
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x_p, y_p);
				x_pl = (int)po.x;
				y_pl = (int)po.y;
			}

			active = findNode(x_pl, y_pl, layer, mag);
			if (null != active) {
				if (2 == me.getClickCount()) {
					marked = active;
					active = null;
					return;
				}
				if (me.isShiftDown() && Utils.isControlDown(me)) {
					// Remove point, and associated branches
					removeNode(active);
					repaint(false); // keep larger size for repainting, will call calculateBoundingBox on mouseRelesed
					active = null;
					return;
				}
				if (me.isShiftDown()) {
					// Create new branch at point, with local coordinates
					Node node = new Node(x_pl, y_pl, layer, active.r);
					addNode(active, node, MAX_EDGE_CONFIDENCE);
					active = node;
					return;
				}
			} else {
				if (2 == me.getClickCount()) {
					marked = null;
					return;
				}
				// Add new point
				if (me.isShiftDown()) {
					Node[] ns = findNearestEdge(x_pl, y_pl, layer, mag);
					if (null != ns) {
						active = new Node(x_pl, y_pl, layer, ns[0].r);
						insertNode(ns[0], ns[1], active, ns[0].getConfidence(ns[1]));
					}
				} else {
					// Find the point closest to any other starting or ending point in all branches
					Node nearest = findNearestEndNode(x_pl, y_pl, layer); // at least the root exists, so it has to find a node, any node
					// append new child; inherits radius from parent
					active = new Node(x_pl, y_pl, layer, nearest.r);
					addNode(nearest, active, MAX_EDGE_CONFIDENCE);
					repaint(true);
				}
				return;
			}
		} else {
			if (-1 == last_radius) {
				last_radius = (float) (10 / Display.getFront().getCanvas().getMagnification()); 
			}

			// First point
			root = active = new Node(x_p, y_p, layer, last_radius); // world coords, so calculateBoundingBox will do the right thing
			addNode(null, active, (byte)0);
		}
	}

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
		active.translate(x_d - x_d_old, y_d - y_d_old);
		repaint(false);
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			repaint(true); //needed at least for the removePoint
		}

		if (null == active) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_r, y_r);
			x_r = (int)pdo.x;
			y_r = (int)pdo.y;
		}

		active.translate(x_r - x_d, y_r - y_d);
		repaint();

		last_edited = active;

		active = null;
	}

	@Override
	public void keyPressed(KeyEvent ke ) {
		Object source = ke.getSource();
		if (! (source instanceof DisplayCanvas)) return;
		DisplayCanvas dc = (DisplayCanvas)source;
		Layer la = dc.getDisplay().getLayer();
		int keyCode = ke.getKeyCode();
		final Point po = dc.getCursorLoc(); // as offscreen coords

		// assumes MAX_EDGE_CONFIDENCE is <= 9.
		if (keyCode >= KeyEvent.VK_0 && keyCode <= (KeyEvent.VK_0 + MAX_EDGE_CONFIDENCE)) {
			// Find an edge near the mouse position, by measuring against the middle of it
			setEdgeConfidence((byte)(keyCode - KeyEvent.VK_0), po.x, po.y, la, dc.getMagnification());
			Display.repaint(layer_set);
			ke.consume();
			return;
		}

		final int modifiers = ke.getModifiers();
		final Display display = Display.getFront();
		final Layer layer = display.getLayer();

		if (0 == modifiers) {
			switch (keyCode) {
				case KeyEvent.VK_R:
					display.center(findPreviousBranchOrRootPoint(po.x, po.y, layer, dc.getMagnification()));
					ke.consume();
					return;
				case KeyEvent.VK_N:
					display.center(findNextBranchOrEndPoint(po.x, po.y, layer, dc.getMagnification()));
					ke.consume();
					return;
				case KeyEvent.VK_L:
					display.center(getLastAdded());
					ke.consume();
					return;
				case KeyEvent.VK_E:
					display.center(getLastEdited());
					ke.consume();
					return;
			}
		}
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent mwe) {
		final int modifiers = mwe.getModifiers();
		if (0 == (MouseWheelEvent.SHIFT_MASK ^ modifiers)) {
			Object source = mwe.getSource();
			if (! (source instanceof DisplayCanvas)) return;
			DisplayCanvas dc = (DisplayCanvas)source;
			Layer la = dc.getDisplay().getLayer();
			final int rotation = mwe.getWheelRotation();
			final double magnification = dc.getMagnification();
			final Rectangle srcRect = dc.getSrcRect();
			final float x = (float)((mwe.getX() / magnification) + srcRect.x);
			final float y = (float)((mwe.getY() / magnification) + srcRect.y);

			adjustEdgeConfidence(rotation > 0 ? 1 : -1, x, y, la, magnification);
			Display.repaint(this);
		}
	}

	/** Used when reconstructing from XML. */
	public void setRoot(final Node new_root) {
		this.root = new_root;
		if (null == new_root) clearCache();
		else cacheSubtree(new_root.getSubtreeNodes());
	}

	@Override
	public void paintSnapshot(final Graphics2D g, final double mag) {
		switch (layer_set.getSnapshotsMode()) {
			case 0:
				// Paint without arrows
				paint(g, Display.getFront().getCanvas().getSrcRect(), mag, false, 0xffffffff, layer, false);
				return;
			case 1:
				paintAsBox(g);
				return;
			default: return; // case 2: // disabled, no paint
		}
	}

	public Set<Node> getEndNodes() {
		return new HashSet<Node>(end_nodes);
	}

	/** Fly-through image stack from source node to mark node.
	 *  @param type is ImagePlus.GRAY8 or .COLOR_RGB */
	public ImagePlus flyThroughMarked(final int width, final int height, final double magnification, final int type) {
		if (null == marked) return null;
		return flyThrough(root, marked, width, height, magnification, type);
	}

	/** Fly-through image stack from first to last node. If first is not lower order than last, then start to last is returned.
	 *  @param type is ImagePlus.GRAY8 or .COLOR_RGB */
	public ImagePlus flyThrough(final Node first, final Node last, final int width, final int height, final double magnification, final int type) {
		// Create regions
		final LinkedList<Region> regions = new LinkedList<Region>();
		Node node = last;
		float[] fps = new float[2];
		while (null != node) {
			fps[0] = node.x;
			fps[1] = node.y;
			this.at.transform(fps, 0, fps, 0, 1);
			regions.addFirst(new Region(new Rectangle((int)fps[0] - width/2,
							          (int)fps[1] - height/2,
								  width, height),
						    node.la,
						    node));
			if (first == node) break;
			node = node.parent;
		}
		return project.getLoader().createFlyThrough(regions, magnification, type);
	}
}
