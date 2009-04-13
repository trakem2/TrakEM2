/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006, 2007 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.render3d;

import ini.trakem2.utils.M;

/** Represents a contour, outline, perimeter or profile, hatever you want to call it, with both x,y coordinates and the vectors from one point to the next, mirroring the homonimous struct in CurveMorphing_just_C.c . Fine-tuned for speed, direct access to fields within the package. */
public class Perimeter2D {

	// protected in purpose: access within the package
	protected double[] x;
	protected double[] y;
	protected double[] v_x = null;
	protected double[] v_y = null;
	protected double delta = -1;
	protected double z;
	protected boolean subsampled = false;
	protected boolean closed = false;

	public Perimeter2D(final double[] x, final double[] y, final double z, final boolean closed) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.closed = closed;
	}

	protected int length() {
		return x.length;
	}

	protected double computeAveragePointInterdistance() {
		this.delta = 0; //reset
		if (0 == x.length) return 0;
		for (int i=1; i<=x.length; i++) {
			delta += Math.sqrt((x[i] - x[i-1])*(x[i] - x[i-1]) + (y[i] - y[i-1])*(y[i] - y[i-1]));
		}
		if (closed) {
			delta += Math.sqrt((x[0] - x[x.length-1])*(x[0] - x[x.length-1]) + (y[0] - y[y.length-1])*(y[0] - y[y.length-1]));
			return delta / (x.length + 1);
		}
		return delta / x.length;
	}
	protected double getDelta() {
		if (-1 == delta) return computeAveragePointInterdistance();
		return delta;
	}

	protected void resample(final double delta) {
		if (subsampled) return;
		int i=0;
		int j=0;
		int p_length = x.length;
		// reorder to CCW if needed
		double x_max = 0;			int x_max_i = 0;
		double y_max = 0;			int y_max_i = 0;
		double x_min = Double.MAX_VALUE;	int x_min_i = 0;
		double y_min = Double.MAX_VALUE;	int y_min_i = 0; // dummy init
		for (i=0;i <p_length; i++) {
			if (x[i] > x_max) { x_max = x[i]; x_max_i = i; } // this lines could be optimized, the p->x etc. are catched below
			if (y[i] > y_max) { y_max = y[i]; y_max_i = i; }
			if (x[i] < x_min) { x_min = x[i]; x_min_i = i; }
			if (y[i] < y_min) { y_min = y[i]; y_min_i = i; }
		}
		int collect = 0;
		if (y_min_i - x_max_i >= 0) collect++;
		if (x_min_i - y_min_i >= 0) collect++;
		if (y_max_i - x_min_i >= 0) collect++;
		if (x_max_i - y_max_i >= 0) collect++;

		if (3 != collect) { // this should be '3 == collect', but then we are looking at the curves from the other side relative to what ImageJ is showing. In any case as long as one or the other gets rearranged, they'll be fine.
			// Clockwise! Reorder to CCW by reversing the arrays in place
			int n = p_length;
			double tmp;
			for (i=0; i< p_length /2; i++) {

				tmp = x[i];
				x[i] = x[n-i-1];
				x[n-i-1] = tmp;

				tmp = y[i];
				y[i] = y[n-i-1];
				y[n-i-1] = tmp;
			}
		}

		// the arrays of points and vectors to return. There may be way more! Caution. realloc ... 
		double[] ps_x = new double[p_length];
		double[] ps_y = new double[p_length];
		double[] v_x = new double[p_length];
		double[] v_y = new double[p_length];
		// the length of the new points is originally p_length (but will be resized)
		int ps_length = p_length;
		// the first point is the same as the one with index 0
		ps_x[0] = x[0];
		ps_y[0] = y[0]; // GOTCHA! The first vector is NOT set !!!! --> set at the end, it is made from the last subsampled point and it is never used before that. TODO: how is this going to affect open curves?

		// the index over x and y
		i = 1;
		// the index over ps: (ps stands for 'perimeter subsampled')
		j = 1;
		// some vars
		double dx, dy, sum;
		double angle, angle1, dist1, dist2;
		final int MAX_AHEAD = 6;
		final double MAX_DISTANCE = 2.5 * delta;
		final int[] ahead = new int[6];
		final double[] distances = new double[MAX_AHEAD];
		int n_ahead = 0;
		int u, ii, k;
		int t, s;
		int prev_i = i;
		int iu;
		double dist_ahead;
		double[] w = new double[MAX_AHEAD]; // the weights for each point ahead

		// start infinite loop: //this ugly construct 'for (;;)' below is preferred upon 'while(true)' ... beats me why. For readability?
		for (;;) {
			if (prev_i > i) break;//the loop has completed one round, since 'i' can go up to MAX_POINTS ahead of the last point into the points at the beggining of the array. Whenever the next point 'i' to start exploring is set beyond the length of the array, then the condition is met.

			// check ps and v array lengths
			if (j >= ps_length) {
				// must enlarge.
				final int new_length = ps_length + 20;
				// TODO: replace by direct realloc calls? Consider.
				v_x = enlargeArrayOfDoubles(v_x, new_length);
				v_y = enlargeArrayOfDoubles(v_y, new_length);
				ps_x = enlargeArrayOfDoubles(ps_x, new_length);
				ps_y = enlargeArrayOfDoubles(ps_y, new_length);
				ps_length += 20; // could be done in one step, but readability needs it!
			}
			
			// get distances of MAX_POINTs ahead from the previous point
			n_ahead = 0; //reset
			for (t=0; t<MAX_AHEAD; t++) {
				s = i + t; // 'i' is the first to start inspecting from
				// fix 's' if it goes over the end
				if (s >= p_length) {
					// fix open curves
					if (!closed) {
						n_ahead = t;
						break;
					}
					// fix closed curves
					s = s - p_length;
				}
				dist_ahead = Math.sqrt((x[s] - ps_x[j-1])*(x[s] - ps_x[j-1]) + (y[s] - ps_y[j-1])*(y[s] - ps_y[j-1]));
				if (dist_ahead < MAX_DISTANCE) {
					ahead[n_ahead] = s;
					distances[n_ahead] = dist_ahead;
					n_ahead++;
				}
			}

			if (0 == n_ahead) { // no points ahead found within MAX_DISTANCE
				// simpler version: use just the next point
				dist1 = Math.sqrt((x[i] - ps_x[j-1])*(x[i] - ps_x[j-1]) + (y[i] - ps_y[j-1])*(y[i] - ps_y[j-1]));
				angle1 = M.getAngle(x[i] - ps_x[j-1], y[i] - ps_y[j-1]);
				dx = Math.cos(angle1) * delta;
				dy = Math.sin(angle1) * delta;
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				v_x[j] = dx;
				v_y[j] = dy;
				//correct for point overtaking the not-close-enough point ahead in terms of 'delta_p' as it is represented in MAX_DISTANCE, but overtaken by the 'delta' used for subsampling:
				if (dist1 <= delta) {
					//look for a point ahead that is over distance delta from the previous j, so that it will lay ahead of the current j
					for (u=i; u<=p_length; u++) {
						dist2 = Math.sqrt((x[u] - ps_x[j-1])*(x[u] - ps_x[j-1]) + (y[u] - ps_y[j-1])*(y[u] - ps_y[j-1]));
						if (dist2 > delta) {
							prev_i = i;
							i = u;
							break;
						}
					}
				}
			} else {
				w[0] = distances[0] / MAX_DISTANCE;
				double largest = w[0];
				for (u=1; u<n_ahead; u++) {
					w[u] = 1 - (distances[u] / MAX_DISTANCE);
					if (w[u] > largest) {
						largest = w[u];
					}
				}
				// normalize weights: divide by largest
				sum = 0;
				for (u=0; u<n_ahead; u++) {
					w[u] = w[u] / largest;
					sum += w[u];
				}
				// correct error. The closest point gets the extra
				if (sum < 1.0) {
					w[0] += 1.0 - sum;
				} else {
					w = recalculate(w, n_ahead, sum);
				}
				// calculate the new point using the weights
				dx = 0.0;
				dy = 0.0;
				for (u=0; u<n_ahead; u++) {
					iu = i+u;
					if (iu >= p_length) iu -= p_length; 
					angle = M.getAngle(x[iu] - ps_x[j-1], y[iu] - ps_y[j-1]);
					dx += w[u] * Math.cos(angle);
					dy += w[u] * Math.sin(angle);
				}
				// make vector and point:
				dx = dx * delta;
				dy = dy * delta;
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				v_x[j] = dx;
				v_y[j] = dy;

				// find the first point that is right ahead of the newly added point
				// so: loop through points that lay within MAX_DISTANCE, and find the first one that is right past delta.
				ii = i;
				for (k=0; k<n_ahead; k++) {
					if (distances[k] > delta) {
						ii = ahead[k];
						break;
					}
				}
				// correct for the case of unseen point (because all MAX_POINTS ahead lay under delta):
				prev_i = i;
				if (i == ii) {
					i = ahead[n_ahead-1] +1; //the one after the last.
					if (i >= p_length) i = i - p_length;
				} else {
					i = ii;
				}
			}
			//advance index in the new points
			j += 1;

		} // closing bracket of infinite loop

		// see whether the subsampling terminated too early, and fill with a line of points.
		// //TODO this is sort of a patch. Why didn't j overcome the last point is not resolved.
		dist1 = Math.sqrt((x[0] - ps_x[j-1])*(x[0] - ps_x[j-1]) + (y[0] - ps_y[j-1])*(y[0] - ps_y[j-1]));
		angle1 = M.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
		dx = Math.cos(angle1) * delta;
		dy = Math.sin(angle1) * delta;
		while (dist1 > delta*1.2) { //added 1.2 to prevent last point from being generated too close to the first point
			// check ps and v array lengths
			if (j >= ps_length) {
				// must enlarge.
				int new_length = ps_length + 20;
				v_x = enlargeArrayOfDoubles(v_x, new_length);
				v_y = enlargeArrayOfDoubles(v_y, new_length);
				ps_x = enlargeArrayOfDoubles(ps_x, new_length);
				ps_y = enlargeArrayOfDoubles(ps_y, new_length);
				ps_length += 20;
			}
			//add a point
			ps_x[j] = ps_x[j-1] + dx;
			ps_y[j] = ps_y[j-1] + dy;
			v_x[j] = dx;
			v_y[j] = dy;
			j++;
			dist1 = Math.sqrt((x[0] - ps_x[j-1])*(x[0] - ps_x[j-1]) + (y[0] - ps_y[j-1])*(y[0] - ps_y[j-1]));
		}

		// set vector 0 to be the vector from the last point to the first
		angle = M.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
		//v_x[0] = Math.cos(angle) * delta;
		//v_y[0] = Math.sin(angle) * delta; // can't use delta, it may be too long and thus overtake the first point!
		v_x[0] = Math.cos(angle) * dist1;
		v_y[0] = Math.sin(angle) * dist1;

		// assign
		this.x = ps_x;
		this.y = ps_y;
		this.v_x = v_x;
		this.v_y = v_y;

		this.subsampled = true;
	}

	private double[] enlargeArrayOfDoubles(final double[] a, final int new_length) {
		double[] b = new double[new_length];
		System.arraycopy(a, 0, b, 0, a.length);
		return b;
	}

	/** Recalculate an array of weights so that its sum is at most 1.0 
	 *  WARNING this function is recursive.
	 *  The possibility of an infinite loop is avoided by the usage of an 'error' range of 0.005
	 */
	private double[] recalculate(final double[] w, final int length, final double sum_) {
		double sum = 0;
		for (int q=0; q<length; q++) {
			w[q] = w[q] / sum_;
			sum += w[q];
		}
		double error = 1.0 - sum;
		// make it be an absolute value
		if (error < 0.0) {
			error = -error;
		}
		if (error < 0.005) {
			w[0] += 1.0 - sum;
		} else if (sum > 1.0) {
			return recalculate(w, length, sum);
		}
		return w;
	}

	/** Make the index min_j be zero, reordering as if the arrays were circular. */
	protected void reorder(final int min_j) {
		// smart copying (and I'm not up for memcpy cryptography)
		final int m = x.length;
		int i=0, j=0;
		double[] tmp = new double[m];
		double[] src;
		// x
		src = x;
		for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
		x = tmp;
		tmp = src;
		// y
		src = y;
		for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
		y = tmp;
		tmp = src;
		// v_x
		src = v_x;
		for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
		v_x = tmp;
		tmp = src;
		// v_y
		src = v_y;
		for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
		v_y = tmp;
	}
}
