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


import ij.ImagePlus;
import ij.gui.GenericDialog;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import mpicbg.models.NoninvertibleModelException;

public final class Layer extends DBObject implements Bucketable, Comparable<Layer> {

	private final ArrayList<Displayable> al_displayables = new ArrayList<Displayable>();
	/** For fast search. */
	Bucket root = null;
	private HashMap<Displayable,HashSet<Bucket>> db_map = null;

	private double z = 0;
	private double thickness = 0;

	private LayerSet parent;

	/** Compare layers by Z. */
	static public final Comparator<Layer> COMPARATOR = new Comparator<Layer>() {
		@Override
		public final int compare(final Layer l1, final Layer l2) {
			if (l1 == l2) return 0; // the same layer
			if (l1.getZ() < l2.getZ()) return -1;
			return 1; // even if same Z, prefer the second
		}
		@Override
		public final boolean equals(final Object ob) { return this == ob; }
	};

	public Layer(final Project project, final double z, final double thickness, final LayerSet parent) {
		super(project);
		this.z = z;
		this.thickness = thickness;
		this.parent = parent;
		addToDatabase();
	}

	/** Reconstruct from database*/
	public Layer(final Project project, final long id, final double z, final double thickness) {
		super(project, id);
		this.z = z;
		this.thickness = thickness;
		this.parent = null;
	}

	/** Reconstruct from XML file. */
	public Layer(final Project project, final long id, final HashMap<String,String> ht_attributes) {
		super(project, id);
		this.parent = null;
		// parse data
		String data;
		if (null != (data = ht_attributes.get("z"))) this.z = Double.parseDouble(data);
		else Displayable.xmlError(this, "z", this.z);
		if (null != (data = ht_attributes.get("thickness"))) this.thickness = Double.parseDouble(data);
		else Displayable.xmlError(this, "thickness", this.thickness);
	}

	/** Creates a new Layer asking for z and thickness, and adds it to the parent and returns it. Returns null if the dialog was canceled.*/
	static public Layer create(final Project project, final LayerSet parent) {
		if (null == parent) return null;
		final GenericDialog gd = ControlWindow.makeGenericDialog("New Layer");
		gd.addMessage("In pixels:"); // TODO set elsewhere the units!
		gd.addNumericField("z coordinate: ", 0.0D, 3);
		gd.addNumericField("thickness: ", 1.0D, 3);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		try {
			final double z = gd.getNextNumber();
			final double thickness = gd.getNextNumber();
			if (Double.isNaN(z) || Double.isNaN(thickness)) return null;
			final Layer layer = new Layer(project, z, thickness, parent);
			parent.add(layer);
			parent.recreateBuckets(layer, true);
			return layer;
		} catch (final Exception e) {}
		return null;
	}

	/** Pops up a dialog to choose the first Z coord, the thickness, the number of layers,
	 *  and whether to skip the creation of any layers whose Z and thickness match
	 *  that of existing layers.
	 *  @return The newly created layers. */
	static public List<Layer> createMany(final Project project, final LayerSet parent) {
		if (null == parent) return null;
		final GenericDialog gd = ControlWindow.makeGenericDialog("Many new layers");
		gd.addNumericField("First Z coord: ", 0, 3);
		gd.addNumericField("thickness: ", 1.0, 3);
		gd.addNumericField("Number of layers: ", 1, 0);
		gd.addCheckbox("Skip existing layers", true);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		// start iteration to add layers
		double z = gd.getNextNumber();
		final double thickness = gd.getNextNumber();
		final int n_layers = (int)gd.getNextNumber();
		final boolean skip = gd.getNextBoolean();
		if (thickness < 0) {
			Utils.log("Can't create layers with negative thickness");
			return null;
		}
		if (n_layers < 1) {
			Utils.log("Invalid number of layers");
			return null;
		}
		final List<Layer> layers = new ArrayList<Layer>(n_layers);
		for (int i=0; i<n_layers; i++) {
			Layer la = null;
			if (skip) {
				// Check if layer exists
				la = parent.getLayer(z);
				if (null == la) la = new Layer(project, z, thickness, parent);
				else la = null;
			} else la = new Layer(project, z, thickness, parent);
			if (null != la) {
				parent.addSilently(la);
				layers.add(la);
			}
			z += thickness;
		}
		parent.recreateBuckets(layers, true); // all empty
		// update the scroller of currently open Displays
		Display.updateLayerScroller(parent);
		return layers;
	}

	/** Returns a title such as 018__4-K4_2__z1.67 , which is [layer_set index]__[title]__[z coord] . The ordinal number starts at 1 and finishes at parent's length, inclusive. */
	public String getPrintableTitle() {
		final LayerThing lt = project.findLayerThing(this);
		if (null == lt) return toString();
		String title = lt.getTitle();
		if (null == title) title = "";
		else title = title.replace(' ', '_');
		final StringBuilder sb = new StringBuilder().append(parent.indexOf(this) + 1);
		final int s_size = Integer.toString(parent.size()).length();
		while (sb.length() < s_size) {
			sb.insert(0, '0');
		}
		sb.append('_').append('_').append(title).append('_').append('_').append('z').append(Utils.cutNumber(this.z, 3, true));
		return sb.toString();
	}

