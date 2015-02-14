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

public class InvertibleCoordinateTransformList< E extends InvertibleCoordinateTransform > extends mpicbg.models.InvertibleCoordinateTransformList< E > implements InvertibleCoordinateTransform
{
	private static final long serialVersionUID = 8609337486676867142L;

	@Override
	public void init( final String data )
	{
		throw new NumberFormatException( "There is no parameter based initialisation for " + this.getClass().getCanonicalName() );
	}

	@Override
	public String toXML( final String indent )
	{
		String s = indent + "<iict_transform_list>";
		for ( final E t : transforms )
			s += "\n" + t.toXML( indent + "\t" );
		return s + "\n" + indent + "</iict_transform_list>";
	}

	@Override
	public String toDataString()
	{
		return "";
	}

	@Override
	public InvertibleCoordinateTransformList< E > copy()
	{
		final InvertibleCoordinateTransformList< E > ctl = new InvertibleCoordinateTransformList< E >();
		for ( final E ct : transforms )
			ctl.add( ( E )ct.copy() );
		return ctl;
	}
}
