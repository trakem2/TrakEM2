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
import ij.measure.Calibration;
import ij.ImagePlus;
import ij.ImageStack;

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.imaging.LayerStack;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;


/** A LayerSet represents an axis on which layers can be stacked up. Paints with 0.67 alpha transparency when not active. */
public class LayerSet extends Displayable { // Displayable is already extending DBObject

	// the anchors for resizing
	static public final int NORTH = 0;
	static public final int NORTHEAST = 1;
	static public final int EAST = 2;
	static public final int SOUTHEAST = 3;
	static public final int SOUTH = 4;
	static public final int SOUTHWEST = 5;
	static public final int WEST = 6;
	static public final int NORTHWEST = 7;
	static public final int CENTER = 8;

	// the possible rotations
	static public final int R90 = 9;
	static public final int R270 = 10;
	// the posible flips
	static public final int FLIP_HORIZONTAL = 11;
	static public final int FLIP_VERTICAL = 12;

	// postions in the stack
	static public final int TOP = 13;
	static public final int UP = 14;
	static public final int DOWN = 15;
	static public final int BOTTOM = 16;


	static public final String[] ANCHORS =  new String[]{"north", "north east", "east", "southeast", "south", "south west", "west", "north west", "center"};
	static public final String[] ROTATIONS = new String[]{"90 right", "90 left", "Flip horizontally", "Flip vertically"};

	private double layer_width; // the Displayable.width is for the representation, not for the dimensions of the LayerSet!
	private double layer_height;
	private double rot_x;
	private double rot_y;
	private double rot_z; // should be equivalent to the Displayable.rot
	private final ArrayList<Layer> al_layers = new ArrayList<Layer>();
	/** The layer in which this LayerSet lives. If null, this is the root LayerSet. */
	private Layer parent = null;
	/** A LayerSet can contain Displayables that are show in every single Layer, such as Pipe objects. */
	private ArrayList<ZDisplayable> al_zdispl = new ArrayList<ZDisplayable>();

	private boolean snapshots_enabled = true;

	/** For creating snapshots. */
	private boolean snapshots_quality = true;

	/** Store Hashtables of displayable/transformation pairs for undo. */
	private LinkedList undo_queue = new LinkedList();
	/** Store Hashtables of displayable/transformation pairs for redo, as they are popped out of the undo_queue list. This list will be cleared the moment a new action is stored in the undo_queue.*/
	//private LinkedList redo_queue = new LinkedList();
	/** The index of the current set of Transformations in the undo/redo queues. */
	private int current = 0;
	/** A flag to indicate that the user is undoing/redoing without adding new undo steps. Gets reset to false when a new undo step is added. */
	private boolean cycle_flag = false;
	private int MAX_UNDO_STEPS = 40; // should be editable, or rather, adaptable: count not the max steps but the max amount of memory used, computed by counting the number of AffineTransforms stored and the size of a single AffineTransform
	/** Tool to manually register using landmarks across two layers. Uses the toolbar's 'Align tool'. */
	private Align align = null;

	/** The scaling applied to the Layers when painting them for presentation as a LayerStack. If -1, automatic mode (default) */
	private double virtual_scale = -1;
	/** The maximum size of either width or height when virtuzaling pixel access to the layers.*/
	private int max_dimension = 1024;
	private boolean virtualization_enabled = false;

	private Calibration calibration = new Calibration(); // default values

	/** Dummy. */
	protected LayerSet(Project project, long id) {
		super(project, id, null, false, null, 20, 20);
	}

	/** Create a new LayerSet with a 0,0,0 rotation vector and default 20,20 px Displayable width,height. */
	public LayerSet(Project project, String title, double x, double y, Layer parent, double layer_width, double layer_height) {
		super(project, title, x, y);
		rot_x = rot_y = rot_z = 0.0D;
		this.width = 20;
		this.height = 20; // for the label that paints into the parent Layer
		this.parent = parent;
		this.layer_width = layer_width;
		this.layer_height = layer_height;
		addToDatabase();
	}

	/** Reconstruct from the database. */
	public LayerSet(Project project, long id, String title, double width, double height, double rot_x, double rot_y, double rot_z, double layer_width, double layer_height, boolean locked, boolean snapshots_enabled, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.rot_x = rot_x;
		this.rot_y = rot_y;
		this.rot_z = rot_z;
		this.layer_width = layer_width;
		this.layer_height= layer_height;
		this.snapshots_enabled = snapshots_enabled;
		// the parent will be set by the LayerThing.setup() calling Layer.addSilently()
		// the al_layers will be filled idem.
	}

