/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006, 2007 Albert Cardona and Rodney Douglas.

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

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.ShapeRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;

import java.awt.Rectangle;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.util.Collections;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.awt.Stroke;
import java.awt.BasicStroke;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.ColorModel;
import java.awt.event.MouseEvent;

import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.History;
import ini.trakem2.Project;

/** Keeps track of selected objects and mediates their transformation.*/ 
public class Selection {

	/** Outward padding in pixels around the selected Displayables maximum enclosing box that may need repainting when updating the screen.*/
	static public int PADDING = 31;

	private Display display;
	/** Queue of all selected objects. */
	private final LinkedList<Displayable> queue = new LinkedList<Displayable>();
	private final LinkedList<Displayable> queue_prev = new LinkedList<Displayable>();
	private final Object queue_lock = new Object();
	private boolean queue_locked = false;
	/** All selected objects plus their links. */
	private final HashSet<Displayable> hs = new HashSet<Displayable>();
	private Displayable active = null;
	private boolean transforming = false;
	private Rectangle box = null;
	private final int iNW = 0;
	private final int iN = 1;
	private final int iNE = 2;
	private final int iE = 3;
	private final int iSE = 4;
	private final int iS = 5;
	private final int iSW = 6;
	private final int iW = 7;
	private final int rNW = 8;
	private final int rNE = 9;
	private final int rSE = 10;
	private final int rSW = 11;
	private final int ROTATION = 12;
	private final int FLOATER = 13;
	private final Handle NW = new BoxHandle(0,0, iNW);
	private final Handle N  = new BoxHandle(0,0, iN);
	private final Handle NE  = new BoxHandle(0,0, iNE);
	private final Handle E  = new BoxHandle(0,0, iE);
	private final Handle SE  = new BoxHandle(0,0, iSE);
	private final Handle S  = new BoxHandle(0,0, iS);
	private final Handle SW  = new BoxHandle(0,0, iSW);
	private final Handle W  = new BoxHandle(0,0, iW);
	private final Handle RO = new RotationHandle(0,0, ROTATION);
	/** Pivot of rotation. Always checked first on mouse pressed, before other handles. */
	private final Floater floater = new Floater(0, 0, FLOATER);
	private final Handle[] handles;
	private Handle grabbed = null;
	private boolean dragging = false; // means: dragging the whole transformation box
	private boolean rotating = false;
	private boolean mouse_dragged = false;

	private int x_d_old, y_d_old, x_d, y_d; // for rotations

	private ImagePlus virtual_imp = null;

	/** The Display can be null, as long as paint, OvalHandle.contains, setTransforming, and getLinkedBox methods are never called on this object. */
	public Selection(Display display) {
		this.display = display;
		this.handles = new Handle[]{NW, N, NE, E, SE, S, SW, W, RO, floater}; // shitty java, why no dictionaries (don't get me started with HashMap class painful usability)
	}

	private void lock() {
		//Utils.printCaller(this, 7);
		while (queue_locked) { try { queue_lock.wait(); } catch (InterruptedException ie) {} }
		queue_locked = true;
	}

	private void unlock() {
		//Utils.printCaller(this);
		queue_locked = false;
		queue_lock.notifyAll();
	}

	/** Paint a white frame around the selected object and a pink frame around all others. Active is painted last, so white frame is always top. */
	public void paint(Graphics2D g, Rectangle srcRect, double magnification) {
		// paint rectangle around selected Displayable elements
		final Composite co = g.getComposite();
		synchronized (queue_lock) {
			try {
				lock();
				if (queue.isEmpty()) return;
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		if (!transforming) {
			g.setColor(Color.pink);
			Displayable[] da = null;
			synchronized (queue_lock) {
				lock();
				try {
					da = new Displayable[queue.size()];
					queue.toArray(da);
				} catch (Exception e) {
					IJError.print(e);
				} finally {
					unlock();
				}
			}
			final Rectangle bbox = new Rectangle();
			for (int i=0; i<da.length; i++) {
				da[i].getBoundingBox(bbox);
				if (da[i].equals(active)) {
					g.setColor(Color.white);
					g.drawRect(bbox.x, bbox.y, bbox.width, bbox.height);
					//g.drawPolygon(da[i].getPerimeter());
					g.setColor(Color.pink);
				} else {
					//g.drawPolygon(da[i].getPerimeter());
					g.drawRect(bbox.x, bbox.y, bbox.width, bbox.height);
				}
			}
		}
		//Utils.log2("transforming, dragging, rotating: " + transforming + "," + dragging + "," + rotating);
		if (transforming) {
			final Graphics2D g2d = (Graphics2D)g;
			final Stroke original_stroke = g2d.getStroke();
			AffineTransform original = g2d.getTransform();
			g2d.setTransform(new AffineTransform());
			if (!rotating) {
				//Utils.log("box painting: " + box);

				// 30 pixel line, 10 pixel gap, 10 pixel line, 10 pixel gap
				float mag = (float)magnification;
				float[] dashPattern = { 30, 10, 10, 10 };
				g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dashPattern, 0));
				g.setColor(Color.yellow);
				// paint box
				//g.drawRect(box.x, box.y, box.width, box.height);
				g2d.draw(original.createTransformedShape(this.box));
				g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				// paint handles for scaling (boxes) and rotating (circles), and floater
				for (int i=0; i<handles.length; i++) {
					handles[i].paint(g, srcRect, magnification);
				}
			} else {
				g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				RO.paint(g, srcRect, magnification);
				((RotationHandle)RO).paintMoving(g, srcRect, magnification, display.getCanvas().getCursorLoc());
			}

			// Restore composite (undoes setXORColor)
			g.setComposite(co);
			if (null != affine_handles) {
				for (final AffinePoint ap : affine_handles) {
					ap.paint(g);
				}
			}

			g2d.setTransform(original);
			g2d.setStroke(original_stroke);
		} else {
			// Restore composite (undoes setXORColor)
			g.setComposite(co);
		}

		/*
		// debug:
		if (null != active) {
			g.setColor(Color.green);
			java.awt.Polygon p = active.getPerimeter(active.getX(), active.getY());
			System.out.println("polygon:");
			for (int i=0; i<p.npoints; i++) {
				System.out.println("x,y : " + p.xpoints[i] + "," + p.ypoints[i]);
				p.xpoints[i] = (int)((p.xpoints[i] - srcRect.x)*magnification);
				p.ypoints[i] = (int)((p.ypoints[i] - srcRect.y)*magnification);
			}
			g.drawPolygon(p);
		}
		*/
	}

