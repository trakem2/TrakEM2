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

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.awt.geom.AffineTransform;
import ij.gui.GenericDialog;
import ini.trakem2.Project;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Search;

/** The class that any element to be drawn on a Display must extend. */
public abstract class Displayable extends DBObject {

	protected double x;
	protected double y;
	protected double width = 0;
	protected double height = 0;
	/** The angle of rotation around the Z axis, in degrees. */
	protected double rot = 0.0D;


	protected AffineTransform at = new AffineTransform();


	private boolean locked = false;
	protected String title;
	protected Color color = Color.yellow;
	protected float alpha = 1.0f; // from 0 to 1 (0 is full transparency)
	protected boolean visible = true;
	protected Layer layer;
	/** The Displayable objects this one is linked to. Can be null. */
	protected HashSet hs_linked = null;
	protected Snapshot snapshot;

	////////////////////////////////////////////////////
	public void setLocked(boolean lock) {
		if (lock) this.locked = lock;
		else {
			// to unlock, unlock those in the linked group that are locked
			this.locked = false;
			unlockAllLinked(new HashSet());
		}
		updateInDatabase("locked");
	}
	private void unlockAllLinked(HashSet hs) {
		if (hs.contains(this)) return;
		hs.add(this);
		if (null == hs_linked) return;
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (d.locked) d.locked = false;
			d.unlockAllLinked(hs);
		}
	}
	/** Return the value of the field 'locked'. */
	public boolean isLocked2() {
		return locked;
	}
	/** Check if this or any of the Displayables in the linked group is locked. */
	public boolean isLocked() {
		if (locked) return true;
		return isLocked(new HashSet());
	}
	private boolean isLocked(HashSet hs) {
		if (locked) return true;
		else if (hs.contains(this)) return false;
		hs.add(this);
		if (null != hs_linked && hs_linked.size() > 0) {
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				if (d.isLocked(hs)) return true;
			}
		}
		return false;
	}
	////////////////////////////////////////////////////
	/** The minimal Displayable constructor. */
	public Displayable(Project project, String title, double x, double y) {
		super(project);
		this.title = title;
		this.x = x;
		this.y = y;
		this.snapshot = new Snapshot(this);
		this.at.translate(x, y);
	}

	/** Reconstruct a Displayable from the database. */
	public Displayable(Project project, long id, String title, double x, double y, boolean locked) {
		super(project, id);
		this.title = title;
		this.x = x;
		this.y = y;
		this.locked = locked;
		this.snapshot = new Snapshot(this);
		this.at.translate(x, y);
	}

	/** Reconstruct a Displayable from an XML entry. Used entries get removed from the Hashtable. */
	public Displayable(Project project, long id, Hashtable ht, Hashtable ht_links) {
		super(project, id);
		this.layer = null; // will be set later
		// parse data // TODO this is weird, why not just call them, since no default values are set anyway
		final ArrayList al_used_keys = new ArrayList();
		for (Enumeration e = ht.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			try {
				String data = (String)ht.get(key);
				if (key.equals("x")) x = Double.parseDouble(data); // this could be done with reflection, but not all, hence this dullness
				else if (key.equals("y")) y = Double.parseDouble(data);
				else if (key.equals("width")) width = Double.parseDouble(data);
				else if (key.equals("height")) height = Double.parseDouble(data);
				else if (key.equals("transform")) {
					final String[] nums = data.substring(data.indexOf('(')+1, data.lastIndexOf(')')).split(",");
					this.at.setTransform(Float.parseFloat(nums[0]), Float.parseFloat(nums[1]),
							     Float.parseFloat(nums[2]), Float.parseFloat(nums[3]),
							     Float.parseFloat(nums[4]), Float.parseFloat(nums[5]));
				}
				else if (key.equals("locked")) locked = data.trim().toLowerCase().equals("true");
				else if (key.equals("visible")) visible = data.trim().toLowerCase().equals("true");
				else if (key.equals("style")) {
					try {
					// 1 - extract alpha
					int i_start = data.indexOf("stroke-opacity:");
					if (-1 == i_start) {
						// try fill-opacity
						i_start = data.indexOf("fill-opacity:");
						if (-1 == i_start) {
							// default
							this.alpha = 1.0f;
						} else {
							this.alpha = Float.parseFloat(data.substring(i_start + 13, data.indexOf(';', i_start + 13)).trim());
						}
					} else {
						this.alpha = Float.parseFloat(data.substring(i_start + 15, data.indexOf(';', i_start + 15)).trim());
					}
					// 2 - extract color
					i_start = data.indexOf("stroke:");
					int i_end;
					if (-1 == i_start) {
						//try fill
						i_start = data.indexOf("fill:");
					}
					if (-1 != i_start) {
						i_start = data.indexOf('#', data.indexOf(':', i_start+4)); // at least 4 (for 'fill:'), max 7 (for 'stroke:')
						i_end = data.indexOf(';', i_start +1);
						this.color = Utils.getRGBColorFromHex(data.substring(i_start+1, i_end));
					} else {
						Utils.log2("Can't parse color for id=" + id);
						this.color = Color.yellow;
					}
					// avoid recording this key for deletion, for the DLabel to read it
					continue;
					} catch (Exception es) {
						if (null == this.color) this.color = Color.yellow;
						Utils.log("ERROR at reading style: " + es);
					}
				} else if (key.equals("links")) {
					// This is hard one, must be stored until all objects exist and then processed
					if (null != data && data.length() > 0) ht_links.put(this, data);
				} else if (key.equals("title")) {
					if (null != data && !data.toLowerCase().equals("null")) {
						this.title = data.replaceAll("^#^", "\""); // fix " and backslash characters
					} else this.title = null;
				} else continue;
				al_used_keys.add(key);
			} catch (Exception ea) {
				Utils.log(this + " : failed to read data for key '" + key + "':\n" + ea);
			}
		}
		for (Iterator it = al_used_keys.iterator(); it.hasNext(); ) {
			ht.remove(it.next());
		}
		this.snapshot = new Snapshot(this);
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
		Utils.log2("paint g, magnification, active, channels, active_layer: not implemented yet for " + this.getClass());
	}

	public void paint(Graphics2D g) {
		Utils.log2("paint g: not implemented yet for " + this.getClass());
	}

	public void paint(Graphics g, Layer active_layer) {
		paint((Graphics2D)g);
	}

	@Deprecated
	public void paint(Graphics g, double magnification, Rectangle srcRect, Rectangle clipRect, boolean active, int channels, Layer active_layer) {
		Utils.log2("Deprecated paint method 1 for " + this.getClass());
	}

	@Deprecated
	public void paint(Graphics g, double magnification, Rectangle srcRect, Rectangle clipRect, boolean active, int channels, Layer active_layer, Transform t) {
		Utils.log2("Deprecated paint method 2 for " + this.getClass());
	}

	/** Not accepted if zero or negative. Remakes the snapshot, updates the snapshot panel and the Display. */
	public void setDimensions(double width, double height) {
		setDimensions(width, height, true);
	}

	/** Not accepted if zero or negative. If repaint is true, remakes the snapshot, updates the snapshot panel and the Display. */
	public void setDimensions(double width, double height, boolean repaint) {
		if (width <= 0 || height <= 0) return;
		this.width = width;
		this.height = height;
		updateInDatabase("dimensions");
		if (repaint) {
			snapshot.remake();
			Display.repaint(layer, this, 5);
			//done with above//Display.updatePanel(layer, this);
		}
	}

	/** Not accepted if new bounds will leave the Displayable beyond layer bounds. Subclasses should extend this method to make it meaningful for them. Does not repaint the Display, but updates its own snapshot panel. */
	public void setBounds(double x, double y, double width, double height) {
		if (x + width <= 0 || y + height <= 0 || x >= layer.getLayerWidth() || y >= layer.getLayerHeight()) return;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		updateInDatabase("position+dimensions");
		snapshot.remake();
		Display.updatePanel(layer, this);
	}

	/** A second method to set the bounds, not to be extended by subclasses and only for rotating the Layer. */
	protected void setBounds2(double x, double y, double width, double height) {
		if (x + width <= 0 || y + height <= 0 || x >= layer.getLayerWidth() || y >= layer.getLayerHeight()) return;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		updateInDatabase("position+dimensions");
		snapshot.remake();
		Display.updatePanel(layer, this);
	}

	public void setLocation(double x, double y) {
		if (x + width < 0 || y + height < 0 || x > layer.getLayerWidth() || y > layer.getLayerHeight()) {
			Utils.log("Prevented moving " + this + " beyond layer bonds.");
			return;
		}
		this.x = x;
		this.y = y;
		updateInDatabase("position");
	}

	public void setLayer(Layer layer, boolean update_db) {
		if (null == layer || this.layer == layer) return;
		this.layer = layer;
		if (update_db) updateInDatabase("layer_id");
	}

	public void setLayer(Layer layer) {
		setLayer(layer, true);
	}

	public Layer getLayer() {
		return layer;
	}

	public void setTitle(String title) {
		if (null == title || 0 == title.length()) return;
		this.title = title;
		Display.updateTitle(layer, this); // update the DisplayablePanel(s) that show this Patch
		updateInDatabase("title");
	}

	public String getTitle() {
		return this.title;
	}

	public String getShortTitle() {
		return "x=" + Utils.cutNumber(x, 2) + " y=" + Utils.cutNumber(y, 2) + (null != layer ? " z=" + Utils.cutNumber(layer.getZ(), 2) : "");
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	public double getWidth() {
		return width;
	}

	public double getHeight() {
		return height;
	}

	public Rectangle getBoundingBox() {
		return getBoundingBox(null);
	}

	/** Saves one allocation, returns the same Rectangle, modified. */
	public Rectangle getBoundingBox(Rectangle r) {
		if (null == r) r = new Rectangle();
		if (this.at.isIdentity()) {
			r.x = 0;
			r.y = 0;
			r.width = (int)this.width;
			r.height = (int)this.height;
		} else {
			// transform points
			final double[] d1 = new double[]{0, 0, width, 0, width, height, 0, height};
			final double[] d2 = new double[8];
			this.at.transform(d1, 0, d2, 0, 4);
			// find min/max
			double min_x=Double.MAX_VALUE, min_y=Double.MAX_VALUE, max_x=-min_x, max_y=-min_y;
			for (int i=0; i<d2.length; i+=2) {
				if (d2[i] < min_x) min_x = d2[i];
				if (d2[i] > max_x) max_x = d2[i];
				if (d2[i+1] < min_y) min_y = d2[i+1];
				if (d2[i+1] > max_y) max_y = d2[i+1];
			}
			r.x = (int)min_x;
			r.y = (int)min_y;
			r.width = (int)(max_x - min_x);
			r.height = (int)(max_y - min_y);
		}
		return r;
	}

	/** Subclasses can override this method to provide the exact contour, otherwise it returns the bounding box with x,y set to zero. */
	public Polygon getPerimeter() {
		return getPerimeter(0, 0);
	}

	/** Get the perimeter translated to the given coordinates. Subclasses can override this method to provide the exact contour, otherwise it returns the bounding box. */
	public Polygon getPerimeter(double xo, double yo) {
		Rectangle r = getBoundingBox();
		Utils.log2("x,y,w,h: " + x + "," + y + "," + width + "," + height);
		Utils.log2("bbox is " + r);
		return new Polygon(new int[]{r.x, r.x+r.width, r.x+r.width, r.x},
				   new int[]{r.y, r.y, r.y+r.height, r.y+r.height},
				   4);
	}

	/** Test whether the given point falls within the perimeter of this Displayable, considering the position x,y. Used by the DisplayCanvas mouse events. */
	public boolean contains(int x_p, int y_p) {
		return getPerimeter().contains(x_p, y_p);
	}

	public void setAlpha(float alpha) {
		setAlpha(alpha, true);
	}

	protected void setAlpha(float alpha, boolean update) {
		if (alpha != this.alpha && alpha >= 0.0f && alpha <= 1.0f) {
			this.alpha = alpha;
			if (update) {
				Display.repaint(layer, this, 5);
				updateInDatabase("alpha");
				Display3D.setTransparency(this, alpha);
			}
		}
	}

	public float getAlpha() { return alpha; }

	public double getRotation() { return rot; }

	public void setRotation(double rot) {
		this.rot = rot;
		updateInDatabase("rot");
	}

	/** Rotate by the given angle (in degrees) relative to the given center of coordinates. Will set the local angle and local x, y position accordingly. */
	public void rotate(double delta_angle, double xo, double yo, boolean update_db) {
		if (0 == delta_angle) return;
		// Answer:
		// 1 - what is the delta local angle to add to the existing local angle?
		// 2 - what is the displacement of the x,y?
		//
		// center:
		double cx = x + width/2;
		double cy = y + height/2;

		// angle of object with origin of rotation
		double b1 = Utils.getAngle(xo - cx, yo - cy);
		//Utils.log2("\nb1: " + Math.toDegrees(b1));
		// new angle of object relative to origin of rotation
		double b2 = b1 + Math.toRadians(delta_angle);
		//Utils.log2("b2: " + Math.toDegrees(b2));
		//Utils.log2("delta_angle: " + delta_angle);
		// displacement
		double hypot = Math.sqrt((xo - cx)*(xo - cx) + (yo - cy)*(yo - cy));
		double dx = (Math.cos(b1) - Math.cos(b2)) * hypot;
		double dy = (Math.sin(b1) - Math.sin(b2)) * hypot;

		// set:
		this.x += dx;
		this.y += dy;
		this.rot += delta_angle;
		if (this.rot > 360) this.rot -= 360;
		if (this.rot < 0) this.rot += 360;
		if (update_db) {
			updateInDatabase("position+rot");
		}
		// TODO check that it doesn't go beyond the layer set limits! (or ask to resize the LayerSet to a minimum enclosing dimensions)
	}

	public Color getColor() { return color; }

	/** Return the HashSet of directly linked Displayable objects. */
	public HashSet getLinked() { return hs_linked; }

	/** Return those of Class c from among the directly linked. */
	public HashSet getLinked(Class c) {
		if (null == hs_linked) return null;
		HashSet hs = new HashSet();
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			if (ob.getClass().equals(c)) hs.add(ob);
		}
		return hs;
	}

	/** Return the HashSet of all diretly and indirectly linked objects. */
	public HashSet getLinkedGroup(HashSet hs) {
		if (null == hs) hs = new HashSet();
		else if (hs.contains(this)) return hs;
		hs.add(this);
		if (null != hs_linked && hs_linked.size() > 0) {
			Iterator it = hs_linked.iterator();
			while (it.hasNext()) {
				((Displayable)it.next()).getLinkedGroup(hs);
			}
		}
		return hs;
	}

	/** Drag by the given differentials this Displayable and the linked group, if 'linked' is true. Returns the bounding box of the entire linked group, for those Displayable objects that share the layer with this.*/ // this method shortcuts existing methods for efficiency (it's slow enough already)
	protected Rectangle drag(double dx, double dy, boolean linked) {
		if (linked) {
			HashSet hs_linked = getLinkedGroup(null);
			Iterator it = hs_linked.iterator();
			Rectangle box = new Rectangle();
			Rectangle r = new Rectangle();
			while (it.hasNext()) {
			Displayable d = (Displayable)it.next();
			d.drag(dx, dy);
			/*if (d.layer.equals(this.layer) || d instanceof ZDisplayable) {*/
			box.add(d.getBoundingBox(r));
			//}
			// MUST warrantee that other Displays are painted properly as well! It is not so much overhead because rarely there will be a lot of displays open.
			}
			return box;
		} else {
			// only this:
			drag(dx, dy);
			return getBoundingBox();
		}
	}

	protected void drag(int dx, int dy) {
		// else drag this
		this.x += dx;
		this.y += dy;
		if (x + width < 0 || y + height < 0 || x > layer.getLayerWidth() || y > layer.getLayerHeight()) {
			Utils.log("Prevented moving " + this + " beyond layer bonds.");
			this.x -= dx;
			this.y -= dy;
			return;
		}
		updateInDatabase("position");
	}

	public void drag(double dx, double dy) {
		drag(dx, dy, 1);
	}

	public void drag(double dx, double dy, int within_bounds_only) { // works as a boolean
		// else drag this
		this.x += dx;
		this.y += dy;
		if (1 == within_bounds_only && (x + width < 0 || y + height < 0 || x > layer.getLayerWidth() || y > layer.getLayerHeight())) {
			Utils.log("Prevented moving " + this + " beyond layer bonds.");
			this.x -= dx;
			this.y -= dy;
			return;
		}
		updateInDatabase("position");
	}

	@Deprecated
	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		Utils.log2("Deprecated mousePressed method for " + this.getClass());
	}

	@Deprecated
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
		Utils.log2("DeprecaPressedted mouseDragged method for " + this.getClass());
	}

	@Deprecated
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		Utils.log2("Deprecated mouseReleased method for " + this.getClass());
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p) {
		Utils.log2("mousePressed not implemented yet for " + this.getClass().getName());
	}
	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		Utils.log2("mouseDragged not implemented yet for " + this.getClass().getName());
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		Utils.log2("mouseReleased not implemented yet for " + this.getClass().getName());
	}

	public void keyPressed(KeyEvent ke) {

		int key_code = ke.getKeyCode();

		Rectangle box = null;

		switch(key_code) {

		case KeyEvent.VK_ENTER: //End transform or bring ImageJ to front
			ke.consume();
			break;
		case KeyEvent.VK_UP: //move one pixel up
			box = drag(0, -1, true);
			ke.consume();
			break;
		case KeyEvent.VK_DOWN: //move one pixel down
			box = drag(0, 1, true);
			ke.consume();
			break;
		case KeyEvent.VK_LEFT: //move one pixel left
			box = drag(-1, 0, true);
			ke.consume();
			break;
		case KeyEvent.VK_RIGHT: //move one pixel right
			box = drag(1, 0, true);
			ke.consume();
			break;
		case KeyEvent.VK_ESCAPE:
			Display.setActive(ke, null);
			ke.consume();
			break;
		}

		if (ke.isConsumed() && KeyEvent.VK_ESCAPE != key_code) {
			// no need to repaint the previous box, falls within 1 pixel (and here I add 5 on the margins)
			if (null == box) {
				Display.repaint(this.layer, this, 5);
			} else {
				Display.repaint(this.layer, box, 5);
			}
		} //not repainting for ESC because it has been done already
	}

	public boolean isVisible() { return this.visible; }

	public void setVisible(boolean visible) {
		//Utils.printCaller(this, 10);
		//Utils.log2("visible is set to be: " + visible);
		if (this.visible == visible) return;
		this.visible = visible;
		Display.setUpdateGraphics(layer, this);
		Display.repaint(layer, this, 5);
		updateInDatabase("visible");
		Display.updateVisibilityCheckbox(layer, this, null); // overkill, but makes my life easy
		//Utils.log2("visible is now: " + visible);
		//Utils.log2("called setV " + visible + System.currentTimeMillis());
	}

	public void setVisible(boolean visible, boolean repaint) {
		if (this.visible == visible) return;
		this.visible = visible;
		if (repaint) {
			Display.setUpdateGraphics(layer, this);
			Display.repaint(layer, this, 5);
		}
		updateInDatabase("visible");
		Display.updateVisibilityCheckbox(layer, this, null); // overkill, but makes my life easy
		//Utils.log2("called setV2 " + visible + System.currentTimeMillis());
	}

	/** Repaint this Displayable in all Display instances that are showing it. */
	public void repaint() {
		Display.repaint(layer, this, 5);
	}

	public void setColor(Color color) {
		if (null == color || color.equals(this.color)) return;
		this.color = color;
		updateInDatabase("color");
		Display.repaint(layer, this, 5);
		Display3D.setColor(this, color);
	}

	/** Release resources. */
	public void destroy() {
		this.layer = null;
	}

	public boolean isOutOfRepaintingClip(double magnification, Rectangle srcRect, Rectangle clipRect) {
		// 1 - check visibility
		if (!visible) {
			//if not visible, it's out, so return true:
			return true;
		}
		Rectangle box = getBoundingBox(); // includes rotation
		// 2 - check if out of clipRect
		if (null != clipRect && null != srcRect) {
			int screen_x = (int)((box.x -srcRect.x) * magnification);
			int screen_y = (int)((box.y -srcRect.y) * magnification);
			int screen_width = (int)(box.width * magnification);
			int screen_height = (int)(box.height * magnification);
			if ((screen_x + screen_width) < clipRect.x || (screen_y + screen_height) < clipRect.y || screen_x > (clipRect.x + clipRect.width) || screen_y > (clipRect.y + clipRect.height)) {
				return true;
			}
		}

		// 3 - check if out of srcRect
		// This is included above anyway, but just in case there is no clipRect !
		if (null != srcRect) {
			if (box.x + box.width < srcRect.x || box.y + box.height < srcRect.y || box.x > srcRect.x + srcRect.width || box.y > srcRect.y + srcRect.height) {
				return true;
			}
		}

		// else: it's in, so paint!
		return false;
	}

	/** For the DisplayNavigator. No srcRect or magnification considered. */
	public boolean isOutOfRepaintingClip(Rectangle clipRect, double scale) {
		// 1 - check visibility
		if (!visible) {
			//if not visible, it's out, so return true:
			return true;
		}
		Rectangle box = getBoundingBox(); // includes rotation
		// 2 - check if out of clipRect
		if (null != clipRect) {
			int screen_x = (int)(box.x * scale);
			int screen_y = (int)(box.y * scale);
			int screen_width = (int)(box.width * scale);
			int screen_height = (int)(box.height * scale);
			if ((screen_x + screen_width) < clipRect.x || (screen_y + screen_height) < clipRect.y || screen_x > (clipRect.x + clipRect.width) || screen_y > (clipRect.y + clipRect.height)) {
			return true;
			}
		}

		// else: it's in, so paint!
		return false;
	}

	public Snapshot getSnapshot() {
		return snapshot;
	}

	/** Remove from both the database and any Display that shows the Layer in which this Displayable is shown. */
	public boolean remove(boolean check) {
		if (super.remove(check) && layer.remove(this)) {
			unlink();
			Search.remove(this);
			return true;
		}
		Utils.log("Failed to remove " + this.getClass().getName() + " " + this);
		return false;
	}

	/** Link the given Displayable with this Displayable, and then tell the given Displayable to link this. Since the link is stored as Displayable objects in a HashSet, there'll never be repeated entries. */
	public void link(Displayable d) {
		link(d, true);
	}

	/** Link the given Displayable with this Displayable, and then tell the given Displayable to link this. Since the link is stored as Displayable objects in a HashSet, there'll never be repeated entries.*/
	public void link(Displayable d, boolean update_database) { // the boolean is used by the loader when reconstructing links.
		if (this == d) return;
		if (null == this.hs_linked) this.hs_linked = new HashSet();
		// link the other to this
		this.hs_linked.add(d);
		// link this to the other
		if (null == d.hs_linked) d.hs_linked = new HashSet();
		d.hs_linked.add(this);
		// update the database
		if (update_database) project.getLoader().addCrossLink(project.getId(), this.id, d.id);
	}

	/** Remove all links held by this Displayable.*/
	public void unlink() {
		if (null == this.hs_linked) return;
		Iterator it = hs_linked.iterator();
		Displayable[] displ = new Displayable[hs_linked.size()];
		int next = 0;
		while (it.hasNext()) {
			displ[next++] = (Displayable)it.next();
		}
		// all these redundancy because of the [typical] 'concurrent modification exception'
		for (int i=0; i<next; i++) {
			unlink(displ[i]);
		}
		this.hs_linked = null;
	}

	/** Remove the link with the given Displayable, and tell the given Displayable to remove the link with this. */
	public void unlink(Displayable d) {
		//Utils.log("Displayable.unlink(Displayable)");
		if (this == d) {
			return; // should not happen
		}
		if (null == this.hs_linked) return; // should not happen
		// unlink the other from this, and this from the other
		if (!( hs_linked.remove(d) && d.hs_linked.remove(this))) {
			// signal database inconsistency (should not happen)
			Utils.log("Database inconsistency: two displayables had a non-reciprocal link. BEWARE of other errors.");
		}
		// update the database in any case
		project.getLoader().removeCrossLink(this.id, d.id);
	}

	/** Check if this object is linked to any other Displayable objects.*/
	public boolean isLinked() {
		if (null == hs_linked) return false;
		return !hs_linked.isEmpty();
	}

	/** Check if this object is only linked to Displayable objects of the given class (returns true) or to none (returns true as well).*/
	public boolean isOnlyLinkedTo(Class c) {
		if (null == hs_linked || hs_linked.isEmpty()) return true;
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			//Utils.log2(this + " is linked to " + ob);
			if (! ob.getClass().equals(c)) return false;
		}
		return true;
	}

	/** Check if this object is only linked to Displayable objects of the given class in the same layer (returns true). Returns true as well when not linked to any of the given class.*/
	public boolean isOnlyLinkedTo(Class c, Layer layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return true;
		for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			// if the class is not the asked one, or the object is not in the same layer, return false!
			if (!d.getClass().equals(c) || !d.layer.equals(this.layer)) return false;
		}
		return true;
	}


	/** Link the Patch objects that lay underneath the bounding box of this profile, so that they cannot be dragged independently. */
	public void linkPatches() {
		// find the patches that don't lay under other profiles of this profile's linking group, and make sure they are unlinked. This will unlink any Patch objects under this Profile:
		unlinkAll(Patch.class);

		// scan the Display and link Patch objects that lay under this Profile's bounding box:

		// catch all displayables of the current Layer
		ArrayList al = layer.getDisplayables();

		// this bounding box:
		Polygon perimeter = getPerimeter(x, y); //displaced by this object's position!
		if (null == perimeter) return; // happens when a profile with zero points is deleted

		// for each Patch, check if it underlays this profile's bounding box
		Rectangle box = new Rectangle();
		Iterator itd = al.iterator();
		while (itd.hasNext()) {
			Displayable displ = (Displayable)itd.next();
			// link only Patch objects
			if (!displ.getClass().equals(Patch.class)) {
			continue;
			}
			// stupid java, Polygon cannot test for intersection with another Polygon !! //if (perimeter.intersects(displ.getPerimeter())) // TODO do it yourself: check if a Displayable intersects another Displayable
			if (perimeter.intersects(displ.getBoundingBox(box))) {
			// Link the patch
			this.link(displ);
			}
		}
	}
	/** Unlink all Displayable objects of the given type linked by this. */
	public void unlinkAll(Class c) {
		if (!this.isLinked() || null == hs_linked) {
			return;
		}
		// catch Displayables, or the iterators will go mad when deleting objects
		int n = hs_linked.size();
		Object[] dall = new Object[n];
		int i = 0;
		Iterator it = hs_linked.iterator();
		while (it.hasNext()) {
			dall[i++] = it.next();
		}
		for (i=0; i<n; i++) {
			if (dall[i].getClass().equals(c)) {
			unlink((Displayable)dall[i]);
			}
		}
	}

	/** Check if this perimeter's intersects that of the given Displayable. */
	public boolean intersects(Displayable d) {
		if (!this.getBoundingBox().intersects(d.getBoundingBox())) return false;
		// now faster: check if any corner of this is contained within the area of 'd'
		// TODO check exact perimeters, rotation ...
		return true;
	}

	/** Returns the sum of bounding boxes of all linked Displayables. */
	public Rectangle getLinkedBox(boolean same_layer) {
		if (null == hs_linked || hs_linked.isEmpty()) return getBoundingBox();
		Rectangle box = new Rectangle();
		accumulateLinkedBox(same_layer, new HashSet(), box);
		return box;
	} // I HATE ECLIPSE

	/** Accumulates in the box. */
	private void accumulateLinkedBox(boolean same_layer, HashSet hs_done, Rectangle box) {
		if (hs_done.contains(this)) return;
		hs_done.add(this);
		box.add(new Rectangle((int)x, (int)y, (int)Math.ceil(width), (int)Math.ceil(height))); // if one tries to save that many allocations of Rectangle instances, the method fails to do its job properly! Something is stinky in the setBounds implementation of the Rectangle class.
		Iterator it = hs_linked.iterator();
		while (it.hasNext()) {
			Displayable d = (Displayable)it.next();
			// add ZDisplayables regardless, for their 'layer' pointer is used to know which part of them must be painted.
			if (same_layer && !(d instanceof ZDisplayable) && !d.layer.equals(this.layer)) continue;
			d.accumulateLinkedBox(same_layer, hs_done, box);
		}
	}

	/** Minimal info that identifies this object as unique, for display on a JTree node.*/
	public String toString() {
		return this.title + (!(this instanceof ZDisplayable) && null != layer ? " z=" + layer.getZ() : "")  + " #" + this.id; // the layer is null when recreating the object from the database and printing it for testing in the Loader
	}

	abstract public boolean isDeletable();

	/** Subclasses can specify the behaviour, for the default is true.*/
	public boolean canSendTo(Layer layer) {
		return true;
	}

	public void handleDoubleClick() {
		Display.setTransforming(this);
	}

	/** Does nothing unless overriden. */
	public void exportSVG(StringBuffer data, double z_scale, String indent) {}

	/** Possible directions are LayerSet.R90, .R270, .FLIP_VERTICAL and .FLIP_HORIZONTAL. */
	public void rotateData(int direction) {}  // DLabel and LayerSet do NOT do anything

	/** Does nothing unless overriden. Used for profile, pipe and ball points when preventing dragging beyond the screen, to snap to cursor when this reenters. */
	public void snapTo(int cx, int cy, int x_p, int y_p) {}

	/** Shows a dialog to adjust properties of this object. */
	public void adjustProperties() {
		GenericDialog gd = makeAdjustPropertiesDialog();
		gd.showDialog();
		if (gd.wasCanceled()) return;
		processAdjustPropertiesDialog(gd);
	}

	protected GenericDialog makeAdjustPropertiesDialog() {
		GenericDialog gd = new GenericDialog("Properties");
		gd.addStringField("title: ", title);
		gd.addNumericField("x: ", x, 2);
		gd.addNumericField("y: ", y, 2);
		gd.addNumericField("width: ", width, 2);
		gd.addNumericField("height: ", height, 2);
		gd.addNumericField("rot (degrees): ", rot, 2);
		gd.addSlider("alpha: ", 0, 100, (int)(alpha*100));
		gd.addCheckbox("visible", visible);
		gd.addCheckbox("locked", locked);
		return gd;
	}

	protected void processAdjustPropertiesDialog(final GenericDialog gd) {
		// store old transforms for undo
		HashSet hs = getLinkedGroup(new HashSet());
		Hashtable ht = new Hashtable();
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			ht.put(d, d.getAffineTransformCopy());
		}
		layer.getParent().addUndoStep(ht);
		// store old box
		//Rectangle box = getLinkedBox(true);//getBoundingBox();
		// adjust values:
		String title1 = gd.getNextString();
		double x1 = gd.getNextNumber();
		double y1 = gd.getNextNumber();
		double w1 = gd.getNextNumber();
		double h1 = gd.getNextNumber();
		double rot1 = gd.getNextNumber();
		float alpha1 = (float)gd.getNextNumber() / 100;
		if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(w1) || Double.isNaN(h1) || Double.isNaN(h1) || Float.isNaN(alpha1)) {
			Utils.showMessage("Invalid values!");
			return;
		}
		boolean visible1 = gd.getNextBoolean();
		boolean locked1 = gd.getNextBoolean();
		if (!title.equals(title1)) {
			this.title = title1;
			updateInDatabase("title");
		}
		if (x1 != x || y1 != y || w1 != width || h1 != height) {
			if (null != hs) {
				// apply the scaling and displacement to all linked
				Rectangle box_old = getBoundingBox();
				// fix FreeHandProfile past errors
				if (0 == box_old.width || 0 == box_old.height) {
					if (this instanceof Profile) {
						((Profile)this).calculateBoundingBox(true);
						box_old = getBoundingBox();
					} else {
						//avoid division by zero
						Utils.showMessage("Some error ocurred: zero width or height ob the object to adjust.\nUnlink this object '" + this + "' and adjust carefully");
						return;
					}
				}
				// store original position+dimensions
				double x0 = this.x; double w0 = this.width;
				double y0 = this.y; double h0 = this.height;
				// set new position+dimensions
				this.x = x1; this.width = w1;
				this.y = y1; this.height = h1;
				Rectangle box_new = getBoundingBox(); // there's rotation involved
				// restore position+dimensions
				this.x = x0; this.width = w0;
				this.y = y0; this.height = h0;
				// compute proportions
				double px = (double)box_new.width / (double)box_old.width;
				double py = (double)box_new.height / (double)box_old.height;
				// scale all
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					Transform t = d.getTransform();
					t.scale(px, py, box_new, box_old); // displaces as well
					d.setTransform(t);
				}
			} else {
				setBounds(x1, y1, w1, h1);
			}
		}
		if (rot1 != rot) {
			if (null != hs) {
				double cx = this.x + this.width/2;
				double cy = this.y + this.height/2;
				double delta_angle = rot1 - this.rot;
				//Utils.log2("delta_angle, rot1, rot: " + delta_angle + "," + rot1 + "," + rot);
				for (Iterator it = hs.iterator(); it.hasNext(); ) {
					Displayable d = (Displayable)it.next();
					d.rotate(delta_angle, cx, cy, true);
				}
			} else {
				setRotation(rot1);
			}
		}
		if (alpha1 != alpha) setAlpha(alpha1, true);
		if (visible1 != visible) setVisible(visible1);
		if (locked1 != locked) setLocked(locked1);
		/*
		// repaint old position
		Display.repaint(layer, box, 5);
		// repaint new position
		Display.repaint(layer, this, 5);
		// If positions don't change, the threading system will prevent a useless repaint
		*/
		// it's lengthy to predict the precise box for each open Display, so just repaint all in all Displays.
		Display.updateSelection();
		Display.repaint(getLayer()); // not this.layer, so ZDisplayables are repainted properly
	}

	static protected final String TAG_ATTR1 = "<!ATTLIST ";
	static protected final String TAG_ATTR2 = " NMTOKEN #REQUIRED>\n";

	/** Adds simply DTD !ATTRIBUTE tags. The ProjectThing that encapsulates this object will give the type. */
	static public void exportDTD(String type, StringBuffer sb_header, HashSet hs, String indent) {
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" oid").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" layer_id").append(TAG_ATTR2)
			 /*
			 .append(indent).append(TAG_ATTR1).append(type).append(" x").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" y").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" width").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" height").append(TAG_ATTR2)
			 */
			 .append(indent).append(TAG_ATTR1).append(type).append(" transform").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" style").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" locked").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" visible").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" title").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" links").append(TAG_ATTR2)
		;
	}

	/** The oid is this objects' id, whereas the 'id' tag will be the id of the wrapper Thing object. */
	public void exportXML(StringBuffer sb_body, String in, Object any) {
		final double[] a = new double[6];
		at.getMatrix(a);
		sb_body.append(in).append("oid=\"").append(id).append("\"\n")
			/*
			.append(in).append("x=\"").append(x).append("\"\n")
			.append(in).append("y=\"").append(y).append("\"\n")
			.append(in).append("rot=\"").append(rot).append("\"\n")
			.append(in).append("width=\"").append(width).append("\"\n")
			.append(in).append("height=\"").append(height).append("\"\n")
			*/
			.append(in).append("transform=\"matrix(").append(a[0]).append(',')
								.append(a[1]).append(',')
								.append(a[2]).append(',')
								.append(a[3]).append(',')
								.append(a[4]).append(',')
								.append(a[5]).append("\"\n")
		;
		// the default is obvious, so just store the value if necessary
		if (locked) sb_body.append(in).append("locked=\"true\"\n");
		if (!visible) sb_body.append(in).append("visible=\"false\"\n");
		// 'style' is taken care in subclasses
		if (null != title && title.length() > 0) {
			sb_body.append(in).append("title=\"").append(title.replaceAll("\"", "^#^")).append("\"\n"); // fix possible harm by '"' characters (backslash should be taken care of as well TODO)
		}
		sb_body.append(in).append("links=\"");
		if (null != hs_linked && 0 != hs_linked.size()) {
			int ii = 0;
			int len = hs_linked.size();
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				sb_body.append(((DBObject)ob).getId());
				if (ii != len-1) sb_body.append(',');
				ii++;
			}
		}
		sb_body.append("\"\n");
	}

	// I'm sure it could be made more efficient, but I'm too tired!
	public boolean hasLinkedGroupWithinLayer(Layer la) {
		HashSet hs = getLinkedGroup(new HashSet());
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (!d.layer.equals(la)) return false;
		}
		return true;
	}

	public ini.trakem2.display.Transform getTransform() {
		Utils.log2("called Deprecated Displayable.getTransform for " + this);
		Utils.printCaller(this, 3);
		return new ini.trakem2.display.Transform(x, y, width, height, rot, alpha, visible, locked, color, layer);
	}

	/** Set the transform and save in database; does NOT repaint, nor affects any linked objects. */
	public void setTransform(Transform t) {
		Utils.log2("called Deprecated Displayable.setTransform for " + this);
	}

	/** Rotate 2D points relative to the given pivot point by the given rot angle (in radians), in the 2D plane. */
	static public double[][] rotatePoints(double[][] p, double rot, double xo, double yo) {
		//Utils.log("calling rotatePoints for " + p + "  with rot=" + Math.toDegrees(rot));
		if (null == p) {
			Utils.log2("WARNING: Displayable.rotatePoints received a null points array.");
			return new double[0][0];
		}
		int length = p[0].length;
		double[][] pr = new double[p.length][length];
		for (int i=0; i<length; i++) {
			// catch for readability
			double x = p[0][i];
			double y = p[1][i];
			// angle relative to the pivot point
			double b1 = Utils.getAngle(x - xo, y - yo);
			// new angle relative to pivot point
			double b2 = b1 + rot;
			// new location
			double hypot = Math.sqrt((x - xo)*(x - xo) + (y - yo)*(y - yo));
			pr[0][i] = xo + (Math.cos(b2) * hypot); //x + (Math.cos(b2) - Math.cos(b1)) * hypot;
			pr[1][i] = yo + (Math.sin(b2) * hypot); //y + (Math.sin(b2) - Math.sin(b1)) * hypot;
		}
		return pr;
	}

	/** Scale 2D points relative to the given pivot point xo,yo. */
	static public double[][] scalePoints(double[][] p, double sx, double sy, double xo, double yo) {
		int length = p[0].length;
		double[][] ps = new double[p.length][length];
		for (int i=0; i<length; i++) {
			ps[0][i] = xo + (p[0][i] - xo) * sx;
			ps[1][i] = yo + (p[1][i] - yo) * sy;
		}
		return ps;
	}

	static public double[][] displacePoints(double[][] p, double dx, double dy) {
		int length = p[0].length;
		double[][] pd = new double[p.length][length];
		for (int i=0; i<length; i++) {
			pd[0][i] = p[0][i] + dx;
			pd[1][i] = p[1][i] + dy;
		}
		return pd;
	}

	/** Transform in place only the 'i' point in the points array.*/
	static public void transformPoint(double[][] p, int i, double dx, double dy, double rot, double xo, double yo) {
		p[0][i] += dx;
		p[1][i] += dy;
		if (0 != rot) {
			double hypot = Math.sqrt(Math.pow(p[0][i] - xo, 2) + Math.pow(p[1][i] - yo, 2));
			double angle = Utils.getAngle(p[0][i] - xo, p[1][i] - yo);
			p[0][i] = xo + Math.cos(angle + rot) * hypot;
			p[1][i] = yo + Math.sin(angle + rot) * hypot;
		}
	}

	/** Fine nearest point in array a, from 0 up to n_points, to point x_p,y_p, with accuracy dependeing on .*/
	static protected int findNearestPoint(double[][] a, int n_points, double x_p, double y_p) {
		if (0 == n_points) return -1;
		double min_dist = Double.MAX_VALUE;
		int index = -1;
		for (int i=0; i<n_points; i++) {
			double sq_dist = Math.pow(a[0][i] - x_p, 2) + Math.pow(a[1][i] - y_p, 2);
			if (sq_dist < min_dist) {
				index = i;
				min_dist = sq_dist;
			}
		}
		return index;
	}

	/** Performs a deep copy of this object. */
	abstract public Object clone();

	public LayerSet getLayerSet() {
		if (null != layer) return layer.getParent();
		return null;
	}

	public boolean updateInDatabase(String key) {
		project.getLoader().updateCache(this, key);
		//if (Utils.java3d) Display3D.update(this);
		return super.updateInDatabase(key);
	}

	static public Rectangle getMinimalBoundingBox(Displayable[] d) {
		final Rectangle box = d[0].getBoundingBox();
		final Rectangle tmp = new Rectangle();
		for (int i=1; i<d.length; i++) {
			box.add(d[i].getBoundingBox(tmp));
		}
		return box;
	}

	public AffineTransform getAffineTransform() {
		return at;
	}

	public AffineTransform getAffineTransformCopy() {
		return (AffineTransform)at.clone();
	}

	public void setAffineTransform(AffineTransform at) {
		this.at = at;
		updateInDatabase("transform");
	}

	public void translate(double dx, double dy) {
		AffineTransform at2 = new AffineTransform();
		at2.translate(dx, dy);
		this.at.preConcatenate(at2);
		updateInDatabase("transform");
	}

	/** Rotate relative to an anchor point. */
	public void rotate(double radians, double xo, double yo) {
		AffineTransform at2 = new AffineTransform();
		at2.rotate(radians, xo, yo);
		this.at.preConcatenate(at2);
		updateInDatabase("transform");
	}

	/** Scale relative to an anchor point (will translate as necessary). */
	public void scale(double sx, double sy, double xo, double yo) {
		Rectangle b1 = getBoundingBox(null);
		this.at.scale(sx, sy);
		Rectangle b2 = getBoundingBox(null);
		// old top-left local to the anchor
		double x1 = b1.x - xo;
		double y1 = b1.y - yo;
		// new top-left local to the anchor
		double x2 = b2.x - xo;
		double y2 = b2.y - yo;
		// desired new top-left position
		double x3 = x1 * sx;
		double y3 = y1 * sy;
		//Utils.log2("x3,y3: " + x3 + "," + y3 + "\nx2,y2: " + x2 + "," + y2);
		// top-left should be at x1*sx,y1*sy distance from the anchor
		AffineTransform at2 = new AffineTransform();
		at2.translate(x3 - x2, y3 - y2);
		this.at.preConcatenate(at2);
		updateInDatabase("transform");
	}
}
