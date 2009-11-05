package ini.trakem2.display;

import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.Component;
import java.awt.geom.AffineTransform;
import java.awt.Stroke;
import java.awt.BasicStroke;

import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.Display;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;

import mpicbg.models.TranslationModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import mpicbg.trakem2.align.Align;
import ij.gui.GenericDialog;

public class ManualAlignMode implements Mode {

	final private GraphicsSource gs = new GraphicsSource() {
		/** Returns the list given as argument without any modification. */
		public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
			return ds;
		}

		public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			Landmarks lm  = m.get(display.getLayer());

			// Set clean canvas
			AffineTransform t = g.getTransform();
			g.setTransform(new AffineTransform());
			Stroke stroke = g.getStroke();
			g.setStroke(new BasicStroke(1.0f));

			if (null != lm) {
				lm.paint(g, srcRect, magnification);
			}

			// Restore
			g.setTransform(t);
			g.setStroke(stroke);
		}
	};

	final private Display display;

	public ManualAlignMode(final Display display) {
		this.display = display;
	}

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return true; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	public boolean isDragging() {
		return false;
	}

	public class Landmarks {
		Layer layer;
		ArrayList<Point> points = new ArrayList<Point>();

		public Landmarks(Layer layer) {
			this.layer = layer;
		}
		/** Returns the index of the newly added point, or -1 if the layer doesn't match. */
		synchronized public int add(Layer layer, float x_p, float y_p) {
			if (this.layer != layer) return -1;
			points.add(new Point(new float[]{x_p, y_p}));
			return points.size() -1;
		}

		/** Returns the index of the closest point, with accuracy depending on magnification. */
		synchronized public int find(final float x_p, final float y_p, final double mag) {
			int index = -1;
			double d = 10 / mag;
			if (d < 2) d = 2;
			double min_dist = Integer.MAX_VALUE;
			int i = 0;
			final Point ref = new Point(new float[]{x_p, y_p});
			for (final Point p : points) {
				double dist = Point.distance(ref, p);
				if (dist <= d && dist <= min_dist) {
					min_dist = dist;
					index = i;
				}
				i++;
			}
			return index;
		}

		/** Sets the point at @param index to the new location. */
		synchronized public void set(final int index, final float x_d, final float y_d) {
			if (index < 0 || index >= points.size()) return;
			Point p = points.remove(index);
			points.add(index, new Point(new float[]{x_d, y_d}));
		}

		synchronized public void remove(final int index) {
			if (index < 0 || index >= points.size()) return;
			points.remove(index);
		}

		synchronized public void paint(final Graphics2D g, final Rectangle srcRect, final double mag) {
			g.setColor(Color.yellow);
			g.setFont(new Font("SansSerif", Font.BOLD, 14));
			int i = 1;
			for (final Point p : points) {
				float[] w = p.getW();
				int x = (int)((w[0] - srcRect.x) * mag);
				int y = (int)((w[1] - srcRect.y) * mag);
				// draw a cross at the exact point
				g.setColor(Color.black);
				g.drawLine(x-4, y+1, x+4, y+1);
				g.drawLine(x+1, y-4, x+1, y+4);
				g.setColor(Color.yellow);
				g.drawLine(x-4, y, x+4, y);
				g.drawLine(x, y-4, x, y+4);
				// draw the index
				g.drawString(Integer.toString(i), x+5, y+5);
				i++;
			}
		}
	}

	private HashMap<Layer,Landmarks> m = new HashMap<Layer,Landmarks>();

	private Layer current_layer = null;
	private int index = -1;

	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		current_layer = display.getLayer();

		// Find an existing Landmarks object for the current layer
		Landmarks lm = m.get(current_layer);
		if (null == lm) {
			lm = new Landmarks(current_layer);
			m.put(current_layer, lm);
		}
		// Find the point in it
		index = lm.find(x_p, y_p, magnification);

		if (-1 == index) {
			index = lm.add(current_layer, x_p, y_p);
		} else if (Utils.isControlDown(me) && me.isShiftDown()) {
			lm.remove(index);
			if (0 == lm.points.size()) {
				m.remove(current_layer);
			}
		}

		display.repaint();
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		Utils.log2("index is " + index);
		if (-1 != index && current_layer == display.getLayer()) {
			Landmarks lm = m.get(current_layer);
			Utils.log2("lm is " + lm);
			if (null != lm) {
				lm.set(index, x_d, y_d);
				display.repaint();
			}
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// Do nothing
	}

	public void undoOneStep() {}
	public void redoOneStep() {}

	public boolean apply() {

		// Check there's more than one layer
		if (m.size() < 2) {
			Utils.showMessage("Need more than one layer to align!");
			return false;
		}

		// Check that the current layer is one of the layers with landmarks.
		// Will be used as reference
		final Layer ref_layer = display.getLayer();
		if (null == m.get(ref_layer)) {
			Utils.showMessage("Please scroll to a layer with landmarks,\nto be used as reference.");
			return false;
		}

		// Check that all layers have the same number of landmarks
		int n_landmarks = -1;
		for (final Map.Entry<Layer,Landmarks> e : m.entrySet()) {
			Landmarks lm = e.getValue();
			if (-1 == n_landmarks) {
				n_landmarks = lm.points.size();
				continue;
			}
			if (n_landmarks != lm.points.size()) {
				Utils.showMessage("Can't apply: there are different amounts of landmarks per layer.\nSee the log window.");
				for (final Map.Entry<Layer,Landmarks> ee : m.entrySet()) {
					Utils.log(ee.getValue().points.size() + " landmarks in layer " + ee.getKey());
				}
				return false;
			}
		}

		// Sort Layers by Z
		final TreeMap<Layer,Landmarks> sorted = new TreeMap<Layer,Landmarks>(new Comparator<Layer>() {
			public boolean equals(Object ob) {
				return this == ob;
			}
			public int compare(Layer l1, Layer l2) {
				// Ascending order
				double dz = l1.getZ() - l2.getZ();
				if (dz < 0) return -1;
				else if (dz > 0) return 1;
				else return 0;
			}
		});
		sorted.putAll(m);

		int iref = 0;
		for (Layer la : sorted.keySet()) {
			if (la != ref_layer) iref++;
			else break;
		}

		// Ok, now ask for a model
		GenericDialog gd = new GenericDialog("Model");
		gd.addChoice("Model:", Align.Param.modelStrings, Align.Param.modelStrings[1]);
		gd.addCheckbox("Propagate to first layer", 0 != iref);
		((Component)gd.getCheckboxes().get(0)).setEnabled(0 != iref);
		gd.addCheckbox("Propagate to last layer", sorted.size()-1 != iref);
		((Component)gd.getCheckboxes().get(1)).setEnabled(sorted.size()-1 != iref);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		int model_index = gd.getNextChoiceIndex();
		final boolean propagate_to_first = gd.getNextBoolean();
		final boolean propagate_to_last = gd.getNextBoolean();

		int min;

		// Create a model as desired
		final AbstractAffineModel2D< ? > model;
		switch ( model_index ) {
			case 0:
				min = 1;
				model = new TranslationModel2D();
				break;
			case 1:
				min = 2;
				model = new RigidModel2D();
				break;
			case 2:
				min = 2;
				model = new SimilarityModel2D();
				break;
			case 3:
				min = 3;
				model = new AffineModel2D();
				break;
			default:
				Utils.log("Unknown model index!");
				return false;
		}

		if (n_landmarks < min) {
			Utils.showMessage("Need at least " + min + " landmarks for a " + Align.param.modelStrings[model_index] + " model");
			return false;
		}

		Bureaucrat.createAndStart(new Worker.Task("Aligning layers with landmarks") {
			public void exec() {

		// Find layers with landmarks, in increasing Z.
		// Match in pairs.

		// So, get two submaps: from ref_layer to first, and from ref_layer to last
		SortedMap<Layer,Landmarks> first_chunk = new TreeMap<Layer,Landmarks>(sorted.headMap(ref_layer)); // strictly lower Z than ref_layer
		first_chunk.put(ref_layer, m.get(ref_layer)); // .. so add ref_layer

		SortedMap<Layer,Landmarks> second_chunk = sorted.tailMap(ref_layer); // equal or larger Z than ref_layer

		// Reverse order of first_chunk
		if (first_chunk.size() > 1) {
			SortedMap<Layer,Landmarks> fc = new TreeMap<Layer,Landmarks>(new Comparator<Layer>() {
				public boolean equals(Object ob) {
					return this == ob;
				}
				public int compare(Layer l1, Layer l2) {
					// Descending order
					double dz = l2.getZ() - l1.getZ();
					if (dz < 0) return -1;
					else if (dz > 0) return 1;
					else return 0;
				}
			});
			fc.putAll(first_chunk);
			first_chunk = fc;
		}

		LayerSet ls = ref_layer.getParent();

		// Apply!

		// Setup undo
		ls.addTransformStep();

		if (first_chunk.size() > 1) {
			AffineTransform aff = align(first_chunk, model);
			if (propagate_to_first) {
				for (Layer la : ls.getLayers().subList(0, ls.indexOf(first_chunk.lastKey()))) { // exclusive last
					la.apply(Patch.class, aff);
				}
			}
		}
		if (second_chunk.size() > 1) {
			AffineTransform aff = align(second_chunk, model);
			if (propagate_to_last) {
				for (Layer la : ls.getLayers().subList(ls.indexOf(second_chunk.lastKey()) + 1, ls.size())) { // exclusive last
					la.apply(Patch.class, aff);
				}
			}
		}

		Display.repaint();

		// Store current state
		ls.addTransformStep();


		}}, display.getProject());

		return true;
	}

	private AffineTransform align(SortedMap<Layer,Landmarks> sm, final AbstractAffineModel2D< ? > model) {
		Layer layer1 = sm.firstKey();
		Landmarks lm1 = sm.get(sm.firstKey());

		AffineTransform accum = new AffineTransform();

		for (Map.Entry<Layer,Landmarks> e : sm.entrySet()) {
			Layer layer2 = e.getKey();
			if (layer1 == layer2) continue;
			Landmarks lm2 = e.getValue();
			// Create pointmatches
			ArrayList<PointMatch> matches = new ArrayList<PointMatch>();
			for (int i=0; i<lm1.points.size(); i++) {
				matches.add(new PointMatch(lm2.points.get(i), lm1.points.get(i)));
			}

			AbstractAffineModel2D< ? > mod = model.clone();
			try {
				mod.fit(matches);
			} catch (Throwable t) {
				IJError.print(t);
				// continue happily
			}

			accum.preConcatenate(mod.createAffine());

			layer2.apply(Patch.class, accum);

			layer1 = layer2;
			lm1 = lm2;
		}

		return accum;
	}


	public boolean cancel() {
		return true;
	}

	public Rectangle getRepaintBounds() {
		return (Rectangle) display.getCanvas().getSrcRect().clone();
	}

	public void srcRectUpdated(Rectangle srcRect, double magnification) {}
	public void magnificationUpdated(Rectangle srcRect, double magnification) {}
}
