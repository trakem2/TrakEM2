package mpi.fruitfly.registration;

import java.util.Random;
import java.util.ArrayList;
import java.util.Vector;
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
	public float[] getWC() { return wc; }

	private Model model;
	final public Model getModel() { return model; }
	final private ArrayList< PointMatch > matches = new ArrayList< PointMatch >();
	final public ArrayList< PointMatch > getMatches() { return matches; }
	final public int getNumMatches() { return matches.size(); }
	
	final private ArrayList< Tile > connectedTiles = new ArrayList< Tile >();
	final public ArrayList< Tile > getConnectedTiles() { return connectedTiles; }
	final public int getNumConnectedTiles() { return connectedTiles.size(); }
	final public boolean addConnectedTile( Tile t )
	{
		if ( connectedTiles.contains( t ) ) return true;
		else return connectedTiles.add( t );
	}
	final public boolean removeConnectedTile( Tile t ) { return connectedTiles.remove( t ); }
	
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
	final public boolean addMatches( Collection< PointMatch > more )
	{
		return matches.addAll( more );
	}
	
	/**
	 * Add one matches
	 *  
	 * @param match
	 * @return true if the list changed as a result of the call.
	 */
	final public boolean addMatch( PointMatch match )
	{
		return matches.add( match );
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
			double sum_weight = 0.0;
			for ( PointMatch match : matches )
			{
				match.apply( model );
				double dl = match.getDistance();
				d += dl;
				e += dl * dl * match.getWeight();
				sum_weight += match.getWeight();
			}
			d /= num_matches;
			e /= sum_weight;
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
		
		for ( int t = 0; t < max_num_tries; ++t )
		{
			model = model.clone();
			model.shake( matches, scale, lc );
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
	
	/**
	 * minimize the model
	 *
	 */
	final public void minimizeModel()
	{
		model.minimize( matches );
		update();
	}
	
	/**
	 * traces the connectivity graph recursively starting from the current tile
	 * 
	 * @param graph
	 * @return the number of connected tiles in the graph
	 */
	final public int traceConnectedGraph( ArrayList< Tile > graph )
	{
		graph.add( this );
		for ( Tile t : connectedTiles )
		{
			if ( !graph.contains( t ) )
				t.traceConnectedGraph( graph );
		}
		return graph.size();
	}
	
	/**
	 * connect two tiles by a set of point correspondences
	 * 
	 * re-weighs the point correpondences
	 * 
	 * We set a weigh of 1.0 / num_matches to each correspondence to equalize
	 * the connections between tiles during minimization.
	 * TODO Check if this is a good idea...
	 * TODO What about the size of a detection, shouldn't it be used as a
	 * weight factor as	well?
	 * 
	 * Change 2007-10-27
	 * Do not normalize by changing the weight, correpondences are weighted by
	 * feature scale. 
	 * 
	 * @param other_tile
	 * @param matches
	 */
	final public void connect(
			Tile other_tile,
			Collection< PointMatch > matches )
	{
//		float num_matches = ( float )matches.size();
//		for ( PointMatch m : matches )
//			m.setWeight( 1.0f / num_matches );
		
		this.addMatches( matches );
		other_tile.addMatches( PointMatch.flip( matches ) );
		
		this.addConnectedTile( other_tile );
		other_tile.addConnectedTile( this );
	}
	
	/**
	 * identify the set of connected graphs that contains all given tiles
	 * 
	 * @param tiles
	 * @return
	 */
	final static public ArrayList< ArrayList< Tile > > identifyConnectedGraphs(
			Collection< Tile > tiles )
	{
		ArrayList< ArrayList< Tile > > graphs = new ArrayList< ArrayList< Tile > >();
		int num_inspected_tiles = 0;
A:		for ( Tile tile : tiles )
		{
			for ( ArrayList< Tile > known_graph : graphs )
				if ( known_graph.contains( tile ) ) continue A; 
			ArrayList< Tile > current_graph = new ArrayList< Tile >();
			num_inspected_tiles += tile.traceConnectedGraph( current_graph );
			graphs.add( current_graph );
			if ( num_inspected_tiles == tiles.size() ) break;
		}
		return graphs;
	}
}
