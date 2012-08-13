package mpi.fruitfly.math;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2007</p>
 *
 * <p>Company: </p>
 *
 * <p>License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * @author Stephan Preibisch
 * @version 1.0
 */

import java.awt.Point;
import java.util.Vector;

public class General
{
	final public static double M_2PI = 2.0 * Math.PI;

	/**
	 * simple square
	 * @param value
	 * @return value * value
	 */
	public static double sq( double a )
	{
		return a * a;
	}

	public static float sq( float a )
	{
		return a * a;
	}
	
	/**
	 * fast floor ld of unsigned v
	 */
	public static int ldu( int v )
	{
	    int c = 0; // c will be lg( v )
	    do
	    {
	    	v >>= 1;
	        c++;
	    }
	    while ( v > 1 );
	    return c;
	}

	/**
	 * return a integer that is flipped in the range [0 ... mod - 1]
	 *
	 * @param a the value to be flipped
	 * @param range the size of the range
	 * @return a flipped in range like a ping pong ball
	 */
	public static int flipInRange( int a, int mod )
	{
		int p = 2 * mod;
		if ( a < 0 ) a = p + a % p;
		if ( a >= p ) a = a % p;
		if ( a >= mod ) a = mod - a % mod - 1;
		return a;
	}
	
    public static int sign (double value)
    {
        if (value < 0)
            return -1;
        else
            return 1;
    }

    public static boolean isApproxEqual(double a, double b, double threshold)
    {
      if (a==b)
        return true;

      if (a + threshold > b && a - threshold < b)
        return true;
      else
        return false;
    }

    public static double computeCorrelationCoefficient(double[] X1, double[] X2)
    {
        // check for valid input arrays
        if (X1 == null || X2 == null)
            return Double.NaN;

        if (X1.length != X2.length)
            return Double.NaN;

        int length = X1.length;

        if (length == 0)
            return Double.NaN;

        // compute averages
        double avgX1 = 0, avgX2 = 0;

        for (int pos = 0; pos < length; pos++)
        {
            avgX1 += X1[pos];
            avgX2 += X2[pos];
        }

        avgX1 /= (double)length;
        avgX2 /= (double)length;

        // compute covariance and variances of X1 and X2
        double covar =  0, varX1 = 0, varX2 = 0;
        double distX1, distX2;

        for (int pos = 0; pos < length; pos++)
        {
            distX1 = X1[pos] - avgX1;
            distX2 = X2[pos] - avgX2;

            covar += distX1 * distX2;

            varX1 += distX1 * distX1;
            varX2 += distX2 * distX2;
        }

        covar /= (double)length;
        varX1 /= (double)length;
        varX2 /= (double)length;

        varX1 = Math.sqrt(varX1);
        varX2 = Math.sqrt(varX2);

        double R = covar / (varX1 * varX2);

        return R;
    }

    public static int min(int a, int b)
    {
        if (a > b)
            return b;
        else
            return a;
    }


    public static double min(double a, double b)
    {
        if (a > b)
            return b;
        else
            return a;
    }

    public static float min(float a, float b)
    {
        if (a > b)
            return b;
        else
            return a;
    }

    public static int max(int a, int b)
    {
        if (a > b)
            return a;
        else
            return b;
    }

    public static float max(float a, float b)
    {
        if (a > b)
            return a;
        else
            return b;
    }
    public static double max(double a, double b)
    {
        if (a > b)
            return a;
        else
            return b;
    }

    /**
     * Berechnet die Differenz(Offset) der ganzzahligen Eingabe zur naechsten Zweierpotenz.
     * Bsp: Eingabe 23 -> naechste Potenz 32 -> Ergebnis 9
     * @param size ganzzahliger Eingabewert
     * @return int der berechnete Offset
     */
    public static int offsetToPowerOf2(int size)
    {
        int offset = 0;
        int power = 2;

        while (power < size)
        {
            power = power * 2;
            if (power == size)
            {
                return 0;
            }
            offset = power - size;
        }
        return offset;
    }

    public static double gLog(double z, double c)
    {
        if (c == 0)
            return z;
        else
            return Log10((z + Math.sqrt(z * z + c * c)) / 2.0);
    }

    public static double gLogInv(double w, double c)
    {
        if (c == 0)
            return w;
        else
            return Exp10(w) - (((c * c) * Exp10( -w)) / 4.0);
    }

    public static double Log10(double x)
    {
        if (x > 0)
            return (Math.log(x) / Math.log(10));
        else
            return 0;
    }

    public static double Exp10(double x)
    {
        return (Math.pow(10, x));
    }

    public static int pow(int a, int b)
    {
        if (b == 0)
            return 1;

        if (b == 1)
            return a;

        int result = a;

        for (int i = 1; i < b; i++)
            result *= a;

        return result;
    }

