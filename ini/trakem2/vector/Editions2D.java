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

import java.util.ArrayList;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

public class Editions2D {

	static public final int DELETION = 1;
	static public final int INSERTION = 2;
	static public final int MUTATION = 3;

	/** In the form [length][3] */
	public int[][] editions;
	public double distance;
	private VectorString2D sv1;
	private VectorString2D sv2;
	private double delta;
	private boolean closed;

	public Editions2D(final VectorString2D sv1, final VectorString2D sv2, final double delta, final boolean closed) { // equivalent to findOptimalEditSequence in CurveMorphing_just_C.c
		this.sv1 = sv1;
		this.sv2 = sv2;
		this.delta = delta;
		this.closed = closed;
		sv1.resample(delta);
		sv2.resample(delta);
		// fetch the optimal matrix
		final double[][] matrix = findMinimumEditDistance(sv1, sv2, delta, closed);
		
		final int n = sv1.length;
		final int m = sv2.length;
		final int initial_length = (int)Math.sqrt((n*n) + (m*m));
		int i = 0;
		int ed_length = initial_length;
		editions = new int[ed_length][3];
		int next = 0;
		i = n;
		int j = m;
		int k;
		final double error = 0.0000001; // for floating-point math
		while (0 != i && 0 != j) { // the matrix is n+1,m+1 in size
			// check editions array
			if (next == ed_length) {
				// enlarge editions array
				this.editions = resizeAndFillEditionsCopy(editions, ed_length, ed_length + 20);
				ed_length += 20;
			}
			// find next i, j and the type of transform:
			if (error > Math.abs(matrix[i][j] - matrix[i-1][j] - delta)) {
				// a deletion:
				editions[next][0] = DELETION;
				editions[next][1] = i;
				editions[next][2] = j;
				i = i-1;
			} else if (error > Math.abs(matrix[i][j] - matrix[i][j-1] - delta)) {
				// an insertion:
				editions[next][0] = INSERTION;
				editions[next][1] = i;
				editions[next][2] = j;
				j = j-1;
			} else {
				// a mutation (a trace between two elements of the string of vectors)
				editions[next][0] = MUTATION;
				editions[next][1] = i;
				editions[next][2] = j;
				i = i-1;
				j = j-1;
			}
			//prepare next
			next++;
		}
		// add unnoticed insertions/deletions. Happens when 'i' and 'j' don't reach the zero value at the same time.
		if (0 != j) {
			for (k=j; k>-1; k--) {
				if (next == ed_length) {
					//enlarge editions array
					editions = resizeAndFillEditionsCopy(editions, ed_length, ed_length + 20);
					ed_length += 20;
				}
				editions[next][0] = INSERTION; // insertion
				editions[next][1] = 0; // the 'i'
				editions[next][2] = k; // the 'j'
				next++;
			}
		}
		if (0 != i) {
			for (k=i; k>-1; k--) {
				if (next == ed_length) {
					//enlarge editions array
					editions = resizeAndFillEditionsCopy(editions, ed_length, ed_length + 20);
					ed_length += 20;
				}
				editions[next][0] = DELETION; // deletion
				editions[next][1] = k; // the 'i'
				editions[next][2] = 0; // the 'j'
				next++;
			}
		}
		// reverse and resize editions array, and DO NOT slice out the last element.
		if (next != ed_length) {
			// allocate a new editions array  ('next' is the length now, since it is the index of the element after the last element)
			int[][] editions2 = new int[next][3];
			for (i=0, j=next-1; i<next; i++, j--) {
				editions2[i][0] = editions[j][0];
				editions2[i][1] = editions[j][1];
				editions2[i][2] = editions[j][2];
			}
			editions = editions2;
		} else {
			//simply reorder in place
			int[] temp;
			for (i=0; i<next; i++) {
				temp = editions[i];
				editions[i] = editions[next -1 -i];
				editions[next -1 -i] = temp;
			}
		}
		// set
		this.distance = matrix[n][m];
	}

	static private int[][] resizeAndFillEditionsCopy(final int[][] editions, final int ed_length, final int new_length) {
		// check
		if (new_length <= ed_length) return editions;
		// create a copy
		final int[][] editions2 = new int[new_length][3];
		// fill with previous values
		for (int k=0; k<ed_length; k++) {
			editions2[k][0] = editions[k][0];
			editions2[k][1] = editions[k][1];
			editions2[k][2] = editions[k][2];
		}
		return editions2;
	}

