/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
 * @author Stephan Saalfeld saalfelds@janelia.hhmi.org
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
