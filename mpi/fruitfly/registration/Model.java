package mpi.fruitfly.registration;

import java.util.Vector;
import java.util.Iterator;

abstract public class Model {
	
	public float error;        //!< maximal error of this model

    /**
     * instantiates an empty model with maximally large error
     */
    public Model()
    {
    	error = Float.MAX_VALUE;
    }

    static final private int MIN_SET_SIZE = 0;

    /**
     * fit the model to a minimal set of FeatureMatches
     */
    abstract boolean fit( Vector< Match > matches );

    /**
     * apply the model to a location
     */
    abstract float[] apply( float[] point );

    /**
     * test the model for a set of Matches
     */
    public float test( Vector< Match > matches, float epsilon, float min_inliers, Vector< Match > inliers )
    {
        int mi = ( int )( min_inliers * matches.size() );
        
        int ni = 0;                                                                                                                                                             
        for ( Iterator< Match > i = matches.iterator(); i.hasNext(); )                                                                                       
        {
        	Match m = i.next();
            
        	float[] p1t = apply( m.p1 );                                                                                                                                                       
        	
        	float te = 0;
        	for ( int j = 0; j < m.p2.length; ++ j )
        	{
        		float d = p1t[ j ] - m.p2[ j ];
        		te += d * d;
        	}
            
        	//System.out.println( sqrt( te ) );
            te = ( float )Math.sqrt( te );
            if ( te < epsilon )
            {
            	inliers.addElement( m );
                ++ni;
            }
            if ( ni > mi )
            {
            	//error = e / static_cast< float >( ni );
                error = 1.0f - ( ( float )( ni ) / ( float )( matches.size() ) );
                if (error > 1.0f)
                	error = 1.0f;
                if (error < 0f)
                	error = 0.0f;
                
            }
        }
        
        return error;
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