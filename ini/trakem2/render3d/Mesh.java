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

import ini.trakem2.utils.Utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.awt.Polygon;

/** A class which contains two lists:
 * - a list of points
 * - a list of faces composed of point indices (faces can be triangles or quads, the difference is that triangular faces have their fourth coordinate as -1, that is, no point index).
 */ // protected class
class Mesh {

	private double[][] points;
	private double[] z;
	private int[][] faces;
	private int next_point = 0;
	private int next_face = 0;
	/** The list of all already added perimeters. */
	private Perimeter2D[] perimeters;
	/** The indices in the points array where the corresponding perimeter starts. */
	private int[] perimeter_starts;
	private int next_perimeter = 0;

	Mesh(int n_points, int n_faces) {
		points = new double[3][n_points];
		z = new double[n_points];
		faces = new int[4][n_faces];
		perimeters = new Perimeter2D[50];
		perimeter_starts = new int[50];
	}

	Mesh() {
		this(1000, 1000);
	}

	private void enlargeArrays() {
		int len = points[0].length;
		double[][] p2 = new double[3][len + 1000];
		double[] z2 = new double[len + 1000];
		System.arraycopy(points[0], 0, p2[0], 0, len); // realloc please !!
		System.arraycopy(points[1], 0, p2[1], 0, len);
		System.arraycopy(points[2], 0, p2[2], 0, len);
		System.arraycopy(z, 0, z2, 0, len);
		points = p2;
		z = z2;
		//
		len = faces[0].length;
		int[][] f2 = new int[4][len + 1000];
		System.arraycopy(faces[0], 0, f2[0], 0, len);
		System.arraycopy(faces[1], 0, f2[1], 0, len);
		System.arraycopy(faces[2], 0, f2[2], 0, len);
		faces = f2;
	}

	private void enlargePerimeterArrays() {
		int len = perimeters.length;
		Perimeter2D[] pe2 = new Perimeter2D[len + 50];
		System.arraycopy(perimeters, 0, pe2, 0, len);
		perimeters = pe2;
		//
		int[] ps = new int[len + 50];
		System.arraycopy(perimeter_starts, 0, ps, 0, len);
		perimeter_starts = ps;
	}

	/** Will only add it if not read already. */
	private void addPoints(final Perimeter2D pe) {
		// check if already there
		for (int i=0; i<next_perimeter; i++) {
			if (perimeters[i].equals(pe)) return;
		}
		// else add its points
		if (perimeters.length == next_perimeter) enlargePerimeterArrays();
		perimeters[next_perimeter] = pe;
		perimeter_starts[next_perimeter] = next_point;
		next_perimeter++;
		final double p_z = pe.z;
		for (int i=0; i<pe.x.length; i++) {
			if (points[0].length == next_point) enlargeArrays();
			points[0][next_point] = pe.x[i];
			points[1][next_point] = pe.y[i];
			z[next_point] = p_z;
			next_point++;
		}
	}

