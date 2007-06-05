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

import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

// now all one has to make the Canvas do is manage the selection process, and once double-clicked (i.e. start transform mode), forward the mouse events to the selection. But this class can also manage the dragging events. The Canvas has to keep an undo list with all hashtables of transforms.
// Then, while a selection exists, the canvas must repaint using the transforms stored in the selection. Otherwise paint the objects directly.


/** Keeps track of selected objects and mediates their transformation.*/ 
public class Selection {

	/** Outward padding in pixels around the selected Displayables maximum enclosing box that may need repainting when updating the screen.*/
	static public int PADDING = 31;

	private Display display;
	/** Queue of all selected objects. */
	private final LinkedList queue = new LinkedList();
	/** Relation of all selected objects plus their links, versus their transforms. */ // because what has to be transformed is all objects linked to the selected ones.
	private final Hashtable ht = new Hashtable();
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
	private final int FLOATER = 12;
	private final Handle NW = new BoxHandle(0,0, iNW);
	private final Handle N  = new BoxHandle(0,0, iN);
	private final Handle NE  = new BoxHandle(0,0, iNE);
	private final Handle E  = new BoxHandle(0,0, iE);
	private final Handle SE  = new BoxHandle(0,0, iSE);
	private final Handle S  = new BoxHandle(0,0, iS);
	private final Handle SW  = new BoxHandle(0,0, iSW);
	private final Handle W  = new BoxHandle(0,0, iW);
	private final Handle R_NW = new OvalHandle(0,0, rNW, -1, -1);
	private final Handle R_NE = new OvalHandle(0,0, rNE, 1, -1);
	private final Handle R_SE = new OvalHandle(0,0, rSE, 1, 1);
	private final Handle R_SW = new OvalHandle(0,0, rSW, -1, 1);
	/** Pivot of rotation. Always checked first on mouse pressed, before other handles. */
	private final Floater floater = new Floater(0, 0, FLOATER);
	private final Handle[] handles;
	private Handle grabbed = null;
	private boolean dragging = false;
	private boolean rotating = false;
	private boolean keep_ratio = false;

	private int x_d_old, y_d_old, x_d, y_d; // for rotations

	private ImagePlus virtual_imp = null;

	/** The Display can be null, as long as paint, OvalHandle.contains, setTransforming, and getLinkedBox methods are never called on this object. */
	public Selection(Display display) {
		this.display = display;
		this.handles = new Handle[]{NW, N, NE, E, SE, S, SW, W, R_NW, R_NE, R_SE, R_SW, floater}; // shitty java, why no dictionaries (don't get me started with Hashtable class painful usability)
	}

