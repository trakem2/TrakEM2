package ini.trakem2.display;

import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Point3f;

/** A one-to-many connection, represented by one source point and one or more target points. The connector is drawn by click+drag+release, defining the origin at click and the target at release. By clicking anywhere else, the connector can be given another target. Points can be dragged and removed.
 * Connectors are meant to represent synapses, in particular polyadic synapses. */
public class Connector extends Treeline {

	public Connector(Project project, String title) {
		super(project, title);
	}
	
	public Connector(Project project, long id, String title, float width, float height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	/** Reconstruct from XML. */
	public Connector(final Project project, final long id, final HashMap<String,String> ht_attr, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	@Override
	public Tree<Float> newInstance() {
		return new Connector(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Node<Float> newNode(float lx, float ly, Layer la, Node<?> modelNode) {
		return new ConnectorNode(lx, ly, la, null == modelNode ? 0 : ((ConnectorNode)modelNode).r);
	}

	@Override
	public Node<Float> newNode(final HashMap<String,String> ht_attr) {
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
		public ConnectorNode(final HashMap<String,String> attr) {
			super(attr);
		}

		@Override
		public final Node<Float> newInstance(final float lx, final float ly, final Layer layer) {
			return new ConnectorNode(lx, ly, layer, 0);
		}
		@Override
		public void paintData(final Graphics2D g, final Rectangle srcRect,
				final Tree<Float> tree, final AffineTransform to_screen, final Color cc,
				final Layer active_layer) {
			g.setColor(cc);
			g.draw(to_screen.createTransformedShape(new Ellipse2D.Float(x -r, y -r, r+r, r+r)));
		}

		@Override
		public boolean intersects(final Area a) {
			if (0 == r) return a.contains(x, y);
			return M.intersects(a, getArea());
		}

		@Override
		public boolean isRoughlyInside(final Rectangle localbox) {
			final float r = this.r <= 0 ? 1 : this.r;
			return localbox.intersects(x - r, y - r, r + r, r + r);
		}

		@Override
		public Area getArea() {
			if (0 == r) return super.getArea(); // a little square
			return new Area(new Ellipse2D.Float(x-r, y-r, r+r, r+r));
		}

		@Override
		public void paintHandle(final Graphics2D g, final Rectangle srcRect, final double magnification, final Tree<Float> t) {
			final Point2D.Double po = t.transformPoint(this.x, this.y);
			final float x = (float)((po.x - srcRect.x) * magnification);
			final float y = (float)((po.y - srcRect.y) * magnification);

			if (null == parent) {
				g.setColor(brightGreen);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("o", (int)x -4, (int)y + 3); // TODO ensure Font is proper
			} else {
				g.setColor(Color.white);
				g.fillOval((int)x - 6, (int)y - 6, 11, 11);
				g.setColor(Color.black);
				g.drawString("x", (int)x -4, (int)y + 3); // TODO ensure Font is proper
			}
		}
	}

	static private final Color brightGreen = new Color(33, 255, 0);

	public void readLegacyXML(final LayerSet ls, final HashMap<String,String> ht_attr, final HashMap<Displayable,String> ht_links) {
		final String origin = ht_attr.get("origin");
		final String targets = ht_attr.get("targets");
		if (null != origin) {
			final String[] o = origin.split(",");
			String[] t = null;
			int len = 1;
			final boolean new_format = 0 == o.length % 4;
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
			final float[] p = new float[len + len];
			final long[] lids = new long[len];
			final float[] radius = new float[len];

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
			//if (!new_format) calculateBoundingBox(null);

			// Now, into nodes:
			final Node<Float> root = new ConnectorNode(p[0], p[1], ls.getLayer(lids[0]), radius[0]);
			for (int i=1; i<lids.length; i++) {
				Node<Float> nd = new ConnectorNode(p[i+i], p[i+i+1], ls.getLayer(lids[i]), radius[i]);
				root.add(nd, Node.MAX_EDGE_CONFIDENCE);
			}
			setRoot(root);

			// Above, cannot be done with addNode: would call repaint and thus calculateBoundingBox, which would screw up relative coords.

			// Fix bounding box to new tree methods:
			calculateBoundingBox(null);
		}
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
		for (final Map.Entry<Node<Float>,Byte> e : c.root.getChildren().entrySet()) {
			final ConnectorNode nd = (ConnectorNode)e.getKey();
			f[0] = nd.x;
			f[1] = nd.y;
			f[2] = nd.x + nd.r; // make the radius be a point to the right of x,y
			f[3] = nd.y;
			aff.transform(f, 0, f, 0, 2);
			this.root.add(new ConnectorNode(f[0],f[1], nd.la, Math.abs(f[2] - f[0])), e.getValue().byteValue());
		}
	}

	public boolean intersectsOrigin(final Area area, final Layer la) {
		if (null == root || root.la != la) return false;
		final Area a = root.getArea();
		a.transform(this.at);
		return M.intersects(area, a);
	}
	
	/** Whether the area of the root node intersects the world coordinates {@param wx}, {@param wy} at {@link Layer} {@param la}. */
	public boolean intersectsOrigin(final double wx, final double wy, final Layer la) {
		if (null == root || root.la != la) return false;
		final Area a = root.getArea();
		a.transform(this.at);
		return a.contains(wx, wy);
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins(final Class<?> c) {
		final int m = c.getModifiers();
		return getOrigins(c, Modifier.isAbstract(m) || Modifier.isInterface(m));
	}
	public Set<Displayable> getOrigins(final Class<?> c, final boolean instance_of) {
		if (null == root) return new HashSet<Displayable>();
		return getUnder(root, c, instance_of);
	}

	private final Set<Displayable> getUnder(final Node<Float> node, final Class<?> c, final boolean instance_of) {
		final Area a = node.getArea();
		a.transform(this.at);
		final HashSet<Displayable> targets = new HashSet<Displayable>(layer_set.find(c, node.la, a, false, instance_of));
		targets.remove(this);
		return targets;
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins() {
		if (null == root) return new HashSet<Displayable>();
		return getUnder(root, Displayable.class, true);
	}

	public List<Set<Displayable>> getTargets(final Class<?> c, final boolean instance_of) {
		final List<Set<Displayable>> al = new ArrayList<Set<Displayable>>();
		if (null == root || !root.hasChildren()) return al;
		for (final Node<Float> nd : root.getChildrenNodes()) {
			al.add(getUnder(nd, c, instance_of));
		}
		return al;
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets(final Class<?> c) {
		final int m = c.getModifiers();
		return getTargets(c, Modifier.isAbstract(m) || Modifier.isInterface(m));
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets() {
		return getTargets(Displayable.class, true);
	}

	public int getTargetCount() {
		if (null == root) return 0;
		return root.getChildrenCount();
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		final String type = "t2_connector";
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

	private final void insert(final Node<Float> nd, final ResultsTable rt, final int i, final Calibration cal, final float[] f) {
		f[0] = nd.x;
		f[1] = nd.y;
		this.at.transform(f, 0, f, 0, 1);
		//
		rt.incrementCounter();
		rt.addLabel("units", cal.getUnits());
		rt.addValue(0, this.id);
		rt.addValue(1, i);
		rt.addValue(2, f[0] * cal.pixelWidth);
		rt.addValue(3, f[1] * cal.pixelHeight);
		rt.addValue(4, nd.la.getZ() * cal.pixelWidth); // NOT pixelDepth!
		rt.addValue(5, ((ConnectorNode)nd).r);
		rt.addValue(6, nd.confidence);
	}

	public ResultsTable measure(ResultsTable rt) {
		if (null == root) return rt;
		if (null == rt) rt = Utils.createResultsTable("Connector results", new String[]{"id", "index", "x", "y", "z", "radius", "confidence"});
		final Calibration cal = layer_set.getCalibration();
		final float[] f = new float[2];
		insert(root, rt, 0, cal, f);
		if (null == root.children) return rt;
		for (int i=0; i<root.children.length; i++) {
			insert(root.children[i], rt, i+1, cal, f);
		}
		return rt;
	}

	public List<Point3f> getTargetPoints(final boolean calibrated) {
		if (null == root) return null;
		final List<Point3f> targets = new ArrayList<Point3f>();
		if (null == root.children) return targets;
		final float[] f = new float[2];
		for (final Node<Float> nd : root.children) {
			targets.add(fix(nd.asPoint(), calibrated, f));
		}
		return targets;
	}

	public Coordinate<Node<Float>> getCoordinateAtOrigin() {
		if (null == root) return null;
		return createCoordinate(root);
	}

	/** Get a coordinate for target i. */
	public Coordinate<Node<Float>> getCoordinate(final int i) {
		if (null == root || !root.hasChildren()) return null;
		return createCoordinate(root.children[i]);
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
	public void mousePressed(MouseEvent me, final Layer layer, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN != ProjectToolbar.getToolId()) {
			return;
		}

		if (-1 == last_radius) {
			last_radius = 10 / (float)mag;
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

			Node<Float> found = findNode(x_pl, y_pl, layer, mag);
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
						layer_set.addChangeTreesStep();
						if (remove2(true)) {
							setActive(null);
							layer_set.addChangeTreesStep();
						} else {
							layer_set.removeLastUndoStep(); // no need
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
				((ConnectorNode)found).setData(last_radius);
				addNode(root, found, Node.MAX_EDGE_CONFIDENCE);
				setActive(found);
				repaint(true, layer);
			}
			return;
		} else {
			// First point
			root = newNode(x_p, y_p, layer, null); // world coords, so calculateBoundingBox will do the right thing
			addNode(null, root, (byte)0);
			((ConnectorNode)root).setData(last_radius);
			setActive(root);
		}
	}

	@Override
	protected boolean requireAltDownToEditRadius() { return false; }

	@Override
	protected Rectangle getBounds(final Collection<? extends Node<Float>> nodes) {
		final Rectangle nb = new Rectangle();
		Rectangle box = null;
		for (final RadiusNode nd : (Collection<RadiusNode>)(Collection)nodes) {
			final int r = 0 == nd.r ? 1 : (int)nd.r;
			if (null == box) box = new Rectangle((int)nd.x - r, (int)nd.y - r, r+r, r+r);
			else {
				nb.setBounds((int)nd.x - r, (int)nd.y - r, r+r, r+r);
				box.add(nb);
			}
		}
		return box;
	}
	
	/** If the root node (the origin) does not remain within the range, this Connector is left empty. */
	@Override
	public boolean crop(List<Layer> range) {
		if (null == root) return true; // it's empty already
		if (!range.contains(root.la)) {
			this.root = null;
			synchronized (node_layer_map) {
				clearCache();
			}
			return true;
		}
		return super.crop(range);
	}
}
