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

import marchingcubes.MCTriangulator;
import isosurface.Triangulator;

import javax.vecmath.Point3f;

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

		// remove translation from a copy of this Displayable's AffineTransform
		final AffineTransform at_translate = new AffineTransform();
		at_translate.translate(-r.x, -r.y);
		aff.preConcatenate(at_translate);
		// incorporate resampling scaling into the transform
		final AffineTransform atK = new AffineTransform();
		//Utils.log("resample: " + resample + "  scale: " + scale);
		final double K = (1.0 / resample) * scale; // 'scale' is there to limit gigantic universes
		atK.scale(K, K);
		aff.preConcatenate(atK);
		//
		final Calibration cal = layer_set.getCalibrationCopy();

		//
		ImageStack stack = null;
		final int w = (int)Math.ceil(r.width * K);
		final int h = (int)Math.ceil(r.height * K);

		// For the thresholding after painting an scaled-down area into an image:
		final int threshold;
		if (K > 0.8) threshold = 200;
		else if (K > 0.5) threshold = 128;
		else if (K > 0.3) threshold = 75; // 75 gives upper 70% of 255 range. It's better to blow up a bit, since resampling down makes marching cubes undercut the mesh.
		else threshold = 40;

		Layer first_layer = null;

		final HashSet<Layer> empty_layers = new HashSet<Layer>();

		for (final Layer la : layer_set.getLayers()) { // layers sorted by Z ASC
			if (0 == n) break; // no more areas to paint
			final Area area = areas.get(la);
			if (null != area) {
				if (null == stack) {
					stack = new ImageStack(w, h);
					first_layer = la;
				}
				d.getProject().getLoader().releaseToFit(w, h, ImagePlus.GRAY8, 3);
				// must be a new image, for pixel array is shared with BufferedImage and ByteProcessor in java 1.6
				final BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
				final Graphics2D g = bi.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(Color.white);
				g.fill(area.createTransformedArea(aff));
				final ByteProcessor bp = new ByteProcessor(bi);
				bp.threshold(threshold);
				stack.addSlice(Double.toString(la.getZ()), bp);
				bi.flush();

				n--;

			} else if (null != stack) {
				// add a black slice
				stack.addSlice(la.getZ() + "", new ByteProcessor(w, h));
				empty_layers.add(la);
			}
		}

		// zero-pad stack
		// No need anymore: MCTriangulator does it on its own now
		stack = zeroPad(stack);

		// Still, the MCTriangulator does NOT zero pad properly

		final ImagePlus imp = new ImagePlus("", stack);
		imp.getCalibration().pixelWidth = 1; // ensure all set to 1.
		imp.getCalibration().pixelHeight = 1;
		imp.getCalibration().pixelDepth = 1;

		// Now marching cubes
		final Triangulator tri = new MCTriangulator();
		final List list = tri.getTriangles(imp, 0, new boolean[]{true, true, true}, 1);


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

		// Correct x,y by resampling and calibration, but not scale (TODO this hints that calibration in 3D is wrong: should be divided by the scale! May affect all Displayable types)
		final float rsw = (float)(resample * cal.pixelWidth);  // scale is already in the pixel coordinates
		final float rsh = (float)(resample * cal.pixelHeight);
		final double sz = scale * cal.pixelWidth; // no resampling in Z. and Uses pixelWidth, not pixelDepth.


		int slice_index = 0;


		// debug:
		/*
		// which p.z types exist?
		final TreeSet<Float> ts = new TreeSet<Float>();
		for (final Iterator it = list.iterator(); it.hasNext(); ) {
			ts.add(((Point3f)it.next()).z);
		}
		for (final Float pz : ts) Utils.log2("A z: " + pz);
		*/

		//Utils.log2("Number of slices: " + imp.getNSlices());

		// Fix all points:
		// Read from list, modify and put into verts
		// and don't modify it if the verts already has it (it's just coincident)

		final Point3f[] verts = new Point3f[list.size()];

		final TreeMap<Integer,Point3f> output = new TreeMap<Integer,Point3f>();

		fix3DPoints(list, output, verts, layer_set.previous(layer_set.getLayer(i_first_layer)), 0, dx, dy, rsw, rsh, sz);

		//ts.remove(new Float(0));

		for (final Layer la : layer_set.getLayers().subList(i_first_layer, i_first_layer + imp.getNSlices() -2)) { // -2: it's padded

			//Utils.log2("handling slice_index: " + slice_index);
			//debug:
			//ts.remove(new Float(slice_index + 1));

			// If layer is empty, continue
			/* // YEAH don't! At least the immediate next layer would have points, like the extra Z level after last layer, to account for the thickness of the layer!
			if (empty_layers.contains(la)) {
				slice_index++;
				continue;
			}
			*/

			fix3DPoints(list, output, verts, la, slice_index + 1, dx, dy, rsw, rsh, sz);  // +1 because of padding

			slice_index++;
		}

		// The last set of vertices to process:
		// Find all pixels that belong to the layer, and transform them back:
		try {
			// Do the last layer again, capturing from slice_index+1 to +2, since the last layer has two Z planes in which it has pixels:
			Layer la = layer_set.getLayer(i_first_layer + slice_index -1); // slice_index has been ++ so no need for +1 now; rather, to get the layer, -1
			fix3DPoints(list, output, verts, la, slice_index +1, dx, dy, rsw, rsh, sz); // not +2, just +1, since it's been ++ at last step of the loop
			//ts.remove(new Float(slice_index +1));
		} catch (Exception ee) {
			IJError.print(ee);
		}

		// debug:
		//Utils.log2("Remaining p.z to process: ");
		//for (final Float pz : ts) Utils.log2("remains:   z: " + pz);

		// Handle potential errors:
		if (0 != list.size() - output.size()) {
			Utils.log2("Unprocessed/unused points: " + (list.size() - output.size()));
			for (int i=0; i<verts.length; i++) {
				if (null == verts[i]) {
					Point3f p = (Point3f) list.get(i);
					Utils.log2("verts[" + i + "] = " + p.x + ", " + p.y + ", " + p.z);
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
	 * @param la The Layer to process points for.
	 * @param layer_index The stack slice index corresponding to the Layer @param la.
	 */
	static private final void fix3DPoints(final List list, final TreeMap<Integer,Point3f> output, final Point3f[] verts, final Layer la, final int layer_index, final float dx, final float dy, final float rsw, final float rsh, final double sz) {
		final double la_z = la.getZ();
		final double la_thickness = la.getThickness();
		int next = 0;
		// Find all pixels that belong to the layer, and transform them back:
		for (int i=0; i<verts.length; i++) {
			if (null != verts[i]) continue; // already processed! The unprocessed Z is merely coincident with a processed Z.
			final Point3f p = (Point3f) list.get(i);
			final int pz = (int)(p.z + 0.05f);
			if ( pz >= layer_index && pz < layer_index + 1) {
				// correct pixel position:
				// -- The '-1' corrects for zero padding
				// -- The 'rsw','rsh' scales back to LayerSet coords
				// -- The 'dx','dy' translates back to this AreaList bounding box
				p.x = (p.x -1) * rsw + dx;
				p.y = (p.y -1) * rsh + dy;

				// The Z is more complicated: the Z of the layer, scaled relative to the layer thickness
				// -- 'offset' is the Z of the first layer, corresponding to the layer that contributed to the first stack slice.
				p.z = (float)((la_z + la_thickness * (p.z - layer_index)) * sz); // using pixelWidth, not pixelDepth!

				verts[i] = p;
				output.put(i, p);
			}
		}
		//Utils.log2("processed slice index " + layer_index + " for layer " + la);
	}

	static public final ImageStack zeroPad(final ImageStack stack) {
		int w = stack.getWidth();
		int h = stack.getHeight();
		// enlarge all processors
		ImageStack st = new ImageStack(w+2, h+2);
		for (int i=1; i<=stack.getSize(); i++) {
			ImageProcessor ip = new ByteProcessor(w+2, h+2);
			ip.insert(stack.getProcessor(i), 1, 1);
			st.addSlice(Integer.toString(i), ip);
		}
		ByteProcessor bp = new ByteProcessor(w+2, h+2);
		// insert slice at 0
		st.addSlice("0", bp, 0);
		// append slice at the end
		st.addSlice(Integer.toString(stack.getSize()+1), bp);

		return st;
	}

}
