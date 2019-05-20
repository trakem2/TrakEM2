package mpicbg.trakem2.align;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.imagefeatures.Feature;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;

public class FeaturesUtil {

	/**
	 * Identify corresponding features. Similar to FeatureTransform.matchFeatures
	 * but uses a KDTree for a radius-search of within-radius features for comparisons.
	 * When the radius is small, the problem then simplifies from N^2 to N*M,
	 * where M<<N.
	 * 
	 * Feature locations and the radius are all in world units.
	 *
	 * @param fs1 feature collection from set 1
	 * @param fs2 feature collection from set 2
	 * @param matches collects the matching coordinates
	 * @param rod Ratio of distances (closest/next closest match)
	 * @param radius Maximum distance for another feature to be considered
	 */
	static public void matchFeatures(
			final Collection< Feature > fs1,
			final Collection< Feature > fs2,
			final List< PointMatch > matches,
			final float rod,
			final double radius )
	{
		final KDTree< Feature > kdtree = new KDTree< Feature >(
				fs2 instanceof List ? ( List< Feature > )fs2 : new ArrayList< Feature >( fs2 ),
				fs2.stream().map( (f2) -> RealPoint.wrap( f2.location ) ).collect(Collectors.toList())
				);
		final RadiusNeighborSearchOnKDTree< Feature> search = new RadiusNeighborSearchOnKDTree<>( kdtree );
		
		for ( final Feature f1 : fs1 )
		{
			Feature best = null;
			double best_d = Double.MAX_VALUE;
			double second_best_d = Double.MAX_VALUE;

			search.search( RealPoint.wrap( f1.location ), radius, false );
			for ( int i = search.numNeighbors() -1; i > -1; --i )
			{
				final Feature f2 = search.getSampler( i ).get();
				final double d = f1.descriptorDistance( f2 );
				if ( d < best_d )
				{
					second_best_d = best_d;
					best_d = d;
					best = f2;
				}
				else if ( d < second_best_d )
					second_best_d = d;
			}
			if ( best != null && second_best_d < Double.MAX_VALUE && best_d / second_best_d < rod )
				matches.add(
						new PointMatch(
								new Point(
										new double[] { f1.location[ 0 ], f1.location[ 1 ] } ),
								new Point(
										new double[] { best.location[ 0 ], best.location[ 1 ] } ) ) );
		}

		// now remove ambiguous matches
		for ( int i = 0; i < matches.size(); )
		{
			boolean amb = false;
			final PointMatch m = matches.get( i );
			final double[] m_p2 = m.getP2().getL();
			for ( int j = i + 1; j < matches.size(); )
			{
				final PointMatch n = matches.get( j );
				final double[] n_p2 = n.getP2().getL();
				if ( m_p2[ 0 ] == n_p2[ 0 ] && m_p2[ 1 ] == n_p2[ 1 ] )
				{
					amb = true;
					matches.remove( j );
				}
				else ++j;
			}
			if ( amb )
				matches.remove( i );
			else ++i;
		}
	}
}
