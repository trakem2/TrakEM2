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

package ini.trakem2.render3d;

import ini.trakem2.utils.Utils;

	/** The sequence of edtions that describes the morphing of this Perimeter2D to some other Perimeter2D. */
class Editions { // private-to-the-package class
	
	private Perimeter2D p1;
	private Perimeter2D p2;
	/**  Index at which the matching starts for p2. For p1, it's always zero.*/
	private int start_p2 = 0;

	/** Sequence of editions*/
	private int[][] editions;
	/** Levenshtein's distance between p1 and p2. */
	private double distance;

	static private final int DELETION = 1;
	static private final int INSERTION = 2;
	static private final int MUTATION = 3;

	protected Editions(final Perimeter2D p1, final Perimeter2D p2, final double delta) {
		this.p1 = p1;
		this.p2 = p2;
		// TODO: I'm giving a 'true' for closed curve always.
		findOptimalEditSequence(delta, true); // p1 is untouched, so don't care; p2 is the one to reorder if necessary (actually, just set start_p2 index for it), so then the is_open_curve must apply to p2 only, and thus only two cases are possible.
	}

	/** Finds the edit matrix for any two given Perimeters
	 * 0 is the starting point for p1
	 * 'first' is the starting point for p2
	 */
	private double[][] findEditMatrix(final Perimeter2D p1, final Perimeter2D p2, final int first, final double delta, final double[][] matrix) {
		// iterators
		int i,j, ii, jj;
		// catch arrays
		final double[] v_x1 = p1.v_x;
		final double[] v_y1 = p1.v_y;
		final double[] v_x2 = p2.v_x;
		final double[] v_y2 = p2.v_y;
		final int n = p1.x.length;
		final int m = p2.y.length;

		// optimized version: less dereferencing (for an expanded version see CurveMorphing_just_C.c
		double fun1, fun2, fun3;
		double vs_x, vs_y;
		double[] mati, mat1;

		for (i=1; i< n + 1; i++) {
			mati = matrix[i];
			mat1 = matrix[i-1];
			for (jj=1; jj< m + 1; jj++) {
				// offset j to first:
				j = first + jj -1; //-1 so it starts at 'first'
				if (j >= m) {
					j = j - m;
				}

				ii = i;
				if (ii == n) ii--;
						// TODO this can be indenting the curves sometimes. FIX
				// cost deletion:
				fun1 =  mat1[jj] + delta; //matrix[i-1][jj] + delta; 
				// cost insertion:
				fun2 = mati[jj-1] + delta;//matrix[i][jj-1] + delta;
				// cost mutation:
				if (i == n || j == m) {
					fun3 = mat1[jj-1];//matrix[i-1][jj-1];
				} else {
					vs_x = v_x1[ii] - v_x2[j];
					vs_y = v_y1[ii] - v_y2[j];
					fun3 = mat1[jj-1] + Math.sqrt(vs_x*vs_x + vs_y*vs_y);//matrix[i-1][jj-1]; + Math.sqrt(vs_x*vs_x + vs_y*vs_y);
				}
				// put the lowest value:
				// 	since most are mutations, start with fun3:
				if (fun3 <= fun1 && fun3 <= fun2) {
					mati[jj] = fun3;//matrix[i][jj] = fun3;
				} else if (fun1 <= fun2 && fun1 <= fun3) {
					mati[jj] = fun1;//matrix[i][jj] = fun1;
				} else {
					mati[jj] = fun2;//matrix[i][jj] = fun2;
				}
			}
		}

		return matrix;
	}

	/** A tuple of mine, containing the starting index and the matrix for that index. Used only in the findMinimumEditDistance below, in the divide and conquer approach */
	private class MinDist {
		int min_j;
		double min_dist;
		double[][] matrix;
		double[][] matrix_e;
	}
	/** A recursive function to find the min_j for the minimum distance between p1 and p2.
	 *  Uses a divide and conquer approach: given the interval length, it will recurse using halfs of it.
	 *  Puts more than 65 minutes to 5 minutes !!!! Awesome. But it's not perfect:
	 *  	- ends may be computed twice
	 *  	NO LONGER TRUE - one of the planes was not aligned correctly in the thresholded, hard model.
	 */

