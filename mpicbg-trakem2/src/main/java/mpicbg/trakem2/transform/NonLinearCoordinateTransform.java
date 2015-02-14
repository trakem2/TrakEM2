/**
 *
 *	Copyright (C) 2008 Verena Kaynig.
 *
 *	This program is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
 *
 *	This program is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU General Public License for more details.
 *
 *	You should have received a copy of the GNU General Public License
 *	along with this program; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

/* ****************************************************************  *
 * Representation of a non linear transform by explicit polynomial
 * kernel expansion.
 *
 * TODO:
 * 	- make different kernels available
 * 	- inverse transform for visualization
 *  - improve image interpolation
 *  - apply and applyInPlace should use precalculated transform?
 *    (What about out of image range pixels?)
 *
 *  Author: Verena Kaynig
 *  Kontakt: verena.kaynig@inf.ethz.ch
 *
 * ****************************************************************  */

package mpicbg.trakem2.transform;

public class NonLinearCoordinateTransform implements mpicbg.trakem2.transform.CoordinateTransform
{
	protected double[][] beta = null;
	protected double[] normMean = null;
	protected double[] normVar = null;
	protected int dimension = 0;
	protected int length = 0;
	protected int width = 0;
	protected int height = 0;

	public NonLinearCoordinateTransform(){};

	@Override
	public void init( final String data ) throws NumberFormatException{
		final String[] fields = data.split( " " );
		int c = 0;

		dimension = Integer.parseInt(fields[c]); c++;
		length = Integer.parseInt(fields[c]); c++;

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		if ( fields.length == 4 + 4*length )
		{
			for (int i=0; i < length; i++){
				beta[i][0] = Double.parseDouble(fields[c]); c++;
				beta[i][1] = Double.parseDouble(fields[c]); c++;
			}

			for (int i=0; i < length; i++){
				normMean[i] = Double.parseDouble(fields[c]); c++;
			}

			for (int i=0; i < length; i++){
				normVar[i] = Double.parseDouble(fields[c]); c++;
			}

			width = Integer.parseInt(fields[c]); c++;
			height = Integer.parseInt(fields[c]); c++;

		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	@Override
	public String toXML(final String indent){
		return new StringBuilder(indent).append("<ict_transform class=\"").append(this.getClass().getCanonicalName()).append("\" data=\"").append(toDataString()).append("\"/>").toString();
	}

	@Override
	public String toDataString(){
		String data = "";
		data += Integer.toString(dimension) + " ";
		data += Integer.toString(length) + " ";

		for (int i=0; i < length; i++){
			data += Double.toString(beta[i][0]) + " ";
			data += Double.toString(beta[i][1]) + " ";
		}

		for (int i=0; i < length; i++){
			data += Double.toString(normMean[i]) + " ";
		}

		for (int i=0; i < length; i++){
			data += Double.toString(normVar[i]) + " ";
		}
		data += Integer.toString(width) + " ";
		data += Integer.toString(height) + " ";

		return data;

	}

	@Override
	public String toString(){ return toDataString(); }

    @Override
    public double[] apply(final double[] location) {

        final double[] position = { (double) location[0], (double) location[1] };
        final double[] featureVector = kernelExpand(position);
        return multiply(beta, featureVector);
    }

    @Override
    public void applyInPlace(final double[] location) {
        final double[] position = { (double) location[0], (double) location[1] };
        final double[] featureVector = kernelExpand(position);
        final double[] newPosition = multiply(beta, featureVector);

        location[0] = newPosition[0];
        location[1] = newPosition[1];
    }


    static protected double[] multiply(final double beta[][], final double featureVector[]) {
        final double[] result = { 0.0, 0.0 };

        if (beta.length != featureVector.length)
        {
            return new double[2];
        }

        for (int i = 0; i < featureVector.length; ++i)
        {
            result[0] = result[0] + featureVector[i] * beta[i][0];
            result[1] = result[1] + featureVector[i] * beta[i][1];
        }

        return result;
    }

    public double[] kernelExpand( final double position[] ) {
        final double expanded[] = new double[length];

        int counter = 0;
        for (int i = 1; i <= dimension; i++) {
            for (double j = i; j >= 0; j--) {
                final double val = Math.pow(position[0], j)
                        * Math.pow(position[1], i - j);
                expanded[counter] = val;
                ++counter;
            }
        }

        for (int i = 0; i < length - 1; i++) {
            expanded[i] = expanded[i] - normMean[i];
            expanded[i] = expanded[i] / normVar[i];
        }

        expanded[length - 1] = 100;

        return expanded;
    }

    /**
     * TODO Make this more efficient
     */
    @Override
    public NonLinearCoordinateTransform copy()
    {
        final NonLinearCoordinateTransform t = new NonLinearCoordinateTransform();
        t.init(toDataString());
        return t;
    }
}
