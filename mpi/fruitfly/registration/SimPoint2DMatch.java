package mpi.fruitfly.registration;

public class SimPoint2DMatch
{
	public SimPoint2D s1;
	public SimPoint2D s2;
	
	public SimPoint2DMatch(
			SimPoint2D s1,
			SimPoint2D s2 )
	{
		this.s1 = s1;
		this.s2 = s2;
	}
	
	final public boolean apply( Model model )
	{
		return s1.apply( model );
	}
}
