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

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;

/**
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1b
 */
public class TransformMesh extends mpicbg.models.TransformMesh
{
	final protected Rectangle boundingBox;
	final public Rectangle getBoundingBox(){ return boundingBox; }

	public TransformMesh(
			final CoordinateTransform t,
			final int numX,
			final double width,
			final double height )
	{
		super( numX, numY( numX, width, height ), width, height );

		double xMin = Double.MAX_VALUE;
		double yMin = Double.MAX_VALUE;

		double xMax = -Double.MAX_VALUE;
		double yMax = -Double.MAX_VALUE;

		final Set< PointMatch > vertices = va.keySet();
		for ( final PointMatch vertex : vertices )
		{
			final double[] w = vertex.getP2().getW();

			t.applyInPlace( w );

			if ( w[ 0 ] < xMin ) xMin = w[ 0 ];
			if ( w[ 0 ] > xMax ) xMax = w[ 0 ];
			if ( w[ 1 ] < yMin ) yMin = w[ 1 ];
			if ( w[ 1 ] > yMax ) yMax = w[ 1 ];
		}

		for ( final PointMatch vertex : vertices )
		{
			final int tx = ( int )xMin;
			final int ty = ( int )yMin;
			final double[] w = vertex.getP2().getW();
			w[ 0 ] -= tx;
			w[ 1 ] -= ty;
		}

		updateAffines();

		final double fw = xMax - xMin;
		final double fh = yMax - yMin;

		final int w = ( int )fw;
		final int h = ( int )fh;

		boundingBox = new Rectangle( ( int )xMin, ( int )yMin, ( w == fw ? w : w + 1 ), ( h == fh ? h : h + 1 ) );
	}

	/**
	 * Catch non-invertible locations outside of the meshes boundaries and
	 * transfer them with the affine defined by the `closest' affine (the affine
	 * whose summed up control points distances to location are smallest).
	 */
	@Override
	public void applyInverseInPlace( final double[] location ) throws NoninvertibleModelException
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

		final Set< AffineModel2D > s = av.keySet();
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			if ( isInConvexTargetPolygon( pm, location ) )
			{
				ai.applyInverseInPlace( location );
				return;
			}
		}

		/* not in the mesh, find the closest affine */
		double dMin = Double.MAX_VALUE;
		AffineModel2D closestAffine = new AffineModel2D();
		final double x = location[ 0 ];
		final double y = location[ 1 ];
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			double d = 0;
			for ( final PointMatch p : pm )
			{
				final double[] w = p.getP2().getW();
				final double dx = w[ 0 ] - x;
				final double dy = w[ 1 ] - y;
				d += Math.sqrt( dx * dx + dy * dy );
			}
			if ( d < dMin )
			{
				dMin = d;
				closestAffine = ai;
			}
		}
		closestAffine.applyInverseInPlace( location );

		throw new NoninvertibleModelException( "Mesh external location ( " + x + ", " + y + " ) transferred to ( " + location[ 0 ] + ", " + location[ 1 ] + " ) by closest affine." );
	}

	/**
	 * Return the {@link AffineModel2D} used by the triangle enclosing or
	 * closest to the passed target location.
	 *
	 * @param location
	 * @return
	 */
	public AffineModel2D closestSourceAffine( final double[] location )
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

		final Set< AffineModel2D > s = av.keySet();
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			if ( isInSourcePolygon( pm, location ) )
				return ai;
		}

		/* not in the mesh, find the closest affine */
		double dMin = Double.MAX_VALUE;
		AffineModel2D closestAffine = new AffineModel2D();
		final double x = location[ 0 ];
		final double y = location[ 1 ];
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			double d = 0;
			for ( final PointMatch p : pm )
			{
				final double[] l = p.getP1().getL();
				final double dx = l[ 0 ] - x;
				final double dy = l[ 1 ] - y;
				d += Math.sqrt( dx * dx + dy * dy );
			}
			if ( d < dMin )
			{
				dMin = d;
				closestAffine = ai;
			}
		}

		return closestAffine;
	}


	/**
	 * Return the {@link AffineModel2D} used by the triangle enclosing or
	 * closest to the passed target location.
	 *
	 * @param location
	 * @return
	 */
	public AffineModel2D closestTargetAffine( final double[] location )
	{
		assert location.length == 2 : "2d transform meshs can be applied to 2d points only.";

		final Set< AffineModel2D > s = av.keySet();
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			if ( isInConvexTargetPolygon( pm, location ) )
				return ai;
		}

		/* not in the mesh, find the closest affine */
		double dMin = Double.MAX_VALUE;
		AffineModel2D closestAffine = new AffineModel2D();
		final double x = location[ 0 ];
		final double y = location[ 1 ];
		for ( final AffineModel2D ai : s )
		{
			final ArrayList< PointMatch > pm = av.get( ai );
			double d = 0;
			for ( final PointMatch p : pm )
			{
				final double[] w = p.getP2().getW();
				final double dx = w[ 0 ] - x;
				final double dy = w[ 1 ] - y;
				d += Math.sqrt( dx * dx + dy * dy );
			}
			if ( d < dMin )
			{
				dMin = d;
				closestAffine = ai;
			}
		}

		return closestAffine;
	}
}
