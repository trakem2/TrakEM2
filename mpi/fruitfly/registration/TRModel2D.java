package mpi.fruitfly.registration;

import java.util.Iterator;
import java.util.Vector;
import java.util.ArrayList;
import java.util.Random;
import java.awt.geom.AffineTransform;

public class TRModel2D extends Model {

	static final public int MIN_SET_SIZE = 2;

	final public AffineTransform affine = new AffineTransform();
	
	@Override
	float[] apply( float[] point )
	{
		float[] transformed = new float[ 2 ];

		affine.transform( point, 0, transformed, 0, 1 );
		// rotate
		/*
		transformed[ 0 ] = cos * point[ 0 ] - sin * point[ 1 ];
		transformed[ 1 ] = sin * point[ 0 ] + cos * point[ 1 ];

		// translate
		transformed[ 0 ] += translation[ 0 ];
		transformed[ 1 ] += translation[ 1 ];
		*/
		return transformed;
	}

	@Override
	boolean fit( Vector< Match > matches )
	{
		Match m1 = matches.get( 0 );
		Match m2 = matches.get( 1 );

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

	/** Returns dx, dy, rot, xo, yo from the two sets of points, where xo,yo is the center of rotation */
	private void minimize()
	{
		// center of mass:
		float xo1 = 0, yo1 = 0;
		float xo2 = 0, yo2 = 0;
		// Implementing Johannes Schindelin's squared error minimization formula
		// tan(angle) = Sum(x1*y1 + x2y2) / Sum(x1*y2 - x2*y1)
		int length = inliers.size();
		// 1 - compute centers of mass, for displacement and origin of rotation

		// TODO fix this ...
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
		
		//angle = Math.toDegrees(angle);
		affine.setToIdentity();
		affine.rotate( angle, xo1, yo1 );
		affine.translate( dx, dy );
		//return new float[]{dx, dy, angle, xo1, yo1};
	}
	
	public void minimize( ArrayList< SimPoint2DMatch > matches )
	{
		// center of mass:
		float xo1 = 0, yo1 = 0;
		float xo2 = 0, yo2 = 0;
		// Implementing Johannes Schindelin's squared error minimization formula
		// tan(angle) = Sum(x1*y1 + x2y2) / Sum(x1*y2 - x2*y1)
		int length = matches.size();
		// 1 - compute centers of mass, for displacement and origin of rotation

		for ( SimPoint2DMatch m : matches )
		{
			xo1 += m.s1.getWtx();
			yo1 += m.s1.getWty();
			xo2 += m.s2.getWtx();
			yo2 += m.s2.getWty();
		}
		xo1 /= length;
		yo1 /= length;
		xo2 /= length;
		yo2 /= length;

		float dx = xo1 - xo2; // reversed, because the second will be moved relative to the first
		float dy = yo1 - yo2;
		float sum1 = 0, sum2 = 0;
		float x1, y1, x2, y2;
		for ( SimPoint2DMatch m : matches )
		{
			// make points local to the center of mass of the first landmark set
			x1 = m.s1.getWtx() - xo1; // x1
			y1 = m.s1.getWty() - yo1; // x2
			x2 = m.s2.getWtx() - xo2 + dx; // y1
			y2 = m.s2.getWty() - yo2 + dy; // y2
			sum1 += x1 * y2 - y1 * x2; //   x1 * y2 - x2 * y1 // assuming p1 is x1,x2, and p2 is y1,y2
			sum2 += x1 * x2 + y1 * y2; //   x1 * y1 + x2 * y2
		}
		float angle = ( float )Math.atan2( -sum1, sum2 );
		
		//angle = Math.toDegrees(angle);
		affine.setToIdentity();
		affine.rotate( -angle, xo2, yo2 );
		affine.translate( -dx, -dy );
		//return new float[]{dx, dy, angle, xo1, yo1};
	}

	/**
	 * estimate the transformation model for a set of feature correspondences
	 * containing a high number of outliers using RANSAC
	 */
	static public TRModel2D estimateModel(
			Vector< Match > matches,
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

		// real random
		//final Random random = new Random( System.currentTimeMillis() );
		// repeatable results
		final Random random = new Random( 69997 );
		int i = 0;
		while ( i < iterations )
		{
			// choose T::MIN_SET_SIZE disjunctive matches randomly
			Vector< Match > points = new Vector< Match >();
			Vector< Integer > keys = new Vector< Integer >();
			//int[] keys;
			for ( int j = 0; j < TRModel2D.MIN_SET_SIZE; ++j )
			{
				int key;
				boolean in_set = false;
				do
				{
					key = ( int )( random.nextDouble() * matches.size() );
					in_set = false;
					for ( Iterator k = keys.iterator(); k.hasNext(); )
					{
						Integer kk = ( Integer )k.next();
						if ( key == kk.intValue() )
						{
							in_set = true;
							break;
						}
					}
				}
				while ( in_set );
				keys.addElement( key );
				points.addElement( matches.get( key ) );
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

			points.clear();
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
}
