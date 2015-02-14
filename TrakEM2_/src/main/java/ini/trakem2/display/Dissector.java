/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007-2009 Albert Cardona and Rodney Douglas.

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

import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


/** Implements the Double Dissector method with scale-invariant grouped labels.
 *  Each label is simply a cross marker with a number attached;
 *  labels are grouped; each member label of the group has the same number.
 *
 */
public class Dissector extends ZDisplayable implements VectorData {

	/** The list of items to count. */
	private ArrayList<Item> al_items = new ArrayList<Item>();

	/** One element to count.
	 * Each item contains one or more x,y,layer_id entries.
	 * There can only be one x,y entry per layer_id.
	 * All entries of the same item print with the same number.
	 */
	private class Item {

		/** Paired with p */
		long[] p_layer;
		/** Paired with p_layer */
		double[][] p;
		/** Current number of points */
		int n_points = 0;
		/** The numeric tag of this Item. */
		int tag;
		/** The dimensions of each box. */
		int radius;

		@Override
        public Object clone() {
			final Item copy = new Item();
			copy.tag = this.tag;
			copy.radius = radius;
			copy.n_points = this.n_points;
			copy.p = new double[2][this.p[0].length];
			System.arraycopy(this.p[0], 0, copy.p[0], 0, this.p[0].length);
			System.arraycopy(this.p[1], 0, copy.p[1], 0, this.p[1].length);
			copy.p_layer = new long[this.p_layer.length];
			System.arraycopy(this.p_layer, 0, copy.p_layer, 0, this.p_layer.length);
			return copy;
		}

		boolean intersects(final Area area, final double z_first, final double z_last) {
			for (int i=0; i<n_points; i++) {
				final Layer la = layer_set.getLayer(p_layer[i]);
				if (la.getZ() >= z_first && la.getZ() <= z_last) {
					for (int k=0; k<n_points; k++) {
						if (area.contains(p[0][k], p[1][k])) return true;
					}
				}
			}
			return false;
		}

		private Item() {}

		private Item(final int tag, final int radius) {
			this.tag = tag;
			this.radius = radius;
			p = new double[2][2];
			p_layer = new long[2];
		}

		Item(final int tag, final int radius, final double x, final double y, final Layer layer) {
			this(tag, radius);
			add(x, y, layer);
		}

		Item(final int tag, final int radius, String data) {
			this(tag, radius);
			// parse
			data = data.trim().replace('\n', ' ');
			data = data.substring(1, data.length() -1); // remove first and last [ ]
			final String[] si = data.split("] *\\["); // this is SLOW but easy
			for (int i=0;i <si.length; i++) {
				if (n_points == p[0].length) enlargeArrays();
				final int isp1 =  si[i].indexOf(' ');
				final int isp2 =  si[i].lastIndexOf(' ');
				p[0][n_points] = Double.parseDouble(si[i].substring(0, isp1));
				p[1][n_points] = Double.parseDouble(si[i].substring(isp1+1, isp2));
				p_layer[n_points] = Long.parseLong(si[i].substring(isp2+1).trim()); // Double.parseDouble trims on its own, but Long.parseLong doesn't - buh!
				n_points++;
			}
		}

