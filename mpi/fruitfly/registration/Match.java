package mpi.fruitfly.registration;

public class Match {
	final public float[] p1;
	final public float[] p2;
	
	public Match( float[] p1, float[] p2 )
	{
		this.p1 = p1;
		this.p2 = p2;
	}
	
	public Match( SIFTProcessor.FeatureMatch fm )
	{
		this.p1 = new float[]{ fm.f1.location[ 0 ], fm.f1.location[ 1 ] };
		this.p2 = new float[]{ fm.f2.location[ 0 ], fm.f2.location[ 1 ] };
	}
}
