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

import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.vector.SkinMaker;
import ini.trakem2.vector.VectorString2D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.vecmath.Point3f;


/** A class to be a user-outlined profile over an image, which is painted with a particular color and also holds an associated text label.
 * TODO - label not implemented yet.
 * 	- can't paint segments in different colors yet
 * 	- the whole curve is updated when only a particular set of points needs readjustment
 * 	- also, points are smooth, and options should be given to make them non-smooth.
 */
public class Profile extends Displayable implements VectorData {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked points.*/
	protected double[][] p = new double[2][5];
	/**The array of left control points, one for each clicked point.*/
	protected double[][] p_l = new double[2][5];
	/**The array of right control points, one for each clicked point.*/
	protected double[][] p_r = new double[2][5];
	/**The array of interpolated points generated from p, p_l and p_r.*/
	protected double[][] p_i = new double[2][0];
	/**Paint/behave as open or closed curve.*/
	protected boolean closed = false;

	/** A new user-requested Profile.*/
	public Profile(final Project project, final String title, final double x, final double y) {
		super(project, title, x, y);
		n_points = 0;
		addToDatabase();
	}

	/**Construct a Bezier Profile object from a set of points mixed in this pattern: PCCPCCPCCPCCP , so, [PCC]n  where P = backbone point and C = control point. This results from a BezierApproximation on a path of points as drawed with the mouse. Keep in mind the control points will NOT have the same tangents, but this may be either left like that or corrected with some smoothing algorithm.*/
	public Profile(final Project project, final String title, final double x, final double y, final Point2D.Double[] points) {
		super(project, title, x, y);
		//setup arrays
		final int size = (points.length / 3) + 1;
		p = new double[2][size];
		p_l =new double[2][size];
		p_r =new double[2][size];
		//first point:
		p[0][0] = points[0].x;
		p[1][0] = points[0].y;
		p_l[0][0] = p[0][0];
		p_l[1][0] = p[1][0];
		p_r[0][0] = points[1].x;
		p_r[1][0] = points[1].y;
		n_points++;
		//all middle points:
		for (int j=1, i=3; i<points.length-3; i+=3, j++) {
			p[0][j] = points[i].x;
			p[1][j] = points[i].y;
			p_l[0][j] = points[i-1].x;
			p_l[1][j] = points[i-1].y;
			if (null == points[i+1]) {
				Utils.log("BezierProfile: points[" + i + " + 1] is null !");
			}
			p_r[0][j] = points[i+1].x;
			p_r[1][j] = points[i+1].y;
			n_points++;
		}
		//last point:
		final int last = points.length-1;
		p[0][n_points] = points[last].x;
		p[1][n_points] = points[last].y;
		p_l[0][n_points] = points[last-1].x;
		p_l[1][n_points] = points[last-1].y;
		p_r[0][n_points] = p[0][n_points];
		p_r[1][n_points] = p[1][n_points];
		n_points++;

		calculateBoundingBox();

		//add to database
		addToDatabase();
		updateInDatabase("points");
	}

	/**Construct a Bezier Profile from the database.*/
	public Profile(final Project project, final long id, final String title, final float alpha, final boolean visible, final Color color, final double[][][] bezarr, final boolean closed, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, 0, 0);
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
		this.closed = closed;
		//make points from the polygon, in which they are stored as LPRLPR ... left control - backbone point - right control point (yes this looks very much like codons, but the important bit is the middle one, not the left one! HaHaHaHa!)
		//// points are fields x,y in PGpoint.
		p_l = bezarr[0];
		p = bezarr[1];
		p_r = bezarr[2];
		n_points = p[0].length;

