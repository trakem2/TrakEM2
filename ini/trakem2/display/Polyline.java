/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;

import ij.gui.Plot;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;

import ini.trakem2.imaging.LayerStack;
import ini.trakem2.Project;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import ini.trakem2.vector.VectorString3D;
import ini.trakem2.imaging.Segmentation;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import features.ComputeCurvatures;
import tracing.Path;
import tracing.SearchThread;
import tracing.TracerThread;
import tracing.SearchProgressCallback;

import javax.vecmath.Vector3d;
import javax.vecmath.Point3f;


/** A sequence of points that make multiple chained line segments. */
public class Polyline extends ZDisplayable implements Line3D, VectorData {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked x,y points as [2][n].*/
	protected double[][] p = new double[2][0];
	/**The array of Layers over which the points of this pipe live */
	protected long[] p_layer = new long[0];

	/** New empty Polyline. */
	public Polyline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
		n_points = 0;
	}

	public Polyline(Project project, long id, String title, double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Reconstruct from XML. */
	public Polyline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
		// parse specific data
		for (Iterator it = ht_attr.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String data = (String)entry.getValue();
			if (key.equals("d")) {
				// parse the points
				// parse the SVG points data
				ArrayList al_p = new ArrayList();
				// M: Move To
				// L: Line To
				// sequence is: M p[0][0],p[1][0] L p[0][1],p[1][1] L p[0][2],p[1][2] ...
				// first point:
				int i_start = data.indexOf('M');
				int i_L = data.indexOf('L', i_start+1);
				int next = 0;
				while (-1 != i_L) {
					if (p[0].length == next) enlargeArrays();
					// parse the point
					// 'X'
					int i_comma = data.indexOf(',', i_start+1);
					p[0][next] = Double.parseDouble(data.substring(i_start+1, i_comma));
					// 'Y'
					i_L = data.indexOf('L', i_comma);
					int i_end = i_L;
					if (-1 == i_L) i_end = data.length();
					p[1][next] = Double.parseDouble(data.substring(i_comma+1, i_end));
			
					// prepare next point
					i_start = i_L;
					next++;
				}
				n_points = next;
				// scale arrays back, so minimal size and also same size as p_layer
				p = new double[][]{Utils.copy(p[0], n_points), Utils.copy(p[1], n_points)};
			} else if (key.equals("layer_ids")) {
				// parse comma-separated list of layer ids. Creates empty Layer instances with the proper id, that will be replaced later.
				final String[] layer_ids = data.replaceAll(" ", "").trim().split(",");
				this.p_layer = new long[layer_ids.length];
				for (int i=0; i<layer_ids.length; i++) {
					if (i == p_layer.length) enlargeArrays();
					this.p_layer[i] = Long.parseLong(layer_ids[i]);
				}
			}
		}
	}

	/**Increase the size of the arrays by 5.*/
	synchronized protected void enlargeArrays() {
		enlargeArrays(5);
	}
	synchronized protected void enlargeArrays(int n_more) {
		//catch length
		int length = p[0].length;
		//make copies
		double[][] p_copy = new double[2][length + n_more];
		long[] p_layer_copy = new long[length + n_more];
		//copy values
		System.arraycopy(p[0], 0, p_copy[0], 0, length);
		System.arraycopy(p[1], 0, p_copy[1], 0, length);
		System.arraycopy(p_layer, 0, p_layer_copy, 0, length);
		//assign them
		this.p = p_copy;
		this.p_layer = p_layer_copy;
	}

	/** Returns the index of the first point in the segment made of any two consecutive points. */
	synchronized protected int findClosestSegment(final int x_p, final int y_p, final long layer_id, final double mag) {
		if (1 == n_points) return -1;
		if (0 == n_points) return -1;
		int index = -1;
		double d = (10.0D / mag);
		if (d < 2) d = 2;
		double sq_d = d*d;
		double min_sq_dist = Double.MAX_VALUE;
		final Calibration cal = layer_set.getCalibration();
		final double z = layer_set.getLayer(layer_id).getZ() * cal.pixelWidth;

		double x2 = p[0][0] * cal.pixelWidth;
		double y2 = p[1][0] * cal.pixelHeight;
		double z2 = layer_set.getLayer(p_layer[0]).getZ() * cal.pixelWidth;
		double x1, y1, z1;

		for (int i=1; i<n_points; i++) {
			x1 = x2;
			y1 = y2;
			z1 = z2;
			x2 = p[0][i] * cal.pixelWidth;
			y2 = p[1][i] * cal.pixelHeight;
			z2 = layer_set.getLayer(p_layer[i]).getZ() * cal.pixelWidth;

			double sq_dist = M.distancePointToSegmentSq(x_p * cal.pixelWidth, y_p * cal.pixelHeight, z,
					                            x1, y1, z1,
								    x2, y2, z2);

			if (sq_dist < sq_d && sq_dist < min_sq_dist) {
				min_sq_dist = sq_dist;
				index = i-1; // previous
			}
		}
		return index;
	}


	/**Find a point in an array, with a precision dependent on the magnification. Only points in the given  layer are considered, the rest are ignored. Returns -1 if none found. */
	synchronized protected int findPoint(final int x_p, final int y_p, final long layer_id, final double mag) {
		int index = -1;
		double d = (10.0D / mag);
		if (d < 2) d = 2;
		double min_dist = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			double dist = Math.abs(x_p - p[0][i]) + Math.abs(y_p - p[1][i]);
			if (layer_id == p_layer[i] && dist <= d && dist <= min_dist) {
				min_dist = dist;
				index = i;
			}
		}
		return index;
	}

	/** Find closest point within the current layer. */
	synchronized protected int findNearestPoint(final int x_p, final int y_p, final long layer_id) {
		int index = -1;
		double min_dist = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			if (layer_id != p_layer[i]) continue;
			double sq_dist = Math.pow(p[0][i] - x_p, 2) + Math.pow(p[1][i] - y_p, 2);
			if (sq_dist < min_dist) {
				index = i;
				min_dist = sq_dist;
			}
		}
		return index;
	}

	/**Remove a point from the bezier backbone and its two associated control points.*/
	synchronized protected void removePoint(final int index) {
		// check preconditions:
		if (index < 0) {
			return;
		} else if (n_points - 1 == index) {
			//last point out
			n_points--;
		} else {
			//one point out (but not the last)
			--n_points;

			// shift all points after 'index' one position to the left:
			for (int i=index; i<n_points; i++) {
				p[0][i] = p[0][i+1];		//the +1 doesn't fail ever because the n_points has been adjusted above, but the arrays are still the same size. The case of deleting the last point is taken care above.
				p[1][i] = p[1][i+1];
				p_layer[i] = p_layer[i+1];
			}
		}

		// Reset or fix autotracing records
		if (index < last_autotrace_start && n_points > 0) {
			last_autotrace_start--;
		} else last_autotrace_start = -1;

		//update in database
		updateInDatabase("points");
	}

	/**Move backbone point by the given deltas.*/
	public void dragPoint(final int index, final int dx, final int dy) {
		if (index < 0 || index >= n_points) return;
		p[0][index] += dx;
		p[1][index] += dy;

		// Reset autotracing records
		if (-1 != last_autotrace_start && index >= last_autotrace_start) {
			last_autotrace_start = -1;
		}
	}

	/** @param x_p,y_p in local coords. */
	protected double[] sqDistanceToEndPoints(final double x_p, final double y_p, final long layer_id) {
		final Calibration cal = layer_set.getCalibration();
		final double lz = layer_set.getLayer(layer_id).getZ();
		final double p0z =layer_set.getLayer(p_layer[0]).getZ();
		final double pNz =layer_set.getLayer(p_layer[n_points -1]).getZ();
		double sqdist0 =   (p[0][0] - x_p) * (p[0][0] - x_p) * cal.pixelWidth * cal.pixelWidth
				 + (p[1][0] - y_p) * (p[1][0] - y_p) * cal.pixelHeight * cal.pixelHeight
				 + (lz - p0z) * (lz - p0z) * cal.pixelWidth * cal.pixelWidth; // double multiplication by pixelWidth, ok, since it's what it's used to compute the pixel position in Z
		double sqdistN =   (p[0][n_points-1] - x_p) * (p[0][n_points-1] - x_p) * cal.pixelWidth * cal.pixelWidth
				 + (p[1][n_points-1] - y_p) * (p[1][n_points-1] - y_p) * cal.pixelHeight * cal.pixelHeight
				 + (lz - pNz) * (lz - pNz) * cal.pixelWidth * cal.pixelWidth;

		return new double[]{sqdist0, sqdistN};
	}

	synchronized public void insertPoint(int i, int x_p, int y_p, long layer_id) {
		if (-1 == n_points) setupForDisplay(); //reload
		if (p[0].length == n_points) enlargeArrays();
		double[][] p2 = new double[2][p[0].length];
		long[] p_layer2 = new long[p_layer.length];
		if (0 != i) {
			System.arraycopy(p[0], 0, p2[0], 0, i);
			System.arraycopy(p[1], 0, p2[1], 0, i);
			System.arraycopy(p_layer, 0, p_layer2, 0, i);
		}
		p2[0][i] = x_p;
		p2[1][i] = y_p;
		p_layer2[i] = layer_id;
		if (n_points != i) {
			System.arraycopy(p[0], i, p2[0], i+1, n_points -i);
			System.arraycopy(p[1], i, p2[1], i+1, n_points -i);
			System.arraycopy(p_layer, i, p_layer2, i+1, n_points -i);
		}
		p = p2;
		p_layer = p_layer2;
		n_points++;
	}

	/** Append a point at the end. Returns the index of the new point. */
	synchronized protected int appendPoint(int x_p, int y_p, long layer_id) {
		if (-1 == n_points) setupForDisplay(); //reload
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		p[0][n_points] = x_p;
		p[1][n_points] = y_p;
		p_layer[n_points] = layer_id;
		n_points++;
		return n_points-1;
	}

	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. The width of the new point is that of the closest point after which it is inserted.*/
	synchronized protected int addPoint(int x_p, int y_p, long layer_id, double magnification) {
		if (-1 == n_points) setupForDisplay(); //reload
		//lookup closest point and then get the closest clicked point to it
		int index = 0;
		if (n_points > 1) index = findClosestSegment(x_p, y_p, layer_id, magnification);
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		//decide:
		if (0 == n_points || 1 == n_points || index + 1 == n_points) {
			//append at the end
			p[0][n_points] = x_p;
			p[1][n_points] = y_p;
			p_layer[n_points] = layer_id;
			index = n_points;

			last_autotrace_start = -1;
		} else if (-1 == index) {
			// decide whether to append at the end or prepend at the beginning
			// compute distance in the 3D space to the first and last points
			final double[] sqd0N = sqDistanceToEndPoints(x_p, y_p, layer_id);
			//final double sqdist0 = sqd0N[0];
			//final double sqdistN = sqd0N[1];

			//if (sqdistN < sqdist0)
			if (sqd0N[1] < sqd0N[0]) {
				//append at the end
				p[0][n_points] = x_p;
				p[1][n_points] = y_p;
				p_layer[n_points] = layer_id;
				index = n_points;

				last_autotrace_start = -1;
			} else {
				// prepend at the beginning
				for (int i=n_points-1; i>-1; i--) {
					p[0][i+1] = p[0][i];
					p[1][i+1] = p[1][i];
					p_layer[i+1] = p_layer[i];
				}
				p[0][0] = x_p;
				p[1][0] = y_p;
				p_layer[0] = layer_id;
				index = 0;

				if (-1 != last_autotrace_start) last_autotrace_start++;
			}
		} else {
			//insert at index:
			index++; //so it is added after the closest point;
			// 1 - copy second half of array
			int sh_length = n_points -index;
			double[][] p_copy = new double[2][sh_length];
			long[] p_layer_copy = new long[sh_length];
			System.arraycopy(p[0], index, p_copy[0], 0, sh_length);
			System.arraycopy(p[1], index, p_copy[1], 0, sh_length);
			System.arraycopy(p_layer, index, p_layer_copy, 0, sh_length);
			// 2 - insert value into 'p' (the two control arrays get the same value)
			p[0][index] = x_p;
			p[1][index] = y_p;
			p_layer[index] = layer_id;
			// 3 - copy second half into the array
			System.arraycopy(p_copy[0], 0, p[0], index+1, sh_length);
			System.arraycopy(p_copy[1], 0, p[1], index+1, sh_length);
			System.arraycopy(p_layer_copy, 0, p_layer, index+1, sh_length);

			// Reset autotracing records
			if (index < last_autotrace_start) {
				last_autotrace_start++;
			}
		}
		//add one up
		this.n_points++;

		return index;
	}

	synchronized protected void appendPoints(final double[] px, final double[] py, final long[] p_layer_ids, int len) {
		for (int i=0, next=n_points; i<len; i++, next++) {
			if (next == p[0].length) enlargeArrays();
			p[0][next] = px[i];
			p[1][next] = py[i];
			p_layer[next] = p_layer_ids[i];
		}
		n_points += len;
		updateInDatabase("points");
	}

	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (0 == n_points) return;
		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
		}
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}

		final boolean no_color_cues = "true".equals(project.getProperty("no_color_cues"));
		final long layer_id = active_layer.getId();
		final double z_current = active_layer.getZ();

		// Different approach: a point paints itself and towards the next, except the last point.
		// First point:
		double z = layer_set.getLayer(p_layer[0]).getZ();
		boolean paint = true;
		if (z < z_current) {
			if (no_color_cues) paint = false;
			else g.setColor(Color.red);
		} else if (z == z_current) g.setColor(this.color);
		else if (no_color_cues) paint = false;
		else g.setColor(Color.blue);

		// Paint half line:
		if (paint && n_points > 1) {
			g.drawLine((int)p[0][0], (int)p[1][0],
				   (int)((p[0][0] + p[0][1])/2), (int)((p[1][0] + p[1][1])/2));
		}

		// Paint handle if active and in the current layer
		if (active && layer_id == p_layer[0]) {
			g.setColor(this.color);
			DisplayCanvas.drawHandle(g, p[0][0], p[1][0], srcRect, magnification);
			// Label the first point distinctively
			Composite comp = g.getComposite();
			AffineTransform aff = g.getTransform();
			g.setTransform(new AffineTransform());
			g.setColor(Color.white);
			g.setXORMode(Color.green);
			g.drawString("1", (int)( (p[0][0] - srcRect.x)*magnification + (4.0 / magnification)),
					  (int)( (p[1][0] - srcRect.y)*magnification)); // displaced 4 screen pixels to the right
			g.setComposite(comp);
			g.setTransform(aff);
		}

		for (int i=1; i<n_points; i++) {
			// Determine color
			z = layer_set.getLayer(p_layer[i]).getZ();
			paint = true;
			if (z < z_current) {
				if (no_color_cues) paint = false;
				else g.setColor(Color.red);
			} else if (z == z_current) g.setColor(this.color);
			else if (no_color_cues) paint = false;
			else g.setColor(Color.blue);
			if (!paint) continue;
			// paint half line towards previous point:
			g.drawLine((int)p[0][i], (int)p[1][i],
				   (int)((p[0][i] + p[0][i-1])/2), (int)((p[1][i] + p[1][i-1])/2));
			// paint half line towards next point:
			if (i < n_points -1) {
				g.drawLine((int)p[0][i], (int)p[1][i],
					   (int)((p[0][i] + p[0][i+1])/2), (int)((p[1][i] + p[1][i+1])/2));
			}
			// Paint handle if active and in the current layer
			if (active && layer_id == p_layer[i]) {
				g.setColor(this.color);
				DisplayCanvas.drawHandle(g, p[0][i], p[1][i], srcRect, magnification);
			}
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	public void keyPressed(KeyEvent ke) {
		int keyCode = ke.getKeyCode();
		switch (keyCode) {
			case KeyEvent.VK_D:
				if (-1 == last_autotrace_start) {
					if (0 > n_points) Utils.log("Cannot remove last set of autotraced points:\n  Manual editions exist, or never autotraced.");
					return;
				}
				// Else, remove:
				final int len = n_points - last_autotrace_start;
				n_points = last_autotrace_start;
				last_autotrace_start = -1;
				repaint(true, null);
				// update buckets for layers of all points from n_points to last_autotrace_start
				final HashSet<Long> hs = new HashSet<Long>();
				for (int i = n_points+1; i < n_points+len; i++) hs.add(p_layer[i]);
				for (final Long l : hs) updateBucket(layer_set.getLayer(l.longValue()));
				Utils.log("Removed " + len + " autotraced points.");
				return;
			case KeyEvent.VK_R: // reset tracing
				tr_map.remove(layer_set);
				ke.consume();
				Utils.log("Reset tracing data for Polyline " + this);
				return;
		}
	}

	/**Helper vars for mouse events. It's safe to have them static since only one Pipe will be edited at a time.*/
	static protected int index;
	static private boolean is_new_point = false;

	final static private HashMap<LayerSet,TraceParameters> tr_map = new HashMap<LayerSet,TraceParameters>();
	private int last_autotrace_start = -1;

	static public void flushTraceCache(final Project project) {
		synchronized (tr_map) {
			for (Iterator<LayerSet> it = tr_map.keySet().iterator(); it.hasNext(); ) {
				if (it.next().getProject() == project) it.remove();
			}
		}
	}

	/** Shared between all Polyline of the same LayerSet. The issue of locking doesn't arise because there is only one source of mouse input. If you try to run it programatically with synthetic MouseEvent, that's your problem. */
	static private class TraceParameters {
		boolean update = true;
		ImagePlus virtual = null;
		double scale = 1;
		ComputeCurvatures hessian = null;
		TracerThread tracer = null; // catched thread for KeyEvent to attempt to stop it
	}

	public void mousePressed(MouseEvent me, final Layer layer, int x_p, int y_p, double mag) {
		// transform the x_p, y_p to the local coordinates
		int x_pd = x_p;
		int y_pd = y_p;
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		final Display display = ((DisplayCanvas)me.getSource()).getDisplay();
		final long layer_id = layer.getId();

		index = findPoint(x_p, y_p, layer_id, mag);

		if (ProjectToolbar.PENCIL == tool && n_points > 0 && -1 == index && !me.isShiftDown() && !Utils.isControlDown(me)) {
			// Use Mark Longair's tracing: from the clicked point to the last one
			final double scale = layer_set.getVirtualizationScale();
			// Ok now with all found images, create a virtual stack that provides access to them all, with caching.
			final Worker[] worker = new Worker[2];

			TraceParameters tr_ = tr_map.get(layer_set);
			final TraceParameters tr = null == tr_ ? new TraceParameters() : tr_;
			if (null == tr_) {
				synchronized (tr_map) {
					tr_map.put(layer_set, tr);
				}
			}

			if (tr.update) {
				worker[0] = new Worker("Preparing Hessian...") { public void run() {
					startedWorking();
					try {
				Utils.log("Push ESCAPE key to cancel autotrace anytime.");
				ImagePlus virtual = new LayerStack(layer_set, scale, ImagePlus.GRAY8, Patch.class, display.getDisplayChannelAlphas(), Segmentation.fmp.SNT_invert_image).getImagePlus();
				//virtual.show();
				Calibration cal = virtual.getCalibration();
				double minimumSeparation = 1;
				if (cal != null) minimumSeparation = Math.min(cal.pixelWidth,
									      Math.min(cal.pixelHeight,
										       cal.pixelDepth));
				ComputeCurvatures hessian = new ComputeCurvatures(virtual, minimumSeparation, null, cal != null);
				hessian.run();

				tr.virtual = virtual;
				tr.scale = scale;
				tr.hessian = hessian;
				tr.update = false;

					} catch (Exception e) {
						IJError.print(e);
					}
					finishedWorking();
				}};
				Bureaucrat.createAndStart(worker[0], project);
			}

			Point2D.Double po = transformPoint(p[0][n_points-1], p[1][n_points-1]);
			final int start_x = (int)po.x;
			final int start_y = (int)po.y;
			final int start_z = layer_set.indexOf(layer_set.getLayer(p_layer[n_points-1])); // 0-based
			final int goal_x = (int)(x_pd * scale); // must transform into virtual space
			final int goal_y = (int)(y_pd * scale);
			final int goal_z = layer_set.indexOf(layer);

			/*
			Utils.log2("x_pd, y_pd : " + x_pd + ", " + y_pd);
			Utils.log2("scale: " + scale);
			Utils.log2("start: " + start_x + "," + start_y + ", " + start_z);
			Utils.log2("goal: " + goal_x + "," + goal_y + ", " + goal_z);
			Utils.log2("virtual: " + tr.virtual);
			*/


			final boolean simplify = me.isAltDown();

			worker[1] = new Worker("Tracer - waiting on hessian") { public void run() {
				startedWorking();
				try {
				if (null != worker[0]) {
					// Wait until hessian is ready
					worker[0].join();
				}
				setTaskName("Tracing path");
				final int reportEveryMilliseconds = 2000;
				tr.tracer = new TracerThread(tr.virtual, 0, 255,
						                       120,  // timeout seconds
								       reportEveryMilliseconds,
								       start_x, start_y, start_z,
								       goal_x, goal_y, goal_z,
								       true, // reciproal pix values at start and goal
								       tr.virtual.getStackSize() == 1,
								       tr.hessian,
								       null == tr.hessian ? 1 : 4,
								       null,
								       null != tr.hessian);
				tr.tracer.addProgressListener(new SearchProgressCallback() {
					public void pointsInSearch(SearchThread source, int inOpen, int inClosed) {
						worker[1].setTaskName("Tracing path: open=" + inOpen + " closed=" + inClosed);
					}
					public void finished(SearchThread source, boolean success) {
						if (!success) {
							Utils.logAll("Could NOT trace a path");
						}
					}
					public void threadStatus(SearchThread source, int currentStatus) {
						// This method gets called every reportEveryMilliseconds
						if (worker[1].hasQuitted()) {
							source.requestStop();
						}
					}
				});

				tr.tracer.run();

				final Path result = tr.tracer.getResult();

				tr.tracer = null;

				if (null == result) {
					Utils.log("Finding a path failed"); //: "+ 
						// not public //SearchThread.exitReasonStrings[tracer.getExitReason()]);
					return;
				}

				// TODO: precise_x_positions etc are likely to be broken (calibrated or something)


				// Remove bogus points: those at the end with 0,0 coords
				int len = result.points;
				final double[][] pos = result.getXYZUnscaled();
				for (int i=len-1; i>-1; i--) {
					if (0 == pos[0][i] && 0 == pos[1][i]) {
						len--;
					} else break;
				}
				// Transform points: undo scale, and bring to this Polyline AffineTransform:
				final AffineTransform aff = new AffineTransform();
				/* Inverse order: */
				/* 2 */ aff.concatenate(Polyline.this.at.createInverse());
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
					p_layer_ids[i] = layer_set.getLayer((int)pos[2][i]).getId(); // z_positions in 0-(N-1), not in 1-N like slices!
					pox[i] = po2[j];
					poy[i] = po2[j+1];
				}

				// Simplify path: to steps of 5 calibration units, or 5 pixels when not calibrated.
				if (simplify) {
					setTaskName("Simplifying path");
					Object[] ob = Polyline.simplify(pox, poy, p_layer_ids, 10000, layer_set);
					pox = (double[])ob[0];
					poy = (double[])ob[1];
					p_layer_ids = (long[])ob[2];
					len = pox.length;
				}

				// Record the first newly-added autotraced point index:
				last_autotrace_start = Polyline.this.n_points;
				Polyline.this.appendPoints(pox, poy, p_layer_ids, len);

				Polyline.this.repaint(true, null);
				Utils.logAll("Added " + len + " new points.");

				} catch (Exception e) { IJError.print(e); }
				finishedWorking();
			}};
			Bureaucrat.createAndStart(worker[1], project);

			index = -1;
			return;

		}

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {

			if (Utils.isControlDown(me) && me.isShiftDown()) {
				final long lid = Display.getFrontLayer(this.project).getId();
				if (-1 == index || lid != p_layer[index]) {
					index = findNearestPoint(x_p, y_p, layer_id);
				}
				if (-1 != index) {
					//delete point
					removePoint(index);
					index = -1;
					repaint(false, null);
				}

				// In any case, terminate
				return;
			}

			if (-1 != index && layer_id != p_layer[index]) index = -1; // disable!

			//if no conditions are met, attempt to add point
			else if (-1 == index && !me.isShiftDown() && !me.isAltDown()) {
				//add a new point
				index = addPoint(x_p, y_p, layer_id, mag);
				is_new_point = true;
				repaint(false, null);
				return;
			}
		}
	}

	public void mouseDragged(MouseEvent me, Layer layer, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			//final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			//x_p = (int)p.x;
			//y_p = (int)p.y;
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			//if a point in the backbone is found, then:
			if (-1 != index && !me.isAltDown() && !me.isShiftDown()) {
				dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				repaint(false, layer);
				return;
			}
		}
	}

	public void mouseReleased(MouseEvent me, Layer layer, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			repaint(true, layer); //needed at least for the removePoint
		}

		//update points in database if there was any change
		if (-1 != index) {
			if (is_new_point) {
				// update all points, since the index may have changed
				updateInDatabase("points");
			} else if (-1 != index && index != n_points) { //second condition happens when the last point has been removed
				// not implemented // updateInDatabase(getUpdatePointForSQL(index));
				// Instead:
				updateInDatabase("points");
			} else if (index != n_points) { // don't do it when the last point is removed
				// update all
				updateInDatabase("points");
			}
			updateInDatabase("dimensions");
		} else if (x_r != x_p || y_r != y_p) {
			updateInDatabase("dimensions");
		}

		repaint(true, layer);

		// reset
		is_new_point = false;
		index = -1;
	}

	synchronized protected void calculateBoundingBox(final boolean adjust_position, final Layer la) {
		if (0 == n_points) {
			this.width = this.height = 0;
			updateBucket(la);
			return;
		}
		final double[] m = calculateDataBoundingBox();

		this.width = m[2] - m[0];  // max_x - min_x;
		this.height = m[3] - m[1]; // max_y - min_y;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (int i=0; i<n_points; i++) {
				p[0][i] -= m[0]; // min_x;
				p[1][i] -= m[1]; // min_y;
			}
			this.at.translate(m[0], m[1]) ; // (min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		updateBucket(la);
	}

	/** Returns min_x, min_y, max_x, max_y. */
	protected double[] calculateDataBoundingBox() {
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;
		// check the points
		for (int i=0; i<n_points; i++) {
			if (p[0][i] < min_x) min_x = p[0][i];
			if (p[1][i] < min_y) min_y = p[1][i];
			if (p[0][i] > max_x) max_x = p[0][i];
			if (p[1][i] > max_y) max_y = p[1][i];
		}
		return new double[]{min_x, min_y, max_x, max_y};
	}

	/**Release all memory resources taken by this object.*/
	synchronized public void destroy() {
		super.destroy();
		p = null;
		p_layer = null;
	}

	/**Release memory resources used by this object: namely the arrays of points, which can be reloaded with a call to setupForDisplay()*/
	synchronized public void flush() {
		p = null;
		p_layer = null;
		n_points = -1; // flag that points exist but are not loaded
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator, final Layer la) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true, la);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		if (-1 == n_points) n_points = 0;
		// load points
		/* Database storage not implemented yet
		if (null == p) {
			ArrayList al = project.getLoader().fetchPolylinePoints(id);
			n_points = al.size();
			p = new double[2][n_points];
			p_layer = new long[n_points];
			Iterator it = al.iterator();
			int i = 0;
			while (it.hasNext()) {
				Object[] ob = (Object[])it.next();
				p[0][i] = ((Double)ob[0]).doubleValue();
				p[1][i] = ((Double)ob[1]).doubleValue();
				p_layer[i] = ((Long)ob[7]).longValue();
				i++;
			}
		}
		*/
	}

	/** The exact perimeter of this polyline, in integer precision. */
	synchronized public Polygon getPerimeter() {
		if (null == p || p[0].length < 2) return new Polygon();

		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}
		int[] x = new int[n_points];
		int[] y = new int[n_points];
		for (int i=0; i<n_points; i++) {
			x[i] = (int)p[0][i];
			y[i] = (int)p[1][i];
		}
		return new Polygon(x, y, n_points);
	}

	/** A little square for each pixel in @param layer.*/
	@Override
	synchronized public Area getAreaAt(final Layer layer) {
		final Area a = new Area();
		for (int i=0; i<n_points; i++) {
			if (p_layer[i] != layer.getId()) continue;
			a.add(new Area(new Rectangle2D.Float((float)p[0][i], (float)p[1][i], 1, 1)));
		}
		a.transform(this.at);
		return a;
	}

	public boolean isDeletable() {
		return 0 == n_points;
	}

	/** The number of points in this pipe. */
	public int length() {
		if (-1 == n_points) setupForDisplay();
		return n_points;
	}

	synchronized public boolean contains(final Layer layer, final int x, final int y) {
		Display front = Display.getFront();
		double radius = 10;
		if (null != front) {
			double mag = front.getCanvas().getMagnification();
			radius = (10.0D / mag);
			if (radius < 2) radius = 2;
		}
		// else assume fixed radius of 10 around the line

		// make x,y local
		final Point2D.Double po = inverseTransformPoint(x, y);
		return containsLocal(layer, (int)po.x, (int)po.y, radius);	
	}

	protected boolean containsLocal(final Layer layer, int x, int y, double radius) {

		final long lid = layer.getId();
		final double z = layer.getZ();

		for (int i=0; i<n_points; i++) {
			if (lid == p_layer[i]) {
				// check both lines:
				if (i > 0 && M.distancePointToLine(x, y, p[0][i-1], p[1][i-1], p[0][i], p[1][i]) < radius) {
					return true;
				}
				if (i < (n_points -1) && M.distancePointToLine(x, y, p[0][i], p[1][i], p[0][i+1], p[1][i+1]) < radius) {
					return true;
				}
			} else if (i > 0) {
				double z1 = layer_set.getLayer(p_layer[i-1]).getZ();
				double z2 = layer_set.getLayer(p_layer[i]).getZ();
				if ( (z1 < z && z < z2)
				  || (z2 < z && z < z1) ) {
					// line between j and j-1 crosses the given layer
					if (M.distancePointToLine(x, y, p[0][i-1], p[1][i-1], p[0][i], p[1][i]) < radius) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/* Scan the Display and link Patch objects that lay under this Pipe's bounding box. */
	public boolean linkPatches() { // TODO needs to check all layers!!
		unlinkAll(Patch.class);
		// sort points by layer id
		final HashMap<Long,ArrayList<Integer>> m = new HashMap<Long,ArrayList<Integer>>();
		for (int i=0; i<n_points; i++) {
			ArrayList<Integer> a = m.get(p_layer[i]);
			if (null == a) {
				a = new ArrayList<Integer>();
				m.put(p_layer[i], a);
			}
			a.add(i);
		}
		boolean must_lock = false;
		// For each layer id, search patches whose perimeter includes
		// one of the backbone points in this path:
		for (Map.Entry<Long,ArrayList<Integer>> e : m.entrySet()) {
			final Layer layer = layer_set.getLayer(e.getKey().longValue());
			for (Displayable patch : layer.getDisplayables(Patch.class)) {
				final Polygon perimeter = patch.getPerimeter();
				for (Integer in : e.getValue()) {
					final int i = in.intValue();
					if (perimeter.contains(p[0][i], p[1][i])) {
						this.link(patch);
						if (patch.locked) must_lock = true;
						break;
					}
				}
			}
		}

		// set the locked flag to this and all linked ones
		if (must_lock && !locked) {
			setLocked(true);
			return true;
		}

		return false;
	}

	/** Returns the layer of lowest Z coordinate where this ZDisplayable has a point in, or the creation layer if no points yet. */
	public Layer getFirstLayer() {
		if (0 == n_points) return this.layer;
		if (-1 == n_points) setupForDisplay(); //reload
		Layer la = this.layer;
		double z = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			Layer layer = layer_set.getLayer(p_layer[i]);
			if (layer.getZ() < z) la = layer;
		}
		return la;
	}

	/** Exports data. */
	synchronized public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_polyline\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		if (-1 == n_points) setupForDisplay(); // reload
		//if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		if (n_points > 0) {
			sb_body.append(in).append("d=\"M");
			for (int i=0; i<n_points-1; i++) {
				sb_body.append(" ").append(p[0][i]).append(",").append(p[1][i]).append(" L");
			}
			sb_body.append(" ").append(p[0][n_points-1]).append(',').append(p[1][n_points-1]).append("\"\n");
			sb_body.append(in).append("layer_ids=\""); // different from 'layer_id' in superclass
			for (int i=0; i<n_points; i++) {
				sb_body.append(p_layer[i]);
				if (n_points -1 != i) sb_body.append(",");
			}
			sb_body.append("\"\n");
		}
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, any);
		sb_body.append(indent).append("</t2_polyline>\n");
	}

	/** Exports to type t2_polyline. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_polyline";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_polyline (").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2)
		;
	}

	/** Performs a deep copy of this object, without the links. */
	synchronized public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Polyline copy = new Polyline(pr, nid, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.locked, (AffineTransform)this.at.clone());
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_layer = (long[])this.p_layer.clone();
		copy.addToDatabase();

		return copy;
	}

	/** Calibrated. */
	synchronized public List generateTriangles(double scale, int parallels, int resample) {
		return generateTriangles(scale, parallels, resample, layer_set.getCalibrationCopy());
	}

	/** Returns a list of Point3f that define a polyline in 3D, for usage with an ij3d CustomLineMesh CONTINUOUS. @param parallels is ignored. */
	synchronized public List generateTriangles(final double scale, final int parallels, final int resample, final Calibration cal) {
		if (-1 == n_points) setupForDisplay();

		if (0 == n_points) return null;

		// local pointers, since they may be transformed
		final int n_points;
		final double[][] p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		} else {
			n_points = this.n_points;
			p = this.p;
		}

		final ArrayList list = new ArrayList();
		final double KW = scale * cal.pixelWidth * resample;
		final double KH = scale * cal.pixelHeight * resample;

		for (int i=0; i<n_points; i++) {
			list.add(new Point3f((float) (p[0][i] * KW),
					     (float) (p[1][i] * KH),
					     (float) (layer_set.getLayer(p_layer[i]).getZ() * KW)));
		}

		if (n_points < 2) {
			// Duplicate first point
			list.add(list.get(0));
		}

		return list;
	}

	synchronized private Object[] getTransformedData() {
		final int n_points = this.n_points;
		final double[][] p = transformPoints(this.p, n_points);
		return new Object[]{p};
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		if (-1 == n_points) setupForDisplay();
		for (int i=0; i<n_points; i++) {
			final double z = layer_set.getLayer(p_layer[i]).getZ();
			if (z < z_first || z > z_last) continue;
			if (area.contains(p[0][i], p[1][i])) return true;
		}
		return false;
	}

	/** Returns a non-calibrated VectorString3D. */
	synchronized public VectorString3D asVectorString3D() {
		// local pointers, since they may be transformed
		int n_points = this.n_points;
		double[][] p = this.p;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			n_points = p[0].length;
		}
		double[] z_values = new double[n_points];
		for (int i=0; i<n_points; i++) {
			z_values[i] = layer_set.getLayer(p_layer[i]).getZ();
		}

		final double[] px = p[0];
		final double[] py = p[1];
		final double[] pz = z_values;
		VectorString3D vs = null;
		try {
			vs = new VectorString3D(px, py, pz, false);
		} catch (Exception e) { IJError.print(e); }
		return vs;
	}

	public String getInfo() {
		if (-1 == n_points) setupForDisplay(); //reload
		// measure length
		double len = 0;
		if (n_points > 1) {
			VectorString3D vs = asVectorString3D();
			vs.calibrate(this.layer_set.getCalibration());
			len = vs.computeLength(); // no resampling
		}
		return new StringBuffer("Length: ").append(Utils.cutNumber(len, 2, true)).append(' ').append(this.layer_set.getCalibration().getUnits()).append('\n').toString();
	}

	public ResultsTable measure(ResultsTable rt) {
		if (-1 == n_points) setupForDisplay(); //reload
		if (0 == n_points) return rt;
		if (null == rt) rt = Utils.createResultsTable("Polyline results", new String[]{"id", "length", "name-id"});
		// measure length
		double len = 0;
		Calibration cal = layer_set.getCalibration();
		if (n_points > 1) {
			VectorString3D vs = asVectorString3D();
			vs.calibrate(cal);
			len = vs.computeLength(); // no resampling
		}
		rt.incrementCounter();
		rt.addLabel("units", cal.getUnit());
		rt.addValue(0, this.id);
		rt.addValue(1, len);
		rt.addValue(2, getNameId());
		return rt;
	}

	/** Resample the curve to, first, a number of points as resulting from resampling to a point interdistance of delta, and second, as adjustment by random walk of those points towards the original points. */
	static public Object[] simplify(final double[] px, final double[] py, final long[] p_layer_ids, /*final double delta, final double allowed_error_per_point,*/ final int max_iterations, final LayerSet layer_set) throws Exception {
		if (px.length != py.length || py.length != p_layer_ids.length) return null;

		final double[] pz = new double[px.length];
		for (int i=0; i<pz.length; i++) {
			pz[i] = layer_set.getLayer(p_layer_ids[i]).getZ();
		}

		/*
		// Resample:
		VectorString3D vs = new VectorString3D(px, py, pz, false);
		Calibration cal = layer_set.getCalibrationCopy();
		vs.calibrate(cal);
		vs.resample(delta);
		cal.pixelWidth = 1 / cal.pixelWidth;
		cal.pixelHeight = 1 / cal.pixelHeight;
		vs.calibrate(cal); // undo it, since source points are in pixels

		Pth path = new Pth(vs.getPoints(0), vs.getPoints(1), vs.getPoints(2));
		vs = null;

		// The above fails with strangely jagged lines.
		*/

		// Instead, just a line:
		Calibration cal = layer_set.getCalibrationCopy();
		double one_unit = 1 / cal.pixelWidth; // in pixels
		double traced_length = M.distance(px[0], py[0], pz[0],
				         px[px.length-1], py[py.length-1], pz[pz.length-1]);
		double segment_length = (one_unit * 5);
		int n_new_points = (int)(traced_length / segment_length) + 1;
		double[] rx = new double[n_new_points];
		double[] ry = new double[n_new_points];
		double[] rz = new double[n_new_points];
		rx[0] = px[0];  rx[rx.length-1] = px[px.length-1];
		ry[0] = py[0];  ry[ry.length-1] = py[py.length-1];
		rz[0] = pz[0];  rz[rz.length-1] = pz[pz.length-1];
		Vector3d v = new Vector3d(rx[n_new_points-1] - rx[0],
				          ry[n_new_points-1] - ry[0],
					  rz[n_new_points-1] - rz[0]);
		v.normalize();
		v.scale(segment_length);
		for (int i=1; i<n_new_points-1; i++) {
			rx[i] = rx[0] + v.x * i;
			ry[i] = ry[0] + v.y * i;
			rz[i] = rz[0] + v.z * i;
		}
		Pth path = new Pth(rx, ry, rz);
		rx = ry = rz = null;

		//final double lowest_error = px.length * allowed_error_per_point;
		final double d = 1;
		final Random rand = new Random(System.currentTimeMillis());

		double current_error = Double.MAX_VALUE;
		int i = 0;
		//double[] er = new double[max_iterations];
		//double[] index = new double[max_iterations];
		int missed_in_a_row = 0;
		for (; i<max_iterations; i++) {
			final Pth copy = path.copy().shakeUpOne(d, rand);
			double error = copy.measureErrorSq(px, py, pz);
			// If less error, keep the copy
			if (error < current_error) {
				current_error = error;
				path = copy;
				//er[i] = error;
				//index[i] = i;
				missed_in_a_row = 0;
			} else {
				//er[i] = current_error;
				//index[i] = i;
				missed_in_a_row++;
				if (missed_in_a_row > 10 * path.px.length) {
					Utils.log2("Stopped random walk at iteration " + i);
					break;
				}
				continue;
			}
			/*
			// If below lowest_error, quit searching
			if (current_error < lowest_error) {
				Utils.log2("Stopped at iteration " + i);
				break;
			}
			*/
		}

		/*
		Plot plot = new Plot("error", "iteration", "error", index, er);
		plot.setLineWidth(2);
		plot.show();
		*/

		if (max_iterations == i) {
			Utils.log2("Reached max iterations -- current error: " + current_error);
		}

		// Approximate new Z to a layer id:
		long[] plids = new long[path.px.length];
		plids[0] = p_layer_ids[0]; // first point untouched
		for (int k=1; k<path.pz.length; k++) {
			plids[k] = layer_set.getNearestLayer(path.pz[k]).getId();
		}

		return new Object[]{path.px, path.py, plids};
	}

	/** A path of points in 3D. */
	static private class Pth {
		final double[] px, py, pz;
		Pth(double[] px, double[] py, double[] pz) {
			this.px = px;
			this.py = py;
			this.pz = pz;
		}
		/** Excludes first and last points. */
		final Pth shakeUpOne(final double d, final Random rand) {
			final int i = rand.nextInt(px.length-1) + 1;
			px[i] += d * (rand.nextBoolean() ? 1 : -1);
			py[i] += d * (rand.nextBoolean() ? 1 : -1);
			pz[i] += d * (rand.nextBoolean() ? 1 : -1);
			return this;
		}
		final Pth copy() {
			return new Pth( Utils.copy(px, px.length),
					Utils.copy(py, py.length),
					Utils.copy(pz, pz.length) );
		}
		/** Excludes first and last points. */
		final double measureErrorSq(final double[] ox, final double[] oy, final double[] oz) {
			double error = 0;
			for (int i=1; i<ox.length -1; i++) {
				double min_dist = Double.MAX_VALUE;
				for (int j=1; j<px.length; j++) {
					// distance from a original point to a line defined by two consecutive new points
					double dist = M.distancePointToSegmentSq(ox[i], oy[i], oz[i],
							                         px[j-1], py[j-1], pz[j-1],
									         px[j], py[j], pz[j]);
					if (dist < min_dist) min_dist = dist;
				}
				error += min_dist;
			}
			return error;
		}
	}

	public void setPosition(FallLine fl) {
		// Where are we now?
	}

	@Override
	final Class getInternalDataPackageClass() {
		return DPPolyline.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPPolyline(this);
	}

	static private final class DPPolyline extends Displayable.DataPackage {
		final double[][] p;
		final long[] p_layer;

		DPPolyline(final Polyline polyline) {
			super(polyline);
			// store copies of all arrays
			this.p = new double[][]{Utils.copy(polyline.p[0], polyline.n_points), Utils.copy(polyline.p[1], polyline.n_points)};
			this.p_layer = new long[polyline.n_points]; System.arraycopy(polyline.p_layer, 0, this.p_layer, 0, polyline.n_points);
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Polyline polyline = (Polyline)d;
			final int len = p[0].length; // == n_points, since it was cropped on copy
			polyline.p = new double[][]{Utils.copy(p[0], len), Utils.copy(p[1], len)};
			polyline.n_points = p[0].length;
			polyline.p_layer = new long[len]; System.arraycopy(p_layer, 0, polyline.p_layer, 0, len);
			return true;
		}
	}

	/** Retain the data within the layer range, and through out all the rest. */
	synchronized public boolean crop(List<Layer> range) {
		HashSet<Long> lids = new HashSet<Long>();
		for (Layer l : range) {
			lids.add(l.getId());
		}
		for (int i=0; i<n_points; i++) {
			if (!lids.contains(p_layer[i])) {
				removePoint(i);
				i--;
			}
		}
		calculateBoundingBox(true, null);
		return true;
	}

	/** Create a shorter Polyline, from start to end (inclusive); not added to the LayerSet. */
	synchronized public Polyline sub(int start, int end) {
		Polyline sub = new Polyline(project, title);
		sub.n_points = end - start + 1;
		sub.p[0] = Utils.copy(this.p[0], start, sub.n_points);
		sub.p[1] = Utils.copy(this.p[1], start, sub.n_points);
		sub.p_layer = new long[sub.n_points];
		System.arraycopy(this.p_layer, start, sub.p_layer, 0, sub.n_points);
		return sub;
	}

	synchronized public void reverse() {
		Utils.reverse(p[0]);
		Utils.reverse(p[1]);
		Utils.reverse(p_layer);
	}
	synchronized protected boolean layerRemoved(Layer la) {
		super.layerRemoved(la);
		for (int i=0; i<p_layer.length; i++) {
			if (la.getId() == p_layer[i]) {
				removePoint(i);
				i--;
			}
		}
		return true;
	}

	synchronized public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		float[] fp = null;
		mpicbg.models.CoordinateTransform chain = null;
		Area localroi = null;
		AffineTransform inverse = null;
		for (int i=0; i<n_points; i++) {
			if (p_layer[i] == la.getId()) {
				if (null == localroi) {
					inverse = this.at.createInverse();
					localroi = roi.createTransformedArea(inverse);
				}
				if (localroi.contains(p[0][i], p[1][i])) {
					if (null == chain) {
						chain = M.wrap(this.at, ict, inverse);
						fp = new float[2];
					}
					M.apply(chain, p, i, fp);
				}
			}
		}
		if (null != chain) calculateBoundingBox(true, la); // may be called way too many times, but avoids lots of headaches.
		return true;
	}
	public boolean apply(final VectorDataTransform vdt) throws Exception {
		final float[] fp = new float[2];
		final VectorDataTransform vlocal = vdt.makeLocalTo(this);
		for (int i=0; i<n_points; i++) {
			if (vdt.layer.getId() == p_layer[i]) {
				for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
					if (rt.roi.contains(p[0][i], p[1][i])) {
						// Transform the point
						M.apply(rt.ct, p, i, fp);
						break;
					}
				}
			}
		}
		calculateBoundingBox(true, vlocal.layer);
		return true;
	}

	@Override
	synchronized public Collection<Long> getLayerIds() {
		return Utils.asList(p_layer, 0, n_points);
	}
}
