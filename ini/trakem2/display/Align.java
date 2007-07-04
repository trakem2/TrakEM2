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

import ij.IJ;
import ij.gui.YesNoCancelDialog;

import ini.trakem2.imaging.Registration;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.tree.Thing;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;

/** Aligns objects from two layers, the objects in the second layer relative to those in the first (which are left untouched). The objects to use are independent of the Display's selection, but must lay under the landmarks set by the mouse. */
public class Align {

	private Display display;
	/** Points in layer 1.*/
	private Landmark l1;
	/** Points in layer 2*/
	private Landmark l2;

	public Align(Display display) {
		this.display = display;
	}

	/** Accepts offscreen coordinates. */
	public void mousePressed(Layer layer, MouseEvent me, int x_p, int y_p) {
		// check preconditions
		if (null == layer || x_p < 0 || y_p < 0 || x_p > layer.getLayerWidth() || y_p > layer.getLayerHeight()) return;
		// create landmark sets if not there
		if (null == l1) {
			l1 = new Landmark(layer);
			l1.add(x_p, y_p);
		} else if (l1.layer.equals(layer)) {
			if (me.isShiftDown()) l1.remove(x_p, y_p);
			else l1.add(x_p, y_p);
		} else if (null == l2) {
			l2 = new Landmark(layer);
			l2.add(x_p, y_p);
		} else if (l2.layer.equals(layer)) {
			if (me.isShiftDown()) l2.remove(x_p, y_p);
			else l2.add(x_p, y_p);
		} else {
			// ignore, different layer
			Utils.showMessage("You have landmarks in two layers already:\nLayer 1: " + l1.layer + "\nLayer 2: " + l2.layer);
		}
	}

	public void paint(Layer layer, Graphics g, Rectangle srcRect, double mag) {
		if (null != l1 && l1.layer.equals(layer)) l1.paint(g, srcRect, mag, Color.yellow);
		if (null != l2 && l2.layer.equals(layer)) l2.paint(g, srcRect, mag, Color.magenta);
	}

	/** A set of landmarks in a specific Layer. */
	private class Landmark {
		Layer layer;
		private ArrayList landmarks;
		Landmark(Layer layer) {
			this.layer = layer;
			this.landmarks = new ArrayList();
		}
		void destroy() {
			this.layer = null;
			this.landmarks = null;
		}
		void add(int x, int y) {
			// check that the point doesn't exist already
			for (Iterator it = landmarks.iterator(); it.hasNext(); ) {
				Point p = (Point)it.next();
				if (p.x == x && p.y == y) return;
			}
			landmarks.add(new Point(x, y));
			Display.repaint(layer); // TODO optimize this!
		}
		void remove(int x, int y) {
			for (Iterator it = landmarks.iterator(); it.hasNext(); ) {
				Point p = (Point)it.next();
				if (p.x == x && p.y == y) {
					it.remove();
					return;
				}
			}
		}
		Point get(int i) {
			if (i < 0 || i > landmarks.size()) return null;
			return (Point)landmarks.get(i);
		}
		/** Draws a point with it's ordinal number next to it. */
		void paint(Graphics g, Rectangle srcRect, double mag, Color color) {
			int i = 1;
			g.setFont(new Font("SansSerif", Font.BOLD, 14));
			for (Iterator it = landmarks.iterator(); it.hasNext(); ) {
				Point p = (Point)it.next();
				int x = (int)((p.x - srcRect.x)*mag);
				int y = (int)((p.y - srcRect.y)*mag);
				// draw a cross at the exact point
				g.setColor(Color.black);
				g.drawLine(x-4, y+1, x+4, y+1);
				g.drawLine(x+1, y-4, x+1, y+4);
				g.setColor(color);
				g.drawLine(x-4, y, x+4, y);
				g.drawLine(x, y-4, x, y+4);
				// draw the index
				g.drawString(Integer.toString(i), x+5, y+5);
				i++;
			}
		}
		int size() {
			return landmarks.size();
		}
		/** Returns all displayables under all landmarks and their linked ones. */
		HashSet getDisplayables() {
			HashSet hs = new HashSet();
			for (Iterator it = landmarks.iterator(); it.hasNext(); ) {
				Point p = (Point)it.next();
				for (Iterator itd = layer.find(p.x, p.y).iterator(); itd.hasNext(); ) {
					Displayable d = (Displayable)itd.next();
					if (hs.contains(d)) continue;
					hs = d.getLinkedGroup(hs);
				}
			}
			return hs;
		}
	}

	public boolean canAlign() {
		if (null == l1 || null == l2 || l1.size() != l2.size()) return false;
		return true;
	}

