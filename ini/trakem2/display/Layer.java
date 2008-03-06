/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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
import ij.ImagePlus;

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.Image;

public class Layer extends DBObject {

	private final ArrayList<Displayable> al_displayables = new ArrayList<Displayable>();

	private double z;
	private double thickness;

	private LayerSet parent;

	public Layer(Project project, double z, double thickness, LayerSet parent) {
		super(project);
		this.z = z;
		this.thickness = thickness;
		this.parent = parent;
		addToDatabase();
	}

	/** Reconstruct from database*/
	public Layer(Project project, long id, double z, double thickness) {
		super(project, id);
		this.z = z;
		this.thickness = thickness;
		this.parent = null;
	}

	/** Reconstruct from XML file. */
	public Layer(Project project, long id, Hashtable ht_attributes) {
		super(project, id);
		this.parent = null;
		// parse data
		for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht_attributes.get(key);
			if (key.equals("z")) {
				this.z = Double.parseDouble(data);
			} else if (key.equals("thickness")) {
				this.thickness = Double.parseDouble(data);
			}
			// all the above could have been done with reflection since the fields have the same name
		}
	}

	/** Creates a new Layer asking for z and thickness, and adds it to the parent and returns it. Returns null if the dialog was canceled.*/
	static public Layer create(Project project, LayerSet parent) {
		if (null == parent) return null;
		GenericDialog gd = ControlWindow.makeGenericDialog("New Layer");
		gd.addMessage("In pixels:"); // TODO set elsewhere the units!
		gd.addNumericField("z coordinate: ", 0.0D, 3);
		gd.addNumericField("thickness: ", 1.0D, 3);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		try {
			double z = gd.getNextNumber();
			double thickness = gd.getNextNumber();
			if (Double.isNaN(z) || Double.isNaN(thickness)) return null;
			Layer layer = new Layer(project, z, thickness, parent);
			parent.add(layer);
			return layer;
		} catch (Exception e) {}
		return null;
	}

	static public Layer[] createMany(Project project, LayerSet parent) {
		if (null == parent) return null;
		GenericDialog gd = ControlWindow.makeGenericDialog("Many new layers");
		gd.addNumericField("First Z coord: ", 0, 3);
		gd.addNumericField("thickness: ", 1.0, 3);
		gd.addNumericField("Number of layers: ", 1, 0);
		gd.addCheckbox("Skip existing layers", true);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		// start iteration to add layers
		boolean skip = gd.getNextBoolean();
		double z = gd.getNextNumber();
		double thickness = gd.getNextNumber();
		int n_layers = (int)gd.getNextNumber();
		if (thickness < 0) {
			Utils.showMessage("Can't create layers with negative thickness");
			return null;
		}
		if (n_layers < 1) {
			Utils.showMessage("Invalid number of layers");
			return null;
		}
		Layer[] layer = new Layer[n_layers];
		for (int i=0; i<n_layers; i++) {
			if (skip) {
				layer[i] = parent.getLayer(z);
				if (null == layer[i]) {
					layer[i] = new Layer(project, z, thickness, parent);
					parent.addSilently(layer[i]);
				} else layer[i] = null; // don't create
			} else {
				layer[i] = new Layer(project, z, thickness, parent);
				parent.addSilently(layer[i]);
			}
			z += thickness;
		}
		// update the scroller of currently open Displays
		Display.updateLayerScroller(parent);
		return layer;
	}

	/** Returns a title such as 018__4-K4_2__z1.67 , which is [layer_set index]__[title]__[z coord] . The ordinal number starts at 1 and finishes at parent's length, inclusive. */
	public String getPrintableTitle() {
		final LayerThing lt = project.findLayerThing(this);
		if (null == lt) return toString();
		String title = lt.getTitle();
		if (null == title) title = "";
		else title = title.replace(' ', '_');
		StringBuffer sb = new StringBuffer().append(parent.indexOf(this) + 1);
		String s_size = Integer.toString(parent.size());
		while (sb.length() < s_size.length()) {
			sb.insert(0, '0');
		}
		sb.append('_').append('_').append(title).append('_').append('_').append('z').append(Utils.cutNumber(this.z, 3, true));
		return sb.toString();
	}

	public String toString() {
		if (null == parent) return "z=" + Utils.cutNumber(z, 4);
		//return "z=" + Utils.cutNumber(z / parent.getCalibration().pixelDepth * z !!!?? I don't have the actual depth to correct with.
		return "z=" + Utils.cutNumber(z, 4);
	}

	/** Add a displayable and update all Display instances showing this Layer. */
	public void add(Displayable displ) { add(displ, true); }

	public void add(Displayable displ, boolean update_displays) {
		add(displ, update_displays, true);
	}

	public void add(Displayable displ, boolean update_displays, boolean update_db) {
		if (null == displ || -1 != al_displayables.indexOf(displ)) return;

		int i=-1, j=-1;
		Displayable[] d = new Displayable[al_displayables.size()];
		al_displayables.toArray(d);
		// what is it?
		if (displ instanceof Patch) {
			// find last Patch (which start at 0)
			for (i=0; i<d.length; i++) {
				if (d[i] instanceof Patch) { j = i;}
				else break;
			}
			if (-1 != j) {
				j++;
				if (j >= d.length) al_displayables.add(displ); // at the end
				else al_displayables.add(j, displ);
			} else {
				// no patches
				al_displayables.add(0, displ); // at the very beggining
			}
		} else if (displ instanceof Profile) {
			// find first LayerSet or if none, first DLabel, add before it
			for (i=d.length-1; i>-1; i--) {
				if (! (d[i] instanceof DLabel || d[i] instanceof LayerSet)) { j = i; break; }
			}
			if (-1 != j) {
				j++;
				if (j >= d.length) al_displayables.add(displ);
				else al_displayables.add(j, displ);
			} else {
				// no labels or LayerSets
				al_displayables.add(displ); // at the end
			}
		} else if (displ instanceof LayerSet) {
			// find first DLabel, add before it
			for (i=d.length-1; i>-1; i--) {
				if (! (d[i] instanceof DLabel)) { j = i; break; }
			}
			if (-1 != j) {
				j++; // add it after the non-label one, displacing the label one position
				if (j >= d.length) al_displayables.add(displ); // at the end
				else al_displayables.add(j, displ);
			} else {
				// no labels
				al_displayables.add(displ); // at the end
			}
		} else {
			// displ is a DLabel
			al_displayables.add(displ); // at the end
		}
		if (update_db) {
			updateInDatabase("stack_index"); // of the displayables ...
			displ.setLayer(this);
		} else {
			displ.setLayer(this, false);
		}
		if (update_displays) {
			Display.add(this, displ);
		}
	}

	/** Used for reconstruction purposes. Assumes the displ are given in the proper order! */
	public void addSilently(final DBObject displ) { // why DBObject and not Displayable ?? TODO
		if (null == displ || -1 != al_displayables.indexOf(displ)) return;
		try {
			((Displayable)displ).setLayer(this, false);
			al_displayables.add((Displayable)displ);
		} catch (Exception e) {
			Utils.log("Layer.addSilently: Not a Displayable/LayerSet, not adding DBObject id=" + displ.getId());
			return;
		}
	}

	/** Remove the Layer only if it's empty. */
	public boolean remove(Displayable displ) {
		if (null == displ || null == al_displayables || -1 == al_displayables.indexOf(displ)) return false;
		al_displayables.remove(displ);
		Display.remove(this, displ);
		return true;
	}

	/** Used for reconstruction purposes. */
	public void setParentSilently(LayerSet layer_set) {
		if (layer_set == this.parent) return;
		this.parent = layer_set;
		//Utils.log("Layer " +id + ": I have as new parent the LayerSet " + layer_set.getId());
	}

	public void setParent(LayerSet layer_set) { // can be null
		if (layer_set == this.parent) return;
		this.parent = layer_set;
		updateInDatabase("layer_set_id");
	}

	public LayerSet getParent() {
		return parent;
	}

	public double getZ() { return z; }
	public double getThickness() { return thickness; }

	/** Remove this layer and all its contents from the project. */
	public boolean remove(boolean check) {
		try {
			if (check && !Utils.check("Really delete " + this.toString() + " and all its children?")) return false;
			// destroy the Display objects that show this layer
			Display.close(this);
			// proceed to remove all the children
			Displayable[] displ = new Displayable[al_displayables.size()]; // to avoid concurrent modifications
			al_displayables.toArray(displ);
			for (int i=0; i<displ.length; i++) {
				if (!displ[i].remove(false)) { // will call back Layer.remove(Displayable)
					Utils.showMessage("Could not delete " + displ[i]);
					return false;
				}
			}
			al_displayables.clear();
			// remove from the parent
			/*can't ever be null//if (null != parent) */
			parent.remove(this);
			removeFromDatabase();
		} catch (Exception e) { IJError.print(e); return false; }
		return true;
	}

	public void setZ(double z) {
		if (Double.isNaN(z) || z == this.z) return;
		this.z = z;
		if (null != parent) {
			parent.reposition(this);
			// fix ordering in the trees (must be done after repositioning in the parent)
			LayerThing lt = project.findLayerThing(this);
			if (null != lt) {
				LayerThing p = (LayerThing)lt.getParent();
				if (null != p) {
					p.removeChild(lt); // does not affect the database
					p.addChild(lt); // idem
				}
			}
		}
		updateInDatabase("z");
	}

	public void setThickness(final double thickness) {
		if (Double.isNaN(thickness) || thickness == this.thickness) return;
		this.thickness = thickness;
		updateInDatabase("thickness");
	}

	public boolean contains(final int x, final int y, int inset) {
		if (inset < 0) inset = -inset;
		return x >= inset && y >= inset && x <= parent.getLayerWidth() - inset && y <= parent.getLayerHeight() - inset;
	}

	public boolean contains(final Displayable displ) {
		return -1 != al_displayables.indexOf(displ);
	}

	/** Returns true if any of the Displayable objects are of the given class. */
	public boolean contains(final Class c) {
		for (Object ob : al_displayables) {
			if (ob.getClass().equals(c)) return true;
		}
		return false;
	}

	/** Count instances of the given Class. */
	public int count(final Class c) {
		int n = 0;
		for (Object ob : al_displayables) {
			if (ob.getClass().equals(c)) n++;
		}
		return n;
	}

	public boolean isEmpty() {
		return 0 == al_displayables.size() && parent.isEmptyAt(this); // check for ZDisplayable painting here as well
	}

	/** Returns a copy of the list of Displayable objects.*/
	public ArrayList<Displayable> getDisplayables() {
		return (ArrayList<Displayable>)al_displayables.clone();
	}

	public int getNDisplayables() {
		return al_displayables.size();
	}

	/** Returns a list of Displayable of class c only.*/
	public ArrayList<Displayable> getDisplayables(final Class c) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (null == c) return al;
		if (c.equals(Displayable.class)) {
			al.addAll(al_displayables);
			return al;
		}
		for (Object ob : al_displayables) {
			if (c.equals(ob.getClass())) al.add((Displayable)ob); // cast only the few added, not all as it would in looping with  Displayabe d : al_displayables
		}
		return al;
	}

	/** Returns a list of all Displayable of class c that intersect the given roi. */
	public ArrayList<Displayable> getDisplayables(final Class c, final Rectangle roi) {
		final ArrayList<Displayable> al = getDisplayables(c);
		final Area aroi = new Area(roi);
		for (Iterator<Displayable> it = al_displayables.iterator(); it.hasNext(); ) {
			Displayable d = it.next();
			Area area = new Area(d.getPerimeter());
			area.intersect(aroi);
			Rectangle b = area.getBounds();
			if (0 == b.width || 0 == b.height) {
				it.remove();
			}
		}
		return al;
	}

	public Displayable get(final long id) {
		for (Displayable d : al_displayables) {
			if (d.getId() == id) return d;
		}
		return null;
	}

	public double getLayerWidth() {
		return parent.getLayerWidth();
	}
	public double getLayerHeight() {
		return parent.getLayerHeight();
	}

	/** Find the Displayable objects that contains the point. */
	public ArrayList<Displayable> find(int x, int y) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (int i = al_displayables.size() -1; i>-1; i--) {
			Displayable d = (Displayable)al_displayables.get(i);
			if (d.contains(x, y)) {
				al.add(d);
			}
		}
		return al;
	}

	/** Find the Displayable objects that intersect with the rectangle. */
	public ArrayList<Displayable> find(final Rectangle r) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Displayable d : al_displayables) {
			if (d.getBoundingBox().intersects(r)) {
				al.add(d);
			}
		}
		return al;
	}

	/** Find the Displayable objects of class 'target' whose bounding box intersects the given Displayable (which is itself not included if present in this very Layer). */
	public ArrayList<Displayable> getIntersecting(final Displayable d, final Class target) {
		ArrayList<Displayable> al = new ArrayList();
		for (int i = al_displayables.size() -1; i>-1; i--) {
			Object ob = al_displayables.get(i);
			if (!ob.getClass().equals(target)) continue;
			Displayable da = (Displayable)ob;
			if (d.intersects(da)) {
				al.add(da);
			}
		}
		// remove the calling one
		if (al.contains(d)) al.remove(d);
		return al;
	}

	/** Returns -1 if not found. */
	public int indexOf(Displayable d) {
		return al_displayables.indexOf(d);
	}

	/** Within its own class only. */ // 'up' is at the last element of the ArrayList (since when painting, the first one gets painted first, and thus gets buried the most while the last paints last, on top)
	public void moveUp(Displayable d) {
		int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || al_displayables.size() -1 == i) return;
		if (al_displayables.get(i+1).getClass().equals(d.getClass())) {
			//swap
			al_displayables.remove(d);
			al_displayables.add(i+1, d);
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
	}

	/** Within its own class only. */
	public void moveDown(Displayable d) {
		int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || 0 == i) return;
		if (al_displayables.get(i-1).getClass().equals(d.getClass())) {
			//swap
			Displayable o = al_displayables.remove(i-1);
			al_displayables.add(i, o);
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
	}

	/** Within its own class only. */
	public void moveTop(Displayable d) { // yes I could have made several lists and make my live easier. Whatever
		int i = al_displayables.indexOf(d);
		int size = al_displayables.size();
		if (null == d || -1 == i || size -1 == i) return;
		Class c = d.getClass();
		boolean done = false;
		for (i=i+1; i<size; i++) {
			if (al_displayables.get(i).getClass().equals(c)) continue;
			else {
				al_displayables.remove(d);
				al_displayables.add(i-1, d);
				done = true;
				break;
			}
		}
		// solves case of no other class present
		if (!done) {
			//add at the end
			al_displayables.remove(d);
			al_displayables.add(d);
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
	}

	/** Within its own class only. */
	public void moveBottom(Displayable d) {
		int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || 0 == i) return;
		Class c = d.getClass();
		boolean done = false;
		for (i=i-1; i > -1; i--) {
			if (al_displayables.get(i).getClass().equals(c)) continue;
			else {
				al_displayables.remove(d);
				al_displayables.add(i+1, d);
				done = true;
				break;
			}
		}
		// solve case of no other class present
		if (!done) {
			al_displayables.remove(d);
			al_displayables.add(0, d);
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
	}

	/** Within its own class only. */
	public boolean isTop(Displayable d) {
		int i = al_displayables.indexOf(d);
		int size = al_displayables.size();
		if (size -1 == i) return true;
		if (al_displayables.get(i+1).getClass().equals(d.getClass())) return false;
		return true;
	} // these two methods will throw an Exception if the Displayable is not found (-1 == i) (the null.getClass() *should* throw it)
	/** Within its own class only. */
	public boolean isBottom(Displayable d) {
		int i = al_displayables.indexOf(d);
		if (0 == i) return true;
		if (al_displayables.get(i-1).getClass().equals(d.getClass())) return false;
		return true;
	}

	/** Get the index of the given Displayable relative to the rest of its class. Beware that the order of the al_displayables is bottom at zero, top at last, but the relative index returned here is inverted: top at zero, bottom at last -to match the tabs' vertical orientation in a Display.*/
	public int relativeIndexOf(Displayable d) {
		int k = al_displayables.indexOf(d);
		if (-1 == k) return -1;
		Class c = d.getClass();
		int size = al_displayables.size();
		int i = k+1;
		for (; i<size; i++) {
			if (al_displayables.get(i).getClass().equals(c)) continue;
			else {
				return i - k -1;
			}
		}
		if (i == size) {
			return i - k - 1;
		}
		Utils.log2("relativeIndexOf: return 0");
		return 0;
	}

	/** Note: Non-recursive into embedded LayerSet objects. */
	public void setVisible(String type, boolean visible, boolean repaint) {
		type = type.toLowerCase();
		Iterator it = al_displayables.iterator();
		while (it.hasNext()) {
			Displayable d = (Displayable)it.next();
			if (d.getClass().getName().toLowerCase().endsWith(type)) {
				d.setVisible(visible, false); // don't repaint
				Display.updateVisibilityCheckbox(this, d, null);
			}
		}
		if (repaint) {
			Display.repaint(this);
		}
	}
	public void setAllVisible(boolean repaint) {
		for (Displayable d : al_displayables) {
			d.setVisible(true, repaint);
		}
	}

	/** Hide all except those whose type is in 'type' list, whose visibility flag is left unchanged. */
	public void hideExcept(ArrayList<Class> type, boolean repaint) {
		for (Displayable d : al_displayables) {
			if (!type.contains(d.getClass())) d.setVisible(false, repaint);
		}
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		String in = indent + "\t";
		// 1 - open tag
		sb_body.append(indent).append("<t2_layer oid=\"").append(id).append("\"\n")
		       .append(in).append(" thickness=\"").append(thickness).append("\"\n")
		       .append(in).append(" z=\"").append(z).append("\"\n")
		;
		String title = project.findLayerThing(this).getTitle();
		if (null == title) title = "";
		sb_body.append(in).append(" title=\"").append(title).append("\"\n"); // TODO 'title' should be a property of the Layer, not the LayerThing. Also, the LayerThing should not exist: LayerSet and Layer should be directly presentable in a tree. They are not Things as in "objects of the sample", but rather, structural necessities such as Patch.
		sb_body.append(indent).append(">\n");
		// 2 - export children
		if (null != al_displayables) {
			for (Iterator it = al_displayables.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				// don't include in the XML file if the object is empty
				if (!d.isDeletable()) d.exportXML(sb_body, in, any);
			}
		}
		// 3 - close tag
		sb_body.append(indent).append("</t2_layer>\n");
	}

	/** Includes all Displayable objects in the list of possible children. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_layer";
		if (hs.contains(type)) return;
		sb_header.append(indent).append("<!ELEMENT t2_layer (t2_patch,t2_label,t2_layer_set,t2_profile)>\n")
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" oid").append(Displayable.TAG_ATTR2)
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" thickness").append(Displayable.TAG_ATTR2)
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" z").append(Displayable.TAG_ATTR2)
		;
	}

	public String getTitle() {
		LayerThing lt = project.findLayerThing(this);
		if (null == lt) return this.toString();
		return lt.getTitle(); //lt.toString();
	}

	public void destroy() {
		for (Iterator it = al_displayables.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			d.destroy();
		}
	}

	/** Returns null if no Displayable objects of class c exist. */
	public Rectangle getMinimalBoundingBox(final Class c) {
		Rectangle box = null;
		Rectangle tmp = new Rectangle();
		for (Iterator it = getDisplayables(c).iterator(); it.hasNext(); ) {
			tmp = ((Displayable)it.next()).getBoundingBox(tmp);
			if (null == box) {
				box = (Rectangle)tmp.clone();
				continue;
			}
			box.add(tmp);
		}
		return box;
	}

	/** Preconcatenate the given AffineTransform to all Displayable objects of class c, without respecting their links. */
	public void apply(final Class c, final AffineTransform at) {
		boolean all = c.equals(Displayable.class);
		for (Iterator it = al_displayables.iterator(); it.hasNext(); ) {
			final Displayable d = (Displayable)it.next();
			if (all || d.getClass().equals(c)) {
				d.getAffineTransform().preConcatenate(at);
				d.updateInDatabase("transform");
			}
		}
	}

	/** Make a copy of this layer into the given LayerSet, enclosing only Displayable objects within the roi, and translating them for that roi x,y. */
	public Layer clone(final Project pr, LayerSet ls, final Rectangle roi, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Layer copy = new Layer(pr, nid, z, thickness);
		copy.parent = ls;
		for (Iterator it = find(roi).iterator(); it.hasNext(); ) {
			Displayable dc = ((Displayable)it.next()).clone(pr, copy_id);
			copy.addSilently(dc);
		}
		final AffineTransform transform = new AffineTransform();
		transform.translate(-roi.x, -roi.y);
		copy.apply(Displayable.class, transform);
		return copy;
	}

	static public final int IMAGEPROCESSOR = 0;
	static public final int PIXELARRAY = 1;
	static public final int IMAGE = 2;
	static public final int IMAGEPLUS = 3;

	/** Returns the region defined by the rectangle as an image in the type and format specified.
	 *  The type is either ImagePlus.GRAY8 or ImagePlus.COLOR_RGB.
	 *  The format is either Layer.IMAGEPROCESSOR, Layer.IMAGEPLUS, Layer.PIXELARRAY or Layer.IMAGE.
	 */
	public Object grab(final Rectangle r, final double scale, final Class c, final int c_alphas, final int format, final int type) {
		// check that it will fit in memory
		if (!project.getLoader().releaseToFit(r.width, r.height, type, 1.1f)) {
			Utils.log("Layer.grab: Cannot fit a flat image of " + (long)(r.width*r.height*(ImagePlus.GRAY8==type?1:4)*1.1) + " bytes in memory.");
			return null;
		}
		if (IMAGE == format) {
			return project.getLoader().getFlatAWTImage(this, r, scale, c_alphas, type, c, null, true);
		} else {
			final ImagePlus imp = project.getLoader().getFlatImage(this, r, scale, c_alphas, type, c, null, true);
			switch (format) {
				case IMAGEPLUS:
					return imp;
				case IMAGEPROCESSOR:
					return imp.getProcessor();
				case PIXELARRAY:
					return imp.getProcessor().getPixels();
			}
		}
		return null;
	}

	public DBObject findById(final long id) {
		if (this.id == id) return this;
		for (Displayable d : al_displayables) {
			if (d.getId() == id) return d;
		}
		return null;
	}

	// private to the package
	void linkPatchesR() {
		for (Displayable d : al_displayables) {
			if (d.getClass().equals(LayerSet.class)) ((LayerSet)d).linkPatchesR();
			d.linkPatches(); // Patch.class does nothing
		}
	}

	/** Recursive into nested LayerSet objects.*/
	public void updateLayerTree() {
		project.getLayerTree().addLayer(parent, this);
		for (Displayable d : getDisplayables(LayerSet.class)) {
			((LayerSet)d).updateLayerTree();
		}
	}
}
