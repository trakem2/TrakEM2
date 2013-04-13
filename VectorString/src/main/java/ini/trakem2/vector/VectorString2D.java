/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona.

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

import java.awt.Polygon;
import java.io.IOException;

import ij.gui.PolygonRoi;
import ij.gui.ShapeRoi;
import ij.io.RoiDecoder;
import ij.measure.Calibration;

/** String of vectors. */
public class VectorString2D implements VectorString {

	// all private to the package: accessible from Editions class
	private double[] x; // points
	private double[] y;
	private double[] v_x = null; // vectors, after resampling
	private double[] v_y = null;
	/** The length of the x,y and v_x, v_y resampled points (the actual arrays may be a bit longer) */
	private int length;
	/** The point interdistance after resampling. */
	private double delta = 0; // the delta used for resampling
	/** The Z coordinate of the entire planar curve represented by this VectorString2D. */
	private double z;
	private boolean closed;

	private Calibration cal = null;

	/** Construct a new String of Vectors from the given points and desired resampling point interdistance 'delta'. */
	public VectorString2D(double[] x, double[] y, double z, boolean closed) throws Exception {
		if (!(x.length == y.length)) {
			throw new Exception("x and y must be of the same length.");
		}
		this.length = x.length;
		this.x = x;
		this.y = y;
		this.z = z;
		this.closed = closed;
	}

