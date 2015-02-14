package ini.trakem2.display;

import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.AlphaComposite;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.TextField;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.media.j3d.Transform3D;
import javax.vecmath.AxisAngle4f;
import javax.vecmath.Color3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

public class Treeline extends Tree<Float> {

	static protected float last_radius = -1;

	public Treeline(final Project project, final String title) {
		super(project, title);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public Treeline(final Project project, final long id, final HashMap<String,String> ht_attr, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase() */
	public Treeline(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Tree<Float> newInstance() {
		return new Treeline(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Node<Float> newNode(final float lx, final float ly, final Layer la, final Node<?> modelNode) {
		return new RadiusNode(lx, ly, la, null == modelNode ? 0 : ((RadiusNode)modelNode).r);
	}

	@Override
	public Node<Float> newNode(final HashMap<String,String> ht_attr) {
		return new RadiusNode(ht_attr);
	}

	@Override
	public Treeline clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Treeline tline =  new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at);
		tline.root = null == this.root ? null : this.root.clone(pr);
		tline.addToDatabase();
		if (null != tline.root) tline.cacheSubtree(tline.root.getSubtreeNodes());
		return tline;
	}

	@Override
	public void mousePressed(final MouseEvent me, final Layer la, final int x_p, final int y_p, final double mag) {
		if (-1 == last_radius) {
			last_radius = 10 / (float)mag;
		}

		if (me.isShiftDown() && me.isAltDown() && !Utils.isControlDown(me)) {
			final Display front = Display.getFront(this.project);
			final Layer layer = front.getLayer();
			final Node<Float> nd = findNodeNear(x_p, y_p, layer, front.getCanvas());
			if (null == nd) {
				Utils.log("Can't adjust radius: found more than 1 node within visible area!");
				return;
			}
			// So: only one node within visible area of the canvas:
			// Adjust the radius by shift+alt+drag

			float xp = x_p,
			      yp = y_p;
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x_p, y_p);
				xp = (int)po.x;
				yp = (int)po.y;
			}

			setActive(nd);
			nd.setData((float)Math.sqrt(Math.pow(xp - nd.x, 2) + Math.pow(yp - nd.y, 2)));
			repaint(true, la);
			setLastEdited(nd);

			return;
		}

		super.mousePressed(me, la, x_p, y_p, mag);
	}

	protected boolean requireAltDownToEditRadius() {
		return true;
	}

	@Override
	public void mouseDragged(final MouseEvent me, final Layer la, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {
		if (null == getActive()) return;

		if (requireAltDownToEditRadius() && !me.isAltDown()) {
			super.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
			return;
		}
		if (me.isShiftDown() && !Utils.isControlDown(me)) {
			// transform to the local coordinates
			float xd = x_d,
			      yd = y_d;
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x_d, y_d);
				xd = (float)po.x;
				yd = (float)po.y;
			}
			final Node<Float> nd = getActive();
			final float r = (float)Math.sqrt(Math.pow(xd - nd.x, 2) + Math.pow(yd - nd.y, 2));
			nd.setData(r);
			last_radius = r;
			repaint(true, la);
			return;
		}

		super.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
	}

	@Override
	public void mouseReleased(final MouseEvent me, final Layer la, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		if (null == getActive()) return;

		if (me.isShiftDown() && me.isAltDown() && !Utils.isControlDown(me)) {
			updateViewData(getActive());
			return;
		}
		super.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
	}

	@Override
	public void mouseWheelMoved(final MouseWheelEvent mwe) {
		final int modifiers = mwe.getModifiers();
		if (0 == ( (MouseWheelEvent.SHIFT_MASK | MouseWheelEvent.ALT_MASK) ^ modifiers)) {
			final Object source = mwe.getSource();
			if (! (source instanceof DisplayCanvas)) return;
			final DisplayCanvas dc = (DisplayCanvas)source;
			final Layer la = dc.getDisplay().getLayer();
			final int rotation = mwe.getWheelRotation();
			final float magnification = (float)dc.getMagnification();
			final Rectangle srcRect = dc.getSrcRect();
			final float x = ((mwe.getX() / magnification) + srcRect.x);
			final float y = ((mwe.getY() / magnification) + srcRect.y);

			final float inc = (rotation > 0 ? 1 : -1) * (1/magnification);
			if (null != adjustNodeRadius(inc, x, y, la, dc)) {
				Display.repaint(this);
				mwe.consume();
				return;
			}
		}
		super.mouseWheelMoved(mwe);
	}

