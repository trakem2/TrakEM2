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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;


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
		int index;

		Item(int index, double x, double y, Layer layer) {
			this.index = index;
			p = new double[2][2];
			p_layer = new long[2];
			add(x, y, layer);
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
				return 0;
			}
			// check if the given layer already contains one point
			for (int i=0; i<n_points; i++) if (lid == p_layer[i]) return -1;

			// check size
			if (n_points >= p[0].length) enlargeArrays();

			final int il = layer_set.indexOf(layer);
			if (layer_set.indexOf(layer_set.getLayer(p_layer[n_points-1])) == il -1) {
				// append at the end
				p[n_points][0] = x;
				p[n_points][1] = y;
				n_points++;
				return n_points-1;
			}
			if (layer_set.indexOf(layer_set.getLayer(p_layer[0])) == il +1) {
				// prepend
				// shift all points one position to the right
				for (int i=n_points-1; i>-1; i--) {
					p[0][i+1] = p[0][i];
					p[1][i+1] = p[1][i];
				}
				p[0][0] = x;
				p[1][0] = y;
				n_points++;
				return 0;
			}
			// else invalid layer
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
		final void paint(final Graphics2D g, final double magnification, final long lid) {
			// only one point per layer
			final int WIDTH = 15;
			for (int i=0; i<n_points; i++) {
				if (lid == p_layer[i]) {
					final Point2D.Double po = transformPoint(p[0][i], p[1][i]);
					final int px = (int)po.x;
					final int py = (int)po.y;
					g.drawLine(px, py - WIDTH/2, px, py + WIDTH/2);
					g.drawLine(px - WIDTH/2, py, px + WIDTH/2, py);
					g.drawString(Integer.toString(index), px + WIDTH/2, py + WIDTH/2);
					return;
				}
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

		/** Check whether the given point x,y falls within radius of any of the points in this Item. */
		final boolean contains(long lid, int x, int y, int radius) {
			for (int i=0; i<n_points; i++) {
				if (p[0][i] + radius > x && p[0][i] - radius < x &&
				    p[1][i] + radius > y && p[1][i] - radius < y) {
					return true;
				}
			}
			return false;
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
		final long lid = active_layer.getId();
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			((Item)it.next()).paint(g, magnification, lid);
		}
	}

	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer min_la = this.layer; // so a null pointer is not returned
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item item = (Item)it.next();
			Layer la = item.getFirstLayer();
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
			Item item = (Item)it.next();
			item.linkPatches();
		}
	}

	public boolean contains(Layer layer, int x, int y) {
		final int RADIUS = 10; // needs adjustment
		final long lid = layer.getId();
		for (Iterator it = al_items.iterator(); it.hasNext(); ) {
			Item item = (Item)it.next();
			if (item.contains(lid, x, y, RADIUS)) return true;
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

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		long lid = Display.getFrontLayer(this.project).getId(); // isn't this.layer pointing to the current layer always?
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
		}

		// 
	}
}