	@Override
	public String toString() {
		if (null == parent) return new StringBuilder("z=").append(Utils.cutNumber(z, 4)).toString();
		//return "z=" + Utils.cutNumber(z / parent.getCalibration().pixelDepth * z !!!?? I don't have the actual depth to correct with.
		//return "z=" + Utils.cutNumber(z, 4);
		
		final String unit = parent.getCalibration().getUnit();
		if (unit.equals("pixel")) {
			return "z=" + Utils.cutNumber(z, 4);
		}
		return "z=" + (z * parent.getCalibration().pixelWidth) + " " + unit
			+ " (" + Utils.cutNumber(z, 4) + " px)";
	}

	/** Add a displayable and update all Display instances showing this Layer. */
	public void add(final Displayable displ) { add(displ, true); }

	public void add(final Displayable displ, final boolean update_displays) {
		add(displ, update_displays, true);
	}

	public void add(final Displayable displ, final boolean update_displays, final boolean update_db) {
		if (null == displ || -1 != al_displayables.indexOf(displ)) return;
		if (displ.getProject() != this.project)
			throw new IllegalArgumentException("Layer rejected a Displayable: belongs to a different project.");

		int i=-1, j=-1;
		final Displayable[] d = new Displayable[al_displayables.size()];
		al_displayables.toArray(d);
		int stack_index = 0;
		// what is it?
		if (displ instanceof Patch) {
			// find last Patch (which start at 0)
			for (i=0; i<d.length; i++) {
				if (d[i] instanceof Patch) { j = i;}
				else break;
			}
			if (-1 != j) {
				j++;
				if (j >= d.length) {
					al_displayables.add(displ); // at the end
					stack_index = d.length;
				} else {
					al_displayables.add(j, displ);
					stack_index = j;
				}
			} else {
				// no patches
				al_displayables.add(0, displ); // at the very beggining
				stack_index = 0;
			}
		} else if (displ instanceof Profile) {
			// find first LayerSet or if none, first DLabel, add before it
			for (i=d.length-1; i>-1; i--) {
				if (! (d[i] instanceof DLabel || d[i] instanceof LayerSet)) { j = i; break; }
			}
			if (-1 != j) {
				j++;
				if (j >= d.length) { al_displayables.add(displ); stack_index = d.length; }
				else { al_displayables.add(j, displ); stack_index = j; }
			} else {
				// no labels or LayerSets
				al_displayables.add(displ); // at the end
				stack_index = d.length;
			}
		} else if (displ instanceof LayerSet) {
			// find first DLabel, add before it
			for (i=d.length-1; i>-1; i--) {
				if (! (d[i] instanceof DLabel)) { j = i; break; }
			}
			if (-1 != j) {
				j++; // add it after the non-label one, displacing the label one position
				if (j >= d.length) { al_displayables.add(displ); stack_index = d.length; } // at the end
				else { al_displayables.add(j, displ); stack_index = j; }
			} else {
				// no labels
				al_displayables.add(displ); // at the end
				stack_index = d.length;
			}
		} else {
			// displ is a DLabel
			al_displayables.add(displ); // at the end
			stack_index = d.length;
		}

		if (update_db) {
			updateInDatabase("stack_index"); // of the displayables ...
			displ.setLayer(this);
		} else {
			displ.setLayer(this, false);
		}

		// insert into bucket
		if (null != root) {
			if (d.length == stack_index) {
				// append at the end
				root.put(stack_index, displ, this, db_map);
			} else {
				// add as last first, then update
				root.put(d.length, displ, this, db_map);
				// find and update the range of affected Displayable objects
				root.updateRange(this, displ, stack_index, d.length); // first to last indices affected
			}
		}

		if (update_displays) {
			Display.add(this, displ);
		}
	}

	@Override
	public HashMap<Displayable, HashSet<Bucket>> getBucketMap(final Layer layer) { // ignore layer
		return db_map;
	}

	/** Used for reconstruction purposes. Assumes the displ are given in the proper order! */
	public void addSilently(final DBObject displ) { // why DBObject and not Displayable ?? TODO
		if (null == displ || -1 != al_displayables.indexOf(displ)) return;
		try {
			((Displayable)displ).setLayer(this, false);
			al_displayables.add((Displayable)displ);
		} catch (final Exception e) {
			Utils.log("Layer.addSilently: Not a Displayable/LayerSet, not adding DBObject id=" + displ.getId());
			return;
		}
	}

	/** Will recreate the buckets; if you intend to remove many, use "removeAll" instead,
	 *  so that the expensive operation of recreating the buckets is done only once. */
	public synchronized boolean remove(final Displayable displ) {
		if (null == displ || null == al_displayables) {
			Utils.log2("Layer can't remove Displayable " + displ.getId());
			return false;
		}
		final int old_stack_index = al_displayables.indexOf(displ);
		if (-1 == old_stack_index) {
			Utils.log2("Layer.remove: not found: " + displ);
			return false;
		}
		al_displayables.remove(old_stack_index);
		if (null != root) recreateBuckets();
		parent.removeFromOffscreens(this);
		Display.remove(this, displ);
		return true;
	}
	
