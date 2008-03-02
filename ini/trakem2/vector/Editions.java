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

import java.util.*;
import javax.vecmath.Point3f;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

/** To extract and represent the sequence of editions that convert any N-dimensional vector string to any other of the same number of dimensions. */
public class Editions {

	static public final int DELETION = 1;
	static public final int INSERTION = 2;
	static public final int MUTATION = 3;

	protected VectorString vs1;
	protected VectorString vs2;
	protected double delta;
	protected boolean closed;

	/** In the form [length][3] */
	protected int[][] editions;
	/** Levenshtein's distance between vs1 and vs2.*/
	public double distance;

	public Editions(final VectorString vs1, final VectorString vs2, final double delta, final boolean closed) {
		this.vs1 = vs1;
		this.vs2 = vs2;
		this.delta = delta;
		this.closed = closed;
		init();
	}

	public double getDistance() { return distance; }
	public int length() { return editions.length; }
	public int[][] getEditions() { return editions; }
	public VectorString getVS1() { return vs1; }
	public VectorString getVS2() { return vs2; }

	/** A mutation is considered an equal or near equal, and thus does not count. Only deletions and insertions count towards scoring the similarity.
	 * 
	 * @param skip_ends enables ignoring sequences in the beginning and ending if they are insertions or deletions.
	 * @param max_mut indicates the maximum length of a contiguous sequence of mutations to be ignored when skipping insertions and deletions at beginning and end.
	 * @param min_chunk indicates the minimal proportion of the string that should remain between the found start and end, for vs1. The function will return the regular similarity if the chunk is too small.
	 *
	 */
	public double getSimilarity(boolean skip_ends, final int max_mut, final float min_chunk) {

		int[] g = getStartEndSkip(skip_ends, max_mut, min_chunk);
		int i_start = g[0];
		int i_end = g[1];
		skip_ends = 1 == g[2];

		int non_mut = 0;

		if (skip_ends) {
			// count non mutations
			for (int i=i_start; i<i_end; i++) {
				if (MUTATION != editions[i][0]) non_mut++;
			}


			// compute proper segment lengths, inlined
			double sim = 1.0 - ( (double)non_mut / Math.max( editions[i_end][1] - editions[i_start][1] + 1, editions[i_end][2] - editions[i_start][2] + 1) );

			if (sim > 0.7) Utils.log2("similarity: non_mut, len1, len2, i_start, i_end : " + non_mut + ", " + (editions[i_end][1] - editions[i_start][1] + 1) + ", " + (editions[i_end][2] - editions[i_start][2] + 1) + ", " + i_start + "," + i_end + "   " + Utils.cutNumber(sim * 100, 2) + " %");

			return sim;

		} else {
			for (int i=0; i<editions.length; i++) {
				if (MUTATION != editions[i][0]) non_mut++;
			}
			//Utils.log2("non_mut: " + non_mut + "  total: " + editions.length);
			return 1.0 - ( (double)non_mut / Math.max(vs1.length(), vs2.length()) );
		}
	}

	public double getSimilarity() {
		return getSimilarity(false, 0, 1);
	}

	public double getSimilarity2() {
		return getSimilarity2(false, 0, 1);
	}

	/** Returns the number of mutations / maximum length of both strings: 1.0 means all are mutations and the sequences jave the same lengths.*/
	public double getSimilarity2(boolean skip_ends, final int max_mut, final float min_chunk) {

		int[] g = getStartEndSkip(skip_ends, max_mut, min_chunk);
		int i_start = g[0];
		int i_end = g[1];
		skip_ends = 1 == g[2];

		int mut = 0;

		if (skip_ends) {
			// count non mutations
			for (int i=i_start; i<i_end; i++) {
				if (MUTATION == editions[i][0]) mut++;
			}

			// compute proper segment lengths, inlined
			double sim = (double)mut / Math.max( editions[i_end][1] - editions[i_start][1] + 1, editions[i_end][2] - editions[i_start][2] + 1);

			if (sim > 0.7) Utils.log2("similarity: mut, len1, len2, i_start, i_end : " + mut + ", " + (editions[i_end][1] - editions[i_start][1] + 1) + ", " + (editions[i_end][2] - editions[i_start][2] + 1) + ", " + i_start + "," + i_end + "   " + Utils.cutNumber(sim * 100, 2) + " %");

			return sim;

		} else {
			for (int i=0; i<editions.length; i++) {
				if (MUTATION == editions[i][0]) mut++;
			}
			return (double)mut / Math.max(vs1.length(), vs2.length());
		}
	}


