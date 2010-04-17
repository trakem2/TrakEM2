package ini.trakem2.utils;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;

import ij.measure.Calibration;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ByteProcessor;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.HashMap;

import marchingcubes.MCTriangulator;
import isosurface.Triangulator;

import javax.vecmath.Point3f;

import mpicbg.imglib.type.numeric.integer.ByteType;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.container.shapelist.ShapeList;
import mpicbg.imglib.container.shapelist.ShapeListCached;
import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.cursor.*;
//import mpicbg.imglib.image.display.imagej.ImageJFunctions;


public final class AreaUtils {

	private AreaUtils() {}

	/** Expects areas in local coordinates to the Displayable @param d.
	 *  @param scale The scaling of the entire universe, to limit the overall box
	 *  @param resample The optimization parameter for marching cubes (i.e. a value of 2 will scale down to half, then apply marching cubes, then scale up by 2 the vertices coordinates).
	 *  @return The List of triangles involved, specified as three consecutive vertices. A list of Point3f vertices. */
	static public List generateTriangles(final Displayable d, final double scale, final int resample_, final Map<Layer,Area> areas) {
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


			final double offset = first_layer.getZ();
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
			// Do the last layer again, capturing from slice_index+1 to +2, since the last layer has two Z planes in which it has pixels:
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
				return new ArrayList(output.values());
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
	static private final void fix3DPoints(final List list, final TreeMap<Integer,Point3f> output, final Point3f[] verts, final double la_z, final double la_thickness, final int layer_index, final float dx, final float dy, final float rsw, final float rsh, final double sz, final int n_slices) {
		int fixed = 0;
		// Find all pixels that belong to the layer, and transform them back:
		for (int i=0; i<verts.length; i++) {
			if (null != verts[i]) continue; // already processed! The unprocessed Z is merely coincident with a processed Z.
			final Point3f p = (Point3f) list.get(i);
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

	/** Extract the Area of the image for the given pixel value.
	 *  ThresholdToSelection is way faster than this, just use it. */
	static public final Area extractArea(final ImageProcessor ip, final float val) {
		final int height = ip.getHeight();
		final int width = ip.getWidth();

		final Area area = new Area();
		final ArrayList<Area> segments = new ArrayList<Area>();
		final Rectangle box = new Rectangle(0, 0, 1, 1);
		int inc = 50;

		for (int y=0; y<height; y++) {
			float prev = ip.getPixelValue(0, y);
			box.x = 0;
			box.y = y;
			box.width = val == prev ? 1 : 0;

			if (0 == y % 50) Utils.log("Done up to " + y);

			for (int x=1; x<width; x++) {

				float pix = ip.getPixelValue(x, y);

				if (pix == val) {
					if (prev == val) {
						box.width++;
						continue;
					} else {
						// start box
						box.x = x;
						prev = pix;
						box.width = 1;
						continue;
					}
				} else if (box.width > 0) {
					// break current box and add it
					segments.add(new Area(box));
					// Reset
					box.x = x;
					box.width = 0;
				}
			}

			// At end of line, add the last
			if (box.width > 0) segments.add(new Area(box));

			if (segments.size() > 32) {
				final Area a = new Area(segments.get(0));
				for (int i=segments.size()-1; i>0; i--) a.add(segments.get(i));
				area.add(a);
				segments.clear();
			}
		}

		if (segments.size() > 0) {
			final Area a = new Area(segments.get(0));
			for (int i=segments.size()-1; i>0; i--) a.add(segments.get(i));
			area.add(a);
		}

		Utils.log2("Done!");

		return area;
	}
}
