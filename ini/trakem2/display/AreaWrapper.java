package ini.trakem2.display;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.PathIterator;
import java.awt.geom.GeneralPath;
import java.awt.geom.Area;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;

import ini.trakem2.display.Layer;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.utils.M;
import ini.trakem2.utils.OptionPanel;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.vector.VectorString3D;

import ij.gui.GenericDialog;
import ij.gui.OvalRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.process.FloatPolygon;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AreaWrapper {

	private final Area area;
	private Painter painter = null;
	private Rectangle r_old = null;
	private Displayable source = null;

	public AreaWrapper(final Displayable source, final Area area) {
		this.source = source;
		this.area = area;
	}

	public AreaWrapper() {
		this(null, new Area());
	}

	public AreaWrapper(final Area area) {
		this(null, area);
	}

	public void setSource(final Displayable source) {
		this.source = source;
	}

	public Displayable getSource() {
		return source;
	}

	public Area getArea() {
		return area;
	}

	/** Does not set the @param area, but copies its internal data. */
	public void putData(final Area a) {
		if (this.area == a) return;
		this.area.reset();
		this.area.add(a);
	}

	/** Add an area in world coordinates. */
	public void add(final Area wa, final Layer layer) {
		try {
			this.area.add(wa.createTransformedArea(source.getAffineTransform().createInverse()));
			((AreaContainer)source).calculateBoundingBox(layer);
		} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
	}

	/** Subtract an area in world coordinates. */
	public void subtract(final Area wa, final Layer layer) {
		try {
			this.area.subtract(wa.createTransformedArea(source.getAffineTransform().createInverse()));
			((AreaContainer)source).calculateBoundingBox(layer);
		} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
	}

	/** Add an area that needs to be transformed by tmp first to bring it to world coordinates;
	 *  will MODIFY the to_world AffineTransform object. */
	public void add(final Area a, final AffineTransform to_world) {
		try {
			to_world.preConcatenate(source.getAffineTransform().createInverse());
			this.area.add(a.createTransformedArea(to_world));
		} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
	}

	public void paint(final Graphics2D g, final AffineTransform aff, final boolean fill, final Color color) {

		g.setColor(color);

		if (!area.isEmpty()) {
			if (fill) g.fill(area.createTransformedArea(aff));
			else      g.draw(area.createTransformedArea(aff));
		}

		if (null != this.painter) {
			try {
				final Area tmp = this.painter.getTmpArea();
				if (null != tmp) {
					if (fill) g.fill(tmp.createTransformedArea(aff));
					else      g.draw(tmp.createTransformedArea(aff)); // won't be perfect except on mouse release
				}
			} catch (Exception e) {}
		}
	}

	private final class Painter extends Thread {
		/** The area to paint into when done or when removing. */
		final private Area target_area;
		/** The temporary area to paint to, which is only different than target_area when adding. */
		final private Area area;
		/** The list of all painted points. */
		private final LinkedList<Point> points = new LinkedList<Point>();
		/** The last point on which a paint event was done. */
		private Point previous_p = null;
		private boolean paint = true;
		private int brush_size; // the diameter
		private final Area brush;
		final private int leftClick=16, alt=9;
		final private DisplayCanvas dc = Display.getFront().getCanvas();
		final private int flags = dc.getModifiers();
		private boolean adding = (0 == (flags & alt));
		private final Layer la;
		private final AffineTransform at, at_inv;
		private final Object arealock = new Object();
		private final ExecutorService accumulator;
		private final ScheduledExecutorService composer;
		private final ScheduledFuture composition;
		private final Runnable interpolator;

		Painter(Area area, double mag, Layer la, AffineTransform at) throws Exception {
			super("AreaWrapper.Painter");
			setPriority(Thread.NORM_PRIORITY);
			this.la = la;
			this.at = at;
			this.at_inv = at.createInverse();
			// if adding areas, make it be a copy, to be added on mouse release
			// (In this way, the receiving Area is small and can be operated on fast)
			if (adding) {
				this.target_area = area;
				this.area = new Area();
			} else {
				this.target_area = area;
				this.area = area;
			}

			brush_size = ProjectToolbar.getBrushSize();
			brush = makeBrush(brush_size, mag);
			if (null == brush) throw new RuntimeException("Can't paint with brush of size 0.");
			accumulator = Utils.newFixedThreadPool(1, "AreaWrapper-accumulator");
			composer = Executors.newScheduledThreadPool(1);
			this.interpolator = new Runnable() {
				public void run() {
					final ArrayList<Point> ps;
					final int n_points;
					synchronized (arealock) {
						n_points = points.size();
						if (0 == n_points) return;
						ps = new ArrayList<Point>(points);
						points.clear();
						points.add(ps.get(n_points -1)); // to start the next spline from the last point
					}
					if (n_points < 2) {
						// No interpolation required
						final AffineTransform atb = new AffineTransform(1, 0, 0, 1, ps.get(0).x, ps.get(0).y);
						atb.preConcatenate(at_inv);
						Area chunk = slashInInts(brush.createTransformedArea(atb));
						synchronized (arealock) {
							if (adding) Painter.this.area.add(chunk);
							else Painter.this.area.subtract(chunk);
						}
						return;
					}
					try {
						// paint the regions between points, using spline interpolation
						// A cheap way would be to just make a rectangle between both points, with thickess radius.
						// A better, expensive way is to fit a spline first, then add each one as a circle.
						// The spline way is wasteful, but way more precise and beautiful. Since there's only one repaint, it's not excessively slow.

						int[] xp = new int[ps.size()];
						int[] yp = new int[xp.length];
						int j = 0;
						for (final Point p : ps) {
							xp[j] = p.x;
							yp[j] = p.y;
							j++;
						}

						PolygonRoi proi = new PolygonRoi(xp, yp, xp.length, Roi.POLYLINE);
						proi.fitSpline();
						FloatPolygon fp = proi.getFloatPolygon();
						proi = null;

						double[] xpd = new double[fp.npoints];
						double[] ypd = new double[fp.npoints];
						// Fails: fp contains float[], which for some reason cannot be copied into double[]
						//System.arraycopy(fp.xpoints, 0, xpd, 0, xpd.length);
						//System.arraycopy(fp.ypoints, 0, ypd, 0, ypd.length);
						for (int i=0; i<xpd.length; i++) {
							xpd[i] = fp.xpoints[i];
							ypd[i] = fp.ypoints[i];
						}
						fp = null;

						// VectorString2D resampling doesn't work
						VectorString3D vs = new VectorString3D(xpd, ypd, new double[xpd.length], false);
						double delta = ((double)brush_size) / 10;
						if (delta < 1) delta = 1;
						vs.resample(delta);
						xpd = vs.getPoints(0);
						ypd = vs.getPoints(1);
						vs = null;
						// adjust first and last points back to integer precision
						Point po = ps.get(0);
						xpd[0] = po.x;
						ypd[0] = po.y;
						po = ps.get(ps.size()-1);
						xpd[xpd.length-1] = po.x;
						ypd[ypd.length-1] = po.y;

						final Area chunk = new Area();
						final AffineTransform atb = new AffineTransform();
						for (int i=0; i<xpd.length; i++) {
							atb.setToTranslation((int)xpd[i], (int)ypd[i]); // always integers
							atb.preConcatenate(at_inv);
							chunk.add(slashInInts(brush.createTransformedArea(atb)));
						}
						synchronized (arealock) {
							if (adding) Painter.this.area.add(chunk);
							else Painter.this.area.subtract(chunk);
						}

						Display.repaint(Painter.this.la, 3, r_old, false, false);

					} catch (Exception e) {
						IJError.print(e);
					}
				}
			};
			composition = composer.scheduleWithFixedDelay(interpolator, 200, 500, TimeUnit.MILLISECONDS);
			start();
		}

		final void quit() {
			if (!this.paint) return; // already quit
			this.paint = false;
			// Make interpolated points affect add or subtract operations
			synchronized (this) {
				try {

				accumulator.shutdownNow();
				composition.cancel(true);
				composer.shutdown();
				composer.awaitTermination(30, TimeUnit.SECONDS);

				if (points.size() > 1) {
					// one last time:
					interpolator.run();
				} else {
					// Just one point: no interpolation needed
					// merge the temporary Area, if any, with the general one
					if (adding) this.target_area.add(area);
					// If subtracting, it was already done
					return;
				}

				if (adding) {
					adding = false;
					this.target_area.add(area);

					// now, depending on paint mode, alter the new target area:

					if (PAINT_OVERLAP == PP.paint_mode) {
						// Nothing happens with PAINT_OVERLAP, default mode.
					} else {
						final Map<Displayable,List<Area>> other_areas = la.getParent().findAreas(la, target_area.createTransformedArea(source.getAffineTransform()).getBounds(), true);

						// prepare undo step:
						final HashMap<Displayable,Runnable> ops = PAINT_ERODE == PP.paint_mode ? new HashMap<Displayable,Runnable>() : null;

						for (final Map.Entry<Displayable,List<Area>> e : other_areas.entrySet()) {
							final Displayable d = e.getKey();
							if (source == d) continue;
							for (final Area a : e.getValue()) {
								if (this.area == a) continue;
								AffineTransform aff;
								switch (PP.paint_mode) {
									case PAINT_ERODE:
										// subtract this target_area from any other Area that overlaps with it
										aff = new AffineTransform(this.at);
										aff.preConcatenate(d.at.createInverse());
										final Area ta = target_area.createTransformedArea(aff);
										if (a.getBounds().intersects(ta.getBounds())) {
											ops.put(d, new Runnable() { public void run() { a.subtract(ta); }});
										}
										break;
									case PAINT_EXCLUDE:
										// subtract all other overlapping Area from the target_area
										aff = new AffineTransform(d.at);
										aff.preConcatenate(this.at.createInverse());
										final Area q = a.createTransformedArea(aff);
										if (q.getBounds().intersects(target_area.getBounds())) {
											target_area.subtract(q);
										}
										break;
									default:
										Utils.log2("Can't handle paint mode " + PP.paint_mode);
										break;
								}
							}
						}

						if (null != ops && ops.size() > 0) {
							source.getLayerSet().addDataEditStep(ops.keySet());
							for (final Runnable r : ops.values()) r.run();
							something_eroded = true;
						}
					}
				}
				// else do nothing, the subtract is already done

				} catch (Exception ee) {
					IJError.print(ee);
				}
			}
		}
		final Area getTmpArea() {
			if (area != target_area) return area;
			return null;
		}
		/** For best smoothness, each mouse dragged event should be captured!*/
		public void run() {
			// create brush
			while (paint) {
				// detect mouse up
				if (0 == (flags & leftClick)) { // I think this never happens, but there have been reports.
					quit();
					return;
				}
				final Point p = dc.getCursorLoc(); // as offscreen coords
				if (p.equals(previous_p) /*|| (null != previous_p && p.distance(previous_p) < brush_size/5) */) {
					try { Thread.sleep(3); } catch (InterruptedException ie) {}
					continue;
				}
				if (!la.contains(p.x, p.y, 0)) {
					// Ignoring point off srcRect
					continue;
				}
				accumulator.submit(new Runnable() {
					public void run() {
						final AffineTransform aff = new AffineTransform(1, 0, 0, 1, p.x, p.y);
						aff.concatenate(at_inv);
						final Area slash = slashInInts(brush.createTransformedArea(aff));
						synchronized (arealock) {
							if (0 == (flags & alt)) {
								// no modifiers, just add
								area.add(slash);
							} else {
								// with alt down, subtract
								area.subtract(slash);
							}
							points.add(p);
						}
						final Rectangle copy = new Rectangle(p.x - brush_size/2, p.y - brush_size/2, brush_size, brush_size);
						// repaint only the last added slash
						Display.repaint(la, 3, copy, false, false); 
						// accumulate rectangle for repainting out the brush circle
						synchronized (arealock) {
							if (null != r_old) copy.add(r_old);
							r_old = copy;
						}
					}
				});

				previous_p = p;
			}
		}

		/** Sets the bounds of the created slash, in offscreen coords, to r if r is not null. */
		/*
		private Area createSlash(final AffineTransform atb, final Rectangle r) {
				Area slash = brush.createTransformedArea(atb); // + int transform, no problem
				if (null != r) r.setRect(slash.getBounds());
				// bring to the current transform, if any
				if (!at.isIdentity()) {
					try {
						slash = slash.createTransformedArea(at.createInverse());
					} catch (NoninvertibleTransformException nite) {
						IJError.print(nite);
						return null;
					}
				}
				// avoid problems with floating-point points, for example inability to properly fill areas or delete them.
				return slashInInts(slash);
		}
		*/

		private final Area slashInInts(final Area area) {
			int[] x = new int[400];
			int[] y = new int[400];
			int next = 0;
			for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
				if (x.length == next) {
					int[] x2 = new int[x.length + 200];
					int[] y2 = new int[y.length + 200];
					System.arraycopy(x, 0, x2, 0, x.length);
					System.arraycopy(y, 0, y2, 0, y.length);
					x = x2;
					y = y2;
				}
				final float[] coords = new float[6];
				int seg_type = pit.currentSegment(coords);
				switch (seg_type) {
					case PathIterator.SEG_MOVETO:
					case PathIterator.SEG_LINETO:
						x[next] = (int)coords[0];
						y[next] = (int)coords[1];
						break;
					case PathIterator.SEG_CLOSE:
						break;
					default:
						Utils.log2("WARNING: slashInInts unhandled seg type.");
						break;
				}
				pit.next();
				if (pit.isDone()) break; // the loop
				next++;
			}
			// resize back (now next is the length):
			if (x.length == next) {
				int[] x2 = new int[next];
				int[] y2 = new int[next];
				System.arraycopy(x, 0, x2, 0, next);
				System.arraycopy(y, 0, y2, 0, next);
				x = x2;
				y = y2;
			}
			return new Area(new Polygon(x, y, next));
		}
	}

	static public Area makeMouseBrush(int diameter, double mag) {
		Display front = Display.getFront();
		Area brush = makeBrush(diameter, mag);
		if (null == front) return brush;
		Point p = front.getCanvas().getCursorLoc();
		return brush.createTransformedArea(new AffineTransform(1, 0, 0, 1, p.x, p.y));
	}

	/** This method could get tones of improvement, which should be pumped upstream into ImageJ's RoiBrush class which is creating it at every while(true) {} iteration!!!
	 * The returned area has its coordinates centered around 0,0
	 */
	static public Area makeBrush(int diameter, double mag) {
		if (diameter < 1) return null;
		if (mag >= 1) return new Area(new OvalRoi(-diameter/2, -diameter/2, diameter, diameter).getPolygon());
		// else, create a smaller brush and transform it up, i.e. less precise, less points to store -but precision matches what the eye sees, and allows for much better storage -less points.
		int screen_diameter = (int)(diameter * mag);
		if (0 == screen_diameter) return null; // can't paint at this mag with this diameter

		Area brush = new Area(new OvalRoi(-screen_diameter/2, -screen_diameter/2, screen_diameter, screen_diameter).getPolygon());
		// scale to world coordinates
		AffineTransform at = new AffineTransform();
		at.scale(1/mag, 1/mag);
		return brush.createTransformedArea(at);


		// smooth out edges
		/*
		Polygon pol = new OvalRoi(-diameter/2, -diameter/2, diameter, diameter).getPolygon();
		Polygon pol2 = new Polygon();
		// cheap and fast: skip every other point, since all will be square angles
		for (int i=0; i<pol.npoints; i+=2) {
			pol2.addPoint(pol.xpoints[i], pol.ypoints[i]);
		}
		return new Area(pol2);
		// the above works nice, but then the fill and fill-remove don't work properly (there are traces in the edges)
		// Needs a workround: before adding/substracting, enlarge the polygon to have square edges
		*/
	}

	private boolean something_eroded = false;
	private Segmentation.BlowCommander blowcommander = null;
	private List<Runnable> post_mouseReleased_tasks = null;
	// shared, and thus made null at every mouse release
	static private Integer controller_key = null;

	public void mousePressed(final MouseEvent me, Layer la, final int x_p_w, final int y_p_w, final double mag) {
		mousePressed(me, la, x_p_w, y_p_w, mag, null);
	}
	public void mousePressed(final MouseEvent me, Layer la, final int x_p_w, final int y_p_w, final double mag, final List<Runnable> post_tasks) {
		this.post_mouseReleased_tasks = post_tasks;

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.BRUSH == tool) {
			if (null != AreaWrapper.controller_key) {
				return;
			}
			if (me.isShiftDown()) {
				// fill/erase a hole/area if the clicked point lays within one
				// An area in world coords:
				Area bmin = null;
				Area bmax = null;
				final ArrayList<Area> intersecting = new ArrayList<Area>(); // a list of areas in world coords
				// Try to find a hole in this or another visible Area, but fill it this
				int min_area = Integer.MAX_VALUE;
				int max_area = 0;
				final Map<Displayable,List<Area>> other_areas = la.getParent().findAreas(la, new Rectangle(x_p_w, y_p_w, 1, 1), true);
				for (final Map.Entry<Displayable,List<Area>> e : other_areas.entrySet()) {
					final Displayable d = e.getKey();
					for (final Area a : e.getValue()) {
						// bring point to zd space
						final Point2D.Double p = d.inverseTransformPoint(x_p_w, y_p_w);
						final Polygon polygon = M.findPath(a, (int)p.x, (int)p.y);
						if (null != polygon) {
							Area bw = new Area(polygon).createTransformedArea(d.at);
							Rectangle bounds = bw.getBounds();
							int pol_area = bounds.width * bounds.height;
							if (pol_area < min_area) {
								bmin = bw;
								min_area = pol_area;
							}
							if (pol_area > max_area) {
								bmax = bw;
								max_area = pol_area;
							}
							intersecting.add(bw);
						}
					}
				}

				// Take the largest area and subtract from it all other visible areas
				if (intersecting.size() > 1) {
					Area compound = new Area(bmax);
					for (Area a : intersecting) {
						if (bmax == a) continue;
						compound.intersect(a);
					}
					if (!compound.isSingular()) {
						Polygon polygon = M.findPath(compound, x_p_w, y_p_w);
						if (null != polygon) {
							compound = new Area(polygon);
						}
					}
					Rectangle cbounds = compound.getBounds();
					int carea = cbounds.width * cbounds.height;
					if (carea < min_area) {
						min_area = carea;
						bmin = compound;
					}
				}
				// Also try to merge all visible areas in current layer and find a hole there
				final Area all = new Area(); // in world coords
				for (final Map.Entry<Displayable,List<Area>> e : la.getParent().findAreas(la, la.getParent().get2DBounds(), true).entrySet()) {
					for (final Area ar : e.getValue()) {
						all.add(ar.createTransformedArea(e.getKey().at));
					}
				}

				Polygon polygon = M.findPath(all, x_p_w, y_p_w); // in world coords

				if (null == polygon && source.getProject().getBooleanProperty("flood_fill_to_image_edge")) {
					Area patch_area = la.getPatchArea(true); // in world coords
					Rectangle bounds = patch_area.getBounds();
					if (0 != bounds.width && 0 != bounds.height) {
						patch_area.subtract(all);
						polygon = M.findPath(patch_area, x_p_w, y_p_w);
					}
				}

				if (null != polygon) {
					Rectangle bounds = polygon.getBounds();
					int pol_area = bounds.width * bounds.height;
					if (pol_area < min_area) {
						min_area = pol_area;
						bmin = new Area(polygon);
					}
				}

				if (null != bmin) {
					try {
						// Add b as local to this Area
						Area blocal = bmin.createTransformedArea(source.getAffineTransform().createInverse());
						if (me.isAltDown()) {
							area.subtract(blocal);
						} else {
							area.add(blocal);
						}
						((AreaContainer)source).calculateBoundingBox(la);
						Display.repaint(la, bmin.getBounds(), 1); // use b, in world coords
					} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
				}
			} else {
				if (null != this.painter) this.painter.quit(); // in case there was a mouse release outside the canvas--may not be detected
				try {
					this.painter = new Painter(area, mag, la, source.getAffineTransformCopy());
				} catch (Exception e) {
					Utils.log2("Oops: " + e);
				}
			}
		} else if (ProjectToolbar.PENCIL == tool) {
			final Displayable src = this.source;
			final ArrayList<Runnable> ptasks = new ArrayList<Runnable>();
			if (null != post_mouseReleased_tasks) {
				ptasks.addAll(post_mouseReleased_tasks);
				post_mouseReleased_tasks = null; // avoid running them twice
			}
			ptasks.add(new Runnable() {
				public void run() {
					// Add data edit step when done for undo/redo
					src.getLayerSet().addDataEditStep(src);
				}
			});
			if (Utils.isControlDown(me)) {
				// Grow with blow tool
				try {
					blowcommander = Segmentation.blowRoi(this, la, Display.getFront().getCanvas().getSrcRect(), x_p_w, y_p_w, ptasks);

				} catch (Exception e) {
					IJError.print(e);
				}
			} else {
				// Grow with fast marching
				Segmentation.fastMarching(this, la, Display.getFront().getCanvas().getSrcRect(), x_p_w, y_p_w, ptasks);
			}
		}
	}
	public void mouseDragged(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// nothing, the BrushThread handles it
		if (null != AreaWrapper.controller_key && KeyEvent.VK_M == AreaWrapper.controller_key.intValue() && ProjectToolbar.getToolId() == ProjectToolbar.BRUSH) {
			// "move" the area
			Rectangle r = area.getBounds();
			area.transform(new AffineTransform(1, 0, 0, 1, x_d - x_d_old, y_d - y_d_old));
			r.add(new Rectangle(r.x + (x_d_old - x_d), r.y + (y_d_old - y_d), r.width, r.height));
			Display.getFront().getCanvas().repaint(source.at.createTransformedShape(r).getBounds(), 1);
			return;
		}
		if (null != blowcommander) {
			blowcommander.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
		}
	}
	public void mouseReleased(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// No matter what tool, ensure that moving and brushing operations are always terminated:
		if (null != AreaWrapper.controller_key) {
			// finish
			AreaWrapper.controller_key = null;
			return;
		}
		if (null != this.painter) {
			this.painter.quit();
			this.painter = null;
		}
		if (null != blowcommander) {
			blowcommander.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
			blowcommander = null;
		}

		if (null != post_mouseReleased_tasks) {
			for (Runnable task : post_mouseReleased_tasks) task.run();
		}

		if (something_eroded) {
			Display.repaint(source.getLayerSet());
			something_eroded = false;
		}

		// Repaint instead the last rectangle, to erase the circle
		if (null != r_old) {
			Display.repaint(la, r_old, 3, false);
			r_old = null;
		}
		// repaint the navigator and snapshot
		Display.repaint(la, source);
	}

	static public final int PAINT_OVERLAP = 0;
	static public final int PAINT_EXCLUDE = 1;
	static public final int PAINT_ERODE = 2;

	static public class PaintParameters {
		public float default_alpha = 0.4f;
		public int paint_mode = AreaWrapper.PAINT_OVERLAP;

		public boolean setup() {
			GenericDialog gd = new GenericDialog("Paint parameters");
			gd.addSlider("Default_alpha", 0, 100, default_alpha * 100);
			final String[] modes = {"Allow overlap", "Exclude others", "Erode others"};
			gd.addChoice("Paint mode", modes, modes[paint_mode]);
			gd.showDialog();
			if (gd.wasCanceled()) return false;
			this.default_alpha = (float) gd.getNextNumber();
			if (this.default_alpha > 1) this.default_alpha = 1f;
			else if (this.default_alpha < 0) this.default_alpha = 0.4f; // back to default's default value
			this.paint_mode = gd.getNextChoiceIndex();
			// trigger update of GUI radio buttons on all displays:
			Display.toolChanged(ProjectToolbar.BRUSH);
			return true;
		}

		public OptionPanel asOptionPanel() {
			OptionPanel op = new OptionPanel();
			final String[] modes = {"Allow overlap", "Exclude others", "Erode others"};
			op.addChoice("Area paint mode:", modes, paint_mode, new OptionPanel.ChoiceIntSetter(this, "paint_mode"));
			return op;
		}
	}

	static public final PaintParameters PP = new PaintParameters();

	public void keyPressed(KeyEvent ke, DisplayCanvas dc, Layer la) {
		final int keyCode = ke.getKeyCode();

		if (null != AreaWrapper.controller_key && KeyEvent.VK_ENTER == keyCode) {
			AreaWrapper.controller_key = null;
			ke.consume();
			return;
		}
		if (KeyEvent.VK_M == keyCode && ProjectToolbar.getToolId() == ProjectToolbar.BRUSH) {
			AreaWrapper.controller_key = keyCode;
			ke.consume();
			return;
		}
		try {
			switch (keyCode) {
				case KeyEvent.VK_C: // COPY
					DisplayCanvas.setCopyBuffer(source.getClass(), area.createTransformedArea(source.getAffineTransform()));
					ke.consume();
					return;
				case KeyEvent.VK_V: // PASTE
					// Casting a null is fine, and addArea survives a null.
					Area a = (Area) DisplayCanvas.getCopyBuffer(source.getClass());
					if (null != a) {
						add(a.createTransformedArea(source.getAffineTransform().createInverse()), la);
						((AreaContainer)source).calculateBoundingBox(la);
					}
					ke.consume();
					return;
				case KeyEvent.VK_F: // fill all holes
					fillHoles();
					ke.consume();
					return;
				case KeyEvent.VK_X: // remove area from current layer, if any
					area.reset();
					((AreaContainer)source).calculateBoundingBox(la);
					ke.consume();
					return;
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (ke.isConsumed()) {
				Display.repaint(la, source.getBoundingBox(), 5);
				source.linkPatches();
				return;
			}
		}

		Roi roi = dc.getFakeImagePlus().getRoi();
		if (null == roi) return;
		// Check ROI
		switch (keyCode) {
			case KeyEvent.VK_A:
			case KeyEvent.VK_D:
			case KeyEvent.VK_K:
				if (!M.isAreaROI(roi)) {
					Utils.log("Only accepts region ROIs, not lines.");
					return;
				}
				break;
		}
		ShapeRoi sroi = new ShapeRoi(roi);
		long layer_id = la.getId();
		try {
			switch (keyCode) {
				case KeyEvent.VK_A:
					add(M.getArea(sroi), la);
					ke.consume();
					break;
				case KeyEvent.VK_D: // VK_S is for 'save' always
					subtract(M.getArea(sroi), la);
					ke.consume();
					break;
			}
			if (ke.isConsumed()) {
				Display.repaint(la, source.getBoundingBox(), 5);
				source.linkPatches();
			}
		} catch (Exception e) {
			Utils.log("Could not add ROI to area at layer " + dc.getDisplay().getLayer() + " : " + e);
		}
	}

	public void fillHoles() {
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
					area.add(new Area(pol));
					// prepare next:
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
	}
}
