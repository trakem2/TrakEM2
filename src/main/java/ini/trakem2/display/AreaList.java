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
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.scijava.vecmath.Point3f;

import amira.AmiraMeshEncoder;
import fiji.geom.AreaCalculations;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.paint.USHORTPaint;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.CircularSequence;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

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

	public AreaList(final Project project, final String title, final double x, final double y) {
		super(project, title, x, y);
		this.alpha = AreaWrapper.PP.default_alpha;
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public AreaList(final Project project, final long id, final HashMap<String,String> ht_attributes, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht_attributes, ht_links);
		// read the fill_paint
		final Object ob_data = ht_attributes.get("fill_paint");
		try {
			if (null != ob_data) this.fill_paint = "true".equals(((String)ob_data).trim().toLowerCase()); // fails: //Boolean.getBoolean((String)ob_data);
		} catch (final Exception e) {
			Utils.log("AreaList: could not read fill_paint value from XML:" + e);
		}
	}

	/** Reconstruct from the database. */
	public AreaList(final Project project, final long id, final String title, final float width, final float height, final float alpha, final boolean visible, final Color color, final boolean locked, final ArrayList<Long> al_ul, final AffineTransform at) { // al_ul contains Long() wrapping layer ids
		super(project, id, title, locked, at, width, height);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		for (final Long lid : al_ul) {
			ht_areas.put(lid, AreaList.UNLOADED); // assumes al_ul contains only Long instances wrapping layer_id long values
		}
	}

	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		//arrange transparency
		Composite original_composite = null;
		try {
			if (layer_set.area_color_cues) {
				original_composite = g.getComposite();
				Color c = layer_set.use_color_cue_colors ? Color.red : this.color;
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(alpha, 0.25f)));
				for (final Layer la : layers) {
					if (active_layer == la) {
						c = layer_set.use_color_cue_colors ? Color.blue : this.color;
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

	@Override
	public void transformPoints(final Layer layer, final double dx, final double dy, final double rot, final double xo, final double yo) {
		Utils.log("AreaList.transformPoints: not implemented yet.");
	}

	/** Returns the layer of lowest Z coordinate Layer where this ZDisplayable has a point in. */
	@Override
	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer first_layer = null;
		for (final Long lid : ht_areas.keySet()) {
			final Layer la = this.layer_set.getLayer(lid.longValue());
			final double z = la.getZ();
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
			final Layer la = this.layer_set.getLayer(lid.longValue());
			final double z = la.getZ();
			if (z > max_z) {
				max_z = z;
				last_layer = la;
			}
		}
		return last_layer;
	} // I do REALLY miss Lisp macros. Writting the above two methods in a lispy way would make the java code unreadable

	/** Get the range of layers between the first and last layers in which this AreaList paints to. */
	public List<Layer> getLayerRange() {
		return layer_set.getLayers(getFirstLayer(), getLastLayer());
	}

	@Override
	public boolean linkPatches() {
		unlinkAll(Patch.class);
		// cheap way: intersection of the patches' bounding box with the area
		Rectangle r = new Rectangle();
		boolean must_lock = false;
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			final Layer la = this.layer_set.getLayer(e.getKey());
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
	@Override
	public boolean contains(final Layer layer, final double x, final double y) {
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

	@Override
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

	@Override
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
	@Override
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

	@Override
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
		aw.mousePressed(me, la, x_p_w, y_p_w, mag, Arrays.asList(new Runnable[]{new Runnable() { @Override
		public void run() {
			// To be run on mouse released:
			// check if empty. If so, remove
			final Rectangle bounds = a.getBounds();
			if (0 == bounds.width && 0 == bounds.height) {
				ht_areas.remove(lid);
			}
			calculateBoundingBox(la);
		}}}));
		aw.setSource(null);
	}
	@Override
	public void mouseDragged(final MouseEvent me, final Layer la, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {
		if (null == aw) return;
		aw.setSource(this);
		aw.mouseDragged(me, la, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
		aw.setSource(null);
	}
	@Override
	public void mouseReleased(final MouseEvent me, final Layer la, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		if (null == aw) return;
		aw.setSource(this);
		aw.mouseReleased(me, la, x_p, y_p, x_d, y_d, x_r, y_r);
		aw.setSource(null);

		lid = null;
		aw = null;
	}

	/** Calculate box, make this width,height be that of the box, and translate all areas to fit in.
	 * @param la is the currently active Layer.
	 */
	@Override
	public boolean calculateBoundingBox(final Layer la) {
		try {
			// check preconditions
			if (0 == ht_areas.size()) return false;

			Rectangle box = null;
			for (final Area a : ht_areas.values()) {
				if (null == a || a.isEmpty()) continue;
				if (null == box) box = a.getBounds();
				else box.add(a.getBounds());
			}

			// If null, the AreaList was empty
			// If box.width,height are zero, the AreaList was empty
			if (null == box || 0 == box.width || 0 == box.height) {
				return false;
			}

			// If box.x,y are 0, then no transform is necessary
			if (0 == box.x && 0 == box.y) {
				// no translation necessary, but must adjust width and height
				this.width = box.width;
				this.height = box.height;
				updateInDatabase("dimensions");
				return true;
			}

			// make local to overall box, so that box starts now at 0,0
			final AffineTransform atb = new AffineTransform(1, 0, 0, 1, -box.x, -box.y);

			// Guess if multithreaded processing would help
			if (ht_areas.size() > 1 && (box.width > 2048 || box.height > 2048 || ht_areas.size() > 10)) {
				// Multithreaded
				final ExecutorService exec = Utils.newFixedThreadPool("AreaList-CBB");
				final List<Future<?>> fus = new ArrayList<Future<?>>();
				for (final Area a : ht_areas.values()) {
					fus.add(exec.submit(new Runnable() {
						@Override
						public void run() {
							a.transform(atb);
						}
					}));
				}
				Utils.wait(fus);
				exec.shutdown();
			} else {
				// Single threaded
				for (final Area a : ht_areas.values()) {
					a.transform(atb);
				}
			}
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
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		sb_body.append(indent).append("<t2_area_list\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		sb_body.append(in).append("fill_paint=\"").append(fill_paint).append("\"\n");
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"stroke:none;fill-opacity:").append(alpha).append(";fill:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";\"\n");
		sb_body.append(indent).append(">\n");
		for (final Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			final Area area = entry.getValue();
			if (null == area || area.isEmpty()) continue;
			sb_body.append(in).append("<t2_area layer_id=\"").append(entry.getKey()).append("\">\n");
			exportArea(sb_body, in + "\t", area);
			sb_body.append(in).append("</t2_area>\n");
		}
		super.restXML(sb_body, in, options);
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
	public ArrayList<ArrayList<Point>> getPaths(final long layer_id) {
		Area ob = ht_areas.get(layer_id);
		if (null == ob) return null;
		if (AreaList.UNLOADED == ob) {
			ob = loadLayer(layer_id);
			if (null == ob) return null;
		}
		final Area area = ob;
		final ArrayList<ArrayList<Point>> al_paths = new ArrayList<ArrayList<Point>>();
		ArrayList<Point> al_points = null;
		for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			final float[] coords = new float[6];
			final int seg_type = pit.currentSegment(coords);
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
		final HashMap<Long,ArrayList<ArrayList<Point>>> ht = new HashMap<Long,ArrayList<ArrayList<Point>>>();
		for (final Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			ht.put(entry.getKey(), getPaths(entry.getKey()));
		}
		return ht;
	}

	public void fillHoles(final Layer la) {
		Object o = ht_areas.get(la.getId());
		if (UNLOADED == o) o = loadLayer(la.getId());
		if (null == o) return;
		final Area area = (Area) o;

		new AreaWrapper(this, area).fillHoles();
	}

	@Override
	public boolean paintsAt(final Layer layer) {
		if (!super.paintsAt(layer)) return false;
		return null != ht_areas.get(new Long(layer.getId()));
	}

	/** Dynamic loading from the database. */
	private Area loadLayer(final long layer_id) {
		final Area area = project.getLoader().fetchArea(this.id, layer_id);
		if (null == area) return null;
		ht_areas.put(new Long(layer_id), area);
		return area;
	}

	@Override
	public void adjustProperties() {
		final GenericDialog gd = makeAdjustPropertiesDialog(); // in superclass
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
					final AreaList ali = (AreaList)zd;
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
		final DoEdit current = new DoEdit(this).init(prev);
		if (isLinked()) current.add(new Displayable.DoTransforms().addAll(getLinkedGroup(null)));
		getLayerSet().addEditStep(current);
	}

	public boolean isFillPaint() { return this.fill_paint; }

	/** Merge all arealists contained in the ArrayList to the first one found, and remove the others from the project, and only if they belong to the same LayerSet. Returns the merged AreaList object. */
	static public AreaList merge(final ArrayList<Displayable> al) {
		AreaList base = null;
		final ArrayList<Displayable> list = new ArrayList<Displayable>(al);
		for (final Iterator<Displayable> it = list.iterator(); it.hasNext(); ) {
			final Object ob = it.next();
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
			final AreaList ali = (AreaList)ob;
			if (base.layer_set != ali.layer_set) it.remove();
		}
		if (list.size() < 1) return null; // nothing to fuse
		for (final Iterator<Displayable> it = list.iterator(); it.hasNext(); ) {
			// add to base
			final AreaList ali = (AreaList)it.next();
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
	
	/**
	 * 
	 * For each AreaList in the list, find disconnected volumes within each and make an AreaList out of each.
	 * If so, remove the original AreaList, and place the new ones under the same ProjectThing parent, with the same name
	 * 
	 * @param al The list of AreaList to split.
	 * @return The list of AreaList that remain after potentially splitting the given ones.
	 * @throws Exception 
	 */
	static public ArrayList<AreaList> split(final List<Displayable> al) throws Exception {
		
		final ArrayList<AreaList> r = new ArrayList<AreaList>();
		
		for (final Displayable d : al) {
			// Filter non-AreaList objects
			if (d.getClass() != AreaList.class) continue;
			// Find volumes in each AreaList, where a Volume is defined as a set of contiguous, overlapping areas.
			final AreaList ali = (AreaList)d;
			final HashSet<AreaList> open = new HashSet<AreaList>();
			final HashSet<AreaList> closed = new HashSet<AreaList>();
			
			// Traverse layers in Z order
			Layer prev_layer = null;
			for (final Layer layer : ali.getLayerRange()) {
				final HashSet<AreaList> touched = new HashSet<AreaList>();
				final Area full = ali.getArea(layer);
				
				if (null != full) {
					for (final Polygon pol : M.getPolygons(full)) {
						Area area = new Area(pol);
						// If area touches (Z-contiguous + 2D-overlap) an open AreaList, add it to it
						// If area does not touch any open AreaList, open a new AreaList
						// If an open AreaList is not touched, send it to closed.
						if (null != prev_layer) {
							final ArrayList<AreaList> overlap = new ArrayList<AreaList>();
							for (final AreaList o : open) {
								if (M.intersects(o.getArea(prev_layer.getId()), area)) {
									// Touches!
									touched.add(o);
									overlap.add(o);
								}
							}
							switch (overlap.size()) {
							case 0:
								break;
							case 1:
								overlap.get(0).addArea(layer.getId(), area);
								area = null;
								break;
							default:
								// More than 1 overlap: fuse them
								final AreaList base = overlap.get(0);
								for (final AreaList o: overlap) {
									if (o == base) continue;
									for (final long layer_id: o.getLayerIds()) {
										base.addArea(layer_id, o.getArea(layer_id));
									}
									open.remove(o);
								}
								area = null;
								break;
							}
						}
						if (null != area) {
							// The area did not touch any open AreaList: place it in a a new one
							final AreaList new_ali = new AreaList(ali.project, ali.title, 0, 0);
							// Copy properties
							new_ali.color = ali.color;
							new_ali.alpha = ali.alpha;
							new_ali.title = ali.title;
							new_ali.at.setTransform(ali.at);
							if (null != ali.props) {
								for (final Entry<String,String> prop: ali.getProperties().entrySet()) {
									new_ali.setProperty(prop.getKey(), prop.getValue());
								}
							}
							// Insert the Area
							new_ali.setArea(layer.getId(), area);
							//
							open.add(new_ali);
							touched.add(new_ali);
						}
					}
				}
				// Else if full is null, then all open AreaList are closed
				
				// Place any untouched AreaList into the list of closed ones
				final Iterator<AreaList> it = open.iterator();
				while (it.hasNext()) {
					final AreaList o = it.next();
					if (touched.contains(o)) continue;
					// Untouched: transfer from open to closed
					it.remove();
					closed.add(o);
				}
				
				prev_layer = layer;
			}
			
			closed.addAll(open);
			
			// Insert the new AreaLists if there was any split
			if (closed.size() > 1) {
				r.addAll(closed);
				Utils.log("Splitted AreaList '" + ali.getTitle() + "' into " + closed.size() + " parts.");
				// Update bounding boxes
				for (final AreaList o: closed) o.calculateBoundingBox(null);
				// Batch-insert all new AreaList into the LayerSet
				ali.getLayerSet().addAll(closed);
				// Add the ProjectThing entries and update bounding boxes
				for (final AreaList o: closed) ali.project.getProjectTree().addSibling(ali, o);
				// Remove the original AreaList
				ali.remove2(false);
			} else {
				r.add(ali);
				Utils.log("Left AreaList '" + ali.getTitle() + "' unsplitted.");
			}
		}
		
		return r;
	}

	/** For each area that ali contains, add it to the corresponding area here.*/
	private void add(final AreaList ali) {
		for (final Map.Entry<Long,Area> entry : ali.ht_areas.entrySet()) {
			Area ob_area = entry.getValue();
			final long lid = entry.getKey();
			if (UNLOADED == ob_area) ob_area = ali.loadLayer(lid);
			Area area = (Area)ob_area;
			area = area.createTransformedArea(ali.at);
			// now need to inverse transform it by this.at
			try {
				area = area.createTransformedArea(this.at.createInverse());
			} catch (final NoninvertibleTransformException nte) {
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

	public Area getArea(final Layer la) {
		if (null == la) return null;
		return getArea(la.getId());
	}
	public Area getArea(final long layer_id) {
		Object ob = ht_areas.get(new Long(layer_id));
		if (null != ob) {
			if (UNLOADED == ob) ob = loadLayer(layer_id);
			return (Area)ob;
		}
		return null;
	}



	/** Performs a deep copy of this object, without the links. */
	@Override
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
		final Area a = getArea(layer_id);
		if (null == a) ht_areas.put(layer_id, new Area(area));
		else a.add(area);
		updateInDatabase("points=" + layer_id);
	}

	/** Adds the given ROI, which is expected in world/LayerSet coordinates, to the area present at Layer with id layer_id, or set it if none present yet. */
	public void add(final long layer_id, final ShapeRoi roi) throws NoninvertibleTransformException{
		if (null == roi) return;
		final Area a = getArea(layer_id);
		final Area asr = M.getArea(roi).createTransformedArea(this.at.createInverse());
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
		final Area a = getArea(layer_id);
		if (null == a) return;
		a.subtract(M.getArea(roi).createTransformedArea(this.at.createInverse()));
		calculateBoundingBox(null != layer_set ? layer_set.getLayer(layer_id) : null);
		updateInDatabase("points=" + layer_id);
	}

	/** Subtracts the given ROI, and then creates a new AreaList with identical properties and the content of the subtracted part. Returns null if there is no intersection between sroi and the Area for layer_id. */
	public AreaList part(final long layer_id, final ShapeRoi sroi) throws NoninvertibleTransformException {
		// The Area to subtract, in world coordinates:
		final Area sub = M.getArea(sroi);
		// The area to subtract from:
		final Area a = getArea(layer_id);
		if (null == a || M.isEmpty(a)) return null;
		// The intersection:
		final Area inter = a.createTransformedArea(this.at);
		inter.intersect(sub);
		if (M.isEmpty(inter)) return null;

		// Subtract from this:
		this.subtract(layer_id, sroi);

		// Create new AreaList with the intersection area, and add it to the same LayerSet as this:
		final AreaList ali = new AreaList(this.project, this.title, 0, 0);
		ali.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		ali.visible = this.visible;
		ali.alpha = this.alpha;
		ali.addArea(layer_id, inter);
		this.layer_set.add(ali); // needed to call updateBucket
		ali.calculateBoundingBox(null != layer_set ? layer_set.getLayer(layer_id) : null);

		return ali;
	}

	@Override
	public void keyPressed(final KeyEvent ke) {
		final Object source = ke.getSource();
		if (! (source instanceof DisplayCanvas)) return;
		final DisplayCanvas dc = (DisplayCanvas)source;
		final Layer layer = dc.getDisplay().getLayer();
		final int keyCode = ke.getKeyCode();
		final long layer_id = layer.getId();

		if (KeyEvent.VK_K == keyCode) {
			final Roi roi = dc.getFakeImagePlus().getRoi();
			if (null == roi) return;
			if (!M.isAreaROI(roi)) {
				Utils.log("AreaList only accepts region ROIs, not lines.");
				return;
			}
			final ShapeRoi sroi = new ShapeRoi(roi);

			try {
				final AreaList p = part(layer_id, sroi);
				if (null != p) {
					project.getProjectTree().addSibling(this, p);
				}
				Display.repaint(layer, getBoundingBox(), 5);
				linkPatches();
			} catch (final NoninvertibleTransformException nite) { IJError.print(nite); }
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
	@Override
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
	@Override
	public boolean intersects(final Area area, final double z_first, final double z_last) {
		for (final Map.Entry<Long,Area> entry : ht_areas.entrySet()) {
			final Layer layer = layer_set.getLayer(((Long)entry.getKey()).longValue());
			if (layer.getZ() >= z_first && layer.getZ() <= z_last) {
				final Area a = ((Area)entry.getValue()).createTransformedArea(this.at);
				a.intersect(area);
				final Rectangle r = a.getBounds();
				if (0 != r.width && 0 != r.height) return true;
			}
		}
		return false;
	}

	/** Export all given AreaLists as one per pixel value, what is called a "labels" file; a file dialog is offered to save the image as a tiff stack. */
	static public void exportAsLabels(final List<Displayable> listToPaint, final ij.gui.Roi roi, final float scale, int first_layer, int last_layer, final boolean visible_only, final boolean to_file, final boolean as_amira_labels) {
		// survive everything:
		if (null == listToPaint || 0 == listToPaint.size()) {
			Utils.log("Null or empty list.");
			return;
		}
		if (scale < 0 || scale > 1) {
			Utils.log("Improper scale value. Must be 0 < scale <= 1");
			return;
		}

		// Select the subset to paint
		final ArrayList<AreaList> list = new ArrayList<AreaList>();
		for (final Displayable d : listToPaint) {
			if (visible_only && !d.isVisible()) continue;
			if (d instanceof AreaList) list.add((AreaList)d);
		}

		Utils.log2("exportAsLabels: list.size() is " + list.size());

		// Current AmiraMeshEncoder supports ByteProcessor only: 256 labels max, including background at zero.
		if (as_amira_labels && list.size() > 255) {
			Utils.log("Saving ONLY first 255 AreaLists!\nDiscarded:");
			final StringBuilder sb = new StringBuilder();
			for (final Displayable d : list.subList(255, list.size())) {
				sb.append("    ").append(d.getProject().getShortMeaningfulTitle(d)).append('\n');
			}
			Utils.log(sb.toString());
			final ArrayList<AreaList> li = new ArrayList<AreaList>(list);
			list.clear();
			list.addAll(li.subList(0, 255));
		}

		String path = null;
		if (to_file) {
			final String ext = as_amira_labels ? ".am" : ".tif";
			final File f = Utils.chooseFile("labels", ext);
			if (null == f) return;
			path = f.getAbsolutePath().replace('\\','/');
		}

		final LayerSet layer_set = list.get(0).getLayerSet();
		if (first_layer > last_layer) {
			final int tmp = first_layer;
			first_layer = last_layer;
			last_layer = tmp;
			if (first_layer < 0) first_layer = 0;
			if (last_layer >= layer_set.size()) last_layer = layer_set.size()-1;
		}
		// Create image according to roi and scale
		final int width, height;
		final Rectangle broi;
		if (null == roi) {
			broi = null;
			width = (int)(layer_set.getLayerWidth() * scale);
			height = (int)(layer_set.getLayerHeight() * scale);
		} else {
			broi = roi.getBounds();
			width = (int)(broi.width * scale);
			height = (int)(broi.height * scale);
		}

		// Compute highest label value, which affects of course the stack image type
		final TreeSet<Integer> label_values = new TreeSet<Integer>();
		for (final Displayable d : list) {
			final String label = d.getProperty("label");
			if (null != label) label_values.add(Integer.parseInt(label));
		}
		int lowest=0, highest=0;
		if (label_values.size() > 0) {
			lowest = label_values.first();
			highest = label_values.last();
		}
		final int n_non_labeled = list.size() - label_values.size();
		final int max_label_value = highest + n_non_labeled;

		int type_ = ImagePlus.GRAY8;
		if (max_label_value > 255) {
			type_ = ImagePlus.GRAY16;
			if (max_label_value > 65535) {
				type_ = ImagePlus.GRAY32;
			}
		}
		final int type = type_;

		final ImageStack stack = new ImageStack(width, height);

		final Calibration cal = layer_set.getCalibration();

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
		final HashMap<AreaList,Integer> labels = new HashMap<AreaList,Integer>();
		for (final AreaList d : list) {
			final String slabel = d.getProperty("label");
			int label;
			if (null != slabel) {
				label = Integer.parseInt(slabel);
			} else {
				label = (++highest); // 0 is background
			}
			labels.put(d, label);
		}

		final ExecutorService exec = Utils.newFixedThreadPool("labels");
		final Map<Integer,ImageProcessor> slices = Collections.synchronizedMap(new TreeMap<Integer,ImageProcessor>());
		final List<Future<?>> fus = new ArrayList<Future<?>>();
		final List<Layer> layers = layer_set.getLayers().subList(first_layer, last_layer+1);

		for (int k = 0; k < layers.size(); k++) {
			final Layer la = layers.get(k);
			final int slice = k;
			fus.add(exec.submit(new Runnable() {
				@Override
				public void run() {
					Utils.showProgress(slice / len);

					final ImageProcessor ip;

					if (ImagePlus.GRAY8 == type) {
						final BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
						final Graphics2D g = bi.createGraphics();

						for (final AreaList ali : list) {
							final Area area = ali.getArea(la);
							if (null == area || area.isEmpty()) continue;
							// Transform: the scale and the roi
							final AffineTransform aff = new AffineTransform();
							// reverse order of transformations:
							/* 3 - To scale: */ if (1 != scale) aff.scale(scale, scale);
							/* 2 - To roi coordinates: */ if (null != broi) aff.translate(-broi.x, -broi.y);
							/* 1 - To world coordinates: */ aff.concatenate(ali.at);
							g.setTransform(aff);
							final int label = labels.get(ali);
							g.setColor(new Color(label, label, label));
							g.fill(area);
						}
						g.dispose();
						ip = new ByteProcessor(bi);
						bi.flush();

					} else if (ImagePlus.GRAY16 == type) {
						final USHORTPaint paint = new USHORTPaint((short)0);
						final BufferedImage bi = new BufferedImage(paint.getComponentColorModel(), paint.getComponentColorModel().createCompatibleWritableRaster(width, height), false, null);
						final Graphics2D g = bi.createGraphics();
						//final ColorSpace ugray = ColorSpace.getInstance(ColorSpace.CS_GRAY);

						int painted = 0;

						for (final AreaList ali : list) {
							final Area area = ali.getArea(la);
							if (null == area || area.isEmpty()) continue;
							// Transform: the scale and the roi
							final AffineTransform aff = new AffineTransform();
							// reverse order of transformations:
							/* 3 - To scale: */ if (1 != scale) aff.scale(scale, scale);
							/* 2 - To roi coordinates: */ if (null != broi) aff.translate(-broi.x, -broi.y);
							/* 1 - To world coordinates: */ aff.concatenate(ali.at);
							// Fill
							g.setTransform(aff);

							// The color doesn't work: paints in a stretched 8-bit mode
							//g.setColor(new Color(ugray, new float[]{((float)labels.get(d)) / range}, 1));

							Utils.log2("value: " + labels.get(ali).shortValue());
							paint.setValue(labels.get(ali).shortValue());
							g.setPaint(paint);

							g.fill(area); //.createTransformedArea(aff));

							painted += 1;
						}
						g.dispose();
						ip = new ShortProcessor(bi);
						bi.flush();

						Utils.log2("painted: " + painted);

					} else {
						// Option 1: could use the same as above, but shifted by 65536, so that 65537 is 1, 65538 is 2, etc.
						//           and keep doing it until no more need to be shifted.
						//           The PROBLEM: cannot keep the order without complicated gymnastics to remember
						//           which label in which image has to be merged to the final image, which prevent
						//           a simple one-pass blitter.
						//
						// Option 2: paint each arealist, extract the image, use it as a mask for filling:

						final FloatProcessor fp = new FloatProcessor(width, height);
						final float[] fpix = (float[]) fp.getPixels();
						ip = fp;

						final BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
						final Graphics2D gbi = bi.createGraphics();

						for (final AreaList ali : list) {
							final Area area = ali.getArea(la);
							if (null == area || area.isEmpty()) {
								continue;
							}
							// Transform: the scale and the roi
							// reverse order of transformations:
							final AffineTransform aff = new AffineTransform();
							/* 3 - To scale: */ if (1 != scale) aff.scale(scale, scale);
							/* 2 - To ROI coordinates: */ if (null != broi) aff.translate(-broi.x, -broi.y);
							/* 1 - To world coordinates: */ aff.concatenate(ali.at);
							final Area s = area.createTransformedArea(aff);
							final Rectangle sBounds = s.getBounds();
							// Need to paint at all?
							if (0 == sBounds.width || 0 == sBounds.height || !sBounds.intersects(0, 0, width, height)) continue;
							// Paint shape
							gbi.setColor(Color.white);
							gbi.fill(s);
							// Read out painted region
							final int x0 = Math.max(0, sBounds.x);
							final int y0 = Math.max(0, sBounds.y);
							final int xN = Math.min(width, sBounds.x + sBounds.width);
							final int yN = Math.min(height, sBounds.y + sBounds.height);
							// Get the array
							final byte[] bpix = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
							final float value = labels.get(ali);
							// For every non-black pixel, set a 'value' pixel in the FloatProcessor
							for (int y = y0; y < yN; ++y) {
								for (int x = x0; x < xN; ++x) {
									final int pos = y * width + x;
									if (0 == bpix[pos]) continue; // black
									fpix[pos] = value;
								}
							}
							// Clear image region
							gbi.setColor(Color.black);
							gbi.fill(s);
						}
						gbi.dispose();
						bi.flush();
					}

					slices.put(slice, ip);
				}
			}));
		}

		Utils.wait(fus);
		exec.shutdownNow();

		for (final Map.Entry<Integer,ImageProcessor> e : slices.entrySet()) {
			final Layer la = layers.get(e.getKey());
			stack.addSlice(la.getZ() * cal.pixelWidth + "", e.getValue());
			if (ImagePlus.GRAY8 != type) {
				e.getValue().setMinAndMax(lowest, highest);
			}
		}

		Utils.showProgress(1);
		// Save via file dialog:
		final ImagePlus imp = new ImagePlus("Labels", stack);
		if (as_amira_labels) imp.setProperty("Info", amira_params);
		imp.setCalibration(layer_set.getCalibrationCopy());
		if (to_file) {
			if (as_amira_labels) {
				final AmiraMeshEncoder ame = new AmiraMeshEncoder(path);
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
		if (null == rt) rt = Utils.createResultsTable("AreaList results",
				new String[]{"id", "volume", "LB-surface", "UBs-surface",
				"UB-surface", "AVGs-surface", "AVG-surface", "max diameter",
				"Sum of tops", "name-id", "Xcm", "Ycm", "Zcm"});
		rt.incrementCounter();
		rt.addLabel("units", layer_set.getCalibration().getUnit());
		rt.addValue(0, this.id);
		final double[] m = measure();
		rt.addValue(1, m[0]); // aprox. volume
		rt.addValue(2, m[1]); // lower bound surface
		rt.addValue(3, m[2]); // upper bound surface smoothed
		rt.addValue(4, m[3]); // upper bound surface
		rt.addValue(5, (m[1] + m[2]) / 2); // average of LB and UBs
		rt.addValue(6, (m[1] + m[3]) / 2); // average of LB and UB
		rt.addValue(7, m[4]); // max diameter
		rt.addValue(8, m[5]); // sum of all cappings (tops and bottoms)
		rt.addValue(9, getNameId());
		rt.addValue(10, m[6]); // X of the center of mass
		rt.addValue(11, m[7]); // Y
		rt.addValue(12, m[8]); // Z
		return rt;
	}

	/** Returns a double array with 0=volume, 1=lower_bound_surface, 2=upper_bound_surface_smoothed, 3=upper_bound_surface, 4=max_diameter, 5=all_tops_and_bottoms
	 *  All measures are approximate.
	 *  [0] Volume: sum(area * thickness) for all sections
	 *  [1] Lower Bound Surface: measure area per section, compute radius of circumference of identical area, compute then area of the sides of the truncated cone of height thickness, for each section. Plus top and bottom areas when visiting sections without a painted area.
	 *  [2] Upper Bound Surface Smoothed: measure smoothed perimeter lengths per section, multiply by thickness to get lateral area. Plus tops and bottoms.
	 *  [3] Upper Bound Surface: measure raw pixelated perimeter lengths per section, multiply by thickness to get lateral area. Plus top and bottoms.
	 *  [4] Maximum diameter: longest distance between any two points in the contours of all painted areas.
	 *  [5] All tops and bottoms: Sum of all included surface areas that are not part of side area.
	 *  [6] X coordinate of the center of mass.
	 *  [7] Y coordinate of the center of mass.
	 *  [8] Z coordinate of the center of mass. */
	public double[] measure() {
		if (0 == ht_areas.size()) return new double[6]; // zeros

		// prepare suitable transform
		final AffineTransform aff = new AffineTransform(this.at);
		AffineTransform aff2 = new AffineTransform();
		// remove translation (for no reason other than historical, and that it may
		//   help avoid numerical overflows)
		final Rectangle box = getBoundingBox(null);
		aff2.translate(-box.x, -box.y);
		aff.preConcatenate(aff2);
		aff2 = null;

		double volume = 0;
		double lower_bound_surface_h = 0;
		double upper_bound_surface = 0;
		double upper_bound_surface_smoothed = 0;
		double prev_surface = 0;
		double prev_perimeter = 0;
		double prev_smooth_perimeter = 0;
		double prev_thickness = 0;
		double all_tops_and_bottoms = 0;  // i.e. surface area that is not part of the side area

		final Calibration cal = layer_set.getCalibration();
		final double pixelWidth = cal.pixelWidth;
		final double pixelHeight = cal.pixelHeight;

		// Put areas in order of their layer index:
		final TreeMap<Integer,Area> ias = new TreeMap<Integer,Area>();
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			final int ilayer = layer_set.indexOf(layer_set.getLayer(e.getKey()));
			if (-1 == ilayer) {
				Utils.log("Could not find a layer with id " + e.getKey());
				continue;
			}
			ias.put(ilayer, e.getValue());
		}

		final ArrayList<Layer> layers = layer_set.getLayers();

		int last_layer_index = -1;

		final ArrayList<Point3f> points = new ArrayList<Point3f>();
		final float[] coords = new float[6];
		final float fpixelWidth = (float) pixelWidth;
		final float fpixelHeight = (float) pixelHeight;

		final float resampling_delta = project.getProperty("measurement_resampling_delta", 1.0f);

		// for each area, measure its area and its perimeter, to compute volume and surface
		for (final Map.Entry<Integer,Area> e : ias.entrySet()) {

			// fetch Layer
			final int layer_index = e.getKey();
			if (layer_index > layers.size()) {
				Utils.log("Could not find a layer at index " + layer_index);
				continue;
			}

			final Layer la = layers.get(layer_index);

			// fetch Area
			Area area = e.getValue();
			if (UNLOADED == area) area = loadLayer(la.getId());
			// Transform area to world coordinates
			area = area.createTransformedArea(aff);

			// measure surface
			final double pixel_area = Math.abs(AreaCalculations.area(area.getPathIterator(null)));
			final double surface = pixel_area * pixelWidth * pixelHeight;

			//Utils.log2(layer_index + " pixel_area: " + pixel_area + "  surface " + surface);

			// measure volume
			final double thickness = la.getThickness() * pixelWidth;// the last one is NOT pixelDepth because layer thickness and Z are in pixels
			volume += surface * thickness;

			final double pix_perimeter = AreaCalculations.circumference(area.getPathIterator(null));
			final double perimeter = pix_perimeter * pixelWidth;

			double smooth_perimeter = 0;

			// smoothed perimeter:
			{
				double smooth_pix_perimeter = 0;
				for (final Polygon pol : M.getPolygons(area)) {

					try {
						// Should use VectorString2D, but takes for ever -- bug in resample?
						// And VectorString3D is likely not respecting the 'closed' flag for resampling.
						// Also, VectorString3D gets stuck in an infinite loop if the sequence is 6 points!
						//VectorString3D v = new VectorString3D(xp, yp, new double[pol.npoints], true);



						if (pol.npoints < 5) {
							// No point in smoothing out such a short polygon:
							// (Plus can't convolve it with a gaussian that needs 5 points adjacent)
							smooth_perimeter += new PolygonRoi(pol, PolygonRoi.POLYGON).getLength();
							continue;
						}
						/*
						// Works but it is not the best smoothing of the Area's countour
						double[] xp = new double[pol.npoints];
						double[] yp = new double[pol.npoints];
						for (int p=0; p<pol.npoints; p++) {
							xp[p] = pol.xpoints[p];
							yp[p] = pol.ypoints[p];
						}
						VectorString2D v = new VectorString2D(xp, yp, 0, true);
						v.resample(resampling_delta);
						smooth_pix_perimeter += v.length() * resampling_delta;
						*/

						// The best solution I've found:
						// 1. Run getInterpolatedPolygon with an interval of 1 to get a point at every pixel
						// 2. convolve with a gaussian
						// Resample to 1 so that at every one pixel of the contour there is a point
						FloatPolygon fpol = new FloatPolygon(new float[pol.npoints], new float[pol.npoints], pol.npoints);
						for (int i=0; i<pol.npoints; ++i) {
							fpol.xpoints[i] = pol.xpoints[i];
							fpol.ypoints[i] = pol.ypoints[i];
						}
						fpol = M.createInterpolatedPolygon(fpol, 1, false);
						final FloatPolygon fp;
						if (fpol.npoints < 5) {
							smooth_pix_perimeter += fpol.getLength(false);
							fp = fpol;
						} else {
							// Convolve with a sigma of 1 to smooth it out
							final FloatPolygon gpol = new FloatPolygon(new float[fpol.npoints], new float[fpol.npoints], fpol.npoints);
							final CircularSequence seq = new CircularSequence(fpol.npoints);
							M.convolveGaussianSigma1(fpol.xpoints, gpol.xpoints, seq);
							M.convolveGaussianSigma1(fpol.ypoints, gpol.ypoints, seq);
							// Resample it to the desired resolution (also facilitates measurement: npoints * resampling_delta)
							if (gpol.npoints > resampling_delta) {
								fp = M.createInterpolatedPolygon(gpol, resampling_delta, false);
							} else {
								fp = gpol;
							}
							// Measure perimeter: last line segment is potentially shorter or longer than resampling_delta
							smooth_pix_perimeter += (fp.npoints -1) * resampling_delta
													+ Math.sqrt(  Math.pow(fp.xpoints[0] - fp.xpoints[fp.npoints-1], 2)
																+ Math.pow(fp.ypoints[0] - fp.ypoints[fp.npoints-1], 2));
						}

						// TEST:
						//ij.plugin.frame.RoiManager.getInstance().addRoi(new PolygonRoi(fp, PolygonRoi.POLYGON));

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
					} catch (final Exception le) { le.printStackTrace(); }
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
				all_tops_and_bottoms += surface;
			} else if (layer_index - last_layer_index > 1) {
				// End of a continuous set ...
				// Sum the last surface and its side:
				lower_bound_surface_h += prev_surface + prev_thickness * 2 * Math.sqrt(prev_surface * Math.PI); //   (2x + 2x) / 2   ==   2x
				upper_bound_surface += prev_surface + prev_perimeter * prev_thickness;
				upper_bound_surface_smoothed += prev_surface + prev_smooth_perimeter * prev_thickness;
				all_tops_and_bottoms += prev_surface;

				// ... and start of a new set
				lower_bound_surface_h += surface;
				upper_bound_surface += surface;
				upper_bound_surface_smoothed += surface;
				all_tops_and_bottoms += surface;
			} else {
				// Continuation of a set: use this Area and the previous as continuous
				final double diff_surface = Math.abs(prev_surface - surface);

				upper_bound_surface += prev_perimeter * (prev_thickness / 2)
						     + perimeter * (prev_thickness / 2)
						     + diff_surface;

				upper_bound_surface_smoothed += prev_smooth_perimeter * (prev_thickness / 2)
					                      + smooth_perimeter * (prev_thickness / 2)
							      + diff_surface;

				// Compute area of the mantle of the truncated cone defined by the radiuses of the circles of same area as the two areas
				// PI * s * (r1 + r2) where s is the hypothenusa
				final double r1 = Math.sqrt(prev_surface / Math.PI);
				final double r2 = Math.sqrt(surface / Math.PI);
				final double hypothenusa = Math.sqrt(Math.pow(Math.abs(r1 - r2), 2) + Math.pow(thickness, 2));
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
			final float z = (float) la.getZ();
			for (final PathIterator pit = area.getPathIterator(null); !pit.isDone(); pit.next()) {
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
		all_tops_and_bottoms += prev_surface;

		// Compute maximum diameter
		final boolean measure_largest_diameter = project.getBooleanProperty("measure_largest_diameter");
		double max_diameter_sq = measure_largest_diameter ? 0 : Double.NaN;
		final int lp = points.size();
		final Point3f c;
		if (lp > 0) {
			c = new Point3f(points.get(0)); // center of mass
			for (int i=0; i<lp; i++) {
				final Point3f p = points.get(i);
				if (measure_largest_diameter) {
					for (int j=i; j<lp; j++) {
						final double len = p.distanceSquared(points.get(j));
						if (len > max_diameter_sq) max_diameter_sq = len;
					}
				}
				if (0 == i) continue;
				c.x += p.x;
				c.y += p.y;
				c.z += p.z;
			}
		} else {
			c = new Point3f(Float.NaN, Float.NaN, Float.NaN);
		}
		// Translate the center of mass
		c.x = box.x + c.x / lp;
		c.y = box.y + c.y / lp;
		c.z /= lp;

		return new double[]{volume, lower_bound_surface_h, upper_bound_surface_smoothed,
				upper_bound_surface, Math.sqrt(max_diameter_sq), all_tops_and_bottoms,
				c.x, c.y, c.z};
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
		@Override
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
	@Override
	synchronized public boolean crop(final List<Layer> range) {
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
		final ImageProcessor ref_ip = Utils.createProcessor(type, 2, 2);
		if (null == ref_ip) {
			Utils.log("AreaList.getStack: Unknown type " + type);
			return null;
		}
		final Rectangle b = getBoundingBox();
		final int w = (int)(0.5 + b.width * scale);
		final int h = (int)(0.5 + b.height * scale);
		final ImageStack stack = new ImageStack(w, h);
		for (final Layer la : getLayerRange()) {
			final Area area = getArea(la);
			final double z = layer.getZ();
			project.getLoader().releaseToFit(w * h * 10);
			final ImageProcessor ip = ref_ip.createProcessor(w, h);
			if (null == area) {
				stack.addSlice(Double.toString(z), ip);
				continue;
			}
			// Create a ROI from the area at Layer la:
			final AffineTransform aff = getAffineTransformCopy();
			aff.translate(-b.x, -b.y);
			aff.scale(scale, scale);
			final ShapeRoi roi = new ShapeRoi(area.createTransformedArea(aff));
			// Create a cropped snapshot of the images at Layer la under the area:
			final ImageProcessor flat = Patch.makeFlatImage(type, la, b, scale, la.getAll(Patch.class), Color.black);
			flat.setRoi(roi);
			final Rectangle rb = roi.getBounds();
			ip.insert(flat.crop(), rb.x, rb.y);
			// Clear the outside
			final ImagePlus bimp = new ImagePlus("", ip);
			bimp.setRoi(roi);
			ip.setValue(0);
			ip.setBackgroundValue(0);
			IJ.run(bimp, "Clear Outside", "");

			stack.addSlice(Double.toString(z), ip);
		}

		final ImagePlus imp = new ImagePlus("AreaList stack for " + this, stack);
		imp.setCalibration(layer_set.getCalibrationCopy());
		return imp;
	}

	@Override
	public List<Area> getAreas(final Layer layer, final Rectangle box) {
		final Area a = (Area) ht_areas.get(layer.getId());
		if (null == a) return null;
		final ArrayList<Area> l = new ArrayList<Area>();
		l.add(a);
		return l;
	}

	@Override
	protected boolean layerRemoved(final Layer la) {
		super.layerRemoved(la);
		ht_areas.remove(la.getId());
		return true;
	}

	@Override
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

	@Override
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

	/** In world coordinates, a copy of the area at {@code layer}. May be null. */
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
		} catch (final NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}
	}

	@Override
	public ResultsTable measureAreas(ResultsTable rt) {
		if (0 == ht_areas.size()) return rt;
		if (null == rt) rt = Utils.createResultsTable("Area results", new String[]{"id", "name-id", "layer index", "area"});
		final double nameId = getNameId();
		final Calibration cal = layer_set.getCalibration();
		final String units = cal.getUnit();
		// Sort by Layer
		final TreeMap<Layer,Area> sm = new TreeMap<Layer, Area>(Layer.COMPARATOR);
		for (final Map.Entry<Long,Area> e : ht_areas.entrySet()) {
			sm.put(layer_set.getLayer(e.getKey()), e.getValue());
		}
		for (final Map.Entry<Layer,Area> e : sm.entrySet()) {
			final Area area = e.getValue();
			if (area.isEmpty()) continue;
			rt.incrementCounter();
			rt.addLabel("units", units);
			rt.addValue(0, this.id);
			rt.addValue(1, nameId);
			rt.addValue(2, layer_set.indexOf(e.getKey()) + 1); // 1-based
			// measure surface
			final double pixel_area = Math.abs(AreaCalculations.area(area.createTransformedArea(this.at).getPathIterator(null)));
			final double surface = pixel_area * cal.pixelWidth * cal.pixelHeight;
			rt.addValue(3, surface);
		}
		return rt;
	}

	/** Interpolate areas between the given first and last layers,
	 * both of which must contain an area.
	 *
	 * @return false if the interpolation could not be done.
	 * @throws Exception */
	public boolean interpolate(final Layer first, final Layer last, final boolean always_use_distance_map) throws Exception {

		int i1 = layer_set.indexOf(first);
		int i2 = layer_set.indexOf(last);
		if (i1 > i2) {
			final int tmp = i1;
			i1 = i2;
			i2 = tmp;
		}
		final List<Layer> range = layer_set.getLayers().subList(i1, i2+1);

		Area start = null;
		int istart = 0;
		int inext = -1;

		final HashSet<Layer> touched = new HashSet<Layer>();

		for (final Layer la : range) {
			inext++;
			final Area next = getArea(la);
			if (null == next || next.isEmpty()) continue;
			if (null == start || 0 == inext - istart -1) { // skip for first area or for no space in between
				start = next;
				istart = inext;
				continue;
			}
			// Interpolate from start to next
			Area[] as = always_use_distance_map? null : AreaUtils.singularInterpolation(start, next, inext - istart -1);

			if (null == as) {
				// NOT SINGULAR: must use BinaryInterpolation2D
				as = AreaUtils.manyToManyInterpolation(start, next, inext - istart -1);
			}
			if (null != as) {
				for (int k=0; k<as.length; k++) {
					final Layer la2 = range.get(k + istart + 1);
					addArea(la2.getId(), as[k]);
					touched.add(la2);
				}
			}

			// Prepare next interval
			start = next;
			istart = inext;
		}

		for (final Layer la : touched) calculateBoundingBox(la);

		return true;
	}
}
