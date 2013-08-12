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
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.imaging.LayerStack;
import ini.trakem2.parallel.Process;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.Thing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;


/** A LayerSet is a container for a list of Layer.
 *  LayerSet methods are NOT synchronized. It is your reponsibility to synchronize access to a LayerSet instance methods. Failure to do so may result in corrupted internal data structures and overall misbehavior.
 */
public final class LayerSet extends Displayable implements Bucketable { // Displayable is already extending DBObject

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
	// the possible flips
	static public final int FLIP_HORIZONTAL = 11;
	static public final int FLIP_VERTICAL = 12;

	// positions in the stack
	static public final int TOP = 13;
	static public final int UP = 14;
	static public final int DOWN = 15;
	static public final int BOTTOM = 16;

	static public final String[] snapshot_modes = new String[]{"Full","Outlines","Disabled"};

	/** 0, 1, 2 -- corresponding to snapshot_modes entries above. */
	private int snapshots_mode = 0;

	static public final String[] ANCHORS =  new String[]{"north", "north east", "east", "southeast", "south", "south west", "west", "north west", "center"};
	static public final String[] ROTATIONS = new String[]{"90 right", "90 left", "Flip horizontally", "Flip vertically"};

	private float layer_width = 5000, // the Displayable.width is for the representation, not for the dimensions of the LayerSet!
				  layer_height = 5000;
	private double rot_x;
	private double rot_y;
	private double rot_z; // should be equivalent to the Displayable.rot
	private final ArrayList<Layer> al_layers = new ArrayList<Layer>();

	/** A map of Long vs Layer, that is lock-free for reading, but locks for modifying it,
	 *  by synchronizing onto IDLAYERS_WRITE_LOCK. */
	private HashMap<Long,Layer> idlayers = new HashMap<Long,Layer>();
	private final Object IDLAYERS_WRITE_LOCK = new Object();

	private final HashMap<Layer,Integer> layerindices = new HashMap<Layer,Integer>();
	/** The layer in which this LayerSet lives. If null, this is the root LayerSet. */
	private Layer parent = null;
	/** A LayerSet can contain Displayables that are show in every single Layer, such as Pipe objects. */
	private final ArrayList<ZDisplayable> al_zdispl = new ArrayList<ZDisplayable>();

	/** For creating snapshots. */
	private boolean snapshots_quality = true;

	/** The maximum size of either width or height when virtualizing pixel access to the layers.*/
	private int max_dimension = 1024;
	private boolean virtualization_enabled = false;

	protected boolean color_cues = true;
	protected boolean area_color_cues = true;
	protected boolean use_color_cue_colors = true;
	protected boolean paint_arrows = true;
	protected boolean paint_tags = true;
	protected boolean paint_edge_confidence_boxes = true;
	protected int n_layers_color_cue = 0; // -1 means all
	protected boolean prepaint = false;
	protected int preload_ahead = 0;

	private Calibration calibration = new Calibration(); // default values

	/** Dummy. */
	protected LayerSet(Project project, long id) {
		super(project, id, null, false, null, 20, 20);
	}

