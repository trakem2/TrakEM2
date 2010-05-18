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
import ij.gui.StackWindow;
import ij.io.FileSaver;
import ij.io.Opener;

import ini.trakem2.imaging.LayerStack;
import ini.trakem2.Project;
import ini.trakem2.parallel.Process;
import ini.trakem2.parallel.TaskFactory;
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
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseListener;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.vecmath.Point3f;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;

import fiji.geom.AreaCalculations;
import java.io.File;

// Ideally, this class would use a linked list of node points, where each node could have a list of branches, which would be in themselves linked lists of nodes and so on.
// That would make sense, and would make re-rooting and removing nodes (with their branches) trivial and fast.
// In practice, I want to reuse Polyline's semiautomatic tracing and thus I am using Polylines for each slab.

/** A sequence of points ordered in a set of connected branches. */
public abstract class Tree extends ZDisplayable implements VectorData {

	static private final Comparator<Layer> COMP_LAYERS = new Comparator<Layer>() {
		public final int compare(final Layer l1, final Layer l2) {
			if (l1 == l2) return 0; // the same layer
			if (l1.getZ() < l2.getZ()) return -1;
			return 1; // even if same Z, prefer the second
		}
		public final boolean equals(Object ob) { return this == ob; }
	};

	protected final TreeMap<Layer,Set<Node>> node_layer_map = new TreeMap<Layer,Set<Node>>(COMP_LAYERS);

	protected final Set<Node> end_nodes = new HashSet<Node>();

	protected Node<?> root = null;

	protected Tree(Project project, String title) {
		super(project, title, 0, 0);
	}