	/** Handles have screen coordinates. */
	private abstract class Handle {
		public int x, y;
		public final int id;
		Handle(int x, int y, int id) {
			this.x = x;
			this.y = y;
			this.id = id;
		}
		abstract public void paint(Graphics g, Rectangle srcRect, double mag);
		/** Radius is the dectection "radius" around the handle x,y. */
		public boolean contains(int x_p, int y_p, double radius) {
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		public void set(int x, int y) {
			this.x = x;
			this.y = y;
		}
		abstract void drag(MouseEvent me, int dx, int dy);
	}

	private class BoxHandle extends Handle {
		BoxHandle(int x, int y, int id) {
			super(x,y,id);
		}
		public void paint(final Graphics g, final Rectangle srcRect, final double mag) {
			final int x = (int)((this.x - srcRect.x)*mag);
			final int y = (int)((this.y - srcRect.y)*mag);
			DisplayCanvas.drawHandle(g, x, y, 1.0); // ignoring magnification for the sizes, since Selection is painted differently
		}
		public void drag(MouseEvent me, int dx, int dy) {
			Rectangle box_old = (Rectangle)box.clone();
			//Utils.log2("dx,dy: " + dx + "," + dy + " before mod");
			double res = dx / 2.0;
			res -= Math.floor(res);
			res *= 2;
			int extra = (int)res;
			int anchor_x = 0,
			    anchor_y = 0;
			switch (this.id) { // java sucks to such an extent, I don't even bother
				case iNW:
					if (x + dx >= E.x) return;
					if (y + dy >= S.y) return;
					box.x += dx;
					box.y += dy;
					box.width -= dx;
					box.height -= dy;
					anchor_x = SE.x;
					anchor_y = SE.y;
					break;
				case iN:
					if (y + dy >= S.y) return;
					box.y += dy;
					box.height -= dy;
					anchor_x = S.x;
					anchor_y = S.y;
					break;
				case iNE:
					if (x + dx <= W.x) return;
					if (y + dy >= S.y) return;
					box.y += dy;
					box.width += dx;
					box.height -= dy;
					anchor_x = SW.x;
					anchor_y = SW.y;
					break;
				case iE:
					if (x + dx <= W.x) return;
					box.width += dx;
					anchor_x = W.x;
					anchor_y = W.y;
					break;
				case iSE:
					if (x + dx <= W.x) return;
					if (y + dy <= N.y) return;
					box.width += dx;
					box.height += dy;
					anchor_x = NW.x;
					anchor_y = NW.y;
					break;
				case iS:
					if (y + dy <= N.y) return;
					box.height += dy;
					anchor_x = N.x;
					anchor_y = N.y;
					break;
				case iSW:
					if (x + dx >= E.x) return;
					if (y + dy <= N.y) return;
					box.x += dx;
					box.width -= dx;
					box.height += dy;
					anchor_x = NE.x;
					anchor_y = NE.y;
					break;
				case iW:
					if (x + dx >= E.x) return;
					box.x += dx;
					box.width -= dx;
					anchor_x = E.x;
					anchor_y = E.y;
					break;
			}
			// proportion:
			double px = (double)box.width / (double)box_old.width;
			double py = (double)box.height / (double)box_old.height;
			// displacement: specific of each element of the selection and their links, depending on where they are.

			final AffineTransform at = new AffineTransform();
			at.translate( anchor_x, anchor_y );
			at.scale( px, py );
			at.translate( -anchor_x, -anchor_y );

			addUndoStep();

			for (final Displayable d : hs) {
				//d.scale(px, py, anchor_x, anchor_y, false); // false because the linked ones are already included in the HashSet
				d.preTransform(at, false);
			}
			fixAffinePoints(at);

			// finally:
			setHandles(box); // overkill. As Graham said, most newly available chip resources are going to be wasted. They are already.
		}
	}

	/** Add an undo step to the internal history. */
	private void addUndoStep() {
		if (mouse_dragged || isEmpty()) return;
		if (transforming) {
			if (null == history) return;
			if (history.indexAtStart() || (history.indexAtEnd() && -1 != history.index())) {
				history.add(new TransformationStep(getTransformationsCopy()));
			} else {
				// remove history elements from index+1 to end
				history.clip();
			}
		}
	}

	// TODO STILL DUPLICATE endings ... ??? <<<<<<<<<<-----------------------####

	synchronized void undoOneStep() {
		LayerSet layerset = display.getLayer().getParent();
		if (transforming) {
			if (null == history) return;
			// store the current state if at end:
			Utils.log2("index at end: " + history.indexAtEnd());
			if (history.indexAtEnd()) history.append(new TransformationStep(getTransformationsCopy()));
			// undo one step
			TransformationStep step = (TransformationStep)history.undoOneStep();
			if (null == step) return; // no more steps
			LayerSet.applyTransforms(step.ht);
		}
		resetBox();
	}

	synchronized void redoOneStep() {
		if (transforming) {
			if (null == history) return;
			TransformationStep step = (TransformationStep)history.redoOneStep();
			if (null == step) return; // no more steps
			LayerSet.applyTransforms(step.ht);
		}
		resetBox();
	}