	/** Create a new LayerSet with a 0,0,0 rotation vector and default 20,20 px Displayable width,height. */
	public LayerSet(Project project, String title, double x, double y, Layer parent, float layer_width, float layer_height) {
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
	public LayerSet(Project project, long id, String title, float width, float height, double rot_x, double rot_y, double rot_z, float layer_width, float layer_height, boolean locked, int snapshots_mode, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.rot_x = rot_x;
		this.rot_y = rot_y;
		this.rot_z = rot_z;
		this.layer_width = layer_width;
		this.layer_height= layer_height;
		this.snapshots_mode = snapshots_mode;
		// the parent will be set by the LayerThing.setup() calling Layer.addSilently()
		// the al_layers will be filled idem.
	}

	/** Reconstruct from an XML entry. */
	public LayerSet(final Project project, final long id, final HashMap<String,String> ht_attributes, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attributes, ht_links);
		String data;
		if (null != (data = ht_attributes.get("layer_width"))) this.layer_width = Float.parseFloat(data);
		else xmlError("layer_width", this.layer_width);
		if (null != (data = ht_attributes.get("layer_height"))) this.layer_height = Float.parseFloat(data);
		else xmlError("layer_height", this.layer_height);
		if (null != (data = ht_attributes.get("rot_x"))) this.rot_x = Double.parseDouble(data);
		else xmlError("rot_x", this.rot_x);
		if (null != (data = ht_attributes.get("rot_y"))) this.rot_y = Double.parseDouble(data);
		else xmlError("rot_y", this.rot_y);
		if (null != (data = ht_attributes.get("rot_z"))) this.rot_y = Double.parseDouble(data);
		else xmlError("rot_z", this.rot_z);
		if (null != (data = ht_attributes.get("snapshots_quality"))) snapshots_quality = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("snapshots_mode"))) {
			final String smode = data.trim();
			for (int i=0; i<snapshot_modes.length; i++) {
				if (smode.equals(snapshot_modes[i])) {
					snapshots_mode = i;
					break;
				}
			}
		}
		if (null != (data = ht_attributes.get("color_cues"))) color_cues = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("area_color_cues"))) area_color_cues = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("n_layers_color_cue"))) {
			n_layers_color_cue = Integer.parseInt(data.trim().toLowerCase());
			if (n_layers_color_cue < -1) n_layers_color_cue = -1;
		}
		if (null != (data = ht_attributes.get("avoid_color_cue_colors"))) {
			// If there's any error in the parsing, default to true for use_color_cue_colors:
			use_color_cue_colors = !Boolean.valueOf(data.trim().toLowerCase());
		}
		if (null != (data = ht_attributes.get("paint_arrows"))) paint_arrows = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("paint_tags"))) paint_tags = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("paint_edge_confidence_boxes"))) paint_edge_confidence_boxes = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("prepaint"))) prepaint = Boolean.valueOf(data.trim().toLowerCase());
		if (null != (data = ht_attributes.get("preload_ahead"))) preload_ahead = Integer.parseInt(data);
	}

	/** For reconstruction purposes: set the active layer to the ZDisplayable objects. Recurses through LayerSets in the children layers. */
	public void setup() {
		final Layer la0 = al_layers.get(0);
		for (ZDisplayable zd : al_zdispl) zd.setLayer(la0); // just any Layer
		for (Layer layer : al_layers) {
			for (final Displayable d : layer.getDisplayables()) {
				if (d.getClass() == LayerSet.class) {
					((LayerSet)d).setup();
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
			float width = (float)gd.getNextNumber();
			float height = (float)gd.getNextNumber();
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
	public void addSilently(final Layer layer) {
		if (null == layer || al_layers.contains(layer)) return;
		try {
			synchronized (IDLAYERS_WRITE_LOCK) {
				// Like put, but replacing the map instance
				final HashMap<Long,Layer> m = new HashMap<Long,Layer>(idlayers);
				m.put(layer.getId(), layer);
				idlayers = m;
			}
			synchronized (layerindices) { layerindices.clear(); }
			double z = layer.getZ();
			int i = 0;
			for (final Layer la : al_layers) {
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
	public void add(final Layer layer) {
		if (layer.getProject() != this.project)
			throw new IllegalArgumentException("LayerSet rejected a Layer: belongs to a different project.");

		if (null != idlayers.get(layer.getId())) return;

		final double z = layer.getZ();
		final int n = al_layers.size();
		int i = 0;
		for (; i<n; i++) {
			Layer l = al_layers.get(i);
			if (l.getZ() < z) continue;
			break;
		}
		if (i < n) {
			al_layers.add(i, layer);
		} else {
			al_layers.add(layer);
		}
		layer.setParent(this);
		synchronized (IDLAYERS_WRITE_LOCK) {
			// Like put, but replacing the map instance
			final HashMap<Long,Layer> m = new HashMap<Long,Layer>(idlayers);
			m.put(layer.getId(), layer);
			idlayers = m;
		}
		synchronized (layerindices) { layerindices.clear(); }
		Display.updateLayerScroller(this);
		//debug();
	}

	public void printDebugInfo() {
		Utils.log("LayerSet debug:");
		for (int i=0; i<al_layers.size(); i++)
			Utils.log(i + " : " + al_layers.get(i).getZ());
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

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
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

	public void paint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer) {
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		// Translate graphics context accordingly
		AffineTransform gt = g.getTransform();
		AffineTransform aff = new AffineTransform(this.at);
		aff.preConcatenate(gt);
		g.setTransform(aff);

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
		g.setTransform(gt);
	}

	public float getLayerWidth() { return layer_width; }
	public float getLayerHeight() { return layer_height; }
	public double getRotX() { return rot_x; }
	public double getRotY() { return rot_y; }
	public double getRotZ() { return rot_z; }

	/** The number of Layers in this LayerSet. */
	public int size() {
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
	public boolean setMinimumDimensions() {
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
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(al_layers.get(i).getDisplayables());
		}
		al.addAll(al_zdispl);

		// find minimum bounding box
		Rectangle b = new Rectangle();
		for (final Displayable d : al) {
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

		double w = xe - x;
		double h = ye - y;
		if (w <= 0 || h <= 0) {
			Utils.log("LayerSet.setMinimumDimensions: zero width or height, NOT resizing.");
			return false;
		}

		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

		// translate
		if (0 != x || 0 != y) {
			project.getLoader().startLargeUpdate();
			try {
				final AffineTransform at2 = new AffineTransform();
				at2.translate(-x, -y);
				//Utils.log2("translating all displayables by " + x + "," + y);
				for (final Displayable d : al) {
					//((Displayable)it.next()).translate(-x, -y, false); // drag regardless of getting off current LayerSet bounds
					// optimized to avoid creating so many AffineTransform instances:
					//Utils.log2("BEFORE: " + d.getBoundingBox());
					d.getAffineTransform().preConcatenate(at2);
					//Utils.log2("AFTER: " + d.getBoundingBox());
					d.updateInDatabase("transform");
				}
				project.getLoader().commitLargeUpdate();
			} catch (Exception e) {
				IJError.print(e);
				project.getLoader().rollback();
				return false;
			}
		}

		//Utils.log("x,y  xe,ye : " + x + "," + y + "  " + xe + "," + ye);
		// finally, accept:
		if (w != layer_width || h != layer_height) {
			this.layer_width = (float)Math.ceil(w); // stupid int to double conversions ... why floating point math is a non-solved problem? It is for SBCL
			this.layer_height = (float)Math.ceil(h);
			updateInDatabase("layer_dimensions");
			recreateBuckets(true);
			// and notify the Displays, if any
			Display.update(this);
			Display.pack(this);
		}

		// Record current state:
		addEditStep(new LayerSet.DoResizeLayerSet(this));

		return true;
	}

	/** Enlarge the 2D universe so that all Displayable in the collection fit in it;
	 * that is, that no Displayable has a negative x,y position or lays beyond bounds.*/
	synchronized public void enlargeToFit(final Collection<? extends Displayable> ds) {
		Rectangle r = null;
		for (Displayable d : ds) {
			if (null == r) r = d.getBoundingBox();
			else r.add(d.getBoundingBox());
		}
		if (null == r) return; //empty collection
		r.add(get2DBounds());
		setDimensions(r.x, r.y, r.width, r.height);
	}

	/** Enlarges the display in the given direction; the anchor is the point to keep still, and can be any of LayerSet.NORTHWEST (top-left), etc. */
	synchronized public boolean enlargeToFit(final Displayable d, final int anchor) {
		final Rectangle r = new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
		final Rectangle b = d.getBoundingBox(null);
		// check if necessary
		if (r.contains(b)) return false;
		// else, enlarge to fit it
		r.add(b);
		return setDimensions(r.width, r.height, anchor);
	}

	/** May leave objects beyond the visible window. */
	public void setDimensions(float x, float y, float layer_width, float layer_height) {
		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

		this.layer_width = layer_width;
		this.layer_height = layer_height;
		final AffineTransform affine = new AffineTransform();
		affine.translate(-x, -y);
		for (ZDisplayable zd : al_zdispl) {
			zd.getAffineTransform().preConcatenate(affine);
			zd.updateInDatabase("transform");
		}
		for (Layer la : al_layers) la.apply(Displayable.class, affine);

		recreateBuckets(true);

		Display.update(this);

		// Record new state
		addEditStep(new LayerSet.DoResizeLayerSet(this));
	}

	/** Returns false if any Displayables are being partially or totally cropped away. */
	public boolean setDimensions(float layer_width, float layer_height, int anchor) {
		// check preconditions
		if (Double.isNaN(layer_width) || Double.isNaN(layer_height)) { Utils.log("LayerSet.setDimensions: NaNs! Not adjusting."); return false; }
		if (layer_width <=0 || layer_height <= 0) { Utils.showMessage("LayerSet: can't accept zero or a minus for layer width or height"); return false; }
		if (anchor < NORTH || anchor > CENTER) {  Utils.log("LayerSet: wrong anchor, not resizing."); return false; }

		// Record previous state
		if (prepareStep(this)) {
			addEditStep(new LayerSet.DoResizeLayerSet(this));
		}

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
		ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (int i=al_layers.size() -1; i>-1; i--) {
			al.addAll(al_layers.get(i).getDisplayables());
		}
		al.addAll(al_zdispl);

		// check that no displayables are being cropped away
		if (layer_width < this.layer_width || layer_height < this.layer_height) {
			for (final Displayable d : al) {
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
			for (final Displayable d : al) {
				Rectangle b = d.getBoundingBox(null);
				//Utils.log("d x,y = " + b.x + ", " + b.y);
				d.setLocation(b.x + new_x, b.y + new_y);
			}
		}

		updateInDatabase("layer_dimensions");
		recreateBuckets(true);
		// and notify the Display
		Display.update(this);
		Display.pack(this);

		// Record new state
		addEditStep(new LayerSet.DoResizeLayerSet(this));

		return true;
	}

	protected boolean remove2(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null != al_layers && al_layers.size() > 0 ? " and all its children?" : ""))) return false;
		}
		LayerThing lt = project.findLayerThing(this);
		if (null == lt) return false;
		return project.getLayerTree().remove(check, lt, null); // will end up calling remove(boolean) on this object
	}

	public boolean remove(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null != al_layers && al_layers.size() > 0 ? " and all its children?" : ""))) return false;
		}
		// delete all layers
		while (0 != al_layers.size()) {
			if (!al_layers.get(0).remove(false)) {
				Utils.showMessage("LayerSet id= " + id + " : Deletion incomplete, check database.");
				return false;
			}
		}
		// delete the ZDisplayables
		for (final ZDisplayable zd : al_zdispl) {
			zd.remove(false); // will call back the LayerSet.remove(ZDisplayable)
		}
		// remove the self
		if (null != parent) parent.remove(this);
		removeFromDatabase();
		return true;
	}

	/** Remove a child. Does not destroy it or delete it from the database. */
	public void remove(final Layer layer) {
		if (null == layer || null == idlayers.get(layer.getId())) return;
		al_layers.remove(layer);
		synchronized (IDLAYERS_WRITE_LOCK) {
			// Like remove, but replacing the map instance
			final HashMap<Long,Layer> m = new HashMap<Long,Layer>(idlayers);
			m.remove(layer.getId());
			idlayers = m;
		}
		synchronized (layerindices) { layerindices.clear(); }
		for (final ZDisplayable zd : new ArrayList<ZDisplayable>(al_zdispl)) zd.layerRemoved(layer); // may call back and add/remove ZDisplayable objects
		Display.updateLayerScroller(this);
		Display.updateTitle(this);
		removeFromOffscreens(layer);
	}

	public Layer next(final Layer layer) {
		final int i = indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.next: no such Layer " + layer);
			return layer;
		}
		if (al_layers.size() -1 == i) return layer;
		else return al_layers.get(i+1);
	}

	public Layer previous(final Layer layer) {
		final int i = indexOf(layer);
		if (-1 == i) {
			Utils.log("LayerSet.previous: no such Layer " + layer);
			return layer;
		}
		if (0 == i) return layer;
		else return al_layers.get(i-1);
	}

	public Layer nextNonEmpty(Layer layer) {
		Layer next = layer;
		Layer given = layer;
		do {
			layer = next;
			next = next(layer);
			if (!next.isEmpty()) return next;
		} while (next != layer);
		return given;
	}
	public Layer previousNonEmpty(Layer layer) {
		Layer previous = layer;
		Layer given = layer;
		do {
			layer = previous;
			previous = previous(layer);
			if (!previous.isEmpty()) return previous;
		} while (previous != layer);
		return given;
	}

	public int getLayerIndex(final long id) {
		final Layer layer = getLayer(id);
		if (null == layer) return -1;
		return indexOf(layer);
	}

	/** Find a layer by index, or null if none. */
	public Layer getLayer(final int i) {
		if (i >=0 && i < al_layers.size()) return al_layers.get(i);
		return null;
	}

	/** Find a layer with the given id, or null if none. */
	public Layer getLayer(final long id) {
		return idlayers.get(id);
	}

	/** Same as getLayer(long) but without box/unbox. */
	public Layer getLayer(final Long id) {
		return idlayers.get(id);
	}

	/** Returns the first layer found with the given Z coordinate, rounded to seventh decimal precision, or null if none found. */
	public Layer getLayer(final double z) {
		double error = 0.0000001; // TODO adjust to an optimal
		for (Layer layer : al_layers) {
			if (error > Math.abs(layer.getZ() - z)) { // floating-point arithmetic is still not a solved problem!
				return layer;
			}
		}
		return null;
	}

	public Layer getNearestLayer(final double z) {
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
	public Layer getLayer(double z, double thickness, boolean create) {
		Iterator<Layer> it = al_layers.iterator();
		Layer layer = null;
		double error = 0.0000001; // TODO adjust to an optimal
		while (it.hasNext()) {
			Layer l = it.next();
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

	/** Useful for typeless scripts so that a ZDisplayable can be added; the
	 * overloaded method add(Layer) and add(ZDisplayable) is not distinguishable otherwise. */
	public void addZDisplayable(final ZDisplayable zdispl) {
		add(zdispl);
	}

	/** Add a Displayable to be painted in all Layers, such as a Pipe. Also updates open displays of the fact. */
	public void add(final ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) {
			Utils.log2("LayerSet: not adding zdispl");
			return;
		}
		if (zdispl.getProject() != this.project)
			throw new IllegalArgumentException("LayerSet rejected a ZDisplayable: belongs to a different project.");

		al_zdispl.add(zdispl); // at the top

		zdispl.setLayerSet(this);
		// The line below can fail (and in the addSilently as well) if one can add zdispl objects while no Layer has been created. But the ProjectThing.createChild prevents this situation.
		zdispl.setLayer(al_layers.get(0));
		zdispl.updateInDatabase("layer_set_id"); // TODO: update stack index? It should!

		// insert into bucket
		/*
		if (null != root) {
			// add as last, then update
			root.put(al_zdispl.size()-1, zdispl, zdispl.getBoundingBox(null));
			// Updating takes too long, just don't do it
			//root.update(this, zdispl, 0, al_zdispl.size()-1);
		}
		*/
		addToBuckets(zdispl, al_zdispl.size()-1);

		Display.add(this, zdispl);
	}

	public void addAll(final Collection<? extends ZDisplayable> coll) {
		if (null == coll || 0 == coll.size()) return;
		for (final ZDisplayable zd : coll) {
			al_zdispl.add(zd);
			zd.setLayerSet(this);
			zd.setLayer(al_layers.get(0));
			zd.updateInDatabase("layer_set_id");
		}
		recreateBuckets(false); // only ZDisplayable
		Display.addAll(this, coll);
	}

	/** Used for reconstruction purposes, avoids repainting or updating. */
	public void addSilently(final ZDisplayable zdispl) {
		if (null == zdispl || -1 != al_zdispl.indexOf(zdispl)) return;
		try {
			zdispl.setLayer(0 == al_layers.size() ? null : al_layers.get(0));
			zdispl.setLayerSet(this, false);
			//Utils.log2("setLayerSet to ZDipl id=" + zdispl.getId());
			al_zdispl.add(zdispl);
		} catch (Exception e) {
			Utils.log("LayerSet.addSilently: not adding ZDisplayable with id=" + zdispl.getId());
			IJError.print(e);
			return;
		}
	}

	/** Remove a child. Does not destroy the child nor remove it from the database, only from the LayerSet and the Display. */
	public boolean remove(final ZDisplayable zdispl) {
		if (null == zdispl || null == al_zdispl) return false;
		final int old_stack_index = al_zdispl.indexOf(zdispl);
		if (-1 == old_stack_index) {
			Utils.log2("LayerSet.remove: Not found: " + zdispl);
			return false;
		}
		al_zdispl.remove(old_stack_index);
		// remove from Bucket AFTER modifying stack index, so it gets reindexed properly
		removeFromBuckets(zdispl, old_stack_index);
		removeFromOffscreens(zdispl);
		Display.remove(zdispl);
		return true;
	}
	
	/** Remove a child. Does not destroy the child nor remove it from the database, only from the LayerSet and the Display.
	 *  Returns false if at least one failed to be removed. */
	public boolean removeAll(final Set<ZDisplayable> zds) {
		if (null == zds || null == al_zdispl) return false;
		// Ensure list is iterated only once: don't ask for index every time!
		int count = 0;
		for (final Iterator<ZDisplayable> it = al_zdispl.iterator(); it.hasNext(); ) {
			final ZDisplayable zd = it.next();
			if (zds.contains(zd)) {
				it.remove();
				removeFromOffscreens(zd);
				Display.remove(zd);
				count++;
				if (zds.size() == count) break;
			}
		}
		removeFromBuckets(zds);
		Display.updateVisibleTabs(this.project);
		return true;
	}

	public boolean contains(final Layer layer) {
		if (null == layer) return false;
		return -1 != indexOf(layer);
	}

	public boolean contains(final Displayable zdispl) {
		if (null == zdispl) return false;
		return -1 != al_zdispl.indexOf(zdispl);
	}

	/** Returns a copy of the layer list. */
	public ArrayList<Layer> getLayers() {
		return new ArrayList<Layer>(al_layers); // for integrity and safety, return a copy.
	}

	/** Returns a sublist of layers from first to last, both inclusive. If last is larger than first, the order is reversed.  */
	public List<Layer> getLayers(final int first, final int last) {
		final List<Layer> las = al_layers.subList(Math.min(first, last), Math.max(first, last) +1);
		if (first > last) {
			final List<Layer> las2 = new ArrayList<Layer>(las);
			Collections.reverse(las2); // would otherwise reverse the original list! A monumental error.
			return las2;
		}
		return new ArrayList<Layer>(las); // editable, thread-safe (a copy)
	}

	/** Returns the layer range from first to last, both included. If last.getZ() &lt; first.getZ(), the order is reversed. */
	public List<Layer> getLayers(final Layer first, final Layer last) {
		return getLayers(indexOf(first), indexOf(last));
	}

	/** Returns the list of layers to paint by considering the range of n_layers_color_cue around the active layer index. */
	public List<Layer> getColorCueLayerRange(final Layer active_layer) {
		if (n_layers_color_cue < 0) {
			return new ArrayList<Layer>(al_layers); // a copy of all
		} else if (0 == n_layers_color_cue) {
			final ArrayList<Layer> list = new ArrayList<Layer>();
			list.add(active_layer);
			return list;
		}
		// Else:
		final int i = indexOf(active_layer);
		if (-1 == i) {
			Utils.log("An error ocurred: could not find an index for layer " + active_layer);
			final ArrayList<Layer> a = new ArrayList<Layer>(); a.add(active_layer); return a;
		}
		int first = i - n_layers_color_cue;
		int last = i + n_layers_color_cue;
		if (first < 0) first = 0;
		int size = al_layers.size();
		if (last >= size) last = size -1;
		return getLayers(first, last);
	}

	public boolean isDeletable() {
		return false;
	}

	/** Overiding. The alpha is used to show whether the LayerSet object is selected or not. */
	public void setAlpha(float alpha) { return; }

	/** Move the given Displayable to the next layer if possible. */
	public void moveDown(final Layer layer, final Displayable d) {
		final int i = indexOf(layer);
		if (al_layers.size() -1 == i || -1 == i) return;
		layer.remove(d);
		(al_layers.get(i +1)).add(d);
	}
	/** Move the given Displayable to the previous layer if possible. */
	public void moveUp(final Layer layer, final Displayable d) {
		final int i = indexOf(layer);
		if (0 == i || -1 == i) return;
		layer.remove(d);
		al_layers.get(i -1).add(d);
	}

	/** Move all Displayable objects in the HashSet to the given target layer. */
	public void move(final Set<Displayable> hs_d, final Layer source, final Layer target) {
		if (0 == hs_d.size() || null == source || null == target || source == target) return;
		Display.setRepaint(false); // disable repaints
		for (final Displayable d : hs_d) {
			if (d instanceof ZDisplayable) continue; // ignore
			if (source == d.getLayer()) {
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

	/** Returns the hash set of objects whose visibility has changed. */
	public HashSet<Displayable> setVisible(String type, final boolean visible, final boolean repaint) {
		type = type.toLowerCase();
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		try {
			project.getLoader().startLargeUpdate();
			if (type.equals("connector") || type.equals("treeline") || type.equals("areatree") || type.equals("pipe") || type.equals("ball") || type.equals("arealist") || type.equals("polyline") || type.equals("stack") || type.equals("dissector")) {
				for (ZDisplayable zd : al_zdispl) {
					if (visible != zd.isVisible() && zd.getClass().getName().toLowerCase().endsWith(type)) { // endsWith, because DLabel is called as Label
						zd.setVisible(visible, false); // don't repaint
						hs.add(zd);
					}
				}
			} else {
				for (Layer layer : al_layers) {
					hs.addAll(layer.setVisible(type, visible, false)); // don't repaint
				}
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			project.getLoader().commitLargeUpdate();
		}
		if (repaint) {
			Display.repaint(this); // this could be optimized to repaint only the accumulated box
		}
		return hs;
	}
	/** Hide all except those whose type is in 'type' list, whose visibility flag is left unchanged. Returns the list of displayables made hidden. */
	public HashSet<Displayable> hideExcept(ArrayList<Class<?>> type, boolean repaint) {
		final HashSet<Displayable> hs = new HashSet<Displayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (!type.contains(zd.getClass()) && zd.isVisible()) {
				zd.setVisible(false, repaint);
				hs.add(zd);
			}
		}
		for (Layer la : al_layers) hs.addAll(la.hideExcept(type, repaint));
		return hs;
	}
	/** Returns the collection of Displayable whose visibility state has changed. */
	public Collection<Displayable> setAllVisible(final boolean repaint) {
		final Collection<Displayable> col = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (!zd.isVisible()) {
				zd.setVisible(true, repaint);
				col.add(zd);
			}
		}
		for (Layer la : al_layers) col.addAll(la.setAllVisible(repaint));
		return col;
	}

	/** Returns true if any of the ZDisplayable objects are of the given class. */
	public boolean contains(final Class<?> c) {
		for (final ZDisplayable zd : al_zdispl) {
			if (zd.getClass() == c) return true;
		}
		return false;
	}
	/** Check in all layers. */
	public boolean containsDisplayable(final Class<?> c) {
		for (final Layer layer : al_layers) {
			if (layer.contains(c)) return true;
		}
		return false;
	}

	/** Returns the distance from the first layer's Z to the last layer's Z. */
	public double getDepth() {
		if (null == al_layers || al_layers.isEmpty()) return 0;
		return al_layers.get(al_layers.size() -1).getZ() - al_layers.get(0).getZ();
	}

	/** Return all the Displayable objects from all the layers of this LayerSet. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables() {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables());
		}
		return al;
	}
	/** Return all the Displayable objects from all the layers of this LayerSet of the given class. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables(Class<?> c) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables(c));
		}
		return al;
	}
	/** Return all the Displayable objects from all the layers of this LayerSet of the given class that intersect the given area. Does not include the ZDisplayables. */
	public ArrayList<Displayable> getDisplayables(final Class<?> c, final Area aroi, final boolean visible_only) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (Layer layer : al_layers) {
			al.addAll(layer.getDisplayables(c, aroi, visible_only));
		}
		return al;
	}

	/** From zero to size-1. */
	public int indexOf(final Layer layer) {
		synchronized (layerindices) {
			Integer i = layerindices.get(layer);
			if (null == i) {
				// Recreate
				layerindices.clear();
				int k = 0;
				for (final Layer la : al_layers) {
					layerindices.put(la, k);
					k++;
				}
				i = layerindices.get(layer);
				if (null == i) {
					Utils.log("ERROR: could not find an index for layer " + layer);
					return -1;
				}
			}
			return i.intValue();
		}
	}

	private static java.lang.reflect.Field sbvalue = null;
	static {
		try {
			sbvalue = StringBuilder.class.getSuperclass().getDeclaredField("value");
			sbvalue.setAccessible(true);
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	public void exportXML(final java.io.Writer writer, final String indent, final XMLOptions options) throws Exception {
		final StringBuilder sb_body = new StringBuilder(512);
		sb_body.append(indent).append("<t2_layer_set\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		sb_body.append(in).append("layer_width=\"").append(layer_width).append("\"\n")
		       .append(in).append("layer_height=\"").append(layer_height).append("\"\n")
		       .append(in).append("rot_x=\"").append(rot_x).append("\"\n")
		       .append(in).append("rot_y=\"").append(rot_y).append("\"\n")
		       .append(in).append("rot_z=\"").append(rot_z).append("\"\n")
		       .append(in).append("snapshots_quality=\"").append(snapshots_quality).append("\"\n")
		       .append(in).append("snapshots_mode=\"").append(snapshot_modes[snapshots_mode]).append("\"\n")
		       .append(in).append("color_cues=\"").append(color_cues).append("\"\n")
		       .append(in).append("area_color_cues=\"").append(area_color_cues).append("\"\n")
		       .append(in).append("avoid_color_cue_colors=\"").append(!use_color_cue_colors).append("\"\n")
		       .append(in).append("n_layers_color_cue=\"").append(n_layers_color_cue).append("\"\n")
		       .append(in).append("paint_arrows=\"").append(paint_arrows).append("\"\n")
		       .append(in).append("paint_tags=\"").append(paint_tags).append("\"\n")
		       .append(in).append("paint_edge_confidence_boxes=\"").append(paint_edge_confidence_boxes).append("\"\n")
		       .append(in).append("prepaint=\"").append(prepaint).append("\"\n")
		       .append(in).append("preload_ahead=\"").append(preload_ahead).append("\"\n")
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
		if (null == sbvalue) {
			writer.write(sb_body.toString());
		} else {
			writer.write((char[])sbvalue.get(sb_body), 0, sb_body.length()); // avoid making a copy of the array
		}
		// Count objects
		int done = 0;
		int total = 0;
		total += al_zdispl.size();
		for (final Layer la : al_layers) {
			total += la.getDisplayableList().size();
		}
		// export ZDisplayable objects
		if (null != al_zdispl) {
			for (final ZDisplayable zd : al_zdispl) {
				sb_body.setLength(0);
				zd.exportXML(sb_body, in, options);
				if (null == sbvalue) {
					writer.write(sb_body.toString()); // each separately, for they can be huge
				} else {
					writer.write((char[])sbvalue.get(sb_body), 0, sb_body.length()); // avoid making a copy of the array
				}
			}
			done += al_zdispl.size();
			Utils.showProgress(done / (double)total);
		}
		// export Layer and contained Displayable objects
		if (null != al_layers) {
			//Utils.log("LayerSet " + id + " is saving " + al_layers.size() + " layers.");
			for (final Layer la : al_layers) {
				sb_body.setLength(0);
				la.exportXML(sb_body, in, options);
				if (null == sbvalue) {
					writer.write(sb_body.toString());
				} else {
					writer.write((char[])sbvalue.get(sb_body), 0, sb_body.length()); // avoid making a copy of the array
				}
				done += la.getDisplayableList().size();
				Utils.showProgress(done / (double)total);
			}
		}
		sb_body.setLength(0);
		if (sb_body.length() > 0) {
			super.restXML(sb_body, in, options);
			if (null == sbvalue) {
				writer.write(sb_body.toString());
			} else {
				writer.write((char[])sbvalue.get(sb_body), 0, sb_body.length()); // avoid making a copy of the array
			}
		}
		writer.write(indent + "</t2_layer_set>\n");
	}

	/** Includes the !ELEMENT */
	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_layer_set";
		if (!hs.contains(type)) {
			sb_header.append(indent).append("<!ELEMENT t2_layer_set (").append(Displayable.commonDTDChildren()).append(",t2_layer,t2_pipe,t2_ball,t2_area_list,t2_calibration,t2_stack,t2_treeline)>\n");
			Displayable.exportDTD(type, sb_header, hs, indent);
			sb_header.append(indent).append(TAG_ATTR1).append(type).append(" layer_width").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" layer_height").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_x").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_y").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" rot_z").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" snapshots_quality").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" color_cues").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" area_color_cues").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" avoid_color_cue_colors").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" n_layers_color_cue").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" paint_arrows").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" paint_tags").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" paint_edge_confidence_boxes").append(TAG_ATTR2)
				 .append(indent).append(TAG_ATTR1).append(type).append(" preload_ahead").append(TAG_ATTR2)
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

	public void setSnapshotsMode(final int mode) {
		if (mode == snapshots_mode) return;
		this.snapshots_mode = mode;
		Display.repaintSnapshots(this);
		updateInDatabase("snapshots_mode");
	}

	public int getSnapshotsMode() {
		return this.snapshots_mode;
	}

	@Override
	public void destroy() {
		for (Iterator<Layer> it = al_layers.iterator(); it.hasNext(); ) {
			Layer layer = it.next();
			layer.destroy();
		}
		for (final ZDisplayable zd : al_zdispl) {
			zd.destroy();
		}
		this.al_layers.clear();
		this.al_zdispl.clear();
		synchronized (IDLAYERS_WRITE_LOCK) { this.idlayers = new HashMap<Long,Layer>(); } // like .clear()
		synchronized (layerindices) { this.layerindices.clear(); }
		this.offscreens.clear();
	}

	/** Used by the Layer.setZ method. */
	protected void reposition(final Layer layer) {
		if (null == layer || !idlayers.containsKey(layer.getId())) return;
		al_layers.remove(layer);
		addSilently(layer);
	}

	/** Get up to 'n' layers before and after the given layers. */
	public ArrayList<Layer> getNeighborLayers(final Layer layer, final int n) {
		final int i_layer = indexOf(layer);
		final ArrayList<Layer> al = new ArrayList<Layer>();
		if (-1 == i_layer) return al;
		int start = i_layer - n;
		if (start < 0) start = 0;
		int end = i_layer + n;
		if (end > al_layers.size()) end = al_layers.size();
		for (int i=start; i<i_layer; i++) al.add(al_layers.get(i));
		for (int i=i_layer+1; i<= i_layer + n || i < end; i++) al.add(al_layers.get(i));
		return al;
	}

	public boolean isTop(ZDisplayable zd) {
		if (null != zd && al_zdispl.size() > 0 && al_zdispl.indexOf(zd) == al_zdispl.size() -1) return true;
		return false;
	}

	public boolean isBottom(ZDisplayable zd) {
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
					// To the end of the list:
					al_zdispl.add(al_zdispl.remove(i));
					// OLD // if (null != root) root.update(this, d, i, al_zdispl.size()-1);
					updateRangeInBuckets(d, i, al_zdispl.size()-1);
					break;
				case LayerSet.UP:
					// +1 in the list
					if (size -1 == i) return;
					al_zdispl.add(i+1, al_zdispl.remove(i));
					//if (null != root) root.update(this, d, i, i+1);
					updateRangeInBuckets(d, i, i+1);
					break;
				case LayerSet.DOWN:
					// -1 in the list
					if (0 == i) return;
					al_zdispl.add(i-1, al_zdispl.remove(i)); //swap
					//if (null != root) root.update(this, d, i-1, i);
					updateRangeInBuckets(d, i-1, i);
					break;
				case LayerSet.BOTTOM:
					// to first position in the list
					al_zdispl.add(0, al_zdispl.remove(i));
					//if (null != root) root.update(this, d, 0, i);
					updateRangeInBuckets(d, 0, i);
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

	/** Returns the reverse index of ZDisplayable zd, which is the actual index as seen in the screen. */
	public int indexOf(final ZDisplayable zd) {
		int k = al_zdispl.indexOf(zd);
		if (-1 == k) return -1;
		return al_zdispl.size() - k -1;
	}

	public boolean isEmptyAt(final Layer layer) {
		for (final ZDisplayable zd : al_zdispl) {
			if (zd.paintsAt(layer)) return false;
		}
		return true;
	}

	public Displayable clone(final Project pr, final boolean copy_id) {
		final Rectangle roi = new Rectangle(0, 0, (int)Math.ceil(getLayerWidth()), (int)Math.ceil(getLayerHeight()));
		final LayerSet copy = (LayerSet) clone(pr, al_layers.get(0), al_layers.get(al_layers.size()-1), roi, false, copy_id, false);
		try {
			LayerSet.cloneInto(this, al_layers.get(0), al_layers.get(al_layers.size()-1), pr, copy, roi, copy_id);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		return copy;
	}

	/** Clone the contents of this LayerSet, from first to last given layers, and cropping for the given rectangle;
	 *  does NOT copy the ZDisplayable, which may be copied using the LayerSet.cloneInto method. */
	public Displayable clone(Project pr, Layer first, Layer last, Rectangle roi, boolean add_to_tree, boolean copy_id, boolean ignore_hidden_patches) {
		// obtain a LayerSet
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final LayerSet copy = new LayerSet(pr, nid, getTitle(), this.width, this.height, this.rot_x, this.rot_y, this.rot_z, roi.width, roi.height, this.locked, this.snapshots_mode, (AffineTransform)this.at.clone());
		copy.setCalibration(getCalibrationCopy());
		copy.snapshots_quality = this.snapshots_quality;
		// copy objects that intersect the roi, from within the given range of layers
		final java.util.List<Layer> range = new ArrayList<Layer>(al_layers).subList(indexOf(first), indexOf(last) +1);
		Utils.log2("range.size() : " + range.size());
		for (Layer layer : range) {
			Layer layercopy = layer.clone(pr, copy, roi, copy_id, ignore_hidden_patches);
			copy.addSilently(layercopy);
			if (add_to_tree) pr.getLayerTree().addLayer(copy, layercopy);
		}
		return copy;
	}

	static public void cloneInto(final LayerSet src, Layer src_first, Layer src_last,
								 final Project pr, final LayerSet copy, Rectangle roi, boolean copy_id)
	throws Exception {
		// copy ZDisplayable objects if they intersect the roi, and translate them properly
		final AffineTransform trans = new AffineTransform();
		trans.translate(-roi.x, -roi.y);
		final List<Layer> range = copy.getLayers();
		List<Layer> src_range = null;
		if (0 == range.size()) throw new Exception("Cannot cloneInto for a range of zero layers!");
		for (final ZDisplayable zd : src.find(range.get(0), range.get(range.size()-1), new Area(roi))) {
			if (src.project != pr && zd instanceof Tree<?>) {
				// Special in-cloning + crop + out-cloning for Tree instances, since they hold Layer pointers
				// 1. Clone within same project, with ALL layers present
				ZDisplayable src_zd_copy = (ZDisplayable)zd.clone(src.project, true); // NOTICE I use src.project, not pr! And also reuse same id -- this is a throwaway.
				// 2. Crop to the desired range, using the range from the original project
				if (null == src_range) src_range = new ArrayList<Layer>(src.al_layers).subList(src.indexOf(src_first), src.indexOf(src_last) +1);
				src_zd_copy.crop(src_range);
				// 3. Clone the cropped Tree
				Tree<?> tcopy = (Tree<?>)src_zd_copy.clone(pr, copy_id);
				tcopy.getAffineTransform().preConcatenate(trans);
				copy.addSilently(tcopy);
				tcopy.calculateBoundingBox(null); // for all layers. Will update buckets.
				continue;
			}
			// Else, normally:
			ZDisplayable zdcopy = (ZDisplayable)zd.clone(pr, copy_id);
			zdcopy.getAffineTransform().preConcatenate(trans);
			copy.addSilently(zdcopy); // must be added before attempting to crop it, because crop needs a LayerSet ref.
			if (zdcopy.crop(range)) {
				if (zdcopy.isDeletable()) {
					pr.remove(zdcopy);
					Utils.log("Skipping empty " + zdcopy);
				} else {
					zdcopy.calculateBoundingBox(null); // null means update buckets for all layers
				}
			} else {
				Utils.log("Could not crop " + zd);
			}
		}
		// fix links:
		copy.linkPatchesR();
	}

	/** Create a virtual layer stack that acts as a virtual ij.ImageStack, in RGB and set to a scale of max_dimension / Math.max(layer_width, layer_height). */
	public LayerStack createLayerStack(Class<?> clazz, int type, int c_alphas) {
		return new LayerStack(this,
				      getVirtualizationScale(),
				      type,
				      clazz,
				      c_alphas);
	}

	public int getPixelsMaxDimension() { return max_dimension; }
	/** From 0.000... to 1. */
	public double getVirtualizationScale() {
		double scale = max_dimension / Math.max(layer_width, layer_height);
		return scale > 1 ? 1 : scale;
	}
	public void setPixelsMaxDimension(final int d) {
		if (d > 2) {
			if (d != max_dimension) {
				max_dimension = d;
				Polyline.flushTraceCache(project); // depends on the scale value
				Utils.log("3D Viewer NOT updated:\n  close it and recreate meshes for any objects you had in it.");
			}
		} else Utils.log("Can't set virtualization max pixels dimension to smaller than 2!");
	}

	public void setPixelsVirtualizationEnabled(boolean b) { this.virtualization_enabled = b; }
	public boolean isPixelsVirtualizationEnabled() { return virtualization_enabled; }


	/** Returns a new Rectangle of 0, 0, layer_width, layer_height. */
	public Rectangle get2DBounds() {
		return new Rectangle(0, 0, (int)Math.ceil(layer_width), (int)Math.ceil(layer_height));
	}

	/** Set the calibration to a clone of the given calibration. */
	public void setCalibration(Calibration cal) {
		if (null == cal) return;
		this.calibration = (Calibration)cal.clone();
	}

	public Calibration getCalibration() {
		return this.calibration;
	}

	public Calibration getCalibrationCopy() {
		return calibration.copy();
	}

	public boolean isCalibrated() {
		Calibration identity = new Calibration();
		if (identity.equals(this.calibration)) return false;
		return true;
	}

	/** Restore calibration from the given XML attributes table.*/
	public void restoreCalibration(HashMap<String,String> ht_attributes) {
		for (final Map.Entry<String,String> entry : ht_attributes.entrySet()) {
			final String key = (String)entry.getKey();
			final String value = (String)entry.getValue();
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
				IJError.print(e);
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
	public ArrayList<Displayable> get(final Class<?> c) {
		return get(new ArrayList<Displayable>(), c);
	}

	/** Find, in this LayerSet and contained layers and their nested LayerSets if any, all Displayable instances of Class c, which are stored in the given ArrayList; returns the same ArrayList, or a new one if its null. Includes the ZDisplayables. */
	public ArrayList<Displayable> get(ArrayList<Displayable> all, final Class<?> c) {
		if (null == all) all = new ArrayList<Displayable>();
		// check whether to include all the ZDisplayable objects
		if (Displayable.class == c || ZDisplayable.class == c) all.addAll(al_zdispl);
		else {
			for (final ZDisplayable zd : al_zdispl) {
				if (zd.getClass() == c) all.add(zd);
			}
		}
		for (final Layer layer : al_layers) {
			all.addAll(layer.getDisplayables(c));
			for (final Displayable ls : layer.getDisplayables(LayerSet.class)) {
				((LayerSet)ls).get(all, c);
			}
		}
		return all;
	}

	/** Returns the region defined by the rectangle as an image in the type and format specified.
	 *  The type is either ImagePlus.GRAY8 or ImagePlus.COLOR_RGB.
	 *  The format is either Layer.IMAGE (an array) or Layer.ImagePlus (it returns an ImagePlus containing an ImageStack), from which any ImageProcessor or pixel arrays can be retrieved trivially.
	 */
	public Object grab(final int first, final int last, final Rectangle r, final double scale, final Class<?> c, final int c_alphas, final int format, final int type) {
		// check preconditions
		if (first < 0 || first > last || last >= al_layers.size()) {
			Utils.log("Invalid first and/or last layers.");
			return null;
		}
		// Ensure some memory is free
		project.getLoader().releaseToFit(r.width, r.height, type, 1.1f);
		if (Layer.IMAGEPLUS == format) {
			ImageStack stack = new ImageStack((int)(r.width*scale), (int)(r.height*scale));
			for (int i=first; i<=last; i++) {
				Layer la = al_layers.get(i);
				Utils.log2("c is " + c);
				ImagePlus imp = project.getLoader().getFlatImage(la, r, scale, c_alphas, type, c, null, true);
				if (null != imp) try {
					//if (0 == stack.getSize()) stack.setColorModel(imp.getProcessor().getColorModel());
					stack.addSlice(imp.getTitle(), imp.getProcessor()); //.getPixels());
				} catch (IllegalArgumentException iae) {
					IJError.print(iae);
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
				image[j] = project.getLoader().getFlatAWTImage(al_layers.get(i), r, scale, c_alphas, type, c, null, true, Color.black);
			}
			return image;
		}
		return null;
	}


	/** Searches in all layers. Ignores the ZDisplaybles. */
	public Displayable findDisplayable(final long id) {
		for (Layer la : al_layers) {
			for (Displayable d : la.getDisplayables()) {
				if (d.getId() == id) return d;
			}
		}
		return null;
	}

	/** Searches in all ZDisplayables and in all layers, recursively into nested LayerSets. */
	public DBObject findById(final long id) {
		if (this.id == id) return this;
		for (ZDisplayable zd : al_zdispl) {
			if (zd.getId() == id) return zd;
		}
		for (Layer la : al_layers) {
			DBObject dbo = la.findById(id);
			if (null != dbo) return dbo;
		}
		return null;
	}

	// private to the package
	void linkPatchesR() {
		for (Layer la : al_layers) la.linkPatchesR();
		for (ZDisplayable zd : al_zdispl) zd.linkPatches();
	}

	/** Recursive into nested LayerSet objects.*/
	public void updateLayerTree() {
		for (Layer la : al_layers) {
			la.updateLayerTree();
		}
	}

	/** Find the ZDisplayable objects that intersect with the 3D roi defined by the first and last layers, and the area -all in world coordinates. */
	public ArrayList<ZDisplayable> find(final Layer first, final Layer last, final Area area) {
		final ArrayList<ZDisplayable> al = new ArrayList<ZDisplayable>();
		for (ZDisplayable zd : al_zdispl) {
			if (zd.intersects(area, first.getZ(), last.getZ())) {
				al.add(zd);
			}
		}
		return al;
	}

	/** A Bucket for the ZDisplayable parts that show in every Layer. */
	protected final class LayerBucket {
		protected final Bucket root;
		protected final HashMap<Displayable,HashSet<Bucket>> db_map = new HashMap<Displayable,HashSet<Bucket>>();

		LayerBucket(final Layer la) {
			this.root = new Bucket(0, 0, (int)(0.00005 + getLayerWidth()), (int)(0.00005 + getLayerHeight()), Bucket.getBucketSide(LayerSet.this, la));
			this.root.populate(LayerSet.this, la, this.db_map);
		}
	}

	/** For fast search. */
	protected HashMap<Layer,LayerBucket> lbucks = new HashMap<Layer,LayerBucket>();

	final private void addToBuckets(final Displayable zd, final int i) {
		synchronized (lbucks) {
			if (lbucks.isEmpty()) return;
			for (final Long lid : zd.getLayerIds()) {
				final Layer la = getLayer(lid); // map lookup
				final LayerBucket lb = lbucks.get(la);
				if (null == lb) {
					nbmsg(la);
					continue;
				}
				lb.root.put(i, zd, la, lb.db_map);
			}
		}
	}
	/** Recreate the buckets of every layer in which the {@link Displayable} has data. */
	final private void removeFromBuckets(final Displayable zd, final int old_stack_index) {
		synchronized (lbucks) {
			if (lbucks.isEmpty()) return;
			for (final Long lid : zd.getLayerIds()) {
				final Layer la = getLayer(lid);
				final LayerBucket lb = lbucks.get(la);
				if (null == lb) {
					nbmsg(la);
					continue;
				}
				recreateBuckets(getLayer(lid), false);
			}
		}
	}

	/** Recreate the buckets for all layers involved. */
	final private void removeFromBuckets(final Collection<ZDisplayable> zds) {
		synchronized (lbucks) {
			if (lbucks.isEmpty()) return;
			final Set<Layer> touched = new HashSet<Layer>();
			for (final ZDisplayable zd : zds) {
				touched.addAll(zd.getLayersWithData());
			}
			for (final Layer la : touched) {
				final LayerBucket lb = lbucks.remove(la);
				if (null == lb) {
					nbmsg(la);
					continue;
				}
				lbucks.put(la, new LayerBucket(la));
			}
		}
	}
	/** Used ONLY by move up/down/top/bottom. */
	final private void updateRangeInBuckets(final Displayable zd, final int i, final int j) {
		synchronized (lbucks) {
			if (lbucks.isEmpty()) return;
			for (final Long lid : zd.getLayerIds()) {
				final Layer la = getLayer(lid);
				final LayerBucket lb = lbucks.get(la);
				if (null == lb) {
					nbmsg(la);
					continue;
				}
				for (final Bucket bu : lb.db_map.get(zd)) {
					bu.updateRange(this, zd, i, j);
				}
			}
		}
	}

	/** Returns a copy of the list of ZDisplayable objects. */
	public ArrayList<ZDisplayable> getZDisplayables() { return new ArrayList<ZDisplayable>(al_zdispl); }

	/** Returns the real list of displayables, not a copy. If you modify this list, Thor may ground you with His lightning. */
	public ArrayList<ZDisplayable> getDisplayableList() {
		return al_zdispl;
	}

	public HashMap<Displayable, HashSet<Bucket>> getBucketMap(final Layer la) {
		synchronized (lbucks) {
			if (lbucks.isEmpty()) return null;
			final LayerBucket lb = lbucks.get(la);
			if (null == lb) {
				nbmsg(la);
				return null;
			}
			return lb.db_map;
		}
	}

	public void updateBucket(final Displayable d, final Layer layer) {
		synchronized (lbucks) {
			final LayerBucket lb = lbucks.get(layer);
			if (null != lb) lb.root.updatePosition(d, layer, lb.db_map);
		}
	}

	/** Recreate the ZDisplayable buckets, and also the Layer Displayable buckets if desired. */
	public void recreateBuckets(final boolean layer_buckets) {
		recreateBuckets(al_layers, layer_buckets);
	}

	/** Recreate the ZDisplayable buckets for {@param layer}, and also the {@link Layer} {@link Displayable} buckets if desired.
	 * @param layer The {@link Layer} to recreate {@link ZDisplayable} buckets for.
	 * @param layer_buckets Whether to also recreate the {@link Layer}-specific buckets for images and text labels.
	 */
	public void recreateBuckets(final Layer layer, final boolean layer_buckets) {
		LayerBucket lb = new LayerBucket(layer);
		synchronized (lbucks) {
			lbucks.put(layer, lb);
		}
		if (layer_buckets && null != layer.root) layer.recreateBuckets();
	}

	/** Regenerate the quad-tree bucket system for the ZDisplayable instances that have data at each of the given layers,
	 *  and optionally regenerate the buckets as well for the 2D Displayable instances of that layer as well. */
	public void recreateBuckets(final Collection<Layer> layers, final boolean layer_buckets) {
		final HashMap<Layer,LayerBucket> m = new HashMap<Layer,LayerBucket>();
		try {
			Process.progressive(layers, new TaskFactory<Layer,Object>() {
				@Override
				public Object process(final Layer layer) {
					LayerBucket lb = new LayerBucket(layer);
					synchronized (m) {
						m.put(layer, lb);
					}
					if (layer_buckets && null != layer.root) layer.recreateBuckets();
					return null;
				}
			}, Process.NUM_PROCESSORS -1); // works even when there is only 1 core, since it checks and fixes the '0' processors request
		} catch (Exception e) {
			IJError.print(e);
		}
		synchronized (lbucks) {
			lbucks.clear();
			lbucks.putAll(m);
		}
	}

	/** Checks only buckets for ZDisplayable, not any related to any layer. */
	public void checkBuckets() {
		synchronized (lbucks) {
			if (!lbucks.isEmpty()) return;
		}
		recreateBuckets(false);
	}

	/** Returns the minimal 2D bounding box for Displayables of class @param c in all layers. */
	public Rectangle getMinimalBoundingBox(final Class<?> c) {
		Rectangle r = null;
		for (final Layer la : al_layers) {
			if (null == r) r = la.getMinimalBoundingBox(c);
			else {
				Rectangle box = la.getMinimalBoundingBox(c); // may be null if Layer is empty
				if (null != box) r.add(box);
			}
		}
		return r;
	}

	/** Time vs DoStep. Not all steps may be specific for a single Displayable. */
	final private TreeMap<Long,DoStep> edit_history = new TreeMap<Long,DoStep>();

	/** The step representing the current diff state. */
	private long current_edit_time = 0;
	private DoStep current_edit_step = null;

	/** Displayable vs its own set of time vs DoStep, for quick access, for those edits that are specific of a Displayable.
	 *  It's necessary to set a ground, starting point for any Displayable whose data will be edited. */
	final private Map<Displayable,TreeMap<Long,DoStep>> dedits = new HashMap<Displayable,TreeMap<Long,DoStep>>();

	/** Time vs DoStep; as steps are removed from the end of edit_history, they are put here. */
	final private TreeMap<Long,DoStep> redo = new TreeMap<Long,DoStep>();

	/** Whether an initial step should be added or not. */
	final boolean prepareStep(final Object ob) {
		synchronized (edit_history) {
			if (0 == edit_history.size() || redo.size() > 0) return true;
			// Check if the last added entry contains the exact same elements and data
			DoStep step = edit_history.get(edit_history.lastKey());
			boolean b = step.isIdenticalTo(ob);
			//Utils.log2(b + " == prepareStep for " + ob);
			// If identical, don't prepare one!
			return !b;
		}
	}

	/** If last step is not a DoEdit "data" step for d, then call addDataEditStep(d). */
	boolean addPreDataEditStep(final Displayable d) {
		if (  null == current_edit_step
		  || (current_edit_step.getD() != d || !((DoEdit)current_edit_step).containsKey("data"))) {
			//Utils.log2("Adding pre-data edit step");
			//return addDataEditStep(d);
			return addEditStep(new Displayable.DoEdit(d).init(d, new String[]{"data"}));
		}
		return false;
	}

	/** A new undo step for the "data" field of Displayable d. */
	boolean addDataEditStep(final Displayable d) {
		//Utils.log2("Adding data edit step");
		// Adds "data", which contains width,height,affinetransform,links, and the data (points, areas, etc.)
		return addDataEditStep(d, new String[]{"data"});
	}
	/** A new undo step for any desired fields of Displayable d. */
	boolean addDataEditStep(final Displayable d, final String[] fields) {
		return addEditStep(new Displayable.DoEdit(d).init(d, fields));
	}

	/** A new undo step for the "data" field of all Displayable in the set. */
	public boolean addDataEditStep(final Set<? extends Displayable> ds) {
		return addDataEditStep(ds, new String[]{"data"});
	}

	boolean addDataEditStep(final Set<? extends Displayable> ds, final String[] fields) {
		final Displayable.DoEdits edits = new Displayable.DoEdits(ds);
		edits.init(fields);
		return addEditStep(edits);
	}

	/** Add an undo step for the transformations of all Displayable in the layer. */
	public void addTransformStep(final Layer layer) {
		addTransformStep(layer.getDisplayables());
	}
	public void addTransformStep(final List<Layer> layers) {
		final ArrayList<Displayable> all = new ArrayList<Displayable>();
		for (final Layer la : layers) all.addAll(la.getDisplayables());
		addTransformStep(all);
	}
	/** Add an undo step for the transformations of all Displayable in hs. */
	public void addTransformStep(final Collection<? extends Displayable> col) {
		//Utils.log2("Added transform step for col");
		addEditStep(new Displayable.DoTransforms().addAll(col));
	}
	/** Add an undo step for the transformations of all Displayable in col, with data as well (for Patch, data includes the CoordinateTransform). */
	public DoEdits addTransformStepWithData(final Collection<? extends Displayable> col) {
		if (col.isEmpty()) return null;
		final Set<? extends Displayable> hs = col instanceof Set<?> ? (Set<? extends Displayable>)col : new HashSet<Displayable>(col);
		final DoEdits step = new Displayable.DoEdits(hs).init(new String[]{"data", "at", "width", "height"});
		addEditStep(step);
		return step;
	}
	/** Includes all ZDisplayable that paint at any of the given layers. */
	public Collection<Displayable> addTransformStepWithDataForAll(final Collection<Layer> layers) {
		if (layers.isEmpty()) return Collections.emptyList();
		final Set<Displayable> hs = new HashSet<Displayable>();
		for (final Layer layer : layers) hs.addAll(layer.getDisplayables());
		for (final ZDisplayable zd : al_zdispl) {
			for (final Layer layer : layers) {
				if (zd.paintsAt(layer)) {
					hs.add((Displayable)zd);
					break;
				}
			}
		}
		addTransformStepWithData(hs);
		return hs;
	}
	/** Add an undo step for the transformations of all Displayable in all layers. */
	public void addTransformStep() {
		//Utils.log2("Added transform step for all");
		Displayable.DoTransforms dt = new Displayable.DoTransforms();
		for (final Layer la : al_layers) {
			dt.addAll(la.getDisplayables());
		}
		addEditStep(dt);
	}

	/** Add a step to undo the addition or deletion of one or more objects in this project and LayerSet. */
	public DoChangeTrees addChangeTreesStep() {
		DoChangeTrees step = new LayerSet.DoChangeTrees(this);
		if (prepareStep(step)) {
			Utils.log2("Added change trees step.");
			addEditStep(step);
		}
		return step;
	}
	/** Add a step to undo the addition or deletion of one or more objects in this project and LayerSet,
	 *  along with an arbitrary set of steps that may alter, for example the data. */
	public DoChangeTrees addChangeTreesStep(final Set<DoStep> dependents) {
		DoChangeTrees step = addChangeTreesStep();
		step.addDependents(dependents);
		addEditStep(step);
		return step;
	}
	/** For the Displayable contained in a Layer: their number, and their stack order. */
	public void addLayerContentStep(final Layer la) {
		DoStep step = new Layer.DoContentChange(la);
		if (prepareStep(step)) {
			Utils.log2("Added layer content step.");
			addEditStep(step);
		}
	}
	/** For the Z and thickness of a layer. */
	public void addLayerEditedStep(final Layer layer) {
		addEditStep(new Layer.DoEditLayer(layer));
	}
	/** For the Z and thickness of a list of layers. */
	public void addLayerEditedStep(final List<Layer> al) {
		addEditStep(new Layer.DoEditLayers(al));
	}

	public void addUndoStep(final DoStep step) {
		addEditStep(step);
	}

	boolean addEditStep(final DoStep step) {
		if (null == step || step.isEmpty()) {
			Utils.log2("Warning: can't add empty step " + step);
			return false;
		}

		synchronized (edit_history) {
			// Check if it's identical to current step
			if (step.isIdenticalTo(current_edit_step)) {
				//Utils.log2("Skipping identical undo step of class " + step.getClass() + ": " + step);
				return false;
			}

			// Store current in undo queue
			if (null != current_edit_step) {
				edit_history.put(current_edit_time, current_edit_step);
				// Store for speedy access, if its Displayable-specific:
				final Displayable d = current_edit_step.getD();
				if (null != d) {
					TreeMap<Long,DoStep> edits = dedits.get(d);
					if (null == edits) {
						edits = new TreeMap<Long,DoStep>();
						dedits.put(d, edits);
					}
					edits.put(current_edit_time, current_edit_step);
				}

				// prune if too large
				while (edit_history.size() > project.getProperty("n_undo_steps", 32)) {
					long t = edit_history.firstKey();
					DoStep st = edit_history.remove(t);
					if (null != st.getD()) {
						TreeMap<Long,DoStep> m = dedits.get(st.getD());
						m.remove(t);
						if (0 == m.size()) dedits.remove(st.getD());
					}
				}
			}

			// Set step as current
			current_edit_time = System.currentTimeMillis();
			current_edit_step = step;

			// Bye bye redo! Can't branch.
			redo.clear();
		}

		return true;
	}

	public boolean canUndo() {
		return edit_history.size() > 0;
	}
	public boolean canRedo() {
		return redo.size() > 0 || null != current_edit_step;
	}

	/** Undoes one step of the ongoing transformation history, otherwise of the overall LayerSet history. */
	public boolean undoOneStep() {
		synchronized (edit_history) {
			if (0 == edit_history.size()) {
				Utils.logAll("Empty undo history!");
				return false;
			}

			//Utils.log2("Undoing one step");

			// Add current (if any) to redo queue
			if (null != current_edit_step) {
				redo.put(current_edit_time, current_edit_step);
			}

			// Remove last step from undo queue, and set it as current
			current_edit_time = edit_history.lastKey();
			current_edit_step = edit_history.remove(current_edit_time);

			// Remove as well from dedits
			if (null != current_edit_step.getD()) {
				dedits.get(current_edit_step.getD()).remove(current_edit_time);
			}

			if (!current_edit_step.apply(DoStep.UNDO)) {
				Utils.log("Undo: could not apply step!");
				return false;
			}

			Utils.log("Undoing " + current_edit_step.getClass().getSimpleName());

			Display.updateVisibleTabs(project);
		}
		return true;
	}

	protected boolean removeLastUndoStep() {
		synchronized (edit_history) {
			if (edit_history.isEmpty()) return false;
			final long time = edit_history.lastKey();
			final DoStep step = edit_history.remove(time);
			if (null != step.getD()) dedits.get(step.getD()).remove(time);
			// shift current
			if (step == current_edit_step) {
				current_edit_time = edit_history.lastKey();
				current_edit_step = edit_history.get(current_edit_time);
			}
		}
		return true;
	}

	/** Redoes one step of the ongoing transformation history, otherwise of the overall LayerSet history. */
	public boolean redoOneStep() {
		synchronized (edit_history) {
			if (0 == redo.size()) {
				Utils.logAll("Empty redo history!");
				if (null != current_edit_step) {
					return current_edit_step.apply(DoStep.REDO);
				}
				return false;
			}

			//Utils.log2("Redoing one step");

			// Add current (if any) to undo queue
			if (null != current_edit_step) {
				edit_history.put(current_edit_time, current_edit_step);
				if (null != current_edit_step.getD()) {
					dedits.get(current_edit_step.getD()).put(current_edit_time, current_edit_step);
				}
			}

			// Remove one step from undo queue and set it as current
			current_edit_time = redo.firstKey();
			current_edit_step = redo.remove(current_edit_time);

			if (!current_edit_step.apply(DoStep.REDO)) {
				Utils.log("Undo: could not apply step!");
				return false;
			}

			Utils.log("Redoing " + current_edit_step.getClass().getSimpleName());

			Display.updateVisibleTabs(project);
		}
		return true;
	}

	static public void applyTransforms(final Map<Displayable,AffineTransform> m) {
		for (final Map.Entry<Displayable,AffineTransform> e : m.entrySet()) {
			e.getKey().setAffineTransform(e.getValue()); // updates buckets
		}
	}

	static {
		// Undo background tasks: should be done in background threads,
		// but on attempting to undo/redo, the undo/redo should wait
		// until all tasks are done. For example, updating mipmaps when
		// undoing/redoing min/max or CoordinateTransform.
		// This could be done with futures: spawn and do in the
		// background, but on redo/undo, call for the Future return
		// value, which will block until there is one to return.
		// Since blocking would block the EventDispatchThread, just refuse to undo/redo and notify the user.
		//
		// TODO
	}

	/** Keeps the width,height of a LayerSet and the AffineTransform of every Displayable in it. */
	static private class DoResizeLayerSet implements DoStep {

		final LayerSet ls;
		final HashMap<Displayable,AffineTransform> affines;
		final float width, height;

		DoResizeLayerSet(final LayerSet ls) {
			this.ls = ls;
			this.width = ls.layer_width;
			this.height = ls.layer_height;
			this.affines = new HashMap<Displayable,AffineTransform>();

			final ArrayList<Displayable> col = ls.getDisplayables(); // it's a new list
			col.addAll(ls.getZDisplayables());
			for (final Displayable d : col) {
				this.affines.put(d, d.getAffineTransformCopy());
			}
		}
		public boolean isIdenticalTo(final Object ob) {
			if (!(ob instanceof LayerSet)) return false;
			final LayerSet layerset = (LayerSet) ob;
			if (layerset.layer_width != this.width || layerset.height != this.height || layerset != this.ls) return false;
			final ArrayList<Displayable> col = ls.getDisplayables();
			col.addAll(ls.getZDisplayables());
			for (final Displayable d : col) {
				final AffineTransform aff = this.affines.get(d);
				if (null == aff) return false;
				if (!aff.equals(d.getAffineTransform())) return false;
			}
			return true;
		}

		public boolean apply(int action) {
			ls.layer_width = width;
			ls.layer_height = height;
			for (final Map.Entry<Displayable,AffineTransform> e : affines.entrySet()) {
				e.getKey().getAffineTransform().setTransform(e.getValue());
			}

			final boolean dobuckets;
			synchronized (ls.lbucks) {
				dobuckets = ls.lbucks.isEmpty();
			}
			if (dobuckets) ls.recreateBuckets(true);

			Display.updateSelection();
			Display.update(ls); //so it's not left out painted beyond borders
			return true;
		}
		public boolean isEmpty() { return false; }
		public Displayable getD() { return null; }
	}

	/** Records the state of the LayerSet.al_layers, each Layer.al_displayables and all the trees and unique types of Project. */
	static protected class DoChangeTrees implements DoStep {
		final LayerSet ls;
		final HashMap<Thing,Boolean> ttree_exp, ptree_exp, ltree_exp;
		final Thing troot, proot, lroot;
		final ArrayList<Layer> all_layers;
		final HashMap<Layer,ArrayList<Displayable>> all_displ;
		final ArrayList<ZDisplayable> all_zdispl;
		final HashMap<Displayable,Set<Displayable>> links;
		final HashMap<Long,Layer> idlayers;
		final HashMap<Layer,Integer> layerindices;

		HashSet<DoStep> dependents = null;

		// TODO: does not consider recursive LayerSets!
		public DoChangeTrees(final LayerSet ls) {
			this.ls = ls;
			final Project p = ls.getProject();

			this.ttree_exp = new HashMap<Thing,Boolean>();
			this.troot = p.getTemplateTree().duplicate(this.ttree_exp);
			this.ptree_exp = new HashMap<Thing,Boolean>();
			this.proot = p.getProjectTree().duplicate(this.ptree_exp);
			this.ltree_exp = new HashMap<Thing,Boolean>();
			this.lroot = p.getLayerTree().duplicate(this.ltree_exp);

			this.all_layers = ls.getLayers(); // a copy of the list, but each object is the running instance
			this.all_zdispl = ls.getZDisplayables(); // idem
			this.idlayers = new HashMap<Long,Layer>(ls.idlayers);
			synchronized (ls.layerindices) {
				this.layerindices = new HashMap<Layer,Integer>(ls.layerindices);
			}

			this.links = new HashMap<Displayable,Set<Displayable>>();
			for (final ZDisplayable zd : this.all_zdispl) {
				this.links.put(zd, zd.hs_linked); // LayerSet is a Displayable
			}

			this.all_displ = new HashMap<Layer,ArrayList<Displayable>>();
			for (final Layer layer : all_layers) {
				final ArrayList<Displayable> al = layer.getDisplayables(); // a copy
				this.all_displ.put(layer, al);
				for (final Displayable d : al) {
					this.links.put(d, null == d.hs_linked ? null : new HashSet<Displayable>(d.hs_linked));
				}
			}
		}
		public Displayable getD() { return null; }
		public boolean isEmpty() { return false; }
		public boolean isIdenticalTo(final Object ob) {
			// TODO
			return false;
		}
		public boolean apply(int action) {
			// Replace all trees
			final Project p = ls.getProject();
			p.resetRootTemplateThing((TemplateThing)this.troot, ttree_exp);
			p.resetRootProjectThing((ProjectThing)this.proot, ptree_exp);
			p.resetRootLayerThing((LayerThing)this.lroot, ltree_exp);
			
			// Replace all layers
			ls.al_layers.clear();
			ls.al_layers.addAll(this.all_layers);
			synchronized (ls.IDLAYERS_WRITE_LOCK) {
				ls.idlayers = new HashMap<Long,Layer>(this.idlayers);
			}
			synchronized (ls.layerindices) {
				ls.layerindices.clear();
				ls.layerindices.putAll(this.layerindices);
			}

			// Replace all Displayable in each Layer
			for (final Map.Entry<Layer,ArrayList<Displayable>> e : all_displ.entrySet()) {
				// Acquire pointer to the actual instance list in each Layer
				final ArrayList<Displayable> al = e.getKey().getDisplayableList(); // the real one!
				// Create a list to contain those Displayable present in old list but not in list to use now
				final HashSet<Displayable> diff = new HashSet<Displayable>(al); // create with all Displayable of old list
				diff.removeAll(e.getValue()); // remove all Displayable present in list to use now, to leave the diff or remainder only
				// Clear current list
				al.clear();
				// Insert all to the current list
				al.addAll(e.getValue());
				// Add to remove-on-shutdown queue all those Patch no longer in the list to use now:
				for (final Displayable d : diff) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().tagForMipmapRemoval((Patch)d, true);
					}
				}
				// Remove from queue all those Patch in the list to use now:
				for (final Displayable d : al) {
					if (d.getClass() == Patch.class) {
						d.getProject().getLoader().tagForMipmapRemoval((Patch)d, false);
					}
				}
			}

			// Replace all ZDisplayable
			ls.al_zdispl.clear();
			ls.al_zdispl.addAll(this.all_zdispl);

			// Replace all links
			for (final Map.Entry<Displayable,Set<Displayable>> e : this.links.entrySet()) {
				final Set<Displayable> hs = e.getKey().hs_linked;
				if (null != hs) {
					final Set<Displayable> hs2 = e.getValue();
					if (null == hs2) e.getKey().hs_linked = null;
					else {
						hs.clear();
						hs.addAll(hs2);
					}
				}
			}

			// Invoke dependents
			if (null != dependents) for (DoStep step : dependents) step.apply(action);

			ls.recreateBuckets(true);

			Display.clearSelection(ls.project);
			Display.update(ls, false);

			return true;
		}

		synchronized public void addDependents(Set<DoStep> dep) {
			if (null == this.dependents) this.dependents = new HashSet<DoStep>();
			this.dependents.addAll(dep);
		}
	}

	/** To undo moving up/down/top/bottom. */
	public DoStep createUndoMoveStep(final Displayable d) {
		return d instanceof ZDisplayable ?
			new LayerSet.DoMoveZDisplayable(this)
			: new Layer.DoMoveDisplayable(d.getLayer());
	}

	/** To undo moving up/down/top/bottom. */
	public void addUndoMoveStep(final Displayable d) {
		addUndoStep(createUndoMoveStep(d));
	}
	
	static protected class DoMoveZDisplayable implements DoStep {
		final ArrayList<ZDisplayable> al_zdispl;
		final LayerSet ls;
		HashSet<DoStep> dependents = null;
		DoMoveZDisplayable(final LayerSet ls) {
			this.ls = ls;
			this.al_zdispl = new ArrayList<ZDisplayable>(ls.al_zdispl);
		}
		@Override
		public boolean apply(int action) {
			// Replace all ZDisplayable
			ls.al_zdispl.clear();
			ls.al_zdispl.addAll(this.al_zdispl);
			Display.update(ls, false);
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
		public boolean isIdenticalTo(Object ob) {
			if (!(ob instanceof DoMoveZDisplayable)) return false;
			final DoMoveZDisplayable dmz = (DoMoveZDisplayable)ob;
			if (dmz.ls != this.ls) return false;
			if (dmz.al_zdispl.size() != this.al_zdispl.size()) return false;
			for (int i=0; i<this.al_zdispl.size(); ++i) {
				if (dmz.al_zdispl.get(i) != this.al_zdispl.get(i)) return false;
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
		Overlay old = this.overlay;
		this.overlay = o;
		return old;
	}

	private final HashMap<DisplayCanvas.ScreenshotProperties,DisplayCanvas.Screenshot> offscreens = new HashMap<DisplayCanvas.ScreenshotProperties,DisplayCanvas.Screenshot>();
	private final HashMap<Layer,HashSet<DisplayCanvas.Screenshot>> offscreens2 = new HashMap<Layer,HashSet<DisplayCanvas.Screenshot>>();

	final DisplayCanvas.Screenshot getScreenshot(final DisplayCanvas.ScreenshotProperties props) {
		synchronized (offscreens) {
			return offscreens.get(props);
		}
	}

	final private void putO2(final Layer la, final DisplayCanvas.Screenshot sc) {
		HashSet<DisplayCanvas.Screenshot> hs = offscreens2.get(la);
		if (null == hs) {
			hs = new HashSet<DisplayCanvas.Screenshot>();
			offscreens2.put(la, hs);
		}
		hs.add(sc);
	}
	final private void removeO2(final DisplayCanvas.Screenshot sc) {
		HashSet<DisplayCanvas.Screenshot> hs = offscreens2.get(sc.layer);
		if (null == hs) return;
		hs.remove(sc);
	}

	final void storeScreenshot(DisplayCanvas.Screenshot s) {
		synchronized(offscreens) {
			offscreens.put(s.props, s);
			putO2(s.layer, s);
		}
	}
	final void clearScreenshots() {
		synchronized (offscreens) {
			for (final DisplayCanvas.Screenshot s : offscreens.values()) {
				s.flush();
			}
			offscreens.clear();
			offscreens2.clear();
		}
	}
	final void trimScreenshots() {
		synchronized(offscreens) {
			if (offscreens.size() > 1000) {
				TreeMap<Long,DisplayCanvas.Screenshot> m = new TreeMap<Long,DisplayCanvas.Screenshot>();
				for (final DisplayCanvas.Screenshot s : offscreens.values()) {
					m.put(s.born, s);
				}
				offscreens.clear();
				offscreens2.clear();
				ArrayList<Long> t = new ArrayList<Long>(m.keySet());
				for (final DisplayCanvas.Screenshot sc : m.subMap(m.firstKey(), t.get(t.size()/2)).values()) {
					offscreens.put(sc.props, sc);
					putO2(sc.layer, sc);
				}
				// not flushing: they will get thrown out eventually
			}
		}
	}
	final void removeFromOffscreens(final DisplayCanvas.Screenshot sc) {
		synchronized (offscreens) {
			offscreens.remove(sc.props);
			removeO2(sc);
		}
	}
	public final void removeFromOffscreens(final Layer la) {
		synchronized (offscreens) {
			final HashSet<DisplayCanvas.Screenshot> hs = offscreens2.remove(la);
			if (null != hs) {
				for (final DisplayCanvas.Screenshot sc : hs) {
					offscreens.remove(sc.props);
				}
			}
		}
	}
	final void removeFromOffscreens(final ZDisplayable zd) {
		synchronized (offscreens) {
			// Throw away any cached that intersect the zd
			final Rectangle box = zd.getBoundingBox();
			for (final Iterator<DisplayCanvas.Screenshot> it = offscreens.values().iterator(); it.hasNext(); ) {
				final DisplayCanvas.Screenshot sc = it.next();
				if (box.intersects(sc.props.srcRect)) {
					it.remove();
					final HashSet<DisplayCanvas.Screenshot> hs = offscreens2.get(sc.layer);
					if (null != hs) hs.remove(sc.props);
				}
			}
		}
	}

	final boolean containsScreenshot(final DisplayCanvas.Screenshot sc) {
		synchronized (offscreens) {
			return offscreens.containsKey(sc.props);
		}
	}

	/** Find all java.awt.geom.Area in layer that intersect with box, if visible.
	 *  Areas are returned as they are, with coords local to the Displayable they come from.
	 *  Modifying the Area instances will modify the actual data in the AreaContainer Displayable. */
	protected Map<Displayable,List<Area>> findAreas(final Layer layer, final Rectangle box, final boolean visible) {
		final Map<Displayable,List<Area>> m = new HashMap<Displayable,List<Area>>();
		for (final Displayable zd : findZDisplayables(layer, box, visible)) {
			if (!(zd instanceof AreaContainer)) continue;
			List<Area> a = ((AreaContainer)zd).getAreas(layer, box);
			if (null == a) continue;
			m.put(zd, a);
		}
		return m;
	}

	/** A set of unique tags, retrievable by their own identity. */
	protected final Map<Integer,HashMap<String,Tag>> tags = new HashMap<Integer,HashMap<String,Tag>>();

	{
		final Tag TODO = new Tag("TODO", KeyEvent.VK_T),
				  UNCERTAIN_END = new Tag("Uncertain end", KeyEvent.VK_U);
		final HashMap<String,Tag> m1 = new HashMap<String,Tag>(),
							      m2 = new HashMap<String,Tag>();
		m1.put(TODO.toString(), TODO);
		m2.put(UNCERTAIN_END.toString(), UNCERTAIN_END);
		tags.put(KeyEvent.VK_T, m1);
		tags.put(KeyEvent.VK_U, m2);
	}

	/** Returns an existing immutable Tag instance if already there, or stores a new one and returns it. */
	public Tag putTag(final String tag, final int keyCode) {
		if (null == tag) return null;
		synchronized (tags) {
			HashMap<String,Tag> ts = tags.get(keyCode);
			if (null == ts) {
				ts = new HashMap<String,Tag>();
				tags.put(keyCode, ts);
			}
			final Tag t = new Tag(tag, keyCode);
			final Tag existing = ts.get(t);
			if (null == existing) {
				ts.put(t.toString(), t);
				return t;
			} else {
				return existing;
			}
		}
	}

	/** If there aren't any tags for keyCode, returns an empty TreeSet. */
	@SuppressWarnings("unchecked")
	public TreeSet<Tag> getTags(final int keyCode) {
		synchronized (tags) {
			final HashMap<String,Tag> ts = tags.get(keyCode);
			return new TreeSet<Tag>(null == ts ? Collections.EMPTY_SET :
												 KeyEvent.VK_R == keyCode ? filterReviewTags(ts.values()) :
													 						ts.values());
		}
	}
	
	private final Collection<Tag> filterReviewTags(final Collection<Tag> ts) {
		final ArrayList<Tag> a = new ArrayList<Tag>();
		for (final Tag tag : ts) {
			if ('#' == tag.toString().charAt(0)) continue;
			else a.add(tag);
		}
		return a;
	}

	protected Tag askForNewTag(final int keyCode) {
		GenericDialog gd = new GenericDialog("Define new tag");
		gd.addMessage("Define new tag for key: " + ((char)keyCode));
		TreeSet<Tag> ts = getTags(keyCode);
		gd.addStringField("New tag:", "", 40);
		if (null != ts && ts.size() > 0) {
			String[] names = new String[ts.size()];
			int next = 0;
			for (Tag t : ts) names[next++] = t.toString();
			gd.addChoice("Existing tags for " + ((char)keyCode) + ":", names, names[0]);
		}
		gd.showDialog();
		if (gd.wasCanceled()) return null;
		String tag = gd.getNextString().trim();
		if (0 == tag.length()) {
			Utils.logAll("Invalid tag " + tag);
			return null;
		}
		return putTag(tag, keyCode);
	}

	/** Returns false if the dialog was canceled or there wasn't any tag to remove. */
	protected boolean askToRemoveTag(final int keyCode) {
		TreeSet<Tag> ts = getTags(keyCode);
		if (null == ts || ts.isEmpty()) return false;
		String[] tags = new String[ts.size()];
		int next = 0;
		for (Tag t : ts) tags[next++] = t.toString();
		GenericDialog gd = new GenericDialog("Remove tag");
		gd.addMessage("Remove a tag for key: " + ((char)keyCode));
		gd.addChoice("Remove:", tags, tags[0]);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		String tag = gd.getNextChoice();
		removeTag(tag, keyCode);
		return true;
	}

	/** Removes the tag from the list of possible tags, and then from wherever it has been assigned. The @param tag is duck-typed. */
	public void removeTag(final String tag, final int keyCode) {
		removeTag(new Tag(tag, keyCode));
	}
	public void removeTag(final Tag t) {
		synchronized (tags) {
			HashMap<String,Tag> ts = tags.get(t.getKeyCode());
			if (null == ts) return;
			ts.remove(t.toString());
		}
		for (final Displayable d : getDisplayables()) {
			d.removeTag(t);
		}
		for (final ZDisplayable zd : al_zdispl) {
			zd.removeTag(t);
		}
		Display.repaint(this);
	}

	public void removeAllTags() {
		// the easy and unperformant way ... I have better things to do
		for (final HashMap<String,Tag> m : tags.values()) {
			for (final Tag t : m.values()) {
				removeTag(t);
			}
		}
	}

	public String exportTags() {
		StringBuilder sb = new StringBuilder("<tags>\n");
		for (final Map.Entry<Integer,HashMap<String,Tag>> e : tags.entrySet()) {
			final char key = (char)e.getKey().intValue();
			for (final Tag t : e.getValue().values()) {
				sb.append(" <tag key=\"").append(key).append("\" val=\"").append(t.toString()).append("\" />\n");
			}
		}
		return sb.append("</tags>").toString();
	}

	public void importTags(String path, boolean replace) {
		HashMap<Integer,HashMap<String,Tag>> backup = new HashMap<Integer,HashMap<String,Tag>>(this.tags); // copy!
		InputStream istream = null;
		try {
			if (replace) removeAllTags();
			SAXParserFactory f = SAXParserFactory.newInstance();
			f.setValidating(false);
			SAXParser parser = f.newSAXParser();
			istream = Utils.createStream(path);
			parser.parse(new InputSource(istream), new TagsParser());
		} catch (Throwable t) {
			IJError.print(t);
			// restore:
			this.tags.clear();
			this.tags.putAll(backup);
			// no undo for all potentially removed tags ...
		} finally {
			try {
				if (null != istream) istream.close();
			} catch (Exception e) {}
		}
	}
	
	private class TagsParser extends DefaultHandler {
		public void startElement(String uri, String localName, String qName, Attributes attributes) {
			if (!"tag".equals(qName.toLowerCase())) return;
			final HashMap<String,String> m = new HashMap<String,String>();
			for (int i=attributes.getLength() -1; i>-1; i--) {
				m.put(attributes.getQName(i).toLowerCase(), attributes.getValue(i));
			}
			final String key = m.get("key"),
				     content = m.get("val");
			if (null == key || key.length() > 1 || Character.isDigit(key.charAt(0)) || null == content) {
				Utils.log("Ignoring invalid tag with key '" + key + "' and value '" + content + "'");
				return;
			}
			putTag(content, (int)key.charAt(0));
		}
	}


	// ==== GET ZDisplayable objects ====
	//
	//  ESSENTIALLY, a filter operation on the al_zdispl list.

	public ArrayList<ZDisplayable> getZDisplayables(final Class<?> c) {
		return getZDisplayables(c, false);
	}

	/** Returns a list of ZDisplayable of class c only.
	 *  If @param instance_of, use c.isInstance(...) instead of class equality. */
	public ArrayList<ZDisplayable> getZDisplayables(final Class<?> c, final boolean instance_of) {
		final ArrayList<ZDisplayable> al = new ArrayList<ZDisplayable>();
		if (null == c) return al;
		if (Displayable.class == c || ZDisplayable.class == c) {
			al.addAll(al_zdispl);
			return al;
		}
		if (instance_of) {
			for (final ZDisplayable zd : al_zdispl) {
				if (c.isInstance(zd)) al.add(zd);
			}
		} else {
			for (final ZDisplayable zd : al_zdispl) {
				if (zd.getClass() == c) al.add(zd);
			}
		}
		return al;
	}

	// FILTER operations but also by an Area in a given Layer:

	/** Use method findZDisplayables(...) instead. */
	@Deprecated
	public ArrayList<ZDisplayable> getZDisplayables(final Class<?> c, final Layer layer, final Area aroi, final boolean visible_only) {
		return getZDisplayables(c, layer, aroi, visible_only, false);
	}

	/** Use method findZDisplayables(...) instead. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Deprecated
	public ArrayList<ZDisplayable> getZDisplayables(final Class<?> c, final Layer layer, final Area aroi, final boolean visible_only, final boolean instance_of) {
		if (!ZDisplayable.class.isAssignableFrom(c)) return new ArrayList<ZDisplayable>();
		return new ArrayList<ZDisplayable>((Collection<ZDisplayable>)(Collection)findZDisplayables(c, layer, aroi, visible_only, instance_of));
	}


	// ============== FIND Displayable or ZDisplayable onjects ============

	/** Find any Displayable or ZDisplayable objects of class C which intersect with the Area @param aroi. If @param visible_only, then only those that are not hidden. */
	public ArrayList<Displayable> find(final Class<?> c, final Layer layer, final Area aroi, final boolean visible_only) {
		return find(c, layer, aroi, visible_only, false);
	}

	/** Find any Displayable or ZDisplayable objects of class C which intersect with the Area @param aroi. If @param visible_only, then only those that are not hidden. If @param instance_of is true, then classes are not check by equality but by instanceof. */
	public ArrayList<Displayable> find(final Class<?> c, final Layer layer, final Area aroi, final boolean visible_only, final boolean instance_of) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (!ZDisplayable.class.isAssignableFrom(c)) {
			al.addAll(layer.getDisplayables(c, aroi, visible_only, instance_of));
		}
		al.addAll(findZDisplayables(c, layer, aroi, visible_only, instance_of));
		return al;
	}

	public ArrayList<Displayable> find(final Class<?> c, final Layer layer, final int x, final int y, final boolean visible_only) {
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		if (!ZDisplayable.class.isAssignableFrom(c)) {
			al.addAll(layer.find(c, x, y, visible_only));
		}
		al.addAll(findZDisplayables(c, layer, x, y, visible_only));
		return al;
	}


	// ======== FIND ZDisplayable only =======

	/** Find ZDisplayable objects that contain the point x,y in the given layer. */
	public Collection<Displayable> findZDisplayables(final Layer layer, final int x, final int y, final boolean visible_only) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.find(x, y, layer, visible_only);
		else nbmsg(layer);

		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (zd.contains(layer, x, y)) al.add(zd);
		}
		return al;
	}

	/** Find ZDisplayable objects of Class c that contain the point x,y in the given layer. */
	public Collection<Displayable> findZDisplayables(final Class<?> c, final Layer layer, final int x, final int y, final boolean visible_only) {
		return findZDisplayables(c, layer, x, y, visible_only, false);
	}
	/** Find ZDisplayable objects of Class c that contain the point x,y in the given layer. */
	public Collection<Displayable> findZDisplayables(final Class<?> c, final Layer layer, final int x, final int y, final boolean visible_only, final boolean instance_of) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.find(c, x, y, layer, visible_only, instance_of);
		else nbmsg(layer);

		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (zd.getClass() == c && zd.contains(layer, x, y)) al.add(zd);
		}
		return al;
	}

	public Collection<Displayable> findZDisplayables(final Class<?> c, final Layer layer, final Rectangle r, final boolean visible_only) {
		return findZDisplayables(c, layer, r, visible_only, false);
	}
	
	/** Find ZDisplayable objects of the given class that intersect the given rectangle in the given layer. */
	public Collection<Displayable> findZDisplayables(final Class<?> c, final Layer layer, final Rectangle r, final boolean visible_only, final boolean instance_of) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.find(c, r, layer, visible_only, instance_of);
		else nbmsg(layer);

		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (instance_of && !c.isInstance(zd)) continue;
			else if (zd.getClass() != c) continue;
			if (zd.getBounds(null, layer).intersects(r)) al.add(zd);
		}
		return al;
	}
	/** Find ZDisplayable objects of the given class that intersect the given area in the given layer.
	 *  If @param instance_of is true, use c.isAssignableFrom instead of class equality. */
	public Collection<Displayable> findZDisplayables(final Class<?> c, final Layer layer, final Area aroi, final boolean visible_only, final boolean instance_of) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.find(c, aroi, layer, visible_only, instance_of);
		else nbmsg(layer);

		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (visible_only && !zd.isVisible()) continue;
			if (instance_of) {
				if (!c.isAssignableFrom(zd.getClass())) continue;
			} else if (zd.getClass() != c) continue;
			if (zd.intersects(layer, aroi)) al.add(zd);
		}
		return al;
	}
	/** Find ZDisplayable objects that intersect the given rectangle in the given layer. */
	public Collection<Displayable> findZDisplayables(final Layer layer, final Rectangle r, final boolean visible_only) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.find(r, layer, visible_only);
		else nbmsg(layer);

		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (visible_only && !zd.isVisible()) continue;
			if (zd.getBounds(null, layer).intersects(r)) al.add(zd);
		}
		return al;
	}

	/** Find ZDisplayable objects that intersect the given rectangle in the given layer.
	 *  May return false positives but never false negatives. */
	public Collection<Displayable> roughlyFindZDisplayables(final Layer layer, final Rectangle r, final boolean visible_only) {
		final LayerBucket lb;
		synchronized (lbucks) {
			lb = lbucks.get(layer);
		}
		if (null != lb) return lb.root.roughlyFind(r, layer, visible_only);
		else nbmsg(layer);

		// Else, linear:
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final ZDisplayable zd : al_zdispl) {
			if (visible_only && !zd.isVisible()) continue;
			if (zd.getBounds(null, layer).intersects(r)) al.add(zd);
		}
		return al;
	}

	private static final void nbmsg(final Layer la) {
		Utils.log2("No buckets for layer " + la);
	}

	/** Get all Displayable or ZDisplayable of the given class.
	 *  Classes are tested by equality, except for ZDisplayable.class.
	 *  Will also consider Displayable.class and subclasses in
	 *  a similar fashion, by calling Layer.getAll(c). */
	@SuppressWarnings("unchecked")
	public<T extends Displayable> List<T> getAll(final Class<T> c) {
		final ArrayList<T> al = new ArrayList<T>();
		if (null == c) return al;
		if (ZDisplayable.class == c) {
			al.addAll((Collection<T>)al_zdispl);
		} else if (ZDisplayable.class.isAssignableFrom(c)) {
			for (final ZDisplayable d : al_zdispl) {
				if (d.getClass() == c) al.add((T)d);
			}
		} else if (Displayable.class == c) {
			for (final Layer la : al_layers) {
				al.addAll((Collection<T>)la.getDisplayables());
			}
			al.addAll((Collection<T>)al_zdispl);
		} else if (Displayable.class.isAssignableFrom(c)) {
			for (final Layer la : al_layers) {
				al.addAll(la.getAll(c));
			}
		}
		return al;
	}
}