	private double[][] findMinimumEditDistance(final VectorString2D sv1, final VectorString2D sv2, final double delta, final boolean closed) {
		int j, i;
		int min_j = 0;
		final int n = sv1.length;
		final int m = sv2.length;
		// allocate the first matrix
		final double[][] matrix1 = new double[n+1][m+1];
		// make the first element be i*delta
		for (i=0; i < n +1; i++) {
			matrix1[i][0] = i * delta;
		}
		// fill first column
		for (j=0; j < m + 1; j++) {
			matrix1[0][j] = j * delta;
		}
		// return the matrix made matching point 0 of both curves, if the curve is open.
		if (!closed) {
			return findEditMatrix(sv1, sv2, 0, delta, matrix1);
		}

		// else try every point in the second curve to see which one is the best possible match.

		// allocate the second matrix
		final double[][] matrix2 = new double[n+1][m+1];
		/// fill top row values for the second matrix
		for (i=0; i < n +1; i++) {
			matrix2[i][0] = i * delta;
		}
		// make the first j row be j * delta
		for (j=0; j < m + 1; j++) {
			matrix2[0][j] = j * delta;
		}

		// the current, editable
		double[][] matrix_e = matrix1;


		// the one to keep
		double[][] matrix = null;

		// The algorithm to find the starting point, based on vector string distance:
		/*
		// find the minimum distance
		for (j=0; j<m; j++) {
			// get the distance starting at 0 in p1 and at j in p2:
			matrix_e = findEditMatrix(p1, p2, j, delta, matrix_e);

			// last element of the matrix is the string distance between p1 and p2
			if (matrix_e[n][m] < min_dist) {
				// record values
				min_dist = matrix_e[n][m];
				min_j = j;
				// record new one
				matrix = matrix_e;
				// swap editable matrix
				if (matrix1 == matrix_e) { // compare the addresses of the arrays.
					matrix_e = matrix2;
				} else {
					matrix_e = matrix1;
				}
			}
			// else nothing needs to be needed, the current editable matrix remains the same.
		}
		*/

		// A 'divide and conquer' approach: much faster, based on the fact that the distances always make a valey when plotted
		// Find the value of one every 10% of points. Then find those intervals with the lowest starting and ending values, and then look for 50% intervals inside those, and so on, until locking into the lowest value. It will save about 80% or more of all computations.
		MinDist min_data = new MinDist();
		min_data.min_j = -1;
		min_data.min_dist = Double.MAX_VALUE;
		min_data.matrix = matrix2;
		min_data.matrix_e = matrix1;

		min_data = findMinDist(sv1, sv2, delta, 0, m-1, (int)Math.ceil(m * 0.1), min_data);

		min_j = min_data.min_j;
		matrix = min_data.matrix;
		matrix_e = min_data.matrix_e;

		// free the current editable matrix. The other one is returned below and will be free elsewhere after usage.
		if (matrix != matrix_e) {
			matrix_e = null;
		} else {
			System.out.println("\nERROR: matrix is matrix_e unexpectedly");
		}


		// Reorder the second array, so that min_j is index zero (i.e. simply making both curves start at points that are closest to each other in terms of curve similarity).
		if (0 != min_j) {
			// copying
			double[] tmp = new double[m];
			double[] src;
			// x
			src = sv2.x;
			for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
			for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
			sv2.x = tmp;
			tmp = src;
			// y
			src = sv2.y;
			for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
			for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
			sv2.y = tmp;
			tmp = src;
			// v_x
			src = sv2.v_x;
			for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
			for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
			sv2.v_x = tmp;
			tmp = src;
			// v_y
			src = sv2.v_y;
			for (i=0, j=min_j; j<m; i++, j++) { tmp[i] = src[j]; }
			for (j=0; j<min_j; i++, j++) { tmp[i] = src[j]; }
			sv2.v_y = tmp;
			tmp = src;

			// equivalent would be:
			//System.arraycopy(src, min_j, tmp, 0, m - min_j);
			//System.arraycopy(src, 0, tmp, m - min_j, min_j);
			//sv2.x = tmp;
			//tmp = src;

			// release
			tmp = null;
		}

		// return the matrix
		return matrix;
	}

