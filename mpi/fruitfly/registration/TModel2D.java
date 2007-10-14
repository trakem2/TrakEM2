package mpi.fruitfly.registration;

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
	public boolean fit( Match[] matches )
	{
		Match m1 = matches[ 0 ];
		
		float tx = m1.p1[ 0 ] - m1.p2[ 0 ];
		float ty = m1.p1[ 1 ] - m1.p2[ 1 ];
		
		affine.setToIdentity();
		affine.translate( tx, ty );

		return true;
	}

	@Override
	public String toString()
	{
		return ( "[3,3](" + affine + ") " + error );
	}

	public void minimize()
	{
		// center of mass:
		float xo1 = 0, yo1 = 0;
		float xo2 = 0, yo2 = 0;
		int length = inliers.size();
		
		for ( Match m : inliers )
		{
			xo1 += m.p1[ 0 ];
			yo1 += m.p1[ 1 ];
			xo2 += m.p2[ 0 ];
			yo2 += m.p2[ 1 ];
		}
		xo1 /= length;
		yo1 /= length;
		xo2 /= length;
		yo2 /= length;

		float dx = xo1 - xo2; // reversed, because the second will be moved relative to the first
		float dy = yo1 - yo2;
		
		affine.setToIdentity();
		affine.translate( dx, dy );
	}
	
	final private void shake( float[] d, float[] center )
	{
		affine.translate( rnd.nextGaussian() * d[ 0 ], rnd.nextGaussian() * d[ 1 ] );
	}
	
	/**
	 * change the model a bit
	 * 
	 * estimates the necessary amount of shaking for each single dimensional
	 * distance in the set of matches
	 * 
	 * @param matches 
	 * @param scale gives a multiplicative factor to each dimensional distance (increases the amount of shaking)
	 */
	final public void shake( Collection< Match > matches, float scale, float[] center )
	{
		double xd = 0.0;
		double yd = 0.0;
		
		int num_matches = matches.size();
		if ( num_matches > 0 )
		{
			for ( Match match : matches )
			{
				xd += Math.abs( match.p1[ 0 ] - match.p2[ 0 ] );;
				yd += Math.abs( match.p1[ 1 ] - match.p2[ 1 ] );;
			}
			xd /= matches.size();
			yd /= matches.size();			
		}
		
		shake( new float[]{ ( float )xd * scale, ( float )yd * scale }, center );
	}

	/**
	 * estimate the transformation model for a set of feature correspondences
	 * containing a high number of outliers using RANSAC
	 */
	static public TModel2D estimateModel(
			List< Match > matches,
			int iterations,
			float epsilon,
			float min_inliers )
	{
		if ( matches.size() < MIN_SET_SIZE )
		{
			System.err.println( matches.size() + " correspondences are not enough to estimate a model, at least " + MIN_SET_SIZE + " correspondences required." );
			return null;
		}

		TModel2D model = new TModel2D();		//!< the final model to be estimated
		
		int i = 0;
		while ( i < iterations )
		{
			Match[] points = new Match[ MIN_SET_SIZE ];
			
			points[ 0 ] = matches.get( ( int )( rnd.nextDouble() * matches.size() ) );

			TModel2D m = new TModel2D();
			m.fit( points );
			int num_inliers = 0;
			boolean is_good = m.test( matches, epsilon, min_inliers );
			while ( is_good && num_inliers < m.inliers.size() )
			{
				num_inliers = m.inliers.size();
				m.minimize();
				is_good = m.test( matches, epsilon, min_inliers );
			}
			if (
					is_good &&
					m.betterThan( model ) &&
					m.inliers.size() > 2 * MIN_SET_SIZE )
			{
				model = m.clone();
			}
			++i;
		}
		if ( model.inliers.size() == 0 )
			return null;

		return model;
	}

	public TModel2D clone()
	{
		TModel2D tm = new TModel2D();
		tm.inliers.addAll( inliers );
		tm.affine.setTransform( affine );
		tm.error = error;
		return tm;
	}
	
	public TRModel2D toTRModel2D()
	{
		TRModel2D trm = new TRModel2D();
		trm.inliers.addAll( inliers );
		trm.getAffine().setTransform( affine );
		trm.error = error;
		return trm;
	}
}