	private final int[] getStartEndSkip(boolean skip_ends, final int max_mut, final float min_chunk) {
		int i_start = 0;
		int i_end = editions.length -1;
		if (skip_ends) {
			// 1 - find start:
			int len_mut_seq = 0;
			// break when the found sequence of continuous mutations is larger than max_mut
			for (int i=0; i<editions.length; i++) {
				if (MUTATION == editions[i][0]) {
					len_mut_seq++;
				} else {
					// reset
					len_mut_seq = 0;
				}
				if (len_mut_seq > max_mut) {
					i_start = i - len_mut_seq +1;
					break;
				}
			}
			// find end
			len_mut_seq = 0;
			for (int i=i_end; i>-1; i--) {
				if (MUTATION == editions[i][0]) {
					len_mut_seq++;
				} else {
					// reset
					len_mut_seq = 0;
				}
				if (len_mut_seq > max_mut) {
					i_end = i;
					break;
				}
			}
			// determine if remaining chunk is larger than required min_chunk
			skip_ends = ((float)(editions[i_end][1] - editions[i_start][1] + 1) / vs1.length()) >= min_chunk;
		}
		return new int[]{i_start, i_end, skip_ends ? 1 : 0};
	}

	/** Returns the average distance between all points involved in a mutation. */
	public double getPhysicalDistance(boolean skip_ends, final int max_mut, final float min_chunk) {
		return getPhysicalDistance(getStartEndSkip(skip_ends, max_mut, min_chunk));
	}

	private double getPhysicalDistance(int[] g) {
		int i_start = g[0];
		int i_end = g[1];
		boolean skip_ends = 1 == g[2];

		double dist = 0;
		int len = 0;
		int i = 0;
		final int len1 = vs1.length();
		final int len2 = vs2.length();
		try {
			for (i=i_start; i<=i_end; i++) {
				if (MUTATION != editions[i][0]) continue;
				int k1 = editions[i][1];
				int k2 = editions[i][2];
				if (len1 == k1 || len2 == k2) continue; // LAST point will fail in some occasions, needs fixing
				dist += vs1.distance(k1, vs2, k2);
				len++;
			}
			return dist / len; // can len be zero?
		} catch (Exception e) {
			IJError.print(e);
			Utils.log2("ERROR in getPhysicalDistance: i,len  j,len : " + editions[i][1] + ", " + vs1.length() + "    " + editions[i][2] + ", " + vs2.length());
			return 100000000;
		}
	}

	final private void init() {
		// equalize point interdistance in both strings of vectors and create the actual vectors
		vs1.resample(delta);
		vs2.resample(delta);
		// fetch the optimal matrix
		final double[][] matrix = findMinimumEditDistance();

		final int n = vs1.length();
		final int m = vs2.length();

		this.distance = matrix[n][m];

		// let's see the matrix
		/*
		final StringBuffer sb = new StringBuffer();
		for (int f=0; f<n+1; f++) {
			for (int g=0; g<m+1; g++) {
				sb.append(matrix[f][g]).append('\t');
			}
			sb.append('\n');
		}
		Utils.saveToFile(new java.io.File("/home/albert/Desktop/matrix.txt"), sb.toString());
		*/


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
	}

	static private final int[][] resizeAndFillEditionsCopy(final int[][] editions, final int ed_length, final int new_length) {
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

	/** Convenient tuple to store the statting index and the matrix for that index.*/
	private class MinDist {
		int min_j;
		double min_dist;
		double[][] matrix;
		double[][] matrix_e;
	}

	private double[][] findMinimumEditDistance() {
		int j, i;
		int min_j = 0;
		final int n = vs1.length();
		final int m = vs2.length();
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
			return findEditMatrix(0, matrix1);
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
			matrix_e = findEditMatrix(j, matrix_e);

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

		min_data = findMinDist(0, m-1, (int)Math.ceil(m * 0.1), min_data);

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
			vs2.reorder(min_j);
		}

		// return the matrix
		return matrix;
	}

