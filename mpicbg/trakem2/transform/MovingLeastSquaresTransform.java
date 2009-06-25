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
 */
package mpicbg.trakem2.transform;

import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

/**
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.2b
 */
public class MovingLeastSquaresTransform extends mpicbg.models.MovingLeastSquaresTransform implements CoordinateTransform
{

	final public void init( final String data ) throws NumberFormatException
	{
		matches.clear();
		
		final String[] fields = data.split( "\\s+" );
		if ( fields.length > 3 )
		{
			final int d = Integer.parseInt( fields[ 1 ] );
			
			if ( ( fields.length - 3 ) % ( 2 * d + 1 ) == 0 )
			{
				if ( d == 2 )
				{
					if ( fields[ 0 ].equals( "translation" ) ) model = new TranslationModel2D();
					else if ( fields[ 0 ].equals( "rigid" ) ) model = new RigidModel2D();
					else if ( fields[ 0 ].equals( "similarity" ) ) model = new SimilarityModel2D();
					else if ( fields[ 0 ].equals( "affine" ) ) model = new AffineModel2D();
					else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				}
				else if ( d == 3 )
				{
					if ( fields[ 0 ].equals( "affine" ) ) model = new AffineModel3D();
					else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				}
				else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				
				alpha = Float.parseFloat( fields[ 2 ] );
				
				int i = 2;
				while ( i < fields.length - 1 )
				{
					final float[] p1 = new float[ d ];
					for ( int k = 0; k < d; ++k )
							p1[ k ] = Float.parseFloat( fields[ ++i ] );
					final float[] p2 = new float[ d ];
					for ( int k = 0; k < d; ++k )
							p2[ k ] = Float.parseFloat( fields[ ++i ] );
					final float weight = Float.parseFloat( fields[ ++i ] );
					final PointMatch m = new PointMatch( new Point( p1 ), new Point( p2 ), weight );
					matches.add( m );
				}
			}
			else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );

	}

	public String toDataString()
	{
		String data = "";
		
		if ( TranslationModel2D.class.isInstance( model ) ) data += "translation 2";
		else if ( RigidModel2D.class.isInstance( model ) ) data += "rigid 2";
		else if ( SimilarityModel2D.class.isInstance( model ) ) data += "similarity 2";
		else if ( AffineModel2D.class.isInstance( model ) ) data += "affine 2";
		else if ( AffineModel3D.class.isInstance( model ) ) data += "affine 3";
		else data += "unknown";
		
		data += " " + alpha;
		
		for ( PointMatch m : matches )
		{
			final float[] p1 = m.getP1().getL();
			final float[] p2 = m.getP2().getW();
			for ( int k = 0; k < p1.length; ++k )
				data += " " + p1[ k ];
			for ( int k = 0; k < p2.length; ++k )
				data += " " + p2[ k ];
			data += " " + m.getWeight();
		}
		return data;
	}

	final public String toXML( final String indent )
	{
		return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\"/>";
	}
	
	@Override
	/**
	 * TODO Make this more efficient
	 */
	final public MovingLeastSquaresTransform clone()
	{
		final MovingLeastSquaresTransform t = new MovingLeastSquaresTransform();
		t.init( toDataString() );
		return t;
	}
}
