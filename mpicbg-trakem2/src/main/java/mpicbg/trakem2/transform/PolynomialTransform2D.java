package mpicbg.trakem2.transform;

/**
 * <em>n</em>-th order 2d polynomial transform.
 *
 * The number of coefficients implicitly specifies the order of the
 * {@link PolynomialTransform2D} which is set to the highest order that is fully
 * specified by the provided coefficients. The coefficients are interpreted in
 * the order specified at
 *
 * http://bishopw.loni.ucla.edu/AIR5/2Dnonlinear.html#polylist
 *
 * , first for x', then for y'. It is thus not possible to omit higher order
 * coefficients assuming that they would become 0.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class PolynomialTransform2D extends mpicbg.models.PolynomialTransform2D implements CoordinateTransform
{
	private static final long serialVersionUID = -3905373956331925743L;

	@Override
	public void init( final String data ) throws NumberFormatException
	{

		final String[] fields = data.split( "\\s+" );
		final double[] coefficients = new double[ fields.length ];

		for ( int i = 0; i < coefficients.length; ++i )
			coefficients[ i ] = Double.parseDouble( fields[ i ] );

		set( coefficients );
	}

	@Override
	public String toXML( final String indent )
	{
		final StringBuilder xml = new StringBuilder();
		xml.append( indent ).append( "<ict_transform class=\"" ).append( this.getClass().getCanonicalName() ).append( "\" data=\"" );
		toDataString( xml );
		return xml.append( "\"/>" ).toString();
	}

	@Override
	public String toDataString()
	{
		final StringBuilder data = new StringBuilder();
		toDataString( data );
		return data.toString();
	}

	@Override
	public PolynomialTransform2D copy()
	{
		final PolynomialTransform2D copy = new PolynomialTransform2D();
		copy.set( a.clone() );
		return copy;
	}

	private final void toDataString( final StringBuilder data )
	{
		data.append( a[ 0 ] );
		for ( int i = 1; i < a.length; ++i )
			data.append( ' ' ).append( a[ i ] );
	}
}
