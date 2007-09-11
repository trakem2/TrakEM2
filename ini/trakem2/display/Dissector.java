/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007 Albert Cardona and Rodney Douglas.

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

import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Search;
import ini.trakem2.persistence.DBObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Hashtable;
import java.util.HashSet;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.event.MouseEvent;


/** Implements the Double Dissector method with scale-invariant grouped labels.
 *  Each label is simply a cross marker with a number attached;
 *  labels are grouped; each member label of the group has the same number.
 *
 */
public class Dissector extends ZDisplayable {

	/** The list of items to count. */
	private ArrayList al_items = new ArrayList();

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

		private Item(int tag, int radius) {
			this.tag = tag;
			this.radius = radius;
			p = new double[2][2];
			p_layer = new long[2];
		}

		Item(int tag, int radius, double x, double y, Layer layer) {
			this(tag, radius);
			add(x, y, layer);
		}

		Item(int tag, int radius, String data) {
			this(tag, radius);
			// parse
			data = data.trim().replace('\n', ' ');
			data = data.substring(1, data.length() -1); // remove first and last [ ]
			final String[] si = data.split("]\\["); // this is SLOW but easy
			for (int i=0;i <si.length; i++) {
				if (n_points == p[0].length) enlargeArrays();
				int isp1 =  si[i].indexOf(' ');
				int isp2 =  si[i].lastIndexOf(' ');
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
		final int add(double x, double y, final Layer layer) {
			final long lid = layer.getId();
			// trivial case
			if (0 == n_points) {
				p[0][0] = x;
				p[1][0] = y;
				p_layer[0] = lid;
				n_points = 1;
				Utils.log2(tag + " trivial case");
				return 0;
			}
			// check if there is already a point for the given layer
			for (int i=0; i<n_points; i++)
				if (lid == p_layer[i]) {
					Utils.log2(tag + " already here");
					return -1;
				}

			final int il = layer_set.indexOf(layer);
			if (layer_set.indexOf(layer_set.getLayer(p_layer[n_points-1])) == il -1) {
				// check if new point is within radius of the found point
				Utils.log2(tag + "  point: " + x + ", " + y);
				Utils.log2(tag + "  last: " + p[0][n_points-1] + ", " + p[0][n_points-1] + "  radius: " + radius);
				if (p[0][n_points-1] + radius >= x && p[0][n_points-1] - radius <= x
				 && p[1][n_points-1] + radius >= y && p[1][n_points-1] - radius <= y) {
					// ok
				} else {
					// can't add
					Utils.log2(tag + " case append: can't add");
					return -1;
				}


				// check size
				if (n_points >= p[0].length) enlargeArrays();

				// append at the end
				p[0][n_points] = x;
				p[1][n_points] = y;
				p_layer[n_points] = lid;
				n_points++;
				Utils.log2("appending at the end, returning index = " + (n_points-1));
				return n_points-1;
			}
			if (layer_set.indexOf(layer_set.getLayer(p_layer[0])) == il +1) {
				// check if new point is within radius of the found point
				if (p[0][0] + radius >= x && p[0][0] - radius <= x
				 && p[1][0] + radius >= y && p[1][0] - radius <= y) {
					// ok
				} else {
					// can't add
					Utils.log2(tag + " case preppend: can't add");
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
			Utils.log2(tag + " invalid layer");
			return -1;
		}

		final private void enlargeArrays() {
			double[][] p2 = new double[2][n_points + 5];
			long[] l2 = new long[n_points + 5];
			System.arraycopy(p[0], 0, p2[0], 0, n_points);
			System.arraycopy(p[1], 0, p2[1], 0, n_points);
			System.arraycopy(p_layer, 0, l2, 0, n_points);
			p = p2;
			p_layer = l2;
		}
		final void paint(final Graphics2D g, final double magnification, final Layer layer) {
			final int i_current = layer_set.getLayerIndex(layer.getId());
			int ii;
			final int M_radius = radius;
			final int EXTRA = 2;
			boolean paint_current = false;
			int paint_i = -1;
			for (int i=0; i<n_points; i++) {
				ii = layer_set.getLayerIndex(p_layer[i]);
				if (ii == i_current -1) g.setColor(Color.red);
				else if (ii == i_current) {
					paint_current = true;
					paint_i = i;
					continue; // paint it last, on top of all
				}
				else if (ii == i_current + 1) g.setColor(Color.blue);
				else continue; // don't paint. Should just return, since points are in order
				final Point2D.Double po = transformPoint(p[0][i], p[1][i]);
				final int px = (int)po.x;
				final int py = (int)po.y;
				g.drawOval(px - M_radius, py - M_radius, M_radius+M_radius, M_radius+M_radius);
				g.drawString(Integer.toString(tag), px + M_radius + EXTRA, py + M_radius);
			}
			if (paint_current) {
				g.setColor(color); // the color of the Dissector
				final Point2D.Double po = transformPoint(p[0][paint_i], p[1][paint_i]);
				final int px = (int)po.x;
				final int py = (int)po.y;
				g.drawRect(px - M_radius, py - M_radius, M_radius+M_radius, M_radius+M_radius);
				g.drawString(Integer.toString(tag), px + M_radius + EXTRA, py + M_radius);
			}
		}

		final Layer getFirstLayer() {
			if (0 == n_points) return null;
			return layer_set.getLayer(p_layer[0]);
		}

		final void linkPatches() {
			final Rectangle r = new Rectangle(); // for reuse
			final double[][] po = transformPoints(p);
			for (int i=0; i<n_points; i++) {
				Layer la = layer_set.getLayer(p_layer[0]);
				for (Iterator it = la.getDisplayables(Patch.class).iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					d.getBoundingBox(r);
					if (r.contains((int)po[0][i], (int)po[1][i])) {
						link(d, true);
					}
				}
			}
		}

		/** Check whether the given point x,y falls within radius of any of the points in this Item.
		 *  Returns -1 if not found, or its index if found. */
		final int find(final long lid, int x, int y) {
			for (int i=0; i<n_points; i++) {
				if (lid == p_layer[i]
				    && p[0][i] + radius > x && p[0][i] - radius < x
				    && p[1][i] + radius > y && p[1][i] - radius < y) {
					return i;
				}
			}
			return -1;
		}

		final void translate(int index, int dx, int dy) {
			p[0][index] += dx;
			p[1][index] += dy;
		}

		final void remove(int index) {
			for (int i=index; i<n_points-1; i++) {
				p[0][i] = p[0][i+1];
				p[1][i] = p[1][i+1];
				p_layer[i] = p_layer[i+1];
			}
			n_points--;
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
		final void translateAll(int dx, int dy) {
			for (int i=0; i<n_points; i++) {
				p[0][i] += dx;
				p[1][i] += dy;
			}
		}

		final void exportXML(StringBuffer sb_body, String indent) {
			sb_body.append(indent).append("<t2_dd_item tag=\"").append(tag).append("\" radius=\"").append(radius).append("\" points=\"");
			for (int i=0; i<n_points; i++) {
				sb_body.append('[').append(p[0][i]).append(' ').append(p[1][i]).append(' ').append(p_layer[i]).append(']');
				if (n_points -1 != i) sb_body.append(' ');
			}
			sb_body.append("\" />\n");
		}
	}

	public Dissector(Project project, String title, double x, double y) {
		super(project, title, x, y);
	}

	public Dissector(Project project, long id, String title,  double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
	}
	/** Reconstruct from XML. */
	public Dissector(Project project, long id, Hashtable ht, Hashtable ht_links) {
		super(project, id, ht, ht_links);
		// individual items will be added as soon as parsed
	}

	public void paint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			((Item)it.next()).paint(g, magnification, active_layer);
		}
	}

	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer min_la = this.layer; // so a null pointer is not returned
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			Layer la = tmp.getFirstLayer();
			if (null != la && la.getZ() < min_z) {
				min_z = la.getZ();
				min_la = la;
			}
		}
		return min_la;
	}

