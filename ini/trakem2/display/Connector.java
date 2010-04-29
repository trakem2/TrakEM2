package ini.trakem2.display;

import ij.measure.Calibration;
import ij.measure.ResultsTable;
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
import java.awt.Color;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
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

/** A one-to-many connection, represented by one source point and one or more target points. The connector is drawn by click+drag+release, defining the origin at click and the target at release. By clicking anywhere else, the connector can be given another target. Points can be dragged and removed.
 * Connectors are meant to represent synapses, in particular polyadic synapses. */
public class Connector extends Treeline {

	static private float last_radius = 0;

	public Connector(Project project, String title) {
		super(project, title);
	}
	
	public Connector(Project project, long id, String title, double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	/** Reconstruct from XML. */
	public Connector(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	@Override
	public Tree newInstance() {
		return new Connector(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Node newNode(float lx, float ly, Layer la, Node modelNode) {
		return new ConnectorNode(lx, ly, la, null == modelNode ? 0 : ((ConnectorNode)modelNode).r);
	}

	@Override
	public Node newNode(HashMap ht_attr) {
		return new ConnectorNode(ht_attr);
	}

	static public class ConnectorNode extends Treeline.RadiusNode {

		public ConnectorNode(final float lx, final float ly, final Layer la) {
			super(lx, ly, la);
		}
		public ConnectorNode(final float lx, final float ly, final Layer la, final float radius) {
			super(lx, ly, la, radius);
		}
		/** To reconstruct from XML, without a layer. */
		public ConnectorNode(final HashMap attr) {
			super(attr);
		}

		@Override
		public final Node newInstance(final float lx, final float ly, final Layer layer) {
			return new ConnectorNode(lx, ly, layer, 0);
		}
		@Override
		public void paintData(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint, final Tree tree) {
			final AffineTransform a = new AffineTransform();
			a.scale(magnification, magnification);
			a.translate(-srcRect.x, -srcRect.y);
			a.concatenate(tree.at);
			;

			// Which color?
			if (active_layer == this.la) {
				g.setColor(tree.getColor());
			} else {
				if (active_layer.getZ() > this.la.getZ()) g.setColor(Color.red);
				else g.setColor(Color.blue);
			}

			g.draw(a.createTransformedShape(new Ellipse2D.Float(x -r, y -r, r+r, r+r)));
		}

		@Override
		public boolean intersects(final Area a) {
			if (0 == r) return a.contains(x, y);
			return M.intersects(a, new Area(new Ellipse2D.Float(x-r, y-r, r+r, r+r)));
		}

		@Override
		public Area getArea() {
			if (0 == r) return super.getArea(); // a little square
			return new Area(new Ellipse2D.Float(x-r, y-r, r+r, r+r));
		}
	}


	public Connector fromLegacyXML(final Project project, final long id, final LayerSet ls, final HashMap ht_attr, final HashMap ht_links) {
		Connector con = new Connector(project, id, ht_attr, ht_links);

		float[] p = null;
		long[] lids = null;
		float[] radius = null;

		String origin = (String) ht_attr.get("origin");
		String targets = (String) ht_attr.get("targets");
		if (null != origin) {
			String[] o = origin.split(",");
			String[] t = null;
			int len = 1;
			boolean new_format = 0 == o.length % 4;
			if (null != targets) {
				t = targets.split(",");
				if (new_format) {
					// new format, with radii
					len += t.length / 4;
				} else {
					// old format, without radii
					len += t.length / 3;
				}
			}
			p = new float[len + len];
			lids = new long[len];
			radius = new float[len];

			// Origin:
			/* X  */ p[0] = Float.parseFloat(o[0]);
			/* Y  */ p[1] = Float.parseFloat(o[1]);
			/* LZ */ lids[0] = Long.parseLong(o[2]);
			if (new_format) {
				radius[0] = Float.parseFloat(o[3]);
			}

			// Targets:
			if (null != targets && targets.length() > 0) {
				int inc = new_format ? 4 : 3;
				for (int i=0, k=1; i<t.length; i+=inc, k++) {
					/* X  */ p[k+k] = Float.parseFloat(t[i]);
					/* Y  */ p[k+k+1] = Float.parseFloat(t[i+1]);
					/* LZ */ lids[k] = Long.parseLong(t[i+2]);
					if (new_format) radius[k] = Float.parseFloat(t[i+3]);
				}
			}
			if (!new_format) calculateBoundingBox();


			// Now, into nodes:
			root = new ConnectorNode(p[0], p[1], ls.getLayer(lids[0]), radius[0]);
			addNode(null, root, (byte)0);
			for (int i=1; i<lids.length; i++) {
				Node nd = new ConnectorNode(p[i+i], p[i+i+1], ls.getLayer(lids[i]), radius[i]);
				addNode(root, nd, Node.MAX_EDGE_CONFIDENCE);
			}
			cacheSubtree(root.getSubtreeNodes());
		}

		return con;
	}

	public int addTarget(final float x, final float y, final long layer_id, final float r) {
		if (null == root) return -1;
		root.add(new ConnectorNode(x, y, layer_set.getLayer(layer_id), r), Node.MAX_EDGE_CONFIDENCE);
		return root.getChildrenCount() - 1;
	}

	public int addTarget(final double x, final double y, final long layer_id, final double r) {
		return addTarget((float)x, (float)y, layer_id, (float)r);
	}

	protected void mergeTargets(final Connector c) throws NoninvertibleTransformException {
		if (null == c.root) return;
		if (null == this.root) this.root = newNode(c.root.x, c.root.y, c.root.la, c.root);
		final AffineTransform aff = new AffineTransform(c.at);
		aff.preConcatenate(this.at.createInverse());
		final float[] f = new float[4];
		for (final Map.Entry<Node,Byte> e : (Collection<Map.Entry<Node,Byte>>)c.root.getChildren().entrySet()) { // TODO should not need to cast
			final ConnectorNode nd = (ConnectorNode)e.getKey();
			f[0] = nd.x;
			f[1] = nd.y;
			f[2] = nd.x + nd.r; // make the radius be a point to the right of x,y
			f[3] = nd.y;
			aff.transform(f, 0, f, 0, 2);
			this.root.add(new ConnectorNode(f[0],f[1], nd.la, Math.abs(f[2] - f[0])), e.getValue().byteValue());
		}
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins(final Class c) {
		if (null == root) return new HashSet<Displayable>();
		return getUnder(root, c);
	}

	private final Set<Displayable> getUnder(final Node node, final Class c) {
		final Area a = node.getArea();
		a.transform(this.at);
		HashSet<Displayable> targets = new HashSet<Displayable>(layer_set.find(c, node.la, a, true));
		targets.remove(this);
		return targets;
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins() {
		return getOrigins(Displayable.class);
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets(final Class c) {
		final List<Set<Displayable>> al = new ArrayList<Set<Displayable>>();
		if (null == root || !root.hasChildren()) return al;
		for (Node nd : (Collection<Node>)root.getChildrenNodes()) { // TODO should not need to cast
			al.add(getUnder(nd, c));
		}
		return al;
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets() {
		return getTargets(Displayable.class);
	}

	public int getTargetCount() {
		if (null == root) return 0;
		return root.getChildrenCount();
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		String type = "t2_connector";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_connector (t2_node*,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	public Connector clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		Connector copy = new Connector(pr, nid, title, width, height, this.alpha, true, this.color, this.locked, this.at);
		copy.root = null == this.root ? null : this.root.clone(pr);
		copy.addToDatabase();
		if (null != copy.root) copy.cacheSubtree(copy.root.getSubtreeNodes());
		return copy;
	}

	public ResultsTable measure(ResultsTable rt) {
		if (null == root) return rt;
		/* // TODO
		if (null == rt) rt = Utils.createResultsTable("Connector results", new String[]{"id", "index", "x", "y", "z", "radius"});
		float[] p = transformPoints(this.p);
		final Calibration cal = layer_set.getCalibration();
		for (int i=0; i<lids.length; i++) {
			rt.incrementCounter();
			rt.addLabel("units", cal.getUnit());
			rt.addValue(0, this.id);
			rt.addValue(1, i); // start at 0, the origin
			rt.addValue(2, p[i+i] * cal.pixelWidth);
			rt.addValue(3, p[i+i+1] * cal.pixelHeight);
			rt.addValue(4, layer_set.getLayer(lids[i]).getZ() * cal.pixelWidth);
			rt.addValue(5, radius[i] * cal.pixelWidth);
		}
		*/
		return rt;
	}

	public Point3f getOriginPoint(final boolean calibrated) {
		if (null == root) return null;
		return root.asPoint(calibrated);
	}

	public List<Point3f> getTargetPoints(final boolean calibrated) {
		if (null == root) return null;
		final List<Point3f> targets = new ArrayList<Point3f>();
		for (final Node nd : (Collection<Node>)root.getChildrenNodes()) { // TODO should not need to cast
			targets.add(nd.asPoint(calibrated));
		}
		return targets;
	}

	public String getInfo() {
		if (null == root) return "Empty";
		return new StringBuilder("Targets: ").append(root.getChildrenCount()).append('\n').toString();
	}

	/** If the root node is in Layer @param la, then all nodes are removed. */
	protected boolean layerRemoved(Layer la) {
		if (null == root) return true;
		if (root.la == la) {
			super.removeNode(root); // and all its children
			return true;
		}
		// Else, remove any targets
		return super.layerRemoved(la);
	}

	/** Takes the List of Connector instances and adds the targets of all to the first one.
	 *  Removes the others from the LayerSet and from the Project.
	 *  If any of the Connector instances cannot be removed, returns null. */
	static public Connector merge(final List<Connector> col) throws NoninvertibleTransformException {
		if (null == col || 0 == col.size()) return null;
		final Connector base = col.get(0);
		for (final Connector con : col.subList(1, col.size())) {
			base.mergeTargets(con);
			if (!con.remove2(false)) {
				Utils.log("FAILED to merge Connector " + con + " into " + base);
				return null;
			}
		}
		return base;
	}

	/** Add a root or child nodes to root. */
	@Override
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

			Node found = findNode(x_pl, y_pl, layer, mag);
			setActive(found);

			if (null != found) {
				if (2 == me.getClickCount()) {
					setLastMarked(found);
					setActive(null);
					return;
				}
				if (me.isShiftDown() && Utils.isControlDown(me)) {
					if (found == root) {
						// Remove the whole Connector
						if (remove2(true)) {
							setActive(null);
						}
						return;
					} else {
						// Remove point
						removeNode(found);
					}
				}
			} else {
				if (2 == me.getClickCount()) {
					setLastMarked(null);
					return;
				}
				// Add new target point to root:
				found = newNode(x_pl, y_pl, layer, root);
				addNode(root, found, Node.MAX_EDGE_CONFIDENCE);
				setActive(found);
				repaint(true);
			}
			return;
		} else {
			// First point
			root = newNode(x_p, y_p, layer, null); // world coords, so calculateBoundingBox will do the right thing
			addNode(null, root, (byte)0);
			setActive(root);
		}
	}
}