	private MinDist findMinDist(final Perimeter2D p1, final Perimeter2D p2, final double delta, int first, int last, int interval_length, final MinDist result) {
		double[][] matrix = result.matrix;
		double[][] matrix_e = result.matrix_e;
		final double[][] matrix1 = matrix_e;
		final double[][] matrix2 = matrix;

		// the iterator over p2
		int j;

		// size of the interval to explore
		int k = 0;
		int length;
		if (last < first) {
			//length = p2->length - last + first + 1;
			length = p2.x.length - first + last;
		} else {
			length = last - first + 1;
		}

		// gather data
		final int n = p1.x.length;
		final int m = p2.x.length;
		int min_j = result.min_j;
		double min_dist = result.min_dist;

		while (k < length) {
			j = first + k;
			// correct circular array
			if (j >= m) {
				//j = k - p2->length; // ?????? would create negative values?
				j = j - m;
			}
			// don't do some twice: TODO this setup does not save the case when the computation was done not in the previous iteration but before.
			if (j == min_j) {
				matrix_e = result.matrix_e;
			} else {
				matrix_e = findEditMatrix(p1, p2, j, delta, matrix_e);
			}
			if (matrix_e[n][m] < min_dist) {
				// record values
				min_j = j;
				matrix = matrix_e;
				min_dist = matrix[n][m];
				// swap editable matrix
				if (matrix_e == matrix1) {
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
				first = m + first; // a '+' so it is substracted from p2->length (or m)
			}
			// correct last:
			if (last >= m) {
				last -= m;
			}
			// recurse, with half the interval length
			interval_length = (int) Math.ceil(interval_length / 2.0f);
			return findMinDist(p1, p2, delta, first, last, interval_length, result);
		}
	}

	/** Find the minimum edit distance between Perimeter p1 and p2.
	 * Returns the matrix as returned by findEditMatrix().
	 * Has one side effect: sets the start_p2 index.
	 */
	private double[][] findMinimumEditDistance(final Perimeter2D p1, final Perimeter2D p2, final double delta, final boolean is_closed_curve) {
		// iterators
		int j, i;

		int min_j = 0;
		int n = p1.x.length;
		int m = p2.x.length;
		// allocate the first matrix
		final double[][] matrix1 = new double[n+1][m+1];
		for (i=1; i < n +1; i++) { // skipping zero, which is zero already
			matrix1[i][0] = i * delta;
		}
		// make the first j row be j * delta
		for (j=1; j < m + 1; j++) { // skipping zero
			matrix1[0][j] = j * delta;
		}

		// return the matrix made matching point 0 of both curves, if the curve is open.
		if (!is_closed_curve) {
			return findEditMatrix(p1, p2, 0, delta, matrix1);
		}

		// else try every point in the second curve to see which one is the best possible match.

		// allocate the second matrix
		final double[][] matrix2 = new double[n+1][m+1];
		for (i=1; i < n +1; i++) { // skipping zero, which is zero already
			matrix2[i][0] = i * delta;
		}

		// make the first j row be j * delta
		for (j=0; j < m + 1; j++) {
			matrix2[0][j] = j * delta;
		}

		// the current, editable
		double[][] matrix_e = matrix1;

		// the one to keep
		double[][] matrix;


		// A 'divide and conquer' approach:
		// Find the value of one every 10% of points. Then find those intervals with the lowest starting and ending values, and then look for 50% intervals inside those, and so on, until locking into the lowest value. It will save about 80% or more of all computations.
		MinDist min_data = new MinDist();
		min_data.min_j = -1;
		min_data.min_dist = Double.MAX_VALUE;
		min_data.matrix = matrix2;
		min_data.matrix_e = matrix1;

		min_data = findMinDist(p1, p2, delta, 0, m-1, (int)Math.ceil(m * 0.1), min_data);

		min_j = min_data.min_j;
		matrix = min_data.matrix;
		matrix_e = min_data.matrix_e;

		// Reorder the second array, so that min_j is index zero (i.e. simply making both curves start at points that are closest to each other in terms of curve similarity).
		/*
		if (0 != min_j) {
			p2.reorder(min_j);
		}
		*/
		start_p2 = min_j;

		// return the matrix
		return matrix;
	}

	static private int[][] resizeAndFillEditionsCopy(final int[][] editions, final int ed_length, final int new_length) {
		final int[][] editions2 = new int[new_length][3];
		// fill with previous values
		for (int k=0; k<ed_length; k++) {
			editions2[k][0] = editions[k][0];
			editions2[k][1] = editions[k][1];
			editions2[k][2] = editions[k][2];
		}
		// return enlarged copy
		return editions2;
	}

	/** Find the optimal path in the matrix.
	 *  Returns a 2D array such as [n][3]:
	 *  	- the second dimension records:
	 *  		- the edition:
	 *  			1 == DELETION
	 *  			2 == INSERTION
	 *  			3 == MUTATION
	 *  		- the i
	 *  		- the j
	 * 
	 * Find the optimal edit sequence, and also reorder the second array to be matchable with the first starting at index zero of both. This method, applied to all consecutive pairs of curves, reorders all but the first (whose zero will be used as zero for all) of the non-interpolated perimeters (the originals) at findMinimumEditDistance.
	 * */
	private void findOptimalEditSequence(final double delta, final boolean is_closed_curve) {
		// fetch the optimal matrix:
		double[][] matrix = findMinimumEditDistance(this.p1, this.p2, delta, is_closed_curve);

		final int n = this.p1.x.length;
		final int m = this.p2.x.length;
		final int initial_length = (int)Math.sqrt((n * n) + (m * m));
		int i = 0;
		int[][] editions = new int[initial_length][3];
		int ed_length = initial_length;
		int next = 0;
		i = n;
		int j = m;
		int k;
		final double error = 0.00001; //for floating-point math.

		while (0 != i && 0 != j) { // the matrix is n+1 x m+1 in size
			// check editions array
			if (next == ed_length) {
				//enlarge editions array
				editions = resizeAndFillEditionsCopy(editions, ed_length, ed_length + 20);
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

		// add unnoticed insertions/deletions. It happens if 'i' and 'j' don't reach the zero value at the same time.
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
		int[][] editions2;
		if (next != ed_length) {
			// allocate a new editions array  ('next' is the length now, since it is the index of the element after the last element)
			editions2 = new int[next][3];
			for (i=0, j=next-1; i<next; i++, j--) {
				editions2[i][0] = editions[j][0];
				editions2[i][1] = editions[j][1];
				editions2[i][2] = editions[j][2];
			}
		} else {
			//simply reorder in place
			int[] temp;
			for (i=0; i<next; i++) {
				temp = editions[i];
				editions[i] = editions[next -1 -i];
				editions[next -1 -i] = temp;
			}
			editions2 = editions;
		}

		//  store
		this.editions = editions2;
		this.distance = matrix[n][m];
	}
}
