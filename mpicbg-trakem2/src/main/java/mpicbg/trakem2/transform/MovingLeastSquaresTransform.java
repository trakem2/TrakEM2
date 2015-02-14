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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

/**
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class MovingLeastSquaresTransform extends mpicbg.models.MovingLeastSquaresTransform implements CoordinateTransform
{
	private static final long serialVersionUID = 969438449108820224L;

	@Override
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

				alpha = Double.parseDouble( fields[ 2 ] );

				int i = 2;
				while ( i < fields.length - 1 )
				{
					final double[] p1 = new double[ d ];
					for ( int k = 0; k < d; ++k )
							p1[ k ] = Double.parseDouble( fields[ ++i ] );
					final double[] p2 = new double[ d ];
					for ( int k = 0; k < d; ++k )
							p2[ k ] = Double.parseDouble( fields[ ++i ] );
					final double weight = Double.parseDouble( fields[ ++i ] );
					final PointMatch m = new PointMatch( new Point( p1 ), new Point( p2 ), weight );
					matches.add( m );
				}
			}
			else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );

	}

	@Override
	public String toDataString()
	{
		final StringBuilder data = new StringBuilder();
		toDataString( data );
		return data.toString();
	}

	static private final Comparator< PointMatch > SORTER = new Comparator< PointMatch >() {
		@Override
		public final int compare(final PointMatch o1, final PointMatch o2) {
			final double[] p1 = o1.getP1().getW();
			final double[] p2 = o1.getP2().getW();
			final double dx = p1[0] - p2[0];
			if ( dx < 0) return -1;
			if ( 0 == dx)
			{
				final double dy = p1[1] - p2[1];
				if ( dy < 0 ) return -1;
				if ( 0 == dy ) return 0;
				return 1;
			}
			return 1;
		}
	};

	private final void toDataString( final StringBuilder data )
	{
		if ( AffineModel2D.class.isInstance( model ) ) data.append("affine 2");
		else if ( TranslationModel2D.class.isInstance( model ) ) data.append("translation 2");
		else if ( RigidModel2D.class.isInstance( model ) ) data.append("rigid 2");
		else if ( SimilarityModel2D.class.isInstance( model ) ) data.append("similarity 2");
		else if ( AffineModel3D.class.isInstance( model ) ) data.append("affine 3");
		else data.append("unknown");

		data.append(' ').append(alpha);

		// Sort matches, so that they are always written the same way
		// Will help lots git and .zip to reduce XML file size

		final ArrayList< PointMatch > pms = new ArrayList< PointMatch >( matches );
		Collections.sort( pms, SORTER );

		for ( final PointMatch m : pms )
		{
			final double[] p1 = m.getP1().getL();
			final double[] p2 = m.getP2().getW();
			for ( int k = 0; k < p1.length; ++k )
				data.append(' ').append(p1[ k ]);
			for ( int k = 0; k < p2.length; ++k )
				data.append(' ').append(p2[ k ]);
			data.append(' ').append(m.getWeight());
		}
	}

	@Override
	final public String toXML( final String indent )
	{
		final StringBuilder xml = new StringBuilder( 128 );
		xml.append( indent )
		   .append( "<ict_transform class=\"" )
		   .append( this.getClass().getCanonicalName() )
		   .append( "\" data=\"" );
		toDataString( xml );
		return xml.append( "\"/>" ).toString();
	}

	@Override
	/**
	 * TODO Make this more efficient
	 */
	final public MovingLeastSquaresTransform copy()
	{
		final MovingLeastSquaresTransform t = new MovingLeastSquaresTransform();
		t.init( toDataString() );
		return t;
	}

	@Override
	final public void applyInPlace( final double[] location )
	{
		final Collection< PointMatch > weightedMatches = new ArrayList< PointMatch >();
		for ( final PointMatch m : matches )
		{
			final double[] l = m.getP1().getL();

			double s = 0;
			for ( int i = 0; i < location.length; ++i )
			{
				final double dx = l[ i ] - location[ i ];
				s += dx * dx;
			}
			if ( s <= 0 )
			{
				final double[] w = m.getP2().getW();
				for ( int i = 0; i < location.length; ++i )
					location[ i ] = w[ i ];
				return;
			}
			final double weight = m.getWeight() * weigh( s );
			final PointMatch mw = new PointMatch( m.getP1(), m.getP2(), weight );
			weightedMatches.add( mw );
		}

		try
		{
			synchronized ( model )
			{
				model.fit( weightedMatches );
				model.applyInPlace( location );
			}
		}
		catch ( final IllDefinedDataPointsException e ){}
		catch ( final NotEnoughDataPointsException e ){}
	}
}
