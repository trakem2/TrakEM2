/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2009 Albert Cardona.

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

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;

import ini.trakem2.imaging.LayerStack;
import ini.trakem2.Project;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import ini.trakem2.vector.VectorString3D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** A sequence of points ordered in a set of connected branches. */
public class Treeline extends ZDisplayable {

	protected Branch root;

	/** A branch only holds the first point if it doesn't have any parent. */
	private final class Branch {

		final Branch parent;
		HashMap<Integer,Branch> branches = null;

		final Polyline pline;

		Branch(Branch parent, double first_x, double first_y, long layer_id) {
			this.parent = parent;
			// Create a new Polyline with an invalid id -1:
			this.pline = new Polyline(project, -1, null, 0, 0, 1.0f, true, Treeline.this.color, false, Treeline.this.at);
			this.pline.setLayerSet(Treeline.this.layer_set);
			this.pline.addPoint((int)first_x, (int)first_y, layer_id, 1.0);
		}

		/** Create a sub-branch at index i, with new point x,y,layer_id.
		 *  @return the new child Branch. */
		final Branch fork(int i, double x, double y, long layer_id) {
			if (null == branches) branches = new HashMap<Integer,Branch>();
			Branch child = new Branch(this, x, y, layer_id);
			branches.put(i, child);
			return child;
		}

		/** Paint recursively into branches. */
		final void paint(Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
			this.pline.paint(g, magnification, active, channels, active_layer);
			if (null == branches) return;
			for (final Branch b : branches.values()) {
				b.paint(g, magnification, active, channels, active_layer);
			}
		}
		final boolean intersects(final Area area, final double z_first, final double z_last) {
			if (null != pline && pline.intersects(area, z_first, z_last)) return true;
			if (null == branches) return false;
			for (Branch b : branches.values()) if (b.intersects(area, z_first, z_last)) return true;
			return false;
		}
		final boolean linkPatches() {
			boolean must_lock = null != pline && pline.linkPatches();
			for (Branch b : branches.values()) {
				must_lock = must_lock || b.linkPatches();
			}
			return must_lock;
		}
		/** Return min_x, min_y, max_x, max_y of all nested Polyline. */
		final double[] calculateDataBoundingBox(double[] m) {
			if (null == pline) return m;
			final double[] mp = pline.calculateDataBoundingBox();
			if (null == m) {
				m = mp;
			} else {
				m[0] = Math.min(m[0], mp[0]);
				m[1] = Math.min(m[1], mp[1]);
				m[2] = Math.max(m[2], mp[2]);
				m[3] = Math.max(m[3], mp[3]);
			}
			if (null == branches) return m;
			for (final Branch b : branches) {
				m = b.calculateDataBoundingBox(m);
			}
			return m;
		}
		/** Subtract x,y from all points of all nested Polyline. */
		final void subtract(final double min_x, final double min_y) {
			if (null == pline) return;
			for (int i=0; i<pline.n_points; i++) {
				pline.p[0][i] -= min_x;
				pline.p[1][i] -= min_y;
			}
			if (null == branches) return;
			for (final Branch b : branches) {
				b.subtract(min_x, min_y);
			}
		}
		/** Return the lowest Z Layer of all nested Polyline. */
		final Layer getFirstLayer() {
			if (null == pline) return null;
			Layer first = pline.getFirstLayer();
			if (null == branches) return first;
			for (final Branch b : branches) {
				Layer la = b.getFirstLayer();
				if (la.getZ() < first.getZ()) first = la;
			}
			return first;
		}
	}

	public Treeline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
	}

	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
		// parse specific data
		for (Iterator it = ht_attr.entrySet().iterator(); it.hasNext(); ) {
			// TODO
		}
	}

	final public void paint(Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (null == root) {
			setupForDisplay();
			if (null == root) return;
		}

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		root.paint(g, magnification, active, channels, active_layer);

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	synchronized protected void calculateBoundingBox(final boolean adjust_position) {
		// Call calculateDataBoundingBox for each Branch and find out absolute min,max. All points are local to this TreeLine AffineTransform.
		if (null == root) return;
		final double[] m = root.calculateDataBoundingBox(null);
		if (null == m) return;

		this.width = m[2] - m[0];  // max_x - min_x;
		this.height = m[3] - m[1]; // max_y - min_y;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			root.subtract(m[0], m[1]);
			this.at.translate(m[0], m[1]) ; // (min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		layer_set.updateBucket(this);
	}

	public void repaint() {
		repaint(true);
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// TODO
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		return null == root ? false
				    : root.intersects(area, z_first, z_last);
	}

	public Layer getFirstLayer() {
		if (null == root) return null;
		return root.getFirstLayer();
	}

	public boolean linkPatches() {
		if (null == root) return false;
		return root.linkPatches();
	}

	public Displayable clone(final Project pr, final boolean copy_id) {
		// TODO
		return null;
	}

	public boolean isDeletable() {
		return null == root || null == root.pline;
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		// transform the x_p, y_p to the local coordinates
		int x_pd = x_p;
		int y_pd = y_p;
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		// TODO
	}
}
