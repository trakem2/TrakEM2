package mpi.fruitfly.registration;

import java.util.Random;
import java.util.ArrayList;
import java.util.Collection;
import java.awt.geom.AffineTransform;


public class Tile
{
	final private float width;
	final private float height;
	private TRModel2D model;
	final public TRModel2D getModel() { return model; }
	final private ArrayList< SimPoint2DMatch > matches = new ArrayList< SimPoint2DMatch >();
	final private static Random rnd = new Random( 69997 );
	
	private float error;
	final public float getError() { return error; }
	
	private float distance;
	final public float getDistance() { return distance; }
	
	/**
	 * Constructor
	 * 
	 * @param width width of the tile in world unit dimension (e.g. pixels)
	 * @param height height of the tile in world unit dimension (e.g. pixels)
	 * @param model the transformation model of the tile
	 * 
	 * TODO Needs revision to make it work with arbitrary models instead of TRModel2D only.
	 */
	public Tile(
			float width,
			float height,
			TRModel2D model )
	{
		this.width = width;
		this.height = height;
		this.model = model;
	}
	
	/**
	 * Add more matches
	 *  
	 * @param more collection of matches
	 * @return true if the list changed as a result of the call.
	 */
	final public boolean addMatches( Collection< SimPoint2DMatch > more )
	{
		return matches.addAll( more );
	}
	
	/**
	 * apply a model to all points, that is update their world-coordinates
	 * @param model
	 */
	final public void setModel( TRModel2D model )
	{
		this.model = model;
		update();
	}
	
	/**
	 * change the model a bit, apply to all point matches and estimate the new
	 * mean displacement (error)
	 * 
	 * @param amount gives the maximal "strength" of shaking
	 * TODO Like for the whole thing, do this for arbitrary models instead of TRModel2D only.
	 */
	final private void shake( float amount )
	{
		double cx = width / 2 - 1;
		double cy = height / 2 - 1;
		
//		AffineTransform ats = new AffineTransform();
//		ats.setToIdentity();
		
//		ats.rotate( rnd.nextGaussian() * 2.0f * Math.PI * amount, cx, cy );
//		ats.rotate( rnd.nextGaussian() * 2.0f * Math.PI / 360, cx, cy );
//		shaked.affine.concatenate( ats );
		
//		shaked.affine.translate( -tx, -ty );
//		shaked.affine.rotate( ( 1.0f - 2.0f * rnd.nextFloat() ) * 2.0f * Math.PI * amount );
//		shaked.affine.translate( tx + ( 1.0f - 2.0f * rnd.nextFloat() ) * width * amount, ty + ( 1.0f - 2.0f * rnd.nextFloat() ) * height * amount );
		
//		shaked.affine.translate( rnd.nextGaussian() * amount, rnd.nextGaussian() * amount );
		model.affine.translate( rnd.nextGaussian() * amount, rnd.nextGaussian() * amount );

		// apply and estimate the error
		update();
	}
	
	final public void update()
	{
		double d = 0.0;
		double e = 0.0;
		for ( SimPoint2DMatch match : matches )
		{
			match.apply( model );
			double dl = match.getDistance();
			d += dl;
			//e += dl * match.s1.getWeight();
			e += dl * dl;
		}
		d /= matches.size();
		e /= matches.size();
		distance = ( float )d;
		error = ( float )e;
		model.error = error;
	}
	
	/**
	 * randomly dice new model in a limited range until the error is smaller than the old one
	 * 
	 * @param max_num_tries maximal number of tries before returning false (which means "no better model found")
	 * @param amount gives the maximal "strength" for random shaking
	 * @return true if a better model was found
	 */
	final public boolean diceBetterModel( int max_num_tries, float amount )
	{
		// store old model
		TRModel2D old_model = model;
		for ( int t = 0; t < max_num_tries; ++t )
		{
			model = model.clone();
			shake( amount );
			if ( model.betterThan( old_model ) ) return true;
			else model = old_model;
		}
		// no better model found, so roll back
		update();
		return false;
	}
	
	/**
	 * minimize the tiles model
	 */
	final public boolean minimizeModel()
	{
		model.minimize( matches );
		return true;
	}
	
}
