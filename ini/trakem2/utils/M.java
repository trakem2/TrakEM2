package ini.trakem2.utils;

import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import java.awt.geom.Area;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.lang.reflect.Field;

import ini.trakem2.persistence.Loader;

import ij.gui.Roi;
import ij.gui.ShapeRoi;

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

	static public final double distance(final double x1, final double y1,
			                    final double x2, final double y2) {
		return Math.sqrt(Math.pow(x1 - x2, 2)
			       + Math.pow(y1 - y2, 2));
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

	static public final Point2D.Double transform(final AffineTransform affine, final double x, final double y) {
		final Point2D.Double pSrc = new Point2D.Double(x, y);
		if (affine.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		affine.transform(pSrc, pDst);
		return pDst;
	}

	static public final Point2D.Double inverseTransform(final AffineTransform affine, final double x, final double y) {
		final Point2D.Double pSrc = new Point2D.Double(x, y);
		if (affine.isIdentity()) return pSrc;
		final Point2D.Double pDst = new Point2D.Double();
		try {
			affine.createInverse().transform(pSrc, pDst);
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
		}
		return pDst;
	}

	/*==============  Geometry =============*/
	
	static public final boolean isEmpty(final Area area) {
		final Rectangle b = area.getBounds();
		return 0 == b.width || 0 == b.height;
	}

	/** Test whether the areas intersect each other. */
	static public final boolean intersects(final Area a1, final Area a2) {
		final Area b = new Area(a1);
		b.intersect(a2);
		final java.awt.Rectangle r = b.getBounds();
		return 0 != r.width && 0 != r.height;
	}

	static public final Area getArea(final Roi roi) {
		if (null == roi) return null;
		ShapeRoi sroi = new ShapeRoi(roi);
		AffineTransform at = new AffineTransform();
		Rectangle bounds = sroi.getBounds();
		at.translate(bounds.x, bounds.y);
		Area area = new Area(getShape(sroi));
		return area.createTransformedArea(at);
	}

	/** Returns the approximated area of the given Area object; Loader can be null; if not, it's used to secure memory space. */
	static public final double measureArea(Area area, final Loader loader) {
		double sum = 0;
		try {
			Rectangle bounds = area.getBounds();
			double scale = 1;
			if (bounds.width > 2048 || bounds.height > 2048) { // TODO value 2048 should be reconsidered as a project property
				scale = 2048.0 / bounds.width;
			}
			if (0 == scale) {
				Utils.log("Can't measure: area too large, out of scale range for approximation.");
				return sum;
			}
			AffineTransform at = new AffineTransform();
			at.translate(-bounds.x, -bounds.y);
			at.scale(scale, scale);
			area = area.createTransformedArea(at);
			bounds = area.getBounds();
			if (0 == bounds.width || 0 == bounds.height) {
				Utils.log("Can't measure: area too large, approximates zero.");
				return sum;
			}
			if (null != loader) loader.releaseToFit(bounds.width * bounds.height * 3);
			BufferedImage bi = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_BYTE_INDEXED);
			Graphics2D g = bi.createGraphics();
			g.setColor(Color.white);
			g.fill(area);
			final byte[] pixels = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData(); // buffer.getData();
			for (int i=pixels.length-1; i>-1; i--) {
				//if (255 == (pixels[i]&0xff)) sum++;
				if (0 != pixels[i]) sum++;
			}
			bi.flush();
			g.dispose();
			if (1 != scale) sum = sum / (scale * scale);
		} catch (Throwable e) {
			IJError.print(e);
		}
		return sum;
	}

	/** Compute the area of the triangle defined by 3 points in 3D space, returning half of the length of the vector resulting from the cross product of vectors p1p2 and p1p3. */
	static public final double measureArea(final Point3f p1, final Point3f p2, final Point3f p3) {
		final Vector3f v = new Vector3f();
		v.cross(new Vector3f(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z),
			new Vector3f(p3.x - p1.x, p3.y - p1.y, p3.z - p1.z));
		return 0.5 * Math.abs(v.x * v.x + v.y * v.y + v.z * v.z);
	}

	/** Returns true if the roi is of closed shape type like an OvalRoi, ShapeRoi, a Roi rectangle, etc. */
	static public final boolean isAreaROI(final Roi roi) {
		switch (roi.getType()) {
			case Roi.POLYLINE:
			case Roi.FREELINE:
			case Roi.LINE:
			case Roi.POINT:
				return false;
		}
		return true;
	}

	static public final Collection<Polygon> getPolygons(Area area) {
		final ArrayList<Polygon> pols = new ArrayList<Polygon>();
		Polygon pol = new Polygon();

		final float[] coords = new float[6];
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
				case PathIterator.SEG_LINETO:
					pol.addPoint((int)coords[0], (int)coords[1]);
					break;
				case PathIterator.SEG_CLOSE:
					pols.add(pol);
					pol = new Polygon();
					break;
				default:
					Utils.log2("WARNING: unhandled seg type.");
					break;
			}
			pit.next();
			if (pit.isDone()) {
				break;
			}
		}
		return pols;
	}

	static private Field shape_field = null;

	static public final Shape getShape(final ShapeRoi roi) {
		try {
			if (null == shape_field) {
				shape_field = ShapeRoi.class.getDeclaredField("shape");
				shape_field.setAccessible(true);
			}
			return (Shape)shape_field.get(roi);
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Detect if a point is not in the area, but lays inside one of its path, which is returned as a Polygon. Otherwise returns null. The given x,y must be already in the Area's coordinate system. */
	static public final Polygon findPath(final Area area, final int x, final int y) {
		Polygon pol = new Polygon();
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			float[] coords = new float[6];
			int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
				case PathIterator.SEG_LINETO:
					pol.addPoint((int)coords[0], (int)coords[1]);
					break;
				case PathIterator.SEG_CLOSE:
					if (pol.contains(x, y)) return pol;
					// else check next
					pol = new Polygon();
					break;
				default:
					Utils.log2("WARNING: unhandled seg type.");
					break;
			}
			pit.next();
			if (pit.isDone()) {
				break;
			}
		}
		return null;
	}
}
