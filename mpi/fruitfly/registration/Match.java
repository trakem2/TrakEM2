package mpi.fruitfly.registration;

import static mpi.fruitfly.math.General.sq;

import Jama.EigenvalueDecomposition;
import Jama.Matrix;

import java.util.Collection;

public class Match {
	
	// coordinates
	final public float[] p1;
	final public float[] p2;
	
	// weight
	final public float w1;
	final public float w2;
	
	public Match( float[] p1, float[] p2 )
	{
		this.p1 = p1;
		this.p2 = p2;
		w1 = w2 = 1.0f;
	}

	public Match( float[] p1, float[] p2, float w1, float w2 )
	{
		this.p1 = p1;
		this.p2 = p2;
		this.w1 = w1;
		this.w2 = w2;
	}

	/**
	 * estimate the covariance, main components (eigenvalues) and mean values
	 * of the spatial distribution of the matches
	 */
	static public void covariance(
			Collection< Match > matches,
			double[] covariance_P1,		//!< xx_1, xy_1, yy_1
			double[] covariance_P2,		//!< xx_2, xy_2, yy_2
			double[] o_P1,				//!< x0_1, y0_1
			double[] o_P2,				//!< x0_2, y0_2
			double[] eigenvalues_P1,	//!< E_1, e_1
			double[] eigenvalues_P2	,	//!< E_1, e_1
			double[] eigenvector_P1,
			double[] eigenvector_P2
			)
	{
		// estimate the covariance matrix of the matching locations in both
		// images
		//---------------------------------------------------------------------

		// first the mean values
		o_P1[ 0 ] = 0;
		o_P1[ 1 ] = 0;
		o_P2[ 0 ] = 0;
		o_P2[ 1 ] = 0;
		for ( Match m : matches )
		{
			o_P1[ 0 ] += ( double )m.p1[ 0 ];
			o_P1[ 1 ] += ( double )m.p1[ 1 ];
			o_P2[ 0 ] += ( double )m.p2[ 0 ];
			o_P2[ 1 ] += ( double )m.p2[ 1 ];
		}
		o_P1[ 0 ] /= matches.size();
		o_P1[ 1 ] /= matches.size();
		o_P2[ 0 ] /= matches.size();
		o_P2[ 1 ] /= matches.size();

		// now the covariances cxx, cyy and cxy
		covariance_P1[ 0 ] = 0;
		covariance_P1[ 1 ] = 0;
		covariance_P1[ 2 ] = 0;
		covariance_P2[ 0 ] = 0;
		covariance_P2[ 1 ] = 0;
		covariance_P2[ 2 ] = 0;

		for ( Match m : matches )
		{
			double dx1 = m.p1[ 0 ] - o_P1[ 0 ];
			double dy1 = m.p1[ 1 ] - o_P1[ 1 ];
			double dx2 = m.p2[ 0 ] - o_P2[ 0 ];
			double dy2 = m.p2[ 1 ] - o_P2[ 1 ];
			covariance_P1[ 0 ] += sq( dx1 );
			covariance_P1[ 1 ] += dx1 * dy1;
			covariance_P1[ 2 ] += sq( dy1 );
			covariance_P2[ 0 ] += sq( dx2 );
			covariance_P2[ 1 ] += dx2 * dy2;
			covariance_P2[ 2 ] += sq( dy2 );
		}
		covariance_P1[ 0 ] /= matches.size();
		covariance_P1[ 1 ] /= matches.size();
		covariance_P1[ 2 ] /= matches.size();
		covariance_P2[ 0 ] /= matches.size();
		covariance_P2[ 1 ] /= matches.size();
		covariance_P2[ 2 ] /= matches.size();

		// estimate the main components being the eigenvalues of the
		// covariance matrix
		/*
		double sqrt1 = Math.sqrt( sq( covariance_P1[ 0 ] + covariance_P1[ 2 ] ) / 4.0 + covariance_P1[ 1 ] * covariance_P1[ 1 ] + covariance_P1[ 0 ] * covariance_P1[ 2 ] );
		double sqrt2 = Math.sqrt( sq( covariance_P2[ 0 ] + covariance_P2[ 2 ] ) / 4.0 + covariance_P2[ 1 ] * covariance_P2[ 1 ] + covariance_P2[ 0 ] * covariance_P2[ 2 ] );

		double p1 = ( covariance_P1[ 0 ] + covariance_P1[ 2 ] ) / 2.0;
		double p2 = ( covariance_P2[ 0 ] + covariance_P2[ 2 ] ) / 2.0;

		eigenvalues_P1[ 0 ] = p1 + sqrt1;
		eigenvalues_P1[ 1 ] = p1 - sqrt1;
		eigenvalues_P2[ 0 ] = p2 + sqrt2;
		eigenvalues_P2[ 1 ] = p2 - sqrt2;

		if ( eigenvalues_P1[ 0 ] < eigenvalues_P1[ 1 ] )
		{
			double t = eigenvalues_P1[ 0 ];
			eigenvalues_P1[ 0 ] = eigenvalues_P1[ 1 ];
			eigenvalues_P1[ 1 ] = t;
		}
		if ( eigenvalues_P2[ 0 ] < eigenvalues_P2[ 1 ] )
		{
			double t = eigenvalues_P2[ 0 ];
			eigenvalues_P2[ 0 ] = eigenvalues_P2[ 1 ];
			eigenvalues_P2[ 1 ] = t;
		}
		*/
		EigenvalueDecomposition evd1 =
			new EigenvalueDecomposition(
					new Matrix(
							new double[][]{
									{ covariance_P1[ 0 ], covariance_P1[ 1 ] },
									{ covariance_P1[ 1 ], covariance_P1[ 2 ] } } ) );
		EigenvalueDecomposition evd2 =
			new EigenvalueDecomposition(
					new Matrix(
							new double[][]{
									{ covariance_P2[ 0 ], covariance_P2[ 1 ] },
									{ covariance_P2[ 1 ], covariance_P2[ 2 ] } } ) );
		double[][] e = evd1.getD().getArray();
		eigenvalues_P1[ 0 ] = e[ 0 ][ 0 ];
		eigenvalues_P1[ 1 ] = e[ 1 ][ 1 ];
		e = evd2.getD().getArray();
		eigenvalues_P2[ 0 ] = e[ 0 ][ 0 ];
		eigenvalues_P2[ 1 ] = e[ 1 ][ 1 ];
		double[][] v = evd1.getV().getArray();
		eigenvector_P1[ 0 ] = v[ 0 ][ 0 ];
		eigenvector_P1[ 1 ] = v[ 1 ][ 0 ];
		eigenvector_P1[ 2 ] = v[ 0 ][ 1 ];
		eigenvector_P1[ 3 ] = v[ 1 ][ 1 ];
		v = evd2.getV().getArray();
		eigenvector_P2[ 0 ] = v[ 0 ][ 0 ];
		eigenvector_P2[ 1 ] = v[ 1 ][ 0 ];
		eigenvector_P2[ 2 ] = v[ 0 ][ 1 ];
		eigenvector_P2[ 3 ] = v[ 1 ][ 1 ];

	}
}