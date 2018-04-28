/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2009 Albert Cardona.

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
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.align.Align;
import mpicbg.trakem2.align.AlignTask;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

public class ManualAlignMode implements Mode {

	final private Display display;

	private final HashMap<Layer,Landmarks> m = new HashMap<Layer,Landmarks>();


	final private GraphicsSource gs = new GraphicsSource() {
		/** Returns the list given as argument without any modification. */
		@Override
        public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
			return ds;
		}

		@Override
        public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			final Landmarks lm  = m.get(display.getLayer());

			// Set clean canvas
			final AffineTransform t = g.getTransform();
			g.setTransform(new AffineTransform());
			final Stroke stroke = g.getStroke();
			g.setStroke(new BasicStroke(1.0f));

			if (null != lm) {
				lm.paint(g, srcRect, magnification);
			}

			// Restore
			g.setTransform(t);
			g.setStroke(stroke);
		}
	};

	public ManualAlignMode(final Display display) {
		this.display = display;
	}

	@Override
    public GraphicsSource getGraphicsSource() {
		return gs;
	}

	@Override
    public boolean canChangeLayer() { return true; }
	@Override
    public boolean canZoom() { return true; }
	@Override
    public boolean canPan() { return true; }

	@Override
    public boolean isDragging() {
		return false;
	}

	public class Landmarks {
		Layer layer;
		ArrayList<Point> points = new ArrayList<Point>();

		public Landmarks(final Layer layer) {
			this.layer = layer;
		}
		/** Returns the index of the newly added point, or -1 if the layer doesn't match. */
		synchronized public int add(final Layer layer, final double x_p, final double y_p) {
			if (this.layer != layer) return -1;
			points.add(new Point(new double[]{x_p, y_p}));
			return points.size() -1;
		}

		/** Returns the index of the closest point, with accuracy depending on magnification. */
		synchronized public int find(final double x_p, final double y_p, final double mag) {
			int index = -1;
			double d = 10 / mag;
			if (d < 2) d = 2;
			double min_dist = Integer.MAX_VALUE;
			int i = 0;
			final Point ref = new Point(new double[]{x_p, y_p});
			for (final Point p : points) {
				final double dist = Point.distance(ref, p);
				if (dist <= d && dist <= min_dist) {
					min_dist = dist;
					index = i;
				}
				i++;
			}
			return index;
		}

		/** Sets the point at @param index to the new location. */
		synchronized public void set(final int index, final double x_d, final double y_d) {
			if (index < 0 || index >= points.size()) return;
			points.remove(index);
			points.add(index, new Point(new double[]{x_d, y_d}));
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
				final double[] w = p.getW();
				final int x = (int)((w[0] - srcRect.x) * mag);
				final int y = (int)((w[1] - srcRect.y) * mag);
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

	private final void addPoint(final Layer layer, final double x, final double y) {
		Landmarks lm = m.get(layer);
		if (null == lm) {
			lm = new Landmarks(layer);
			m.put(layer, lm);
		}
		lm.add(layer, x, y);
	}

	private Layer current_layer = null;
	private int index = -1;

	@Override
	public void mousePressed(final MouseEvent me, final int x_p, final int y_p, final double magnification) {
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

		display.repaintAll3();
	}

	@Override
	public void mouseDragged(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {
		//Utils.log2("index is " + index);
		if (-1 != index && current_layer == display.getLayer()) {
			final Landmarks lm = m.get(current_layer);
			//Utils.log2("lm is " + lm);
			if (null != lm) {
				lm.set(index, x_d, y_d);
				Display.repaint();
			}
		}
	}

	@Override
	public void mouseReleased(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		// Do nothing
	}

	@Override
    public void undoOneStep() {}
	@Override
    public void redoOneStep() {}

	@Override
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
			final Landmarks lm = e.getValue();
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
			@Override
            public boolean equals(final Object ob) {
				return this == ob;
			}
			@Override
            public int compare(final Layer l1, final Layer l2) {
				// Ascending order
				final double dz = l1.getZ() - l2.getZ();
				if (dz < 0) return -1;
				else if (dz > 0) return 1;
				else return 0;
			}
		});
		sorted.putAll(m);

		int iref = 0;
		for (final Layer la : sorted.keySet()) {
			if (la != ref_layer) iref++;
			else break;
		}

		// Ok, now ask for a model
		final GenericDialog gd = new GenericDialog("Model");
		gd.addChoice("Model:", Align.Param.modelStrings, Align.Param.modelStrings[1]);
		gd.addCheckbox("Propagate to first layer", 0 != iref);
		((Component)gd.getCheckboxes().get(0)).setEnabled(0 != iref);
		gd.addCheckbox("Propagate to last layer", sorted.size()-1 != iref);
		((Component)gd.getCheckboxes().get(1)).setEnabled(sorted.size()-1 != iref);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		final int model_index = gd.getNextChoiceIndex();
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
			Utils.showMessage("Need at least " + min + " landmarks for a " + Align.Param.modelStrings[model_index] + " model");
			return false;
		}

		Bureaucrat.createAndStart(new Worker.Task("Aligning layers with landmarks") {
			@Override
            public void exec() {

		// Find layers with landmarks, in increasing Z.
		// Match in pairs.

		// So, get two submaps: from ref_layer to first, and from ref_layer to last
		final SortedMap<Layer,Landmarks> first_chunk_ = new TreeMap<Layer,Landmarks>(sorted.headMap(ref_layer)); // strictly lower Z than ref_layer
		first_chunk_.put(ref_layer, m.get(ref_layer)); // .. so add ref_layer

		final SortedMap<Layer,Landmarks> second_chunk = sorted.tailMap(ref_layer); // equal or larger Z than ref_layer

		final SortedMap<Layer,Landmarks> first_chunk;

		// Reverse order of first_chunk
		if (first_chunk_.size() > 1) {
			final SortedMap<Layer,Landmarks> fc = new TreeMap<Layer,Landmarks>(new Comparator<Layer>() {
				@Override
                public boolean equals(final Object ob) {
					return this == ob;
				}
				@Override
                public int compare(final Layer l1, final Layer l2) {
					// Descending order
					final double dz = l2.getZ() - l1.getZ();
					if (dz < 0) return -1;
					else if (dz > 0) return 1;
					else return 0;
				}
			});
			fc.putAll(first_chunk_);
			first_chunk = fc;
		} else {
			first_chunk = first_chunk_;
		}

		final LayerSet ls = ref_layer.getParent();

		final Collection<Layer> affected_layers = new HashSet<Layer>(m.keySet());

		// Gather all Patch instances that will be affected
		final ArrayList<Patch> patches = new ArrayList<Patch>();
		for (final Layer la : m.keySet()) patches.addAll(la.getAll(Patch.class));
		if (propagate_to_first && first_chunk.size() > 1) {
			final Collection<Layer> affected = ls.getLayers().subList(0, ls.indexOf(first_chunk.lastKey()));
			for (final Layer la : affected) {
				patches.addAll(la.getAll(Patch.class));
			}
			affected_layers.addAll(affected);
		}
		if (propagate_to_last && second_chunk.size() > 1) {
			final Collection<Layer> affected = ls.getLayers().subList(ls.indexOf(second_chunk.lastKey()) + 1, ls.size());
			for (final Layer la : affected) {
				patches.addAll(la.getAll(Patch.class));
			}
		}


		// Transform segmentations along with patches
		AlignTask.transformPatchesAndVectorData(patches, new Runnable() { @Override
        public void run() {


		// Apply!


		// TODO: when adding non-linear transforms, use this single line for undo instead of all below:
		// (these transforms may be non-linear as well, which alter mipmaps.)
		//ls.addTransformStepWithData(affected_layers);


		// Setup undo:
		// Find all images in the range of affected layers,
		// plus all Displayable of those layers (but Patch instances in a separate DoTransforms step,
		//   to avoid adding a "data" undo for them, which would recreate mipmaps when undone).
		// plus all ZDisplayable that paint in those layers
		final HashSet<Displayable> ds = new HashSet<Displayable>();
		final ArrayList<Displayable> patches = new ArrayList<Displayable>();
		for (final Layer layer : affected_layers) {
			for (final Displayable d : layer.getDisplayables()) {
				if (d.getClass() == Patch.class) {
					patches.add(d);
				} else {
					ds.add(d);
				}
			}
		}
		for (final ZDisplayable zd : ls.getZDisplayables()) {
			for (final Layer layer : affected_layers) {
				if (zd.paintsAt(layer)) {
					ds.add((Displayable)zd);
					break;
				}
			}
		}
		if (ds.size() > 0) {
			final Displayable.DoEdits step = ls.addTransformStepWithData(ds);
			if (patches.size() > 0) {
				final ArrayList<DoStep> a = new ArrayList<DoStep>();
				a.add(new Displayable.DoTransforms().addAll(patches));
				step.addDependents(a);
			}
		}

		if (first_chunk.size() > 1) {
			final AffineTransform aff = align(first_chunk, model);
			if (propagate_to_first) {
				for (final Layer la : ls.getLayers().subList(0, ls.indexOf(first_chunk.lastKey()))) { // exclusive last
					la.apply(Patch.class, aff);
				}
			}
		}
		if (second_chunk.size() > 1) {
			final AffineTransform aff = align(second_chunk, model);
			if (propagate_to_last) {
				for (final Layer la : ls.getLayers().subList(ls.indexOf(second_chunk.lastKey()) + 1, ls.size())) { // exclusive last
					la.apply(Patch.class, aff);
				}
			}
		}

		Display.repaint();

		// Store current state
		if (ds.size() > 0) {
			final Displayable.DoEdits step2 = ls.addTransformStepWithData(ds);
			if (patches.size() > 0) {
				final ArrayList<DoStep> a2 = new ArrayList<DoStep>();
				a2.add(new Displayable.DoTransforms().addAll(patches));
				step2.addDependents(a2);
			}
		}

		}});


		}}, display.getProject());

		return true;
	}

	private AffineTransform align(final SortedMap<Layer,Landmarks> sm, final AbstractAffineModel2D< ? > model) {
		Layer layer1 = sm.firstKey();
		Landmarks lm1 = sm.get(sm.firstKey());

		final AffineTransform accum = new AffineTransform();

		for (final Map.Entry<Layer,Landmarks> e : sm.entrySet()) {
			final Layer layer2 = e.getKey();
			if (layer1 == layer2) continue;
			final Landmarks lm2 = e.getValue();
			// Create pointmatches
			final ArrayList<PointMatch> matches = new ArrayList<PointMatch>();
			for (int i=0; i<lm1.points.size(); i++) {
				matches.add(new PointMatch(lm2.points.get(i), lm1.points.get(i)));
			}

			final AbstractAffineModel2D< ? > mod = model.copy();
			try {
				mod.fit(matches);
			} catch (final Throwable t) {
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


	@Override
    public boolean cancel() {
		return true;
	}

	@Override
    public Rectangle getRepaintBounds() {
		return (Rectangle) display.getCanvas().getSrcRect().clone();
	}

	@Override
    public void srcRectUpdated(final Rectangle srcRect, final double magnification) {}
	@Override
    public void magnificationUpdated(final Rectangle srcRect, final double magnification) {}

	/** Export landmarks into XML file, in patch coordinates. */
	public boolean exportLandmarks() {
		if (m.isEmpty()) {
			Utils.log("No landmarks to export!");
			return false;
		}
		final StringBuilder sb = new StringBuilder("<landmarks>\n");
		for (final Map.Entry<Layer,Landmarks> e : new TreeMap<Layer,Landmarks>(m).entrySet()) { // sorted by Layer z
			sb.append(" <layer id=\"").append(e.getKey().getId()).append("\">\n");
			for (final Point p : e.getValue().points) {
				final double[] w = p.getW();
				double x = w[0],
				       y = w[1];
				// Find the point in a patch, and inverseTransform it into the patch local coords
				final Collection<Displayable> under = e.getKey().find(Patch.class, (int)x, (int)y, true);
				if (!under.isEmpty()) {
					final Patch patch = (Patch)under.iterator().next();
					final Point2D.Double po = patch.inverseTransformPoint(x, y);
					x = po.x;
					y = po.y;
					sb.append("  <point patch_id=\"").append(patch.getId()).append('\"');
				} else {
					// Store the point as absolute
					sb.append("  <point ");
				}
				sb.append(" x=\"").append(x).append("\" y=\"").append(y).append("\" />\n");
			}
			sb.append(" </layer>\n");
		}
		sb.append("</landmarks>");
		final File f = Utils.chooseFile(null, "landmarks", ".xml");
		if (null != f && Utils.saveToFile(f, sb.toString())) {
			return true;
		}
		return false;
	}

	/** Import landmarks from XML file. */
	public boolean importLandmarks() {
		if (!this.m.isEmpty() && !Utils.checkYN("Remove current landmarks and import new landmarks from a file?")) return false;

		// copy for restore in case of failure:
		final HashMap<Layer,Landmarks> current = new HashMap<Layer,Landmarks>(this.m);
		InputStream istream = null;
		try {
			final String[] fs = Utils.selectFile("Choose landmarks XML file");
			if (null == fs || null == fs[0] || null == fs[1]) return false;
			final File f = new File(fs[0] + fs[1]);
			if (!f.exists() || !f.canRead()) {
				Utils.log("ERROR: cannot read file at " + f.getAbsolutePath());
				return false;
			}
			// Clear current landmarks and parse from file:
			this.m.clear();
			final SAXParserFactory ft = SAXParserFactory.newInstance();
			ft.setValidating(false);
			final SAXParser parser = ft.newSAXParser();
			istream = Utils.createStream(fs[0] + fs[1]);
			parser.parse(new InputSource(istream), new LandmarksParser());

			// Warn on inconsistencies
			final HashSet<Integer> sizes = new HashSet<Integer>();
			for (final Landmarks lm : this.m.values()) {
				sizes.add(lm.points.size());
			}
			if (sizes.size() > 1) {
				Utils.log("WARNING: different number of landmarks in at least one layer.");
			}
			Display.repaint();

		} catch (final Throwable t) {
			IJError.print(t);
			Utils.log("ERROR: did not import any landmarks.");
			this.m.clear();
			this.m.putAll(current);
			return false;
		} finally {
			try {
				if (null != istream) istream.close();
			} catch (final Exception e) {}
		}
		return true;
	}

	private final class LandmarksParser extends DefaultHandler {
		Layer layer = null;
		@Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes attributes) {
			final HashMap<String,String> a = new HashMap<String,String>();
			for (int i=attributes.getLength() -1; i>-1; i--) {
				a.put(attributes.getQName(i).toLowerCase(), attributes.getValue(i));
			}
			final String tag = qName.toLowerCase();
			if ("point".equals(tag)) {
				if (null == this.layer) return; // ignore!
				final String sid = a.get("patch_id");
				final String sX = a.get("x");
				final String sY = a.get("y");
				if (null == sX || null == sY) {
					Utils.log("ERROR: ignoring point with x, y : " + sX + ", " + sY);
					return;
				}
				if (null == sid) {
					// Assume absolute coords
					addPoint(layer, Double.parseDouble(sX), Double.parseDouble(sY));
				} else {
					// Find the patch
					final Patch p = (Patch)layer.findById(Long.parseLong(sid));
					if (null == p) {
						Utils.log("ERROR: ignoring point for layer " + layer + "\n  Reason: could not find Patch with id " + sid);
						return;
					}
					// ... and add the point transformed to world
					final Point2D.Double po = p.transformPoint(Double.parseDouble(sX), Double.parseDouble(sY));
					addPoint(layer, (float)po.x, (float)po.y);
				}
			} else if ("layer".equals(tag)) {
				final String sid = a.get("id");
				this.layer = null;
				if (null == sid) {
					Utils.log("ERROR: could not parse a layer that lacks an id!");
					return;
				}
				this.layer = display.getLayerSet().getLayer(Long.parseLong(sid));
				if (null == this.layer) {
					Utils.log("ERROR: could not find layer with id " + sid);
					return;
				}
			}
		}
		@Override
        public void endElement(final String namespace_URI, final String local_name, final String qualified_name) {
			if ("layer".equals(qualified_name)) {
				final Landmarks lm = m.get(this.layer);
				Utils.log("Loaded " + lm.points.size() + " landmarks for layer " + (layer.getParent().indexOf(layer) + 1) + ": " + layer);
			}
		}
	}
}