		//calculate width and height
		calculateBoundingBox(false);
	}

	/** Construct a Bezier Profile from the database, but the points will be loaded later, when actually needed, by calling setupForDisplay(). */
	public Profile(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean closed, final boolean locked, final AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
		this.closed = closed;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Construct a Bezier Profile from an XML entry. */
	public Profile(final Project project, final long id, final HashMap<String,String> ht, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht, ht_links);
		// parse data
		for (final Map.Entry<String,String> entry : ht.entrySet()) {
			final String key = entry.getKey();
			final String data = entry.getValue();
			if (key.equals("d")) {
				// parse the SVG points data
				final ArrayList<String> al_p = new ArrayList<String>(),
									al_p_r = new ArrayList<String>(),
									al_p_l = new ArrayList<String>();// needs shifting, inserting one point at the beginning if not closed.
				// sequence is: M p[0],p[1] C p_r[0],p_r[1] p_l[0],p_l[1] and repeat without the M, and finishes with the last p[0],p[1]. If closed, appended at the end is p_r[0],p_r[1] p_l[0],p_l[1]
				// first point:
				int i_start = data.indexOf('M');
				int i_end = data.indexOf('C');
				final String point = data.substring(i_start+1, i_end).trim();
				al_p.add(point);
				boolean go = true;
				while (go) {
					i_start = i_end;
					i_end = data.indexOf('C', i_end+1);
					if (-1 == i_end) {
						i_end = data.length() -1;
						go = false;
					}
					String txt = data.substring(i_start+1, i_end).trim();
					// eliminate double spaces
					while (-1 != txt.indexOf("  ")) {
						txt = txt.replaceAll("  ", " ");
					}
					// reduce ", " and " ," to ","
					txt = txt.replaceAll(" ,", ",");
					txt = txt.replaceAll(", ", ",");
					// cut by spaces
					final String[] points = txt.split(" ");
					// debug:
					//Utils.log("Profile init: txt=__" + txt + "__ points.length=" + points.length);
					if (3 == points.length) {
						al_p_r.add(points[0]);
						al_p_l.add(points[1]);
						al_p.add(points[2]);
					} else {
						// error
						Utils.log("Profile constructor from XML: error at parsing points.");
					}
					// example: C 34.5,45.6 45.7,23.0 34.8, 78.0 C ..
				}
				// NO, usability problems // this.closed = (-1 != data.lastIndexOf('z')); // 'z' must be lowercase to comply with SVG style
				if (this.closed) {
					// prepend last left control point and delete from the end
					al_p_l.add(0, al_p_l.remove(al_p_l.size() -1));
					// remove last point (duplicated in this SVG format)
					al_p.remove(al_p.size() -1); // TODO check that it's really closed by comparing the points, not just by the z, and if not closed, remove the control points.
				} else {
					// prepend a left control point equal to the first point
					al_p_l.add(0, al_p.get(0)); // no need to clone, String is final
					// and append a right control point equal to the last point
					al_p_r.add(al_p.get(al_p.size() -1));
				}
				// sanity check:
				if (!(al_p.size() == al_p_l.size() && al_p_l.size() == al_p_r.size())) {
					Utils.log2("Profile XML parsing: Disagreement in the number of points:\n\tp.length=" + al_p.size() + "\n\tp_l.length=" + al_p_l.size() + "\n\tp_r.length=" + al_p_r.size());
				}
				// Now parse the points
				this.n_points = al_p.size();
				this.p = new double[2][n_points];
				this.p_l = new double[2][n_points];
				this.p_r = new double[2][n_points];
				for (int i=0; i<n_points; i++) {
					String[] sp = al_p.get(i).split(",");
					p[0][i] = Double.parseDouble(sp[0]);
					p[1][i] = Double.parseDouble(sp[1]);
					sp = al_p_l.get(i).split(",");
					p_l[0][i] = Double.parseDouble(sp[0]);
					p_l[1][i] = Double.parseDouble(sp[1]);
					sp = al_p_r.get(i).split(",");
					p_r[0][i] = Double.parseDouble(sp[0]);
					p_r[1][i] = Double.parseDouble(sp[1]);
				}
				this.p_i = new double[2][0]; // empty
				generateInterpolatedPoints(0.05);
			}
		}
	}

	/** A constructor for cloning purposes. */
	private Profile(final Project project, final String title, final double x, final double y, final float width, final float height, final float alpha, final Color color, final int n_points, final double[][] p, final double[][] p_r, final double[][] p_l, final double[][] p_i, final boolean closed) {
		super(project, title, x, y);
		this.width = width;
		this.height = height;
		this.alpha = alpha;
		this.color = color;
		this.n_points = n_points;
		this.p = p;
		this.p_r = p_r;
		this.p_l = p_l;
		this.p_i = p_i;
		this.closed = closed;
		addToDatabase();
		updateInDatabase("all");
	}

	/**Increase the size of the arrays by 5 points.*/
	protected void enlargeArrays() {
		//catch length
		final int length = p[0].length;
		//make copies
		final double[][] p_copy = new double[2][length + 5];
		final double[][] p_l_copy = new double[2][length + 5];
		final double[][] p_r_copy = new double[2][length + 5];
		//copy values
		System.arraycopy(p[0], 0, p_copy[0], 0, length);
		System.arraycopy(p[1], 0, p_copy[1], 0, length);
		System.arraycopy(p_l[0], 0, p_l_copy[0], 0, length);
		System.arraycopy(p_l[1], 0, p_l_copy[1], 0, length);
		System.arraycopy(p_r[0], 0, p_r_copy[0], 0, length);
		System.arraycopy(p_r[1], 0, p_r_copy[1], 0, length);
		//assign them
		this.p = p_copy;
		this.p_l = p_l_copy;
		this.p_r = p_r_copy;
	}

	/** Returns the number of backbone points. */
	public int getPointCount() {
		return n_points;
	}

	public boolean isClosed() {
		return this.closed;
	}

	/**Find a point in an array 'a', with a precision dependent on the magnification. */
	protected int findPoint(final double[][] a, final double x_p, final double y_p, final double magnification) {
		int index = -1;
		// make parameters local
		double d = (10.0D / magnification);
		if (d < 2) d = 2;
		for (int i=0; i<n_points; i++) {
			if ((Math.abs(x_p - a[0][i]) + Math.abs(y_p - a[1][i])) <= d) {
				index = i;
			}
		}
		return index;
	}

	/**Remove a point from the bezier backbone and its two associated control points.*/
	protected void removePoint(final int index) {
		// check preconditions:
		if (index < 0) {
			return;
		} else if (n_points - 1 == index) {
			//last point out
			n_points--;
		} else {
			//one point out (but not the last)
			n_points--;
			// shift all points after 'index' one position to the left:
			for (int i=index; i<n_points; i++) {
				p[0][i] = p[0][i+1];		//the +1 doesn't fail ever because the n_points has been adjusted above, but the arrays are still the same size. The case of deleting the last point is taken care above.
				p[1][i] = p[1][i+1];
				p_l[0][i] = p_l[0][i+1];
				p_l[1][i] = p_l[1][i+1];
				p_r[0][i] = p_r[0][i+1];
				p_r[1][i] = p_r[1][i+1];
			}
		}
		// open the curve if necessary
		if (closed && n_points < 2) {
			closed = false;
			updateInDatabase("closed");
		}
		//update in database
		updateInDatabase("points");
	}

	/**Calculate distance from one point to another.*/
	protected double distance(final double x1, final double y1, final double x2, final double y2) {
		return Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1));
	}

	/**Move backbone point.*/
	protected void dragPoint(final int index, final int dx, final int dy) {
		p[0][index] += dx;
		p[1][index] += dy;
		p_l[0][index] += dx;
		p_l[1][index] += dy;
		p_r[0][index] += dx;
		p_r[1][index] += dy;
	}

	/**Set the control points to the same value as the backbone point which they control.*/
	protected void resetControlPoints(final int index) {
		p_l[0][index] = p[0][index];
		p_l[1][index] = p[1][index];
		p_r[0][index] = p[0][index];
		p_r[1][index] = p[1][index];
	}

	/**Drag a control point and adjust the other, dependent one, in a symmetric way or not.*/
	protected void dragControlPoint(final int index, final double x_d, final double y_d, final double[][] p_dragged, final double[][] p_adjusted, final boolean symmetric) {
		//measure hypothenusa: from p to p control
		double hypothenusa;
		if (symmetric) {
			//make both points be dragged in parallel, the same distance
			hypothenusa = distance(p[0][index], p[1][index], p_dragged[0][index], p_dragged[1][index]);
		} else {
			//make each point be dragged with its own distance
			hypothenusa = distance(p[0][index], p[1][index], p_adjusted[0][index], p_adjusted[1][index]);
		}
		//measure angle: use the point being dragged
		final double angle = Math.atan2(p_dragged[0][index] - p[0][index], p_dragged[1][index] - p[1][index]) + Math.PI;
		//apply
		p_dragged[0][index] = x_d;
		p_dragged[1][index] = y_d;
		p_adjusted[0][index] = p[0][index] + hypothenusa * Math.sin(angle); // it's sin and not cos because of stupid Math.atan2 way of delivering angles
		p_adjusted[1][index] = p[1][index] + hypothenusa * Math.cos(angle);
	}

	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. Does not update the database. x_p,y_p are in the local space. */
	protected int addPoint(final double x_p, final double y_p, final double magnification, final double bezier_finess) {
		//lookup closest interpolated point and then get the closest clicked point to it
		int index = findClosestPoint(x_p, y_p, magnification, bezier_finess); // x_p, y_p are already local.
		if (closed && -1 == index) {
			return -1;
		}
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		//decide:
		if (0 == n_points || 1 == n_points || -1 == index || index + 1 == n_points) {
			//append at the end
			p[0][n_points] = p_l[0][n_points] = p_r[0][n_points] = x_p;
			p[1][n_points] = p_l[1][n_points] = p_r[1][n_points] = y_p;
			index = n_points;
		} else {
			//insert at index:
			index++; //so it is added after the closest point;
			// 1 - copy second half of array
			final int sh_length = n_points -index;
			final double[][] p_copy = new double[2][sh_length];
			final double[][] p_l_copy = new double[2][sh_length];
			final double[][] p_r_copy = new double[2][sh_length];
			System.arraycopy(p[0], index, p_copy[0], 0, sh_length);
			System.arraycopy(p[1], index, p_copy[1], 0, sh_length);
			System.arraycopy(p_l[0], index, p_l_copy[0], 0, sh_length);
			System.arraycopy(p_l[1], index, p_l_copy[1], 0, sh_length);
			System.arraycopy(p_r[0], index, p_r_copy[0], 0, sh_length);
			System.arraycopy(p_r[1], index, p_r_copy[1], 0, sh_length);
			// 2 - insert value into 'p' (the two control arrays get the same value)
			p[0][index] = p_l[0][index] = p_r[0][index] = x_p;
			p[1][index] = p_l[1][index] = p_r[1][index] = y_p;
			// 3 - copy second half into the array
			System.arraycopy(p_copy[0], 0, p[0], index+1, sh_length);
			System.arraycopy(p_copy[1], 0, p[1], index+1, sh_length);
			System.arraycopy(p_l_copy[0], 0, p_l[0], index+1, sh_length);
			System.arraycopy(p_l_copy[1], 0, p_l[1], index+1, sh_length);
			System.arraycopy(p_r_copy[0], 0, p_r[0], index+1, sh_length);
			System.arraycopy(p_r_copy[1], 0, p_r[1], index+1, sh_length);
		}
		//add one up
		this.n_points++;

		// set the x,y and readjust points
		calculateBoundingBox();

		return index;
	}

	/**Find the closest point to an interpolated point with precision depending upon magnification. The point x_p,y_p is in local coordinates. */
	protected int findClosestPoint(final double x_p, final double y_p, final double magnification, final double bezier_finess) {
		if (0 == p_i[0].length) return -1; // when none added yet
		int index = -1;
		double distance_sq = Double.MAX_VALUE;
		double distance_sq_i;
		double max = 12.0D / magnification;
		max = max * max; //squaring it
		for (int i=0; i<p_i[0].length; i++) {
			//see which point is closer (there's no need to calculate the distance by multiplying squares and so on).
			distance_sq_i = (p_i[0][i] - x_p)*(p_i[0][i] - x_p) + (p_i[1][i] - y_p)*(p_i[1][i] - y_p);
			if (distance_sq_i < max && distance_sq_i < distance_sq) {
				index = i;
				distance_sq = distance_sq_i;
			}
		}
		if (-1 != index) {
			int index_found = (int)((double)index * bezier_finess);
			if (index < (index_found / bezier_finess)) {
				index_found--;
			}
			index = index_found;
		}
		return index;
	}

	/**Toggle curve closed/open.*/
	public void toggleClosed() {
		if (closed) {
			closed = false;
		} else {
			closed = true;
		}
		//update database
		updateInDatabase("closed");
	}

	protected void generateInterpolatedPoints(final double bezier_finess) {
		if (0 >= n_points) {
			return;
		}

		int n = n_points;
		if (closed && n > 1) {
			//do the loop for one more
			n++;
			//enlarge arrays if needed
			if (p[0].length == n_points) {
				enlargeArrays();
			}
			//add first point to the end (doesn't need to be deleted because n_points hasn't changed)
			// n_points works as an index here.
			p[0][n_points] = p[0][0];
			p[1][n_points] = p[1][0];
			p_l[0][n_points] = p_l[0][0];
			p_l[1][n_points] = p_l[1][0];
			p_r[0][n_points] = p_r[0][0];
			p_r[1][n_points] = p_r[1][0];
		}

		// case there's only one point
		if (1 == n_points) {
			p_i = new double[2][1];
			p_i[0][0] = p[0][0];
			p_i[1][0] = p[1][0];
			return;
		}
		// case there's more: interpolate!
		p_i = new double[2][(int)(n * (1.0D/bezier_finess))];
		double t, f0, f1, f2, f3;
		int next = 0;
		for (int i=0; i<n-1; i++) {
			for (t=0.0D; t<1.0D; t += bezier_finess) {
				f0 = (1-t)*(1-t)*(1-t);
				f1 = 3*t*(1-t)*(1-t);
				f2 = 3*t*t*(1-t);
				f3 = t*t*t;
				p_i[0][next] = f0*p[0][i] + f1*p_r[0][i] + f2*p_l[0][i+1] + f3*p[0][i+1];
				p_i[1][next] = f0*p[1][i] + f1*p_r[1][i] + f2*p_l[1][i+1] + f3*p[1][i+1];
				next++;
				//enlarge if needed (when bezier_finess is not 0.05, it's difficult to predict because of int loss of precision.
				if (p_i[0].length == next) {
					final double[][] p_i_copy = new double[2][p_i[0].length + 5];
					System.arraycopy(p_i[0], 0, p_i_copy[0], 0, p_i[0].length);
					System.arraycopy(p_i[1], 0, p_i_copy[1], 0, p_i[1].length);
					p_i = p_i_copy;
				}
			}
		}
		if (p_i[0].length != next) { // 'next' works as a length here
			//resize back
			final double[][] p_i_copy = new double[2][next];
			System.arraycopy(p_i[0], 0, p_i_copy[0], 0, next);
			System.arraycopy(p_i[1], 0, p_i_copy[1], 0, next);
			p_i = p_i_copy;
		}
	}

	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		if (0 == n_points) return;
		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
			if (-1 == n_points) {
				Utils.log2("Profile.paint: Some error ocurred, can't load points from database.");
				return;
			}
		}
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[][] p_i = this.p_i;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_i = (double[][])ob[3];
		}
		if (active) {
			//draw/fill points
			final int oval_radius = (int)Math.ceil(4 / magnification);
			final int oval_corr = (int)Math.ceil(3 / magnification);
			for (int j=0; j<n_points; j++) {
				DisplayCanvas.drawHandle(g, (int)p[0][j], (int)p[1][j], magnification);
				g.setColor(this.color);
				//fill small ovals at control points
				g.fillOval((int)p_l[0][j] -oval_corr, (int)p_l[1][j] -oval_corr, oval_radius, oval_radius);
				g.fillOval((int)p_r[0][j] -oval_corr, (int)p_r[1][j] -oval_corr, oval_radius, oval_radius);
				//draw lines between backbone and control points
				g.drawLine((int)p[0][j], (int)p[1][j], (int)p_l[0][j], (int)p_l[1][j]);
				g.drawLine((int)p[0][j], (int)p[1][j], (int)p_r[0][j], (int)p_r[1][j]);
			}
		}

		//set color
		g.setColor(this.color);

		//draw lines between any two consecutive interpolated points
		for (int i=0; i<p_i[0].length-1; i++) {
			g.drawLine((int)p_i[0][i], (int)p_i[1][i], (int)p_i[0][i+1], (int)p_i[1][i+1]);
		}
		//draw last segment between last and first points, only if closed:
		if (closed) {
			g.drawLine((int)p_i[0][p_i[0].length-1], (int)p_i[1][p_i[0].length-1], (int)p_i[0][0], (int)p_i[1][0]);
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	/**Helper vars for mouse events. It's safe to have them static since only one Profile will be edited at a time.*/
	static private int index = -1;
	static private int index_l = -1;
	static private int index_r = -1;
	static private boolean is_new_point = false;

	/**Execute the mousePressed MouseEvent on this Profile.*/
	@Override
    public void mousePressed(final MouseEvent me, final Layer layer, int x_p, int y_p, final double mag) {
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		// reset helper vars
		is_new_point = false;
		index = index_r = index_l = -1;

		if (ProjectToolbar.PEN == tool) {

			//collect vars
			if (Utils.isControlDown(me) && me.isShiftDown()) {
				index = findNearestPoint(p, n_points, x_p, y_p);
			} else {
				index = findPoint(p, x_p, y_p, mag);
			}

			if (-1 != index) {
				if (Utils.isControlDown(me) && me.isShiftDown()) {
					//delete point
					removePoint(index);
					index = index_r = index_l = -1;
					generateInterpolatedPoints(0.05);
					repaint(false);
					return;
				} else if (me.isAltDown()) {
					resetControlPoints(index);
					return;
				} else if (me.isShiftDown()) {
					if (0 == index && n_points > 1 && !closed) {
						//close curve, reset left control point of the first point and set it up for dragging
						closed = true;
						updateInDatabase("closed");
						p_l[0][0] = p[0][0];
						p_l[1][0] = p[1][0];
						index = -1;
						index_r = -1;
						index_l = 0; //the first one
						repaint(false);
						return;
					}
				}
			}

			// find if click is on a left control point
			index_l = findPoint(p_l, x_p, y_p, mag);
			index_r = -1;
			// if not, then try on the set of right control points
			if (-1 == index_l) {
				index_r = findPoint(p_r, x_p, y_p, mag);
			}

			//if no conditions are met, attempt to add point (won't get added if the curve is closed and the click is too far from the interpolated points).
			if (-1 == index && -1 == index_l && -1 == index_r && !me.isShiftDown() && !me.isAltDown()) {
				//add a new point and assign its index to the left control point, so it can be dragged right away. This is copying the way Karbon does for drawing Bezier curves, which is the contrary to Adobe's way, but which I find more useful.
				index_l = addPoint(x_p, y_p, mag, 0.05);
				if (-1 != index_l) is_new_point = true;
				else if (1 == n_points) {
					//for the very first point, drag the right control point, not the left.
					index_r = index_l;
					index_l = -1;
				}
				repaint(false);
				return;
			}
		}
	}

	/**Execute the mouseDragged MouseEvent on this Profile.*/
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

			//if a point in the backbone is found, then:
			if (-1 != index) {
				if (!me.isAltDown()) {
					//drag point
					dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				} else {
					//drag both control points symmetrically
					dragControlPoint(index, x_d, y_d, p_l, p_r, true);
				}
				generateInterpolatedPoints(0.05);
				repaint(false);
				return;
			}

			//if a control point is found, then drag it, adjusting the other control point non-symmetrically
			if (-1 != index_r) {
				dragControlPoint(index_r, x_d, y_d, p_r, p_l, is_new_point);
				generateInterpolatedPoints(0.05);
				repaint(false);
				return;
			}
			if (-1 != index_l) {
				dragControlPoint(index_l, x_d, y_d, p_l, p_r, is_new_point);
				generateInterpolatedPoints(0.05);
				repaint(false);
				return;
			}

			// no points selected. Drag the whole curve on alt down (without affecting linked curves)
			if (me.isAltDown()) {
				final int dx = x_d - x_d_old;
				final int dy = y_d - y_d_old;
				this.at.translate(dx, dy);
				repaint(false);
				return;
			}
		}
	}

	/**Execute the mouseReleased MouseEvent on this Profile.*/
	@Override
    public void mouseReleased(final MouseEvent me, final Layer layer, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		final int tool = ProjectToolbar.getToolId();
		if (ProjectToolbar.PEN == tool) {
			//generate interpolated points
			generateInterpolatedPoints(0.05);
			repaint(); //needed at least for the removePoint, and also for repainting the DisplayablePanel and the DisplayNavigator // TODO this may be redundant with below
		}

		//update points in database if there was any change
		if (-1 != index || -1 != index_r || -1 != index_l) {
			updateInDatabase("points");
			updateInDatabase("transform+dimensions"); //was: dimensions
			Display.repaint(layer, this); // the DisplayablePanel
		} else if (x_r != x_p || y_r != y_p) {
			updateInDatabase("transform+dimensions");
			Display.repaint(layer, this); // the DisplayablePanel
		}
		// reset helper vars
		is_new_point = false;
		index = index_r = index_l = -1;
	}

	protected void calculateBoundingBox() {
		calculateBoundingBox(true);
	}

	/**Calculate the bounding box of the curve in the shape of a Rectangle defined by x,y,width,height. If adjust_position is true, then points are made local to the minimal x,y. */
	protected void calculateBoundingBox(final boolean adjust_position) {
		if (0 == n_points) {
			this.width = this.height = 0;
			updateBucket();
			return;
		}
		//go over all points and control points and find the max and min
		//	(there's no need to use the interpolated points because the points and control points define the boxes in which the interpolated points are).
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;

		for (int i=0; i<n_points; i++) {
			if (p[0][i] < min_x) min_x = p[0][i];
			if (p_l[0][i] < min_x) min_x = p_l[0][i];
			if (p_r[0][i] < min_x) min_x = p_r[0][i];
			if (p[1][i] < min_y) min_y = p[1][i];
			if (p_l[1][i] < min_y) min_y = p_l[1][i];
			if (p_r[1][i] < min_y) min_y = p_r[1][i];
			if (p[0][i] > max_x) max_x = p[0][i];
			if (p_l[0][i] > max_x) max_x = p_l[0][i];
			if (p_r[0][i] > max_x) max_x = p_r[0][i];
			if (p[1][i] > max_y) max_y = p[1][i];
			if (p_l[1][i] > max_y) max_y = p_l[1][i];
			if (p_r[1][i] > max_y) max_y = p_r[1][i];
		}

		this.width = (float)(max_x - min_x);
		this.height = (float)(max_y - min_y);

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (int i=0; i<n_points; i++) {
				p[0][i] -= min_x;	p[1][i] -= min_y;
				p_l[0][i] -= min_x;	p_l[1][i] -= min_y;
				p_r[0][i] -= min_x;	p_r[1][i] -= min_y;
			}
			for (int i=0; i<p_i[0].length; i++) {
				p_i[0][i] -= min_x;	p_i[1][i] -= min_y;
			}
			this.at.translate(min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateBucket();
		updateInDatabase("dimensions");
	}


	@Override
    public void repaint() {
		repaint(true);
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Profile. */
	public void repaint(final boolean repaint_navigator) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		final Rectangle box = getBoundingBox(null);
		calculateBoundingBox();
		box.add(getBoundingBox(null));
		Display.repaint(layer, this, box, 5, repaint_navigator);
	}

	/**Check if the given point (usually from a MOUSE_PRESSED MouseEvent) is contained within the boundaries of this object. The point is expected as local coordinates. */
	public boolean containsPoint(final int x_p, final int y_p) {
		// as in getPerimeter():
		final int n_i = p_i[0].length;
		final int[] intx = new int[n_i];
		final int[] inty = new int[n_i];
		for (int i=0; i<n_i; i++) {
			intx[i] = (int)p_i[0][i];
			inty[i] = (int)p_i[1][i];
		}
		final Polygon polygon = new Polygon(intx, inty, n_i);
		return polygon.contains(x_p, y_p);
	}

	/**Release all memory resources taken by this object.*/
	@Override
    public void destroy() {
		super.destroy();
		p = null;
		p_l = null;
		p_r = null;
		p_i = null;
	}

	/**Make this object ready to be painted.*/
	private void setupForDisplay() {
		// load points
		if (null == p || null == p_l || null == p_r) {
			//load points from database
			final double[][][] bezarr = project.getLoader().fetchBezierArrays(this.id);
			if (null == bezarr) {
				Utils.log("Profile.setupForDisplay: could not load the bezier points from the database for id=" + this.id);
				this.p_i = new double[0][0];
				return;
			}
			p_l = bezarr[0];
			p = bezarr[1];
			p_r = bezarr[2];
			n_points = p[0].length;
			// recreate interpolated points
			generateInterpolatedPoints(0.05); //TODO the 0.05 bezier finess, read the value from the Project perhaps.
		}
	}

	/**Cache this Profile if needed.*/ //Is there much sense in caching Profile objects? Unless you have thousands ... and that CAN be the case!
	public void cache() {
		//TODO
	}

	/**Release memory resources used by this object: namely the arrays of points, which can be reloaded with a call to setupForDisplay()*/
	public void flush() {
		p = null;
		p_l = null;
		p_r = null;
		p_i = null;
		n_points = -1; // flag that points exist (and need to be reloaded)
	}

	/** The perimeter of this profile, in integer precision. */
	@Override
    public Polygon getPerimeter() {
		if (-1 == n_points) setupForDisplay();
		if (null == p_i) return null; // has been flushed, incorrect access! This is a patch.

		// transform
		double[][] p_i = this.p_i;
		if (!this.at.isIdentity()) p_i = transformPoints(this.p_i);

		final int n_i = p_i[0].length;
		final int[] intx = new int[n_i];
		final int[] inty = new int[n_i];
		for (int i=0; i<n_i; i++) {
			intx[i] = (int)p_i[0][i];
			inty[i] = (int)p_i[1][i];
		}
		return new Polygon(intx, inty, n_i);
	}

	/** Writes the data of this object as a Bezier object in the .shapes file represented by the 'data' StringBuffer. The z_scale is added to manually correct for sample squashing under the coverslip. */
	public void toShapesFile(final StringBuffer data, final String group, final String color, final double z_scale) {
		if (-1 == n_points) setupForDisplay(); // reload
		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
		}
		final double z = layer.getZ();
		final char l = '\n';
		data.append("type=bezier").append(l)
		    .append("name=").append(project.getMeaningfulTitle(this)).append(l)
		    .append("group=").append(group).append(l)
		    .append("color=").append(color).append(l)
		    .append("supergroup=").append("null").append(l)
		    .append("supercolor=").append("null").append(l)
		    .append("in slice=").append(z * z_scale).append(l)	 // fake, this is now the absolute z coordinate
		    .append("curve_closed=").append(true).append(l) // must!
		    .append("density field=").append(false).append(l) // must!
		;
		for (int i=0; i<n_points; i++) {
			data.append("p x=").append(p[0][i]).append(l)
			    .append("p y=").append(p[1][i]).append(l)
			    .append("p_r x=").append(p_r[0][i]).append(l)
			    .append("p_r y=").append(p_r[1][i]).append(l)
			    .append("p_l x=").append(p_l[0][i]).append(l)
			    .append("p_l y=").append(p_l[1][i]).append(l)
			;
		}
	}

	@Override
    public void exportSVG(final StringBuffer data, final double z_scale, final String indent) {
		final String in = indent + "\t";
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		final String[] RGB = Utils.getHexRGBColor(color);
		final double[] a = new double[6];
		at.getMatrix(a);
		data.append(indent).append("<path\n")
		    .append(in).append("type=\"profile\"\n")
		    .append(in).append("id=\"").append(id).append("\"\n")
		    .append(in).append("transform=\"matrix(").append(a[0]).append(',')
								.append(a[1]).append(',')
								.append(a[2]).append(',')
								.append(a[3]).append(',')
								.append(a[4]).append(',')
								.append(a[5]).append(")\"\n")
		    .append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n")
		    .append(in).append("d=\"M");//.append(p[0][0]).append(',').append(p[1][0]).append(" C ").append(p_r[0][0]).append(',').append(p_r[1][0]);
		for (int i=0; i<n_points-1; i++) {
			data.append(' ').append(p[0][i]).append(',').append(p[1][i])
			    .append(" C ").append(p_r[0][i]).append(',').append(p_r[1][i])
			    .append(' ').append(p_l[0][i+1]).append(',').append(p_l[1][i+1])
			;
		}
		data.append(' ').append(p[0][n_points-1]).append(',').append(p[1][n_points-1]);
		if (closed) {
			data.append(" C ").append(p_r[0][n_points-1]).append(',').append(p_r[1][n_points-1])
			    .append(' ').append(p_l[0][0]).append(',').append(p_l[1][0])
			    .append(' ').append(p[0][0]).append(',').append(p[1][0])
			    .append(" z")
			;
		}
		data.append("\"\n")
		    .append(in).append("z=\"").append(layer.getZ() * z_scale).append("\"\n")
		    .append(in).append("links=\"")
		;
		if (null != hs_linked && 0 != hs_linked.size()) {
			int ii = 0;
			final int len = hs_linked.size();
			for (final Displayable d : hs_linked) {
				data.append(d.getId());
				if (ii != len-1) data.append(',');
				ii++;
			}
		}
		data.append("\"\n")
		    .append(indent).append("/>\n")
		;
	}

	/** Returns a triple array, each containing a [2][n_points] array
	 *  specifiying the x,y of each left control point, backbone point
	 *  and right control point respectively. All of them are copies. */
	public double[][][] getBezierArrays() {
		//assumes the profile is a Bezier curve.
		//put points and control points into PGpoint objects, as: LPRLPRLPR... (L = left control point, P = backbone point, R = right control point)
		if (-1 == n_points) setupForDisplay(); // reload
		final double[][][] b = new double[3][2][];
		b[0][0] = Utils.copy(p_l[0], n_points);
		b[0][1] = Utils.copy(p_l[1], n_points);
		b[1][0] = Utils.copy(p[0], n_points);
		b[1][1] = Utils.copy(p[1], n_points);
		b[2][0] = Utils.copy(p_r[0], n_points);
		b[2][1] = Utils.copy(p_r[1], n_points);
		return b;
	}

	@Override
    public boolean isDeletable() {
		return 0 == n_points;
	}

	/** Returns true if it's linked to at least one patch in the same Layer. Otherwise returns false. */
	@Override
    public boolean isLinked() {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		for (final Displayable d : hs_linked) {
			if (d instanceof Patch && d.layer.equals(this.layer)) return true;
		}
		return false;
	}

	/** Returns false if the target_layer contains a profile that is directly linked to this profile. */
	@Override
    public boolean canSendTo(final Layer target_layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		for (final Displayable d : hs_linked) {
			if (d instanceof Profile && d.layer.equals(target_layer)) return false;
		}
		return true;
	}

	protected double[][] getFirstPoint()
	{
		if (0 == n_points) return null;
		return new double[][]{{p_l[0][0],p_l[1][0]},{p[0][0],p[1][0]},{p_r[0][0],p_r[1][0]}};
	}

	protected double[][] getLastPoint()
	{
		return new double[][]{{p_l[0][n_points-1],p_l[1][n_points-1]},{p[0][n_points-1],p[1][n_points-1]},{p_r[0][n_points-1],p_r[1][n_points-1]}};
	}

	public boolean hasPoints() {
		return 0 != n_points;
	}

	protected void setPoints(final double[][] p_l, final double[][] p, final double[][] p_r) {
		this.p_l = p_l;
		this.p = p;
		this.p_r = p_r;
		this.n_points = p_l[0].length;
		this.generateInterpolatedPoints(0.05);
	}

	public void setPoints(final double[][] p_l, final double[][] p, final double[][] p_r, final boolean update) {
		setPoints(p_l, p, p_r);
		calculateBoundingBox();
		if (update) {
			updateInDatabase("points");
			repaint(true);
		}
	}

	protected void addPointsAtBegin(final double[][] new_p_l, final double[][] new_p, final double[][] new_p_r) {
		final double[][] tmp_p_l = new double[2][p_l[0].length + new_p_l[0].length];
		final double[][] tmp_p = new double[2][p[0].length + new_p[0].length];
		final double[][] tmp_p_r = new double[2][p_r[0].length + new_p_r[0].length];
		int i = 0;
		for (; i < new_p_l[0].length; i++) {
			tmp_p_l[0][i] = new_p_l[0][i];
			tmp_p_l[1][i] = new_p_l[1][i];
			tmp_p[0][i] = new_p[0][i];
			tmp_p[1][i] = new_p[1][i];
			tmp_p_r[0][i] = new_p_r[0][i];
			tmp_p_r[1][i] = new_p_r[1][i];
		}
		for (int j = 0; j < n_points; j++, i++) {
			tmp_p_l[0][i] = p_l[0][j];
			tmp_p_l[1][i] = p_l[1][j];
			tmp_p[0][i] = p[0][j];
			tmp_p[1][i] = p[1][j];
			tmp_p_r[0][i] = p_r[0][j];
			tmp_p_r[1][i] = p_r[1][j];
		}
		this.n_points += new_p_l[0].length;

		p_l = tmp_p_l;
		p = tmp_p;
		p_r = tmp_p_r;
		this.generateInterpolatedPoints(0.05);
	}

	protected void addPointsAtEnd(final double[][] new_p_l, final double[][] new_p, final double[][] new_p_r) {
		final double[][] tmp_p_l = new double[2][p_l[0].length + new_p_l[0].length];
		final double[][] tmp_p = new double[2][p[0].length + new_p[0].length];
		final double[][] tmp_p_r = new double[2][p_r[0].length + new_p_r[0].length];
		int i = 0;
		for (; i < n_points;  i++) {
			tmp_p_l[0][i] = p_l[0][i];
			tmp_p_l[1][i] = p_l[1][i];
			tmp_p[0][i] = p[0][i];
			tmp_p[1][i] = p[1][i];
			tmp_p_r[0][i] = p_r[0][i];
			tmp_p_r[1][i] = p_r[1][i];
		}

		for (int j = 0; j < new_p_l[0].length; i++,j++) {
			tmp_p_l[0][i] = new_p_l[0][j];
			tmp_p_l[1][i] = new_p_l[1][j];
			tmp_p[0][i] = new_p[0][j];
			tmp_p[1][i] = new_p[1][j];
			tmp_p_r[0][i] = new_p_r[0][j];
			tmp_p_r[1][i] = new_p_r[1][j];
		}
		this.n_points += new_p_l[0].length;

		p_l = tmp_p_l;
		p = tmp_p;
		p_r = tmp_p_r;
		this.generateInterpolatedPoints(0.05);
	}

	public int getNearestPointIndex(final double x_p, final double y_p) {
		int ret = -1;
		double minDist = Double.POSITIVE_INFINITY;
		for (int i = 0; i < this.n_points; i++) {
			final double dx = this.p[0][i]-x_p;
			final double dy = this.p[1][i]-y_p;
			final double dist = dx*dx+dy*dy;
			if(dist < minDist)
			{
				minDist = dist;
				ret = i;
			}
		}
		return ret;
	}

	public void insertBetween(int startIndex, int endIndex, final double[][] tmp_p_l, final double[][] tmp_p, final double[][] tmp_p_r){
		if(endIndex < startIndex)
		{
			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < tmp_p[0].length/2; j++) {
					final double tmppl = tmp_p_l[i][j];
					final double tmpp = tmp_p[i][j];
					final double tmppr = tmp_p_r[i][j];

					tmp_p_r[i][j] = tmp_p_l[i][tmp_p_l[0].length -1- j];
					tmp_p[i][j] = tmp_p[i][tmp_p[0].length -1- j];
					tmp_p_l[i][j] = tmp_p_r[i][tmp_p_r[0].length -1- j];

					tmp_p_r[i][tmp_p_l[0].length -1- j]=tmppl;
					tmp_p[i][tmp_p[0].length -1- j]=tmpp;
					tmp_p_l[i][tmp_p_r[0].length -1- j]=tmppr;
				}
			}

			final int tmp = startIndex;
			startIndex = endIndex;
			endIndex = tmp;
		}


		double[][] beginning_p_l;
		double[][] beginning_p;
		double[][] beginning_p_r;

		double[][] ending_p_l;
		double[][] ending_p;
		double[][] ending_p_r;

		if(endIndex - startIndex < n_points + startIndex - endIndex || closed == false)
		{
			beginning_p_l = new double [2][startIndex+1];
			beginning_p = new double [2][startIndex+1];
			beginning_p_r = new double [2][startIndex+1];

			ending_p_l = new double [2][n_points- endIndex];
			ending_p = new double [2][n_points- endIndex];
			ending_p_r = new double [2][n_points- endIndex];

			for(int i = 0 ; i <= startIndex ; i++)
			{
				for (int j = 0; j < 2; j++) {
					beginning_p_l [j][i] = this.p_l[j][i];
					beginning_p [j][i] = this.p[j][i];
					beginning_p_r [j][i] = this.p_r[j][i];
				}
			}
			for(int i = endIndex ; i < this.n_points ; i++)
			{
				for (int j = 0; j < 2; j++) {
					ending_p_l [j][i-endIndex] = this.p_l[j][i];
					ending_p [j][i-endIndex] = this.p[j][i];
					ending_p_r [j][i-endIndex] = this.p_r[j][i];
				}
			}
			System.out.println("1");
		}
		else
		{
			beginning_p_l = new double [2][endIndex-startIndex + 1];
			beginning_p = new double [2][ endIndex-startIndex + 1 ];
			beginning_p_r = new double [2][endIndex-startIndex + 1];

			ending_p_l = new double [2][0];
			ending_p = new double [2][0];
			ending_p_r = new double [2][0];

			for(int i = startIndex ; i <= endIndex ; i++)
			{
				for (int j = 0; j < 2; j++) {
					beginning_p_r [j][endIndex - i] = this.p_l[j][i];
					beginning_p [j][endIndex - i] = this.p[j][i];
					beginning_p_l [j][endIndex - i] = this.p_r[j][i];
				}
			}

			System.out.println("2");

		}



		final double[][] new_p_l = new double[2][beginning_p_l[0].length + ending_p_l[0].length + tmp_p_l[0].length];
		final double[][] new_p = new double[2][beginning_p[0].length + ending_p[0].length + tmp_p[0].length];
		final double[][] new_p_r = new double[2][beginning_p_r[0].length + ending_p_r[0].length + tmp_p_r[0].length];

		for (int i = 0; i < beginning_p[0].length; i++) {
			for (int j = 0; j < 2; j++) {
				new_p_l[j][i] = beginning_p_l[j][i];
				new_p[j][i] = beginning_p[j][i];
				new_p_r[j][i] = beginning_p_r[j][i];
			}
		}
		for (int i = 0; i < tmp_p[0].length; i++) {
			for (int j = 0; j < 2; j++) {
				new_p_l[j][i+beginning_p[0].length] = tmp_p_l[j][i];
				new_p[j][i+beginning_p[0].length] = tmp_p[j][i];
				new_p_r[j][i+beginning_p[0].length] = tmp_p_r[j][i];
			}
		}
		for (int i = 0; i < ending_p[0].length; i++) {
			for (int j = 0; j < 2; j++) {
				new_p_l[j][i+beginning_p[0].length+tmp_p[0].length] = ending_p_l[j][i];
				new_p[j][i+beginning_p[0].length+tmp_p[0].length] = ending_p[j][i];
				new_p_r[j][i+beginning_p[0].length+tmp_p[0].length] = ending_p_r[j][i];
			}
		}
		this.n_points = new_p[0].length;
		this.p_l = new_p_l;
		this.p = new_p;
		this.p_r = new_p_r;
		this.calculateBoundingBox();
		this.generateInterpolatedPoints(0.05);
	}

	public void printPoints() {
		System.out.println("#####\nw,h: " + width + "," + height);
		for (int i=0; i<n_points; i++) {
			System.out.println("x,y: " + p[0][i] + " , " + p[1][i]);
		}
		System.out.println("\n");
	}


	/** x,y is the cursor position in offscreen coordinates. */ // COPIED from the Pipe
	@Override
    public void snapTo(final int cx, final int cy, final int x_p, final int y_p) { // WARNING: DisplayCanvas is locking at mouseDragged when the cursor is outside the DisplayCanvas Component, so this is useless or even harmful at the moment.
		if (-1 != index) {
			// #$#@$%#$%!!! TODO this doesn't work, although it *should*. The index_l and index_r work, and the mouseEntered coordinates are fine too. Plus it messes up the x,y position or something, for then on reload the pipe is streched or displaced (not clear).
			/*
			double dx = p_l[0][index] - p[0][index];
			double dy = p_l[1][index] - p[1][index];
			p_l[0][index] = cx + dx;
			p_l[1][index] = cy + dy;
			dx = p_r[0][index] - p[0][index];
			dy = p_r[1][index] - p[1][index];
			p_r[0][index] = cx + dx;
			p_r[1][index] = cy + dy;
			p[0][index] = cx;
			p[1][index] = cy;
			*/
		} else if (-1 != index_l) {
			p_l[0][index_l] = cx;
			p_l[1][index_l] = cy;
		} else if (-1 != index_r) {
			p_r[0][index_r] = cx;
			p_r[1][index_r] = cy;
		} else {
			// drag the whole pipe
			// CONCEPTUALLY WRONG, what happens when not dragging the pipe, on mouseEntered? Disaster!
			//drag(cx - x_p, cy - y_p);
		}
	}

	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		sb_body.append(indent).append("<t2_profile\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		if (-1 == n_points) setupForDisplay(); // reload
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n");
		if (n_points > 0) {
			sb_body.append(in).append("d=\"M");
			for (int i=0; i<n_points-1; i++) {
				sb_body.append(' ').append(p[0][i]).append(',').append(p[1][i])
				    .append(" C ").append(p_r[0][i]).append(',').append(p_r[1][i])
				    .append(' ').append(p_l[0][i+1]).append(',').append(p_l[1][i+1])
				;
			}
			sb_body.append(' ').append(p[0][n_points-1]).append(',').append(p[1][n_points-1]);
			if (closed) {
				sb_body.append(" C ").append(p_r[0][n_points-1]).append(',').append(p_r[1][n_points-1])
				    .append(' ').append(p_l[0][0]).append(',').append(p_l[1][0])
				    .append(' ').append(p[0][0]).append(',').append(p[1][0])
				    .append(" z")
				;
			}
			sb_body.append("\"\n");
		}
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, options);
		sb_body.append(indent).append("</t2_profile>\n");
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_profile";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_profile (").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2)
		;
	}

	/** Returns the interpolated points as a VectorString2D, calibrated.
	 *  Returns null if there aren't any points. */
	public VectorString2D getPerimeter2D() {
		return getPerimeter2D(layer.getParent().getCalibration());
	}
	private VectorString2D getPerimeter2D(final Calibration cal) {
		if (-1 == n_points) setupForDisplay();
		if (0 == n_points) return null;
		if (0 == p_i[0].length) generateInterpolatedPoints(0.05);
		final double[][] pi = transformPoints(p_i);
		VectorString2D sv = null;
		try {
			sv = new VectorString2D(pi[0], pi[1], this.layer.getZ(), this.closed);
		} catch (final Exception e) {
			IJError.print(e);
		}
		if (null != cal) sv.calibrate(cal);
		return sv;
	}

	@Override
    public void keyPressed(final KeyEvent ke) {
		super.keyPressed(ke);
		if (ke.isConsumed()) return;
		final int key_code = ke.getKeyCode();
		Rectangle box = null;
		switch(key_code) {
		case KeyEvent.VK_X: // remove all points
			if (0 == ke.getModifiers() && (ProjectToolbar.getToolId() == ProjectToolbar.PEN || ProjectToolbar.getToolId() == ProjectToolbar.PENCIL)) {
				box = getBoundingBox(box);
				n_points = 0;
				this.p_i = new double[2][0];
				calculateBoundingBox(true);
				ke.consume();
				if (closed) toggleClosed();
				updateInDatabase("points");
			}
			break;
		case KeyEvent.VK_C: // toggle close with shift+c
			if (0 == (ke.getModifiers() ^ java.awt.Event.SHIFT_MASK)) {
				//preconditions: at least 2 points!
				if (n_points > 1) {
					toggleClosed();
					generateInterpolatedPoints(0.05);
					ke.consume();
				}
			}
			break;
		}
		if (ke.isConsumed()) {
			Display.repaint(this.layer, box, 5);
		}
	}

	@Override
    public void setColor(final Color c) {
		// propagate to all linked profiles within the same profile_list
		setColor(c, new HashSet<Profile>());
	}

	/** Exploits the fact that Profile instances among the directly linked as returned by getLinked(Profile.class) will be members of the same profile_list. */
	private void setColor(final Color c, final HashSet<Profile> hs_done) {
		if (hs_done.contains(this)) return;
		hs_done.add(this);
		super.setColor(c);
		final HashSet<Displayable> hs = getLinked(Profile.class);
		if (null != hs) {
			for (final Iterator<Displayable> it = hs.iterator(); it.hasNext(); ) {
				final Profile p = (Profile)it.next();
				p.setColor(c, hs_done);
			}
		}
	}

	/** Performs a deep copy of this object, unlocked and visible. */
	@Override
    public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Profile copy = new Profile(pr, nid, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), closed, this.locked, (AffineTransform)this.at.clone());
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_l = new double[][]{(double[])this.p_l[0].clone(), (double[])this.p_l[1].clone()};
		copy.p_r = new double[][]{(double[])this.p_r[0].clone(), (double[])this.p_r[1].clone()};
		copy.p_i = new double[][]{(double[])this.p_i[0].clone(), (double[])this.p_i[1].clone()};
		// add
		copy.addToDatabase();

		return copy;
	}

	private Object[] getTransformedData() {
		final double[][] p = transformPoints(this.p);
		final double[][] p_l = transformPoints(this.p_l);
		final double[][] p_r = transformPoints(this.p_r);
		final double[][] p_i = transformPoints(this.p_i);
		return new Object[]{p, p_l, p_r, p_i};
	}

	/** Takes a profile_list, scans for its Profile children, makes sublists of continuous profiles (if they happen to be branched), and then creates triangles for them using weighted vector strings. */
	static public List<Point3f> generateTriangles(final ProjectThing pt, final double scale) {
		if (!pt.getType().equals("profile_list")) {
			Utils.log2("Profile: ignoring unhandable ProjectThing type.");
			return null;
		}
		final ArrayList<ProjectThing> al = pt.getChildren(); // should be sorted by Z already
		if (al.size() < 2) {
			Utils.log("profile_list " + pt + " has less than two profiles: can't render in 3D.");
			return null;
		}
		// collect all Profile
		final HashSet<Profile> hs = new HashSet<Profile>();
		for (final ProjectThing child : al) {
			final Object ob = child.getObject();
			if (ob instanceof Profile) {
				hs.add((Profile)ob);
			} else {
				Utils.log2("Render: skipping non Profile class child");
			}
		}
		// Create sublists of profiles, following the chain of links.
		final Profile[] p = new Profile[hs.size()];
		hs.toArray(p);
		// find if at least one is visible
		boolean hidden = true;
		for (int i=0; i<p.length; i++) {
			if (p[i].visible) {
				hidden = false;
				break;
			}
			if (null == p[i] || 0 == p[i].n_points) {
				Utils.log("Cannot generate triangle mesh: empty profile " + p[i] + (null != p[i] ? " at layer " + p[i].getLayer() : ""));
				return null;
			}
		}
		if (hidden) return null;
		// collect starts and ends
		final HashSet<Profile> hs_bases = new HashSet<Profile>();
		final HashSet<Profile> hs_done = new HashSet<Profile>();
		final List<Point3f> triangles = new ArrayList<Point3f>();
		do {
			Profile base = null;
			// choose among existing bases
			if (hs_bases.size() > 0) {
				base = hs_bases.iterator().next();
			} else {
				// find a new base, simply by taking the lowest Z or remaining profiles
				double min_z = Double.MAX_VALUE;
				for (int i=0; i<p.length; i++) {
					if (hs_done.contains(p[i])) continue;
					final double z = p[i].getLayer().getZ();
					if (z < min_z) {
						min_z = z;
						base = p[i];
					}
				}
				// add base
				if (null != base) hs_bases.add(base);
			}
			if (null == base) {
				Utils.log2("No more bases.");
				break;
			}
			// crawl list to get a sequence of profiles in increasing or decreasing Z order, but not mixed z trends
			final ArrayList<Profile> al_profiles = new ArrayList<Profile>();
			//Utils.log2("Calling accumulate for base " + base);
			al_profiles.add(base);
			final Profile last = accumulate(hs_done, al_profiles, base, 0);
			// if the trend was not empty, add it
			if (last != base) {
				// count as done
				hs_done.addAll(al_profiles);
				// add new possible base (which may have only 2 links if it was from a broken Z trend)
				hs_bases.add(last);
				// create 3D object from base to base
				final Profile[] profiles = new Profile[al_profiles.size()];
				al_profiles.toArray(profiles);
				final List<Point3f> tri = makeTriangles(profiles, scale);
				if (null != tri) triangles.addAll(tri);
			} else {
				// remove base
				hs_bases.remove(base);
			}
		} while (0 != hs_bases.size());

		return triangles;
	}

	/** Recursive; returns the last added profile. */
	static private Profile accumulate(final HashSet<Profile> hs_done, final ArrayList<Profile> al, final Profile step, int z_trend) {
		final HashSet<Displayable> hs_linked = step.getLinked(Profile.class);
		if (al.size() > 1 && hs_linked.size() > 2) {
			// base found
			return step;
		}
		final double step_z = step.getLayer().getZ();
		Profile next_step = null;
		boolean started = false;
		for (final Iterator<Displayable> it = hs_linked.iterator(); it.hasNext(); ) {
			final Object ob = it.next();
			// loop only one cycle, to move only in one direction
			if (al.contains(ob) || started || hs_done.contains(ob)) continue;
			started = true;
			next_step = (Profile)ob;
			final double next_z = next_step.getLayer().getZ();
			if (0 == z_trend) {
				// define trend
				if (next_z > step_z) {
					z_trend = 1;
				} else {
					z_trend = -1;
				}
				// add!
				al.add(next_step);
			} else {
				// if the z trend is broken, finish
				if ( (next_z > step_z &&  1 == z_trend)
				  || (next_z < step_z && -1 == z_trend) ) {
					// z trend continues
					al.add(next_step);
				} else {
					// z trend broken
					next_step = null;
				}
			}
		}
		Profile last = step;
		if (null != next_step) {
			hs_done.add(next_step);
			last = accumulate(hs_done, al, next_step, z_trend);
		}
		return last;
	}

	/** Make a mesh as a calibrated list of 3D triangles.*/
	static private List<Point3f> makeTriangles(final Profile[] p, final double scale) {
		try {
			final VectorString2D[] sv = new VectorString2D[p.length];
			boolean closed = true; // dummy initialization
			final Calibration cal = p[0].getLayerSet().getCalibrationCopy();
			cal.pixelWidth *= scale;
			cal.pixelHeight *= scale;
			for (int i=0; i<p.length; i++) {
				if (0 == p[i].n_points) continue;
				if (0 == i) closed = p[i].closed;
				else if (p[i].closed != closed) {
					Utils.log2("All profiles should be either open or closed, not mixed.");
					return null;
				}
				sv[i] = p[i].getPerimeter2D(cal);
			}
			return SkinMaker.generateTriangles(sv, -1, -1, closed);
		} catch (final Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Does nothing. */
	@Override
	public boolean softRemove() {
		return true;
	}

	@Override
    protected boolean remove2(final boolean check) {
		return project.getProjectTree().remove(check, project.findProjectThing(this), null); // will call remove(check) here
	}

	/** Calibrated for pixel width only (that is, it assumes pixel aspect ratio 1:1), in units as specified at getLayerSet().getCalibration().getUnit() */
	public double computeLength() {
		if (-1 == n_points || 0 == this.p_i[0].length) setupForDisplay();
		if (this.p_i[0].length < 2) return 0;
		final double[][] p_i = transformPoints(this.p_i);
		double len = 0;
		for (int i=1; i<p_i[0].length; i++) {
			len += Math.sqrt(Math.pow(p_i[0][i] - p_i[0][i-1], 2) + Math.pow(p_i[1][i] - p_i[1][i-1], 2));
		}
		if (closed) {
			final int last = p[0].length -1;
			len += Math.sqrt(Math.pow(p_i[0][last] - p_i[0][0], 2) + Math.pow(p_i[1][last] - p_i[1][0], 2));
		}
		// to calibrate for pixelWidth and pixelHeight, I'd have to multiply each x,y values above separately
		return len * getLayerSet().getCalibration().pixelWidth;
	}

	/** Calibrated, in units as specified at getLayerSet().getCalibration().getUnit() -- returns zero if this profile is not closed. */
	public double computeArea() {
		if (-1 == n_points) setupForDisplay();
		if (n_points < 2) return 0;
		if (!closed) return 0;
		if (0 == p_i[0].length) generateInterpolatedPoints(0.05);
		final Calibration cal = getLayerSet().getCalibration();
		return M.measureArea(new Area(getPerimeter()), getProject().getLoader()) * cal.pixelWidth * cal.pixelHeight;
	}

	/** Measures the calibrated length, the lateral surface as the length times the layer thickness, and the volume (if closed) as the area times the layer thickness. */
	@Override
	public ResultsTable measure(ResultsTable rt) {
		if (null == rt) rt = Utils.createResultsTable("Profile results", new String[]{"id", "length", "side surface: length x thickness", "volume: area x thickness", "name-id"});
		if (-1 == n_points) setupForDisplay();
		if (n_points < 2) return null;
		if (0 == p_i[0].length) generateInterpolatedPoints(0.05);
		final Calibration cal = getLayerSet().getCalibration();
		// computeLength returns a calibrated length, so only calibrate the layer thickness:
		final double len = computeLength();
		final double surface_flat = len * layer.getThickness() * cal.pixelWidth;
		rt.incrementCounter();
		rt.addLabel("units", cal.getUnit());
		rt.addValue(0, id);
		rt.addValue(1, len);
		rt.addValue(2, surface_flat);
		final double volume = closed ? computeArea() * layer.getThickness() * cal.pixelWidth : 0;
		rt.addValue(3, volume);
		rt.addValue(4, getNameId());
		return rt;
	}

	/** Assumes Z-coord sorted list of profiles, as stored in a "profile_list" ProjectThing type. . */
	static public ResultsTable measure(final Profile[] profiles, ResultsTable rt, final long profile_list_id) {
		Utils.log2("profiles.length" + profiles.length);
		if (null == profiles || 0 == profiles.length) return null;
		if (1 == profiles.length) {
			// don't measure if there is only one
			return rt;
		}
		for (final Profile p : profiles) {
			if (null == p || 0 == p.n_points) {
				Utils.log("Cannot measure: empty profile " + p + (null != p ? " at layer " + p.getLayer() : ""));
				return rt;
			}
		}
		if (null == rt) rt = Utils.createResultsTable("Profile list results", new String[]{"id", "interpolated surface", "surface: sum of length x thickness", "volume", "name-id"});
		final Calibration cal = profiles[0].getLayerSet().getCalibration();
		// else, interpolate skin and measure each triangle
		final List<Point3f> tri = makeTriangles(profiles, 1.0); // already calibrated
		final int n_tri = tri.size();
		if (0 != n_tri % 3) {
			Utils.log("Profile.measure error: triangle verts list not a multiple of 3 for profile list id " + profile_list_id);
			return rt;
		}
		// Surface: calibrated sum of the area of all triangles in the mesh.
		double surface = 0;
		for (int i=2; i<n_tri; i+=3) {
			surface += M.measureArea(tri.get(i-2), tri.get(i-1), tri.get(i));
		}
		// add capping ends
		final double area_first = profiles[0].computeArea();
		final double area_last = profiles[profiles.length-1].computeArea();
		if (profiles[0].closed) surface += area_first;
		if (profiles[profiles.length-1].closed) surface += area_last;

		// Surface flat: sum of the perimeter lengths times the layer thickness
		double surface_flat = 0;
		for (int i=0; i<profiles.length; i++) {
			if (0 == profiles[i].p_i[0].length) profiles[i].generateInterpolatedPoints(0.05);
			surface_flat += profiles[i].computeLength() * profiles[i].layer.getThickness() * cal.pixelWidth;
		}

		// Volume: area times layer thickness
		double volume = area_first * profiles[0].layer.getThickness();
		for (int i=1; i<profiles.length-1; i++) {
			volume += profiles[i].computeArea() * profiles[i].layer.getThickness();
		}
		volume += area_last * profiles[profiles.length-1].layer.getThickness();

		// calibrate volume: the Z is still in pixels
		volume *= cal.pixelWidth;

		rt.incrementCounter();
		rt.addLabel("units", cal.getUnit());
		rt.addValue(0, profile_list_id);
		rt.addValue(1, surface);
		rt.addValue(2, surface_flat);
		rt.addValue(3, volume);
		double nameid = 0;
		try {
			nameid = Double.parseDouble(profiles[0].project.findProjectThing(profiles[0]).getParent().getTitle());
		} catch (final NumberFormatException nfe) {}
		rt.addValue(4, nameid);
		return rt;
	}

	@Override
	final Class<?> getInternalDataPackageClass() {
		return DPProfile.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPProfile(this);
	}

	static private final class DPProfile extends Displayable.DataPackage {
		final double[][] p, p_l, p_r, p_i;
		final boolean closed;

		DPProfile(final Profile profile) {
			super(profile);
			// store copies of all arrays
			this.p = new double[][]{Utils.copy(profile.p[0], profile.n_points), Utils.copy(profile.p[1], profile.n_points)};
			this.p_r = new double[][]{Utils.copy(profile.p_r[0], profile.n_points), Utils.copy(profile.p_r[1], profile.n_points)};
			this.p_l = new double[][]{Utils.copy(profile.p_l[0], profile.n_points), Utils.copy(profile.p_l[1], profile.n_points)};
			this.p_i = new double[][]{Utils.copy(profile.p_i[0], profile.p_i[0].length), Utils.copy(profile.p_i[1], profile.p_i[0].length)};
			this.closed = profile.closed;
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Profile profile = (Profile)d;
			final int len = p[0].length; // == n_points, since it was cropped on copy
			profile.p = new double[][]{Utils.copy(p[0], len), Utils.copy(p[1], len)};
			profile.n_points = p[0].length;
			profile.p_r = new double[][]{Utils.copy(p_r[0], len), Utils.copy(p_r[1], len)};
			profile.p_l = new double[][]{Utils.copy(p_l[0], len), Utils.copy(p_l[1], len)};
			profile.p_i = new double[][]{Utils.copy(p_i[0], p_i[0].length), Utils.copy(p_i[1], p_i[1].length)};
			profile.closed = closed;
			return true;
		}
	}

	// It's such a pitty that this code is almost identical to that of the Pipe, and it can't be abstracted effectively any further.
	@Override
    synchronized public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		if (this.layer != la) return true;
		double[] fp = null;
		mpicbg.models.CoordinateTransform chain = null;
		Area localroi = null;
		AffineTransform inverse = null;
		for (int i=0; i<n_points; i++) {
			if (null == localroi) {
				inverse = this.at.createInverse();
				localroi = roi.createTransformedArea(inverse);
			}
			if (localroi.contains(p[0][i], p[1][i])) {
				if (null == chain) {
					chain = M.wrap(this.at, ict, inverse);
					fp = new double[2];
				}
				// The point and its two associated control points:
				M.apply(chain, p, i, fp);
				M.apply(chain, p_l, i, fp);
				M.apply(chain, p_r, i, fp);
			}
		}
		if (null != chain) {
			generateInterpolatedPoints(0.05);
			calculateBoundingBox(true);
		}
		return true;
	}
	@Override
    public boolean apply(final VectorDataTransform vdt) throws Exception {
		if (vdt.layer != this.layer) return false;
		final double[] fp = new double[2];
		final VectorDataTransform vlocal = vdt.makeLocalTo(this);
		for (int i=0; i<n_points; i++) {
			for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
				if (rt.roi.contains(p[0][i], p[1][i])) {
					// The point and its two associated control points:
					M.apply(rt.ct, p, i, fp);
					M.apply(rt.ct, p_l, i, fp);
					M.apply(rt.ct, p_r, i, fp);
					break;
				}
			}
		}
		generateInterpolatedPoints(0.05);
		calculateBoundingBox(true);
		return true;
	}

	@Override
	synchronized public boolean isRoughlyInside(final Layer layer, final Rectangle r) {
		if (this.layer != layer) return false;
		try {
			final Rectangle box = this.at.createInverse().createTransformedShape(r).getBounds();
			for (int i=0; i<n_points; i++) {
				if (box.contains(p[0][i], p[1][i])) return true;
			}
		} catch (final NoninvertibleTransformException e) {
			IJError.print(e);
		}
		return false;
	}
}