		/** Returns the index of the added point only if:
		 * - there are no points, and thus this is the first;
		 * - there is no point already for that layer;
		 * - the given layer is immediately adjacent (outwards) to that of the first or the last points.
		 *
		 * Otherwise returns -1
		 *
		 */
		final int add(final double x, final double y, final Layer layer) {
			final long lid = layer.getId();
			// trivial case
			if (0 == n_points) {
				p[0][0] = x;
				p[1][0] = y;
				p_layer[0] = lid;
				n_points = 1;
				return 0;
			}
			// check if there is already a point for the given layer
			for (int i=0; i<n_points; i++)
				if (lid == p_layer[i]) {
					return -1;
				}

			final int il = layer_set.indexOf(layer);
			if (layer_set.indexOf(layer_set.getLayer(p_layer[n_points-1])) == il -1) {
				// check if new point is within radius of the found point
				if (p[0][n_points-1] + radius >= x && p[0][n_points-1] - radius <= x
				 && p[1][n_points-1] + radius >= y && p[1][n_points-1] - radius <= y) {
					// ok
				} else {
					// can't add
					//Utils.log2(tag + " case append: can't add");
					return -1;
				}


				// check size
				if (n_points >= p[0].length) enlargeArrays();

				// append at the end
				p[0][n_points] = x;
				p[1][n_points] = y;
				p_layer[n_points] = lid;
				n_points++;
				return n_points-1;
			}
			if (layer_set.indexOf(layer_set.getLayer(p_layer[0])) == il +1) {
				// check if new point is within radius of the found point
				if (p[0][0] + radius >= x && p[0][0] - radius <= x
				 && p[1][0] + radius >= y && p[1][0] - radius <= y) {
					// ok
				} else {
					// can't add
					//Utils.log2(tag + " case preppend: can't add");
					return -1;
				}

				// check size
				if (n_points >= p[0].length) enlargeArrays();

				// prepend
				// shift all points one position to the right
				for (int i=n_points-1; i>-1; i--) {
					p[0][i+1] = p[0][i];
					p[1][i+1] = p[1][i];
					p_layer[i+1] = p_layer[i];
				}
				p[0][0] = x;
				p[1][0] = y;
				p_layer[0] = lid;
				n_points++;
				return 0;
			}
			// else invalid layer
			//Utils.log2(tag + " invalid layer");
			return -1;
		}

		final private void enlargeArrays() {
			final double[][] p2 = new double[2][n_points + 5];
			final long[] l2 = new long[n_points + 5];
			System.arraycopy(p[0], 0, p2[0], 0, n_points);
			System.arraycopy(p[1], 0, p2[1], 0, n_points);
			System.arraycopy(p_layer, 0, l2, 0, n_points);
			p = p2;
			p_layer = l2;
		}
		// Expects graphics with an identity transform
		final void paint(final Graphics2D g, final AffineTransform aff, final Layer layer) {
			final int i_current = layer_set.indexOf(layer);
			int ii;
			final int M_radius = radius;
			final int EXTRA = 2;
			int paint_i = -1;
			for (int i=0; i<n_points; i++) {
				ii = layer_set.getLayerIndex(p_layer[i]);
				if (ii == i_current -1) g.setColor(Color.red);
				else if (ii == i_current) {
					paint_i = i;
					continue; // paint it last, on top of all
				}
				else if (ii == i_current + 1) g.setColor(Color.blue);
				else continue; // don't paint. Should just return, since points are in order
				// Convert point from local to world coordinates
				//Point2D.Double po = transformPoint(p[0][i], p[1][i]);
				// Convert point from world to screen coordinates
				//po = M.transform(gt, po.x, po.y);
				final Point2D.Double po = M.transform(aff, p[0][i], p[1][i]);
				final int px = (int)po.x;
				final int py = (int)po.y;
				g.drawOval(px - M_radius, py - M_radius, M_radius+M_radius, M_radius+M_radius);
				g.drawString(Integer.toString(tag), px + M_radius + EXTRA, py + M_radius);
			}
			// paint current:
			if (-1 != paint_i) {
				g.setColor(color); // the color of the Dissector
				// Convert point to world coordinates
				//Point2D.Double po = transformPoint(p[0][paint_i], p[1][paint_i]);
				// Convert point to screen coordinates
				//po = M.transform(gt, po.x, po.y);
				final Point2D.Double po = M.transform(aff, p[0][paint_i], p[1][paint_i]);
				final int px = (int)po.x;
				final int py = (int)po.y;
				g.drawRect(px - M_radius, py - M_radius, M_radius+M_radius, M_radius+M_radius);
				g.drawString(Integer.toString(tag), px + M_radius + EXTRA, py + M_radius);
			}
		}

		final void addResults(final ResultsTable rt, final Calibration cal, final double nameid) {
			for (int i=0; i<n_points; i++) {
				final Layer la = layer_set.getLayer(p_layer[i]);
				if (null == layer) {
					Utils.log("Dissector.addResults: could not find layer with id " + p_layer[i]);
					continue;
				}
				final Point2D.Double po = M.transform(Dissector.this.at, p[0][i], p[1][i]);
				rt.incrementCounter();
				rt.addLabel("units", cal.getUnit());
				rt.addValue(0, Dissector.this.id);
				rt.addValue(1, tag);
				rt.addValue(2, po.x * cal.pixelWidth);
				rt.addValue(3, po.y * cal.pixelHeight);
				rt.addValue(4, la.getZ() * cal.pixelWidth); // layer Z is in pixels
				rt.addValue(5, radius * cal.pixelWidth);
				rt.addValue(6, nameid);
			}
		}

