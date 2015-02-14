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

public class AffineModel3D extends mpicbg.models.AffineModel3D implements InvertibleCoordinateTransform
{
	private static final long serialVersionUID = 4661684107044575726L;

	@Override
	final public void init( final String data )
	{
		final String[] fields = data.split( "\\s+" );
		if ( fields.length == 12 )
		{
			final double m00 = Double.parseDouble( fields[ 0 ] );
			final double m01 = Double.parseDouble( fields[ 1 ] );
			final double m02 = Double.parseDouble( fields[ 2 ] );
			final double m03 = Double.parseDouble( fields[ 3 ] );

			final double m10 = Double.parseDouble( fields[ 4 ] );
			final double m11 = Double.parseDouble( fields[ 5 ] );
			final double m12 = Double.parseDouble( fields[ 6 ] );
			final double m13 = Double.parseDouble( fields[ 7 ] );

			final double m20 = Double.parseDouble( fields[ 8 ] );
			final double m21 = Double.parseDouble( fields[ 9 ] );
			final double m22 = Double.parseDouble( fields[ 10 ] );
			final double m23 = Double.parseDouble( fields[ 11 ] );

			set(
					m00, m01, m02, m03,
					m10, m11, m12, m13,
					m20, m21, m22, m23 );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	//@Override
	@Override
	final public String toXML( final String indent )
	{
		return indent + "<iict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\" />";
	}

	//@Override
	@Override
	final public String toDataString()
	{
		return
				m00 + " " + m01 + " " + m02 + " " + m03 + " " +
				m10 + " " + m11 + " " + m12 + " " + m13 + " " +
				m20 + " " + m21 + " " + m22 + " " + m23;
	}

	@Override
	public AffineModel3D copy()
	{
		final AffineModel3D m = new AffineModel3D();
		m.m00 = m00;
		m.m10 = m10;
		m.m20 = m20;
		m.m01 = m01;
		m.m11 = m11;
		m.m21 = m21;
		m.m02 = m02;
		m.m12 = m12;
		m.m22 = m22;
		m.m03 = m03;
		m.m13 = m13;
		m.m23 = m23;

		m.cost = cost;

		m.invert();

		return m;
	}

	@Override
	public AffineModel3D createInverse()
	{
		final AffineModel3D ict = new AffineModel3D();

		ict.m00 = i00;
		ict.m10 = i10;
		ict.m20 = i20;
		ict.m01 = i01;
		ict.m11 = i11;
		ict.m21 = i21;
		ict.m02 = i02;
		ict.m12 = i12;
		ict.m22 = i22;
		ict.m03 = i03;
		ict.m13 = i13;
		ict.m23 = i23;

		ict.i00 = m00;
		ict.i10 = m10;
		ict.i20 = m20;
		ict.i01 = m01;
		ict.i11 = m11;
		ict.i21 = m21;
		ict.i02 = m02;
		ict.i12 = m12;
		ict.i22 = m22;
		ict.i03 = m03;
		ict.i13 = m13;
		ict.i23 = m23;

		ict.cost = cost;

		return ict;
	}
}
