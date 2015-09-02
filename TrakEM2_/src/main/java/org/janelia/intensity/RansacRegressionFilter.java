/**
 * 
 */
package org.janelia.intensity;

import java.util.Collection;
import java.util.List;

import mpicbg.models.AffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;

/**
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 *
 */
public class RansacRegressionFilter implements PointMatchFilter
{
	final protected Model< ? > model = new AffineModel1D();
	final protected int iterations = 1000;
	final protected float  maxEpsilon = 0.1f;
	final protected float minInlierRatio = 0.1f;
	final protected int minNumInliers = 10;
	final protected float maxTrust = 3.0f;
	
	@Override
	public void filter( List< PointMatch > candidates, Collection< PointMatch > inliers )
	{
		try
		{
			if (
				!model.filterRansac(
						candidates,
						inliers,
						iterations,
						maxEpsilon,
						minInlierRatio,
						minNumInliers,
						maxTrust ) )
				inliers.clear();
		}
		catch ( Exception e )
		{
			inliers.clear();
		}
	}

}