		final Layer getFirstLayer() {
			if (0 == n_points) return null;
			return layer_set.getLayer(p_layer[0]);
		}

		/** Returns where any of the newly linked was locked. */
		final boolean linkPatches() {
			final Rectangle r = new Rectangle(); // for reuse
			final double[][] po = transformPoints(p);
			boolean must_lock = false;
			for (int i=0; i<n_points; i++) {
				final Layer la = layer_set.getLayer(p_layer[0]);
				for (final Displayable d : la.getDisplayables(Patch.class)) {
					d.getBoundingBox(r);
					if (r.contains((int)po[0][i], (int)po[1][i])) {
						link(d, true);
						must_lock = true;
					}
				}
			}
			return must_lock;
		}

		/** Check whether the given point x,y falls within radius of any of the points in this Item.
		 *  Returns -1 if not found, or its index if found. */
		final int find(final long lid, final double x, final double y, final double mag) {
			final int radius = (int)(this.radius / mag);
			for (int i=0; i<n_points; i++) {
				if (lid == p_layer[i]
				    && p[0][i] + radius > x && p[0][i] - radius < x
				    && p[1][i] + radius > y && p[1][i] - radius < y) {
					return i;
				}
			}
			return -1;
		}

		final void translate(final int index, final int dx, final int dy) {
			p[0][index] += dx;
			p[1][index] += dy;
		}

		final void remove(final int index) {
			for (int i=index; i<n_points-1; i++) {
				p[0][i] = p[0][i+1];
				p[1][i] = p[1][i+1];
				p_layer[i] = p_layer[i+1];
			}
			n_points--;
		}

		final void layerRemoved(final long lid) {
			for (int i=0; i<n_points; i++) {
				if (lid == p_layer[i]) {
					remove(i);
					i--;
				}
			}
		}

		final Rectangle getBoundingBox() {
			int x1=Integer.MAX_VALUE;
			int y1=x1,
			    x2=-x1, y2=-x1;
			for (int i=0; i<n_points; i++) {
				if (p[0][i] < x1) x1 = (int)p[0][i];
				if (p[1][i] < y1) y1 = (int)p[1][i];
				if (p[0][i] > x2) x2 = (int)Math.ceil(p[0][i]);
				if (p[1][i] > y2) y2 = (int)Math.ceil(p[1][i]);
			}
			return new Rectangle(x1 -radius, y1 -radius, x2-x1 + radius+radius, y2-y1 + radius+radius);
		}
		final void translateAll(final int dx, final int dy) {
			for (int i=0; i<n_points; i++) {
				p[0][i] += dx;
				p[1][i] += dy;
			}
		}

		final void exportXML(final StringBuilder sb_body, final String indent) {
			sb_body.append(indent).append("<t2_dd_item tag=\"").append(tag).append("\" radius=\"").append(radius).append("\" points=\"");
			for (int i=0; i<n_points; i++) {
				sb_body.append('[').append(p[0][i]).append(' ').append(p[1][i]).append(' ').append(p_layer[i]).append(']');
				if (n_points -1 != i) sb_body.append(' ');
			}
			sb_body.append("\" />\n");
		}

		final void putData(final StringBuilder sb) {
			for (int i=0; i<n_points; i++) {
				sb.append(tag).append('\t')
				   .append(radius).append('\t')
				   .append(p[0][i]).append('\t')
				   .append(p[1][i]).append('\t')
				   .append(layer_set.getLayer(p_layer[i]).getZ()).append('\n');
			}
		}
	}

	public Dissector(final Project project, final String title, final double x, final double y) {
		super(project, title, x, y);
	}

