package ini.trakem2.display;

import ij.measure.Calibration;
import ini.trakem2.Project;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collection;
import java.util.List;
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

public class AreaTree extends Tree implements AreaContainer {

	private boolean fill_paint = true;

	public AreaTree(Project project, String title) {
		super(project, title);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public AreaTree(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
	}

	/** For cloning purposes, does not call addToDatabase() */
	public AreaTree(final Project project, final long id, final String title, final double width, final double height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Tree newInstance() {
		return new AreaTree(project, project.getLoader().getNextId(), title, width, height, alpha, visible, color, locked, at);
	}

	@Override
	public Node newNode(float lx, float ly, Layer la) {
		return new AreaNode(lx, ly, la);
	}
	
	@Override
	public Node newNode(HashMap ht_attr) {
		return new AreaNode(ht_attr);
	}

	@Override
	public AreaTree clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		AreaTree art =  new AreaTree(pr, nid, title, width, height, alpha, visible, color, locked, at);
		art.root = this.root.clone();
		art.addToDatabase();
		return art;
	}

	static public final class AreaNode extends Node<AreaWrapper> {
		/** The Area wrapped by AreaWrapper is in local AreaTree coordinates, not in local Node coordinates. */
		private AreaWrapper aw;

		public AreaNode(final float lx, final float ly, final Layer la) {
			super(lx, ly, la);
			this.aw = new AreaWrapper();
		}
		/** To reconstruct from XML, without a layer. */
		public AreaNode(final HashMap attr) {
			super(attr);
		}

		public final Node newInstance(final float lx, final float ly, final Layer layer) {
			return new AreaNode(lx, ly, layer);
		}

		public final boolean setData(AreaWrapper aw) {
			this.aw = aw;
			return true;
		}
		public final AreaWrapper getData() { 
			if (null == this.aw) this.aw = new AreaWrapper();
			return this.aw;
		}

		@Override
		public void paintData(final Graphics2D g, final Layer active_layer, final boolean active, final Rectangle srcRect, final double magnification, final Set<Node> to_paint, final Tree tree) {
			if (active_layer != this.la || null == aw) return;

			final AffineTransform aff = new AffineTransform();
			aff.scale(magnification, magnification);
			aff.translate(-srcRect.x, -srcRect.y);
			aff.concatenate(tree.at);

			aw.paint(g, aff, ((AreaTree)tree).fill_paint, tree.getColor());
		}

		final boolean contains(final int lx, final int ly) {
			return aw.getArea().contains(lx, ly);
		}
	}

	public List<Area> getAreas(final Layer layer, final Rectangle box) {
		synchronized (node_layer_map) {
			final Set<Node> nodes = node_layer_map.get(layer);
			if (null == nodes) return null;
			final List<Area> a = new ArrayList<Area>();
			for (final AreaNode nd : (Collection<AreaNode>) (Collection) nodes) {
				if (nd.aw.getArea().createTransformedArea(this.at).getBounds().intersects(box)) {
					a.add(nd.aw.getArea());
				}
			}
			return a;
		}
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		Tree.exportDTD(sb_header, hs, indent);
		String type = "t2_areatree";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_areatree (t2_node,").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
	}

	protected boolean exportXMLNodeAttributes(StringBuffer indent, StringBuffer sb, Node node) { return true; }

	protected boolean exportXMLNodeData(StringBuffer indent, StringBuffer sb, Node node) {
		AreaNode an = (AreaNode)node;
		if (null == an.aw || an.aw.getArea().isEmpty()) return true;
		sb.append(indent).append("<t2_area>\n");
		indent.append(' ');
		AreaList.exportArea(sb, indent.toString(), ((AreaNode)node).aw.getArea());
		indent.setLength(indent.length() -1);
		sb.append(indent).append("</t2_area>\n");
		return true;
	}

	public boolean calculateBoundingBox() {
		if (null == root) return false;
		Rectangle box = null;
		synchronized (node_layer_map) {
			for (final Collection<Node> nodes : node_layer_map.values()) {
				for (final AreaNode nd : (Collection<AreaNode>) (Collection) nodes) {
					if (null == box) box = new Rectangle((int)nd.x, (int)nd.y, 1, 1);
					else box.add((int)nd.x, (int)nd.y);
					box.add(nd.aw.getArea().getBounds());
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
		for (final Collection<Node> nodes : node_layer_map.values()) {
			for (final AreaNode nd : (Collection<AreaNode>) (Collection) nodes) {
				nd.translate(-box.x, -box.y); // just the x,y itself
				nd.getData().getArea().transform(aff);
			}}
		this.at.translate(box.x, box.y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.

		if (null != layer_set) layer_set.updateBucket(this);

		return true;
	}

	private AreaNode receiver = null;

	@Override
	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
			super.mousePressed(me, x_p, y_p, mag);
			return;
		}
		if (null == root) return;

		final Layer layer = Display.getFrontLayer();
		final Collection<Node> nodes = node_layer_map.get(layer);
		if (null == nodes || nodes.isEmpty()) {
			return;
		}

		// Find a node onto which a click was made
		//
		// transform the x_p, y_p to the local coordinates
		int x_pl = x_p;
		int y_pl = y_p;
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_pl = (int)po.x;
			y_pl = (int)po.y;
		}

		AreaNode nd = (AreaNode) findNode(x_pl, y_pl, layer, mag);

		if (null == nd) {
			// Try to find an area onto which the point intersects
			synchronized (node_layer_map) {
				for (final AreaNode an : (Collection<AreaNode>) (Collection) nodes) {
					if (an.contains(x_pl, y_pl)) {
						nd = an;
						break;
					}
				}
			}
		}

		if (null == nd) {
			// Check whether last area is suitable:
			if (null != receiver && layer == receiver.la) {
				Rectangle srcRect = Display.getFront().getCanvas().getSrcRect();
				if (receiver.getData().getArea().createTransformedArea(this.at).intersects(srcRect)) {
					// paint on last area, its in this layer and within current view
				} else {
					receiver = null;
				}
			}
		} else {
			receiver = nd;
		}

		if (null != receiver) {
			receiver.getData().setSource(this);
			receiver.getData().mousePressed(me, x_p, y_p, mag);
		}
	}
	@Override
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
			super.mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
			return;
		}
		if (null == receiver) return;
		receiver.getData().mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
	}
	@Override
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
			super.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
			return;
		}
		if (null == receiver) return;
		receiver.getData().mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
		receiver.getData().setSource(null);
	}
}
