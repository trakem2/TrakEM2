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


import ij.gui.OvalRoi;
import ij.gui.ShapeRoi;
import ij.gui.Toolbar;
import ij.gui.GenericDialog;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.ImagePlus;
import ij.ImageStack;

import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.render3d.Perimeter2D;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.Shape;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Composite;
import java.awt.AlphaComposite;

import marchingcubes.MCTriangulator;
import isosurface.Triangulator;

import javax.vecmath.Point3f;

/** A list of brush painted areas similar to a set of labelfields in Amira.
 * 
 * For each layer where painting has been done, there is an entry in the ht_areas Hashtable that contains the layer's id as a Long, and a java.awt.geom.Area object.
 * Area objects are stored with the top left 0,0 of the LayerSet as their 0,0 coordinate. This is out of necessity: this class x,y,with,height (defined in superclass Displayable) are but the bounding box of the last selected Area in the Hashtable. TODO the latter must change because it won't work properly for multiple displays showing different Layers of the same LayerSet
 *
 */
public class AreaList extends ZDisplayable {

	/** Contains the table of layer ids and their associated Area object.*/
	private Hashtable ht_areas = new Hashtable();

	/** Flag to signal dynamic loading from the database for the Area of a given layer id in the ht_areas Hashtable. */
	static private Object UNLOADED = new Object();

	/** Flag to repaint faster even if the object is selected. */
	static private boolean brushing = false;

	/** Paint as outlines (false) or as solid areas (false; default).*/
	private boolean fill_paint = false;

	public AreaList(Project project, String title, double x, double y) {
		super(project, title, x, y);
		addToDatabase();
	}

	/** Reconstruct from XML. */
	public AreaList(Project project, long id, Hashtable ht_attributes, Hashtable ht_links) {
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
	public AreaList(Project project, long id, String title, double x, double y, double width, double height, float alpha, boolean visible, Color color, boolean locked, ArrayList al_ul) { // al_ul contains Long() wrapping layer ids
		super(project, id, title, x, y, locked);
		this.visible = visible;
		this.alpha = alpha;
		this.width = width;
		this.height = height;
		this.visible = visible;
		this.color = color;
		for (Iterator it = al_ul.iterator(); it.hasNext(); ) {
			ht_areas.put(it.next(), AreaList.UNLOADED); // assumes al_ul contains only Long instances wrapping layer_id long values
		}
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		Object ob = ht_areas.get(new Long(active_layer.getId()));
		if (null == ob) {
			return;
		}
		if (AreaList.UNLOADED.equals(ob)) {
			ob = loadLayer(active_layer.getId());
			if (null == ob) {
				return;
			}
		}
		final Area area = (Area)ob;

		g.setColor(this.color);
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		if (fill_paint) {
			g.fill(area.createTransformedArea(this.at));
		} else {
			g.draw(area.createTransformedArea(this.at));  // the contour only
		}
		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	public void paint(Graphics g, final Layer current) {
		if (null == current) return;
		if (!this.visible) return;
		Object ob = ht_areas.get(new Long(current.getId()));
		if (null == ob) return;
		if (AreaList.UNLOADED.equals(ob)) {
			ob = loadLayer(current.getId());
			if (null == ob) return;
		}
		final Area area = (Area)ob;
		g.setColor(this.color);
		final Graphics2D g2d = (Graphics2D)g;
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g2d.getComposite();
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		if (fill_paint) {
			g2d.fill(area.createTransformedArea(this.at));
		} else {
			g2d.draw(area.createTransformedArea(this.at));  // the contour only
		}
		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g2d.setComposite(original_composite);
		}
	}

	public void transformPoints(Layer layer, double dx, double dy, double rot, double xo, double yo) {
		Utils.log("AreaList.transformPoints: not implemented yet.");
	}

	/** Returns the layer of lowest Z coordinate where this ZDisplayable has a point in. */
	public Layer getFirstLayer() {
		double min_z = Double.MAX_VALUE;
		Layer first_layer = null;
		for (Iterator it = ht_areas.keySet().iterator(); it.hasNext(); ) {
			Layer la = this.layer_set.getLayer(((Long)it.next()).longValue());
			double z = la.getZ();
			if (z < min_z) {
				min_z = z;
				first_layer = la;
			}
		}
		return first_layer;
	}

	public void linkPatches() {
		unlinkAll(Patch.class);
		// cheap way: intersection of the pathches' bounding box with the area
		Rectangle r = new Rectangle();
		for (Iterator it = ht_areas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Layer la = this.layer_set.getLayer(((Long)entry.getKey()).longValue());
			Area area = (Area)entry.getValue();
			area = area.createTransformedArea(this.at);
			for (Iterator dit = la.getDisplayables(Patch.class).iterator(); dit.hasNext(); ) {
				Displayable d = (Displayable)dit.next();
				r = d.getBoundingBox(r);
				r.translate(-(int)this.x, -(int)this.y);
				if (area.intersects(r)) {
					link(d, true);
				}
			}
		}
	}

	/** Returns whether the point x,y is contained in this object at the given Layer. */
	public boolean contains(Layer layer, int x, int y) {
		Object ob = ht_areas.get(new Long(layer.getId()));
		if (null == ob) return false;
		if (AreaList.UNLOADED.equals(ob)) {
			ob = loadLayer(layer.getId());
			if (null == ob) return false;
		}
		Area area = (Area)ob;
		if (!this.at.isIdentity()) area = area.createTransformedArea(this.at);
		return area.contains(x, y);
	}

	public boolean isDeletable() {
		if (0 == ht_areas.size()) return true;
		return false;
	}

	private boolean is_new = false;

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		long lid = Display.getFrontLayer(this.project).getId(); // isn't this.layer pointing to the current layer always?
		Object ob = ht_areas.get(new Long(lid));
		Area area = null;
		if (null == ob) {
			area = new Area();
			ht_areas.put(new Long(lid), area);
			is_new = true;
			this.width = layer_set.getLayerWidth(); // will be set properly at mouse release
			this.height = layer_set.getLayerHeight(); // without this, the first brush slash doesn't get painted because the isOutOfRepaintingClip returns true
		} else {
			if (AreaList.UNLOADED.equals(ob)) {
				ob = loadLayer(lid);
				if (null == ob) return;
			}
			area = (Area)ob;
		}

		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
		}

