/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007-2009 Albert Cardona.

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

import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.persistence.Loader;

import mpi.fruitfly.math.datastructures.FloatArrayND;

import java.util.*;
import javax.vecmath.Point3f;


/** To extract the sequence of editions that convert any number of open N-dimensional vector strings to an ideal common N-dimensional open vector string. */
public class EditionsND {

	protected VectorString3D[] vs;
	protected double delta;

	protected int[][] editions;
	protected double distance;

	protected Loader loader;

	public EditionsND(final Loader loader, final VectorString3D[] vs, final double delta) throws Exception {
		int ndim = vs[0].getDimensions();
		for (int i=1; i<vs.length; i++) {
			if (vs[i].getDimensions() != ndim) throw new Exception("All VectorString must have the same number of dimensions.");
		}
		this.vs = vs;
		this.delta = delta;
		this.loader = loader;
		init();
	}

	final private void init() {
		// equalize point interdistance
		for (int i=0; i<vs.length; i++) {
			vs[i].resample(delta);
		}

		FloatArrayND fa = makeMatrix();
		// matrix is of length vs.length times the length of each vector.
	}

	private FloatArrayND makeMatrix() { // equivalent to the misnamed Editions.findEditMatrix
		// create a 1-dimensional array to hold the n-dimensional matrix
		final int n_dims = vs.length;
		final int[] len = new int[n_dims];
		long size = vs[0].length();
		for (int i=0; i<n_dims; i++) {
			len[i] = vs[i].length();
			if (i > 0) size *= len[i];
		}
		loader.releaseToFit((long)(size * 1.1));
		final FloatArrayND fa = new FloatArrayND(len);
		final float felta = (float)delta;
		final int[] i = new int[n_dims]; // the positions on each dimension, iu.e. (3,2,6,...4)
		int j = 0;
		final int[] i1 = new int[n_dims];
		for (;;) {
			for (j=0; j<n_dims; j++) {
				if (++i[j] >= len[j]) i[j] = 0;
				else break;
				// here: the i[] contains all the positions, one for each
				//
				// The "equivalent" of the difference of 2 vectors, but for N vectors.
				float val = felta - (float)VectorString3D.getAverageVectorLength(i, vs);

				// min cost deletions and insertions, in all possible combinations
				final float fun1 = getMinCost(fa, i);
				// cost mutation:
				for (int k=0; k<n_dims; k++) {
					if (i[k] -1 > 0) i1[k] = i[k] -1;
				}
				final float fun3 = fa.get(i1) + val;

				fa.set(Math.min(fun1, fun3), i);

			}
			if (j == n_dims) break;
		}
		return fa;
	}

	private final float getMinCost(final FloatArrayND fa, final int[] i) {
		float min = Float.MAX_VALUE;
		// TODO explore, for i positions, all combinations of i and i-1
		return min;
	}

}
