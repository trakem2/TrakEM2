/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.vecmath.Point3f;

public class Ball extends ZDisplayable implements VectorData {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked points.*/
	protected double[][] p;
	/**The array of Layers over which each point lives */
	protected long[] p_layer;
	/**The width of each point. */
	protected double[] p_width;

	/** Every new Ball will have, for its first point, the last user-adjusted radius value or the radius of the last user-selected point. */
	static private double last_radius = -1;

	/** Paint as outlines (false) or as solid areas (true; default, with a default alpha of 0.4f).*/
	private boolean fill_paint = true;

	public Ball(final Project project, final String title, final double x, final double y) {
		super(project, title, x, y);
		n_points = 0;
		p = new double[2][5];
		p_layer = new long[5]; // the ids of the layers in which each point lays
		p_width = new double[5];
		addToDatabase();
	}

	/** Construct an unloaded Ball from the database. Points will be loaded later, when needed. */
	public Ball(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Construct a Ball from an XML entry. */
	public Ball(final Project project, final long id, final HashMap<String,String> ht, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht, ht_links);
		// individual balls will be added as soon as parsed
		this.n_points = 0;
		this.p = new double[2][5];
		this.p_layer = new long[5];
		this.p_width = new double[5];

		final Object ob_data = ht.get("fill");
		try {
			if (null != ob_data) this.fill_paint = "true".equals(((String)ob_data).trim().toLowerCase()); // fails: //Boolean.getBoolean((String)ob_data);
		} catch (final Exception e) {
			Utils.log("Ball: could not read fill_paint value from XML:" + e);
		}
	}

	/** Used to add individual ball objects when parsing. */
	public void addBall(final double x, final double y, final double r, final long layer_id) {
		if (p[0].length == n_points) enlargeArrays();
		p[0][n_points] = x;
		p[1][n_points] = y;
		p_width[n_points] = r;
		p_layer[n_points] = layer_id;
		n_points++;
	}

	/**Increase the size of the arrays by 5.*/
	private void enlargeArrays() {
		//catch length
		final int length = p[0].length;
		//make copies
		final double[][] p_copy = new double[2][length + 5];
		final long[] p_layer_copy = new long[length + 5];
		final double[] p_width_copy = new double[length + 5];
		//copy values
		System.arraycopy(p[0], 0, p_copy[0], 0, length);
		System.arraycopy(p[1], 0, p_copy[1], 0, length);
		System.arraycopy(p_layer, 0, p_layer_copy, 0, length);
		System.arraycopy(p_width, 0, p_width_copy, 0, length);
		//assign them
		this.p = p_copy;
		this.p_layer = p_layer_copy;
		this.p_width = p_width_copy;
	}

	/**Find a point in an array, with a precision dependent on the magnification.*/
	protected int findPoint(final double[][] a, final int x_p, final int y_p, final double magnification, final long lid) {
		int index = -1;
		double d = (10.0D / magnification);
		if (d < 4) d = 4;
		for (int i=0; i<n_points; i++) {
			if (p_layer[i] != lid) continue;
			if ((Math.abs(x_p - a[0][i]) + Math.abs(y_p - a[1][i])) <= p_width[i]) {
				index = i;
			}
		}
		return index;
	}
	/**Remove a point.*/
	protected void removePoint(final int index) {
		// check preconditions:
		if (index < 0) {
			return;
		} else if (n_points - 1 == index) {
			//last point out
			n_points--;
		} else {
			//one point out (but not the last)
			--n_points;

			// shift all points after 'index' one position to the left:
			for (int i=index; i<n_points; i++) {
				p[0][i] = p[0][i+1];		//the +1 doesn't fail ever because the n_points has been adjusted above, but the arrays are still the same size. The case of deleting the last point is taken care above.
				p[1][i] = p[1][i+1];
				p_layer[i] = p_layer[i+1];
				p_width[i] = p_width[i+1];
			}
		}

		//later! Otherwise can't repaint properly//calculateBoundingBox(true);

		//update in database
		updateInDatabase("points");
	}

	/**Move backbone point by the given deltas.*/
	private void dragPoint(final int index, final int dx, final int dy) {
		p[0][index] += dx;
		p[1][index] += dy;
	}