	/** Paint a white frame around the selected object and a pink frame around all others. Active is painted last, so white frame is always top. */
	public void paint(Graphics g, Rectangle srcRect, double magnification) {
		// paint rectangle around selected Displayable elements
		if (queue.isEmpty()) return;
		g.setColor(Color.pink);
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			Transform t = (Transform)ht.get(d);
			//Utils.log("d, t: " + d + ", " + t);
			if (null == t) {
				Utils.log2("Selection.paint warning: null t for d: " + d);
				continue;
			}
			if (d.equals(active)) {
				g.setColor(Color.white);
				t.paintBox(g, srcRect, magnification);
				g.setColor(Color.pink);
			}
			t.paintBox(g, srcRect, magnification);
		}
		//Utils.log2("transforming, dragging, rotating: " + transforming + "," + dragging + "," + rotating);
		if (transforming && !rotating) {
			//Utils.log("box painting: " + box);
			g.setColor(Color.yellow);
			Graphics2D g2d = (Graphics2D)g;
			Stroke original_stroke = g2d.getStroke();
			Composite original_composite = g2d.getComposite();
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
			//g2d.setStroke(new BasicStroke(5)); // no need  , BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			
			// 30 pixel line, 10 pixel gap, 10 pixel line, 10 pixel gap
			float[] dashPattern = { 30, 10, 10, 10 };
			g2d.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dashPattern, 0));

			// paint box
			g.drawRect((int)((box.x - srcRect.x)*magnification),
				   (int)((box.y - srcRect.y)*magnification),
				   (int)Math.ceil(box.width  * magnification),
				   (int)Math.ceil(box.height * magnification));
			//restore Graphics object
			g2d.setComposite(original_composite);
			g2d.setStroke(original_stroke);
			// paint handles for scaling (boxes) and rotating (circles), and floater
			for (int i=0; i<handles.length; i++) {
				handles[i].paint(g, srcRect, magnification);
			}
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
		public void paint(Graphics g, Rectangle srcRect, double mag) {
			int x = (int)((this.x - srcRect.x)*mag);
			int y = (int)((this.y - srcRect.y)*mag);
			g.setColor(Color.white);
			g.drawRect(x - 2, y - 2, 5, 5);
			g.setColor(Color.black);
			g.drawRect(x - 1, y - 1, 3, 3);
			g.setColor(Color.white);
			g.fillRect(x, y, 1, 1);
		}
		public void drag(int dx, int dy) {
			Rectangle box_old = (Rectangle)box.clone();
			//Utils.log2("dx,dy: " + dx + "," + dy + " before mod");
			if (keep_ratio) { // keep largest
				switch (this.id) {
					case iN:
					case iS:
						// listen only to dy
						dx = dy;
						break;
					case iE:
					case iW:
						// listen only to dx
						dy = dx;
						break;
					case iNW:
					case iSE:
						// listen to the largest
						if (Math.abs(dx) > Math.abs(dy)) dy = dx;
						else dx = dy;
						break;
					case iNE:
					case iSW:
						// listen to the largest by assign the negative of!
						if (Math.abs(dx) > Math.abs(dy)) dy = -dx;
						else dx = -dy;
						break;
				}
			}
			double res = dx / 2.0;
			res -= Math.floor(res);
			res *= 2;
			int extra = (int)res;
			//Utils.log2("dx,dy: " + dx + "," + dy + ", keep_ratio is " + keep_ratio + "  extra: " + extra);
			switch (this.id) { // java sucks to such an extent, I don't even bother
				case iNW:
					if (x + dx >= E.x) return;
					if (y + dy >= S.y) return;
					box.x += dx;
					box.y += dy;
					box.width -= dx;
					box.height -= dy;
					break;
				case iN:
					if (y + dy >= S.y) return;
					box.y += dy;
					box.height -= dy;
					if (keep_ratio) {
						box.x += dx/2;
						box.y -= extra; //correcting years and years of inaccurate computer math. What are these guys thinking?
						box.width -= dx;
					}
					break;
				case iNE:
					if (x + dx <= W.x) return;
					if (y + dy >= S.y) return;
					box.y += dy;
					box.width += dx;
					box.height -= dy;
					break;
				case iE:
					if (x + dx <= W.x) return;
					box.width += dx;
					if (keep_ratio) {
						box.y -= dy/2;
						box.height += dy + extra;
					}
					break;
				case iSE:
					if (x + dx <= W.x) return;
					if (y + dy <= N.y) return;
					box.width += dx;
					box.height += dy;
					break;
				case iS:
					if (y + dy <= N.y) return;
					box.height += dy;
					if (keep_ratio) {
						box.x -= dx/2;
						box.width += dx;
						box.height -= extra;
					}
					break;
				case iSW:
					if (x + dx >= E.x) return;
					if (y + dy <= N.y) return;
					box.x += dx;
					box.width -= dx;
					box.height += dy;
					break;
				case iW:
					if (x + dx >= E.x) return;
					box.x += dx;
					box.width -= dx;
					if (keep_ratio) {
						box.y += dy/2;
						box.x -= extra;
						box.height -= dy;
					}
					break;
			}
			// proportion:
			double px = (double)box.width / (double)box_old.width;
			double py = (double)box.height / (double)box_old.height;
			if (keep_ratio) { // homogenize to largest
				if (px > py) py = px;
				else px = py;
			}
			// displacement: specific of each element of the selection and their links, depending on where they are.
			for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
				Transform t = (Transform)it.next();
				t.scale(px, py, box, box_old);
			}

			// finally:
			setHandles(box); // overkill. As Graham said, most newly available chip resources are going to be wasted. They are already.
		}
	}

	private class OvalHandle extends Handle {
		int sign_x;
		int sign_y;
		OvalHandle(int x, int y, int id, int sign_x, int sign_y) {
			super(x,y, id);
			this.sign_x = sign_x;
			this.sign_y = sign_y;
		}
		public void paint(Graphics g, Rectangle srcRect, double mag) {
			int x = (int)((this.x - srcRect.x)*mag) + sign_x * 25;
			int y = (int)((this.y - srcRect.y)*mag) + sign_y * 25;
			g.setColor(Color.white);
			g.fillOval(x -4, y -4, 9, 9);
			g.setColor(Color.black);
			g.drawOval(x -2, y -2, 5, 5);
		}
		public boolean contains(int x_p, int y_p, double radius) {
			double mag = display.getCanvas().getMagnification();
			double x = this.x + sign_x * 25 / mag;
			double y = this.y + sign_y * 25 / mag;
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		public void drag(int dx, int dy) {
			/// Bad design, I know, I'm ignoring the dx,dy
			// how:
			// center is the floater

			double cos = Utils.getCos(x_d_old - floater.x, y_d_old - floater.y, x_d - floater.x, y_d - floater.y);
			//double sin = Math.sqrt(1 - cos*cos);
			//double delta = Utils.getAngle(cos, sin);
			double delta = Math.acos(cos); // same thing as the two lines above
			// need to compute the sign of rotation as well: the cross-product!
			// cross-product:
			// a = (3,0,0) and b = (0,2,0)
			// a x b = (3,0,0) x (0,2,0) = ((0 x 0 - 2 x 0), -(3 x 0 - 0 x 0), (3 x 2 - 0 x 0)) = (0,0,6).
			double zc = (x_d_old - floater.x) * (y_d - floater.y) - (x_d - floater.x) * (y_d_old - floater.y);
			if (zc < 0) {
				delta = -delta;
			}
			for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
				Transform t = (Transform)it.next(); // all this is so obvious and ridiculous compared to python's for t in ht: ...
				
				t.rotate(Math.toDegrees(delta), floater.x, floater.y);
			}
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
		}
		public void center() {
			this.x = box.x + box.width/2;
			this.y = box.y + box.height/2;
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
		if (!queue.contains(d)) {
			Utils.log2("Selection.setActive warning: " + d + " is not part of the selection");
			return;
		}
		active = d;
		if (null != display) {
			if (active instanceof ZDisplayable) {
				active.setLayer(display.getLayer());
				//no need, selection is cleared and then the ZDisplayable is added again when changing layers in the Display //ht.put(active, active.getTransform()); // update for the active Layer
			}
			display.setActive(d);
		}
	}

	public void add(Displayable d) {
		if (null == d) {
			Utils.log2("Selection.add warning: skipping null ob");
			return;
		}
		this.active = d;
		if (queue.contains(d)) {
			if (null != display) {
				if (d instanceof ZDisplayable) d.setLayer(display.getLayer());
				display.setActive(d);
			}
			Utils.log2("Selection.add warning: already have " + d + " selected.");
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
		if (!ht.contains(d)) {
			Transform t = d.getTransform();
			ht.put(d, t);
			// now, grab the linked group and add it as well to the hashtable
			HashSet hs = d.getLinkedGroup(new HashSet());
			if (null != hs) {
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable displ = (Displayable)it.next();
					if (!ht.contains(displ)) ht.put(displ, displ.getTransform());
					if (0 != displ.getRotation()) {
						keep_ratio = true;
					}
				}
			}
		}
		// finally: (so that when calling the selection, its Transform exists already here)
		if (null != display) display.setActive(d);
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
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (queue.contains(d)) continue;
			queue.add(d);
			if (ht.contains(d)) continue;
			Transform t = d.getTransform();
			ht.put(d, t);
			// now, grab the linked group and add it as well to the hashtable
			HashSet hs = d.getLinkedGroup(new HashSet());
			if (null != hs) {
				for (Iterator hit = hs.iterator(); hit.hasNext(); ) {
					Displayable displ = (Displayable)hit.next();
					if (!ht.contains(displ)) ht.put(displ, displ.getTransform());
					if (0 != displ.getRotation()) {
						keep_ratio = true;
					}
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
	}

	// TODO think of a way of disabling autocommit, but setting save points and commiting the good part and rolig back tha bad part if any

	/** Delete all selected objects from their Layer. */
	public boolean deleteAll() {
		if (null == active) return true; // nothing to remove
		if (!Utils.check("Remove " + queue.size() + " selected object" + (1 == queue.size() ? "?" : "s?"))) return false;
		if (null != display) display.setActive(null);
		this.active = null;
		StringBuffer sb = new StringBuffer();
		Displayable[] d = new Displayable[queue.size()];
		queue.toArray(d);
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
		return true;
	}

	/** Remove the given displayable from this selection. */
	public void remove(Displayable d) {
		if (null == d) {
			Utils.log2("Selection.remove warning: null Displayable to remove.");
			return;
		}
		Object ob_t = ht.get(d);
		if (null == ob_t) {
			//Utils.log2("Selection.remove warning: can't find ob " + ob_t + " to remove");
			// happens when removing a profile from the project tree that is not selected in the Display to which this Selection belongs
			return;
		}
		queue.remove(d);
		ht.remove(d);
		if (d.equals(active)) {
			if (0 == queue.size()) {
				active = null;
				box = null;
				ht.clear();
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
			ht.clear();
			keep_ratio = false;
			return;
		}
		// now, remove linked ones from the ht
		HashSet hs_to_remove = d.getLinkedGroup(new HashSet());
		HashSet hs_to_keep = new HashSet();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Displayable displ = (Displayable)it.next();
			hs_to_keep = displ.getLinkedGroup(hs_to_keep);
		}
		for (Iterator it = ht.keySet().iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (hs_to_keep.contains(ob)) continue; // avoid linked ones still in queue or linked to those in queue
			ht.remove(ob);
		}
		// recompute box
		Rectangle r = new Rectangle(); // as temp storage
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Transform tr = (Transform)ht.get(it.next());
			box.add(d.getBoundingBox(r));
		}
		// reposition handles
		setHandles(box);
		// recheck keep_ratio
		keep_ratio = false;
		for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
			Transform t = (Transform)it.next();
			if (0 != t.rot) {
				keep_ratio = true;
				break;
			}
		}
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
		R_NW.set(tx, ty);
		R_NE.set(tx + tw, ty);
		R_SE.set(tx + tw, ty + th);
		R_SW.set(tx, ty + th);
	}

	/** Remove all Displayables from this selection. */
	public void clear() {
		this.queue.clear();
		this.ht.clear();
		this.active = null;
		if (null != display) display.setActive(null);
		this.box = null;
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
			// finish transform: apply transformations
			applyTransforms();
			transforming = false;
		}
	}

	public void applyTransforms() {
		if (null != display) display.getProject().getLoader().startLargeUpdate();
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Displayable d = (Displayable)entry.getKey();
			Transform t = (Transform)entry.getValue();
			d.setTransform(t);
		}
		if (null != display) display.getProject().getLoader().commitLargeUpdate();
		resetBox();
	}

	public void cancelTransform() { // TODO : use the undo feature, reread the transforms as originally cached for undo.
		transforming = false;
		if (null == active) return;
		// reread all transforms and remake box
		Rectangle b = new Rectangle();
		box = active.getBoundingBox(b);
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Displayable d = (Displayable)entry.getKey();
			entry.setValue(d.getTransform());
			box.add(d.getBoundingBox(b));
		}
		setHandles(box);
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
					if (grabbed.id >= rNW && grabbed.id <= rSW) rotating = true;
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
			// drag the whole thing
			for (Iterator it = ht.keySet().iterator(); it.hasNext(); ) {
				Transform t = (Transform)ht.get(it.next());
				t.translate(dx, dy);
			}
			//and the box!
			box.x += dx;
			box.y += dy;
			// and the handles!
			setHandles(box);
		}
		// The penalty is tremendous, even when in a thread.
		if (null != active) {
			new Thread() {
				public void run() {
					Transform t = (Transform)ht.get(active);
					//Utils.showStatus("x,y : " + t.x + ", " + t.y, false);
					//Utils.log("x,y : " + t.x + ", " + t.y); // TODO: show in status bar
					//TODO: what it should show is the absolute x,y AND the delta displacement 
				}
			}.start();
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
		if (!transforming) {
			// apply modified transforms if any change has really occurred:
			if (x_p != x_d || y_p != y_d) { // using x_d,y_d since they are the last meaningful ones. This is to avoid the undesired movement that happens when releasing the mouse sometimes.
				applyTransforms();
			}
		} else {
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
	public Rectangle getLinkedBox() {
		if (null == active) return null;
		Rectangle b = active.getBoundingBox();
		Layer layer = display.getLayer();
		Rectangle r = new Rectangle();
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Displayable d = (Displayable)entry.getKey();
			if (d.getLayer().equals(layer)) {
				Transform t = (Transform)entry.getValue();
				b.add(t.getBoundingBox(r));
			}
		}
		// include floater, whereever it is
		b.add(floater.getBoundingBox(r));
		return b;
	}

	/** Test if any of the selection objects is directly or indirectly locked. */
	public boolean isLocked() {
		if (null == active || null == ht || ht.isEmpty()) return false;
		// loop directly to avoid looping through the same linked groups if two or more selected objects belong to the same linked group. The ht contains all linked items anyway.
		for (Iterator it = ht.keySet().iterator(); it.hasNext(); ) {
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
		Utils.log2(msg + ": queue size = " + queue.size() + "  ht size: " + ht.size());
	}

	/** May return null if not selected or not among the linked group. */
	protected Transform getTransform(Displayable d) {
		if (null == ht) {
			Utils.log2("Selection.getTransform warning: null ht");
			return null;
		}
		return (Transform)ht.get(d);
	}

	protected Hashtable getTransformationsCopy() {
		Hashtable ht_copy = new Hashtable(ht.size());
		for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Displayable d = (Displayable)entry.getKey();
			Transform t = (Transform)entry.getValue();
			ht_copy.put(d, t.clone()); // the java language designers knew very well that the type is superfluous info on the pointer .. hence all these "convenient" methods to add and retrieve objects as Object from the lists,collections,tables, etc.
		}
		return ht_copy;
	}

	/** Update the transforms of all selected objects and their linked ones - should NEVER be called directly, but through Display.updateSelection only, to ensure all selections in all Display instances are properly updated. */ // This is rather wrong, since updates will occur for layers that do not belong to the same LayerSet without reason. Needs some redesign of the 'active' and the 'selection' pointers, perhaps moving them to the LayerSet (but I don't want to loose the ability to have a different active/selection in each Display)
	void update() {
		if (transforming) {
			Utils.log("Selection.update warning: shouldn't be doing this while transforming!");
		}
		ht.clear();
		HashSet hs = new HashSet();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			// collect all linked ones into the hs
			hs = d.getLinkedGroup(hs);
		}
		if (0 == hs.size()) {
			active = null;
			if (null != display) display.setActive(null);
			return;
		}
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			ht.put(d, d.getTransform());
		}
	}

	public boolean isDragging() {
		return dragging;
	}

	/** Recalculate box and reset handles. */
	public void resetBox() {
		box = null;
		Rectangle b = new Rectangle();
		for (Iterator it = queue.iterator(); it.hasNext(); ) {
			Transform t = (Transform)ht.get(it.next());
			b = t.getBoundingBox(b);
			if (null == box) box = (Rectangle)b.clone();
			box.add(b);
		}
		setHandles(box);
	}

	/** Update the cached transform for the given Displayable, and the bounding box of the whole selection. */
	public void updateTransform(Displayable d) {
		if (null == d) {
			Utils.log2("Selection.updateTransform warning: null Displayable");
			return;
		}
		if (!ht.containsKey(d)) {
			Utils.log2("Selection.updateTransform warning: " + d + " not selected or among the linked");
			return;
		}
		ht.put(d, d.getTransform());
		resetBox();
	}

	public int getNSelected() {
		return queue.size();
	}

	public int getNLinked() {
		return ht.size();
	}

	/** Rotate the objects in the current selection by the given angle, in degrees, relative to the floater position. */
	public void rotate(double angle) {
		for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
			Transform t = (Transform)it.next(); // all this is so obvious and ridiculous compared to python's for t in ht: ...
			t.rotate(angle, floater.x, floater.y);
		}
		resetBox();
	}
	/** Translate all selected objects and their links by the given differentials. The floater position is unaffected; if you want to update it call centerFloater() */
	public void translate(double dx, double dy) {
		for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
			Transform t = (Transform)it.next();
			t.x += dx;
			t.y += dy;
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
		return ht.keySet();
	}
}