	protected Node<Float> adjustNodeRadius(final float inc, final float x, final float y, final Layer layer, final DisplayCanvas dc) {
		final Node<Float> nearest = findNodeNear(x, y, layer, dc);
		if (null == nearest) {
			Utils.log("Can't adjust radius: found more than 1 node within visible area!");
			return null;
		}
		nearest.setData(nearest.getData() + inc);
		return nearest;
	}

	static public class RadiusNode extends Node<Float> {
		protected float r;

		public RadiusNode(final float lx, final float ly, final Layer la) {
			this(lx, ly, la, 0);
		}
		public RadiusNode(final float lx, final float ly, final Layer la, final float radius) {
			super(lx, ly, la);
			this.r = radius;
		}
		/** To reconstruct from XML, without a layer. */
		public RadiusNode(final HashMap<String,String> attr) {
			super(attr);
			final String sr = (String)attr.get("r");
			this.r = null == sr ? 0 : Float.parseFloat(sr);
		}

		@Override
        public Node<Float> newInstance(final float lx, final float ly, final Layer layer) {
			return new RadiusNode(lx, ly, layer, 0);
		}

		/** Set the radius to a positive value. When zero or negative, it's set to zero. */
		@Override
        public final boolean setData(final Float radius) {
			this.r = radius > 0 ? radius : 0;
			return true;
		}
		@Override
        public final Float getData() { return this.r; }

		@Override
        public final Float getDataCopy() { return this.r; }

		@Override
		public boolean isRoughlyInside(final Rectangle localbox) {
			if (0 == this.r) {
				if (null == parent) {
					return localbox.contains((int)this.x, (int)this.y);
				} else {
					if (0 == parent.getData()) { // parent.getData() == ((RadiusNode)parent).r
						return localbox.intersectsLine(parent.x, parent.y, this.x, this.y);
					} else {
						return segmentIntersects(localbox);
					}
				}
			} else {
				if (null == parent) {
					return localbox.contains((int)this.x, (int)this.y);
				} else {
					return segmentIntersects(localbox);
				}
			}
		}

		private final Polygon getSegment() {
			final RadiusNode parent = (RadiusNode) this.parent;
			float vx = parent.x - this.x;
			float vy = parent.y - this.y;
			final float len = (float) Math.sqrt(vx*vx + vy*vy);
			if (0 == len) {
				// Points are on top of each other
				return new Polygon(new int[]{(int)this.x, (int)Math.ceil(parent.x)},
								   new int[]{(int)this.y, (int)Math.ceil(parent.y)}, 2);
			}
			vx /= len;
			vy /= len;
			// perpendicular vector
			final float vx90 = -vy;
			final float vy90 = vx;
			final float vx270 = vy;
			final float vy270 = -vx;

			return new Polygon(new int[]{(int)(parent.x + vx90 * parent.r), (int)(parent.x + vx270 * parent.r), (int)(this.x + vx270 * this.r), (int)(this.x + vx90 * this.r)},
					   new int[]{(int)(parent.y + vy90 * parent.r), (int)(parent.y + vy270 * parent.r), (int)(this.y + vy270 * this.r), (int)(this.y + vy90 * this.r)},
					   4);
		}

