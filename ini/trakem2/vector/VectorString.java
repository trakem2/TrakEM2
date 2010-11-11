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


public interface VectorString {

	static public final int X_AXIS = 0;
	static public final int Y_AXIS = 1;
	static public final int Z_AXIS = 2;

	public void resample(double delta, boolean with_source);
	public void resample(double delta);
	public void reorder(int min_j);
	public int length();
	/** Get the array at dimension dim which ranges from 0 to n-dimensions -1; 0=X, 1=Y, 2=Z, et cetera depending on how many dimensions this VectorString supports. */
	public double[] getPoints(int dim);
	public double[] getVectors(int dim);
	/** Get the value at index i for dimension dim; dimensions start at 0 and go up to n-dimensions -1, for example for VectorString3D, 0=X, 1=Y, 2=Z; to get y[32] call getPoint(1, 32);*/
	public double getPoint(int dim, int i);
	public double getVector(int dim, int i);
	public boolean isClosed();
	public double getDiffVectorLength(int i, int j, VectorString vs);
	public double distance(int i, VectorString vs, int j);
	public VectorString subVectorString(int first, int last) throws Exception;
	public void reverse();
	public int getDimensions();
	public double getDelta();
}