	private final double rotate(MouseEvent me) {
		// center of rotation is the floater
		double cos = Utils.getCos(x_d_old - floater.x, y_d_old - floater.y, x_d - floater.x, y_d - floater.y);
		//double sin = Math.sqrt(1 - cos*cos);
		//double delta = Utils.getAngle(cos, sin);
		double delta = Math.acos(cos); // same thing as the two lines above
		// need to compute the sign of rotation as well: the cross-product!
		// cross-product:
		// a = (3,0,0) and b = (0,2,0)
		// a x b = (3,0,0) x (0,2,0) = ((0 x 0 - 2 x 0), -(3 x 0 - 0 x 0), (3 x 2 - 0 x 0)) = (0,0,6).

		if (me.isControlDown()) {
			delta = Math.toDegrees(delta);
			if (me.isShiftDown()) {
				// 1 degree angle increments
				delta = (int)(delta + 0.5);
			} else {
				// 10 degrees angle increments: snap to closest
				delta = (int)((delta + 5.5 * (delta < 0 ? -1 : 1)) / 10) * 10;
			}
			Utils.showStatus("Angle: " + delta + " degrees");
			delta = Math.toRadians(delta);

			// TODO: the angle above is just the last increment on mouse drag, not the total amount of angle accumulated since starting this mousePressed-mouseDragged-mouseReleased cycle, neither the actual angle of the selected elements. So we need to store the accumulated angle and diff from it to do the above roundings.
		}

		if (Double.isNaN(delta)) {
			Utils.log2("Selection rotation handle: ignoring NaN angle");
			return Double.NaN;
		}

		double zc = (x_d_old - floater.x) * (y_d - floater.y) - (x_d - floater.x) * (y_d_old - floater.y);
		// correction:
		if (zc < 0) {
			delta = -delta;
		}
		rotate(Math.toDegrees(delta), floater.x, floater.y);
		return delta;
	}

	private class RotationHandle extends Handle {
		final int shift = 50;
		RotationHandle(int x, int y, int id) {
			super(x, y, id);
		}
		public void paint(final Graphics g, final Rectangle srcRect, final double mag) {
			final int x = (int)((this.x - srcRect.x)*mag) + shift;
			final int y = (int)((this.y - srcRect.y)*mag);
			final int fx = (int)((floater.x - srcRect.x)*mag);
			final int fy = (int)((floater.y - srcRect.y)*mag);
			draw(g, fx, fy, x, y);
		}
		private void draw(final Graphics g, int fx, int fy, int x, int y) {
			g.setColor(Color.white);
			g.drawLine(fx, fy, x, y);
			g.fillOval(x -4, y -4, 9, 9);
			g.setColor(Color.black);
			g.drawOval(x -2, y -2, 5, 5);
		}
		public void paintMoving(final Graphics g, final Rectangle srcRect, final double mag, final Point mouse) {
			// mouse as xMouse,yMouse from ImageCanvas: world coordinates, not screen!
			final int fx = (int)((floater.x - srcRect.x)*mag);
			final int fy = (int)((floater.y - srcRect.y)*mag);
			// vector
			double vx = (mouse.x - srcRect.x)*mag - fx;
			double vy = (mouse.y - srcRect.y)*mag - fy;
			//double len = Math.sqrt(vx*vx + vy*vy);
			//vx = (vx / len) * 50;
			//vy = (vy / len) * 50;
			draw(g, fx, fy, fx + (int)vx, fy + (int)vy);
		}
		public boolean contains(int x_p, int y_p, double radius) {
			final double mag = display.getCanvas().getMagnification();
			final double x = this.x + shift / mag;
			final double y = this.y;
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		public void drag(MouseEvent me, int dx, int dy) {
			/// Bad design, I know, I'm ignoring the dx,dy
			// how:
			// center is the floater

			rotate(me);
		}
	}

	private class Floater extends Handle {
		Floater(int x, int y, int id) {
			super(x,y, id);
		}
		public void paint(Graphics g, Rectangle srcRect, double mag) {
			int x = (int)((this.x - srcRect.x)*mag);
			int y = (int)((this.y - srcRect.y)*mag);
			g.setXORMode(Color.white);
			g.drawOval(x -10, y -10, 21, 21);
			g.drawRect(x -1, y -15, 3, 31);
			g.drawRect(x -15, y -1, 31, 3);
		}
		public Rectangle getBoundingBox(Rectangle b) {
			b.x = this.x - 15;
			b.y = this.y - 15;
			b.width = this.x + 31;
			b.height = this.y + 31;
			return b;
		}
		public void drag(MouseEvent me, int dx, int dy) {
			this.x += dx;
			this.y += dy;
			RO.x = this.x;
			RO.y = this.y;
		}
		public void center() {
			this.x = RO.x = box.x + box.width/2;
			this.y = RO.y = box.y + box.height/2;
		}
		public boolean contains(int x_p, int y_p, double radius) {
			return super.contains(x_p, y_p, radius*3.5);
		}
	}

	public void centerFloater() {
		floater.center();
	}

	/** No display bounds are checked, the floater can be placed wherever you want. */
	public void setFloater(int x, int y) {
		floater.x = x;
		floater.y = y;
	}

	public int getFloaterX() { return floater.x; }
	public int getFloaterY() { return floater.y; }

