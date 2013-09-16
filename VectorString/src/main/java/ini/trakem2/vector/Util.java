package ini.trakem2.vector;

public class Util {

	/** Will make a new double[] array, then fit in it as many points from the given array as possible according to the desired new length. If the new length is shorter that a.length, it will shrink and crop from the end; if larger, the extra spaces will be set with zeros. */
	static public final double[] copy(final double[] a, final int new_length) {
		final double[] b = new double[new_length];
		final int len = a.length > new_length ? new_length : a.length; 
		System.arraycopy(a, 0, b, 0, len);
		return b;
	}

	static public final double[] copy(final double[] a, final int first, final int new_length) {
		final double[] b = new double[new_length];
		final int len = new_length < a.length - first ? new_length : a.length - first;
		System.arraycopy(a, first, b, 0, len);
		return b;
	}


	// from utilities.c in my CurveMorphing C module ... from C! Java is a low level language with the disadvantages of the high level languages ...
	/** Returns the angle in radians of the given polar coordinates, correcting the Math.atan2 output.
	 * Adjusting so that 0 is 3 o'clock, PI+PI/2 is 12 o'clock, PI is 9 o'clock, and PI/2 is 6 o'clock (why atan2 doesn't output angles this way? I remember I had the same problem for Pipe.java in the A_3D_editing plugin) */
	static public final double getAngle(final double x, final double y) {
		// calculate angle
		double a = Math.atan2(x, y);
		// fix too large angles (beats me why are they ever generated)
		if (a > 2 * Math.PI) {
			a = a - 2 * Math.PI;
		}
		// fix atan2 output scheme to match my mental scheme
		if (a >= 0.0 && a <= Math.PI/2) {
			a = Math.PI/2 - a;
		} else if (a < 0 && a >= -Math.PI) {
			a = Math.PI/2 -a;
		} else if (a > Math.PI/2 && a <= Math.PI) {
			a = Math.PI + Math.PI + Math.PI/2 - a;
		}
		return a;
	}

	/** Reverse in place an array of doubles. */
	static public final void reverse(final double[] a) {
		for (int left=0, right=a.length-1; left<right; left++, right--) {
			double tmp = a[left];
			a[left] = a[right];
			a[right] = tmp;
		}
	}
	
	public static final void convolveGaussianSigma1(final double[] in, final double[] out, final CircularSequence seq) {
		// Weights for sigma = 1 and kernel 5x1, normalized so they add up to 1.
		final double w2 = 0.05448869,
		              w1 = 0.24420134,
		              w0 = 0.40261994;
		for (int i=0; i<2; ++i) {
			out[i] =   in[seq.setPosition(i -2)] * w2
					 + in[seq.setPosition(i -1)] * w1
					 + in[seq.setPosition(i   )] * w0
					 + in[seq.setPosition(i +1)] * w1
					 + in[seq.setPosition(i +2)] * w2;
		}
		final int cut = out.length -2;
		for (int i=2; i<cut; ++i) {
			out[i] =   in[i -2] * w2
					 + in[i -1] * w1
					 + in[i   ] * w0
					 + in[i +1] * w1
					 + in[i +2] * w2;
		}
		for (int i=cut; i<out.length; ++i) {
			out[i] =   in[seq.setPosition(i -2)] * w2
					 + in[seq.setPosition(i -1)] * w1
					 + in[seq.setPosition(i   )] * w0
					 + in[seq.setPosition(i +1)] * w1
					 + in[seq.setPosition(i +2)] * w2;
		
		}
	}

	static public final class CircularSequence
	{
		int i;
		final private int size;

		/** A sequence of integers from 0 to {@param size}
		 * that cycle back to zero when reaching the end;
		 * the starting point is the last point in the sequence,
		 * so that a call to {@link #next()} delivers the first value, 0.
		 * 
		 * @param size The length of the range to cycle over.
		 */
		public CircularSequence(final int size) {
			this.size = size;
			this.i = size -1;
		}
		final public int next() {
			++i;
			i = i % size;
			return i;
		}
		final public int previous() {
			--i;
			i = i % size;
			return i;
		}
		/** Will wrap around if k<0 or k>size. */
		final public int setPosition(final int k) {
			i = k;
			if (i < 0) i = size - ((-i) % size);
			else i = i % size;
			return i;
		}
		/** Will wrap around. */
		final public int move(final int inc) {
			return setPosition(i + inc);
		}
	}
	
