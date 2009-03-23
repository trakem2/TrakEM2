package ini.trakem2.utils;

import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;

/** TrakEM2's mathematician. */
public final class M {

	/*==============  3D =============*/

	static public final double distancePointToSegment
		(final double px, final double py, final double pz,
		 final double lx1, final double ly1, final double lz1,
		 final double lx2, final double ly2, final double lz2)
	{
		return distancePointToSegment(new Vector3d(px, py, pz),
				              new Vector3d(lx1, ly1, lz1),
					      new Vector3d(lx2, ly2, lz2));
	}
	static public final double distancePointToSegmentSq
		(final double px, final double py, final double pz,
		 final double lx1, final double ly1, final double lz1,
		 final double lx2, final double ly2, final double lz2)
	{
		return distancePointToSegmentSq(new Vector3d(px, py, pz),
				              new Vector3d(lx1, ly1, lz1),
					      new Vector3d(lx2, ly2, lz2));
	}

	/** Minimum distance between point v0 and a line segment defined by points v1 and v2. */
	static public final double distancePointToSegment(final Vector3d p, final Vector3d v1, final Vector3d v2) {
		final Vector3d v = new Vector3d();
		v.sub(v2, v1);
		final Vector3d w = new Vector3d();
		w.sub(p, v1);

		final double c1 = w.dot(v);
		if (c1 <= 0) return distance(p, v1);

		final double c2 = v.dot(v);
		if (c2 <= c1) return distance(p, v2);

		final double b = c1 / c2;

		final Vector3d pb = new Vector3d(v);
		pb.scale(b);
		pb.add(v1);
		return distance(p, pb);
	}

	static public final double distancePointToSegmentSq(final Vector3d p, final Vector3d v1, final Vector3d v2) {
		final Vector3d v = new Vector3d();
		v.sub(v2, v1);
		final Vector3d w = new Vector3d();
		w.sub(p, v1);

		final double c1 = w.dot(v);
		if (c1 <= 0) return distanceSq(p, v1);

		final double c2 = v.dot(v);
		if (c2 <= c1) return distanceSq(p, v2);

		final double b = c1 / c2;

		final Vector3d pb = new Vector3d(v);
		pb.scale(b);
		pb.add(v1);
		return distanceSq(p, pb);
	}


	static public final double distance(final Tuple3d t1, final Tuple3d t2) {
		return Math.sqrt(  Math.pow(t1.x - t2.x, 2)
				 + Math.pow(t1.y - t2.y, 2)
				 + Math.pow(t1.z - t2.z, 2) );
	}

	static public final double distanceSq(final Tuple3d t1, final Tuple3d t2) {
		return Math.pow(t1.x - t2.x, 2)
		     + Math.pow(t1.y - t2.y, 2)
		     + Math.pow(t1.z - t2.z, 2);
	}

	static public final double distance(final double x1, final double y1, final double z1,
			              final double x2, final double y2, final double z2) {
		return Math.sqrt(Math.pow(x1 - x2, 2)
			       + Math.pow(y1 - y2, 2)
			       + Math.pow(z1 - z2, 2));
	}

	static public final double distanceSq(final double x1, final double y1, final double z1,
			              final double x2, final double y2, final double z2) {
		return  Math.pow(x1 - x2, 2)
		      + Math.pow(y1 - y2, 2)
		      + Math.pow(z1 - z2, 2);
	}


	/*==============  2D =============*/

	/** For a point px,py return its distance to a line defined by points lx1,ly1 and lx2,ly2. Formula as in http://mathworld.wolfram.com/Point-LineDistance2-Dimensional.html at 2008-11-11 20:19 Zurich time.*/
	static public double distancePointToLine(final double px, final double py, final double lx1, final double ly1, final double lx2, final double ly2) {
		        return Math.abs( (lx2 - lx1) * (ly1 - py) - (lx1 - px) * (ly2 - ly1) )
			     / Math.sqrt( Math.pow(lx2 - lx1, 2) + Math.pow(ly2 - ly1, 2) );
	}

	/** For a point px,py return its distance to a line SEGMENT defined by points lx1,ly1 and lx2,ly2. */
	static public double distancePointToSegment(final double px, final double py, final double lx1, final double ly1, final double lx2, final double ly2) {
		double xu = px - lx1;
		double yu = py - ly1;
		double xv = lx2 - lx1;
		double yv = ly2 - ly1;
		if (xu * xv + yu * yv < 0) return Math.sqrt( Math.pow(px - lx1, 2) + Math.pow(py - ly1, 2) );

		xu = px - lx2;
		yu = py - ly2;
		xv = -xv;
		yv = -yv;
		if (xu * xv + yu * yv < 0) return Math.sqrt( Math.pow(px - lx2, 2) + Math.pow(py - ly2, 2) );

		return Math.abs( (px * (ly1 - ly2) + py * (lx2 - lx1) + (lx1 * ly2 - lx2 * ly1) ) / Math.sqrt( Math.pow(lx2 - lx1, 2) + Math.pow(ly2 - ly1, 2) ) );
	}

	static public final boolean equals(final double a, final double b) {
		return Math.abs(a - b) < Utils.FL_ERROR;
	}
}
