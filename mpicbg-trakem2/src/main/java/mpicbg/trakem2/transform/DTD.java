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

import java.util.HashSet;

public class DTD
{
	private DTD(){}
	
	static public void append(final StringBuilder sb_header, final HashSet< String > hs, final String indent )
	{
		/* non invertible coordinate transforms (ignore the i) */
		if ( !hs.contains( "ict_transform" ) )
		{
			hs.add( "ict_transform" );
			sb_header.append( indent ).append( "<!ELEMENT ict_transform EMPTY>\n" );
			sb_header.append( indent ).append( "<!ATTLIST ict_transform class CDATA #REQUIRED>\n" );
			sb_header.append( indent ).append( "<!ATTLIST ict_transform data CDATA #REQUIRED>\n" );

		}
		/* invertible coordinate transforms (notice the additional i) */
		if ( !hs.contains( "iict_transform" ) )
		{
			hs.add( "iict_transform" );
			sb_header.append( indent ).append( "<!ELEMENT iict_transform EMPTY>\n" );
			sb_header.append( indent ).append( "<!ATTLIST iict_transform class CDATA #REQUIRED>\n" );
			sb_header.append( indent ).append( "<!ATTLIST iict_transform data CDATA #REQUIRED>\n" );

		}
		if ( !hs.contains( "ict_transform_list" ) )
		{
			hs.add( "ict_transform_list" );
			sb_header.append( indent ).append( "<!ELEMENT ict_transform_list (ict_transform|iict_transform)*>\n" );
		}
		if ( !hs.contains( "iict_transform_list" ) )
		{
			hs.add( "iict_transform_list" );
			sb_header.append( indent ).append( "<!ELEMENT iict_transform_list (iict_transform*)>\n" );
		}
	}
}
