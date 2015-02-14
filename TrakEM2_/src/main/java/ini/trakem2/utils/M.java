package ini.trakem2.utils;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.process.FloatPolygon;
import ini.trakem2.display.VectorDataTransform;
import ini.trakem2.persistence.Loader;
import ini.trakem2.vector.VectorString2D;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

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

	/** In 3D */
	static public double distancePointToLine(final double px, final double py, final double pz, final double lx1, final double ly1, final double lz1, final double lx2, final double ly2, final double lz2 ) {
		final double segment_length = new Vector3d(lx2 - lx1, ly2 - ly1, lz2 - lz1).length();
		if (0 == segment_length) return 0;
		final Vector3d cross = new Vector3d();
		cross.cross(new Vector3d(px - lx1, py - ly1, pz - lz1),
			    new Vector3d(px - lx2, py - ly2, pz - lz2));
		return cross.length() / segment_length;
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
		} catch (final NoninvertibleTransformException nite) {
			IJError.print(nite);
		}
		return pDst;
	}

	// from utilities.c in my CurveMorphing C module ... from C! Java is a low level language with the disadvantages of the high level languages ...
	/** Returns the angle in radians of the given polar coordinates, correcting the Math.atan2 output.
	 * Adjusting so that 0 is 3 o'clock, PI+PI/2 is 12 o'clock, PI is 9 o'clock, and PI/2 is 6 o'clock (why atan2 doesn't output angles this way? I remember I had the same problem for Pipe.java in the A_3D_editing plugin) */
	static public final double getAngle(final double x, final double y) {
		// calculate angle
		double a = Math.atan2(x, y);
		// fix too large angles (beats me why are they ever generated)
		if (a > 2 * Math.PI) {
			a = a - 2 * Math.PI;
		}
		// fix atan2 output scheme to match my mental scheme
		if (a >= 0.0 && a <= Math.PI/2) {
			a = Math.PI/2 - a;
		} else if (a < 0 && a >= -Math.PI) {
			a = Math.PI/2 -a;
		} else if (a > Math.PI/2 && a <= Math.PI) {
			a = Math.PI + Math.PI + Math.PI/2 - a;
		}
		return a;
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
		if (roi instanceof ShapeRoi) return getArea((ShapeRoi)roi);
		return getArea(new ShapeRoi(roi));
	}
	static public final Area getArea(final ShapeRoi sroi) {
		if (null == sroi) return null;
		final AffineTransform at = new AffineTransform();
		final Rectangle bounds = sroi.getBounds();
		at.translate(bounds.x, bounds.y);
		final Area area = new Area(sroi.getShape());
		area.transform(at);
		return area;
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
			final AffineTransform at = new AffineTransform();
			at.translate(-bounds.x, -bounds.y);
			at.scale(scale, scale);
			area = area.createTransformedArea(at);
			bounds = area.getBounds();
			if (0 == bounds.width || 0 == bounds.height) {
				Utils.log("Can't measure: area too large, approximates zero.");
				return sum;
			}
			if (null != loader) loader.releaseToFit(bounds.width * bounds.height * 3);
			final BufferedImage bi = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_BYTE_INDEXED);
			final Graphics2D g = bi.createGraphics();
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
		} catch (final Throwable e) {
			IJError.print(e);
		}
		return sum;
	}

	/** Compute the area of the triangle defined by 3 points in 3D space, returning half of the length of the vector resulting from the cross product of vectors p1p2 and p1p3. */
	static public final double measureArea(final Point3f p1, final Point3f p2, final Point3f p3) {
		// Distance from p1 to line p2-p3, times length of line p2-p3, divided by 2:
		return 0.5 * M.distancePointToLine(p1.x, p1.y, p1.z,
						   p2.x, p2.y, p2.z,
						   p3.x, p3.y, p3.z)
			   * p2.distance(p3);
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

	@SuppressWarnings("null")
	static public final Collection<Polygon> getPolygons(final Area area) {
		final ArrayList<Polygon> pols = new ArrayList<Polygon>();
		Polygon pol = null;

		final float[] coords = new float[6];
		for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			final int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
					pol = new Polygon();
				//$FALL-THROUGH$
			case PathIterator.SEG_LINETO:
					pol.addPoint((int)coords[0], (int)coords[1]);
					break;
				case PathIterator.SEG_CLOSE:
					pols.add(pol);
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
	static public final Collection<Polygon> getPolygonsByRounding(final Area area) {
		final ArrayList<Polygon> pols = new ArrayList<Polygon>();
		Polygon pol = new Polygon();

		final float[] coords = new float[6];
		for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			final int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
				case PathIterator.SEG_LINETO:
					pol.addPoint(Math.round(coords[0]), Math.round(coords[1]));
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

	/** Return a new Area resulting from applying @param ict to @param a;
	 *  assumes areas consists of paths with moveTo, lineTo and close operations. */
	static public final Area transform(final mpicbg.models.CoordinateTransform ict, final Area a) {
		final GeneralPath path = new GeneralPath();
		final float[] coords = new float[6];
		final double[] fp = new double[2];

		for (final PathIterator pit = a.getPathIterator(null); !pit.isDone(); ) {
			final int seg_type = pit.currentSegment(coords);
			fp[0] = coords[0];
			fp[1] = coords[1];
			ict.applyInPlace(fp);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
					path.moveTo(fp[0], fp[1]);
					break;
				case PathIterator.SEG_LINETO:
				case PathIterator.SEG_CLOSE:
					path.lineTo(fp[0], fp[1]);
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
		return new Area(path);
	}

	/** Apply in place the @param ict to the Area @param a, but only for the part that intersects the roi. */
	static final public void apply(final mpicbg.models.CoordinateTransform ict, final Area roi, final Area a) {
		final Area intersection = new Area(a);
		intersection.intersect(roi);
		a.subtract(intersection);
		a.add(M.transform(ict, intersection));
	}

	static final public void apply(final VectorDataTransform vdt, final Area a) {
		apply(vdt, a, false);
	}

	/** Parts of @param a not intersected by any of @param vdt rois will be left untouched if @param remove_outside is false. */
	static final public void apply(final VectorDataTransform vdt, final Area a, final boolean remove_outside) {
		final Area b = new Area();
		for (final VectorDataTransform.ROITransform rt : vdt.transforms) {
			// Cut the intersecting part from a:
			final Area intersection = new Area(a);
			intersection.intersect(rt.roi);
			a.subtract(intersection);
			// .. and add it to b, transformed:
			b.add(M.transform(rt.ct, intersection));
		}

		if (!M.isEmpty(a)) {
			if (remove_outside) {
				// Clear areas not affected any ROITransform
				Utils.log("WARNING: parts of an area in layer " + vdt.layer + "\n    did not intersect any transformation target\n    and where removed.");
				a.reset();
			} else Utils.log("WARNING: parts of an area in layer " + vdt.layer + "\n    remain untransformed.");
		}

		// Add b (the transformed parts) to what remains of a
		a.add(b);
	}

	/** Detect if a point is not in the area, but lays inside one of its path, which is returned as a Polygon. Otherwise returns null. The given x,y must be already in the Area's coordinate system. */
	static public final Polygon findPath(final Area area, final int x, final int y) {
		Polygon pol = new Polygon();
		for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			final float[] coords = new float[6];
			final int seg_type = pit.currentSegment(coords);
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

	/** Converts all points in @param area to ints by casting. */
	static public final Area areaInInts(final Area area) {
		final Area a = new Area();
		for (final Polygon pol : M.getPolygons(area)) {
			a.exclusiveOr(new Area(pol));
		}
		return a;
	}

	/** Converts all points in @param area to ints by rounding. */
	static public final Area areaInIntsByRounding(final Area area) {
		final Area a = new Area();
		for (final Polygon pol : M.getPolygonsByRounding(area)) {
			a.add(new Area(pol));
		}
		return a;
	}

	/* ================================================= */

	public static void quicksort(final float[] data, final Object[] sortAlso) throws IllegalArgumentException {
		if (data.length != sortAlso.length) {
			throw new IllegalArgumentException("data and sortAlso arrays don't have the same length.");
		}
		quicksort(data, sortAlso, 0, data.length-1);
	}

	/** Adapted from Stephan Preibisch's mpi.fruitfly.math.General homonimous method. */
	public static void quicksort(final float[] data, final Object[] sortAlso,
			             final int left, final int right) {
		if (data.length < 2) return;
		int i = left, j = right;
		final float x = data[(left + right) / 2];
		do {
			while (data[i] < x) i++;
			while (x < data[j]) j--;
			if (i <= j) {
				final float temp = data[i];
				data[i] = data[j];
				data[j] = temp;

				final Object temp2 = sortAlso[i];
				sortAlso[i] = sortAlso[j];
				sortAlso[j] = temp2;

				i++;
				j--;
			}
		} while (i <= j);
		if (left < j) quicksort(data, sortAlso, left, j);
		if (i < right) quicksort(data, sortAlso, i, right);
	}

	static private Polygon arrowhead = null;

	/** Create an arrowhead at the end of the line segment defined by x1,y1 and x2,y2. */
	static public final Shape createArrowhead(final double x1, final double y1, final double x2, final double y2) {
		return createArrowhead(x1, y1, x2, y2, 1.0);
	}
	static public final Shape createArrowhead(final double x1, final double y1, final double x2, final double y2, final double magnification) {
		if (null == arrowhead) arrowhead = new Polygon(new int[]{-14, -13, 0, -13, -14, -9}, new int[]{-5, -5, 0, 5, 5, 0}, 6);
		final AffineTransform aff = new AffineTransform();
		aff.translate(x2, y2);
		aff.rotate(M.getAngle(x2 - x1, y2 - y1));
		if (magnification < 1.0) aff.scale(magnification, magnification);
		return aff.createTransformedShape(arrowhead);
	}

	static final private float phi = (1 + (float)Math.sqrt(5)) / 2;
	static final private float[][] icosahedron = { { phi, 1, 0 },
					{ -phi, 1, 0 },
					{ phi, -1, 0 },
					{ -phi, -1, 0 },
					{ 1, 0, phi },
					{ 1, 0, -phi },
					{-1, 0, phi },
					{-1, 0, -phi },
					{0, phi, 1 },
					{0, -phi, 1},
					{0, phi, -1 },
					{0, -phi, -1} };
	static final private int[][] icosfaces =    { { 0, 8, 4 },
					{ 0, 5, 10 },
					{ 2, 4, 9 },
					{ 2, 11, 5 },
					{ 1, 6, 8 },
					{ 1, 10, 7 },
					{ 3, 9, 6 },
					{ 3, 7, 11 },
					{ 0, 10, 8 },
					{ 1, 8, 10 },
					{ 2, 9, 11 },
					{ 3, 11, 9 },
					{ 4, 2, 0 },
					{ 5, 0, 2 },
					{ 6, 1, 3 },
					{ 7, 3, 1 },
					{ 8, 6, 4 },
					{ 9, 4, 6 },
					{ 10, 5, 7 },
					{ 11, 7, 5 } };

	/** Returns a "3D Viewer"-ready list mesh, centered at 0,0,0 and with radius as the radius of the enclosing sphere. */
	static public final List<Point3f> createIcosahedron(int subdivisions, final float radius) {
		List<Point3f> ps = new ArrayList<Point3f>();
		for (int i=0; i<icosfaces.length; i++) {
			for (int k=0; k<3; k++) {
				ps.add(new Point3f(icosahedron[icosfaces[i][k]]));
			}
		}
		while (subdivisions-- > 0) {
			final List<Point3f> sub = new ArrayList<Point3f>();
			// Take three consecutive points, which define a face, and create 4 faces out of them.
			for (int i=0; i<ps.size(); i+=3) {
				final Point3f p0 = ps.get(i);
				final Point3f p1 = ps.get(i+1);
				final Point3f p2 = ps.get(i+2);

				final Point3f p01 = new Point3f((p0.x + p1.x)/2, (p0.y + p1.y)/2, (p0.z + p1.z)/2);
				final Point3f p02 = new Point3f((p0.x + p2.x)/2, (p0.y + p2.y)/2, (p0.z + p2.z)/2);
				final Point3f p12 = new Point3f((p1.x + p2.x)/2, (p1.y + p2.y)/2, (p1.z + p2.z)/2);
				// lower left:
				sub.add(p0);
				sub.add(p01);
				sub.add(p02);
				// upper:
				sub.add(new Point3f(p01)); // as copies
				sub.add(p1);
				sub.add(p12);
				// lower right:
				sub.add(new Point3f(p12));
				sub.add(p2);
				sub.add(new Point3f(p02));
				// center:
				sub.add(new Point3f(p01));
				sub.add(new Point3f(p12));
				sub.add(new Point3f(p02));
			}
			ps = sub;
		}

		// Project all vertices to the surface of a sphere of radius 1
		final Vector3f v = new Vector3f();
		for (final Point3f p : ps) {
			v.set(p);
			v.normalize();
			v.scale(radius);
			p.set(v);
		}

		return ps;
	}

	/** Reuses the @param fp to apply in place. */
	static public final void apply(final mpicbg.models.CoordinateTransform ict, final double[][] p, final int i, final double[] fp) {
		fp[0] = p[0][i];
		fp[1] = p[1][i];
		ict.applyInPlace(fp);
		p[0][i] = fp[0];
		p[1][i] = fp[1];
	}

	/** The @param ict is expected to transform the data as if this data was expressed in world coordinates,
	 *  so this method returns a transformation list that prepends the transform from local to world, then the @param ict, then from world to local. */
	static public final mpicbg.models.CoordinateTransform wrap(final AffineTransform to_world, final mpicbg.models.CoordinateTransform ict, final AffineTransform to_local) throws Exception {
		final mpicbg.models.CoordinateTransformList<mpicbg.models.CoordinateTransform> chain
		  = new mpicbg.models.CoordinateTransformList<mpicbg.models.CoordinateTransform>(); // bravo!
		// 1 - Prepend to world
		final mpicbg.models.AffineModel2D toworld = new mpicbg.models.AffineModel2D();
		toworld.set(to_world);
		chain.add(toworld);
		// 2 - Perform the transform in world coordinates
		chain.add(ict);
		// 3 - back to local
		final mpicbg.models.AffineModel2D tolocal = new mpicbg.models.AffineModel2D();
		tolocal.set(to_local);
		chain.add(tolocal);
		return chain;
	}

	/** Returns the shortest possible String representation of a float number, according to the desired decimal @param precision. */
	static public final String shortest(final float f, final float precision) {
		return Math.abs(f - (int)f) < precision ?
			  Integer.toString((int)f)
			: Float.toString(f);
	}

	/** Appends to @param sb the shortest possible String representation of a float number, according to the desired decimal @param precision. */
	static public final void appendShortest(final float f, final float precision, final StringBuilder sb) {
		if (Math.abs(f - (int)f) < precision) {
			sb.append((int)f);
		} else {
			sb.append(f);
		}
	}

	/** @returns the point of intersection of the two segments a and b, or null if they don't intersect. */
	public static final float[] computeSegmentsIntersection(
			final float ax0, final float ay0, final float ax1, final float ay1,
			final float bx0, final float by0, final float bx1, final float by1) {
		final float d = (ax0 - ax1)*(by0 - by1) - (ay0 - ay1) * (bx0 - bx1);
		if (0 == d) return null;
		final float xi = ((bx0 - bx1)*(ax0*ay1 - ay0*ax1) - (ax0 - ax1)*(bx0*by1 - by0*bx1)) / d;
		final float yi = ((by0 - by1)*(ax0*ay1 - ay0*ax1) - (ay0 - ay1)*(bx0*by1 - by0*bx1)) / d;
		if (xi < Math.min(ax0, ax1) || xi > Math.max(ax0, ax1)) return null;
		if (xi < Math.min(bx0, bx1) || xi > Math.max(bx0, bx1)) return null;
		if (yi < Math.min(ay0, ay1) || yi > Math.max(ay0, ay1)) return null;
		if (yi < Math.min(by0, by1) || yi > Math.max(by0, by1)) return null;
		return new float[]{xi, yi};
	}

	/** Returns an array of two Area objects, or of 1 if the @param proi doesn't intersect @param a. */
	public static final Area[] splitArea(final Area a, final PolygonRoi proi, final Rectangle world) {
		if (!a.getBounds().intersects(proi.getBounds())) return new Area[]{a};

		final int[] x = proi.getXCoordinates(),
			  y = proi.getYCoordinates();
		final Rectangle rb = proi.getBounds();
		final int len = proi.getNCoordinates();
		final int x0 = x[0] + rb.x;
		final int y0 = y[0] + rb.y;
		final int xN = x[len -1] + rb.x;
		final int yN = y[len -1] + rb.y;

		// corners, clock-wise:
		final int[][] corners = new int[][]{new int[]{world.x, world.y}, // top left
									  new int[]{world.x + world.width, world.y}, // top right
									  new int[]{world.x + world.width, world.y + world.height}, // bottom right
									  new int[]{world.x, world.y + world.height}}; // bottom left
		// Which corner is closest to x0,y0, and which to xN,yN
		double min_dist0 = Double.MAX_VALUE;
		int min_i0 = 0;
		int i = 0;
		double min_distN = Double.MAX_VALUE;
		int min_iN = 0;
		for (final int[] corner : corners) {
			double d = distance(x0, y0, corner[0], corner[1]);
			if (d < min_dist0) {
				min_dist0 = d;
				min_i0 = i;
			}
			d = distance(xN, yN, corner[0], corner[1]);
			if (d < min_distN) {
				min_distN = d;
				min_iN = i;
			}
			i++;
		}
		// Create new Area 'b' with which to intersect Area 'a':
		int[] xb, yb;
		int l,
		    i1;
        final int i2 = -1;
		// 0 1 2 3: when there difference is 2, there is a point in between
		if (2 != Math.abs(min_iN - min_i0)) {
			l = len +4;
			xb = new int[l];
			yb = new int[l];
			// -4 and -1 will be the drifted corners
			i1 = -4;
			xb[l-4] = corners[min_iN][0];
			yb[l-4] = corners[min_iN][1];
			xb[l-3] = corners[min_iN][0];
			yb[l-3] = corners[min_iN][1];
			xb[l-2] = corners[min_i0][0];
			yb[l-2] = corners[min_i0][1];
			xb[l-1] = corners[min_i0][0];
			yb[l-1] = corners[min_i0][1];
		} else {
			l = len +5;
			xb = new int[l];
			yb = new int[l];
			// -5 and -1 will be the drifted corners
			i1 = -5;
			xb[l-5] = corners[min_iN][0];
			yb[l-5] = corners[min_iN][1];
			xb[l-4] = corners[min_iN][0];
			yb[l-4] = corners[min_iN][1];
			xb[l-3] = corners[(min_iN + min_i0) / 2][0];
			yb[l-3] = corners[(min_iN + min_i0) / 2][1];
			xb[l-2] = corners[min_i0][0];
			yb[l-2] = corners[min_i0][1];
			xb[l-1] = corners[min_i0][0];
			yb[l-1] = corners[min_i0][1];
		}
		// Enter polyline points, corrected for proi bounds
		for (int k=0; k<len; k++) {
			xb[k] = x[k] + rb.x;
			yb[k] = y[k] + rb.y;
		}
		// Drift corners to closest point on the sides
		// Last:
		int dx = Math.abs(xb[l+i1] - (x[len-1] + rb.x)),
		    dy = Math.abs(yb[l+i1] - (y[len-1] + rb.y));
		if (dy < dx) xb[l+i1] = x[len-1] + rb.x;
		else yb[l+i1] = y[len-1] + rb.y;
		// First:
		dx = Math.abs(xb[l+i2] - (x[0] + rb.x));
		dy = Math.abs(yb[l+i2] - (y[0] + rb.y));
		if (dy < dx) xb[l+i2] = x[0] + rb.x;
		else yb[l+i2] = y[0] + rb.y;

		final Area b = new Area(new Polygon(xb, yb, xb.length));
		final Area c = new Area(world);
		c.subtract(b);

		return new Area[]{b, c};
	}

	public static final VectorString2D asVectorString2D(final Polygon pol, final double z) throws Exception {
		final double[] x = new double[pol.npoints];
		final double[] y = new double[pol.npoints];
		for (int i=0; i<x.length; i++) {
			x[i] = pol.xpoints[i];
			y[i] = pol.ypoints[i];
		}
		return new VectorString2D(x, y, z, true);
	}

	public static final double volumeOfTruncatedCone(final double r1, final double r2, final double height) {
		return Math.PI
				* (  r1 * r1
				   + r1 * r2
				   + r2 * r2)
				* height
				/ 3;
	}

	public static final double lateralAreaOfTruncatedCone(final double r1, final double r2, final double height) {
		return Math.PI * (r1 + r2) * Math.sqrt(Math.pow(r2 - r1, 2) + height * height);
	}

	/** The lateral area plus the two circles. */
	public static final double totalAreaOfTruncatedCone(final double r1, final double r2, final double height) {
		return lateralAreaOfTruncatedCone(r1, r2, height)
			+ Math.PI * (r1 * r1 + r2 * r2);
	}

	public static final void convolveGaussianSigma1(final float[] in, final float[] out, final CircularSequence seq) {
		// Weights for sigma = 1 and kernel 5x1, normalized so they add up to 1.
		final float w2 = 0.05448869f,
		             w1 = 0.24420134f,
		             w0 = 0.40261994f;
		// Handle boundary case
		if (out.length < 5) {
			for (int i=0; i<out.length; ++i) {
				out[i] =   in[seq.setPosition(i -2)] * w2
						 + in[seq.setPosition(i -1)] * w1
						 + in[seq.setPosition(i   )] * w0
						 + in[seq.setPosition(i +1)] * w1
						 + in[seq.setPosition(i +2)] * w2;
			}
			return;
		}
		// Optimized general case: use the CircularSequence only at the ends
		for (int i=0; i<2; ++i) {
			out[i] =   in[seq.setPosition(i -2)] * w2
					 + in[seq.setPosition(i -1)] * w1
					 + in[seq.setPosition(i   )] * w0
					 + in[seq.setPosition(i +1)] * w1
					 + in[seq.setPosition(i +2)] * w2;
		}
		final int cut = out.length -2;
		for (int i=2; i<cut; ++i) {
			out[i] =   in[i -2] * w2
					 + in[i -1] * w1
					 + in[i   ] * w0
					 + in[i +1] * w1
					 + in[i +2] * w2;
		}
		for (int i=cut; i<out.length; ++i) {
			out[i] =   in[seq.setPosition(i -2)] * w2
					 + in[seq.setPosition(i -1)] * w1
					 + in[seq.setPosition(i   )] * w0
					 + in[seq.setPosition(i +1)] * w1
					 + in[seq.setPosition(i +2)] * w2;
		}
	}

	/** Copied from ImageJ's ij.gui.PolygonRoi.getInterpolatedPolygon, by Wayne Rasband and collaborators.
	 * The reason I copied this method is that creating a new PolygonRoi just to get an interpolated polygon
	 * processes the float[] arrays of the coordinates, subtracting the minimum x,y. Not only it is an extra
	 * operation but it is also in place, altering data arrays. Fortunately FloatPolygon does not touch the arrays. */
	final static public FloatPolygon createInterpolatedPolygon(
			final FloatPolygon p,
			final double interval,
			final boolean isLine) {
		final double length = p.getLength(isLine);
		final int npoints2 = (int)((length*1.2)/interval);
		final float[] xpoints2 = new float[npoints2];
		final float[] ypoints2 = new float[npoints2];
		xpoints2[0] = p.xpoints[0];
		ypoints2[0] = p.ypoints[0];
		int n=1, n2;
		final double inc = 0.01;
		double distance=0.0, distance2=0.0, dx=0.0, dy=0.0, xinc, yinc;
		double x, y, lastx, lasty, x1, y1, x2=p.xpoints[0], y2=p.ypoints[0];
		int npoints = p.npoints;
		if (!isLine) npoints++;
		for (int i=1; i<npoints; i++) {
			x1=x2; y1=y2;
			x=x1; y=y1;
			if (i<p.npoints) {
				x2=p.xpoints[i];
				y2=p.ypoints[i];
			} else {
				x2=p.xpoints[0];
				y2=p.ypoints[0];
			}
			dx = x2-x1;
			dy = y2-y1;
			distance = Math.sqrt(dx*dx+dy*dy);
			xinc = dx*inc/distance;
			yinc = dy*inc/distance;
			lastx=xpoints2[n-1]; lasty=ypoints2[n-1];
			//n2 = (int)(dx/xinc);
			n2 = (int)(distance/inc);
			if (npoints==2) n2++;
			do {
				dx = x-lastx;
				dy = y-lasty;
				distance2 = Math.sqrt(dx*dx+dy*dy);
				//IJ.log(i+"   "+IJ.d2s(xinc,5)+"   "+IJ.d2s(yinc,5)+"   "+IJ.d2s(distance,2)+"   "+IJ.d2s(distance2,2)+"   "+IJ.d2s(x,2)+"   "+IJ.d2s(y,2)+"   "+IJ.d2s(lastx,2)+"   "+IJ.d2s(lasty,2)+"   "+n+"   "+n2);
				if (distance2>=interval-inc/2.0 && n<xpoints2.length-1) {
					xpoints2[n] = (float)x;
					ypoints2[n] = (float)y;
					//IJ.log("--- "+IJ.d2s(x,2)+"   "+IJ.d2s(y,2)+"  "+n);
					n++;
					lastx=x; lasty=y;
				}
				x += xinc;
				y += yinc;
			} while (--n2>0);
		}
		return new FloatPolygon(xpoints2, ypoints2, n);
	}
}