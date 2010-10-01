/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.ColorModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

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
	/** The box enclosing all selected Displayable, in world coordinates. */
	private Rectangle box = null;

	/** The Display can be null, as long as paint, OvalHandle.contains, setTransforming, and getLinkedBox methods are never called on this object. */
	public Selection(Display display) {
		this.display = display;
	}

	public void setActive(Displayable d) {
		synchronized (queue_lock) {
			try {
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
			}
		}
	}

	public Displayable getActive() {
		return active;
	}

	public void add(final Displayable d) {
		if (null == d) {
			Utils.log2("Selection.add warning: skipping null ob");
			return;
		}
		synchronized (queue_lock) {
		try {
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
			// check if it was among the linked already before adding it's links and its transform
			if (!hs.contains(d)) {
				hs.add(d);
				// now, grab the linked group and add it as well to the hashtable
				HashSet<Displayable> hsl = d.getLinkedGroup(new HashSet<Displayable>());
				if (null != hsl) {
					for (final Displayable displ : hsl) {
						if (!hs.contains(displ)) hs.add(displ);
					}
				}
			}
			// finally:
			if (null != display) display.setActive(d);
		} catch (Exception e) {
			IJError.print(e);
		}}
	}
	
	/** Select all objects in the Display's current layer, preserving the active one (if any) as active; includes all the ZDisplayables, whether visible in this layer or not. */
	public void selectAll() {
		if (null == display) return;
		ArrayList<Displayable> al = display.getLayer().getDisplayables();
		al.addAll(display.getLayer().getParent().getZDisplayables());
		selectAll(al);
	}

	/** Select all isVisible() objects in the Display's current layer, preserving the active one (if any) as active; includes all the ZDisplayables, whether visible in this layer or not, as long as their return true from isVisible(). */
	public void selectAllVisible() {
		if (null == display) return;
		ArrayList<Displayable> al = display.getLayer().getDisplayables();
		al.addAll(display.getLayer().getParent().getZDisplayables());
		final Rectangle tmp = new Rectangle();
		for (final Iterator<Displayable> it = al.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable) it.next();
			if (!d.isVisible() || 0 == d.getAlpha()) {
				it.remove();
				continue;
			}
			Rectangle box = d.getBounds(tmp, display.getLayer());
			if (0 == box.width || 0 == box.height) {
				it.remove(); // not visible either, no data
				continue; // defensive programming
			}
		}
		if (al.size() > 0) selectAll(al);
	}

	/** Select all objects in the given layer, preserving the active one (if any) as active. */
	public void selectAll(Layer layer) {
		selectAll(layer.getDisplayables());
	}

	/** Select all objects under the given roi, in the current display's layer.
	 *  If visible_only, then a Displayable is not selected when its visible boolean flag is false, or its alpha value is zero, or either of its width,height dimensions are 0. */
	public void selectAll(Roi roi, boolean visible_only) {
		if (null == display) return;
		if (null == roi) {
			if (visible_only) {
				selectAllVisible();
				return;
			}
			// entire 2D bounds:
			roi = new ShapeRoi(display.getLayerSet().get2DBounds());
		}
		//Utils.log2("roi bounds: " + roi.getBounds());
		ShapeRoi shroi = roi instanceof ShapeRoi ? (ShapeRoi)roi : new ShapeRoi(roi);

		Area aroi = new Area(M.getShape(shroi));
		AffineTransform affine = new AffineTransform();
		Rectangle bounds = shroi.getBounds();
		affine.translate(bounds.x, bounds.y);
		aroi = aroi.createTransformedArea(affine);
		Collection<Displayable> al = display.getLayer().getDisplayables(Displayable.class, aroi, visible_only);
		al.addAll(display.getLayer().getParent().findZDisplayables(ZDisplayable.class, display.getLayer(), aroi, visible_only, true));
		final Rectangle tmp = new Rectangle();
		if (visible_only) {
			for (Iterator<Displayable> it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (!d.isVisible() || 0 == d.getAlpha()) {
					it.remove();
					continue;
				}
				Rectangle box = d.getBounds(tmp, display.getLayer());
				if (0 == box.width || 0 == box.height) {
					it.remove();
					continue; // defensive programming
				}
			}
		}
		if (al.size() > 0) selectAll(al);
	}

	public void selectAll(Collection<? extends Displayable> al) {
		synchronized (queue_lock) {
		try {
			setPrev(queue);
			for (final Displayable d : al) {
				if (queue.contains(d)) continue;
				queue.add(d);
				if (hs.contains(d)) continue;
				hs.add(d);
				// now, grab the linked group and add it as well to the hashset
				HashSet<Displayable> hsl = d.getLinkedGroup(new HashSet<Displayable>());
				if (null != hsl) {
					for (final Displayable displ : hsl) {
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
		}}
	}

	/** Delete all selected objects from their Layer. */
	public boolean deleteAll() {
		if (0 == queue.size()) return true; // nothing to remove
		if (!Utils.check("Remove " + queue.size() + " selected object" + (1 == queue.size() ? "?" : "s?"))) return false;
		
		final HashSet<Displayable> set = new HashSet<Displayable>(queue.size());
		synchronized (queue_lock) {
			try {
				setPrev(queue);
				if (null != display) display.setActive(null);
				this.active = null;
				set.addAll(queue);
				queue.clear();
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		
		if (null != display) display.getLayerSet().addChangeTreesStep();
		
		set.iterator().next().getProject().removeAll(set);

		Display.updateSelection(); // from all displays

		if (null != display) display.getLayerSet().addChangeTreesStep();

		return true;
	}

	/** Set the elements of the given LinkedList as those of the stored, previous selection, only if the given list is not empty. */
	private void setPrev(final LinkedList<Displayable> q) {
		if (0 == q.size()) return;
		queue_prev.clear();
		queue_prev.addAll(q);
	}

	/** Remove all given displayables from this selection. */
	public void removeAll(final Collection<Displayable> col) {
		for (final Displayable d : col) remove(d);
	}

	/** Remove the given displayable from this selection. */
	public void remove(final Displayable d) {
		if (null == d) {
			Utils.log2("Selection.remove warning: null Displayable to remove.");
			return;
		}
		synchronized (queue_lock) {
		try {
			if (!hs.contains(d)) {
				//Utils.log2("Selection.remove warning: can't find ob " + ob_t + " to remove");
				// happens when removing a profile from the project tree that is not selected in the Display to which this Selection belongs
				return;
			}
			queue.remove(d);
			d.deselect();
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
				return;
			}
			// now, remove linked ones from the hs
			HashSet<Displayable> hs_to_keep = new HashSet<Displayable>();
			for (final Displayable displ : queue) {
				hs_to_keep = displ.getLinkedGroup(hs_to_keep); //accumulates into the hashset
			}
			for (final Iterator<Displayable> it = hs.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				if (hs_to_keep.contains(ob)) continue; // avoid linked ones still in queue or linked to those in queue
				it.remove();
			}
			// recompute box
			Rectangle r = new Rectangle(); // as temp storage
			for (final Displayable di : queue) {
				box.add(di.getBoundingBox(r));
			}
		} catch (Exception e) {
			IJError.print(e);
		}}
	}

	/** Remove all Displayables from this selection. */
	public void clear() {
		synchronized (queue_lock) {
		try {
			// set null active before clearing so that borders can be repainted
			if (null != display && queue.size() > 0) {
				display.setActive(null);
				Display.repaint(display.getLayer(), 5, box, false);
			}
			setPrev(queue);
			this.queue.clear();
			this.hs.clear();
			this.active = null;
			Rectangle bb = box;
			this.box = null;
			if (null != display) {
				Display.repaint(display.getLayer(), 5, bb, false);
			}
		} catch (Exception e) {
			IJError.print(e);
		}}
	}

	/** Returns the total box enclosing all selected objects and their linked objects within the current layer, or null if none are selected. Includes the position of the floater, when transforming.*/
	public Rectangle getLinkedBox() {
		if (null == active) return null;
		Rectangle b = active.getBoundingBox();
		Layer layer = display.getLayer();
		Rectangle r = new Rectangle(); // for reuse
		synchronized (queue_lock) {
			for (final Displayable d : hs) {
				if (!d.equals(active) && d.getLayer().equals(layer)) {
					b.add(d.getBoundingBox(r));
				}
			}
		}
		return b;
	}

	/** Test if any of the selection objects is directly or indirectly locked. */
	public boolean isLocked() {
		// loop directly to avoid looping through the same linked groups if two or more selected objects belong to the same linked group. The ht contains all linked items anyway.
		synchronized (queue_lock) {
			if (null == active || null == hs || hs.isEmpty()) return false;
			for (Displayable d : hs) {
				if (d.isLocked2()) return true;
			}
		}
		return false;
	}

	/** Lock / unlock all selected objects. */
	public void setLocked(final boolean b) {
		apply("locked", new Action() {
			void exec(Displayable d) { d.setLocked(b); }
		}, new Runnable() {
			public void run() {
				synchronized (queue_lock) {
					Display.updateCheckboxes(hs, DisplayablePanel.LOCK_STATE, b);
				}
			}
		});
	}

	final void addDataEditStep(final String[] fields) {
		if (null != display) display.getLayerSet().addDataEditStep(new HashSet<Displayable>(queue), fields);
	}
	
	static private abstract class Action {
		abstract void exec(final Displayable d);
	}

	private void apply(final String field, final Action task, final Runnable post_task) {
		if (null == active) return;
		final HashSet<Displayable> sel = new HashSet<Displayable>();
		final LayerSet ls;
		synchronized (queue_lock) {
			sel.addAll(queue);
			ls = active.getLayer().getParent();
		}
		ls.addDataEditStep(sel, new String[]{field});
		try {
			for (final Displayable d : sel) task.exec(d);
			ls.addDataEditStep(sel, new String[]{field});
			if (null != post_task) post_task.run();
		} catch (Exception e) {
			IJError.print(e);
			ls.undoOneStep();
		}
	}

	public void setColor(final Color c) {
		apply("color", new Action() {
			void exec(Displayable d) { d.setColor(c); }
		}, null);
	}
	public void setAlpha(final float alpha) {
		apply("alpha", new Action() {
			void exec(Displayable d) { d.setAlpha(alpha); }
		}, null);
	}

	public boolean isEmpty() {
		synchronized (queue_lock) {
			return 0 == queue.size();
		}
	}

	public boolean contains(final Displayable d) {
		synchronized (queue_lock) {
			try {
				return queue.contains(d);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return false;
	}

	/** Returns true if selection contains any items of the given class.*/
	public boolean contains(final Class<?> c) {
		if (null == c) return false;
		synchronized (queue_lock) {
			try {
				if (c.equals(Displayable.class) && queue.size() > 0) return true;
				for (final Displayable d : queue) {
					if (c.isInstance(d)) return true;
				}
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return false;
	}

	protected void debug(String msg) {
		Utils.log2(msg + ": queue size = " + queue.size() + "  hs size: " + hs.size());
	}

	/** Recompute list of linked Displayable. */
	void update() {
		synchronized (queue_lock) {
		try {
			if (null != display && display.getCanvas().isTransforming()) {
				Utils.log2("Selection.update warning: shouldn't be doing this while transforming!");
				return;
			}
			Utils.log2("updating selection");
			hs.clear();
			HashSet<Displayable> hsl = new HashSet<Displayable>();
			for (final Displayable d : queue) {
				// collect all linked ones into the hs
				hsl = d.getLinkedGroup(hsl);
			}
			if (0 == hsl.size()) {
				active = null;
				if (null != display) display.setActive(null);
				return;
			}
			hs.addAll(hsl); // hs is final, can't just assign
		} catch (Exception e) {
			IJError.print(e);
		}}
	}

	/** Update the bounding box of the whole selection. */
	public void updateTransform(final Displayable d) {
		if (null == d) {
			Utils.log2("Selection.updateTransform warning: null Displayable");
			return;
		}
		synchronized (queue_lock) {
		try {
			if (!hs.contains(d)) {
				Utils.log2("Selection.updateTransform warning: " + d + " not selected or among the linked");
				return;
			}
			// TODO should call what? The mode? Should remake the mode?
			// resetBox();
		} catch (Exception e) {
			IJError.print(e);
		}}
	}

	public int getNSelected() {
		synchronized (queue_lock) {
			return queue.size();
		}
	}

	public int getNLinked() {
		synchronized (queue_lock) {
			return hs.size();
		}
	}

	/** Rotate the objects in the current selection by the given angle, in degrees, relative to the x_o, y_o origin. */
	public void rotate(final double angle, final double xo, final double yo) {
		final AffineTransform at = new AffineTransform();
		at.rotate(Math.toRadians(angle), xo, yo);

		for (final Displayable d : getAffected()) {
			d.preTransform(at, false); // all linked ones included in the hashset
		}
		// TODO update affine mode
	}

	/** Translate all selected objects and their links by the given differentials. The floater position is unaffected; if you want to update it call centerFloater() */
	public void translate(final double dx, final double dy) {
		final AffineTransform at = new AffineTransform();
		at.translate(dx, dy);

		for (final Displayable d : getAffected()) {
			d.preTransform(at, false); // all linked ones already included in the hashset
		}
		// TODO update affine mode
	}

	/** Scale all selected objects and their links by by the given scales, relative to the origin position. */
	public void scale(final double sx, final double sy, final double x_o, final double y_o) {
		if (0 == sx || 0 == sy) {
			Utils.showMessage("Cannot scale to 0.");
			return;
		}

		final AffineTransform at = new AffineTransform();
		at.translate(x_o, y_o);
		at.scale(sx, sy);
		at.translate(-x_o, -y_o);

		for (final Displayable d : getAffected()) {
			d.preTransform(at, false); // all linked ones already included in the hashset
		}
	}

	/** Returns a copy of the list of all selected Displayables (and not their linked ones). */
	public ArrayList<Displayable> getSelected() {
		synchronized (queue_lock) {
			return new ArrayList<Displayable>(queue);
		}
	}

	/** Returns a copy of the list of all selected Displayables (and not their linked ones) of the given class. */
	public ArrayList<Displayable> getSelected(final Class<?> c) {
		if (null == c || c == Displayable.class) {
			return getSelected();
		}
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Displayable d : getSelected()) {
			if (c.isInstance(d)) al.add(d);
		}
		return al;
	}

	/** Returns a list of selected Displayable of class c only.*/
	public <T extends Displayable> List<T> get(final Class<T> c) {
		if (Displayable.class == c) {
			return (List<T>)getSelected();
		}
		final ArrayList<T> a = new ArrayList<T>();
		for (final Displayable d : getSelected()) {
			if (c.isInstance(d)) a.add((T)d);
		}
		return a;
	}

	/** Returns the subset of selected objects of Class c, in the proper order according to the Layer.indexOf or the LayerSet.indexOf.
	 *  Class c cannot be Displayable (returns null); must be any Displayable subclass. */
	public Collection<Displayable> getSelectedSorted(final Class<? extends Displayable> c) {
		if (Displayable.class == c) return null;
		final ArrayList<Displayable> al = getSelected(c);
		final TreeMap<Integer,Displayable> tm = new TreeMap<Integer,Displayable>();
		if (ZDisplayable.class.isAssignableFrom(c)) {
			for (final Displayable d : al) tm.put(d.getLayerSet().indexOf((ZDisplayable)d), d);
		} else {
			for (final Displayable d : al) tm.put(d.getLayer().indexOf(d), d);
		}
		return tm.values();
	}

	/** Returns the set of all Displayable objects affected by this selection, that is, the selected ones and their linked ones.*/
	public Set<Displayable> getAffected() {
		synchronized (queue_lock) {
			return new HashSet<Displayable>(hs);
		}
	}

	/** Returns the set of all Displayable objects of the given class affected by this selection, that is, among the selected ones and their linked ones. */
	public Set<Displayable> getAffected(final Class<?> c) {
		HashSet<Displayable> copy = new HashSet<Displayable>();
		synchronized (queue_lock) {
			if (Displayable.class.equals(c) && hs.size() > 0) {
				copy.addAll(hs);
				return copy;
			}
			for (final Displayable d : this.hs) {
				if (c.isInstance(d)) copy.add(d);
			}
		}
		return copy;
	}

	/** If any of the selected or linked is of Class c. */
	public boolean containsAffected(final Class<?> c) {
		synchronized (queue_lock) {
			if (Displayable.class.equals(c) && hs.size() > 0) {
				return true;
			}
			for (final Displayable d : hs) {
				if (c.isInstance(d)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Send all selected components to the previous layer. */
	public void moveUp() {
		if (null == display) return;
		Layer la = display.getLayer();
		for (final Displayable d : getSelected()) {
			la.getParent().moveUp(la, d); // calls back Selection.remove
		}
		clear();
	}

	/** Send all selected components to the next layer. */
	public void moveDown() {
		if (null == display) return;
		Layer la = display.getLayer();
		for (final Displayable d : getSelected()) {
			la.getParent().moveDown(la, d); // calls back Selection.remove
		}
		clear();
	}

	/** Set all selected objects visible/hidden; returns a collection of those that changed state.
	 *  Also updates checkboxes state in the Display. */
	public Collection<Displayable> setVisible(boolean b) {
		Collection<Displayable> col = new ArrayList<Displayable>();
		synchronized (queue_lock) {
			for (final Displayable d : queue) {
				if (b != d.isVisible()) {
					d.setVisible(b);
					col.add(d);
				}
			}
		}
		if (null != display) {
			Display.updateCheckboxes(col, DisplayablePanel.VISIBILITY_STATE, b);
			// after updating checkboxes and clearing screenshots:
			Display.repaint(display.getLayer(), box, 10);
		}
		return col;
	}

	/** Removes the given Displayable from the selection and previous selection list. */
	protected void removeFromPrev(Displayable d) {
		if (null == d) return;
		synchronized (queue_lock) {
			queue_prev.remove(d);
		}
	}

	/** Restore the previous selection. */
	public void restore() {
		synchronized (queue_lock) {
			LinkedList<Displayable> q = new LinkedList<Displayable>(queue);
			ArrayList<Displayable> al = new ArrayList<Displayable>();
			al.addAll(queue_prev);
			clear();
			if (al.size() > 0) selectAll(al);
			setPrev(q);
		}
	}

	public void specify() {
		if (null == display || null == display.getActive()) return;
		final GenericDialog gd = new GenericDialog("Specify");
		gd.addMessage("Relative to the floater's position:");
		gd.addNumericField("origin X: ", box.x + box.width/2, 2);
		gd.addNumericField("origin Y: ", box.y + box.height/2, 2);
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
		display.getLayerSet().addTransformStep(active.getLinkedGroup(null));
		final Rectangle sel_box = getLinkedBox();
		double x_o = gd.getNextNumber();
		double y_o = gd.getNextNumber();
		double rot = gd.getNextNumber();
		double dx = gd.getNextNumber();
		double dy = gd.getNextNumber();
		double sx = gd.getNextNumber();
		double sy = gd.getNextNumber();
		if (0 != dx || 0 != dy) translate(dx, dy);
		if (0 != rot) rotate(rot, x_o, y_o);
		if (0 != sx && 0 != sy) scale(sx, sy, x_o, y_o);
		else Utils.showMessage("Cannot scale to zero.");
		sel_box.add(getLinkedBox());
		Display.repaint(display.getLayer(), sel_box, Selection.PADDING);
	}

	protected void apply(final int what, final double[] params) {
		if (null == active) return;
		final LayerSet ls = active.getLayerSet();
		final Collection<Displayable> affected = getAffected();
		try {
			ls.addTransformStep(affected);
			final Rectangle sel_box = getLinkedBox();
			switch (what) {
				case 0: translate(params[0], params[1]); break;
				case 1: rotate(params[0], box.x + box.width/2, box.y + box.height/2); break;
				case 2: scale(params[0], params[1], box.x + box.width/2, box.y + box.height/2); break;
			}
			sel_box.add(getLinkedBox());
			ls.addTransformStep(affected);
			Display.repaint(display.getLayer(), sel_box, Selection.PADDING);
		} catch (Exception e) {
			IJError.print(e);
			ls.undoOneStep();
		}
	}

	/** Apply the given LUT to all selected 8-bit, 16-bit, 32-bit images. */
	public void setLut(ColorModel cm) {
		final ArrayList<Patch> al = new ArrayList<Patch>();
		for (Displayable d : getSelected(Patch.class)) al.add((Patch)d);
		if (0 == al.size()) {
			return;
		}
		if (!Utils.check("Really change LUT for " + al.size() + " images?")) return;
		Utils.log("Selection.setLut: not yet implemented");
	}

	/** Returns the Project of the Display, or if the latter is null, that of the first selected Displayable. */
	public Project getProject() {
		if (null != display) return  display.getProject();
		synchronized (queue_lock) {
			try {
				if (queue.size() > 0) return queue.get(0).getProject();
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return null;
	}

	/** Returns the Layer of the Display, or if the latter is null, that of the first selected Displayable. */
	public Layer getLayer() {
		if (null != display) return display.getLayer();
		synchronized (queue_lock) {
			try {
				if (queue.size() > 0) return queue.get(0).getLayer();
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		return null;
	}

	/** Returns a copy of the box enclosing all selected ob, or null if none.*/
	public Rectangle getBox() {
		if (null == box) return null;
		return (Rectangle)box.clone();
	}
	/** Recalculate box and reset handles. */
	public void resetBox() {
		synchronized (queue_lock) {
			box = null;
			Rectangle b = new Rectangle();
			for (final Displayable d : queue) {
				b = d.getBoundingBox(b);
				if (null == box) box = (Rectangle)b.clone();
				box.add(b);
			}
		}
	}
}
