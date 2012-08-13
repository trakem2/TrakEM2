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

public class TranslationModel3D extends mpicbg.models.TranslationModel3D implements InvertibleCoordinateTransform
{

	//@Override
	final public void init( final String data )
	{
		final String[] fields = data.split( "\\s+" );
		if ( fields.length == 3 )
		{
			final float tx = Float.parseFloat( fields[ 0 ] );
			final float ty = Float.parseFloat( fields[ 1 ] );
			final float tz = Float.parseFloat( fields[ 2 ] );
			set( tx, ty, tz );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	//@Override
	final public String toXML( final String indent )
	{
		return indent + "<iict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\" />";
	}
	
	//@Override
	final public String toDataString()
	{
		return translation[ 0 ] + " " + translation[ 1 ] + " " + translation[ 2 ];
	}
	
	@Override
	public TranslationModel3D copy()
	{
		final TranslationModel3D m = new TranslationModel3D();
		m.translation[ 0 ] = translation[ 0 ];
		m.translation[ 1 ] = translation[ 1 ];
		m.translation[ 2 ] = translation[ 2 ];
		m.cost = cost;
		return m;
	}
}
