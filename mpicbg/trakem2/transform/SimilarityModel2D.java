package mpicbg.trakem2.transform;


public class SimilarityModel2D extends mpicbg.models.SimilarityModel2D implements InvertibleCoordinateTransform{

	public void init(String data) throws NumberFormatException {
		final String[] fields = data.split( "\\s+" );
		if ( fields.length == 4 )
		{
			final float scos = Float.parseFloat( fields[ 0 ] );
			final float ssin = Float.parseFloat( fields[ 1 ] );
			final float tx = Float.parseFloat( fields[ 2 ] );
			final float ty = Float.parseFloat( fields[ 3 ] );
			set( scos, ssin, tx, ty );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
		
	}

	public String toDataString() {
		return scos + " " + ssin + " " + tx + " " + ty;
	}

	public String toXML(String indent) {
		return indent + "<iict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\" />";
	}
	
	@Override
	public SimilarityModel2D copy()
	{
		final SimilarityModel2D m = new SimilarityModel2D();
		m.scos = super.scos;
		m.ssin = super.ssin;
		m.tx = super.tx;
		m.ty = super.ty;
		
		m.cost = super.cost;
		
		m.invert();

		return m;
	}

}
