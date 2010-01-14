package ini.trakem2.display;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Collection;
import java.util.Collections;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.Rectangle;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Point2D;

import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

/** Can only have one parent, so there aren't cyclic graphs. */
public abstract class Node<T> implements Taggable {
	/** Maximum possible confidence in an edge (ranges from 0 to 5, inclusive).*/
	static public final byte MAX_EDGE_CONFIDENCE = 5;

	protected Node parent = null;
	public Node getParent() { return parent; }

	protected float x, y;
	public float getX() { return x; }
	public float getY() { return y; }

	protected Layer la;
	public Layer getLayer() { return la; }

	protected Node[] children = null;
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
	protected byte[] confidence = null;
	public List<Byte> getEdgeConfidence() {
		final ArrayList<Byte> a = new ArrayList<Byte>();
		if (null == children) return a;
		for (int i=0; i<children.length; i++) a.add(confidence[i]);
		return a;
	}

	public String toString() {
		return new StringBuilder("{:x ").append(x).append(" :y ").append(y).append(" :layer ").append(la.getId()).append('}').toString();
	}

	/** @param parent The parent Node, which has an edge of a certain confidence value towards this Node.
	 *  @param x The X in local coordinates.
	 *  @param y The Y in local coordinates.
	 *  @param layer The Layer where the point represented by this Node sits. */
	public Node(final float x, final float y, final Layer la) {
		this.x = x;
		this.y = y;
		this.la = la;
	}
	/** To reconstruct from XML, without a layer. */
	public Node(final HashMap attr) {
		this.x = Float.parseFloat((String)attr.get("x"));
		this.y = Float.parseFloat((String)attr.get("y"));
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
	final void paintSlabs(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint, final AffineTransform aff, final Color t_color, final boolean with_arrows, final boolean with_confidence_boxes) {
		// Since this method is called, this node is to be painted and by definition is inside the Set to_paint.
		if (null != children) {
			final double actZ = active_layer.getZ();
			final double thisZ = this.la.getZ();
			//Which edge color?
			Color local_edge_color = t_color;
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
								g.setColor(t_color);
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
	final void paintHandle(final Graphics2D g, final Rectangle srcRect, final double magnification, final Tree t) {
		Point2D.Double po = t.transformPoint(this.x, this.y);
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
	public final List<Node> getSubtreeNodes() {
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
		final Node root = newInstance(x, y, la);
		root.setData(this.getDataCopy());
		root.tags = null == this.tags ? null : new TreeSet<Tag>(this.tags);
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
				copy.children[i] = newInstance(ochild.x, ochild.y, ochild.la);
				copy.children[i].setData(ochild.getDataCopy());
				copy.children[i].parent = copy;
				copy.children[i].tags = null == ochild.tags ? null : new TreeSet<Tag>(ochild.tags);
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

	public abstract boolean setData(T t);

	public abstract T getData();

	public abstract T getDataCopy();

	public abstract Node newInstance(float x, float y, Layer layer);

	abstract public void paintData(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint, final Tree tree);

	/** Expects Area in local coords. */
	public abstract boolean intersects(Area a);

	/** Returns a list of Patch to link, which lay under the node. Use the given @param aff to transform the Node data before looking up targets. */
	public Collection<Displayable> findLinkTargets(final AffineTransform aff) {
		float x = this.x,
		      y = this.y;
		if (!aff.isIdentity()) {
			float[] fp = new float[]{x, y};
			aff.transform(fp, 0, fp, 0, 1); // aff is already an inverted affine
			x = fp[0];
			y = fp[1];
		}
		return this.la.find(Patch.class, (int)x, (int)y, true);
	}

	TreeSet<Tag> tags = null; // private to the package

	/** @return true if the tag wasn't there already. */
	synchronized public boolean addTag(Tag tag) {
		if (null == tags) tags = new TreeSet<Tag>();
		return tags.add(tag);
	}

	/** @return true if the tag was there. */
	synchronized public boolean removeTag(Tag tag) {
		if (null == tags) return false;
		boolean b = tags.remove(tag);
		if (tags.isEmpty()) tags = null;
		return b;
	}

	/** @return a shallow copy of the tags set, if any, or null. */
	synchronized public Set<Tag> getTags() {
		if (null == tags) return null;
		return (Set<Tag>) tags.clone();
	}

	/** @return the tags, if any, or null. */
	synchronized public Set<Tag> removeAllTags() {
		Set<Tag> tags = this.tags;
		this.tags = null;
		return tags;
	}

	public void paintTags(final Graphics2D g, final Rectangle srcRect, final double magnification, final AffineTransform aff) {
		if (null == this.tags) return;
		Set<Tag> tags;
		synchronized (this) {
			tags = new TreeSet<Tag>(this.tags);
		}
		final float[] fp = new float[]{x, y};
		aff.transform(fp, 0, fp, 0, 1);
		final int x = (int)((fp[0] - srcRect.x) * magnification);
		final int y = (int)((fp[1] - srcRect.y) * magnification);

		final int ox = x + 20;
		int oy = y + 20;

		Stroke stroke = g.getStroke();
		g.setStroke(Taggable.DASHED_STROKE);
		g.setColor(Taggable.TAG_BACKGROUND);
		g.drawLine(x, y, ox, oy);
		g.setStroke(stroke);

		for (final Tag ob : tags) {
			String tag = ob.toString();
			Dimension dim = Utils.getDimensions(tag, g.getFont());
			final int arc = (int)(dim.height / 3.0f);
			RoundRectangle2D rr = new RoundRectangle2D.Float(ox, oy, dim.width+4, dim.height+2, arc, arc);
			g.setColor(Taggable.TAG_BACKGROUND);
			g.fill(rr);
			g.setColor(Color.blue);
			g.drawString(tag, ox +2, oy +dim.height -1);
			oy += dim.height + 3;
		}
	}
}