    public static double computeTurkeyBiAverage(double[] conc)
    {
        return computeTurkeyBiAverage(conc, null, 5);
    }

    public static double computeTurkeyBiAverage(double[] conc, double c)
    {
        return computeTurkeyBiAverage(conc, null, c);
    }

    public static double computeTurkeyBiAverage(double[] conc, double[] W, double c)
    {
        // weighted or non-weighted
        if (W == null)
        {
            W = new double[conc.length];
            for (int i = 0; i < W.length; i++)
                W[i] = 1;
        }

        // compute average
        double avgConc = 0;
        double sumW = 0;

        for (int i = 0; i < conc.length; i++)
        {
            avgConc += W[i] * conc[i];
            sumW += W[i];
        }

        avgConc /= sumW;

        // compute standard deviation
        double SD = 0;

        for (int i = 0; i < conc.length; i++)
        {
            SD += W[i] * Math.pow(conc[i] - avgConc, 2);
        }

        SD = Math.sqrt(SD / sumW);

        // compute outlier
        double u[] = new double[conc.length];
        double e;

        for (int i = 0; i < u.length; i++)
        {
            e = Math.abs(0.01 * conc[i]); // prevent division by zero
            u[i] = (W[i] * (conc[i] - avgConc)) / ((c * SD) + e);
        }

        // compute turkey-bi weightening values

        double tb[] = new double[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            if (Math.abs(u[i]) > 1)
                tb[i] = 0;
            else
                tb[i] = Math.pow(1 - (u[i] * u[i]), 2);
        }

        // compute result
        double concTB = 0;
        double temp = 0;

        for (int i = 0; i < conc.length; i++)
        {
            concTB += tb[i] * conc[i];
            temp += tb[i];
        }

        return (concTB / temp);
    }

    public static double computeTurkeyBiMedian(double[] concinput, double c)
    {
        double[] conc = concinput.clone();

        // compute median
        double medianConc = computeMedian(conc);

        // compute "standard deviation"
        double SD = 0;
        double SDValues[] = new double[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            SDValues[i] = Math.abs(conc[i] - medianConc);
        }

        SD = computeMedian(SDValues);

        // compute "ausreisser"
        double u[] = new double[conc.length];
        double e;

        for (int i = 0; i < u.length; i++)
        {
            e = Math.abs(0.01 * conc[i]); // prevent division by zero
            u[i] = ((conc[i] - medianConc)) / ((c * SD) + e);
        }

        // compute turkey-bi weightening values

        double tb[] = new double[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            if (Math.abs(u[i]) > 1)
                tb[i] = 0;
            else
                tb[i] = Math.pow(1 - (u[i] * u[i]), 2);
        }

        // compute result
        double concTB = 0;
        double temp = 0;

        for (int i = 0; i < conc.length; i++)
        {
            concTB += tb[i] * conc[i];
            temp += tb[i];
        }

        return (concTB / temp);
    }

    public static float computeTurkeyBiMedian(float[] concinput, float c)
    {
        float[] conc = concinput.clone();

        // compute median
        float medianConc = computeMedian(conc);

        // compute "standard deviation"
        float SD = 0;
        float SDValues[] = new float[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            SDValues[i] = Math.abs(conc[i] - medianConc);
        }

        SD = computeMedian(SDValues);

        // compute "ausreisser"
        float u[] = new float[conc.length];
        float e;

        for (int i = 0; i < u.length; i++)
        {
            e = (float)Math.abs(0.01 * conc[i]); // prevent division by zero
            u[i] = ((conc[i] - medianConc)) / ((c * SD) + e);
        }

        // compute turkey-bi weightening values

        float tb[] = new float[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            if (Math.abs(u[i]) > 1)
                tb[i] = 0;
            else
                tb[i] = (float)Math.pow(1 - (u[i] * u[i]), 2);
        }

        // compute result
        float concTB = 0;
        float temp = 0;

        for (int i = 0; i < conc.length; i++)
        {
            concTB += tb[i] * conc[i];
            temp += tb[i];
        }

        return (concTB / temp);
    }

    public static double[] computeTurkeyBiMedianWeights(double[] concinput, double c)
    {
        double[] conc = concinput.clone();

        // compute median
        double medianConc = computeMedian(conc);

        // compute "standard deviation"
        double SD = 0;
        double SDValues[] = new double[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            SDValues[i] = Math.abs(conc[i] - medianConc);
        }

        SD = computeMedian(SDValues);

        // compute "ausreisser"
        double u[] = new double[conc.length];
        double e;

        for (int i = 0; i < u.length; i++)
        {
            e = Math.abs(0.01 * conc[i]); // prevent division by zero
            u[i] = ((conc[i] - medianConc)) / ((c * SD) + e);
        }

        // compute turkey-bi weightening values

        double tb[] = new double[conc.length];

        for (int i = 0; i < conc.length; i++)
        {
            if (Math.abs(u[i]) > 1)
                tb[i] = 0;
            else
                tb[i] = Math.pow(1 - (u[i] * u[i]), 2);
        }

        return tb;
    }