	/** Reconstruct from XML. */
	protected Tree(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase(), neither creates any root node. */
	protected Tree(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
	}

	final protected Collection<Node> getNodesToPaint(final Layer active_layer) {
		// Determine which layers to paint
		final Collection<Node> nodes;
		if (layer_set.color_cues) {
			nodes = new ArrayList<Node>();
			if (-1 == layer_set.n_layers_color_cue) {
				// All layers
				for (final Set<Node> ns : node_layer_map.values()) nodes.addAll(ns);
			} else {
				for (final Layer la : layer_set.getColorCueLayerRange(active_layer)) {
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
			final Collection<Node> nodes = getNodesToPaint(active_layer);
			if (null != nodes) {
				// Clear transform and stroke
				gt = g.getTransform();
				g.setTransform(DisplayCanvas.DEFAULT_AFFINE);
				stroke = g.getStroke();
				g.setStroke(DisplayCanvas.DEFAULT_STROKE);
				for (final Node nd : nodes) {
					nd.paintData(g, active_layer, active, srcRect, magnification, nodes, this);
					nd.paintSlabs(g, active_layer, active, srcRect, magnification, nodes, this.at, this.color, with_arrows, layer_set.paint_edge_confidence_boxes);
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
					if (active && active_layer == nd.la) nd.paintHandle(g, srcRect, magnification, this);
				}
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

	protected Rectangle getPaintingBounds() {
		Rectangle box = null;
		synchronized (node_layer_map) {
			for (final Collection<Node> nodes : node_layer_map.values()) {
				final Rectangle b = getBounds(nodes);
				if (null == box) box = b;
				else if (null != b) box.add(b);
			}
		}
		return box;
	}

	@Override
	public Rectangle getBounds(final Rectangle tmp, final Layer layer) {
		synchronized (node_layer_map) {
			final Collection<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			Rectangle r = getBounds(nodes);
			if (null == r) return null;
			if (null != tmp) tmp.setRect(r); // it's expected
			return r;
		}
	}

	// Call always from within a synchronized (node_layer_map) block.
	protected Rectangle getBounds(final Collection<Node> nodes) {
		Rectangle b = null;
		for (final Node nd : nodes) {
			if (null == b) b = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
			else b.add((int)nd.x, (int)nd.y);
		}
		return b;
	}

	protected boolean calculateBoundingBox(final Layer la) {
		if (null == root) {
			this.at.setToIdentity();
			this.width = 0;
			this.height = 0;
			return true;
		}

		final Rectangle box = getPaintingBounds();

		this.width = box.width;
		this.height = box.height;

		// now readjust points to make min_x,min_y be the x,y
		for (final Collection<Node> nodes : node_layer_map.values()) {
			for (final Node nd : nodes) {
				nd.translate(-box.x, -box.y); }}
		this.at.translate(box.x, box.y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.

		updateBucket(la);
		return true;
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator, Layer la) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(la);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 10, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// TODO
	}

	@Override
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
							if (nd.intersects(a)) return true;
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

	private final List<Node> tolink = new ArrayList<Node>();

	protected final void addToLinkLater(final Node nd) {
		synchronized (tolink) {
			tolink.add(nd);
		}
	}
	protected final void removeFromLinkLater(final Node nd) {
		synchronized (tolink) {
			tolink.remove(nd);
		}
	}

	public boolean linkPatches() {
		if (null == root) return false;
		// Obtain local copy and clear 'tolink':
		final ArrayList<Node> tolink;
		synchronized (this.tolink) {
			tolink = new ArrayList<Node>(this.tolink);
			this.tolink.clear();
		}
		if (tolink.isEmpty()) return true;

		boolean must_lock = false;

		AffineTransform aff;
		try {
			aff = this.at.createInverse();
		} catch (Exception e) {
			IJError.print(e);
			return false;
		}

		for (final Node nd : tolink) {
			for (final Patch patch : (Collection<Patch>) (Collection) nd.findLinkTargets(aff)) {
				link(patch);
				if (patch.locked) must_lock = true;
			}
		}

		if (must_lock && !locked) {
			setLocked(true);
			return true;
		}
		return false;
	}

	/** Create a new instance, intialized with same ZDisplayable-level parameters (affine, color, title, etc.). */
	abstract protected Tree newInstance();

	abstract protected Node newNode(float lx, float ly, Layer layer, Node modelNode);

	/** To reconstruct from XML. */
	abstract public Node newNode(HashMap ht_attr);

	public boolean isDeletable() {
		return null == root;
	}

	/** Exports to type t2_treeline. */
	static public void exportDTD(final StringBuilder sb_header, final HashSet hs, final String indent) {
		final String type = "t2_node";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_tag EMPTY>\n");
		sb_header.append(indent).append(TAG_ATTR1).append("t2_tag name").append(TAG_ATTR2);
		sb_header.append(indent).append(TAG_ATTR1).append("t2_tag key").append(TAG_ATTR2);
		sb_header.append(indent).append("<!ELEMENT t2_node (t2_area*,t2_tag*)>\n");
		sb_header.append(indent).append(TAG_ATTR1).append("t2_node x").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node y").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node lid").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node c").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("t2_node r NMTOKEN #IMPLIED>\n")
		;
	}

	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final Object any) {
		final String type = "t2_" + getClass().getSimpleName().toLowerCase();
		sb_body.append(indent).append("<").append(type).append('\n');
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, any);
		if (null != root) exportXML(this, in, sb_body, root);
		sb_body.append(indent).append("</").append(type).append(">\n");
	}

	/** One day, java will get tail-call optimization (i.e. no more stack overflow errors) and I will laugh at this function. */
	static private void exportXML(final Tree tree, final String indent_base, final StringBuilder sb, final Node root) {
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
				dataNodeXML(tree, getIndents(indent_base, list.size()), sb, node);
				list.removeLast();
				continue;
			} else {
				final Integer ii = table.get(node);
				if (null == ii) {
					// Never yet processed a child, add first
					dataNodeXML(tree, getIndents(indent_base, list.size()), sb, node);
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
	static private final StringBuilder getIndents(final String base, int more) {
		final StringBuilder sb = new StringBuilder(base.length() + more);
		sb.append(base);
		while (more > 0) {
			sb.append(' ');
			more--;
		}
		return sb;
	}
	static private final void dataNodeXML(final Tree tree, final StringBuilder indent, final StringBuilder sb, final Node node) {
		sb.append(indent)
		  .append("<t2_node x=\"").append(node.x)
		  .append("\" y=\"").append(node.y)
		  .append("\" lid=\"").append(node.la.getId()).append('\"');
		;
		if (null != node.parent) sb.append(" c=\"").append(node.parent.getConfidence(node)).append('\"');
		tree.exportXMLNodeAttributes(indent, sb, node); // may not add anything
		sb.append(">\n");
		// ... so accumulated potentially extra chars are 3: \">\n

		indent.append(' ');
		boolean data = tree.exportXMLNodeData(indent, sb, node);
		if (data) {
			if (null != node.tags) exportTags(node, sb, indent);
			if (null == node.children) {
				indent.setLength(indent.length() -1);
				sb.append(indent).append("</t2_node>\n");
				return;
			}
		} else if (null == node.children) {
			if (null != node.tags) {
				exportTags(node, sb, indent);
				sb.append(indent).append("</t2_node>\n");
			} else {
				sb.setLength(sb.length() -3); // remove "\">\n"
				sb.append("\" />\n");
			}
		} else if (null != node.tags) {
			exportTags(node, sb, indent);
		}
		indent.setLength(indent.length() -1);
	}
	abstract protected boolean exportXMLNodeAttributes(StringBuilder indent, StringBuilder sb, Node node);
	abstract protected boolean exportXMLNodeData(StringBuilder indent, StringBuilder sb, Node node);

	static private void exportTags(final Node node, final StringBuilder sb, final StringBuilder indent) {
		for (final Tag tag : (Collection<Tag>) node.getTags()) {
			sb.append(indent).append("<t2_tag name=\"").append(Displayable.getXMLSafeValue(tag.toString()))
					 .append("\" key=\"").append((char)tag.getKeyCode()).append("\" />\n");
		}
	}

	static private final void closeNodeXML(final StringBuilder indent, final StringBuilder sb) {
		sb.append(indent).append("</t2_node>\n");
	}

	/** @return a CustomLineMesh.PAIRWISE list for a LineMesh. */
	public List generateTriangles(double scale_, int parallels, int resample) {
		if (null == root) return null;
		final ArrayList list = new ArrayList();

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

	@Override
	final Class getInternalDataPackageClass() {
		return DPTree.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPTree(this);
	}

	static class DPTree extends Displayable.DataPackage {
		final Node root;
		DPTree(final Tree t) {
			super(t);
			this.root = null == t.root ? null : t.root.clone(t.project);
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Tree t = (Tree)d;
			if (null != this.root) {
				t.root = this.root.clone(t.project);
				t.clearCache();
				t.cacheSubtree(t.root.getSubtreeNodes());
				t.updateView();
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

			end_nodes.add(this.root);
			end_nodes.remove(nd);
			nd.setRoot();
			this.root = nd;
			updateView();
			return true;
		}
	}

	/** Split the Tree into new Tree at the point closest to the x,y,layer world coordinate.
	 *  @return null if no node was found near the x,y,layer point with precision dependent on magnification. */
	synchronized public List<Tree> splitNear(float x, float y, Layer layer, double magnification) {
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
				// Remove all children nodes of found node 'nd' from the Tree cache arrays:
				removeNode(nd, subtree_nodes);
				// Set the found node 'nd' as a new root: (was done by removeNode/Node.remove anyway)
				nd.parent = null;
				// With the found nd, now a root, create a new Tree
				Tree t = newInstance();
				t.addToDatabase();
				t.root = nd;
				// ... and fill its cache arrays
				t.cacheSubtree(subtree_nodes); // includes nd itself
				// Recompute bounds -- TODO: must translate the second properly, or apply the transforms and then recalculate bounding boxes and transforms.
				this.calculateBoundingBox(null);
				t.calculateBoundingBox(null);
				// Done!
				return Arrays.asList(new Tree[]{this, t});
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	protected void cacheSubtree(final Collection<Node> nodes) {
		cache(nodes, end_nodes, node_layer_map);
	}
	protected void clearCache() {
		end_nodes.clear();
		node_layer_map.clear();
		setLastAdded(null);
		setLastEdited(null);
		setLastMarked(null);
		setLastVisited(null);
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

	protected Coordinate<Node> createCoordinate(final Node nd) {
		if (null == nd) return null;
		float x = nd.x;
		float y = nd.y;
		if (!this.at.isIdentity()) {
			float[] dps = new float[]{x, y};
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

	protected Coordinate<Node> findNearAndGetNext(float x, float y, Layer layer, double magnification) {
		Node nd = findNodeNear(x, y, layer, magnification);
		if (null == nd) nd = last_visited;
		if (null == nd) return null;
		int n_children = nd.getChildrenCount();
		if (0 == n_children) return null;
		if (1 == n_children) {
			setLastVisited(nd.children[0]);
			return createCoordinate(nd.children[0]);
		}
		// else, find the closest child edge
		if (!this.at.isIdentity()) {
			Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		nd = findNearestChildEdge(nd, x, y);
		if (null != nd) setLastVisited(nd);
		return createCoordinate(nd);
	}
	protected Coordinate<Node> findNearAndGetPrevious(float x, float y, Layer layer, double magnification) {
		Node nd = findNodeNear(x, y, layer, magnification);
		if (null == nd) nd = last_visited;
		if (null == nd || null == nd.parent) return null;
		setLastVisited(nd.parent);
		return createCoordinate(nd.parent);
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
			if (null != nearest && null != nearest.parent && nearest.parent.adjustConfidence(nearest, inc)) {
				updateViewData(nearest);
				return nearest;
			}
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
			return findClosestNode(node_layer_map.get(layer), lx, ly, magnification);
		}
	}

	/** Expects world coords; with precision depending on magnification. */
	public Node findClosestNodeW(final Collection<Node> nodes, final float wx, final float wy, final double magnification) {
		float lx = wx,
		      ly = wy;
		if (!this.at.isIdentity()) {
			Point2D.Double po = inverseTransformPoint(wx, wy);
			lx = (float)po.x;
			ly = (float)po.y;
		}
		return findClosestNode(nodes, lx, ly, magnification);
	}

	/** Expects local coords; with precision depending on magnification. */
	public Node findClosestNode(final Collection<Node> nodes, final float lx, final float ly, final double magnification) {
		if (null == nodes || nodes.isEmpty()) return null;
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
			Collection<Node> subtree = in_between.getSubtreeNodes();
			cacheSubtree(subtree);
			// If child was in end_nodes, remains there

			setLastAdded(in_between);
			updateView();

			addToLinkLater(in_between);

			return true;
		}
	}

	/** Considering only the set of consecutive layers currently painted, find a point near an edge
	 *  with accurancy depending upon magnification.
	 *  @return null if none of the edges is close enough, or an array of parent and child describing the edge. */
	public Node[] findNearestEdge(float x_pl, float y_pl, Layer layer, double magnification) {
		if (null == root) return null;
		// Don't traverse all, just look into nodes currently being painted according to layer_set.n_layers_color_cue
		final Collection<Node> nodes = getNodesToPaint(layer);
		if (null == nodes) return null;
		//
		double d = (10.0D / magnification);
		if (d < 2) d = 2;
		double min_dist = Double.MAX_VALUE;
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

	/** In projected 2D only, since that's the perspective of the user. */
	private Node findNearestChildEdge(final Node parent, final float lx, final float ly) {
		if (null == parent || null == parent.children) return null;
		
		Node nd = null;
		double min_dist = Double.MAX_VALUE;

		for (final Node child : parent.children) {
			double dist = M.distancePointToSegment(lx, ly,
							       parent.x, parent.y,
							       child.x, child.y);
			if (dist < min_dist) {
				min_dist = dist;
				nd = child;
			}
		}
		return nd;
	}

	public boolean addNode(final Node parent, final Node child, final byte confidence) {
		//try {

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
				Collection<Node> subtree = child.getSubtreeNodes();
				cacheSubtree(subtree);

				repaint(true, child.la);
				setLastAdded(child);
				updateView();

				synchronized (tolink) {
					tolink.addAll(subtree);
				}

				return true;
			} else if (0 == nodes.size()) {
				node_layer_map.remove(child.la);
			}
			return false;
		}

		/*} finally {
			//Utils.log2("new node: " + child + " with parent: " + parent);
			//Utils.log2("layers with nodes: " + node_layer_map.size() + ", child.la = " + child.la + ", nodes:" + node_layer_map.get(child.la).size());
			if (null == parent) {
				Utils.log2("just added parent: ");
				Utils.log2(" nodes to paint: " + Utils.toString(getNodesToPaint(Display.getFrontLayer())));
				Utils.log2(" nodes in node_layer_map: " + Utils.toString(node_layer_map));
			}
		}*/
	}

	/** Remove a node only (not its subtree).
	 *  @return true on success. Will return false when the node has 2 or more children.
	 *  The new edge confidence is that of the parent to the @param node. */
	public boolean popNode(final Node node) {
		switch (node.getChildrenCount()) {
			case 0:
				// End node:
				removeNode(node, null);
				return true;
			case 1:
				if (null == node.parent) {
					// Make its child the new root
					root = node.children[0];
					root.parent = null;
				} else {
					node.parent.children[node.parent.indexOf(node)] = node.children[0];
					node.children[0].parent = node.parent;
				}
				synchronized (node_layer_map) {
					node_layer_map.get(node.la).remove(node);
				}
				fireNodeRemoved(node);
				updateView();
				return true;
			default:
				return false;
		}
	}

	/** If the tree is a cyclic graph, it may destroy all. */
	public void removeNode(final Node node) {
		synchronized (node_layer_map) {
			removeNode(node, node.getSubtreeNodes());
		}
	}

	// TODO this function is not thread safe. Works well because multiple threads aren't so far calling cache-modifying functions.
	// Should synchronize on node_layer_map.
	private void removeNode(final Node node, final Collection<Node> subtree_nodes) {
		if (null == node.parent) {
			root = null;
			clearCache();
		} else {
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
			if (1 == node.parent.getChildrenCount()) {
				end_nodes.add(node.parent);
			}
			// Finally, remove from parent node
			node.parent.remove(node);
		}
		synchronized (tolink) {
			if (null != subtree_nodes) {
				tolink.removeAll(subtree_nodes);
			} else tolink.remove(node);
		}
		fireNodeRemoved(node);
		updateView();
	}

	/** Join all given Trees by using the first one as the receiver, and all the others as the ones to be merged into the receiver.
	 *  Requires each Tree to have a non-null marked Node; otherwise, returns false. */
	public boolean canJoin(final List<? extends Tree> ts) {
		if (null == marked) {
			Utils.log("No marked node in to-be parent Tree " + this);
			return false;
		}
		boolean quit = false;
		for (final Tree tl : ts) {
			if (this == tl) continue;
			if (null == tl.marked) {
				Utils.log("No marked node in to-be child treeline " + tl);
				quit = true;
			}
		}
		return !quit;
	}

	/*  Requires each Tree to have a non-null marked Node; otherwise, returns false. */
	public boolean join(final List<? extends Tree> ts) {
		if (!canJoin(ts)) return false;
		// All Tree in ts have a marked node

		final AffineTransform at_inv;
		try {
			at_inv = this.at.createInverse();
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}

		for (final Tree tl : ts) {
			if (this == tl) continue;
			tl.marked.setRoot();
			// transform nodes from there to here
			final AffineTransform aff = new AffineTransform(tl.at); // 1 - to world coords
			aff.preConcatenate(at_inv);		// 2 - to this local coords
			final float[] fps = new float[2];
			for (final Node nd : (List<Node>) tl.marked.getSubtreeNodes()) { // fails to compile? Why?
				fps[0] = nd.x;
				fps[1] = nd.y;
				aff.transform(fps, 0, fps, 0, 1);
				nd.x = fps[0];
				nd.y = fps[1];
			}
			addNode(this.marked, tl.marked, Node.MAX_EDGE_CONFIDENCE);
			// Remove from tl pointers
			tl.root = null; // stolen!
			tl.setLastMarked(null);
			// Remove from tl cache
			tl.node_layer_map.clear();
			tl.end_nodes.clear();
		}

		calculateBoundingBox(null);

		// Don't clear this.marked

		updateView();

		return true;
	}

	/** Expects world coordinates. If no node is near x,y but there is only one node in the current Display view of the layer, then it returns that node. */
	protected Node findNodeNear(float x, float y, final Layer layer, final double magnification) {
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
			// Find a node near the coordinate
			Node nd = findNode(x, y, layer, magnification);
			// If that fails, try any node show all by itself in the display:

			if (null == nd) {
				// Is there only one node within the srcRect?
				final Area a;
				try {
					a = new Area(Display.getFront().getCanvas().getSrcRect())
						    .createTransformedArea(this.at.createInverse());
				} catch (NoninvertibleTransformException nite) {
					IJError.print(nite);
					return null;
				}
				int count = 0;
				for (final Node node : nodes) {
					if (node.intersects(a)) {
						nd = node;
						count++;
						if (count > 1) {
							nd = null;
							break;
						}
					}
				}
			}

			/*
			if (null == nd) {
				Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
				return null;
			}
			*/
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
			Node found = findNode(x, y, layer, magnification);
			if (null == found) {
				Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
				return false;
			}
			setLastMarked(found);
			return true;
		}
	}
	public boolean unmark() {
		if (null != marked) {
			setLastMarked(null);
			return true;
		}
		return false;
	}

	protected void setActive(Node nd) {
		this.active = nd;
		if (null != nd) setLastVisited(nd);
	}
	protected Node getActive() { return active; }

	protected void setLastEdited(Node nd) {
		this.last_edited = nd;
		setLastVisited(nd);
	}
	protected void setLastAdded(Node nd) {
		this.last_added = nd;
		setLastVisited(nd);
	}
	protected void setLastMarked(Node nd) {
		this.marked = nd;
		setLastVisited(nd);
	}
	protected void setLastVisited(Node nd) { this.last_visited = nd; }

	protected void fireNodeRemoved(final Node nd) {
		if (nd == marked) marked = null;
		if (nd == last_added) last_added = null;
		if (nd == last_edited) last_edited = null;
		if (nd == last_visited) last_visited = null;
		removeFromLinkLater(nd);
	}

	protected void clearState() {
		// clear:
		marked = last_added = last_edited = last_visited = null;
	}

	/** The Node double-clicked on, for join operations. */
	private Node marked = null;
	/** The Node clicked on, for mouse operations. */
	private Node active = null;
	/** The last added node */
	private Node last_added = null;
	/** The last edited node, which will be the last added as well until some other node is edited. */
	private Node last_edited = null;
	/** The last visited node, either navigating or editing. */
	private Node last_visited = null;

	static private Polygon MARKED_PARENT, MARKED_CHILD;

	static private final void createMarks() {
		MARKED_PARENT = new Polygon(new int[]{0, -1, -2, -4, -18, -18, -4, -2, -1},
					    new int[]{0, -2, -3, -4, -4, 4, 4, 3, 2}, 9);
		MARKED_CHILD = new Polygon(new int[]{0, 10, 12, 12, 22, 22, 12, 12, 10},
					   new int[]{0, 10, 10, 4, 4, -4, -4, -10, -10}, 9);
	}

	@Override
	public void mousePressed(MouseEvent me, final Layer layer, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN != ProjectToolbar.getToolId()) {
			return;
		}

		if (null != root) {
			// transform the x_p, y_p to the local coordinates
			int x_pl = x_p;
			int y_pl = y_p;
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x_p, y_p);
				x_pl = (int)po.x;
				y_pl = (int)po.y;
			}

			Node found = findNode(x_pl, y_pl, layer, mag);
			setActive(found);

			if (null != found) {
				if (2 == me.getClickCount()) {
					setLastMarked(found);
					setActive(null);
					return;
				}
				if (me.isShiftDown() && Utils.isControlDown(me)) {
					if (me.isAltDown()) {
						// Remove point and its subtree
						removeNode(found);
					} else {
						// Just remove the slab point, joining parent with child
						if (!popNode(found)) {
							Utils.log("Can't pop out branch point!\nUse shift+control+alt+click to remove a branch point and its subtree.");
							setActive(null);
							return;
						}
					}
					repaint(false, layer); // keep larger size for repainting, will call calculateBoundingBox on mouseRelesed
					setActive(null);
					return;
				}
				if (me.isShiftDown() && !me.isAltDown()) {
					// Create new branch at point, with local coordinates
					Node child = newNode(x_pl, y_pl, layer, found);
					addNode(found, child, Node.MAX_EDGE_CONFIDENCE);
					setActive(child);
					return;
				}
			} else {
				if (2 == me.getClickCount()) {
					setLastMarked(null);
					return;
				}
				if (me.isAltDown()) {
					return;
				}
				// Add new point
				if (me.isShiftDown()) {
					Node[] ns = findNearestEdge(x_pl, y_pl, layer, mag);
					if (null != ns) {
						found = newNode(x_pl, y_pl, layer, ns[0]);
						insertNode(ns[0], ns[1], found, ns[0].getConfidence(ns[1]));
						setActive(found);
					}
				} else {
					// Find the point closest to any other starting or ending point in all branches
					Node nearest = findNearestEndNode(x_pl, y_pl, layer); // at least the root exists, so it has to find a node, any node
					// append new child; inherits radius from parent
					found = newNode(x_pl, y_pl, layer, nearest);
					addNode(nearest, found, Node.MAX_EDGE_CONFIDENCE);
					setActive(found);
					repaint(true, layer);
				}
				return;
			}
		} else {
			// First point
			root = newNode(x_p, y_p, layer, null); // world coords, so calculateBoundingBox will do the right thing
			addNode(null, root, (byte)0);
			setActive(root);
		}
	}

	@Override
	public void mouseDragged(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		translateActive(me, la, x_d, y_d, x_d_old, y_d_old);
	}

	@Override
	public void mouseReleased(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		final int tool = ProjectToolbar.getToolId();

		translateActive(me, la, x_r, y_d, x_d, y_d);

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			repaint(true, la); //needed at least for the removePoint
		}

		updateViewData(active);

		setActive(null);
	}

	private final void translateActive(MouseEvent me, Layer la, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (null == active || me.isAltDown() || Utils.isControlDown(me)) return;
		// shiftDown is ok: when dragging a newly branched node.

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
		repaint(false, la);
		setLastEdited(active);
	}

	static private Node to_tag = null;
	static private Node to_untag = null;
	static private boolean show_tag_dialogs = false;

	@Override
	public void keyPressed(KeyEvent ke) {

		switch (ProjectToolbar.getToolId()) {
			case ProjectToolbar.PEN:
			case ProjectToolbar.BRUSH:
				break;
			default:
				// Reject
				return;
		}

		Object source = ke.getSource();
		if (! (source instanceof DisplayCanvas)) return;

		final int keyCode = ke.getKeyCode();
		final DisplayCanvas dc = (DisplayCanvas)source;

		if (null != to_tag || null != to_untag) {
			// Can only add a tag for A-Z or numbers!
			if (! (Character.isLetterOrDigit((char)keyCode) && (Character.isDigit((char)keyCode) || Character.isUpperCase((char)keyCode)))) { // VK_F1, F2 ... are lower case letters! Evil!
				// Cancel tagging
				Utils.showStatus("Canceled tagging");
				to_tag = null;
				to_untag = null;
				ke.consume();
				return;
			}
			if (!show_tag_dialogs && KeyEvent.VK_0 == keyCode) {
				// force dialogs for next key
				show_tag_dialogs = true;
				ke.consume();
				return;
			}

			final boolean untag = null != to_untag;
			final Node target = untag ? to_untag : to_tag;

			try {

				layer_set.addPreDataEditStep(this);

				if (show_tag_dialogs) {
					if (untag) {
						if (layer_set.askToRemoveTag(keyCode)) { // if removed from tag namespace, it will be removed from all nodes that have it
							layer_set.addDataEditStep(this);
						}
					} else {
						Tag t = layer_set.askForNewTag(keyCode);
						if (null != t) {
							target.addTag(t);
							Display.repaint(layer_set);
							layer_set.addDataEditStep(this); // no 'with' macros ... without half a dozen layers of cruft.
						}
					}
					show_tag_dialogs = false;
					return;
				}

				TreeSet<Tag> ts = layer_set.getTags(keyCode);
				if (ts.isEmpty()) {
					if (untag) return;
					if (null == layer_set.askForNewTag(keyCode)) return;
					ts = layer_set.getTags(keyCode);
				}
				// Ask to chose one, if more than one
				final Set<Tag> target_tags = target.getTags();
				if (untag && (null == target_tags || target_tags.isEmpty())) {
					// nothing to untag
					return;
				}
				if (ts.size() > 1) {
					// if the target_tags has only one tag for the given keycode, just remove it
					if (untag && null != target_tags) {
						int count = 0;
						Tag t = null;
						for (final Tag tag : target_tags) {
							if (tag.getKeyCode() == keyCode) {
								count++;
								t = tag;
							}
						}
						if (1 == count) {
							// just remove it
							target.removeTag(t);
							Display.repaint(layer_set);
							return;
						}
					}
					final JPopupMenu popup = new JPopupMenu();
					popup.add(new JLabel(untag ? "Untag:" : "Tag:"));
					int i = 1;
					for (final Tag tag : ts) {
						JMenuItem item = new JMenuItem(tag.toString());
						popup.add(item);
						if (i < 10) {
							item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, 0, true));
						}
						i++;
						if (null != target_tags) {
							if (untag) item.setEnabled(target_tags.contains(tag));
							else item.setEnabled(!target_tags.contains(tag));
						}
						item.addActionListener(new ActionListener() {
							public void actionPerformed(ActionEvent ae) {
								if (untag) target.removeTag(tag);
								else target.addTag(tag);
								Display.repaint(layer_set);
								layer_set.addDataEditStep(Tree.this);
							}
						});
					}
					popup.addSeparator();
					JMenuItem item = new JMenuItem(untag ? "Remove tag..." : "Define new tag...");
					popup.add(item);
					item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, 0, true));
					item.addActionListener(new ActionListener() {
						public void actionPerformed(ActionEvent ae) {
							if (untag) {
								layer_set.askToRemoveTag(keyCode);
							} else {
								Tag t = layer_set.askForNewTag(keyCode);
								if (null == t) return;
								target.addTag(t);
								Display.repaint(layer_set);
							}
							layer_set.addDataEditStep(Tree.this);
						}
					});

					// Show the popup on the Display, under the node
					final float[] fp = new float[]{target.x, target.y};
					this.at.transform(fp, 0, fp, 0, 1);
					Rectangle srcRect = dc.getSrcRect();
					double magnification = dc.getMagnification();
					final int x = (int)((fp[0] - srcRect.x) * magnification);
					final int y = (int)((fp[1] - srcRect.y) * magnification);
					popup.show(dc, x, y);
				} else {
					if (untag) target.removeTag(ts.first());
					else target.addTag(ts.first());
					Display.repaint(layer_set);
					layer_set.addDataEditStep(this);
				}
				return;
			} finally {
				updateViewData(untag ? to_untag : to_tag);
				to_tag = null;
				to_untag = null;
				ke.consume();
			}
		}

		Layer la = dc.getDisplay().getLayer();
		final Point po = dc.getCursorLoc(); // as offscreen coords

		// assumes Node.MAX_EDGE_CONFIDENCE is <= 9.
		if (keyCode >= KeyEvent.VK_0 && keyCode <= (KeyEvent.VK_0 + Node.MAX_EDGE_CONFIDENCE)) {
			// Find an edge near the mouse position, by measuring against the middle of it
			setEdgeConfidence((byte)(keyCode - KeyEvent.VK_0), po.x, po.y, la, dc.getMagnification());
			Display.repaint(layer_set);
			ke.consume();
			return;
		}

		final int modifiers = ke.getModifiers();
		final Display display = Display.getFront();
		final Layer layer = display.getLayer();

		Node nd = null;
		Coordinate<Node> c = null;
		try {

		switch (keyCode) {
			case KeyEvent.VK_T:
				if (0 == modifiers) {
					to_tag = findNodeNear(po.x, po.y, layer, dc.getMagnification());
				} else if (0 == (modifiers ^ KeyEvent.SHIFT_MASK)) {
					to_untag = findNodeNear(po.x, po.y, layer, dc.getMagnification());
				}
				ke.consume();
				return;
		}

		if (0 == modifiers) {
			switch (keyCode) {
				case KeyEvent.VK_R:
					nd = root;
					display.center(createCoordinate(root));
					ke.consume();
					return;
				case KeyEvent.VK_B:
					c = findPreviousBranchOrRootPoint(po.x, po.y, layer, dc.getMagnification());
					if (null == c) return;
					nd = c.object;
					display.center(c);
					ke.consume();
					return;
				case KeyEvent.VK_N:
					c = findNextBranchOrEndPoint(po.x, po.y, layer, dc.getMagnification());
					if (null == c) return;
					nd = c.object;
					display.center(c);
					ke.consume();
					return;
				case KeyEvent.VK_L:
					c = getLastAdded();
					if (null == c) return;
					nd = c.object;
					display.center(c);
					ke.consume();
					return;
				case KeyEvent.VK_E:
					c = getLastEdited();
					if (null == c) return;
					nd = c.object;
					display.center(c);
					ke.consume();
					return;
				case KeyEvent.VK_CLOSE_BRACKET:
					display.animateBrowsingTo(findNearAndGetNext(po.x, po.y, layer, dc.getMagnification()));
					ke.consume();
					return;
				case KeyEvent.VK_OPEN_BRACKET:
					display.animateBrowsingTo(findNearAndGetPrevious(po.x, po.y, layer, dc.getMagnification()));
					ke.consume();
					return;
				case KeyEvent.VK_G:
					nd = findClosestNodeW(getNodesToPaint(layer), po.x, po.y, dc.getMagnification());
					if (null != nd) {
						display.toLayer(nd.la);
						ke.consume();
						return;
					}
			}
		}
		} finally {
			if (null != nd) setLastVisited(nd);
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
			mwe.consume();
		}
	}