	/** Remove a set of children. Does not destroy the children nor remove them from the database, only from the Layer and the Display. */
	public synchronized boolean removeAll(final Set<Displayable> ds) {
		if (null == ds || null == al_displayables) return false;
		// Ensure list is iterated only once: don't ask for index every time!
		for (final Iterator<Displayable> it = al_displayables.iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (ds.contains(d)) {
				it.remove();
				parent.removeFromOffscreens(this);
				Display.remove(this, d);
			}
		}
		if (null != root) recreateBuckets();
		Display.updateVisibleTabs(this.project);
		return true;
	}

	/** Used for reconstruction purposes. */
	public void setParentSilently(final LayerSet layer_set) {
		if (layer_set == this.parent) return;
		this.parent = layer_set;
		//Utils.log("Layer " +id + ": I have as new parent the LayerSet " + layer_set.getId());
	}

	public void setParent(final LayerSet layer_set) { // can be null
		if (layer_set == this.parent) return;
		this.parent = layer_set;
		updateInDatabase("layer_set_id");
	}

	public LayerSet getParent() {
		return parent;
	}

	public double getZ() { return z; }
	public double getThickness() { return thickness; }
	
	public double getCalibratedZ() {
		return z * parent.getCalibration().pixelWidth; // not pixelDepth ... 
	}
	public double getCalibratedThickness() {
		return thickness * parent.getCalibration().pixelWidth; // not pixelDepth ...
	}

	/** Remove this layer and all its contents from the project. */
	@Override
	public boolean remove(final boolean check) {
		try {
			if (check && !Utils.check("Really delete " + this.toString() + " and all its children?")) return false;
			// destroy the Display objects that show this layer
			Display.remove(this);
			// proceed to remove all the children
			final Displayable[] displ = new Displayable[al_displayables.size()]; // to avoid concurrent modifications
			al_displayables.toArray(displ);
			for (int i=0; i<displ.length; i++) {
				if (!displ[i].remove2(false)) { // will call back Layer.remove(Displayable)
					Utils.log("Could not delete " + displ[i]);
					return false;
				}
			}
			al_displayables.clear();
			// remove from the parent
			/*can't ever be null//if (null != parent) */
			parent.remove(this);
			Display.updateLayerScroller(parent);
			removeFromDatabase();
		} catch (final Exception e) { IJError.print(e); return false; }
		return true;
	}

