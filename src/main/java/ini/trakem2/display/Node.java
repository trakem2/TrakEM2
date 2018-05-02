package ini.trakem2.display;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.lang.reflect.InvocationTargetException;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.scijava.vecmath.Point3f;

import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

/** Can only have one parent, so there aren't cyclic graphs. */
public abstract class Node<T> implements Taggable {
	/** Maximum possible confidence in an edge (ranges from 0 to 5, inclusive).*/
	static public final byte MAX_EDGE_CONFIDENCE = 5;

	protected Node<T> parent = null;
	public Node<T> getParent() { return parent; }

	protected float x, y;
	public float getX() { return x; }
	public float getY() { return y; }

	protected Color color;
	public Color getColor() { return this.color; }
	public void setColor(final Color c) { this.color = c; }
	public void setPosition(final float x, final float y) {
		this.x = x;
		this.y = y;
	}
	/** Expects two dimensions. */
	public void setPosition(final float[] p) {
		this.x = p[0];
		this.y = p[1];
	}

	/** The confidence value of the edge towards the parent;
	 *  in other words, how much this node can be trusted to continue from its parent node.
	 *  Defaults to MAX_EDGE_CONFIDENCE for full trust, and 0 for none. */
	protected byte confidence = MAX_EDGE_CONFIDENCE;
	public byte getConfidence() { return confidence; }

	protected Layer la;
	public Layer getLayer() { return la; }

	protected Node<T>[] children = null;
	public ArrayList<Node<T>> getChildrenNodes() {
		final ArrayList<Node<T>> a = new ArrayList<Node<T>>();
		if (null == children) return a;
		for (int i=0; i<children.length; i++) a.add(children[i]);
		return a;
	}
	/** @return a map of child node vs edge confidence to that child. */
	public Map<Node<T>,Byte> getChildren() {
		final HashMap<Node<T>,Byte> m = new HashMap<Node<T>,Byte>();
		if (null == children) return m;
		for (int i=0; i<children.length; i++) m.put(children[i], children[i].confidence);
		return m;
	}

	public List<Byte> getEdgeConfidence() {
		final ArrayList<Byte> a = new ArrayList<Byte>();
		if (null == children) return a;
		for (int i=0; i<children.length; i++) a.add(children[i].confidence);
		return a;
	}
	public byte getEdgeConfidence(final Node<T> child) {
		if (null == children) return (byte)0;
		for (int i=0; i<children.length; i++) {
			if (child == children[i]) return children[i].confidence;
		}
		return (byte)0;
	}

	@Override
    public String toString() {
		return new StringBuilder("{:x ").append(x).append(" :y ").append(y).append(" :layer ").append(la.getId()).append('}').toString();
	}

	/** 
	 *  @param x The X in local coordinates.
	 *  @param y The Y in local coordinates.
	 *  @param la The Layer where the point represented by this Node sits. */
	public Node(final float x, final float y, final Layer la) {
		this.x = x;
		this.y = y;
		this.la = la;
	}
	/** To reconstruct from XML, without a layer.
	 *  WARNING this method doesn't do any error checking. If "x" or "y" are not present in @param attr, it will simply fail with a NumberFormatException. */
	public Node(final HashMap<String,String> attr) {
		this.x = Float.parseFloat(attr.get("x"));
		this.y = Float.parseFloat(attr.get("y"));
		this.la = null;
	}
	public void setLayer(final Layer la) {
		this.la = la;
	}
	/** Returns -1 when not added (e.g. if child is null). */
	synchronized public final int add(final Node<T> child, final byte conf) {
		if (null == child) return -1;
		if (null != child.parent) {
			Utils.log("WARNING: tried to add a node that already had a parent!");
			return -1;
		}
		if (null != children) {
			for (final Node<T> nd : children) {
				if (nd == child) {
					Utils.log("WARNING: tried to add a node to a parent that already had the node as a child!");
					return -1;
				}
			}
		}
		enlargeArrays(1);
		this.children[children.length-1] = child;
		child.confidence = conf;
		child.parent = this;
		return children.length -1;
	}
	synchronized public final boolean remove(final Node<T> child) {
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
			return true;
		}

