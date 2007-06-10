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
public class FloatArray2D extends FloatArray
{
//    public float data[] = null;
    public int width = 0;
    public int height = 0;


    public FloatArray2D(int width, int height)
    {
        data = new float[width * height];
        this.width = width;
        this.height = height;
    }

    public FloatArray2D(float[] data, int width, int height)
    {
        this.data = data;
        this.width = width;
        this.height = height;
    }

    public FloatArray2D clone()
    {
        FloatArray2D clone = new FloatArray2D(width, height);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    public int getPos(int x, int y)
    {
        return x + width * y;
    }

    public float get(int x, int y)
    {
        return data[getPos(x,y)];
    }

    public float getMirror(int x, int y)
    {
        if (x >= width)
            x = width - (x - width + 2);

        if (y >= height)
            y = height - (y - height + 2);

        if (x < 0)
            x = -(x%width);

        if (y < 0)
            y = -(y%height);

        return data[getPos(x,y)];
    }

    public float getZero(int x, int y)
    {
        if (x >= width)
            return 0;

        if (y >= height)
            return 0;

        if (x < 0)
            return 0;

        if (y < 0)
            return 0;

        return data[getPos(x,y)];
    }

    public void set(float value, int x, int y)
    {
        data[getPos(x,y)] = value;
    }
}
