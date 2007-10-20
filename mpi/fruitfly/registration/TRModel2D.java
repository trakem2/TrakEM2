package mpi.fruitfly.registration;

import java.util.Iterator;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.awt.geom.AffineTransform;

public class TRModel2D extends Model {

	static final public int MIN_SET_SIZE = 2;

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
		Match m2 = matches[ 1 ];

		float x1 = m2.p1[ 0 ] - m1.p1[ 0 ];
		float y1 = m2.p1[ 1 ] - m1.p1[ 1 ];
		float x2 = m2.p2[ 0 ] - m1.p2[ 0 ];
		float y2 = m2.p2[ 1 ] - m1.p2[ 1 ];
		float l1 = ( float )Math.sqrt( x1 * x1 + y1 * y1 );
		float l2 = ( float )Math.sqrt( x2 * x2 + y2 * y2 );

		x1 /= l1;
		x2 /= l2;
		y1 /= l1;
		y2 /= l2;

		//! unrotate (x1,y1)^T to (x2,y2)^T = (1,0)^T getting the sinus and cosinus of the rotation angle
		float cos = x1 * x2 + y1 * y2;
		float sin = y1 * x2 - x1 * y2;

		//m.alpha = atan2( y, x );

		//! rotate c1->f1
		//x1 = cos * m1.p1[ 0 ] - sin * m1.p1[ 1 ];
		//y1 = sin * m1.p1[ 0 ] + cos * m1.p1[ 1 ];
		
		float tx = m1.p1[ 0 ] - cos * m1.p2[ 0 ] + sin * m1.p2[ 1 ];
		float ty = m1.p1[ 1 ] - sin * m1.p2[ 0 ] - cos * m1.p2[ 1 ];
		
		affine.setTransform( cos, sin, -sin, cos, tx, ty );

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
		// Implementing Johannes Schindelin's squared error minimization formula
		// tan(angle) = Sum(x1*y1 + x2y2) / Sum(x1*y2 - x2*y1)
		int length = inliers.size();
		// 1 - compute centers of mass, for displacement and origin of rotation

		if (0 == length) return;

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
		float sum1 = 0, sum2 = 0;
		float x1, y1, x2, y2;
		for ( Match m : inliers )
		{
			// make points local to the center of mass of the first landmark set
			x1 = m.p1[ 0 ] - xo1; // x1
			y1 = m.p1[ 1 ] - yo1; // x2
			x2 = m.p2[ 0 ] - xo2 + dx; // y1
			y2 = m.p2[ 1 ] - yo2 + dy; // y2
			sum1 += x1 * y2 - y1 * x2; //   x1 * y2 - x2 * y1 // assuming p1 is x1,x2, and p2 is y1,y2
			sum2 += x1 * x2 + y1 * y2; //   x1 * y1 + x2 * y2
		}
		float angle = ( float )Math.atan2( -sum1, sum2 );
		
		affine.setToIdentity();
		affine.rotate( angle, xo1, yo1 );
		affine.translate( dx, dy );
	}
	
	final private void shake( float[] d, float[] center )
	{
		affine.rotate( rnd.nextGaussian() * d[ 2 ], center[ 0 ], center[ 1 ] );
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
		double rd = 0.0;
		
		int num_matches = matches.size();
		if ( num_matches > 0 )
		{
			for ( Match match : matches )
			{
				xd += Math.abs( match.p1[ 0 ] - match.p2[ 0 ] );;
				yd += Math.abs( match.p1[ 1 ] - match.p2[ 1 ] );;
				
				// shift relative to the center
				float x1 = match.p1[ 0 ] - center[ 0 ];
				float y1 = match.p1[ 1 ] - center[ 1 ];
				float x2 = match.p2[ 0 ] - center[ 0 ];
				float y2 = match.p2[ 1 ] - center[ 1 ];
				
				float l1 = ( float )Math.sqrt( x1 * x1 + y1 * y1 );
				float l2 = ( float )Math.sqrt( x2 * x2 + y2 * y2 );

				x1 /= l1;
				x2 /= l2;
				y1 /= l1;
				y2 /= l2;

				//! unrotate (x1,y1)^T to (x2,y2)^T = (1,0)^T getting the sinus and cosinus of the rotation angle
				float cos = x1 * x2 + y1 * y2;
				float sin = y1 * x2 - x1 * y2;

				rd += Math.abs( Math.atan2( sin, cos ) );
			}
			xd /= matches.size();
			yd /= matches.size();
			rd /= matches.size();
			
			//System.out.println( rd );
		}
		
		shake( new float[]{ ( float )xd * scale, ( float )yd * scale, ( float )rd * scale }, center );
	}

	/**
	 * estimate the transformation model for a set of feature correspondences
	 * containing a high number of outliers using RANSAC
	 */
	static public TRModel2D estimateModel(
			List< Match > matches,
			int iterations,
			float epsilon,
			float min_inliers )
	{
		if ( matches.size() < TRModel2D.MIN_SET_SIZE )
		{
			System.err.println( matches.size() + " correspondences are not enough to estimate a model, at least " + TRModel2D.MIN_SET_SIZE + " correspondences required." );
			return null;
		}

		TRModel2D model = new TRModel2D();		//!< the final model to be estimated
		//std::vector< FeatureMatch* > points;

		int i = 0;
		while ( i < iterations )
		{
			// choose T::MIN_SET_SIZE disjunctive matches randomly
			Match[] points = new Match[ MIN_SET_SIZE ];
			int[] keys = new int[ MIN_SET_SIZE ];
			
			for ( int j = 0; j < TRModel2D.MIN_SET_SIZE; ++j )
			{
				int key;
				boolean in_set = false;
				do
				{
					key = ( int )( rnd.nextDouble() * matches.size() );
					in_set = false;
					
					// check if this key exists already
					for ( int k = 0; k < j; ++k )
					{
						if ( key == keys[ k ] )
						{
							in_set = true;
							break;
						}
					}
				}
				while ( in_set );
				keys[ j ] = key;
				points[ j ] = matches.get( key );
			}

			TRModel2D m = new TRModel2D();
			m.fit( points );
			int num_inliers = 0;
			boolean is_good = m.test( matches, epsilon, min_inliers );
			//System.out.println( "tested: " + m.toString() + " with inliers: " + num_inliers );
			while ( is_good && num_inliers < m.inliers.size() )
			{
				num_inliers = m.inliers.size();
				m.minimize();
				is_good = m.test( matches, epsilon, min_inliers );
				//System.out.println( "minimized: " + m.toString() + " with inliers: " + m.inliers.size() );
			}
			if (
					is_good &&
					m.betterThan( model ) &&
					m.inliers.size() > 2 * MIN_SET_SIZE )
			{
				model = m.clone();
				//System.out.println( "good model found" );
			}
			++i;
		}
		if ( model.inliers.size() == 0 )
			return null;

		//System.out.println( "number of inliers: " + inliers.size() + " of " + matches.size() );
		return model;
	}

	public TRModel2D clone()
	{
		TRModel2D trm = new TRModel2D();
		trm.inliers.addAll( inliers );
		trm.affine.setTransform( affine );
		trm.error = error;
		return trm;
	}

	public void preConcatenate(TRModel2D model) {
		this.affine.preConcatenate(model.affine);
	}
}
