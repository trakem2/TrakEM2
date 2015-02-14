/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 *
 */
package mpicbg.trakem2.transform;

public class AffineModel2D extends mpicbg.models.AffineModel2D implements InvertibleCoordinateTransform
{
	private static final long serialVersionUID = 7177056267150312600L;

	@Override
	final public void init( final String data )
	{
		final String[] fields = data.split( "\\s+" );
		if ( fields.length == 6 )
		{
			final double m00 = Double.parseDouble( fields[ 0 ] );
			final double m10 = Double.parseDouble( fields[ 1 ] );
			final double m01 = Double.parseDouble( fields[ 2 ] );
			final double m11 = Double.parseDouble( fields[ 3 ] );
			final double m02 = Double.parseDouble( fields[ 4 ] );
			final double m12 = Double.parseDouble( fields[ 5 ] );
			set( m00, m10, m01, m11, m02, m12 );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	@Override
	final public String toXML( final String indent )
	{
		final StringBuilder xml = new StringBuilder( 128 );
		xml.append( indent )
		   .append( "<iict_transform class=\"" )
		   .append( this.getClass().getCanonicalName() )
		   .append( "\" data=\"" );
		toDataString( xml );
		return xml.append( "\" />" ).toString();
	}

	@Override
	final public String toDataString()
	{
		final StringBuilder data = new StringBuilder();
		toDataString( data );
		return data.toString();
	}

	final private void toDataString( final StringBuilder data )
	{
		data.append( m00 ).append(' ')
		    .append( m10 ).append(' ')
		    .append( m01 ).append(' ')
		    .append( m11 ).append(' ')
		    .append( m02 ).append(' ')
		    .append( m12 );
	}

	@Override
	public AffineModel2D copy()
	{
		final AffineModel2D m = new AffineModel2D();
		m.m00 = m00;
		m.m01 = m01;
		m.m10 = m10;
		m.m11 = m11;

		m.m02 = m02;
		m.m12 = m12;

		m.cost = cost;

		m.invert();

		return m;
	}

	/**
	 * TODO Not yet tested
	 */
	//@Override
	@Override
	public AffineModel2D createInverse()
	{
		final AffineModel2D ict = new AffineModel2D();

		ict.m00 = i00;
		ict.m10 = i10;
		ict.m01 = i01;
		ict.m11 = i11;
		ict.m02 = i02;
		ict.m12 = i12;

		ict.i00 = m00;
		ict.i10 = m10;
		ict.i01 = m01;
		ict.i11 = m11;
		ict.i02 = m02;
		ict.i12 = m12;

		ict.cost = cost;

		return ict;
	}
}