		// The human compiler at work!
		/** Detect intersection between localRect and the bounds of getSegment() */
		private final boolean segmentIntersects(final Rectangle localRect) {
			final RadiusNode parent = (RadiusNode) this.parent;
			float vx = parent.x - this.x;
			float vy = parent.y - this.y;
			final float len = (float) Math.sqrt(vx*vx + vy*vy);
			if (0 == len) {
				// Points are on top of each other
				return localRect.contains(this.x, this.y);
			}
			vx /= len;
			vy /= len;
			// perpendicular vector
			//final float vx90 = -vy;
			//final float vy90 = vx;
			//final float vx270 = vy;
			//final float vy270 = -vx;

			final float x1 = parent.x + (-vy) /*vx90*/ * parent.r,
						y1 = parent.y + vx    /*vy90*/ * parent.r,
						x2 = parent.x + vy    /*vx270*/ * parent.r,
						y2 = parent.y + (-vx) /*vy270*/ * parent.r,
						x3 = this.x + vy    /*vx270*/ * this.r,
						y3 = this.y + (-vx) /*vy270*/ * this.r,
						x4 = this.x + (-vy) /*vx90*/ * this.r,
						y4 = this.y + vx    /*vy90*/ * this.r;
			final float min_x = Math.min(Math.min(x1, x2), Math.min(x3, x4)),
						min_y = Math.min(Math.min(y1, y2), Math.min(y3, y4)),
						max_x = Math.max(Math.max(x1, x2), Math.max(x3, x4)),
						max_y = Math.max(Math.max(y1, y2), Math.max(y3, y4));
			/*
			final float w = max_x - min_x,
						h = max_y - min_y;

			return min_x + w > localRect.x
			    && min_y + h > localRect.y
			    && min_x < localRect.x + localRect.width
			    && min_y < localRect.y + localRect.height;
			*/

			// As above, but inline:
			return min_x + max_x - min_x > localRect.x
				&& min_y + max_y - min_y > localRect.y
				&& min_x < localRect.x + localRect.width
				&& min_y < localRect.y + localRect.height;

			// May give false negatives!
			//return localRect.contains((int)(parent.x + vx90 * parent.r), (int)(parent.y + vy90 * parent.r))
			//	|| localRect.contains((int)(parent.x + vx270 * parent.r), (int)(parent.y + vy270 * parent.r))
			//	|| localRect.contains((int)(this.x + vx270 * this.r), (int)(this.y + vy270 * this.r))
			//	|| localRect.contains((int)(this.x + vx90 * this.r), (int)(this.y + vy90 * this.r));
		}

