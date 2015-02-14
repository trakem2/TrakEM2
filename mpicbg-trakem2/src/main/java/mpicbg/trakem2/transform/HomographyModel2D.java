package mpicbg.trakem2.transform;


public class HomographyModel2D extends mpicbg.models.HomographyModel2D implements InvertibleCoordinateTransform{

	private static final long serialVersionUID = 3098424136915475353L;

	@Override
	public void init(final String data) throws NumberFormatException {
		final String[] fields = data.split( "\\s+" );
        if ( fields.length == 9 )
        {
            final double m00 = Double.parseDouble( fields[ 0 ] );
            final double m01 = Double.parseDouble( fields[ 1 ] );
            final double m02 = Double.parseDouble( fields[ 2 ] );
            final double m10 = Double.parseDouble( fields[ 3 ] );
            final double m11 = Double.parseDouble( fields[ 4 ] );
            final double m12 = Double.parseDouble( fields[ 5 ] );
            final double m20 = Double.parseDouble( fields[ 6 ] );
            final double m21 = Double.parseDouble( fields[ 7 ] );
            final double m22 = Double.parseDouble( fields[ 8 ] );
            set(m00, m01, m02, m10, m11, m12, m20, m21, m22);
        }
        else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	@Override
	public String toDataString() {
		return  m00 + " " + m01 + " " + m02 + " " +
			m10 + " " + m11 + " " + m12 + " " +
			m20 + " " + m21 + " " + m22 + " ";

    }

	@Override
	public String toXML(final String indent) {
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
