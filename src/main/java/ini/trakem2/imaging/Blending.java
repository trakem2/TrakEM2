/**
 *
 */
package ini.trakem2.imaging;

import ij.process.ByteProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.transform.TransformMesh;

/** Utility functions for blending images together, to remove contrast seams.
 *  Inspired and guided by Stephan Preibisch's blending functions in his Stitching plugins. */
public final class Blending {

	static public final Bureaucrat blend(final List<Layer> layers, final boolean respect_current_mask, final Filter<Patch> filter) {
		return Bureaucrat.createAndStart(
				new Worker.Task("Blending layer-wise") {
					@Override
                    public void exec() {
						blendLayerWise(layers, respect_current_mask, filter);
					}
				}, layers.get(0).getProject());
	}

	static public final void blendLayerWise(final List<Layer> layers, final boolean respect_current_mask, final Filter<Patch> filter) {
		for (final Layer layer : layers) {
			final List<Patch> patches = layer.getAll(Patch.class);
			final Set<Patch> s = new HashSet<Patch>();
			if (null == filter) {
				s.addAll(patches);
			} else {
				for (final Iterator<Patch> it = patches.iterator(); it.hasNext(); ) {
					final Patch p = it.next();
					if (filter.accept(p)) s.add(p);
				}
			}
			blendPatches(s, respect_current_mask);
		}
	}

	/** For each file, find the weight for the alpha mask according to
	 *  wether the pixel overlaps with other images (weighted alpha
	 *  dependent on the distante to the image border and of that on
	 *  the other images) or not (full alpha).
	 *  An image that doesn't overlap at all gets no alpha set at all.
	 */
	static public final Bureaucrat blend(final Set<Patch> patches, final boolean respect_current_mask) {
		if (null == patches || patches.size() < 2) return null;

		return Bureaucrat.createAndStart(
			new Worker.Task("Blending images") {
				@Override
                public void exec() {
					blendPatches(patches, respect_current_mask);
				}
			}, patches.iterator().next().getProject());
	}

	static public final void blendPatches(final Set<Patch> patches, final boolean respect_current_mask) {
		ExecutorService exe = null;
		try {
			if (null == patches || patches.size() < 2) return;

			final Layer layer = patches.iterator().next().getLayer();

			for (final Patch p : patches) {
				if (null != p.getCoordinateTransform()) {
					Utils.log("CANNOT blend: at least one image has a coordinate transform.\nBlending of coordinate-transformed images will be enabled in the near future.");
					return;
				}
				if (p.getLayer() != layer) {
					Utils.log("CANNOT blend: all images must belong to the same layer!\n  Otherwise the overlap cannot be computed.");
					return;
				}
			}

			final HashMap<Patch,TransformMesh> meshes = new HashMap<Patch,TransformMesh>();
			for (final Patch p : patches) {
				meshes.put(p, null == p.getCoordinateTransform() ? null
						: new TransformMesh(p.getCoordinateTransform(), p.getMeshResolution(), p.getOWidth(), p.getOHeight()));
			}

			exe = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
			final List<Future<?>> futures = Collections.synchronizedList(new ArrayList<Future<?>>());
			final List<Future<?>> futures2 = Collections.synchronizedList(new ArrayList<Future<?>>());


			// Cache the indices that determine overlap order within the layer
			final HashMap<Patch,Integer> indices = new HashMap<Patch,Integer>();
			int i = 0;
			for (final Displayable d : layer.getDisplayables()) {
				if (d.getClass() == Patch.class && patches.contains((Patch)d)) {
					indices.put((Patch)d, i);
				}
				i += 1;
			}

			for (final Patch p : patches) {
				if (Thread.currentThread().isInterrupted()) break;
				futures.add(exe.submit(new Runnable() { @Override
                public void run() {
					final int pLayerIndex = indices.get(p);
					final Set<Patch> overlapping = new HashSet<Patch>();
					for (final Patch op : patches) {
						if (indices.get(op) < pLayerIndex) overlapping.add(op);
					}
					if (setBlendingMask(p, overlapping, meshes, respect_current_mask)) {
						futures2.add(p.updateMipMaps());
					}
				}}, null));
			}

			// join all:
			Utils.waitIfAlive(futures, false);
			Utils.waitIfAlive(futures2, false);

		} catch (final Exception e) {
			IJError.print(e);
		} finally {
			if (null != exe) exe.shutdown();
			Display.repaint();
		}
	}

