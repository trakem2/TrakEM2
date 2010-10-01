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


import fiji.geom.AreaCalculations;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.Project;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.vector.VectorString2D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.vecmath.Point3f;

import amira.AmiraMeshEncoder;

/** A list of brush painted areas similar to a set of labelfields in Amira.
 * 
 * For each layer where painting has been done, there is an entry in the ht_areas HashMap that contains the layer's id as a Long, and a java.awt.geom.Area object.
 * All Area objects are local to this AreaList's AffineTransform.
 */
public class AreaList extends ZDisplayable implements AreaContainer, VectorData {

	/** Contains the table of layer ids and their associated Area object.*/
	private HashMap<Long,Area> ht_areas = new HashMap<Long,Area>();

	/** Flag to signal dynamic loading from the database for the Area of a given layer id in the ht_areas HashMap. */
	static private final Area UNLOADED = new Area();

	/** Paint as outlines (false) or as solid areas (true; default, with a default alpha of 0.4f).*/
	private boolean fill_paint = true;

	public AreaList(Project project, String title, double x, double y) {
		super(project, title, x, y);
		this.alpha = AreaWrapper.PP.default_alpha;
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public AreaList(final Project project, final long id, HashMap<String,String> ht_attributes, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attributes, ht_links);
		// read the fill_paint
		Object ob_data = ht_attributes.get("fill_paint");
		try {
			if (null != ob_data) this.fill_paint = "true".equals(((String)ob_data).trim().toLowerCase()); // fails: //Boolean.getBoolean((String)ob_data);
		} catch (Exception e) {
			Utils.log("AreaList: could not read fill_paint value from XML:" + e);
		}
	}

