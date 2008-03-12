package mpi.fruitfly.registration;

import java.util.Random;
import java.util.List;
import java.util.Collection;
import java.util.Vector;

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
public abstract class Model {
	
	// minimal number of point correspondences required to solve the model
	static private int MIN_SET_SIZE = 0;
	abstract public int getMIN_SET_SIZE();
	
	// real random
	//final Random random = new Random( System.currentTimeMillis() );
	// repeatable results
	final static Random rnd = new Random( 69997 );

	/**
	 * error depends on what kind of algorithm is running
	 * small error is better than large error
	 */
	public double error = Double.MAX_VALUE;
	

	/**
	 * less than operater to make the models comparable, returns false for error < 0
	 * 
	 * @param m
	 * @return false for error < 0, otherwise true if this.error is smaller than m.error
	 */
	boolean betterThan( Model m )
	{
		if ( error < 0 ) return false;
		return error < m.error;
	}
	
	/**
	 * fit the model to a minimal set of point correpondences
	 * estimates a model to transform match.p2.local to match.p1.world
	 * 
	 * @param min_matches minimal set of point correpondences
	 * @return true if a model was estimated
	 */
	abstract public boolean fitMinimalSet( PointMatch[] min_matches );

	/**
	 * apply the model to a point location
	 * 
	 * @param point
	 * @return transformed point
	 */
	abstract public float[] apply( float[] point );
	
	/**
	 * apply the model to a point location
	 * 
	 * @param point
	 */
	abstract public void applyInPlace( float[] point );
	
	/**
	 * apply the inverse of the model to a point location
	 * 
	 * @param point
	 * @return transformed point
	 */
	abstract public float[] applyInverse( float[] point );
	
	/**
	 * apply the inverse of the model to a point location
	 * 
	 * @param point
	 */
	abstract public void applyInverseInPlace( float[] point );
	
		
	/**
	 * randomly change the model a bit
	 * 
	 * estimates the necessary amount of shaking for each single dimensional
	 * distance in the set of matches
	 *
	 * @param matches point matches
	 * @param scale gives a multiplicative factor to each dimensional distance (scales the amount of shaking)
	 * @param center local pivot point for centered shakes (e.g. rotation)
	 */
	abstract public void shake(
			Collection< PointMatch > matches,
			float scale,
			float[] center );

	
	/**
	 * Fit the model to a set of point correpondences minimizing the global
	 * transfer error.  This is assumed to be implemented as a least squares
	 * minimization.
	 * 
	 * The estimated model transfers match.p2.local to match.p1.world.
	 * 
	 * @param matches set of point correpondences
	 */
	abstract public void fit( Collection< PointMatch > matches );

	
	/**
	 * Create a meaningful string representation of the model for save into
	 * text-files or display on terminals.
	 */
	abstract public String toString();

	
	/**
	 * Clone the model.
	 */
	abstract public Model clone();
};
