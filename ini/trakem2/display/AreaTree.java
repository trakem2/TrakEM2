package ini.trakem2.display;

import ini.trakem2.Project;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Color3f;
import javax.vecmath.Point3f;

public class AreaTree extends Tree<Area> implements AreaContainer {

	private boolean fill_paint = true;

	public AreaTree(Project project, String title) {
		super(project, title);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public AreaTree(final Project project, final long id, final HashMap<String,String> ht_attr, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase() */
	public AreaTree(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Tree<Area> newInstance() {
		return new AreaTree(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Node<Area> newNode(float lx, float ly, Layer la, Node<?> modelNode) {
		// Ignore modeNode (could be nice, though, to automatically add the previous area)
		return new AreaNode(lx, ly, la);
	}
	
	@Override
	public Node<Area> newNode(HashMap<String,String> ht_attr) {
		return new AreaNode(ht_attr);
	}

	@Override
	public AreaTree clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		AreaTree art =  new AreaTree(pr, nid, title, width, height, alpha, visible, color, locked, at);
		art.root = null == this.root ? null : this.root.clone(pr);
		art.addToDatabase();
		if (null != art.root) art.cacheSubtree(art.root.getSubtreeNodes());
		return art;
	}

	static public final class AreaNode extends Node<Area> {
		/** The Area wrapped by AreaWrapper is in local AreaTree coordinates, not in local Node coordinates. */
		private AreaWrapper aw;

		public AreaNode(final float lx, final float ly, final Layer la) {
			super(lx, ly, la);
		}
		/** To reconstruct from XML, without a layer. */
		public AreaNode(final HashMap<String,String> attr) {
			super(attr);
		}

		public final Node<Area> newInstance(final float lx, final float ly, final Layer layer) {
			return new AreaNode(lx, ly, layer);
		}

		public final synchronized boolean setData(final Area area) {
			if (null == area) {
				if (null == this.aw) return true;
				this.aw.getArea().reset();
			} else {
				if (null != this.aw) this.aw.putData(area);
				else this.aw = new AreaWrapper(area);
			}
			return true;
		}
		public final synchronized Area getData() {
			if (null == this.aw) this.aw = new AreaWrapper();
			return this.aw.getArea();
		}
		public final synchronized Area getDataCopy() {
			if (null == this.aw) return null;
			return new Area(this.aw.getArea());
		}

		/** Return Area in local coords. The area includes a little square for the point, always. */
		@Override
		public final Area getArea() {
			if (null == this.aw) return super.getArea(); // a little square labeling this point
			Area a = this.aw.getArea();
			if (a.isEmpty()) return super.getArea();
			a.add(super.getArea()); // ensure the point is part of the Area always
			return a;
		}

		/** Returns false if the edges should not be painted. */
		@Override
		public boolean paintData(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Collection<Node<Area>> to_paint, final Tree<Area> tree, final AffineTransform to_screen) {
			if (active_layer != this.la || null == aw) return true; // yes, paint the edges

			aw.paint(g, to_screen, ((AreaTree)tree).fill_paint, null == this.color ? tree.getColor() : this.color);

			return true;
		}

		/*
		final boolean contains(final int lx, final int ly) {
			return null != aw && aw.getArea().contains(lx, ly);
		}
		*/
		final boolean contains(final float lx, final float ly) {
			return null != aw && aw.getArea().contains(lx, ly);
		}

		/** Expects @param a in local coords. */
		public boolean intersects(final Area a) {
			if (null == aw) return a.contains(x, y);
			return M.intersects(a, aw.getArea());
		}

		@Override
		public boolean isRoughlyInside(final Rectangle localbox) {
			if (null == aw) return localbox.contains((int)x, (int)y);
			if (aw.getArea().getBounds().intersects(localbox)) return true;
			return super.isRoughlyInside(localbox);
		}

		@Override
		public Collection<Displayable> findLinkTargets(final AffineTransform aff) {
			if (null == aw) return super.findLinkTargets(aff);
			Area a = aw.getArea();
			if (!aff.isIdentity()) {
				a = a.createTransformedArea(aff);
			}
			return this.la.getDisplayables(Patch.class, a, true);
		}

		@Override
		public void apply(final mpicbg.models.CoordinateTransform ct, final Area roi) {
			// transform the point itself
			super.apply(ct, roi);
			// ... and the area
			if (null == aw) return;
			M.apply(ct, roi, aw.getArea());
		}

		@Override
		public void apply(final VectorDataTransform vdt) {
			// transform the point itself
			super.apply(vdt);
			// ... and the area
			if (null == aw) return;
			M.apply(vdt, aw.getArea());
		}
		
		@Override
		protected void transformData(final AffineTransform aff) {
			if (null == aw) return;
			aw.getArea().transform(aff);
		}
	}

	public List<Area> getAreas(final Layer layer, final Rectangle box) {
		synchronized (node_layer_map) {
			final Set<Node<Area>> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			final List<Area> a = new ArrayList<Area>();
			for (final AreaNode nd : (Collection<AreaNode>) (Collection) nodes) {
				if (null != nd.aw && nd.aw.getArea().createTransformedArea(this.at).getBounds().intersects(box)) {
					a.add(nd.aw.getArea());
				}
			}
			return a;
		}
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		final String type = "t2_areatree";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_areatree (t2_node*,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	@Override
	protected boolean exportXMLNodeAttributes(final StringBuilder indent, final StringBuilder sb, final Node<Area> node) { return true; }

	@Override
	protected boolean exportXMLNodeData(final StringBuilder indent, final StringBuilder sb, final Node<Area> node) {
		final AreaNode an = (AreaNode)node;
		//Utils.log2("Calling AreaTree.exportXMLNodeData for node " + an + " which has area: " + (null != an.aw) + " which is not empty: " + (null != an.aw ? !an.aw.getArea().isEmpty() : true));
		if (null == an.aw || an.aw.getArea().isEmpty()) {
			return true;
		}
		//Utils.log2("exporting area:");
		sb.append(indent).append("<t2_area>\n");
		indent.append(' ');
		AreaList.exportArea(sb, indent.toString(), ((AreaNode)node).aw.getArea());
		indent.setLength(indent.length() -1);
		sb.append(indent).append("</t2_area>\n");
		return true;
	}

	public boolean calculateBoundingBox(final Layer la) {
		if (null == root) return false;
		Rectangle box = null;
		synchronized (node_layer_map) {
			for (final Collection<Node<Area>> nodes : node_layer_map.values()) {
				for (final AreaNode nd : (Collection<AreaNode>) (Collection) nodes) {
					if (null == box) box = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
					else box.add((int)nd.x, (int)nd.y);
					if (null != nd.aw) box.add(nd.aw.getArea().getBounds());
				}
			}
		}
		
		this.width = box.width;
		this.height = box.height;

		if (0 == box.x && 0 == box.y) {
			return true;
		}

		final AffineTransform aff = new AffineTransform(1, 0, 0, 1, -box.x, -box.y);

		// now readjust points to make min_x,min_y be the x,y
		for (final Collection<Node<Area>> nodes : node_layer_map.values()) {
			for (final Node<Area> nd : nodes) {
				nd.translate(-box.x, -box.y); // just the x,y itself
				nd.getData().transform(aff);
			}}
		this.at.translate(box.x, box.y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.

		updateBucket(la);

		return true;
	}

	private AreaNode findEventReceiver(final Collection<Node<Area>> nodes, final int lx, final int ly, final Layer layer, final double mag, final InputEvent ie) {

		Area brush = null;
		try {
			brush = AreaWrapper.makeMouseBrush(ProjectToolbar.getBrushSize(), mag).createTransformedArea(this.at.createInverse());
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}

		// Try to find an area onto which the point intersects, or the brush diameter
		synchronized (node_layer_map) {
			AreaNode closest = null;
			double min_dist = Double.MAX_VALUE;

			for (final AreaNode an : (Collection<AreaNode>) (Collection) nodes) { // nodes are the nodes in the current layer
				if (brush.contains(an.x, an.y) || M.intersects(an.getData(), brush)) {
					return an;
				}
				if (null == an.aw) continue;
				// Look inside holes, for filling
				final Collection<Polygon> pols = M.getPolygons(an.getData());
				for (final Polygon pol : pols) {
					if (pol.contains(lx, ly)) {
						return an;
					}
				}
				// If erasing, find the closest area to the brush
				if (ie.isAltDown()) {
					for (final Polygon pol : pols) {
						for (int i=0; i<pol.npoints; i++) {
							double sqdist = Math.pow(lx - pol.xpoints[i], 2) + Math.pow(ly - pol.ypoints[i], 2);
							if (sqdist < min_dist) {
								closest = an;
								min_dist = sqdist;
							}
						}
					}
				}
			}
			if (null != closest) return closest;
		}

		// Check whether last area is suitable:
		/* // IT'S CONFUSING when there's more than one node per layer
		if (null != receiver && layer == receiver.la) {
			Rectangle srcRect = Display.getFront().getCanvas().getSrcRect();
			if (receiver.getData().createTransformedArea(this.at).intersects(srcRect)) {
				// paint on last area, its in this layer and within current view
				return receiver;
			}
		}
		*/
		return null;
	}

	private AreaNode receiver = null;

	@Override
	public void mousePressed(MouseEvent me, final Layer la, int x_p, int y_p, double mag) {
		int tool = ProjectToolbar.getToolId();
		//Utils.log2("tool is pen: " + (ProjectToolbar.PEN == tool) + "  or brush: " + (ProjectToolbar.BRUSH == tool));
		if (ProjectToolbar.PEN == tool) {
			super.mousePressed(me, la, x_p, y_p, mag);
			return;
		}

		if (null == root) return;

		final Layer layer = Display.getFrontLayer();
		final Collection<Node<Area>> nodes = node_layer_map.get(layer);
		if (null == nodes || nodes.isEmpty()) {
			return;
		}

		// Find a node onto which a click was made
		//

		if (tool == ProjectToolbar.PENCIL) {
			// Semi automatic segmentation tools
			final Area roi;
			try {
				roi = new Area(this.at.createInverse().createTransformedShape(Segmentation.fmp.getBounds(x_p, y_p)));
			} catch (NoninvertibleTransformException nite) {
				IJError.print(nite);
				return;
			}
			for (final Node<Area> nd : nodes) {
				if (nd.intersects(roi)) {
					receiver = (AreaNode)nd;
					break;
				}
			}
		} else if (tool == ProjectToolbar.BRUSH) {
			// transform the x_p, y_p to the local coordinates
			int x_pl = x_p;
			int y_pl = y_p;
			if (!this.at.isIdentity()) {
				final Point2D.Double po = inverseTransformPoint(x_p, y_p);
				x_pl = (int)po.x;
				y_pl = (int)po.y;
			}
			receiver = findEventReceiver(nodes, x_pl, y_pl, layer, mag, me);
		}

		if (null != receiver) {
			receiver.getData(); // create the AreaWrapper if not there already
			receiver.aw.setSource(this);
			receiver.aw.mousePressed(me, la, x_p, y_p, mag);
			calculateBoundingBox(la);
			receiver.aw.setSource(null);

			setLastEdited(receiver);

			//Utils.log2("receiver: " + receiver);
			//Utils.log2(" at layer: " + receiver.la);
			//Utils.log2(" area: " + receiver.getData());
		}

	}
	@Override
	public void mouseDragged(MouseEvent me, final Layer la, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
			super.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
			return;
		}
		if (null == receiver) return;
		receiver.aw.setSource(this);
		receiver.aw.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
		// no need, repaint includes the brush area//calculateBoundingBox();
		receiver.aw.setSource(null); // since a mouse released can occur outside the canvas
	}
	@Override
	public void mouseReleased(MouseEvent me, final Layer la, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
			super.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
			return;
		}
		if (null == receiver) return;
		receiver.aw.setSource(this);
		receiver.aw.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
		calculateBoundingBox(la);
		receiver.aw.setSource(null);

		updateViewData(receiver);
		receiver = null;
	}

	@Override
	public void keyPressed(KeyEvent ke) {
		final int tool = ProjectToolbar.getToolId();
		try {
			if (ProjectToolbar.BRUSH == tool) {

				Object origin = ke.getSource();
				if (! (origin instanceof DisplayCanvas)) {
					ke.consume();
					return;
				}
				DisplayCanvas dc = (DisplayCanvas)origin;
				Layer layer = dc.getDisplay().getLayer();

				final Collection<Node<Area>> nodes = node_layer_map.get(layer);
				if (null == nodes || nodes.isEmpty()) {
					return;
				}

				final Point p = dc.getCursorLoc(); // as offscreen coords
				int x = p.x;
				int y = p.y;
				if (!this.at.isIdentity()) {
					final Point2D.Double po = inverseTransformPoint(x, y);
					x = (int)po.x;
					y = (int)po.y;
				}

				AreaNode nd = findEventReceiver(nodes, x, y, layer, dc.getMagnification(), ke);

				if (null != nd && null != nd.aw) {
					nd.aw.setSource(this);
					nd.aw.keyPressed(ke, dc, layer);
					nd.aw.setSource(null);
					if (ke.isConsumed()) {
						updateViewData(nd);
						return;
					}
				}
			}
		} finally {
			if (!ke.isConsumed()) {
				super.keyPressed(ke);
			}
		}
	}

	@Override
	protected Rectangle getBounds(final Collection<Node<Area>> nodes) {
		Rectangle box = null;
		for (final AreaNode nd : (Collection<AreaNode>)(Collection)nodes) {
			final Rectangle b;
			if (null == nd.aw || nd.aw.getArea().isEmpty()) b = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
			else b = nd.aw.getArea().getBounds();
			//
			if (null == box) box = b;
			else box.add(b);
		}
		return box;
	}

	public MeshData generateMesh(final double scale, final int resample) {
		HashMap<Layer,Area> areas = new HashMap<Layer,Area>();
		synchronized (node_layer_map) {
			for (final Map.Entry<Layer,Set<Node<Area>>> e : node_layer_map.entrySet()) {
				final Area a = new Area();
				for (final AreaNode nd : (Collection<AreaNode>) (Collection) e.getValue()) {
					if (null != nd.aw) a.add(nd.aw.getArea());
				}
				areas.put(e.getKey(), a);
			}
		}
		final List<Point3f> ps = AreaUtils.generateTriangles(this, scale, resample, areas);
		
		final List<Color3f> colors = new ArrayList<Color3f>();
		// Determine colors by proximity to a node, since there isn't any other way.
		// TODO
		Utils.log("WARNING: AreaTree multicolor 3D mesh is not yet implemented.");
		final Color3f cf = new Color3f(this.color);
		for (int i=0; i<ps.size(); i++) colors.add(cf);
		
		return new MeshData(ps, colors);
	}

	public void debug() {
		for (Map.Entry<Layer,Set<Node<Area>>> e : node_layer_map.entrySet()) {
			for (Node<Area> nd : e.getValue()) {
				Area a = ((AreaNode)nd).aw.getArea();
				Utils.log2("area: " + a + "  " + (null != a ? a.getBounds() : null));
				Utils.log2(" .. and has paths: " + M.getPolygons(a).size());
			}
		}
	}

	/** Returns true if the given point falls within a certain distance of any of the treeline segments,
	 *  where a segment is defined as the line between a clicked point and the next. */
	@Override
	protected boolean isAnyNear(final Collection<Node<Area>> nodes, final float lx, final float ly, final float radius) {
		for (final Node<Area> nd : nodes) {
			final AreaNode an = (AreaNode)nd;
			if (null == an.aw && an.isNear(lx, ly, radius)) return true;
			if (an.getData().contains(lx, ly)) return true;
		}
		return false;
	}
}