	static private double getFirstWidth() {
		if (null == Display.getFront()) return 10;
		if (-1 != last_radius) return last_radius;
		return 10 / Display.getFront().getCanvas().getMagnification(); // 10 pixels in the screen
	}

	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. The width of the new point is that of the closest point after which it is inserted.*/
	protected int addPoint(final double x_p, final double y_p, final long layer_id, final double radius) {
		if (-1 == n_points) setupForDisplay(); //reload
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		//append at the end
		p[0][n_points] = x_p;
		p[1][n_points] = y_p;
		p_layer[n_points] = layer_id;
		p_width[n_points] = radius;
		index = n_points;
		//add one up
		this.n_points++;
		updateInDatabase(new StringBuilder("INSERT INTO ab_ball_points (ball_id, x, y, width, layer_id) VALUES (").append(id).append(",").append(x_p).append(",").append(y_p).append(",").append(p_width[index]).append(",").append(layer_id).append(")").toString());
		return index;
	}

	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		if (0 == n_points) return;
		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
		}
		//arrange transparency
		final Composite original_composite = g.getComposite(),
				perimeter_composite = alpha == 1.0f ? original_composite : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha),
				area_composite = fill_paint ? (alpha > 0.4f ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f) : perimeter_composite)
							    : null;

		// Clear transform and stroke
		final AffineTransform gt = g.getTransform();
		g.setTransform(DisplayCanvas.DEFAULT_AFFINE);
		final Stroke stroke = g.getStroke();
		g.setStroke(DisplayCanvas.DEFAULT_STROKE);

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[] p_width = this.p_width;

		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_width = (double[])ob[1];
		}

		final boolean color_cues = layer_set.color_cues;
		final int n_layers_color_cue = layer_set.n_layers_color_cue;
		final Color below, above;
		if (layer_set.use_color_cue_colors) {
			below = Color.red;
			above = Color.blue;
		} else {
			below = this.color;
			above = this.color;
		}

		// Paint a sliced sphere
		final double current_layer_z = active_layer.getZ();
		final int current_layer_index = layer_set.indexOf(active_layer);
		final long active_lid = active_layer.getId();
		for (int j=0; j<n_points; j++) {
			if (active_lid == p_layer[j]) {
				g.setColor(this.color);
				final int radius = (int)p_width[j];
				final int x = (int)((p[0][j] -radius -srcRect.x) * magnification),
				          y = (int)((p[1][j] -radius -srcRect.y) * magnification),
					  w = (int)(2 * radius * magnification);
				if (fill_paint) {
					g.setComposite(area_composite);
					g.fillOval(x, y, w, w);
				}
				g.setComposite(perimeter_composite);
				g.drawOval(x, y, w, w);
			} else if (color_cues) {
				boolean can_paint = -1 == n_layers_color_cue;
				final Layer layer = layer_set.getLayer(p_layer[j]); // fast: map lookup
				if (!can_paint) {
					can_paint = Math.abs(current_layer_index - layer_set.indexOf(layer)) <= n_layers_color_cue; // fast: map lookup
				}
				// Check if p_layer[j] is within the range of layers to color cue:
				//Utils.logMany2("current_layer_index: ", current_layer_index, "layer index:", layer_set.indexOf(layer), "n_layers_color_cue", n_layers_color_cue);
				if (can_paint) {
					// does the point intersect with the layer?
					final double z = layer.getZ();
					final double depth = Math.abs(current_layer_z - z);
					if (depth < this.p_width[j]) { // compare with untransformed data, in pixels!
						// intersects!
						if (z < current_layer_z) g.setColor(below);
						else g.setColor(above);
						// h^2 = sin^2 + cos^2 ---> p_width[j] is h, and sin*h is depth
						final int slice_radius = (int)(p_width[j] * Math.sqrt(1 - Math.pow(depth/p_width[j], 2)));
						final int x = (int)((p[0][j] -slice_radius -srcRect.x) * magnification),
							  y = (int)((p[1][j] -slice_radius -srcRect.y) * magnification),
							  w = (int)(2 * slice_radius * magnification);
						if (fill_paint) {
							g.setComposite(area_composite);
							g.fillOval(x, y, w, w);
						}
						g.setComposite(perimeter_composite);
						g.drawOval(x, y, w, w);
					}
				}
			}
		}

		if (active) {
			final long layer_id = active_layer.getId();
			for (int j=0; j<n_points; j++) {
				if (layer_id != p_layer[j]) continue;
				DisplayCanvas.drawScreenHandle(g, (int)((p[0][j] -srcRect.x) * magnification),
								  (int)((p[1][j] -srcRect.y) * magnification));
			}
		}

		//Transparency: fix alpha composite back to original.
		g.setComposite(original_composite);

		// Restore
		g.setTransform(gt);
		g.setStroke(stroke);
	}

	@Override
    public void keyPressed(final KeyEvent ke) {
		// TODO
	}

	/**Helper vars for mouse events. Safe as static since only one Ball will be edited at a time.*/
	static int index = -1;

	@Override
    public void mousePressed(final MouseEvent me, final Layer layer, int x_p, int y_p, final double mag) {
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			final long layer_id = layer.getId();
			if (Utils.isControlDown(me) && me.isShiftDown()) {
				index = findNearestPoint(p, p_layer, n_points, x_p, y_p, layer.getId()); // should go to an AbstractProfile or something
			} else {
				index = findPoint(p, x_p, y_p, mag, layer.getId());
			}
			if (-1 != index) {
				if (layer_id == p_layer[index]) {
					if (Utils.isControlDown(me) && me.isShiftDown() && p_layer[index] == Display.getFrontLayer().getId()) {
						removePoint(index);
						index = -1; // to prevent saving in the database twice
						repaint(false, layer);
						return;
					}
				} else index = -1; // disable if not in the front layer (so a new point will be added)
				if (-1 != index) {
					// Make the radius for newly added balls that of the last selected
					last_radius = p_width[index];
				}
			}
			if (-1 == index) {
				index = addPoint(x_p, y_p, layer_id, (0 == n_points ? Ball.getFirstWidth() : p_width[n_points -1])); // either 10 screen pixels or the same as the last point
				repaint(false, layer);
			}
		}
	}

	@Override
    public void mouseDragged(final MouseEvent me, final Layer layer, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			if (-1 != index) {
				if (me.isShiftDown()) {
					p_width[index] = Math.sqrt((x_d - p[0][index])*(x_d - p[0][index]) + (y_d - p[1][index])*(y_d - p[1][index]));
					last_radius = p_width[index];
					Utils.showStatus("radius: " + p_width[index], false);
				} else {
					dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				}
				repaint(false, layer);
			}
		}
	}

	@Override
    public void mouseReleased(final MouseEvent me, final Layer layer, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {

		//update points in database if there was any change
		if (-1 != index && index != n_points) { // don't do it when the last point is removed
			// NEEDS to be able to identify each point separately!! Needs an id, or an index as in pipe!! //updateInDatabase(getUpdatePointForSQL(index));
			updateInDatabase("points"); // delete and add all again. TEMPORARY
		}
		if (-1 != index) {
			//later!//calculateBoundingBox(true);
			updateInDatabase("transform+dimensions");
		}

		// reset
		index = -1;
		repaint(true, layer);
	}

	@Override
	protected boolean calculateBoundingBox(final Layer la) {
		calculateBoundingBox(true, la);
		return true;
	}

	/** Uses the @param layer to update a specific Bucket for that layer. */
	private void calculateBoundingBox(final boolean adjust_position, final Layer la) {
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;
		if (0 == n_points) {
			this.width = this.height = 0;
			updateBucket(la);
			return;
		}
		if (0 != n_points) {
			for (int i=0; i<n_points; i++) {
				if (p[0][i] - p_width[i] < min_x) min_x = p[0][i] - p_width[i];
				if (p[1][i] - p_width[i] < min_y) min_y = p[1][i] - p_width[i];
				if (p[0][i] + p_width[i] > max_x) max_x = p[0][i] + p_width[i];
				if (p[1][i] + p_width[i] > max_y) max_y = p[1][i] + p_width[i];
			}
		}
		this.width = (float)(max_x - min_x);
		this.height = (float)(max_y - min_y);

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (int i=0; i<n_points; i++) {
				p[0][i] -= min_x;	p[1][i] -= min_y;
			}
			this.at.translate(min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform+dimensions");
		} else {
			updateInDatabase("dimensions");
		}

		updateBucket(la);
	}

	/**Release all memory resources taken by this object.*/
	@Override
    public void destroy() {
		super.destroy();
		p = null;
		p_layer = null;
		p_width = null;
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Profile. */
	public void repaint(final boolean repaint_navigator, final Layer layer) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		final Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true, layer);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	private void setupForDisplay() {
		// load points
		if (null == p) {
			final ArrayList<?> al = project.getLoader().fetchBallPoints(id);
			n_points = al.size();
			p = new double[2][n_points];
			p_layer = new long[n_points];
			p_width = new double[n_points];
			final Iterator<?> it = al.iterator();
			int i = 0;
			while (it.hasNext()) {
				final Object[] ob = (Object[])it.next();
				p[0][i] = ((Double)ob[0]).doubleValue();
				p[1][i] = ((Double)ob[1]).doubleValue();
				p_width[i] = ((Double)ob[2]).doubleValue();
				p_layer[i] = ((Long)ob[3]).longValue();
				i++;
			}
		}
	}
	/**Release memory resources used by this object: namely the arrays of points, which can be reloaded with a call to setupForDisplay()*/
	public void flush() {
		p = null;
		p_width = null;
		p_layer = null;
		n_points = -1; // flag that points exist
	}

	/** The exact perimeter of this Ball, in integer precision. */
	@Override
    public Polygon getPerimeter() {
		if (-1 == n_points) setupForDisplay();

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[] p_width = this.p_width;

		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_width = (double[])ob[1];
		}
		if (-1 != index) {
			// the box of the selected point
			return new Polygon(new int[]{(int)(p[0][index] - p_width[index]), (int)(p[0][index] + p_width[index]), (int)(p[0][index] + p_width[index]), (int)(p[0][index] - p_width[index])}, new int[]{(int)(p[1][index] - p_width[index]), (int)(p[1][index] + p_width[index]), (int)(p[1][index] + p_width[index]), (int)(p[1][index] - p_width[index])}, 4);
		} else {
			// the whole box
			return super.getPerimeter();
		}
	}
	/** Writes the data of this object as a Ball object in the .shapes file represented by the 'data' StringBuffer. */
	public void toShapesFile(final StringBuffer data, final String group, final String color, final double z_scale) {
		if (-1 == n_points) setupForDisplay();
		// TEMPORARY FIX: sort balls by layer_id (by Z, which is roughly the same)
		final HashMap<Long,StringBuffer> ht = new HashMap<Long,StringBuffer>();
		final char l = '\n';
		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[] p_width = this.p_width;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_width = (double[])ob[1];
		}
		final StringBuffer sb = new StringBuffer();
		sb.append("type=ball").append(l)
		  .append("name=").append(project.getMeaningfulTitle(this)).append(l)
		  .append("group=").append(group).append(l)
		  .append("color=").append(color).append(l)
		  .append("supergroup=").append("null").append(l)
		  .append("supercolor=").append("null").append(l)
		  .append("in slice=")
		;
		StringBuffer tmp = null;
		for (int i=0; i<n_points; i++) {
			final Long layer_id = new Long(p_layer[i]);
			// Doesn't work ??//if (ht.contains(layer_id)) tmp = (StringBuffer)ht.get(layer_id);
			for (final Map.Entry<Long,StringBuffer> e : ht.entrySet()) {
				if (e.getKey().longValue() == p_layer[i]) {
					tmp = e.getValue();
				}
			}
			if (null == tmp) {
			//else {
				tmp = new StringBuffer(sb.toString()); // can't clone ?!?
				tmp.append(layer.getParent().getLayer(p_layer[i]).getZ() * z_scale).append(l);
				ht.put(layer_id, tmp);
			}
			tmp.append("x").append(p[0][i]).append(l)
			   .append("y").append(p[1][i]).append(l)
			   .append("r").append(p_width[i]).append(l)
			;
			tmp = null;
		}
		for (final StringBuffer s : ht.values()) {
			data.append(s).append(l);

			Utils.log("s : " + s.toString());
		}
	}

	/** Return the list of query statements needed to insert all the points in the database. */
	public String[] getPointsForSQL() {
		final String[] sql = new String[n_points];
		for (int i=0; i<n_points; i++) {
			final StringBuilder sb = new StringBuilder("INSERT INTO ab_ball_points (ball_id, x, y, width, layer_id) VALUES (");
			sb.append(this.id).append(",")
			  .append(p[0][i]).append(",")
			  .append(p[1][i]).append(",")
			  .append(p_width[i]).append(",")
			  .append(p_layer[i])
			  .append(")");
			; //end
			sql[i] = sb.toString();
		}
		return sql;
	}

	@Override
    public boolean isDeletable() {
		return 0 == n_points;
	}

	/** Test whether the Ball contains the given point at the given layer. What it does: and tests whether the point is contained in any of the balls present in the given layer. */
	@Override
	public boolean contains(final Layer layer, double x, double y) {
		if (-1 == n_points) setupForDisplay(); // reload points
		if (0 == n_points) return false;
		// make x,y local
		final Point2D.Double po = inverseTransformPoint(x, y);
		x = po.x;
		y = po.y;
		//
		final long layer_id = layer.getId();
		for (int i=0; i<n_points; i++) {
			if (layer_id != p_layer[i]) continue;
			if (x >= p[0][i] - p_width[i] && x <= p[0][i] + p_width[i] && y >= p[1][i] - p_width[i] && y <= p[1][i] + p_width[i]) return true;
		}
		return false;
	}

	/** Get the perimeter of all parts that show in the given layer (as defined by its Z), but representing each ball as a square in a Rectangle object. Returns null if none found. */
	private Rectangle[] getSubPerimeters(final Layer layer) {
		final ArrayList<Rectangle> al = new ArrayList<Rectangle>();
		final long layer_id = layer.getId();
		double[][] p = this.p;
		double[] p_width = this.p_width;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_width = (double[])ob[1];
		}
		for (int i=0; i<n_points; i++) {
			if (layer_id != p_layer[i]) continue;
			al.add(new Rectangle((int)(p[0][i] - p_width[i]), (int)(p[1][i] - p_width[i]), (int)Math.ceil(p_width[i] + p_width[i]), (int)Math.ceil(p_width[i] + p_width[i]))); // transformRectangle returns a copy of the Rectangle
		}
		if (al.isEmpty()) return null;
		else {
			final Rectangle[] rects = new Rectangle[al.size()];
			al.toArray(rects);
			return rects;
		}
	}

	@Override
    public boolean linkPatches() {
		// find the patches that don't lay under other profiles of this profile's linking group, and make sure they are unlinked. This will unlink any Patch objects under this Profile:
		unlinkAll(Patch.class);

		// scan the Display and link Patch objects that lay under this Profile's bounding box:

		// catch all displayables of the current Layer
		final ArrayList<Displayable> al = layer.getDisplayables(Patch.class);

		// this bounding box as in the present layer
		final Rectangle[] perimeters = getSubPerimeters(layer); // transformed
		if (null == perimeters) return false;

		boolean must_lock = false;

		// for each Patch, check if it underlays this profile's bounding box
		final Rectangle box = new Rectangle(); // as tmp
		for (final Displayable displ : al) {
			// stupid java, Polygon cannot test for intersection with another Polygon !! //if (perimeter.intersects(displ.getPerimeter())) // TODO do it yourself: check if a Displayable intersects another Displayable
			for (int i=0; i<perimeters.length; i++) {
				if (perimeters[i].intersects(displ.getBoundingBox(box))) {
					// Link the patch
					this.link(displ);
					if (displ.locked) must_lock = true;
					break;
				}
			}
		}

		// set the locked flag to this and all linked ones
		if (must_lock && !locked) {
			setLocked(true);
			return true;
		}

		return false;
	}

	/** Returns the layer of lowest Z coordinate where this ZDisplayable has a point in, or the creation layer if no points yet. */
	@Override
    public Layer getFirstLayer() {
		if (0 == n_points) return this.layer;
		if (-1 == n_points) setupForDisplay(); //reload
		Layer la = this.layer;
		final double z = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			final Layer layer = layer_set.getLayer(p_layer[i]);
			if (layer.getZ() < z) la = layer;
		}
		return la;
	}

	/** Returns the raw data for the balls, sorted by Layer ID versus double[]{z,y,r} . */
	public Map<Layer,double[]> getRawBalls() {
		if (-1 == n_points) setupForDisplay(); // reload
		final HashMap<Layer,double[]> m = new HashMap<Layer,double[]>();
		for (int i=0; i<n_points; i++) {
			m.put(layer_set.getLayer(p_layer[i]), new double[]{p[0][i], p[1][i], p_width[i]});
		}
		return m;
	}

	/** Returns a [n_points][4] array, with x,y,z,radius on the second part; not transformed, but local!
	 *  To obtain balls in world coordinates, calibrated, use getWorldBalls().
	 */
	public double[][] getBalls() {
		if (-1 == n_points) setupForDisplay(); // reload
		final double[][] b = new double[n_points][4];
		for (int i=0; i<n_points; i++) {
			b[i][0] = p[0][i];
			b[i][1] = p[1][i];
			b[i][2] = layer_set.getLayer(p_layer[i]).getZ();
			b[i][3] = p_width[i];
		}
		return b;
	}

	/** Returns a [n_points][4] array, with x,y,z,radius on the second part, in world coordinates (that is, transformed with this AffineTransform and calibrated with the containing LayerSet's calibration). */
	public double[][] getWorldBalls() {
		if (-1 == n_points) setupForDisplay(); // reload
		final double[][] b = new double[n_points][4];
		final Calibration cal = getLayerSet().getCalibrationCopy();
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		for (int i=0; i<n_points; i++) {
			final Point2D.Double po = transformPoint(p[0][i], p[1][i]); // bring to world coordinates
			b[i][0] = po.x * cal.pixelWidth;
			b[i][1] = po.y * cal.pixelHeight;
			b[i][2] = layer_set.getLayer(p_layer[i]).getZ() * cal.pixelWidth * sign;
			b[i][3] = p_width[i] * cal.pixelWidth;
		}
		return b;
	}

	/** Returns a Point3f for every x,y,z ball, in calibrated world space. */
	public List<Point3f> asWorldPoints() {
		final ArrayList<Point3f> ps = new ArrayList<Point3f>();
		for (final double[] d : getWorldBalls()) {
			ps.add(new Point3f((float)d[0], (float)d[1], (float)d[2]));
		}
		return ps;
	}

	@Override
    public void exportSVG(final StringBuffer data, final double z_scale, final String indent) {
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		final String in = indent + "\t";
		final String[] RGB = Utils.getHexRGBColor(color);
		final double[] a = new double[6];
		at.getMatrix(a);
		data.append(indent).append("<ball_ob\n>")
		    .append(in).append("id=\"").append(id).append("\"")
		    .append(in).append("transform=\"matrix(").append(a[0]).append(',')
								.append(a[1]).append(',')
								.append(a[2]).append(',')
								.append(a[3]).append(',')
								.append(a[4]).append(',')
								.append(a[5]).append(")\"\n")
		    .append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n")
		    .append(in).append("links=\"")
		;
		if (null != hs_linked && 0 != hs_linked.size()) {
			int ii = 0;
			final int len = hs_linked.size();
			for (final Displayable d : hs_linked) {
				data.append(d.getId());
				if (ii != len-1) data.append(",");
				ii++;
			}
		}
		data.append("\"\n")
		    .append(indent).append(">\n");
		for (int i=0; i<n_points; i++) {
			data.append(in).append("<ball x=\"").append(p[0][i]).append("\" y=\"").append(p[1][0]).append("\" z=\"").append(layer_set.getLayer(p_layer[i]).getZ() * z_scale).append("\" r=\"").append(p_width[i]).append("\" />\n");
		}
		data.append(indent).append("</ball_ob>\n");
	}

	/** Similar to exportSVG but the layer_id is saved instead of the z. The convention is my own, a ball_ob that contains ball objects and links. */
	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		if (-1 == n_points) setupForDisplay(); // reload
		//if (0 == n_points) return;
		final String in = indent + "\t";
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(indent).append("<t2_ball\n");
		super.exportXML(sb_body, in, options);
		if (!fill_paint) sb_body.append(in).append("fill=\"").append(fill_paint).append("\"\n"); // otherwise no need
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n")
		;
		sb_body.append(indent).append(">\n");
		for (int i=0; i<n_points; i++) {
			sb_body.append(in).append("<t2_ball_ob x=\"").append(p[0][i]).append("\" y=\"").append(p[1][i]).append("\" layer_id=\"").append(p_layer[i]).append("\" r=\"").append(p_width[i]).append("\" />\n");
		}
		super.restXML(sb_body, in, options);
		sb_body.append(indent).append("</t2_ball>\n");
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_ball";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_ball (").append(Displayable.commonDTDChildren()).append(",t2_ball_ob)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append("<!ATTLIST t2_ball fill NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ELEMENT t2_ball_ob EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_ball_ob x NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_ball_ob y NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_ball_ob r NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_ball_ob layer_id NMTOKEN #REQUIRED>\n")
		;
	}

	/** */ // this may be inaccurate
	@Override
	public boolean paintsAt(final Layer layer) {
		if (!super.paintsAt(layer)) return false;
		// find previous and next
		final long lid_previous = layer_set.previous(layer).getId(); // never null, may be the same though
		final long lid_next = layer_set.next(layer).getId(); // idem
		final long lid = layer.getId();
		for (int i=0; i<p_layer.length; i++) {
			if (lid == p_layer[i] || lid_previous == p_layer[i] || lid_next == p_layer[i]) return true;
		}
		return false;
	}

	/** Returns information on the number of ball objects per layer. */
	@Override
    public String getInfo() {
		// group balls by layer
		final HashMap<Long,ArrayList<Integer>> ht = new HashMap<Long,ArrayList<Integer>>();
		for (int i=0; i<n_points; i++) {
			ArrayList<Integer> al = ht.get(new Long(p_layer[i]));
			if (null == al) {
				al = new ArrayList<Integer>();
				ht.put(p_layer[i], al);
			}
			al.add(i);
		}
		int total = 0;
		final StringBuilder sb1 = new StringBuilder("Ball id: ").append(this.id).append('\n');
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<Long,ArrayList<Integer>> entry : ht.entrySet()) {
			final long lid = entry.getKey().longValue();
			final ArrayList<Integer> al = entry.getValue();
			sb.append("\tLayer ").append(this.layer_set.getLayer(lid).toString()).append(":\n");
			sb.append("\t\tcount : ").append(al.size()).append('\n');
			total += al.size();
			double average = 0;
			for (final Integer i : al) {
				average += p_width[i.intValue()];
			}
			sb.append("\t\taverage radius: ").append(average / al.size()).append('\n');
		}
		return sb1.append("Total count: ").append(total).append('\n').append(sb).toString();
	}

	/** Performs a deep copy of this object, without the links. */
	@Override
    public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Ball copy = new Ball(pr, nid, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, (AffineTransform)this.at.clone());
		// links are left null
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_layer = (long[])this.p_layer.clone();
		copy.p_width = (double[])this.p_width.clone();
		copy.addToDatabase();
		return copy;
	}


	/** Generate a globe of radius 1.0 that can be used for any Ball. First dimension is Z, then comes a double array x,y. Minimal accepted meridians and parallels is 3.*/
	static public double[][][] generateGlobe(int meridians, int parallels) {
		if (meridians < 3) meridians = 3;
		if (parallels < 3) parallels = 3;
		/* to do: 2 loops:
		-first loop makes horizontal circle using meridian points.
		-second loop scales it appropiately and makes parallels.
		Both loops are common for all balls and so should be done just once.
		Then this globe can be properly translocated and resized for each ball.
		*/
		// a circle of radius 1
		double angle_increase = 2*Math.PI / meridians;
		double temp_angle = 0;
		final double[][] xy_points = new double[meridians+1][2];    //plus 1 to repeat last point
		xy_points[0][0] = 1;     // first point
		xy_points[0][1] = 0;
		for (int m=1; m<meridians; m++) {
			temp_angle = angle_increase*m;
			xy_points[m][0] = Math.cos(temp_angle);
			xy_points[m][1] = Math.sin(temp_angle);
		}
		xy_points[xy_points.length-1][0] = 1; // last point
		xy_points[xy_points.length-1][1] = 0;

		// Build parallels from circle
		angle_increase = Math.PI / parallels;   // = 180 / parallels in radians
		//final double angle90 = Math.toRadians(90);
		final double[][][] xyz = new double[parallels+1][xy_points.length][3];
		for (int p=1; p<xyz.length-1; p++) {
			final double radius = Math.sin(angle_increase*p);
			final double Z = Math.cos(angle_increase*p);
			for (int mm=0; mm<xyz[0].length-1; mm++) {
				//scaling circle to appropriate radius, and positioning the Z
				xyz[p][mm][0] = xy_points[mm][0] * radius;
				xyz[p][mm][1] = xy_points[mm][1] * radius;
				xyz[p][mm][2] = Z;
			}
			xyz[p][xyz[0].length-1][0] = xyz[p][0][0];  //last one equals first one
			xyz[p][xyz[0].length-1][1] = xyz[p][0][1];
			xyz[p][xyz[0].length-1][2] = xyz[p][0][2];
		}

		// south and north poles
		for (int ns=0; ns<xyz[0].length; ns++) {
			xyz[0][ns][0] = 0;	//south pole
			xyz[0][ns][1] = 0;
			xyz[0][ns][2] = 1;
			xyz[xyz.length-1][ns][0] = 0;    //north pole
			xyz[xyz.length-1][ns][1] = 0;
			xyz[xyz.length-1][ns][2] = -1;
		}

		return xyz;
	}


	/** Put all balls as a single 'mesh'; the returned list contains all faces as three consecutive Point3f. The mesh is also translated by x,y,z of this Displayable.*/
	public List<Point3f> generateTriangles(final double scale, final double[][][] globe) {
		try {
			Class.forName("javax.vecmath.Point3f");
		} catch (final ClassNotFoundException cnfe) {
			Utils.log("Java3D is not installed.");
			return null;
		}
		final Calibration cal = layer_set.getCalibrationCopy();
		// modify the globe to fit each ball's radius and x,y,z position
		final ArrayList<Point3f> list = new ArrayList<Point3f>();
		// transform points
		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[] p_width = this.p_width;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_width = (double[])ob[1];
		}
		final int sign = cal.pixelDepth < 0 ? -1 : 1;
		// for each ball
		for (int i=0; i<n_points; i++) {
			// create local globe for the ball, and translate it to z,y,z
			final double[][][] ball = new double[globe.length][globe[0].length][3];
			for (int z=0; z<ball.length; z++) {
				for (int k=0; k<ball[0].length; k++) {
					// the line below says: to each globe point, multiply it by the radius of the particular ball, then translate to the ball location, then translate to this Displayable's location, then scale to the Display3D scale.
					ball[z][k][0] = (globe[z][k][0] * p_width[i] + p[0][i]) * scale * cal.pixelWidth;
					ball[z][k][1] = (globe[z][k][1] * p_width[i] + p[1][i]) * scale * cal.pixelHeight;
					ball[z][k][2] = (globe[z][k][2] * p_width[i] + layer_set.getLayer(p_layer[i]).getZ()) * scale * cal.pixelWidth * sign; // not pixelDepth, see day notes 20080227. Because pixelDepth is in microns/px, not in px/microns, and the z coord here is taken from the z of the layer, which is in pixels.
				}
			}
			// create triangular faces and add them to the list
			for (int z=0; z<ball.length-1; z++) { // the parallels
				for (int k=0; k<ball[0].length -1; k++) { // meridian points
					// half quadrant (a triangle)
					list.add(new Point3f((float)ball[z][k][0], (float)ball[z][k][1], (float)ball[z][k][2]));
					list.add(new Point3f((float)ball[z+1][k+1][0], (float)ball[z+1][k+1][1], (float)ball[z+1][k+1][2]));
					list.add(new Point3f((float)ball[z+1][k][0], (float)ball[z+1][k][1], (float)ball[z+1][k][2]));
					// the other half quadrant
					list.add(new Point3f((float)ball[z][k][0], (float)ball[z][k][1], (float)ball[z][k][2]));
					list.add(new Point3f((float)ball[z][k+1][0], (float)ball[z][k+1][1], (float)ball[z][k+1][2]));
					list.add(new Point3f((float)ball[z+1][k+1][0], (float)ball[z+1][k+1][1], (float)ball[z+1][k+1][2]));
				}
				// the Point3f could be initialized through reflection, by getting the Construntor from the Class and calling new Instance(new Object[]{new Double(x), new Double(y), new Double(z)), so it would compile even in the absence of java3d
			}
		}
		return list;
	}


	private final Object[] getTransformedData() {
		return getTransformedData(null);
	}

	/** Apply the AffineTransform to a copy of the points and return the arrays. */
	private final Object[] getTransformedData(final AffineTransform additional) {
		// transform points
		final double[][] p = transformPoints(this.p, additional);
		// create points to represent the point where the radius ends. Since these are abstract spheres, there's no need to consider a second point that would provide the shear. To capture both the X and Y axis deformations, I use a diagonal point which sits at (x,y) => (p[0][i] + p_width[i], p[1][i] + p_width[i])
		double[][] pw = new double[2][n_points];
		for (int i=0; i<n_points; i++) {
			pw[0][i] = this.p[0][i] + p_width[i]; //built relative to the untransformed points!
			pw[1][i] = this.p[1][i] + p_width[i];
		}
		pw = transformPoints(pw, additional);
		final double[] p_width = new double[n_points];
		for (int i=0; i<n_points; i++) {
			// plain average of differences in X and Y axis, relative to the transformed points.
			p_width[i] = (Math.abs(pw[0][i] - p[0][i]) + Math.abs(pw[1][i] - p[1][i])) / 2;
		}
		return new Object[]{p, p_width};
	}

	/** @param area is expected in world coordinates. */
	@Override
    public boolean intersects(final Area area, final double z_first, final double z_last) {
		// find lowest and highest Z
		double min_z = Double.MAX_VALUE;
		double max_z = 0;
		for (int i=0; i<n_points; i++) {
			final double laz =layer_set.getLayer(p_layer[i]).getZ();
			if (laz < min_z) min_z = laz;
			if (laz > max_z) max_z = laz;
		}
		if (z_last < min_z || z_first > max_z) return false;
		// check the roi
		for (int i=0; i<n_points; i++)  {
			final Rectangle[] rec = getSubPerimeters(layer_set.getLayer(p_layer[i]));
			for (int k=0; k<rec.length; k++) {
				final Area a = new Area(rec[k]); // subperimeters already in world coords
				a.intersect(area);
				final Rectangle r = a.getBounds();
				if (0 != r.width && 0 != r.height) return true;
			}
		}
		return false;
	}

	@Override
	synchronized public Area getAreaAt(final Layer layer) {
		final Area a = new Area();
		for (int i=0; i<n_points; i++) {
			if (p_layer[i] != layer.getId()) continue;
			a.add(new Area(new Ellipse2D.Float((float)(p[0][i] - p_width[i]/2), (float)(p[1][i] - p_width[i]/2), (float)p_width[i], (float)p_width[i])));
		}
		a.transform(this.at);
		return a;
	}

	@Override
	protected boolean isRoughlyInside(final Layer layer, final Rectangle r) {
		if (0 == n_points) return false;
		try {
			final Rectangle box = this.at.createInverse().createTransformedShape(r).getBounds();
			for (int i=0; i<n_points; i++) {
				if (box.contains(p[0][i], p[1][i])) return true;
			}
		} catch (final NoninvertibleTransformException nite) {
			IJError.print(nite);
		}
		return false;
	}

	/** Returns a listing of all balls contained here, one per row with index, x, y, z, and radius, all calibrated.
	 * 'name-id' is a column that displays the title of this Ball object only when such title is purely a number.
	 */
	@Override
	public ResultsTable measure(ResultsTable rt) {
		if (-1 == n_points) setupForDisplay(); //reload
		if (0 == n_points) return rt;
		if (null == rt) rt = Utils.createResultsTable("Ball results", new String[]{"id", "index", "x", "y", "z", "radius", "name-id"});
		final Object[] ob = getTransformedData();
		final double[][] p = (double[][])ob[0];
		final double[] p_width = (double[])ob[1];
		final Calibration cal = layer_set.getCalibration();
		for (int i=0; i<n_points; i++) {
			rt.incrementCounter();
			rt.addLabel("units", cal.getUnit());
			rt.addValue(0, this.id);
			rt.addValue(1, i+1);
			rt.addValue(2, p[0][i] * cal.pixelWidth);
			rt.addValue(3, p[1][i] * cal.pixelHeight);
			rt.addValue(4, layer_set.getLayer(p_layer[i]).getZ() * cal.pixelWidth);
			rt.addValue(5, p_width[i] * cal.pixelWidth);
			rt.addValue(6, getNameId());
		}
		return rt;
	}

	@Override
	Class<?> getInternalDataPackageClass() {
		return DPBall.class;
	}

	@Override
	Object getDataPackage() {
		return new DPBall(this);
	}

	static private final class DPBall extends Displayable.DataPackage {
		final double[][] p;
		final double[] p_width;
		final long[] p_layer;

		DPBall(final Ball ball) {
			super(ball);
			// store copies of all arrays
			this.p = new double[][]{Utils.copy(ball.p[0], ball.n_points), Utils.copy(ball.p[1], ball.n_points)};
			this.p_width = Utils.copy(ball.p_width, ball.n_points);
			this.p_layer = new long[ball.n_points]; System.arraycopy(ball.p_layer, 0, this.p_layer, 0, ball.n_points);
		}
		@Override
        final boolean to2(final Displayable d) {
			super.to1(d);
			final Ball ball = (Ball)d;
			final int len = p[0].length; // == n_points, since it was cropped on copy
			ball.p = new double[][]{Utils.copy(p[0], len), Utils.copy(p[1], len)};
			ball.n_points = p[0].length;
			ball.p_layer = new long[len]; System.arraycopy(p_layer, 0, ball.p_layer, 0, len);
			ball.p_width = Utils.copy(p_width, len);
			return true;
		}
	}

	/** Retain the data within the layer range, and throw out all the rest. */
	@Override
    synchronized public boolean crop(final List<Layer> range) {
		if (-1 == n_points) setupForDisplay();
		final HashSet<Long> lids = new HashSet<Long>();
		for (final Layer l : range) {
			lids.add(l.getId());
		}
		for (int i=0; i<n_points; i++) {
			if (!lids.contains(p_layer[i])) {
				removePoint(i);
				i--;
			}
		}
		calculateBoundingBox(true, null);
		return true;
	}

	@Override
    synchronized protected boolean layerRemoved(final Layer la) {
		super.layerRemoved(la);
		for (int i=0; i<p_layer.length; i++) {
			if (la.getId() == p_layer[i]) {
				removePoint(i);
				i--;
			}
		}
		return true;
	}

	@Override
    synchronized public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		double[] fp = null;
		mpicbg.models.CoordinateTransform chain = null;
		Area localroi = null;
		AffineTransform inverse = null;
		for (int i=0; i<n_points; i++) {
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
					// Keep point copy
					final double ox = p[0][i],
					       oy = p[1][i];
					// Transform the point
					M.apply(chain, p, i, fp);
					// For radius, assume it's a point to the right of the center point
					fp[0] = (float)(ox + p_width[i]);
					fp[1] = (float)oy;
					chain.applyInPlace(fp);
					p_width[i] = Math.abs(fp[0] - p[0][i]);
				}
			}
		}
		if (null != chain) calculateBoundingBox(true, la); // may be called way too many times, but avoids lots of headaches.
		return true;
	}
	@Override
    public boolean apply(final VectorDataTransform vdt) throws Exception {
		final double[] fp = new double[2];
		final VectorDataTransform vlocal = vdt.makeLocalTo(this);
		for (int i=0; i<n_points; i++) {
			if (vlocal.layer.getId() == p_layer[i]) {
				for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
					if (rt.roi.contains(p[0][i], p[1][i])) {
						// Keep point copy
						final double ox = p[0][i],
						       oy = p[1][i];
						// Transform the point
						M.apply(rt.ct, p, i, fp);
						// For radius, assume it's a point to the right of the center point
						fp[0] = (float)(ox + p_width[i]);
						fp[1] = (float)oy;
						rt.ct.applyInPlace(fp);
						p_width[i] = Math.sqrt(Math.pow(fp[0] - p[0][i], 2) + Math.pow(fp[1] - p[1][i], 2));
						break;
					}
				}
			}
		}
		calculateBoundingBox(true, vlocal.layer);
		return true;
	}

	@Override
	synchronized public Collection<Long> getLayerIds() {
		return Utils.asList(p_layer, 0, n_points);
	}

	@Override
	public void adjustProperties() {
		final GenericDialog gd = makeAdjustPropertiesDialog(); // in superclass
		gd.addCheckbox("Paint as outlines", !fill_paint);
		gd.addCheckbox("Apply paint mode to all Ball instances", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		// superclass processing
		final Displayable.DoEdit prev = processAdjustPropertiesDialog(gd);
		// local proccesing
		final boolean fp = !gd.getNextBoolean();
		final boolean to_all = gd.getNextBoolean();
		if (to_all) {
			for (final ZDisplayable zd : layer_set.getZDisplayables()) {
				if (zd.getClass() == Ball.class) {
					final Ball b = (Ball)zd;
					b.fill_paint = fp;
					b.updateInDatabase("fill_paint");
				}
			}
			Display.repaint(layer_set);
		} else if (fill_paint != fp) {
			prev.add("fill_paint", fp);
			this.fill_paint = fp; // change it after storing state in DoEdit
			updateInDatabase("fill_paint");
		}

		// Add current step, with the same modified keys
		final DoEdit current = new DoEdit(this).init(prev);
		if (isLinked()) current.add(new Displayable.DoTransforms().addAll(getLinkedGroup(null)));
		getLayerSet().addEditStep(current);
	}

	/** Set the x,y,radius raw pixel values for the ball at index i.
	 * When done setting values, call repaint(true, null).
	 * @throws IndexOutOfBoundsException if i &lt; 0 or i &gt;= the number of points. */
	public void set(final int i, final double x, final double y, final Layer la, final double radius) {
		if (i < 0 || i > n_points) throw new IndexOutOfBoundsException("i must be 0<=i<n_points, but it is " + i);
		p[0][i] = x;
		p[1][i] = y;
		p_layer[i] = la.getId();
		p_width[i] = radius;
	}

	/** Return the number of balls. */
	public int getCount() {
		return n_points;
	}

	/** Set the radius (raw pixel value) for the ball at index i.
	 * When done setting values, call repaint(true, null).
	 * @throws IndexOutOfBoundsException if i &lt; 0 or i &gt;= the number of points. */
	public void setRadius(final int i, final double radius) {
		if (i < 0 || i > n_points) throw new IndexOutOfBoundsException("i must be 0<=i<n_points, but it is " + i);
		p_width[i] = radius;
	}
}
