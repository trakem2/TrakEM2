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
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

/**
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class MovingLeastSquaresTransform2 extends mpicbg.models.MovingLeastSquaresTransform2 implements CoordinateTransform
{
	@Override
	final public void init( final String data ) throws NumberFormatException
	{
		final String[] fields = data.split( "\\s+" );
		if ( fields.length > 3 )
		{
			final int n = Integer.parseInt( fields[ 1 ] );
			
			if ( ( fields.length - 3 ) % ( 2 * n + 1 ) == 0 )
			{
				final int l = ( fields.length - 3 ) / ( 2 * n + 1 );
				
				if ( n == 2 )
				{
					if ( fields[ 0 ].equals( "translation" ) ) model = new TranslationModel2D();
					else if ( fields[ 0 ].equals( "rigid" ) ) model = new RigidModel2D();
					else if ( fields[ 0 ].equals( "similarity" ) ) model = new SimilarityModel2D();
					else if ( fields[ 0 ].equals( "affine" ) ) model = new AffineModel2D();
					else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				}
				else if ( n == 3 )
				{
					if ( fields[ 0 ].equals( "affine" ) ) model = new AffineModel3D();
					else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				}
				else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
				
				alpha = Float.parseFloat( fields[ 2 ] );
				
				p = new float[ n ][ l ];
				q = new float[ n ][ l ];
				w = new float[ l ];
				
				int i = 2, j = 0;
				while ( i < fields.length - 1 )
				{
					for ( int d = 0; d < n; ++d )
						p[ d ][ j ] = Float.parseFloat( fields[ ++i ] );
					for ( int d = 0; d < n; ++d )
						q[ d ][ j ] = Float.parseFloat( fields[ ++i ] );
					w[ j ] = Float.parseFloat( fields[ ++i ] );
					++j;
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

	private final void toDataString( final StringBuilder data )
	{
		if ( AffineModel2D.class.isInstance( model ) )
			data.append( "affine 2" );
		else if ( TranslationModel2D.class.isInstance( model ) )
			data.append( "translation 2" );
		else if ( RigidModel2D.class.isInstance( model ) )
			data.append( "rigid 2" );
		else if ( SimilarityModel2D.class.isInstance( model ) )
			data.append( "similarity 2" );
		else if ( AffineModel3D.class.isInstance( model ) )
			data.append( "affine 3" );
		else
			data.append( "unknown" );

		data.append(' ').append(alpha);

		final int n = p.length;
		final int l = p[ 0 ].length;
		for ( int i = 0; i < l; ++i )
		{
			for ( int d = 0; d < n; ++d )
				data.append(' ').append( p[ d ][ i ] );
			for ( int d = 0; d < n; ++d )
				data.append(' ').append( q[ d ][ i ] );
			data.append(' ').append( w[ i ] );
		}
	}

	@Override
	final public String toXML( final String indent )
	{
		final StringBuilder xml = new StringBuilder( 80000 );
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
	final public MovingLeastSquaresTransform2 copy()
	{
		final MovingLeastSquaresTransform2 t = new MovingLeastSquaresTransform2();
		t.init( toDataString() );
		return t;
	}

	/**
	 * Multi-threading safe version of the original applyInPlace method.
	 */
	@Override
	public void applyInPlace( final float[] location )
	{
		final float[] ww = new float[ w.length ];
		for ( int i = 0; i < w.length; ++i )
		{
			float s = 0;
			for ( int d = 0; d < location.length; ++d )
			{
				final float dx = p[ d ][ i ] - location[ d ];
				s += dx * dx;
			}
			if ( s <= 0 )
			{
				for ( int d = 0; d < location.length; ++d )
					location[ d ] = q[ d ][ i ];
				return;
			}
			ww[ i ] = w[ i ] * ( float )weigh( s );
		}
		
		try
		{
			synchronized ( model )
			{
				model.fit( p, q, ww );
				model.applyInPlace( location );
			}
		}
		catch ( IllDefinedDataPointsException e ){}
		catch ( NotEnoughDataPointsException e ){}
	}
}
