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

import java.awt.Rectangle;
import java.awt.Color;
import java.awt.Graphics;
import java.util.Collections;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.Hashtable;
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

import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

/** Keeps track of selected objects and mediates their transformation.*/ 
public class Selection {

	/** Outward padding in pixels around the selected Displayables maximum enclosing box that may need repainting when updating the screen.*/
	static public int PADDING = 31;

	private Display display;
	/** Queue of all selected objects. */
	private final LinkedList queue = new LinkedList();
	private final Object queue_lock = new Object();
	private boolean queue_locked = false;
	/** All selected objects plus their links. */
	private final HashSet hs = new HashSet();
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
	private boolean dragging = false;
	private boolean rotating = false;

	private int x_d_old, y_d_old, x_d, y_d; // for rotations

	private ImagePlus virtual_imp = null;

	/** The Display can be null, as long as paint, OvalHandle.contains, setTransforming, and getLinkedBox methods are never called on this object. */
	public Selection(Display display) {
		this.display = display;
		this.handles = new Handle[]{NW, N, NE, E, SE, S, SW, W, RO, floater}; // shitty java, why no dictionaries (don't get me started with Hashtable class painful usability)
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
	public void paint(Graphics g, Rectangle srcRect, double magnification) {
		// paint rectangle around selected Displayable elements
		if (queue.isEmpty()) return;
		g.setColor(Color.pink);
		Displayable[] da = null;
		synchronized (queue_lock) {
			lock();
			try {
				da = new Displayable[queue.size()];
				queue.toArray(da);
			} catch (Exception e) {
				new IJError(e);
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
				g.setColor(Color.pink);
			} else {
				g.drawRect(bbox.x, bbox.y, bbox.width, bbox.height);
			}
		}
		//Utils.log2("transforming, dragging, rotating: " + transforming + "," + dragging + "," + rotating);
		if (transforming && !rotating) {
			//Utils.log("box painting: " + box);
			g.setColor(Color.yellow);
			final Graphics2D g2d = (Graphics2D)g;
			final Stroke original_stroke = g2d.getStroke();
			//g2d.setStroke(new BasicStroke(5)); // no need  , BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			
			// 30 pixel line, 10 pixel gap, 10 pixel line, 10 pixel gap
			float[] dashPattern = { 30, 10, 10, 10 };
			g2d.setStroke(new BasicStroke((float)(2/magnification), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dashPattern, 0));

			// paint box
			g.drawRect(box.x, box.y, box.width, box.height);
			//restore Graphics object
			g2d.setStroke(original_stroke);
			// paint handles for scaling (boxes) and rotating (circles), and floater
			AffineTransform original = g2d.getTransform();
			g2d.setTransform(new AffineTransform());
			for (int i=0; i<handles.length; i++) {
				handles[i].paint(g, srcRect, magnification);
			}
			g2d.setTransform(original);
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
		abstract void drag(int dx, int dy);
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
		public void drag(int dx, int dy) {
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
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				d.scale(px, py, anchor_x, anchor_y, false); // false because the linked ones are already included in the HashSet
			}

			// finally:
			setHandles(box); // overkill. As Graham said, most newly available chip resources are going to be wasted. They are already.
		}
	}

	private final void rotate() {
		// center of rotation is the floater
		double cos = Utils.getCos(x_d_old - floater.x, y_d_old - floater.y, x_d - floater.x, y_d - floater.y);
		//double sin = Math.sqrt(1 - cos*cos);
		//double delta = Utils.getAngle(cos, sin);
		double delta = Math.acos(cos); // same thing as the two lines above
		// need to compute the sign of rotation as well: the cross-product!
		// cross-product:
		// a = (3,0,0) and b = (0,2,0)
		// a x b = (3,0,0) x (0,2,0) = ((0 x 0 - 2 x 0), -(3 x 0 - 0 x 0), (3 x 2 - 0 x 0)) = (0,0,6).

		if (Double.isNaN(delta)) {
			Utils.log2("Selection rotation handle: ignoring NaN angle");
			return;
		}

		double zc = (x_d_old - floater.x) * (y_d - floater.y) - (x_d - floater.x) * (y_d_old - floater.y);
		// correction:
		if (zc < 0) {
			delta = -delta;
		}
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			d.rotate(delta, floater.x, floater.y, false); // false because the linked ones are already included in the HashSet
		}
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
			g.setColor(Color.white);
			g.drawLine(fx, fy, x, y);
			g.fillOval(x -4, y -4, 9, 9);
			g.setColor(Color.black);
			g.drawOval(x -2, y -2, 5, 5);
		}
		public boolean contains(int x_p, int y_p, double radius) {
			final double mag = display.getCanvas().getMagnification();
			final double x = this.x + shift / mag;
			final double y = this.y;
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		public void drag(int dx, int dy) {
			/// Bad design, I know, I'm ignoring the dx,dy
			// how:
			// center is the floater

			rotate();
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
		public void drag(int dx, int dy) {
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
				new IJError(e);
			} finally {
				unlock();
			}
		}
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
				unlock();
				return;
			}
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
			new IJError(e);
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
		Utils.log2("Just selected all: " + queue.size());
	}

	/** Select all objects in the given layer, preserving the active one (if any) as active. */
	public void selectAll(Layer layer) {
		selectAll(layer.getDisplayables());
	}

	private void selectAll(ArrayList al) {
		synchronized (queue_lock) {
		try {
			lock();
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (queue.contains(d)) continue;
				queue.add(d);
				if (hs.contains(d)) continue;
				hs.add(d);
				// now, grab the linked group and add it as well to the hashtable
				HashSet hsl = d.getLinkedGroup(new HashSet());
				if (null != hsl) {
					for (Iterator hit = hsl.iterator(); hit.hasNext(); ) {
						Displayable displ = (Displayable)hit.next();
						if (!hs.contains(displ)) hs.add(displ);
					}
				}
			}

			resetBox();

			if (null != display && null == this.active) {
				display.setActive((Displayable)queue.getLast());
				this.active = display.getActive();
				Utils.log2("calling display.setActive");
			} else {
				Utils.log2("not calling display.setActive");
			}
		} catch (Exception e) {
			new IJError(e);
		} finally {
			unlock();
		}}
	}

	// TODO think of a way of disabling autocommit, but setting save points and commiting the good part and rolig back tha bad part if any

	/** Delete all selected objects from their Layer. */
	public boolean deleteAll() {
		synchronized (queue_lock) {
		try {
			if (null == active) return true; // nothing to remove
			if (!Utils.check("Remove " + queue.size() + " selected object" + (1 == queue.size() ? "?" : "s?"))) return false;
			lock();
			if (null != display) display.setActive(null);
			this.active = null;
			StringBuffer sb = new StringBuffer();
			Displayable[] d = new Displayable[queue.size()];
			queue.toArray(d);
			unlock();
			try {
				display.getProject().getLoader().startLargeUpdate();
				for (int i=0; i<d.length; i++) {
					if (!d[i].remove(false)) {
						sb.append(d[i].getTitle()).append('\n');
						continue;
					}
				}
			} catch (Exception e) {
				new IJError(e);
			} finally {
				display.getProject().getLoader().commitLargeUpdate();
			}
			if (sb.length() > 0) {
				Utils.showMessage("Could NOT delete:\n" + sb.toString());
			}
			//Display.repaint(display.getLayer(), box, 0);
			Display.updateSelection(); // from all displays
		} catch (Exception e) {
			new IJError(e);
		} finally {
			if (queue_locked) unlock();
		}}
		return true;
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
				hs.remove(ob);
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
			new IJError(e);
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
			this.queue.clear();
			this.hs.clear();
			this.active = null;
			if (null != display) display.setActive(null);
			this.box = null;
		} catch (Exception e) {
			new IJError(e);
		} finally {
			unlock();
		}}
	}

	public void setTransforming(boolean b) {
		if (b == transforming) {
			Utils.log2("Selection.setTransforming warning: trying to set the same mode");
			return;
		}
		if (b) {
			// start transform
			transforming = true;
			floater.center();
			display.getLayer().getParent().addUndoStep(getTransformationsCopy());
		} else {
			transforming = false;
		}
	}

	public void cancelTransform() { // TODO : use the undo feature, reread the transforms as originally cached for undo.
		transforming = false;
		if (null == active) return;
		// reread all transforms and remake box
		resetBox();
		// restoring transforms
		display.getLayer().getParent().undoOneStep();
	}

	public boolean isTransforming() { return this.transforming; }

	public void mousePressed(int x_p, int y_p, double magnification) {
		grabbed = null; // reset
		if (transforming) {
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
	public void mouseDragged(int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		this.x_d = x_d;
		this.y_d = y_d;
		this.x_d_old = x_d_old;
		this.y_d_old = y_d_old;
		int dx = x_d - x_d_old;
		int dy = y_d - y_d_old;
		if (null != grabbed) {
			// drag the handle and perform whatever task it has assigned
			grabbed.drag(dx, dy);
		} else if (dragging) {
			// drag all selected and linked
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				((Displayable)it.next()).translate(dx, dy, false); // false because the linked ones are already included in the HashSet
			}
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

	public void mouseReleased(int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
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
	}

	/** Returns a copy of the box enclosing all selected ob.*/
	public Rectangle getBox() {
		return (Rectangle)box.clone();
	}

	/** Returns the total box enclosing all selected objects and their linked objects within the current layer.*/
	public Rectangle getLinkedBox() { // TODO has to change to query the Displayable directly
		if (null == active) return null;
		Rectangle b = active.getBoundingBox();
		Layer layer = display.getLayer();
		Rectangle r = new Rectangle();
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (d.getLayer().equals(layer)) {
				b.add(d.getBoundingBox(r));
			}
		}
		// include floater, whereever it is
		b.add(floater.getBoundingBox(r));
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
	public void setLocked(boolean b) {
		if (null == active) return; // empty
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			d.setLocked(b);
		}
		// update the 'locked' field in the Transforms
		update();
	}

	public boolean isEmpty() {
		return 0 == queue.size(); // active must always exists if selection is not empty
	}

	public boolean contains(Displayable d) {
		return queue.contains(d);
	}

	/** Returns true if selection contains any items of the given class.*/
	public boolean contains(Class c) {
		if (null == c) return false;
		if (c.equals(Displayable.class) && queue.size() > 0) return true;
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			if (it.next().getClass().equals(c)) return true;
		}
		return false;
	}

	protected void debug(String msg) {
		Utils.log2(msg + ": queue size = " + queue.size() + "  hs size: " + hs.size());
	}

	/** Returns a hash table with all selected Displayables as keys, and a copy of their affine transform as value. This is useful to easily create undo steps. */
	protected Hashtable getTransformationsCopy() {
		Hashtable ht_copy = new Hashtable(hs.size());
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			ht_copy.put(d, d.getAffineTransformCopy());
		}
		return ht_copy;
	}

	// this method should be removed TODO
	void update() {
		synchronized (queue_lock) {
		try {
			lock();
			if (transforming) {
				Utils.log("Selection.update warning: shouldn't be doing this while transforming!");
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
			new IJError(e);
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
		setHandles(box);
	}

	/** Update the bounding box of the whole selection. */
	public void updateTransform(Displayable d) {
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
			new IJError(e);
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
	public void rotate(double angle) {
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next(); // all this is so obvious and ridiculous compared to python's for t in ht: ...
			d.rotate(Math.toRadians(angle), floater.x, floater.y);
		}
		resetBox();
	}
	/** Translate all selected objects and their links by the given differentials. The floater position is unaffected; if you want to update it call centerFloater() */
	public void translate(double dx, double dy) {
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			d.translate(dx, dy);
		}
		resetBox();
	}

	/** Returns all selected Displayables (and not their linked ones). */
	public ArrayList getSelected() {
		final ArrayList al = new ArrayList();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			al.add(it.next());
		}
		return al;
	}

	/** Returns all selected Displayables (and not their linked ones) of the given class. */
	public ArrayList getSelected(final Class c) {
		final ArrayList al = new ArrayList();
		if (null == c || c.equals(Displayable.class) || c.equals(ZDisplayable.class)) {
			al.addAll(queue);
			return al;
		}
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (c.equals(ob.getClass())) al.add(ob);
		}
		return al;
	}

	/** Returns the set of all Displayable objects affected by this selection, that is, the selected ones and their linked ones.*/
	public Set getAffected() {
		return (Set)hs.clone();
	}

	/** Send all selected components to the previous layer. */
	public void moveUp() {
		if (null == display) return;
		Layer la = display.getLayer();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			la.getParent().moveUp(la, (Displayable)it.next());
		}
		clear();
	}

	/** Send all selected components to the next layer. */
	public void moveDown() {
		if (null == display) return;
		Layer la = display.getLayer();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			la.getParent().moveDown(la, (Displayable)it.next());
		}
		clear();
	}
}