		if (me.isShiftDown()) {
			// fill in a hole if the clicked point lays within one
			Polygon pol = null;
			if (area.contains(x_p, y_p)) {
				if (me.isAltDown()) {
					// fill-remove
					pol = findPath(area, x_p, y_p); // no null check, exists for sure
					area.subtract(new Area(pol));
				}
			} else if (!me.isAltDown()) {
				// fill-add
				pol = findPath(area, x_p, y_p);
				if (null != pol) {
					area.add(new Area(pol)); // may not exist
				}
			}
			if (null != pol) {
				final Rectangle r_pol = transformRectangle(pol.getBounds());
				Display.repaint(Display.getFrontLayer(), r_pol, 1);
				updateInDatabase("points=" + lid);
			}
		} else {
			new BrushThread(area);
			brushing = true;
		}
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
		// nothing, the BrushThread handles it
		//irrelevant//if (ProjectToolbar.getToolId() == ProjectToolbar.PEN) brushing = true;
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		if (!brushing) {
			// nothing changed
			//Utils.log("AreaList mouseReleased: no brushing");
			return;
		}
		brushing = false;
		if (null != last) last.quit();
		long lid = Display.getFrontLayer(this.project).getId();
		Object ob = ht_areas.get(new Long(lid));
		Area area = null;
		if (null != ob) {
			area = (Area)ob;
		}
		// check if empty. If so, remove
		Rectangle bounds = area.getBounds();
		if (0 == bounds.width && 0 == bounds.height) {
			ht_areas.remove(new Long(lid));
			Utils.log("removing empty area");
		}

		final boolean translated = calculateBoundingBox(); // will reset all areas' top-left coordinates, and update the database if necessary
		if (translated) {
			// update for all, since the bounding box has changed
			updateInDatabase("all_points");
		} else {
			// update the points for the current layer only
			updateInDatabase("points=" + lid);
		}