		@Override
		public void paintData(final Graphics2D g, final Rectangle srcRect,
				final Tree<Float> tree, final AffineTransform to_screen, final Color cc,
				final Layer active_layer) {
			if (null == this.parent) return; // doing it here for less total cost
			if (0 == this.r && 0 == parent.getData()) return;

			// Two transformations, but it's only 4 points each and it's necessary
			//final Polygon segment = getSegment();
			//if (!tree.at.createTransformedShape(segment).intersects(srcRect)) return Node.FALSE;
			//final Shape shape = to_screen.createTransformedShape(segment);
			final Shape shape = to_screen.createTransformedShape(getSegment());
			final Composite c = g.getComposite();
			final float alpha = tree.getAlpha();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha > 0.4f ? 0.4f : alpha));
			g.setColor(cc);
			g.fill(shape);
			g.setComposite(c);
			g.draw(shape); // in Tree's composite mode (such as an alpha)
		}

		/** Expects @param a in local coords. */
		@Override
		public boolean intersects(final Area a) {
			if (0 == r) return a.contains(x, y);
			return M.intersects(a, new Area(new Ellipse2D.Float(x-r, y-r, r+r, r+r)));
			// TODO: not the getSegment() ?
		}

		@Override
		public void apply(final mpicbg.models.CoordinateTransform ct, final Area roi) {
			// store the point
			final double ox = x,
			      oy = y;
			// transform the point itself
			super.apply(ct, roi);
			// transform the radius: assume it's a point to its right
			if (0 != r) {
				final double[] fp = new double[]{ox + r, oy};
				ct.applyInPlace(fp);
				r = ( float )Math.abs(fp[0] - this.x);
			}
		}
		@Override
		public void apply(final VectorDataTransform vdt) {
			for (final VectorDataTransform.ROITransform rt : vdt.transforms) {
				// Apply only the first one that contains the point
				if (rt.roi.contains(x, y)) {
					// Store point
					final double ox = x,
					      oy = y;
					// Transform point
					final double[] fp = new double[]{x, y};
					rt.ct.applyInPlace(fp);
					x = ( float )fp[0];
					y = ( float )fp[1];
					// Transform the radius: assume it's a point to the right of the untransformed point
					if (0 != r) {
						fp[0] = ox + r;
						fp[1] = oy;
						rt.ct.applyInPlace(fp);
						r = ( float )Math.abs(fp[0] - this.x);
					}
					break;
				}
			}
		}

		@Override
		protected void transformData(final AffineTransform aff) {
			switch (aff.getType()) {
				case AffineTransform.TYPE_IDENTITY:
				case AffineTransform.TYPE_TRANSLATION:
					// Radius doesn't change
					return;
				default:
					// Scale the radius as appropriate
					final double[] fp = new double[]{x, y, x + r, y};
					aff.transform(fp, 0, fp, 0, 2);
					r = (float)Math.sqrt(Math.pow(fp[2] - fp[0], 2) + Math.pow(fp[3] - fp[1], 2));
			}
		}
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		final String type = "t2_treeline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_treeline (t2_node*,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	/** Export the radius only if it is larger than zero. */
	@Override
	protected boolean exportXMLNodeAttributes(final StringBuilder indent, final StringBuilder sb, final Node<Float> node) {
		if (node.getData() > 0) sb.append(" r=\"").append(node.getData()).append('\"');
		return true;
	}

	@Override
	protected boolean exportXMLNodeData(final StringBuilder indent, final StringBuilder sb, final Node<Float> node) {
		return false;
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
	/*
	static final public void testMeshGenerationPerformance(int n_iterations) {
		// test 3D mesh generation

		Layer la = Display.getFrontLayer();
		java.util.Random rnd = new java.util.Random(67779);
		Node root = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
		Node parent = root;
		for (int i=0; i<10000; i++) {
			Node child = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
			parent.add(child, Node.MAX_EDGE_CONFIDENCE);
			if (0 == i % 100) {
				// add a branch of 100 nodes
				Node pa = parent;
				for (int k = 0; k<100; k++) {
					Node ch = new RadiusNode(rnd.nextFloat(), rnd.nextFloat(), la);
					pa.add(ch, Node.MAX_EDGE_CONFIDENCE);
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
	*/

	/** Returns a list of two lists: the List<Point3f> and the corresponding List<Color3f>. */
	public MeshData generateMesh(final double scale_, int parallels) {
		// Construct a mesh made of straight tubes for each edge, and balls of the same ending diameter on the nodes.
		//
		// TODO:
		// With some cleverness, such meshes could be welded together by merging the nearest vertices on the ball
		// surfaces, or by cleaving the surface where the diameter of the tube cuts it.
		// A tougher problem is where tubes cut each other, but perhaps if the resulting mesh is still non-manifold, it's ok.

		final float scale = (float)scale_;
		if (parallels < 3) parallels = 3;

		// Simple ball-and-stick model

		// first test: just the nodes as icosahedrons with 1 subdivision

		final Calibration cal = layer_set.getCalibration();
		final float pixelWidthScaled = (float)cal.pixelWidth * scale;
		final float pixelHeightScaled = (float)cal.pixelHeight * scale;
		final int sign = cal.pixelDepth < 0 ? -1 : 1;

		final List<Point3f> ico = M.createIcosahedron(1, 1);
		final List<Point3f> ps = new ArrayList<Point3f>();

		// A plane made of as many edges as parallels, with radius 1
		// Perpendicular vector of the plane is 0,0,1
		final List<Point3f> plane = new ArrayList<Point3f>();
		final double inc_rads = (Math.PI * 2) / parallels;
		double angle = 0;
		for (int i=0; i<parallels; i++) {
			plane.add(new Point3f((float)Math.cos(angle), (float)Math.sin(angle), 0));
			angle += inc_rads;
		}
		final Vector3f vplane = new Vector3f(0, 0, 1);
		final Transform3D t = new Transform3D();
		final AxisAngle4f aa = new AxisAngle4f();

		final List<Color3f> colors = new ArrayList<Color3f>();
		final Color3f cf = new Color3f(this.color);
		final HashMap<Color,Color3f> cached_colors = new HashMap<Color,Color3f>();
		cached_colors.put(this.color, cf);

		for (final Set<Node<Float>> nodes : node_layer_map.values()) {
			for (final Node<Float> nd : nodes) {
				Point2D.Double po = transformPoint(nd.x, nd.y);
				final float x = (float)po.x * pixelWidthScaled;
				final float y = (float)po.y * pixelHeightScaled;
				final float z = (float)nd.la.getZ() * pixelWidthScaled * sign;
				final float r = ((RadiusNode)nd).r * pixelWidthScaled; // TODO r is not transformed by the AffineTransform
				for (final Point3f vert : ico) {
					final Point3f v = new Point3f(vert);
					v.x = v.x * r + x;
					v.y = v.y * r + y;
					v.z = v.z * r + z;
					ps.add(v);
				}

				int n_verts = ico.size();

				// Tube from parent to child
				// Check if a 3D volume representation is necessary for this segment
				if (null != nd.parent && (0 != nd.parent.getData() || 0 != nd.getData())) {

					po = null;

					// parent:
					final Point2D.Double pp = transformPoint(nd.parent.x, nd.parent.y);
					final float parx = (float)pp.x * pixelWidthScaled;
					final float pary = (float)pp.y * pixelWidthScaled;
					final float parz = (float)nd.parent.la.getZ() * pixelWidthScaled * sign;
					final float parr = ((RadiusNode)nd.parent).r * pixelWidthScaled; // TODO r is not transformed by the AffineTransform

					// the vector perpendicular to the plane is 0,0,1
					// the vector from parent to child is:
					final Vector3f vpc = new Vector3f(x - parx, y - pary, z - parz);

					if (x == parx && y == pary) {
						aa.set(0, 0, 1, 0);
					} else {
						final Vector3f cross = new Vector3f();
						cross.cross(vpc, vplane);
						cross.normalize(); // not needed?
						aa.set(cross.x, cross.y, cross.z, -vplane.angle(vpc));
					}
					t.set(aa);


					final List<Point3f> parent_verts = transform(t, plane, parx, pary, parz, parr);
					final List<Point3f> child_verts = transform(t, plane, x, y, z, r);

					for (int i=1; i<parallels; i++) {
						addTriangles(ps, parent_verts, child_verts, i-1, i);
						n_verts += 6;
					}
					// faces from last to first:
					addTriangles(ps, parent_verts, child_verts, parallels -1, 0);
					n_verts += 6;
				}

				// Colors for each segment:
				Color3f c;
				if (null == nd.color) {
					c = cf;
				} else {
					c = cached_colors.get(nd.color);
					if (null == c) {
						c = new Color3f(nd.color);
						cached_colors.put(nd.color, c);
					}
				}
				while (n_verts > 0) {
					n_verts--;
					colors.add(c);
				}
			}
		}

		//Utils.log2("Treeline MeshData lists of same length: " + (ps.size() == colors.size()));

		return new MeshData(ps, colors);
	}

	static private final void addTriangles(final List<Point3f> ps, final List<Point3f> parent_verts, final List<Point3f> child_verts, final int i0, final int i1) {
		// one triangle
		ps.add(new Point3f(parent_verts.get(i0)));
		ps.add(new Point3f(parent_verts.get(i1)));
		ps.add(new Point3f(child_verts.get(i0)));
		// another
		ps.add(new Point3f(parent_verts.get(i1)));
		ps.add(new Point3f(child_verts.get(i1)));
		ps.add(new Point3f(child_verts.get(i0)));
	}

	static private final List<Point3f> transform(final Transform3D t, final List<Point3f> plane, final float x, final float y, final float z, final float radius) {
		final List<Point3f> ps = new ArrayList<Point3f>(plane.size());
		for (final Point3f p2 : plane) {
			final Point3f p = new Point3f(p2);
			p.scale(radius);
			t.transform(p);
			p.x += x;
			p.y += y;
			p.z += z;
			ps.add(p);
		}
		return ps;
	}

	@Override
	public void keyPressed(final KeyEvent ke) {
		if (isTagging()) {
			super.keyPressed(ke);
			return;
		}
		final int tool = ProjectToolbar.getToolId();
		try {
			if (ProjectToolbar.PEN == tool) {
				final Object origin = ke.getSource();
				if (! (origin instanceof DisplayCanvas)) {
					ke.consume();
					return;
				}
				final DisplayCanvas dc = (DisplayCanvas)origin;
				final Layer layer = dc.getDisplay().getLayer();
				final Point p = dc.getCursorLoc(); // as offscreen coords

				switch (ke.getKeyCode()) {
					case KeyEvent.VK_O:
						if (askAdjustRadius(p.x, p.y, layer, dc.getMagnification())) {
							ke.consume();
						}
						break;
				}
			}
		} finally {
			if (!ke.isConsumed()) {
				super.keyPressed(ke);
			}
		}
	}

	private boolean askAdjustRadius(final float x, final float y, final Layer layer, final double magnification) {
		final Collection<Node<Float>> nodes = node_layer_map.get(layer);
		if (null == nodes) return false;

		RadiusNode nd = (RadiusNode) findClosestNodeW(nodes, x, y, magnification);
		if (null == nd) {
			final Node<Float> last = getLastVisited();
			if (last.getLayer() == layer) nd = (RadiusNode)last;
		}
		if (null == nd) return false;

		return askAdjustRadius(nd);
	}

	protected boolean askAdjustRadius(final Node<Float> nd) {

		final GenericDialog gd = new GenericDialog("Adjust radius");
		final Calibration cal = layer_set.getCalibration();
		String unit = cal.getUnit();
		if (!unit.toLowerCase().startsWith("pixel")) {
			final String[] units = new String[]{"pixels", unit};
			gd.addChoice("Units:", units, units[1]);
			gd.addNumericField("Radius:", nd.getData() * cal.pixelWidth, 2);
			final TextField tfr = (TextField) gd.getNumericFields().get(0);
			((Choice)gd.getChoices().get(0)).addItemListener(new ItemListener() {
				@Override
                public void itemStateChanged(final ItemEvent ie) {
					final double val = Double.parseDouble(tfr.getText());
					if (Double.isNaN(val)) return;
					tfr.setText(Double.toString(units[0] == ie.getItem() ?
									val / cal.pixelWidth
								      : val * cal.pixelWidth));
				}
			});
		} else {
			unit = null;
			gd.addNumericField("Radius:", nd.getData(), 2, 10, "pixels");
		}
		final String[] choices = {"this node only", "nodes until next branch or end node", "entire subtree"};
		gd.addChoice("Apply to:", choices, choices[0]);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		double radius = gd.getNextNumber();
		if (Double.isNaN(radius) || radius < 0) {
			Utils.log("Invalid radius: " + radius);
			return false;
		}
		if (null != unit && 1 == gd.getNextChoiceIndex() && 0 != radius) {
			// convert radius from units to pixels
			radius = radius / cal.pixelWidth;
		}
		final float r = (float)radius;
		final Node.Operation<Float> op = new Node.Operation<Float>() {
			@Override
			public void apply(final Node<Float> node) throws Exception {
				node.setData(r);
			}
		};
		// Apply to:
		try {
			layer_set.addDataEditStep(this);
			switch (gd.getNextChoiceIndex()) {
				case 0:
					// Just the node
					nd.setData(r);
					break;
				case 1:
					// All the way to the next branch or end point
					nd.applyToSlab(op);
					break;
				case 2:
					// To the entire subtree of nodes
					nd.applyToSubtree(op);
					break;
				default:
					return false;
			}
			layer_set.addDataEditStep(this);
		} catch (final Exception e) {
			IJError.print(e);
			layer_set.undoOneStep();
		}

		calculateBoundingBox(layer);
		Display.repaint(layer_set);

		return true;
	}

	@Override
	protected Rectangle getBounds(final Collection<? extends Node<Float>> nodes) {
		Rectangle box = null;
		for (final RadiusNode nd : (Collection<RadiusNode>) nodes) {
			if (null == nd.parent) {
				if (null == box) box = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
				else box.add((int)nd.x, (int)nd.y);
				continue;
			}
			// Get the segment with the parent node
			if (null == box) box = nd.getSegment().getBounds();
			else box.add(nd.getSegment().getBounds());
		}
		return box;
	}

	private class RadiusMeasurementPair extends Tree<Float>.MeasurementPair
	{
		public RadiusMeasurementPair(final Tree<Float>.NodePath np) {
			super(np);
		}
		/** A list of calibrated radii, one per node in the path.*/
		@Override
		protected List<Float> calibratedData() {
			final ArrayList<Float> data = new ArrayList<Float>();
			final AffineTransform aff = new AffineTransform(Treeline.this.at);
			final Calibration cal = layer_set.getCalibration();
			aff.preConcatenate(new AffineTransform(cal.pixelWidth, 0, 0, cal.pixelHeight, 0, 0));
			final float[] fp = new float[4];
			for (final Node<Float> nd : super.path) {
				final Float r = nd.getData();
				if (null == r) data.add(null);
				fp[0] = nd.x;
				fp[1] = nd.y;
				fp[2] = nd.x + r.floatValue();
				fp[3] = nd.y;
				aff.transform(fp, 0, fp, 0, 2);
				data.add((float)Math.sqrt(Math.pow(fp[2] - fp[0], 2) + Math.pow(fp[3] - fp[1], 2)));
			}
			return data;
		}
		@Override
		public String getResultsTableTitle() {
			return "Treeline tagged pairs";
		}
		@Override
		public ResultsTable toResultsTable(ResultsTable rt, final int index, final double scale, final int resample) {
			if (null == rt) {
				final String unit = layer_set.getCalibration().getUnit();
				rt = Utils.createResultsTable(getResultsTableTitle(),
					new String[]{"id", "index", "length " + unit, "volume " + unit + "^3",
					"shortest diameter " + unit, "longest diameter " + unit,
					"average diameter " + unit, "stdDev diameter"});
			}
			rt.incrementCounter();
			rt.addValue(0, Treeline.this.id);
			rt.addValue(1, index);
			rt.addValue(2, distance);
			double minRadius = Double.MAX_VALUE,
				   maxRadius = 0,
				   sumRadii = 0,
				   volume = 0;
			int i = 0;
			double last_r = 0;
			Point3f last_p = null;
			final Iterator<Point3f> itp = coords.iterator();
			final Iterator<Float> itr = data.iterator();
			while (itp.hasNext()) {
				final double r = itr.next();
				final Point3f p = itp.next();
				//
				minRadius = Math.min(minRadius, r);
				maxRadius = Math.max(maxRadius, r);
				sumRadii += r;
				//
				if (i > 0) {
					volume += M.volumeOfTruncatedCone(r, last_r, p.distance(last_p));
				}
				//
				i += 1;
				last_r = r;
				last_p = p;
			}
			final int count = path.size();
			final double avgRadius = (sumRadii / count);
			// Compute standard deviation of the diameters:
			double s = 0;
			for (final Float r : data) s += Math.pow(2 * (r - avgRadius), 2);
			final double stdDev = Math.sqrt(s / count);
			//
			rt.addValue(3, volume);
			rt.addValue(4, minRadius * 2);
			rt.addValue(5, maxRadius * 2);
			rt.addValue(6, avgRadius * 2);
			rt.addValue(7, stdDev);
			return rt;
		}
		@Override
		public MeshData createMesh(final double scale, final int parallels) {
			final Treeline sub = new Treeline(project, -1, title, width, height, alpha, visible, color, locked, new AffineTransform(Treeline.this.at));
			sub.layer_set = Treeline.this.layer_set;
			sub.root = path.get(0);
			sub.cacheSubtree(path);
			return sub.generateMesh(scale, parallels);
		}
	}

	@Override
	protected MeasurementPair createMeasurementPair(final NodePath np) {
		return new RadiusMeasurementPair(np);
	}
}