	final static class DoublePolygon {
		final double[] xpoints;
		final double[] ypoints;
		final int npoints;
		public DoublePolygon(final double[] xpoints, final double[] ypoints, final int npoints) {
			this.xpoints = xpoints;
			this.ypoints = ypoints;
			this.npoints = npoints;
		}
		// Copied from ImageJ's FloatPolygon
		public double computeLength(boolean isLine) {
			double dx, dy;
			double length = 0.0;
			for (int i=0; i<(npoints-1); i++) {
				dx = xpoints[i+1]-xpoints[i];
				dy = ypoints[i+1]-ypoints[i];
				length += Math.sqrt(dx*dx+dy*dy);
			}
			if (!isLine) {
				dx = xpoints[0]-xpoints[npoints-1];
				dy = ypoints[0]-ypoints[npoints-1];
				length += Math.sqrt(dx*dx+dy*dy);
			}
			return length;
		}
	}
	
	/** Copied from ImageJ's ij.gui.PolygonRoi.getInterpolatedPolygon, by Wayne Rasband and collaborators.
	 * The reason I copied this method is that creating a new PolygonRoi just to get an interpolated polygon
	 * processes the float[] arrays of the coordinates, subtracting the minimum x,y. Not only it is an extra
	 * operation but it is also in place, altering data arrays. Additionally, double arrays were required. */
	final static public DoublePolygon createInterpolatedPolygon(
			final DoublePolygon p,
			final double interval,
			final boolean isLine) {
		double length = p.computeLength(isLine);
		int npoints2 = (int)((length*1.2)/interval);
		double[] xpoints2 = new double[npoints2];
		double[] ypoints2 = new double[npoints2];
		xpoints2[0] = p.xpoints[0];
		ypoints2[0] = p.ypoints[0];
		int n=1, n2;
		double inc = 0.01;
		double distance=0.0, distance2=0.0, dx=0.0, dy=0.0, xinc, yinc;
		double x, y, lastx, lasty, x1, y1, x2=p.xpoints[0], y2=p.ypoints[0];
		int npoints = p.npoints;
		if (!isLine) npoints++;
		for (int i=1; i<npoints; i++) {
			x1=x2; y1=y2;
			x=x1; y=y1;
			if (i<p.npoints) {
				x2=p.xpoints[i];
				y2=p.ypoints[i];
			} else {
				x2=p.xpoints[0];
				y2=p.ypoints[0];
			}
			dx = x2-x1;
			dy = y2-y1;
			distance = Math.sqrt(dx*dx+dy*dy);
			xinc = dx*inc/distance;
			yinc = dy*inc/distance;
			lastx=xpoints2[n-1]; lasty=ypoints2[n-1];
			//n2 = (int)(dx/xinc);
			n2 = (int)(distance/inc);
			if (npoints==2) n2++;
			do {
				dx = x-lastx;
				dy = y-lasty;
				distance2 = Math.sqrt(dx*dx+dy*dy);
				//IJ.log(i+"   "+IJ.d2s(xinc,5)+"   "+IJ.d2s(yinc,5)+"   "+IJ.d2s(distance,2)+"   "+IJ.d2s(distance2,2)+"   "+IJ.d2s(x,2)+"   "+IJ.d2s(y,2)+"   "+IJ.d2s(lastx,2)+"   "+IJ.d2s(lasty,2)+"   "+n+"   "+n2);
				if (distance2>=interval-inc/2.0 && n<xpoints2.length-1) {
					xpoints2[n] = (float)x;
					ypoints2[n] = (float)y;
					//IJ.log("--- "+IJ.d2s(x,2)+"   "+IJ.d2s(y,2)+"  "+n);
					n++;
					lastx=x; lasty=y;
				}
				x += xinc;
				y += yinc;
			} while (--n2>0);
		}
		return new DoublePolygon(xpoints2, ypoints2, n);
	}
}