	/** Aligns the objects under the landmarks (and their linked ones) in layer two relative to the landmarks in layer one. If post_register is true, the landmarks are used as a pre-aligning, and then FFT-based registration of all images in both layers is performed, within the limits previously set as properties. */
	public void apply(final boolean post_register) {
		// check preconditions
		if (null == l1) {
			Utils.showMessage("Click to add landmarks in two different layers.");
			return;
		}
		if (null == l2) {
			Utils.showMessage("Need landmarks in a second layer as well.");
			return;
		}
		if (l1.size() != l2.size()) {
			Utils.showMessage("Please add an equal number of landmark points!\nThere are:\n\t" + l1.size() + " landmarks in layer " + l1.layer + "\n\t" + l2.size() + " landmarks in layer " + l2.layer);
			return;
		}
		// go!

		// select objects under landmarks and their linked ones
		HashSet hs1 = l1.getDisplayables();
		HashSet hs2 = l2.getDisplayables();
		// check that both hashsets do not overlap
		for (Iterator it = hs1.iterator(); it.hasNext(); ) {
			if (hs2.contains(it.next())) {
				Utils.showMessage("Can't align: objects under landmarks in both layers belong to the same linking group.");
				display.getLayer().getParent().cancelAlign(); // convoluted way of repainting and destroying itself after being applied
				return;
			}
		}
		// check that no objects are locked
		HashSet hs_locked = new HashSet();
		for (Iterator it = hs2.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			if (d.isLocked()) {
				hs_locked.add(d);
			}
		}
		if (hs_locked.size() > 0) {
			YesNoCancelDialog yn = new YesNoCancelDialog(IJ.getInstance(), "Locked objects", "There are " + hs_locked.size() + " locked objects. Unlock them?");
			if (yn.yesPressed()) {
				for (Iterator it = hs_locked.iterator(); it.hasNext(); ) {
					((Displayable)it.next()).setLocked(false);
				}
			} else if (yn.cancelPressed()) {
				Display.updateSelection();
				display.getLayer().getParent().cancelAlign(); // [convoluted way of] repainting and destroying itself after being applied
			} else {
				// "No". Don't unlock, thus don't affect the locked objects
				for  (Iterator it = hs_locked.iterator(); it.hasNext(); ) {
					hs2.remove(it.next());
				}
			}
			return;
		}
		if (0 == hs2.size()) {
			Display.updateSelection();
			display.getLayer().getParent().cancelAlign(); // [convoluted way of] repainting and destroying itself after being applied
			return;
		}
		// setup an undo
		Hashtable ht = new Hashtable();
		/*for (Iterator it = hs1.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			ht.put(d, d.getTransform());
		}*/ // only second layer will be affected
		for (Iterator it = hs2.iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			ht.put(d, d.getAffineTransformCopy());
		}
		l1.layer.getParent().addUndoStep(ht);
		ArrayList base_points = new ArrayList();
		ArrayList target_points = new ArrayList();
		for (int i=l1.size()-1; i>-1; i--) {
			base_points.add(l1.get(i));
			target_points.add(l2.get(i));
		}
		double[] data = crunchLandmarks(base_points, target_points);
		double dx = data[0];
		double dy = data[1];
		double angle = data[2];
		double xo = data[3];
		double yo = data[4];
		// transforming those of the second layer only
		boolean translate = true;
		boolean rotate = true;
		if (0 == angle) {
			for (Iterator it = hs2.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				d.drag(dx, dy, 1);
			}
		} else {
			for (Iterator it = hs2.iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				Transform t = d.getTransform();
				if (translate) {
					t.x += dx;
					t.y += dy;
				}
				/*
				if (scale) {
					t.scale(scale, scale, ?, ?); // need the boxes ... I just don't like it, besides, the scaling will introduce artifacts rather than correct them.
				}
				*/
				if (rotate) {
					t.rotate(angle, xo, yo);
				}
				d.setTransform(t);
			}
		}
		ini.trakem2.Project p = l1.layer.getProject();
		String log = "Aligned target=[" + p.findLayerThing(l2.layer).getTitle() + " z=" + l2.layer.getZ() + "] to base=[" + p.findLayerThing(l1.layer).getTitle() + " z=" + l1.layer.getZ() + "] with angle=" + angle + " xo=" + xo + " yo=" + yo + " dx=" + dx + " dy=" + dy;
		Utils.log(log);
		ij.IJ.write(log);

		// Stephan Preibisch's Cepstrum implementation for image registration
		if (post_register && l1.layer.contains(Patch.class) && l2.layer.contains(Patch.class)) {
			final LayerSet ls = display.getLayer().getParent();
			final double max_rot = ls.getRegistrationProperty("rg_max_rot").doubleValue();
			final double max_displacement = ls.getRegistrationProperty("rg_max_displacement").doubleValue();
			final double scale = ls.getRegistrationProperty("rg_scale").doubleValue();
			final boolean ignore_squared_angles = (ls.getRegistrationProperty("rg_ignore_squared_angles").equals(new Double(1)));
			final boolean enhance_edges = (ls.getRegistrationProperty("rg_enhance_edges").equals(new Double(1)));
			Registration.registerLayers(l1.layer, l2.layer, max_rot, max_displacement, scale, ignore_squared_angles, enhance_edges);
		}

		// end
		Display.updateSelection();
		display.getLayer().getParent().cancelAlign(); // [convoluted way of] repainting and destroying itself after being applied
	}

