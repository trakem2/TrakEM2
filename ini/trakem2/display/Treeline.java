package ini.trakem2.display;

import ij.measure.Calibration;
import ij.gui.GenericDialog;
import ini.trakem2.Project;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.awt.Point;
import java.awt.Choice;
import java.awt.TextField;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Collection;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import javax.media.j3d.Transform3D;
import javax.vecmath.AxisAngle4f;
import java.awt.Polygon;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.Composite;
import java.awt.AlphaComposite;

public class Treeline extends Tree<Float> {

	static protected float last_radius = -1;

	public Treeline(Project project, String title) {
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
	public Node<Float> newNode(float lx, float ly, Layer la, Node<?> modelNode) {
		return new RadiusNode(lx, ly, la, null == modelNode ? 0 : ((RadiusNode)modelNode).r);
	}

	@Override
	public Node<Float> newNode(HashMap<String,String> ht_attr) {
		return new RadiusNode(ht_attr);
	}

	@Override
	public Treeline clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		Treeline tline =  new Treeline(pr, nid, title, width, height, alpha, visible, color, locked, at);
		tline.root = null == this.root ? null : this.root.clone(pr);
		tline.addToDatabase();
		if (null != tline.root) tline.cacheSubtree(tline.root.getSubtreeNodes());
		return tline;
	}

