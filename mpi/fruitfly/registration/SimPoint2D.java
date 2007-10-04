package mpi.fruitfly.registration;

/**
 * a similarity, consists of tx, ty, orientation, scale
 * 
 * all coordinates w... are given in the WORLD reference frame,
 * local reference
 * 
 * TODO specify a nice way to get local coordinates... 
 */
public class SimPoint2D
{
	// world coordinates
	private float wtx;
	private float wty;
	private float worientation;
	private float wscale;
	
	final public float getWtx() { return wtx; }
	final public float getWty() { return wty; }
	final public float getWorientation() { return worientation; }
	final public float getWscale() { return wscale; }
	final public float getWeight() { return wscale; }
	
	
	// local coordinates
	final private float ltx;
	final private float lty;
	final private float lorientation;
	final private float lscale;
	
	public SimPoint2D(
			float tx,
			float ty,
			float orientation,
			float scale )
	{
		this.ltx = this.wtx = tx;
		this.lty = this.wty = ty;
		this.lorientation = this.worientation = orientation;
		this.lscale = this.wscale = scale;
	}
	
	/**
	 * apply a model to the similarity
	 * 
	 * TODO For the current application it is only necessary to transform
	 *   wtx and wty.  This should be extended to orientation and scale for
	 *   further applications.  Note that for this purpose we have to specify
	 *   clearly how to transform those applying higher order transformation
	 *   models than similarities.
	 */
	final public boolean apply( Model model )
	{
		float[] t = new float[]{ ltx, lty };
		t = model.apply( t );
		wtx = t[ 0 ];
		wty = t[ 1 ];
		return true;
	}
	
	/**
	 * estimate the Euclidean distance of two points in the world
	 *  
	 * @param s1
	 * @param s2
	 * @return Euclidean distance
	 */
	final public static float distance( SimPoint2D s1, SimPoint2D s2 )
	{
		float dx = s1.wtx - s2.wtx;
		float dy = s1.wty - s2.wty;
		return ( float )Math.sqrt( dx * dx + dy * dy );
	}
}
