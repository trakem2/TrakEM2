/**
 * 
 */
package org.janelia.intensity;

import java.util.Collection;
import java.util.List;

import mpicbg.models.PointMatch;

/**
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 *
 */
public interface PointMatchFilter
{
	public void filter(
			final List< PointMatch > candidates,
			final Collection< PointMatch > inliers );
}
