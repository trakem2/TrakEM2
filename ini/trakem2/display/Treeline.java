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
import java.awt.Dimension;
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
import java.util.ArrayList;
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

	public Treeline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
	}

	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	public Treeline(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at, final Node root) {
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.root = root;
	}

	/** To reconstruct from XML. */
	public void parse(final StringBuilder sb) {
		Utils.log2("Treeline.parse(StringBuilder) not yet implemented.");
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

		AffineTransform gt = g.getTransform();
		AffineTransform both = new AffineTransform(this.at);
		both.preConcatenate(gt);
		g.setTransform(both);

		final Rectangle srcRect = Display.getFront().getCanvas().getSrcRect();
		Stroke stroke = null;

		synchronized (node_layer_map) {
			// Determine which layers to paint
			final Set<Node> nodes;
			if ("true".equals(project.getProperty("no_color_cues"))) {
				nodes = node_layer_map.get(active_layer);
			} else {
				nodes = new HashSet<Node>();
				for (final Set<Node> ns : node_layer_map.values()) nodes.addAll(ns);
			}
			if (null != nodes) {
				// Clear transform and stroke
				g.setTransform(DisplayCanvas.DEFAULT_AFFINE);
				stroke = g.getStroke();
				g.setStroke(DisplayCanvas.DEFAULT_STROKE);
				for (final Node nd : nodes) {
					nd.paintSlabs(g, active_layer, active, srcRect, magnification, nodes);
					nd.paintHandle(g, active, active_layer, srcRect, magnification);
				}
			}
		}

		// restore
		g.setTransform(gt);
		if (null != stroke) g.setStroke(stroke);

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
		return new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at, root.clone());
	}

	public boolean isDeletable() {
		return null == root;
	}

	/** Exports to type t2_treeline. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_treeline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_treeline (").append(Displayable.commonDTDChildren()).append(")>\n");
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
		if (null != root) {
			sb_body.append(in);
			//TODO // root.exportXML(sb_body, in);
			sb_body.append('\n');
		}
		sb_body.append(indent).append("</t2_treeline>\n");
	}

	public List generateTriangles(double scale, int parallels, int resample) {
		ArrayList list = new ArrayList();
		// TODO
		//root.generateTriangles(list, scale, parallels, resample, layer_set.getCalibrationCopy());
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
		final Node root;
		DPTreeline(final Treeline tline) {
			super(tline);
			this.root = null == tline.root ? null : tline.root.clone();
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Treeline tline = (Treeline)d;
			tline.root = null == this.root ? null : this.root.clone();
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
		// TODO
		Utils.log2("Treeline.reRoot not yet implemented");
	}

	/** Split the Treeline into new Treelines at the point closest to the x,y,layer_id world coordinate. */
	synchronized public ArrayList<Treeline> split(double x, double y, long layer_id) {
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x, y);
			x = po.x;
			y = po.y;
		}
		// TODO
		Utils.log2("Treeline.split not yet implemented");
		return null;
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

	/** Does not check for recursive cyclic graphs. */
	private class Node {
		private Node parent = null;
		private float x, y, r;
		private Layer la;
		private Node[] children = null;
		/** The confidence value of the edge towards a child;
		 *  in other words, how much this node can be trusted.
		 *  Defaults to 0x11111111 (-1, or '255' if not signed) for full trust, and 0 for none. */
		private byte[] confidence = null;

		public String toString() {
			return new StringBuilder("{:x ").append(x).append(" :y ").append(y).append(" :r ").append(r).append(" :layer ").append(la.getId()).append('}').toString();
		}

		/** @param parent The parent Node, which has an edge of a certain confidence value towards this Node.
		 *  @param x The X in local coordinates.
		 *  @param y The Y in local coordinates.
		 *  @param layer The Layer where the point represented by this Node sits.
		 *  @param r The radius, in local pixel dimensions (follows the X scaling of the Treeline affine). */
		Node(final Node parent, final float x, final float y, final Layer la, final float r) {
			this.parent = parent;
			this.x = x;
			this.y = y;
			this.la = la;
			this.r = r;
		}
		/** Returns -1 when not added (e.g. if child is null). */
		synchronized final int add(final Node child, final byte confidence) {
			if (null == child) return -1;
			if (null != children) {
				for (final Node nd : children) {
					if (nd == child) {
						Utils.log("WARNING: tried to add a node to a parent that already had it!");
						return -1;
					}
				}
			}
			enlargeArrays(1);
			this.children[children.length-1] = child;
			this.confidence[children.length-1] = confidence;
			return children.length -1;
		}
		synchronized final boolean remove(final Node child) {
			if (null == children) {
				Utils.log("WARNING: tried to remove a child from a childless node!");
				return false; // no children!
			}
			if (1 == children.length) {
				children = null;
				confidence = null;
				return true;
			}
			// find its index
			int k = -1;
			for (int i=0; i<children.length; i++) {
				if (child == children[i]) {
					k = i;
					break;
				}
			}
			if (-1 == k) return false; // not a child!
			// Else, remove:
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
		final void paintSlabs(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint) {
			// Since this method is called, this node is to be painted and by definition is inside the Set to_paint.
			if (null != children) {
				final double actZ = active_layer.getZ();
				final double thisZ = this.la.getZ();
				//Which edge color?
				Color local_edge_color = Treeline.this.color;
				if (active_layer == this.la) {} // default color
				else if (actZ > thisZ) {
					local_edge_color = Color.red;
				} else if (actZ < thisZ) local_edge_color = Color.blue;

				final float[] fps = new float[children.length + children.length];
				fps[0] = this.x;
				fps[1] = this.y;
				Treeline.this.at.transform(fps, 0, fps, 0, 1);

				// To screen coords:
				final int x = (int)((fps[0] - srcRect.x) * magnification);
				final int y = (int)((fps[1] - srcRect.y) * magnification);

				synchronized (this) {
					// Transform points
					for (int i=0, k=0; i<children.length; i++, k+=2) {
						fps[k] = children[i].x;
						fps[k+1] = children[i].y;
					}
					Treeline.this.at.transform(fps, 0, fps, 0, children.length);
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
						} else {
							// Paint full edge, but perhaps in two halfs of different colors
							if ((child.la == this.la && this.la == active_layer)
							  || (this.la.getZ() < actZ && child.la.getZ() < actZ)
							  || (this.la.getZ() > actZ && child.la.getZ() > actZ)) {
								// Full edge in local color
								g.setColor(local_edge_color);
								g.drawLine((int)x, (int)y, (int)chx, (int)chy);
							} else {
								if (thisZ < actZ && actZ < child.la.getZ()) {
									// passing by: edge crosses the current layer
									// Draw middle segment in current color
									g.setColor(local_edge_color);
									g.drawLine((int)(x + (chx - x)/4), (int)(y + (chy - y)/4),
										   (int)(x + 3*(chx - x)/4), (int)(y + 3*(chy - y)/4));
								} else if (this.la == active_layer) {
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
								} else if (child.la == active_layer) {
									// Distal half in the Displayable color
									g.setColor(Treeline.this.color);
									g.drawLine((int)(x + (chx - x)/2), (int)(y + (chy - y)/2), (int)chx, (int)chy);
									// Proximal half in either red or blue:
									g.setColor(local_edge_color);
									g.drawLine((int)x, (int)y, (int)(x + (chx - x)/2), (int)(y + (chy - y)/2));
								}
							}
						}
						if (active && (active_layer == this.la || active_layer == child.la || (thisZ < actZ && actZ > child.la.getZ()))) {
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
		final void paintHandle(final Graphics2D g, final boolean active, final Layer active_layer, final Rectangle srcRect, final double magnification) {
			Point2D.Double po = transformPoint(this.x, this.y);
			float x = (float)((po.x - srcRect.x) * magnification);
			float y = (float)((po.y - srcRect.y) * magnification);

			// paint the node as a draggable point
			if (active && active_layer == this.la) {
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
					g.drawString("e", (int)x -3, (int)y + 4); // TODO ensure Font is proper
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
					g.setColor(color.yellow);
					g.fillOval((int)x - 6, (int)y - 6, 11, 11);
					g.setColor(Color.black);
					g.drawString("Y", (int)x -3, (int)y + 4); // TODO ensure Font is proper
				}
			}
		}

		/** Returns the nodes belonging to the subtree of this node, including the node itself as the root.*/
		final Set<Node> getSubtreeNodes() {
			HashSet<Node> nodes = new HashSet<Node>();
			getSubtreeNodes(nodes);
			return nodes;
		}

		final void getSubtreeNodes(Set<Node> nodes) {
			if (!nodes.add(this)) return; // was already there
			if (null == children) return;
			synchronized (this) {
				for (final Node nd : children) {
					nd.getSubtreeNodes(nodes);
				}
			}
		}
		/** Only this node, not any of its children. */
		final void translate(final float dx, final float dy) {
			x += dx;
			y += dy;
		}
		/** Recursive copying of the subtree; smart, handles cycles.
		 * Makes this node be a root, without parent. */
		final public Node clone() {
			Node copy = new Node(null, x, y, la, r);
			cloneChildren(copy, new HashSet<Node>());
			return copy;
		}
		final private void cloneChildren(final Node parent_copy, final Set<Node> seen) {
			if (seen.contains(this)) return;
			seen.add(this);
			if (null == children) return;
			parent_copy.children = new Node[this.children.length];
			parent_copy.confidence = new byte[this.children.length];
			for (int i=0; i<this.children.length; i++) {
				final Node child = this.children[i];
				parent_copy.children[i] = new Node(parent_copy, child.x, child.y, child.la, child.r);
				parent_copy.confidence[i] = this.confidence[i];
				this.children[i].cloneChildren(parent_copy.children[i], seen);
			}
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
	}

	private final TreeMap<Layer,Set<Node>> node_layer_map = new TreeMap<Layer,Set<Node>>(new Comparator<Layer>() {
		public int compare(Layer l1, Layer l2) {
			if (l1 == l2) return 0; // the same layer
			if (l1.getZ() < l2.getZ()) return -1;
			return 1; // even if same Z, prefer the second
		}
		public boolean equals(Object ob) { return this == ob; }
	});

	private final Set<Node> end_nodes = new HashSet<Node>();

	private Node root = null;

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
			if (d > sqdist) continue;
			sqdist = d;
			nearest = nd;
		}
		return nearest;
	}

	/** Find the spatially closest node, in calibrated coords. */
	public Node findNearestEndNode(final float lx, final float ly, final Layer layer) {
		synchronized (node_layer_map) {
			return findNearestNode(lx, ly, (float)layer.getZ(), layer.getParent().getCalibration(), end_nodes);
		}
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
					Utils.log("WARNING: child was alreadu in end_nodes list!");
				}
				return true;
			}
			return false;
		}

		} finally {
			Utils.log2("new node: " + child + " with parent: " + parent);
			Utils.log2("layers with nodes: " + node_layer_map.size() + ", child.la = " + child.la + ", nodes:" + node_layer_map.get(child.la).size());
		}
	}

	/** If the tree is a cyclic graph, it may destroy all. */
	public void removeNode(final Node node) {
		// Remove from parent node
		if (null != node.parent) node.parent.remove(node);
		// if not an end-point, update cached lists
		if (null != node.children) {
			synchronized (node_layer_map) {
				for (final Node nd : node.getSubtreeNodes()) { // includes itself
					node_layer_map.get(nd.la).remove(nd);
					if (null == nd.children && !end_nodes.remove(nd)) {
						Utils.log2("WARNING: node to remove had no children but wasn't in end_nodes list!");
					}
				}
			}
		} else {
			node_layer_map.get(node.la).remove(node);
		}
	}

	private Node active = null;

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
			Utils.log2("1 found active: " + active);
			if (null != active) {
				if (me.isShiftDown() && Utils.isControlDown(me)) {
					// Remove point, and associated branches
					removeNode(active);
					repaint(false); // keep larger size for repainting, will call calculateBoundingBox on mouseRelesed
					active = null;
					Utils.log2("2 removed active: " + active);
					return;
				}
				if (me.isShiftDown()) {
					// Create new branch at point, with local coordinates
					Node node = new Node(active, x_pl, y_pl, layer, active.r);
					addNode(active, node, MAX_EDGE_CONFIDENCE);
					active = node;
					Utils.log2("3 added active: " + active);
					return;
				}
				Utils.log2("4 pressed on active: " + active);
			} else {
				// Add new point
				// Find the point closest to any other starting or ending point in all branches
				Node nearest = findNearestEndNode(x_pl, y_pl, layer);
				// append new child; inherits radius from parent
				active = new Node(nearest, x_pl, y_pl, layer, nearest.r);
				addNode(nearest, active, MAX_EDGE_CONFIDENCE);
				Utils.log2("5 added active: " + active);
				repaint(true);
				return;
			}
		} else {
			if (-1 == last_radius) {
				last_radius = (float) (10 / Display.getFront().getCanvas().getMagnification()); 
			}

			// First point
			root = active = new Node(null, x_p, y_p, layer, last_radius); // world coords, so calculateBoundingBox will do the right thing
			addNode(null, active, (byte)0);
			Utils.log2("6 first active: " + active);
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

		active = null;
	}
}
