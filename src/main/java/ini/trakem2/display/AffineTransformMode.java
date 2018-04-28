package ini.trakem2.display;

import ini.trakem2.ControlWindow;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.utils.History;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AffineTransformMode implements Mode {

	private final Display display;
	private final History history;
	private final ATGS atgs = new AffineTransformMode.ATGS();

	public AffineTransformMode(final Display display) {
		this.display = display;
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		// Init:
		resetBox();
		floater.center();
		this.handles = new Handle[]{NW, N, NE, E, SE, S, SW, W, RO, floater};
		accum_affine = new AffineTransform();
		history = new History(); // unlimited steps
		history.add(new TransformationStep(getTransformationsCopy()));
		display.getCanvas().repaint(false);
	}

	/** Returns a hash table with all selected Displayables as keys, and a copy of their affine transform as value. This is useful to easily create undo steps. */
	private HashMap<Displayable,AffineTransform> getTransformationsCopy() {
		final HashMap<Displayable,AffineTransform> ht_copy = new HashMap<Displayable,AffineTransform>();
		for (final Displayable d : display.getSelection().getAffected()) {
			ht_copy.put(d, d.getAffineTransformCopy());
		}
		return ht_copy;
	}

	/** Add an undo step to the internal history. */
	private void addUndoStep() {
		if (mouse_dragged || display.getSelection().isEmpty()) return;
		if (null == history) return;
		if (history.indexAtStart() || (history.indexAtEnd() && -1 != history.index())) {
			history.add(new TransformationStep(getTransformationsCopy()));
		} else {
			// remove history elements from index+1 to end
			history.clip();
		}
	}

	@Override
    synchronized public void undoOneStep() {
		if (null == history) return;
		// store the current state if at end:
		Utils.log2("index at end: " + history.indexAtEnd());

		Map.Entry<Displayable,AffineTransform> Be = ((TransformationStep)history.getCurrent()).ht.entrySet().iterator().next();

		if (history.indexAtEnd()) {
			final HashMap<Displayable,AffineTransform> m = getTransformationsCopy();
			history.append(new TransformationStep(m));
			Be = m.entrySet().iterator().next(); // must set again, for the other one was the last step, not the current state.
		}

		// disable application to other layers (too big a headache)
		accum_affine = null;
		// undo one step
		final TransformationStep step = (TransformationStep)history.undoOneStep();
		if (null == step) return; // no more steps
		LayerSet.applyTransforms(step.ht);
		resetBox();

		// call fixAffinePoints with the diff affine transform, as computed from first selected object

		try {
			// t0     t1
			// CA  =  B
			// C = BA^(-1)
			final AffineTransform A = step.ht.get(Be.getKey()); // the t0
			final AffineTransform C = new AffineTransform(Be.getValue());
			C.concatenate(A.createInverse());
			fixAffinePoints(C);
		} catch (final Exception e) {
			IJError.print(e);
		}
	}

	@Override
    synchronized public void redoOneStep() {
		if (null == history) return;

		final Map.Entry<Displayable,AffineTransform> Ae = ((TransformationStep)history.getCurrent()).ht.entrySet().iterator().next();

		final TransformationStep step = (TransformationStep)history.redoOneStep();
		if (null == step) return; // no more steps
		LayerSet.applyTransforms(step.ht);
		resetBox();

		// call fixAffinePoints with the diff affine transform, as computed from first selected object
		//  t0   t1
		//  A  = CB
		//  AB^(-1) = C
		final AffineTransform B = step.ht.get(Ae.getKey());
		final AffineTransform C = new AffineTransform(Ae.getValue());
		try {
			C.concatenate(B.createInverse());
			fixAffinePoints(C);
		} catch (final Exception e) {
			IJError.print(e);
		}
	}

	@Override
    public boolean isDragging() {
		return dragging;
	}

	private class ATGS implements GraphicsSource {
		@Override
        public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
			return ds;
		}
		/** Paints the transformation handles and a bounding box around all selected. */
		@Override
        public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			final Stroke original_stroke = g.getStroke();
			final AffineTransform original = g.getTransform();
			g.setTransform(new AffineTransform());
			if (!rotating) {
				//Utils.log("box painting: " + box);

				// 30 pixel line, 10 pixel gap, 10 pixel line, 10 pixel gap
				//float mag = (float)magnification;
				final float[] dashPattern = { 30, 10, 10, 10 };
				g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dashPattern, 0));
				g.setColor(Color.yellow);
				// paint box
				//g.drawRect(box.x, box.y, box.width, box.height);
				g.draw(original.createTransformedShape(box));
				g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				// paint handles for scaling (boxes) and rotating (circles), and floater
				for (int i=0; i<handles.length; i++) {
					handles[i].paint(g, srcRect, magnification);
				}
			} else {
				g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				RO.paint(g, srcRect, magnification);
				((RotationHandle)RO).paintMoving(g, srcRect, magnification, display.getCanvas().getCursorLoc());
			}

			if (null != affine_handles) {
				for (final AffinePoint ap : affine_handles) {
					ap.paint(g);
				}
			}

			g.setTransform(original);
			g.setStroke(original_stroke);
		}
	}

	@Override
    public GraphicsSource getGraphicsSource() {
		return atgs;
	}

	@Override
    public boolean canChangeLayer() { return false; }
	@Override
    public boolean canZoom() { return true; }
	@Override
    public boolean canPan() { return true; }




	/* From former Selection class: the affine transformation GUI */

	private final int iNW = 0;
	private final int iN = 1;
	private final int iNE = 2;
	private final int iE = 3;
	private final int iSE = 4;
	private final int iS = 5;
	private final int iSW = 6;
	private final int iW = 7;
	private final int ROTATION = 12;
	private final int FLOATER = 13;
	private final Handle NW = new BoxHandle(0,0, iNW);
	private final Handle N  = new BoxHandle(0,0, iN);
	private final Handle NE  = new BoxHandle(0,0, iNE);
	private final Handle E  = new BoxHandle(0,0, iE);
	private final Handle SE  = new BoxHandle(0,0, iSE);
	private final Handle S  = new BoxHandle(0,0, iS);
	private final Handle SW  = new BoxHandle(0,0, iSW);
	private final Handle W  = new BoxHandle(0,0, iW);
	private final Handle RO = new RotationHandle(0,0, ROTATION);
	/** Pivot of rotation. Always checked first on mouse pressed, before other handles. */
	private final Floater floater = new Floater(0, 0, FLOATER);
	private final Handle[] handles;
	private Handle grabbed = null;
	private boolean dragging = false; // means: dragging the whole transformation box
	private boolean rotating = false;
	private boolean mouse_dragged = false;
	private Rectangle box;

	private int x_d_old, y_d_old, x_d, y_d;

	/** Handles have screen coordinates. */
	private abstract class Handle {
		public int x, y;
		public final int id;
		Handle(final int x, final int y, final int id) {
			this.x = x;
			this.y = y;
			this.id = id;
		}
		abstract public void paint(Graphics2D g, Rectangle srcRect, double mag);
		/** Radius is the dectection "radius" around the handle x,y. */
		public boolean contains(final int x_p, final int y_p, final double radius) {
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		public void set(final int x, final int y) {
			this.x = x;
			this.y = y;
		}
		abstract void drag(MouseEvent me, int dx, int dy);
	}

	private class BoxHandle extends Handle {
		BoxHandle(final int x, final int y, final int id) {
			super(x,y,id);
		}
		@Override
        public void paint(final Graphics2D g, final Rectangle srcRect, final double mag) {
			final int x = (int)((this.x - srcRect.x)*mag);
			final int y = (int)((this.y - srcRect.y)*mag);
			DisplayCanvas.drawHandle(g, x, y, 1.0); // ignoring magnification for the sizes, since Selection is painted differently
		}
		@Override
        public void drag(final MouseEvent me, final int dx, final int dy) {
			final Rectangle box_old = (Rectangle)box.clone();
			//Utils.log2("dx,dy: " + dx + "," + dy + " before mod");
			double res = dx / 2.0;
			res -= Math.floor(res);
			res *= 2;
			int anchor_x = 0,
			    anchor_y = 0;
			switch (this.id) { // java sucks to such an extent, I don't even bother
				case iNW:
					if (x + dx >= E.x) return;
					if (y + dy >= S.y) return;
					box.x += dx;
					box.y += dy;
					box.width -= dx;
					box.height -= dy;
					anchor_x = SE.x;
					anchor_y = SE.y;
					break;
				case iN:
					if (y + dy >= S.y) return;
					box.y += dy;
					box.height -= dy;
					anchor_x = S.x;
					anchor_y = S.y;
					break;
				case iNE:
					if (x + dx <= W.x) return;
					if (y + dy >= S.y) return;
					box.y += dy;
					box.width += dx;
					box.height -= dy;
					anchor_x = SW.x;
					anchor_y = SW.y;
					break;
				case iE:
					if (x + dx <= W.x) return;
					box.width += dx;
					anchor_x = W.x;
					anchor_y = W.y;
					break;
				case iSE:
					if (x + dx <= W.x) return;
					if (y + dy <= N.y) return;
					box.width += dx;
					box.height += dy;
					anchor_x = NW.x;
					anchor_y = NW.y;
					break;
				case iS:
					if (y + dy <= N.y) return;
					box.height += dy;
					anchor_x = N.x;
					anchor_y = N.y;
					break;
				case iSW:
					if (x + dx >= E.x) return;
					if (y + dy <= N.y) return;
					box.x += dx;
					box.width -= dx;
					box.height += dy;
					anchor_x = NE.x;
					anchor_y = NE.y;
					break;
				case iW:
					if (x + dx >= E.x) return;
					box.x += dx;
					box.width -= dx;
					anchor_x = E.x;
					anchor_y = E.y;
					break;
			}
			// proportion:
			final double px = (double)box.width / (double)box_old.width;
			final double py = (double)box.height / (double)box_old.height;
			// displacement: specific of each element of the selection and their links, depending on where they are.

			final AffineTransform at = new AffineTransform();
			at.translate( anchor_x, anchor_y );
			at.scale( px, py );
			at.translate( -anchor_x, -anchor_y );

			addUndoStep();

			if (null != accum_affine) accum_affine.preConcatenate(at);

			Displayable.preConcatenate(at, display.getSelection().getAffected());
			fixAffinePoints(at);

			// finally:
			setHandles(box); // overkill. As Graham said, most newly available chip resources are going to be wasted. They are already.
		}
	}

	private final double rotate(final MouseEvent me) {
		// center of rotation is the floater
		final double cos = Utils.getCos(x_d_old - floater.x, y_d_old - floater.y, x_d - floater.x, y_d - floater.y);
		//double sin = Math.sqrt(1 - cos*cos);
		//double delta = M.getAngle(cos, sin);
		double delta = Math.acos(cos); // same thing as the two lines above
		// need to compute the sign of rotation as well: the cross-product!
		// cross-product:
		// a = (3,0,0) and b = (0,2,0)
		// a x b = (3,0,0) x (0,2,0) = ((0 x 0 - 2 x 0), -(3 x 0 - 0 x 0), (3 x 2 - 0 x 0)) = (0,0,6).

		if (Utils.isControlDown(me)) {
			delta = Math.toDegrees(delta);
			if (me.isShiftDown()) {
				// 1 degree angle increments
				delta = (int)(delta + 0.5);
			} else {
				// 10 degrees angle increments: snap to closest
				delta = (int)((delta + 5.5 * (delta < 0 ? -1 : 1)) / 10) * 10;
			}
			Utils.showStatus("Angle: " + delta + " degrees");
			delta = Math.toRadians(delta);

			// TODO: the angle above is just the last increment on mouse drag, not the total amount of angle accumulated since starting this mousePressed-mouseDragged-mouseReleased cycle, neither the actual angle of the selected elements. So we need to store the accumulated angle and diff from it to do the above roundings.
		}

		if (Double.isNaN(delta)) {
			Utils.log2("Selection rotation handle: ignoring NaN angle");
			return Double.NaN;
		}

		final double zc = (x_d_old - floater.x) * (y_d - floater.y) - (x_d - floater.x) * (y_d_old - floater.y);
		// correction:
		if (zc < 0) {
			delta = -delta;
		}
		rotate(Math.toDegrees(delta), floater.x, floater.y);
		return delta;
	}

	private class RotationHandle extends Handle {
		final int shift = 50;
		RotationHandle(final int x, final int y, final int id) {
			super(x, y, id);
		}
		@Override
        public void paint(final Graphics2D g, final Rectangle srcRect, final double mag) {
			final int x = (int)((this.x - srcRect.x)*mag) + shift;
			final int y = (int)((this.y - srcRect.y)*mag);
			final int fx = (int)((floater.x - srcRect.x)*mag);
			final int fy = (int)((floater.y - srcRect.y)*mag);
			draw(g, fx, fy, x, y);
		}
		private void draw(final Graphics2D g, final int fx, final int fy, final int x, final int y) {
			g.setColor(Color.white);
			g.drawLine(fx, fy, x, y);
			g.fillOval(x -4, y -4, 9, 9);
			g.setColor(Color.black);
			g.drawOval(x -2, y -2, 5, 5);
		}
		public void paintMoving(final Graphics2D g, final Rectangle srcRect, final double mag, final Point mouse) {
			// mouse as xMouse,yMouse from ImageCanvas: world coordinates, not screen!
			final int fx = (int)((floater.x - srcRect.x)*mag);
			final int fy = (int)((floater.y - srcRect.y)*mag);
			// vector
			final double vx = (mouse.x - srcRect.x)*mag - fx;
			final double vy = (mouse.y - srcRect.y)*mag - fy;
			//double len = Math.sqrt(vx*vx + vy*vy);
			//vx = (vx / len) * 50;
			//vy = (vy / len) * 50;
			draw(g, fx, fy, fx + (int)vx, fy + (int)vy);
		}
		@Override
        public boolean contains(final int x_p, final int y_p, final double radius) {
			final double mag = display.getCanvas().getMagnification();
			final double x = this.x + shift / mag;
			final double y = this.y;
			if (x - radius <= x_p && x + radius >= x_p
			 && y - radius <= y_p && y + radius >= y_p) return true;
			return false;
		}
		@Override
        public void drag(final MouseEvent me, final int dx, final int dy) {
			/// Bad design, I know, I'm ignoring the dx,dy
			// how:
			// center is the floater

			rotate(me);
		}
	}

	private class Floater extends Handle {
		Floater(final int x, final int y, final int id) {
			super(x,y, id);
		}
		@Override
        public void paint(final Graphics2D g, final Rectangle srcRect, final double mag) {
			final int x = (int)((this.x - srcRect.x)*mag);
			final int y = (int)((this.y - srcRect.y)*mag);
			final Composite co = g.getComposite();
			g.setXORMode(Color.white);
			g.drawOval(x -10, y -10, 21, 21);
			g.drawRect(x -1, y -15, 3, 31);
			g.drawRect(x -15, y -1, 31, 3);
			g.setComposite(co); // undo XOR paint
		}
		public Rectangle getBoundingBox(final Rectangle b) {
			b.x = this.x - 15;
			b.y = this.y - 15;
			b.width = this.x + 31;
			b.height = this.y + 31;
			return b;
		}
		@Override
        public void drag(final MouseEvent me, final int dx, final int dy) {
			this.x += dx;
			this.y += dy;
			RO.x = this.x;
			RO.y = this.y;
		}
		public void center() {
			this.x = RO.x = box.x + box.width/2;
			this.y = RO.y = box.y + box.height/2;
		}
		@Override
        public boolean contains(final int x_p, final int y_p, final double radius) {
			return super.contains(x_p, y_p, radius*3.5);
		}
	}

	public void centerFloater() {
		floater.center();
	}

	/** No display bounds are checked, the floater can be placed wherever you want. */
	public void setFloater(final int x, final int y) {
		floater.x = x;
		floater.y = y;
	}

	public int getFloaterX() { return floater.x; }
	public int getFloaterY() { return floater.y; }

	private void setHandles(final Rectangle b) {
		final int tx = b.x;
		final int ty = b.y;
		final int tw = b.width;
		final int th = b.height;
		NW.set(tx, ty);
		N.set(tx + tw/2, ty);
		NE.set(tx + tw, ty);
		E.set(tx + tw, ty + th/2);
		SE.set(tx + tw, ty + th);
		S.set(tx + tw/2, ty + th);
		SW.set(tx, ty + th);
		W.set(tx, ty + th/2);
	}

	private AffineTransform accum_affine = null;

	/** Skips current layer, since its done already. */
	synchronized protected void applyAndPropagate(final Set<Layer> sublist) {
		if (null == accum_affine) {
			Utils.log2("Cannot apply to other layers: undo/redo was used.");
			return;
		}
		if (0 == sublist.size()) {
			Utils.logAll("No layers to apply to!");
			return;
		}
		// Check if there are links across affected layers
		if (Displayable.areThereLayerCrossLinks(sublist, false)) {
			if (ControlWindow.isGUIEnabled()) {
				final YesNoDialog yn = ControlWindow.makeYesNoDialog("Warning!", "Some objects are linked!\nThe transformation would alter interrelationships.\nProceed anyway?");
				if ( ! yn.yesPressed()) return;
			} else {
				Utils.log("Can't apply: some images may be linked across layers.\n  Unlink them by removing segmentation objects like arealists, pipes, profiles, etc. that cross these layers.");
				return;
			}
		}
		// Add undo step
		final ArrayList<Displayable> al = new ArrayList<Displayable>();
		for (final Layer l : sublist) {
			al.addAll(l.getDisplayables());
		}
		display.getLayer().getParent().addTransformStep(al);


		// Must capture last step of free affine when using affine points:
		if (null != free_affine && null != model) {
			accum_affine.preConcatenate(free_affine);
			accum_affine.preConcatenate(model.createAffine());
		}

		// Apply!
		for (final Layer l : sublist) {
			if (display.getLayer() == l) continue; // already applied
			l.apply(Displayable.class, accum_affine);
		}

		// Record current state as last step in undo queue
		display.getLayer().getParent().addTransformStep(al);
	}

	@Override
    public boolean apply() {
		// Notify each Displayable that any set of temporary transformations are over.
		// The transform is the same, has not changed. This is just sending an event.
		for (final Displayable d : display.getSelection().getAffected()) {
			d.setAffineTransform( d.getAffineTransform() );
		}
		return true;
	}

	@Override
    public boolean cancel() {
		if (null != history) {
			// apply first
			LayerSet.applyTransforms(((TransformationStep)history.get(0)).ht);
		}
		return true;
	}

	private class AffinePoint {
		int x, y;
		AffinePoint(final int x, final int y) {
			this.x = x;
			this.y = y;
		}
		@Override
        public boolean equals(final Object ob) {
			//if (!ob.getClass().equals(AffinePoint.class)) return false;
			final AffinePoint ap = (AffinePoint) ob;
			final double mag = display.getCanvas().getMagnification();
			final double dx = mag * ( ap.x - this.x );
			final double dy = mag * ( ap.y - this.y );
			final double d =  dx * dx + dy * dy;
			return  d < 64.0;
		}
		void translate(final int dx, final int dy) {
			x += dx;
			y += dy;
		}
		private void paint(final Graphics2D g) {
			final int x = display.getCanvas().screenX(this.x);
			final int y = display.getCanvas().screenY(this.y);
			Utils.drawPoint(g, x, y);
		}
	}

	private ArrayList<AffinePoint> affine_handles = null;
	private ArrayList< mpicbg.models.PointMatch > matches = null;
	private mpicbg.models.Point[] p = null;
	private mpicbg.models.Point[] q = null;
	private mpicbg.models.AbstractAffineModel2D<?> model = null;
	private AffineTransform free_affine = null;
	private HashMap<Displayable,AffineTransform> initial_affines = null;

	/*
	private void forgetAffine() {
		affine_handles = null;
		matches = null;
		p = q = null;
		model = null;
		free_affine = null;
		initial_affines = null;
	}
	*/

	private void initializeModel() {
		// Store current "initial" state in the accumulated affine
		if (null != free_affine && null != model && null != accum_affine) {
			accum_affine.preConcatenate(free_affine);
			accum_affine.preConcatenate(model.createAffine());
		}

		free_affine = new AffineTransform();
		initial_affines = getTransformationsCopy();

		final int size = affine_handles.size();

		switch (size) {
			case 0:
				model = null;
				q = p = null;
				matches = null;
				return;
			case 1:
				model = new mpicbg.models.TranslationModel2D();
				break;
			case 2:
				model = new mpicbg.models.SimilarityModel2D();
				break;
			case 3:
				model = new mpicbg.models.AffineModel2D();
				break;
		}
		p = new mpicbg.models.Point[size];
		q = new mpicbg.models.Point[size];
		matches = new ArrayList< mpicbg.models.PointMatch >();
		int i = 0;
		for (final AffinePoint ap : affine_handles) {
			p[i] = new mpicbg.models.Point(new double[]{ap.x, ap.y});
			q[i] = p[i].clone();
			matches.add(new mpicbg.models.PointMatch(p[i], q[i]));
			i++;
		}
	}

	private void freeAffine(final AffinePoint affp) {
		// The selected point
		final double[] w = q[affine_handles.indexOf(affp)].getW();
		w[0] = affp.x;
		w[1] = affp.y;

		try {
			model.fit(matches);
		} catch (final Exception e) {}

		final AffineTransform model_affine = model.createAffine();
		for (final Map.Entry<Displayable,AffineTransform> e : initial_affines.entrySet()) {
			final AffineTransform at = new AffineTransform(e.getValue());
			at.preConcatenate(free_affine);
			at.preConcatenate(model_affine);
			e.getKey().setAffineTransform(at);
		}
	}

	private void fixAffinePoints(final AffineTransform at) {
		if (null != matches) {
			final float[] po = new float[2];
			for (final AffinePoint affp : affine_handles) {
				po[0] = affp.x;
				po[1] = affp.y;
				at.transform(po, 0, po, 0, 1);
				affp.x = (int)po[0];
				affp.y = (int)po[1];
			}
			// Model will be reinitialized when needed
			free_affine.setToIdentity();
			model = null;
		}
	}

	private AffinePoint affp = null;


	@Override
    public void mousePressed(final MouseEvent me, final int x_p, final int y_p, final double magnification) {
		grabbed = null; // reset
		if (me.isShiftDown()) {
			if (Utils.isControlDown(me) && null != affine_handles) {
				if (affine_handles.remove(new AffinePoint(x_p, y_p))) {
					if (0 == affine_handles.size()) affine_handles = null;
					else initializeModel();
				}
				return;
			}
			if (null == affine_handles) {
				affine_handles = new ArrayList<AffinePoint>();
			}
			if (affine_handles.size() < 3) {
				affine_handles.add(new AffinePoint(x_p, y_p));
				if (1 == affine_handles.size()) {
					free_affine = new AffineTransform();
					initial_affines = getTransformationsCopy();
				}
				initializeModel();
			}
			return;
		} else if (null != affine_handles) {
			final int index = affine_handles.indexOf(new AffinePoint(x_p, y_p));
			if (-1 != index) {
				affp = affine_handles.get(index);
				return;
			}
		}

		// find scale handle
		double radius = 4 / magnification;
		if (radius < 1) radius = 1;
		// start with floater (the last)
		for (int i=handles.length -1; i>-1; i--) {
			if (handles[i].contains(x_p, y_p, radius)) {
				grabbed = handles[i];
				if (grabbed.id > iW && grabbed.id <= ROTATION) rotating = true;
				return;
			}
		}

		// if none grabbed, then drag the whole thing
		dragging = false; //reset
		if (box.x <= x_p && box.y <= y_p && box.x + box.width >= x_p && box.y + box.height >= y_p) {
			dragging = true;
		}
	}
	@Override
    public void mouseDragged(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {
		// Store old for rotation handle:
		this.x_d = x_d;
		this.y_d = y_d;
		this.x_d_old = x_d_old;
		this.y_d_old = y_d_old;

		// compute translation
		final int dx = x_d - x_d_old;
		final int dy = y_d - y_d_old;

		execDrag(me, dx, dy);
		display.getCanvas().repaint(true);

		mouse_dragged = true; // after execDrag, so the first undo step is added.
	}
	private void execDrag(final MouseEvent me, final int dx, final int dy) {
		if (0 == dx && 0 == dy) return;
		if (null != affp) {
			affp.translate(dx, dy);
			if (null == model) {
				// Model has been canceled by a transformation from the other handles
				initializeModel();
			}
			// Passing on the translation from start
			freeAffine(affp);
			return;
		}

		if (null != grabbed) {
			// drag the handle and perform whatever task it has assigned
			grabbed.drag(me, dx, dy);
		} else if (dragging) {
			// drag all selected and linked
			translate(dx, dy);
			//and the box!
			box.x += dx;
			box.y += dy;
			// and the handles!
			setHandles(box);
		}
	}

	@Override
    public void mouseReleased(final MouseEvent me, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {

		// Record current state for selected Displayable set, if there was any change:
		final int dx = x_r - x_p;
		final int dy = y_r - y_p;
		if (0 != dx || 0 != dy) {
			display.getLayerSet().addTransformStep(display.getSelection().getAffected()); // all selected and their links: i.e. all that will change
		}

		// me is null when calling from Display, because of popup interfering with mouseReleased
		if (null != me) {
			execDrag(me, x_r - x_d, y_r - y_d);
			display.getCanvas().repaint(true);
		}

		// recalculate box
		resetBox();

		//reset
		if ((null != grabbed && grabbed.id <= iW) || dragging) {
			floater.center();
		}

		grabbed = null;
		dragging = false;
		rotating = false;
		affp = null;
		mouse_dragged = false;
	}

	/** Recalculate box and reset handles. */
	public void resetBox() {
		box = null;
		Rectangle b = new Rectangle();
		for (final Displayable d : display.getSelection().getSelected()) {
			b = d.getBoundingBox(b);
			if (null == box) box = (Rectangle)b.clone();
			box.add(b);
		}
		if (null != box) setHandles(box);
	}

	/** Rotate the objects in the current selection by the given angle, in degrees, relative to the x_o, y_o origin. */
	public void rotate(final double angle, final int xo, final int yo) {
		final AffineTransform at = new AffineTransform();
		at.rotate(Math.toRadians(angle), xo, yo);

		addUndoStep();

		if (null != accum_affine) accum_affine.preConcatenate(at);

		Displayable.preConcatenate(at, display.getSelection().getAffected());
		fixAffinePoints(at);
		resetBox();
	}

	/** Translate all selected objects and their links by the given differentials. The floater position is unaffected; if you want to update it call centerFloater() */
	public void translate(final double dx, final double dy) {
		final AffineTransform at = new AffineTransform();
		at.translate(dx, dy);

		addUndoStep();

		if (null != accum_affine) accum_affine.preConcatenate(at);

		Displayable.preConcatenate(at, display.getSelection().getAffected());
		fixAffinePoints(at);
		resetBox();
	}

	/** Scale all selected objects and their links by by the given scales, relative to the floater position. . */
	public void scale(final double sx, final double sy) {
		if (0 == sx || 0 == sy) {
			Utils.showMessage("Cannot scale to 0.");
			return;
		}

		final AffineTransform at = new AffineTransform();
		at.translate(floater.x, floater.y);
		at.scale(sx, sy);
		at.translate(-floater.x, -floater.y);

		addUndoStep();

		if (null != accum_affine) accum_affine.preConcatenate(at);

		Displayable.preConcatenate(at, display.getSelection().getAffected());
		fixAffinePoints(at);
		resetBox();
	}

	@Override
    public Rectangle getRepaintBounds() {
		final Rectangle b = display.getSelection().getLinkedBox();
		b.add(floater.getBoundingBox(new Rectangle()));
		return b;
	}

	@Override
    public void srcRectUpdated(final Rectangle srcRect, final double magnification) {}
	@Override
    public void magnificationUpdated(final Rectangle srcRect, final double magnification) {}
}
