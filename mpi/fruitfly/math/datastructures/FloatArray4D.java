package mpi.fruitfly.math.datastructures;

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
public class FloatArray4D extends FloatArray
{
    public float data[] = null;

    public int n1 = 0;
    public int n2 = 0;
    public int n3 = 0;
    public int n4 = 0;


    public FloatArray4D(int n1, int n2, int n3, int n4)
    {
        data = new float[n1 * n2 * n3 * n4];
        this.n1 = n1;
        this.n2 = n2;
        this.n3 = n3;
        this.n4 = n4;
    }

    public FloatArray4D clone()
    {
        FloatArray4D clone = new FloatArray4D(n1, n2, n3, n4);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    public int getPos(int i1, int i2, int i3, int i4)
    {
        return i1 + n1 * (i2 + n2 * (i3  + n3 * i4));
    }

    public float get(int i1, int i2, int i3, int i4)
    {
        return data[getPos(i1, i2, i3, i4)];
    }

    public void set(float value, int i1, int i2, int i3, int i4)
    {
        data[getPos(i1, i2, i3, i4)] = value;
    }
}