	/** Does NOT clone the vector arrays, which are initialized to NULL; only the x,y,delta,z. */
	public Object clone() {
		double[] x2 = new double[length];
		double[] y2 = new double[length];
		System.arraycopy(x, 0, x2, 0, length);
		System.arraycopy(y, 0, y2, 0, length);
		try {
			return new VectorString2D(x2, y2, z, closed);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public int length() { return length; }

	/** If not resampled, returns zero. */
	public double getDelta() { return delta; }

	public double[] getPoints(final int dim) {
		switch (dim) {
			case 0: return x;
			case 1: return y;
		}
		return null;
	}
	public double[] getVectors(final int dim) {
		switch (dim) {
			case 0: return v_x;
			case 1: return v_y;
		}
		return null;
	}
	public double getPoint(final int dim, final int i) {
		switch (dim) {
			case 0: return x[i];
			case 1: return y[i];
			case 2: return z;
		}
		return 0;
	}
	public double getVector(final int dim, final int i) {
		switch (dim) {
			case 0: return v_x[i];
			case 1: return v_y[i];
		}
		return 0;
	}
	public boolean isClosed() { return closed; }

	public double getAverageDelta() { // equivalent to C's getAndSetAveragePointInterdistance function
		double d = 0;
		for (int i=x.length -1; i>0; i--) {
			d += Math.sqrt( (x[i] - x[i-1])*(x[i] - x[i-1]) + (y[i] - y[i-1])*(y[i] - y[i-1])); // pytagorian rule for 2D
		}
		return d / x.length;
	}

	/** Same as resample(delta). */
	public void resample(double delta, boolean with_source) {
		resample(delta);
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
	
	static private class Sequence {
		protected int i = -1;
		protected int size;
		public Sequence(final int size) {
			this.size = size;
		}
		int next() {
			++i;
			return i == size ? -1 : i;
		}
		void setPosition(final int k) {
			if (k < 0 || k >= size) throw new RuntimeException( k + " is out of bounds.");
			this.i = k;
		}
		final int position() {
			return i;
		}
	}
	
	static private final class CircularSequence extends Sequence {
		public CircularSequence(final int size) {
			super(size);
		}
		@Override
		final int next() {
			++i;
			i = i % size;
			return i;
		}
		@Override
		final void setPosition(final int k) {
			i = k;
			if (i < 0) i = size - ((-i) % size);
			else i = i % size;
		}
	}
	
	static private final class GrowablePolygon {
		private double[] x, y;
		private int last = -1, growth;
		GrowablePolygon(final int initialSize, final int growth) {
			this.growth = growth;
			this.x = new double[initialSize];
			this.y = new double[initialSize];
		}
		final void resize() {
			final double[] x2 = new double[this.x.length + this.growth];
			System.arraycopy(this.x, 0, x2, 0, x.length);
			this.x = x2;
			final double[] y2 = new double[x2.length];
			System.arraycopy(this.y, 0, y2, 0, y.length);
			this.y = y2;
		}
		final void append(final double px, final double py) {
			this.last += 1;
			if (this.last == this.x.length) resize();
			this.x[last] = px;
			this.y[last] = py;
		}
		final double lastX() { return this.x[last]; }
		final double lastY() { return this.y[last]; }
	}
	
	private final void reorderToCCW() {
		// reorder to CCW if needed: (so all curves have the same orientation)
		// find bounding box:
		double x_max = 0;			int x_max_i = 0;
		double y_max = 0;			int y_max_i = 0;
		double x_min = Double.MAX_VALUE;		int x_min_i = 0;
		double y_min = Double.MAX_VALUE;		int y_min_i = 0;
		for (int i=0;i <this.length; i++) {
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
		//if (3 == collect)
		if (3 != collect) { // this should be '3 == collect', but then we are looking at the curves from the other side relative to what ImageJ is showing. In any case as long as one or the other gets rearranged, they'll be fine.
			// Clockwise! Reorder to CCW by reversing the arrays in place
			int n = this.length;
			double tmp;
			for (int i=0; i< this.length /2; i++) {

				tmp = x[i];
				x[i] = x[n-i-1];
				x[n-i-1] = tmp;

				tmp = y[i];
				y[i] = y[n-i-1];
				y[n-i-1] = tmp;
			}
		}
	}
	
	static private final class DoubleArray {
		private double[] a;
		private int next = 0;
		DoubleArray(final int initialCapacity) {
			this.a = new double[initialCapacity];
		}
		final void append(final double d) {
			this.a[next] = d;
			++next;
			if (this.a.length == next) {
				final double[] b = new double[this.a.length + 1008];
				System.arraycopy(this.a, 0, b, 0, this.a.length);
				this.a = b;
			}
		}
		final void reset() {
			this.next = 0;
		}
		final int size() {
			return next;
		}
	}
	
	private void resample() {
		System.out.println("NEW RESAMPLE: " + delta + ", " + x.length);
		reorderToCCW();
		final double MAX_DISTANCE = 4 * delta;
		final double MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;
		final double deltaSq = delta * delta;
		final Sequence seq = this.closed ? new CircularSequence(this.length) : new Sequence(this.length);
		final GrowablePolygon gpol = new GrowablePolygon(this.length, 200);
		final DoubleArray w = new DoubleArray(16);
		boolean end_seen = false;
		final int last = closed ? 0 : this.length -1;
		// First resampled point is the same as zero
		gpol.append(x[0], y[0]);
		// Start using the first point for interpolations
		seq.setPosition(1);
		// Grow all the way to the last point
		loop: while (true) {
			final double lastX = gpol.lastX(),
			               lastY = gpol.lastY();
			final int first_i = seq.position(); // the first point ahead to consider
			double sumW = 0; // the sum of weights, for normalization
			int i = first_i; // the index over the original sequence of points
			int next_i = first_i; // the next index for the next iteration
			w.reset(); // reset the array of weights: empty it
			// Iterate over next points within MAX_DISTANCE to estimate the next gpol point
			while (true) {
				// Determine termination:
				if (!end_seen) end_seen = i == last;
				if (end_seen) {
					double distSq_to_last = Math.pow(this.x[last] - lastX, 2) + Math.pow(this.y[last] - lastY, 2);
					if (distSq_to_last < deltaSq) {
						// Do not add more points
						break loop;
					}
				}

				// If i is the first point to be further than delta, record it as the next starting index:
				double distSq = Math.pow(this.x[i] - lastX, 2) + Math.pow(this.y[i] - lastY, 2);
				if (next_i == first_i && distSq > deltaSq) {
					next_i = i;
				}
				// If i is within MAX_DISTANCE, include it in the estimation of the next gpol point
				if (distSq < MAX_DISTANCE_SQ) {
					double weight = Math.sqrt(distSq);
					sumW += weight;
					w.append(weight);
					// ... and advance to the next
					i = seq.next();
				} else {
					break;
				}
			}
			
			if (w.size() > 0) {
				// Normalize weights so that their sum equals 1
				double sumW2 = 0;
				for (int j=w.size() -1; j > -1; --j) {
					w.a[j] /= sumW;
					sumW2 += w.a[j];
				}
				// Correct for floating-point issues: add error to first weight
				if (sumW2 < 1.0) {
					w.a[0] += 1.0 - sumW2;
				}
				// Create next interpolated point
				seq.setPosition(first_i);
				double dx = 0,
				        dy = 0;
				for (int j=0, k = seq.position(); j<w.size(); ++j, k = seq.next()) {
					final double angleXY = Util.getAngle(x[k] - lastX, y[k] - lastY);
					dx += w.a[j] * Math.cos(angleXY);
					dy += w.a[j] * Math.sin(angleXY);
				}
				gpol.append(lastX + dx * delta, lastY + dy * delta);
			} else {
				// Use the next point
				final double angleXY = Util.getAngle(x[i] - lastX, y[i] - lastY);
				gpol.append(lastX + Math.cos(angleXY) * delta,
				            lastY + Math.sin(angleXY) * delta);
			}
			// Set next point:
			seq.setPosition(next_i);
		}
		
		// assign the new resampled points
		this.length = gpol.last + 1;
		this.x = gpol.x;
		this.y = gpol.y;
		// assign the vectors
		this.v_x = new double[this.length];
		this.v_y = new double[this.length];
		for (int k=1; k<this.length; ++k) {
			this.v_x[k] = this.x[k] - this.x[k-1];
			this.v_y[k] = this.y[k] - this.y[k-1];
		}
		// For non-closed this arrangement of vectors seems wrong, but IIRC the vector at zero is ignored.
		this.v_x[0] = this.x[0] - this.x[this.length -1];
		this.v_y[0] = this.y[0] - this.y[this.length -1];
	}

	/** As in the resampling method for CurveMorphing_just_C.c but for 2D. Uses the assigned 'delta'. Will reorder to counter-clock wise if necessary. */
	private void resampleOld() {
		final int MAX_AHEAD = 6;
		final double MAX_DISTANCE = 2.5 * delta;
		double[] ps_x = new double[length];
		double[] ps_y = new double[length];
		double[] v_x = new double[length];
		double[] v_y = new double[length];
		final int p_length = this.length; // to keep my head cool
		int ps_length = this.length; // the length of the resampled vectors

		reorderToCCW();

		// first resampled point is the same as 0
		ps_x[0] = x[0];
		ps_y[0] = y[0];
		// the index over x,y
		int i = 1;
		// the index over ps (the resampled points)
		int j = 1;
		// some vars:
		double dx, dy, sum;
		double angleXY, dist1, dist2;
		int[] ahead = new int[MAX_AHEAD];
		double[] distances = new double[MAX_AHEAD];
		int n_ahead = 0;
		int u, ii, k;
		int t, s;
		int prev_i = i;
		int iu;
		double dist_ahead;
		double[] w = new double[MAX_AHEAD];

		// HERE IS A great opportunity to change the resampling function
		// to a convolution with a gaussian, and then sample points so that they are delta apart.
		// Perhaps the approach is the opposite: loop over the expected number of points
		// and then compute the X,Y position for each according to the points in the line.
		
		// start infinite loop
		for (;prev_i <= i;) {
			if (prev_i > i) {
				//the loop has completed one round, since 'i' can go up to MAX_POINTS ahead
				// of the last point into the points at the begining of the array. Whenever
				// the next point 'i' to start exploring is set beyond the length of the array,
				// then the condition is met.
				break;
			}
			// check ps and v array lengths
			if (j >= ps_length) {
				// must enlarge
				ps_length += 20;
				v_x = Util.copy(v_x, ps_length);
				v_y = Util.copy(v_y, ps_length);
				ps_x = Util.copy(ps_x, ps_length);
				ps_y = Util.copy(ps_y, ps_length);
				System.out.println("Enlarged to: " + ps_length);
			}
			// get distances of MAX_POINTs ahead from the previous point
			n_ahead = 0; // reset
			for (t=0; t<MAX_AHEAD; t++) {
				s = i + t; // 'i' is the first to start inspecting from
				// fix 's' if it goes over the end // TODO this is problematic for sure for open curves.
				if (s >= p_length) {
					if (this.closed) s = s - p_length;
					else s = p_length -1; // the last
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
				angleXY = Util.getAngle(x[i] - ps_x[j-1], y[i] - ps_y[j-1]);

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
					recalculate(w, n_ahead, sum);
				}
				// calculate the new point using the weights
				dx = 0.0;
				dy = 0.0;
				for (u=0; u<n_ahead; u++) {
					iu = i+u;
					if (iu >= p_length) iu -= p_length; 
					angleXY = Util.getAngle(x[iu] - ps_x[j-1], y[iu] - ps_y[j-1]);
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
		double lastx = x[0];
		double lasty = y[0];
		if (!closed) {
			lastx = x[p_length -1];
			lasty = y[p_length -1];
		}
		dist1 = Math.sqrt((lastx - ps_x[j-1])*(lastx - ps_x[j-1]) + (lasty - ps_y[j-1])*(lasty - ps_y[j-1]));
		if (dist1 > delta*1.2) {
			// TODO needs revision
			// System.out.println("resampling terminated too early. Why?");
			angleXY = Util.getAngle(lastx - ps_x[j-1], lasty - ps_y[j-1]);
			dx = Math.cos(angleXY) * delta;
			dy = Math.sin(angleXY) * delta;
			while (dist1 > delta*1.2) {//added 1.2 to prevent last point from being generated too close to the first point
				// check ps and v array lengths
				if (j >= ps_length) {
					// must enlarge.
					ps_length += 20;
					v_x = Util.copy(v_x, ps_length);
					v_y = Util.copy(v_y, ps_length);
					ps_x = Util.copy(ps_x, ps_length);
					ps_y = Util.copy(ps_y, ps_length);
				}
				//add a point
				ps_x[j] = ps_x[j-1] + dx;
				ps_y[j] = ps_y[j-1] + dy;
				v_x[j] = dx;
				v_y[j] = dy;
				j++;
				dist1 = Math.sqrt((lastx - ps_x[j-1])*(lastx - ps_x[j-1]) + (lasty - ps_y[j-1])*(lasty - ps_y[j-1]));
			}
		}
		// set vector 0 to be the vector from the last point to the first // TODO also for non-closed?
		angleXY = Util.getAngle(lastx - ps_x[j-1], lasty - ps_y[j-1]);
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

	static private final void recalculate(final double[] w, final int length, final double sum_) {
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
			recalculate(w, length, sum);
		}
	}

	public void reorder(final int new_zero) { // this function is optimized for speed: no array duplications beyond minimally necessary, and no superfluous method calls.
		int i, j;
		// copying
		double[] tmp = new double[this.length];
		double[] src;
		// x
		src = x;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		x = tmp;
		tmp = src;
		// y
		src = y;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		y = tmp;
		tmp = src;
		// v_x
		src = v_x;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		v_x = tmp;
		tmp = src;
		// v_y
		src = v_y;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		v_y = tmp;
		tmp = src;

		// equivalent would be:
		//System.arraycopy(src, new_zero, tmp, 0, m - new_zero);
		//System.arraycopy(src, 0, tmp, m - new_zero, new_zero);
		//sv2.x = tmp;
		//tmp = src;

		// release
		tmp = null;
	}

	/** Subtracts vs2 vector j to this vector i and returns its length, without changing any data. */
	public double getDiffVectorLength(final int i, final int j, final VectorString vs2) {
		final VectorString2D vs = (VectorString2D)vs2;
		final double dx = v_x[i] - vs.v_x[j];
		final double dy = v_y[i] - vs.v_y[j];
		return Math.sqrt(dx*dx + dy*dy);
	}

	/** Distance from point i in this to point j in vs2. */
	public double distance(int i, VectorString vs, int j) {
		VectorString2D vs2 = (VectorString2D)vs;
		return distance(x[i], y[i],
				vs2.x[i], vs2.y[i]);
	}

	static public double distance(double x1, double y1,
			              double x2, double y2) {
		return Math.sqrt(Math.pow(x1 - x2, 2)
			       + Math.pow(y1 - y2, 2));
	}

	/** Create a new VectorString for the given range. If last &lt; first, it will be created as reversed. */
	public VectorString subVectorString(int first, int last) throws Exception {
		boolean reverse = false;
		if (last < first) {
			int tmp = first;
			first = last;
			last = tmp;
			reverse = true;
		}
		int len = last - first + 1;
		double[] x = new double[len];
		double[] y = new double[len];
		System.arraycopy(this.x, first, x, 0, len);
		System.arraycopy(this.y, first, y, 0, len);
		final VectorString2D vs = new VectorString2D(x, y, this.z, this.closed);
		if (reverse) vs.reverse();
		if (null != this.v_x) {
			// this is resampled, so:
			vs.delta = this.delta;
			// create vectors
			vs.v_x = new double[len];
			vs.v_y = new double[len];
			for (int i=1; i<len; i++) {
				vs.v_x[i] = vs.x[i] - vs.x[i-1];
				vs.v_y[i] = vs.y[i] - vs.y[i-1];
			}
		}
		return vs;
	}

	/** Invert the order of points. Will clear all vector arrays if any! */
	public void reverse() {
		Util.reverse(x);
		Util.reverse(y);
		delta = 0;
		if (null != v_x) v_x = v_y = null;
	}

	public int getDimensions() { return 2; }

	/** Scale to match cal.pixelWidth, cal.pixelHeight and computed depth. If cal is null, returns immediately. Will make all vectors null, so you must call resample(delta) again after calibrating. So it brings all values to cal.units, such as microns. */
	public void calibrate(final Calibration cal) {
		if (null == cal) return;
		this.cal = cal;
		final int sign = cal.pixelDepth < 0 ? - 1 :1;
		for (int i=0; i<x.length; i++) {
			x[i] *= cal.pixelWidth;
			y[i] *= cal.pixelHeight; // should be the same as pixelWidth
		}
		z *= cal.pixelWidth * sign; // since layer Z is in pixels.
		// reset vectors
		if (null != v_x) v_x = v_y = null;
		delta = 0;
	}

	public boolean isCalibrated() {
		return null != this.cal;
	}
	public Calibration getCalibrationCopy() {
		return null == this.cal ? null : this.cal.copy();
	}
	
	static public final void main(String[] args) {
		try {
			RoiDecoder rd = new RoiDecoder("/home/albert/Desktop/t2/test-spline/1067-polygon.roi");
			PolygonRoi sroi = (PolygonRoi)rd.getRoi();
			Polygon pol = sroi.getPolygon();
			double[] x = new double[pol.npoints];
			double[] y = new double[pol.npoints];
			for (int i=0; i<pol.npoints; ++i) {
				x[i] = pol.xpoints[i];
				y[i] = pol.ypoints[i];
			}
			VectorString2D v = new VectorString2D(x, y, 0, true);
			v.resample(1);
			StringBuffer sb = new StringBuffer();
			sb.append("x = [");
			for (int i=0; i<v.length; ++i) {
				sb.append(v.x[i] + ",\n");
			}
			sb.setLength(sb.length() -2);
			sb.append("]\n");
			sb.append("\ny = [");
			for (int i=0; i<v.length; ++i) {
				sb.append(v.y[i] + ",\n");
			}
			sb.setLength(sb.length() -2);
			sb.append("]\n");
			System.out.println(sb.toString());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