	@Override
	public void mousePressed(MouseEvent me, Layer la, int x_p, int y_p, double mag) {
		if (-1 == last_radius) {
			last_radius = 10 / (float)mag;
		}

		if (me.isShiftDown() && me.isAltDown() && !Utils.isControlDown(me)) {
			final Layer layer = Display.getFrontLayer(this.project);
			Node<Float> nd = findNodeNear(x_p, y_p, layer, mag);
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
	public void mouseDragged(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
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
			Node<Float> nd = getActive();
			float r = (float)Math.sqrt(Math.pow(xd - nd.x, 2) + Math.pow(yd - nd.y, 2));
			nd.setData(r);
			last_radius = r;
			repaint(true, la);
			return;
		}

		super.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
	}

	@Override
	public void mouseReleased(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		if (null == getActive()) return;

		if (me.isShiftDown() && me.isAltDown() && !Utils.isControlDown(me)) {
			updateViewData(getActive());
			return;
		}
		super.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent mwe) {
		final int modifiers = mwe.getModifiers();
		if (0 == ( (MouseWheelEvent.SHIFT_MASK | MouseWheelEvent.ALT_MASK) ^ modifiers)) {
			Object source = mwe.getSource();
			if (! (source instanceof DisplayCanvas)) return;
			DisplayCanvas dc = (DisplayCanvas)source;
			Layer la = dc.getDisplay().getLayer();
			final int rotation = mwe.getWheelRotation();
			final float magnification = (float)dc.getMagnification();
			final Rectangle srcRect = dc.getSrcRect();
			final float x = ((mwe.getX() / magnification) + srcRect.x);
			final float y = ((mwe.getY() / magnification) + srcRect.y);

			float inc = (rotation > 0 ? 1 : -1) * (1/magnification);
			if (null != adjustNodeRadius(inc, x, y, la, magnification)) {
				Display.repaint(this);
				mwe.consume();
				return;
			}
		}
		super.mouseWheelMoved(mwe);
	}

	protected Node<Float> adjustNodeRadius(float inc, float x, float y, Layer layer, double magnification) {
		Node<Float> nearest = findNodeNear(x, y, layer, magnification);
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
		public RadiusNode(final HashMap attr) {
			super(attr);
			String sr = (String)attr.get("r");
			this.r = null == sr ? 0 : Float.parseFloat(sr);
		}

		public Node<Float> newInstance(final float lx, final float ly, final Layer layer) {
			return new RadiusNode(lx, ly, layer, 0);
		}

		/** Set the radius to a positive value. When zero or negative, it's set to zero. */
		public final boolean setData(final Float radius) {
			this.r = radius > 0 ? radius : 0;
			return true;
		}
		public final Float getData() { return this.r; }

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
						return getSegment().intersects(localbox);
					}
				}
			} else {
				if (null == parent) {
					return localbox.contains((int)this.x, (int)this.y);
				} else {
					return getSegment().intersects(localbox);
				}
			}
		}

		private final Polygon getSegment() {
			final RadiusNode parent = (RadiusNode) this.parent;
			float vx = parent.x - this.x;
			float vy = parent.y - this.y;
			float len = (float) Math.sqrt(vx*vx + vy*vy);
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

		/** Paint segments. Returns true if the edges have to be painted as well. */
		@Override
		public boolean paintData(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Collection<Node<Float>> to_paint, final Tree<Float> tree, final AffineTransform to_screen) {
			if (null == this.parent) return true; // doing it here for less total cost
			if (0 == this.r && 0 == parent.getData()) return true;

			// Two transformations, but it's only 4 points each and it's necessary
			final Polygon segment = getSegment();
			if (!tree.at.createTransformedShape(segment).intersects(srcRect)) return false;
			final Shape shape = to_screen.createTransformedShape(segment);

			// Which color?
			if (active_layer == this.la) {
				g.setColor(tree.getColor());
			} else {
				if (active_layer.getZ() > this.la.getZ()) g.setColor(Color.red);
				else g.setColor(Color.blue);
			}

			final Composite c = g.getComposite();
			final float alpha = tree.getAlpha();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha > 0.4f ? 0.4f : alpha));
			g.fill(shape);
			g.setComposite(c);
			g.draw(shape); // in Tree's composite mode (such as an alpha)

			return true;
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
			float ox = x,
			      oy = y;
			// transform the point itself
			super.apply(ct, roi);
			// transform the radius: assume it's a point to its right
			if (0 != r) {
				float[] fp = new float[]{ox + r, oy};
				ct.applyInPlace(fp);
				r = Math.abs(fp[0] - this.x);
			}
		}
		@Override
		public void apply(final VectorDataTransform vdt) {
			for (final VectorDataTransform.ROITransform rt : vdt.transforms) {
				// Apply only the first one that contains the point
				if (rt.roi.contains(x, y)) {
					// Store point
					float ox = x,
					      oy = y;
					// Transform point
					float[] fp = new float[]{x, y};
					rt.ct.applyInPlace(fp);
					x = fp[0];
					y = fp[1];
					// Transform the radius: assume it's a point to the right of the untransformed point
					if (0 != r) {
						fp[0] = ox + r;
						fp[1] = oy;
						rt.ct.applyInPlace(fp);
						r = Math.abs(fp[0] - this.x);
					}
					break;
				}
			}
		}
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet hs, final String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		final String type = "t2_treeline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_treeline (t2_node*,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	@Override
	protected boolean exportXMLNodeAttributes(final StringBuilder indent, final StringBuilder sb, final Node node) {
		sb.append(" r=\"").append(node.getData()).append('\"');
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

	public List<Point3f> generateMesh(double scale_, int parallels) {
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


		for (final Set<Node<Float>> nodes : node_layer_map.values()) {
			for (final Node<Float> nd : nodes) {
				Point2D.Double po = transformPoint(nd.x, nd.y);
				final float x = (float)po.x * pixelWidthScaled;
				final float y = (float)po.y * pixelHeightScaled;
				final float z = (float)nd.la.getZ() * pixelWidthScaled * sign;
				final float r = ((RadiusNode)nd).r * pixelWidthScaled; // TODO r is not transformed by the AffineTransform
				for (final Point3f vert : ico) {
					Point3f v = new Point3f(vert);
					v.x = v.x * r + x;
					v.y = v.y * r + y;
					v.z = v.z * r + z;
					ps.add(v);
				}
				// Tube from parent to child
				if (null == nd.parent) continue;

				po = null;

				// parent:
				Point2D.Double pp = transformPoint(nd.parent.x, nd.parent.y);
				final float parx = (float)pp.x * pixelWidthScaled;
				final float pary = (float)pp.y * pixelWidthScaled;
				final float parz = (float)nd.parent.la.getZ() * pixelWidthScaled * sign;
				final float parr = ((RadiusNode)nd.parent).r * pixelWidthScaled; // TODO r is not transformed by the AffineTransform

				// the vector perpendicular to the plane is 0,0,1
				// the vector from parent to child is:
				Vector3f vpc = new Vector3f(x - parx, y - pary, z - parz);
				Vector3f cross = new Vector3f();
				cross.cross(vpc, vplane);
				cross.normalize(); // not needed?
				aa.set(cross.x, cross.y, cross.z, -vplane.angle(vpc));
				t.set(aa);
				

				final List<Point3f> parent_verts = transform(t, plane, parx, pary, parz, parr);
				final List<Point3f> child_verts = transform(t, plane, x, y, z, r);

				for (int i=1; i<parallels; i++) {
					addTriangles(ps, parent_verts, child_verts, i-1, i);
				}
				// faces from last to first:
				addTriangles(ps, parent_verts, child_verts, parallels -1, 0);
			}
		}

		return ps;
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
		for (Point3f p : plane) {
			p = new Point3f(p);
			p.x *= radius;
			p.y *= radius;
			p.z *= radius;
			t.transform(p);
			p.x += x;
			p.y += y;
			p.z += z;
			ps.add(p);
		}
		return ps;
	}

	@Override
	public void keyPressed(KeyEvent ke) {
		final int tool = ProjectToolbar.getToolId();
		try {
			if (ProjectToolbar.PEN == tool) {
				Object origin = ke.getSource();
				if (! (origin instanceof DisplayCanvas)) {
					ke.consume();
					return;
				}
				DisplayCanvas dc = (DisplayCanvas)origin;
				Layer layer = dc.getDisplay().getLayer();
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
		if (null == nd) return false;

		GenericDialog gd = new GenericDialog("Adjust radius");
		final Calibration cal = layer.getParent().getCalibration();
		String unit = cal.getUnit(); 
		if (!unit.toLowerCase().startsWith("pixel")) {
			final String[] units = new String[]{"pixels", unit};
			gd.addChoice("Units:", units, units[1]);
			gd.addNumericField("Radius:", nd.getData() * cal.pixelWidth, 2);
			final TextField tfr = (TextField) gd.getNumericFields().get(0);
			((Choice)gd.getChoices().get(0)).addItemListener(new ItemListener() {
				public void itemStateChanged(ItemEvent ie) {
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
		nd.setData((float)radius);

		calculateBoundingBox(layer);
		Display.repaint(layer_set);

		return true;
	}

	@Override
	protected Rectangle getBounds(final Collection<Node<Float>> nodes) {
		Rectangle box = null;
		for (final RadiusNode nd : (Collection<RadiusNode>)(Collection)nodes) {
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
}
