/**
 *
 */
package org.janelia.intensity;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import mpicbg.models.AffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

/**
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 *
 */
public class RansacRegressionReduceFilter implements PointMatchFilter
{
	final protected Model< ? > model = new AffineModel1D();
	final protected int iterations = 1000;
	final protected double  maxEpsilon = 0.1;
	final protected double minInlierRatio = 0.1;
	final protected int minNumInliers = 10;
	final protected double maxTrust = 3.0;

	final static protected double[] minMax( final Iterable< PointMatch > matches )
	{
		final Iterator< PointMatch > iter = matches.iterator();
		PointMatch m = iter.next();
		double min = m.getP1().getL()[ 0 ], max = min;
		while ( iter.hasNext() )
		{
			m = iter.next();
			final double x = m.getP1().getL()[ 0 ];
			if ( x < min )
				min = x;
			else if ( x > max )
				max = x;
		}
		return new double[]{ min, max };
	}

	@Override
	public void filter( final List< PointMatch > candidates, final Collection< PointMatch > inliers )
	{
		try
		{
			if (
					model.filterRansac(
							candidates,
							inliers,
							iterations,
							maxEpsilon,
							minInlierRatio,
							minNumInliers,
							maxTrust ) )
			{
				model.fit( inliers );


				final double[] minMax = minMax( inliers );

				inliers.clear();

				final Point p1 = new Point( new double[]{ minMax[ 0 ] } );
				final Point p2 = new Point( new double[]{ minMax[ 1 ] } );
				p1.apply( model );
				p2.apply( model );
				inliers.add( new PointMatch( p1, new Point( p1.getW().clone() ) ) );
				inliers.add( new PointMatch( p2, new Point( p2.getW().clone() ) ) );
			}
			else
					inliers.clear();
		}
		catch ( final Exception e )
		{
			inliers.clear();
		}
	}

}
