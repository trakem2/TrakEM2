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
}
