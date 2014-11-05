package ini.trakem2.imaging;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.StackStatistics;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.imaging.filters.EqualizeHistogram;
import ini.trakem2.imaging.filters.IFilter;
import ini.trakem2.parallel.Process;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ContrastEnhancerWrapper {

	private final ContrastEnhancer ce = new ContrastEnhancer();

	private Patch reference = null;
	private ImageStatistics reference_stats = null;

	private double saturated = 0.4;
	private boolean normalize = true;
	private boolean equalize = false;
	private int stats_mode = 0; // Stack Histogram
	private boolean use_full_stack = false;
	private boolean from_existing_min_and_max = false;
	private boolean visible_only = true;

	final ExecutorService waiter = Utils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "contrast-enhancer-waiter");

	public ContrastEnhancerWrapper() {
		this(null);
	}
	public ContrastEnhancerWrapper(final Patch reference) {
		this.reference = reference;
		if (null != reference) {
			ImageProcessor ip = reference.getImageProcessor();
			reference_stats = ImageStatistics.getStatistics(ip, Measurements.MIN_MAX, reference.getLayer().getParent().getCalibrationCopy());
		}
	}

	private String[] getChoices() {
		return null == reference ?
			 new String[]{"Stack Histogram", "Each image histogram"}
		       : new String[]{"Stack Histogram", "Each image histogram", "Active Patch Histogram"};
	}

	/**
	 * @param stats_mode can be 0==stack histogram, 1==each image's histogram, and 2==reference Patch histogram.
	 *
	 **/
	public void set(double saturated, boolean normalize, boolean equalize, int stats_mode, boolean use_full_stack, boolean from_existing_min_and_max, boolean visible_only) throws Exception {
		if (null == reference && 2 == stats_mode) throw new IllegalArgumentException("Need a non-null reference Patch to use 2==stats_mode !");

		this.saturated = saturated;
		set("saturated", saturated);
		this.normalize = normalize;
		set("normalize", normalize);
		this.equalize = equalize;
		set("equalize", equalize);

		this.stats_mode = stats_mode;
		this.use_full_stack = use_full_stack;
		if (from_existing_min_and_max && null != reference) {
			// recreate reference_stats
			ImageProcessor ip = reference.getImageProcessor();
			ip.setMinAndMax(reference.getMin(), reference.getMax());
			reference_stats = ImageStatistics.getStatistics(ip, Measurements.MIN_MAX, reference.getLayer().getParent().getCalibrationCopy());
		}
		this.from_existing_min_and_max = from_existing_min_and_max;
		this.visible_only = visible_only;
	}

	/** Uses the @param reference Patch as the one to extract the reference histogram from.
	 *  Otherwise will use the stack histogram.
	 *  @return false when canceled. */
	public boolean showDialog() {
		GenericDialog gd = new GenericDialog("Enhance Contrast");
		gd.addNumericField("Saturated Pixels:", saturated, 1, 4, "%");
		gd.addCheckbox("Normalize", normalize);
		gd.addCheckbox("Equalize Histogram", equalize);
		final String[] choices = getChoices();
		gd.addChoice("Use:", choices, choices[stats_mode]);
		gd.addCheckbox("Use full stack", use_full_stack);
		gd.addCheckbox("From existing min and max", from_existing_min_and_max);
		gd.addCheckbox("Visible images only", visible_only);

		gd.showDialog();
		if (gd.wasCanceled()) return false;

		try {
			set(gd.getNextNumber(), gd.getNextBoolean(), gd.getNextBoolean(),
			    gd.getNextChoiceIndex(), gd.getNextBoolean(), gd.getNextBoolean(),
			    gd.getNextBoolean());
		} catch (Exception e) {
			IJError.print(e);
			return false;
		}
		return true;
	}

	private void set(String field, Object value) throws Exception {
		Field f = ContrastEnhancer.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(ce, value);
	}

	public boolean applyLayerWise(final Collection<Layer> layers) {
		boolean b = true;
		for (final Layer layer : layers) {
			if (Thread.currentThread().isInterrupted()) return false;
			b = b && apply(layer.getDisplayables(Patch.class, visible_only));
			// Wait until all mipmaps have regenerated:
			try {
				waiter.submit(new Runnable() { public void run() { /*buh!*/ } }).get();
			} catch (Exception e) {
				IJError.print(e);
				return false;
			}
		}
		return b;
	}

	public boolean apply(final Collection<Displayable> patches_) {
		if (null == patches_) return false;

		// Create appropriate patch list
		ArrayList<Patch> patches = new ArrayList<Patch>();
		for (final Displayable d : patches_) {
			if (d.getClass() == Patch.class) patches.add((Patch)d);
		}
		if (0 == patches.size()) return false;

		// Check that all images are of the same size and type
		Patch firstp = (Patch) patches.get(0);
		final int ptype = firstp.getType();
		final double pw = firstp.getOWidth();
		final double ph = firstp.getOHeight();

		for (final Patch p : patches) {
			if (p.getType() != ptype) {
				// can't continue
				Utils.log("Can't homogenize histograms: images are not all of the same type.\nFirst offending image is: " + p);
				return false;
			}
			if (!equalize && 0 == stats_mode && p.getOWidth() != pw || p.getOHeight() != ph) {
				Utils.log("Can't homogenize histograms: images are not all of the same size.\nFirst offending image is: " + p);
				return false;
			}
		}

		try {
			if (equalize) {
				for (final Patch p : patches) {
					if (Thread.currentThread().isInterrupted()) return false;
					p.appendFilters(new IFilter[]{new EqualizeHistogram()});
					/*
					p.getProject().getLoader().releaseToFit(p.getOWidth(), p.getOHeight(), p.getType(), 3);
					ImageProcessor ip = p.getImageProcessor().duplicate(); // a throw-away copy
					if (this.from_existing_min_and_max) {
						ip.setMinAndMax(p.getMin(), p.getMax());
					}
					ce.equalize(ip);
					p.setMinAndMax(ip.getMin(), ip.getMax());
					*/

					// submit for regeneration
					p.getProject().getLoader().decacheImagePlus(p.getId());
					regenerateMipMaps(p);
				}
				return true;
			}

			// Else, call stretchHistogram with an appropriate stats object

			final ImageStatistics stats;

			if (1 == stats_mode) { // use each image independent stats
				stats = null;
			} else if (0 == stats_mode) { // use stack statistics
				final ArrayList<Patch> sub = new ArrayList<Patch>();
				if (use_full_stack) {
					sub.addAll(patches);
				} else {
					// build stack statistics, ordered by stdDev
					final SortedMap<Stats,Patch> sp = Collections.synchronizedSortedMap(new TreeMap<Stats,Patch>());
					Process.progressive(
							patches,
							new TaskFactory<Patch, Stats>() {
								public Stats process(final Patch p) {
									if (Thread.currentThread().isInterrupted()) return null;
									ImagePlus imp = p.getImagePlus();
									p.getProject().getLoader().releaseToFit(p.getOWidth(), p.getOHeight(), p.getType(), 2);
									Stats s = new Stats(imp.getStatistics());
									sp.put(s, p);
									return s;
								}
							});
					if (Thread.currentThread().isInterrupted()) return false;
					final ArrayList<Patch> a = new ArrayList<Patch>(sp.values());
					final int count = a.size();
					if (count < 3) {
						sub.addAll(a);
					} else if (3 == count) {
						sub.add(a.get(1)); // the middle one
					} else if (4 == count ) {
						sub.addAll(a.subList(1, 3));
					} else if (count > 4) {
						int first = (int)(count / 4.0 + 0.5);
						int last = (int)(count / 4.0 * 3 + 0.5);
						sub.addAll(a.subList(first, last));
					}
				}
				stats = new StackStatistics(new PatchStack(sub.toArray(new Patch[sub.size()]), 1));
			} else {
				stats = reference_stats;
			}

			final Calibration cal = patches.get(0).getLayer().getParent().getCalibrationCopy();

			Process.progressive(
					patches,
					new TaskFactory<Patch, Object>() {
						public Object process(final Patch p) {
							if (Thread.currentThread().isInterrupted()) return null;
							p.getProject().getLoader().releaseToFit(p.getOWidth(), p.getOHeight(), p.getType(), 3);
							ImageProcessor ip = p.getImageProcessor().duplicate(); // a throw-away copy
							if (ContrastEnhancerWrapper.this.from_existing_min_and_max) {
								ip.setMinAndMax(p.getMin(), p.getMax());
							}
							ImageStatistics st = stats;
							if (null == stats) {
								Utils.log2("Null stats, using image's self");
								st = ImageStatistics.getStatistics(ip, Measurements.MIN_MAX, cal);
							}
							ce.stretchHistogram(ip, saturated, st);
							// This is all we care about from stretching the histogram:
							p.setMinAndMax(ip.getMin(), ip.getMax());

							regenerateMipMaps(p);
							return null;
						}
					});
		} catch (Exception e) {
			IJError.print(e);
			return false;
		}

		return true;
	}

	private void regenerateMipMaps(final Patch p) {
		// submit for regeneration
		final Future<?> fu = p.getProject().getLoader().regenerateMipMaps(p);
		// ... and when done, decache any images
		tasks.add(waiter.submit(new Runnable() {
			public void run() {
				if (null != fu) {
					try {
						fu.get();
					} catch (Exception e) {
						IJError.print(e);
					}
				}
				p.getProject().getLoader().decacheAWT(p.getId());
			}
		}));
	}

	final Vector<Future<?>> tasks = new Vector<Future<?>>();

	/** Waits until all tasks have finished executing. */
	public void shutdown() {
		// Add a job at the end of the queue that closes the queue
		tasks.add(waiter.submit(new Runnable() {
			public void run() {
				waiter.shutdown();
				tasks.clear();
			}
		}));
		// ... and wait until all tasks are executed
		for (Future<?> fu : new Vector<Future<?>>(tasks)) {
			if (null != fu) {
				try {
					fu.get();
				} catch (InterruptedException ie) {
					waiter.shutdownNow();
					tasks.clear();
					return;
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		}
	}

	static private class Stats implements Comparable<Stats> {
		ImageStatistics s;
		Stats(ImageStatistics s) {
			this.s = s;
		}
		public int compareTo(Stats o) {
			return (int)(o.s.stdDev - this.s.stdDev);
		}
	}
}
