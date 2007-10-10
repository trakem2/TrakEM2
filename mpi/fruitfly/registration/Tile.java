package mpi.fruitfly.registration;

import java.util.Random;
import java.util.ArrayList;
import java.util.Collection;
import java.awt.geom.AffineTransform;


public class Tile
{
	final private float width;
	final private float height;

	// local center coordinates of the tile
	private float[] lc;
	// world center coordinates of the tile
	private float[] wc;

	private Model model;
	final public Model getModel() { return model; }
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
			Model model )
	{
		this.width = width;
		this.height = height;
		this.model = model;
		
		lc = new float[]{ width / 2.0f - 1.0f, height / 2.0f - 1.0f };
		wc = new float[]{ lc[ 0 ], lc[ 1 ] };
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
	 * apply a model to all points, update their world-coordinates
	 * 
	 * @param model
	 */
	final public void setModel( Model model )
	{
		this.model = model;
		update();
	}
		
	final public void update()
	{
		double d = 0.0;
		double e = 0.0;
		
		// world center coordinates
		wc = model.apply( lc );
		int num_matches = matches.size();
		if ( num_matches > 0 )
		{
			for ( SimPoint2DMatch match : matches )
			{
				match.apply( model );
				double dl = match.getDistance();
				d += dl;
				//e += dl * dl * match.s1.getWeight();
				e += dl * dl;
			}
			d /= matches.size();
			e /= matches.size();
		}
		distance = ( float )d;
		error = ( float )e;
		model.error = error;
	}
	
	/**
	 * randomly dice new model until the error is smaller than the old one
	 * 
	 * @param max_num_tries maximal number of tries before returning false (which means "no better model found")
	 * @param scale strength of shaking
	 * @return true if a better model was found
	 */
	final public boolean diceBetterModel( int max_num_tries, float scale )
	{
		// store old model
		Model old_model = model;
		ArrayList< Match > ms = SimPoint2DMatch.toMatches( matches );
		
		for ( int t = 0; t < max_num_tries; ++t )
		{
			model = model.clone();
			model.shake( ms, scale, lc );
			update();
			if ( model.betterThan( old_model ) )
			{
				return true;
			}
			else model = old_model;
		}
		// no better model found, so roll back
		update();
		return false;
	}
}
