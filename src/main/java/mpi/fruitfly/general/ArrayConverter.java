/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