		// Else, rearrange arrays:
		final Node<T>[] ch = (Node<T>[])new Node[children.length-1];
		System.arraycopy(children, 0, ch, 0, k);
		System.arraycopy(children, k+1, ch, k, children.length - k -1);
		children = ch;
		return true;
	}
	private final void enlargeArrays(final int n_more) {
		if (null == children) {
			children = (Node<T>[])new Node[n_more];
		} else {
			final Node<T>[] ch = (Node<T>[])new Node[children.length + n_more];
			System.arraycopy(children, 0, ch, 0, children.length);
			final byte[] co = new byte[children.length + n_more];
			children = ch;
		}
	}

	/** Paint this node, and edges to parent and children varies according to whether they are included in the to_paint list.
	 *  Returns a task (or null) to paint the tags. */
	final Runnable paint(final Graphics2D g, final Layer active_layer,
			final boolean active, final Rectangle srcRect,
			final double magnification, final Collection<Node<T>> to_paint,
			final Tree<T> tree, final AffineTransform to_screen,
			final boolean with_arrows, final boolean with_tags,
			final boolean with_confidence_boxes, final boolean with_data,
			Color above, Color below) {
		// The fact that this method is called indicates that this node is to be painted and by definition is inside the Set to_paint.

		final double actZ = active_layer.getZ();
		final double thisZ = this.la.getZ();
		final Color node_color;
		if (null == this.color) {
			// this node doesn't have its color set, so use tree color and given above/below colors
			node_color = tree.color;
		} else {
			node_color = this.color;
			// Depth cue colors may not be in use:
			if (tree.color == above) above = this.color;
			if (tree.color == below) below = this.color;
		}
		// Which edge color?
		final Color local_edge_color;
		if (active_layer == this.la) {
			local_edge_color = node_color;
		} // default color
		else if (actZ > thisZ) {
			local_edge_color = below;
		} else if (actZ < thisZ) local_edge_color = above;
		else local_edge_color = node_color;

		if (with_data) paintData(g, srcRect, tree, to_screen, local_edge_color, active_layer);

		//if (null == children && !paint) return null;

		//final boolean paint = with_arrows && null != tags; // with_arrows acts as a flag for both arrows and tags
		//if (null == parent && !paint) return null;

		final float[] fps = new float[4];
		final int parent_x, parent_y;
		fps[0] = this.x;
		fps[1] = this.y;
		if (null == parent) {
			parent_x = parent_y = 0;
			tree.at.transform(fps, 0, fps, 0, 1);
		} else {
			fps[2] = parent.x;
			fps[3] = parent.y;
			tree.at.transform(fps, 0, fps, 0, 2);
			parent_x = (int)((fps[2] - srcRect.x) * magnification);
			parent_y = (int)((fps[3] - srcRect.y) * magnification);
		}

		// To screen coords:
		final int x = (int)((fps[0] - srcRect.x) * magnification);
		final int y = (int)((fps[1] - srcRect.y) * magnification);

		final Runnable tagsTask;
		if (with_tags && null != tags) {
			tagsTask = new Runnable() {
				@Override
                public void run() {
					paintTags(g, x, y, local_edge_color);
				}
			};
		} else tagsTask = null;

		//if (null == parent) return tagsTask;

		synchronized (this) {
			if (null != parent) {
				// Does the line from parent to this cross the srcRect?
				// Or what is the same, does the line from parent to this cross any of the edges of the srcRect?
				// Paint full edge, but perhaps in two halves of different colors
				if (parent.la == this.la && this.la == active_layer) {      // in treeline color
					// Full edge in local color
					g.setColor(local_edge_color);
					g.drawLine(x, y, parent_x, parent_y);
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
				} else if (this.la == active_layer) {
					// Proximal half in this color
					g.setColor(local_edge_color);
					g.drawLine(parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2, x, y);
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
					// Distal either red or blue:
					Color c = local_edge_color;
					// If other towards higher Z:
					if (actZ < parent.la.getZ()) c = above;
					// If other towards lower Z:
					else if (actZ > parent.la.getZ()) c = below;
					//
					g.setColor(c);
					g.drawLine(parent_x, parent_y, parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2);
				} else if (parent.la == active_layer) {
					// Distal half in the Displayable or Node color
					g.setColor(node_color);
					g.drawLine(parent_x, parent_y, parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2);
					// Proximal half in either red or blue:
					g.setColor(local_edge_color);
					g.drawLine(parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2, x, y);
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
				} else if (thisZ < actZ && actZ < parent.la.getZ()) {
					// proximal half in red
					g.setColor(below);
					g.drawLine(x, y, parent_x + (x - parent_x)/2, (parent_y + (y - parent_y)/2));
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
					// distal half in blue
					g.setColor(above);
					g.drawLine(parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2, parent_x, parent_y);
				} else if (thisZ > actZ && actZ > parent.la.getZ()) {
					// proximal half in blue
					g.setColor(above);
					g.drawLine(x, y, parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2);
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
					// distal half in red
					g.setColor(below);
					g.drawLine(parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2, parent_x, parent_y);
				} else if ((thisZ < actZ && parent.la.getZ() < actZ)
						|| (thisZ > actZ && parent.la.getZ() > actZ)) {
					g.setColor(local_edge_color);
					if (to_paint.contains(parent)) {
						// full edge
						g.drawLine(x, y, parent_x, parent_y);
					} else {
						// paint proximal half
						g.drawLine(x, y, parent_x + (x - parent_x)/2, parent_y + (y - parent_y)/2);
					}
					if (with_arrows) g.fill(M.createArrowhead(parent_x, parent_y, x, y, magnification));
				}
			} else if (with_arrows && !active) {
				// paint a gray handle for the root
				g.setColor(active_layer == this.la ? Color.gray : local_edge_color);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("S", (int)x -3, (int)y + 4); // TODO ensure Font is proper
			}
			if (null != children) {
				final float[] fp = new float[2];
				for (final Node<T> child : children) {
					if (to_paint.contains(child)) continue;
					fp[0] = child.x;
					fp[1] = child.y;
					tree.at.transform(fp, 0, fp, 0, 1);
					final int cx = (int)(((int)fp[0] - srcRect.x) * magnification),
					cy = (int)(((int)fp[1] - srcRect.y) * magnification);
					if (child.la == this.la){
						// child in same layer but outside the field of view
						// paint full edge to it
						g.setColor(null == child.color ? tree.color : child.color);
						g.drawLine(x, y, cx, cy);
						if (with_arrows) g.fill(M.createArrowhead(x, y, cx, cy, magnification));
					} else {
						if (child.la.getZ() < actZ) g.setColor(Color.red);
						else if (child.la.getZ() > actZ) g.setColor(Color.blue);
						// paint half edge to the child
						g.drawLine(x, y, x + (cx - x)/2, y + (cy - y)/2);
					}
				}
			}
			if (null != parent && active && with_confidence_boxes && (active_layer == this.la || active_layer == parent.la || (thisZ < actZ && actZ < parent.la.getZ()))) {
				// Draw confidence half-way through the edge
				final String s = Integer.toString(confidence);
				final Dimension dim = Utils.getDimensions(s, g.getFont());
				g.setColor(Color.white);
				final int xc = (int)(parent_x + (x - parent_x)/2),
						  yc = (int)(parent_y + (y - parent_y)/2);  // y + 0.5*chy - 0.5y = (y + chy)/2
				g.fillRect(xc, yc, dim.width+2, dim.height+2);
				g.setColor(Color.black);
				g.drawString(s, xc+1, yc+dim.height+1);
			}
		}
		return tagsTask;
	}

	static private final Color receiver_color = Color.green.brighter();

	protected void paintHandle(final Graphics2D g, final Rectangle srcRect, final double magnification, final Tree<T> t) {
		paintHandle(g, srcRect, magnification, t, false);
	}

	/** Paint in the context of offscreen space, without transformations. */
	protected void paintHandle(final Graphics2D g, final Rectangle srcRect, final double magnification, final Tree<T> t, final boolean paint_background) {
		final Point2D.Double po = t.transformPoint(this.x, this.y);
		final float x = (float)((po.x - srcRect.x) * magnification);
		final float y = (float)((po.y - srcRect.y) * magnification);

		final Color receiver = t.getLastVisited() == this ? Node.receiver_color : null;
		// paint the node as a draggable point
		if (null == parent) {
			// As origin
			g.setColor(null == receiver ? Color.magenta : receiver);
			g.fillOval((int)x - 6, (int)y - 6, 11, 11);
			g.setColor(Color.black);
			g.drawString("S", (int)x -3, (int)y + 4); // TODO ensure Font is proper
		} else if (null == children) {
			// as end point
			g.setColor(null == receiver ? Color.white : receiver);
			g.fillOval((int)x - 6, (int)y - 6, 11, 11);
			g.setColor(Color.black);
			g.drawString("e", (int)x -4, (int)y + 3); // TODO ensure Font is proper
		} else if (1 == children.length) {
			// as a slab: no branches
			if (paint_background) {
				g.setColor(Color.black);
				g.fillOval((int)x - 4, (int)y - 4, 9, 9);
			}
			g.setColor(null == receiver ? (null == this.color ? t.getColor() : this.color) : receiver);
			g.fillOval((int)x - 3, (int)y - 3, 7, 7);
		} else {
			// As branch point
			g.setColor(null == receiver ? Color.yellow : receiver);
			g.fillOval((int)x - 6, (int)y - 6, 11, 11);
			g.setColor(Color.black);
			g.drawString("Y", (int)x -4, (int)y + 4); // TODO ensure Font is proper
		}
	}

	/** Returns a lazy read-only Collection of the nodes belonging to the subtree of this node, including the node itself as the root.
	 *  Non-recursive, avoids potential stack overflow. */
	public final Collection<Node<T>> getSubtreeNodes() {
		/*
		final List<Node<T>> nodes = new ArrayList<Node<T>>();
		final LinkedList<Node<T>> todo = new LinkedList<Node<T>>();
		todo.add(this);

		while (!todo.isEmpty()) {
			// Grab one node from the todo list and add it
			final Node<T> nd = todo.removeFirst();
			nodes.add(nd);
			// Then add all its children to the todo list
			if (null != nd.children) {
				for (final Node<T> child : nd.children) todo.add(child);
			}
		}

		return nodes;
		*/

		return new NodeCollection<T>(this, BreadthFirstSubtreeIterator.class);
	}

	/** Returns a lazy read-only Collection of the nodes from this node up to the next branch node or end node, inclusive. */
	public final Collection<Node<T>> getSlabNodes() {
		return new NodeCollection<T>(this, SlabIterator.class);
	}

	/** Returns a lazy read-only Collection of all branch and end nodes under this node. */
	public final Collection<Node<T>> getBranchAndEndNodes() {
		return new NodeCollection<T>(this, BranchAndEndNodeIterator.class);
	}

	/** Returns a lazy read-only Collection of all branch nodes under this node. */
	public final Collection<Node<T>> getBranchNodes() {
		return new NodeCollection<T>(this, BranchNodeIterator.class);
	}

	/** Returns a lazy read-only Collection of all end nodes under this node. */
	public final Collection<Node<T>> getEndNodes() {
		return new NodeCollection<T>(this, EndNodeIterator.class);
	}

	/** Only this node, not any of its children. */
	final public void translate(final float dx, final float dy) {
		x += dx;
		y += dy;
	}

	/** Returns a recursive copy of this Node subtree, where the copy of this Node is the root.
	 * Non-recursive to avoid stack overflow. */
	final public Node<T> clone(final Project pr) {
		// todo list containing packets of a copied node and the lists of original children and confidence to clone into it
		final LinkedList<Object[]> todo = new LinkedList<Object[]>();
		final Node<T> root = newInstance(x, y, la);
		root.setData(this.getDataCopy());
		root.tags = getTagsCopy();
		if (null != this.children) {
			todo.add(new Object[]{root, this.children});
		}

		final HashMap<Long,Layer> ml;
		if (pr != la.getProject()) {
			// Layers must be replaced by their corresponding clones
			ml = new HashMap<Long,Layer>();
			for (final Layer layer : pr.getRootLayerSet().getLayers()) {
				ml.put(layer.getId(), layer);
			}
		} else ml = null;

		if (null != ml) root.la = ml.get(root.la.getId()); // replace Layer pointer in the copy

		while (!todo.isEmpty()) {
			final Object[] o = todo.removeFirst();
			final Node<T> copy = (Node<T>)o[0];
			final Node<T>[] original_children = (Node<T>[])o[1];
			copy.children = (Node<T>[])new Node[original_children.length];
			for (int i=0; i<original_children.length; i++) {
				final Node<T> ochild = original_children[i];
				copy.children[i] = newInstance(ochild.x, ochild.y, ochild.la);
				copy.children[i].setData(ochild.getDataCopy());
				copy.children[i].confidence = ochild.confidence;
				copy.children[i].parent = copy;
				copy.children[i].tags = ochild.getTagsCopy();
				if (null != ml) copy.children[i].la = ml.get(copy.children[i].la.getId()); // replace Layer pointer in the copy
				if (null != ochild.children) {
					todo.add(new Object[]{copy.children[i], ochild.children});
				}
			}
		}
		return root;
	}

	/** Check if this point or the edges to its children are closer to xx,yy than radius, in the 2D plane only. */
	final boolean isNear(final float xx, final float yy, final float sqradius) {
		if (  sqradius > (Math.pow(xx - x, 2) + Math.pow(yy - y, 2)) )
			return true;
							
		// if the point is not near, check the segment halves
		// check children:
		if (null != children)
			for (int i=0; i<children.length; i++) {
				if (sqradius > M.distancePointToSegmentSq(xx, yy, 0, // point
						x, y, 0,  // origin of edge
						(children[i].x + x)/2, (children[i].y + y)/2, 0)) // end of half-edge to child
				{
					return true;
				}
			}
		
		// Check to parent's half segment
		return null != parent && sqradius > M.distancePointToSegmentSq(xx, yy, 0, // point
									       x, y, 0, // origin of edge
									       (x + parent.x)/2, (y + parent.y)/2, 0); // end of half-edge to parent
	}
	public final boolean hasChildren() {
		return null != children && children.length > 0;
	}
	public final int getChildrenCount() {
		if (null == children) return 0;
		return children.length;
	}
	/** Traverse the tree from this node all the way to the root node,
	 *  and count how many nodes apart this node is from the root node:
	 *  that is the degree.
	 *  Thanks to Johannes Schindelin.
	 */
	public final int computeDegree() {
	    int result = 0;
	    for (Node<T> node = this; node != null; node = node.parent)
	        result++;
	    return result;
	}
	/** Obtain the (only) list from node a to node b,
	 *  including both a (the first element) and b (the last element).
	 *  Thanks to Johannes Schindelin.
	 */
	public static <I> List<Node<I>> findPath(Node<I> a, Node<I> b) {
	    int degreeA = a.computeDegree(),
	    	degreeB = b.computeDegree();
	    final List<Node<I>> listA = new ArrayList<Node<I>>(),
	    					listB = new ArrayList<Node<I>>();
	    // Traverse upstream the parent chain until finding nodes of the same degree
	    for (; degreeB > degreeA; degreeB--, b = b.parent)
	        listB.add(b);
	    for (; degreeA > degreeB; degreeA--, a = a.parent)
	        listA.add(a);
	    // Traverse upstream the parent chain until finding a common parent node
	    for (; a != b; a = a.parent, b = b.parent) {
	        listA.add(a);
	        listB.add(b);
	    }
	    // Add that common parent node
	    listA.add(a);
	    // add all in reverse
	    for (final ListIterator<Node<I>> it = listB.listIterator(listB.size()); it.hasPrevious(); ) {
	    	listA.add(it.previous());
	    }
	    return listA;
	}

	/** Return a map of node vs degree of that node, for the entire subtree (including this node). */
	public HashMap<Node<T>,Integer> computeAllDegrees() {
		final HashMap<Node<T>,Integer> degrees = new HashMap<Node<T>, Integer>();
		int degree = 1;
		ArrayList<Node<T>> next_level = new ArrayList<Node<T>>();
		ArrayList<Node<T>> current_level = new ArrayList<Node<T>>();
		current_level.add(this);
		do {
			for (final Node<T> nd : current_level) {
				degrees.put(nd, degree);
				if (null != nd.children) {
					for (final Node<T> child : nd.children) {
						next_level.add(child);
					}
				}
			}
			// rotate lists:
			current_level.clear();
			final ArrayList<Node<T>> tmp = current_level;
			current_level = next_level;
			next_level = tmp;
			degree++;
		} while (!current_level.isEmpty());

		return degrees;
	}

	/** Assumes this is NOT a graph with cycles. Non-recursive to avoid stack overflows. */
	final void setRoot() {
		// Works, but can be done in one pass TODO
		//
		// Find first the list of nodes from this node to the current root
		// and then proceed in reverse direction!

		final LinkedList<Node<T>> path = new LinkedList<Node<T>>();
		path.add(this);
		Node<T> parent = this.parent;
		while (null != parent) {
			path.addFirst(parent);
			parent = parent.parent;
		}
		Node<T> newchild = path.removeFirst();
		for (final Node<T> nd : path) {
			// Made nd the parent of newchild (was the opposite)
			// 1 - Find out the confidence of the edge to the child node:
			byte conf = MAX_EDGE_CONFIDENCE;
			for (int i=0; i<newchild.children.length; i++) {
				if (nd == newchild.children[i]) {
					conf = newchild.children[i].confidence;
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
					conf = parent.children[i].confidence;
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
	/** Set the confidence value of this node with its parent. */
	synchronized public final boolean setConfidence(final byte conf) {
		if (conf < 0 || conf > MAX_EDGE_CONFIDENCE) return false;
		confidence = conf;
		return true;
	}
	/** Adjust the confidence value of this node with its parent. */
	final public boolean adjustConfidence(final int inc) {
		final byte conf = (byte)((confidence&0xff) + inc);
		if (conf < 0 || conf > MAX_EDGE_CONFIDENCE) return false;
		confidence = conf;
		return true;
	}
	/** Returns -1 if not a child of this node. */
	final byte getConfidence(final Node<T> child) {
		if (null == children) return -1;
		for (int i=0; i<children.length; i++) {
			if (child == children[i]) return children[i].confidence;
		}
		return -1;
	}
	final int indexOf(final Node<T> child) {
		if (null == children) return -1;
		for (int i=0; i<children.length; i++) {
			if (child == children[i]) return i;
		}
		return -1;
	}
	synchronized public final Node<T> findPreviousBranchOrRootPoint() {
		if (null == this.parent) return null;
		Node<T> parent = this.parent;
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
	synchronized public final Node<T> findNextBranchOrEndPoint() {
		Node<T> child = this;
		while (true) {
			if (null == child.children || child.children.length > 1) return child;
			child = child.children[0];
		}
	}

	public abstract boolean setData(T t);

	public abstract T getData();

	public abstract T getDataCopy();

	public abstract Node<T> newInstance(float x, float y, Layer layer);

	abstract public void paintData(final Graphics2D g, final Rectangle srcRect,
			final Tree<T> tree, final AffineTransform to_screen, final Color cc,
			final Layer active_layer);

	/** Expects Area in local coords. */
	public abstract boolean intersects(Area a);

	/** May return a false positive but never a false negative.
	 *  Checks only for itself and towards its parent. */
	public boolean isRoughlyInside(final Rectangle localbox) {
		if (null == parent) {
			return localbox.contains((int)this.x, (int)this.y);
		} else {
			return localbox.intersectsLine(parent.x, parent.y, this.x, this.y);
		}
	}

	/** Returns area in local coords. */
	public Area getArea() {
		return new Area(new Rectangle2D.Float(x, y, 1, 1)); // a "little square" -- sinful! xDDD
	}

	/** Returns a list of Patch to link, which lay under the node. Use the given @param aff to transform the Node data before looking up targets. */
	public Collection<Displayable> findLinkTargets(final AffineTransform to_world) {
		float x = this.x,
		      y = this.y;
		if (null != to_world && !to_world.isIdentity()) {
			final float[] fp = new float[]{x, y};
			to_world.transform(fp, 0, fp, 0, 1);
			x = fp[0];
			y = fp[1];
		}
		return this.la.find(Patch.class, (int)x, (int)y, true);
	}

	/** The tags:
	 *  null: none
	 *  a Tag instance: just one tag
	 *  a Tag[] instance: more than one tag, sorted.
	 *
	 *  The justification for this seemingly silly storage system is to take the minimal space possible,
	 *  while preserving the sortedness of tags.
	 *  As expected, the huge storage savings (used to be a TreeSet&lt;Tag&gt;, which has inside a TreeMap, which has a HashMap inside, and so on),
	 *  result in heavy computations required to add or remove a Tag, but these operations are rare and thus acceptable.
	 */
	Object tags = null; // private to the package

	/** @return true if the tag wasn't there already. */
	@Override
    synchronized public boolean addTag(final Tag tag) {
		if (null == this.tags) {
			// Currently no tags
			this.tags = tag;
			return true;
		}
		// If not null, there is already at least one tag
		final Tag[] t2;
		if (tags instanceof Tag[]) {
			// Currently more than one tag
			final Tag[] t1 = (Tag[])tags;
			for (final Tag t : t1) {
				if (t.equals(tag)) return false;
			}
			t2 = new Tag[t1.length + 1];
			System.arraycopy(t1, 0, t2, 0, t1.length);
			// Add tag as last
			t2[t2.length -1] = tag;
		} else {
			// Currently only one tag
			if (tag.equals(this.tags)) return false;
			t2 = new Tag[]{(Tag)this.tags, tag};
		}
		// Sort tags
		final ArrayList<Tag> al = new ArrayList<Tag>(t2.length);
		for (final Tag t : t2) al.add(t);
		Collections.sort(al);
		this.tags = al.toArray(t2); // reuse t2 array, has the right size
		return true;
	}

	/** @return true if the tag was there. */
	@Override
    synchronized public boolean removeTag(final Tag tag) {
		if (null == tags) return false; // no tags
		if (tags instanceof Tag[]) {
			// Currently more than one tag
			final Tag[] t1 = (Tag[])this.tags;
			for (int i=0; i<t1.length; i++) {
				if (t1[i].equals(tag)) {
					// remove:
					if (2 == t1.length) {
						this.tags = 0 == i ? t1[1] : t1[0];
					} else {
						final Tag[] t2 = new Tag[t1.length -1];
						if (0 == i) {
							System.arraycopy(t1, 1, t2, 0, t2.length);
						} else if (t1.length -1 == i) {
							System.arraycopy(t1, 0, t2, 0, t2.length);
						} else {
							System.arraycopy(t1, 0, t2, 0, i);
							System.arraycopy(t1, i+1, t2, i, t2.length - i);
						}
						this.tags = t2;
					}
					return true;
				}
			}
			return false;
		} else {
			// Currently just one tag
			if (this.tags.equals(tag)) {
				this.tags = null;
			}
			return false;
		}
	}

	protected final void copyProperties(final Node<?> nd) {
		this.confidence = nd.confidence;
		this.tags = nd.getTagsCopy();
	}

	synchronized private final Object getTagsCopy() {
		if (null == this.tags) return null;
		if (this.tags instanceof Tag) return this.tags;
		final Tag[] t1 = (Tag[])this.tags;
		final Tag[] t2 = new Tag[t1.length];
		System.arraycopy(t1, 0, t2, 0, t1.length);
		return t2;
	}

	synchronized public boolean hasTag(final Tag t) {
		if (null == this.tags) return false;
		return getTags().contains(t);
	}

	/** @return a shallow copy of the tags set, if any, or null. */
	@Override
    synchronized public Set<Tag> getTags() {
		if (null == tags) return null;
		final TreeSet<Tag> ts = new TreeSet<Tag>();
		if (tags instanceof Tag[]) {
			for (final Tag t : (Tag[])this.tags) ts.add(t);
		} else {
			ts.add((Tag)this.tags);
		}
		return ts;
	}

	/** @return the tags, if any, or null. */
	@Override
    synchronized public Set<Tag> removeAllTags() {
		final Set<Tag> tags = getTags();
		this.tags = null;
		return tags;
	}

	private void paintTags(final Graphics2D g, final int x, final int y, Color background_color) {
		final int ox = x + 20;
		int oy = y + 20;

		Color fontcolor = Color.blue;
		if (Color.red == background_color || Color.blue == background_color) fontcolor = Color.white;
		else background_color = Taggable.TAG_BACKGROUND;
		// so the Color indicated in the parameter background_color is used only as a flag

		final Stroke stroke = g.getStroke();
		g.setStroke(Taggable.DASHED_STROKE);
		g.setColor(background_color);
		g.drawLine(x, y, ox, oy);
		g.setStroke(stroke);

		final Tag[] tags = this.tags instanceof Tag[] ? (Tag[])this.tags : new Tag[]{(Tag)this.tags};

		for (final Tag ob : tags) {
			final String tag = ob.toString();
			final Dimension dim = Utils.getDimensions(tag, g.getFont());
			final int arc = (int)(dim.height / 3.0f);
			final RoundRectangle2D rr = new RoundRectangle2D.Float(ox, oy, dim.width+4, dim.height+2, arc, arc);
			g.setColor(background_color);
			g.fill(rr);
			g.setColor(fontcolor);
			g.drawString(tag, ox +2, oy +dim.height -1);
			oy += dim.height + 3;
		}
	}

	public void apply(final mpicbg.models.CoordinateTransform ct, final Area roi) {
		final double[] fp = new double[]{x, y};
		ct.applyInPlace(fp);
		this.x = (float)fp[0];
		this.y = (float)fp[1];
	}
	public void apply(final VectorDataTransform vlocal) {
		for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
			// Apply only the first one that contains the point
			if (rt.roi.contains(x, y)) {
				final double[] fp = new double[]{x, y};
				rt.ct.applyInPlace(fp);
				x = (float)fp[0];
				y = (float)fp[1];
				break;
			}
		}
	}
	public Point3f asPoint() {
		return new Point3f(x, y, (float)la.getZ());
	}

	/** Apply @param aff to the data, not to the x,y position. */
	protected void transformData(final AffineTransform aff) {}



	// ==================== Node Iterators

	/** Stateful abstract Node iterator. */
	static public abstract class NodeIterator<I> implements Iterator<Node<I>> {
		Node<I> next;
		public NodeIterator(final Node<I> first) {
			this.next = first;
		}
		@Override
		public boolean hasNext() { return null != next; }
		@Override
		public void remove() {}
	}

	static public abstract class SubtreeIterator<I> extends NodeIterator<I> {
		final LinkedList<Node<I>> todo = new LinkedList<Node<I>>();
		public SubtreeIterator(final Node<I> first) {
			super(first);
			todo.add(first);
		}
		@Override
		public boolean hasNext() { return todo.size() > 0; }
	}

	/** For a given starting node, iterates over the complete set of children nodes, recursively and breadth-first. */
	static public class BreadthFirstSubtreeIterator<I> extends SubtreeIterator<I> {
		public BreadthFirstSubtreeIterator(final Node<I> first) {
			super(first);
		}
		@Override
		public Node<I> next() {
			if (todo.isEmpty()) {
				next = null;
				return null;
			}
			next = todo.removeFirst();
			if (null != next.children) {
				for (int i=0; i<next.children.length; i++) todo.add(next.children[i]);
			}
			return next;
		}
	}

	static public abstract class FilteredIterator<I> extends SubtreeIterator<I> {
		public FilteredIterator(final Node<I> first) {
			super(first);
			prepareNext();
		}
		public abstract boolean accept(final Node<I> node);

		private final void prepareNext() {
			while (todo.size() > 0) {
				final Node<I> nd = todo.removeFirst();
				if (null != nd.children) {
					for (int i=0; i<nd.children.length; i++) todo.add(nd.children[i]);
				}
				if (accept(nd)) {
					next = nd;
					return;
				}
			}
			next = null;
		}

		@Override
		public boolean hasNext() { return null != next; }

		@Override
		public Node<I> next() {
			try {
				return next;
			} finally {
				prepareNext();
			}
			//final Node<I> node = next;
			//prepareNext();
			//return node;
		}
	}

	static public class BranchAndEndNodeIterator<I> extends FilteredIterator<I> {
		public BranchAndEndNodeIterator(final Node <I> first) {
			super(first);
		}
		@Override
		public boolean accept(final Node<I> node) {
			return 1 != node.getChildrenCount();
		}
	}
	static public class BranchNodeIterator<I> extends FilteredIterator<I> {
		public BranchNodeIterator(final Node <I> first) {
			super(first);
		}
		@Override
		public boolean accept(final Node<I> node) {
			return node.getChildrenCount() > 1;
		}
	}
	static public class EndNodeIterator<I> extends FilteredIterator<I> {
		public EndNodeIterator(final Node <I> first) {
			super(first);
		}
		@Override
		public boolean accept(final Node<I> node) {
			return 0 == node.getChildrenCount();
		}
	}

	/** For a given starting node, iterates all the way to the next end node or branch node, inclusive. */
	static public class SlabIterator<I> extends SubtreeIterator<I> {
		public SlabIterator(final Node<I> first) {
			super(first);
		}
		@Override
		public Node<I> next() {
			if (todo.isEmpty()) {
				next = null;
				return null; // reached an end node or branch node
			}
			final Node<I> next = todo.removeFirst();
			if (null == next.children || next.children.length > 1) {
				this.next = null;
				return next; // reached an end node or branch node
			}
			todo.add(next.children[0]);
			this.next = next;
			return next;
		}
	}

	/** Read-only Collection with a very expensive size().
	 *  It is meant for traversing Node subtrees.  */
	static public class NodeCollection<I> extends AbstractCollection<Node<I>> {
		final Node<I> first;
		final Class<?> type;

		public NodeCollection(final Node<I> first, final Class<?> type) {
			this.first = first;
			this.type = type;
		}
		@Override
		public Iterator<Node<I>> iterator() {
			try {
				return (Iterator<Node<I>>) type.getConstructor(Node.class).newInstance(first);
			} catch (final NoSuchMethodException nsme) { IJError.print(nsme); }
			  catch (final InvocationTargetException ite) { IJError.print(ite); }
			  catch (final IllegalAccessException iae) { IJError.print(iae); }
			  catch (final InstantiationException ie) { IJError.print(ie); }
			return null;
		}
		/** Override to avoid calling size(). */
		@Override
		public boolean isEmpty() {
			return null == first;
		}
		/** WARNING: O(n) operation: will traverse the whole collection. */
		@Override
		public int size() {
			int count = 0;
			final Iterator<Node<I>> it = iterator();
			while (it.hasNext()) {
				count++;
				it.next();
			}
			return count;
		}
		/** Override to avoid calling size(), which would iterate the whole list just for that. */
		@Override
		public Node<I>[] toArray() {
			Node<I>[] a = (Node<I>[])new Node[10];
			int next = 0;
			for (final Iterator<Node<I>> it = iterator(); it.hasNext(); ) {
				if (a.length == next) {
					final Node[] b = new Node[a.length + 10];
					System.arraycopy(a, 0, b, 0, a.length);
					a = b;
				}
				a[next++] = it.next();
			}
			return next < a.length ? Arrays.copyOf(a, next) : a;
		}
		@Override
		public<Y> Y[] toArray(final Y[] a) {
			final Node<I>[] b = toArray();
			if (a.length < b.length) {
				return (Y[])b;
			}
			System.arraycopy(b, 0, a, 0, b.length);
			if (a.length > b.length) a[b.length] = null; // null-terminate
			return a;
		}
	}

	// ============= Operations on collections of nodes

	/** An operation to be applied to a specific Node. */
	static public interface Operation<I> {
		public void apply(final Node<I> nd) throws Exception;
	}

	protected final void apply(final Operation<T> op, final Iterator<Node<T>> nodes) throws Exception {
		while (nodes.hasNext()) op.apply(nodes.next());
	}

	/** Apply @param op to this Node and all its subtree nodes. */
	public void applyToSubtree(final Operation<T> op) throws Exception {
		apply(op, new BreadthFirstSubtreeIterator<T>(this));
		/*
		final Node<?> first = this;
		apply(op, new Iterable<Node<?>>() {
			public Iterator<Node<?>> iterator() {
				return new NodeIterator(first) {
					public final boolean hasNext() {
						if (null == next.children) return false;
						else if (1 == next.children.length) {
							next = next.children[0];
							return true;
						}
						return false;
					}
				};
			}
		});
		*/
	}

	/** Apply @param op to this Node and all its subtree nodes until reaching a branch node or end node, inclusive. */
	public void applyToSlab(final Operation<T> op) throws Exception {
		apply(op, new SlabIterator<T>(this));
	}

	public boolean hasSameTags(final Node<?> other) {
		if (null == this.tags && null == other.tags) return true;
		final Set<Tag> t1 = getTags(),
					   t2 = other.getTags();
		if (null == t1 || null == t2) return false; // at least one is not null
		t1.removeAll(t2);
		return t1.isEmpty();
	}
}
