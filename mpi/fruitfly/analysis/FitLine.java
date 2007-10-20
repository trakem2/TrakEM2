package mpi.fruitfly.analysis;

import Jama.SingularValueDecomposition;
import Jama.Matrix;

/**
 * <p>Title: Line Fitter</p>
 *
 * <p>Description: Fits line (m*x + n) to a double array and return correlation coefficient, standard deviation and m and n of the linear equation</p>
 *
 * <p>Copyright: Copyright (c) 2007</p>
 *
 * <p>Company: MPI-CBG</p>
 *
 * @author Stephan Preibisch
 * @version 1.0
 */
public class FitLine
{
    /**
     * Fits the line to a point cloud and returns fitted line (m*x + n) and regression coefficient and Standard Deviation
     *
     * @param values double[] - values to fit a line to
     * @return double[] - double array containing (R, stDev, m, n)
     */
    public static double[] fitLine(double[] values)
    {
        // we need the following data structures
        double[][] deltaLin = new double[2][2];
        double[] thetaLin = new double[2];

        double[] resultLin = new double[2];
        double x, y;

        for (int pos = 0; pos < values.length; pos++)
        {
            x = pos;
            y = values[pos];

            deltaLin[0][0] += Math.pow(x, 2); //delta[Zeile][Spalte]
            deltaLin[0][1] += x; //delta[Zeile][Spalte]
            deltaLin[1][0] += x; //delta[Zeile][Spalte]
            deltaLin[1][1] += 1; //delta[Zeile][Spalte]

            thetaLin[0] += y * x;
            thetaLin[1] += y;
        }

        // solve linear systems of equations
        int errorsLin = solveLSE(2, deltaLin, thetaLin, resultLin);

        if (errorsLin > 0)
            System.out.println("Error solving linear system of equations for linear fit. ");

        // compute residuals
        double m = resultLin[0];
        double n = resultLin[1];

        double[] line = new double[values.length];

        for (int pos = 0; pos < values.length; pos++)
        {
            x = (double) pos;
            line[pos] = m * x + n;

            //System.out.println(values[pos] + "\t" + line[pos]);
        }

        double R = computeCorrelationCoefficient(line, values);
        double stDev = Math.sqrt(computeVariance(values));

        return new double[] {R, stDev, m, n};
    }

    public static double computeAverage(double[] values)
    {
            double avg = 0;

            for (double v : values)
                    avg += v;

            avg /= (double)values.length;

            return avg;
    }

    public static double computeVariance(double[] values)
    {
            double variance = 0;
            double avg = computeAverage(values);

            for (double v : values)
                    variance += (v - avg) * (v - avg);

            variance /= ((double)values.length - 1);

            return variance;
    }

    private static double computeCorrelationCoefficient(double[] X1, double[] X2)
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

        avgX1 /= (double) length;
        avgX2 /= (double) length;

        // compute covariance and variances of X1 and X2
        double covar = 0, varX1 = 0, varX2 = 0;
        double distX1, distX2;

        for (int pos = 0; pos < length; pos++)
        {
            distX1 = X1[pos] - avgX1;
            distX2 = X2[pos] - avgX2;

            covar += distX1 * distX2;

            varX1 += distX1 * distX1;
            varX2 += distX2 * distX2;
        }

        covar /= (double) length;
        varX1 /= (double) length;
        varX2 /= (double) length;

        varX1 = Math.sqrt(varX1);
        varX2 = Math.sqrt(varX2);

        double R = covar / (varX1 * varX2);

        return R;
    }

    private static int solveLSE(int matrixSize, double[][] delta, double[] tetha, double[] result)
    {
        int errors = 0;
        Matrix A = new Matrix(delta);
        Matrix B = new Matrix(tetha, matrixSize);
        SingularValueDecomposition svd = new SingularValueDecomposition(A);
        try
        {
            Matrix U = svd.getU(); //U Left Matrix
            Matrix S = svd.getS(); //W
            Matrix V = svd.getV(); //VT Right Matrix

            //invert S
            for (int i = 0; i < matrixSize; i++)
            {
                double temp = S.get(i, i);
                if (temp < 0.00001)
                    temp = 0;
                else
                    temp = 1 / temp;
                S.set(i, i, temp);
            }
            //done

            //transponse U and V
            U = U.transpose();
            //done

            //X = V*S*U*B;
            Matrix X = ((V.times(S)).times(U)).times(B);

            for (int i = 0; i < matrixSize; i++)
                result[i] = X.get(i, 0);
        } catch (Exception e)
        {
            System.err.println("Fehler : " + e);
        }

        //check wheater result is correct
        for (int y = 0; y < matrixSize; y++) //Zeile
        {
            double lineSum = 0.0;
            for (int x = 0; x < matrixSize; x++) //Spalte
            {
                lineSum += delta[y][x] * result[x];
            }
            if (Math.abs(lineSum - tetha[y]) > 0.001) errors++;
        }

        return errors;
    }
}



