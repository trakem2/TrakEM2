/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.render3d.Perimeter2D;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Polygon;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Composite;
import java.awt.AlphaComposite;


/** A class to be a user-outlined profile over an image, which is painted with a particular color and also holds an associated text label.
 * TODO - label not implemented yet.
 * 	- can't paint segments in different colors yet
 * 	- the whole curve is updated when only a particular set of points needs readjustment
 * 	- also, points are smooth, and options should be given to make them non-smooth.
 */
public class Profile extends Displayable {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked points.*/
	protected double[][] p;
	/**The array of left control points, one for each clicked point.*/
	protected double[][] p_l;
	/**The array of right control points, one for each clicked point.*/
	protected double[][] p_r;
	/**The array of interpolated points generated from p, p_l and p_r.*/
	protected double[][] p_i;
	/**Paint/behave as open or closed curve.*/
	protected boolean closed = false;

	/** A new user-requested Profile.*/
	public Profile(Project project, String title, double x, double y) {
		super(project, title, x, y);
		n_points = 0;
		p = new double[2][5];
		p_l = new double[2][5];
		p_r = new double[2][5];
		p_i = new double[2][0];
		addToDatabase();
	}

	/**Construct a Bezier Profile object from a set of points mixed in this pattern: PCCPCCPCCPCCP , so, [PCC]n  where P = backbone point and C = control point. This results from a BezierApproximation on a path of points as drawed with the mouse. Keep in mind the control points will NOT have the same tangents, but this may be either left like that or corrected with some smoothing algorithm.*/
	public Profile(Project project, String title, double x, double y, Point2D.Double[] points) {
		super(project, title, x, y);
		//setup arrays
		int size = (points.length / 3) + 1;
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
		int last = points.length-1;
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
	public Profile(Project project, long id, String title, double x, double y, float alpha, boolean visible, Color color, double[][][] bezarr, boolean closed, boolean locked) {
		super(project, id, title, x, y, locked);
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
	public Profile(Project project, long id, String title, double x, double y, double width, double height, float alpha, boolean visible, Color color, boolean closed, boolean locked) {
		super(project, id, title, x, y, locked);
		this.width = width;
		this.height = height;
		this.visible = visible;
		this.alpha = alpha;
		this.color = color;
		this.closed = closed;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Construct a Bezier Profile from an XML entry. */
	public Profile(Project project, long id, Hashtable ht, Hashtable ht_links) {
		super(project, id, ht, ht_links);
		// parse data
		for (Enumeration e = ht.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht.get(key);
			if (key.equals("d")) {
				// parse the SVG points data
				ArrayList al_p = new ArrayList();
				ArrayList al_p_r = new ArrayList();
				ArrayList al_p_l = new ArrayList();// needs shifting, inserting one point at the beginning if not closed.
				// sequence is: M p[0],p[1] C p_r[0],p_r[1] p_l[0],p_l[1] and repeat without the M, and finishes with the last p[0],p[1]. If closed, appended at the end is p_r[0],p_r[1] p_l[0],p_l[1]
				// first point:
				int i_start = data.indexOf('M');
				int i_end = data.indexOf('C');
				String point = data.substring(i_start+1, i_end).trim();
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
					String[] points = txt.split(" ");
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
				this.closed = (-1 != data.lastIndexOf('z')); // 'z' must be lowercase to comply with SVG style
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
					String[] sp = ((String)al_p.get(i)).split(",");
					p[0][i] = Double.parseDouble(sp[0]);
					p[1][i] = Double.parseDouble(sp[1]);
					sp = ((String)al_p_l.get(i)).split(",");
					p_l[0][i] = Double.parseDouble(sp[0]);
					p_l[1][i] = Double.parseDouble(sp[1]);
					sp = ((String)al_p_r.get(i)).split(",");
					p_r[0][i] = Double.parseDouble(sp[0]);
					p_r[1][i] = Double.parseDouble(sp[1]);
				}
				this.p_i = new double[2][0]; // empty
				generateInterpolatedPoints(0.05);
			}
		}
	}

	/** A constructor for cloning purposes. */
	private Profile(Project project, String title, double x, double y, double width, double height, float alpha, Color color, int n_points, double[][] p, double[][] p_r, double[][] p_l, double[][] p_i, boolean closed) {
		super(project, title, x, y);
		this.width = width;
		this.height = height;
		this.alpha = alpha;
		this.color = color; 			// TODO: no rotation?
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
		int length = p[0].length;
		//make copies
		double[][] p_copy = new double[2][length + 5];
		double[][] p_l_copy = new double[2][length + 5];
		double[][] p_r_copy = new double[2][length + 5];
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

	public boolean isClosed() {
		return this.closed;
	}

	/**Find a point in an array 'a', with a precision dependent on the magnification. */
	protected int findPoint(double[][] a, double x_p, double y_p, double magnification) {
		int index = -1;
		// make parameters local
		double d = (7.0D / magnification);
		for (int i=0; i<n_points; i++) {
			if ((Math.abs(x_p - a[0][i]) + Math.abs(y_p - a[1][i])) <= d) {
				index = i;
			}
		}
		return index;
	}

	/**Remove a point from the bezier backbone and its two associated control points.*/
	protected void removePoint(int index) {
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
	protected double distance(double x1, double y1, double x2, double y2) {
		return Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1));
	}

	/**Move backbone point.*/
	protected void dragPoint(int index, int dx, int dy) {
		p[0][index] += dx;
		p[1][index] += dy;
		p_l[0][index] += dx;
		p_l[1][index] += dy;
		p_r[0][index] += dx;
		p_r[1][index] += dy;
	}

	/**Set the control points to the same value as the backbone point which they control.*/
	protected void resetControlPoints(int index) {
		p_l[0][index] = p[0][index];
		p_l[1][index] = p[1][index];
		p_r[0][index] = p[0][index];
		p_r[1][index] = p[1][index];
	}

	/**Drag a control point and adjust the other, dependent one, in a symmetric way or not.*/
	protected void dragControlPoint(int index, double x_d, double y_d, double[][] p_dragged, double[][] p_adjusted, boolean symmetric) {
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
		double angle = Math.atan2(p_dragged[0][index] - p[0][index], p_dragged[1][index] - p[1][index]) + Math.PI;
		//apply
		p_dragged[0][index] = x_d;
		p_dragged[1][index] = y_d;
		p_adjusted[0][index] = p[0][index] + hypothenusa * Math.sin(angle); // it's sin and not cos because of stupid Math.atan2 way of delivering angles
		p_adjusted[1][index] = p[1][index] + hypothenusa * Math.cos(angle);
	}

	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. Does not update the database. x_p,y_p are in the local space. */
	protected int addPoint(double x_p, double y_p, double magnification, double bezier_finess) {
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
			int sh_length = n_points -index;
			double[][] p_copy = new double[2][sh_length];
			double[][] p_l_copy = new double[2][sh_length];
			double[][] p_r_copy = new double[2][sh_length];
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
	protected int findClosestPoint(double x_p, double y_p, double magnification, double bezier_finess) {
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
	protected void toggleClosed() {
		if (closed) {
			closed = false;
		} else {
			closed = true;
		}
		//update database
		updateInDatabase("closed");
	}

	protected void generateInterpolatedPoints(double bezier_finess) {
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
					double[][] p_i_copy = new double[2][p_i[0].length + 5];
					System.arraycopy(p_i[0], 0, p_i_copy[0], 0, p_i[0].length);
					System.arraycopy(p_i[1], 0, p_i_copy[1], 0, p_i[1].length);
					p_i = p_i_copy;
				}
			}
		}
		if (p_i[0].length != next) { // 'next' works as a length here
			//resize back
			double[][] p_i_copy = new double[2][next];
			System.arraycopy(p_i[0], 0, p_i_copy[0], 0, next);
			System.arraycopy(p_i[1], 0, p_i_copy[1], 0, next);
			p_i = p_i_copy;
		}
	}

	/**Paint the bezier curve in the given graphics.*/
	public void paint(Graphics g, double magnification, Rectangle srcRect, Rectangle clipRect, boolean active, int channels, Layer active_layer) {
		if (0 == n_points) {
			return;
		}

		// check if it has to be painted at all
		if (super.isOutOfRepaintingClip(magnification, srcRect, clipRect)) {
			return;
		}

		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
			if (-1 == n_points) {
				Utils.log2("Profile.paint: Some error ocurred, can't load points from database.");
				return;
			}
		}
		if (n_points < 1 || null == p_i || 0 == p_i.length) return;

		// translate graphics origin of coordinates
		Graphics2D g2d = (Graphics2D)g;
		g2d.translate((x - srcRect.x)* magnification, (y - srcRect.y)* magnification);

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g2d.getComposite();
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		//
		// TODO add methods to make it paint solid, as an area. Such a feature will require a different color (or XORed color) for the outline, so that draggable points are visible.
		// BEWARE that the g.fillPolygon() is a very time-consuming method.

		if (active) {
			//draw/fill points
			for (int j=0; j<n_points; j++) { //TODO there is room for optimization, operations are being done twice or 3 times; BUT is the creation of new variables as costly as the calculations? I have no idea.
				//draw big ovals at backbone points
				//g.drawOval((int)(p[0][j]*magnification) -3, (int)(p[1][j]*magnification) -3, 6, 6);
				DisplayCanvas.drawHandle(g, (int)(p[0][j]*magnification), (int)(p[1][j]*magnification));
				g.setColor(this.color);
				//fill small ovals at control points
				g.fillOval((int)(p_l[0][j]*magnification) -3, (int)(p_l[1][j]*magnification) -3, 4, 4);
				g.fillOval((int)(p_r[0][j]*magnification) -3, (int)(p_r[1][j]*magnification) -3, 4, 4);
				//draw lines between backbone and control points
				g.drawLine((int)(p[0][j]*magnification), (int)(p[1][j]*magnification), (int)(p_l[0][j]*magnification), (int)(p_l[1][j]*magnification));
				g.drawLine((int)(p[0][j]*magnification), (int)(p[1][j]*magnification), (int)(p_r[0][j]*magnification), (int)(p_r[1][j]*magnification));
			}
		}

		//set color
		g.setColor(this.color);

		//draw lines between any two consecutive interpolated points
		for (int i=0; i<p_i[0].length-1; i++) {
			g.drawLine((int)(p_i[0][i]*magnification), (int)(p_i[1][i]*magnification), (int)(p_i[0][i+1]*magnification), (int)(p_i[1][i+1]*magnification));
		}
		//draw last segment between last and first points, only if closed:
		if (closed) {
			g.drawLine((int)(p_i[0][p_i[0].length-1]*magnification), (int)(p_i[1][p_i[0].length-1]*magnification), (int)(p_i[0][0]*magnification), (int)(p_i[1][0]*magnification));
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g2d.setComposite(original_composite);
		}
		// undo transport painting pointer
		g2d.translate(- (x - srcRect.x)* magnification, - (y - srcRect.y)* magnification);
	}

