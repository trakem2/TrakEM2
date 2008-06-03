package mpi.fruitfly.math.datastructures;

/**
 * <p>Title: FloatArray3D</p>
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
public class FloatArray3D extends FloatArray
{
    public final int width;
    public final int height;
    public final int depth;

    public FloatArray3D(final float[] data, final int width, final int height, final int depth)
    {
	super(data);
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public FloatArray3D(final int width, final int height, final int depth)
    {
        super(new float[width * height * depth]);
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

    public final int getPos(final int x, final int y, final int z)
    {
        return x + width * (y + z * height);
    }

    public final float get(final int x, final int y, final int z)
    {
        return data[getPos(x,y,z)];
    }

    public final float getMirror(int x, int y, int z)
    {
        if (x >= width)
            x = width - (x - width + 2);

        if (y >= height)
            y = height - (y - height + 2);

        if (z >= depth)
            z = depth - (z - depth + 2);

        if (x < 0)
        {
            int tmp = 0;
            int dir = 1;

            while (x < 0)
            {
                tmp += dir;
                if (tmp == width - 1 || tmp == 0)
                    dir *= -1;
                x++;
            }
            x = tmp;
        }

        if (y < 0)
        {
            int tmp = 0;
            int dir = 1;

            while (y < 0)
            {
                tmp += dir;
                if (tmp == height - 1 || tmp == 0)
                    dir *= -1;
                y++;
            }
            y = tmp;
        }

        if (z < 0)
        {
            int tmp = 0;
            int dir = 1;

            while (z < 0)
            {
                tmp += dir;
                if (tmp == height - 1 || tmp == 0)
                    dir *= -1;
                z++;
            }
            z = tmp;
        }

        return data[getPos(x,y,z)];
    }


    public final void set(final float value, final int x, final int y, final int z)
    {
        data[getPos(x,y,z)] = value;
    }

    public final FloatArray2D getXPlane(final int x)
    {
        final FloatArray2D plane = new FloatArray2D(height, depth);

        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                plane.set(this.get(x,y,z),y,z);

        return plane;
    }

    public final float[][] getXPlane_float(final int x)
    {
        float[][] plane = new float[height][depth];

        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                plane[y][z]=this.get(x,y,z);

        return plane;
    }

    public final FloatArray2D getYPlane(final int y)
    {
        final FloatArray2D plane = new FloatArray2D(width, depth);

        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                plane.set(this.get(x,y,z),x,z);

        return plane;
    }

    public final float[][] getYPlane_float(final int y)
    {
        final float[][] plane = new float[width][depth];

        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                plane[x][z] = this.get(x,y,z);

        return plane;
    }

    public final FloatArray2D getZPlane(final int z)
    {
        final FloatArray2D plane = new FloatArray2D(width, height);

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                plane.set(this.get(x,y,z),x,y);

        return plane;
    }

    public final float[][] getZPlane_float(final int z)
    {
        final float[][] plane = new float[width][height];

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                plane[x][y] = this.get(x,y,z);

        return plane;
    }

    public final void setXPlane(final FloatArray2D plane, final int x)
    {
        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                this.set(plane.get(y,z),x,y,z);
    }

    public final void setXPlane(final float[][] plane, final int x)
    {
        for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++)
                this.set(plane[y][z],x,y,z);
    }

    public final void setYPlane(final FloatArray2D plane, final int y)
    {
        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                this.set(plane.get(x,z),x,y,z);
    }

    public final void setYPlane(final float[][] plane, final int y)
    {
        for (int x = 0; x < width; x++)
            for (int z = 0; z < depth; z++)
                this.set(plane[x][z], x, y, z);
    }

    public final void setZPlane(final FloatArray2D plane, final int z)
    {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                this.set(plane.get(x,y),x,y,z);
    }

    public final void setZPlane(final float[][] plane, final int z)
    {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                this.set(plane[x][y],x,y,z);
    }

}