	/** Returns the same instance of MinDist given as a parameter (so it has to be non-null). */
	private MinDist findMinDist(int first, int last, int interval_length, final MinDist result) {
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
			length = vs2.length() - first + last;
		} else {
			length = last - first + 1;
		}

		// gather data
		final int n = vs1.length();
		final int m = vs2.length();
		int min_j = result.min_j;
		double min_dist = result.min_dist;

		while (k < length) {
			j = first + k;
			// correct circular array
			if (j >= m) {
				j = j - m;
			}
			// don't do some twice: TODO this setup does not save the case when the computation was done not in the previous iteration but before.
			if (j == result.min_j) {
				matrix_e = result.matrix_e;
			} else {
				matrix_e = findEditMatrix(j, matrix_e);
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
				first = m + first; // a '+' so it is substracted from vs2.length
			}
			// correct last:
			if (last >= m) {
				last -= m;
			}
			// recurse, with half the interval length
			interval_length = (int)Math.ceil(interval_length / 2.0f);
			return findMinDist(first, last, interval_length, result);
		}
	}

	/** Generate the matrix between vs1 and vs2. The lower right value of the matrix is the Levenshtein's distance between the two strings of vectors. @param delta is the desired point interdistance. @param first is the first index of vs2  to be matched with index zero of vs1. @param matrix is optional, for recyclying. */
	private double[][] findEditMatrix(final int first, double[][] matrix_) {

		final int n = vs1.length();
		final int m = vs2.length();

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
		double dx, dy;
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
					//dx = v_x1[i] - v_x2[j];
					//dy = v_y1[i] - v_y2[j];
					fun3 = mat1[j-1] + vs1.getDiffVectorLength(i, j, vs2); //  Math.sqrt(dx*dx + dy*dy); // the vector length is the hypothenusa
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

	/** Get the sequence of editions and matches in three lines, like:
	 *  vs1: 1 2 3 4 5 6     7 8 9
	 *       M M D M M M I I M M M 
	 *  vs2: 1 2   3 4   5 6 7 8 9
	 *
	 *  With the given separator (defaults to tab if null)
	 */
	public String prettyPrint(String separator) {
		if (null == editions) return null;
		if (null == separator) separator = "\t";
		final StringBuffer s1 = new StringBuffer(editions.length*2 + 5);
		final StringBuffer se = new StringBuffer(editions.length*2 + 5);
		final StringBuffer s2 = new StringBuffer(editions.length*2 + 5);
		s1.append("vs1:");
		se.append("    ");
		s2.append("vs2:");
		for (int i=0; i<editions.length; i++) {
			switch (editions[i][0]) {
				case MUTATION:
					s1.append(separator).append(editions[i][1] + 1);
					se.append(separator).append('M');
					s2.append(separator).append(editions[i][2] + 1);
					break;
				case DELETION:
					s1.append(separator).append(editions[i][1] + 1);
					se.append(separator).append('D');
					s2.append(separator).append(' ');
					break;
				case INSERTION:
					s1.append(separator).append(' ');
					se.append(separator).append('I');
					s2.append(separator).append(editions[i][2] + 1);
					break;
			}
		}
		s1.append('\n');
		se.append('\n');
		s2.append('\n');
		return s1.append(se).append(s2).toString();
	}

	private class Chunk {
		int i_start, i_end;
		Chunk(int i_start, int i_end) {
			this.i_start = i_start;
			this.i_end = i_end;
		}
		int length() { return i_end - i_start + 1; }
	}

	/*
	private class ChunkComparator implements Comparator {
		public int compare(Object o1, Object o2) {
			Chunck c1 = (Chunck)o1;
			Chunck c2 = (Chunck)o2;
			double val = c1.length() - c2.length();
			if (val < 0) return -1; // c1 is shorter
			if (val > 0) return 1; // c1 is longer
			return 0; // equally long
		}
	}
	*/

	/** Find the longest chunk of mutations (which can include chunks of up to max_non_mut of non-mutations),
	 *  then take the center point and split both vector strings there, perform matching towards the ends,
	 *  and assemble a new Editions object.
	 */
	public Editions recreateFromCenter(final int max_non_mut) throws Exception {
		// find the longest chunk of contiguous mutations
		// with no interruptions (insertions or deletions) longer than max_non_mut
		final ArrayList<Chunk> chunks = new ArrayList<Chunk>();

		// search for chunks of consecutive mutations, with any number of gaps of maximum length max_non_mut
		Chunk cur = new Chunk(0, 0);
		boolean reached_mut = false;
		int non_mut = 0;

		for (int i=0; i<editions.length; i++) {
			if (MUTATION == editions[i][0]) {
				cur.i_end = i;
				if (!reached_mut) reached_mut = true;
			} else {
				if (!reached_mut) cur.i_start = i; // move forward until finding the first mutation
				else non_mut++; // if it has reached some mut, count non-muts
				if (non_mut > max_non_mut || editions.length -1 == i) {
					// break current chain, try to add it if long enough
					cur.i_end = i;
					if (0 != chunks.size()) {
						// else, check if its equally long or longer than any existent one
						final Chunk[] c = new Chunk[chunks.size()];
						chunks.toArray(c);
						boolean add = false;
						final int len = cur.length();
						for (int k=c.length-1; k>-1; k--) {
							int clen = c[k].length();
							if (len > clen) {
								chunks.remove(c[k]); // remove all found that are shorter
							}
							if (!add && len >= clen) {
								add = true;
							}
						}
						if (add) chunks.add(cur);
					} else {
						// if none yet, just add it
						chunks.add(cur);
					}
					// create new current
					cur = new Chunk(i, i);
					reached_mut =false;
					non_mut = 0;
				}
			}
		}

		if (0 == chunks.size()) {
			Utils.log2("No chunks found.");
			return null;
		}

		// select a chunk
		Chunk chunk = null;
		if (1 == chunks.size()) chunk = chunks.get(0);
		else {
			// All added chunks have the same length (otherwise would have been deleted)
			// Find the one with the smallest physical distance
			double[] dist = new double[chunks.size()];
			int next = 0;
			Hashtable<Chunk,Double> ht = new Hashtable<Chunk,Double>();
			for (Chunk c : chunks) {
				dist[next] = getPhysicalDistance(new int[]{c.i_start, c.i_end, max_non_mut});
				ht.put(c, dist[next]);
				next++;
			}
			Arrays.sort(dist);
			for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				if ( ((Double)entry.getValue()).doubleValue() == dist[0]) {
					chunk = (Chunk)entry.getKey();
				}
			}
		}

		// from the midpoint, create two vector strings and reverse them, and compute editions for them
		// (so: get new edition sequence from chunk's midpoint to zero)

		final int midpoint = (chunk.i_start + chunk.i_end) / 2;

		VectorString3D firsthalf1 = (VectorString3D)vs1.subVectorString(editions[midpoint][1], 0); // already reversed, by giving indices in reverse order
		VectorString3D firsthalf2 = (VectorString3D)vs2.subVectorString(editions[midpoint][2], 0); // already reversed, by giving indices in reverse order

		final Editions ed = new Editions(firsthalf1, firsthalf2, this.delta, this.closed);
		// compose new editions array from the new one and the second half of this
		int[][] mushup = new int[ed.editions.length + this.editions.length - midpoint][3]; // not +1 after midpoint to not include the midpoint, which was included in the new Editions.
		for (int i=0; i<=midpoint/* same as i<ed.editions.length*/; i++) {
			mushup[midpoint -i] = ed.editions[i];
		}
		for (int i=midpoint+1; i<this.editions.length; i++) {
			mushup[i] = this.editions[i];
		}
		// Levenshtein's distance should be recomputed ... but I don't know how, considering the above mush up
		ed.vs1 = this.vs1;
		ed.vs2 = this.vs2;
		ed.distance = this.distance;
		ed.editions = mushup;

		return ed;
	}

}