	/** Reconstruct from the database. */
	public AreaList(Project project, long id, String title, float width, float height, float alpha, boolean visible, Color color, boolean locked, ArrayList<Long> al_ul, AffineTransform at) { // al_ul contains Long() wrapping layer ids
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		for (final Long lid : al_ul) {
			ht_areas.put(lid, AreaList.UNLOADED); // assumes al_ul contains only Long instances wrapping layer_id long values
		}
	}

	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		//arrange transparency
		Composite original_composite = null;
		try {
			if (layer_set.color_cues) {
				original_composite = g.getComposite();
				Color c = Color.red;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(alpha, 0.25f)));
				for (final Layer la : layers) {
					if (active_layer == la) {
						c = Color.blue;
						continue;
					}
					Area area = ht_areas.get(la.getId());
					if (null == area) continue;
					if (AreaList.UNLOADED == area) {
						area = loadLayer(la.getId());
						if (null == area) continue;
					}
					g.setColor(c);

					if (fill_paint) g.fill(area.createTransformedArea(this.at));
					else 		g.draw(area.createTransformedArea(this.at));  // the contour only
				}
				if (1.0f == alpha) g.setComposite(original_composite);
			}
			if (alpha != 1.0f) {
				if (null == original_composite) original_composite = g.getComposite();
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			}

			// The active layer, on top:
			if (null != aw) {
				aw.paint(g, this.at, fill_paint, this.color);
			} else {
				Area area = ht_areas.get(active_layer.getId());
				if (null == area) return;
				if (AreaList.UNLOADED == area) {
					area = loadLayer(active_layer.getId());
					if (null == area) return;
				}
				g.setColor(this.color);

				if (fill_paint) g.fill(area.createTransformedArea(this.at));
				else 		g.draw(area.createTransformedArea(this.at));  // the contour only
			}
		} finally {
			//Transparency: fix alpha composite back to original.
			if (null != original_composite) {
				g.setComposite(original_composite);
			}
		}
	}

	public void transformPoints(Layer layer, double dx, double dy, double rot, double xo, double yo) {
		Utils.log("AreaList.transformPoints: not implemented yet.");
	}

	/** Returns the layer of lowest Z coordinate Layer where this ZDisplayable has a point in. */
	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer first_layer = null;
		for (final Long lid : ht_areas.keySet()) {
			final Layer la = this.layer_set.getLayer(lid.longValue());
			double z = la.getZ();
			if (z < min_z) {
				min_z = z;
				first_layer = la;
			}
		}
		return first_layer;
	}

	/** Returns the layer of highest Z coordinate Layer where this ZDisplayable has a point in. */
	public Layer getLastLayer() {
		double max_z = -Double.MAX_VALUE;
		Layer last_layer = null;
		for (final Long lid : ht_areas.keySet()) {
			Layer la = this.layer_set.getLayer(lid.longValue());
			double z = la.getZ();
			if (z > max_z) {
				max_z = z;
				last_layer = la;
			}
		}
		return last_layer;
	} // I do REALLY miss Lisp macros. Writting the above two methods in a lispy way would make the java code unreadable

	/** Get the range of layers betweeh the first and last layers in which this AreaList paints to. */
	public List<Layer> getLayerRange() {
		return layer_set.getLayers(getFirstLayer(), getLastLayer());
	}

	public boolean linkPatches() {
		unlinkAll(Patch.class);
		// cheap way: intersection of the patches' bounding box with the area
		Rectangle r = new Rectangle();
		boolean must_lock = false;
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			Layer la = this.layer_set.getLayer(e.getKey());
			if (null == la) {
				Utils.log2("AreaList.linkPatches: ignoring null layer for id " + e.getKey());
				continue;
			}
			final Area area = e.getValue().createTransformedArea(this.at);
			for (final Patch d : la.getAll(Patch.class)) {
				r = d.getBoundingBox(r);
				if (area.intersects(r)) {
					link(d, true);
					if (d.locked) must_lock = true;
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

	/** Returns whether the point x,y is contained in this object at the given Layer. */
	public boolean contains(final Layer layer, final int x, final int y) {
		Object ob = ht_areas.get(new Long(layer.getId()));
		if (null == ob) return false;
		if (AreaList.UNLOADED == ob) {
			ob = loadLayer(layer.getId());
			if (null == ob) return false;
		}
		Area area = (Area)ob;
		if (!this.at.isIdentity()) area = area.createTransformedArea(this.at);
		return area.contains(x, y);
	}

	public boolean intersects(final Layer layer, final Rectangle r) {
		Object ob = ht_areas.get(layer.getId());
		if (null == ob) return false;
		if (AreaList.UNLOADED == ob) {
			ob = loadLayer(layer.getId());
			if (null == ob) return false;
		}
		final Area a = ((Area)ob).createTransformedArea(this.at);
		return a.intersects(r.x, r.y, r.width, r.height);
	}

	public boolean intersects(final Layer layer, final Area area) {
		Object ob = ht_areas.get(layer.getId());
		if (null == ob) return false;
		if (AreaList.UNLOADED == ob) {
			ob = loadLayer(layer.getId());
			if (null == ob) return false;
		}
		final Area a = ((Area)ob).createTransformedArea(this.at);
		a.intersect(area);
		final Rectangle b = a.getBounds();
		return 0 != b.width && 0 != b.height;
	}

	/** Returns the bounds of this object as it shows in the given layer. */
	public Rectangle getBounds(final Rectangle r, final Layer layer) {
		if (null == layer) return super.getBounds(r, null);
		final Area area = (Area)ht_areas.get(layer.getId());
		if (null == area) {
			if (null == r) return new Rectangle();
			r.x = 0;
			r.y = 0;
			r.width = 0;
			r.height = 0;
			return r;
		}
		final Rectangle b = area.createTransformedArea(this.at).getBounds();
		if (null == r) return b;
		r.setBounds(b.x, b.y, b.width, b.height);
		return r;
	}

	public boolean isDeletable() {
		if (0 == ht_areas.size()) return true;
		return false;
	}

	private AreaWrapper aw = null;
	private Long lid = null;

	@Override
	public void mousePressed(final MouseEvent me, final Layer la, final int x_p_w, final int y_p_w, final double mag) {
		lid = la.getId(); // isn't this.layer pointing to the current layer always? It *should*
		Object ob = ht_areas.get(new Long(lid));
		Area area = null;
		if (null == ob) {
			area = new Area();
			ht_areas.put(new Long(lid), area);
			this.width = layer_set.getLayerWidth(); // will be set properly at mouse release
			this.height = layer_set.getLayerHeight(); // without this, the first brush slash doesn't get painted because the isOutOfRepaintingClip returns true
		} else {
			if (AreaList.UNLOADED == ob) {
				ob = loadLayer(lid);
				if (null == ob) return;
			}
			area = (Area)ob;
		}

		// help ease the transition from PEN to BRUSH:
		if (ProjectToolbar.getToolId() == ProjectToolbar.PEN) ProjectToolbar.setTool(ProjectToolbar.BRUSH);

		aw = new AreaWrapper(area);
		aw.setSource(this);
		final Area a = aw.getArea();
		final Long lid = this.lid;
		aw.mousePressed(me, la, x_p_w, y_p_w, mag, Arrays.asList(new Runnable[]{new Runnable() { public void run() {
			// To be run on mouse released:
			// check if empty. If so, remove
			Rectangle bounds = a.getBounds();
			if (0 == bounds.width && 0 == bounds.height) {
				ht_areas.remove(lid);
			}
			calculateBoundingBox(la);
		}}}));
		aw.setSource(null);
	}
	@Override
	public void mouseDragged(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (null == aw) return;
		aw.setSource(this);
		aw.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
		aw.setSource(null);
	}
	@Override
	public void mouseReleased(MouseEvent me, Layer la, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		if (null == aw) return;
		aw.setSource(this);
		aw.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
		aw.setSource(null);

		lid = null;
		aw = null;
	}

	/** Calculate box, make this width,height be that of the box, and translate all areas to fit in. @param lid is the currently active Layer. */ //This is the only road to sanity for ZDisplayable objects.
	public boolean calculateBoundingBox(final Layer la) {
		try {
			// check preconditions
			if (0 == ht_areas.size()) return false;
			final Area[] area = new Area[ht_areas.size()];
			ht_areas.values().toArray(area);
			final Rectangle[] b = new Rectangle[area.length];
			Rectangle box = null;
			for (int i=0; i<area.length; i++) {
				b[i] = area[i].getBounds();
				if (null == box) box = (Rectangle)b[i].clone();
				else box.add(b[i]);
			}
			if (null == box || 0 == box.width || 0 == box.height) return false; // empty AreaList

			final AffineTransform atb = new AffineTransform();
			atb.translate(-box.x, -box.y); // make local to overall box, so that box starts now at 0,0
			/*
			final Map.Entry<Long,Area>[] entry = (Map.Entry<Long,Area>[])new Map.Entry[area.length];
			ht_areas.entrySet().toArray(entry);
			for (int i=0; i<area.length; i++) {
				entry[i].setValue(area[i].createTransformedArea(atb));
			}
			*/
			for (final Area a : ht_areas.values()) {
				a.transform(atb);
			}
			//this.translate(box.x, box.y);
			this.at.translate(box.x, box.y);
			this.width = box.width;
			this.height = box.height;
			updateInDatabase("transform+dimensions");
			return true;
		} finally {
			updateBucket(la);
		}
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		final String type = "t2_area_list";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_area_list (").append(Displayable.commonDTDChildren()).append(",t2_area)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent); // all ATTLIST of a Displayable
		sb_header.append(indent).append("<!ATTLIST t2_area_list fill_paint NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ELEMENT t2_area (t2_path)>\n")
			 .append(indent).append("<!ATTLIST t2_area layer_id NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ELEMENT t2_path EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_path d NMTOKEN #REQUIRED>\n")
		;
	}

	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final Object any) {
		sb_body.append(indent).append("<t2_area_list\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		sb_body.append(in).append("fill_paint=\"").append(fill_paint).append("\"\n");
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"stroke:none;fill-opacity:").append(alpha).append(";fill:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";\"\n");
		sb_body.append(indent).append(">\n");
		for (final Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			sb_body.append(in).append("<t2_area layer_id=\"").append(entry.getKey()).append("\">\n");
			exportArea(sb_body, in + "\t", entry.getValue());
			sb_body.append(in).append("</t2_area>\n");
		}
		super.restXML(sb_body, in, any);
		sb_body.append(indent).append("</t2_area_list>\n");
	}

	/** Exports the given area as a list of SVG path elements with integers only. Only reads SEG_MOVETO, SEG_LINETO and SEG_CLOSE elements, all others ignored (but could be just as easily saved in the SVG path). */
	static final void exportArea(final StringBuilder sb, final String indent, final Area area) {
		// I could add detectors for straight lines and thus avoid saving so many points.
		final float[] coords = new float[6];
		final float precision = 0.0001f;
		for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			switch (pit.currentSegment(coords)) {
				case PathIterator.SEG_MOVETO:
					//Utils.log2("SEG_MOVETO: " + coords[0] + "," + coords[1]); // one point
					sb.append(indent).append("<t2_path d=\"M ");
					M.appendShortest(coords[0], precision, sb);
					sb.append(' ');
					M.appendShortest(coords[1], precision, sb);
					break;
				case PathIterator.SEG_LINETO:
					//Utils.log2("SEG_LINETO: " + coords[0] + "," + coords[1]); // one point
					sb.append(" L ");
					M.appendShortest(coords[0], precision, sb);
					sb.append(' ');
					M.appendShortest(coords[1], precision, sb);
					break;
				case PathIterator.SEG_CLOSE:
					//Utils.log2("SEG_CLOSE");
					// make a line to the first point
					//sb.append(" L ").append(x0).append(" ").append(y0);
					sb.append(" z\" />\n");
					break;
				default:
					Utils.log2("WARNING: AreaList.exportArea unhandled seg type.");
					break;
			}
			pit.next();
			if (pit.isDone()) {
				//Utils.log2("finishing");
				return;
			}
		}
	}

	/** Returns an ArrayList of ArrayList of Point as value with all paths for the Area of the given layer_id. */
	public ArrayList<ArrayList<Point>> getPaths(long layer_id) {
		Area ob = ht_areas.get(layer_id);
		if (null == ob) return null;
		if (AreaList.UNLOADED == ob) {
			ob = loadLayer(layer_id);
			if (null == ob) return null;
		}
		Area area = ob;
		ArrayList<ArrayList<Point>> al_paths = new ArrayList<ArrayList<Point>>();
		ArrayList<Point> al_points = null;
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			float[] coords = new float[6];
			int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
					al_points = new ArrayList<Point>();
					al_points.add(new Point((int)coords[0], (int)coords[1]));
					break;
				case PathIterator.SEG_LINETO:
					al_points.add(new Point((int)coords[0], (int)coords[1]));
					break;
				case PathIterator.SEG_CLOSE:
					al_paths.add(al_points);
					al_points = null;
					break;
				default:
					Utils.log2("WARNING: AreaList.getPaths() unhandled seg type.");
					break;
			}
			pit.next();
			if (pit.isDone()) {
				break;
			}
		}
		return al_paths;
	}

	/** Returns a table of Long layer ids versus the ArrayList that getPaths(long) returns for it.*/
	public HashMap<Long,ArrayList<ArrayList<Point>>> getAllPaths() {
		HashMap<Long,ArrayList<ArrayList<Point>>> ht = new HashMap<Long,ArrayList<ArrayList<Point>>>();
		for (final Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			ht.put(entry.getKey(), getPaths(entry.getKey()));
		}
		return ht;
	}

	public void fillHoles(final Layer la) {
		Object o = ht_areas.get(la.getId());
		if (UNLOADED == o) o = loadLayer(la.getId());
		if (null == o) return;
		Area area = (Area) o;

		new AreaWrapper(this, area).fillHoles();
	}

	public boolean paintsAt(final Layer layer) {
		if (!super.paintsAt(layer)) return false;
		return null != ht_areas.get(new Long(layer.getId()));
	}

	/** Dynamic loading from the database. */
	private Area loadLayer(final long layer_id) {
		Area area = project.getLoader().fetchArea(this.id, layer_id);
		if (null == area) return null;
		ht_areas.put(new Long(layer_id), area);
		return area;
	}

	public void adjustProperties() {
		GenericDialog gd = makeAdjustPropertiesDialog(); // in superclass
		gd.addCheckbox("Paint as outlines", !fill_paint);
		gd.addCheckbox("Apply paint mode to all AreaLists", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		// superclass processing
		final Displayable.DoEdit prev = processAdjustPropertiesDialog(gd);
		// local proccesing
		final boolean fp = !gd.getNextBoolean();
		final boolean to_all = gd.getNextBoolean();
		if (to_all) {
			for (final ZDisplayable zd : this.layer_set.getZDisplayables()) {
				if (zd.getClass() == AreaList.class) {
					AreaList ali = (AreaList)zd;
					ali.fill_paint = fp;
					ali.updateInDatabase("fill_paint");
					Display.repaint(this.layer_set, ali, 2);
				}
			}
		} else {
			if (this.fill_paint != fp) {
				prev.add("fill_paint", fp);
				this.fill_paint = fp;
				updateInDatabase("fill_paint");
			}
		}

		// Add current step, with the same modified keys
		DoEdit current = new DoEdit(this).init(prev);
		if (isLinked()) current.add(new Displayable.DoTransforms().addAll(getLinkedGroup(null)));
		getLayerSet().addEditStep(current);
	}

	public boolean isFillPaint() { return this.fill_paint; }

	/** Merge all arealists contained in the ArrayList to the first one found, and remove the others from the project, and only if they belong to the same LayerSet. Returns the merged AreaList object. */
	static public AreaList merge(final ArrayList<Displayable> al) {
		AreaList base = null;
		final ArrayList<Displayable> list = new ArrayList<Displayable>(al);
		for (final Iterator<Displayable> it = list.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (ob.getClass() != AreaList.class) it.remove();
			if (null == base) {
				base = (AreaList)ob;
				if (null == base.layer_set) {
					Utils.log2("AreaList.merge: null LayerSet for base AreaList.");
					return null;
				}
				it.remove();
			}
			// check that it belongs to the same layer set as the base
			AreaList ali = (AreaList)ob;
			if (base.layer_set != ali.layer_set) it.remove();
		}
		if (list.size() < 1) return null; // nothing to fuse
		for (final Iterator<Displayable> it = list.iterator(); it.hasNext(); ) {
			// add to base
			AreaList ali = (AreaList)it.next();
			base.add(ali);
			// remove from project
			ali.project.removeProjectThing(ali, false, true, 1); // the node from the Project Tree
			Utils.log2("Merged AreaList " + ali + " to base " + base);
		}
		// update
		base.calculateBoundingBox(null);
		// relink
		base.linkPatches();

		return base;
	}

	/** For each area that ali contains, add it to the corresponding area here.*/
	private void add(final AreaList ali) {
		for (final Map.Entry<Long,Area> entry : ali.ht_areas.entrySet()) {
			Area ob_area = entry.getValue();
			long lid = entry.getKey();
			if (UNLOADED == ob_area) ob_area = ali.loadLayer(lid);
			Area area = (Area)ob_area;
			area = area.createTransformedArea(ali.at);
			// now need to inverse transform it by this.at
			try {
				area = area.createTransformedArea(this.at.createInverse());
			} catch (NoninvertibleTransformException nte) {
				IJError.print(nte);
				// do what?
			}
			Object this_area = this.ht_areas.get(entry.getKey());
			if (UNLOADED == this_area) { this_area = loadLayer(lid); }
			if (null == this_area) this.ht_areas.put(entry.getKey(), (Area)area.clone());
			else ((Area)this_area).add(area);
			updateInDatabase("points=" + ((Long)entry.getKey()).intValue());
		}
	}

	/** How many layers does this object paint to. */
	public int getNAreas() { return ht_areas.size(); }

	public Area getArea(Layer la) {
		if (null == la) return null;
		return getArea(la.getId());
	}
	public Area getArea(long layer_id) {
		Object ob = ht_areas.get(new Long(layer_id));
		if (null != ob) {
			if (UNLOADED == ob) ob = loadLayer(layer_id);
			return (Area)ob;
		}
		return null;
	}



	/** Performs a deep copy of this object, without the links. */
	public Displayable clone(final Project pr, final boolean copy_id) {
		final ArrayList<Long> al_ul = new ArrayList<Long>();
		for (final Long lid : ht_areas.keySet()) { // TODO WARNING the layer ids are wrong if the project is different or copy_id is false! Should lookup closest layer by Z ...
			al_ul.add(new Long(lid)); // clones of the Long that wraps layer id
		}
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final AreaList copy = new AreaList(pr, nid, null != title ? title.toString() : null, width, height, alpha, this.visible, new Color(color.getRed(), color.getGreen(), color.getBlue()), this.visible, al_ul, (AffineTransform)this.at.clone());
		for (final Map.Entry<Long,Area> entry : copy.ht_areas.entrySet()) {
			entry.setValue(new Area(this.ht_areas.get(entry.getKey())));
		}
		return copy;
	}


	public List<Point3f> generateTriangles(final double scale, final int resample) {
		final HashMap<Layer,Area> areas = new HashMap<Layer,Area>();
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			areas.put(layer_set.getLayer((Long)e.getKey()), (Area)e.getValue());
		}
		return AreaUtils.generateTriangles(this, scale, resample, areas);
	}

	/** Directly place an Area for the specified layer. Keep in mind it will be added in this AreaList coordinate space, not the overall LayerSet coordinate space. Does not make it local, you should call calculateBoundingBox() after setting an area. */
	public void setArea(final long layer_id, final Area area) {
		if (null == area) return;
		ht_areas.put(layer_id, area);
		updateInDatabase("points=" + layer_id);
	}

	/** Add a copy of an Area object to the existing, if any, area object at Layer with layer_id as given, or if not existing, just set the copy as it. The area is expected in this AreaList coordinate space. Does not make it local, you should call calculateBoundingBox when done. */
	public void addArea(final long layer_id, final Area area) {
		if (null == area) return;
		Area a = getArea(layer_id);
		if (null == a) ht_areas.put(layer_id, new Area(area));
		else a.add(area);
		updateInDatabase("points=" + layer_id);
	}

	/** Adds the given ROI, which is expected in world/LayerSet coordinates, to the area present at Layer with id layer_id, or set it if none present yet. */
	public void add(final long layer_id, final ShapeRoi roi) throws NoninvertibleTransformException{
		if (null == roi) return;
		Area a = getArea(layer_id);
		Area asr = M.getArea(roi).createTransformedArea(this.at.createInverse());
		if (null == a) {
			ht_areas.put(layer_id, asr);
		} else {
			a.add(asr);
			ht_areas.put(layer_id, a);
		}
		calculateBoundingBox(null != layer_set ? layer_set.getLayer(layer_id) : null);
		updateInDatabase("points=" + layer_id);
	}
	/** Subtracts the given ROI, which is expected in world/LayerSet coordinates, to the area present at Layer with id layer_id, or set it if none present yet. */
	public void subtract(final long layer_id, final ShapeRoi roi) throws NoninvertibleTransformException {
		if (null == roi) return;
		Area a = getArea(layer_id);
		if (null == a) return;
		a.subtract(M.getArea(roi).createTransformedArea(this.at.createInverse()));
		calculateBoundingBox(null != layer_set ? layer_set.getLayer(layer_id) : null);
		updateInDatabase("points=" + layer_id);
	}

	/** Subtracts the given ROI, and then creates a new AreaList with identical properties and the content of the subtracted part. Returns null if there is no intersection between sroi and the Area for layer_id. */
	public AreaList part(final long layer_id, final ShapeRoi sroi) throws NoninvertibleTransformException {
		// The Area to subtract, in world coordinates:
		Area sub = M.getArea(sroi);
		// The area to subtract from:
		Area a = getArea(layer_id);
		if (null == a || M.isEmpty(a)) return null;
		// The intersection:
		Area inter = a.createTransformedArea(this.at);
		inter.intersect(sub);
		if (M.isEmpty(inter)) return null;

		// Subtract from this:
		this.subtract(layer_id, sroi);

		// Create new AreaList with the intersection area, and add it to the same LayerSet as this:
		AreaList ali = new AreaList(this.project, this.title, 0, 0);
		ali.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		ali.visible = this.visible;
		ali.alpha = this.alpha;
		ali.addArea(layer_id, inter);
		this.layer_set.add(ali); // needed to call updateBucket
		ali.calculateBoundingBox(null != layer_set ? layer_set.getLayer(layer_id) : null);

		return ali;
	}

	public void keyPressed(KeyEvent ke) {
		Object source = ke.getSource();
		if (! (source instanceof DisplayCanvas)) return;
		DisplayCanvas dc = (DisplayCanvas)source;
		Layer layer = dc.getDisplay().getLayer();
		int keyCode = ke.getKeyCode();
		long layer_id = layer.getId();

		if (KeyEvent.VK_K == keyCode) {
			Roi roi = dc.getFakeImagePlus().getRoi();
			if (null == roi) return;
			if (!M.isAreaROI(roi)) {
				Utils.log("AreaList only accepts region ROIs, not lines.");
				return;
			}
			ShapeRoi sroi = new ShapeRoi(roi);

			try {
				AreaList p = part(layer_id, sroi);
				if (null != p) {
					project.getProjectTree().addSibling(this, p);
				}
				Display.repaint(layer, getBoundingBox(), 5);
				linkPatches();
			} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
			ke.consume();
		} else {
			Area a = getArea(layer_id);
			if (null == a) {
				a = new Area();
				ht_areas.put(layer_id, a);
			}
			new AreaWrapper(this, a).keyPressed(ke, dc, layer);
		}
	}

	/** Measure the volume (in voxels) of this AreaList,
	 *  and the surface area, the latter estimated as the number of voxels that
	 *  make the outline.
	 *  
	 *  If the width and height of this AreaList cannot be fit in memory, scaled down versions will be used,
	 *  and thus the results will be approximate.
	 *
	 *  */
	public String getInfo() {
		if (0 == ht_areas.size()) return "Empty AreaList " + this.toString();
		final double[] m = measure();
		return new StringBuilder("Volume: ").append(IJ.d2s(m[0], 2))
					.append(" Lower Bound Surface: ").append(IJ.d2s(m[1], 2))
					.append(" Upper Bound Surface Smoothed: ").append(IJ.d2s(m[2], 2))
					.append(" Upper Bound Surface: ").append(IJ.d2s(m[3], 2))
					.append(" Maximum diameter: ").append(IJ.d2s(m[4], 2))
					.toString();
	}

	/** @param area is expected in world coordinates. */
	public boolean intersects(final Area area, final double z_first, final double z_last) {
		for (Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			final Layer layer = layer_set.getLayer(((Long)entry.getKey()).longValue());
			if (layer.getZ() >= z_first && layer.getZ() <= z_last) {
				Area a = ((Area)entry.getValue()).createTransformedArea(this.at);
				a.intersect(area);
				Rectangle r = a.getBounds();
				if (0 != r.width && 0 != r.height) return true;
			}
		}
		return false;
	}

	/** Export all given AreaLists as one per pixel value, what is called a "labels" file; a file dialog is offered to save the image as a tiff stack. */
	static public void exportAsLabels(final java.util.List<Displayable> list, final ij.gui.Roi roi, final float scale, int first_layer, int last_layer, final boolean visible_only, final boolean to_file, final boolean as_amira_labels) {
		// survive everything:
		if (null == list || 0 == list.size()) {
			Utils.log("Null or empty list.");
			return;
		}
		if (scale < 0 || scale > 1) {
			Utils.log("Improper scale value. Must be 0 < scale <= 1");
			return;
		}

		// Current AmiraMeshEncoder supports ByteProcessor only: 256 labels max, including background at zero.
		if (as_amira_labels && list.size() > 255) {
			Utils.log("Saving ONLY first 255 AreaLists!\nDiscarded:");
			final StringBuilder sb = new StringBuilder();
			for (final Displayable d : list.subList(255, list.size())) {
				sb.append("    ").append(d.getProject().getShortMeaningfulTitle(d)).append('\n');
			}
			Utils.log(sb.toString());
			ArrayList<Displayable> li = new ArrayList<Displayable>(list);
			list.clear();
			list.addAll(li.subList(0, 255));
		}

		String path = null;
		if (to_file) {
			String ext = as_amira_labels ? ".am" : ".tif";
			File f = Utils.chooseFile("labels", ext);
			if (null == f) return;
			path = f.getAbsolutePath().replace('\\','/');
		}

		LayerSet layer_set = list.get(0).getLayerSet();
		if (first_layer > last_layer) {
			int tmp = first_layer;
			first_layer = last_layer;
			last_layer = tmp;
			if (first_layer < 0) first_layer = 0;
			if (last_layer >= layer_set.size()) last_layer = layer_set.size()-1;
		}
		// Create image according to roi and scale
		final int width, height;
		Rectangle broi = null;
		if (null == roi) {
			width = (int)(layer_set.getLayerWidth() * scale);
			height = (int)(layer_set.getLayerHeight() * scale);
		} else {
			broi = roi.getBounds();
			width = (int)(broi.width * scale);
			height = (int)(broi.height * scale);
		}

		// Compute highest label value, which affects of course the stack image type
		TreeSet<Integer> label_values = new TreeSet<Integer>();
		for (final Displayable d : list) {
			String label = d.getProperty("label");
			if (null != label) label_values.add(Integer.parseInt(label));
		}
		int lowest = 0;
		int highest = 0;
		if (label_values.size() > 0) {
			lowest = label_values.first();
			highest = label_values.last();
		}
		int n_non_labeled = list.size() - label_values.size();
		int max_label_value = highest + n_non_labeled;

		final ImageStack stack = new ImageStack(width, height);
		// processor type:
		int type = ImagePlus.GRAY8;
		if (max_label_value > 255) { // 0 is background, and 255 different arealists
			type = ImagePlus.GRAY16;
			if (max_label_value > 65535) { // 0 is background, and 65535 different arealists
				type = ImagePlus.GRAY32;
			}
		}
		Calibration cal = layer_set.getCalibration();

		String amira_params = null;
		if (as_amira_labels) {
			final StringBuilder sb = new StringBuilder("CoordType \"uniform\"\nMaterials {\nExterior {\n Id 0,\nColor 0 0 0\n}\n");
			final float[] c = new float[3];
			int value = 0;
			for (final Displayable d : list) {
				value++; // 0 is background
				d.getColor().getRGBColorComponents(c);
				String s = d.getProject().getShortMeaningfulTitle(d);
				s = s.replace('-', '_').replaceAll(" #", " id");
				sb.append(Utils.makeValidIdentifier(s)).append(" {\n")
				  .append("Id ").append(value).append(",\n")
				  .append("Color ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append("\n}\n");
			}
			sb.append("}\n");
			amira_params = sb.toString();
		}

		final float len = last_layer - first_layer + 1;

		// Assign labels
		final HashMap<Displayable,Integer> labels = new HashMap<Displayable,Integer>();
		for (final Displayable d : list) {
			if (visible_only && !d.isVisible()) continue;
			String slabel = d.getProperty("label");
			int label;
			if (null != slabel) {
				label = Integer.parseInt(slabel);
			} else {
				label = (++highest); // 0 is background
			}
			labels.put(d, label);
		}

		final Area world = new Area(new Rectangle(0, 0, width, height));

		int count = 0;
		for (final Layer la : layer_set.getLayers().subList(first_layer, last_layer+1)) {
			Utils.showProgress(count/len);
			count++;
			ImageProcessor ip = Utils.createProcessor(type, width, height);
			if (!(ip instanceof ByteProcessor)) {
				ip.setMinAndMax(lowest, highest);
			}
			// paint here all arealist that paint to the layer 'la'
			for (final Displayable d : list) {
				if (visible_only && !d.isVisible()) continue;
				ip.setValue(labels.get(d));
				AreaList ali = (AreaList)d;
				Area area = ali.getArea(la);
				if (null == area) {
					Utils.log2("Layer " + la + " id: " + d.getId() + " area is " + area);
					continue;
				}
				// Transform: the scale and the roi
				AffineTransform aff = new AffineTransform();
				// reverse order of transformations:
				/* 3 - To scale: */ if (1 != scale) aff.scale(scale, scale);
				/* 2 - To roi coordinates: */ if (null != broi) aff.translate(-broi.x, -broi.y);
				/* 1 - To world coordinates: */ aff.concatenate(ali.at);
				Area aroi = area.createTransformedArea(aff);
				Rectangle b = aroi.getBounds();
				if (b.x < 0 || b.y < 0 || b.x + b.width >= width || b.y + b.height >= height) {
					aroi.intersect(world); // work around ij.gui.ShapeRoi bug
				}
				ShapeRoi sroi = new ShapeRoi(aroi);
				ip.setRoi(sroi);
				ip.fill(sroi.getMask());
			}
			stack.addSlice(la.getZ() * cal.pixelWidth + "", ip);
		}
		Utils.showProgress(1);
		// Save via file dialog:
		ImagePlus imp = new ImagePlus("Labels", stack); 
		if (as_amira_labels) imp.setProperty("Info", amira_params);
		imp.setCalibration(layer_set.getCalibrationCopy());
		if (to_file) {
			if (as_amira_labels) {
				AmiraMeshEncoder ame = new AmiraMeshEncoder(path);
				if (!ame.open()) {
					Utils.log("Could not write to file " + path);
					return;
				}
				if (!ame.write(imp)) {
					Utils.log("Error in writing Amira file!");
					return;
				}
			} else {
				new FileSaver(imp).saveAsTiff(path);
			}
		} else imp.show();
	}

	@Override
	public ResultsTable measure(ResultsTable rt) {
		if (0 == ht_areas.size()) return rt;
		if (null == rt) rt = Utils.createResultsTable("AreaList results", new String[]{"id", "volume", "LB-surface", "UBs-surface", "UB-surface", "AVGs-surface", "AVG-surface", "max diameter", "name-id"});
		rt.incrementCounter();
		rt.addLabel("units", layer_set.getCalibration().getUnit());
		rt.addValue(0, this.id);
		double[] m = measure();
		rt.addValue(1, m[0]); // aprox. volume
		rt.addValue(2, m[1]); // lower bound surface
		rt.addValue(3, m[2]); // upper bound surface smoothed
		rt.addValue(4, m[3]); // upper bound surface
		rt.addValue(5, (m[1] + m[2]) / 2); // average of LB and UBs
		rt.addValue(6, (m[1] + m[3]) / 2); // average of LB and UB
		rt.addValue(7, m[4]); // max diameter
		rt.addValue(8, getNameId());
		return rt;
	}

	/** Returns a double array with 0=volume, 1=lower_bound_surface, 2=upper_bound_surface_smoothed, 3=upper_bound_surface, 4=max_diameter.
	 *  All measures are approximate.
	 *  [0] Volume: sum(area * thickness) for all sections
	 *  [1] Lower Bound Surface: measure area per section, compute radius of circumference of identical area, compute then are of the sides of the truncated cone of height thickness, for each section. Plus top and bottom areas when visiting sections without a painted area.
	 *  [2] Upper Bound Surface Smoothed: measure smoothed perimeter lengths per section, multiply by thickness to get lateral area. Plus tops and bottoms.
	 *  [3] Upper Bound Surface: measure raw pixelated perimeter lengths per section, multiply by thickness to get lateral area. Plus top and bottoms.
	 *  [4] Maximum diameter: longest distance between any two points in the contours of all painted areas. */
	public double[] measure() {
		if (0 == ht_areas.size()) return new double[5]; // zeros

		// prepare suitable transform
		AffineTransform aff = (AffineTransform)this.at.clone();
		AffineTransform aff2 = new AffineTransform();
		// remove translation
		Rectangle box = getBoundingBox(null);
		aff2.translate(-box.x, -box.y);
		aff.preConcatenate(aff2);
		aff2 = null;
		box = null;

		double volume = 0;
		double lower_bound_surface_h = 0;
		double upper_bound_surface = 0;
		double upper_bound_surface_smoothed = 0;
		double prev_surface = 0;
		double prev_perimeter = 0;
		double prev_smooth_perimeter = 0;
		double prev_thickness = 0;

		Calibration cal = layer_set.getCalibration();
		final double pixelWidth = cal.pixelWidth;
		final double pixelHeight = cal.pixelHeight;

		// Put areas in order of their layer index:
		final TreeMap<Integer,Area> ias = new TreeMap<Integer,Area>();
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			int ilayer = layer_set.indexOf(layer_set.getLayer(e.getKey()));
			if (-1 == ilayer) {
				Utils.log("Could not find a layer with id " + e.getKey());
				continue;
			}
			ias.put(ilayer, e.getValue());
		}

		ArrayList<Layer> layers = layer_set.getLayers();

		int last_layer_index = -1;

		ArrayList<Point3f> points = new ArrayList<Point3f>();
		final float[] coords = new float[6];
		final float fpixelWidth = (float) pixelWidth;
		final float fpixelHeight = (float) pixelHeight;

		// for each area, measure its area and its perimeter, to compute volume and surface
		for (final Map.Entry<Integer,Area> e : ias.entrySet()) {

			// fetch Layer
			int layer_index = e.getKey();
			if (layer_index > layers.size()) {
				Utils.log("Could not find a layer at index " + layer_index);
				continue;
			}

			// fetch Area
			Area area = e.getValue();
			if (UNLOADED == area) area = loadLayer(layer.getId());
			// Transform area to world coordinates
			area = area.createTransformedArea(aff);

			// measure surface
			double pixel_area = Math.abs(AreaCalculations.area(area.getPathIterator(null)));
			double surface = pixel_area * pixelWidth * pixelHeight;

			//Utils.log2(layer_index + " pixel_area: " + pixel_area + "  surface " + surface);

			// measure volume
			double thickness = layer.getThickness() * pixelWidth;// the last one is NOT pixelDepth because layer thickness and Z are in pixels
			volume += surface * thickness;

			//Utils.log2(layer_index + "  volume: " + volume);

			double pix_perimeter = AreaCalculations.circumference(area.getPathIterator(null));
			double perimeter = pix_perimeter * pixelWidth;

			double smooth_perimeter = 0;

			// smoothed perimeter:
			// Get all paths, make VectorString2D from them
			{
				double smooth_pix_perimeter = 0;
				for (Polygon pol : M.getPolygons(area)) {
					if (pol.npoints < 7) {
						// no point in smoothing out such a short polygon:
						smooth_perimeter += pol.npoints;
						continue;
					}
					double[] xp = new double[pol.npoints];
					double[] yp = new double[pol.npoints];
					for (int p=0; p<pol.npoints; p++) {
						xp[p] = pol.xpoints[p];
						yp[p] = pol.ypoints[p];
					}
					try {
						// Should use VectorString2D, but takes for ever -- bug in resample?
						// And VectorString3D is likely not respecting the 'closed' flag for resampling.
						// Also, VectorString3D gets stuck in an infinite loop if the sequence is 6 points!
						//VectorString3D v = new VectorString3D(xp, yp, new double[pol.npoints], true);
						VectorString2D v = new VectorString2D(xp, yp, 0, true);
						v.resample(1);


						// TESTING: make a polygon roi and show it
						// ... just in case to see that resampling works as expected, without weird endings
						/*
						int[] x = new int[v.length()];
						int[] y = new int[x.length];
						double[] xd = v.getPoints(0);
						double[] yd = v.getPoints(1);
						for (int p=0; p<x.length; p++) {
							x[p] = (int)xd[p];
							y[p] = (int)yd[p];
						}
						PolygonRoi proi = new PolygonRoi(x, y, x.length, PolygonRoi.POLYGON);
						Rectangle b = proi.getBounds();
						for (int p=0; p<x.length; p++) {
							x[p] -= b.x;
							y[p] -= b.y;
						}
						ImagePlus imp = new ImagePlus("test", new ByteProcessor(b.width, b.height));
						imp.setRoi(new PolygonRoi(x, y, x.length, PolygonRoi.POLYGON));
						imp.show();
						*/

						smooth_pix_perimeter += v.length() -1; // resampled to 1, so just number_of_points * delta_of_1.
						                                       // Subtracting 1: the resampled curve has the first point as the last too.
					} catch (Exception le) { le.printStackTrace(); }
				}

				smooth_perimeter = smooth_pix_perimeter * pixelWidth;
			}

			//Utils.log2("p, sp: " + perimeter + ", " + smooth_perimeter);

			//Utils.log2(layer_index + "  pixelWidth,pixelHeight: " + pixelWidth + ", " + pixelHeight);
			//Utils.log2(layer_index + "  thickness: " + thickness);

			//Utils.log2(layer_index + "  pix_perimeter: " + pix_perimeter + "   perimeter: " + perimeter);

			if (-1 == last_layer_index) {
				// Start of the very first continuous set:
				lower_bound_surface_h += surface;
				upper_bound_surface += surface;
				upper_bound_surface_smoothed += surface;
			} else if (layer_index - last_layer_index > 1) {
				// End of a continuous set
				// sum the last surface and its side:
				lower_bound_surface_h += prev_surface + prev_thickness * 2 * Math.sqrt(prev_surface * Math.PI); //   (2x + 2x) / 2   ==   2x
				upper_bound_surface += prev_surface + prev_perimeter * prev_thickness;
				upper_bound_surface_smoothed += prev_surface + prev_smooth_perimeter * prev_thickness;

				// ... and start of a new set
				lower_bound_surface_h += surface;
				upper_bound_surface += surface;
				upper_bound_surface_smoothed += surface;
			} else {
				// Continuation of a set: use this Area and the previous as continuous
				double diff_surface = Math.abs(prev_surface - surface);

				upper_bound_surface += prev_perimeter * (prev_thickness / 2)
						     + perimeter * (prev_thickness / 2)
						     + diff_surface;

				upper_bound_surface_smoothed += prev_smooth_perimeter * (prev_thickness / 2)
					                      + smooth_perimeter * (prev_thickness / 2)
							      + diff_surface;

				// Compute area of the mantle of the truncated cone defined by the radiuses of the circles of same area as the two areas
				// PI * s * (r1 + r2) where s is the hypothenusa
				double r1 = Math.sqrt(prev_surface / Math.PI);
				double r2 = Math.sqrt(surface / Math.PI);
				double hypothenusa = Math.sqrt(Math.pow(Math.abs(r1 - r2), 2) + Math.pow(thickness, 2)); 
				lower_bound_surface_h += Math.PI * hypothenusa * (r1 + r2);

				// Adjust volume too:
				volume += diff_surface * prev_thickness / 2;
			}

			// store for next iteration:
			prev_surface = surface;
			prev_perimeter = perimeter;
			prev_smooth_perimeter = smooth_perimeter;
			last_layer_index = layer_index;
			prev_thickness = thickness;

			// Iterate points:
			final float z = (float) layer.getZ();
			for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); pit.next()) {
				switch (pit.currentSegment(coords)) {
					case PathIterator.SEG_MOVETO:
					case PathIterator.SEG_LINETO:
					case PathIterator.SEG_CLOSE:
						points.add(new Point3f(coords[0] * fpixelWidth, coords[1] * fpixelHeight, z * fpixelWidth));
						break;
					default:
						Utils.log2("WARNING: unhandled seg type.");
						break;
				}
			}
		}

		// finish last:
		lower_bound_surface_h += prev_surface + prev_perimeter * prev_thickness;
		upper_bound_surface += prev_surface + prev_perimeter * prev_thickness;
		upper_bound_surface_smoothed += prev_surface + prev_smooth_perimeter * prev_thickness;


		// Compute maximum diameter
		double max_diameter_sq = 0;
		final int lp = points.size();
		for (int i=0; i<lp; i++) {
			final Point3f p = points.get(i);
			for (int j=i; j<lp; j++) {
				double len = p.distanceSquared(points.get(j));
				if (len > max_diameter_sq) max_diameter_sq = len;
			}
		}

		return new double[]{volume, lower_bound_surface_h, upper_bound_surface_smoothed, upper_bound_surface, Math.sqrt(max_diameter_sq)};
	}

	@Override
	Class<?> getInternalDataPackageClass() {
		return DPAreaList.class;
	}

	@Override
	Object getDataPackage() {
		// The width,height,links,transform and list of areas
		return new DPAreaList(this);
	}

	static private final class DPAreaList extends Displayable.DataPackage {
		final protected HashMap<Long,Area> ht;
		DPAreaList(final AreaList ali) {
			super(ali);
			this.ht = new HashMap<Long,Area>();
			for (final Map.Entry<Long,Area> e : ali.ht_areas.entrySet()) {
				this.ht.put(e.getKey(), new Area(e.getValue()));
			}
		}
		final boolean to2(final Displayable d) {
			super.to1(d);
			final AreaList ali = (AreaList)d;
			ali.ht_areas.clear();
			for (final Map.Entry<Long,Area> e : ht.entrySet()) {
				ali.ht_areas.put(e.getKey(), new Area(e.getValue()));
			}
			return true;
		}
	}

	/** Retain the data within the layer range, and through out all the rest. */
	synchronized public boolean crop(List<Layer> range) {
		final Set<Long> lids = new HashSet<Long>();
		for (final Layer l : range) lids.add(l.getId());
		for (final Iterator<Long> it = ht_areas.keySet().iterator(); it.hasNext(); ) {
			if (!lids.contains(it.next())) it.remove();
		}
		calculateBoundingBox(null);
		return true;
	}

	/** Returns a stack of images representing the pixel data of this LayerSet inside this AreaList. */
	public ImagePlus getStack(final int type, final double scale) {
		ImageProcessor ref_ip = Utils.createProcessor(type, 2, 2);
		if (null == ref_ip) {
			Utils.log("AreaList.getStack: Unknown type " + type);
			return null;
		}
		Rectangle b = getBoundingBox();
		int w = (int)(0.5 + b.width * scale);
		int h = (int)(0.5 + b.height * scale);
		ImageStack stack = new ImageStack(w, h);
		for (Layer la : getLayerRange()) {
			Area area = getArea(la);
			double z = layer.getZ();
			project.getLoader().releaseToFit(w * h * 10);
			ImageProcessor ip = ref_ip.createProcessor(w, h);
			if (null == area) {
				stack.addSlice(Double.toString(z), ip);
				continue;
			}
			// Create a ROI from the area at Layer la:
			AffineTransform aff = getAffineTransformCopy();
			aff.translate(-b.x, -b.y);
			aff.scale(scale, scale);
			ShapeRoi roi = new ShapeRoi(area.createTransformedArea(aff));
			// Create a cropped snapshot of the images at Layer la under the area:
			ImageProcessor flat = Patch.makeFlatImage(type, la, b, scale, la.getAll(Patch.class), Color.black);
			flat.setRoi(roi);
			Rectangle rb = roi.getBounds();
			ip.insert(flat.crop(), rb.x, rb.y);
			// Clear the outside
			ImagePlus bimp = new ImagePlus("", ip);
			bimp.setRoi(roi);
			ip.setValue(0);
			ip.setBackgroundValue(0);
			IJ.run(bimp, "Clear Outside", "");

			stack.addSlice(Double.toString(z), ip);
		}

		ImagePlus imp = new ImagePlus("AreaList stack for " + this, stack);
		imp.setCalibration(layer_set.getCalibrationCopy());
		return imp;
	}

	public List<Area> getAreas(final Layer layer, final Rectangle box) {
		Area a = (Area) ht_areas.get(layer.getId());
		if (null == a) return null;
		ArrayList<Area> l = new ArrayList<Area>();
		l.add(a);
		return l;
	}

	protected boolean layerRemoved(Layer la) {
		super.layerRemoved(la);
		ht_areas.remove(la.getId());
		return true;
	}

	public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ct) throws Exception {
		final Area a = getArea(la);
		if (null == a) return true;
		final AffineTransform inverse = this.at.createInverse();
		if (M.intersects(a, roi.createTransformedArea(inverse))) {
			M.apply(M.wrap(this.at, ct, inverse), roi, a);
			calculateBoundingBox(la);
		}
		return true;
	}

	public boolean apply(final VectorDataTransform vdt) throws Exception {
		final Area a = getArea(vdt.layer);
		if (null == a) return true;
		M.apply(vdt.makeLocalTo(this), a);
		calculateBoundingBox(vdt.layer);
		return true;
	}

	@Override
	synchronized public Collection<Long> getLayerIds() {
		return new ArrayList<Long>(ht_areas.keySet());
	}

	@Override
	public Area getAreaAt(final Layer layer) {
		final Area a = getArea(layer);
		if (null == a) return null;
		return a.createTransformedArea(this.at);
	}

	@Override
	public boolean isRoughlyInside(final Layer layer, final Rectangle box) {
		final Area a = getArea(layer);
		if (null == a) return false;
		/*
		final float[] coords = new float[6];
		final float precision = 0.0001f;
		for (final PathIterator pit = a.getPathIterator(this.at); !pit.isDone(); pit.next()) {
			switch (pit.currentSegment(coords)) {
				case PathIterator.SEG_MOVETO:
				case PathIterator.SEG_LINETO:
				case PathIterator.SEG_CLOSE:
					if (box.contains(coords[0], coords[1])) return true;
					break;
				default:
					break;
			}
		}
		return false;
		*/
		// The above is about 2x to 3x faster than:
		//return a.createTransformedArea(this.at).intersects(box);

		// But this is 3x faster even than using path iterator:
		try {
			return this.at.createInverse().createTransformedShape(box).intersects(a.getBounds());
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}
	}
}