	/** Generate the matrix between sv1 and sv2. The lower right value of the matrix is the Levenshtein's distance between the two strings of vectors. @param delta is the desired point interdistance. @param first is the first index of sv2  to be matched with index zero of sv1. @param matrix is optional, for recyclying. */
	static private double[][] findEditMatrix(final VectorString2D sv1, final VectorString2D sv2, final int first, final double delta, double[][] matrix_) {

		// if already resampled, nothing will change
		sv1.resample(delta);
		sv2.resample(delta);

		// cache locally
		final double[] v_x1 = sv1.v_x;
		final double[] v_y1 = sv1.v_y;
		final double[] v_x2 = sv2.v_x;
		final double[] v_y2 = sv2.v_y;

		final int n = sv1.length;
		final int m = sv2.length;

		if (null == matrix_) matrix_ =  new double[n+1][m+1];
		final double[][] matrix = matrix_;

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
		double v_x, v_y;
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
					v_x = v_x1[i] - v_x2[j];
					v_y = v_y1[i] - v_y2[j];
					fun3 = mat1[j-1] + Math.sqrt(v_x*v_x + v_y*v_y); // the vector length is the hypothenusa
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

	/** Convenient tuple to store the statting index and the matrix for that index.*/
	private class MinDist {
		int min_j;
		double min_dist;
		double[][] matrix;
		double[][] matrix_e;
	}

	/** Returns the same instance of MinDist given as a parameter (so it has to be non-null). */
	static private MinDist findMinDist(final VectorString2D sv1, final VectorString2D sv2, final double delta, int first, int last, int interval_length, final MinDist result) {
		double[][] matrix = result.matrix;
		double[][] matrix_e = result.matrix_e;
		double[][] matrix1 = matrix_e;
		double[][] matrix2 = matrix;

		// the iterator over p2
		int j;

		// size of the interval to explore
		int k = 0;
		int length;
		if (last < first) {
			length = sv2.length - first + last;
		} else {
			length = last - first + 1;
		}

		// gather data
		final int n = sv1.length;
		final int m = sv2.length;
		int min_j = result.min_j;
		double min_dist = result.min_dist;

		while (k < length) {
			j = first + k;
			// correct circular array
			if (j >= sv2.length) {
				j = j - sv2.length;
			}
			// don't do some twice: TODO this setup does not save the case when the computation was done not in the previous iteration but before.
			if (j == result.min_j) {
				matrix_e = result.matrix_e;
			} else {
				matrix_e = findEditMatrix(sv1, sv2, j, delta, matrix_e);
			}
			if (matrix_e[n][m] < min_dist) {
				// record values
				min_j = j;
				matrix = matrix_e;
				min_dist = matrix[n][m];
				// swap editable matrix
				if (matrix_e == matrix1) { // compare the addresses of the arrays.
					matrix_e = matrix2;
				} else {
					matrix_e = matrix1;
				}
			}
			// advance iterator
			if (length -1 != k && k + interval_length >= length) {
				// do the last one (and then finish)
				k = length -1;
			} else {
				k += interval_length;
			}
		}

		// pack result:
		result.min_j = min_j;
		result.min_dist = min_dist;
		result.matrix = matrix;
		result.matrix_e = matrix_e;

		if (1 == interval_length) {
			// done!
			return result;
		} else {
			first = min_j - (interval_length -1);
			last = min_j + (interval_length -1);
			// correct first:
			if (first < 0) {
				first = sv2.length + first; // a '+' so it is substracted from sv2.length
			}
			// correct last:
			if (last >= sv2.length) {
				last -= sv2.length;
			}
			// recurse, with half the interval length
			interval_length = (int)Math.ceil(interval_length / 2.0f);
			return findMinDist(sv1, sv2, delta, first, last, interval_length, result);
		}
	}

	/** Returns a weighted VectorString2D. @param alpha is the weight, between 0 and 1. */
	public double[][] getMorphedPerimeter(final double alpha) {
		final int n = sv1.length;
		final int m = sv2.length;
		final double[] v_x1 = sv1.v_x;
		final double[] v_y1 = sv1.v_y;
		final double[] v_x2 = sv2.v_x;
		final double[] v_y2 = sv2.v_y;
		final int n_editions = editions[0].length;
		// the points to create. There is one point for each edition, plus the starting point.
		final double[] x = new double[n_editions +1];
		final double[] y = new double[n_editions +1]; 
		//starting point: a weighted average between both starting points
		x[0] = (sv1.x[0] * (1-alpha) + sv2.x[0] * alpha);
		y[0] = (sv1.y[0] * (1-alpha) + sv2.y[0] * alpha);

		// the iterators
		int next = 0;
		int i;
		int j;

		// generate weighted vectors to make weighted points

		//// PATCH to avoid displacement of the interpolated curves when edition[1][0] is a MUTATION. //////
		int start = 0;
		int end = n_editions; // was: -1
		if (INSERTION == editions[0][0] || DELETION == editions[0][0]) {
			start = 1;
			end = n_editions; // that is how it works. I need to internalize this to understand it myself. It may be that an extra one is being added when creating the editions, by mistake.
		}
		
		// the weighted vectors to generate:
		double vs_x = 0;
		double vs_y = 0;

		for (int e=start; e<end; e++) {
			i = editions[e][1];
			j = editions[e][2];
			// check for deletions and insertions at the lower-right edges of the matrix:
			if (i == n) {
				i = 0; // zero, so the starting vector is applied.
			}
			if (j == m) {
				j = 0;
			}
			// do:
			switch (editions[e][0]) {
				case INSERTION:
					vs_x = v_x2[j] * alpha;
					vs_y = v_y2[j] * alpha;
					break;
				case DELETION:
					vs_x = v_x1[i] * (1.0 - alpha);
					vs_y = v_y1[i] * (1.0 - alpha);
					break;
				case MUTATION:
					vs_x = v_x1[i] * (1.0 - alpha) + v_x2[j] * alpha;
					vs_y = v_y1[i] * (1.0 - alpha) + v_y2[j] * alpha;
					break;
				default:
					Utils.log2("\ngetMorphedPerimeter: Nothing added!");
					break;
			}
			// store the point
			x[next+1] = x[next] + vs_x;
			y[next+1] = y[next] + vs_y;

			// advance
			next++;
		}
		// return packed:
		double[][] d = new double[2][];
		d[0] = x;
		d[1] = y;
		return d;
	}

	/** From two VectorString2D, return an array of x,y points ( in the form [2][n] ) defining all necessary intermediate, morphed perimeters that describe a skin between them (not including the two VectorString2D) */
	public double[][][] getMorphedPerimeters(int n_morphed_perimeters) {
		sv1.resample(this.delta);
		sv2.resample(this.delta);
		// check automatic mode (-1 is the flag; if less, then just survive it):
		if (n_morphed_perimeters < 0) n_morphed_perimeters = (int)(Math.sqrt(Math.sqrt(this.distance)));

		final double alpha = 1.0 / (n_morphed_perimeters +1); // to have 10 subdivisions we need 11 boxes
		double[][][] p_list = new double[n_morphed_perimeters][][];
		for (int a=0; a<n_morphed_perimeters; a++) {
			double aa = alpha * (a+1); // aa 0 would be sv1, aa 1 would be sv2.
			p_list[a] = getMorphedPerimeter(aa);
		}

		return p_list;
	}

	/** From an array of VectorString2D, return a new array of VectorString2D defining all necessary intermediate, morphed perimeters that describe a skin between each consecutive pair; includes the originals inserted at the proper locations. The 'z' is interpolated. Returns the array of VectorString2D and the array of Editions2D (which is one item smaller, since it represents matches). */
	static public ArrayList<Editions2D.Match> getMorphedPerimeters(final VectorString2D[] sv, int n_morphed_perimeters, double delta_, boolean closed) {
		//check preconditions:
		if (n_morphed_perimeters < -1 || sv.length <=0) {
			Utils.log2("\nERROR: args are not acceptable at getAllPerimeters:\n\t n_morphed_perimeters " + n_morphed_perimeters + ", n_perimeters " + sv.length);
			return null;
		}

		final ArrayList<Editions2D.Match> al_matches = new ArrayList();

		try {
			// get all morphed curves and return them.
			int i, j; //, n_morphed_perimeters;

			double delta = 0.0;
			// calculate delta, or taken the user-given one if acceptable (TODO acceptable is not only delta > 0, but also smaller than half the curve length or so.
			if (delta_ > 0) {
				delta = delta_;
			} else {
				for (i=0; i<sv.length; i++) {
					delta += sv[i].getAverageDelta();
				}
				delta = delta / sv.length;
			}

			Utils.log2("\nUsing delta=" + delta);

			// resample perimeters so that point interdistance becomes delta
			for (i=0; i<sv.length; i++) {
				sv[i].resample(delta);
			}

			// fetch morphed ones and fill all_perimeters array:
			for (i=1; i<sv.length; i++) {
				Editions2D ed = new Editions2D(sv[i-1], sv[i], delta, closed);
				double[][][] d = ed.getMorphedPerimeters(n_morphed_perimeters);
				// else, add them all
				double z_start = sv[i-1].z;
				double z_inc = (sv[i].z - z_start) / ( 0 == d.length ? 1 : d.length); // if zero, none are added anyway; '1' is a dummy number
				VectorString2D[] p = new VectorString2D[d.length];
				for (int k=0; k<d.length; k++) {
					p[k] = new VectorString2D(d[k][0], d[k][1], delta, z_start + z_inc*i);
				}
				al_matches.add(new Match(sv[i-1], sv[i], ed, p));
			}

			return al_matches;

		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	/** Tuple to avoid ugly castings. Java is disgusting. */
	static public class Match {
		public VectorString2D sv1;
		public VectorString2D sv2;
		public Editions2D editions;
		/** The interpolated curves in between sv1 nd sv2.*/
		public VectorString2D[] p;
		public Match(VectorString2D sv1, VectorString2D sv2, Editions2D editions, VectorString2D[] p) {
			this.sv1 = sv1;
			this.sv2 = sv2;
			this.editions = editions;
			this.p = p;
		}
	}
}