	public void setActive(Displayable d) {
		synchronized (queue_lock) {
			try {
				lock();
				if (!queue.contains(d)) {
					Utils.log2("Selection.setActive warning: " + d + " is not part of the selection");
					return;
				}
				active = d;
				if (null != display) {
					if (active instanceof ZDisplayable) {
						active.setLayer(display.getLayer());
					}
					display.setActive(d);
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
	}

	public Displayable getActive() {
		return active;
	}

	public void add(Displayable d) {
		if (null == d) {
			Utils.log2("Selection.add warning: skipping null ob");
			return;
		}
		synchronized (queue_lock) {
		try {
			lock();
			this.active = d;
			if (queue.contains(d)) {
				if (null != display) {
					if (d instanceof ZDisplayable) d.setLayer(display.getLayer());
					display.setActive(d);
				}
				Utils.log2("Selection.add warning: already have " + d + " selected.");
				return;
			}
			setPrev(queue);
			queue.add(d);
			//Utils.log("box before adding: " + box);
			// add it's box to the selection box
			Rectangle b = d.getBoundingBox(new Rectangle());
			if (null == box) box = b;
			else box.add(b);
			//Utils.log("box after adding: " + box);
			setHandles(box);
			// check if it was among the linked already before adding it's links and its transform
			if (!hs.contains(d)) {
				hs.add(d);
				// now, grab the linked group and add it as well to the hashtable
				HashSet hsl = d.getLinkedGroup(new HashSet());
				if (null != hsl) {
					for (Iterator it = hsl.iterator(); it.hasNext(); ) {
						Displayable displ = (Displayable)it.next();
						if (!hs.contains(displ)) hs.add(displ);
					}
				}
			}
			// finally:
			if (null != display) display.setActive(d);
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	/** Select all objects in the Display's current layer, preserving the active one (if any) as active; includes all the ZDisplayables, whether visible in this layer or not. */
	public void selectAll() {
		if (null == display) return;
		ArrayList al = display.getLayer().getDisplayables();
		al.addAll(display.getLayer().getParent().getZDisplayables());
		selectAll(al);
	}

	/** Select all objects in the given layer, preserving the active one (if any) as active. */
	public void selectAll(Layer layer) {
		selectAll(layer.getDisplayables());
	}

	/** Select all objects under the given roi, in the current display's layer. */
	public void selectAll(Roi roi, boolean visible_only) {
		//Utils.log2("roi bounds: " + roi.getBounds());
		ShapeRoi shroi = roi instanceof ShapeRoi ? (ShapeRoi)roi : new ShapeRoi(roi);

		Area aroi = new Area(Utils.getShape(shroi));
		AffineTransform affine = new AffineTransform();
		Rectangle bounds = shroi.getBounds();
		affine.translate(bounds.x, bounds.y);
		aroi = aroi.createTransformedArea(affine);
		ArrayList al = display.getLayer().getDisplayables(Displayable.class, aroi, visible_only);
		al.addAll(display.getLayer().getParent().getZDisplayables(ZDisplayable.class, display.getLayer(), aroi, visible_only));
		if (visible_only) {
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (!d.isVisible()) it.remove();
			}
		}
		if (al.size() > 0) selectAll(al);
	}

	private void selectAll(ArrayList al) {
		synchronized (queue_lock) {
		try {
			lock();
			setPrev(queue);
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (queue.contains(d)) continue;
				queue.add(d);
				if (hs.contains(d)) continue;
				hs.add(d);
				// now, grab the linked group and add it as well to the hashset
				HashSet hsl = d.getLinkedGroup(new HashSet());
				if (null != hsl) {
					for (Iterator hit = hsl.iterator(); hit.hasNext(); ) {
						Displayable displ = (Displayable)hit.next();
						if (!hs.contains(displ)) hs.add(displ);
					}
				}
			}

			resetBox();

			if (null != display) {
				if (null == this.active) {
					display.setActive((Displayable)queue.getLast());
					this.active = display.getActive();
				}
				Display.update(display.getLayer());
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	/** Delete all selected objects from their Layer. */
	public boolean deleteAll() {
		if (null == active) return true; // nothing to remove
		if (!Utils.check("Remove " + queue.size() + " selected object" + (1 == queue.size() ? "?" : "s?"))) return false;
		// obtain a list of displayables to remove
		TreeMap<Integer,Displayable> sorted_d = null;
		TreeMap<Integer,Displayable> sorted_zd = null;
		synchronized (queue_lock) {
			try {
				lock();
				setPrev(queue);
				if (null != display) display.setActive(null);
				this.active = null;

				// Order by stack index (for buckets; see later)
				sorted_d = new TreeMap<Integer,Displayable>();
				sorted_zd = new TreeMap<Integer,Displayable>();

				for (final Displayable d : queue) {
					if (d instanceof ZDisplayable) {
						ZDisplayable zd = (ZDisplayable)d;
						sorted_zd.put(d.getLayerSet().indexOf(zd), d);
					} else {
						sorted_d.put(d.getLayer().indexOf(d), d);
					}
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}

		final ArrayList<Displayable> al_d = new ArrayList<Displayable>(sorted_d.values());
		al_d.addAll(sorted_zd.values());
		// Remove starting with higher stack index numbers:
		Collections.reverse(al_d);

		if (null != display) display.getLayerSet().addChangeTreesStep();

		// remove one by one, skip those that fail and log the error
		StringBuffer sb = new StringBuffer();
		try {
			if (null != display) display.getProject().getLoader().startLargeUpdate();
			for (final Displayable d : al_d) {
				// Remove from the trees and from the Layer/LayerSet
				if (!d.remove2(false)) {
					sb.append(d.getTitle()).append('\n');
					continue;
				}
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != display) display.getProject().getLoader().commitLargeUpdate();
		}
		if (sb.length() > 0) {
			Utils.log("Could NOT delete:\n" + sb.toString());
		}
		//Display.repaint(display.getLayer(), box, 0);
		Display.updateSelection(); // from all displays

		if (null != display) display.getLayerSet().addChangeTreesStep();

		return true;
	}

	/** Set the elements of the given LinkedList as those of the stored, previous selection, only if the given list is not empty. */
	private void setPrev(LinkedList q) {
		if (0 == q.size()) return;
		queue_prev.clear();
		queue_prev.addAll(q);
	}

	/** Remove all given displayables from this selection. */
	public void removeAll(HashSet<Displayable> hs) {
		for (Displayable d : hs) remove(d);
	}

	/** Remove all given displayables from this selection. */
	public void removeAll(ArrayList<Displayable> al) {
		for (Displayable d : al) remove(d);
	}

	/** Remove the given displayable from this selection. */
	public void remove(Displayable d) {
		if (null == d) {
			Utils.log2("Selection.remove warning: null Displayable to remove.");
			return;
		}
		synchronized (queue_lock) {
		try {
			lock();
			if (!hs.contains(d)) {
				//Utils.log2("Selection.remove warning: can't find ob " + ob_t + " to remove");
				// happens when removing a profile from the project tree that is not selected in the Display to which this Selection belongs
				unlock();
				return;
			}
			queue.remove(d);
			setPrev(queue);
			hs.remove(d);
			if (d.equals(active)) {
				if (0 == queue.size()) {
					active = null;
					box = null;
					hs.clear();
				} else {
					// select last
					active = (Displayable)queue.getLast();
				}
			}
			// update
			if (null != display) display.setActive(active);
			// finish if none left
			if (0 == queue.size()) {
				box = null;
				hs.clear();
				unlock();
				return;
			}
			// now, remove linked ones from the hs
			HashSet hs_to_remove = d.getLinkedGroup(new HashSet());
			HashSet hs_to_keep = new HashSet();
			for (Iterator it = queue.iterator(); it.hasNext(); ) {
				Displayable displ = (Displayable)it.next();
				hs_to_keep = displ.getLinkedGroup(hs_to_keep); //accumulates into the hashset
			}
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				if (hs_to_keep.contains(ob)) continue; // avoid linked ones still in queue or linked to those in queue
				it.remove();
			}
			// recompute box
			Rectangle r = new Rectangle(); // as temp storage
			for (Iterator it = queue.iterator(); it.hasNext(); ) {
				Displayable di = (Displayable)it.next();
				box.add(di.getBoundingBox(r));
			}
			// reposition handles
			setHandles(box);
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	private void setHandles(final Rectangle b) {
		final int tx = b.x;
		final int ty = b.y;
		final int tw = b.width;
		final int th = b.height;
		NW.set(tx, ty);
		N.set(tx + tw/2, ty);
		NE.set(tx + tw, ty);
		E.set(tx + tw, ty + th/2);
		SE.set(tx + tw, ty + th);
		S.set(tx + tw/2, ty + th);
		SW.set(tx, ty + th);
		W.set(tx, ty + th/2);
	}

	/** Remove all Displayables from this selection. */
	public void clear() {
		synchronized (queue_lock) {
		try {
			lock();
			// set null active before clearing so that borders can be repainted
			if (null != display && queue.size() > 0) {
				display.setActive(null);
				display.repaint(display.getLayer(), 5, box, false);
			}
			setPrev(queue);
			this.queue.clear();
			this.hs.clear();
			this.active = null;
			this.box = null;
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	private History history = null;

	synchronized public void setTransforming(final boolean b) {
		if (b == transforming) {
			Utils.log2("Selection.setTransforming warning: trying to set the same mode");
			return;
		}
		if (b) {
			// start transform
			history = new History(); // unlimited
			history.add(new TransformationStep(getTransformationsCopy()));
			transforming = true;
			floater.center();
		} else {
			// Record current state of modified Displayable set:
			if (!history.indexAtStart()) {
				TransformationStep step = (TransformationStep)history.getCurrent();
				LayerSet.applyTransforms(step.ht);
			}
			if (null != history) {
				history.clear();
				history = null;
			}
			// the transform is already applied, just forget it:
			transforming = false;
			forgetAffine();
		}
	}

	synchronized public void cancelTransform() {
		transforming = false;
		if (null == active) return;
		// restoring transforms
		if (null != history) {
			// apply first
			display.getLayer().getParent().applyTransforms(((TransformationStep)history.get(0)).ht);
		}
		// reread all transforms and remake box
		resetBox();
		forgetAffine();
	}

	synchronized public boolean isTransforming() { return this.transforming; }

	private class AffinePoint {
		int x, y;
		AffinePoint(int x, int y) {
			this.x = x;
			this.y = y;
		}
		public boolean equals(Object ob) {
			//if (!ob.getClass().equals(AffinePoint.class)) return false;
			AffinePoint ap = (AffinePoint) ob;
			double mag = display.getCanvas().getMagnification();
			double dx = mag * ( ap.x - this.x );
			double dy = mag * ( ap.y - this.y );
			double d =  dx * dx + dy * dy;
			return  d < 64.0;
		}
		void translate(int dx, int dy) {
			x += dx;
			y += dy;
		}
		private void paint(Graphics g) {
			int x = display.getCanvas().screenX(this.x);
			int y = display.getCanvas().screenY(this.y);
			g.setColor(Color.white);
			g.drawLine(x-4, y+2, x+8, y+2);
			g.drawLine(x+2, y-4, x+2, y+8);
			g.setColor(Color.yellow);
			g.fillRect(x+1,y+1,3,3);
			g.setColor(Color.black);
			g.drawRect(x, y, 4, 4);
		}
	}

	private ArrayList<AffinePoint> affine_handles = null;
	private ArrayList< mpicbg.models.PointMatch > matches = null;
	private mpicbg.models.Point[] p = null;
	private mpicbg.models.Point[] q = null;
	private mpicbg.models.AbstractAffineModel2D<?> model = null;
	private AffineTransform free_affine = null;
	private HashMap initial_affines = null;
	
	private void forgetAffine() {
		affine_handles = null;
		matches = null;
		p = q = null;
		model = null;
		free_affine = null;
		initial_affines = null;
	}

	private void initializeModel() {
		free_affine = new AffineTransform();
		initial_affines = getTransformationsCopy();

		int size = affine_handles.size();

		switch (size) {
			case 0:
				model = null;
				q = p = null;
				matches = null;
				return;
			case 1:
				model = new mpicbg.models.TranslationModel2D();
				break;
			case 2:
				model = new mpicbg.models.SimilarityModel2D();
				break;
			case 3:
				model = new mpicbg.models.AffineModel2D();
				break;
		}
		p = new mpicbg.models.Point[size];
		q = new mpicbg.models.Point[size];
		matches = new ArrayList< mpicbg.models.PointMatch >();
		int i = 0;
		for (final AffinePoint ap : affine_handles) {
			p[i] = new mpicbg.models.Point(new float[]{ap.x, ap.y});
			q[i] = p[i].clone();
			matches.add(new mpicbg.models.PointMatch(p[i], q[i]));
			i++;
		}
	}

	private void freeAffine(AffinePoint affp) {
		// The selected point
		final float[] w = q[affine_handles.indexOf(affp)].getW();
		w[0] = affp.x;
		w[1] = affp.y;

		try {
			model.fit(matches);
		} catch (Exception e) {}
		
		AffineTransform model_affine = model.createAffine();
		for (Iterator it = initial_affines.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry e = (Map.Entry)it.next();
			Displayable d = (Displayable)e.getKey();
			AffineTransform at = new AffineTransform((AffineTransform)e.getValue());
			at.preConcatenate(free_affine);
			at.preConcatenate(model_affine);
			d.setAffineTransform(at);
		}
	}

	private void fixAffinePoints(final AffineTransform at) {
		if (null != matches) {
			float[] po = new float[2];
			for (final AffinePoint affp : affine_handles) {
				po[0] = affp.x;
				po[1] = affp.y;
				at.transform(po, 0, po, 0, 1);
				affp.x = (int)po[0];
				affp.y = (int)po[1];
			}
			// Model will be reinitialized when needed
			free_affine.setToIdentity();
			model = null;
		}
	}

	private AffinePoint affp = null;

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		grabbed = null; // reset
		Utils.log2("transforming: " + transforming);
		if (!transforming) {
			if (display.getLayerSet().prepareStep(new ArrayList<Displayable>(queue))) {
				display.getLayerSet().addTransformStep(new ArrayList<Displayable>(queue));
			}
		} else {
			if (me.isShiftDown()) {
				if (me.isControlDown() && null != affine_handles) {
					if (affine_handles.remove(new AffinePoint(x_p, y_p))) {
						if (0 == affine_handles.size()) affine_handles = null;
						else initializeModel();
					}
					return;
				}
				if (null == affine_handles) {
					affine_handles = new ArrayList<AffinePoint>();
				}
				if (affine_handles.size() < 3) {
					affine_handles.add(new AffinePoint(x_p, y_p));
					if (1 == affine_handles.size()) {
						free_affine = new AffineTransform();
						initial_affines = getTransformationsCopy();
					}
					initializeModel();
				}
				return;
			} else if (null != affine_handles) {
				int index = affine_handles.indexOf(new AffinePoint(x_p, y_p));
				if (-1 != index) {
					affp = affine_handles.get(index);
					return;
				}
			}

			// find scale handle
			double radius = 4 / magnification;
			if (radius < 1) radius = 1;
			// start with floater (the last)
			for (int i=handles.length -1; i>-1; i--) {
				if (handles[i].contains(x_p, y_p, radius)) {
					grabbed = handles[i];
					if (grabbed.id >= rNW && grabbed.id <= ROTATION) rotating = true;
					return;
				}
			}
		}
		// if none grabbed, then drag the whole thing
		dragging = false; //reset
		if (box.x <= x_p && box.y <= y_p && box.x + box.width >= x_p && box.y + box.height >= y_p) {
			dragging = true;
		}
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {

		this.x_d = x_d;
		this.y_d = y_d;
		this.x_d_old = x_d_old;
		this.y_d_old = y_d_old;
		int dx = x_d - x_d_old;
		int dy = y_d - y_d_old;

		execDrag(me, dx, dy);

		mouse_dragged = true; // after execDrag, so the first undo step is added.
	}

	private void execDrag(MouseEvent me, int dx, int dy) {
		if (0 == dx && 0 == dy) return;
		if (null != affp) {
			affp.translate(dx, dy);
			if (null == model) {
				// Model has been canceled by a transformation from the other handles
				initializeModel();
			}
			// Passing on the translation from start
			freeAffine(affp);
			return;
		}

		if (null != grabbed) {
			// drag the handle and perform whatever task it has assigned
			grabbed.drag(me, dx, dy);
		} else if (dragging) {
			// drag all selected and linked
			translate(dx, dy);
			//and the box!
			box.x += dx;
			box.y += dy;
			// and the handles!
			setHandles(box);
		}
	}

	/*
	public void mouseMoved() {
		if (null != active) {
			new Thread() {
				public void run() {
					Transform t = (Transform)ht.get(active);
					Utils.showStatus("x,y : " + t.x + ", " + t.y, false);
				}
			}.start();
		}
	}
	*/

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {

		// Record current state for selected Displayable set, if there was any change:
		final int dx = x_r - x_p;
		final int dy = y_r - y_p;
		if (!transforming && (0 != dx || 0 != dy)) {
			display.getLayerSet().addTransformStep(hs); // all selected and their links: i.e. all that will change
		}

		// me is null when calling from Display, because of popup interfering with mouseReleased
		if (null != me) execDrag(me, x_r - x_d, y_r - y_d);

		if (transforming) {
			// recalculate box
			resetBox();
		}

		//reset
		if ((null != grabbed && grabbed.id <= iW) || dragging) {
			floater.center();
		}
		grabbed = null;
		dragging = false;
		rotating = false;
		affp = null;
		mouse_dragged = false;
	}

	/** Returns a copy of the box enclosing all selected ob, or null if none.*/
	public Rectangle getBox() {
		if (null == box) return null;
		return (Rectangle)box.clone();
	}

	/** Returns the total box enclosing all selected objects and their linked objects within the current layer, or null if none are selected. Includes the position of the floater, when transforming.*/
	public Rectangle getLinkedBox() {
		if (null == active) return null;
		Rectangle b = active.getBoundingBox();
		Layer layer = display.getLayer();
		Rectangle r = new Rectangle(); // for reuse
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (!d.equals(active) && d.getLayer().equals(layer)) {
				b.add(d.getBoundingBox(r));
			}
		}
		// include floater, whereever it is
		if (transforming) b.add(floater.getBoundingBox(r));
		return b;
	}

	/** Test if any of the selection objects is directly or indirectly locked. */
	public boolean isLocked() {
		if (null == active || null == hs || hs.isEmpty()) return false;
		// loop directly to avoid looping through the same linked groups if two or more selected objects belong to the same linked group. The ht contains all linked items anyway.
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (d.isLocked2()) return true;
		}
		return false;
	}

	/** Lock / unlock all selected objects. */
	public void setLocked(final boolean b) {
		if (null == active) return; // empty
		synchronized (queue_lock) {
			try {
				lock();
				addDataEditStep(new String[]{"locked"});
				for (final Displayable d : queue) {
					d.setLocked(b);
				}
				addDataEditStep(new String[]{"locked"});
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		// update the 'locked' field in the Transforms (rather, in the linked group)
		update(); // TODO this could be unnecessary
	}

	final void addDataEditStep(final String[] fields) {
		if (null != display) display.getLayerSet().addDataEditStep(new HashSet<Displayable>(queue), fields);
	}

	public void setColor(final Color c) {
		if (null == active) return; // empty
		synchronized (queue_lock) {
			try {
				lock();
				addDataEditStep(new String[]{"color"});
				for (final Displayable d : queue) {
					d.setColor(c);
				}
				addDataEditStep(new String[]{"color"});
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
	}
	// no memfn ... viva copy/paste
	public void setAlpha(final float alpha) {
		if (null == active) return; // empty
		synchronized (queue_lock) {
			try {
				lock();
				// DONE on mouse release from transp_slider // addDataEditStep(new String[]{"alpha"});
				for (final Displayable d : queue) {
					d.setAlpha(alpha);
				}
				// DONE on mouse release from transp_slider // addDataEditStep(new String[]{"alpha"});
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
	}

	public boolean isEmpty() {
		synchronized (queue_lock) {
			try {
				lock();
				return 0 == queue.size();
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		return true;
	}

	public boolean contains(final Displayable d) {
		synchronized (queue_lock) {
			try {
				lock();
				return queue.contains(d);
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		return false;
	}

	/** Returns true if selection contains any items of the given class.*/
	public boolean contains(final Class c) {
		if (null == c) return false;
		synchronized (queue_lock) {
			try {
				lock();
				if (c.equals(Displayable.class) && queue.size() > 0) return true;
				for (Iterator it = queue.iterator(); it.hasNext(); ) {
					if (it.next().getClass().equals(c)) return true;
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		return false;
	}

	protected void debug(String msg) {
		Utils.log2(msg + ": queue size = " + queue.size() + "  hs size: " + hs.size());
	}

	/** Returns a hash table with all selected Displayables as keys, and a copy of their affine transform as value. This is useful to easily create undo steps. */
	protected HashMap<Displayable,AffineTransform> getTransformationsCopy() {
		final HashMap<Displayable,AffineTransform> ht_copy = new HashMap<Displayable,AffineTransform>(hs.size());
		for (final Displayable d : hs) {
			ht_copy.put(d, d.getAffineTransformCopy());
		}
		return ht_copy;
	}

	void update() {
		synchronized (queue_lock) {
		try {
			lock();
			if (transforming) {
				Utils.log2("Selection.update warning: shouldn't be doing this while transforming!");
				return;
			}
			Utils.log2("updating selection");
			hs.clear();
			HashSet hsl = new HashSet();
			for (Iterator it = queue.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				// collect all linked ones into the hs
				hsl = d.getLinkedGroup(hsl);
			}
			if (0 == hsl.size()) {
				active = null;
				if (null != display) display.setActive(null);
				unlock();
				return;
			}
			hs.addAll(hsl); // hs is final, can't just assign
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	public boolean isDragging() {
		return dragging;
	}

	/** Recalculate box and reset handles. */
	public void resetBox() {
		box = null;
		Rectangle b = new Rectangle();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			b = d.getBoundingBox(b);
			if (null == box) box = (Rectangle)b.clone();
			box.add(b);
		}
		if (null != box) setHandles(box);
	}

	/** Update the bounding box of the whole selection. */
	public void updateTransform(final Displayable d) {
		if (null == d) {
			Utils.log2("Selection.updateTransform warning: null Displayable");
			return;
		}
		synchronized (queue_lock) {
		try {
			lock();
			if (!hs.contains(d)) {
				Utils.log2("Selection.updateTransform warning: " + d + " not selected or among the linked");
				return;
			}
			resetBox();
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			unlock();
		}}
	}

	public int getNSelected() {
		return queue.size();
	}

	public int getNLinked() {
		return hs.size();
	}

	/** Rotate the objects in the current selection by the given angle, in degrees, relative to the floater position. */
	public void rotate(final double angle, final int xo, final int yo) {
		final AffineTransform at = new AffineTransform();
		at.rotate(Math.toRadians(angle), xo, yo);

		addUndoStep();

		for (final Displayable d : hs) {
			d.preTransform(at, false); // all linked ones included in the hashset
		}
		fixAffinePoints(at);
		resetBox();
	}
	/** Translate all selected objects and their links by the given differentials. The floater position is unaffected; if you want to update it call centerFloater() */
	public void translate(final double dx, final double dy) {
		final AffineTransform at = new AffineTransform();
		at.translate(dx, dy);

		addUndoStep();

		for (final Displayable d : hs) {
			d.preTransform(at, false); // all linked ones already included in the hashset
		}
		fixAffinePoints(at);
		resetBox();
	}
	/** Scale all selected objects and their links by by the given scales, relative to the floater position. . */
	public void scale(final double sx, final double sy) {
		if (0 == sx || 0 == sy) {
			Utils.showMessage("Cannot scale to 0.");
			return;
		}

		final AffineTransform at = new AffineTransform();
		at.translate(floater.x, floater.y);
		at.scale(sx, sy);
		at.translate(-floater.x, -floater.y);

		addUndoStep();

		for (final Displayable d : hs) {
			d.preTransform(at, false); // all linked ones already included in the hashset
		}
		fixAffinePoints(at);
		resetBox();
	}

	/** Returns a copy of the list of all selected Displayables (and not their linked ones). */
	public ArrayList<Displayable> getSelected() {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			al.add((Displayable)it.next());
		}
		return al;
	}

	/** Returns a copy of the list of all selected Displayables (and not their linked ones) of the given class. */
	public ArrayList<Displayable> getSelected(final Class c) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (null == c || c.equals(Displayable.class)) {
			al.addAll((LinkedList<Displayable>)queue);
			return al;
		}
		boolean zd = c.equals(ZDisplayable.class);
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if ((zd && ob instanceof ZDisplayable)
			  || c.equals(ob.getClass())) {
				al.add((Displayable)ob);
			 }
		}
		return al;
	}

	/** Returns the set of all Displayable objects affected by this selection, that is, the selected ones and their linked ones.*/
	public Set<Displayable> getAffected() {
		Set<Displayable> set = null;
		synchronized (queue_lock) {
			lock();
			set = (Set<Displayable>)hs.clone();
			unlock();
		}
		return set;
	}

	/** Returns the set of all Displayable objects of the given class affected by this selection, that is, among the selected ones and their linked ones. */
	public Set<Displayable> getAffected(final Class c) {
		HashSet<Displayable> copy = new HashSet<Displayable>();
		synchronized (queue_lock) {
			lock();
			if (Displayable.class.equals(c) && hs.size() > 0) {
				copy.addAll(hs);
				unlock();
				return copy;
			}
			boolean zd = ZDisplayable.class.equals(c);
			for (Displayable d : this.hs) {
				if (zd && d instanceof ZDisplayable) {
					copy.add(d);
				} else if (d.getClass().equals(c)) {
					copy.add(d);
				}
			}
			unlock();
		}
		return hs;
	}

	/** If any of the selected or linked is of Class c. */
	public boolean containsAffected(final Class c) {
		synchronized (queue_lock) {
			lock();
			if (Displayable.class.equals(c) && hs.size() > 0) {
				unlock();
				return true;
			}
			boolean zd = ZDisplayable.class.equals(c);
			for (Displayable d : hs) {
				if (zd && d instanceof ZDisplayable) {
					unlock();
					return true;
				} else if (d.getClass().equals(c)) {
					unlock();
					return true;
				}
			}
			unlock();
		}
		return false;
	}

	/** Send all selected components to the previous layer. */
	public void moveUp() {
		if (null == display) return;
		Layer la = display.getLayer();
		LinkedList list = (LinkedList)queue.clone();
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			la.getParent().moveUp(la, (Displayable)it.next());
		}
		clear();
	}

	/** Send all selected components to the next layer. */
	public void moveDown() {
		if (null == display) return;
		Layer la = display.getLayer();
		LinkedList list = (LinkedList)queue.clone();
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			la.getParent().moveDown(la, (Displayable)it.next());
		}
		clear();
	}

	/** Set all selected objects visible/hidden. */
	public void setVisible(boolean b) {
		synchronized (queue_lock) {
			lock();
			for (Iterator it = queue.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (b != d.isVisible()) d.setVisible(b);
			}
			unlock();
		}
		Display.repaint(display.getLayer(), box, 10);
	}

	/** Removes the given Displayable from the selection and previous selection list. */
	protected void removeFromPrev(Displayable d) {
		if (null == d) return;
		synchronized (queue_lock) {
			lock();
			queue_prev.remove(d);
			unlock();
		}
	}

	/** Restore the previous selection. */
	public void restore() {
		synchronized (queue_lock) {
			lock();
			LinkedList q = (LinkedList)queue.clone();
			ArrayList al = new ArrayList();
			al.addAll(queue_prev);
			unlock();
			clear();
			if (al.size() > 0) selectAll(al);
			lock();
			setPrev(q);
			unlock();
		}
	}

	public void specify() {
		if (null == display || null == display.getActive()) return;
		final GenericDialog gd = new GenericDialog("Specify");
		gd.addMessage("Relative to the floater's position:");
		gd.addNumericField("floater X: ", getFloaterX(), 2);
		gd.addNumericField("floater Y: ", getFloaterY(), 2);
		gd.addMessage("Transforms applied in the same order as listed below:");
		gd.addNumericField("rotate : ", 0, 2);
		gd.addNumericField("translate in X: ", 0, 2);
		gd.addNumericField("translate in Y: ", 0, 2);
		gd.addNumericField("scale in X: ", 1.0, 2);
		gd.addNumericField("scale in Y: ", 1.0, 2);
		gd.showDialog();
		if (gd.wasCanceled()) {
			return;
		}
		boolean tr = transforming;
		if (!tr) setTransforming(true);
		if (!tr) display.getLayerSet().addTransformStep(active.getLinkedGroup(null));
		final Rectangle sel_box = getLinkedBox();
		setFloater((int)gd.getNextNumber(), (int)gd.getNextNumber());
		double rot = gd.getNextNumber();
		double dx = gd.getNextNumber();
		double dy = gd.getNextNumber();
		double sx = gd.getNextNumber();
		double sy = gd.getNextNumber();
		if (0 != dx || 0 != dy) translate(dx, dy);
		if (0 != rot) rotate(rot, floater.x, floater.y);
		if (0 != sx && 0 != sy) scale(sx, sy);
		else Utils.showMessage("Cannot scale to zero.");
		sel_box.add(getLinkedBox());
		// restore state if different
		if (!tr) setTransforming(tr);
		Display.repaint(display.getLayer(), sel_box, Selection.PADDING);
	}

	protected void apply(final int what, final double[] params) {
		final Rectangle sel_box = getLinkedBox();
		switch (what) {
			case 0: translate(params[0], params[1]); break;
			case 1: rotate(params[0], floater.x, floater.y); break;
			case 2: scale(params[0], params[1]); break;
		}
		sel_box.add(getLinkedBox());
		Display.repaint(display.getLayer(), sel_box, Selection.PADDING);
	}

	/** Apply the given LUT to all selected 8-bit, 16-bit, 32-bit images. */
	public void setLut(ColorModel cm) {
		final ArrayList<Patch> al = new ArrayList<Patch>();
		for (Displayable d : getSelected(Patch.class)) al.add((Patch)d);
		if (0 == al.size()) {
			return;
		}
		// reduce stacks to a single Patch
		// TODO
		if (!Utils.check("Really change LUT for " + al.size() + " images?")) return;
	}

	/** Returns the Project of the Display, or if the latter is null, that of the first selected Displayable. */
	public Project getProject() {
		if (null != display) return  display.getProject();
		synchronized (queue_lock) {
			lock();
			try {
				if (queue.size() > 0) return queue.get(0).getProject();
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		return null;
	}

	/** Returns the Layer of the Display, or if the latter is null, that of the first selected Displayable. */
	public Layer getLayer() {
		if (null != display) return  display.getLayer();
		synchronized (queue_lock) {
			lock();
			try {
				if (queue.size() > 0) return queue.get(0).getLayer();
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				unlock();
			}
		}
		return null;
	}

	//private class DoSelect implements DoStep {
	//}
}
