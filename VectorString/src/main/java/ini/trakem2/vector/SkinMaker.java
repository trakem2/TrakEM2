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

import java.util.ArrayList;
import java.util.List;

import javax.vecmath.Point3f;

public class SkinMaker {

	// service class (i.e. convenient namespace)
	private SkinMaker() {}

	/** Returns a weighted VectorString2D. @param alpha is the weight, between 0 and 1. */
	static public double[][] getMorphedPerimeter(final VectorString2D vs1, final VectorString2D vs2, final double alpha, final Editions ed) {
		final int n = vs1.length();
		final int m = vs2.length();
		final double[] v_x1 = vs1.getVectors(0);
		final double[] v_y1 = vs1.getVectors(1);
		final double[] v_x2 = vs2.getVectors(0);
		final double[] v_y2 = vs2.getVectors(1);
		final int[][] editions = ed.getEditions();
		final int n_editions = ed.length();
		// the points to create. There is one point for each edition, plus the starting point.
		double[] x = new double[n_editions]; // +1];
		double[] y = new double[n_editions]; // +1]; 
		//starting point: a weighted average between both starting points
		x[0] = (vs1.getPoint(0, 0) * (1-alpha) + vs2.getPoint(0, 0) * alpha);
		y[0] = (vs1.getPoint(1, 0) * (1-alpha) + vs2.getPoint(1, 0) * alpha);

		// the iterators
		int next = 0;
		int i;
		int j;

		// generate weighted vectors to make weighted points

		//// PATCH to avoid displacement of the interpolated curves when edition[1][0] is a MUTATION. //////
		int start = 0;
		int end = n_editions; // was: -1
		if (Editions.INSERTION == editions[0][0] || Editions.DELETION == editions[0][0]) {
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
				// TODO both  these if statements may be wrong for open curves!
			if (j == m) {
				j = 0;
			}
			// do:
			switch (editions[e][0]) {
				case Editions.INSERTION:
					vs_x = v_x2[j] * alpha;
					vs_y = v_y2[j] * alpha;
					break;
				case Editions.DELETION:
					vs_x = v_x1[i] * (1.0 - alpha);
					vs_y = v_y1[i] * (1.0 - alpha);
					break;
				case Editions.MUTATION:
					vs_x = v_x1[i] * (1.0 - alpha) + v_x2[j] * alpha;
					vs_y = v_y1[i] * (1.0 - alpha) + v_y2[j] * alpha;
					break;
				default:
					System.out.println("\ngetMorphedPerimeter: Nothing added!");
					break;
			}
			if (next+1 == n_editions) {
				x = Util.copy(x, x.length+1);
				y = Util.copy(y, y.length+1);
			}
			// store the point
			x[next+1] = x[next] + vs_x;
			y[next+1] = y[next] + vs_y;

			// advance
			next++;
		}

		//System.out.println("editions length: " + editions.length + "\nx,y length: " + x.length + ", " + y.length);


		// return packed:
		double[][] d = new double[2][];
		d[0] = x;
		d[1] = y;
		return d;
	}

	/** From two VectorString2D, return an array of x,y points ( in the form [2][n] ) defining all necessary intermediate, morphed perimeters that describe a skin between them (not including the two VectorString2D) */
	static public double[][][] getMorphedPerimeters(final VectorString2D vs1, final VectorString2D vs2, int n_morphed_perimeters, final Editions ed) {
		// check automatic mode (-1 is the flag; if less, then just survive it):
		if (n_morphed_perimeters < 0) n_morphed_perimeters = (int)(Math.sqrt(Math.sqrt(ed.getDistance())));

		final double alpha = 1.0 / (n_morphed_perimeters +1); // to have 10 subdivisions we need 11 boxes
		double[][][] p_list = new double[n_morphed_perimeters][][];
		for (int a=0; a<n_morphed_perimeters; a++) {
			double aa = alpha * (a+1); // aa 0 would be vs1, aa 1 would be vs2.
			p_list[a] = SkinMaker.getMorphedPerimeter(vs1, vs2, aa, ed);
		}

		return p_list;
	}