	/** Returns dx, dy, rot, xo, yo from the two sets of points, where xo,yo is the center of rotation */
	public double[] crunchLandmarks(final ArrayList base, final ArrayList target) {
		if (1 == base.size()) {
			// if one point per layer, just translate the second to the first
			Point p1 = (Point)base.get(0);
			Point p2 = (Point)target.get(0);
			double dx = p1.x - p2.x;
			double dy = p1.y - p2.y;
			return new double[]{dx, dy, 0, 0, 0};
		} else {
			// center of mass:
			double xo1=0, yo1=0;
			double xo2=0, yo2=0;
			// Implementing Johannes Schindelin's squared error minimization formula
			// tan(angle) = Sum(x1*y1 + x2y2) / Sum(x1*y2 - x2*y1)
			int length = base.size();
			// 1 - compute centers of mass, for displacement and origin of rotation
			for (int i=0; i<length; i++) {
				Point p1 = (Point)base.get(i);
				Point p2 = (Point)target.get(i);
				xo1 += p1.x;
				yo1 += p1.y;
				xo2 += p2.x;
				yo2 += p2.y;
			}
			xo1 /= length;
			yo1 /= length;
			xo2 /= length;
			yo2 /= length;
			//
			double dx = xo1 - xo2; // reversed, because the second will be moved relative to the first
			double dy = yo1 - yo2;
			double sum1=0, sum2=0;
			double x1,y1,x2,y2;
			for (int i=0; i<length; i++) {
				Point p1 = (Point)base.get(i);
				Point p2 = (Point)target.get(i);
				// make points local to the center of mass of the first landmark set
				x1 = p1.x - xo1; // x1
				y1 = p1.y - yo1; // x2
				x2 = p2.x - xo2 + dx; // y1
				y2 = p2.y - yo2 + dy; // y2
				sum1 += x1 * y2 - y1 * x2; //   x1 * y2 - x2 * y1 // assuming p1 is x1,x2, and p2 is y1,y2
				sum2 += x1 * x2 + y1 * y2; //   x1 * y1 + x2 * y2
			}
			double angle = Math.atan2(-sum1,sum2);
			angle = Math.toDegrees(angle);
			return new double[]{dx, dy, angle, xo1, yo1};
		}
	}