	/** Add the perimeter to the mesh, making faces with those in the ArrayList. */
	void add(final Perimeter2D perimeter, final ArrayList al) {
		if (0 == al.size()) return;
		// This function has to keep track of added profiles, which can later appear inside the ArrayList.
		// Somehow, the list of points belonging to the added profile has to be kept related to the profile instance itself. So, Hashtable (of sorts, my own, because it's horrible to keep integers in a java.util.Hashtable)
		addPoints(perimeter);
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			addPoints((Perimeter2D)it.next());
		}
		// now: mesh making
		if (1 == al.size()) {
			// trivial case: one profile morphed into the other
			addSkin(perimeter, (Perimeter2D)al.get(0));
		} else {
			// grouped meshing
			addBranchedSkin(perimeter, al);
		}
	}

	/** Generate faces between both perimeters and add them, by interpolating perimeters in between if necessary, according to the CurveMorphing algorithm. The perimeters' points have been previously added to the points array. */
	private void addSkin(final Perimeter2D p1, final Perimeter2D p2) {
		// TODO I'm waiting to see the methods that the multibranching version will generate, for reuse
	}

	private void addBranchedSkin(final Perimeter2D p1, final ArrayList al) {
		// there we go!
		// So, must do:
		// 0 - check if any of the al perimeters intersect each other, if so, merge the affected ones.
		// 1 - get a sequence of editions for each pair p1 vs. al.get(i)
		// 2 - morph from the al_other towards the p1
		//       - when any two morphed perimeters intersect, join them into one.
		//         Recompute editions between p1 and the joined perimeter, and continue
		//
		// 0 - check if any of the al perimeters intersect each other, if so, merge the affected ones.
		// All perimeters are CCW (after subsampling)
	}

	/** Returns null if p1 and p2 do not intersect each other in the 3D space.*/
	private Perimeter2D merge(final Perimeter2D p1, final Perimeter2D p2) {
		// to check for intersection, iterate all segments in p1 over all segments in p2, checking for intersection.
		// Test 1: need equal Z
		if (Math.abs(p1.z - p2.z) > 0.00000001) return null;
		// A cheap pre-screen is for bounding box position of the points
		final double[] x1 = p1.x;
		final double[] y1 = p2.y;
		final int len1 = x1.length;
		final double[] x2 = p2.x;
		final double[] y2 = p2.y;
		final int len2 = x2.length;
		int i_start_merge = -1;
		int j_start_merge = -1;
		int i_end_merge = -1;
		int j_end_merge = -1;
		// start with an i for a point that is for sure outside p2
		final int i_start = findOutsidePoint(p1, p2);
		if (-1 == i_start) {
			// no such outside point! p1 is included inside p2
			return p2;
		}
		// p1 as circular array
		for (int ii=i_start, i=1; ii < i_start + len1; ii++) {
			if (ii >= len1) i = ii - len1;
			else i = ii;
			for (int j=1; j<len2; j++) {
				// line x1[i],y1[i] to x1[i-1],y1[i-1]
				final double a1 = (y1[i-1] - y1[i]) / (x1[i-1] - x1[i]);
				final double b1 = y1[i] - a1 * x1[i];
				// line x2[i],y2[i] to x2[i-2],y2[i-2005]
				final double a2 = (y2[i-1] - y2[i]) / (x2[i-1] - x2[i]);
				final double b2 = y2[i] - a2 * x2[i];
				// intersection point ix,iy
				// y = a1*x + b1
				// y = a2*x + b2
				// a1*x + b1 = a2*x + b2 ; x * (a1 - a2) = b2 - b1 ;
				if ( 0 == (a1 - a2)) continue; // lines are totally parallel
				final double ix = (b2 - b1) / (a1 - a2);
				final double iy = a1 * ix + b1;
				// check if intersection point lays within the segment i, i-1
				final double ix_dist = Math.abs(x1[i] - ix) + Math.abs(x1[i-1] - ix);
				final double iy_dist = Math.abs(y1[i] - iy) + Math.abs(y1[i-1] - iy);
				if (ix_dist <= Math.abs(x1[i] - x1[i-1])
				 && iy_dist <= Math.abs(y1[i] - y1[i-1])) {
					// intersection! // TODO needs some logic to choose the right i or i-1, and j or j-1
					i_start_merge = i;
					j_start_merge = j; // TODO it is very important to start looping on an outside point!
				}
			}
		}
		// TODO
		return null; // TODO
	}

	/** Starting at p1 point zero, find a point that is not contained in p2. */ // I am sure this function will be very costly and can be smart-ass optimized
	private int findOutsidePoint(final Perimeter2D p1, final Perimeter2D p2) {
		final int len = p2.x.length;
		final int[] x = new int[len];
		final int[] y = new int[len];
		for (int i=0; i<len; i++) {
			x[i] = (int)p2.x[i];
			y[i] = (int)p2.y[i];
		}
		Polygon pol2 = new Polygon(x, y, len);
		for (int i=0; i<p1.x.length; i++) {
			if (!pol2.contains(p1.x[i], p1.y[i])) return i;
		}
		return -1;
	}
}
