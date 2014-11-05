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

import fiji.geom.AreaCalculations;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.StackWindow;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.analysis.Centrality;
import ini.trakem2.analysis.Vertex;
import ini.trakem2.parallel.Process;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Event;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Scrollbar;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.vecmath.Color3f;
import javax.vecmath.Point3f;

// To remove the warnings, both the Node and the Tree would have to know about the type of Node used.
// So: a recursive declaration of Node<T, N extends Node<T,N>> is required.

/** A sequence of points ordered in a set of connected branches. */
public abstract class Tree<T> extends ZDisplayable implements VectorData {

	protected final Map<Layer,Set<Node<T>>> node_layer_map = new HashMap<Layer,Set<Node<T>>>();

	protected final Set<Node<T>> end_nodes = new HashSet<Node<T>>();

	protected Node<T> root = null;

	protected Tree(Project project, String title) {
		super(project, title, 0, 0);
	}

	/** Reconstruct from XML. */
	protected Tree(final Project project, final long id, final HashMap<String,String> ht_attr, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase(), neither creates any root node. */
	protected Tree(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
	}

	/** Get a copy of the {@link Set} of {@link Node} that exist at {@param layer}; the {@link Node} instances are the originals.
	 * Returns an empty {@link Set} if none found. */
	public Set<Node<T>> getNodesAt(final Layer layer) {
		synchronized (node_layer_map) {
			final Set<Node<T>> s = node_layer_map.get(layer);
			return null == s ? new HashSet<Node<T>>() : new HashSet<Node<T>>(s);
		}
	}

	final protected Set<Node<T>> getNodesToPaint(final Layer active_layer) {
		return getNodesToPaint(active_layer, active_layer.getParent().getColorCueLayerRange(active_layer));
	}
	
	final protected Set<Node<T>> getNodesToPaint(final Layer active_layer, final List<Layer> color_cue_layers) {
		synchronized (node_layer_map) {
			// Determine which layers to paint
			if (layer_set.color_cues) {
				Set<Node<T>> nodes = null;
				if (-1 == layer_set.n_layers_color_cue) {
					// All layers
					nodes = new HashSet<Node<T>>();
					for (final Set<Node<T>> ns : node_layer_map.values()) nodes.addAll(ns);
				} else {
					for (final Layer la : color_cue_layers) {
						Set<Node<T>> ns = node_layer_map.get(la);
						if (null != ns) {
							if (null == nodes) nodes = new HashSet<Node<T>>();
							nodes.addAll(ns);
						}
					}
				}
				return nodes;
			}
			// Else, just the active layer, if any
			final Set<Node<T>> nodeSet = node_layer_map.get(active_layer);
			return null == nodeSet? null : new HashSet<Node<T>>(nodeSet);
		}
	}

	@Override
	final public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		paint(g, srcRect, magnification, active, channels, active_layer, layers, layer_set.paint_arrows, layer_set.paint_tags);
	}
	final public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers, final boolean with_arrows, final boolean with_tags) {
		if (null == root) {
			setupForDisplay();
			if (null == root) return;
		}

		Composite original_composite = null;
		AffineTransform gt = null;
		Stroke stroke = null;

		final Color below, above;
		if (layer_set.use_color_cue_colors) {
			below = Color.red;
			above = Color.blue;
		} else {
			below = this.color;
			above = this.color;
		}

		synchronized (node_layer_map) {
			// Determine which layers to paint
			final Set<Node<T>> nodes = getNodesToPaint(active_layer, layers);
			if (null != nodes) {
				// Filter nodes outside the srcRect
				// The DisplayNavigator and the snapshot panels call paint with the full srcRect
				// so avoid filtering for them:
				if (srcRect.x > 0 && srcRect.y > 0
				 && srcRect.width < (int)layer_set.getLayerWidth()
				 && srcRect.height < (int)layer_set.getLayerHeight()) {
					try {
						final Rectangle localRect = this.at.createInverse().createTransformedShape(srcRect).getBounds();
						for (final Iterator<Node<T>> it = nodes.iterator(); it.hasNext(); ) {
							final Node<T> nd = it.next();
							if (nd.isRoughlyInside(localRect)) continue;
							it.remove();
						}
					} catch (NoninvertibleTransformException nite) {
						IJError.print(nite);
					}
				}
				// Arrange transparency
				if (alpha != 1.0f) {
					original_composite = g.getComposite();
					g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
				}
				// Clear transform and stroke
				gt = g.getTransform();
				g.setTransform(DisplayCanvas.DEFAULT_AFFINE);
				stroke = g.getStroke();
				g.setStroke(DisplayCanvas.DEFAULT_STROKE);

				final AffineTransform to_screen = new AffineTransform();
				to_screen.scale(magnification, magnification);
				to_screen.translate(-srcRect.x, -srcRect.y);
				to_screen.concatenate(this.at);

				final Node<T>[] handles = active ? new Node[nodes.size()] : null;
				int next = 0;
				final ArrayList<Runnable> tags_tasks = new ArrayList<Runnable>();

				for (final Node<T> nd : nodes) {
					final Runnable task = nd.paint(g, active_layer, active, srcRect, magnification, nodes, this, to_screen, with_arrows, with_tags, layer_set.paint_edge_confidence_boxes, true, above, below);
					if (null != task) tags_tasks.add(task);
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
					if (active && active_layer == nd.la) handles[next++] = nd;
				}
				for (final Runnable task : tags_tasks) task.run();
				if (active) {
					for (int i=0; i<next; i++) {
						handles[i].paintHandle(g, srcRect, magnification, this);
					}
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
			for (final Collection<Node<T>> nodes : node_layer_map.values()) {
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
			final Collection<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) {
				if (null == tmp) return new Rectangle(); // 0 width and 0 height: no data
				tmp.setBounds(0, 0, 0, 0);
				return tmp;
			}
			final Rectangle r = getBounds(nodes);
			if (null == tmp) {
				if (null == r) return new Rectangle();
				return r;
			} else {
				if (null == r) tmp.setRect(0, 0, 0, 0);
				else tmp.setRect(r);
				return tmp;
			}
		}
	}

	// Call always from within a synchronized (node_layer_map) block.
	protected Rectangle getBounds(final Collection<? extends Node<T>> nodes) {
		Rectangle b = null;
		for (final Node<T> nd : nodes) {
			if (null == b) b = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
			else b.add((int)nd.x, (int)nd.y);
		}
		return b;
	}

	public boolean calculateBoundingBox(final Layer la) {
		try {
			if (null == root) {
				this.at.setToIdentity();
				this.width = 0;
				this.height = 0;
				return false;
			}

			final Rectangle box = getPaintingBounds();

			this.width = box.width;
			this.height = box.height;

			if (0 == box.x && 0 == box.y) {
				// No need to translate
				return false;
			}

			synchronized (node_layer_map) {
				// now adjust points to make min_x,min_y be the x,y
				for (final Collection<Node<T>> nodes : node_layer_map.values()) {
					for (final Node<T> nd : nodes) {
						nd.translate(-box.x, -box.y); }}
			}
			this.at.translate(box.x, box.y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.

			return true;
		} finally {
			updateBucket(la);
		}
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
				for (final Map.Entry<Layer,Set<Node<T>>> e : node_layer_map.entrySet()) {
					final double z = e.getKey().getZ();
					if (z >= z_first && z <= z_last) {
						for (final Node<T> nd : e.getValue()) {
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
			ArrayList<Layer> las = new ArrayList<Layer>(node_layer_map.keySet());
			Collections.sort(las, Layer.COMPARATOR);
			return las.get(0);
		}
	}

	private final List<Node<T>> tolink = new ArrayList<Node<T>>();

	protected final void addToLinkLater(final Node<T> nd) {
		synchronized (tolink) {
			tolink.add(nd);
		}
	}
	protected final void removeFromLinkLater(final Node<T> nd) {
		synchronized (tolink) {
			tolink.remove(nd);
		}
	}

	public boolean linkPatches() {
		if (null == root) return false;
		// Obtain local copy and clear 'tolink':
		final ArrayList<Node<T>> tolink;
		synchronized (this.tolink) {
			tolink = new ArrayList<Node<T>>(this.tolink);
			this.tolink.clear();
		}
		if (tolink.isEmpty()) return true;

		boolean must_lock = false;

		for (final Node<T> nd : tolink) {
			for (final Patch patch : (Collection<Patch>) (Collection) nd.findLinkTargets(this.at)) {
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
	abstract protected Tree<T> newInstance();

	abstract protected Node<T> newNode(float lx, float ly, Layer layer, Node<?> modelNode);

	/** Create a new node, copying some properties from the modelNode such as radius or color.
	 *  The modelNode should be the node that will become the parent of the new node,
	 *  but it doesn't have to be. */
	protected Node<T> createNewNode(float lx, float ly, Layer layer, Node<?> modelNode) {
		final Node<T> nd = newNode(lx, ly, layer, modelNode);
		if (null == modelNode) return nd;
		nd.setColor(modelNode.getColor());
		return nd;
	}

	/** To reconstruct from XML. */
	abstract public Node<T> newNode(HashMap<String,String> ht_attr);

	public boolean isDeletable() {
		return null == root;
	}

	/** Exports to type t2_treeline. */
	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
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
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		final String type = "t2_" + getClass().getSimpleName().toLowerCase();
		sb_body.append(indent).append("<").append(type).append('\n');
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, options);
		if (null != root) exportXML(this, in, sb_body, root);
		sb_body.append(indent).append("</").append(type).append(">\n");
	}

	/** One day, java will get tail-call optimization (i.e. no more stack overflow errors) and I will laugh at this function. */
	private final void exportXML(final Tree<T> tree, final String indent_base, final StringBuilder sb, final Node<T> root) {
		// Simulating recursion
		//
		// write depth-first, closing as children get written
		final LinkedList<Node<T>> list = new LinkedList<Node<T>>();
		list.add(root);
		final Map<Node<T>,Integer> table = new HashMap<Node<T>,Integer>();
		
		final StringBuilder indent = new StringBuilder(indent_base);
		
		while (!list.isEmpty()) {
			Node<T> node = list.getLast();
			if (null == node.children) {
				// Processing end point
				dataNodeXML(tree, indent, sb, node);
				list.removeLast();
				continue;
			} else {
				final Integer ii = table.get(node);
				if (null == ii) {
					// Never yet processed a child, add first
					dataNodeXML(tree, indent, sb, node);
					table.put(node, 0);
					list.add(node.children[0]);
					continue;
				} else {
					final int i = ii.intValue();
					// Are there any more children to process?
					if (i == node.children.length -1) {
						// No more children to process
						closeNodeXML(indent, sb);
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

	private final void dataNodeXML(final Tree<T> tree, final StringBuilder indent, final StringBuilder sb, final Node<T> node) {
		sb.append(indent)
		  .append("<t2_node x=\"").append(node.x)
		  .append("\" y=\"").append(node.y)
		  .append("\" lid=\"").append(node.la.getId()).append('\"');
		;
		if (null != node.parent) {
			final byte conf = node.getConfidence();
			if (Node.MAX_EDGE_CONFIDENCE != conf) sb.append(" c=\"").append(conf).append('\"');
		}
		if (null != node.color) {
			sb.append(" color=\"");
			Utils.asHexRGBColor(sb, node.color);
			sb.append('\"');
		}

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
	abstract protected boolean exportXMLNodeAttributes(StringBuilder indent, StringBuilder sb, Node<T> node);
	abstract protected boolean exportXMLNodeData(StringBuilder indent, StringBuilder sb, Node<T> node);

	private final void exportTags(final Node<T> node, final StringBuilder sb, final StringBuilder indent) {
		for (final Tag tag : (Collection<Tag>) node.getTags()) {
			sb.append(indent).append("<t2_tag name=\"").append(Displayable.getXMLSafeValue(tag.toString()))
					 .append("\" key=\"").append((char)tag.getKeyCode()).append("\" />\n");
		}
	}

	static private final void closeNodeXML(final StringBuilder indent, final StringBuilder sb) {
		sb.append(indent).append("</t2_node>\n");
	}
	
	/** @see generateSkeleton */
	@Deprecated
	public List<Point3f> generateTriangles(double scale_, int parallels, int resample) {
		return generateSkeleton(scale_, parallels, resample).verts;
	}

	/** @return a CustomLineMesh.PAIRWISE list for a LineMesh. */
	public MeshData generateSkeleton(double scale_, int parallels, int resample) {
		if (null == root) return null;
		final ArrayList<Point3f> list = new ArrayList<Point3f>();
		final ArrayList<Color3f> colors = new ArrayList<Color3f>();

		// Simulate recursion
		final LinkedList<Node<T>> todo = new LinkedList<Node<T>>();
		todo.add(root);

		final float scale = (float)scale_;
		final Calibration cal = layer_set.getCalibration();
		final float pixelWidthScaled = (float) cal.pixelWidth * scale;
		final float pixelHeightScaled = (float) cal.pixelHeight * scale;
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		final float[] fps = new float[2];
		final Map<Node<T>,Point3f> points = new HashMap<Node<T>,Point3f>();

		// A few performance tests are needed:
		// 1 - if the map caching of points helps or recomputing every time is cheaper than lookup
		// 2 - if removing no-longer-needed points from the map helps lookup or overall slows down
		//
		// The method, by the way, is very parallelizable: each is independent.

		final HashMap<Color,Color3f> cached_colors = new HashMap<Color, Color3f>();
		final Color3f cf = new Color3f(this.color);
		cached_colors.put(this.color, cf);

		boolean go = true;
		while (go) {
			final Node<T> node = todo.removeFirst();
			// Add children to todo list if any
			if (null != node.children) {
				for (final Node<T> nd : node.children) todo.add(nd);
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
				if (null == node.color) {
					colors.add(cf);
					colors.add(cf); // twice: a line segment
				} else {
					Color3f c = cached_colors.get(node.color);
					if (null == c) {
						c = new Color3f(node.color);
						cached_colors.put(node.color, c);
					}
					colors.add(c);
					colors.add(c); // twice: a line segment
				}
				if (go && node.parent != todo.getFirst().parent) {
					// node.parent point no longer needed (last child just processed)
					points.remove(node.parent);
				}
			}
		}
		//Utils.log2("Skeleton MeshData lists of same length: " + (list.size() == colors.size()));
		return new MeshData(list, colors);
	}

	@Override
	final Class<?> getInternalDataPackageClass() {
		return DPTree.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPTree(this);
	}

	private final class DPTree extends Displayable.DataPackage {
		final Node<T> root;
		DPTree(final Tree<T> t) {
			super(t);
			this.root = null == t.root ? null : t.root.clone(t.project);
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Tree<T> t = (Tree<T>)d;
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
	public boolean reRoot(float x, float y, Layer layer, double magnification) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			// Search within the nodes in layer
			Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No node at " + x + ", " + y + ", " + layer);
				return false;
			}
			nodes = null;
			// Find a node near the coordinate
			Node<T> nd = findNode(x, y, layer, magnification);
			if (null == nd) {
				Utils.log("No node near " + x + ", " + y + ", " + layer);
				return false;
			}
			
			return reRoot(nd);
		}
	}

	/** @param nd A node of this Tree. */
	public boolean reRoot(final Node<T> nd) {
		if (null == nd) return false;
		synchronized (node_layer_map) {
			Set<Node<T>> nodes = node_layer_map.get(nd.la);
			if (null == nodes || !nodes.contains(nd)) return false;
			end_nodes.add(this.root);
			end_nodes.remove(nd);
			nd.setRoot();
			this.root = nd;
		}
		updateView();
		return true;
	}

	/** Split the Tree into new Tree at the point closest to the x,y,layer world coordinate.
	 *  @return null if no node was found near the x,y,layer point with precision dependent on magnification. */
	public List<Tree<T>> splitNear(float x, float y, Layer layer, double magnification) {
		try {
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x, y);
				x = (float)po.x;
				y = (float)po.y;
			}
			synchronized (node_layer_map) {
				// Search within the nodes in layer
				Set<Node<T>> nodes = node_layer_map.get(layer);
				if (null == nodes || nodes.isEmpty()) {
					Utils.log("No nodes at " + x + ", " + y + ", " + layer);
					return null;
				}
				nodes = null;
				// Find a node near the coordinate
				Node<T> nd = findNode(x, y, layer, magnification);
				if (null == nd) {
					Utils.log("No node near " + x + ", " + y + ", " + layer + ", mag=" + magnification);
					return null;
				}
				if (null == nd.parent) {
					Utils.log("Cannot split at a root point!");
					return null;
				}
				return splitAt(nd);
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** @param nd A node of this Tree. */
	public List<Tree<T>> splitAt(final Node<T> nd) {
		if (null == nd) return null;
		try {
			ArrayList<Tree<T>> a;
			synchronized (node_layer_map) {
				// Sanity check:
				final Set<Node<T>> nodes = node_layer_map.get(nd.la);
				if (null == nodes || !nodes.contains(nd)) return null;
				// Cache the children of 'nd'
				Collection<Node<T>> subtree_nodes = new ArrayList<Node<T>>(nd.getSubtreeNodes());
				// Remove any review stacks for the nodes in the subtree
				for (final Node<T> node : subtree_nodes) {
					removeReview(node);
				}
				// Remove all children nodes of found node 'nd' from the Tree cache arrays:
				removeNode(nd, subtree_nodes);
				// Set the found node 'nd' as a new root: (was done by removeNode/Node.remove anyway)
				nd.parent = null;
				// With the found nd, now a root, create a new Tree
				Tree<T> t = newInstance();
				t.addToDatabase();
				t.root = nd;
				// ... and fill its cache arrays
				t.cacheSubtree(subtree_nodes); // includes nd itself
				// Recompute bounds -- TODO: must translate the second properly, or apply the transforms and then recalculate bounding boxes and transforms.
				t.calculateBoundingBox(null);
				// Done!
				a = new ArrayList<Tree<T>>();
				a.add(this);
				a.add(t);
			}
			this.calculateBoundingBox(null); // outside synch
			return a;
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	protected void cacheSubtree(final Iterable<Node<T>> nodes) {
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
	private final void cache(final Iterable<Node<T>> nodes, final Collection<Node<T>> end_nodes, final Map<Layer,Set<Node<T>>> node_layer_map) {
		for (final Node<T> child : nodes) {
			if (null == child.children) end_nodes.add(child);
			Set<Node<T>> nds = node_layer_map.get(child.la);
			if (null == nds) {
				nds = new HashSet<Node<T>>();
				node_layer_map.put(child.la, nds);
			}
			nds.add(child);
		}
	}

	/** Update the internal {@link Node} cache; you want to invoke this operation
	 * after altering programmatically the {@link Layer} pointers of any of the
	 * {@link Node} of this {@link Tree}.
	 */
	public void updateCache() {
		synchronized (node_layer_map) {
			clearCache();
			if (null == root) return;
			cacheSubtree(this.root.getSubtreeNodes());
		}
	}

	/** Returns true if the given point falls within a certain distance of any of the treeline segments,
	 *  where a segment is defined as the line between a clicked point and the next. */
	@Override
	public boolean contains(final Layer layer, final double x, final double y) {
		if (null == root) return false;
		final Display front = Display.getFront();
		synchronized (node_layer_map) {
			final Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) return false;
			float radius = 10;
			if (null != front) {
				double mag = front.getCanvas().getMagnification();
				radius = (float)(10 / mag);
				if (radius < 2) radius = 2;
			}
			final Point2D.Double po = inverseTransformPoint(x, y);
			return isAnyNear(nodes, (float)po.x, (float)po.y, radius * radius);
		}
	}

	protected boolean isAnyNear(final Collection<Node<T>> nodes, final float lx, final float ly, final float radius) {
		for (final Node<T> nd : nodes) {
			if (nd.isNear(lx, ly, radius)) return true;
		}
		return false;
	}

	public Node<T> getRoot() {
		return root;
	}

	protected Coordinate<Node<T>> createCoordinate(final Node<T> nd) {
		if (null == nd) return null;
		float x = nd.x;
		float y = nd.y;
		if (!this.at.isIdentity()) {
			float[] dps = new float[]{x, y};
			this.at.transform(dps, 0, dps, 0, 1);
			x = dps[0];
			y = dps[1];
		}
		return new Coordinate<Node<T>>(x, y, nd.la, nd);
	}

	public Coordinate<Node<T>> findPreviousBranchOrRootPoint(float x, float y, Layer layer, DisplayCanvas dc) {
		Node<T> nd = findNodeNear(x, y, layer, dc);
		if (null == nd) return null;
		return createCoordinate(nd.findPreviousBranchOrRootPoint());
	}
	/** If the node found near x,y,layer is a branch point, returns it; otherwise the next down
	 *  the chain; on reaching an end point, returns it. */
	public Coordinate<Node<T>> findNextBranchOrEndPoint(float x, float y, Layer layer, DisplayCanvas dc) {
		Node<T> nd = findNodeNear(x, y, layer, dc);
		if (null == nd) return null;
		return createCoordinate(nd.findNextBranchOrEndPoint());
	}

	protected Coordinate<Node<T>> findNearAndGetNext(float x, float y, Layer layer, DisplayCanvas dc) {
		Node<T> nd = findNodeNear(x, y, layer, dc);
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
	protected Coordinate<Node<T>> findNearAndGetPrevious(float x, float y, Layer layer, DisplayCanvas dc) {
		Node<T> nd = findNodeNear(x, y, layer, dc);
		if (null == nd) nd = last_visited;
		if (null == nd || null == nd.parent) return null;
		setLastVisited(nd.parent);
		return createCoordinate(nd.parent);
	}

	public Coordinate<Node<T>> getLastEdited() {
		return createCoordinate(last_edited);
	}
	public Coordinate<Node<T>> getLastAdded() {
		return createCoordinate(last_added);
	}

	/** Find an edge near the world coords x,y,layer with precision depending upon magnification,
	 *  and adjust its confidence to @param confidence.
	 *  @return the node whose parent edge is altered, or null if none found. */
	protected Node<T> setEdgeConfidence(byte confidence) {
		synchronized (node_layer_map) {
			if (null == last_visited) return null;
			last_visited.setConfidence(confidence);
			updateViewData(last_visited);
			return last_visited;
		}
	}

	/** Expects world coordinates. */
	protected Node<T> adjustEdgeConfidence(int inc, float x, float y, Layer layer, DisplayCanvas dc) {
		Node<T> nearest;
		synchronized (node_layer_map) {
			nearest = findNodeConfidenceBox(x, y, layer, dc.getMagnification());
			if (null == nearest) nearest = findNodeNear(x, y, layer, dc, true);
			if (null == nearest) return null;
			if (!nearest.adjustConfidence(inc)) {
				return null;
			}
		}
		if (null != nearest) updateViewData(nearest);
		return nearest;
	}

	/** Find the node whose confidence box for the parent edge is closest to x,y,layer, if any.  */
	private Node<T> findNodeConfidenceBox(float x, float y, Layer layer, double magnification) {
		final Set<Node<T>> nodes = node_layer_map.get(layer);
		if (null == nodes) return null;

		Point2D.Double po = inverseTransformPoint(x, y);
		x = (float)po.x;
		y = (float)po.y;

		float radius = (float)(10 / magnification);
		if (radius < 2) radius = 2;
		radius *= radius; // squared

		float min_sq_dist = Float.MAX_VALUE;
		Node<T> nearest = null;
		for (final Node<T> nd : nodes) {
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
	public Node<T> findNode(final float lx, final float ly, final Layer layer, final double magnification) {
		synchronized (node_layer_map) {
			return findClosestNode(node_layer_map.get(layer), lx, ly, magnification);
		}
	}

	/** Expects world coords; with precision depending on magnification. */
	public Node<T> findClosestNodeW(final float wx, final float wy, final Layer layer, final double magnification) {
		if (null == root) return null;
		synchronized (node_layer_map) {
			final Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			return findClosestNodeW(nodes, wx, wy, magnification);
		}
	}

	/** Expects world coords; with precision depending on magnification. */
	public Node<T> findClosestNodeW(final Collection<Node<T>> nodes, final float wx, final float wy, final double magnification) {
		float lx = wx,
		      ly = wy;
		if (!this.at.isIdentity()) {
			Point2D.Double po = inverseTransformPoint(wx, wy);
			lx = (float)po.x;
			ly = (float)po.y;
		}
		return findClosestNode(nodes, lx, ly, magnification);
	}

	/** Also sets the last visited and the receiver node. This is a GUI method. */
	protected Layer toClosestPaintedNode(final Layer active_layer, final float wx, final float wy, final double magnification) {
		final Node<T> nd = findClosestNodeW(getNodesToPaint(active_layer), wx, wy, magnification);
		if (null != nd) {
			setLastVisited(nd);
			return nd.la;
		}
		return null;
	}

	/** Expects local coords; with precision depending on magnification. */
	public Node<T> findClosestNode(final Collection<Node<T>> nodes, final float lx, final float ly, final double magnification) {
		if (null == nodes || nodes.isEmpty()) return null;
		double d = (10.0D / magnification);
		if (d < 2) d = 2;
		float min_dist = Float.MAX_VALUE;
		Node<T> nd = null;
		for (final Node<T> node : nodes) {
			float dist = Math.abs(node.x - lx) + Math.abs(node.y - ly);
			if (dist < min_dist) {
				min_dist = dist;
				nd = node;
			}
		}
		return min_dist < d ? nd : null;
	}

	/** Find the spatially closest node, in calibrated coords; expects local coords. */
	public Node<T> findNearestNode(final float lx, final float ly, final Layer layer) {
		synchronized (node_layer_map) {
			final Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			return findNearestNode(lx, ly, (float)layer.getZ(), layer.getParent().getCalibration(), nodes);
		}
	}

	private final Node<T> findNearestNode(float lx, float ly, float lz, final Calibration cal, final Collection<Node<T>> nodes) {
		if (null == nodes) return null;
		// A distance map would help here
		final float pixelWidth = (float) cal.pixelWidth;
		final float pixelHeight = (float) cal.pixelHeight;
		Node<T> nearest = null;
		float sqdist = Float.MAX_VALUE;
		for (final Node<T> nd : nodes) {
			final float d = (float) (Math.pow(pixelWidth * (nd.x - lx), 2) + Math.pow(pixelHeight * (nd.y -ly), 2) + Math.pow(pixelWidth * (nd.la.getZ() - lz), 2));
			if (d < sqdist) {
				sqdist = d;
				nearest = nd;
			}
		}
		return nearest;
	}

	/** Find the spatially closest node, in calibrated coords. */
	public Node<T> findNearestEndNode(final float lx, final float ly, final Layer layer) {
		synchronized (node_layer_map) {
			return findNearestNode(lx, ly, (float)layer.getZ(), layer.getParent().getCalibration(), end_nodes);
		}
	}

	public boolean insertNode(final Node<T> parent, final Node<T> child, final Node<T> in_between, final byte confidence) {
		synchronized (node_layer_map) {
			byte b = parent.getConfidence(child);
			parent.remove(child);
			parent.add(in_between, b);
			in_between.add(child, confidence);
			// cache
			Collection<Node<T>> subtree = in_between.getSubtreeNodes();
			cacheSubtree(subtree);
			// If child was in end_nodes, remains there

			setLastAdded(in_between);
		}
		updateView();
		addToLinkLater(in_between);
		return true;
	}

	/** Considering only the set of consecutive layers currently painted, find a point near an edge
	 *  with accuracy depending upon magnification.
	 *  @return null if none of the edges is close enough, or an array of parent and child describing the edge. */
	public Node<T>[] findNearestEdge(float x_pl, float y_pl, Layer layer, double magnification) {
		if (null == root) return null;
		// Don't traverse all, just look into nodes currently being painted according to layer_set.n_layers_color_cue
		final Collection<Node<T>> nodes = getNodesToPaint(layer);
		if (null == nodes) return null;
		//
		double d = (10.0D / magnification);
		if (d < 2) d = 2;
		double min_dist = Double.MAX_VALUE;
		Node<T>[] ns = new Node[2]; // parent and child
		//
		for (final Node<T> node : nodes) {
			if (null == node.children) continue;
			// Examine if the point is closer to the 2D-projected edge than any other so far:
			// TODO it's missing edges with parents beyond the set of painted layers,
			//      and it's doing edges to children beyond the set of painted layers.
			for (final Node<T> child : node.children) {
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
	private Node<T> findNearestChildEdge(final Node<T> parent, final float lx, final float ly) {
		if (null == parent || null == parent.children) return null;
		
		Node<T> nd = null;
		double min_dist = Double.MAX_VALUE;

		for (final Node<T> child : parent.children) {
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

	/** Will call calculateBoundingBox and repaint. */
	public boolean addNode(final Node<T> parent, final Node<T> child, final byte confidence) {

		boolean added = false;
		Collection<Node<T>> subtree = null;
		synchronized (node_layer_map) {
			Set<Node<T>> nodes = node_layer_map.get(child.la);
			if (null == nodes) {
				nodes = new HashSet<Node<T>>();
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
				subtree = child.getSubtreeNodes();
				cacheSubtree(subtree);

				setLastAdded(child);

				added = true;

			} else if (0 == nodes.size()) {
				node_layer_map.remove(child.la);
			}
		}
		if (added) {
			repaint(true, child.la);
			updateView();

			if (null != subtree) {
				synchronized (tolink) {
					tolink.addAll(subtree);
				}
			}
			return true;
		}
		return false;
	}

	/** Remove a node only (not its subtree).
	 *  @return true on success. Will return false when the node has 2 or more children.
	 *  The new edge confidence is that of the parent to the @param node. */
	public boolean popNode(final Node<T> node) {
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
					root.confidence = Node.MAX_EDGE_CONFIDENCE; // with its now non-existent parent
					if (node == last_visited) setLastVisited(root);
				} else {
					node.parent.children[node.parent.indexOf(node)] = node.children[0];
					node.children[0].parent = node.parent;
					if (node == last_visited) setLastVisited(node.parent);
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
	public void removeNode(final Node<T> node) {
		removeNode(node, node.getSubtreeNodes());
	}

	private void removeNode(final Node<T> node, final Collection<Node<T>> subtree_nodes) {
		synchronized (node_layer_map) {
			if (null == node.parent) {
				root = null;
				clearCache();
			} else {
				// if not an end-point, update cached lists
				if (null != node.children) {
					Utils.log2("Removing children of node " + node);
					for (final Node<T> nd : subtree_nodes) { // includes the node itself
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
				// Update receiver:
				setLastVisited(node.parent);
				// Finally, remove from parent node
				node.parent.remove(node);
			}
			fireNodeRemoved(node);
		}
		synchronized (tolink) {
			if (null != subtree_nodes) {
				tolink.removeAll(subtree_nodes);
			} else tolink.remove(node);
		}
		updateView();
	}

	/** Check if it is possible to join all given Trees into this,
	 * by using the first one as the receiver (which should be this),
	 * and all the others as the ones to be merged into the receiver.
	 *  Requires each Tree to have a non-null marked Node; otherwise, returns false. */
	public boolean canJoin(final List<? extends Tree<T>> ts) {
		if (null == marked) {
			Utils.log("No marked node in to-be parent Tree " + this);
			return false;
		}
		if (null == this.root) {
			Utils.log("The root of this tree is null!");
			return false;
		}
		if (1 == ts.size()) {
			Utils.log("No other trees to join!");
			return false;
		}
		for (final Tree<T> tl : ts) {
			if (this == tl) continue;
			if (null == tl.root) {
				Utils.log("Can't join: tree #" + tl.id + " does not have any nodes!");
				return false;
			}
			if (getClass() != tl.getClass()) {
				Utils.log("For joining, all trees must be of the same kind!");
				return false;
			}
			if (null == tl.marked) {
				Utils.log("No marked node in to-be child treeline " + tl);
				return false;
			}
		}
		return true;
	}

	/*  Requires each Tree to have a non-null marked Node; otherwise, returns false. */
	public boolean join(final List<? extends Tree<T>> ts) {
		if (!canJoin(ts)) return false;
		// Preconditions passed: all Tree in ts have a marked node and are of the same kind
		for (final Tree<T> tl : ts) {
			if (this == tl) continue;
			tl.marked.setRoot();
			// transform nodes from there to here
			final AffineTransform at_inv;
			try {
				at_inv = this.at.createInverse();
			} catch (NoninvertibleTransformException nite) {
				IJError.print(nite);
				return false;
			}
			final AffineTransform aff = new AffineTransform(tl.at); // 1 - to world coords
			aff.preConcatenate(at_inv);		// 2 - to this local coords
			final float[] fps = new float[2];
			for (final Node<T> nd : tl.marked.getSubtreeNodes()) {
				fps[0] = nd.x;
				fps[1] = nd.y;
				aff.transform(fps, 0, fps, 0, 1);
				nd.x = fps[0];
				nd.y = fps[1];
				nd.transformData(aff);
				// Remove review stack if any
				removeReview(nd);
			}
			addNode(this.marked, tl.marked, Node.MAX_EDGE_CONFIDENCE); // will calculateBoundingBox, hence at_inv has to be recomputed every time
			// Remove from tl pointers
			tl.root = null; // stolen!
			tl.setLastMarked(null);
			// Remove from tl cache
			synchronized (tl.node_layer_map) {
				tl.node_layer_map.clear();
			}
			tl.end_nodes.clear();
		}

		// Don't clear this.marked

		updateView();

		return true;
	}
	
	protected Node<T> findNodeNear(float x, float y, final Layer layer, final DisplayCanvas dc) {
		return findNodeNear(x, y, layer, dc, false);
	}

	/** Expects world coordinates. If no node is near x,y but there is only one node in the current Display view of the layer, then it returns that node. */
	protected Node<T> findNodeNear(float x, float y, final Layer layer, final DisplayCanvas dc, final boolean use_receiver_when_null) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = (float)po.x;
			y = (float)po.y;
		}
		synchronized (node_layer_map) {
			// Search within the nodes in layer
			Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No nodes at " + x + ", " + y + ", " + layer);
				return null;
			}
			// Find a node near the coordinate
			Node<T> nd = findNode(x, y, layer, dc.getMagnification());
			// If that fails, try any node show all by itself in the display:

			if (null == nd) {
				// Is there only one node within the srcRect?
				final Area a;
				try {
					a = new Area(dc.getSrcRect()).createTransformedArea(this.at.createInverse());
				} catch (NoninvertibleTransformException nite) {
					IJError.print(nite);
					return null;
				}
				int count = 0;
				for (final Node<T> node : nodes) {
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

			final Node<T> receiver = last_visited;
			if (null == nd && use_receiver_when_null && null != receiver && receiver.la == layer) {
				float[] f = new float[]{receiver.x, receiver.y};
				at.transform(f, 0, f, 0, 1);
				if (dc.getSrcRect().contains((int)f[0], (int)f[1])) {
					nd = receiver;
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
			Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes || nodes.isEmpty()) {
				Utils.log("No nodes at " + x + ", " + y + ", " + layer);
				return false;
			}
			nodes = null;
			// Find a node near the coordinate
			Node<T> found = findNode(x, y, layer, magnification);
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

	/** The node currently being dragged or edited in some way. */
	protected void setActive(Node<T> nd) {
		this.active = nd;
	}
	/** The node currently being dragged or edited in some way. */
	protected Node<T> getActive() { return active; }

	protected void setLastEdited(Node<T> nd) {
		this.last_edited = nd;
		setLastVisited(nd);
	}
	protected void setLastAdded(Node<T> nd) {
		this.last_added = nd;
		setLastVisited(nd);
	}
	public void setLastMarked(Node<T> nd) {
		this.marked = nd;
		setLastVisited(nd);
	}
	/** The node that paints in green, which is the receiver of events. */
	protected void setLastVisited(Node<T> nd) {
		this.last_visited = nd;
	}
	
	public Node<T> getMarked() {
		return marked;
	}

	public Node<T> getLastVisited() {
		return last_visited;
	}

	@Override
	public void deselect() {
		setLastVisited(null);
	}

	protected void fireNodeRemoved(final Node<T> nd) {
		if (nd == marked) marked = null;
		if (nd == last_added) last_added = null;
		if (nd == last_edited) last_edited = null;
		if (nd == last_visited) {
			if (null != nd.parent) last_visited = nd.parent;
			else if (nd.getChildrenCount() > 0) last_visited = nd.children[0];
			else last_visited = null;
		}
		removeFromLinkLater(nd);
		removeReview(nd);
	}

	protected void clearState() {
		// clear:
		marked = last_added = last_edited = last_visited = null;
	}

	/** The Node double-clicked on, for join operations. */
	private Node<T> marked = null;
	/** The Node clicked on, for mouse operations. */
	private Node<T> active = null;
	/** The last added node */
	private Node<T> last_added = null;
	/** The last edited node, which will be the last added as well until some other node is edited. */
	private Node<T> last_edited = null;
	/** The last visited node, either navigating or editing.
	 *  It's the only node that can receive new children by clicking*/
	private Node<T> last_visited = null;
	
	// TODO: last_visited and receiver overlap TOTALLY

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

			Node<T> found = findNode(x_pl, y_pl, layer, mag);
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
					Node<T>[] ns = findNearestEdge(x_pl, y_pl, layer, mag);
					if (null != ns) {
						found = createNewNode(x_pl, y_pl, layer, ns[0]);
						insertNode(ns[0], ns[1], found, ns[0].getConfidence(ns[1]));
						setActive(found);
					}
				} else {
					Node<T> nearest = last_visited;
					if (null == nearest) {
						Utils.showMessage("Before adding a new node, please activate an existing node\nby clicking on it, or pushing 'g' on it.");
						return;
					}
					// Find the point closest to any other starting or ending point in all branches
					//Node<T> nearest = findNearestEndNode(x_pl, y_pl, layer); // at least the root exists, so it has to find a node, any node
					// append new child; inherits radius from parent
					found = createNewNode(x_pl, y_pl, layer, nearest);
					addNode(nearest, found, Node.MAX_EDGE_CONFIDENCE);
					setActive(found);
					repaint(true, layer);
				}
				return;
			}
		} else {
			// First point
			root = createNewNode(x_p, y_p, layer, null); // world coords, so calculateBoundingBox will do the right thing
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

		setLastVisited(active);
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

	static private Node<?> to_tag = null;
	static private Node<?> to_untag = null;
	static private boolean show_tag_dialogs = false;
	
	protected boolean isTagging() { return null != to_tag || null != to_untag; }

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
			final Node<?> target = untag ? to_untag : to_tag;

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
								updateViewData(target);
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
							updateViewData(target);
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

		final Point po = dc.getCursorLoc(); // as offscreen coords

		// Set confidence of the receiver node
		if (keyCode >= KeyEvent.VK_0 && keyCode <= (KeyEvent.VK_0 + Node.MAX_EDGE_CONFIDENCE)) {
			if (null != setEdgeConfidence((byte)(keyCode - KeyEvent.VK_0))) {
				Display.repaint(layer_set);
				ke.consume();
			}
			return;
		}

		final int modifiers = ke.getModifiers();
		final Display display = Display.getFront();
		final Layer layer = display.getLayer();

		Node<T> nd = null;
		Coordinate<Node<T>> c = null;
		try {

		switch (keyCode) {
			case KeyEvent.VK_T:
				if (0 == modifiers) {
					to_tag = findNodeNear(po.x, po.y, layer, dc, true);
				} else if (0 == (modifiers ^ KeyEvent.SHIFT_MASK)) {
					to_untag = findNodeNear(po.x, po.y, layer, dc, true);
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
					c = findPreviousBranchOrRootPoint(po.x, po.y, layer, dc);
					if (null == c) return;
					nd = c.object;
					display.center(c);
					ke.consume();
					return;
				case KeyEvent.VK_N:
					c = findNextBranchOrEndPoint(po.x, po.y, layer, dc);
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
					display.animateBrowsingTo(findNearAndGetNext(po.x, po.y, layer, dc));
					ke.consume();
					return;
				case KeyEvent.VK_OPEN_BRACKET:
					display.animateBrowsingTo(findNearAndGetPrevious(po.x, po.y, layer, dc));
					ke.consume();
					return;
				case KeyEvent.VK_G:
					nd = findClosestNodeW(getNodesToPaint(layer), po.x, po.y, dc.getMagnification());
					if (null != nd) {
						display.toLayer(nd.la);
						if (nd != last_visited) {
							setLastVisited(nd);
							display.getCanvas().repaint(false);
						}
						ke.consume();
						return;
					}
					break;
			}
		}
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId() && 0 == (modifiers ^ Event.SHIFT_MASK) && KeyEvent.VK_C == keyCode) {
			nd = findClosestNodeW(getNodesToPaint(layer), po.x, po.y, dc.getMagnification());
			if (null == nd) {
				Node<T> last = getLastVisited();
				if (null != last && layer == last.getLayer()) nd = last;
			}
			if (null != nd && adjustNodeColors(nd)) {
				ke.consume();
				return;
			}
		}
		} finally {
			if (null != nd) setLastVisited(nd);
		}
	}

	protected boolean adjustNodeColors(final Node<T> nd) {
		final Color color = null == nd.color ? this.color : nd.color;
		GenericDialog gd = new GenericDialog("Node colors");
		gd.addSlider("Red: ", 0, 255, color.getRed());
		gd.addSlider("Green: ", 0, 255, color.getGreen());
		gd.addSlider("Blue: ", 0, 255, color.getBlue());
		final Scrollbar red = (Scrollbar)gd.getSliders().get(0);
		final Scrollbar green = (Scrollbar)gd.getSliders().get(1);
		final Scrollbar blue = (Scrollbar)gd.getSliders().get(2);
		final Color original = nd.color; // may be null
		final SliderListener slc = new SliderListener() {
			public void update() {
				nd.setColor(new Color(red.getValue(), green.getValue(), blue.getValue()));
				Display.repaint();
			}
		};
		red.addAdjustmentListener(slc);
		green.addAdjustmentListener(slc);
		blue.addAdjustmentListener(slc);
		final String[] choices = {"this node only", "nodes until next branch or end node", "entire subtree"};
		gd.addChoice("Apply to:", choices, choices[0]);
		gd.showDialog();
		if (gd.wasCanceled()) {
			nd.setColor(original);
			Display.repaint();
			return false;
		}
		try {
			layer_set.addDataEditStep(this);
			final Color c = new Color(red.getValue(), green.getValue(), blue.getValue());
			switch (gd.getNextChoiceIndex()) {
				case 0:
					// this node only: already done
					return true;
				case 1:
					// the downstream slab:
					for (final Node<T> node : new Node.NodeCollection<T>(nd, Node.SlabIterator.class)) {
						node.setColor(c);
					}
					return true;
				case 2:
					// the entire subtree:
					for (final Node<T> node: new Node.NodeCollection<T>(nd, Node.BreadthFirstSubtreeIterator.class)) {
						node.setColor(c);
					}
					return true;
				default:
					layer_set.removeLastUndoStep();
			}
		} finally {
			layer_set.addDataEditStep(this);
			Display.repaint();
		}
		return true;
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

			adjustEdgeConfidence(rotation > 0 ? 1 : -1, x, y, la, dc);
			Display.repaint(this);
			mwe.consume();
		}
	}

	/** Used when reconstructing from XML. */
	public void setRoot(final Node<T> new_root) {
		this.root = new_root;
		synchronized (node_layer_map) {
			if (null == new_root) clearCache();
			else cacheSubtree(new_root.getSubtreeNodes());
		}
	}

	@Override
	public void paintSnapshot(final Graphics2D g, final Layer layer, final List<Layer> layers, final Rectangle srcRect, final double mag) {
		switch (layer_set.getSnapshotsMode()) {
			case 0:
				// Paint without arrows
				paint(g, srcRect, mag, false, 0xffffffff, layer, layers, false, false);
				return;
			case 1:
				paintAsBox(g);
				return;
			default: return; // case 2: // disabled, no paint
		}
	}

	public Set<Node<T>> getEndNodes() {
		return new HashSet<Node<T>>(end_nodes);
	}

	/** Fly-through image stack from source node to mark node.
	 *  @param type is ImagePlus.GRAY8 or .COLOR_RGB */
	public ImagePlus flyThroughMarked(final int width, final int height, final double magnification, final int type, final String dir) {
		if (null == marked) return null;
		return flyThrough(root, marked, width, height, magnification, type, dir);
	}

	public LinkedList<Region<Node<T>>> generateRegions(final Node<T> first, final Node<T> last, final int width, final int height, final double magnification) {
		final LinkedList<Region<Node<T>>> regions = new LinkedList<Region<Node<T>>>();
		Node<T> node = last;
		float[] fps = new float[2];
		while (null != node) {
			fps[0] = node.x;
			fps[1] = node.y;
			this.at.transform(fps, 0, fps, 0, 1);
			regions.addFirst(new Region<Node<T>>(new Rectangle((int)fps[0] - width/2,
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
	public ImagePlus flyThrough(final Node<T> first, final Node<T> last, final int width, final int height, final double magnification, final int type, final String dir) {
		return project.getLoader().createFlyThrough(generateRegions(first, last, width, height, magnification), magnification, type, dir);
	}

	/** Measures number of branch points and end points, and total cable length.
	 *  Cable length is measured as:
	 *    Cable length: the sum of all distances between all consecutive pairs of nodes.
	 *    Lower-bound cable length: the sum of all distances between all end points to branch points, branch points to other branch points, and first branch point to root. */
	@Override
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
			for (final Collection<Node<T>> nodes : node_layer_map.values()) {
				for (final Node<T> nd : nodes) {
					if (nd.getChildrenCount() > 1) branch_points++;
					// Skip the root node
					if (null == nd.parent) continue;
					//
					fps[0] = nd.x;   fps[2] = nd.parent.x;
					fps[1] = nd.y;   fps[3] = nd.parent.y;
					this.at.transform(fps, 0, fps, 0, 2);
					cable += Math.sqrt(Math.pow( (fps[0] - fps[2]) * pixelWidth, 2)
							 + Math.pow( (fps[1] - fps[3]) * pixelHeight, 2)
							 + Math.pow( (nd.la.getZ() - nd.parent.la.getZ()) * pixelWidth, 2));

					// Lower bound cable length:
					if (1 == nd.getChildrenCount()) continue; // include only end nodes and branch nodes
					else {
						Node<T> prev = nd.findPreviousBranchOrRootPoint();
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
		Set<Node<T>> nodes = node_layer_map.get(layer);
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
		return null != firstIntersectingNode(layer, area);
	}
	/** Expects Area in world coords.
	 * @return The first {@link Node} that intersects the {@param area} at the given {@param layer}, or null if none do. */
	public Node<T> firstIntersectingNode(final Layer layer, final Area area) {
		final Set<Node<T>> nodes = node_layer_map.get(layer);
		if (null == nodes || nodes.isEmpty()) return null;
		try {
			return findFirstIntersectingNode(nodes, area.createTransformedArea(this.at.createInverse()));
		} catch (NoninvertibleTransformException e) {
			IJError.print(e);
		}
		return null;
	}

	/** Expects an Area in local coordinates. */
	protected Node<T> findFirstIntersectingNode(final Set<Node<T>> nodes, final Area a) {
		for (final Node<T> nd : nodes) if (nd.intersects(a)) return nd;
		return null;
	}

	@Override
	public boolean paintsAt(final Layer layer) {
		synchronized (node_layer_map) {
			Collection<Node<T>> nodes = node_layer_map.get(layer);
			return null != nodes && nodes.size() > 0;
		}
	}

	@Override
	void removeTag(final Tag tag) {
		synchronized (node_layer_map) {
			for (final Map.Entry<Layer,Set<Node<T>>> e : node_layer_map.entrySet()) {
				for (final Node<T> nd : e.getValue()) {
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
				try {
					if (null == tndv) {
						tndv = new TreeNodesDataView(root);
						return tndv.frame;
					} else {
						tndv.show();
						return tndv.frame;
					}
				} catch (Exception e) {
					IJError.print(e);
				}
				return null;
			}
		}});
	}

	protected void updateView() {
		if (null == tndv) return;
		synchronized (tndv) {
			tndv.recreate(this.root);
		}
	}
	protected void updateViewData(final Node<?> node) {
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
		private List<Node<T>> /*branchnodes,
				   endnodes,*/
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
		private final HashMap<Node<T>,NodeData> nodedata = new HashMap<Node<T>,NodeData>();
		private final HashSet<Node<T>> visited_reviews = new HashSet<Node<T>>();

		TreeNodesDataView(final Node<T> root) {
			create(root);
			createGUI();
		}
		private final class CustomCellRenderer extends DefaultTableCellRenderer {
			public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
				// Colorize visited review cells
				if (8 == column && visited_reviews.contains(((NodeTableModel)table.getModel()).nodes.get(row))) {
					c.setForeground(Color.white);
					c.setBackground(Color.green);
				} else {
					if (isSelected) {
						setForeground(table.getSelectionForeground());
			            setBackground(table.getSelectionBackground());
					} else {
						c.setForeground(Color.black);
						c.setBackground(Color.white);
					}
				}
				return c;
			}
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
							if (!project.isInputEnabled()) {
								Utils.showMessage("Please wait until the current task completes!");
								return;
							}
							JPopupMenu popup = new JPopupMenu();
							final JMenuItem go = new JMenuItem("Go"); popup.add(go);
							final JMenuItem review = new JMenuItem("Review"); popup.add(review);
							review.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0, true));
							final JMenuItem rm_review = new JMenuItem("Remove review stack"); popup.add(rm_review);
							popup.addSeparator();
							final JMenuItem generate = new JMenuItem("Generate all review stacks"); popup.add(generate);
							final JMenuItem gsub = new JMenuItem("Generate review stacks for subtree"); popup.add(gsub);
							final JMenuItem rm_reviews = new JMenuItem("Remove all reviews"); popup.add(rm_reviews);
							popup.addSeparator();
							final JMenuItem mark_as_reviewed = new JMenuItem("Mark selected as reviewed"); popup.add(mark_as_reviewed);
							final JMenuItem clear_visited_reviews = new JMenuItem("Unmark all reviewed"); popup.add(clear_visited_reviews);
							//
							ActionListener listener = new ActionListener() {
								public void actionPerformed(ActionEvent ae) {
									final Object src = ae.getSource();
									if (go == src) go(row);
									else if (generate == src) {
										if (!Utils.check("Really generate all review stacks?")) {
											return;
										}
										generateSubtreeReviewStacks(root);
									}
									else if (gsub == src) {
										if (!Utils.check("Really generate review stacks for the subtree?")) {
											return;
										}
										generateSubtreeReviewStacks(((NodeTableModel)getModel()).nodes.get(getSelectedRow()));
									}
									else if (review == src) review(row);
									else if (rm_reviews == src) {
										if (!Utils.check("Really remove all review tags and associated stacks?")) {
											return;
										}
										removeReviews();
										visited_reviews.clear();
									}
									else if (rm_review == src) {
										if (Utils.check("Really remove review stack for " + getReviewTags(row))) {
											removeReview(row); // will remove the node from visited_reviews
										}
									}
									else if (clear_visited_reviews == src) {
										visited_reviews.clear();
										repaint();
									}
									else if (mark_as_reviewed == src) {
										// Get multiple selection
										final NodeTableModel m = (NodeTableModel)getModel();
										for (final int row : getSelectedRows()) {
											final Node<T> nd = m.nodes.get(row);
											if (!"".equals(m.getNodeData(nd).reviews)) {
												visited_reviews.add(nd);
											}
										}
										repaint();
									}
								}
							};
							go.addActionListener(listener);
							review.addActionListener(listener);
							review.setEnabled(hasReviewTag(row));
							rm_review.addActionListener(listener);
							rm_review.setEnabled(hasReviewTag(row));
							generate.addActionListener(listener);
							rm_reviews.addActionListener(listener);
							clear_visited_reviews.addActionListener(listener);
							mark_as_reviewed.addActionListener(listener);
							popup.show(Table.this, me.getX(), me.getY());
						}
					}
				});
				this.addKeyListener(new KeyAdapter() {
					public void keyPressed(KeyEvent ke) {
						final int keyCode = ke.getKeyCode();
						final int row = getSelectedRow();
						if (keyCode == KeyEvent.VK_G) {
							if (-1 != row) go(row);
						} else if (keyCode == KeyEvent.VK_R && 0 == ke.getModifiers()) {
							// If there is a review stack, open it
							if (-1 != row) review(row);
						} else if (keyCode == KeyEvent.VK_W && (0 == (Utils.getControlModifier() ^ ke.getModifiers()))) {
							frame.dispose();
						}
					}
				});
			}
			/*
			Node<T> getNode(int row) {
				return ((NodeTableModel)this.getModel()).nodes.get(row);
			}
			*/
			/*
			String getNodeTags(int row) {
				NodeTableModel m = (NodeTableModel)this.getModel();
				Node<T> nd = m.nodes.get(row);
				return m.getNodeData(nd).tags;
			}
			*/
			String getReviewTags(int row) {
				Node<T> nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				Set<Tag> tags = nd.getTags();
				if (null == tags) return null;
				StringBuilder sb = new StringBuilder();
				for (Tag t : tags) {
					if (t.toString().startsWith("#R")) {
						sb.append(t.toString()).append(", ");
					}
				}
				if (0 == sb.length()) return null;
				sb.setLength(sb.length() -2);
				return sb.toString();
			}
			void go(int row) {
				Node<T> node = ((NodeTableModel)this.getModel()).nodes.get(row);
				setLastVisited(node);
				Display.centerAt(Tree.this.createCoordinate(node));
			}
			void resort() {
				if (-1 != last_sorted_column) {
					((NodeTableModel)getModel()).sortByColumn(last_sorted_column, last_sorting_order);
				}
			}
			private boolean hasReviewTag(final int row) {
				Node<T> nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				Set<Tag> tags = nd.getTags();
				if (null == tags) return false;
				for (Tag tag : tags) if (tag.toString().startsWith("#R-")) return true;
				return false;
			}
			private void review(int row) {
				Node<T> nd = ((NodeTableModel)this.getModel()).nodes.get(row);
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
				visited_reviews.add(nd);
				// Find a stack for the review tag, and open it
				Tree.this.openImage(getReviewTagPath(review_tag), nd);
				repaint();
			}
			private void removeReview(final int row) {
				final Node<T> nd = ((NodeTableModel)this.getModel()).nodes.get(row);
				if (null == nd) return;
				if (Tree.this.removeReview(nd)) {
					visited_reviews.remove(nd);
				}
				Display.repaint(getLayerSet());
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
			pane.setLayout(gb);
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weightx = 1;
			c.gridwidth = 1;
			c.anchor = GridBagConstraints.NORTH;
			c.fill = GridBagConstraints.BOTH;
			c.insets = new Insets(4,10,5,2);
			gb.setConstraints(search, c);
			pane.add(search);
			c.gridx = 1;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			c.insets = new Insets(4,0,5,10);
			gb.setConstraints(b, c);
			pane.add(b);
			c.gridx = 0;
			c.gridy = 1;
			c.gridwidth = 2;
			c.weighty = 1;
			c.fill = GridBagConstraints.BOTH;
			JScrollPane scp = new JScrollPane(table_searchnodes);
			c.insets = new Insets(0,0,0,0);
			gb.setConstraints(scp, c);
			pane.add(scp);
			tabs.add("Search", pane);

			frame.getContentPane().add(tabs);
			frame.pack();
			ij.gui.GUI.center(frame);
			frame.setVisible(true);
		}
		private synchronized void create(final Node<T> root) {
			final ArrayList<Node<T>> branchnodes = new ArrayList<Node<T>>(),
			      		      endnodes = new ArrayList<Node<T>>(),
					      searchnodes = new ArrayList<Node<T>>(),
					      allnodes;
			synchronized (node_layer_map) {
				allnodes = null == root ? new ArrayList<Node<T>>() : new ArrayList<Node<T>>(root.getSubtreeNodes());
			}
			for (final Node<T> nd : allnodes) {
				switch (nd.getChildrenCount()) {
					case 0: endnodes.add(nd); break;
					case 1: continue; // slab
					default: branchnodes.add(nd); break;
				}
			}
			// Remove nodes no longer present:
			visited_reviews.retainAll(allnodes);

			// Swap:
			/*
			this.branchnodes = branchnodes;
			this.endnodes = endnodes;
			*/
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
			

			try {
				CustomCellRenderer ccr = new CustomCellRenderer();
				setCellRenderer(table_branchnodes, ccr);
				setCellRenderer(table_endnodes, ccr);
				setCellRenderer(table_allnodes, ccr);
				setCellRenderer(table_searchnodes, ccr);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		void setCellRenderer(JTable t, DefaultTableCellRenderer ccr) {
			t.setDefaultRenderer(t.getColumnClass(8), ccr);
		}
		void recreate(final Node<T> root) {
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				create(root);
				table_branchnodes.resort();
				table_searchnodes.resort();
				table_endnodes.resort();
				table_allnodes.resort();
				Utils.revalidateComponent(frame);
			}});
		}
		void updateData(final Node<?> node) {
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
				this.searchnodes = new ArrayList<Node<T>>();
				for (final Node<T> nd : allnodes) {
					final Collection<Tag> tags = nd.getTags();
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
			} catch (Exception e) {
				IJError.print(e);
			}
		}
	}

	private final class NodeData {
		final double x, y, z;
		final String data, tags, conf, reviews;
		NodeData(final Node<T> nd) {
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
				final StringBuilder sb = new StringBuilder();
				final StringBuilder sbr = new StringBuilder();
				for (final Tag t : ts) {
					String s = t.toString();
					if ('#' == s.charAt(0) && 'R' == s.charAt(1)) sbr.append(s).append(", ");
					else sb.append(s).append(", ");
				}
				if (sb.length() > 0) sb.setLength(sb.length() -2);
				if (sbr.length() > 0) sbr.setLength(sbr.length() - 2);
				this.tags = sb.toString();
				this.reviews = sbr.toString();
			} else {
				this.tags = this.reviews = "";
			}
		}
	}

	private class NodeTableModel extends AbstractTableModel {
		List<Node<T>> nodes;
		final HashMap<Node<T>,NodeData> nodedata;

		private NodeTableModel(final List<Node<T>> nodes, final HashMap<Node<T>,NodeData> nodedata) {
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
				case 8: return "Reviews";
				default: return null; // should be an error
			}
		}
		public int getRowCount() { return nodes.size(); }
		public int getColumnCount() { return 9; }
		public Object getRawValueAt(int row, int col) {
			if (0 == nodes.size()) return null;
			final Node<T> nd = nodes.get(row);
			switch (col) {
				case 0: return row+1;
				case 1: return getNodeData(nd).x;
				case 2: return getNodeData(nd).y;
				case 3: return getNodeData(nd).z;
				case 4: return nd.la.getParent().indexOf(nd.la) + 1;
				case 5: return getNodeData(nd).conf;
				case 6: return getNodeData(nd).data;
				case 7: return getNodeData(nd).tags;
				case 8: return getNodeData(nd).reviews;
				default: return null;
			}
		}
		public Object getValueAt(int row, int col) {
			final Object o = getRawValueAt(row, col);
			return o instanceof Double ? Utils.cutNumber(((Double)o).doubleValue(), 1) : o;
		}
		private NodeData getNodeData(final Node<T> nd) {
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
			final ArrayList<Node<T>> nodes = new ArrayList<Node<T>>(NodeTableModel.this.nodes);
			Collections.sort(nodes, new Comparator<Node<T>>() {
				public int compare(Node<T> nd1, Node<T> nd2) {
					if (descending) {
						Node<T> tmp = nd1;
						nd1 = nd2;
						nd2 = tmp;
					}
					Object val1 = getRawValueAt(nodes.indexOf(nd1), col);
					Object val2 = getRawValueAt(nodes.indexOf(nd2), col);
					if (col > 6) { // 7 and 8 are tags
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
		final Set<Node<T>> nodes;
		synchronized (node_layer_map) {
			nodes = node_layer_map.remove(la);
		}
		if (null == nodes) return true;
		for (final Iterator<Node<T>> it = nodes.iterator(); it.hasNext(); ) {
			final Node<T> nd = it.next();
			it.remove();
			fireNodeRemoved(nd);
			if (null == nd.parent) {
				switch (nd.getChildrenCount()) {
					case 1:
						this.root = nd.children[0];
						this.root.parent = null;
						nd.children[0] = null; // does not matter
						break;
					case 0:
						this.root = null;
						break;
					default:
						// split: the first child remains as root:
						this.root = nd.children[0];
						this.root.parent = null;
						nd.children[0] = null;
						// ... and the rest of children become children of the new root
						for (int i=1; i<nd.children.length; i++) {
							nd.children[i].parent = null;
							this.root.add(nd.children[i], nd.children[i].confidence);
							nd.children[i] = null; // does not matter
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
						nd.parent.add(nd.children[i], nd.children[i].confidence);
					}
				}
				nd.parent.remove(nd);
			}
		}
		this.calculateBoundingBox(la);
		updateView();
		return true;
	}

	public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		mpicbg.models.CoordinateTransform chain = null;
		synchronized (node_layer_map) {
			if (null == root) return true;
			final Set<Node<T>> nodes = node_layer_map.get(la);
			if (null == nodes || nodes.isEmpty()) return true;
			AffineTransform inverse = this.at.createInverse();
			final Area localroi = roi.createTransformedArea(inverse);
			for (final Node<T> nd : nodes) {
				if (nd.intersects(localroi)) {
					if (null == chain) {
						chain = M.wrap(this.at, ict, inverse);
					}
				}
				nd.apply(chain, roi);
			}
		}
		if (null != chain) calculateBoundingBox(la);
		return true;
	}
	public boolean apply(final VectorDataTransform vdt) throws Exception {
		synchronized (node_layer_map) {
			if (null == root) return true;
			final Set<Node<T>> nodes = node_layer_map.get(vdt.layer);
			if (null == nodes || nodes.isEmpty()) return true;
			final VectorDataTransform vlocal = vdt.makeLocalTo(this);
			for (final Node<T> nd : nodes) {
				nd.apply(vlocal);
			}
		}
		calculateBoundingBox(vdt.layer);
		return true;
	}

	@Override
	public Collection<Long> getLayerIds() {
		synchronized (node_layer_map) {
			final ArrayList<Long> ids = new ArrayList<Long>(node_layer_map.size());
			for (final Layer la : node_layer_map.keySet()) ids.add(la.getId());
			return ids;
		}
	}
	@Override
	public Collection<Layer> getLayersWithData() {
		synchronized (node_layer_map) {
			return new ArrayList<Layer>(node_layer_map.keySet());
		}
	}

	/** In world coordinates. Returns an empty area when there aren't any nodes in @param layer. */
	@Override
	public Area getAreaAt(final Layer layer) {
		synchronized (node_layer_map) {
			final Area a = new Area();
			final Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) return a; // empty
			for (final Node<T> nd : nodes) a.add(nd.getArea()); // all local
			a.transform(this.at);
			return a;
		}
	}

	/** Fast and dirty, never returns a false negative but may return a false positive. */
	@Override
	protected boolean isRoughlyInside(final Layer layer, final Rectangle box) {
		synchronized (node_layer_map) {
			final Set<Node<T>> nodes = node_layer_map.get(layer);
			if (null == nodes) return false;
			try {
				final Rectangle local = this.at.createInverse().createTransformedShape(box).getBounds();
				for (final Node<T> nd : nodes) {
					// May not be enough for lots of corner cases
					// such as:
					//  * parent and child node outside, but paint inside
					//  * data of an outside node spills inside the box
					//
					// if (local.contains((int)nd.x, (int)nd.y)) return true;

					// A bit more careful:
					if (nd.isRoughlyInside(local)) return true;
				}
				return false;
			} catch (NoninvertibleTransformException nite) {
				IJError.print(nite);
				return false;
			}
		}
	}

	/** Retain the data within the layer range, and through out all the rest.
	 *  Does NOT call calculateBoundingBox or updateBucket; that is your responsibility. */
	@Override
	public boolean crop(List<Layer> range) {
		synchronized (node_layer_map) {
			// Iterate nodes and when a node sits on a Layer that doesn't belong to the range, then remove it and give its children, if any, to the parent node.
			final HashSet<Layer> keep = new HashSet<Layer>(range);
			for (final Iterator<Map.Entry<Layer,Set<Node<T>>>> it = node_layer_map.entrySet().iterator(); it.hasNext(); ) {
				final Map.Entry<Layer,Set<Node<T>>> e = it.next();
				if (keep.contains(e.getKey())) continue;
				else {
					// Else, remove the set of nodes for that layer
					it.remove();
					// ... and remove all nodes from their parents, merging their children
					for (final Node<T> nd : e.getValue()) {
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
									nd.children[0].add(nd.children[i], nd.children[i].confidence);
								}
							}
						} else {
							// Remove from its parent
							Node<T> nd_parent = nd.parent; //cached, since the statement below will nullify it
							nd.parent.remove(nd);
							// ... and handle its children:
							if (null == nd.children) {
								// An end point
								continue;
							} else {
								// Else, add all its children to its parent
								for (int i=0; i<nd.children.length; i++) {
									nd.children[i].parent = null; // so it can't be rejected when adding it to a node
									nd_parent.add(nd.children[i], nd.children[i].confidence);
								}
							}
						}
					}
				}
			}
			clearState();
			return true;
		}
	}

	/** Open an image in a separate thread and returns the thread. Frees up to 1 Gb for it. */
	private Future<ImagePlus> openImage(final String path, final Node<T> last) {
		return project.getLoader().doLater(new Callable<ImagePlus>() {
			public ImagePlus call() {
				try {
					if (!new File(path).exists()) {
						Utils.log("Could not find file " + path);
						return null;
					}
					project.getLoader().releaseToFit(1000000000L); // 1 Gb : can't tell for jpegs and tif-jpg, TODO would have to read the header.
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
							@Override
							public void mousePressed(MouseEvent me) {
								if (2 == me.getClickCount()) {
									me.consume();
									// Go to the node
									// Slices are 1-based: 1<=i<=N
									int slice = imp.getCurrentSlice();
									if (slice == imp.getNSlices()) {
										Display.centerAt(createCoordinate(last));
									} else {
										Node<T> parent = last.getParent();
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
							@Override
							public void mouseDragged(MouseEvent me) {
								if (2 == me.getClickCount()) {
									me.consume();
								}
							}
							@Override
							public void mouseReleased(MouseEvent me) {
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
		return generateSubtreeReviewStacks(root);
	}

	public Bureaucrat generateSubtreeReviewStacks(final Node<T> root) {
		return Bureaucrat.createAndStart(new Worker.Task("Generating review stacks") {
			public void exec() {
				if (null == root) return;

		// Find all end nodes and branch nodes
		// Add review tags to end nodes and branch nodes, named: "#R-<x>", where <x> is a number.
		// Generate a fly-through stack from each found node to its previous branch point or root
		final int nproc = Runtime.getRuntime().availableProcessors();
		final ExecutorService exe = Executors.newFixedThreadPool(Math.max(1, Math.min(4, nproc)));
		// Above, use maximum 4 threads. I/O bound operations don't deal well with more.
		
		// Disable window
		final TreeNodesDataView tndv = Tree.this.tndv;
		if (null != tndv && null != tndv.frame) Utils.setEnabled(tndv.frame.getContentPane(), false);
		try {
			final List<Future<?>> fus = new ArrayList<Future<?>>();
			final Object dirsync = new Object();
			
			final ArrayList<Node<T>> be_nodes = new ArrayList<Node<T>>();
			// Remove all reviews, if any
			for (final Node<T> nd : root.getSubtreeNodes()) {
				removeReview(nd);
				if (1 != nd.getChildrenCount()) be_nodes.add(nd);
			}

			final Runnable[] rs = new Runnable[be_nodes.size()];
			final int n_digits = Integer.toString(rs.length).length();
			int k = 0;
			for (final Node<T> nd : be_nodes) {
				if (Thread.currentThread().isInterrupted()) return;
				final Tag tag = new Tag("#R-" + Utils.zeroPad(k+1, n_digits), KeyEvent.VK_R);
				nd.addTag(tag);
				updateViewData(nd);
				rs[k] = new Runnable() {
					public void run() {
						String filepath = getReviewTagPath(tag);
						synchronized (dirsync) {
							if (!Utils.ensure(filepath)) {
								Utils.log("Did NOT create review stack for tag " + tag);
								return;
							}
						}
						createReviewStack(nd.findPreviousBranchOrRootPoint(), nd, tag, filepath, 512, 512, 1.0, ImagePlus.COLOR_RGB);
					}
				};
				k++;
			}
			Display.repaint(getLayerSet());
			// Now that all tags exists (and will get painted), generate the stacks
			for (int i=0; i<rs.length; i++) fus.add(exe.submit(rs[i]));
			Utils.wait(fus);
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != tndv && null != tndv.frame) Utils.setEnabled(tndv.frame.getContentPane(), true);
			exe.shutdown();
			Display.repaint(getLayerSet());
		}
			}}, getProject());
	}

	/** The behavior is undefined if @param last is not a descendant of @param first. */
	public void createReviewStack(final Node<T> first, final Node<T> last, final Tag tag, final String filepath, final int width, final int height, final double magnification, final int image_type) {
		try {
			ImagePlus imp = project.getLoader().createLazyFlyThrough(generateRegions(first, last, width, height, magnification), magnification, image_type, this);
			imp.setTitle(imp.getTitle() + tag.toString());
			ij.IJ.redirectErrorMessages();
			new FileSaver(imp).saveAsZip(filepath);
		} catch (Exception e) {
			IJError.print(e);
			Utils.log("\nERROR: NOT created review stack for " + tag.toString());
			return;
		}
	}
	
	
	private String getReviewTagPath(Tag tag) {
		// Remove the "#" from the tag name
		return getProject().getLoader().getUNUIdFolder() + "tree.review.stacks/" + getId() + "/" + tag.toString().substring(1) + ".zip";
	}
	boolean removeReview(final Node<T> nd) {
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
		for (final Map.Entry<Layer,Set<Node<T>>> e : node_layer_map.entrySet()) {
			for (final Node<T> nd : e.getValue()) {
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

	public Map<Node<T>,Collection<Displayable>> findIntersecting(final Class<?> c) throws Exception {
		final HashMap<Node<T>,Collection<Displayable>> m = new HashMap<Node<T>,Collection<Displayable>>();
		Process.progressive(root.getSubtreeNodes(),
							new TaskFactory<Node<T>,Object>() {
								@Override
					 			public Object process(final Node<T> nd) {
					 				final Area a = nd.getArea();
					 				a.transform(Tree.this.at);
					 				final Collection<Displayable> col = layer_set.find(c, nd.la, a, false, true);
					 				if (col.isEmpty()) return null;
					 				synchronized (m) {
					 					m.put(nd, col);
					 				}
					 				return null;
					 			}
					 		});
		return m;
	}

	/** Returns an array of two Collection of connectors: the first one has the outgoing connectors, and the second one has the incoming connectors. */
	@SuppressWarnings("unchecked")
	public List<Connector>[] findConnectors() throws Exception {
		final ArrayList<Connector> outgoing = new ArrayList<Connector>();
		final ArrayList<Connector> incoming = new ArrayList<Connector>();
		if (null != root) {
			Process.progressive(root.getSubtreeNodes(),
				     new TaskFactory<Node<T>,Object>() {
						@Override
					 	public Object process(final Node<T> nd) {
							final Area a = nd.getArea();
							a.transform(Tree.this.at);
							final Collection<Displayable> col = layer_set.findZDisplayables(Connector.class, nd.la, a, false, false);
							if (col.isEmpty()) return null;
							// Outgoing or incoming?
							for (final Connector c : (Collection<Connector>)(Collection)col) {
								if (c.intersectsOrigin(a, nd.la)) {
									synchronized (outgoing) {
										outgoing.add(c);
									}
								} else {
									synchronized (incoming) {
										incoming.add(c);
									}
								}
							}
							return null;
						}
					 });
		}
		return (List<Connector>[]) new List[]{outgoing, incoming};
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
	
	/** Return the {@link Node} as a point in space.
	 * @param nd The Node to extract coordinates from.
	 * @param calibrated Whether to calibrate or not the point.
	 * @return The Point3f representing the node. */
	public Point3f asPoint(final Node<T> nd, final boolean calibrated) {
		return fix(nd.asPoint(), calibrated, new float[2]);
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

	/** Takes a collection of Tree instances and creates duplicate siblings of the target class.
	 *  Will ignore any non-tree Displayable instances in the Collection.
	 *  The duplicated trees are added to the ProjectTree as siblings of the originals, and to the LayerSet.
	 *  @return The map of original trees vs copies in target class form. */
	public static<A extends Tree<?>> Map<Tree<?>,Tree<?>> duplicateAs(final Collection<Displayable> col, final Class<A> target) throws Exception {
		final HashMap<Tree<?>,Tree<?>> m = new HashMap<Tree<?>, Tree<?>>();
		for (final Displayable d : col) {
			if (target.isInstance(d)) {
				Utils.log(d + " is already of class " + target.getSimpleName());
				continue;
			}
			if (!(d instanceof Tree<?>)) {
				Utils.log("Ignoring " + d + ": not a Tree subclass");
				continue;
			}
			final Tree<?> src = (Tree<?>)d;
			if (null == src.root) {
				Utils.log("Ignoring empty tree " + src);
				continue;
			}
			if (Treeline.class == target) {
				/*
				// Specific for a target Treeline:
				
				final Treeline t = new Treeline(src.project, src.title);
				t.at.setTransform(src.at);
				
				final Map<Node<?>,Treeline.RadiusNode> rel = new HashMap<Node<?>,Treeline.RadiusNode>();
				final LinkedList<Node<?>> todo = new LinkedList<Node<?>>();
				//t.root = new Treeline.RadiusNode(src.root.x, src.root.y, src.root.la);
				todo.add(src.root);
				while (!todo.isEmpty()) {
					final Node<?> a = todo.removeLast();
					// Put all children nodes to the end of the todo list
					if (null != a.children)
						for (final Node<?> child : a.children)
							todo.add(child);
					// Copy the content of the 'a' node
					Treeline.RadiusNode copy = new Treeline.RadiusNode(a.x, a.y, a.la);
					copy.copyProperties(a);
					// Store relationship between original and copy
					rel.put(a, copy);
					// Find parent if any
					if (null == a.parent) continue;
					// .. and if found, add the copy to the copied parent:
					rel.get(a.parent).add(copy, copy.confidence);
				}
				*/
				
				m.put(src, copyAs(src, Treeline.class, Treeline.RadiusNode.class));
			} else if (AreaTree.class == target) {
				m.put(src, copyAs(src, AreaTree.class, AreaTree.AreaNode.class));
			} else {
				Utils.log("Ignoring " + src);
			}
			final Tree<?> copy = m.get(src);
			if (null != copy) {
				src.layer_set.add(copy);
				if (null == src.project.getProjectTree().addSibling(src, copy)) {
					Utils.log("Could not add " + src.getClass().getSimpleName() + " as " + target.getSimpleName());
					m.remove(src);
					src.layer_set.remove(copy);
				}
			}
 		}
		return m;
	}
	
	/** Can copy a Treeline to an AreaTree and viceversa.
	 *  Copies the transform, the nodes (with tags and confidence), and the color. The transparency, locked stated and links are not copied. */
	static public<A extends Tree<?>, B extends Node<?>> A copyAs(final Tree<?> src, final Class<A> tree_class, final Class<B> node_class) throws Exception {
		final String title = "copy of " + src.title + " #" + src.id;
		final A t = tree_class.getConstructor(Project.class, String.class).newInstance(src.project, title);
		t.at.setTransform(src.at);
		t.color = src.color;
		t.width = src.width;
		t.height = src.height;

		final Map<Node<?>,B> rel = new HashMap<Node<?>,B>();
		final LinkedList<Node<?>> todo = new LinkedList<Node<?>>();
		//t.root = new Treeline.RadiusNode(src.root.x, src.root.y, src.root.la);
		todo.add(src.root);
		while (!todo.isEmpty()) {
			final Node<?> a = todo.removeLast();
			// Put all children nodes to the end of the todo list
			if (null != a.children)
				for (final Node<?> child : a.children)
					todo.add(child);
			// Copy the content of the 'a' node
			final B copy = node_class.getConstructor(Float.TYPE, Float.TYPE, Layer.class).newInstance(a.x, a.y, a.la);
			copy.copyProperties(a);
			// Store relationship between original and copy
			rel.put(a, copy);
			// Find parent if any
			if (null == a.parent) {
				// Set the copy as the root
				t.root = (Node)copy; // need to cast
				continue;
			}
			// .. and if found, add the copy to the copied parent:
			rel.get(a.parent).add((Node)copy, copy.confidence); // TODO no other way than to cast?
		}
		// create internals
		t.cacheSubtree((Collection)t.root.getSubtreeNodes());
		return t;
	}

	/** One color per vertex. */
	static public class MeshData {
		final public List<Color3f> colors;
		final public List<Point3f> verts;
		public MeshData(final List<Point3f> v, final List<Color3f> c) {
			this.verts = v;
			this.colors = c;
		}
	}

	private Node<T> guiFindNode(final float x, final float y, final Layer layer, final double magnification) {
		final Collection<Node<T>> nodes;
		synchronized (node_layer_map) {
			nodes = node_layer_map.get(layer);
		}
		if (null == nodes) {
			Utils.log("No nodes in layer " + layer);
			return null;
		}
		final Node<T> node = findClosestNodeW(nodes, x, y, magnification);
		if (null == node) {
			Utils.log("Could not find any node! Zoom in for better precision.");
		}
		return node;
	}

	/** @return null if no node is near @param x, @param y */ 
	public Bureaucrat generateReviewStackForSlab(final float x, final float y, final Layer layer, final double magnification) {
		return generateReviewStackForSlab(guiFindNode(x, y, layer, magnification));
	}

	public Bureaucrat generateSubtreeReviewStacks(int x, int y, Layer layer, double magnification) {
		return generateSubtreeReviewStacks(guiFindNode(x, y, layer, magnification));
	}

	/** Generate a review stack from the previous branch node or root, to the next branch node or end node. */
	public Bureaucrat generateReviewStackForSlab(final Node<T> node) {
		return Bureaucrat.createAndStart(new Worker.Task("Create review stack") {
			public void exec() {
				if (null == node) return;
				final Node<T> first = node.findPreviousBranchOrRootPoint();
				final Node<T> last = node.findNextBranchOrEndPoint();
				// Check if 'last' already has a review tag
				final Set<Tag> tags = last.getTags();
				String name = "#R-slab-1";
				if (null != tags && !tags.isEmpty()) {
					final ArrayList<Integer> a = new ArrayList<Integer>();
					for (Tag t : tags) {
						if (t.toString().startsWith("#R-slab-")) {
							a.add(Integer.parseInt(t.toString().substring(7)));
						}
					}
					if (a.isEmpty()) name += 1;
					else {
						Collections.sort(a);
						name = name + (a.get(a.size()-1) + 1);
					}
				}
				final Tag tag = new Tag(name, KeyEvent.VK_R);
				last.addTag(tag);
				final String filepath = getReviewTagPath(tag);
				Utils.ensure(filepath);
				createReviewStack(first, last, tag, filepath, 512, 512, 1.0, ImagePlus.COLOR_RGB);
			}}, project);
	}
	
	static public final class MeasurePathDistance<I> {
		private double dist = 0;
		private int branch_points = 0;
		final private float[] fpA = new float[2],
							  fpB = new float[2];
		final private float firstx,
							firsty;
		final private List<Node<I>> path;
		final private Calibration cal;
		final private Tree<I> tree;
		final private Node<I> a, b;
		
		public double getDistance() { return dist; }
		public List<Node<I>> getPath() { return path; }
		public int getBranchNodesInPath() { return branch_points; }
		public Point3f getFirstNodeCoordinates() {
			return new Point3f(firstx, firsty, (float)(path.get(0).la.getZ() * cal.pixelWidth));
		}
		public Point3f getLastNodeCoordinates() {
			if (1 == path.size()) {
				return getFirstNodeCoordinates();
			}
			return new Point3f(fpB[0], fpB[1], (float)(path.get(path.size()-1).la.getZ() * cal.pixelWidth));
		}
		public MeasurePathDistance(final Tree<I> tree, final Node<I> a, final Node<I> b) {
			this(tree, a, b, Node.findPath(a, b));
		}
		/** @throws an Exception if a path cannot be found between @param a and @param b. */
		private MeasurePathDistance(final Tree<I> tree, final Node<I> a, final Node<I> b, final List<Node<I>> path) {
			this.path = path;
			this.cal = tree.layer_set.getCalibrationCopy();
			this.tree = tree;
			this.a = a;
			this.b = b;
			final Iterator<Node<I>> it = path.iterator();
			//
			Node<?> first = it.next();
			if (first.getChildrenCount() > 1) branch_points++;
			fpA[0] = first.x;
			fpA[1] = first.y;
			tree.at.transform(fpA, 0, fpA, 0, 1);
			double zA = first.la.getZ();
			//
			firstx = fpA[0];
			firsty = fpA[1];
			//
			if (1 == path.size()) {
				fpB[0] = fpA[0];
				fpB[1] = fpA[1];
			}
			//
			while (it.hasNext()) {
				Node<?> second = it.next();
				if (second.getChildrenCount() > 1) branch_points++;
				fpB[0] = second.x;
				fpB[1] = second.y;
				tree.at.transform(fpB, 0, fpB, 0, 1);
				double zB = second.la.getZ();
				dist += Math.sqrt(Math.pow((fpB[0] - fpA[0]) * cal.pixelWidth, 2)
						+ Math.pow((fpB[1] - fpA[1]) * cal.pixelHeight, 2)
						+ Math.pow((zB - zA) * cal.pixelWidth, 2));
				// prepare next iteration
				first = second;
				fpA[0] = fpB[0];
				fpA[1] = fpB[1];
				zA = zB;
			}
		}
		/** Reuses @param rt unless it is null, in which case it creates a new one. */
		public ResultsTable show(ResultsTable rt) {
			if (null == rt) rt = Utils.createResultsTable("Tree path measurements", new String[]{"id", "XA", "YA", "Layer A", "XB", "YB", "Layer B", "distance", "N nodes", "N branch points"});
			rt.incrementCounter();
			rt.addLabel("units", cal.getUnit());
			rt.addValue(0, tree.id);
			rt.addValue(1, firstx);
			rt.addValue(2, firsty);
			rt.addValue(3, tree.layer_set.indexOf(a.la) + 1); // 1-based, not zero-based!
			rt.addValue(4, fpB[0]);
			rt.addValue(5, fpB[1]);
			rt.addValue(6, tree.layer_set.indexOf(b.la) + 1);
			rt.addValue(7, dist);
			rt.addValue(8, path.size());
			rt.addValue(9, branch_points);
			return rt;
		}
	}

	/** Reuses @param rt unless it is null, in which case it creates a new one.
	 *  Will check if both nodes belong to this tree.
	 *  @return The used ResultsTable instance. */
	public ResultsTable measurePathDistance(final Node<T> a, final Node<T> b, ResultsTable rt) {
		synchronized (node_layer_map) {
			// Do both nodes belong to this Tree?
			Set<Node<T>> nodes1 = node_layer_map.get(a.la);
			if (null == nodes1 || !nodes1.contains(a)) {
				Utils.log("Tree.measurePathDistance: node " + a + " does not belong to tree " + this);
				return rt;
			}
			Set<Node<T>> nodes2 = node_layer_map.get(b.la);
			if (null == nodes2 || !nodes2.contains(b)) {
				Utils.log("Tree.measurePathDistance: node " + b + " does not belong to tree " + this);
				return rt;
			}
			try {
				return new MeasurePathDistance<T>(this, a, b).show(rt);
			} catch (Exception e) {
				IJError.print(e);
			}
			return rt;
		}
	}
	/** Measure the distance, in calibrated units, between nodes a and b of this tree.
	 *  Does not check if the nodes really belong to this tree. */
	public double measurePathDistance(final Node<T> a, final Node<T> b) throws Exception {
		return new MeasurePathDistance<T>(this, a, b).getDistance();
	}

	/** Search all nodes for unique tags and returns them. */
	public Set<Tag> findTags() {
		final HashSet<Tag> tags = new HashSet<Tag>();
		synchronized (node_layer_map) {
			for (final Set<Node<T>> nodes : node_layer_map.values()) {
				for (final Node<T> node : nodes) {
					final Set<Tag> t = node.getTags();
					if (null == t) continue;
					tags.addAll(t);
				}
			}
		}
		return tags;
	}
	
	@Override
	public void destroy() {
		super.destroy();
		TreeConnectorsView.dispose(this);
	}

	public HashMap<Node<T>,Integer> computeAllDegrees() {
		if (null == root) return new HashMap<Node<T>,Integer>();
		return root.computeAllDegrees();
	}
	
	public Collection<Node<T>> getBranchNodes() {
		if (null == root) return new ArrayList<Node<T>>();
		return root.getBranchNodes();
	}
	public Collection<Node<T>> getBranchAndEndNodes() {
		if (null == root) return new ArrayList<Node<T>>();
		return root.getBranchAndEndNodes();
	}

	static private final<T> Collection<Vertex<Node<T>>> findNeighbors(final Node<T> node, final HashMap<Node<T>,Vertex<Node<T>>> m) {
		final Node<T> parent = node.getParent();
		final Collection<Vertex<Node<T>>> neighbors = new ArrayList<Vertex<Node<T>>>();
		if (null != parent) neighbors.add(m.get(parent));
		for (final Node<T> child : node.getChildrenNodes()) {
			neighbors.add(m.get(child));
		}
		return neighbors;
	}

	/** Return a representation of this Tree with Vertex instead of Node. */
	public HashMap<Node<T>,Vertex<Node<T>>> asVertices() {
		final HashMap<Node<T>,Vertex<Node<T>>> m = new HashMap<Node<T>,Vertex<Node<T>>>();
		if (null == root) return m;
		// Create one Vertex per Node<T>
		for (final Node<T> node : this.getRoot().getSubtreeNodes()) {
			m.put(node, new Vertex<Node<T>>(node));
		}
		// Determine the neighbors of that Vertex
		for (final Map.Entry<Node<T>,Vertex<Node<T>>> e : m.entrySet()) {
			e.getValue().neighbors.addAll(findNeighbors(e.getKey(), m));
		}
		return m;
	}

	/** Computes betweenness centrality of each node in the tree,
	 *  using Ulrik Brandes betweenness centrality algorithm. */
	public HashMap<Node<T>,Float> computeCentrality() {
		final HashMap<Node<T>,Float> cs = new HashMap<Node<T>,Float>();
		if (null == root) return cs;

		final HashMap<Node<T>,Vertex<Node<T>>> m = asVertices();
		Centrality.compute(m.values());
		
		for (final Map.Entry<Node<T>,Vertex<Node<T>>> e : m.entrySet()) {
			cs.put(e.getKey(), e.getValue().centrality);
		}
		return cs;
	}
	
	public void colorizeByNodeBetweennessCentrality() {
		if (null == root) return;
		final HashMap<Node<T>,Vertex<Node<T>>> m = asVertices();
		Centrality.compute(m.values());
		final IndexColorModel cm = Utils.fireLUT();
		final Map<Integer,Color> colors = new HashMap<Integer,Color>();
		double max = 0;
		for (final Vertex<?> v : m.values()) max = Math.max(max, v.centrality);
		for (final Map.Entry<Node<T>, Vertex<Node<T>>> e : m.entrySet()) {
			int i = (int)(255 * (e.getValue().centrality / max) + 0.5f);
			Color c = colors.get(i);
			if (null == c) {
				c = new Color(cm.getRed(i), cm.getGreen(i), cm.getBlue(i));
				colors.put(i, c);
			}
			e.getKey().setColor(c);
		}
	}
	
	public void colorizeByBranchBetweennessCentrality(final int etching_multiplier) {
		if (null == root) return;
		final Collection<Vertex<Node<T>>> vs = asVertices().values();
		Centrality.branchWise(vs, etching_multiplier);
		final IndexColorModel cm = Utils.fireLUT();
		final Map<Integer,Color> colors = new HashMap<Integer,Color>();
		double max = 0;
		for (final Vertex<?> v : vs) max = Math.max(max, v.centrality);
		if (0 == max) {
			Utils.logAll("Branch centrality: all have zero!");
			return;
		}
		for (final Vertex<Node<T>> v : vs) {
			int i = (int)(255 * (v.centrality / max) + 0.5f);
			Utils.log("branch centrality: " + v.centrality + " , i: " + i);
			Color c = colors.get(i);
			if (null == c) {
				c = new Color(cm.getRed(i), cm.getGreen(i), cm.getBlue(i));
				colors.put(i, c);
			}
			v.data.setColor(c);
		}
	}
	
	public class Pair {
		/** Two nodes of a tree; there is a unique path that goes from a to b. */
		public Node<T> a, b;

		public Pair(Node<T> a, Node<T> b) {
			this.a = a;
			this.b = b;
		}
		
		/** The calibrated distance from a to b. */
		public double measureDistance() throws Exception {
			return new MeasurePathDistance<T>(Tree.this, a, b).getDistance();
		}

		/** The list of associated data element with each node. */
		private List<T> getData(final List<Node<T>> path) {
			final ArrayList<T> d = new ArrayList<T>();
			for (final Node<T> nd : path) {
				d.add(nd.getData());
			}
			return d;
		}

		/** The list of data elements associated with each node in the path, ordered from a to b (both included). */
		public List<T> getData() {
			return getData(Node.findPath(a, b));
		}
	}
	
	public class NodePath extends Pair {
		/** The ordered list of nodes from a to b, both included. */
		final protected List<Node<T>> path;

		public NodePath(Node<T> a, Node<T> b) {
			this(a, b, Node.findPath(a, b));
		}

		/** Assumes that a is the first element in path, and b the last,
		 * and that a has a lower degree than b (that is, a is upstream of b). */
		public NodePath(Node<T> a, Node<T> b, List<Node<T>> path) {
			super(a, b);
			this.path = path;
		}
		
		public List<Node<T>> getPath() {
			return path;
		}
	}
	
	public abstract class MeasurementPair extends NodePath
	{
		/** The calibrated path distance between nodes a and b, measured
		 * as the sum of all the distances between all consecutive pairs
		 * of nodes between a and b. */
		final public double distance;
		/** The ordered list of calibrated data elements of each node in the path
		 * of nodes between a and b, both included. */
		final public List<T> data;
		/** The ordered list of calibrated coordinates of all nodes in the path
		 * of nodes between a and b, both included. */
		final public List<Point3f> coords;
		
		public MeasurementPair(NodePath np) {
			this(np.a, np.b, np.path);
		}
		public MeasurementPair(Node<T> a, Node<T> b, List<Node<T>> path) {
			super(a, b, path);
			this.distance = new MeasurePathDistance<T>(Tree.this, a, b, path).getDistance();
			this.data = calibratedData();
			this.coords = new ArrayList<Point3f>();
			final AffineTransform aff = toCalibration();
			final float[] fp = new float[2];
			for (final Node<T> nd : path) {
				fp[0] = nd.x;
				fp[1] = nd.y;
				aff.transform(fp, 0, fp, 0, 1);
				coords.add(new Point3f(fp[0], fp[1], (float)nd.getLayer().getCalibratedZ()));
			}
		}
		abstract protected List<T> calibratedData();
		abstract public ResultsTable toResultsTable(ResultsTable rt, int index, double scale, int resample);
		abstract public MeshData createMesh(final double scale, final int resample);
		abstract public String getResultsTableTitle();
		@Override
		public double measureDistance() {
			return distance;
		}
		@Override
		public List<T> getData() {
			return data;
		}
		/** Concatenate the affine of the Tree and an affine that expresses the x,y calibration. */
		protected final AffineTransform toCalibration() {
			final AffineTransform aff = new AffineTransform(Tree.this.at);
			final Calibration cal = layer_set.getCalibration();
			aff.preConcatenate(new AffineTransform(cal.pixelWidth, 0, 0, cal.pixelHeight, 0, 0));
			return aff;
		}
	}
	
	protected abstract MeasurementPair createMeasurementPair(NodePath np);
	
	public List<NodePath> findTaggedPairs(final Tag upstream, final Tag downstream) {
		final ArrayList<NodePath> pairs = new ArrayList<NodePath>();
		if (null == root) return pairs;
		for (final Node<T> nd : root.getSubtreeNodes()) {
			if (nd.hasTag(downstream)) {
				List<Node<T>> path = new ArrayList<Node<T>>();
				path.add(nd);
				Node<T> parent = nd.getParent();
				while (!parent.hasTag(upstream)) {
					path.add(parent);
					parent = parent.getParent();
					if (null == parent) break;
				}
				if (null == parent) continue;
				path.add(parent);
				Collections.reverse(path);
				pairs.add(new NodePath(parent, nd, path));
			}
		}
		return pairs;
	}
	
	public List<MeasurementPair> measureTaggedPairs(final Tag upstream, final Tag downstream) {
		final ArrayList<MeasurementPair> pairs = new ArrayList<MeasurementPair>();
		for (final NodePath np : findTaggedPairs(upstream, downstream)) {
			pairs.add(createMeasurementPair(np));
		}
		return pairs;
	}

	/** Drop all tags from the nodes of this tree. */
	public void dropAllTags() {
		if (null == root) return;
		for (final Node<T> nd : root.getSubtreeNodes()) {
			nd.removeAllTags();
		}
	}
}
