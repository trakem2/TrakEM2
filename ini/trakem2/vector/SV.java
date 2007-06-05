/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2006, 2007 Albert Cardona and Rodney Douglas.

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
public class SV {

	protected double[] x; // points
	protected double[] y;
	protected double[] z;
	protected double[] vs_x = null; // vectors, after resampling
	protected double[] vs_y = null;
	protected double[] vs_z = null;
	protected int length;
	protected double delta; // the delta used for resampling

	/** Construct a new String of Vectors from the given points and desired resampling point interdistance 'delta'. */
	public SV(double[] x, double[] y, double[] z, double delta) throws Exception {
		if (!(x.length == y.length && y.length == z.length)) {
			throw new Exception("x,y,z must be of the same length.");
		}
		this.length = x.length;
		this.x = x;
		this.y = y;
		this.z = z;
		this.delta = delta;
	}

	/** Does NOT clone the vector arrays, which are initialized to NULL. */
	public Object clone() {
		double[] x2 = new double[length];
		double[] y2 = new double[length];
		double[] z2 = new double[length];
		System.arraycopy(x, 0, x2, 0, length);
		System.arraycopy(y, 0, y2, 0, length);
		System.arraycopy(z, 0, z2, 0, length);
		try {
			return new SV(x2, y2, z2, delta);
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
	}

	public double[] getX() { return x; }
	public double[] getY() { return y; }
	public double[] getZ() { return x; }
	public int getLength() { return length; }

	/** Transform points to exist in the new axis of reference defined by the 3 given vectors. Returns a new instance, does not modify the original. */
	/*
	public SV changeAxis(double[] vx, double[] vy, double vz) {
		// TODO
	}
	*/

	public double getAverageDelta() {
		double d = 0;
		for (int i=x.length -1; i>0; i--) {
			d += Math.sqrt( (x[i] - x[i-1])*(x[i] - x[i-1]) + (y[i] - y[i-1])*(y[i] - y[i-1]) + (z[i] - z[i-1])*(z[i] - z[i-1])); // pytagorian rule for 3D
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

	/** As in the resampling method for CurveMorphing_just_C.c but for 3D and only open curves! Uses the assigned 'delta'. */
	private void resample() {
		int MAX_AHEAD = 6;
		double MAX_DISTANCE = 2.5 * delta;
		double[] ps_x = new double[length];
		double[] ps_y = new double[length];
		double[] ps_z = new double[length];
		double[] v_x = new double[length];
		double[] v_y = new double[length];
		double[] v_z = new double[length];
		int p_length = this.length; // to keep my head cool
		int ps_length = this.length;
		// first resampled point is the same as 0
		ps_x[0] = x[0];
		ps_y[0] = y[0];
		ps_z[0] = z[0];
		// the index over x,y,z
		int i = 1;
		// the index over ps (the resampled points)
		int j = 1;
		// some vars:
		double dx, dy, dz, sum;
		double angle, angleXY, angleZY, dist1, dist2;
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
				v_z = enlargeArrayOfDoubles(v_z, new_length);
				ps_x = enlargeArrayOfDoubles(ps_x, new_length);
				ps_y = enlargeArrayOfDoubles(ps_y, new_length);
				ps_z = enlargeArrayOfDoubles(ps_z, new_length);
			}
			// get distances of MAX_POINTs ahead from the previous point
			n_ahead = 0; // reset
			for (t=0; t<MAX_AHEAD; t++) {
				s = i + t; // 'i' is the first to start inspecting from
				// stop if it goes over the end (no closed curves!)
				if (s >= p_length) {
					break;
				}
				dist_ahead = Math.sqrt((x[s] - ps_x[j-1])*(x[s] - ps_x[j-1]) + (y[s] - ps_y[j-1])*(y[s] - ps_y[j-1]) + (z[s] - ps_z[j-1])*(z[s] - ps_z[j-1]));
				if (dist_ahead < MAX_DISTANCE) {
					ahead[n_ahead] = s;
					distances[n_ahead] = dist_ahead;
					n_ahead++;
				}
			}
			//
			if (0 == n_ahead) { // no points ahead found within MAX_DISTANCE   TODO what happens with the last point of an open curve?
				// all MAX_POINTS ahead lay under delta.
				// ...
				// simpler version: use just the next point
				dist1 = Math.sqrt((x[i] - ps_x[j-1])*(x[i] - ps_x[j-1]) + (y[i] - ps_y[j-1])*(y[i] - ps_y[j-1]) + (z[i] - ps_z[j-1])*(z[i] - ps_z[j-1]));
				angleXY = Utils.getAngle(x[i] - ps_x[j-1], y[i] - ps_y[j-1]); // TODO: introduce angle in 3D ... need to think about it!
				angleZY = Utils.getAngle(z[i] - ps_z[j-1], y[i] - ps_y[j-1]);
				// I believe one needs to get two angles: on the XY and the ZY planes.


				//printf("\n i: %i, j: %i, dx: %f, dy: %f, angle1: %f, dist: %f", i, j, dx, dy, angle1, dist1);
				dx = Math.cos(angleXY) * delta;
				dy = Math.sin(angleXY) * delta;
				dz = Math.cos(angleZY) * delta; // TODO this may be wrong, draw!
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				ps_y[j] = ps_z[j-1] + dz;
				v_x[j] = dx;
				v_y[j] = dy;
				v_z[j] = dz;

				//correct for point overtaking the not-close-enough point ahead in terms of 'delta_p' as it is represented in MAX_DISTANCE, but overtaken by the 'delta' used for subsampling:
				if (dist1 <= delta) {
					//look for a point ahead that is over distance delta from the previous j, so that it will lay ahead of the current j
					for (u=i; u<=p_length; u++) {
						dist2 = Math.sqrt((x[u] - ps_x[j-1])*(x[u] - ps_x[j-1]) + (y[u] - ps_y[j-1])*(y[u] - ps_y[j-1]) + (z[u] - ps_z[j-1])*(z[u] - ps_z[j-1]) );
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
				dz = 0.0;
				for (u=0; u<n_ahead; u++) {
					iu = i+u;
					if (iu >= p_length) iu -= p_length; 
					angleXY = Utils.getAngle(x[iu] - ps_x[j-1], y[iu] - ps_y[j-1]);
					angleZY = Utils.getAngle(z[iu] - ps_z[j-1], y[iu] - ps_y[j-1]);
					dx += w[u] * Math.cos(angleXY);
					dy += w[u] * Math.sin(angleXY);
					dz += w[u] * Math.cos(angleZY);
				}
				// make vector and point:
				dx = dx * delta;
				dy = dy * delta;
				dz = dz * delta;
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				ps_z[j] = ps_z[j-1] + dz;
				v_x[j] = dx;
				v_y[j] = dy;
				v_z[j] = dz;

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
		dist1 = Math.sqrt((x[0] - ps_x[j-1])*(x[0] - ps_x[j-1]) + (y[0] - ps_y[j-1])*(y[0] - ps_y[j-1]) + (z[0] - ps_z[j-1])*(z[0] - ps_z[j-1]));
		if (dist1 > delta*1.2) {
			// TODO is this needed?
			System.out.println("resampling terminated too early. Why?");
			angleXY = Utils.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
			angleZY = Utils.getAngle(z[0] - ps_z[j-1], y[0] - ps_y[j-1]);
			dx = Math.cos(angleXY) * delta;
			dy = Math.sin(angleXY) * delta;
			dz = Math.cos(angleZY) * delta;
			while (dist1 > delta*1.2) {//added 1.2 to prevent last point from being generated too close to the first point
				// check ps and v array lengths
				if (j >= ps_length) {
					// must enlarge.
					int new_length = ps_length + 20;
					v_x = enlargeArrayOfDoubles(v_x, new_length);
					v_y = enlargeArrayOfDoubles(v_y, new_length);
					v_z = enlargeArrayOfDoubles(v_z, new_length);
					ps_x = enlargeArrayOfDoubles(ps_x, new_length);
					ps_y = enlargeArrayOfDoubles(ps_y, new_length);
					ps_z = enlargeArrayOfDoubles(ps_z, new_length);
					ps_length += 20;
				}
				//add a point
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				ps_z[j] = ps_z[j-1] + dz;
				v_x[j] = dx;
				v_y[j] = dy;
				v_z[j] = dz;
				j++;
				dist1 = Math.sqrt((x[0] - ps_x[j-1])*(x[0] - ps_x[j-1]) + (y[0] - ps_y[j-1])*(y[0] - ps_y[j-1]) + (z[0] - ps_z[j-1])*(z[0] - ps_z[j-1]));
			}
		}
		// set vector 0 to be the vector from the last point to the first
		angleXY = Utils.getAngle(x[0] - ps_x[j-1], y[0] - ps_y[j-1]);
		angleZY = Utils.getAngle(z[0] - ps_z[j-1], y[0] - ps_y[j-1]);
		//v_x[0] = Math.cos(angle) * delta;
		//v_y[0] = Math.sin(angle) * delta; // can't use delta, it may be too long and thus overtake the first point!
		v_x[0] = Math.cos(angleXY) * dist1;
		v_y[0] = Math.sin(angleXY) * dist1;
		v_z[0] = Math.cos(angleZY) * dist1;

		// j is now the length of the ps_x, ps_y, v_x and v_y arrays (i.e. the number of subsampled points).
		this.length = j;
		// done!
	}

	private final double[] enlargeArrayOfDoubles(final double[] a, final int new_length) {
		final double[] b = new double[new_length]; 
		System.arraycopy(a, 0, b, 0, a.length);
		return b;
	}

	private double[] recalculate(double[] w, int length, double sum_) {
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

	/** Generate the matrix between sv1 and sv2. The lower right value of the matrix is the Levenshtein's distance between the two strings of vectors. 'delta' is the desired point interdistance. */
	static public double[][] getMatrix(SV sv1, SV sv2, double delta) {

		sv1.resample(delta);
		sv2.resample(delta);

		double[] vs_x1 = sv1.vs_x;
		double[] vs_y1 = sv1.vs_y;
		double[] vs_z1 = sv1.vs_z;
		double[] vs_x2 = sv2.vs_x;
		double[] vs_y2 = sv2.vs_y;
		double[] vs_z2 = sv2.vs_z;

		int n = vs_x1.length;
		int m = vs_x2.length;

		double[][] matrix = new double[n+1][m+1];
		int i=0, j=0;
		for (; i < n +1; i++) {
			matrix[i][0] = i * delta;
		}
		for (; j < m +1; j++) {
			matrix[0][j] = j * delta;
		}
		// as optimized in the findEditMatrix in CurveMorphing_just_C.c
		double[] mati;
		double[] mat1;
		double fun1, fun2, fun3;
		double vs_x, vs_y, vs_z;
		for (i=1; i < n +1; i++) {
			mati = matrix[i];
			mat1 = matrix[i-1];
			for (j=1; j < m +1; j++) {
				// cost deletion:
				fun1 = mat1[j] + delta; // matrix[i-1][j] + delta
				// cost insertion:
				fun2 = mati[j-1] + delta; // matrix[i][j-1] + delta
				// cost mutation:
				if (i == n || j == m) {
					fun3 = mat1[j-1]; // matrix[i-1][j-1]
				} else {
					vs_x = vs_x1[i] - vs_x2[j];
					vs_y = vs_y1[i] - vs_y2[j];
					vs_z = vs_z1[i] - vs_z2[j];
					fun3 = mat1[j-1] + Math.sqrt(vs_x*vs_x + vs_y*vs_y + vs_z*vs_z); // the vector length is the hypothenusa ////matrix[i-1][jj-1]; + Math.sqrt(vs_x*vs_x + vs_y*vs_y + vs_z*vs_z);
				}
				// insert the lowest value in the matrix.
				// since most are mutations, start with fun3:
				if (fun3 <= fun1 && fun3 <= fun2) {
					mati[j] = fun3;//matrix[i][jj] = fun3;
				} else if (fun1 <= fun2 && fun1 <= fun3) {
					mati[j] = fun1;//matrix[i][jj] = fun1;
				} else {
					mati[j] = fun2;//matrix[i][jj] = fun2;
				}
			}
		}

		return matrix;
	}
}