	/** Used when reconstructing from XML. */
	public void setRoot(final Node new_root) {
		this.root = new_root;
		if (null == new_root) clearCache();
		else cacheSubtree(new_root.getSubtreeNodes());
	}

	@Override
	public void paintSnapshot(final Graphics2D g, final Layer layer, final Rectangle srcRect, final double mag) {
		switch (layer_set.getSnapshotsMode()) {
			case 0:
				// Paint without arrows
				paint(g, srcRect, mag, false, 0xffffffff, layer, false);
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
	public ImagePlus flyThroughMarked(final int width, final int height, final double magnification, final int type, final String dir) {
		if (null == marked) return null;
		return flyThrough(root, marked, width, height, magnification, type, dir);
	}

	public LinkedList<Region> generateRegions(final Node first, final Node last, final int width, final int height, final double magnification) {
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
		return regions;
	}

	/** Fly-through image stack from first to last node. If first is not lower order than last, then start to last is returned.
	 *  @param type is ImagePlus.GRAY8 or .COLOR_RGB */
	public ImagePlus flyThrough(final Node first, final Node last, final int width, final int height, final double magnification, final int type, final String dir) {
		return project.getLoader().createFlyThrough(generateRegions(first, last, width, height, magnification), magnification, type, dir);
	}

	/** Measures number of branch points and end points, and total cable length.
	 *  Cable length is measured as:
	 *    Cable length: the sum of all distances between all consecutive pairs of nodes.
	 *    Lower-bound cable length: the sum of all distances between all end points to branch points, branch points to other branch points, and first branch point to root. */
	public ResultsTable measure(ResultsTable rt) {
		if (null == root) return rt;
		double cable = 0,
		       lb_cable = 0;
		int branch_points = 0;
		final Calibration cal = layer_set.getCalibration();
		final double pixelWidth = cal.pixelWidth;
		final double pixelHeight = cal.pixelHeight;

		final float[] fps = new float[4];
		final float[] fpp = new float[2];

		synchronized (node_layer_map) {
			for (final Collection<Node> nodes : node_layer_map.values()) {
				for (final Node nd : nodes) {
					if (nd.getChildrenCount() > 1) branch_points++;
					if (null == nd.parent) continue;
					fps[0] = nd.x;   fps[2] = nd.parent.x;
					fps[1] = nd.y;   fps[3] = nd.parent.y;
					this.at.transform(fps, 0, fps, 0, 2);
					cable += Math.sqrt(Math.pow( (fps[0] - fps[2]) * pixelWidth, 2)
							 + Math.pow( (fps[1] - fps[3]) * pixelHeight, 2)
							 + Math.pow( (nd.la.getZ() - nd.parent.la.getZ()) * pixelWidth, 2));

					// Lower bound cable length:
					if (1 == nd.getChildrenCount()) continue;
					else {
						Node prev = nd.findPreviousBranchOrRootPoint();
						if (null == prev) {
							Utils.log("ERROR: Can't find the previous branch or root point for node " + nd);
							continue;
						}
						fpp[0] = prev.x;
						fpp[1] = prev.y;
						this.at.transform(fpp, 0, fpp, 0, 1);
						lb_cable += Math.sqrt(Math.pow( (fpp[0] - fps[0]) * pixelWidth, 2)
								    + Math.pow( (fpp[1] - fps[1]) * pixelHeight, 2)
								    + Math.pow( (nd.la.getZ() - nd.parent.la.getZ()) * pixelWidth, 2));
					}
				}
			}
		}

		if (null == rt) rt = Utils.createResultsTable("Tree results", new String[]{"id", "N branch points", "N end points", "Cable length", "LB Cable length"});
		rt.incrementCounter();
		rt.addLabel("units", cal.getUnit());
		rt.addValue(0, this.id);
		rt.addValue(1, branch_points);
		rt.addValue(2, end_nodes.size());
		rt.addValue(3, cable);
		rt.addValue(4, lb_cable);

		return rt;
	}

	/** Expects Rectangle in world coords. */
	public boolean intersects(final Layer layer, final Rectangle r) {
		Set<Node> nodes = node_layer_map.get(layer);
		if (null == nodes || nodes.isEmpty()) return false;
		try {
			return null != findFirstIntersectingNode(nodes, new Area(r).createTransformedArea(this.at.createInverse()));
		} catch (NoninvertibleTransformException e) {
			IJError.print(e);
		}
		return false;
	}
	/** Expects Area in world coords. */
	public boolean intersects(final Layer layer, final Area area) {
		Set<Node> nodes = node_layer_map.get(layer);
		if (null == nodes || nodes.isEmpty()) return false;
		try {
			return null != findFirstIntersectingNode(nodes, area.createTransformedArea(this.at.createInverse()));
		} catch (NoninvertibleTransformException e) {
			IJError.print(e);
		}
		return false;
	}

	/** Expects an Area in local coordinates. */
	protected Node findFirstIntersectingNode(final Set<Node> nodes, final Area a) {
		for (final Node nd : nodes) if (nd.intersects(a)) return nd;
		return null;
	}

	@Override
	public boolean paintsAt(final Layer layer) {
		synchronized (node_layer_map) {
			Collection<Node> nodes = node_layer_map.get(layer);
			return null != nodes && nodes.size() > 0;
		}
	}

	@Override
	void removeTag(final Tag tag) {
		synchronized (node_layer_map) {
			for (final Map.Entry<Layer,Set<Node>> e : node_layer_map.entrySet()) {
				for (final Node nd : e.getValue()) {
					nd.removeTag(tag);
				}
			}
		}
	}

	private TreeNodesDataView tndv = null;

	/** Create a GUI to list, in three tabs: starting point, branch points, end points, and all points.
	 *  The table has columns for X, Y, Z, data (radius or area), Layer, and tags.
	 *  Double-click on a row positions the front display at that coordinate.
	 *  An extra tab has a search field, to list nodes for a given typed-in (regex) tag. */
	public Future<JFrame> createMultiTableView() {
		if (null == root) return null;
		return project.getLoader().doLater(new Callable<JFrame>() { public JFrame call() {
			synchronized (Tree.this) {
				if (null == tndv) {
					tndv = new TreeNodesDataView(root);
					return tndv.frame;
				} else {
					tndv.show();
					return tndv.frame;
				}
			}
		}});
	}

	protected void updateView() {
		if (null == tndv) return;
		synchronized (tndv) {
			tndv.recreate(this.root);
		}
	}
	protected void updateViewData(final Node node) {
		if (null == tndv) return;
		synchronized (tndv) {
			tndv.updateData(node);
		}
	}

	public boolean remove2(boolean check) {
		if (super.remove2(check)) {
			synchronized (this) {
				if (null != tndv) {
					tndv.frame.dispose();
					tndv = null;
				}
			}
			return true;
		}
		return false;
	}

	private class TreeNodesDataView {
		private JFrame frame;
		private List<Node> branchnodes,
				   endnodes,
				   allnodes,
				   searchnodes;
		private Table table_branchnodes = new Table(),
			      table_endnodes = new Table(),
			      table_allnodes = new Table(),
			      table_searchnodes = new Table();
		private NodeTableModel model_branchnodes,
				       model_endnodes,
				       model_allnodes,
				       model_searchnodes;
		private final HashMap<Node,NodeData> nodedata = new HashMap<Node,NodeData>();

		TreeNodesDataView(final Node root) {
			create(root);
			createGUI();
		}
		private final class Table extends JTable {
			private int last_sorted_column = -1;
			private boolean last_sorting_order = true; // descending == true
			Table() {
				super();
				getTableHeader().addMouseListener(new MouseAdapter() {
					public void mouseClicked(MouseEvent me) { // mousePressed would fail to repaint due to asynch issues
						if (2 != me.getClickCount()) return;
						int viewColumn = getColumnModel().getColumnIndexAtX(me.getX());
						int column = convertColumnIndexToModel(viewColumn);
						if (-1 == column) return;
						((NodeTableModel)getModel()).sortByColumn(column, me.isShiftDown());
						last_sorted_column = column;
						last_sorting_order = me.isShiftDown();
					}
				});
				this.addMouseListener(new MouseAdapter() {
					public void mousePressed(MouseEvent me) {
						final int row = Table.this.rowAtPoint(me.getPoint());
						if (2 == me.getClickCount()) {
							go(row);
						} else if (Utils.isPopupTrigger(me)) {
							JPopupMenu popup = new JPopupMenu();
							final JMenuItem go = new JMenuItem("Go"); popup.add(go);
							final JMenuItem review = new JMenuItem("Review"); popup.add(review);
							final JMenuItem rm_review = new JMenuItem("Remove review stack"); popup.add(rm_review);
							popup.addSeparator();
							final JMenuItem generate = new JMenuItem("Generate all review stacks"); popup.add(generate);
							final JMenuItem rm_reviews = new JMenuItem("Remove all reviews"); popup.add(rm_reviews);
							//
							ActionListener listener = new ActionListener() {
								public void actionPerformed(ActionEvent ae) {
									final Object src = ae.getSource();
									if (go == src) go(row);
									else if (generate == src) {
										if (!Utils.check("Really generate all review stacks?")) {
											return;
										}
										generateAllReviewStacks();
									}
									else if (review == src) review(row);
									else if (rm_reviews == src) {
										if (!Utils.check("Really remove all review tags and associated stacks?")) {
											return;
										}
										removeReviews();
									}
									else if (rm_review == src) removeReview(row);
								}
							};
							go.addActionListener(listener);
							review.addActionListener(listener);
							review.setEnabled(hasReviewTag(row));
							rm_review.addActionListener(listener);
							rm_review.setEnabled(hasReviewTag(row));
							generate.addActionListener(listener);
							rm_reviews.addActionListener(listener);
							popup.show(Table.this, me.getX(), me.getY());
						}
					}
				});
				this.addKeyListener(new KeyAdapter() {
					public void keyPressed(KeyEvent ke) {
						if (ke.getKeyCode() == KeyEvent.VK_G) {
							final int row = getSelectedRow();
							if (-1 != row) go(row);
						} else if (ke.getKeyCode() == KeyEvent.VK_W && (0 == (Utils.getControlModifier() ^ ke.getModifiers()))) {
							frame.dispose();
						}
					}
				});
			}
			void go(int row) {
				Display.centerAt(Tree.this.createCoordinate(((NodeTableModel)this.getModel()).nodes.get(row)));
			}
			void resort() {
				if (-1 != last_sorted_column) {
					((NodeTableModel)getModel()).sortByColumn(last_sorted_column, last_sorting_order);
				}
			}
			private boolean hasReviewTag(final int row) {
				Node nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				Set<Tag> tags = nd.getTags();
				if (null == tags) return false;
				for (Tag tag : tags) if (tag.toString().startsWith("#R-")) return true;
				return false;
			}
			private void review(int row) {
				Node nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				// See if there are any tags
				Set<Tag> tags = nd.getTags();
				if (null == tags) {
					Utils.log("Node without review tag!");
					return;
				}
				// Find a review tag, if any
				Tag review_tag = null;
				for (Tag tag : tags) {
					if (tag.toString().startsWith("#R-")) {
						review_tag = tag;
						break;
					}
				}
				if (null == review_tag) {
					Utils.log("Node without review tag!");
					return;
				}
				// Find a stack for the review tag, and open it
				Tree.this.openImage(getReviewTagPath(review_tag), nd);
			}
			private void removeReview(final int row) {
				final Node nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				if (null == nd) return;
				Tree.this.removeReview(nd);
			}
		}
		void show() {
			frame.pack();
			frame.setVisible(true);
			frame.toFront();
		}
		private void createGUI() {
			this.frame = new JFrame("Nodes for " + Tree.this);
			frame.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					Tree.this.tndv = null;
				}
			});
			JTabbedPane tabs = new JTabbedPane();
			tabs.setPreferredSize(new Dimension(500, 500));
			tabs.add("All nodes", new JScrollPane(table_allnodes));
			tabs.add("Branch nodes", new JScrollPane(table_branchnodes));
			tabs.add("End nodes", new JScrollPane(table_endnodes));

			final JTextField search = new JTextField(14);
			search.addKeyListener(new KeyAdapter() {
				public void keyPressed(KeyEvent ke) {
					if (ke.getKeyCode() == KeyEvent.VK_ENTER) {
						search(search.getText());
					}
				}
			});
			JButton b = new JButton("Search");
			b.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent ae) {
					search(search.getText());
				}
			});
			JPanel pane = new JPanel();
			GridBagLayout gb = new GridBagLayout();
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weightx = 0;
			c.gridwidth = 1;
			c.anchor = GridBagConstraints.NORTH;
			gb.setConstraints(search, c);
			pane.add(search);
			c.gridx = 1;
			gb.setConstraints(b, c);
			pane.add(b);
			c.gridx = 0;
			c.gridy = 1;
			c.gridwidth = 2;
			c.fill = GridBagConstraints.BOTH;
			pane.add(new JScrollPane(table_searchnodes));
			tabs.add("Search", pane);

			frame.getContentPane().add(tabs);
			frame.pack();
			ij.gui.GUI.center(frame);
			frame.setVisible(true);
		}
		private synchronized void create(final Node root) {
			final ArrayList<Node> branchnodes = new ArrayList<Node>(),
			      		      endnodes = new ArrayList<Node>(),
					      allnodes = null == root ? new ArrayList<Node>() : new ArrayList<Node>(root.getSubtreeNodes()),
					      searchnodes = new ArrayList<Node>();
			for (final Node nd : allnodes) {
				switch (nd.getChildrenCount()) {
					case 0: endnodes.add(nd); break;
					case 1: continue; // slab
					default: branchnodes.add(nd); break;
				}
			}

			// Swap:
			this.branchnodes = branchnodes;
			this.endnodes = endnodes;
			this.allnodes = allnodes;
			this.searchnodes = searchnodes;

			this.model_branchnodes = new NodeTableModel(branchnodes, nodedata);
			this.model_endnodes = new NodeTableModel(endnodes, nodedata);
			this.model_allnodes = new NodeTableModel(allnodes, nodedata);
			this.model_searchnodes = new NodeTableModel(searchnodes, nodedata);

			this.table_branchnodes.setModel(this.model_branchnodes);
			this.table_endnodes.setModel(this.model_endnodes);
			this.table_allnodes.setModel(this.model_allnodes);
			this.table_searchnodes.setModel(this.model_searchnodes);
		}
		void recreate(final Node root) {
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				create(root);
				for (final Table t : new Table[]{table_branchnodes, table_searchnodes, table_endnodes, table_allnodes}) {
					t.resort();
				}
				Utils.revalidateComponent(frame);
			}});
		}
		void updateData(final Node node) {
			synchronized (nodedata) {
				nodedata.remove(node);
			}
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				Utils.revalidateComponent(frame);
			}});
		}
		private void search(final String regex) {
			final StringBuilder sb = new StringBuilder();
			if (!regex.startsWith("^")) sb.append("^.*");
			sb.append(regex);
			if (!regex.endsWith("$")) sb.append(".*$");
			try {
				final Pattern pat = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
				this.searchnodes = new ArrayList<Node>();
				for (final Node nd : allnodes) {
					final Collection<Tag> tags = (Collection<Tag>) nd.getTags();
					if (null == tags) continue;
					for (final Tag tag : tags) {
						if (pat.matcher(tag.toString()).matches()) {
							this.searchnodes.add(nd);
							break;
						}
					}
				}
				this.model_searchnodes = new NodeTableModel(this.searchnodes, this.nodedata);
				this.table_searchnodes.setModel(this.model_searchnodes);
				this.frame.pack();
			} catch (Exception e) {
				IJError.print(e);
			}
		}
	}

	private final class NodeData {
		final double x, y, z;
		final String data, tags, conf;
		NodeData(final Node nd) {
			final float[] fp = new float[]{nd.x, nd.y};
			Tree.this.at.transform(fp, 0, fp, 0, 1);
			final Calibration cal = Tree.this.layer_set.getCalibration();
			this.x = fp[0] * cal.pixelHeight;
			this.y = fp[1] * cal.pixelWidth;
			this.z = nd.la.getZ() * cal.pixelWidth;
			// Assumes only RadiusNode and AreaNode exist
			if (nd.getClass() == AreaTree.AreaNode.class) {
				this.data = new StringBuilder
					(Utils.cutNumber
					  (Math.abs
					    (AreaCalculations.area
					      (((AreaTree.AreaNode)nd).getData().getPathIterator(null)))
					    * cal.pixelWidth * cal.pixelHeight, 1)).append(' ').append(cal.getUnits()).append('^').append(2).toString();
			} else {
				this.data = new StringBuilder(Utils.cutNumber(((Treeline.RadiusNode)nd).getData(), 1)).append(' ').append(cal.getUnits()).toString();
			}
			this.conf = null == nd.parent ? "root" : Byte.toString(nd.parent.getEdgeConfidence(nd));
			//
			final Set<Tag> ts = nd.getTags();
			if (null != ts) {
				if (1 == ts.size()) this.tags = ts.iterator().next().toString();
				else {
					final StringBuilder sb = new StringBuilder();
					for (final Tag t : ts) sb.append(t.toString()).append(", ");
					sb.setLength(sb.length() -2);
					this.tags = sb.toString();
				}
			} else {
				this.tags = "";
			}
		}
	}

	private class NodeTableModel extends AbstractTableModel {
		List<Node> nodes;
		final HashMap<Node,NodeData> nodedata;

		private NodeTableModel(final List<Node> nodes, final HashMap<Node,NodeData> nodedata) {
			this.nodes = nodes;
			this.nodedata = nodedata; // a cache
		}
		private String getDataName() {
			if (nodes.isEmpty()) return "Data";
			if (nodes.get(0) instanceof Treeline.RadiusNode) return "Radius";
			if (nodes.get(0) instanceof AreaTree.AreaNode) return "Area";
			return "Data";
		}
		public String getColumnName(int col) {
			switch (col) {
				case 0: return ""; // listing
				case 1: return "X";
				case 2: return "Y";
				case 3: return "Z";
				case 4: return "Layer";
				case 5: return "Edge confidence";
				case 6: return getDataName();
				case 7: return "Tags";
				default: return null; // should be an error
			}
		}
		public int getRowCount() { return nodes.size(); }
		public int getColumnCount() { return 8; }
		public Object getRawValueAt(int row, int col) {
			if (0 == nodes.size()) return null;
			final Node nd = nodes.get(row);
			switch (col) {
				case 0: return row+1;
				case 1: return getNodeData(nd).x;
				case 2: return getNodeData(nd).y;
				case 3: return getNodeData(nd).z;
				case 4: return nd.la.getParent().indexOf(nd.la) + 1;
				case 5: return getNodeData(nd).conf;
				case 6: return getNodeData(nd).data;
				case 7: return getNodeData(nd).tags;
				default: return null;
			}
		}
		public Object getValueAt(int row, int col) {
			final Object o = getRawValueAt(row, col);
			return o instanceof Double ? Utils.cutNumber(((Double)o).doubleValue(), 1) : o;
		}
		private NodeData getNodeData(final Node nd) {
			synchronized (nodedata) {
				NodeData ndat = nodedata.get(nd);
				if (null == ndat) {
					ndat = new NodeData(nd);
					nodedata.put(nd, ndat);
				}
				return ndat;
			}
		}
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		public void setValueAt(Object value, int row, int col) {}
		public void sortByColumn(final int col, final boolean descending) {
			final ArrayList<Node> nodes = new ArrayList<Node>(NodeTableModel.this.nodes);
			Collections.sort(nodes, new Comparator<Node>() {
				public int compare(Node nd1, Node nd2) {
					if (descending) {
						Node tmp = nd1;
						nd1 = nd2;
						nd2 = tmp;
					}
					Object val1 = getRawValueAt(nodes.indexOf(nd1), col);
					Object val2 = getRawValueAt(nodes.indexOf(nd2), col);
					if (7 == col) {
						// Replace empty strings with a row of z
						val1 = fixStrings(val1);
						val2 = fixStrings(val2);
					}
					return ((Comparable)val1).compareTo((Comparable)val2);
				}
			});
			this.nodes = nodes; // swap
			fireTableDataChanged();
			fireTableStructureChanged();
		}
		private final Object fixStrings(final Object val) {
			if (val.getClass() == String.class) {
				if (0 == ((String)val).length()) return "zzzzzz";
				else return ((String)val).toLowerCase();
			}
			return val;
		}
	}

	synchronized protected boolean layerRemoved(Layer la) {
		super.layerRemoved(la);
		final Set<Node> nodes = node_layer_map.remove(la);
		if (null == nodes) return true;
		final List<Tree> siblings = new ArrayList<Tree>();
		for (final Iterator<Node> it = nodes.iterator(); it.hasNext(); ) {
			final Node nd = it.next();
			it.remove();
			fireNodeRemoved(nd);
			if (null == nd.parent) {
				switch (nd.getChildrenCount()) {
					case 1:
						this.root = nd.children[0];
						nd.children[0] = null;
						break;
					case 0:
						this.root = null;
						break;
					default:
						// split: the first child remains as root:
						this.root = nd.children[0];
						nd.children[0] = null;
						// ... and the rest of children become a new Tree each when done
						for (int i=1; i<nd.children.length; i++) {
							Tree t = newInstance();
							t.addToDatabase();
							t.root = nd.children[i];
							nd.children[i] = null;
							siblings.add(t);
						}
						break;
				}
			} else {
				if (null == nd.children) {
					end_nodes.remove(nd);
				} else {
					// add all its children to the parent
					for (int i=0; i<nd.children.length; i++) {
						nd.children[i].parent = null;
						nd.parent.add(nd.children[i], nd.confidence[i]);
					}
				}
				nd.parent.remove(nd);
			}
		}
		if (!siblings.isEmpty()) {
			this.clearCache();
			if (null != this.root) this.cacheSubtree(root.getSubtreeNodes());
			for (Tree t : siblings) {
				t.cacheSubtree(t.root.getSubtreeNodes());
				t.calculateBoundingBox(la);
				layer_set.add(t);
				project.getProjectTree().addSibling(this, t);
			}
		}
		this.calculateBoundingBox(la);
		updateView();
		return true;
	}

	public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		synchronized (node_layer_map) {
			if (null == root) return true;
			final Set<Node> nodes = node_layer_map.get(la);
			if (null == nodes || nodes.isEmpty()) return true;
			AffineTransform inverse = this.at.createInverse();
			final Area localroi = roi.createTransformedArea(inverse);
			mpicbg.models.CoordinateTransform chain = null;
			for (final Node nd : nodes) {
				if (nd.intersects(localroi)) {
					if (null == chain) {
						chain = M.wrap(this.at, ict, inverse);
					}
				}
				nd.apply(chain, roi);
			}
			if (null != chain) calculateBoundingBox(la);
		}
		return true;
	}
	public boolean apply(final VectorDataTransform vdt) throws Exception {
		synchronized (node_layer_map) {
			if (null == root) return true;
			final Set<Node> nodes = node_layer_map.get(vdt.layer);
			if (null == nodes || nodes.isEmpty()) return true;
			final VectorDataTransform vlocal = vdt.makeLocalTo(this);
			for (final Node nd : nodes) {
				nd.apply(vlocal);
			}
			calculateBoundingBox(vdt.layer);
		}
		return true;
	}

	@Override
	synchronized public Collection<Long> getLayerIds() {
		final ArrayList<Long> ids = new ArrayList<Long>();
		for (final Layer la : node_layer_map.keySet()) ids.add(la.getId());
		return ids;
	}
	@Override
	synchronized public Collection<Layer> getLayersWithData() {
		return new ArrayList<Layer>(node_layer_map.keySet());
	}

	/** Returns an empty area when there aren't any nodes in @param layer. */
	@Override
	public Area getAreaAt(final Layer layer) {
		synchronized (node_layer_map) {
			final Area a = new Area();
			final Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return a; // empty
			for (final Node nd : nodes) a.add(nd.getArea()); // all local
			a.transform(this.at);
			return a;
		}
	}

	/** Retain the data within the layer range, and through out all the rest. */
	@Override
	synchronized public boolean crop(List<Layer> range) {
		// Iterate nodes and when a node sits on a Layer that doesn't belong to the range, then remove it and give its children, if any, to the parent node.
		final HashSet<Layer> keep = new HashSet<Layer>(range);
		for (final Iterator<Map.Entry<Layer,Set<Node>>> it = node_layer_map.entrySet().iterator(); it.hasNext(); ) {
			final Map.Entry<Layer,Set<Node>> e = it.next();
			if (keep.contains(e.getKey())) continue;
			else {
				// Else, remove the set of nodes for that layer
				it.remove();
				// ... and remove all nodes from their parents, merging their children
				for (final Node nd : e.getValue()) {
					// if end node, just remove it from its parent
					if (null == nd.parent) {
						// The current root:
						if (null == nd.children) {
							this.root = null;
							continue; // a tree of 1 node
						} else {
							// First child as new root:
							nd.children[0].parent = null; // the new root
							this.root = nd.children[0];
							// ... and gets any other children of the root
							for (int i=1; i<nd.children.length; i++) {
								nd.children[i].parent = null;
								nd.children[0].add(nd.children[i], nd.confidence[i]);
							}
						}
					} else {
						// Remove from its parent
						nd.parent.remove(nd);
						// ... and handle its children:
						if (null == nd.children) {
							// An end point
							continue;
						} else {
							// Else, add all its children to its parent
							for (int i=0; i<nd.children.length; i++) {
								nd.children[i].parent = null; // so it can't be rejected when adding it to a node
								nd.parent.add(nd.children[i], nd.confidence[i]);
							}
						}
					}
				}
			}
		}
		clearState();
		return true;
	}

	/** Open an image in a separate thread and returns the thread. Frees up to 1 Gb for it. */
	private Future<ImagePlus> openImage(final String path, final Node last) {
		return project.getLoader().doLater(new Callable<ImagePlus>() {
			public ImagePlus call() {
				try {
					if (!new File(path).exists()) {
						Utils.log("Could not find file " + path);
						return null;
					}
					project.getLoader().releaseMemory(1000000000L); // 1 Gb
					Opener op = new Opener();
					op.setSilentMode(true);
					final ImagePlus imp = op.openImage(path); // TODO WARNING should go via the Loader
					if (null == imp) {
						Utils.log("ERROR: could not open " + path);
					} else {
						StackWindow stack = new StackWindow(imp);
						MouseListener[] ml = stack.getCanvas().getMouseListeners();
						for (MouseListener m : ml) stack.getCanvas().removeMouseListener(m);
						stack.getCanvas().addMouseListener(new MouseAdapter() {
							public void mousePressed(MouseEvent me) {
								if (2 == me.getClickCount()) {
									me.consume();
									// Go to the node
									// Slices are 1-based: 1<=i<=N
									int slice = imp.getCurrentSlice();
									if (slice == imp.getNSlices()) {
										Display.centerAt(createCoordinate(last));
									} else {
										Node parent = last.getParent();
										int count = imp.getNSlices() -1;
										while (null != parent) {
											if (count == slice) {
												Display.centerAt(createCoordinate(parent));
												break;
											}
											// next cycle
											count--;
											parent = parent.getParent();
										};
									}
								}
							}
							public void mouseDragged(MouseEvent me) {
								if (2 == me.getClickCount()) {
									me.consume();
								}
							}
							public void mouseRelesed(MouseEvent me) {
								if (2 == me.getClickCount()) {
									me.consume();
								}
							}
						});
						for (MouseListener m : ml) stack.getCanvas().addMouseListener(m);
					}
					return imp;
				} catch (Exception e) {
					IJError.print(e);
				}
				return null;
			}
		});
	}

	public Bureaucrat generateAllReviewStacks() {
		return Bureaucrat.createAndStart(new Worker.Task("Generating review stacks") {
			public void exec() {

		// Find all end nodes and branch nodes
		// Add review tags to end nodes and branch nodes, named: "#R-<x>", where <x> is a number.
		// Generate a fly-through stack from each found node to its previous branch point or root
		final ExecutorService exe = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		// Disable window
		final TreeNodesDataView tndv = Tree.this.tndv;
		if (null != tndv && null != tndv.frame) Utils.setEnabled(tndv.frame.getContentPane(), false);
		try {
			int next = 0;
			final List<Runnable> todo = new ArrayList<Runnable>();
			final List<Future> fus = new ArrayList<Future>();
			final Object dirsync = new Object();
			for (final Map.Entry<Layer,Set<Node>> e : node_layer_map.entrySet()) {
				for (final Node nd : e.getValue()) {
					if (1 == nd.getChildrenCount() || null == nd.parent) continue; // slab node or root node
					if (Thread.currentThread().isInterrupted()) return;
					final int num = ++next;
					final Tag tag = new Tag("#R-" + next, KeyEvent.VK_R);
					nd.addTag(tag);
					updateViewData(nd);
					//
					todo.add(new Runnable() {
						public void run() {
							try {
								ImagePlus imp = project.getLoader().createLazyFlyThrough(generateRegions(nd.findPreviousBranchOrRootPoint(), nd, 512, 512, 1.0), 1.0, ImagePlus.COLOR_RGB);
								imp.setTitle(imp.getTitle() + tag.toString());
								File fdir = new File(getReviewTagPath(tag));
								synchronized (dirsync) {
									File parent = fdir.getParentFile();
									if (!parent.exists()) {
										if (!parent.mkdirs()) {
											Utils.log("FAILED to create directories " + parent.getAbsolutePath()
											+ "\nNOT created review stack for " + tag.toString());
											return;
										}
									}
								}
								ij.IJ.redirectErrorMessages();
								new FileSaver(imp).saveAsZip(fdir.getAbsolutePath());
							} catch (Exception e) {
								IJError.print(e);
								Utils.log("\nERROR: NOT created review stack for " + tag.toString());
								return;
							}
						}
					});
				}
			}
			// Now that all tags exists (and will get painted), generate the stacks
			for (final Runnable r : todo) fus.add(exe.submit(r));
			Utils.wait(fus);
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != tndv && null != tndv.frame) Utils.setEnabled(tndv.frame.getContentPane(), false);
			exe.shutdown();
			Display.repaint(getLayerSet());
		}
			}}, getProject());
	}

	private String getReviewTagPath(Tag tag) {
		// Remove the "#" from the tag name
		return getProject().getLoader().getUNUIdFolder() + "tree.review.stacks/" + getId() + "/" + tag.toString().substring(1) + ".zip";
	}
	boolean removeReview(final Node nd) {
		final Set<Tag> tags = nd.getTags();
		if (null == tags) return true;
		for (final Tag tag : tags) {
			String s = tag.toString();
			if (s.startsWith("#R-")) {
				try {
					String path = getReviewTagPath(tag);
					File f = new File(path);
					if (f.exists()) {
						if (!f.delete()) {
							Utils.log("FAILED to delete: " + path + "\n   did NOT remove tag " + tag);
							return false;
						}
					} else {
						Utils.log("No review file exists for " + s);
					}
					// Remove tag:
					nd.removeTag(tag);
					updateViewData(nd);
				} catch (Exception ee) {
					IJError.print(ee);
				}
			}
		}
		return true;
	}
	public Bureaucrat removeReviews() {
		return Bureaucrat.createAndStart(new Worker.Task("Removing review stacks") { // .. and tags
			public void exec() {

		// Remove all review tags
		// Remove all .zip stacks for this Treeline
		boolean success = true;
		for (final Map.Entry<Layer,Set<Node>> e : node_layer_map.entrySet()) {
			for (final Node nd : e.getValue()) {
				if (Thread.currentThread().isInterrupted()) return;
				success = success && removeReview(nd);
			}
		}
		File f = new File(getProject().getLoader().getUNUIdFolder() + "tree.review.stacks/" + getId());
		if (success) {
			// Remove directory (even if not empty)
			Utils.removeFile(f);
		} else {
			Utils.log("Could not delete some review stacks.\n --> Directory remains: " + f.getAbsolutePath());
		}
		Display.repaint(getLayerSet());

			}}, getProject());
	}

	public Map<Node,Collection<Displayable>> findIntersecting(final Class c) throws Exception {
		final HashMap<Node,Collection<Displayable>> m = new HashMap<Node,Collection<Displayable>>();
		Process.progressive(root.getSubtreeNodes(),
				     new TaskFactory<Node,Object>() {
					 public Callable<Object> create(final Node nd) {
						 return new Callable<Object>() {
							 public Object call() {
								final Area a = nd.getArea();
								a.transform(Tree.this.at);
								final Collection<Displayable> col = layer_set.find(c, nd.la, a, false, true);
								if (col.isEmpty()) return null;
								synchronized (m) {
									m.put(nd, col);
								}
								return null;
							 }
						 };
					 }});
		return m;
	}

	/** Returns an array of two Collection of connectors: the first one has the outgoing connectors, and the second one has the incomming connectors. */
	public List<Connector>[] findConnectors() throws Exception {
		final HashMap<Node,Collection<Displayable>> m = new HashMap<Node,Collection<Displayable>>();
		final ArrayList<Connector> outgoing = new ArrayList<Connector>();
		final ArrayList<Connector> incomming = new ArrayList<Connector>();
		Process.progressive(root.getSubtreeNodes(),
				     new TaskFactory<Node,Object>() {
					 public Callable<Object> create(final Node nd) {
						 return new Callable<Object>() {
							 public Object call() {
								final Area a = nd.getArea();
								a.transform(Tree.this.at);
								final Collection<Displayable> col = layer_set.findZDisplayables(Connector.class, nd.la, a, false, false);
								if (col.isEmpty()) return null;
								// Outgoing or incomming?
								for (final Connector c : (Collection<Connector>)(Collection)col) {
									if (c.intersectsOrigin(a)) {
										synchronized (outgoing) {
											outgoing.add(c);
										}
									} else {
										synchronized (incomming) {
											incomming.add(c);
										}
									}
								}
								return null;
							 }
						 };
					 }});
		return (List<Connector>[]) new List[]{outgoing, incomming};
	}

	@Override
	public String getShortTitle() {
		final String title = getTitle();
		if (null != title && !getClass().getSimpleName().toLowerCase().equals(title.toLowerCase())) return title;
		if (null == root) return "Empty";
		final Point3f p = getOriginPoint(true);
		return new StringBuilder("Root: x=").append(p.x).append(", y=" + p.y).append(" z=").append(p.z).toString();
	}

	public Point3f getOriginPoint(final boolean calibrated) {
		if (null == root) return null;
		return fix(root.asPoint(), calibrated, new float[2]);
	}

	/** Expects a non-null float[] for reuse, and modifies @param p in place. */
	protected Point3f fix(final Point3f p, final boolean calibrated, final float[] f) {
		f[0] = p.x;
		f[1] = p.y;
		this.at.transform(f, 0, f, 0, 1);
		p.x = f[0];
		p.y = f[1];
		if (calibrated) {
			final Calibration cal = layer_set.getCalibration();
			p.x *= cal.pixelWidth;
			p.y *= cal.pixelHeight;
			p.z *= cal.pixelWidth; // not pixelDepth!
		}
		return p;
	}
}
