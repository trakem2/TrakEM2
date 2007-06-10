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
public class FloatArray3D extends FloatArray
{
    //public float data[] = null;
    public int width = 0;
    public int height = 0;
    public int depth = 0;

    public FloatArray3D(float[] data, int width, int height, int depth)
    {
        this.data = data;
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public FloatArray3D(int width, int height, int depth)
    {
        data = new float[width * height * depth];
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public FloatArray3D clone()
    {
        FloatArray3D clone = new FloatArray3D(width, height, depth);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    public int getPos(int x, int y, int z)
    {
        return x + width * (y + z * height);
    }

    public float get(int x, int y, int z)
    {
        return data[getPos(x,y,z)];
    }

    public void set(float value, int x, int y, int z)
    {
        data[getPos(x,y,z)] = value;
    }

    public FloatArray2D getXPlane(int x)
    {
        FloatArray2D plane = new FloatArray2D(height, depth);

        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                plane.set(this.get(x,y,z),y,z);

        return plane;
    }

    public float[][] getXPlane_float(int x)
    {
        float[][] plane = new float[height][depth];

        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                plane[y][z]=this.get(x,y,z);

        return plane;
    }

    public FloatArray2D getYPlane(int y)
    {
        FloatArray2D plane = new FloatArray2D(width, depth);

        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                plane.set(this.get(x,y,z),x,z);

        return plane;
    }

    public float[][] getYPlane_float(int y)
    {
        float[][] plane = new float[width][depth];

        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                plane[x][z] = this.get(x,y,z);

        return plane;
    }

    public FloatArray2D getZPlane(int z)
    {
        FloatArray2D plane = new FloatArray2D(width, height);

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                plane.set(this.get(x,y,z),x,y);

        return plane;
    }

    public float[][] getZPlane_float(int z)
    {
        float[][] plane = new float[width][height];

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                plane[x][y] = this.get(x,y,z);

        return plane;
    }

    public void setXPlane(FloatArray2D plane, int x)
    {
        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                this.set(plane.get(y,z),x,y,z);
    }

    public void setXPlane(float[][] plane, int x)
    {
        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                this.set(plane[y][z],x,y,z);
    }

    public void setYPlane(FloatArray2D plane, int y)
    {
        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                this.set(plane.get(x,z),x,y,z);
    }

    public void setYPlane(float[][] plane, int y)
    {
        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                this.set(plane[x][z], x, y, z);
    }

    public void setZPlane(FloatArray2D plane, int z)
    {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                this.set(plane.get(x,y),x,y,z);
    }

    public void setZPlane(float[][] plane, int z)
    {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                this.set(plane[x][y],x,y,z);
    }

}