	/** A method to paint with no ovals or control points, just the outline of the Profile; no magnification or srcRect are considered. */
	public void paint(Graphics g, Layer active_layer) {
		if (!this.visible) return;

		if (-1 == n_points) setupForDisplay(); // reload
		if (n_points < 1 || null == p_i || 0 == p_i.length) return;

		Graphics2D g2d = (Graphics2D)g;
		//translate
		g2d.translate(x, y);
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g2d.getComposite();
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		//set color
		g.setColor(this.color);

		//draw lines between any two consecutive interpolated points
		for (int i=0; i<p_i[0].length-1; i++) {
			g.drawLine((int)(p_i[0][i]), (int)(p_i[1][i]), (int)(p_i[0][i+1]), (int)(p_i[1][i+1]));
		}
		//draw last segment between last and first points, only if closed:
		if (closed) {
			g.drawLine((int)(p_i[0][p_i[0].length-1]), (int)(p_i[1][p_i[0].length-1]), (int)(p_i[0][0]), (int)(p_i[1][0]));
		}

		//Transparency: fix composite back to original.
		if (alpha != 1.0f) {
			g2d.setComposite(original_composite);
		}
		// undo translation
		g2d.translate(-x, -y);
	}

	/** For painting while transforming. The given box is in offscreen coords. */
	public void paint(Graphics g, double magnification, Rectangle srcRect, Rectangle clipRect, boolean active, int channels, Layer active_layer, Transform t) {
		if (0 == n_points) return;

		// check if it has to be painted at all
		if (super.isOutOfRepaintingClip(magnification, srcRect, clipRect)) {
			return;
		}

		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
		}
		if (n_points < 1 || null == p_i || 0 == p_i.length) return;