	public void linkPatches() {
		if (0 == al_items.size()) return;
		unlinkAll(Patch.class);
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			tmp.linkPatches();
		}
	}

	public boolean contains(Layer layer, int x, int y) {
		final long lid = layer.getId();
		Point2D.Double po = inverseTransformPoint(x, y);
		x = (int)po.x;
		y = (int)po.y;
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			if (-1 != tmp.find(lid, x, y)) return true;
		}
		return false;
	}

	/** Returns a deep copy. */
	public Object clone() {
		// TODO
		Utils.log2("Cloning a Dissector not implemented yet.");
		return null;
	}

	public boolean isDeletable() {
		if (0 == al_items.size()) return true;
		return false;
	}

	/** The active item in the mouse pressed-dragged-released cycle. */
	private Item item = null;
	/** The selected label in the active item. */
	private int index = -1;

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		final int tool = ProjectToolbar.getToolId();
		if (ProjectToolbar.PEN != tool) return;

		final Layer la = Display.getFrontLayer(this.project);
		final long lid = la.getId(); // isn't this.layer pointing to the current layer always?

		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
		}

		// find if the click is within radius of an existing point for the current layer
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			index = tmp.find(lid, x_p, y_p);
			if (-1 != index) {
				this.item = tmp;
				break;
			}
		}

		if (-1 != index) {
			if (me.isShiftDown() && me.isControlDown()) {
				// delete
				item.remove(index);
				if (0 == item.n_points) al_items.remove(item);
				item = null;
				index = -1;
			}
			// in any case:
			return;
		}
		// else try to add a point to a suitable item
		// Find an item in the previous or the next layer,
		//     which falls within radius of the clicked point
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			index = tmp.add(x_p, y_p, la);
			if (-1 != index) {
				this.item = tmp;
				return;
			}
		}
		// could not be added to an existing item, so creating a new item with a new point in it
		int max_tag = 0;
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			if (tmp.tag > max_tag) max_tag = tmp.tag;
		}
		int radius = 8;
		if (al_items.size() > 0) radius = ((Item)al_items.get(al_items.size()-1)).radius;
		this.item = new Item(max_tag+1, radius, x_p, y_p, la);
		index = 0;
		al_items.add(this.item);
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
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
			calculateBoundingBox();
			Display.repaint(layer_set, this, 0);
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		this.item = null;
		this.index = -1;
		calculateBoundingBox();
	}

	/** Make points as local as possible, and set the width and height. */
	private void calculateBoundingBox() {
		Rectangle box = null;
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			if (null == box) box = tmp.getBoundingBox();
			else box.add(tmp.getBoundingBox());
		}
		// edit the AffineTransform
		this.translate(box.x, box.y, false);
		// set dimensions
		this.width = box.width;
		this.height = box.height;
		// apply new x,y position to all items
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			tmp.translateAll(-box.x, -box.y);
		}
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_dissector";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_dissector (t2_dd_item)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent); // all ATTLIST of a Displayable
		sb_header.append(indent).append("<!ELEMENT t2_dd_item EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item radius NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item tag NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_dd_item points NMTOKEN #REQUIRED>\n")
		;
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_dissector\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n");
		sb_body.append(indent).append(">\n");
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item tmp = (Item)it.next();
			tmp.exportXML(sb_body, in);
		}
		sb_body.append(indent).append("</t2_dissector>\n");
	}

	/** For reconstruction purposes from XML. */
	public void addItem(int tag, int radius, String data) {
		al_items.add(new Item(tag, radius, data));
	}
}