	/** Reconstruct from an XML entry. */
	public LayerSet(Project project, long id, Hashtable ht_attributes, Hashtable ht_links) {
		super(project, id, ht_attributes, ht_links);
		for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht_attributes.get(key);
			if (key.equals("layer_width")) {
				this.layer_width = Double.parseDouble(data);
			} else if (key.equals("layer_height")) {
				this.layer_height = Double.parseDouble(data);
			} else if (key.equals("rot_x")) {
				this.rot_x = Double.parseDouble(data);
			} else if (key.equals("rot_y")) {
				this.rot_y = Double.parseDouble(data);
			} else if (key.equals("rot_z")) {
				this.rot_z = Double.parseDouble(data);
			} else if (key.equals("snapshots_quality")) {
				snapshots_quality = Boolean.valueOf(data.trim().toLowerCase());
			} else if (key.equals("snapshots_enabled")) {
				snapshots_enabled = Boolean.valueOf(data.trim().toLowerCase());
			}
			// the above would be trivial in Jython, and can be done by reflection! The problem would be in the parsing, that would need yet another if/else if/ sequence was any field to change or be added.
		}
	}

	/** For reconstruction purposes: set the active layer to the ZDisplayable objects. Recurses through LayerSets in the children layers. */
	synchronized public void setup() {
		final Layer la0 = al_layers.get(0);
		for (ZDisplayable zd : al_zdispl) zd.setLayer(la0); // just any Layer
		for (Layer layer : al_layers) {
			for (Iterator itl = layer.getDisplayables().iterator(); itl.hasNext(); ) {
				Object ob = itl.next();
				if (ob instanceof LayerSet) {
					((LayerSet)ob).setup();
				}
			}
		}
	}

	/** Create a new LayerSet in the middle of the parent Layer. */
	public LayerSet create(Layer parent_layer) {
		if (null == parent_layer) return null;
		GenericDialog gd = ControlWindow.makeGenericDialog("New Layer Set");
		gd.addMessage("In pixels:");
		gd.addNumericField("width: ", this.layer_width, 3);
		gd.addNumericField("height: ", this.layer_height, 3);
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		try {
			double width = gd.getNextNumber();
			double height = gd.getNextNumber();
			if (Double.isNaN(width) || Double.isNaN(height)) return null;
			if (0 == width || 0 == height) {
				Utils.showMessage("Cannot accept zero width or height for LayerSet dimensions.");
				return null;
			}
			// make a new LayerSet with x,y in the middle of the parent_layer
			return new LayerSet(project, "Layer Set", parent_layer.getParent().getLayerWidth() / 2, parent_layer.getParent().getLayerHeight() / 2, parent_layer, width/2, height/2);
		} catch (Exception e) { Utils.log("LayerSet.create: " + e); }
		return null;
	}

	/** Add a new Layer silently, ordering by z as well.*/
	synchronized public void addSilently(final Layer layer) {
		if (null == layer || al_layers.contains(layer)) return;
		try {
			double z = layer.getZ();
			int i = 0;
			for (Layer la : al_layers) {
				if (! (la.getZ() < z) ) {
					al_layers.add(i, layer);
					layer.setParentSilently(this);
					return;
				}
				i++;
			}
			// else, add at the end
			al_layers.add(layer);
			layer.setParentSilently(this);
		} catch (Exception e) {
			Utils.log("LayerSet.addSilently: Not a Layer, not adding DBObject id=" + layer.getId());
			return;
		}
	}

	/** Add a new Layer, inserted according to its Z. */
	synchronized public void add(final Layer layer) {
		if (-1 != al_layers.indexOf(layer)) return;
		final double z = layer.getZ();
		final int n = al_layers.size();
		int i = 0;
		for (; i<n; i++) {
			Layer l = (Layer)al_layers.get(i);
			if (l.getZ() < z) continue;
			break;
		}
		if (i < n) {
			al_layers.add(i, layer);
		} else {
			al_layers.add(layer);
		}
		layer.setParent(this);
		Display.updateLayerScroller(this);
		//debug();
	}

	synchronized private void debug() {
		Utils.log("LayerSet debug:");
		for (int i=0; i<al_layers.size(); i++)
			Utils.log(i + " : " + ((Layer)al_layers.get(i)).getZ());
	}

	public Layer getParent() {
		return parent;
	}

	/** 'update' in database or not. */
	public void setLayer(Layer layer, boolean update) {
		super.setLayer(layer, update);
		if (null != layer) this.parent = layer; // repeated pointer, eliminate 'parent' !
	}

	public void setParent(Layer layer) {
		if (null == layer || layer == parent) return;
		this.parent = layer;
		updateInDatabase("parent_id");
	}

	synchronized public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		if (ProjectToolbar.SELECT != ProjectToolbar.getToolId()) return;
		Display.setActive(me, this);
		if (2 == me.getClickCount() && al_layers.size() > 0) {
			new Display(project, al_layers.get(0));
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
		if (ProjectToolbar.SELECT != ProjectToolbar.getToolId()) return;
		super.translate(x_d - x_d_old, y_d - y_d_old);
		Display.repaint(layer, this, 0);
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		// nothing
	}

	public void keyPressed(KeyEvent ke) {
		Utils.log("LayerSet.keyPressed: not yet implemented.");
		// TODO
	}

	public String toString() {
		return this.title;
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		//set color
		g.setColor(this.color);
		// fill a background box
		g.fillRect(0, 0, (int)(this.width), (int)(this.height));
		g.setColor(new Color(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue()).brighter()); // the "opposite", but brighter, so it won't fail to generate contrast if the color is 127 in all channels
		int x = (int)(this.width/5);
		int y = (int)(this.height/5);
		int width = (int)(this.width/5);
		int height = (int)(this.height/5 * 3);

		g.fillRect(x, y, width, height);

		x = (int)(this.width/5 * 2);
		y = (int)(this.height/5 * 3);
		width = (int)(this.width/5 * 2);
		height = (int)(this.height/5);

		g.fillRect(x, y, width, height);

		//Transparency: fix composite back to original.
		if (alpha != 1.0f) {
			g.setComposite(original_composite);
		}
	}

	public double getLayerWidth() { return layer_width; }
	public double getLayerHeight() { return layer_height; }
	public double getRotX() { return rot_x; }
	public double getRotY() { return rot_y; }
	public double getRotZ() { return rot_z; }

	synchronized public int size() {
		return al_layers.size();
	}

	public void setRotVector(double rot_x, double rot_y, double rot_z) {
		if (Double.isNaN(rot_x) || Double.isNaN(rot_y) || Double.isNaN(rot_z)) {
			Utils.showMessage("LayerSet: Rotation vector contains NaNs. Not updating.");
			return;
		} else if (rot_x == this.rot_x && rot_y == this.rot_y && rot_z == this.rot_z) {
			return;
		}
		this.rot_x = rot_x;
		this.rot_y = rot_y;
		this.rot_z = rot_z;
		updateInDatabase("rot");
	}

	/** Used by the Loader after loading blindly a lot of Patches. Will crop the canvas to the minimum size possible. */
	synchronized public boolean setMinimumDimensions() {
		// find current x,y,width,height that crops the canvas without cropping away any Displayable
		double x = Double.NaN;
		double y = Double.NaN;
		double xe = 0; // lower right corner (x end)
		double ye = 0;
		double tx = 0;
		double ty = 0;
		double txe = 0;
		double tye = 0;
		// collect all Displayable and ZDisplayable objects
		final ArrayList al = new ArrayList();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(((Layer)al_layers.get(i)).getDisplayables());
		}
		al.addAll(al_zdispl);

		// find minimum bounding box
		Rectangle b = new Rectangle();
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			b = d.getBoundingBox(b); // considers rotation
			tx = b.x;//d.getX();
			ty = b.y;//d.getY();
			// set first coordinates
			if (Double.isNaN(x) || Double.isNaN(y)) { // Double.NaN == x fails!
				x = tx;
				y = ty;
			}
			txe = tx + b.width;//d.getWidth();
			tye = ty + b.height;//d.getHeight();
			if (tx < x) x = tx;
			if (ty < y) y = ty;
			if (txe > xe) xe = txe;
			if (tye > ye) ye = tye;
		}
		// if none, then stop
		if (Double.isNaN(x) || Double.isNaN(y)) {
			Utils.showMessage("No displayable objects, don't know how to resize the canvas and Layerset.");
			return false;
		}

		// translate
		if (0 != x || 0 != y) {
			project.getLoader().startLargeUpdate();
			try {
				final AffineTransform at2 = new AffineTransform();
				at2.translate(-x, -y);
				//Utils.log2("translating all displayables by " + x + "," + y);
				for (Iterator it = al.iterator(); it.hasNext(); ) {
					//((Displayable)it.next()).translate(-x, -y, false); // drag regardless of getting off current LayerSet bounds
					// optimized to avoid creating so many AffineTransform instances:
					final Displayable d = (Displayable)it.next();
					//Utils.log2("BEFORE: " + d.getBoundingBox());
					d.getAffineTransform().preConcatenate(at2);
					//Utils.log2("AFTER: " + d.getBoundingBox());
					d.updateInDatabase("transform");
				}
				// translate all undo steps as well TODO need a better undo system, to call 'undo resize layerset', a system of undo actions or something
				for (Iterator it = undo_queue.iterator(); it.hasNext(); ) {
					Hashtable ht = (Hashtable)it.next();
					for (Iterator hi = ht.values().iterator(); hi.hasNext(); ) {
						AffineTransform at = (AffineTransform)hi.next();
						at.preConcatenate(at2);
					}
				}
				project.getLoader().commitLargeUpdate();
			} catch (Exception e) {
				new IJError(e);
				project.getLoader().rollback();
				return false; //TODO no notice to the user ...
			}
		}

		//Utils.log("x,y  xe,ye : " + x + "," + y + "  " + xe + "," + ye);
		// finally, accept:
		double w = xe - x;
		double h = ye - y;
		if (w < 0 || h < 0) {
			Utils.log("LayerSet.setMinimumDimensions: zero width or height, NOT resizing.");
			return false;
		}
		if (w != layer_width || h != layer_height) {
			this.layer_width = Math.ceil(w); // stupid int to double conversions ... why floating point math is a non-solved problem? Well, it is for SBCL
			this.layer_height = Math.ceil(h);
			updateInDatabase("layer_dimensions");
			// and notify the Displays, if any
			Display.update(this);
		}
		return true;
	}

	/** Enlarges the display in the given direction, */
	public boolean enlargeToFit(final Displayable d, final int anchor) {
		// check if necessary
		final Rectangle b = d.getBoundingBox(null);
		if (b.x + b.width < layer_width && b.y + b.height < layer_height) {
			return true;
		}
		// else, enlarge to fit it
		final Rectangle r = new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
		r.add(b);
		return setDimensions(r.width, r.height, anchor);
	}

	/** Returns false if any Displayables are being partially or totally cropped away. */
	synchronized public boolean setDimensions(double layer_width, double layer_height, int anchor) {
		// check preconditions
		if (Double.isNaN(layer_width) || Double.isNaN(layer_height)) { Utils.log("LayerSet.setDimensions: NaNs! Not adjusting."); return false; }
		if (layer_width <=0 || layer_height <= 0) { Utils.showMessage("LayerSet: can't accept zero or a minus for layer width or height"); return false; }
		if (anchor < NORTH || anchor > CENTER) {  Utils.log("LayerSet: wrong anchor, not resizing."); return false; }
		// new coordinates:
		double new_x = 0;// the x,y of the old 0,0
		double new_y = 0;
		switch (anchor) {
			case NORTH:
			case SOUTH:
			case CENTER:
				new_x = (layer_width - this.layer_width) / 2; // (this.layer_width - layer_width) / 2;
				break;
			case NORTHWEST:
			case WEST:
			case SOUTHWEST:
				new_x = 0;
				break;
			case NORTHEAST:
			case EAST:
			case SOUTHEAST:
				new_x = layer_width - this.layer_width; // (this.layer_width - layer_width);
				break;
		}
		switch (anchor) {
			case WEST:
			case EAST:
			case CENTER:
				new_y = (layer_height - this.layer_height) / 2;
				break;
			case NORTHWEST:
			case NORTH:
			case NORTHEAST:
				new_y = 0;
				break;
			case SOUTHWEST:
			case SOUTH:
			case SOUTHEAST:
				new_y = (layer_height - this.layer_height);
				break;
		}

		/*
		Utils.log("anchor: " + anchor);
		Utils.log("LayerSet: existing w,h = " + this.layer_width + "," + this.layer_height);
		Utils.log("LayerSet: new      w,h = " + layer_width + "," + layer_height);
		*/

		// collect all Displayable and ZDisplayable objects
		ArrayList al = new ArrayList();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(((Layer)al_layers.get(i)).getDisplayables());
		}
		al.addAll(al_zdispl);

		// check that no displayables are being cropped away
		if (layer_width < this.layer_width || layer_height < this.layer_height) {
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				Rectangle b = d.getBoundingBox(null);
				double dw = b.getWidth();
				double dh = b.getHeight();
				// respect 10% margins
				if (b.x + dw + new_x < 0.1 * dw || b.x + 0.9 * dw + new_x > layer_width || b.y + dh + new_y < 0.1 * dh || b.y + 0.9 * dh + new_y > layer_height) {
					// cropping!
					Utils.showMessage("Cropping " + d + "\nLayerSet: not resizing.");
					return false;
				}
			}
		}
		this.layer_width = layer_width;
		this.layer_height = layer_height;
		//Utils.log("LayerSet.setDimensions: new_x,y: " + new_x + "," + new_y);
		// translate all displayables
		if (0 != new_x || 0 != new_y) {
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				Rectangle b = d.getBoundingBox(null);
				Utils.log("d x,y = " + b.x + ", " + b.y);
				d.setLocation(b.x + new_x, b.y + new_y);
			}
		}

		updateInDatabase("layer_dimensions");
		// and notify the Display
		Display.update(this);
		return true;
	}

	synchronized public boolean remove(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null != al_layers && al_layers.size() > 0 ? " and all its children?" : ""))) return false;
		}
		// delete all layers
		while (0 != al_layers.size()) {
			if (!((DBObject)al_layers.get(0)).remove(false)) {
				Utils.showMessage("LayerSet id= " + id + " : Deletion incomplete, check database.");
				return false;
			}
		}
		// delete the ZDisplayables
		Iterator it = al_zdispl.iterator();
		while (it.hasNext()) {
			((ZDisplayable)it.next()).remove(false); // will call back the LayerSet.remove(ZDisplayable)
		}
		// remove the self
		if (null != parent) parent.remove(this);
		removeFromDatabase();
		return true;
	}

	/** Remove a child. Does not destroy it or delete it from the database. */
	synchronized public void remove(Layer layer) {
		if (null == layer || -1 == al_layers.indexOf(layer)) return;
		al_layers.remove(layer);
		Display.updateLayerScroller(this);
		Display.updateTitle(this);
	}

	synchronized public Layer next(Layer layer) {
		int i = al_layers.indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.next: no such Layer " + layer);
			return layer;
		}
		if (al_layers.size() -1 == i) return layer;
		else return (Layer)al_layers.get(i+1);
	}

	synchronized public Layer previous(Layer layer) {
		int i = al_layers.indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.previous: no such Layer " + layer);
			return layer;
		}
		if (0 == i) return layer;
		else return (Layer)al_layers.get(i-1);
	}

	public Layer nextNonEmpty(Layer layer) {
		Layer next = layer;
		Layer given = layer;
		do {
			layer = next;
			next = next(layer);
			if (!next.isEmpty()) return next;
		} while (!next.equals(layer));
		return given;
	}
	public Layer previousNonEmpty(Layer layer) {
		Layer previous = layer;
		Layer given = layer;
		do {
			layer = previous;
			previous = previous(layer);
			if (!previous.isEmpty()) return previous;
		} while (!previous.equals(layer));
		return given;
	}

	synchronized public int getLayerIndex(long id) {
		for (int i=al_layers.size()-1; i>-1; i--) {
			if (((Layer)al_layers.get(i)).getId() == id) return i;
		}
		return -1;
	}

	/** Find a layer by index, or null if none. */
	synchronized public Layer getLayer(int i) {
		if (i >=0 && i < al_layers.size()) return (Layer)al_layers.get(i);
		return null;
	}

	/** Find a layer with the given id, or null if none. */
	synchronized public Layer getLayer(final long id) {
		Iterator it = al_layers.iterator();
		while (it.hasNext()) {
			Layer layer = (Layer)it.next();
			if (layer.getId() == id) return layer;
		}
		return null;
	}

	/** Returns the first layer found with the given Z coordinate, rounded to seventh decimal precision, or null if none found. */
	synchronized public Layer getLayer(final double z) {
		Iterator it = al_layers.iterator();
		double error = 0.0000001; // TODO adjust to an optimal
		while (it.hasNext()) {
			Layer layer = (Layer)it.next();
			if (error > Math.abs(layer.getZ() - z)) { // floating-point arithmetic is still not a solved problem!
				return layer;
			}
		}
		return null;
	}

	synchronized public Layer getNearestLayer(final double z) {
		double min_dist = Double.MAX_VALUE;
		Layer closest = null;
		for (Layer layer : al_layers) {
			double dist = Math.abs(layer.getZ() - z);
			if (dist < min_dist) {
				min_dist = dist;
				closest = layer;
			}
		}
		return closest;
	}

	/** Returns null if none has the given z and thickness. If 'create' is true and no layer is found, a new one with the given Z is created and added to the LayerTree. */
	synchronized public Layer getLayer(double z, double thickness, boolean create) {
		Iterator it = al_layers.iterator();
		Layer layer = null;
		double error = 0.0000001; // TODO adjust to an optimal
		while (it.hasNext()) {
			Layer l = (Layer)it.next();
			if (error > Math.abs(l.getZ() - z) && error > Math.abs(l.getThickness() - thickness)) { // floating point is still not a solved problem.
				//Utils.log("LayerSet.getLayer: found layer with z=" + l.getZ());
				layer = l;
			}
		}
		if (create && null == layer && !Double.isNaN(z) && !Double.isNaN(thickness)) {
			//Utils.log("LayerSet.getLayer: creating new Layer with z=" + z);
			layer = new Layer(project, z, thickness, this);
			add(layer);
			project.getLayerTree().addLayer(this, layer);
		}
		return layer;
	}

	/** Add a Displayable to be painted in all Layers, such as a Pipe. */
	synchronized public void add(final ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) {
			Utils.log2("LayerSet: not adding zdispl");
			return;
		}
		al_zdispl.add(0, zdispl); // at the top
		zdispl.setLayerSet(this);
		// The line below can fail (and in the addSilently as well) if one can add zdispl objects while no Layer has been created. But the ProjectThing.createChild prevents this situation.
		zdispl.setLayer(al_layers.get(0));
		zdispl.updateInDatabase("layer_set_id"); // TODO: update stack index?
		Display.add(this, zdispl);
	}

	/** Used for reconstruction purposes, avoids repainting or updating. */
	synchronized public void addSilently(ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) return;
		try {
			zdispl.setLayer(0 == al_layers.size() ? null : al_layers.get(0));
			zdispl.setLayerSet(this, false);
			//Utils.log2("setLayerSet to ZDipl id=" + zdispl.getId());
			al_zdispl.add(zdispl);
		} catch (Exception e) {
			Utils.log("LayerSet.addSilently: not adding Zdisplayable with id=" + zdispl.getId());
			new IJError(e);
			return;
		}
	}

	/** Remove a child. Does not destroy the child nor remove it from the database, only from the Display. */
	synchronized public boolean remove(ZDisplayable zdispl) {
		if (null == zdispl || null == al_zdispl || -1 == al_zdispl.indexOf(zdispl)) return false;
		al_zdispl.remove(zdispl);
		Display.remove(zdispl);
		return true;
	}

	/** Returns a copy of the list of ZDisplayable objects. */
	synchronized public ArrayList<ZDisplayable> getZDisplayables() { return (ArrayList<ZDisplayable>)al_zdispl.clone(); }

	/** Returns a list of ZDisplayable of class c only.*/
	synchronized public ArrayList getZDisplayables(final Class c) {
		final ArrayList al = new ArrayList();
		if (null == c) return al;
		if (c.equals(Displayable.class) || c.equals(ZDisplayable.class)) {
			al.addAll(al_zdispl);
			return al;
		}
		for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (c.equals(ob.getClass())) al.add(ob);
		}
		return al;
	}

	synchronized public boolean contains(Layer layer) {
		if (null == layer) return false;
		return -1 != al_layers.indexOf(layer);
	}

	synchronized public boolean contains(Displayable zdispl) {
		if (null == zdispl) return false;
		return -1 != al_zdispl.indexOf(zdispl);
	}

	/** Returns a copy of the layer list. */
	synchronized public ArrayList<Layer> getLayers() {
		return (ArrayList<Layer>)al_layers.clone(); // for integrity and safety, return a copy.
	}

	public boolean isDeletable() {
		return false;
	}

	/** Overiding. The alpha is used to show whether the LayerSet object is selected or not. */
	public void setAlpha(float alpha) { return; }

	/** Move the given Displayable to the next layer if possible. */
	synchronized public void moveDown(Layer layer, Displayable d) {
		int i = al_layers.indexOf(layer);
		if (al_layers.size() -1 == i || -1 == i) return;
		layer.remove(d);
		((Layer)(al_layers.get(i +1))).add(d);
	}
	/** Move the given Displayable to the previous layer if possible. */
	synchronized public void moveUp(Layer layer, Displayable d) {
		int i = al_layers.indexOf(layer);
		if (0 == i || -1 == i) return;
		layer.remove(d);
		((Layer)(al_layers.get(i -1))).add(d);
	}

	/** Move all Displayable objects in the HashSet to the given target layer. */
	public void move(HashSet hs_d, Layer source, Layer target) {
		if (0 == hs_d.size() || null == source || null == target || source.equals(target)) return;
		Display.setRepaint(false); // disable repaints
		for (Iterator it = hs_d.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (source.equals(d.getLayer())) {
				source.remove(d);
				target.add(d, false, false); // these contortions to avoid repeated DB traffic
				d.updateInDatabase("layer_id");
				Display.add(target, d, false); // don't activate
			}
		}
		Display.setRepaint(true); // enable repaints
		source.updateInDatabase("stack_index");
		target.updateInDatabase("stack_index");
		Display.repaint(source); // update graphics: true
		Display.repaint(target);
	}

	/** Find ZDisplayable objects that contain the point x,y in the given layer. */
	synchronized public ArrayList findZDisplayables(Layer layer, int x, int y) {
		ArrayList al = new ArrayList();
		Iterator it = al_zdispl.iterator();
		while (it.hasNext()) {
			ZDisplayable zd = (ZDisplayable)it.next();
			if (zd.contains(layer, x, y)) al.add(zd);
		}
		return al;
	}


	synchronized public void setVisible(String type, boolean visible, boolean repaint) {
		type = type.toLowerCase();
		try {
			project.getLoader().startLargeUpdate();
			if (type.equals("pipe") || type.equals("ball") || type.equals("arealist")) {
				Iterator it = al_zdispl.iterator();
				while (it.hasNext()) {
					ZDisplayable zd = (ZDisplayable)it.next();
					if (zd.getClass().getName().toLowerCase().endsWith(type)) { // endsWith, because DLabel is called as Label
						zd.setVisible(visible, false); // don't repaint
					}
				}
			} else {
				if (type.equals("image")) type = "patch";
				Iterator it = al_layers.iterator();
				while (it.hasNext()) {
					Layer l = (Layer)it.next();
					l.setVisible(type, visible, false); // don't repaint
				}
			}
		} catch (Exception e) {
			new IJError(e);
		} finally {
			project.getLoader().commitLargeUpdate();
		}
		if (repaint) {
			Display.repaint(this); // this could be optimized to repaint only the accumulated box
		}
	}
	/** Returns true if any of the ZDisplayable objects are of the given class. */
	synchronized public boolean contains(Class c) {
		Iterator it = al_zdispl.iterator();
		while (it.hasNext()) {
			if (it.next().getClass().equals(c)) return true;
		}
		return false;
	}
	/** Check in all layers. */
	synchronized public boolean containsDisplayable(Class c) {
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer la = (Layer)it.next();
			if (la.contains(c)) return true;
		}
		return false;
	}

	/** Returns the distance from the first layer's Z to the last layer's Z. */
	synchronized public double getDepth() {
		if (null == al_layers || al_layers.isEmpty()) return 0;
		return ((Layer)al_layers.get(al_layers.size() -1)).getZ() - ((Layer)al_layers.get(0)).getZ();
	}

	/** Return all the Displayable objects from all the layers of this LayerSet. Does not include the ZDisplayables. */
	synchronized public ArrayList getDisplayables() {
		final ArrayList al = new ArrayList();
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			al.addAll(layer.getDisplayables());
		}
		return al;
	}
	/** Return all the Displayable objects from all the layers of this LayerSet of the given class. Does not include the ZDisplayables. */
	synchronized public ArrayList getDisplayables(Class c) {
		final ArrayList al = new ArrayList();
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			al.addAll(layer.getDisplayables(c));
		}
		return al;
	}

	/** From zero to size-1. */
	synchronized public int indexOf(Layer layer) {
		return al_layers.indexOf(layer);
	}

	synchronized public void exportXML(final java.io.Writer writer, final String indent, final Object any) throws Exception {
		final StringBuffer sb_body = new StringBuffer();
		sb_body.append(indent).append("<t2_layer_set\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		sb_body.append(in).append("layer_width=\"").append(layer_width).append("\"\n")
		       .append(in).append("layer_height=\"").append(layer_height).append("\"\n")
		       .append(in).append("rot_x=\"").append(rot_x).append("\"\n")
		       .append(in).append("rot_y=\"").append(rot_y).append("\"\n")
		       .append(in).append("rot_z=\"").append(rot_z).append("\"\n")
		       .append(in).append("snapshots_quality=\"").append(snapshots_quality).append("\"\n")
		       .append(in).append("snapshots_enabled=\"").append(snapshots_enabled).append("\"\n")
		       // TODO: alpha! But it's not necessary.
		;
		sb_body.append(indent).append(">\n");
		if (null != calibration) {
			sb_body.append(in).append("<t2_calibration\n")
			       .append(in).append("\tpixelWidth=\"").append(calibration.pixelWidth).append("\"\n")
			       .append(in).append("\tpixelHeight=\"").append(calibration.pixelHeight).append("\"\n")
			       .append(in).append("\tpixelDepth=\"").append(calibration.pixelDepth).append("\"\n")
			       .append(in).append("\txOrigin=\"").append(calibration.xOrigin).append("\"\n")
			       .append(in).append("\tyOrigin=\"").append(calibration.yOrigin).append("\"\n")
			       .append(in).append("\tzOrigin=\"").append(calibration.zOrigin).append("\"\n")
			       .append(in).append("\tinfo=\"").append(calibration.info).append("\"\n")
			       .append(in).append("\tvalueUnit=\"").append(calibration.getValueUnit()).append("\"\n")
			       .append(in).append("\ttimeUnit=\"").append(calibration.getTimeUnit()).append("\"\n")
			       .append(in).append("\tunit=\"").append(calibration.getUnit()).append("\"\n")
			       .append(in).append("/>\n")
			;
		}
		writer.write(sb_body.toString());
		// export ZDisplayable objects
		if (null != al_zdispl) {
			for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
				ZDisplayable zd = (ZDisplayable)it.next();
				// don't include in the XML file if the object is empty
				if (!zd.isDeletable()) {
					sb_body.setLength(0);
					zd.exportXML(sb_body, in, any);
					writer.write(sb_body.toString()); // each separately, for they can be huge
				}
			}
		}
		// export Layer and contained Displayable objects
		if (null != al_layers) {
			//Utils.log("LayerSet " + id + " is saving " + al_layers.size() + " layers.");
			for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
				sb_body.setLength(0);
				((Layer)it.next()).exportXML(sb_body, in, any);
				writer.write(sb_body.toString());
			}
		}
		writer.write("</t2_layer_set>\n");
	}

	/** Includes the !ELEMENT */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_layer_set";
		if (!hs.contains(type)) {
			sb_header.append(indent).append("<!ELEMENT t2_layer_set (t2_layer,t2_pipe,t2_ball,t2_area_list,t2_calibration)>\n");
			Displayable.exportDTD(type, sb_header, hs, indent);
			sb_header.append(indent).append(TAG_ATTR1).append(type).append(" layer_width").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" layer_height").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_x").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_y").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_z").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" snapshots_quality").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" snapshots_enabled").append(TAG_ATTR2)
			;
			sb_header.append(indent).append("<!ELEMENT t2_calibration EMPTY>\n")
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelWidth").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelHeight").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration pixelDepth").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration xOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration yOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration zOrigin").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration info").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration valueUnit").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration timeUnit").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append("t2_calibration unit").append(TAG_ATTR2)
			;
		}
	}

	public void setSnapshotsEnabled(boolean b) {
		if (b == this.snapshots_enabled) return;
		this.snapshots_enabled = b;
		Display.repaintSnapshots(this);
		updateInDatabase("snapshots_enabled");
	}

	public boolean areSnapshotsEnabled() {
		return this.snapshots_enabled;
	}

	/** Creates an undo step that contains transformations for all Displayable objects of this LayerSet */
	synchronized public void createUndoStep() {
		final Hashtable ht_undo = new Hashtable();
		for (Iterator lit = al_layers.iterator(); lit.hasNext(); ) {
			Layer la = (Layer)lit.next();
			for (Iterator dit = la.getDisplayables().iterator(); dit.hasNext(); ) {
				Displayable d = (Displayable)dit.next();
				ht_undo.put(d, d.getAffineTransformCopy());
			}
		}
		addUndoStep(ht_undo);
	}

	/** Creates an undo step that contains transformations for all Displayable objects in the given Layer. */
	public void createUndoStep(final Layer layer) {
		if (null == layer) return;
		final Hashtable ht_undo = new Hashtable();
		for (Iterator dit = layer.getDisplayables().iterator(); dit.hasNext(); ) {
			Displayable d = (Displayable)dit.next();
			ht_undo.put(d, d.getAffineTransformCopy());
		}
		addUndoStep(ht_undo);
	}

	/** The @param ht should be a hastable of Displayable keys and Transform values, such as those obtained from selection.getTransformationsCopy() . By adding a new undo step, the redo steps are cleared. */
	public void addUndoStep(Hashtable ht) {
		if (ht.isEmpty()) return;
		if (undo_queue.size() == MAX_UNDO_STEPS) {
			undo_queue.removeFirst();
			current--;
		}
		//Utils.log("addUndoStep A: current: " + current + "  total: " + undo_queue.size());
		// clear undo_queue beyond current
		while (undo_queue.size() > current) {
			if (0 == undo_queue.size()) {
				Utils.log2("attempted to remove from empty list: current is " + current);
				break;
			}
			undo_queue.removeLast();
		}
		// reset
		cycle_flag = false;
		undo_queue.add(ht);
		current = undo_queue.size(); // current is not stored, is beyond bounds. What was just stored was the previous to current
		//Utils.log("current: " + current + "   total: " + undo_queue.size());
		/*
		// discard redo steps
		redo_queue.clear(); */
	}

	/** Usable only when undoing the last step, to catch the current step (which is not in the undo queue).*/
	void appendCurrent(Hashtable ht) {
		if (ht.isEmpty() || undo_queue.size() != current || cycle_flag) return;
		Utils.log2("appendCurrent: undo queue size: " + undo_queue.size() + " and current: " + current);
		undo_queue.add(ht);
		// current doesn't change, but now it exists in the undo_queue
	}

	public boolean canUndo() {
		return current > 0; //0 != undo_queue.size();
	}
	public boolean canRedo() {
		return current < undo_queue.size(); //0 != redo_queue.size();
	}
	public void undoOneStep() {
		if (current < 1 || 0 == undo_queue.size()) return;
		if (cycle_flag && undo_queue.size() == current) current--; // compensate
		current--;
		if (current < 0) current = 0; // patching ...
		Hashtable step = (Hashtable)undo_queue.get(current);
		applyStep(step);
		cycle_flag = true;
		Utils.log2("undoing to current=" + current);
		/*
		Hashtable last = (Hashtable)undo_queue.removeLast();
		if (null != current) redo_queue.add(current);
		current = last;
		applyStep(last);
		*/
		//Utils.log2("undoing " + step);
	}
	public void redoOneStep() {
		//if (/*0 == redo_queue.size()*/ 0 == undo_queue.size() || current == undo_queue.size() -2) return;
		current += 1;
		if (current >= undo_queue.size()) {
			Utils.log2("prevented redoing to current=" + current);
			current = undo_queue.size();
			return;
		}
		Hashtable step = (Hashtable)undo_queue.get(current);
		applyStep(step);
		/*
		Hashtable next = (Hashtable)redo_queue.removeLast();
		if (null != current) undo_queue.add(current);
		current = next;
		applyStep(next);
		if (0 == redo_queue.size()) {
			current = null; // reset
		}
		*/
		//Utils.log2("redoing " + step);
		Utils.log2("redoing to current=" + current);
	}
	private void applyStep(Hashtable ht) {
		// apply:
		Rectangle box = null;
		Rectangle b = new Rectangle(); // tmp
		project.getLoader().startLargeUpdate();
		try {
			for (Iterator it = ht.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next(); // I hate java
				Displayable d = (Displayable)entry.getKey();
				// add both the previous and the after box, for repainting
				if (null == box) box = d.getBoundingBox(b);
				else box.add(d.getBoundingBox(b));
				d.setAffineTransform((AffineTransform)entry.getValue());
				box.add(d.getBoundingBox(b));
			}
			project.getLoader().commitLargeUpdate();
		} catch (Exception e) {
			new IJError(e);
			Utils.log("Rolling back");
			project.getLoader().rollback();
		}
		Display.updateSelection();
		Display.repaint(this, box);
		Display.repaintSnapshots(this);
	}

	/** Find the given Displayable in the undo/redo queues and clear it. This functionality is used when an object is removed, for which there is no undo. */
	public void removeFromUndo(Displayable d) {
		// from the undo_queue
		for (Iterator it = undo_queue.iterator(); it.hasNext(); ) {
			Hashtable ht = (Hashtable)it.next();
			for (Iterator itd = ht.keySet().iterator(); itd.hasNext(); ) {
				if (d.equals(itd.next())) {
					itd.remove();
					break; // the inner loop only
				}
			}
		}
	}

	/** Used when there has been no real transformation (for example, a mouse click and release, but no drag. */
	void discardLastUndo() {
		if (0 == undo_queue.size()) return;
		//Utils.log2("discarding last undo!");
		undo_queue.removeLast();
		current--;
	}

	synchronized public void destroy() {
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			layer.destroy();
		}
		for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
			ZDisplayable zd = (ZDisplayable)it.next();
			zd.destroy();
		}
		this.al_layers.clear();
		this.al_zdispl.clear();
		this.al_zdispl = null;
		this.undo_queue.clear();
		this.undo_queue = null;
		//this.redo_queue.clear();
		//this.redo_queue = null;
		if (null != align) {
			align.destroy();
			align = null;
		}
	}

	public boolean isAligning() {
		return null != align;
	}

	public void cancelAlign() {
		if (null != align) {
			align.cancel(); // will repaint
			align = null;
		}
	}

	public void applyAlign(final boolean post_register) {
		if (null != align) align.apply(post_register);
	}

	public void applyAlign(final Layer la_start, final Layer la_end, final Selection selection) {
		if (null != align) align.apply(la_start, la_end, selection);
	}

	public void startAlign(Display display) {
		align = new Align(display);
	}

	public Align getAlign() {
		return align;
	}

	/** Used by the Layer.setZ method. */
	synchronized protected void reposition(Layer layer) {
		if (null == layer || !al_layers.contains(layer)) return;
		al_layers.remove(layer);
		addSilently(layer);
	}

	/** Get up to 'n' layers before and after the given layers. */
	synchronized public ArrayList getNeighborLayers(final Layer layer, final int n) {
		final int i_layer = al_layers.indexOf(layer);
		final ArrayList al = new ArrayList();
		if (-1 == i_layer) return al;
		int start = i_layer - n;
		if (start < 0) start = 0;
		int end = i_layer + n;
		if (end > al_layers.size()) end = al_layers.size();
		for (int i=start; i<i_layer; i++) al.add(al_layers.get(i));
		for (int i=i_layer+1; i<= i_layer + n || i < end; i++) al.add(al_layers.get(i));
		return al;
	}

	synchronized public boolean isTop(ZDisplayable zd) {
		if (null != zd && al_zdispl.size() > 0 && al_zdispl.indexOf(zd) == al_zdispl.size() -1) return true;
		return false;
	}

	synchronized public boolean isBottom(ZDisplayable zd) {
		if (null != zd && al_zdispl.size() > 0 && al_zdispl.indexOf(zd) == 0) return true;
		return false;
	}

	/** Hub method: ZDisplayable or into the Displayable's Layer. */
	protected boolean isTop(Displayable d) {
		if (d instanceof ZDisplayable) return isTop((ZDisplayable)d);
		else return d.getLayer().isTop(d);
	}
	/** Hub method: ZDisplayable or into the Displayable's Layer. */
	protected boolean isBottom(Displayable d) {
		if (d instanceof ZDisplayable) return isBottom((ZDisplayable)d);
		else return d.getLayer().isBottom(d);
	}

	/** Change z position in the layered stack, which defines the painting order. */ // the BOTTOM of the stack is the first element in the al_zdispl array
	synchronized protected void move(final int place, final Displayable d) {
		if (d instanceof ZDisplayable) {
			int i = al_zdispl.indexOf(d);
			if (-1 == i) {
				Utils.log("LayerSet.move: object does not belong here");
				return;
			}
			int size = al_zdispl.size();
			if (1 == size) return;
			switch(place) {
				case LayerSet.TOP:
					al_zdispl.add(al_zdispl.remove(i));
					break;
				case LayerSet.UP:
					if (size -1 == i) return;
					al_zdispl.add(i+1, al_zdispl.remove(i));
					break;
				case LayerSet.DOWN:
					if (0 == i) return;
					al_zdispl.add(i, al_zdispl.remove(i-1)); //swap
					break;
				case LayerSet.BOTTOM:
					al_zdispl.add(0, al_zdispl.remove(i));
					break;
			}
			updateInDatabase("stack_index");
			Display.updatePanelIndex(d.getLayer(), d);
		} else {
			switch (place) {
				case LayerSet.TOP: d.getLayer().moveTop(d); break;
				case LayerSet.UP: d.getLayer().moveUp(d); break;
				case LayerSet.DOWN: d.getLayer().moveDown(d); break;
				case LayerSet.BOTTOM: d.getLayer().moveBottom(d); break;
			}
		}
	}

	synchronized public int indexOf(ZDisplayable zd) {
		int k = al_zdispl.indexOf(zd);
		if (-1 == k) return -1;
		return al_zdispl.size() - k -1;
	}

	synchronized public boolean isEmptyAt(Layer la) {
		for (Iterator it = al_zdispl.iterator(); it.hasNext(); ) {
			if (((ZDisplayable)it.next()).paintsAt(la)) return false;
		}
		return true;
	}

	synchronized public Displayable clone(Project project) {
		return clone(project, (Layer)al_layers.get(0), (Layer)al_layers.get(al_layers.size()-1), new Rectangle(0, 0, (int)Math.ceil(getLayerWidth()), (int)Math.ceil(getLayerHeight())), false);
	}

	/** Clone the contents of this LayerSet, from first to last given layers, and cropping for the given rectangle. */
	synchronized public Displayable clone(Project project, Layer first, Layer last, Rectangle roi, boolean add_to_tree) {
		// obtain a LayerSet
		LayerSet target = null;
		if (null == this.layer) {
			// copy into the exisiting root layer set
			target = project.getRootLayerSet();
			target.setDimensions(roi.width, roi.height, LayerSet.NORTHWEST);
		} else {
			target = new LayerSet(project, getTitle(), 0, 0, null, layer_width, layer_height);
		}
		target.setCalibration(getCalibrationCopy());
		// copy into the target LayerSet the range of layers
		final java.util.List al = ((ArrayList)al_layers.clone()).subList(indexOf(first), indexOf(last) +1);
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Layer source = (Layer)it.next();
			Layer copy = source.clone(project, target, roi);
			target.addSilently(copy);
			if (add_to_tree) project.getLayerTree().addLayer(target, copy);
		}
		return (Displayable)target;
	}

	public LayerStack makeLayerStack(Display display) {
		return new LayerStack(this, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height), display);
	}

	/** Will return the virtual_scale value if different than -1, or else a scale value automatically computed to fit the largest dimension of the box to max_dimension.*/
	public double getPixelsVirtualizationScale(Rectangle box) {
		// else automatic mode: limit size to 1024 width and 1024 height, keeping aspect ratio
		if (box.width > box.height) {
			return max_dimension / (double)box.width;
		} else {
			return max_dimension / (double)box.height;
		}
	}

	public int getPixelsDimension() { return max_dimension; }
	public void setPixelsDimension(int d) {
		// TODO
	}

	public void setPixelsVirtualizationEnabled(boolean b) { this.virtualization_enabled = b; }
	public boolean isPixelsVirtualizationEnabled() { return virtualization_enabled; }


	public Rectangle get2DBounds() {
		return new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
	}

	public void setCalibration(Calibration cal) {
		if (null == cal) return;
		this.calibration = (Calibration)cal.clone();
	}

	protected Calibration getCalibration() {
		return this.calibration;
	}

	public Calibration getCalibrationCopy() {
		return calibration.copy();
	}

	public boolean isCalibrated() {
		Calibration identity = new Calibration();
		if (identity.equals(this.calibration)) return true;
		return false;
	}

	/** Restore calibration from the given XML attributes table.*/
	public void restoreCalibration(Hashtable ht_attributes) {
		for (Iterator it = ht_attributes.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String value = (String)entry.getValue();
			// remove the prefix 't2_'
			key.substring(3).toLowerCase(); // case-resistant
			try {
				if (key.equals("pixelwidth")) {
					calibration.pixelWidth = Double.parseDouble(value);
				} else if (key.equals("pixelheight")) {
					calibration.pixelHeight = Double.parseDouble(value);
				} else if (key.equals("pixeldepth")) {
					calibration.pixelDepth = Double.parseDouble(value);
				} else if (key.equals("xorigin")) {
					calibration.xOrigin = Double.parseDouble(value);
				} else if (key.equals("yorigin")) {
					calibration.yOrigin = Double.parseDouble(value);
				} else if (key.equals("zorigin")) {
					calibration.zOrigin = Double.parseDouble(value);
				} else if (key.equals("info")) {
					calibration.info = value;
				} else if (key.equals("valueunit")) {
					calibration.setValueUnit(value);
				} else if (key.equals("timeunit")) {
					calibration.setTimeUnit(value);
				} else if (key.equals("unit")) {
					calibration.setUnit(value);
				}
			} catch (Exception e) {
				Utils.log2("LayerSet.restoreCalibration, key/value failed:" + key + "=\"" + value +"\"");
				new IJError(e);
			}
		}
		//Utils.log2("Restored LayerSet calibration: " + calibration);
	}

	/** For creating snapshots, using a very slow but much better scaling algorithm (the Image.SCALE_AREA_AVERAGING method). */
	public boolean snapshotsQuality() {
		return snapshots_quality;
	}

	public void setSnapshotsQuality(boolean b) {
		this.snapshots_quality = b;
		updateInDatabase("snapshots_quality");
		// TODO this is obsolete
	}

	/** Find, in this LayerSet and contained layers and their nested LayerSets if any, all Displayable instances of Class c. Includes the ZDisplayables. */
	public ArrayList get(final Class c) {
		return get(new ArrayList(), c);
	}

	/** Find, in this LayerSet and contained layers and their nested LayerSets if any, all Displayable instances of Class c, which are stored in the given ArrayList; returns the same ArrayList, or a new one if its null. Includes the ZDisplayables. */
	synchronized public ArrayList get(ArrayList all, final Class c) {
		if (null == all) all = new ArrayList();
		// check whether to include all the ZDisplayable objects
		if (Displayable.class.equals(c) || ZDisplayable.class.equals(c)) all.addAll(al_zdispl);
		else {
			for (Iterator it = al_zdispl.iterator(); it.hasNext(); ){
				Object ob = it.next();
				if (ob.getClass().equals(c)) all.add(ob);
			}
		}
		for (Iterator it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = (Layer)it.next();
			all.addAll(layer.getDisplayables(c));
			ArrayList al_ls = layer.getDisplayables(LayerSet.class);
			for (Iterator i2 = al_ls.iterator(); i2.hasNext(); ) {
				LayerSet ls = (LayerSet)i2.next();
				ls.get(all, c);
			}
		}
		return all;
	}

	/** Returns the region defined by the rectangle as an image in the type and format specified.
	 *  The type is either ImagePlus.GRAY8 or ImagePlus.COLOR_RGB.
	 *  The format is either Layer.IMAGE (an array) or Layer.ImagePlus (it returns an ImagePlus containing an ImageStack), from which any ImageProcessor or pixel arrays can be retrieved trivially.
	 */
	public Object grab(final int first, final int last, final Rectangle r, final double scale, final Class c, final int format, final int type) {
		// check preconditions
		if (first < 0 || first > last || last >= al_layers.size()) {
			Utils.log("Invalid first and/or last layers.");
			return null;
		}
		// check that it will fit in memory
		if (!project.getLoader().releaseToFit(r.width, r.height, type, 1.1f)) {
			Utils.log("LayerSet.grab: Cannot fit an image stack of " + (long)(r.width*r.height*(ImagePlus.GRAY8==type?1:4)*1.1) + " bytes in memory.");
			return null;
		}
		if (Layer.IMAGEPLUS == format) {
			ImageStack stack = new ImageStack((int)Math.ceil(r.width*scale), (int)Math.ceil(r.height*scale));
			for (int i=first; i<=last; i++) {
				Layer la = (Layer)al_layers.get(i);
				Utils.log2("c is " + c);
				ImagePlus imp = project.getLoader().getFlatImage(la, r, scale, 1, type, c, null, true);
				if (null != imp) try {
					stack.addSlice(imp.getTitle(), imp.getProcessor().getPixels());
				} catch (IllegalArgumentException iae) {
					new IJError(iae);
				} else Utils.log("LayerSet.grab: Ignoring layer " + la);
			}
			if (0 == stack.getSize()) {
				Utils.log("LayerSet.grab: could not make slices.");
				return null;
			}
			return new ImagePlus("Stack " + first + "-" + last, stack);
		} else if (Layer.IMAGE == format) {
			final Image[] image = new Image[last - first + 1];
			for (int i=first, j=0; i<=last; i++, j++) {
				image[j] = project.getLoader().getFlatAWTImage((Layer)al_layers.get(i), r, scale, 1, type, c, null, true);
			}
			return image;
		}
		return null;
	}
}
