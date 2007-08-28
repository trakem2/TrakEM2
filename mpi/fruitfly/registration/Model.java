package mpi.fruitfly.registration;

import java.util.Vector;

abstract public class Model {
	
	static final public int MIN_SET_SIZE = 0;

	public float error;		//!< maximal error of this model
	final protected Vector< Match > inliers = new Vector< Match >();
	final public Vector< Match > getInliers()
	{
		return inliers;
	}

	/**
	 * instantiates an empty model with maximally large error
	 */
	public Model()
	{
		error = Float.MAX_VALUE;
	}

	/**
	 * fit the model to a minimal set of FeatureMatches
	 * 
	 * estimates a model to transform the second set to the first
	 */
	abstract boolean fit( Vector< Match > matches );

	/**
	 * apply the model to a location
	 */
	abstract float[] apply( float[] point );

	/**
	 * test the model for a set of point correspondences
	 * 
	 * @param matches set of point correspondences
	 * @param epsilon maximal allowed transfer error
	 * @param min_inliers minimal ratio of inliers of 1.0
	 * @param inliers set of point correspondences fitting the model with a transfer error smaller than epsilon  
	 */
	public boolean test(
			Vector< Match > matches,
			float epsilon,
			float min_inlier_ratio )
	{
		inliers.clear();
		
		//System.out.println();

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
			
			//System.out.println( te );
			
			if ( te < epsilon ) inliers.addElement( m );
		}
		
		//System.out.println( inliers.size() + " inliers" );
		
		float ir = ( float )( inliers.size() ) / ( float )( matches.size() );
		error = 1.0f - ir;
		if (error > 1.0f)
			error = 1.0f;
		if (error < 0f)
			error = 0.0f;
		
		//System.out.println( ir + " " + ( ir > min_inlier_ratio ) );
		
		
		return ( ir > min_inlier_ratio );
	}

	/**
	 * less than operater to make the models sortable, returns false for error < 0
	 */
	public boolean betterThan( Model m )
	{
		if ( error < 0 ) return false;
		return error < m.error;
	}

	/**
	 * string to output stream
	 */
	abstract public String toString();

};