    public static double computeMedian(double[] values)
    {
        double temp[] = values.clone();
        double median;

        int length = temp.length;

        quicksort(temp, 0, length - 1);

        if (length % 2 == 1) //odd length
            median = temp[length / 2];
        else //even length
            median = (temp[length / 2] + temp[(length / 2) - 1]) / 2;

        temp = null;
        return median;
    }

    public static float computeMedian(float[] values)
    {
        float temp[] = values.clone();
        float median;

        int length = temp.length;

        quicksort(temp, 0, length - 1);

        if (length % 2 == 1) //odd length
            median = temp[length / 2];
        else //even length
            median = (temp[length / 2] + temp[(length / 2) - 1]) / 2;

        temp = null;
        return median;
    }

    // entropie integral p * log(p)
    // entropie der joint distr. - entropie 1 - entropie 2
    // wieviel von der gemeinsamen entropie st

    // helligkeiten sind ausgang des experimentes


    public static void quicksort(double[] data, int left, int right)
    {
        if (data == null || data.length < 2)return;
        int i = left, j = right;
        double x = data[(left + right) / 2];
        do
        {
            while (data[i] < x) i++;
            while (x < data[j]) j--;
            if (i <= j)
            {
                double temp = data[i];
                data[i] = data[j];
                data[j] = temp;
                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, left, j);
        if (i < right) quicksort(data, i, right);
    }

    public static void quicksort(float[] data, int left, int right)
    {
        if (data == null || data.length < 2)return;
        int i = left, j = right;
        float x = data[(left + right) / 2];
        do
        {
            while (data[i] < x) i++;
            while (x < data[j]) j--;
            if (i <= j)
            {
                float temp = data[i];
                data[i] = data[j];
                data[j] = temp;
                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, left, j);
        if (i < right) quicksort(data, i, right);
    }

    public static void quicksort(Quicksortable[] data, int left, int right)
    {
        if (data == null || data.length < 2)return;
        int i = left, j = right;

        double x = data[(left + right) / 2].getQuicksortValue();

        do
        {
            while (data[i].getQuicksortValue() < x) i++;
            while (x < data[j].getQuicksortValue()) j--;
            if (i <= j)
            {
                Quicksortable temp = data[i];
                data[i] = data[j];
                data[j] = temp;
                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, left, j);
        if (i < right) quicksort(data, i, right);
    }

    public static void quicksort(Vector data, int left, int right)
    {
        if (data == null || data.size() < 2)return;
        int i = left, j = right;

        double x = ((Quicksortable)data.get((left + right) / 2)).getQuicksortValue();
        do
        {
            while (((Quicksortable)data.get(i)).getQuicksortValue() < x) i++;
            while (x < ((Quicksortable)data.get(j)).getQuicksortValue()) j--;
            if (i <= j)
            {
                Quicksortable temp = (Quicksortable)data.get(i);
                data.set( i, data.get( j ) );
                data.set(j, temp);
                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, left, j);
        if (i < right) quicksort(data, i, right);
    }

    public static void quicksort(float[] data, Point[] sortAlso, int left, int right)
    {
        if (data == null || data.length < 2)return;
        int i = left, j = right;
        float x = data[(left + right) / 2];
        do
        {
            while (data[i] < x) i++;
            while (x < data[j]) j--;
            if (i <= j)
            {
                float temp = data[i];
                data[i] = data[j];
                data[j] = temp;

                Point temp2 = sortAlso[i];
                sortAlso[i] = sortAlso[j];
                sortAlso[j] = temp2;

                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, sortAlso, left, j);
        if (i < right) quicksort(data, sortAlso, i, right);
    }

    public static void quicksort(double[] data, int[] sortAlso, int left, int right)
    {
        if (data == null || data.length < 2)return;
        int i = left, j = right;
        double x = data[(left + right) / 2];
        do
        {
            while (data[i] < x) i++;
            while (x < data[j]) j--;
            if (i <= j)
            {
                double temp = data[i];
                data[i] = data[j];
                data[j] = temp;

                int temp2 = sortAlso[i];
                sortAlso[i] = sortAlso[j];
                sortAlso[j] = temp2;

                i++;
                j--;
            }
        }
        while (i <= j);
        if (left < j) quicksort(data, sortAlso, left, j);
        if (i < right) quicksort(data, sortAlso, i, right);
    }

}
