package ini.trakem2.utils;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ImageProcessor;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.imaging.BinaryInterpolation2D;
import ini.trakem2.vector.Editions;
import ini.trakem2.vector.SkinMaker;
import ini.trakem2.vector.VectorString2D;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.vecmath.Point3f;

import marchingcubes.MCTriangulator;
//import mpicbg.imglib.algorithm.labeling.BinaryInterpolation2D; // using ini.trakem2.imaging.BinaryInterpolation2D until imglib's algorithms jar is released
import mpicbg.imglib.container.shapelist.ShapeList;
import mpicbg.imglib.container.shapelist.ShapeListCached;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.type.logic.BitType;
import mpicbg.imglib.type.numeric.integer.ByteType;


public final class AreaUtils {

	/** Project property key. */
	static public final String always_interpolate_areas_with_distance_map = "always_interpolate_areas_with_distance_map";
	
	private AreaUtils() {}

	/** Expects areas in local coordinates to the Displayable @param d.
	 *  @param scale The scaling of the entire universe, to limit the overall box
	 *  @param resample The optimization parameter for marching cubes (i.e. a value of 2 will scale down to half, then apply marching cubes, then scale up by 2 the vertices coordinates).
	 *  @return The List of triangles involved, specified as three consecutive vertices. A list of Point3f vertices. */
	static public List<Point3f> generateTriangles(final Displayable d, final double scale, final int resample_, final Map<Layer,Area> areas) {
		// in the LayerSet, layers are ordered by Z already.
		try {

			int n = areas.size();
			if (0 == n) return null;

			final int resample;
			if (resample_ <=0 ) {
				resample = 1;
				Utils.log2("Fixing zero or negative resampling value to 1.");
			} else resample = resample_;

			final LayerSet layer_set = d.getLayerSet();
			final AffineTransform aff = d.getAffineTransformCopy();
			final Rectangle r = d.getBoundingBox(null);

			// remove translation from a copy of the Displayable's AffineTransform
			final AffineTransform at_translate = new AffineTransform();
			at_translate.translate(-r.x, -r.y);
			aff.preConcatenate(at_translate);
			// incorporate resampling scaling into the transform
			final AffineTransform atK = new AffineTransform();
			//Utils.log("resample: " + resample + "  scale: " + scale);
			final double K = (1.0 / resample) * scale; // 'scale' is there to limit gigantic universes
			atK.scale(K, K);
			aff.preConcatenate(atK);

			final Calibration cal = layer_set.getCalibrationCopy();

			// Find first layer, compute depth, and fill in the depth vs area map
			Layer first_layer = null,
			      last_layer = null;
			final int w = (int)Math.ceil(r.width * K);
			final int h = (int)Math.ceil(r.height * K);
			int depth = 0;

			final Map<Integer,Area> ma = new HashMap<Integer,Area>();

			for (final Layer la : layer_set.getLayers()) { // layers sorted by Z ASC
				final Area area = areas.get(la);
				if (null != area) {
					ma.put(depth, area);
					if (null == first_layer) {
						first_layer = la;
					}
					//Utils.log("area at depth " + depth + " for layer " + la);
					depth++;
					n--;
				} else if (0 != depth) {
					//Utils.log("Empty area at depth " + depth);
					depth++; // an empty layer
				}
				// else, continue iterating until finding the first layer

				if (0 == n) {
					last_layer = la;
					break; // no more areas to paint
				}
			}

			if (0 == depth) {
				Utils.log("ERROR could not find any areas for " + d);
				return null;
			}
			if (0 != n) {
				Utils.log("WARNING could not find all areas for " + d);
			}

			// No zero-padding: Marching Cubes now can handle edges
			final ShapeList<ByteType> shapeList = new ShapeListCached<ByteType>(new int[]{w, h, depth}, new ByteType(), 32);
			final Image<ByteType> shapeListImage = new Image<ByteType>(shapeList, shapeList.getBackground(), "ShapeListContainer");
			final ByteType intensity = new ByteType((byte)127); // 255 or -1 don't work !? So, giving the highest value (127) that is both a byte and an int.

			for (final Map.Entry<Integer,Area> e : ma.entrySet()) {
				Area a = e.getValue();
				if (!aff.isIdentity()) {
					a = M.areaInIntsByRounding(a.createTransformedArea(aff));
				}
				shapeList.addShape(a, intensity, new int[]{e.getKey()});
			}

			//debug:
			//ImagePlus imp = ImageJFunctions.displayAsVirtualStack(shapeListImage);
			//imp.getProcessor().setMinAndMax( 0, 255 );
			//imp.show();

			//Utils.log2("Using imglib Shape List Image Container");

			// Now marching cubes
			final List<Point3f> list = new MCTriangulator().getTriangles(shapeListImage, 1, new float[3]); // origins at 0,0,0: uncalibrated


			// The list of triangles has coordinates:
			// - in x,y: in pixels, scaled by K = (1 / resample) * scale, 
			//			translated by r.x, r.y (the top-left coordinate of this AreaList bounding box)
			// - in z: in stack slice indices

			// So all x,y,z must be corrected in x,y and z of the proper layer


			//final double offset = first_layer.getZ();
			final int i_first_layer = layer_set.indexOf(first_layer);

			// The x,y translation to correct each point by:
			final float dx = (float)(r.x * scale * cal.pixelWidth);
			final float dy = (float)(r.y * scale * cal.pixelHeight);

			// Correct x,y by resampling and calibration, but not scale
			final float rsw = (float)(resample * cal.pixelWidth);  // scale is already in the pixel coordinates
			final float rsh = (float)(resample * cal.pixelHeight);
			final double sz = scale * cal.pixelWidth; // no resampling in Z. and Uses pixelWidth, not pixelDepth.


			// debug:
			/*
			// which p.z types exist?
			final TreeSet<Float> ts = new TreeSet<Float>();
			for (final Iterator it = list.iterator(); it.hasNext(); ) {
				ts.add(((Point3f)it.next()).z);
			}
			for (final Float pz : ts) Utils.log2("A z: " + pz);
			*/


			// debug: How many different Z?
			/*
			HashSet<Float> zs = new HashSet<Float>();
			for (Point3f p : list) {
				zs.add(p.z);
			}
			ArrayList<Float> a = new ArrayList<Float>(zs);
			java.util.Collections.sort(a);
			for (Float f : a) {
				Utils.log("f: " + f);
			}
			*/

			//Utils.log2("Number of slices: " + imp.getNSlices());

			// Fix all points:
			// Read from list, modify and put into verts
			// and don't modify it if the verts already has it (it's just coincident)

			final Point3f[] verts = new Point3f[list.size()];

			//Utils.log("number of verts: " + verts.length + " mod 3: " + (verts.length % 3));

			final TreeMap<Integer,Point3f> output = new TreeMap<Integer,Point3f>();


			// The first section generates vertices at -1 and 0
			// The last section generates them at last_section_index and last_section_index +1

			// Capture from -1 to 0
			fix3DPoints(list, output, verts, first_layer.getZ(), 0, -1, dx, dy, rsw, rsh, sz, 1);

			int slice_index = 0;

			for (final Layer la : layer_set.getLayers().subList(i_first_layer, i_first_layer + depth)) {

				// If layer is empty, continue
				/* // YEAH don't! At least the immediate next layer would have points, like the extra Z level after last layer, to account for the thickness of the layer!
				if (empty_layers.contains(la)) {
					slice_index++;
					continue;
				}
				*/

				fix3DPoints(list, output, verts, la.getZ(), la.getThickness(), slice_index, dx, dy, rsw, rsh, sz, 1);

				slice_index++;
			}

			// The last set of vertices to process:
			// Do the last layer again. The last layer has two Z planes in which it has pixels:
			try {
				// Capture from last_section_index to last_section_index+1, inclusive
				fix3DPoints(list, output, verts, last_layer.getZ() + last_layer.getThickness(), 0, slice_index, dx, dy, rsw, rsh, sz, 2);
			} catch (Exception ee) {
				IJError.print(ee);
			}

			//Utils.log("number of verts in output: " + output.size() + " mod 3: " + (output.size() % 3));


			// debug:
			//Utils.log2("Remaining p.z to process: ");
			//for (final Float pz : ts) Utils.log2("remains:   z: " + pz);


			// Handle potential errors:
			if (0 != list.size() - output.size()) {
				Utils.log2("Unprocessed/unused points: " + (list.size() - output.size()));
				for (int i=0; i<verts.length; i++) {
					if (null == verts[i]) {
						Point3f p = (Point3f) list.get(i);
						Utils.log2("verts[" + i + "] = " + p.x + ", " + p.y + ", " + p.z + "  p.z as int: " + ((int)(p.z + 0.05f)));
					}
				}
				return new ArrayList<Point3f>(output.values());
			} else {
				return java.util.Arrays.asList(verts);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * @param list The original points
	 * @param output The accumulated list of modified points to construct a mesh from
	 * @param verts The array of vertices, each index is filled if the point has been processed already.
	 * @param la_Z The Layer to process points for.
	 * @param la_thickness the thickness of that layer
	 * @param layer_index The stack slice index corresponding to the Layer @param la.
	 */
	static private final void fix3DPoints(final List<Point3f> list, final TreeMap<Integer,Point3f> output, final Point3f[] verts, final double la_z, final double la_thickness, final int layer_index, final float dx, final float dy, final float rsw, final float rsh, final double sz, final int n_slices) {
		int fixed = 0;
		// Find all pixels that belong to the layer, and transform them back:
		for (int i=0; i<verts.length; i++) {
			if (null != verts[i]) continue; // already processed! The unprocessed Z is merely coincident with a processed Z.
			final Point3f p = list.get(i);
			final int pz = (int)(p.z + 0.05f);
			//final int pz = (int)(p.z + (0.5f * Math.signum(p.z)));
			if ( pz >= layer_index && pz < layer_index + n_slices) {
				// correct pixel position:
				// -- The 'rsw','rsh' scales back to LayerSet coords
				// -- The 'dx','dy' translates back to this AreaList bounding box
				p.x = p.x * rsw + dx;
				p.y = p.y * rsh + dy;

				// The Z is more complicated: the Z of the layer, scaled relative to the layer thickness
				p.z = (float)((la_z + la_thickness * (p.z - layer_index)) * sz); // using pixelWidth, not pixelDepth!

				verts[i] = p;
				output.put(i, p);
				fixed++;
			}
		}
		//Utils.log("fix between " + layer_index + " and " + (layer_index + n_slices) + " (" + fixed + ")");
	}

	/** Extracts all non-background areas. */
	static public final Map<Float,Area> extractAreas(final ImageProcessor ip) {
		return extractAreas(ip, null, false, null, Thread.currentThread(), false);
	}

	/** Scan line-wise for all areas, returning a Map of area pixel values in @param ip vs. Area instances.
	 *  If @param map_ is not null, it puts the areas there and returns it.
	 *  If @param box_ is not null, it uses it as the unit pixel area. To make any sense, it must be setup as Rectangle(0,0,1,1).
	 *  If @param report is true, it will report progress every 100 lines. */
	static public final Map<Float,Area> extractAreas(final ImageProcessor ip, final HashMap<Float,Area> map_, final boolean add_background, final Rectangle box_, final Thread parent, final boolean report) {
		final int height = ip.getHeight();
		final int width = ip.getWidth();
		int inc = height / 100;
		if (inc < 10) inc = 10;

		final Map<Float,Area> map = null == map_ ? new HashMap<Float,Area>() : map_;
		final Rectangle box = null == box_ ? new Rectangle(0, 0, 1, 1) : box_;

		for (int y=0; y<height; y++) {
			if (0 == y % inc) {
				if (parent.isInterrupted()) return map;
				if (report) Utils.showStatus(new StringBuilder().append("line: ").append(y).append('/').append(height).toString());
			}

			float prev = ip.getPixelValue(0, y);
			box.x = 0;
			box.y = y;
			box.width = 0;

			for (int x=1; x<width; x++) {

				float pix = ip.getPixelValue(x, y);

				if (pix == prev) {
					box.width++;
					continue;
				} else {
					// add previous one
					if (!Float.isNaN(prev) && (add_background || 0 != prev)) {
						box.width++;
						Area area = map.get(new Float(prev));
						if (null == area) {
							area = new Area(box);
							map.put(new Float(prev), area);
						} else {
							area.add(new Area(box));
						}
					}
					// start new box
					box.x = x;
					box.y = y;
					box.width = 0;
					prev = pix;
				}
			}

			// At end of line, add the last
			if (!Float.isNaN(prev) && (add_background || 0 != prev)) {
				Area area = map.get(new Float(prev));
				if (null == area) {
					area = new Area(box);
					map.put(new Float(prev), area);
				} else {
					area.add(new Area(box));
				}
			}
		}

		return map;
	}

    static public Area infiniteArea()
    {
        final Path2D.Double path = new Path2D.Double();
        path.moveTo(Double.MAX_VALUE, Double.MAX_VALUE);
        path.lineTo(-Double.MAX_VALUE, Double.MAX_VALUE);
        path.lineTo(-Double.MAX_VALUE, -Double.MAX_VALUE);
        path.lineTo(Double.MAX_VALUE, -Double.MAX_VALUE);
        path.lineTo(Double.MAX_VALUE, Double.MAX_VALUE);

        return new Area(path);
    }

	/** Extract the Area of the image for the given pixel value.
	 *  ThresholdToSelection is way faster than this, just use it.
	 *  It's BROKEN do not use. */
	/*
	static public final Area extractArea(final ImageProcessor ip, final float val) {
		final int height = ip.getHeight();
		final int width = ip.getWidth();

		final Area area = new Area();
		final ArrayList<Area> segments = new ArrayList<Area>();
		final Rectangle box = new Rectangle(0, 0, 1, 1);

		for (int y=0; y<height; y++) {
			float prev = ip.getPixelValue(0, y);
			box.x = 0;
			box.y = y;
			box.width = val == prev ? 1 : 0;

			for (int x=1; x<width; x++) {

				float pix = ip.getPixelValue(x, y);

				if (val == pix) {
					if (pix == prev) {
						box.width++;
						continue;
					}
					// Else, add previous one
					segments.add(new Area(box));
					// ... and start a new box
					box.x = x;
					box.y = y;
					box.width = 1;
					prev = pix;
				}
			}

			// At end of line, add the last
			segments.add(new Area(box));

			// Join a few
			if (segments.size() > 32) {
				final Area a = new Area(segments.get(0));
				final int len = segments.size();
				for (int i=1; i<len; i++) a.add(segments.get(i));
				area.add(a);
				segments.clear();
			}
		}

		if (segments.size() > 0) {
			final Area a = new Area(segments.get(0));
			final int len = segments.size();
			for (int i=1; i<len; i++) a.add(segments.get(i));
			area.add(a);
		}

		return area;
	}
	*/

	/** Interpolate areas only if they are made of a single shape each.
	 *  Assumes that areas are in the same coordinate system.
	 * @throws Exception */
	static public final Area[] singularInterpolation(final Area a1, final Area a2, final int nInterpolates) throws Exception {
		if (!a1.isSingular() || !a2.isSingular()) {
			return null;
		}
		VectorString2D vs1 = M.asVectorString2D(M.getPolygons(a1).iterator().next(), 0);
		VectorString2D vs2 = M.asVectorString2D(M.getPolygons(a2).iterator().next(), 1);
		
		Editions ed = new Editions(vs1, vs2, Math.min(vs1.getAverageDelta(), vs2.getAverageDelta()), true);
		
		double[][][] d = SkinMaker.getMorphedPerimeters(vs1, vs2, nInterpolates, ed);
		
		Area[] a = new Area[d.length];
		for (int i=0; i<d.length; i++) {
			double[] x = d[i][0];
			double[] y = d[i][1];
			int[] xi = new int[x.length];
			int[] yi = new int[y.length];
			for (int k=0; k<x.length; k++) {
				xi[k] = (int) x[k];
				yi[k] = (int) y[k];
			}
			a[i] = new Area(new Polygon(xi, yi, xi.length));
		}

		return a;
	}

	static public final Area[] manyToManyInterpolation(final Area a1, final Area a2, final int nInterpolates) throws InterruptedException, ExecutionException {
		final Rectangle b = a1.getBounds();
		b.add(a2.getBounds());
		final AffineTransform translate = new AffineTransform(1, 0, 0, 1, -b.x, -b.y);

		final ShapeList<BitType> shapeList1 = new ShapeListCached<BitType>(new int[]{b.width, b.height}, new BitType(false), 32);
		shapeList1.addShape(a1.createTransformedArea(translate), new BitType(true), new int[]{0});
		final Image<BitType> img1 = new Image<BitType>(shapeList1, shapeList1.getBackground(), "ShapeListContainer");

		final ShapeList<BitType> shapeList2 = new ShapeListCached<BitType>(new int[]{b.width, b.height}, new BitType(false), 32);
		shapeList2.addShape(a2.createTransformedArea(translate), new BitType(true), new int[]{0});
		final Image<BitType> img2 = new Image<BitType>(shapeList2, shapeList2.getBackground(), "ShapeListContainer");

		final float inc = 1.0f / (nInterpolates + 1);

		final BinaryInterpolation2D interpol = new BinaryInterpolation2D(img1, img2, inc);
		if (!interpol.checkInput()) {
			System.out.println("Error: " + interpol.getErrorMessage());
			return null;
		}

		final Area[] as = new Area[nInterpolates];
		final AffineTransform back = new AffineTransform(1, 0, 0, 1, b.x, b.y);

		// TODO parallelize, which needs the means to call process() in parallel too--currently it cannot,
		// the result would get overwritten.
		
		ExecutorService exec = Executors.newFixedThreadPool(Math.min(nInterpolates, Runtime.getRuntime().availableProcessors()));
		ArrayList<Future<Area>> fus = new ArrayList<Future<Area>>();
		
		try {

			for (int i=1; i<=nInterpolates; i++) {
				final float weight = 1 - inc * i;
				fus.add(exec.submit(new Callable<Area>() {
					@Override
					public Area call() throws Exception {
						Image<BitType> imb = interpol.process(weight);
						ImagePlus imp = ImageJFunctions.copyToImagePlus(imb, ImagePlus.GRAY8);
						// BitType gets copied to 0 and 255 in 8-bit ByteProcessor
						ThresholdToSelection ts = new ThresholdToSelection();
						ts.setup("", imp);
						ImageProcessor ip = imp.getProcessor();
						ip.setThreshold(1, 255, ImageProcessor.NO_LUT_UPDATE);
						ts.run(ip);
						Roi roi = imp.getRoi();
						return null == roi ? new Area() : M.getArea(roi).createTransformedArea(back);
					}
				}));
			}

			int i = 0;
			for (Future<Area> fu : fus) {
				as[i++] = fu.get();
			}

		} catch (Throwable t) {
			IJError.print(t);
		} finally {
			exec.shutdown();
		}
		
		return as;
	}
}