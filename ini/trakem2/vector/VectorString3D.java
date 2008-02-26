/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007 Albert Cardona.

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
import ini.trakem2.utils.Vector3D;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.LayerSet;

import ij.measure.Calibration;

import java.util.Arrays;
import java.util.Random;
import java.util.ArrayList;
import Jama.Matrix;
import javax.media.j3d.Transform3D;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;
import java.awt.Color;


public class VectorString3D implements VectorString {

	/** Points. */
	private double[] x, y, z;
	/** Vectors, after resampling. */
	private double[] vx, vy, vz;
	/** Relative vectors, after calling 'relative()'. */
	private double[] rvx, rvy, rvz;
	/** Length of points and vectors - since arrays may be a little longer. */
	private int length = 0;
	/** The point interdistance after resampling. */
	private double delta = 0;

	/** Bits: 000CRXYZ where C=closed, R=reversed, X=mirrored in X, Y=mirrored in Y, Z=mirrored in Z. */
	private byte tags = 0;
	// masks
	static public final byte CLOSED = (1<<4);
	static public final byte REVERSED = (1<<3);
	static public final byte MIRROR_X = (1<<2);
	static public final byte MIRROR_Y = (1<<1);
	static public final byte MIRROR_Z = 1;

	private Calibration cal = null;

	/** Dependent arrays that will get resampled along. */
	private double[][] dep;

	// DANGER: the orientation of the curve can't be checked like in 2D. There is no up and down in the 3D space.

	public VectorString3D(double[] x, double[] y, double[] z, boolean closed) throws Exception {
		if (!(x.length == y.length && x.length == z.length)) throw new Exception("x,y,z must have the same length.");
		this.length = x.length;
		this.x = x;
		this.y = y;
		this.z = z;
		if (closed) this.tags ^= CLOSED;
	}

	/** Add an array that will get resampled along; must be of the same length as the value returned by length() */
	public void addDependent(final double[] a) throws Exception {
		if (a.length != this.length) throw new Exception("Dependent array must be of the same size as thevalue returned by length()");
		if (null == dep) {
			dep = new double[1][];
			dep[0] = a;
		} else {
			// resize and append
			double[][] dep2 = new double[dep.length + 1][];
			for (int i=0; i<dep.length; i++) dep2[i] = dep[i];
			dep2[dep.length] = a;
			dep = dep2;
		}
	}
	public double[] getDependent(final int i) {
		return dep[i];
	}

	/** Return the average point interdistance. */
	public double getAverageDelta() {
		double d = 0;
		for (int i=length -1; i>0; i--) {
			d += Math.sqrt( Math.pow(x[i] - x[i-1], 2) + Math.pow(y[i] - y[i-1], 2) + Math.pow(z[i] - z[i-1], 2));
		}
		return d / length;
	}

	/** Homogenize the average point interdistance to 'delta'. */
	public void resample(double delta) {
		if (Math.abs(delta - this.delta) < 0.0000001) {
			// delta is the same
			return;
		}
		this.delta = delta; // store for checking purposes
		this.resample();
	}

	public int length() { return length; }
	public double[] getPoints(final int dim) {
		switch (dim) {
			case 0: return x;
			case 1: return y;
			case 2: return z;
		}
		return null;
	}
	public double[] getVectors(final int dim) {
		switch (dim) {
			case 0: return vx;
			case 1: return vy;
			case 2: return vz;
		}
		return null;
	}
	public double getPoint(final int dim, final int i) {
		switch (dim) {
			case 0: return x[i];
			case 1: return y[i];
			case 2: return z[i];
		}
		return 0;
	}
	public double getVector(final int dim, final int i) {
		switch (dim) {
			case 0: return vx[i];
			case 1: return vy[i];
			case 2: return vy[i];
		}
		return 0;
	}
	public double getRelativeVector(final int dim, final int i) {
		switch (dim) {
			case 0: return rvx[i];
			case 1: return rvy[i];
			case 2: return rvy[i];
		}
		return 0;
	}

	public final boolean isClosed() {
		return 0 != (tags & CLOSED);
	}

	public void debug() {
		Utils.log2("#### " + getClass().getName() + " ####");
		for (int i=0; i<x.length; i++) {
			Utils.log2( i + ": " + x[i] + "," + y[i] + ", " + z[i]);
		}
		Utils.log2("#### END ####");
	}


	private class ResamplingData {

		private double[] rx, ry, rz,
				 vx, vy, vz;
		private double[][] dep;

