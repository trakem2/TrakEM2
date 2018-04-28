package mpi.fruitfly.general;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: </p>
 *
 * @author not attributable
 * @version 1.0
 */

public class ArrayConverter
{
    public static float[] twoDimArrayToOneDimArray(float[][] filter)
    {
        float[] result = new float[filter.length * filter[0].length];

        for (int y = 0; y < filter[0].length; y++)
            for (int x = 0; x < filter.length; x++)
                result[x + filter.length*y] = filter[x][y];

        return result;
    }

    public static double[] twoDimArrayToOneDimArray(double[][] filter)
    {
        double[] result = new double[filter.length * filter[0].length];

        for (int y = 0; y < filter[0].length; y++)
            for (int x = 0; x < filter.length; x++)
                result[x + filter.length*y] = filter[x][y];

        return result;
    }

    public static double[][] oneDimArrayToTwoDimArray(double[] filter, int width, int height)
    {
        double[][] result = new double[width][height];

        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                result[x][y] = filter[x + width*y];

        return result;
    }

    // needed because matrix dimension is [row][column] which is the opposite of [x][y]!!!
    //
    public static double[][] oneDimArrayToTwoDimArrayInvert(double[] filter, int width /*columns*/, int height /*rows*/)
    {
        //                             row     column
        double[][] result = new double[height][width];

        for (int row = 0; row < height; row++)
            for (int column = 0; column < width; column++)
                result[row][column] = filter[column + width*row];

        return result;
    }

}
