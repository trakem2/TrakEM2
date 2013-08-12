package mpicbg.trakem2.transform;


public class HomographyModel2D extends mpicbg.models.HomographyModel2D implements InvertibleCoordinateTransform{

	public void init(String data) throws NumberFormatException {
		final String[] fields = data.split( "\\s+" );
        if ( fields.length == 9 )
        {
            final float m00 = Float.parseFloat( fields[ 0 ] );
            final float m01 = Float.parseFloat( fields[ 1 ] );
            final float m02 = Float.parseFloat( fields[ 2 ] );
            final float m10 = Float.parseFloat( fields[ 3 ] );
            final float m11 = Float.parseFloat( fields[ 4 ] );
            final float m12 = Float.parseFloat( fields[ 5 ] );
            final float m20 = Float.parseFloat( fields[ 6 ] );
            final float m21 = Float.parseFloat( fields[ 7 ] );
            final float m22 = Float.parseFloat( fields[ 8 ] );
            set(m00, m01, m02, m10, m11, m12, m20, m21, m22);
        }
        else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	public String toDataString() {
		return  m00 + " " + m01 + " " + m02 + " " +
			m10 + " " + m11 + " " + m12 + " " +
			m20 + " " + m21 + " " + m22 + " ";

    }

	public String toXML(String indent) {
		return indent + "<iict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\" />";
	}
	
	@Override
	public HomographyModel2D copy()
	{
		final HomographyModel2D m = new HomographyModel2D();
	        m.set(m00, m01, m02, m10, m11, m12, m20, m21, m22);
		return m;
	}

}
