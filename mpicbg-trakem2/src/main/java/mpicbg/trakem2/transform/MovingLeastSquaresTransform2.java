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
	private static final long serialVersionUID = 4878940232249133983L;

	public float[][] getP() {
        return p;
    }

    public float[][] getQ() {
        return q;
    }

    public float[] getWeight() {
        return w;
    }

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


	public void init2( final String s ) throws Exception {
		// WARNING: assumes all whitespace is single
		final int len = s.length();
		int i = 0;
		// Advance to the first white space
		while (' ' != s.charAt(++i)) {}
		// Interpret model by the last letter of the name
		final char modelLastChar = s.charAt(i - 1);
		// Determine dimension 2 or 3
		final int n = (s.charAt(i + 1)) - 48;

		switch (n) {
			case 3:
				model = new AffineModel3D();
				break;
			case 2:
				switch (modelLastChar) {
					case 'n': // translation
						model = new TranslationModel2D();
						break;
					case 'd': // rigid
						model = new RigidModel2D();
						break;
					case 'y': // similarity
						model = new SimilarityModel2D();
						break;
					case 'e': // affine
						model = new AffineModel2D();
						break;
					default:
						throw new Exception("Unknown model " + s.substring(0, i));
				}
				break;
			default:
				throw new NumberFormatException("Unsupported model dimensions: " + n + " for " + this.getClass().getCanonicalName());
		}

		// 'i' is at whitespace before n
		// Move i to whitespace before alpha
		i += 2;
		// Mark last char before whitespace
		int cut = i - 1;
		// Find white space after alpha
		while (' ' != s.charAt(++i)) {} // 'i' ends up at the whitespace after alpha
		// Parse alpha
		float[] f = new float[1];
		parse(s, cut, i-1, f, 0);
		this.alpha = f[0];

		// Count numbers by counting one whitespace before each number
		int nVals = 0;
		for (int k=i; k<len; ++k) {
			if (' ' == s.charAt(k)) ++nVals;
		}

		// The size of a unit of numbers
		final int cell = n + n + 1;

		// Detect inconsistency:
		if ( 0 != nVals % cell ) {
			throw new NumberFormatException("Inappropriate parameters for " + this.getClass().getCanonicalName());
		}

		// Create arrays
		this.p = new float[ n ][ nVals / cell ];
		this.q = new float[ n ][ this.p[0].length ];
		this.w = new float[ this.p[0].length ];

		// Mark the whitespace char before the first number
		cut = i - 1;
		// Start parsing from the end
		i = len -1;
		int count = 0;

		if (2 == n) {
			while (i > cut) {
				// Determine which array from {p,q,w} and which position in the array, using n and count:
				switch (count % cell) { // n for dimensions, +1 for the weight
					case 0: f = this.w; break;
					case 1: f = this.q[1]; break;
					case 2: f = this.q[0]; break;
					case 3: f = this.p[1]; break;
					case 4: f = this.p[0]; break;
				}
				i =  parse(s, cut, i, f, this.w.length - (count / cell) - 1);
				++count;
			}
		} else {
			while (i > cut) {
				// Determine which array from {p,q,w} and which position in the array, using n and count:
				switch (count % (n + n + 1)) { // n for dimensions, +1 for the weight
					case 0: f = this.w; break;
					case 1: f = this.q[2]; break;
					case 2: f = this.q[1]; break;
					case 3: f = this.q[0]; break;
					case 4: f = this.p[2]; break;
					case 5: f = this.p[1]; break;
					case 6: f = this.p[0]; break;
				}
				i =  parse(s, cut, i, f, this.w.length - (count / cell) - 1);
				++count;
			}
		}
	}

	/**
	 * Suprinsingly faster than {@link #parse1(String, int, int, float[], int)} despite the creation of new {@link String}
	 * via the {@link String#substring(int, int)} method (which doesn't duplicate the internal {@code char[]}).
	 *
	 * @param s The {@link String} with all the data.
	 * @param cut One less than the lowest index to look into, must correspond to one char before whitespace in {@param s}.
	 * @param i The index, larger than {@param cut}, where to start back-parsing the number from, and which should NOT be a whitespace.
	 * @param f The array to set the parsed float at {@param i}.
	 * @param i The index in {@param f} to set the parsed float at.
	 * @return The next cut.
	 */
	static private final int parse(final String s, final int cut, int i, final float[] f, final int k) {
		final int last = i + 1;
		while (i > cut) {
			if (' ' == s.charAt(i)) {
				f[k] = Float.parseFloat(s.substring(i+1, last));
				return i - 1; // skip the whitespace
			}
			--i;
		}
		// Signal error
		return Integer.MIN_VALUE;
	}

	/**
	 * Admittedly convoluted and potentially non-compliant way of parsing a float, but works even for {@link Float#MIN_VALUE},
	 * {@link Float#MIN_VALUE} +1, {@link Float#MAX_VALUE} and {@link Float#MAX_VALUE} -1, and
	 * is about 4-7 times faster than {@link Float#parseFloat(String)} by, in the first place, not creating
	 * any temporary {@link String} instances and parsing the {@code char[]} array directly.
	 *
	 * The same could be done by reading 1234.5678 as 1.2345678E3 and then using {@link Float#intBitsToFloat(int)}
	 * to properly create a {@code float} number from sign, mantissa and exponent, all bit-shifted into an int.
	 *
	 * @param s The {@link String} with all the data.
	 * @param cut One less than the lowest index to look into, must correspond to one char before whitespace in {@param s}.
	 * @param i The index, larger than {@param cut}, where to start back-parsing the number from, and which should NOT be a whitespace.
	 * @return The next cut.
	 */
	static private final int parse1(final String s, final int cut, int i, final float[] f, final int k) {
		int pos = 1;
		int numI = 0;
		float numF = 0;
		int sign = 1;
		int exp = 0;
		while (i > cut) {
			final char c = s.charAt(i);
			// Parse one number
			switch (c) {
				case ' ':
					// Finish number: add the non-decimal part and the sign
					f[k] = (float)((numI + numF) * sign * Math.pow(10, exp));
					return i - 1; // skip the whitespace
				case '-':
					sign = -1;
					break;
				case '.':
					// Divide by position to put numI after the comma
					numF = numI;
					numF /= pos;
					// Reset to start accumulating the non-decimal part in numI
					numI = 0;
					pos = 1;
					break;
				case 'E':
					// The integer and sign read so far are the exponent
					exp = numI * sign;
					// Reset:
					numI = 0;
					sign = 1;
					break;
				default:
					numI += ((c) -48) * pos; // digit zero is char with int value 48
					pos *= 10;
					break;
			}
			--i;
		}
		// Signal error
		return Integer.MIN_VALUE;
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
	final public MovingLeastSquaresTransform2 copy()
	{
		/*
		final MovingLeastSquaresTransform2 t = new MovingLeastSquaresTransform2();
		t.init( toDataString() );
		return t;
		*/

		final MovingLeastSquaresTransform2 t = new MovingLeastSquaresTransform2();
		t.model = this.model.copy();
		t.alpha = this.alpha;
		// Copy p, q, w
		t.p = new float[this.p.length][this.p[0].length];
		//
		for (int i=0; i<this.p.length; ++i)
			for (int k=0; k<this.p[0].length; ++k)
				t.p[i][k] = this.p[i][k];
		//
		t.q = new float[this.q.length][this.q[0].length];
		//
		for (int i=0; i<this.q.length; ++i)
			for (int k=0; k<this.q[0].length; ++k)
				t.q[i][k] = this.q[i][k];
		//
		t.w = new float[this.w.length];
		//
		for (int i=0; i<this.w.length; ++i)
			t.w[i] = this.w[i];

		return t;
	}

	/**
	 * Multi-threading safe version of the original applyInPlace method.
	 */
	@Override
	public void applyInPlace( final double[] location )
	{
		final float[] ww = new float[ w.length ];
		for ( int i = 0; i < w.length; ++i )
		{
			double s = 0;
			for ( int d = 0; d < location.length; ++d )
			{
				final double dx = p[ d ][ i ] - location[ d ];
				s += dx * dx;
			}
			if ( s <= 0 )
			{
				for ( int d = 0; d < location.length; ++d )
					location[ d ] = q[ d ][ i ];
				return;
			}
			ww[ i ] = ( float )( w[ i ] * weigh( s ) );
		}

		try
		{
			synchronized ( model )
			{
				model.fit( p, q, ww );
				model.applyInPlace( location );
			}
		}
		catch ( final IllDefinedDataPointsException e ){}
		catch ( final NotEnoughDataPointsException e ){}
	}
}
