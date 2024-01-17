/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ini.trakem2.display;

import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;

import features.ComputeCurvatures;
import ij.ImagePlus;
import ij.measure.Calibration;
import ini.trakem2.Project;
import ini.trakem2.display.Polyline.TraceParameters;
import ini.trakem2.imaging.LayerStack;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT.SearchImageType;
import sc.fiji.snt.SearchProgressCallback;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.tracing.TracerThread;
import sc.fiji.snt.tracing.cost.Cost;
import sc.fiji.snt.tracing.cost.Reciprocal;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;

/**
 * Helper class to encapsulate access to {@code org.morphonets:SNT}.
 * <p>
 * In this way, if SNT is not present on the classpath, this class will fail to
 * load, and {@link NoClassDefFoundError} can be caught and handled at runtime.
 * </p>
 */
class SNTFunctions {

	static void trace(final Polyline line, final Project project,
		final Layer layer, final Display display, final MouseEvent me,
		final int x_pd, final int y_pd)
	{
		// Use Mark Longair's tracing: from the clicked point to the last one

		// Check that there are any images -- otherwise may hang. TODO
		if (line.layer_set.getDisplayables(Patch.class).isEmpty()) {
			Utils.log("No images are present!");
			return;
		}

		final double scale = line.layer_set.getVirtualizationScale();
		// Ok now with all found images, create a virtual stack that provides access to them all, with caching.
		final Worker[] worker = new Worker[2];

		final TraceParameters tr_ = Polyline.tr_map.get(line.layer_set);
		final TraceParameters tr = null == tr_ ? new TraceParameters() : tr_;
		if (null == tr_) {
			synchronized (Polyline.tr_map) {
				Polyline.tr_map.put(line.layer_set, tr);
			}
		}

		if (tr.update) {
			worker[0] = new Worker("Preparing Hessian...") { @Override
		          public void run() {
				startedWorking();
				try {
			Utils.log("Push ESCAPE key to cancel autotrace anytime.");
			final ImagePlus virtual = new LayerStack(line.layer_set, scale, ImagePlus.GRAY8, Patch.class, display.getDisplayChannelAlphas(), Segmentation.fmp.SNT_invert_image).getImagePlus();
			//virtual.show();
			final Calibration cal = virtual.getCalibration();
			double minimumSeparation = 1;
			if (cal != null) minimumSeparation = Math.min(cal.pixelWidth,
								      Math.min(cal.pixelHeight,
									       cal.pixelDepth));
			final ComputeCurvatures hessian = new ComputeCurvatures(virtual, minimumSeparation, null, cal != null);
			hessian.run();

			tr.virtual = virtual;
			//tr.scale = scale;
			tr.hessian = hessian;
			tr.update = false;

				} catch (final Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}};
			Bureaucrat.createAndStart(worker[0], project);
		}

		final Point2D.Double po = line.transformPoint(line.p[0][line.n_points-1], line.p[1][line.n_points-1]);
		final int start_x = (int)po.x;
		final int start_y = (int)po.y;
		final int start_z = line.layer_set.indexOf(line.layer_set.getLayer(line.p_layer[line.n_points-1])); // 0-based
		final int goal_x = (int)(x_pd * scale); // must transform into virtual space
		final int goal_y = (int)(y_pd * scale);
		final int goal_z = line.layer_set.indexOf(layer);

		/*
		Utils.log2("x_pd, y_pd : " + x_pd + ", " + y_pd);
		Utils.log2("scale: " + scale);
		Utils.log2("start: " + start_x + "," + start_y + ", " + start_z);
		Utils.log2("goal: " + goal_x + "," + goal_y + ", " + goal_z);
		Utils.log2("virtual: " + tr.virtual);
		*/


		final boolean simplify = me.isAltDown();

		worker[1] = new Worker("Tracer - waiting on hessian") { @Override
		      public void run() {
			startedWorking();
			try {
			if (null != worker[0]) {
				// Wait until hessian is ready
				worker[0].join();
			}
			setTaskName("Tracing path");

			@SuppressWarnings({"rawtypes", "unchecked"})
			final RandomAccessibleInterval<? extends RealType<?>> image =
				(RandomAccessibleInterval) ImageJFunctions.wrap(tr.virtual);
			final Calibration calibration = tr.virtual.getCalibration();
			final int timeoutSeconds = 120;
			final int reportEveryMilliseconds = 2000;
			final SearchImageType searchImageType = SearchImageType.ARRAY;
			final Cost costFunction = new Reciprocal(0, 255);
			final Heuristic heuristic = new Euclidean(calibration);
			tr.tracer = new TracerThread(image, calibration,
							       start_x, start_y, start_z,
							       goal_x, goal_y, goal_z,
							       timeoutSeconds, reportEveryMilliseconds,
							       searchImageType,
							       costFunction, heuristic);

			((TracerThread) tr.tracer).addProgressListener(new SearchProgressCallback() {
				@Override
		              public void pointsInSearch(final SearchInterface source, final long inOpen, final long inClosed) {
					worker[1].setTaskName("Tracing path: open=" + inOpen + " closed=" + inClosed);
				}
				@Override
		              public void finished(final SearchInterface source, final boolean success) {
					if (!success) {
						Utils.logAll("Could NOT trace a path");
					}
				}
				@Override
		              public void threadStatus(final SearchInterface source, final int currentStatus) {
					// This method gets called every reportEveryMilliseconds
					if (worker[1].hasQuitted() && source instanceof Thread) {
						((Thread) source).interrupt();
					}
				}
			});

			((TracerThread) tr.tracer).run();

			final Path result = ((TracerThread) tr.tracer).getResult();

			tr.tracer = null;

			if (null == result) {
				Utils.log("Finding a path failed"); //: "+
					// not public //SearchThread.exitReasonStrings[tracer.getExitReason()]);
				return;
			}

			// TODO: precise_x_positions etc are likely to be broken (calibrated or something)


			// Remove bogus points: those at the end with 0,0 coords
			int len = result.size();
			final double[][] pos = new double[3][len];
			for (int i = len - 1; i > -1; i--) {
				pos[0][i] = result.getXUnscaledDouble(i);
				pos[1][i] = result.getYUnscaledDouble(i);
				pos[2][i] = result.getZUnscaledDouble(i);
			}
			for (int i=len-1; i>-1; i--) {
				if (0 == pos[0][i] && 0 == pos[1][i]) {
					len--;
				} else break;
			}
			// Transform points: undo scale, and bring to this Polyline AffineTransform:
			final AffineTransform aff = new AffineTransform();
			/* Inverse order: */
			/* 2 */ aff.concatenate(line.at.createInverse());
			/* 1 */ aff.scale(1/scale, 1/scale);
			final double[] po = new double[len * 2];
			for (int i=0, j=0; i<len; i++, j+=2) {
				po[j] = pos[0][i];
				po[j+1] = pos[1][i];
			}
			final double[] po2 = new double[len * 2];
			aff.transform(po, 0, po2, 0, len); // what a stupid format: consecutive x,y pairs

			long[] p_layer_ids = new long[len];
			double[] pox = new double[len];
			double[] poy = new double[len];
			for (int i=0, j=0; i<len; i++, j+=2) {
				p_layer_ids[i] = line.layer_set.getLayer((int)pos[2][i]).getId(); // z_positions in 0-(N-1), not in 1-N like slices!
				pox[i] = po2[j];
				poy[i] = po2[j+1];
			}

			// Simplify path: to steps of 5 calibration units, or 5 pixels when not calibrated.
			if (simplify) {
				setTaskName("Simplifying path");
				final Object[] ob = Polyline.simplify(pox, poy, p_layer_ids, 10000, line.layer_set);
				pox = (double[])ob[0];
				poy = (double[])ob[1];
				p_layer_ids = (long[])ob[2];
				len = pox.length;
			}

			// Record the first newly-added autotraced point index:
			line.last_autotrace_start = line.n_points;
			line.appendPoints(pox, poy, p_layer_ids, len);

			line.repaint(true, null);
			Utils.logAll("Added " + len + " new points.");

			} catch (final Exception e) { IJError.print(e); }
			finishedWorking();
		}};
		Bureaucrat.createAndStart(worker[1], project);

		Polyline.index = -1;
	}

}
