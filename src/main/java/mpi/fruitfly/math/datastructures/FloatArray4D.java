/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
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
    public final int n1;
    public final int n2;
    public final int n3;
    public final int n4;

    public FloatArray4D(final int n1, final int n2, final int n3, final int n4)
    {
        super(new float[n1 * n2 * n3 * n4]);
        this.n1 = n1;
        this.n2 = n2;
        this.n3 = n3;
        this.n4 = n4;
    }

    public FloatArray4D clone()
    {
        final FloatArray4D clone = new FloatArray4D(n1, n2, n3, n4);
        System.arraycopy(this.data, 0, clone.data, 0, this.data.length);
        return clone;
    }

    public final int getPos(final int i1, final int i2, final int i3, final int i4)
    {
        return i1 + n1 * (i2 + n2 * (i3  + n3 * i4));
    }

    public final float get(final int i1, final int i2, final int i3, final int i4)
    {
        return data[getPos(i1, i2, i3, i4)];
    }

    public final void set(final float value, final int i1, final int i2, final int i3, final int i4)
    {
        data[getPos(i1, i2, i3, i4)] = value;
    }
}