		/** Initialize with a starting length. */
		ResamplingData(final int length, final double[][] dep) {
			// resampled points
			rx = new double[length];
			ry = new double[length];
			rz = new double[length];
			// vectors
			vx = new double[length];
			vy = new double[length];
			vz = new double[length];
			// dependents
			if (null != dep) this.dep = new double[dep.length][length];
		}
		/** Arrays are enlarged if necessary.*/
		final void setP(final int i, final double xval, final double yval, final double zval) {
			if (i >= rx.length) resize(i+10);
			this.rx[i] = xval;
			this.ry[i] = yval;
			this.rz[i] = zval;
		}
		/** Arrays are enlarged if necessary.*/
		final void setV(final int i, final double xval, final double yval, final double zval) {
			if (i >= rx.length) resize(i+10);
			this.vx[i] = xval;
			this.vy[i] = yval;
			this.vz[i] = zval;
		}
		/** Arrays are enlarged if necessary.*/
		final void setPV(final int i, final double rxval, final double ryval, final double rzval, final double xval, final double yval, final double zval) {
			if (i >= rx.length) resize(i+10);
			this.rx[i] = rxval;
			this.ry[i] = ryval;
			this.rz[i] = rzval;
			this.vx[i] = xval;
			this.vy[i] = yval;
			this.vz[i] = zval;
		}
		final void resize(final int new_length) {
			this.rx = Utils.copy(this.rx, new_length);
			this.ry = Utils.copy(this.ry, new_length);
			this.rz = Utils.copy(this.rz, new_length);
			this.vx = Utils.copy(this.vx, new_length);
			this.vy = Utils.copy(this.vy, new_length);
			this.vz = Utils.copy(this.vz, new_length);
			if (null != dep) {
				// java doesn't have generators! ARGH
				double[][] dep2 = new double[dep.length][];
				for (int i=0; i<dep.length; i++) dep2[i] = Utils.copy(dep[i], new_length);
				dep = dep2;
			}
		}
		final double x(final int i) { return rx[i]; }
		final double y(final int i) { return ry[i]; }
		final double z(final int i) { return rz[i]; }
		/** Distance from point rx[i],ry[i], rz[i] to point x[j],y[j],z[j] */
		final double distance(final int i, final double x, final double y, final double z) {
			return Math.sqrt(Math.pow(x - rx[i], 2)
				       + Math.pow(y - ry[i], 2)
				       + Math.pow(z - rz[i], 2));
		}
		final void put(final VectorString3D vs, final int length) {
			vs.x = Utils.copy(this.rx, length); // crop away empty slots
			vs.y = Utils.copy(this.ry, length);
			vs.z = Utils.copy(this.rz, length);
			vs.vx = Utils.copy(this.vx, length);
			vs.vy = Utils.copy(this.vy, length);
			vs.vz = Utils.copy(this.vz, length);
			vs.length = length;
			if (null != dep) {
				vs.dep = new double[dep.length][];
				for (int i=0; i<dep.length; i++) vs.dep[i] = Utils.copy(dep[i], length);
			}
		}
		final void setDeps(final int i, final double[][] src_dep, final int[] ahead, final double[] weight, final int len) {
			if (null == dep) return;
			if (i >= rx.length) resize(i+10);
			//
			for (int k=0; k<dep.length; k++) {
				for (int j=0; j<len; j++) {
					dep[k][i] += src_dep[k][ahead[j]] * weight[j];
				}
			} // above, the ahead and weight arrays (which have the same length) could be of larger length than the given 'len', thus len is used.
		}
	}