	/** For the selected layer range, take the center of profiles that have linked profiles in the next layer as landmarks, and align all objects in the next layer. The applied transformations do not propagate to other layers regardless of links. */
	public void apply(final Layer la_start, final Layer la_end, final Selection selection) {
		Utils.log2("start apply using profiles");
		// check preconditions
		final LayerSet ls = la_start.getParent();
		if (la_start.equals(la_end) || !selection.contains(Profile.class) || !ls.equals(la_end.getParent()) || ls.indexOf(la_start) > ls.indexOf(la_end) || selection.isLocked()) {
			Utils.log2("align: improper preconditions");
			return;
		}
		// clear
		destroy();
		// generate lists of profile_list objects in selection
		ArrayList al_profile_lists = new ArrayList();
		ArrayList al_profiles = new ArrayList();
		for (Iterator it = selection.getSelected(Profile.class).iterator(); it.hasNext(); ) {
			Profile pro = (Profile)it.next();
			boolean found = false;
			for (Iterator plit = al_profile_lists.iterator(); plit.hasNext(); ) {
				Thing thing = (Thing)plit.next();
				if (null != thing.findChild(pro)) {
					found = true;
					break;
				}
			}
			if (!found) {
				Thing profile_list = ls.getProject().findProjectThing(pro).getParent();
				al_profile_lists.add(profile_list);
				for (Iterator ot = profile_list.getChildren().iterator(); ot.hasNext(); ) {
					Thing child = (Thing)ot.next();
					al_profiles.add(child.getObject());
				}
			}
		}
		// now, find selected profiles which continue into the following layer
		Layer la_base = la_start;
		Layer la_target = ls.next(la_start);
		// setup general undo
		ls.createUndoStep();
		ls.getProject().getLoader().startLargeUpdate();
		try {
			// now process consecutive pairs of layers
			double[] previous_data = null;
			while (true) {
				final ArrayList base_landmarks = new ArrayList();
				final ArrayList target_landmarks = new ArrayList();
				// gather profiles in la_start
				final ArrayList al_b = la_base.getDisplayables(Profile.class);
				final ArrayList al_t = la_target.getDisplayables(Profile.class);
				Utils.log2("size: " + al_b.size() + " - " + al_t.size());
				// filter out non-selected profiles
				for (Iterator it = al_b.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (!al_profiles.contains(ob)) {
						it.remove();
					}
				}
				for (Iterator it = al_t.iterator(); it.hasNext(); ) {
					Object ob = it.next();
					if (!al_profiles.contains(ob)) {
						it.remove();
					}
				}
				Utils.log2("size after: " + al_b.size() + " - " + al_t.size());
				// find which of the profiles in b are diretctly linked to profiles in t, and generate landmarks
				// (note: this is assymetric, some profiles in t may not have any correspondent in b, and are thus dully ignored)
				for (Iterator it = al_b.iterator(); it.hasNext(); ) {
					Profile pro_b = (Profile)it.next();
					for (Iterator lit = pro_b.getLinked(Profile.class).iterator(); lit.hasNext(); ) {
						Object ob = lit.next();
						if (al_t.contains(ob)) {
							Utils.log2("for " + pro_b + " , added profile " + ob);
							// generate landmark pair
							Rectangle box = new Rectangle(); // reusable pointer
							box = pro_b.getBoundingBox(box);
							base_landmarks.add(new Point(box.x + box.width/2, box.y + box.height/2));
							box = ((Profile)ob).getBoundingBox(box);
							target_landmarks.add(new Point(box.x + box.width/2, box.y + box.height/2));
						}
					}
				}
				double[] data = null;
				if (0 != base_landmarks.size()) {
					data = crunchLandmarks(base_landmarks, target_landmarks);
					previous_data = data;
				} else if (null != previous_data) {
					// follow the last applied alignment
					data = previous_data;
				}
				Utils.log("points: " + base_landmarks.size());
				// use the landmarks to transform all objects in the layer but none of their linked Displayable objects in other layers
				if (null != data) {
					alignLayer(la_target, data);
					// debug:
					double dx = data[0];
					double dy = data[1];
					double rot = data[2];
					double xo = data[3];
					double yo = data[4];
					Utils.log2("base: " + la_base + " target: " + la_target + "dx,dy,rot,xo,yo: " + dx + "," + dy + "  " + rot + "," + xo + "," + yo);
				}

				// finish if possible
				if (la_target.equals(la_end)) break;
				/// prepare next iteration
				la_base = la_target;
				la_target = ls.next(la_base);
			}
			// now apply last transform data to all layers after the last target, to preserve their registration with the last registered one
			for (int i=ls.indexOf(la_end) +1; i < ls.size(); i++) {
				alignLayer((Layer)ls.getLayers().get(i), previous_data);
			}

			// done!
			ls.getProject().getLoader().commitLargeUpdate();
		} catch (Exception e) {
			new IJError(e);
			ls.getProject().getLoader().rollback();
			ls.undoOneStep();
		}
		// repaint and finish
		Display.updateSelection();
		ls.cancelAlign(); // [convoluted way of] repainting and destroying itself after being applied
	}

	private void alignLayer(final Layer la_target, final double[] data) {
		double dx = data[0];
		double dy = data[1];
		double rot = data[2];
		double xo = data[3];
		double yo = data[4];
		for (Iterator it = la_target.getDisplayables().iterator(); it.hasNext(); ) {
			Displayable d = (Displayable)it.next();
			Transform t = d.getTransform();
			t.translate(dx, dy);
			if (0 != rot) {
				t.rotate(rot, xo, yo);
			}
			d.setTransform(t);
		}
		// translate/rotate points of ZDisplayable objects that fall within the target layer
		for (Iterator it = la_target.getParent().getZDisplayables().iterator(); it.hasNext(); ) {
			ZDisplayable zd = (ZDisplayable)it.next();
			zd.transformPoints(la_target, dx, dy, rot, xo, yo);
		}
	}

	public void cancel() {
		if (null != l1) {
			Display.repaint(l1.layer);
			l1.destroy();
			l1 = null;
		}
		if (null != l2) {
			Display.repaint(l2.layer);
			l2.destroy();
			l2 = null;
		}
		display = null;
	}

	public void destroy() {
		if (null != l1) {
			l1.destroy();
			l1 = null;
		}
		if (null != l2) {
			l2.destroy();
			l2 = null;
		}
		display = null;
	}
}