	/** From an array of VectorString2D, return a new array of VectorString2D defining all necessary intermediate, morphed perimeters that describe a skin between each consecutive pair; includes the originals inserted at the proper locations. The 'z' is interpolated. Returns the array of VectorString2D and the array of Editions (which is one item smaller, since it represents matches). */
	static public ArrayList<SkinMaker.Match> getMorphedPerimeters(final VectorString2D[] vs, int n_morphed_perimeters, double delta_, boolean closed) {
		//check preconditions:
		if (n_morphed_perimeters < -1 || vs.length <=0) {
			System.out.println("\nERROR: args are not acceptable at getAllPerimeters:\n\t n_morphed_perimeters " + n_morphed_perimeters + ", n_perimeters " + vs.length);
			return null;
		}

		final ArrayList<SkinMaker.Match> al_matches = new ArrayList<SkinMaker.Match>();

		try {
			// get all morphed curves and return them.
			double delta = 0.0;
			// calculate delta, or taken the user-given one if acceptable (TODO acceptable is not only delta > 0, but also smaller than half the curve length or so.
			if (delta_ > 0) {
				delta = delta_;
			} else {
				for (int i=0; i<vs.length; i++) {
					delta += vs[i].getAverageDelta();
				}
				delta = delta / vs.length;
			}

			System.out.println("\nUsing delta=" + delta);

			// fetch morphed ones and fill all_perimeters array:
			for (int i=1; i<vs.length; i++) {
				Editions ed = new Editions(vs[i-1], vs[i], delta, closed);
				// correct for reverse order: choose the best scoring
				VectorString2D rev = ((VectorString2D)vs[i].clone());
				rev.reverse();
				Editions ed_rev = new Editions(vs[i-1], rev, delta, closed);
				if (ed_rev.getDistance() < ed.getDistance()) {
					vs[i] = rev;
					ed = ed_rev;
				}

				final double[][][] d = SkinMaker.getMorphedPerimeters(vs[i-1], vs[i], n_morphed_perimeters, ed);
				// else, add them all
				final double z_start = vs[i-1].getPoint(2, 0); // z
				final double z_inc = (vs[i].getPoint(2, 0) - z_start) / (double)( 0 == d.length ? 1 : (d.length + 1)); // if zero, none are added anyway; '1' is a dummy number
				//System.out.println("vs[i].z: " + vs[i].getPoint(2, 0) + "  z_start: " + z_start + "  z_inc is: " + z_inc);
				VectorString2D[] p = new VectorString2D[d.length];
				for (int k=0; k<d.length; k++) {
					p[k] = new VectorString2D(d[k][0], d[k][1], z_start + z_inc*(k+1), vs[0].isClosed()); // takes the closed value from the first one, ignoring the other
				}
				al_matches.add(new Match(vs[i-1], vs[i], ed, p));
			}

			return al_matches;

		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/** Tuple to avoid ugly castings. Java is disgusting. */
	static public class Match {
		private VectorString2D vs1;
		private VectorString2D vs2;
		private Editions ed;
		/** The interpolated curves in between vs1 and vs2.*/
		private VectorString2D[] p;

		public Match(VectorString2D vs1, VectorString2D vs2, Editions ed, VectorString2D[] p) {
			this.vs1 = vs1;
			this.vs2 = vs2;
			this.ed = ed;
			this.p = p;
		}
		/** Generate a list of Point3f points, every three defining a triangle, between vs1 and vs2 using the given sequence of editions. */
		public List<Point3f> generateTriangles(final boolean closed) {
			ArrayList<Point3f> triangles = new ArrayList<Point3f>();
			if (null == p || 0 == p.length) {
				triangles.addAll(makeSkin(vs1, vs2, closed, true, true));
			} else {
				triangles.addAll(makeSkin(vs1, p[0], closed, true, false));
				for (int i=1; i<p.length; i++) {
					triangles.addAll(makeSkin(p[i-1], p[i], closed, false, false));
				}
				triangles.addAll(makeSkin(p[p.length-1], vs2, closed, false, true));
			}
			return triangles;
		}
		private ArrayList<Point3f> makeSkin(final VectorString2D a, final VectorString2D b, final boolean closed, final boolean ao, final boolean bo) {
			final double[] ax = a.getPoints(0);
			final double[] ay = a.getPoints(1);
			final float az = (float)a.getPoint(2, 0);
			final int alength = a.length();
			final double[] bx = b.getPoints(0);
			final double[] by = b.getPoints(1);
			final float bz = (float)b.getPoint(2, 0);
			final int blength = b.length();
			final ArrayList<Point3f> triangles = new ArrayList<Point3f>();
			// the sequence of editions defines the edges
			final int[][] editions = ed.editions;
			int e_start = 0; // was 1
			//if (Editions.MUTATION == editions[0][0]) e_start = 0; // apparently I have fixed old errors elsewhere
			int i1, j1;
			int i=0,
			    j=0;
			if (!(!ao && !bo)) { // if at least one is original, use editions for matching
				int ei;
				int lag = 0;
				for (int e=e_start; e<editions.length; e++) {
					ei = editions[e][0];
					i1 = editions[e][1];
					j1 = editions[e][2];
					switch (ei) {
						case Editions.INSERTION:
							if (!ao) lag++;
							break;
						case Editions.DELETION:
							if (!bo) lag++;
							break;
					}
					if (!ao) i1 += lag; // if a is not original
					if (!bo) j1 += lag; // if b is not original
					// safety checks
					if (i1 >= alength) {
						if (closed) i1 = 0;
						else i1 = alength - 1;
					}
					if (j1 >= blength) {
						if (closed) j1 = 0;
						else j1 = blength - 1;
					}
					if ( Editions.MUTATION == ei || ( (!ao || !bo) && (Editions.INSERTION == ei || Editions.DELETION == ei) ) ) { 
						// if it's a mutation, or one of the two curves is not original
						// a quad, split into two triangles:
						// i1, i, j
						triangles.add(new Point3f((float)ax[i1], (float)ay[i1], az));
						triangles.add(new Point3f((float)ax[i], (float)ay[i], az));
						triangles.add(new Point3f((float)bx[j], (float)by[j], bz));
						// i1, j, j1
						triangles.add(new Point3f((float)ax[i1], (float)ay[i1], az));
						triangles.add(new Point3f((float)bx[j], (float)by[j], bz));
						triangles.add(new Point3f((float)bx[j1], (float)by[j1], bz));
					} else {
						// an INSERTION or a DELETION, whe both curves are original
						// i, j, j1
						triangles.add(new Point3f((float)ax[i], (float)ay[i], az));
						triangles.add(new Point3f((float)bx[j], (float)by[j], bz));
						triangles.add(new Point3f((float)bx[j1], (float)by[j1], bz));
					}
					i = i1;
					j = j1;
				}
			} else {
				// Orthogonal match: both are interpolated and thus have the same amount of points,
				//  	which correspond to each other 1:1
				for (int k=0; k<alength-1; k++) { // should be a.length-1, but for some reason the last point is set to 0,0,z and is superfluous
					i1 = k+1;
					j1 = i1;
					// a quad, split into two triangles:
					// i1, i, j
					triangles.add(new Point3f((float)ax[i1], (float)ay[i1], az));
					triangles.add(new Point3f((float)ax[i], (float)ay[i], az));
					triangles.add(new Point3f((float)bx[j], (float)by[j], bz));
					// i1, j, j1
					triangles.add(new Point3f((float)ax[i1], (float)ay[i1], az));
					triangles.add(new Point3f((float)bx[j], (float)by[j], bz));
					triangles.add(new Point3f((float)bx[j1], (float)by[j1], bz));
					i = i1;
					j = j1;
				}
			}
			if (closed) {
				/* // for some reason this is not necessary (inspect why!)
				// last point with first point: a quad
				// 0_i, last_i, last_j
				triangles.add(new Point3f((float)ax[0], (float)ay[0], az));
				triangles.add(new Point3f((float)ax[alength-1], (float)ay[alength-1], az));
				triangles.add(new Point3f((float)bx[blength-1], (float)by[blength-1], bz));
				// 0_i, last_j, 0_j
				triangles.add(new Point3f((float)ax[0], (float)ay[0], az));
				triangles.add(new Point3f((float)bx[blength-1], (float)by[blength-1], bz));
				triangles.add(new Point3f((float)bx[0], (float)by[0], bz));
				*/
			}
			return triangles;
		}
	}

	/** From an array of VectorString2D, obtain a list of Point3f which define, every three, a triangle of a skin. */
	static public List<Point3f> generateTriangles(final VectorString2D[] vs, int n_morphed_perimeters, double delta_, boolean closed) {
		final ArrayList<SkinMaker.Match> al_matches = SkinMaker.getMorphedPerimeters(vs, -1, -1, true); // automatic number of interpolated curves, automatic delta
		final List<Point3f> triangles = new ArrayList<Point3f>(); // every three consecutive Point3f make a triangle
		for (final SkinMaker.Match match : al_matches) {
			triangles.addAll(match.generateTriangles(closed));
		}
		return triangles;
	}
}