	public Dissector(final Project project, final long id, final String title,  final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
	}
	/** Reconstruct from XML. */
	public Dissector(final Project project, final long id, final HashMap<String,String> ht, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht, ht_links);
		// individual items will be added as soon as parsed
	}

	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		AffineTransform gt = null;
		Stroke stroke = null;
		AffineTransform aff = this.at;
		// remove graphics transform
		if (!"true".equals(getProject().getProperty("dissector_zoom"))) {
			gt = g.getTransform();
			g.setTransform(new AffineTransform()); // identity
			stroke = g.getStroke();
			g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
			aff = new AffineTransform(gt);
			aff.concatenate(this.at);
		}

		for (final Item item : al_items) {
			item.paint(g, aff, active_layer);
		}

		// restore
		if (null != gt) g.setTransform(gt);
		if (null != stroke) g.setStroke(stroke);
	}

	@Override
	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer min_la = this.layer; // so a null pointer is not returned
		for (final Item item : al_items) {
			final Layer la = item.getFirstLayer();
			if (null != la && la.getZ() < min_z) {
				min_z = la.getZ();
				min_la = la;
			}
		}
		return min_la;
	}

	@Override
	public boolean linkPatches() {
		if (0 == al_items.size()) return false;
		unlinkAll(Patch.class);
		boolean must_lock = false;
		for (final Item item : al_items) {
			must_lock = item.linkPatches() || must_lock;
		}
		if (must_lock) {
			setLocked(true);
			return true;
		}
		return false;
	}

	@Override
	public boolean contains(final Layer layer, double x, double y) {
		final long lid = layer.getId();
		final Point2D.Double po = inverseTransformPoint(x, y);
		x = po.x;
		y = po.y;
		for (final Item item : al_items) {
			if (-1 != item.find(lid, x, y, 1)) return true;
		}
		return false;
	}

	/** Returns a deep copy. */
	@Override
	public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Dissector copy = new Dissector(pr, nid, this.title, this.width, this.height, this.alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, (AffineTransform)this.at.clone());
		for (final Item item : this.al_items) {
			copy.al_items.add((Item)item.clone());
		}
		copy.addToDatabase();
		return copy;
	}

	@Override
	public boolean isDeletable() {
		if (0 == al_items.size()) return true;
		return false;
	}

	/** The active item in the mouse pressed-dragged-released cycle. */
	private Item item = null;
	/** The selected label in the active item. */
	private int index = -1;

	private Rectangle bbox = null;

	@Override
	public void mousePressed(final MouseEvent me, final Layer la, int x_p, int y_p, final double mag) {
		final int tool = ProjectToolbar.getToolId();
		if (ProjectToolbar.PEN != tool) return;

		final long lid = la.getId(); // isn't this.layer pointing to the current layer always?

		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
		}

		// find if the click is within radius of an existing point for the current layer
		for (final Item tmp : al_items) {
			index = tmp.find(lid, x_p, y_p, mag);
			if (-1 != index) {
				this.item = tmp;
				break;
			}
		}


		//final boolean is_zoom_invariant = "true".equals(project.getProperty("dissector_zoom"));

		// TODO: if zoom invariant, should check for nearest point. Or nearest point anyway, when deleting
		// (but also for adding a new one?)

		if (me.isShiftDown() && Utils.isControlDown(me)) {
			if (-1 != index) {
				// delete
				item.remove(index);
				if (0 == item.n_points) al_items.remove(item);
				item = null;
				index = -1;
				Display.repaint(layer_set, this, 0);
			}
			// in any case:
			return;
		}
		if (-1 != index) return;
		// else try to add a point to a suitable item
		// Find an item in the previous or the next layer,
		//     which falls within radius of the clicked point
		try {
			for (final Item tmp : al_items) {
				index = tmp.add(x_p, y_p, la);
				if (-1 != index) {
					this.item = tmp;
					return;
				}
			}
			// could not be added to an existing item, so creating a new item with a new point in it
			int max_tag = 0;
			for (final Item tmp : al_items) {
				if (tmp.tag > max_tag) max_tag = tmp.tag;
			}
			int radius = 8; //default
			if (al_items.size() > 0) radius = al_items.get(al_items.size()-1).radius;
			this.item = new Item(max_tag+1, radius, x_p, y_p, la);
			index = 0;
			al_items.add(this.item);
		} finally {
			if (null != item) {
				bbox = this.at.createTransformedShape(item.getBoundingBox()).getBounds();
				Display.repaint(layer_set, bbox);
			} else  Display.repaint(layer_set, this, 0);
		}
	}

	@Override
	public void mouseDragged(final MouseEvent me, final Layer la, final int x_p, final int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		final int tool = ProjectToolbar.getToolId();
		if (ProjectToolbar.PEN != tool) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			//final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			//x_p = (int)p.x;
			//y_p = (int)p.y;
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		if (-1 != index) {
			if (me.isShiftDown()) {
				// resize
				item.radius = (int)Math.ceil(Math.sqrt((x_d - item.p[0][index])*(x_d - item.p[0][index]) + (y_d - item.p[1][index])*(y_d - item.p[1][index])));
				if (item.radius < 1) item.radius = 1;
			} else {
				item.translate(index, x_d - x_d_old, y_d - y_d_old);
			}
			Rectangle repaint_bbox = bbox;
			final Rectangle current_bbox = this.at.createTransformedShape(item.getBoundingBox()).getBounds();
			if (null == bbox) repaint_bbox = current_bbox;
			else {
				repaint_bbox = (Rectangle)bbox.clone();
				repaint_bbox.add(current_bbox);
			}
			bbox = current_bbox;
			Display.repaint(layer_set, repaint_bbox);
		}
	}

	@Override
	public void mouseReleased(final MouseEvent me, final Layer la, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		this.item = null;
		this.index = -1;
		calculateBoundingBox(la, bbox);
	}

	@Override
	protected boolean calculateBoundingBox(final Layer la) {
		return calculateBoundingBox(la, null);
	}

	/** Make points as local as possible, and set the width and height. */
	private boolean calculateBoundingBox(final Layer la, Rectangle box) {
		for (final Item item : al_items) {
			if (null == box) box = item.getBoundingBox();
			else box.add(item.getBoundingBox());
		}
		if (null == box) {
			// no items
			this.width = this.height = 0;
			updateBucket(la);
			return true;
		}
		// edit the AffineTransform
		this.translate(box.x, box.y, false);
		// set dimensions
		this.width = box.width;
		this.height = box.height;
		// apply new x,y position to all items
		if (0 != box.x || 0 != box.y) {
			for (final Item item : al_items) item.translateAll(-box.x, -box.y);
		}
		updateBucket(la);
		return true;
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_dissector";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_dissector (").append(Displayable.commonDTDChildren()).append(",t2_dd_item)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent); // all ATTLIST of a Displayable
		sb_header.append(indent).append("<!ELEMENT t2_dd_item EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item radius NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item tag NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item points NMTOKEN #REQUIRED>\n")
		;
	}

	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		sb_body.append(indent).append("<t2_dissector\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n");
		sb_body.append(indent).append(">\n");
		for (final Item item : al_items) {
			item.exportXML(sb_body, in);
		}
		super.restXML(sb_body, in, options);
		sb_body.append(indent).append("</t2_dissector>\n");
	}

	/** For reconstruction purposes from XML. */
	public void addItem(final int tag, final int radius, final String data) {
		al_items.add(new Item(tag, radius, data));
	}

	/** Returns:
	 * - first line: the title of this dissector
	 * - second line: the number of items included in this dissector
	 *  and then a list of 5 tab-separated columns: item tag, radius, x, y, z
	 */
	@Override
	public String getInfo() {
		final StringBuilder sb = new StringBuilder("title: ").append(this.title).append("\nitems: ").append(al_items.size()).append('\n').append("tag\tradius\tx\ty\tz\n");
		for (final Item item : al_items) {
			item.putData(sb);
		}
		return sb.toString();
	}

	/** Always paint as box. TODO paint as the area of an associated ROI. */
	@Override
	public void paintSnapshot(final Graphics2D g, final Layer layer, final List<Layer> layers, final Rectangle srcRect, final double mag) {
		paintAsBox(g);
	}

	@Override
	public boolean intersects(final Area area, final double z_first, final double z_last) {
		Area ai;
		try {
			ai = area.createTransformedArea(this.at.createInverse());
		} catch (final NoninvertibleTransformException nite) {
			nite.printStackTrace();
			return false;
		}
		for (final Item item : al_items) {
			if (item.intersects(ai, z_first, z_last)) return true;
		}
		return false;
	}

	@Override
	public ResultsTable measure(ResultsTable rt) {
		if (0 == al_items.size()) return rt;
		if (null == rt) rt = Utils.createResultsTable("Dissector results", new String[]{"id", "tag", "x", "y", "z", "radius", "nameid"});
		for (final Item item : al_items) item.addResults(rt, layer_set.getCalibration(), getNameId());
		return rt;
	}

	@Override
	Class<?> getInternalDataPackageClass() {
		return DPDissector.class;
	}

	@Override
	Object getDataPackage() {
		return new DPDissector(this);
	}

	static private final class DPDissector extends Displayable.DataPackage {
		final ArrayList<Item> items;

		DPDissector(final Dissector dissector) {
			super(dissector);
			items = new ArrayList<Item>();
			for (final Item item : dissector.al_items) {
				items.add((Item)item.clone());
			}
		}
		@Override
        final boolean to2(final Displayable d) {
			super.to1(d);
			final Dissector dissector = (Dissector) d;
			final ArrayList<Item> m = new ArrayList<Item>();
			for (final Item item : items) { // no memfn ...
				m.add((Item)item.clone());
			}
			dissector.al_items = m;
			return true;
		}
	}

	/** Retain the data within the layer range, and through out all the rest. */
	@Override
	synchronized public boolean crop(final List<Layer> range) {
		final HashSet<Long> lids = new HashSet<Long>();
		for (final Layer l : range) {
			lids.add(l.getId());
		}
		for (final Item item : al_items) {
			for (int i=0; i<item.n_points; i++) {
				if (!lids.contains(item.p_layer[i])) {
					item.remove(i);
					i--;
				}
			}
		}
		calculateBoundingBox(null);
		return true;
	}

	@Override
	protected boolean layerRemoved(final Layer la) {
		super.layerRemoved(la);
		for (final Item item : al_items) item.layerRemoved(la.getId());
		return true;
	}

	@Override
	public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		double[] fp = null;
		mpicbg.models.CoordinateTransform chain = null;
		Area localroi = null;
		AffineTransform inverse = null;
		for (final Item item : al_items) {
			final long[] p_layer = item.p_layer;
			final double[][] p = item.p;
			for (int i=0; i<item.n_points; i++) {
				if (p_layer[i] == la.getId()) {
					if (null == localroi) {
						inverse = this.at.createInverse();
						localroi = roi.createTransformedArea(inverse);
					}
					if (localroi.contains(p[0][i], p[1][i])) {
						if (null == chain) {
							chain = M.wrap(this.at, ict, inverse);
							fp = new double[2];
						}
						// Transform the point
						M.apply(chain, p, i, fp);
					}
				}
			}
		}
		if (null != chain) calculateBoundingBox(la);
		return true;
	}

	@Override
	public boolean apply(final VectorDataTransform vdt) throws Exception {
		final double[] fp = new double[2];
		final VectorDataTransform vlocal = vdt.makeLocalTo(this);
		for (final Item item : al_items) {
			for (int i=0; i<item.n_points; i++) {
				if (vdt.layer.getId() == item.p_layer[i]) {
					for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
						if (rt.roi.contains(item.p[0][i], item.p[1][i])) {
							// Transform the point
							M.apply(rt.ct, item.p, i, fp);
							break;
						}
					}
				}
			}
		}
		calculateBoundingBox(vlocal.layer);
		return true;
	}

	@Override
	synchronized public Collection<Long> getLayerIds() {
		final HashSet<Long> lids = new HashSet<Long>();
		for (final Item item : al_items) lids.addAll(Utils.asList(item.p_layer, 0, item.n_points));
		return lids;
	}

	@Override
	synchronized public Area getAreaAt(final Layer layer) {
		final Area a = new Area();
		for (final Item item : al_items) {
			for (int i=0; i<item.n_points; i++) {
				if (item.p_layer[i] != layer.getId()) continue;
				a.add(new Area(new Rectangle2D.Float((float)(item.p[0][i] - item.radius), (float)(item.p[1][i] - item.radius), item.radius, item.radius)));
			}
		}
		a.transform(this.at);
		return a;
	}
}