		Graphics2D g2d = (Graphics2D)g;

		// translate graphics origin of coordinates
		g2d.translate((int)((t.x + t.width/2 -srcRect.x)*magnification), (int)((t.y + t.height/2 -srcRect.y)*magnification));
		double sx = t.width / this.width * magnification;
		double sy = t.height / this.height * magnification;
		double cx = this.width/2; // center of data
		double cy = this.height/2;
		AffineTransform original = g2d.getTransform();
		g2d.rotate((t.rot * Math.PI) / 180); //(t.rot * 2 * Math.PI / 360); //Math.toRadians(rot));

		//arrange transparency
		Composite original_composite = null;
		if (t.alpha != 1.0f) {
			original_composite = g2d.getComposite();
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, t.alpha));
		}
		//
		// TODO add methods to make it paint solid, as an area. Such a feature will require a different color (or XORed color) for the outline, so that draggable points are visible.
		// BEWARE that the g.fillPolygon() is a very time-consuming method.

		if (active && (ProjectToolbar.getToolId() == ProjectToolbar.PEN || ProjectToolbar.getToolId() == ProjectToolbar.PENCIL)) {
			//draw/fill points
			for (int j=0; j<n_points; j++) { //TODO there is room for optimization, operations are being done twice or 3 times; BUT is the creation of new variables as costly as the calculations? I have no idea.
				//draw big ovals at backbone points
				//g.drawOval((int)(p[0][j]*magnification) -3, (int)(p[1][j]*magnification) -3, 6, 6);
				DisplayCanvas.drawHandle(g, (int)((p[0][j] - cx)*sx), (int)((p[1][j] - cy)*sy));
				g.setColor(this.color);
				//fill small ovals at control points
				g.fillOval((int)((p_l[0][j] -cx) * sx) -3, (int)((p_l[1][j] -cy) * sy) -3, 4, 4);
				g.fillOval((int)((p_r[0][j] -cx) * sx) -3, (int)((p_r[1][j] -cy) * sy) -3, 4, 4);
				//draw lines between backbone and control points
				g.drawLine((int)((p[0][j] -cx) * sx), (int)((p[1][j] -cy) * sy), (int)((p_l[0][j] -cx) * sx), (int)((p_l[1][j] - cy) * sy));
				g.drawLine((int)((p[0][j] -cx) * sx), (int)((p[1][j] -cy) * sy), (int)((p_r[0][j] -cx) * sx), (int)((p_r[1][j] - cy) * sy));
			}
		}

		//set color
		g.setColor(this.color);

		//draw lines between any two consecutive interpolated points
		for (int i=0; i<p_i[0].length-1; i++) {
			g.drawLine((int)((p_i[0][i] -cx) * sx), (int)((p_i[1][i] -cy) * sy), (int)((p_i[0][i+1] -cx) * sx), (int)((p_i[1][i+1] -cy) * sy));
		}
		//draw last segment between last and first points, only if closed:
		if (closed) {
			g.drawLine((int)((p_i[0][p_i[0].length-1] -cx) * sx), (int)((p_i[1][p_i[0].length-1] -cy) * sy), (int)((p_i[0][0] -cx) * sx), (int)((p_i[1][0] -cy) * sy));
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g2d.setComposite(original_composite);
		}
		// undo scale
		// DONE BELOW //g2d.scale(1/sx, 1/sy);
		g2d.setTransform(original);
		// undo transport painting pointer
		g2d.translate( - (int)((t.x + t.width/2 -srcRect.x)*magnification), - (int)((t.y + t.height/2 -srcRect.y)*magnification));
	}

	/**Helper vars for mouse events. It's safe to have them static since only one Profile will be edited at a time.*/
	static private int index = -1;
	static private int index_l = -1;
	static private int index_r = -1;
	static private boolean is_new_point = false;

	/**Execute the mousePressed MouseEvent on this Profile.*/
	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		if (0 == n_points) {
			this.x = x_p;
			this.y = y_p;
		}
		// make local
		x_p = x_p - (int)this.x;
		y_p = y_p - (int)this.y;

		int tool = ProjectToolbar.getToolId();

		// reset helper vars
		is_new_point = false;
		index = index_r = index_l = -1;

		if (ProjectToolbar.PEN == tool) {

			//collect vars
			if (me.isControlDown() && me.isShiftDown()) {
				index = findNearestPoint(p, n_points, x_p, y_p);
			} else {
				index = findPoint(p, x_p, y_p, mag);
			}

			if (-1 != index) {
				if (me.isControlDown() && me.isShiftDown()) {
					//delete point
					removePoint(index);
					index = index_r = index_l = -1;
					generateInterpolatedPoints(0.05);
					repaint();
					return;
				} else if (me.isAltDown()) {
					resetControlPoints(index);
					return;
				} else if (me.isShiftDown()) {
					//preconditions: at least 2 points!
					if (n_points < 2) {
						return;
					}
					toggleClosed();
					generateInterpolatedPoints(0.05);
					repaint();
					return;
				} else if (0 == index && n_points > 1 && !closed) {
					//close curve, reset left control point of the first point and set it up for dragging
					closed = true;
					updateInDatabase("closed");
					p_l[0][0] = p[0][0];
					p_l[1][0] = p[1][0];
					index = -1;
					index_r = -1;
					index_l = 0; //the first one
					repaint();
					return;
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
				repaint();
				return;
			}
		}
	}

	/**Execute the mouseDragged MouseEvent on this Profile.*/
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {

		//make local
		x_p = x_p - (int)this.x;
		y_p = y_p - (int)this.y;
		x_d = x_d - (int)this.x;
		y_d = y_d - (int)this.y;
		x_d_old = x_d_old - (int)this.x;
		y_d_old = y_d_old - (int)this.y;

		int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {

			//if a point in the backbone is found, then:
			if (-1 != index) {
				if (!me.isAltDown()) {
					//drag point
					//dragPoint(index, x_d, y_d);
					dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				} else { //TODO in linux the alt+click is stolen by the KDE window manager but then the middle-click works as if it was the alt+click. Weird!
					//drag both control points symmetrically
					dragControlPoint(index, x_d, y_d, p_l, p_r, true);
				}
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}

			//if a control point is found, then drag it, adjusting the other control point non-symmetrically
			if (-1 != index_r) {
				dragControlPoint(index_r, x_d, y_d, p_r, p_l, is_new_point);
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}
			if (-1 != index_l) {
				dragControlPoint(index_l, x_d, y_d, p_l, p_r, is_new_point);
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}
			
			// no points selected. Drag the whole curve on alt down (without affecting linked curves)
			if (me.isAltDown()) {
				int dx = x_d - x_d_old;
				int dy = y_d - y_d_old;
				this.x += dx;
				this.y += dy;
				repaint();
				return;
			}
		}

		// Drag the whole curve
		else if (ProjectToolbar.SELECT == tool/* && contains(x_p, y_p)*/) { // TODO this contains can be computationally VERY expensive on an awt.Polygon
			super.drag(x_d - x_d_old, y_d - y_d_old);
			repaint(); // repaint only this one, which is the active and thus the one receiving the event
			return;
		}
	}

	/**Execute the mouseReleased MouseEvent on this Profile.*/
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		int tool = ProjectToolbar.getToolId();
		if (ProjectToolbar.PEN == tool) {
			//generate interpolated points
			generateInterpolatedPoints(0.05);
			repaint(); //needed at least for the removePoint, and also for repainting the DisplayablePanel (with the snapshot) and the DisplayNavigator // TODO this may be redundant with below
		}

		//update points in database if there was any change
		if (-1 != index || -1 != index_r || -1 != index_l) {
			updateInDatabase("points");
			updateInDatabase("position+dimensions"); //was: dimensions
			//remake snapshot
			snapshot.remake();
			//repaint snapshot
			Display.repaint(layer, this); // the DisplayablePanel
		} else if (x_r != x_p || y_r != y_p) {
			updateInDatabase("position+dimensions");
			//repaint snapshot
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
	protected void calculateBoundingBox(boolean adjust_position) {
		if (0 == n_points) {
			this.width = this.height = 0.0D;
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

		this.width = max_x - min_x;
		this.height = max_y - min_y;

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
			this.x += min_x;
			this.y += min_y;
		}
	}


	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Profile. */
	public void repaint() {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox();
		box.add(getBoundingBox(null));
		Display.repaint(layer, this, box, 5);
	}

	/**Check if the given point (usually from a MOUSE_PRESSED MouseEvent) is contained within the boundaries of this object.*/
	public boolean containsPoint(int x_p, int y_p) {
		// as in getPerimeter():
		int n_i = p_i[0].length;
		int[] intx = new int[n_i];
		int[] inty = new int[n_i];
		for (int i=0; i<n_i; i++) {
			intx[i] = (int)p_i[0][i];
			inty[i] = (int)p_i[1][i];
		}
		Polygon polygon = new Polygon(intx, inty, n_i);
		return polygon.contains(x_p, y_p);
	}

	/**Release all memory resources taken by this object.*/
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
			double[][][] bezarr = project.getLoader().fetchBezierArrays(this.id);
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
			//calculate width and height
			// HASN'T CHANGED //calculateBoundingBox(false);
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
		n_points = -1; // flag that points exist
	}

	/** The perimeter of this profile, in integer precision. */
	public Polygon getPerimeter() {
		if (-1 == n_points) setupForDisplay();
		if (null == p_i) return null; // has been flushed, incorrect access! This is a patch.
		int n_i = p_i[0].length;
		int[] intx = new int[n_i];
		int[] inty = new int[n_i];
		for (int i=0; i<n_i; i++) {
			intx[i] = (int)p_i[0][i];
			inty[i] = (int)p_i[1][i];
		}
		return new Polygon(intx, inty, n_i);
	}

	/** The perimeter of this profile, in integer precision, translated to origin xo,yo */
	public Polygon getPerimeter(double xo, double yo) {
		if (-1 == n_points) setupForDisplay();
		if (null == p_i) return null; // has been flushed, incorrect access! TODO maybe reload with setupForDisplay() ? But wouldn't it be there, if it is being tested on, presumably from a mouse click?
		int n_i = p_i[0].length;
		int[] intx = new int[n_i];
		int[] inty = new int[n_i];
		for (int i=0; i<n_i; i++) {
			intx[i] = (int)(xo + p_i[0][i]);
			inty[i] = (int)(yo + p_i[1][i]);
		}
		return new Polygon(intx, inty, n_i);
	}

	/** Writes the data of this object as a Bezier object in the .shapes file represented by the 'data' StringBuffer. The z_scale is added to manually correct for sample squashing under the coverslip. */
	public void toShapesFile(StringBuffer data, String group, String color, double z_scale) {
		if (-1 == n_points) setupForDisplay(); // reload
		double z = layer.getZ();
		String l = "\n";
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
			data.append("p x=").append(x + p[0][i]).append(l)
			    .append("p y=").append(y + p[1][i]).append(l)
			    .append("p_r x=").append(x + p_r[0][i]).append(l)
			    .append("p_r y=").append(y + p_r[1][i]).append(l)
			    .append("p_l x=").append(x + p_l[0][i]).append(l)
			    .append("p_l y=").append(y + p_l[1][i]).append(l)
			;
		}
	}

	public void exportSVG(StringBuffer data, double z_scale, String indent) {
		String in = indent + "\t";
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		data.append(indent).append("<path\n")
		    .append(in).append("type=\"profile\"\n")
		    .append(in).append("id=\"").append(id).append("\"\n")
		    .append(in).append("x=\"").append(x).append("\"\n")
		    .append(in).append("y=\"").append(y).append("\"\n")
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
			int len = hs_linked.size();
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				data.append(((DBObject)ob).getId());
				if (ii != len-1) data.append(',');
				ii++;
			}
		}
		data.append("\"\n")
		    .append(indent).append("/>\n")
		;
	}

	/** Returns a deep copy of this Profile, with a new id.*/
	/*
	public Object clone() {
		double[][] p = new double[2][n_points];
		System.arraycopy(this.p[0], 0, p[0], 0, n_points);
		System.arraycopy(this.p[1], 0, p[1], 0, n_points);
		double[][] p_l=new double[2][n_points];
		System.arraycopy(this.p_l[0], 0, p_l[0], 0, n_points);
		System.arraycopy(this.p_l[1], 0, p_l[1], 0, n_points);
		double[][] p_r=new double[2][n_points];
		System.arraycopy(this.p_r[0], 0, p_r[0], 0, n_points);
		System.arraycopy(this.p_r[1], 0, p_r[1], 0, n_points);
		double[][] p_i = new double[2][this.p_i[0].length];
		System.arraycopy(this.p_i[0], 0, p_i[0], 0, this.p_i[0].length);
		System.arraycopy(this.p_i[1], 0, p_i[1], 0, this.p_i[0].length);

		return new Profile(this.project, this.title, this.x, this.y, this.width, this.height, this.alpha, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.n_points, p, p_r, p_l, p_i, this.closed);
	}
	*/


	/** Returns a triple array, each containing a [2][n_points] array specifiying the x,y of each left control point, backbone point and right control point respectively.*/
	public double[][][] getBezierArrays() {
		//assumes the profile is a Bezier curve.
		//put points and control points into PGpoint objects, as: LPRLPRLPR... (L = left control point, P = backbone point, R = right control point)
		if (-1 == n_points) setupForDisplay(); // reload
		final double[][][] b = new double[3][][];
		b[0] = p_l;
		b[1] = p;
		b[2] = p_r;
		return b;
	}

	public boolean isDeletable() {
		return 0 == n_points;
	}

	/** Not accepted if new bounds will leave the Displayable beyond layer bounds. */
	public void setBounds(double x, double y, double width, double height) {
		if (x + width <= 0 || y + height <= 0 || x >= layer.getLayerWidth() || y >= layer.getLayerHeight()) return;
		// scale the points
		double sx = width / this.width;
		double sy = height / this.height;
		for (int i=0; i<n_points; i++) {
			p[0][i] *= sx;	p[1][i] *= sy;
			p_r[0][i] *= sx; p_r[1][i] *= sy;
			p_l[0][i] *= sx; p_l[1][i] *= sy;
		}
		generateInterpolatedPoints(0.05);
		updateInDatabase("points");
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		updateInDatabase("position+dimensions");
		snapshot.remake();
		Display.updatePanel(layer, this);
	}

	/** Returns true if it's linked to at least one patch in the same Layer. Otherwise returns false. */
	public boolean isLinked() {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (d instanceof Patch && d.layer.equals(this.layer)) return true;
		}
		return false;
	}

	/** Returns false if the target_layer contains a profile that is directly linked to this profile. */
	public boolean canSendTo(Layer target_layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
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

	protected void setPoints(double[][] p_l, double[][] p, double[][] p_r) {
		this.p_l = p_l;
		this.p = p;
		this.p_r = p_r;
		this.n_points = p_l[0].length;
		this.generateInterpolatedPoints(0.05);
	}

	protected void addPointsAtBegin(double[][] new_p_l, double[][] new_p, double[][] new_p_r) {
		double[][] tmp_p_l = new double[2][p_l[0].length + new_p_l[0].length];
		double[][] tmp_p = new double[2][p[0].length + new_p[0].length];
		double[][] tmp_p_r = new double[2][p_r[0].length + new_p_r[0].length];
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

	protected void addPointsAtEnd(double[][] new_p_l, double[][] new_p, double[][] new_p_r) {
		double[][] tmp_p_l = new double[2][p_l[0].length + new_p_l[0].length];
		double[][] tmp_p = new double[2][p[0].length + new_p[0].length];
		double[][] tmp_p_r = new double[2][p_r[0].length + new_p_r[0].length];
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

	public void rotateData(int direction) {
		boolean flushed = false;
		if (-1 == n_points) {
			setupForDisplay(); //reload
			flushed = true;
		}
		if (0 == n_points) return;
		double tmp;
		for (int i=0; i<n_points; i++) {
			switch (direction) {
				case LayerSet.R90:
					tmp = p[0][i]; // tmp is x0
					p[0][i] = height - p[1][i]; // x1 = height - y0
					p[1][i] = tmp; // y1 = x0
					tmp = p_r[0][i];
					p_r[0][i] = height - p_r[1][i];
					p_r[1][i] = tmp;
					tmp = p_l[0][i];
					p_l[0][i] = height - p_l[1][i];
					p_l[1][i] = tmp;
					break;
				case LayerSet.R270:
					tmp = p[0][i];
					p[0][i] = p[1][i]; 
					p[1][i] = width - tmp;
					tmp = p_r[0][i];
					p_r[0][i] = p_r[1][i];
					p_r[1][i] = width - tmp;
					tmp = p_l[0][i];
					p_l[0][i] = p_l[1][i];
					p_l[1][i] = width - tmp;
					break;
				case LayerSet.FLIP_HORIZONTAL:
					p[0][i] = width - p[0][i];
					p_r[0][i] = width - p_r[0][i];
					p_l[0][i] = width - p_l[0][i];
					break;
				case LayerSet.FLIP_VERTICAL:
					p[1][i] = height - p[1][i];
					p_r[1][i] = height - p_r[1][i];
					p_l[1][i] = height - p_l[1][i];
					break;
			}
		}

		// debug:
		//for (int i=0; i<n_points; i++) {
		//	System.out.println("x1,y1: " + p[0][i] + " , " + p[1][i]);
		//}

		updateInDatabase("points");
		generateInterpolatedPoints(0.05);
		// restore loaded state
		if (flushed) flush();
	}

	public int getNearestPointIndex(double x_p, double y_p) {
		int ret = -1;
		double minDist = Double.POSITIVE_INFINITY;
		for (int i = 0; i < this.n_points; i++) {
			double dx = this.p[0][i]-x_p;
			double dy = this.p[1][i]-y_p;
			double dist = dx*dx+dy*dy;
			if(dist < minDist)
			{
				minDist = dist;
				ret = i;
			}
		}
		return ret;
	}

	public void insertBetween(int startIndex, int endIndex, double[][] tmp_p_l, double[][] tmp_p, double[][] tmp_p_r){
		if(endIndex < startIndex)
		{
			for (int i = 0; i < 2; i++) {
				for (int j = 0; j < tmp_p[0].length/2; j++) {
					double tmppl = tmp_p_l[i][j];
					double tmpp = tmp_p[i][j];
					double tmppr = tmp_p_r[i][j];
					
					tmp_p_r[i][j] = tmp_p_l[i][tmp_p_l[0].length -1- j];
					tmp_p[i][j] = tmp_p[i][tmp_p[0].length -1- j];
					tmp_p_l[i][j] = tmp_p_r[i][tmp_p_r[0].length -1- j];
					
					tmp_p_r[i][tmp_p_l[0].length -1- j]=tmppl;
					tmp_p[i][tmp_p[0].length -1- j]=tmpp;
					tmp_p_l[i][tmp_p_r[0].length -1- j]=tmppr;
				}
			}

			int tmp = startIndex;
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
		
		
		
		double[][] new_p_l = new double[2][beginning_p_l[0].length + ending_p_l[0].length + tmp_p_l[0].length];
		double[][] new_p = new double[2][beginning_p[0].length + ending_p[0].length + tmp_p[0].length];
		double[][] new_p_r = new double[2][beginning_p_r[0].length + ending_p_r[0].length + tmp_p_r[0].length];
		
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
	public void snapTo(int cx, int cy, int x_p, int y_p) { // WARNING: DisplayCanvas is locking at mouseDragged when the cursor is outside the DisplayCanvas Component, so this is useless or even harmful at the moment. 
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

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_profile\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;\"\n")
		       .append(in).append("d=\"M")
		;
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
		sb_body.append(indent).append("/>\n");
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_profile";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_profile EMPTY>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2)
		;
	}

	public void setTransform(Transform t) {
		if (-1 == n_points) {
			setupForDisplay(); // reload
		}
		if (0 == n_points) {
			Utils.log("WARNING: attempted to set a Transform to empty Profile " + this + "  with id=" + id);
			return;
		}
		// Remember: data is local, the x,y makes it global
		// center is:
		double cx = this.width/2; // points are local!
		double cy = this.height/2;
		double sx = t.width / this.width;
		double sy = t.height / this.height;
		// scale data relative to center
		if (t.width != this.width || t.height != this.height) {
			this.p = scalePoints(p, sx, sy, cx, cy);
			this.p_r = scalePoints(p_r, sx, sy, cx, cy);
			this.p_l = scalePoints(p_l, sx, sy, cx, cy);
		}
		// rotate data relative to center
		if (0 != t.rot) {
			double r = Math.toRadians(t.rot);
			this.p = rotatePoints(p, r, cx, cy);
			this.p_r = rotatePoints(p_r, r, cx, cy);
			this.p_l = rotatePoints(p_l, r, cx, cy);
		}
		// reset transform data:
		if (0 != t.rot || t.width != this.width || t.height != this.height) {
			double dx = t.x - this.x; //displacement, needed when there is a rotation and a displacement, to correct the x,y generated by the calculateBoundingBox
			double dy = t.y - this.y;
			// update width & height and interpolated points
			generateInterpolatedPoints(0.05);
			calculateBoundingBox(true); // will CHANGE this.x and this.y
			t.x = this.x + (t.width - t.width/sx)/2 + dx; // this.x and this.y has been changed by calculateBoundingBox
			t.y = this.y + (t.height - t.height/sy)/2 + dy; // TODO there may be some avoidable loss of precission here (we love floating-point math, uh?)
			t.width = this.width; // should be equal anyway
			t.height = this.height;
			t.rot = 0;
		}
		// update database position, dimensions, etc.
		super.setTransform(t);
		updateInDatabase("points");
	}

	/** Returns the interpolated points as a Perimeter2D. */
	public Perimeter2D getPerimeter2D() {
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return null;
		if (0 == p_i[0].length) generateInterpolatedPoints(0.05);
		double[] x = new double[p_i[0].length];
		double[] y = new double[x.length];
		System.arraycopy(p_i[0], 0, x, 0, x.length);
		System.arraycopy(p_i[1], 0, y, 0, x.length); // breaking symmetry in purpose, same result
		return new Perimeter2D(x, y, layer.getZ(), this.closed);
	}

	public void keyPressed(KeyEvent ke) {
		super.keyPressed(ke);
		if (ke.isConsumed()) return;
		int key_code = ke.getKeyCode();
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
		}
		if (ke.isConsumed()) {
			Display.repaint(this.layer, box, 5);
		}
	}

	public void setColor(Color c) {
		// propagate to al linked profiles within the same profile_list
		setColor(c, new HashSet());
	}

	/** Exploits the fact that Profile instances among the directly linked as returned by getLinked(Profile.class) will be members of the same profile_list. */
	private void setColor(Color c, HashSet hs_done) {
		if (hs_done.contains(this)) return;
		hs_done.add(this);
		super.setColor(c);
		HashSet hs = getLinked(Profile.class);
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Profile p = (Profile)it.next();
			p.setColor(c, hs_done);
		}
	}

	/** Performs a deep copy of this object, unlocked and visible. */
	public Object clone() {
		final Profile copy = new Profile(project, project.getLoader().getNextId(), null != title ? title.toString() : null, x, y, width, height, alpha, true, new Color(color.getRed(), color.getGreen(), color.getBlue()), closed, false);
		// the rotation is always zero because it has been applied.
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_l = new double[][]{(double[])this.p_l[0].clone(), (double[])this.p_l[1].clone()};
		copy.p_r = new double[][]{(double[])this.p_r[0].clone(), (double[])this.p_r[1].clone()};
		copy.p_i = new double[][]{(double[])this.p_i[0].clone(), (double[])this.p_i[1].clone()};
		// add
		copy.addToDatabase();
		// the snapshot has been already created in the Displayable constructor, but needs updating
		snapshot.remake();

		return copy;
	}
}
