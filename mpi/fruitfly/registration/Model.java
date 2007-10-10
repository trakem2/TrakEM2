package mpi.fruitfly.registration;

import java.util.Random;
import java.util.List;
import java.util.Collection;
import java.util.Vector;
import java.awt.geom.AffineTransform;

/**
 * Abstract class for arbitrary geometric transformation models to be applied
 * to points in n-dimensional space.  
 * 
 * Provides methods for generic optimization and model extraction algorithms.
 * Currently, RANSAC and Monte-Carlo minimization implemented.  Needs revision...
 *  
 * TODO A model is planned to be a generic transformation pipeline to be
 * applied to images, volumes or arbitrary sets of n-dimensional points.  E.g.
 * lens transformation of camera images, pose and location of mosaic tiles,
 * non-rigid bending of confocal stacks etc.
 *  
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 *
 */
abstract public class Model {
	
	// minimal number of point correspondences required to solve the model
	static final public int MIN_SET_SIZE = 0;
	
	// real random
	//final Random random = new Random( System.currentTimeMillis() );
	// repeatable results
	final static Random rnd = new Random( 69997 );

	/**
	 * error depends on what kind of algorithm is running
	 * small error is better than large error
	 */
	public float error;
	
	/**
	 * set of point correspondences that match the model
	 * the model transforms match.p2 to match.p1
	 * TODO check if this is correctly used everywhere
	 */
	final protected Vector< Match > inliers = new Vector< Match >();
	final public Vector< Match > getInliers() { return inliers;	}

	/**
	 * instantiates an empty model with maximally large error
	 */
	public Model()
	{
		error = Float.MAX_VALUE;
	}

	/**
	 * fit the model to a minimal set of point correpondences
	 * estimates a model to transform match.p2 to match.p1
	 * 
	 * @param matches minimal set of point correpondences
	 * @return true if a model was estimated
	 */
	abstract boolean fit( Match[] matches );

	/**
	 * apply the model to a point location
	 * 
	 * @param point
	 * @return transformed point
	 */
	abstract float[] apply( float[] point );

	/**
	 * test the model for a set of point correspondences
	 * 
	 * sets this.inliers with the fitting subset of matches
	 * 
	 * @param matches set of point correspondences
	 * @param epsilon maximal allowed transfer error
	 * @param min_inliers minimal ratio of inliers (0.0 => 0%, 1.0 => 100%)
	 */
	public boolean test(
			Collection< Match > matches,
			float epsilon,
			float min_inlier_ratio )
	{
		inliers.clear();
		
		for ( Match m : matches )
		{
			float[] p2t = apply( m.p2 );

			// estimate Euclidean distance
			float te = 0;
			for ( int j = 0; j < m.p1.length; ++ j )
			{
				float d = p2t[ j ] - m.p1[ j ];
				te += d * d;
			}
			te = ( float )Math.sqrt( te );
			
			if ( te < epsilon ) inliers.addElement( m );
		}
		
		float ir = ( float )( inliers.size() ) / ( float )( matches.size() );
		error = 1.0f - ir;
		if (error > 1.0f)
			error = 1.0f;
		if (error < 0f)
			error = 0.0f;
		
		return ( ir > min_inlier_ratio );
	}

	/**
	 * less than operater to make the models comparable, returns false for error < 0
	 * 
	 * @param m
	 * @return false for error < 0, otherwise true if this.error is smaller than m.error
	 */
	public boolean betterThan( Model m )
	{
		if ( error < 0 ) return false;
		return error < m.error;
	}
	
	abstract public void shake( Collection< Match > matches, float scale, float[] center );
	
	abstract public AffineTransform getAffine();

	/**
	 * string to output stream
	 */
	abstract public String toString();
	
	/**
	 * clone
	 */
	abstract public Model clone();
};
