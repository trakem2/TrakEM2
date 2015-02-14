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
package lenscorrection;

import java.util.Collection;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AbstractModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * A wrpper for {@link NonLinearTransform} and the {@link AbstractAffineModel2D}
 * to which it is regularized.
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class PolynomialModel2D extends AbstractModel< PolynomialModel2D >
{
	private NonLinearTransform nlt = new NonLinearTransform();
	private AbstractAffineModel2D< ? > affine = null;
	private float lambda = 0f;


	public AbstractAffineModel2D< ? > getAffine(){ return affine; }
	public void setAffine( final AbstractAffineModel2D< ? > affine ){ this.affine = affine; }
	public void setAffine( final Class< ? extends AbstractAffineModel2D< ? > > affineClass ) throws Exception
	{
		affine = affineClass.newInstance();
	}

	public int getOrder(){ return nlt.getDimension(); }
	public void setOrder( final int order ){ nlt.setDimension( order ); }

	public float getLambda(){ return lambda; }
	public void setLambda( final float lambda ){ this.lambda = lambda; }


	@Override
	public PolynomialModel2D copy()
	{
		final PolynomialModel2D clone = new PolynomialModel2D();
		clone.nlt = nlt.copy();
		clone.affine = affine.copy();
		clone.lambda = lambda;
		return clone;
	}

	@Override
	public < P extends PointMatch >void fit( final Collection< P > pointMatches ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		if ( pointMatches.size() < getMinNumMatches() )
			throw new NotEnoughDataPointsException( pointMatches.size() + " data points are not enough to estimate a 2d polynomial of order " + nlt.getDimension() + ", at least " + getMinNumMatches() + " data points required." );

		affine.fit( pointMatches );

		final double h1[][] = new double[ pointMatches.size() ][ 2 ];
	    final double h2[][] = new double[ pointMatches.size() ][ 2 ];

	    int i = 0;
		for ( final P match : pointMatches )
	    {
	    	final double[] tmp1 = match.getP1().getL().clone();
	    	affine.applyInPlace( tmp1 );

	    	final double[] tmp2 = match.getP2().getW();

	    	h1[ i ] = new double[]{ tmp1[ 0 ], tmp1[ 1 ] };
			h2[ i ] = new double[]{ tmp2[ 0 ], tmp2[ 1 ] };

			++i;
	    }

		nlt.fit( h1, h2, lambda );
	}

	@Override
	public int getMinNumMatches()
	{
		return Math.max( affine.getMinNumMatches(), nlt.getMinNumMatches() );
	}

	@Override
	public void set( final PolynomialModel2D m )
	{
		nlt.set( m.nlt );
		affine = m.affine.copy();
		lambda = m.lambda;
	}

	@Override
	public String toString()
	{
		return "affine: " + affine.toString() + "; polynomial: " + nlt.toString();
	}

	@Override
	public double[] apply( final double[] location )
	{
		final double[] l = location.clone();
		applyInPlace( l );
		return l;
	}

	@Override
	public void applyInPlace( final double[] location )
	{
		affine.applyInPlace( location );
		nlt.applyInPlace( location );
	}
}
