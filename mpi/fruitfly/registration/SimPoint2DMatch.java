package mpi.fruitfly.registration;

import java.util.ArrayList;
import java.util.Collection;

public class SimPoint2DMatch
{
	public SimPoint2D s1;
	public SimPoint2D s2;
	
	private float distance;
	private float xDistance;
	private float yDistance;
	private float rDistance;
	
	final public float getDistance(){ return distance; }
	final public float getXDistance(){ return xDistance; }
	final public float getYDistance(){ return yDistance; }
	final public float getRDistance(){ return rDistance; }
	
	public SimPoint2DMatch(
			SimPoint2D s1,
			SimPoint2D s2 )
	{
		this.s1 = s1;
		this.s2 = s2;
		
		distance = SimPoint2D.distance( s1, s2 );
	}
	
	final public void apply( Model model )
	{
		s1.apply( model );
		distance = SimPoint2D.distance( s1, s2 );
	}
	
	final public static ArrayList< SimPoint2DMatch > fromMatches( Collection< Match > matches )
	{
		ArrayList< SimPoint2DMatch > list = new ArrayList< SimPoint2DMatch >();
		float weight = 1.0f / ( float )matches.size();
		for ( Match match : matches )
		{
			list.add(
					new SimPoint2DMatch(
							new SimPoint2D(
									match.p1[ 0 ],
									match.p1[ 1 ],
									0.0f,
									weight ),
							new SimPoint2D(
									match.p2[ 0 ],
									match.p2[ 1 ],
									0.0f,
									weight ) ) );
		}
		return list;
	}
	
	final public static ArrayList< Match > toMatches( Collection< SimPoint2DMatch > matches )
	{
		ArrayList< Match > list = new ArrayList< Match >();
		for ( SimPoint2DMatch match : matches )
		{
			list.add(
					new Match(
							new float[]{ match.s1.getWtx(), match.s1.getWty() },
							new float[]{ match.s2.getWtx(), match.s2.getWty() },
							match.s1.getWeight(),
							match.s2.getWeight() ) );
		}
		return list;
	}
	
	final public static ArrayList< SimPoint2DMatch > flip( Collection< SimPoint2DMatch > matches )
	{
		ArrayList< SimPoint2DMatch > list = new ArrayList< SimPoint2DMatch >();
		for ( SimPoint2DMatch match : matches )
		{
			list.add(
					new SimPoint2DMatch(
							match.s2,
							match.s1 ) );
		}
		return list;
	}
	
}