	static class Vector {
		private double x, y, z;
		private double length;
		// 0 coords and 0 length, virtue of the 'calloc'
		Vector() {}
		Vector(final double x, final double y, final double z) {
			set(x, y, z);
		}
		Vector(final Vector v) {
			this.x = v.x;
			this.y = v.y;
			this.z = v.z;
			this.length = v.length;
		}
		final public Object clone() {
			return new Vector(this);
		}
		final void set(final double x, final double y, final double z) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.length = computeLength();
		}
		final void normalize() {
			if (0 == length) return;
			// check if length is already 1
			if (Math.abs(1 - length) < 0.00000001) return; // already normalized
			this.x /= length;
			this.y /= length;
			this.z /= length;
			this.length = computeLength(); // should be 1
		}
		final double computeLength() {
			return Math.sqrt(x*x + y*y + z*z);
		}
		final double length() {
			return length;
		}
		final void scale(final double factor) {
			this.x *= factor;
			this.y *= factor;
			this.z *= factor;
			this.length = computeLength();
		}
		final void add(final Vector v, final boolean compute_length) {
			this.x += v.x;
			this.y += v.y;
			this.z += v.z;
			if (compute_length) this.length = computeLength();
		}
		final void setLength(final double len) {
			normalize();
			scale(len);
		}
		final void put(final int i, final ResamplingData r) {
			r.setPV(i, r.x(i-1) + this.x, r.y(i-1) + this.y, r.z(i-1) + this.z, this.x, this.y, this.z);
		}
		/** As row. */
		final void put(final double[] d) {
			d[0] = x;
			d[1] = y;
			d[2] = z;
		}
		/** As column. */
		final void put(final double[][] d, final int col) {
			d[0][col] = x;
			d[1][col] = y;
			d[2][col] = z;
		}
		final void put(final int i, final double[] x, final double[] y, final double[] z) {
			x[i] = this.x;
			y[i] = this.y;
			z[i] = this.z;
		}
		final Vector getCrossProduct(final Vector v) {
			// (a1; a2; a3) x (b1; b2; b3) = (a2b3 - a3b2; a3b1 - a1b3; a1b2 - a2b1)
			return new Vector(y * v.z - z * v.y,
					  z * v.x - x * v.z,
					  x * v.y - y * v.x);
		}
		final void setCrossProduct(final Vector v, final Vector w) {
			this.x = v.y * w.z - v.z * w.y;
			this.y = v.z * w.x - v.x * w.z;
			this.z = v.x * w.y - v.y * w.x;
		}
		/** Change coordinate system. */
		final void changeRef(final Vector v_delta, final Vector v_i1, final Vector v_new1) { // this vector works like new2
			// ortogonal system 1: the target
			// (a1'; a2'; a3')
			Vector a2 = new Vector(  v_new1   );  // vL
			a2.normalize();
			Vector a1 = a2.getCrossProduct(v_i1); // vQ
			a1.normalize();
			Vector a3 = a2.getCrossProduct(a1);
			// no need //a3.normalize();

			final double[][] m1 = new double[3][3];
			a1.put(m1, 0);
			a2.put(m1, 1);
			a3.put(m1, 2);
			final Matrix mat1 = new Matrix(m1);

			// ortogonal system 2: the current
			// (a1'; b2'; b3')
			Vector b2 = new Vector(  v_delta  ); // vA
			b2.normalize();
			Vector b3 = a1.getCrossProduct(b2); // vQ2

			final double[][] m2 = new double[3][3];
			a1.put(m2, 0);
			b2.put(m2, 1);
			b3.put(m2, 2);
			final Matrix mat2 = new Matrix(m2).transpose();

			final Matrix R = mat1.times(mat2);
			final Matrix mthis = new Matrix(new double[]{this.x, this.y, this.z}, 1);
			// The rotated vector as a one-dim matrix
			// (i.e. the rescued difference vector as a one-dimensional matrix)
			final Matrix v_rot = R.transpose().times(mthis.transpose()); // 3x3 times 3x1, hence the transposing of the 1x3
			final double[][] arr = v_rot.getArray();
			// done!
			this.x = arr[0][0];
			this.y = arr[1][0];
			this.z = arr[2][0];
		}
		Point3d asPoint3d() {
			return new Point3d(x, y, z);
		}
	}

	private final void recalculate(final double[] w, final int length, final double sum_) {
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

	private void resample() {
		// parameters
		final int MAX_AHEAD = 6;
		final double MAX_DISTANCE = 2.5 * delta;

		// convenient data carrier and editor
		final ResamplingData r = new ResamplingData(this.length, this.dep);
		final Vector vector = new Vector();

		// first resampled point is the same as point zero
		r.setP(0, x[0], y[0], z[0]);
		// the first vector is 0,0,0 unless the path is closed, in which case it contains the vector from last-to-first.

		// index over x,y,z
		int i = 1;
		// index over rx,ry,rz (resampled points)
		int j = 1;
		// some vars
		int t, s, ii, u, iu, k;
		int prev_i = i;
		double dist_ahead, dist1, dist2, sum;
		final double[] w = new double[MAX_AHEAD];
		final double[] distances = new double[MAX_AHEAD];
		final Vector[] ve = new Vector[MAX_AHEAD];
		int next_ahead;
		for (next_ahead = 0; next_ahead < MAX_AHEAD; next_ahead++) ve[next_ahead] = new Vector();
		final int[] ahead = new int[MAX_AHEAD];

		try {

		// start infinite loop
		for (;prev_i <= i;) {
			if (prev_i > i || (!isClosed() && i == this.length -1)) break;
			// get distances of MAX_POINTs ahead from the previous point
			next_ahead = 0;
			for (t=0; t<MAX_AHEAD; t++) {
				s = i + t;
				// fix 's' if it goes over the end
				if (s >= this.length) {
					if (isClosed()) s -= this.length;
					else break;
				}
				dist_ahead = r.distance(j-1, x[s], y[s], z[s]);
				if (dist_ahead < MAX_DISTANCE) {
					ahead[next_ahead] = s;
					distances[next_ahead] = dist_ahead;
					next_ahead++;
				}
			}
			if (0 == next_ahead) {
				// No points (ahead of the i point) are found within MAX_DISTANCE
				// Just use the next point as target towards which create a vector of length delta
				vector.set(x[i] - r.x(j-1), y[i] - r.y(j-1), z[i] - r.z(j-1));
				dist1 = vector.length();
				vector.setLength(delta);
				vector.put(j, r);
				if (null != dep) r.setDeps(j, dep, new int[]{i}, new double[]{1.0}, 1);

				//Utils.log2("j: " + j + " (ZERO)  " + vector.computeLength() + "  " + vector.length());

				//correct for point overtaking the not-close-enough point ahead in terms of 'delta_p' as it is represented in MAX_DISTANCE, but overtaken by the 'delta' used for subsampling:
				if (dist1 <= delta) {
					//look for a point ahead that is over distance delta from the previous j, so that it will lay ahead of the current j
					for (u=i; u<this.length; u++) {
						dist2 = Math.sqrt(Math.pow(x[u] - r.x(j-1), 2)
								+ Math.pow(y[u] - r.y(j-1), 2)
								+ Math.pow(z[u] - r.z(j-1), 2));
						if (dist2 > delta) {
							prev_i = i;
							i = u;
							break;
						}
					}
				}
			} else {
				// Compose a point ahead out of the found ones.
				//
				// First, adjust weights for the points ahead
				w[0] = distances[0] / MAX_DISTANCE;
				double largest = w[0];
				for (u=1; u<next_ahead; u++) {
					w[u] = 1 - (distances[u] / MAX_DISTANCE);
					if (w[u] > largest) {
						largest = w[u];
					}
				}
				// normalize weights: divide by largest
				sum = 0;
				for (u=0; u<next_ahead; u++) {
					w[u] = w[u] / largest;
					sum += w[u];
				}
				// correct error. The closest point gets the extra
				if (sum < 1.0) {
					w[0] += 1.0 - sum;
				} else {
					recalculate(w, next_ahead, sum);
				}
				// Now, make a vector for each point with the corresponding weight
				vector.set(0, 0, 0);
				for (u=0; u<next_ahead; u++) {
					iu = i + u;
					if (iu >= this.length) iu -= this.length;
					ve[u].set(x[iu] - r.x(j-1), y[iu] - r.y(j-1), z[iu] - r.z(j-1));
					ve[u].setLength(w[u] * delta);
					vector.add(ve[u], u == next_ahead-1); // compute the length only on the last iteration
				}
				// correct potential errors
				if (Math.abs(vector.length() - delta) > 0.00000001) {
					vector.setLength(delta);
				}
				// set
				vector.put(j, r);
				if (null != dep) r.setDeps(j, dep, ahead, w, next_ahead);

				//Utils.log2("j: " + j + "  (" + next_ahead + ")   " + vector.computeLength() + "  " + vector.length());


				// find the first point that is right ahead of the newly added point
				// so: loop through points that lay within MAX_DISTANCE, and find the first one that is right past delta.
				ii = i;
				for (k=0; k<next_ahead; k++) {
					if (distances[k] > delta) {
						ii = ahead[k];
						break;
					}
				}
				// correct for the case of unseen point (because all MAX_POINTS ahead lay under delta):
				prev_i = i;
				if (i == ii) {
					i = ahead[next_ahead-1] +1; //the one after the last.
					if (i >= this.length) {
						if (isClosed()) i = i - this.length; // this.length is the length of the x,y,z, the original points
						else i = this.length -1;
					}
				} else {
					i = ii;
				}
			}
			//advance index in the new points
			j += 1;
		} // end of for loop

		} catch (Exception e) {
			e.printStackTrace();
			Utils.log2("Some data: x,y,z .length = " + x.length + "," + y.length + "," + z.length
				 + "\nj=" + j + ", i=" + i + ", prev_i=" + prev_i
					);
		}


		dist_ahead = r.distance(j-1, x[this.length-1], y[this.length-1], z[this.length-1]);

		//Utils.log2("delta: " + delta + "\nlast point: " + x[x.length-1] + ", " + y[y.length-1] + ", " + z[z.length-1]);
		//Utils.log2("last resampled point: x,y,z " + r.x(j-1) + ", " + r.y(j-1) + ", " + r.z(j-1));
		//Utils.log2("distance: " + dist_ahead);

		// see whether the subsampling terminated too early, and fill with a line of points.
		final int last_i = isClosed() ? 0 : this.length -1;
		if (dist_ahead > delta*1.2) {
			System.out.println("resampling terminated too early. Why?");
			while (dist_ahead > delta*1.2) {
				// make a vector from the last resampled point to the last point
				vector.set(x[last_i] - r.x(j-1), y[last_i] - r.y(j-1), z[last_i] - r.z(j-1));
				// resize it to length delta
				vector.setLength(delta);
				vector.put(j, r);
				j++;
				dist_ahead = r.distance(j-1, x[last_i], y[last_i], z[last_i]);
			}
		}
		// done!
		r.put(this, j); // j acts as length of resampled points and vectors
		// vector at zero is left as 0,0 which makes no sense. Should be the last point that has no vector, or has it only in the event that the list of points is declared as closed: a vector to the first point. Doesn't really matter though, as long as it's clear: as of right now, the first point has no vector unless the path is closed, in which case it contains the vector from the last-to-first.
	}

	/** Reorder the arrays so that the index zero becomes new_zero -the arrays are circular. */
	public void reorder(final int new_zero) {
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
		// z
		src = z;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		z = tmp;
		tmp = src;
		// vx
		src = vx;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		vx = tmp;
		tmp = src;
		// vy
		src = vy;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		vy = tmp;
		tmp = src;
		// vz
		src = vz;
		for (i=0, j=new_zero; j<length; i++, j++) { tmp[i] = src[j]; }
		for (j=0; j<new_zero; i++, j++) { tmp[i] = src[j]; }
		vz = tmp;
		tmp = src;
	}

	/** Subtracts vs2 vector j to this vector i and returns its length, without changing any data. */
	public double getDiffVectorLength(final int i, final int j, final VectorString vsb) {
		final VectorString3D vs = (VectorString3D)vsb;
		if (null == rvx || null == rvy || null == rvz) {
			// use absolute vectors
			final double dx = vx[i] - vs.vx[j];
			final double dy = vy[i] - vs.vy[j];
			final double dz = vz[i] - vs.vz[j];

			if (i == j){
				double leni = Math.sqrt(vx[i]*vx[i]
						      + vy[i]*vy[i]
						      + vz[i]*vz[i]);
				double lenj = Math.sqrt(vs.vx[j]*vs.vx[j]
						      + vs.vy[j]*vs.vy[j]
						      + vs.vz[j]*vs.vz[j]);

				/*
				Utils.log2("i: " + i + " len: " + leni + "\t\tj: " + j + " len:" + lenj
					+ "\n\t" + vx[i] + "\t\t\t" +  vs.vx[j]
					+ "\n\t" + vy[i] + "\t\t\t" +  vs.vy[j]
					+ "\n\t" + vz[i] + "\t\t\t" +  vs.vz[j]);
				*/
			}

			return Math.sqrt(dx*dx + dy*dy + dz*dz);

		} else {
			// use relative vectors
			final double dx = rvx[i] - vs.rvx[j];
			final double dy = rvy[i] - vs.rvy[j];
			final double dz = rvz[i] - vs.rvz[j];
			final double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
			if (j == i) /*(j > i-2 && j < i+2)*/ {
				//Utils.log2("rel: i,j,dist "+ i + ", " + j + ", " + dist + " dx,dy,dz: " + dx + ", " + dy + ", " + dz);
				double leni = Math.sqrt(rvx[i]*rvx[i]
						      + rvy[i]*rvy[i]
						      + rvz[i]*rvz[i]);
				double lenj = Math.sqrt(vs.rvx[j]*vs.rvx[j]
						      + vs.rvy[j]*vs.rvy[j]
						      + vs.rvz[j]*vs.rvz[j]);

				/*
				Utils.log2("i: " + i + " len: " + leni + "\t\tj: " + j + " len:" + lenj
					+ "\n\t" + rvx[i] + "\t\t\t" +  vs.rvx[j]
					+ "\n\t" + rvy[i] + "\t\t\t" +  vs.rvy[j]
					+ "\n\t" + rvz[i] + "\t\t\t" +  vs.rvz[j]);
				*/
			}
			return dist;
		}
	}

	public Object clone() {
		try {
			final VectorString3D vs = new VectorString3D(Utils.copy(x, length), Utils.copy(y, length), Utils.copy(z, length), isClosed());
			vs.delta = delta;
			if (null != vx) vs.vx = Utils.copy(vx, length);
			if (null != vy) vs.vy = Utils.copy(vy, length);
			if (null != vz) vs.vz = Utils.copy(vz, length);
			if (null != rvx) vs.rvx = Utils.copy(rvx, length);
			if (null != rvy) vs.rvy = Utils.copy(rvy, length);
			if (null != rvz) vs.rvz = Utils.copy(rvz, length);
			vs.tags = this.tags;
			return vs;
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	public VectorString3D makeReversedCopy() {
		try {
			final VectorString3D vs = new VectorString3D(Utils.copy(x, length), Utils.copy(y, length), Utils.copy(z, length), isClosed());
			vs.reverse();
			if (delta != 0 && null != vx) {
				vs.delta = this.delta;
				vs.resample();
				if (null != rvx) {
					vs.relative();
				}
			}
			return vs;
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Create the relative vectors, that is, the differences of one vector to the next. In this way, vectors represent changes in the path and are independent of the actual path orientation in space. */
	public void relative() {
		// create vectors if not there yet
		if (null == vx || null == vy || null == vz) {
			resample(getAverageDelta());
		}
		rvx = new double[length];
		rvy = new double[length];
		rvz = new double[length];


		// TODO 2: also, it should test both orientations and select the best

		// TODO: there is a mistake. Both vectors, i and i-1, have to be rotated so that vector i becomes aligned with whatever axis, as long as all vectors get aligned to the same. In this way then one truly stores differences only. An easy approach is to store the angle difference, since then it will be a scalar value independent of the orientation of the vectors. As long as the angle is represented with a vector, fine.
		// Another alternative is, if both vectors are rotated, then the first is always on the axis and the second has the "difference" information already as is.
		// So for a vector c1,c2,c3 of length L, I want it as L,0,0 (for example) and then the second vector, as rotated, is the one to use as relative.
		// So: v[i-1], vL, and v[i]; then v[i-1] + W = vL, so W = vL - v[i-1], and then the diff vector is v[i] + W

		//double wx, wy, wz; // the W
		//double vLx; // vLy = vLz = 0

		// the first vector:
		if (isClosed()) { // last to first
			rvx[0] = vx[0] + delta - vx[length-1];
			rvy[0] = vy[0] - vy[length-1];
			rvz[0] = vz[0] - vz[length-1];
		} // else, as open curve the first vector remains 0,0,0
		// fill in the rest:


		final Vector vL = new Vector();
		final Vector vA = new Vector();
		final Vector vQ = new Vector();
		final Vector vQQ = new Vector();
		final Vector vQ2 = new Vector();
		final double[][] m1 = new double[3][3];
		final double[][] m2 = new double[3][3];

		for (int i=1; i<length; i++) {
			// So: Johannes Schindelin said: vector Y is the rotated, X is the original
			// Y = A + R * (X - A)    where R is a rotation matrix (A is the displacement vector, 0,0,0 in this case)
			// so the axis of rotation is the cross product of {L,0,0} and i-1
			// (a1; a2; a3) x (b1; b2; b3) = (a2b3 - a3b2; a3b1 - a1b3; a1b2 - a2b1)
			//Vector axis = new Vector(0 - 0, 0 - delta * vz[i-1], delta * vy[i-1] - 0);
			vL.set(delta, 0, 0);
			vL.normalize();		// this line can be reduced to new Vector(1,0,0);
			vA.set(vx[i-1], vy[i-1], vz[i-1]);
			vA.normalize();
			vQ.setCrossProduct(vA, vL);
			//vQ.normalize(); // no need: the cross product of two normalized vectors is also a normalized vector
			vQQ.setCrossProduct(vQ, vL);
			//vQQ.normalize(); // no need

			// the matrices expect the vectors as columns, not as rows! ARGH! Solved.

			// now, vQ,vL,vQQ define an orthogonal system
			// (vQ'; vL'; vQ' x vL')
			vQ.put(m1, 0);
			vL.put(m1, 1);
			vQQ.put(m1, 2);
			Matrix mat1 = new Matrix(m1);

			// (vQ'; vA'; vQ' x vA')^T            // Johannes wrote vB to mean vA. For me vB is the second vector
			vQ.put(m2, 0);
			vA.put(m2, 1);
			vQ2.setCrossProduct(vQ, vA);
			//vQ2.normalize(); // no need
			vQ2.put(m2, 2);
			Matrix mat2 = new Matrix(m2).transpose();

			final Matrix R = mat1.times(mat2);
			// the difference vector as a one-dimensional matrix
			final Matrix vB = new Matrix(new double[]{vx[i] - vx[i-1], vy[i] - vy[i-1], vz[i] - vz[i-1]}, 1);
			final Matrix vB_rot = R.transpose().times(vB.transpose());  //  3x3 times 3x1, hence the transposing of the 1x3 vector so that the inner lengths are the same
			double[][] arr = vB_rot.getArray();
			rvx[i] = arr[0][0];
			rvy[i] = arr[1][0];
			rvz[i] = arr[2][0];
			//Utils.log2("rv{x,y,z}[" + i + "]= " + rvx[i] + ", " + rvy[i] + ", " + rvz[i]);
		}
	}

	/** Expand or shrink the points in this 3D path so that the average point interdistance becomes delta.
	 *
	 * This method is intended to enhance the comparison of two paths in 3D space which may be similar but differ greatly in dimensions; for example, secondary lineage tracts in 3rd instar Drosophila larvae versus adult.
	 */
	public void equalize(final double target_delta) {
		if (length < 2) return;
		final double scale = target_delta / getAverageDelta();
		for (int i=0; i<length; i++) {
			x[i] *= scale;
			y[i] *= scale;
			z[i] *= scale;
		}
	}

	static public VectorString3D createRandom(final int length, final double delta, final boolean closed) throws Exception {
		Utils.log2("Creating random with length " + length + " and delta " + delta);
		double[] x = new double[length];
		double[] y = new double[length];
		double[] z = new double[length];
		Random rand = new Random(69997); // fixed seed, so that when length is equal the generated sequence is also equal
		Vector v = new Vector();
		for (int i=0; i<length; i++) {
			v.set(rand.nextDouble()*2 -1, rand.nextDouble()*2 -1, rand.nextDouble()*2 -1); // random values in the range [-1,1]
			v.setLength(delta);
			v.put(i, x, y, z);
		}
		final VectorString3D vs = new VectorString3D(x, y, z, closed);
		vs.delta = delta;
		vs.vx = new double[length];
		vs.vy = new double[length];
		vs.vz = new double[length];
		for (int i=1; i<length; i++) {
			vs.vx[i] = vs.x[i] - vs.x[i-1];
			vs.vy[i] = vs.y[i] - vs.y[i-1];
			vs.vz[i] = vs.z[i] - vs.z[i-1];
		}
		if (closed) {
			vs.vx[0] = vs.x[0] - vs.x[length-1];
			vs.vy[0] = vs.y[0] - vs.y[length-1];
			vs.vz[0] = vs.z[0] - vs.z[length-1];
		}
		return vs;
	}

	/** Scale to match cal.pixelWidth, cal.pixelHeight and cal.pixelDepth. If cal is null, returns immediately. Will make all vectors null, so you must call resample(delta) again after calibrating. */
	public void calibrate(final Calibration cal) {
		if (null == cal) return;
		this.cal = cal;
		for (int i=0; i<x.length; i++) {
			x[i] *= cal.pixelWidth;
			y[i] *= cal.pixelHeight;
			// TODO z is obtained from the layer, which is already set in pixel coordinates // z[i] *= cal.pixelDepth;
			// That has to change eventually.
		}
		// reset vectors
		vx = vy = vz = null;
		rvx = rvy = rvz = null;
		delta = 0;
	}

	public boolean isCalibrated() {
		return null != this.cal;
	}
	public Calibration getCalibrationCopy() {
		return null == this.cal ? null : this.cal.copy();
	}

	/** Invert the order of points. Will clear all vector arrays if any! */
	public void reverse() {
		tags ^= REVERSED; // may be re-reversed
		// reverse point arrays
		Utils.reverse(x);
		Utils.reverse(y);
		Utils.reverse(z);
		// eliminate vector data
		delta = 0;
		if (null != vx || null != vy || null != vz) {
			vx = vy = vz = null;
		}
		if (null != rvx || null != rvy || null != rvz) {
			rvx = rvy = rvz = null;
		}
	}

	public boolean isReversed() {
		return 0 != (this.tags & REVERSED);
	}

	static public VectorString3D createInterpolated(final Editions ed, final double alpha) throws Exception {
		VectorString3D vs1 = (VectorString3D)ed.getVS1();
		VectorString3D vs2 = (VectorString3D)ed.getVS2();
		return vs1.createInterpolated(vs2, ed, alpha);
	}

	/** Create an interpolated VectorString3D between this and the given one, with the proper weight 'alpha' which must be 0 &lt;= weight &lt;= 1 (otherwise returns null) */
	public VectorString3D createInterpolated(final VectorString3D other, final Editions ed, final double alpha) throws Exception {
		// check deltas: must be equal
		if (Math.abs(this.delta - other.delta) > 0.00000001) {
			throw new Exception("deltas are not the same: this.delta=" + this.delta + "  other.delta=" + other.delta);
		}
		// check nonsense weight
		if (alpha < 0 || alpha > 1) return null;
		// if 0 or 1, the limits, the returned VectorString3D will be equal to this one or to the other, but relative to this one in position.

		// else, make a proper intermediate curve
		double[] vx1 = this.vx;
		double[] vy1 = this.vy;
		double[] vz1 = this.vz;
		double[] vx2 = other.vx;
		double[] vy2 = other.vy;
		double[] vz2 = other.vz;
		boolean relative = false;
		if (null != this.rvx && null != other.rvx) {
			// both are relative
			vx1 = this.rvx;
			vy1 = this.rvy;
			vz1 = this.rvz;
			vx2 = other.rvx;
			vy2 = other.rvy;
			vz2 = other.rvz;
			relative = true;
		}

		final int[][] editions = ed.getEditions();
		final int n_editions = ed.length(); // == Math.max(this.length, other.length)
		final double[] px = new double[n_editions];
		final double[] py = new double[n_editions];
		final double[] pz = new double[n_editions];

		// first point: an average of both starting points
		px[0] = (this.x[0] * (1-alpha) + other.x[0] * alpha);
		py[0] = (this.y[0] * (1-alpha) + other.y[0] * alpha);
		pz[0] = (this.z[0] * (1-alpha) + other.z[0] * alpha);

		int start = 0;
		int end = n_editions;
		if (Editions.INSERTION == editions[0][0] || Editions.DELETION == editions[0][0]) {
			start = 1;
		}

		final int n = this.length();
		final int m = other.length();

		int i=0,j=0;
		int next=0;
		double vs_x=0, vs_y=0, vs_z=0;
		final Vector v = new Vector();
		final Vector v_newref = new Vector();
		final Vector v_delta = new Vector(this.delta, 0, 0);
		final Vector v_i1 = new Vector();
		final double[] d = new double[3];


		try {

		for (int e=start; e<end; e++) {
			i = editions[e][1];
			j = editions[e][2];
			// check for deletions and insertions at the lower-right edges of the matrix:
			if (isClosed()) {
				if (i == n) i = 0; // zero, so the starting vector is applied. 
				if (j == m) j = 0;
			} else {
				if (i == n) i -= 1;
				if (j == m) j -= 1;
			}

			// TODO: add dependents!


			// do:
			switch (editions[e][0]) {
				case Editions.INSERTION:
					vs_x = vx2[j] * alpha;
					vs_y = vy2[j] * alpha;
					vs_z = vz2[j] * alpha;
					break;
				case Editions.DELETION:
					vs_x = vx1[i] * (1.0 - alpha);
					vs_y = vy1[i] * (1.0 - alpha);
					vs_z = vz1[i] * (1.0 - alpha);
					break;
				case Editions.MUTATION:
					vs_x = vx1[i] * (1.0 - alpha) + vx2[j] * alpha;
					vs_y = vy1[i] * (1.0 - alpha) + vy2[j] * alpha;
					vs_y = vz1[i] * (1.0 - alpha) + vz2[j] * alpha;
					break;
				default:
					// using same vectors as last edition
					Utils.log2("\nIgnoring unknown edition " + editions[e][0]);
					break;
			}
			// store the point
			if (relative) {
				if (0 == i) {
					// ?
				} else {
					// must inverse rotate the vector, so that instead of the difference relative to (delta,0,0)
					// it becomes the difference relative to (vx1,vy1,vz1)-1, i.e. absolute again in relation to this VectorString3D
					v_newref.set(vx1[i], vy1[i], vz1[i]);
					v_i1.set(vx1[i-1], vy1[i-1], vz1[i-1]);
					v.set(vs_x, vs_y, vs_z);
					v.changeRef(v_delta, v_i1, v_newref);
					v.put(d);
					vs_x = vx1[i-1] + d[0]; // making the difference vector be an absolute vector relative to this (uf!)
					vs_y = vy1[i-1] + d[1]; 
					vs_z = vz1[i-1] + d[2];
				}
			}
			if (next+1 == px.length) {
				Utils.log2("breaking early");
				break;
			}

			px[next+1] = px[next] + vs_x;
			py[next+1] = py[next] + vs_y;
			py[next+1] = py[next] + vs_z;
			// advance
			next++;
		}

		
		} catch (Exception e) {
			IJError.print(e);
			Utils.log2("next: " + next + " length: " + px.length + " i,j: " + i + ", " + j);
		}


		return new VectorString3D(px, py, pz, isClosed());
	}

	/** Where axis is any of VectorString.X_AXIS, .Y_AXIS or .Z_AXIS,
	    and the mirroring is done relative to the local 0,0 of this VectorString. */
	public void mirror(final int axis) {
		final Transform3D t = new Transform3D();
		switch(axis) {
			case VectorString.X_AXIS:
				t.setScale(new Vector3d(-1, 1, 1));
				tags ^= MIRROR_X;
				break;
			case VectorString.Y_AXIS:
				t.setScale(new Vector3d(1, -1, 1));
				tags ^= MIRROR_Y;
				break;
			case VectorString.Z_AXIS:
				t.setScale(new Vector3d(1, 1, -1));
				tags ^= MIRROR_Z;
				break;
			default:
				return;
		}
		final Point3d p = new Point3d();
		transform(x, y, z, t, p);
		if (null != this.vx) transform(vx, vy, vz, t, p);
		if (null != this.rvx) transform(rvx, rvy, rvz, t, p);
	}

	private final void transform(final double[] x, final double[] y, final double[] z, final Transform3D t, final Point3d p) {
		for (int i=0; i<length; i++) {
			p.x = x[i];	p.y = y[i];	p.z = z[i];
			t.transform(p);
			x[i] = p.x;	y[i] = p.y;	z[i] = p.z;
		}
	}

	public boolean isMirroredX() { return 0 != (tags & MIRROR_X); }
	public boolean isMirroredY() { return 0 != (tags & MIRROR_Y); }
	public boolean isMirroredZ() { return 0 != (tags & MIRROR_Z); }
	public byte getTags() { return tags; }

	/** The physical length of this sequence of points. */
	public double computeLength() {
		double len = 0;
		for (int i=1; i<length; i++) {
			len += Math.sqrt(Math.pow(x[i] - x[i-1], 2) + Math.pow(y[i] - y[i-1], 2) + Math.pow(z[i] - z[i-1], 2));
		}
		return len;
	}

	/** Returns a normalized average vector, or null if not resampled. */
	private Vector3d makeAverageNormalizedVector() {
		if (null == vx || null == vy || null == vz) return null;
		final Vector3d v = new Vector3d();
		for (int i=0; i<length; i++) {
			v.x += vx[i];
			v.y += vy[i];
			v.z += vz[i];
		}
		v.normalize();
		return v;
	}

	static public double distance(double x1, double y1, double z1,
			              double x2, double y2, double z2) {
		return Math.sqrt(Math.pow(x1 - x2, 2)
			       + Math.pow(y1 - y2, 2)
			       + Math.pow(z1 - z2, 2));
	}

	/** Returns an array of 4 Vector3d: the three unit vectors in the same order as the vector strings, and the origin of coordinates. */
	static public Vector3d[] createOrigin(VectorString3D vs1, VectorString3D vs2, VectorString3D vs3) {
		// Aproximate a origin of coordinates
		VectorString3D[] vs = new VectorString3D[]{vs1, vs2, vs3};
		ArrayList<Point3d> ps = new ArrayList<Point3d>();
		int[] dir = new int[]{1, 1, 1};

		for (int i=0; i<vs.length; i++) {
			for (int k=i+1; k<vs.length; k++) {
				double min_dist = Double.MAX_VALUE;
				int ia=0, ib=0;
				for (int a=0; a<vs[i].length(); a++) {
					for (int b=0; b<vs[k].length(); b++) {
						double d = distance(vs[i], a, vs[k], b);
						if (d < min_dist) {
							min_dist = d;
							ia = a;
							ib = b;
						}
					}
				}
				ps.add(new Point3d((vs[i].x[ia] + vs[k].x[ib])/2,
						  (vs[i].y[ia] + vs[k].y[ib])/2,
						  (vs[i].z[ia] + vs[k].z[ib])/2));
				// determine orientation of the VectorString3D relative to the origin
				if (ia > vs[i].length()/2) dir[i] = -1;
				if (ib > vs[k].length()/2) dir[k] = -1;
				// WARNING: we don't check for the case where it contradicts
			}
		}
		Vector3d origin = new Vector3d();
		final int len = ps.size();
		for (Point3d p : ps) {
			p.x /= len;
			p.y /= len;
			p.z /= len;
		}
		for (Point3d p : ps) origin.add(p);

		// aproximate a vector for each axis
		Vector3d v1 = vs1.makeAverageNormalizedVector(); // v1 is peduncle
		Vector3d v2 = vs2.makeAverageNormalizedVector(); // v2 is dorsal lobe
		Vector3d v3 = vs3.makeAverageNormalizedVector(); // v3 is medial lobe

		// adjust orientation, so vectors point away from the origin towards the other end of the vectorstring
		v1.scale(dir[0]);
		v2.scale(dir[1]);
		v3.scale(dir[2]);

		Utils.log2("dir[0]=" + dir[0]);
		Utils.log2("dir[1]=" + dir[1]);
		Utils.log2("dir[2]=" + dir[2]);

		// compute medial vector: perpendicular to the plane made by peduncle and dorsal lobe
		Vector3d vc_medial = new Vector3d();
		vc_medial.cross(v1, v2);
		// check orientation:
		Vector3d vc_med = new Vector3d(vc_medial);
		vc_med.add(v3); // adding the actual medial lobe vector
		// if the sum is smaller, then it means it should be inverted (it was the other side)
		if (vc_med.length() < v3.length()) {
			vc_medial.scale(-1);
			Utils.log2("inverting cp v3");
		}

		// compute dorsal vector: perpedicular to the plane made by v1 and vc_medial
		Vector3d vc_dorsal = new Vector3d();
		vc_dorsal.cross(v1, vc_medial);
		// check orientation
		Vector3d vc_dor = new Vector3d(vc_dorsal);
		vc_dor.add(v2);
		// if the sum is smaller, invert
		if (vc_dor.length() < v2.length()) {
			vc_dorsal.scale(-1);
			Utils.log2("inverting cp v2");
		}

		vc_medial.normalize();
		vc_dorsal.normalize();

		// SO, finally, the three vectors are
		//  v1
		//  vc_medial
		//  vc_dorsal

		return new Vector3d[]{
			v1,
			vc_medial,
			vc_dorsal,
			origin
		};
	}

	static private double distance(VectorString3D vs1, int i, VectorString3D vs2, int j) {
		return distance(vs1.x[i], vs1.y[i], vs1.z[i],
				vs2.x[j], vs2.y[j], vs2.z[j]);
	}

	static public void testCreateOrigin(LayerSet ls, VectorString3D vs1, VectorString3D vs2, VectorString3D vs3) {
		try {
			// create vectors
			double delta = (vs1.getAverageDelta() + vs2.getAverageDelta() + vs3.getAverageDelta()) / 3;
			vs1.resample(delta);
			vs2.resample(delta);
			vs3.resample(delta);
			//
			Vector3d[] o = createOrigin(vs1, vs2, vs3);
			Display3D.addMesh(ls, makeVSFromP(o[0], o[3]), "v1", Color.green);
			Display3D.addMesh(ls, makeVSFromP(o[1], o[3]), "v2", Color.orange);
			Display3D.addMesh(ls, makeVSFromP(o[2], o[3]), "v3", Color.red);
			System.out.println("v1:" + o[0]);
			System.out.println("v2:" + o[1]);
			System.out.println("v3:" + o[2]);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	static private VectorString3D makeVSFromP(Vector3d p, Vector3d origin) throws Exception {
		double[] x1 = new double[20];
		double[] y1 = new double[20];
		double[] z1 = new double[20];
		double K = 10;
		x1[0] = p.x * K;
		y1[0] = p.y * K;
		z1[0] = p.z * K;
		for (int i=1; i<x1.length; i++) {
			x1[i] = p.x * K + x1[i-1];
			y1[i] = p.y * K + y1[i-1];
			z1[i] = p.z * K + z1[i-1];
		}
		for (int i=0; i<x1.length; i++) {
			x1[i] += origin.x;
			y1[i] += origin.y;
			z1[i] += origin.z;
		}
		return new VectorString3D(x1, y1, z1, false);
	}
}
