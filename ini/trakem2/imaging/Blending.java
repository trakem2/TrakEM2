/**
 *
 */
package ini.trakem2.imaging;

import ini.trakem2.display.Patch;
import ini.trakem2.display.Display;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.IJError;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.TransformMesh;
import mpicbg.models.NoninvertibleModelException;

import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;

import ij.process.ByteProcessor;

import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;

/** Utility functions for blending images together, to remove contrast seams.
 *  Inspired and guided by Stephan Preibisch's blending functions in his Stitching plugins. */
public final class Blending {

	/** For each file, find the weight for the alpha mask according to
	 *  wether the pixel overlaps with other images (weighted alpha
	 *  dependent on the distante to the image border and of that on
	 *  the other images) or not (full alpha).
	 *  An image that doesn't overlap at all gets no alpha set at all.
	 */
	static public final Bureaucrat blend(final Set<Patch> patches) {
		if (null == patches || patches.size() < 2) return null;

		return Bureaucrat.createAndStart(new Worker("Blending images") {
			public void run() {
				try {
					startedWorking();

					final HashMap<Patch,TransformMesh> meshes = new HashMap<Patch,TransformMesh>();
					for (final Patch p : patches) {
						meshes.put(p, null == p.getCoordinateTransform() ? null
												 : new TransformMesh(p.getCoordinateTransform(), 32, p.getOWidth(), p.getOHeight()));
					}

					ExecutorService exe = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
					final ArrayList<FutureTask> futures = new ArrayList<FutureTask>();

					for (final Patch p : patches) {
						if (Thread.currentThread().isInterrupted()) return;
						FutureTask future = new FutureTask(new Runnable() { public void run() {
							final int pLayerIndex = p.getLayer().indexOf( p );
							final Set< Patch > overlapping = new HashSet< Patch >();
							for ( Patch op : patches )
								if ( p.getLayer().indexOf( op ) < pLayerIndex )
									overlapping.add( op );
							if (setBlendingMask(p, overlapping, meshes)) {
								p.updateMipmaps();
							}
						}}, null);
						futures.add(future);
						exe.submit(future);
					}

					// join all:
					for (final FutureTask future : futures) {
						try {
							future.get();
						} catch (InterruptedException ie) {} // thrown when canceled
					}

					exe.shutdownNow();

					Display.repaint();

				} catch (Exception e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		}, patches.iterator().next().getProject());
	}

	/** Returns true if a new mask has been set to Patch p. */
	static private boolean setBlendingMask(final Patch p, Set<Patch> overlapping, final Map<Patch,TransformMesh> meshes) {

		Utils.log2("Blending " + p);
	
		if (overlapping.contains(p)) {
			overlapping = new HashSet<Patch>(overlapping);
			overlapping.remove(p);
		}

		final AffineTransform at = p.getAffineTransform();
		final TransformMesh mesh = meshes.get(p);

		final ByteProcessor mask = new ByteProcessor(p.getOWidth(), p.getOHeight());
		final byte[] pix = (byte[]) mask.getPixels();

		final Point2D.Double po = new Point2D.Double();
		final float[] fo = new float[2];

		final int p_o_width = p.getOWidth();
		final int p_o_height = p.getOHeight();

		int next = 0;
		final float[] weights = new float[overlapping.size() + 1]; // the self as well
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

				fo[0] = (float) po.x;
				fo[1] = (float) po.y;

				// debug:
				if (0 == x && 0 == y) {
					Utils.log2("point 0,0 goes to " + fo[0] + ", " + fo[1]);
				}

				// check if it intersects any Patch
				next = 0;
				for (final Patch other : overlapping) {
					float weight = intersects(fo, other, meshes.get(other));
					if (weight > 0) weights[next++] = weight;
				}
				if (next > 0) {
					weights[next++] = computeWeight(x, y, p_o_width, p_o_height); // the weight of Patch p, added last
					float sum = 0;
					for (int f=0; f<next; f++) sum += weights[f];
					pix[y * p_o_height + x] = (byte)((int)(255 * (weights[next-1] / sum))); // using an alpha range of [0.5,1] by 0.5f + 0.5f * w does not help removing the dark bands.
					masked++;
				} else {
					// no weights, no overlap: full mask
					pix[y * p_o_height + x] = (byte) 255;
				}
			}
		}

		Utils.log2("Masked = " + masked + " for " + p);

		if (masked > 0) {
			p.setAlphaMask(mask);

			new ij.ImagePlus("mask for " + p.getId(), mask).show();

			return true;
		}

		return false;
	}

	static private final float computeWeight(final float x, final float y, final int width, final int height) {
		//return Math.min(Math.min(x, width - x),
		//		Math.min(y, height - y));
		// Normalized, as suggested by Stephan Preibisch:
		return (Math.min(x, width - x) / (width/2)) * (Math.min(y, height - y) / (height/2));
	}

	/** Returns true if fo[0,1] x,y world coords intersect the affine and potentially coordinate transformed pixels of the other Patch. */
	static private float intersects(final float[] fo, final Patch other, final TransformMesh mesh) {
		// First inverse affine transform
		final AffineTransform at = other.getAffineTransform();
		final Point2D.Double po = new Point2D.Double(fo[0], fo[1]);
		final int o_width = other.getOWidth();
		final int o_height = other.getOHeight();
		try {
			at.inverseTransform(po, po);
		} catch (NoninvertibleTransformException nite) {
			return -1;
		}
		if (null == mesh) {
			if (po.x >= 0 && po.x < o_width
			 && po.y >= 0 && po.y < o_height) {
				return computeWeight((float)po.x, (float)po.y, o_width, o_height);
			 } else {
				 return -1;
			 }
		}
		// Then inverse the coordinate transform
		try {
			fo[0] = (float) po.x;
			fo[1] = (float) po.y;
			mesh.applyInverseInPlace(fo);
			return computeWeight(fo[0], fo[1], o_width, o_height);
		} catch (NoninvertibleModelException nime) {
			// outside boundaries
			return -1;
		}
	}
}