	public void setZ(final double z) {
		if (Double.isNaN(z) || z == this.z) return;
		this.z = z;
		if (null != parent) {
			parent.reposition(this);
			// fix ordering in the trees (must be done after repositioning in the parent)
			final LayerThing lt = project.findLayerThing(this);
			if (null != lt) {
				final LayerThing p = (LayerThing)lt.getParent();
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
	public boolean contains(final Class<?> c) {
		for (final Object ob : al_displayables) {
			if (ob.getClass() == c) return true;
		}
		return false;
	}

	/** Returns true if any of the Displayable objects are of the given class; if {@param visible_only} is true,
	 * will return true only if at least one of the matched objects is visible. */
	public boolean contains(final Class<?> c, final boolean visible_only) {
		for (final Displayable d : al_displayables) {
			if (visible_only && !d.isVisible()) continue;
			if (d.getClass() == c) return true;
		}
		return false;
	}

	/** Count instances of the given Class. */
	public int count(final Class<?> c) {
		int n = 0;
		for (final Object ob : al_displayables) {
			if (ob.getClass() == c) n++;
		}
		return n;
	}

	/** Checks if there are any Displayable or if any ZDisplayable paints in this layer. */
	public boolean isEmpty() {
		return 0 == al_displayables.size() && parent.isEmptyAt(this); // check for ZDisplayable painting here as well
	}

	/** Returns a copy of the list of Displayable objects.*/
	synchronized public ArrayList<Displayable> getDisplayables() {
		return new ArrayList<Displayable>(al_displayables);
	}

	/** Returns the real list of displayables, not a copy. If you modify this list, Thor may ground you with His lightning. */
	@Override
	public final ArrayList<Displayable> getDisplayableList() {
		return al_displayables;
	}

	synchronized public int getNDisplayables() {
		return al_displayables.size();
	}

	/** Returns a list of Displayable of class c only.*/
	synchronized public<T extends Displayable> ArrayList<T> getAll(final Class<T> c) {
		// So yes, it can be done to return a typed list of any kind: this WORKS:
		final ArrayList<T> al = new ArrayList<T>();
		if (null == c) return al;
		if (Displayable.class == c) {
			al.addAll((Collection<T>)al_displayables); // T is Displayable
			return al;
		}
		for (final Displayable d : al_displayables) {
			if (d.getClass() == c) al.add((T)d);
		}
		return al;
	}

	/** Returns a list of Displayable of class c only.*/
	synchronized public ArrayList<Displayable> getDisplayables(final Class<?> c) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (null == c) return al;
		if (Displayable.class == c) {
			al.addAll(al_displayables);
			return al;
		}
		for (final Displayable d : al_displayables) {
			if (d.getClass() == c) al.add(d);
		}
		return al;
	}

	synchronized public ArrayList<Displayable> getDisplayables(final Class<?> c, final boolean visible_only, final boolean instance_of) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (null == c) return al;
		if (instance_of) {
			for (final Displayable d : al_displayables) {
				if (visible_only && !d.isVisible()) continue;
				if (c.isAssignableFrom(d.getClass())) al.add(d);
			}
		} else {
			for (final Displayable d : al_displayables) {
				if (visible_only && !d.isVisible()) continue;
				if (d.getClass() == c) al.add(d);
			}
		}
		return al;
	}


	/** Returns a list of all Displayable of class c that intersect the given rectangle. */
	public Collection<Displayable> getDisplayables(final Class<?> c, final Rectangle roi) {
		return getDisplayables(c, new Area(roi), true, false);
	}

	/** Returns a list of all Displayable of class c that intersect the given area. */
	synchronized public Collection<Displayable> getDisplayables(final Class<?> c, final Area aroi, final boolean visible_only) {
		return getDisplayables(c, aroi, visible_only, false);
	}

	/** Check class identity by instanceof instead of equality. */
	synchronized public Collection<Displayable> getDisplayables(final Class<?> c, final Area aroi, final boolean visible_only, final boolean instance_of) {
		if (null != root) return root.find(c, aroi, this, visible_only, instance_of);
		// Else, the slow way
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (Displayable.class == c) {
			for (final Displayable d : al_displayables) {
				if (visible_only && !d.isVisible()) continue;
				final Area area = d.getArea();
				area.intersect(aroi);
				final Rectangle b = area.getBounds();
				if (!(0 == b.width || 0 == b.height)) al.add(d);
			}
			return al;
		}
		if (instance_of) {
			for (final Displayable d : al_displayables) {
				if (visible_only && !d.isVisible()) continue;
				if (c.isAssignableFrom(d.getClass())) {
					final Area area = d.getArea();
					area.intersect(aroi);
					final Rectangle b = area.getBounds();
					if (!(0 == b.width || 0 == b.height)) al.add(d);
				}
			}
		} else {
			for (final Displayable d : al_displayables) {
				if (visible_only && !d.isVisible()) continue;
				if (d.getClass() == c) {
					final Area area = d.getArea();
					area.intersect(aroi);
					final Rectangle b = area.getBounds();
					if (!(0 == b.width || 0 == b.height)) al.add(d);
				}
			}
		}
		return al;
	}

	/** Check class identity with equality, so no superclasses or interfaces are possible. */
	synchronized public ArrayList<Displayable> getDisplayables(final Class<?> c, final boolean visible_only) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final Displayable d : al_displayables) {
			if (d.getClass() == c) {
				if (visible_only && !d.isVisible()) continue;
				al.add(d);
			}
		}
		return al;
	}

	public Displayable get(final long id) {
		for (final Displayable d : al_displayables) {
			if (d.getId() == id) return d;
		}
		return null;
	}

	@Override
	public float getLayerWidth() {
		return parent.getLayerWidth();
	}
	@Override
	public float getLayerHeight() {
		return parent.getLayerHeight();
	}

	public Collection<Displayable> find(final double x, final double y) {
		return find(x, y, false);
	}

	/** Find the Displayable objects that contain the point. */
	synchronized public Collection<Displayable> find(final double x, final double y, final boolean visible_only) {
		if (null != root) return root.find(x, y, this, visible_only);
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (int i = al_displayables.size() -1; i>-1; i--) {
			final Displayable d = (Displayable)al_displayables.get(i);
			if (visible_only && !d.isVisible()) continue;
			if (d.contains(x, y)) {
				al.add(d);
			}
		}
		return al;
	}

	public Collection<Displayable> find(final Class<?> c, final double x, final double y) {
		return find(c, x, y, false, false);
	}

	/** Find the Displayable objects of Class c that contain the point, with class equality. */
	synchronized public Collection<Displayable> find(final Class<?> c, final double x, final double y, final boolean visible_only) {
		return find(c, x, y, visible_only, false);
	}
	/** Find the Displayable objects of Class c that contain the point, with instanceof if instance_of is true. */
	synchronized public Collection<Displayable> find(final Class<?> c, final double x, final double y, final boolean visible_only, final boolean instance_of) {		
		if (null != root) return root.find(c, x, y, this, visible_only, instance_of);
		if (Displayable.class == c) return find(x, y, visible_only); // search among all
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (int i = al_displayables.size() -1; i>-1; i--) {
			final Displayable d = al_displayables.get(i);
			if (visible_only && !d.isVisible()) continue;
			if (d.getClass() == c && d.contains(x, y)) {
				al.add(d);
			}
		}
		return al;
	}

	public Collection<Displayable> find(final Rectangle r) {
		return find(r, false);
	}

	/** Find the Displayable objects whose bounding box intersects with the given rectangle. */
	synchronized public Collection<Displayable> find(final Rectangle r, final boolean visible_only) {
		if (null != root && root.isBetter(r, this)) return root.find(r, this, visible_only);
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final Displayable d : al_displayables) {
			if (visible_only && !d.isVisible()) continue;
			if (d.getBoundingBox().intersects(r)) {
				al.add(d);
			}
		}
		return al;
	}
	
	synchronized public Collection<Displayable> find(final Class<?> c, final Rectangle r, final boolean visible_only) {
		return find(c, r, visible_only, false);
	}

	/** Find the Displayable objects whose bounding box intersects with the given rectangle. */
	synchronized public Collection<Displayable> find(final Class<?> c, final Rectangle r, final boolean visible_only, final boolean instance_of) {
		if (Displayable.class == c) return find(r, visible_only);
		if (null != root && root.isBetter(r, this)) return root.find(c, r, this, visible_only, instance_of);
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final Displayable d : al_displayables) {
			if (visible_only && !d.isVisible()) continue;
			if (d.getClass() != c) continue;
			if (d.getBoundingBox().intersects(r)) {
				al.add(d);
			}
		}
		return al;
	}

	/** Find the Displayable objects of class 'target' whose perimeter (not just the bounding box)
	 * intersect the given Displayable (which is itself included if present in this very Layer). */
	synchronized public <T extends Displayable> Collection<T> getIntersecting(final Displayable d, final Class<T> target) {
		if (null != root) {
			final Area area = new Area(d.getPerimeter());
			if (root.isBetter(area.getBounds(), this)) {
				return (Collection<T>) root.find(target, area, this, false, true);
			}
		}
		final ArrayList<T> al = new ArrayList<T>();
		for (int i = al_displayables.size() -1; i>-1; i--) {
			final Object ob = al_displayables.get(i);
			if (target.isAssignableFrom(ob.getClass())) continue;
			final Displayable da = (Displayable)ob;
			if (d.intersects(da)) {
				al.add((T)da);
			}
		}
		return al;
	}

	/** Returns -1 if not found. */
	public final int indexOf(final Displayable d) {
		return al_displayables.indexOf(d);
	}

	/** Within its own class only.
	 * 'up' is at the last element of the ArrayList (since when painting, the first one gets painted first, and thus gets buried the most while the last paints last, on top). */
	public void moveUp(final Displayable d) {
		final int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || al_displayables.size() -1 == i) return;
		if (al_displayables.get(i+1).getClass() == d.getClass()) {
			//swap
			al_displayables.remove(d);
			al_displayables.add(i+1, d);
		} else return;
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
		if (null != root) root.updateRange(this, d, i, i+1);
	}

	/** Within its own class only. */
	public void moveDown(final Displayable d) {
		final int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || 0 == i) return;
		if (al_displayables.get(i-1).getClass() == d.getClass()) {
			//swap
			final Displayable o = al_displayables.remove(i-1);
			al_displayables.add(i, o);
		} else return;
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
		if (null != root) root.updateRange(this, d, i-1, i);
	}

	/** Within its own class only. */
	public void moveTop(final Displayable d) { // yes I could have made several lists and make my life easier. Whatever
		final int i = al_displayables.indexOf(d);
		final int size = al_displayables.size();
		if (null == d || -1 == i || size -1 == i) return;
		final Class<?> c = d.getClass();
		boolean done = false;
		int j = i + 1;
		for (; j<size; j++) {
			if (al_displayables.get(j).getClass() == c) continue;
			else {
				al_displayables.remove(d);
				al_displayables.add(--j, d); // j-1
				done = true;
				break;
			}
		}
		// solves case of no other class present
		if (!done) {
			//add at the end
			al_displayables.remove(d);
			al_displayables.add(d);
			j = size-1;
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
		if (null != root) root.updateRange(this, d, i, j);
	}

	/** Within its own class only. */
	public void moveBottom(final Displayable d) {
		final int i = al_displayables.indexOf(d);
		if (null == d || -1 == i || 0 == i) return;
		final Class<?> c = d.getClass();
		boolean done = false;
		int j = i - 1;
		for (; j > -1; j--) {
			if (al_displayables.get(j).getClass() == c) continue;
			else {
				al_displayables.remove(d);
				al_displayables.add(++j, d); // j+1
				done = true;
				break;
			}
		}
		// solve case of no other class present
		if (!done) {
			al_displayables.remove(d);
			al_displayables.add(0, d);
			j = 0;
		}
		updateInDatabase("stack_index");
		Display.updatePanelIndex(d.getLayer(), d);
		if (null != root) root.updateRange(this, d, j, i);
	}

	/** Within its own class only. */
	public boolean isTop(final Displayable d) {
		final int i = al_displayables.indexOf(d);
		final int size = al_displayables.size();
		if (size -1 == i) return true;
		if (al_displayables.get(i+1).getClass() == d.getClass()) return false;
		return true;
	} // these two methods will throw an Exception if the Displayable is not found (-1 == i) (the null.getClass() *should* throw it)
	/** Within its own class only. */
	public boolean isBottom(final Displayable d) {
		final int i = al_displayables.indexOf(d);
		if (0 == i) return true;
		if (al_displayables.get(i-1).getClass() == d.getClass()) return false;
		return true;
	}

	/** Get the index of the given Displayable relative to the rest of its class. Beware that the order of the al_displayables is bottom at zero, top at last, but the relative index returned here is inverted: top at zero, bottom at last -to match the tabs' vertical orientation in a Display.*/
	public int relativeIndexOf(final Displayable d) {
		final int k = al_displayables.indexOf(d);
		if (-1 == k) return -1;
		final Class<?> c = d.getClass();
		final int size = al_displayables.size();
		int i = k+1;
		for (; i<size; i++) {
			if (al_displayables.get(i).getClass() == c) continue;
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

	/** Note: Not recursive into embedded LayerSet objects. Returns the hash set of objects whose visibility has changed. */
	public HashSet<Displayable> setVisible(String type, final boolean visible, final boolean repaint) {
		type = type.toLowerCase();
		if (type.equals("image")) type = "patch";
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		for (final Displayable d : al_displayables) {
			if (visible != d.isVisible() && d.getClass().getName().toLowerCase().endsWith(type)) {
				d.setVisible(visible, false); // don't repaint
				hs.add(d);
			}
		}
		if (repaint) {
			Display.repaint(this);
		}
		Display.updateCheckboxes(hs, DisplayablePanel.VISIBILITY_STATE, visible);
		return hs;
	}
	/** Returns the collection of Displayable whose visibility state has changed. */
	public Collection<Displayable> setAllVisible(final boolean repaint) {
		final Collection<Displayable> col = new ArrayList<Displayable>();
		for (final Displayable d : al_displayables) {
			if (!d.isVisible()) {
				d.setVisible(true, repaint);
				col.add(d);
			}
		}
		return col;
	}

	/** Hide all except those whose type is in 'type' list, whose visibility flag is left unchanged. Returns the list of displayables made hidden. */
	public HashSet<Displayable> hideExcept(final ArrayList<Class<?>> type, final boolean repaint) {
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		for (final Displayable d : al_displayables) {
			if (!type.contains(d.getClass()) && d.isVisible()) {
				d.setVisible(false, repaint);
				hs.add(d);
			}
		}
		return hs;
	}

	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		final String in = indent + "\t";
		// 1 - open tag
		sb_body.append(indent).append("<t2_layer oid=\"").append(id).append("\"\n")
		       .append(in).append(" thickness=\"").append(thickness).append("\"\n")
		       .append(in).append(" z=\"").append(z).append("\"\n")
		;
		// TODO this search is linear!
		final LayerThing lt = project.findLayerThing(this);
		String title;
		if (null == lt) title = null;
		else title = lt.getTitle();
		if (null == title) title = "";
		sb_body.append(in).append(" title=\"").append(title).append("\"\n"); // TODO 'title' should be a property of the Layer, not the LayerThing. Also, the LayerThing should not exist: LayerSet and Layer should be directly presentable in a tree. They are not Things as in "objects of the sample", but rather, structural necessities such as Patch.
		sb_body.append(indent).append(">\n");
		// 2 - export children
		if (null != al_displayables) {
			for (final Displayable d : al_displayables) {
				d.exportXML(sb_body, in, options);
			}
		}
		// 3 - close tag
		sb_body.append(indent).append("</t2_layer>\n");
	}

	/** Includes all Displayable objects in the list of possible children. */
	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_layer";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_layer (t2_patch,t2_label,t2_layer_set,t2_profile)>\n")
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" oid").append(Displayable.TAG_ATTR2)
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" thickness").append(Displayable.TAG_ATTR2)
			 .append(indent).append(Displayable.TAG_ATTR1).append(type).append(" z").append(Displayable.TAG_ATTR2)
		;
	}

	protected String getLayerThingTitle() {
		final LayerThing lt = project.findLayerThing(this);
		if (null == lt || null == lt.getTitle() || 0 == lt.getTitle().trim().length()) return "";
		return lt.getTitle();
	}
	
	@Override
	public String getTitle() {
		final LayerThing lt = project.findLayerThing(this);
		if (null == lt || null == lt.getTitle() || 0 == lt.getTitle().trim().length()) return this.toString();
		return lt.getTitle();
	}

	public void destroy() {
		for (final Displayable d : al_displayables) {
			d.destroy();
		}
	}

	/** Returns null if no Displayable objects of class c exist. */
	public Rectangle getMinimalBoundingBox(final Class<?> c) {
		return getMinimalBoundingBox(c, true);
	}

	/** Returns null if no Displayable objects of class c exist (or are visible if {@param visible_only} is true). */
	public Rectangle getMinimalBoundingBox(final Class<?> c, final boolean visible_only) {
		Rectangle box = null;
		Rectangle tmp = new Rectangle();
		for (final Displayable d : getDisplayables(c, visible_only)) {
			tmp = d.getBoundingBox(tmp);
			if (null == box) {
				box = (Rectangle)tmp.clone();
				continue;
			}
			box.add(tmp);
		}
		return box;
	}

	/** Returns an Area in world coordinates that represents the inside of all Patches. */
	public Area getPatchArea(final boolean visible_only) {
		final Area area = new Area(); // with width,height zero
		for (final Patch p: getAll(Patch.class)) {
			if (visible_only && p.isVisible()) {
				area.add(p.getArea());
			}
		}
		return area;
	}

	/** Preconcatenate the given AffineTransform to all Displayable objects of class c, without respecting their links. */
	public void apply(final Class<?> c, final AffineTransform at) {
		final boolean all = Displayable.class == c;
		for (final Displayable d : al_displayables) {
			if (all || d.getClass() == c) {
				d.at.preConcatenate(at);
			}
		}
		recreateBuckets();
	}

	/** Make a copy of this layer into the given LayerSet, enclosing only Displayable objects within the roi, and translating them for that roi x,y. */
	public Layer clone(final Project pr, final LayerSet ls, final Rectangle roi, final boolean copy_id, final boolean ignore_hidden_patches) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Layer copy = new Layer(pr, nid, z, thickness);
		copy.parent = ls;
		for (final Displayable d : find(roi)) {
			if (ignore_hidden_patches && !d.isVisible() && d.getClass() == Patch.class) continue;
			copy.addSilently(d.clone(pr, copy_id));
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
	public Object grab(final Rectangle r, final double scale, final Class<?> c, final int c_alphas, final int format, final int type) {
		//Ensure some memory is free
		project.getLoader().releaseToFit(r.width, r.height, type, 1.1f);
		if (IMAGE == format) {
			return project.getLoader().getFlatAWTImage(this, r, scale, c_alphas, type, c, null, true, Color.black);
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
		for (final Displayable d : al_displayables) {
			if (d.getId() == id) return d;
		}
		return null;
	}

	// private to the package
	void linkPatchesR() {
		for (final Displayable d : al_displayables) {
			if (d.getClass() == LayerSet.class) ((LayerSet)d).linkPatchesR();
			d.linkPatches(); // Patch.class does nothing
		}
	}

	/** Recursive into nested LayerSet objects.*/
	public void updateLayerTree() {
		project.getLayerTree().addLayer(parent, this);
		for (final Displayable d : getDisplayables(LayerSet.class)) {
			((LayerSet)d).updateLayerTree();
		}
	}

	/** Don't use this for fast pixel grabbing; this is intended for the dropper tool and status bar reporting by mouse motion. */
	public int[] getPixel(final int x, final int y, final double mag) {
		// find Patch under cursor
		final Collection<Displayable> under = find(Patch.class, x, y);
		if (null == under || under.isEmpty()) return new int[3]; // zeros
		final Patch pa = (Patch)under.iterator().next();// get(0) // the top one, since they are ordered like html divs
		// TODO: edit here when adding layer mipmaps
		return pa.getPixel(x, y, mag);
	}

	synchronized public void recreateBuckets() {
		this.root = new Bucket(0, 0, (int)(0.00005 + getLayerWidth()), (int)(0.00005 + getLayerHeight()), Bucket.getBucketSide(this, this));
		this.db_map = new HashMap<Displayable,HashSet<Bucket>>();
		this.root.populate(this, this, db_map);
		//root.debug();
	}

	/** Update buckets of a position change for the given Displayable. */
	@Override
	public void updateBucket(final Displayable d, final Layer layer) { // ignore layer
		if (null != root) root.updatePosition(d, this, db_map);
	}

	public void checkBuckets() {
		if (use_buckets && (null == root || null == db_map)) recreateBuckets();
	}

	private boolean use_buckets = true;

	public void setBucketsEnabled(final boolean b) {
		this.use_buckets = b;
		if (!use_buckets) this.root = null;
	}

	static class DoEditLayer implements DoStep {
		final double z, thickness;
		final Layer la;
		DoEditLayer(final Layer layer) {
			this.la = layer;
			this.z = layer.z;
			this.thickness = layer.thickness;
		}
		@Override
		public Displayable getD() { return null; }
		@Override
		public boolean isEmpty() { return false; }
		@Override
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof Layer)) return false;
			final Layer layer = (Layer) ob;
			return this.la.id == layer.id && this.z == layer.z && this.thickness == layer.thickness;
		}
		@Override
		public boolean apply(final int action) {
			la.z = this.z;
			la.thickness = this.thickness;
			la.getProject().getLayerTree().updateUILater();
			Display.update(la.getParent());
			return true;
		}
	}

	static class DoEditLayers implements DoStep {
		final ArrayList<DoEditLayer> all = new ArrayList<DoEditLayer>();
		DoEditLayers(final List<Layer> all) {
			for (final Layer la : all) {
				this.all.add(new DoEditLayer(la));
			}
		}
		@Override
		public Displayable getD() { return null; }
		@Override
		public boolean isEmpty() { return all.isEmpty(); }
		@Override
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof DoEditLayers)) return false;
			final DoEditLayers other = (DoEditLayers) ob;
			if (all.size() != other.all.size()) return false;
			// Order matters:
			final Iterator<DoEditLayer> it1 = all.iterator();
			final Iterator<DoEditLayer> it2 = other.all.iterator();
			for (; it1.hasNext() && it2.hasNext(); ) {
				if (!it1.next().isIdenticalTo(it2.next())) return false;
			}
			return true;
		}
		@Override
		public boolean apply(final int action) {
			boolean failed = false;
			for (final DoEditLayer one : all) {
				if (!one.apply(action)) {
					failed = true;
				}
			}
			return !failed;
		}
	}

	/** Add an object that is Layer bound, such as Profile, Patch, DLabel and LayerSet. */
	static class DoContentChange implements DoStep {
		final Layer la;
		final ArrayList<Displayable> al;
		DoContentChange(final Layer la) {
			this.la = la;
			this.al = la.getDisplayables(); // a copy
		}
		@Override
		public Displayable getD() { return null; }
		@Override
		public boolean isEmpty() { return false; }
		/** Check that the Displayable objects of this layer are the same and in the same quantity and order. */
		@Override
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof DoContentChange)) return false;
			final DoContentChange dad = (DoContentChange) ob;
			if (la != dad.la || al.size() != dad.al.size()) return false;
			// Order matters:
			final Iterator<Displayable> it1 = al.iterator();
			final Iterator<Displayable> it2 = dad.al.iterator();
			for (; it1.hasNext() && it2.hasNext(); ) {
				if (it1.next() != it2.next()) return false;
			}
			return true;
		}
		@Override
		public boolean apply(final int action) {
			// find the subset in la.al_displayables that is not in this.al
			final HashSet<Displayable> sub1 = new HashSet<Displayable>(la.al_displayables);
			sub1.removeAll(this.al);
			// find the subset in this.al that is not in la.al_displayables
			final HashSet<Displayable> sub2 = new HashSet<Displayable>(this.al);
			sub2.removeAll(la.al_displayables);

			HashSet<Displayable> subA=null, subB=null;

			if (action == DoStep.UNDO) {
				subA = sub1;
				subB = sub2;
			} else if (action == DoStep.REDO) {
				subA = sub2;
				subB = sub1;
			}
			if (null != subA && null != subB) {
				// Mark Patch for mipmap file removal
				for (final Displayable d: subA) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().queueForMipmapRemoval((Patch)d, true);
					}
				}
				// ... or unmark:
				for (final Displayable d: subB) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().queueForMipmapRemoval((Patch)d, false);
					}
				}
			}

			la.al_displayables.clear();
			la.al_displayables.addAll(this.al);
			la.recreateBuckets();
			Display.updateVisibleTabs();
			Display.clearSelection();
			Display.update(la);
			return true;
		}
	}
	
	static protected class DoMoveDisplayable implements DoStep {
		final ArrayList<Displayable> al_displayables;
		final Layer layer;
		HashSet<DoStep> dependents = null;
		DoMoveDisplayable(final Layer layer) {
			this.layer = layer;
			this.al_displayables = new ArrayList<Displayable>(layer.al_displayables);
		}
		@Override
		public boolean apply(final int action) {
			// Replace all ZDisplayable
			layer.al_displayables.clear();
			layer.al_displayables.addAll(this.al_displayables);
			Display.update(layer);
			return true;
		}
		@Override
		public boolean isEmpty() {
			return false;
		}
		@Override
		public Displayable getD() {
			return null;
		}
		@Override
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof DoMoveDisplayable)) return false;
			final DoMoveDisplayable dm = (DoMoveDisplayable)ob;
			if (dm.layer != this.layer) return false;
			if (dm.al_displayables.size() != this.al_displayables.size()) return false;
			for (int i=0; i<this.al_displayables.size(); ++i) {
				if (dm.al_displayables.get(i) != this.al_displayables.get(i)) return false;
			}
			return true;
		}
	}
	
	

	private Overlay overlay = null;

	/** Return the current Overlay or a new one if none yet. */
	synchronized public Overlay getOverlay() {
		if (null == overlay) overlay = new Overlay();
		return overlay;
	}
	// Used by DisplayCanvas to paint
	Overlay getOverlay2() {
		return overlay;
	}
	/** Set to null to remove the Overlay.
	 *  @return the previous Overlay, if any. */
	synchronized public Overlay setOverlay(final Overlay o) {
		final Overlay old = this.overlay;
		this.overlay = o;
		return old;
	}

	@Override
	public int compareTo(final Layer layer) {
		final double diff = this.z - layer.z;
		if (diff < 0) return -1;
		if (diff > 0) return 1;
		return 0;
	}

	/** Transfer the world coordinate specified by {@param world_x},{@param world_y}
	 * in pixels, to the local coordinate of the {@link Patch} immediately present under it.
	 * @return null if no {@link Patch} is under the coordinate, else the {@link Coordinate} with the x, y, {@link Layer} and the {@link Patch}.
	 * @throws NoninvertibleModelException 
	 * @throws NoninvertibleTransformException 
	 */
	public Coordinate<Patch> toPatchCoordinate(final double world_x, final double world_y) throws NoninvertibleTransformException, NoninvertibleModelException {
		final Collection<Displayable> ps = find(Patch.class, world_x, world_y, true, false);
		Patch patch = null;
		if (ps.isEmpty()) {
			// No Patch under the point. Find the nearest Patch instead
			final Collection<Patch> patches = getAll(Patch.class);
			if (patches.isEmpty()) return null;
			double minSqDist = Double.MAX_VALUE;
			for (final Patch p : patches) {
				// Check if any of the 4 corners of the bounding box are beyond minSqDist
				final Rectangle b = p.getBoundingBox();
				final double d1 = Math.pow(b.x - world_x, 2) + Math.pow(b.y - world_y, 2),
				       d2 = Math.pow(b.x + b.width - world_x, 2) + Math.pow(b.y - world_y, 2),
				       d3 = Math.pow(b.x - world_x, 2) + Math.pow(b.y + b.height - world_y, 2),
				       d4 = Math.pow(b.x + b.width - world_x, 2) + Math.pow(b.y + b.height - world_y, 2),
				       d = Math.min(d1, Math.min(d2, Math.min(d3, d4)));
				if (d < minSqDist) {
					patch = p;
					minSqDist = d;
				}
				// If the Patch has a CoordinateTransform, find the closest perimeter point
				if (p.hasCoordinateTransform()) {
					for (final Polygon pol : M.getPolygons(p.getArea())) { // Area in world coordinates
						for (int i=0; i<pol.npoints; ++i) {
							final double sqDist = Math.pow(pol.xpoints[0] - world_x, 2) + Math.pow(pol.ypoints[1] - world_y, 2);
							if (sqDist < minSqDist) {
								minSqDist = sqDist;
								patch = p;
							}
						}
					}
				}
			}
		} else {
			patch = (Patch) ps.iterator().next();
		}

		final double[] point = patch.toPixelCoordinate(world_x, world_y);
		return new Coordinate<Patch>(point[0], point[1], patch.getLayer(), patch);
	}
}
