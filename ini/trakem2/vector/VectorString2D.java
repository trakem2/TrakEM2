/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2006, 2007 Albert Cardona.

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

package ini.trakem2.vector;

import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

/** String of vectors. */
public class VectorString2D {

	// all private to the package: accessible from Editions class
	double[] x; // points
	double[] y;
	double[] v_x = null; // vectors, after resampling
	double[] v_y = null;
	/** The length of the x,y and v_x, v_y resampled points (the actual arrays may be a bit longer) */
	int length;
	double delta; // the delta used for resampling
	/** The Z coordinate of the entire planar curve represented by this VectorString2D. */
	double z;

	/** Construct a new String of Vectors from the given points and desired resampling point interdistance 'delta'. */
	public VectorString2D(double[] x, double[] y, double delta, double z) throws Exception {
		if (!(x.length == y.length)) {
			throw new Exception("x and y must be of the same length.");
		}
		this.length = x.length;
		this.x = x;
		this.y = y;
		this.delta = delta;
		this.z = z;
	}

	/** Does NOT clone the vector arrays, which are initialized to NULL; only the x,y,delta,z. */
	public Object clone() {
		double[] x2 = new double[length];
		double[] y2 = new double[length];
		System.arraycopy(x, 0, x2, 0, length);
		System.arraycopy(y, 0, y2, 0, length);
		try {
			return new VectorString2D(x2, y2, delta, z);
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
	}

	public double[] getX() { return x; }
	public double[] getY() { return y; }
	public int getLength() { return length; }

	public double getAverageDelta() { // equivalent to C's getAndSetAveragePointInterdistance function
		double d = 0;
		for (int i=x.length -1; i>0; i--) {
			d += Math.sqrt( (x[i] - x[i-1])*(x[i] - x[i-1]) + (y[i] - y[i-1])*(y[i] - y[i-1])); // pytagorian rule for 2D
		}
		return d / x.length;
	}

	/** Homogenize the average point interdistance to 'delta'. */ // There are problems with the last point.
	public void resample(double delta) {
		if (Math.abs(delta - this.delta) < 0.0000001) {
			// delta is the same
			return;
		}
		this.delta = delta; // store for checking purposes
		this.resample();
	}

	/** As in the resampling method for CurveMorphing_just_C.c but for 2D and only open curves! Uses the assigned 'delta'. */
	private void resample() {
		final int MAX_AHEAD = 6;
		final double MAX_DISTANCE = 2.5 * delta;
		double[] ps_x = new double[length];
		double[] ps_y = new double[length];
		double[] v_x = new double[length];
		double[] v_y = new double[length];
		final int p_length = this.length; // to keep my head cool
		int ps_length = this.length; // the length of the resampled vectors
		// first resampled point is the same as 0
		ps_x[0] = x[0];
		ps_y[0] = y[0];
		// the index over x,y
		int i = 1;
		// the index over ps (the resampled points)
		int j = 1;
		// some vars:
		double dx, dy, sum;
		double angle, angleXY, dist1, dist2;
		int[] ahead = new int[MAX_AHEAD];
		double[] distances = new double[MAX_AHEAD];
		int n_ahead = 0;
		int u, ii, k;
		int t, s;
		int prev_i = i;
		int iu;
		double dist_ahead;
		double[] w = new double[MAX_AHEAD];

		// start infinite loop
		for (;prev_i <= i;) {
			// check ps and v array lengths
			if (j >= ps_length) {
				int new_length = ps_length + 20;
				// must enlarge
				v_x = enlargeArrayOfDoubles(v_x, new_length);
				v_y = enlargeArrayOfDoubles(v_y, new_length);
				ps_x = enlargeArrayOfDoubles(ps_x, new_length);
				ps_y = enlargeArrayOfDoubles(ps_y, new_length);
			}
			// get distances of MAX_POINTs ahead from the previous point
			n_ahead = 0; // reset
			for (t=0; t<MAX_AHEAD; t++) {
				s = i + t; // 'i' is the first to start inspecting from
				// stop if it goes over the end (no closed curves!)
				if (s >= p_length) {
					break;
				}
				dist_ahead = Math.sqrt((x[s] - ps_x[j-1])*(x[s] - ps_x[j-1]) + (y[s] - ps_y[j-1])*(y[s] - ps_y[j-1]));
				if (dist_ahead < MAX_DISTANCE) {
					ahead[n_ahead] = s;
					distances[n_ahead] = dist_ahead;
					n_ahead++;
				}
			}
			//
			if (0 == n_ahead) { // no points ahead found within MAX_DISTANCE
				// all MAX_POINTS ahead lay under delta.
				// ...
				// simpler version: use just the next point
				dist1 = Math.sqrt((x[i] - ps_x[j-1])*(x[i] - ps_x[j-1]) + (y[i] - ps_y[j-1])*(y[i] - ps_y[j-1]));
				angleXY = Utils.getAngle(x[i] - ps_x[j-1], y[i] - ps_y[j-1]);

				dx = Math.cos(angleXY) * delta;
				dy = Math.sin(angleXY) * delta;
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
							//printf("\nADVANCING: prev i=%i, next i=%i, j-1=%i, dist2 = %f", i, u, j, dist2);
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
					w = recalculate(w, n_ahead, sum); // TODO : 'recalculate' function!
				}
				// calculate the new point using the weights
				dx = 0.0;
				dy = 0.0;
				for (u=0; u<n_ahead; u++) {
					iu = i+u;
					if (iu >= p_length) iu -= p_length; 
					angleXY = Utils.getAngle(x[iu] - ps_x[j-1], y[iu] - ps_y[j-1]);
					dx += w[u] * Math.cos(angleXY);
					dy += w[u] * Math.sin(angleXY);
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
		} // end of for (;;) loop

		// see whether the subsampling terminated too early, and fill with a line of points.
		// TODO this is sort of a patch. Why didn't j overcome the last point is not resolved.
		dist1 = Math.sqrt((x[0] - ps_x[j-1])*(x[0] - ps_x[j-1]) + (y[0] - ps_y[j-1])*(y[0] - ps_y[j-1]));
		if (dist1 > delta*1.2) {
			// TODO needs revision
			System.out.println("resampling terminated too early. Why?");
			angleXY = Utils.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
			dx = Math.cos(angleXY) * delta;
			dy = Math.sin(angleXY) * delta;
			while (dist1 > delta*1.2) {//added 1.2 to prevent last point from being generated too close to the first point
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
		}
		// set vector 0 to be the vector from the last point to the first
		angleXY = Utils.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
		//v_x[0] = Math.cos(angle) * delta;
		//v_y[0] = Math.sin(angle) * delta; // can't use delta, it may be too long and thus overtake the first point!
		v_x[0] = Math.cos(angleXY) * dist1;
		v_y[0] = Math.sin(angleXY) * dist1;

		// assign the new subsampled points
		this.x = ps_x;
		this.y = ps_y;
		// assign the vectors
		this.v_x = v_x;
		this.v_y = v_y;
		// j is now the length of the ps_x, ps_y, v_x and v_y arrays (i.e. the number of resampled points).
		this.length = j;

		// done!
	}

	private final double[] enlargeArrayOfDoubles(final double[] a, final int new_length) {
		final double[] b = new double[new_length]; 
		System.arraycopy(a, 0, b, 0, a.length);
		return b;
	}

	private final double[] recalculate(final double[] w, final int length, final double sum_) {
		double sum = 0;
		int q;
		for (q=0; q<length; q++) {
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
}
