package mpi.fruitfly.registration;

import ij.IJ;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.awt.geom.AffineTransform;

public class TModel2D extends Model {

	static final public int MIN_SET_SIZE = 1;

	final private AffineTransform affine = new AffineTransform();
	public AffineTransform getAffine() { return affine; }
	
	@Override
	public float[] apply( float[] point )
	{
		float[] transformed = new float[ 2 ];
		affine.transform( point, 0, transformed, 0, 1 );
		return transformed;
	}
	
	@Override
	public void applyInPlace( float[] point )
	{
		affine.transform( point, 0, point, 0, 1 );
	}


	@Override
	public boolean fit( PointMatch[] min_matches )
	{
		PointMatch m1 = min_matches[ 0 ];
		
		float[] m1_p1 = m1.getP1().getL(); 
		float[] m1_p2 = m1.getP2().getL(); 
		
		float tx = m1_p1[ 0 ] - m1_p2[ 0 ];
		float ty = m1_p1[ 1 ] - m1_p2[ 1 ];
		
		affine.setToIdentity();
		affine.translate( tx, ty );

		return true;
	}

	@Override
	public String toString()
	{
		return ( "[3,3](" + affine + ") " + error );
	}

	public void minimize( Collection< PointMatch > matches )
	{
		// center of mass:
		float xo1 = 0, yo1 = 0;
		float xo2 = 0, yo2 = 0;
		int length = matches.size();
		
		for ( PointMatch m : matches )
		{
			float[] m_p1 = m.getP1().getL(); 
			float[] m_p2 = m.getP2().getL(); 
			
			xo1 += m_p1[ 0 ];
			yo1 += m_p1[ 1 ];
			xo2 += m_p2[ 0 ];
			yo2 += m_p2[ 1 ];
		}
		xo1 /= length;
		yo1 /= length;
		xo2 /= length;
		yo2 /= length;

		float dx = xo2 - xo1;
		float dy = yo2 - yo1;
		
		affine.setToIdentity();
		affine.translate( dx, dy );
	}
	
	/**
	 * change the model a bit
	 * 
	 * estimates the necessary amount of shaking for each single dimensional
	 * distance in the set of matches
	 * 
	 * @param matches point matches
	 * @param scale gives a multiplicative factor to each dimensional distance (scales the amount of shaking)
	 * @param center local pivot point for centered shakes (e.g. rotation)
	 */
	final public void shake(
			Collection< PointMatch > matches,
			float scale,
			float[] center )
	{
		double xd = 0.0;
		double yd = 0.0;
		
		int num_matches = matches.size();
		if ( num_matches > 0 )
		{
			for ( PointMatch m : matches )
			{
				float[] m_p1 = m.getP1().getW(); 
				float[] m_p2 = m.getP2().getW(); 
				
				xd += Math.abs( m_p1[ 0 ] - m_p2[ 0 ] );;
				yd += Math.abs( m_p1[ 1 ] - m_p2[ 1 ] );;
			}
			xd /= matches.size();
			yd /= matches.size();			
		}
		
		affine.translate(
				rnd.nextGaussian() * ( float )xd * scale,
				rnd.nextGaussian() * ( float )yd );
	}

	/**
	 * estimate the transformation model for a set of feature correspondences
	 * containing a high number of outliers using RANSAC
	 */
	static public TModel2D estimateModel(
			List< PointMatch > candidates,
			Collection< PointMatch > inliers,
			int iterations,
			float epsilon,
			float min_inliers )
	{
		inliers.clear();
		
		if ( candidates.size() < MIN_SET_SIZE )
		{
			System.err.println( candidates.size() + " correspondences are not enough to estimate a model, at least " + MIN_SET_SIZE + " correspondences required." );
			return null;
		}
		
		TModel2D model = new TModel2D();		//!< the final model to be estimated
		
		int i = 0;
		while ( i < iterations )
		{
			PointMatch[] min_matches = new PointMatch[ MIN_SET_SIZE ];
			
			min_matches[ 0 ] = candidates.get( ( int )( rnd.nextDouble() * candidates.size() ) );

			TModel2D m = new TModel2D();
			final ArrayList< PointMatch > temp_inliers = new ArrayList< PointMatch >();
			m.fit( min_matches );
			int num_inliers = 0;
			boolean is_good = m.test( candidates, temp_inliers, epsilon, min_inliers );
			while ( is_good && num_inliers < temp_inliers.size() )
			{
				num_inliers = temp_inliers.size();
				m.minimize( temp_inliers );
				is_good = m.test( candidates, temp_inliers, epsilon, min_inliers );
			}
			if (
					is_good &&
					m.betterThan( model ) &&
					temp_inliers.size() >= 3 * MIN_SET_SIZE ) // now at least 3 matches required
			{
				model = m.clone();
				inliers.clear();
				inliers.addAll( temp_inliers );
			}
			++i;
		}
		if ( inliers.size() == 0 )
			return null;

		return model;
	}
	
	/**
	 * estimate the transformation model for a set of feature correspondences
	 * containing a high number of outliers using RANSAC
	 * 
	 * increase the error as long as not more inliers occur 
	 */
	static public TModel2D estimateBestModel(
			List< PointMatch > candidates,
			Collection< PointMatch > inliers,
			float min_epsilon,
			float max_epsilon,
			float min_inlier_ratio )
	{
		inliers.clear();
		TModel2D model = null;
		float epsilon = 0.0f;
		if ( candidates.size() > MIN_SET_SIZE )
		{
			int highest_num_inliers = 0;
			int convergence_count = 0;
			TModel2D m = null;
			do
			{
				final ArrayList< PointMatch > temp_inliers = new ArrayList< PointMatch >();
				epsilon += min_epsilon;
				// 1000 iterations lead to a probability of < 0.01% that only bad data values were found
				m = estimateModel(
						candidates,					//!< point correspondence candidates
						temp_inliers,
						1000,						//!< iterations
						epsilon,					//!< maximal alignment error for a good point pair when fitting the model
						min_inlier_ratio );			//!< minimal partition (of 1.0) of inliers
						
				if ( m != null )
				{
					int num_inliers = temp_inliers.size();
					if ( num_inliers <= highest_num_inliers )
					{
						++convergence_count;
					}
					else
					{
						model = m.clone();
						inliers.clear();
						inliers.addAll( temp_inliers );
						convergence_count = 0;
						highest_num_inliers = num_inliers;
					}
				}
			}
			while ( ( m == null || convergence_count < 4 ) && epsilon < max_epsilon );
		}
		if ( model == null )
		{
			IJ.log( "No sufficient model found, keeping original transformation." );
		}
		else
		{
			IJ.log( "Model with epsilon <= " + epsilon + " for " + inliers.size() + " inliers found." );
			IJ.log( "  Affine transform: " + model.getAffine().toString() );
		}
				
		return model;
	}

	public TModel2D clone()
	{
		TModel2D tm = new TModel2D();
		tm.affine.setTransform( affine );
		tm.error = error;
		return tm;
	}
	
	public TRModel2D toTRModel2D()
	{
		TRModel2D trm = new TRModel2D();
		trm.getAffine().setTransform( affine );
		trm.error = error;
		return trm;
	}
}
