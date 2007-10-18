package mpi.fruitfly.math.datastructures;

/**
 * <p>Title:        N-Dimensional Array Access </p>
 * <p>Description:  This class creates an N-dimensional float array as 1-dimensional array and provides addressing for arbitrary position.
 *                  Be careful, there is no checking for valid inputs at all!</p>
 * <p>Copyright:    Copyright (c) 2007</p>
 * <p>Company:      MPI-CBG Dresden/Germany</p>
 * <p> * Permission to use, copy, modify and distribute this version of this software or any parts
 * of it and its documentation or any parts of it ("the software"), for any purpose is
 * hereby granted, provided that the above copyright notice and this permission notice
 * appear intact in all copies of the software and that you do not sell the software,
 * or include the software in a commercial package.
 * The release of this software into the public domain does not imply any obligation
 * on the part of the author to release future versions into the public domain.
 * The author is free to make upgraded or improved versions of the software available
 * for a fee or commercially only.
 * Commercial licensing of the software is available by contacting the author.
 * THE SOFTWARE IS PROVIDED "AS IS" AND WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS, IMPLIED OR OTHERWISE, INCLUDING WITHOUT LIMITATION, ANY
 * WARRANTY OF MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.</p>
 * @author       Stephan Preibisch (preibisch@mpi-cbg.de
 */

public class FloatArrayND extends FloatArray
{
    /** The float array, also directly accessible. This is f. ex. important if you just want to iterate on it.
     *  Then there is no need to compute the position each time... <br><br>
     *
     *  for (float value: data)<br>
     *  {<br>
     *       &nbsp;&nbsp;&nbsp;&nbsp;do something...<br>
     *  }<br>
     * <br>
     *  int count = 0;<br>
     *  while (something)<br>
     *  {<br>
     *       &nbsp;&nbsp;&nbsp;&nbsp;float temp = data[count++];<br>
     *       &nbsp;&nbsp;&nbsp;&nbsp;do something...<br>
     *  }<br>
     *
     */
    public float data[] = null;

    // dimensions
    private int n[] = null;

    // the offset for computing the position
    private int dim = 0;

    /** Create N-dimensional float array
    @param n - The entries are the sizes in each dimension
    */
    public FloatArrayND(int n[])
    {
        int size = 0;

        for (int value : n)
            size += value;

        data = new float[size];
        this.n = n.clone();
        this.dim = n.length - 1;
    }


    public FloatArrayND clone()
    {
        FloatArrayND clone = new FloatArrayND(n);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    /** Return the position in a 1-dimensional array for a given positions in the nth-dimension
    @param i - The entries are the positions in each dimension
    @return the position in a 1-dimensional array
    */
    public int getPos(int i[])
    {
        int pos;

        if (dim > 0)
        {
            pos = i[dim - 1] + n[dim - 1] * i[dim];

            for (int j = dim - 2; j >= 0; j--)
            {
                pos *= n[j];
                pos += i[j];
            }
        }
        else
            pos = i[0];

        return pos;
    }

    /** Return the value for a given positions in the nth-dimension
    @param  i - The entries are the positions in each dimension
    @return the value for given postion
    */
    public float get(int i[])
    {
        return data[getPos(i)];
    }

    /** Sets the value for a given positions in the n-dimensional array
    @param i - The entries are the positions in each dimension
    */
    public void set(float value, int i[])
    {
        data[getPos(i)] = value;
    }
}