	/** Returns true if a new mask has been set to Patch p. */
	static private boolean setBlendingMask(final Patch p, Set<Patch> overlapping, final Map<Patch,TransformMesh> meshes, final boolean respect_current_mask) {

		Utils.log2("Blending " + p);

		if (overlapping.contains(p)) {
			overlapping = new HashSet<Patch>(overlapping);
			overlapping.remove(p);
		}

		final AffineTransform at = p.getAffineTransform();
		final TransformMesh mesh = meshes.get(p);

		ByteProcessor mask = null;
		if (respect_current_mask) {
			mask = p.getAlphaMask();
		}
		if (null == mask) {
			mask = new ByteProcessor(p.getOWidth(), p.getOHeight());
			mask.setValue(255);
			mask.fill();
		}

		final byte[] pix = (byte[]) mask.getPixels();

		final Point2D.Double po = new Point2D.Double();
		final double[] fo = new double[2];

		final int p_o_width = p.getOWidth();
		final int p_o_height = p.getOHeight();

		int next = 0;
		final double[] weights = new double[overlapping.size() + 1]; // the self as well
		int masked = 0;

		for (int y=0; y<p_o_height; y++) {

			if (Thread.currentThread().isInterrupted()) return false;

			for (int x=0; x<p_o_width; x++) {

				// transform x,y to world coords
				if (null != mesh) {
					fo[0] = x;
					fo[1] = y;
					mesh.applyInPlace(fo);
					po.x = fo[0];
					po.y = fo[1];
				} else {
					po.x = x;
					po.y = y;
				}

				at.transform(po, po);

				fo[0] = po.x;
				fo[1] = po.y;

				// debug:
				if (0 == x && 0 == y) {
					Utils.log2("point 0,0 goes to " + fo[0] + ", " + fo[1]);
				}

				// check if it intersects any Patch
				next = 0;
				for (final Patch other : overlapping) {
					final double weight = intersects(fo, other, meshes.get(other));
					if (weight > 0) weights[next++] = weight;
				}

				final int i = y * p_o_width + x;

				if (respect_current_mask) {
					// Don't compute if no overlap or if current mask value is zero
					if (next > 0 && pix[i] != 0) {
						weights[next++] = computeWeight(x, y, p_o_width, p_o_height); // the weight of Patch p, added last
						double sum = 0;
						for (int f=0; f<next; f++) sum += weights[f];
						pix[i] = (byte)((int)(255 * (weights[next-1] / sum) * ((pix[i]&0xff) / 255.0f) ));
						masked++;
					}
					// else leave current value untouched
				} else if (next > 0) {
					// Overwritting current mask
					weights[next++] = computeWeight(x, y, p_o_width, p_o_height); // the weight of Patch p, added last
					double sum = 0;
					for (int f=0; f<next; f++) sum += weights[f];
					pix[i] = (byte)((int)(255 * (weights[next-1] / sum)));
					masked++;
				}
			}
		}

		Utils.log2("Masked = " + masked + " for " + p);

		if (masked > 0) {
			p.setAlphaMask(mask);

			//new ij.ImagePlus("mask for " + p.getId(), mask).show();

			return true;
		}


		Utils.log("Nothing to blend in image " + p);

		return false;
	}

	static private final double computeWeight(final double x, final double y, final int width, final int height) {
		//return Math.min(Math.min(x, width - x),
		//		Math.min(y, height - y));
		// Normalized, as suggested by Stephan Preibisch:
		return (Math.min(x, width - x) / (width/2)) * (Math.min(y, height - y) / (height/2));
	}

	/** Returns true if fo[0,1] x,y world coords intersect the affine and potentially coordinate transformed pixels of the other Patch. */
	static private double intersects(final double[] fo, final Patch other, final TransformMesh mesh) {
		// First inverse affine transform
		final AffineTransform at = other.getAffineTransform();
		final Point2D.Double po = new Point2D.Double(fo[0], fo[1]);
		final int o_width = other.getOWidth();
		final int o_height = other.getOHeight();
		try {
			at.inverseTransform(po, po);
		} catch (final NoninvertibleTransformException nite) {
			return -1;
		}
		if (null == mesh) {
			if (po.x >= 0 && po.x < o_width
			 && po.y >= 0 && po.y < o_height) {
				return computeWeight(po.x, po.y, o_width, o_height);
			 } else {
				 return -1;
			 }
		}
		// Then inverse the coordinate transform
		try {
			fo[0] = po.x;
			fo[1] = po.y;
			mesh.applyInverseInPlace(fo);
			return computeWeight(fo[0], fo[1], o_width, o_height);
		} catch (final NoninvertibleModelException nime) {
			// outside boundaries
			return -1;
		}
	}
}
