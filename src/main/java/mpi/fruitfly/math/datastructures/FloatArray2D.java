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

package mpi.fruitfly.math.datastructures;

/**
 * PhaseCorrelation2D.
 *
 * @author Stephan Preibisch
 */
public class FloatArray2D extends FloatArray
{
    public final int width;
    public final int height;


    public FloatArray2D(final int width, final int height)
    {
        super(new float[width * height]);
        this.width = width;
        this.height = height;
    }

    public FloatArray2D(final float[] data, final int width, final int height)
    {
        super(data);
        this.width = width;
        this.height = height;
    }

    public FloatArray2D clone()
    {
        FloatArray2D clone = new FloatArray2D(width, height);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    public final int getPos(final int x, final int y)
    {
        return x + width * y;
    }

    public final float get(final int x, final int y)
    {
        return data[getPos(x,y)];
    }

    public final float getMirror(int x, int y)
    {
        if (x >= width)
            x = width - (x - width + 2);

        if (y >= height)
            y = height - (y - height + 2);

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

       return data[getPos(x,y)];
    }

    public final float getZero(final int x, final int y)
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

    public final void set(final float value, final int x, final int y)
    {
        data[getPos(x,y)] = value;
    }
}
