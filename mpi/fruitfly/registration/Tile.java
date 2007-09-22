package mpi.fruitfly.registration;

import java.util.Random;
import java.util.Vector;
import java.awt.geom.AffineTransform;


public class Tile
{
	final private float width;
	final private float height;
	private Model model;
	final private Vector< SimPoint2DMatch > matches = new Vector< SimPoint2DMatch >();
	final private static Random rnd = new Random( 69997 );
	
	private float error;
	
	final public float getError() { return error; }
	
	/**
	 * Constructor
	 * 
	 * @param width width of the tile in world unit dimension (e.g. pixels)
	 * @param height height of the tile in world unit dimension (e.g. pixels)
	 * @param model the transformation model of the tile
	 */
	public Tile(
			float width,
			float height,
			Model model )
	{
		this.width = width;
		this.height = height;
		this.model = model;
	}
	
	final public void apply( Model model )
	{
		for ( SimPoint2DMatch m : matches ) m.apply( model );
	}
	
	/**
	 * change the model a bit, apply it to all point matches and
	 * 
	 * @param amount gives the maximal "strength" of shaking
	 */
	final public void shake( float amount )
	{
		/**
		 * now comes the dirty part, i had no idea, how to make this generic
		 * and thus we shake it manually using the affine transforms of the model...
		 */
		TRModel2D shaked = ( TRModel2D )model.clone();
		
		AffineTransform at = shaked.affine;
		double tx = at.getTranslateX() + width / 2 - 1;
		double ty = at.getTranslateY() + height / 2 - 1;
		
		at.translate( -tx, -ty );
		at.rotate( ( 1.0f - 2.0f * rnd.nextFloat() ) * 2.0f * Math.PI * amount );
		at.translate( tx + ( 1.0f - 2.0f * rnd.nextFloat() ) * width * amount, tx + ( 1.0f - 2.0f * rnd.nextFloat() ) * height * amount );
		
		for ( SimPoint2DMatch m : matches )
			m.apply( shaked );
	}
}
