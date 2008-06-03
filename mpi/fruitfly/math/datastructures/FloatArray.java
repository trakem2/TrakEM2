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
public abstract class FloatArray
{
    public final float data[];
    public abstract FloatArray clone();

    public FloatArray(final float[] data) {
	    this.data = data;
    }
}