		// Repaint instead the last rectangle, to erase the circle
		if (null != r_old) {
			Display.repaint(Display.getFrontLayer(), r_old, 3, false);
			r_old = null;
		}
		// repaint the navigator and snapshot
		Display.repaint(Display.getFrontLayer(), this);
	}

	/** Calculate box, make this width,height be that of the box, and translate all areas to fit in. @param lid is the currently active Layer. */ //This is the only road to sanity for ZDisplayable objects.
	public boolean calculateBoundingBox() {
		// forget it if this has been done once already, for at the moment it would work only for translations, not any other types of transforms. TODO: need to fix this somehow, generates repainting problems.
		//if (this.at.getType() != AffineTransform.TYPE_TRANSLATION) return false; // meaning, there's more bits in the type than just the translation
		// check preconditions
		if (0 == ht_areas.size()) return false;
		Area[] area = new Area[ht_areas.size()];
		Map.Entry[] entry = new Map.Entry[area.length];
		ht_areas.entrySet().toArray(entry);
		ht_areas.values().toArray(area);
		Rectangle[] b = new Rectangle[area.length];
		Rectangle box = null;
		for (int i=0; i<area.length; i++) {
			b[i] = area[i].getBounds();
			if (null == box) box = (Rectangle)b[i].clone();
			else box.add(b[i]);
		}
		if (null == box) return false; // empty AreaList
		final AffineTransform atb = new AffineTransform();
		atb.translate(-box.x, -box.y); // make local to overall box, so that box starts now at 0,0
		for (int i=0; i<area.length; i++) {
			entry[i].setValue(area[i].createTransformedArea(atb));
		}
		//this.translate(box.x, box.y);
		this.at.translate(box.x, box.y);
		updateInDatabase("transform");
		this.width = box.width;
		this.height = box.height;
		updateInDatabase("position+dimensions");
		if (0 != box.x || 0 != box.y) {
			return true;
		}
		return false;
	}

	private BrushThread last = null;
	static private Rectangle r_old = null;

	/** Modeled after the ij.gui.RoiBrush class from ImageJ. */
	private class BrushThread extends Thread {
		private Area area;
		private Point previous_p = null;
		private boolean paint = true;
		private int brush_size;
		private Area brush;
		BrushThread(Area area) {
			super("BrushThread");
			setPriority(Thread.NORM_PRIORITY);
			this.area = area;
			// cancel other BrushThreads if any
			if (null != last) last.paint = false;
			last = this;
			brush_size = ProjectToolbar.getBrushSize();
			brush = makeBrush(brush_size);
			if (null == brush) return;
			start();
		}
		public void quit() {
			this.paint = false;
		}
		/** For best smoothness, each mouse dragged event should be captured!*/
		public void run() {
			// create brush
			final DisplayCanvas dc = Display.getFront().getCanvas();
			Point p;
			final AffineTransform atb = new AffineTransform();
			int flags;
			final int leftClick=16, alt=9;
			double sqrt2 = Math.sqrt(2) + 0.001;
			while (paint) {
				flags = dc.getModifiers();
				// detect mouse up
				if (0 == (flags & leftClick)) {
					quit();
					return;
				}
				p = dc.getCursorLoc(); // as offscreen coords
				if (p.equals(previous_p) /*|| (null != previous_p && p.distance(previous_p) < brush_size/5) */) {
					try { Thread.sleep(1); } catch (InterruptedException ie) {}
					continue;
				}
				// bring to offscreen position of the mouse
				atb.translate(p.x, p.y);
				Area slash = brush.createTransformedArea(atb);
				// capture bounds while still in offscreen coordinates
				final Rectangle r = slash.getBounds();
				// bring to the current transform, if any
				if (!at.isIdentity()) {
					try {
						slash = slash.createTransformedArea(at.createInverse());
					} catch (NoninvertibleTransformException nite) {
						new IJError(nite);
					}
				}
				if (0 == (flags & alt)) {
					// no modifiers, just add
					area.add(slash);
				} else {
					// with alt down, substract
					area.subtract(slash);
				}
				previous_p = p;

				final Rectangle copy = (Rectangle)r.clone();
				if (null != r_old) r.add(r_old);
				r_old = copy;

				Display.repaint(Display.getFrontLayer(), r, 3, false); // repaint only the last added slash

				// reset
				atb.setToIdentity();
			}
		}

		/** This method could get tones of improvement, which should be pumped upstream into ImageJ's RoiBrush class which is creating it at every while(true) {} iteration!!!
		 * The returned area has its coordinates centered around 0,0
		 */
		private Area makeBrush(int diameter) {
			if (diameter < 1) return null;
			return new Area(new OvalRoi(-diameter/2, -diameter/2, diameter, diameter).getPolygon());
			// smooth out edges
			/*
			Polygon pol = new OvalRoi(-diameter/2, -diameter/2, diameter, diameter).getPolygon();
			Polygon pol2 = new Polygon();
			// cheap and fast: skip every other point, since all will be square angles
			for (int i=0; i<pol.npoints; i+=2) {
				pol2.addPoint(pol.xpoints[i], pol.ypoints[i]);
			}
			return new Area(pol2);
			// the above works nice, but then the fill and fill-remove don't work properly (there are traces in the edges)
			// Needs a workround: before adding/substracting, enlarge the polygon to have square edges
			*/
		}
	}

	private void updateBounds(Rectangle b) {
		x = b.x;
		y = b.y;
		width = b.width;
		height = b.height;
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_area_list";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_area_list (t2_area)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent); // all ATTLIST of a Displayable
		sb_header.append(indent).append("<!ATTLIST t2_area_list fill_paint NMTOKEN #REQUIRED>\n");
		sb_header.append(indent).append("<!ELEMENT t2_area (t2_path)>\n")
			 .append(indent).append("<!ATTLIST t2_area layer_id NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ELEMENT t2_path EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_path d NMTOKEN #REQUIRED>\n")
		;
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_area_list\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		sb_body.append(in).append("fill_paint=\"").append(fill_paint).append("\"\n");
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"stroke:none;fill-opacity:").append(alpha).append(";fill:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";\"\n");
		sb_body.append(indent).append(">\n");
		for (Iterator it = ht_areas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long lid = ((Long)entry.getKey()).longValue();
			Area area = (Area)entry.getValue();
			sb_body.append(in).append("<t2_area layer_id=\"").append(lid).append("\">\n");
			exportArea(sb_body, in + "\t", area);
			sb_body.append(in).append("</t2_area>\n");
		}
		sb_body.append(indent).append("</t2_area_list>\n");
	}

	/** Exports the given area as a list of SVG path elements with integers only. Only reads SEG_MOVETO, SEG_LINETO and SEG_CLOSE elements, all others ignored (but could be just as easily saved in the SVG path). */
	private void exportArea(StringBuffer sb, String indent, Area area) {
		// I could add detectors for straight lines and thus avoid saving so many points.
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			float[] coords = new float[6];
			int seg_type = pit.currentSegment(coords);
			int x0=0, y0=0;
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
					//Utils.log2("SEG_MOVETO: " + coords[0] + "," + coords[1]); // one point
					x0 = (int)coords[0];
					y0 = (int)coords[1];
					sb.append(indent).append("<t2_path d=\"M ").append(x0).append(" ").append(y0);
					break;
				case PathIterator.SEG_LINETO:
					//Utils.log2("SEG_LINETO: " + coords[0] + "," + coords[1]); // one point
					sb.append(" L ").append((int)coords[0]).append(" ").append((int)coords[1]);
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
	public ArrayList getPaths(long layer_id) {
		Object ob = ht_areas.get(new Long(layer_id));
		if (null == ob) return null;
		if (AreaList.UNLOADED.equals(ob)) {
			ob = loadLayer(layer_id);
			if (null == ob) return null;
		}
		Area area = (Area)ob;
		ArrayList al_paths = new ArrayList();
		ArrayList al_points = null;
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			float[] coords = new float[6];
			int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
					al_points = new ArrayList();
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
	public Hashtable getAllPaths() {
		Hashtable ht = new Hashtable();
		for (Iterator it = ht_areas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			ht.put(entry.getKey(), getPaths(((Long)entry.getKey()).longValue()));
		}
		return ht;
	}

	/** These methods below prepended with double-underscore to mean: they are public, but should not be used except by the TMLHandler. I could put a caller detector but parsing XML code doesn't need any more overhead.*/

	/** For reconstruction from XML. */
	public void __startReconstructing(long lid) {
		ht_areas.put("CURRENT", new Long(lid));
		ht_areas.put(new Long(lid), new GeneralPath(GeneralPath.WIND_EVEN_ODD));
	}
	/** For reconstruction from XML. */
	public void __addPath(String svg_path) {
		GeneralPath gp = (GeneralPath)ht_areas.get(ht_areas.get("CURRENT"));
		svg_path = svg_path.trim();
		while (-1 != svg_path.indexOf("  ")) {
			svg_path = svg_path.replaceAll("  "," "); // make all spaces be single
		}
		int i_M = svg_path.indexOf('M');
		int i_L = svg_path.indexOf('L');
		/*
		String[] s = svg_path.substring(i_M+2, i_L-1).split(" ");
		int x0 = Integer.parseInt(s[0]);
		int y0 = Integer.parseInt(s[1]);
		gp.moveTo(x0, y0);
		int i_L_old = i_L;
		i_L = svg_path.indexOf('L', i_L+1);
		while (-1 != i_L) {
			s = svg_path.substring(i_L_old+2, i_L-1).split(" ");
			gp.lineTo(Integer.parseInt(s[0]), Integer.parseInt(s[1]));
			i_L_old = i_L;
			i_L = svg_path.indexOf('L', i_L_old+1);
		}
		*/
		// the above works, but generates thousands of String objects (slows down a lot)

		// start fast reading
		final char[] data = new char[svg_path.length() + 2]; // padding for the "first = last +2" in readXY
		svg_path.getChars(0, data.length -2, data, 0);
		if ('z' != data[data.length-3]) {
			Utils.log("AreaList: no closing z, ignoring sub path");
			return;
		}
		data[data.length-3] = 'L'; // replacing the closing z for an L, since we read backwards
		final int[] xy = new int[2];
		readXY(data, i_L, xy);
		final int x0 = xy[0];
		final int y0 = xy[1];
		gp.moveTo(x0, y0);
		int first = i_L+1;
		while (-1 != (first = readXY(data, first, xy))) {
			gp.lineTo(xy[0], xy[1]);
		}

		// close loop
		gp.lineTo(x0, y0); //TODO unnecessary?
		gp.closePath();
	}
	/** Assumes all read chars will be digits except for the separator (single white space char), and won't fail (but generate ugly results) when any char is not a digit. */
	private final int readXY(final char[] data, int first, final int[] xy) { // final method: inline
		int last = first;
		char c = data[first];
		while ('L' != c) {
			last++;
			if (data.length == last) return -1;
			c = data[last];
		}
		first = last +2; // the first digit position after the found L, which will be the next first.

		// skip the L and the white space separating <y> and L
		last -= 2;
		c = data[last];

		// the 'y'
		xy[1] = 0;
		int pos = 1;
		while (' ' != c) {
			xy[1] += (((int)c) -48) * pos; // digit zero is char with int value 48
			last--;
			c = data[last];
			pos *= 10;
		}

		// skip separating space
		last--;

		// the 'x'
		c = data[last];
		pos = 1;
		xy[0] = 0;
		while (' ' != c) {
			xy[0] += (((int)c) -48) * pos;
			last--;
			c = data[last];
			pos *= 10;
		}
		return first;
	}
	/** For reconstruction from XML. */
	public void __endReconstructing() {
		Object ob = ht_areas.get("CURRENT");
		if (null != ob) {
			GeneralPath gp = (GeneralPath)ht_areas.get((Long)ob);
			ht_areas.put(ob, new Area(gp));
			ht_areas.remove("CURRENT");
		}
	}

	/** Detect if a point in offscreen coords is not in the area, but lays inside one of its path, which is returned as a Polygon. Otherwise returns null. The given x,y must be already in the Area's coordinate system. */
	private Polygon findPath(Area area, int x, int y) {
		Polygon pol = new Polygon();
		for (PathIterator pit = area.getPathIterator(null); !pit.isDone(); ) {
			float[] coords = new float[6];
			int seg_type = pit.currentSegment(coords);
			switch (seg_type) {
				case PathIterator.SEG_MOVETO:
				case PathIterator.SEG_LINETO:
					pol.addPoint((int)coords[0], (int)coords[1]);
					break;
				case PathIterator.SEG_CLOSE:
					if (pol.contains(x, y)) return pol;
					// else check next
					pol = new Polygon();
					break;
				default:
					Utils.log2("WARNING: unhandled seg type.");
					break;
			}
			pit.next();
			if (pit.isDone()) {
				break;
			}
		}
		return null;
	}

	/** Deform all areas according to the Transform relative to the area in the current Layer. */
	public void setTransform(Transform t) {
		//Utils.log("box: " + x + ", " + y + ", " + width + ", " + height);
		//Utils.log("t:   " + t.x + ", " + t.y + ", " + t.width + ", " + t.height);

		// extract parameters
		// scale:
		double sx = t.width / this.width;
		double sy = t.height / this.height;
		// translation
		double dx = t.x - this.x;
		double dy = t.y - this.y;
		if ( (1 != dx || 1 != dy) || (1 != sx || 1 != sy) || 0 != t.rot) {
			for (Iterator it = ht_areas.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				Area area = (Area)entry.getValue();
				long lid = ((Long)entry.getKey()).longValue();
				// ah... java and it's ridiculous verbosity!
				AffineTransform at = new AffineTransform();
				if (0 != t.rot) {
					//Utils.log2("bounds are " + area.getBounds());
					// make center be 0,0 for rotation
					at.translate(-this.width/2, -this.height/2);
					area.transform(at); //area.createTransformedArea(at);
					at.setToIdentity(); // = new AffineTransform(); // in two steps, otherwise fails
					at.rotate(Math.toRadians(t.rot));
					area.transform(at); // = area.createTransformedArea(at);

					// if correct, bounds should be as in paint method
					//Utils.log2("area.getBounds(): " + area.getBounds());
					// looks FINE, exactly like when painting
					at.setToIdentity();


					at.translate(this.width/2, this.height/2); // translate back
					area.transform(at); // = area.createTransformedArea(at);
					at.setToIdentity(); // = new AffineTransform();
					//entry.setValue(area);
				}
				if (1 != sx || 1 != sy) {
					at.scale(sx, sy);
					area.transform(at); // in place
				}
				//area = area.createTransformedArea(at);
				//entry.setValue(area);

				if (0 != t.rot || 1 != sx || 1 != sy) updateInDatabase("points=" + lid);
			}
			double old_x = t.x;
			double old_y = t.y;
			calculateBoundingBox(); // will change x,y,width,height
			// reset transform data: the t.x, t.y need only to change when there are rotations
			if (0 != t.rot) {
				t.x = this.x + (t.width - this.width)/2 + dx;
				t.y = this.y + (t.height - this.height)/2 + dy;
				//t.x =  this.x + (this.width - t.width/sx)/2 + dx;
				//t.y =  this.y + (this.height - t.height/sy)/2 + dy;
			}
			t.rot = 0;
			t.width = this.width; // should be the same anyway
			t.height = this.height;
			super.setTransform(t); // sets this.x,y to t.x,y
		}
	}

	public boolean paintsAt(final Layer layer) {
		if (!super.paintsAt(layer)) return false;
		if (null != ht_areas.get(new Long(layer.getId()))) return true;
		return false;
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
		processAdjustPropertiesDialog(gd);
		// local proccesing
		final boolean fp = !gd.getNextBoolean();
		final boolean to_all = gd.getNextBoolean();
		if (to_all) {
			for (Iterator it = this.layer_set.getZDisplayables().iterator(); it.hasNext(); ) {
				Object ob = it.next();
				if (ob instanceof AreaList) {
					AreaList ali = (AreaList)ob;
					ali.fill_paint = fp;
					ali.updateInDatabase("fill_paint");
					Display.repaint(this.layer_set, ali, 2);
				}
			}
		} else {
			if (this.fill_paint != fp) {
				this.fill_paint = fp;
				updateInDatabase("fill_paint");
			}
		}
	}

	public boolean isFillPaint() { return this.fill_paint; }

	/** Merge all arealists contained in the ArrayList to the first one found, and remove the others from the project, and only if they belong to the same LayerSet. Returns the merged AreaList object. */
	static public AreaList merge(final ArrayList al) {
		AreaList base = null;
		final ArrayList list = (ArrayList)al.clone();
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (!ob.getClass().equals(AreaList.class)) it.remove();
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
			if (!base.layer_set.equals(ali.layer_set)) it.remove();
		}
		if (list.size() < 1) return null; // nothing to fuse
		if (!Utils.check("Merging AreaList has no undo. Continue?")) return null;
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			// add to base
			AreaList ali = (AreaList)it.next();
			base.add(ali);
			// remove from project
			ali.project.removeProjectThing(ali, false, true, 1); // the node from the Project Tree
			Utils.log2("Merged AreaList " + ali + " to base " + base);
		}
		// update
		base.calculateBoundingBox();
		// relink
		base.linkPatches();

		return base;
	}

	/** For each area that ali contains, add it to the corresponding area here.*/
	private void add(AreaList ali) {
		for (Iterator it = ali.ht_areas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			Object ob_area = entry.getValue();
			long lid = ((Long)entry.getKey()).longValue();
			if (UNLOADED.equals(ob_area)) ob_area = ali.loadLayer(lid);
			Area area = (Area)ob_area;
			AffineTransform at = new AffineTransform();
			at.translate(ali.x - this.x, ali.y - this.y);
			area = area.createTransformedArea(at);
			Object this_area = this.ht_areas.get(entry.getKey());
			if (UNLOADED.equals(this_area)) { this_area = loadLayer(lid); }
			if (null == this_area) this.ht_areas.put(entry.getKey(), (Area)area.clone());
			else ((Area)this_area).add(area);
			updateInDatabase("points=" + ((Long)entry.getKey()).intValue());
		}
	}

	/** How many layers does this object paint to. */
	public int getNAreas() { return ht_areas.size(); }

	public Area getArea(Layer la) {
		Object ob = ht_areas.get(new Long(la.getId()));
		if (null != ob) {
			if (UNLOADED.equals(ob)) ob = loadLayer(la.getId());
			return (Area)ob;
		}
		return null;
	}

	/** Performs a deep copy of this object, without the links, unlocked and visible. */
	public Object clone() {
		final ArrayList al_ul = new ArrayList();
		for (Iterator it = ht_areas.keySet().iterator(); it.hasNext(); ) {
			al_ul.add(new Long(((Long)it.next()).longValue())); // clones of the Long that wrap layer ids
		}
		final AreaList copy = new AreaList(project, project.getLoader().getNextId(), null != title ? title.toString() : null, x, y, width, height, alpha, true, new Color(color.getRed(), color.getGreen(), color.getBlue()), false, al_ul);
		for (Iterator it = copy.ht_areas.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			entry.setValue(((Area)this.ht_areas.get(entry.getKey())).clone());
		}
		// add
		copy.layer = this.layer; // this does not add it to any layer, just sets the 'current' layer pointer
		copy.addToDatabase();
		snapshot.remake();
		//
		return copy;
	}

	/** Will make the assumption that all layers have the same thickness as the first one found.
	 *  @param scale The scaling of the entire universe, to limit the overall box
	 *  @param resample The optimization parameter for marching cubes (i.e. a value of 2 will scale down to half, then apply marching cubes, then scale up by 2 the vertices coordinates).
	 *  @return The List of triangles involved, specified as three consecutive vertices. A list of Point3f vertices.
	 */
	public List generateTriangles(double scale, int resample) {
		// in the LayerSet, layers are ordered by Z already.
		try {
		if (resample <=0 ) {
			resample = 1;
			Utils.log2("Fixing zero or negative resampling value to 1.");
		}
		int n = getNAreas();
		if (0 == n) return null;
		final Rectangle r = getBoundingBox();
		ImageStack stack = null;
		float z = 0;
		double thickness = 1;
		//Utils.log("resample: " + resample + "  scale: " + scale);
		final double K = (1.0 / resample) * scale; // 'scale' is there to limit gigantic universes
		final int w = (int)Math.ceil(r.width * K);
		final int h = (int)Math.ceil(r.height * K);
		for (Iterator it = layer_set.getLayers().iterator(); it.hasNext(); ) {
			if (0 == n) break; // no more areas to paint
			final Layer la = (Layer)it.next();
			Area area = getArea(la);
			if (null != area) {
				if (null == stack) {
					Utils.log("0 - creating stack with  w,h : " + w + ", " + h);
					stack = new ImageStack(w, h);
					z = (float)la.getZ(); // z of the first layer
					thickness = la.getThickness();
				}
				ImageProcessor ip = new ByteProcessor(w, h);
				ip.setColor(Color.white);
				AffineTransform at = new AffineTransform();
				at.scale(K, K);
				area = area.createTransformedArea(at);
				ShapeRoi roi = new ShapeRoi(area);
				ip.setRoi(roi);
				ip.fill(roi.getMask()); // should be automatic!
				stack.addSlice(la.getZ() + "", ip);
				n--;
			} else if (null != stack) {
				// add a black slice
				stack.addSlice(la.getZ() + "", new ByteProcessor(w, h));
				n--;
			}
		}
		// zero-pad stack
		stack = zeroPad(stack);

		ImagePlus imp = new ImagePlus("", stack);
		imp.getCalibration().pixelDepth = thickness * scale; // no need to factor in resampling
		//debug:
		//imp.show();
		// end of generating byte[] arrays
		// Now marching cubes
		Triangulator tri = new MCTriangulator();
		List list = tri.getTriangles(imp, 0, new boolean[]{true, true, true}, 1);
		// now translate all coordinates by x,y,z (it would be nice to simply assign them to a mesh object)
		float dx = (float)this.x * (float)scale;
		float dy = (float)this.y * (float)scale;
		float dz = (float)((z - thickness) * scale); // the z of the first layer found, corrected for both scale and the zero padding
		Utils.log2("AreaList: scale=" + scale + " x,y: " + x + "," + y + "  dx,dy: " + dx + "," + dy);
		for (Iterator it = list.iterator(); it.hasNext(); ) {
			Point3f p = (Point3f)it.next();
			// fix back the resampling (but not the universe scale, which has already been considered)
			p.x *= resample; // a resampling of '2' means 0.5  (I love inverted worlds..)
			p.y *= resample;
			//Z was not resampled
			// translate to the x,y,z coordinate of the object in space
			p.x += dx;
			p.y += dy;
			p.z += dz;
		}
		return list;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	static private ImageStack zeroPad(final ImageStack stack) {
		int w = stack.getWidth();
		int h = stack.getHeight();
		// enlarge all processors
		ImageStack st = new ImageStack(w+2, h+2);
		for (int i=1; i<=stack.getSize(); i++) {
			ImageProcessor ip = new ByteProcessor(w+2, h+2);
			ip.insert(stack.getProcessor(i), 1, 1);
			st.addSlice(Integer.toString(i), ip);
		}
		ByteProcessor bp = new ByteProcessor(w+2, h+2);
		// insert slice at 0
		st.addSlice("0", bp, 0);
		// append slice at the end
		st.addSlice(Integer.toString(stack.getSize()+1), bp);

		return st;
	}

	/** Directly place an Area for the specified layer. Does not make it local, you should call calculateBoundingBox() after setting an area. */
	public void setArea(long layer_id, Area area) {
		if (null == area) return;
		ht_areas.put(new Long(layer_id), area);
		updateInDatabase("points=" + layer_id);
	}
}
