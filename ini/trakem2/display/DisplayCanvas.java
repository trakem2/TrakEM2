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

import ij.*;
import ij.gui.*;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.*;
import ini.trakem2.imaging.*;

import java.awt.event.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.BufferStrategy;
import java.util.*;
import java.awt.Cursor;


public class DisplayCanvas extends ImageCanvas implements KeyListener/*, FocusListener*/, MouseWheelListener {

	private Display display;

	private boolean update_graphics = false;
	private Image offscreen, offscreen_a, offscreen_b;
	private Rectangle clipRect_a, clipRect_b;
	private ArrayList al_top = new ArrayList();

	public final class Lock {
		boolean locked = false;
		public final void lock() {
			while (locked) try { this.wait(); } catch (InterruptedException ie) {}
			locked = true;
		}
		public final void unlock() {
			locked = false;
			this.notifyAll();
		}
	}

	private final Lock lock_a = new Lock();
	private final Lock lock_b = new Lock();

	private Rectangle box = null; // the bounding box of the active

	private FakeImageWindow fake_win;

	private FreeHandProfile freehandProfile = null;
	private Robot r;// used for setting the mouse pointer

	private RepaintThread rt_old = null;
	/** Any painting operation should wait on this object, set the controling flag to true, and when done set controling to false and controler.notifyAll(). */
	private final Object controler_ob = new Object();
	private boolean controling = false;

	private final Object offscreen_lock = new Object();
	private boolean offscreen_locked = false;
	private boolean cancel_painting = false;

	private Cursor noCursor;

	private boolean snapping = false;
	private boolean dragging = false;
	private boolean input_disabled = false;
	private boolean input_disabled2 = false;

	public DisplayCanvas(Display display, int width, int height) {
		super(new FakeImagePlus(width, height, display));
		fake_win = new FakeImageWindow(imp, this, display);
		this.display = display;
		this.imageWidth = width;
		this.imageHeight = height;
		removeKeyListener(IJ.getInstance());
		addKeyListener(this);
		addMouseWheelListener(this);
	}

	/** Used to constrain magnification so that only snapshots are used for painting when opening a new, large and filled Display. */
	protected void setInitialMagnification(double mag) { // calling this method 'setMagnification' would conflict with the super class homonimous method.
		this.magnification = mag; // don't save in the database. This value is overriden when reopening from the database by calling the setup method.
	}

	/** Used for restoring properties from the database. */
	public void setup(double mag, Rectangle srcRect) {
		this.magnification = mag;
		this.srcRect = (Rectangle)srcRect.clone(); // just in case
		super.setDrawingSize((int)Math.ceil(srcRect.width * mag), (int)Math.ceil(srcRect.height * mag));
		setMagnification(mag);
		display.pack();
	}

	/** Does not repaint. */
	public void setDimensions(double width, double height) {
		this.imageWidth = (int)Math.ceil(width);
		this.imageHeight = (int)Math.ceil(height);
		((FakeImagePlus)imp).setDimensions(imageWidth, imageHeight);
		zoomToFit();
	}

	/** Overriding to disable it. */
	public void handlePopupMenu() {}

	public void update(Graphics g) {
		this.paint(g);
	}

	/** Master over the rt_old */
	private final Object controler_ob2 = new Object();
	private boolean controling2 = false;

	//private int count = 0; // threads' tag

	/** A thread to manage the onset of creation of offscreen images, and when done it calls the proper repaint */
	private class RepaintThread extends Thread {

		private Rectangle clipRect;
		private DisplayCanvas dc;
		private boolean quit = false;
		private boolean done = false;
		private boolean stop_offscreen_data = false;
		private boolean create_offscreen_data;
		private final long start = System.currentTimeMillis();

		//private int label;

		private OffscreenThread offscreen_thread = null;

		RepaintThread(DisplayCanvas dc, Rectangle clipRect, boolean create_offscreen_data) {
			setPriority(Thread.NORM_PRIORITY);
			this.create_offscreen_data = create_offscreen_data;
			//label = count++;
			//Utils.log2(label + " new rt");
			this.clipRect = clipRect;
			this.dc =  dc;
			synchronized (controler_ob2) {
				while (controling2) { try { controler_ob2.wait(); } catch (InterruptedException ie) {} }
				controling2 = true;

				// merge clip with previous thread if it wasn't finished
				if (null != rt_old) { // only if unfinished
					// in any case, cancel previous thread
					if (create_offscreen_data) {
						// cancel previous creation of offscreen data (if any)
						if (null != rt_old.offscreen_thread) {
							rt_old.stop_offscreen_data = true;
							rt_old.offscreen_thread.cancel();
							try { rt_old.offscreen_thread.join(); } catch (InterruptedException ie) {}
							rt_old.offscreen_thread = null;
						}
					}
					if (!rt_old.done) {
						rt_old.quit();
						// merge clips
						if (null != this.clipRect) {
							if (null != rt_old.clipRect) this.clipRect.add(rt_old.clipRect);
							else this.clipRect = null; // null means 'all'
						}
					}
				}
				// register (this is why I synch with controler_ob2)
				rt_old = this;

				//Utils.log2("RepaintThread clip: " + this.clipRect);

				controling2 = false;
				controler_ob2.notifyAll();
			}
			start();
		}

		public void quit() {
			//Utils.log2(label + "quit rt");
			this.quit = true;
			this.stop_offscreen_data = true;
			if (null != this.offscreen_thread) this.offscreen_thread.cancel();
		}

		public void run() {
			if (quit) return;
			try {
				Layer layer = display.getLayer();
				if (null == layer) return; // fixing startup problems (stupid java, calling repaint() on an object before it has been initialized in full!)

				final int g_width = getWidth(); // from the awt.Component (the awt.Canvas, i.e. the drawing area dimensions). Isn't this dstWidth and dstHeight in ImageCanvas ?
				final int g_height = getHeight();
				Displayable active = display.getActive();
				int c_alphas = display.getDisplayChannelAlphas();

				if (create_offscreen_data || null == offscreen) {

					if (quit && start - System.currentTimeMillis() > 100) return;
					this.offscreen_thread = new OffscreenThread(clipRect, layer, g_width, g_height, active, c_alphas);
					this.offscreen_thread.start();
				}

				// call the paint(Graphics g) ATTENTION this is the only place where any of the repaint methods of the superclass are to be called (which will call the update(Graphics g), which will call the paint method.
				if (null == clipRect) DisplayCanvas.super.repaint(0, 0, 0, g_width, g_height); // using super.repaint() causes infinite thread loops in the IBM-1.4.2-ppc
				else {
					// the clipRect is already in screen coords
					DisplayCanvas.super.repaint(0, clipRect.x, clipRect.y, clipRect.width, clipRect.height);
				}

			} catch (OutOfMemoryError oome) {
				Utils.log2("RepaintThread OutOfMemoryError: " + oome); // so OutOfMemoryError won't generate locks
			} catch (Exception e) {
				Utils.log2("RepaintThread Error: " + e); // so OutOfMemoryError won't generate locks
				new IJError(e);
			}
			done = true;
		}
	}

	private final void setRenderingHints(final Graphics2D g) {
		/* // so slow!! Particularly the first one.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		//g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		//g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
		//g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		//g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		*/
	}

	public void setMagnification(double mag) {
		// ensure a stroke of thickness 1.0 regardless of magnification
		this.stroke = new BasicStroke((float)(1.0/mag), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
		super.setMagnification(mag);
	}

	/** Paint lines always with a thickness of 1 pixel. This stroke is modified when the magnification is changed, to compensate. */
	private BasicStroke stroke = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);

	private final void resetStroke() {
		this.stroke = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	}

	/** The affine transform representing the srcRect displacement and the magnification. */
	private final AffineTransform atc = new AffineTransform();

	public void paint(final Graphics g) {
		try {
			display.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

			// ensure proper positioning
			g.translate(0, 0); // ints!

			final Rectangle clipRect = g.getClipBounds();
			//Utils.log2("clip as offscreen: " + atc.createInverse().createTransformedShape(clipRect).getBounds());

			final Displayable active = display.getActive();
			final int c_alphas = display.getDisplayChannelAlphas();
			final int sr_width = (int) (srcRect.width * magnification) + 1; // to make it a ceil operation
			final int sr_height = (int) (srcRect.height * magnification) + 1;

			final int g_width = getWidth(); // from the awt.Component (the awt.Canvas, i.e. the drawing area dimensions). Isn't this dstWidth and dstHeight in ImageCanvas ?
			final int g_height = getHeight();

			final Roi roi = imp.getRoi();

			final Selection selection = display.getSelection();
			final Layer active_layer = display.getLayer();

			final Graphics2D g2d = (Graphics2D)g;

			Displayable[] di = null;

			if (ProjectToolbar.getToolId() == ProjectToolbar.PEN && (0 != (flags & InputEvent.BUTTON1_MASK)) && (0 == (flags & InputEvent.ALT_MASK)) && null != active && active.getClass().equals(AreaList.class) && ((AreaList)active).isFillPaint()) {
				// no background paint if painting in fill_paint mode and not erasing
			} else {
				synchronized (offscreen_lock) {
					while (offscreen_locked) { try { offscreen_lock.wait(); } catch (InterruptedException ie) {} }
					offscreen_locked = true;

					if (null != offscreen) {
						g.drawImage(offscreen, 0, 0, null);

						//Utils.log2(offscreen == offscreen_a ? "a" : "b");
					}

					// prepare the canvas for the srcRect and magnification
					final AffineTransform at_original = g2d.getTransform();
					atc.setToIdentity();
					atc.scale(magnification, magnification);
					atc.translate(-srcRect.x, -srcRect.y);
					at_original.preConcatenate(atc);
					g2d.setTransform(at_original);

					di = new Displayable[al_top.size()];
					al_top.toArray(di);

					offscreen_locked = false;
					offscreen_lock.notifyAll();
				}
			}

			if (null != active && !active.getClass().equals(Patch.class)) {
				active.paint(g2d, magnification, true, c_alphas, active_layer);
			}

			if (null != di) {
				for (int i=0; i<di.length; i++) {
					if (cancel_painting) {
						display.setCursor(Cursor.getDefaultCursor());
						return;
					}
					di[i].paint(g2d, magnification, false, c_alphas, active_layer);
				}
			}

			// always a stroke of 1.0, regardless of magnification
			g2d.setStroke(this.stroke);

			if (cancel_painting) {
				display.setCursor(Cursor.getDefaultCursor());
				return;
			}

			// paint a pink frame around selected objects, and a white frame around the active object
			if (null != selection && ProjectToolbar.getToolId() < ProjectToolbar.PENCIL) { // i.e. PENCIL, PEN and ALIGN
				selection.paint(g, srcRect, magnification);
			}

			g2d.setTransform(new AffineTransform()); // reset to identity

			final Align align = null != active_layer ? active_layer.getParent().getAlign() : null;
			if (null != align) {
				align.paint(active_layer, g, srcRect, magnification);
			}

			// paint brush outline for AreaList
			if (mouse_in && null != active && ProjectToolbar.getToolId() == ProjectToolbar.PEN && active.getClass().equals(AreaList.class)) {
				// reset stroke, always thickness of 1
				g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				int brushSize = ProjectToolbar.getBrushSize();
				g.setColor(active.getColor());
				g.drawOval((int)((xMouse -srcRect.x -brushSize/2)*magnification), (int)((yMouse - srcRect.y -brushSize/2)*magnification), (int)(brushSize * magnification), (int)(brushSize * magnification));
			}

			if (null != roi) {
				// reset stroke, always thickness of 1
				g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
				roi.draw(g);
			}

			// restore cursor
			display.setCursor(Cursor.getDefaultCursor());

			// clean up
			if (Thread.currentThread().equals(rt_old)) {
				synchronized (controler_ob2) {
					while (controling2) { try { controler_ob2.wait(); } catch (InterruptedException ie) {} }
					controling2 = true;
					rt_old = null;
					controling2 = false;
					controler_ob2.notifyAll();
				}
			}

			// Mathias code:
			if (null != freehandProfile) {
				freehandProfile.paint(g, magnification, srcRect, true);
				if(noCursor == null)
					noCursor = Toolkit.getDefaultToolkit().createCustomCursor(new BufferedImage(1,1,BufferedImage.TYPE_BYTE_BINARY), new Point(0,0), "noCursor");
			}

		} catch (Exception e) {
			Utils.log2("DisplayCanvas.paint(Graphics) Error: " + e);
			new IJError(e);
		} finally {
			// restore cursor
			if (null == freehandProfile) {
				setCursor(Cursor.getDefaultCursor());
			} else {
				setCursor(noCursor);
			}
		}
	}

	public void waitForRepaint() {
		Thread t = null;
		synchronized (controler_ob2) {
			while (controling2) { try { controler_ob2.wait(); } catch (InterruptedException ie) {} }
			controling2 = true;
			t = rt_old;
			controling2 = false;
			controler_ob2.notifyAll();
		}
		if (null != t) try {
			t.join();
		} catch (InterruptedException ie) {}
	}

	/** Paints a handle on the screen coords. Adapted from ij.gui.Roi class. */
	static public void drawHandle(final Graphics g, final int x, final int y, final double magnification) {
		final int width5 = (int)Math.round(5 / magnification);
		final int width3 = (int)Math.round(3 / magnification);
		final int corr2 = (int)Math.round(2 / magnification);
		final int corr1 = (int)Math.ceil(1 / magnification);
		g.setColor(Color.white);
		g.drawRect(x - corr2, y - corr2, width5, width5);
		g.setColor(Color.black);
		g.drawRect(x - corr1, y - corr1, width3, width3);
		g.setColor(Color.white);
		g.fillRect(x, y, corr1, corr1);
	}

	/** Paints a handle on the offscreen x,y. Adapted from ij.gui.Roi class. */
	private void drawHandle(Graphics g, double x, double y) {
		g.setColor(Color.black);
		g.fillRect((int) ((x - srcRect.x) * magnification) - 1, (int) ((y - srcRect.y) * magnification) - 1, 3, 3);
		g.setColor(Color.white);
		g.drawRect((int) ((x - srcRect.x) * magnification) - 2, (int) ((y - srcRect.y) * magnification) - 2, 5, 5);
	}

	protected void setDrawingColor(int ox, int oy, boolean setBackground) {
		super.setDrawingColor(ox, oy, setBackground);
	}

	/** As offscreen. */
	private int x_p, y_p, x_d, y_d, x_d_old, y_d_old;

	private boolean popup = false;

	private boolean locked = false; // TODO temporary!

	private int tmp_tool = -1;

	public void mousePressed(MouseEvent me) {

		this.flags = me.getModifiers();

		x_p = x_d = srcRect.x + (int) (me.getX() / magnification); // offScreenX(me.getX());
		y_p = y_d = srcRect.y + (int) (me.getY() / magnification); // offScreenY(me.getY());

		this.xMouse = x_p;
		this.yMouse = y_p;

		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		// Popup:
		popup = false; // not reset properly in macosx
		if ((me.isPopupTrigger() || me.isControlDown() || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) && 1 == me.getClickCount() && !me.isShiftDown() && !me.isAltDown()) {
			popup = true;
			display.getPopupMenu().show(this, me.getX(), me.getY());
			return;
		}

		// reset
		snapping = false;

		int tool = ProjectToolbar.getToolId();

		// pan with middle mouse like in inkscape
		/* // works, but can't use it: the alt button then is useless (so no erasing with areas, and so on)
		if (0 != (flags & InputEvent.BUTTON2_MASK)) {
		*/
		if (me.getButton() == MouseEvent.BUTTON2) {
			tmp_tool = tool;
			ProjectToolbar.setTool(Toolbar.HAND);
			tool = Toolbar.HAND;
		}
		//Utils.log2("button: " + me.getButton() + "  BUTTON2: " + MouseEvent.BUTTON2);

		switch (tool) {
		case Toolbar.MAGNIFIER:
			if (me.isAltDown()) zoomOut(me.getX(), me.getY());
			else zoomIn(me.getX(), me.getY());
			return;
		case Toolbar.HAND:
			super.setupScroll(x_p, y_p); // offscreen coords.
			//display.repaintAll();
			return;
		}

		if (input_disabled) {
			input_disabled2 = true;
			Utils.showMessage("Please wait while completing the task.\nOnly the glass and hand tool are enabled.");
			return; // only zoom and pan are allowed
		}

		Displayable active = display.getActive();


		switch (tool) {
		case Toolbar.WAND:
			if (null != active && active instanceof Patch) {
				me.translatePoint(-(int) active.getX(), -(int) active.getY());
				super.mousePressed(me);
				repaint();
			}
			return;
		case ProjectToolbar.PENCIL:
			if (active.isVisible() && active instanceof Profile) {
				Profile prof = (Profile) active;
				this.freehandProfile = new FreeHandProfile(prof);
				freehandProfile.mousePressed(x_p, y_p, srcRect, magnification);
			} else {
				Utils.showMessage("Select a profile first.");
			}
			return;
		case Toolbar.RECTANGLE:
		case Toolbar.OVAL:
		case Toolbar.POLYGON:
		case Toolbar.FREEROI:
		case Toolbar.LINE:
		case Toolbar.POLYLINE:
		case Toolbar.FREELINE:
		case Toolbar.ANGLE:
		case Toolbar.POINT:
			// pass the mouse event to superclass ImageCanvas.
			super.mousePressed(me);
			repaint();
			return;
		case Toolbar.DROPPER:
			// The color dropper
			setDrawingColor(x_p, y_p, me.isAltDown());
			return;
		}

		// check:
		if (display.isReadOnly()) return;

		switch (tool) {
		case Toolbar.TEXT:
			// edit a label, or add a new one
			if (null == active || !active.contains(x_p, y_p)) {
				// find a Displayable to activate, if any
				display.choose(me.getX(), me.getY(), x_p, y_p, DLabel.class);
				active = display.getActive();
			}
			if (null != active && active.isVisible() && active instanceof DLabel) {
				// edit
				((DLabel) active).edit();
			} else {
				// new
				DLabel label = new DLabel(display.getProject(), "  ", x_p, y_p);
				label.edit();
				display.getLayer().add(label);
			}
			return;
		}

		// SPECIFIC for SELECT and above tools

		// no ROIs allowed past this point
		if (tool >= ProjectToolbar.SELECT) imp.killRoi();
		else return;

		Selection selection = display.getSelection();
		if (selection.isTransforming()) {
			box = selection.getLinkedBox();
			selection.mousePressed(x_p, y_p, magnification);
			return;
		}
		// select or deselect another active Displayable, or add it to the selection group:
		if (ProjectToolbar.SELECT == tool) {
			display.choose(me.getX(), me.getY(), x_p, y_p, me.isShiftDown(), null);
		}
		active = display.getActive();
		selection = display.getSelection();

		// store for undo:
		if (!selection.isEmpty() && ProjectToolbar.SELECT == tool && null == display.getLayer().getParent().getAlign() && !selection.isTransforming()) {
			display.getLayer().getParent().addUndoStep(selection.getTransformationsCopy());
		}

		if (ProjectToolbar.ALIGN == tool) {
			LayerSet set = display.getLayer().getParent();
			if (!set.isAligning()) {
				set.startAlign(display);
			}
			set.getAlign().mousePressed(display.getLayer(), me, x_p, y_p);
			return;
		}

		if (null == active || !active.isVisible()) return;

		switch (tool) {
		case ProjectToolbar.SELECT:
			// check if the active is usable:
			// check if the selection contains locked objects
			if (selection.isLocked()) {
				locked = true;
				return;
			}
			if (selection.isEmpty()) {
				locked = true;
				return;
			}
			// gather initial box (for repainting purposes)
			box = selection.getLinkedBox();
			selection.mousePressed(x_p, y_p, magnification);
			break;
		default: // the PEN and PENCIL tools, and any other custom tool
			box = active.getBoundingBox();
			active.mousePressed(me, x_p, y_p, srcRect, magnification);
			break;
		}
		//Utils.log("locked: " + locked + " popup: " + popup + " input_disabled2: " + input_disabled2);
	}

	public void mouseDragged(MouseEvent me) {
		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		if (popup || locked || null != display.getLayer().getParent().getAlign()) return;

		dragging = true;

		this.flags = me.getModifiers();

		x_d_old = x_d;
		y_d_old = y_d;

		x_d = srcRect.x + (int) (me.getX() / magnification); // offscreen
		y_d = srcRect.y + (int) (me.getY() / magnification);

		this.xMouse = x_d;
		this.yMouse = y_d;

		// protection:
		int me_x = me.getX();
		int me_y = me.getY();
		if (me_x < 0 || me_x > this.getWidth() || me_y < 0 || me_y > this.getHeight()) {
			x_d = x_d_old;
			y_d = y_d_old;
			return;
		}

		int tool = ProjectToolbar.getToolId();


		// pan with middle mouse like in inkscape
		/* // works, but can't use it: the alt button then is useless (so no erasing with areas, and so on)
		if (0 != (flags & InputEvent.BUTTON2_MASK)) {
			tool = Toolbar.HAND;
		}
		*/ // so the above has been implemented as a temporary switch to the HAND tool at the mousePressed function.

		switch (tool) {
		case Toolbar.MAGNIFIER: // TODO : create a zooms-area tool
			return;
		case Toolbar.HAND:
			update_graphics = true; // update the offscreen images.
			scroll(me.getX(), me.getY());
			display.getNavigator().repaint(false);
			repaint(true);
			return;
		}

		if (input_disabled2) return;


		//debug:
		//Utils.log2("x_d,y_d : " + x_d + "," + y_d + "   x_d_old, y_d_old : " + x_d_old + "," + y_d_old + "  dx, dy : " + (x_d_old - x_d) + "," + (y_d_old - y_d));

		// Code for Matthias' FreehandProfile (TODO this should be done on mousePressed, not on mouseDragged)
		try {
			if (r == null) {
				r = new Robot(this.getGraphicsConfiguration().getDevice());
			}
		} catch (AWTException e) {
			e.printStackTrace();
		}

		switch (tool) {
		case ProjectToolbar.PENCIL:
			if (freehandProfile == null)
				return; // starting painting out of the DisplayCanvas border
			double dx = x_d - x_d_old;
			double dy = y_d - y_d_old;
			freehandProfile.mouseDragged(me, x_d, y_d, dx, dy, srcRect, magnification);
			repaint();
			// Point screenLocation = getLocationOnScreen();
			// mousePos[0] += screenLocation.x;
			// mousePos[1] += screenLocation.y;
			// r.mouseMove( mousePos[0], mousePos[1]);
			return;
		case Toolbar.RECTANGLE:
		case Toolbar.OVAL:
		case Toolbar.POLYGON:
		case Toolbar.FREEROI:
		case Toolbar.LINE:
		case Toolbar.POLYLINE:
		case Toolbar.FREELINE:
		case Toolbar.ANGLE:
		case Toolbar.POINT:
			// pass the mouse event to superclass ImageCanvas.
			super.mouseDragged(me);
			repaint(false);
			return;
		}
		// no ROIs beyond this point
		if (tool >= ProjectToolbar.SELECT) imp.killRoi();
		else return;

		// check:
		if (display.isReadOnly()) return;


		Displayable active = display.getActive();
		Selection selection = display.getSelection();

		if (null != active && active.isVisible()) {
			// prevent dragging beyond the layer limits
			if (display.getLayer().contains(x_d, y_d, 1)) {
				Rectangle box2;
				switch (tool) {
				case ProjectToolbar.SELECT:
					selection.mouseDragged(x_p, y_p, x_d, y_d, x_d_old, y_d_old);
					box2 = selection.getLinkedBox();
					box.add(box2);
					// repaint all Displays (where it was and where it is now, hence the sum of both boxes):
					Display.repaint(display.getLayer(), Selection.PADDING, box, false);
					// box for next mouse dragged iteration
					box = box2;
					break;
				case ProjectToolbar.ALIGN:
					break; // nothing
				default:
					active.mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old, srcRect, magnification);
					// the line above must repaint on its own
					break;
				}
			} else { 
				locked = true; // TODO temporary until the snapTo and mouseEntered issues are fixed
				Utils.log("DisplayCanvas.mouseDragged: preventing drag beyond layer limits.");
			}
		}
	}

	public void mouseReleased(MouseEvent me) {
		boolean dragging2 = dragging;
		dragging = false;
		if (popup) {
			popup = false;
			return;
		}

		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		int tool = ProjectToolbar.getToolId();

		// pan with middle mouse like in inkscape
		/* // works, but can't use it: the alt button then is useless (so no erasing with areas, and so on)
		if (0 != (flags & InputEvent.BUTTON2_MASK)) {
			tool = Toolbar.HAND;
		}
		*/

		switch (tool) {
		case Toolbar.MAGNIFIER:
			// display.updateInDatabase("srcRect"); // TODO if the display.frame
			// is shrinked, the pack() in the zoom methods will also call the
			// updateInDatabase("srcRect") (so it's going to be done twice)
			display.updateTitle();
			return;
		case Toolbar.HAND:
			display.updateInDatabase("srcRect");
			if (-1 != tmp_tool) {
				ProjectToolbar.setTool(tmp_tool);
				tmp_tool = -1;
			}
			if (!dragging2) repaint(true); // TEMPORARY just to allow fixing bad screen when simply cliking with the hand
			return;
		}

		if (input_disabled2) {
			input_disabled2 = false; // reset
			return;
		}

		if (locked) {
			locked = false;
			if (dragging2) {
				String msg = "\nRight-click and select\"";
				if (null != display.getActive()) {
					msg += display.getActive().getClass().equals(Patch.class) ? "Unlock" : "Unlink";
				}
				msg += "\" first.";
				Utils.showMessage("Selection is locked or contains links to a locked object." + msg);
			}
			return;
		}

		if (display.getLayer().getParent().isAligning()) {
			return;
		}

		// pan with middle mouse like in inkscape
		/* // works, but can't use it: the alt button then is useless (so no erasing with areas, and so on)
		if (0 != (flags & InputEvent.BUTTON2_MASK)) {
			tool = Toolbar.HAND;
		}
		*/

		this.flags = me.getModifiers();
		flags &= ~InputEvent.BUTTON1_MASK; // make sure button 1 bit is not set (FOR AreaList brush-like)
		flags &= ~InputEvent.BUTTON2_MASK; // make sure button 2 bit is not set
		flags &= ~InputEvent.BUTTON3_MASK; // make sure button 3 bit is not set

		if (!dragging2) {
			// no real change
			display.getLayer().getParent().discardLastUndo();
		}

		int x_r = srcRect.x + (int)(me.getX() / magnification);
		int y_r = srcRect.y + (int)(me.getY() / magnification);

		this.xMouse = x_r;
		this.yMouse = y_r;

		switch (tool) {
			case ProjectToolbar.PENCIL:
				if (freehandProfile == null)
					return; // starting painting out of the DisplayCanvas boarder
				freehandProfile.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r, srcRect, magnification);
				freehandProfile = null;
				//repaint(true);
				Selection selection = display.getSelection();
				selection.updateTransform(display.getActive());
				Display.repaint(display.getLayer(), selection.getBox(), Selection.PADDING); // repaints the navigator as well
				return;
			case Toolbar.RECTANGLE:
			case Toolbar.OVAL:
			case Toolbar.POLYGON:
			case Toolbar.FREEROI:
			case Toolbar.LINE:
			case Toolbar.POLYLINE:
			case Toolbar.FREELINE:
			case Toolbar.ANGLE:
			case Toolbar.POINT:
				// pass the mouse event to superclass ImageCanvas.
				super.mouseReleased(me);
				repaint();
				// return;
		}

		// must be done here, for now the ROI is complete
		Roi roi = imp.getRoi();
		if (null != roi) {
			ImagePlus last_temp = display.getLastTemp();
			if (null != last_temp) {
				last_temp.setRoi(roi);
			}
		}

		// check:
		if (display.isReadOnly()) return;

		if (tool >= ProjectToolbar.SELECT) {
			if (null != roi) imp.killRoi();
		} else return;

		Displayable active = display.getActive();

		Selection selection = display.getSelection();

		if (snapping) {
			// finish dragging
			selection.mouseReleased(x_p, y_p, x_d, y_d, x_r, y_r);
			box.add(selection.getLinkedBox());
			Display.repaint(display.getLayer(), box, Selection.PADDING); // repaints the navigator as well
			StitchingTEM.snap(active, display); // will repaint whatever is appropriate (the visible linked group snapped along)
			// reset:
			snapping = false;
			return;
		}

		if (null != active && active.isVisible()) {
			switch(tool) {
			case ProjectToolbar.SELECT:
				selection.mouseReleased(x_p, y_p, x_d, y_d, x_r, y_r);
				box.add(selection.getLinkedBox());
				Display.repaint(display.getLayer(), Selection.PADDING, box, !selection.isTransforming()); // does not repaint the navigator
				break;
			case ProjectToolbar.PENCIL:
			case ProjectToolbar.PEN:
				active.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r, srcRect, magnification); // active, not selection (Selection only handles transforms, not active's data editions)
				// update active's bounding box
				selection.updateTransform(active);
				box.add(selection.getBox());
				if (!active.getClass().equals(AreaList.class)) Display.repaint(display.getLayer(), box, Selection.PADDING); // repaints the navigator as well
				// TODO: this last repaint call is unnecessary, if the box was properly repainted on mouse drag for Profile etc.
				else if (null != old_brush_box) {
					repaint(old_brush_box, 0, false);
					old_brush_box = null; // from mouseMoved
				}
				break;
			}
		}
	}

	// private to the package
	boolean isDragging() {
		if (null == display.getSelection()) {
			Utils.log2("WARNING DisplayCanvas.isDragging thinks the display.getSelection() gives a null object ?!?");
			return false;
		}
		return display.getSelection().isDragging();
	}

	private boolean mouse_in = false;

	public void mouseEntered(MouseEvent me) {
		mouse_in = true;
		// try to catch focus if the JFrame is front most
		if (display.isActiveWindow() && !this.hasFocus()) {
			this.requestFocus();
		}
		// bring dragged point to mouse pointer
		// TODO doesn't work as expected.
		/*
		Displayable active = display.getActive();
		int x = offScreenX(me.getX());
		int y = offScreenY(me.getY());
		if (null != active) {
			active.snapTo(x, y, x_p, y_p);
			x_p = x_d = x_d_old = x;
			y_p = y_d = y_d_old = y;
		}
		*/
		//Utils.log2("mouseEntered x,y: " + offScreenX(me.getX()) + "," + offScreenY(me.getY()));
	}

	public void mouseExited(MouseEvent me) {
		mouse_in = false;
		// paint away the circular brush if any
		if (ProjectToolbar.getToolId() == ProjectToolbar.PEN) {
			Displayable active = display.getActive();
			if (null != active && active.isVisible() && AreaList.class.equals(active.getClass())) {
				if (null != old_brush_box) {
					this.repaint(old_brush_box, 0);
					old_brush_box = null;
				}
			}
		}
	}

	/** Sets the cursor based on the current tool and cursor location. */
	public void setCursor(int sx, int sy, int ox, int oy) {
		// copy of ImageCanvas.setCursor without the win==null
		xMouse = ox;
		yMouse = oy;
		Roi roi = imp.getRoi();
		/*
		 * ImageWindow win = imp.getWindow(); if (win==null) return;
		 */
		if (IJ.spaceBarDown()) {
			setCursor(handCursor);
			return;
		}
		switch (Toolbar.getToolId()) {
		case Toolbar.MAGNIFIER:
			if (IJ.isMacintosh())
				setCursor(defaultCursor);
			else
				setCursor(moveCursor);
			break;
		case Toolbar.HAND:
			setCursor(handCursor);
			break;
		default: // selection tool
					
			if (roi != null && roi.getState() != roi.CONSTRUCTING
					&& roi.isHandle(sx, sy) >= 0)
				setCursor(handCursor);
			else if (Prefs.usePointerCursor
					|| (roi != null && roi.getState() != roi.CONSTRUCTING && roi
							.contains(ox, oy)))
				setCursor(defaultCursor);
			else
				setCursor(crosshairCursor);
		}
	}
	/** Set the srcRect - used by the DisplayNavigator. */
	protected void setSrcRect(int x, int y, int width, int height) {
		this.srcRect.setRect(x, y, width, height);
		display.updateInDatabase("srcRect");
	}

	public void setDrawingSize(int new_width, int new_height,
			boolean adjust_srcRect) {
		// adjust srcRect!
		if (adjust_srcRect) {
			double mag = super.getMagnification();
			// This method is very important! Make it fit perfectly.
			if (srcRect.width * mag < new_width) {
				// expand
				if (new_width > imageWidth * mag) {
					// too large, limit
					srcRect.x = 0;
					srcRect.width = imageWidth;
				} else {
					srcRect.width = (int) Math.ceil(new_width / mag);
					if (srcRect.x + srcRect.width > imageWidth) {
						srcRect.x = imageWidth - srcRect.width;
					}
				}
			} else {
				// shrink
				srcRect.width = (int) Math.ceil(new_width / mag);
			}
			if (srcRect.height * mag < new_height) {
				// expand
				if (new_height > imageHeight * mag) {
					// too large, limit
					srcRect.y = 0;
					srcRect.height = imageHeight;
				} else {
					srcRect.height = (int) Math.ceil(new_height / mag);
					if (srcRect.y + srcRect.height > imageHeight) {
						srcRect.y = imageHeight - srcRect.height;
					}
				}
			} else {
				// shrink
				srcRect.height = (int) Math.ceil(new_height / mag);
			}
		}
		super.setDrawingSize(new_width, new_height);
	}

	private void zoomIn2(int x, int y) {
		// copy of ImageCanvas.zoomIn except for the canEnlarge is different and
		// there's no call to the non-exisiting ImageWindow
		if (magnification >= 32)
			return;
		double newMag = getHigherZoomLevel(magnification);
		int newWidth = (int) (imageWidth * newMag);
		int newHeight = (int) (imageHeight * newMag);
		/*
		 * if (canEnlarge(newWidth, newHeight)) { setDrawingSize(newWidth,
		 * newHeight); //imp.getWindow().pack(); } else { int w =
		 * (int)Math.round(dstWidth/newMag); if (w*newMag<dstWidth) w++; int h =
		 * (int)Math.round(dstHeight/newMag); if (h*newMag<dstHeight) h++; x =
		 * offScreenX(x); y = offScreenY(y); Rectangle r = new Rectangle(x-w/2,
		 * y-h/2, w, h); if (r.x<0) r.x = 0; if (r.y<0) r.y = 0; if
		 * (r.x+w>imageWidth) r.x = imageWidth-w; if (r.y+h>imageHeight) r.y =
		 * imageHeight-h; srcRect = r; }
		 */

		// Instead, set the size that of the JPanel
		Rectangle rb = display.getCanvasPanel().getBounds(null);
		super.setDrawingSize(rb.width, rb.height);
		// .. and adjust the srcRect to the new dimensions
		int w = (int) Math.round(dstWidth / newMag);
		if (w * newMag < dstWidth)
			w++;
		if (w > imageWidth)
			w = imageWidth;
		int h = (int) Math.round(dstHeight / newMag);
		if (h * newMag < dstHeight)
			h++;
		if (h > imageHeight)
			h = imageHeight;
		x = offScreenX(x);
		y = offScreenY(y);
		Rectangle r = new Rectangle(x - w / 2, y - h / 2, w, h);
		if (r.x < 0)
			r.x = 0;
		if (r.y < 0)
			r.y = 0;
		if (r.x + w > imageWidth)
			r.x = imageWidth - w;
		if (r.y + h > imageHeight)
			r.y = imageHeight - h;
		srcRect = r;

		display.pack();

		setMagnification(newMag);
		display.updateInDatabase("srcRect");
		display.repaintAll2(); // this repaint includes this canvas's repaint as well, but also the navigator, etc. // repaint();
	}

	/**
	 * Zooms out by making srcRect bigger. If we can't make it bigger, than make
	 * the window smaller.
	 */
	private void zoomOut2(int x, int y) {
		if (magnification <= 0.03125)
			return;
		double newMag = getLowerZoomLevel(magnification);
		if (imageWidth * newMag > dstWidth) {
			int w = (int) Math.round(dstWidth / newMag);
			if (w * newMag < dstWidth)
				w++;
			int h = (int) Math.round(dstHeight / newMag);
			if (h * newMag < dstHeight)
				h++;
			x = offScreenX(x);
			y = offScreenY(y);
			Rectangle r = new Rectangle(x - w / 2, y - h / 2, w, h);
			if (r.x < 0)
				r.x = 0;
			if (r.y < 0)
				r.y = 0;
			if (r.x + w > imageWidth)
				r.x = imageWidth - w;
			if (r.y + h > imageHeight)
				r.y = imageHeight - h;
			srcRect = r;
		} else {
			srcRect = new Rectangle(0, 0, imageWidth, imageHeight);
			setDrawingSize((int) (imageWidth * newMag),
					(int) (imageHeight * newMag));
			/*
			 * // Albert: imp.getWindow().pack();
			 */
		}
		display.pack();

		setMagnification(newMag);
		display.repaintAll2(); // this repaint includes this canvas's repaint
		// as well, but also the navigator, etc.
		// repaint();
		display.updateInDatabase("srcRect");
	}

	/*
	 * // OBSOLETE: modified ij.gui.ImageCanvas directly
	 public void mouseMoved(MouseEvent e) { if (IJ.getInstance()==null) return; int sx =
	 * e.getX(); int sy = e.getY(); int ox = offScreenX(sx); int oy =
	 * offScreenY(sy); flags = e.getModifiers(); setCursor(sx, sy, ox, oy);
	 * IJ.setInputEvent(e); Roi roi = imp.getRoi(); if (roi!=null &&
	 * (roi.getType()==Roi.POLYGON || roi.getType()==Roi.POLYLINE ||
	 * roi.getType()==Roi.ANGLE) && roi.getState()==roi.CONSTRUCTING) {
	 * PolygonRoi pRoi = (PolygonRoi)roi; pRoi.handleMouseMove(ox, oy); } else {
	 * if (ox<imageWidth && oy<imageHeight) { //ImageWindow win =
	 * imp.getWindow(); //if (win!=null) win.mouseMoved(ox, oy);
	 * imp.mouseMoved(ox, oy); } else IJ.showStatus(""); } }
	 */

	private Rectangle old_brush_box = null;

	public void mouseMoved(MouseEvent me) {
		if (input_disabled || display.getSelection().isDragging()) return;
		//if (dragging) {
		//	display.getSelection().mouseMoved();
		//} else {
			//super.mouseMoved(me);
		//}

		// set xMouse. yMouse
		super.mouseMoved(me);

		Displayable active = display.getActive();

		// only when no mouse buttons are down
		int flags = me.getModifiers(); // override, the super fails for some reason
		if (0 == (flags & InputEvent.BUTTON1_MASK)
		/* && 0 == (flags & InputEvent.BUTTON2_MASK) */ // this is the alt key down ..
		 && 0 == (flags & InputEvent.BUTTON3_MASK)
		//if (me.getButton() == MouseEvent.NOBUTTON
		 && ProjectToolbar.getToolId() == ProjectToolbar.PEN && null != active && active.isVisible() && AreaList.class.equals(active.getClass())) {
			// repaint area where the brush circle is
			int brushSize = ProjectToolbar.getBrushSize() +2; // +2 padding
			Rectangle r = new Rectangle( xMouse - brushSize/2,
				                     yMouse - brushSize/2,
						     brushSize+1,
						     brushSize+1 );
			Rectangle copy = (Rectangle)r.clone(); 
			if (null != old_brush_box) r.add(old_brush_box);
			old_brush_box = copy;
			this.repaint(r, 1); // padding because of painting rounding which would live dirty trails
		}
	}

	/** Zoom in using the current mouse position, or the center if the mouse is out. */
	public void zoomIn() {
		if (xMouse < 0 || screenX(xMouse) > dstWidth || yMouse < 0 || screenY(yMouse) > dstHeight) {
			zoomIn(dstWidth/2, dstHeight/2);
		} else {
			zoomIn(screenX(xMouse), screenY(yMouse));
		}
	}

	/** Overriding to repaint the DisplayNavigator as well. */
	public void zoomIn(int x, int y) {
		update_graphics = true; // update the offscreen images.
		zoomIn2(x, y);
	}

	/** Zoom out using the current mouse position, or the center if the mouse is out. */
	public void zoomOut() {
		if (xMouse < 0 || screenX(xMouse) > dstWidth || yMouse < 0 || screenY(yMouse) > dstHeight) {
			zoomOut(dstWidth/2, dstHeight/2);
		} else zoomOut(screenX(xMouse), screenY(yMouse));
	}

	/** Overriding to repaint the DisplayNavigator as well. */
	public void zoomOut(int x, int y) {
		update_graphics = true; // update the offscreen images.
		zoomOut2(x, y);
	}

	/** Center the srcRect around the given object(s) bounding box, zooming if necessary. */
	public void showCentered(Rectangle r) {
		// multiply bounding box dimensions by two
		r.x -= r.width / 2;
		r.y -= r.height / 2;
		r.width += r.width;
		r.height += r.height;
		// compute target magnification
		double magn = getWidth() / (double)r.width;
		// bring bounds within limits of the layer and the canvas' drawing size
		double lw = display.getLayer().getLayerWidth();
		double lh = display.getLayer().getLayerHeight();
		int cw = (int) (getWidth() / magn); // canvas dimensions in offscreen coords
		int ch = (int) (getHeight() / magn);

		// 2nd attempt:
		// fit to canvas drawing size:
		r.y += (r.height - ch) / 2;
		r.width = cw;
		r.height = ch;
		// place within layer bounds
		if (r.x < 0) r.x = 0;
		if (r.y < 0) r.y = 0;
		if (r.width > lw) {
			r.x = 0;
			r.width = (int)lw;
		}
		if (r.height > lh) {
			r.y = 0;
			r.height = (int)lh;
		}
		if (r.x + r.width > lw) r.x = (int)(lw - cw);
		if (r.y + r.height > lh) r.y = (int)(lh - ch);
		// compute magn again, since the desired width may have changed:
		magn = getWidth() / (double)r.width;

		// set magnification and srcRect
		setup(magn, r);
		try { Thread.sleep(200); } catch (Exception e) {} // swing ... waiting for the display.pack()
		update_graphics = true;
		new RepaintThread(this, null, update_graphics); //repaint(); // everything
		display.updateInDatabase("srcRect");
		display.updateTitle();
		display.getNavigator().repaint(false);
	}

	private long last_repaint = -1;

	private Rectangle last_clip = null;

	 /** Repaint as much as the bounding box around the given Displayable. If the Displayable is null, the entire canvas is repainted, remaking the offscreen images. */
	public void repaint(Displayable d) {
		repaint(d, 0);
	}

	/**
	 * Repaint as much as the bounding box around the given Displayable plus the
	 * extra padding. If the Displayable is null, the entire canvas is
	 * repainted, remaking the offscreen images.
	 */
	public void repaint(Displayable displ, int extra) {
		if (null != displ) {
			Rectangle r = displ.getBoundingBox();
			r.x = (int) ((r.x - srcRect.x) * magnification) - extra;
			r.y = (int) ((r.y - srcRect.y) * magnification) - extra;
			r.width = (int) Math.ceil(r.width * magnification) + extra + extra;
			r.height = (int) Math.ceil(r.height * magnification) + extra
					+ extra;
			//repaint(r.x, r.y, r.width, r.height);
			new RepaintThread(this, r, update_graphics);
		} else {
			// everything
			repaint(true);
		}
	}

	/**
	 * Repaint the clip corresponding to the sum of all boundingboxes of
	 * Displayable objects in the hashset.
	 */
	// it is assumed that the linked objects are close to each other, otherwise
	// the clip rectangle grows enormously.
	public void repaint(HashSet hs) {
		Iterator it = hs.iterator();
		int count = 0;
		Rectangle r = new Rectangle();
		while (it.hasNext()) {
			Displayable d = (Displayable) it.next();
			if (d.getLayer().equals(this)) {
				count++;
				r.add(d.getBoundingBox());
			}
		}
		if (count > 0) {
			//repaint(r.x, r.y, r.width, r.height);
			new RepaintThread(this, r, update_graphics);
		}
	}

	/**
	 * Repaint the given offscreen Rectangle after transforming its data on the fly to the
	 * srcRect and magnification of this DisplayCanvas. The Rectangle is not
	 * modified.
	 */
	public void repaint(Rectangle r, int extra) {
		if (null == r) {
			Utils.log2("DisplayCanvas.repaint(Rectangle, int) warning: null r");
			new RepaintThread(this, null, update_graphics); // repaint everything
			return;
		}
		// repaint((int) ((r.x - srcRect.x) * magnification) - extra, (int) ((r.y - srcRect.y) * magnification) - extra, (int) Math .ceil(r.width * magnification) + extra + extra, (int) Math.ceil(r.height * magnification) + extra + extra);
		new RepaintThread(this, new Rectangle((int) ((r.x - srcRect.x) * magnification) - extra, (int) ((r.y - srcRect.y) * magnification) - extra, (int) Math .ceil(r.width * magnification) + extra + extra, (int) Math.ceil(r.height * magnification) + extra + extra), update_graphics);
	}

	/**
	 * Repaint the given Rectangle after transforming its data on the fly to the
	 * srcRect and magnification of this DisplayCanvas. The Rectangle is not
	 * modified.
	 * @param box The rectangle to repaint
	 * @param extra The extra outbound padding to add to the rectangle
	 * @param update_graphics Whether to recreate the offscreen images or not
	 */
	public void repaint(Rectangle box, int extra, boolean update_graphics) {
		this.update_graphics = update_graphics;
		repaint(box, extra);
	}

	/** Repaint everything, updating offscreen graphics if so specified. */
	public void repaint(boolean update_graphics) {
		this.update_graphics = update_graphics | this.update_graphics;
		new RepaintThread(this, null, update_graphics); // substitutes super.repaint()
	}

	/** Overridden to multithread. This method is here basically to enable calls to the FakeImagePlus.draw from the HAND and other tools to repaint properly.*/
	public void repaint() {
		//Utils.log2("issuing thread");
		new RepaintThread(this, null, update_graphics);
	}

	/** Overridden to multithread. */
	/* // saved as unoveridden to make sure there are no infinite thread loops when calling super in buggy JVMs
	public void repaint(long ms, int x, int y, int width, int height) {
		new RepaintThread(this, new Rectangle(x, y, width, height));
	}
	*/

	/** Overridden to multithread. */
	public void repaint(int x, int y, int width, int height) {
		new RepaintThread(this, new Rectangle(x, y, width, height), update_graphics);
	}

	public void setUpdateGraphics(boolean b) {
		update_graphics = b;
	}

	protected void cancelRepaints() {
		synchronized (controler_ob2) {
			while (controling2) { try { controler_ob.wait(); } catch (InterruptedException ie) {} }
			controling2 = true;
			if (null != rt_old) rt_old.quit();
			controling2 = false;
			controler_ob2.notifyAll();
		}
	}

	/** Release offscreen images and stop threads. */
	public void flush() {
		cancelRepaints();
		// cleanup update graphics thread if any
		synchronized (controler_ob2) {
			while (controling2) { try { controler_ob2.wait(); } catch (InterruptedException ie) {} }
			controling2 = true;
			if (null != rt_old) {
				rt_old.quit();
			}
			controling2 = false;
			controler_ob2.notifyAll();
		}
		synchronized (offscreen_lock) {
			while (offscreen_locked) { try { offscreen_lock.wait(); } catch (InterruptedException ie) {} }
			offscreen_locked = true;
			
			offscreen = null;
			if (null != offscreen_a) {
				offscreen_a.flush();
				offscreen_a = null;
			}
			if (null != offscreen_b) {
				offscreen_b.flush();
				offscreen_b = null;
			}
			// reset for remaking if necessary TODO doesn't work in at least java 1.6 ?
			update_graphics = true;

			offscreen_locked = false;
			offscreen_lock.notifyAll();
		}
	}

	public void destroy() {
		flush();
		WindowManager.setTempCurrentImage(imp); // the FakeImagePlus
		WindowManager.removeWindow(fake_win); // the FakeImageWindow
	}

	public boolean isTransforming() { // TODO: this can fail if the Display is closed quickly after creation
		return display.getSelection().isTransforming();
	}

	public void setTransforming(boolean b) {
		if (ProjectToolbar.getToolId() != ProjectToolbar.SELECT && b) {
			ProjectToolbar.setTool(ProjectToolbar.SELECT);
		}
		display.getSelection().setTransforming(b);
		//repaint other Displays as well!//repaint(display.getSelection().getBox(), Selection.PADDING);
		Display.repaint(display.getLayer(), display.getSelection().getBox(), Selection.PADDING); // repaints the navigator as well
	}

	public void cancelTransform() {
		Selection selection = display.getSelection();
		Rectangle box = selection.getLinkedBox();
		selection.cancelTransform();
		box.add(selection.getLinkedBox()); // the restored box now.
		if (!(selection.getNSelected() == 1 && !display.getActive().isLinked())) update_graphics = true;
		repaint(box, Selection.PADDING);
	}

	/*
	public void keyReleased(KeyEvent ke) {
		int key_code = ke.getKeyCode();
		switch (key_code) {
		case KeyEvent.VK_UP:
		case KeyEvent.VK_DOWN:
		case KeyEvent.VK_LEFT:
		case KeyEvent.VK_RIGHT:
			Selection selection = display.getSelection();
			Rectangle b = selection.getLinkedBox();
			selection.resetBox();
			b.add(selection.getLinkedBox());
			repaint(b, 0);
			ke.consume();
			break;
		}
	}
	*/

	public void keyPressed(KeyEvent ke) {

		/*
		// debug shortcut:
		if (ke.getKeyCode() == KeyEvent.VK_D && ke.isShiftDown() && ke.isAltDown() && ke.isControlDown()) {
			try {
			java.lang.reflect.Field f = Display.class.getDeclaredField("hs_panels");
			f.setAccessible(true);
			Utils.log("Display n_panels:" + ((java.util.Hashtable)f.get(display)).size());
			Utils.log("Display displ.:  " + display.getLayer().getDisplayables().size());
			ke.consume();
			} catch (Exception e) {
				new IJError(e);
			}
			return;
		}
		*/


		Displayable active = display.getActive();

		if (ProjectToolbar.getToolId() == ProjectToolbar.PENCIL) {
			if (null != freehandProfile) {
				freehandProfile.abort();
			}
			ke.consume();
			return;
		}
		/*
		 * TODO screen editor ... TEMPORARY if (active instanceof DLabel) {
		 * active.keyPressed(ke); ke.consume(); return; }
		 */

		int keyCode = ke.getKeyCode();
		int keyChar = ke.getKeyChar();

		boolean used = false;

		switch (keyChar) {
		case '+':
		case '=':
			zoomIn();
			used = true;
			break;
		case '-':
		case '_':
			zoomOut();
			used = true;
			break;
		default:
			break;
		}

		if (input_disabled) {
			if (KeyEvent.VK_ESCAPE == keyCode) {
				// cancel last job if any
				if (Utils.checkYN("Really cancel job?")) {
					display.getProject().getLoader().quitJob(null);
					display.repairGUI();
				}
			}
			ke.consume();
			return; // only zoom is enabled, above
		}

		if (KeyEvent.VK_W == keyCode) {
			display.remove(false); // will call back the canvas.flush()
			ke.consume();
			return;
		} else if (KeyEvent.VK_S == keyCode && 0 == ke.getModifiers() && display.getProject().getLoader().isAsynchronous()) {
			display.getProject().getLoader().save(display.getProject());
			ke.consume();
			return;
		}

		// if display is not read-only, check for other keys:
		switch (keyChar) {
		case '<':
		case ',': // select next Layer up
			display.previousLayer(ke.getModifiers()); // repaints as well
			ke.consume();
			return;
			/*
			 * used = true; break;
			 */
		case '>':
		case '.': // select next Layer down
			display.nextLayer(ke.getModifiers());
			ke.consume();
			return;
			/*
			 * used = true; break;
			 */
		}

		if (null != imp.getRoi()) {
			IJ.getInstance().keyPressed(ke);
			return;
		}

		// end here if display is read-only
		if (display.isReadOnly()) {
			ke.consume();
			display.repaintAll();
			return;
		}

		if (used) {
			ke.consume(); // otherwise ImageJ would use it!
			// repaint();
			return;
		}

		if (KeyEvent.VK_ENTER == keyCode) {
			if (display.getSelection().isTransforming()) {
				setTransforming(false); // will apply transforms and repaint
				ke.consume();
				return;
			} else if (display.getLayer().getParent().isAligning()) {
				display.getLayer().getParent().applyAlign(false);
				ke.consume();
				return;
			} else {
				IJ.getInstance().toFront();
				ke.consume();
				return;
			}
		}

		// check preconditions (or the keys are meaningless). Allow 'enter' to
		// bring forward the ImageJ window, and 'v' to paste a patch.
		/*if (null == active && KeyEvent.VK_ENTER != keyCode && KeyEvent.VK_V != keyCode && KeyEvent) {
			return;
		}*/

		Layer layer = display.getLayer();

		switch (keyCode) {
			case KeyEvent.VK_COMMA:
			case 0xbc: // select next Layer up
				display.nextLayer(ke.getModifiers());
				break;
			case KeyEvent.VK_PERIOD:
			case 0xbe: // select next Layer down
				display.previousLayer(ke.getModifiers());
				break;
			case KeyEvent.VK_Z:
				int mod = ke.getModifiers();
				if (0 == (mod ^ Event.SHIFT_MASK)) {
					if (!layer.getParent().canRedo()) {
						// catch the current
						display.getLayer().getParent().appendCurrent(display.getSelection().getTransformationsCopy());
					}
					display.getLayer().getParent().undoOneStep();
					ke.consume();
				} else if (0 == (mod ^ Event.ALT_MASK)) {
					display.getLayer().getParent().redoOneStep();
					ke.consume();
				}
				// else, the 'z' command restores the image using ImageJ internal undo
				break;
			case KeyEvent.VK_T:
				if (null != active && 0 == ke.getModifiers() && !isTransforming()) {
					setTransforming(true);
					ke.consume();
				}
				// else, let ImageJ grab the ROI into the Manager, if any
				break;
			case KeyEvent.VK_ESCAPE: // cancel transformation
				if (display.getLayer().getParent().isAligning()) {
					display.getLayer().getParent().cancelAlign();
					ke.consume();
				} else if (null != active) {
					if (display.getSelection().isTransforming()) cancelTransform();
					else {
						display.select(null); // deselect
						// repaint out the brush if present
						if (ProjectToolbar.PEN == ProjectToolbar.getToolId()) {
							repaint(old_brush_box, 0);
						}
					}
					ke.consume();
				}
				break;
			case KeyEvent.VK_SPACE:
				if (0 == ke.getModifiers()) {
					if (null != active) {
						if (Math.abs(active.getAlpha() - 0.5f) > 0.001f) active.setAlpha(0.5f);
						else active.setAlpha(1.0f);
						display.setTransparencySlider(active.getAlpha());
						ke.consume();
					}
				}
				break;
			case KeyEvent.VK_S:
				if (ke.isAltDown()) {
					snapping = true;
					ke.consume();
				} else if (dragging) {
					// ignore improper 's' that open ImageJ's save dialog (linux problem ... in macosx, a single dialog opens with lots of 'ssss...' in the text field)
					ke.consume();
				}
				break;
			case KeyEvent.VK_I:
				if (ke.isAltDown()) {
					if (ke.isShiftDown()) display.importImage();
					else display.importNextImage();
					ke.consume();
				}
				break;
			case KeyEvent.VK_PAGE_UP: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.moveUp(active); // active is not null, see above
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_PAGE_DOWN: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.moveDown(active); // active is not null, see above
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_HOME: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.moveTop(active); // active is not null, see above
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_END: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.moveBottom(active); // active is not null, see above
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_V: // paste image
				if (0 == ke.getModifiers()) {
					ImagePlus clipboard = ImagePlus.getClipboard();
					if (null != clipboard) {
						ImagePlus imp = new ImagePlus(clipboard.getTitle() + "_" + System.currentTimeMillis(), clipboard.getProcessor().crop());
						Object info = clipboard.getProperty("Info");
						if (null != info) imp.setProperty("Info", (String)info);
						double x = srcRect.x + srcRect.width/2 - imp.getWidth()/2;
						double y = srcRect.y + srcRect.height/2 - imp.getHeight()/2;
						display.getLayer().add(new Patch(display.getProject(), imp.getTitle(), x, y, imp)); // WARNING potential problem with lack of path, but the Loader caches such situation and asks for the image to be saved, OR should do so at addToDatabase() level
						ke.consume();
					} // TODO there isn't much ImageJ integration in the pasting. Can't paste to a selected image, for example.
				}
				break;
			case KeyEvent.VK_C: // copy active Patch if any into ImageJ clipboard
				if (0 == ke.getModifiers()) {
					ImagePlus imp = display.getLastTemp();
					if (null != imp) {
						imp.copy(false);
						ke.consume();
					}
				}
				break;
			default:
				// forward event to active
				if (null != active) {
					active.keyPressed(ke);
					if (ke.isConsumed()) {
						Selection selection = display.getSelection();
						repaint(selection.getLinkedBox(), Selection.PADDING + 2); // optimization
					}
				}
		}

		if ( !(keyCode == KeyEvent.VK_UNDEFINED || keyChar == KeyEvent.CHAR_UNDEFINED) && !ke.isConsumed() && null != active && active instanceof Patch) {
			// forward to ImageJ for a final try
			IJ.getInstance().keyPressed(ke);
			repaint(active, 5);
			ke.consume();
		}
		//Utils.log2("keyCode, keyChar: " + keyCode + ", " + keyChar + " ref: " + KeyEvent.VK_UNDEFINED + ", " + KeyEvent.CHAR_UNDEFINED);
	}

	public void keyTyped(KeyEvent ke) {
	}

	public void keyReleased(KeyEvent ke) {
	}

	public void zoomToFit() {
		double magw = (double) getWidth() / imageWidth;
		double magh = (double) getHeight() / imageHeight;
		this.magnification = magw < magh ? magw : magh;
		this.srcRect.setRect(0, 0, imageWidth, imageHeight);
		setMagnification(magnification);
		display.updateInDatabase("srcRect"); // includes magnification
	}


	public void setReceivesInput(boolean b) {
		this.input_disabled = !b;
	}

	public boolean isInputEnabled() {
		return !input_disabled;
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append("<canvas magnification=\"").append(magnification).append("\" srcrect_x=\"").append(srcRect.x).append("\" srcrect_y=\"").append(srcRect.y).append("\" srcrect_width=\"").append(srcRect.width).append("\" srcrect_height=\"").append(srcRect.height).append("\">\n");
	}

	/** CAREFUL: the ImageProcessor of the returned ImagePlus is fake, that is, a 4x4 byte array; but the dimensions that it returns are those of the host LayerSet. Used to retrieve ROIs for example.*/
	public ImagePlus getFakeImagePlus() {
		return this.imp;
	}

	/*
	public void focusGained(FocusEvent e) {
	}
	public void focusLost(FocusEvent e) {
	}
	*/

	/** Key/Mouse bindings like:
	 * - ij.gui.StackWindow: wheel to scroll slices (in this case Layers)
	 * - Inkscape: control+wheel to zoom (apple+wheel in macosx, since control+wheel zooms desktop)
	 */
	public void mouseWheelMoved(MouseWheelEvent mwe) {
		if (dragging) return; // prevent unexpected mouse wheel movements
		int modifiers = mwe.getModifiers();
		int zoom = InputEvent.CTRL_MASK;
		if (IJ.isMacOSX()) zoom = InputEvent.META_MASK; // the apple key, according to ImageJ.keyPressed
		if (0 != (modifiers & zoom)) {
			// scroll zooom under pointer
			int x = mwe.getX();
			int y = mwe.getY();
			if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
				x = getWidth()/2;
				y = getHeight()/2;
			}
			if (1 == mwe.getWheelRotation()) {
				zoomOut(x, y);
			} else {
				zoomIn(x, y);
			}
		} else if (0 != (modifiers & InputEvent.SHIFT_MASK) && ProjectToolbar.getToolId() == ProjectToolbar.PEN) {
			int brushSize_old = ProjectToolbar.getBrushSize();
			// resize brush for AreaList painting
			int brushSize = ProjectToolbar.setBrushSize(5 * mwe.getWheelRotation()); // the getWheelRotation provides the sign
			if (brushSize_old > brushSize) brushSize = brushSize_old; // for repainting purposes alnne
			Rectangle r = new Rectangle((int)(mwe.getX() / magnification) + srcRect.x - brushSize/2, (int)(mwe.getY() / magnification) + srcRect.y - brushSize/2, brushSize+2, brushSize+2);
			this.repaint(r, 0);
		} else {
			// scroll layers
			if (1 == mwe.getWheelRotation()) display.nextLayer(modifiers);
			else display.previousLayer(modifiers);
		}
	}

	private class OffscreenThread extends Thread {
		private boolean stop_offscreen_data = false;
		private Layer layer;
		private int g_width;
		private int g_height;
		private Displayable active;
		private int c_alphas;
		private Rectangle clipRect;
		OffscreenThread(final Rectangle clipRect, final Layer layer, final int g_width, final int g_height, final Displayable active, final int c_alphas) {
			this.clipRect = clipRect;
			this.layer = layer;
			this.g_width = g_width;
			this.g_height = g_height;
			this.active = active;
			this.c_alphas = c_alphas;
			//Utils.log2("offscreen created " + this.getId());
		}
		public void cancel() {
			//Utils.log2("offscreen canceled " + this.getId());
			this.stop_offscreen_data = true;
		}

		public void run() {
			try {
				final Loader loader = layer.getProject().getLoader();
				// flag Loader to do massive flushing if needed
				loader.setMassiveMode(true);

				if (stop_offscreen_data) return;

				// paint all to offscreen image
				Graphics2D g = null;
				Area background = null;

				if (stop_offscreen_data) return;

				Lock lock = null;
				Image target = null;

				long start = System.currentTimeMillis();

				synchronized (offscreen_lock) {
					while (offscreen_locked) { try { offscreen_lock.wait(); } catch (InterruptedException ie) {} }
					offscreen_locked = true;

					Rectangle prev_clip = null;

					// Paint on the offscreen image currently not used for painting the screen
					// recreate if canvas size has changed, otherwise reuse
					if (offscreen_b == offscreen) {
						// paint on a
						if (null == offscreen_a || g_width != offscreen_a.getWidth(null) || g_height != offscreen_a.getHeight(null)) {
							if (null != offscreen_a) offscreen_a.flush();
							offscreen_a = getGraphicsConfiguration().createCompatibleImage(g_width, g_height);
							offscreen_a.setAccelerationPriority(1.0f);
						}
						target = offscreen_a;
						prev_clip = clipRect_b;
						clipRect_a = null != clipRect ? (Rectangle)clipRect.clone() : null; // update
						lock = lock_a;
					} else {
						// offscreen_a == offscreen, paint on b
						if (null == offscreen_b || g_width != offscreen_b.getWidth(null) || g_height != offscreen_b.getHeight(null)) {
							if (null != offscreen_b) offscreen_b.flush();
							offscreen_b = getGraphicsConfiguration().createCompatibleImage(g_width, g_height);
							offscreen_b.setAccelerationPriority(1.0f);
						}
						target = offscreen_b;
						prev_clip = clipRect_a;
						clipRect_b = null != clipRect ? (Rectangle)clipRect.clone() : null; // update
						lock = lock_b;
					}

					// add the clip that was used in the other offscreen image, unless null (which would mean 'the entire area')
					if (null != prev_clip) {
						if (null != clipRect) clipRect.add(prev_clip);
					}

					offscreen_locked = false;
					offscreen_lock.notifyAll();
				}

				synchronized (lock) {
					lock.lock();

					g = (Graphics2D)target.getGraphics();
					// ALMOST, but not always perfect //if (null != clipRect) g.setClip(clipRect);
					//    BUT IT SHOULD BE HERE TODO

					// prepare the canvas for the srcRect and magnification
					final AffineTransform at_original = g.getTransform();
					atc.setToIdentity();
					atc.scale(magnification, magnification);
					atc.translate(-srcRect.x, -srcRect.y);
					at_original.preConcatenate(atc);
					g.setTransform(at_original);

					//setRenderingHints(g);
					// always a stroke of 1.0, regardless of magnification
					g.setStroke(stroke);

					// Area to which each Patch will subtract from
					background =  new Area(new Rectangle(0, 0, target.getWidth(null), target.getHeight(null)));
					// bring the area to Layer space
					background.transform(atc.createInverse());
					boolean bkgd_painted = false;

					// the non-srcRect areas, in offscreen coords
					Rectangle r1 = new Rectangle(srcRect.x + srcRect.width, srcRect.y, (int)(target.getWidth(null) / magnification) - srcRect.width, (int)(target.getHeight(null) / magnification));
					Rectangle r2 = new Rectangle(srcRect.x, srcRect.y + srcRect.height, srcRect.width, (int)(target.getHeight(null) / magnification) - srcRect.height);
					// subtract them from the clipRect
					// TODO to avoid painting images and then the grey rect on top
					
					//if (offscreen_a == target) Utils.log2("clip: " + clipRect);

					al_top.clear();
					boolean top = false;

					// start
					final ArrayList al = layer.getDisplayables();
					final int n = al.size();
					final ArrayList al_zdispl = layer.getParent().getZDisplayables();
					final int m = al_zdispl.size();

					// Assumes the Layer has its objects in order:
					// 1 - Patches
					// 2 - Profiles, Balls
					// 3 - Pipes and ZDisplayables (from the parent LayerSet)
					// 4 - DLabels

					int i = 0;
					while (i < n) {
						if (stop_offscreen_data && start - System.currentTimeMillis() > 100) {
							//offscreen_locked = false;
							//offscreen_lock.notifyAll();
							lock.unlock();
							return;
						}
						final Displayable d = (Displayable)al.get(i);
						final Class c = d.getClass();
						if (c.equals(DLabel.class) || c.equals(LayerSet.class)) {
							break;
						}
						if (c.equals(Patch.class)) {
							background.subtract(new Area(d.getPerimeter())); // must be outside because the clip could be limited to the active, for instance
						} else {
							if (!background.isEmpty()) {
								// subtract non-srcRect areas
								background.subtract(new Area(r1));
								background.subtract(new Area(r2));
								// paint background
								g.setColor(Color.black);
								g.fill(background);
								bkgd_painted = true;
								//Utils.log2("off is " + (offscreen == offscreen_a ?  "a" : "b"));
							}
							if (d.equals(active)) top = true; // no Patch instances allowed on top
						}
						if (!d.isOutOfRepaintingClip(magnification, srcRect, null)) {
							if (!c.equals(Patch.class) && top) al_top.add(d);
							else d.prePaint(g, magnification, false, c_alphas, layer);
						}
						i++;
					}
					// paint the ZDisplayables here, before the labels and LayerSets, if any
					int j = 0;
					while (j < m) {
						if (stop_offscreen_data && start - System.currentTimeMillis() > 100) {
							//offscreen_locked = false;
							//offscreen_lock.notifyAll();
							lock.unlock();
							return;
						}
						final ZDisplayable zd = (ZDisplayable) al_zdispl.get(j);
						if (!zd.isOutOfRepaintingClip(magnification, srcRect, null)) {
							if (zd.equals(active)) top = true;
							if (top) al_top.add(zd);
							else zd.paint(g, magnification, false, c_alphas, layer);
						}
						j++;
					}
					// paint LayerSet and DLabel objects!
					while (i < n) {
						if (stop_offscreen_data && start - System.currentTimeMillis() > 100) {
							//offscreen_locked = false;
							//offscreen_lock.notifyAll();
							lock.unlock();
							return;
						}
						final Displayable d = (Displayable) al.get(i);
						if (!d.isOutOfRepaintingClip(magnification, srcRect, null)) {
							if (d.equals(active)) top = true;
							if (top) al_top.add(d);
							else d.paint(g, magnification, false, c_alphas, layer);
						}
						i++;
					}

					// there may be only Patch objects ..
					if (!bkgd_painted) {
						g.setColor(Color.black);
						g.fill(background);
					}

					// finally, paint non-srcRect areas
					g.setColor(Color.gray);
					g.fill(r1);
					g.fill(r2);

					lock.unlock();
				}

				synchronized (offscreen_lock) {
					while (offscreen_locked) { try { offscreen_lock.wait(); } catch (InterruptedException ie) {} }
					offscreen_locked = true;

					// only on success:
					update_graphics = false;
					loader.setMassiveMode(false);
					offscreen = target;

					offscreen_locked = false;
					offscreen_lock.notifyAll();
				}

				// signal that the offscreen image is done: repaint
				new Thread() { public void run() { new RepaintThread(DisplayCanvas.this, clipRect, false); }}.start(); // the new thread prevents lock up, since a thread may be joining this thread if it's trying to quit it

			} catch (OutOfMemoryError oome) {
				// so OutOfMemoryError won't generate locks
				new IJError(oome);
				synchronized (offscreen_lock) {
					if (offscreen_locked) {
						offscreen_locked = false;
						offscreen_lock.notifyAll();
					}
				}
			} catch (Exception e) {
				new IJError(e);
				synchronized (offscreen_lock) {
					if (offscreen_locked) {
						offscreen_locked = false;
						offscreen_lock.notifyAll();
					}
				}
			}
		}
	}

	// added here to prevent flickering, but doesn't help. All it does is avoid a call to imp.redraw()
	protected void scroll(int sx, int sy) {
		int ox = xSrcStart + (int)(sx/magnification);  //convert to offscreen coordinates
		int oy = ySrcStart + (int)(sy/magnification);
		int newx = xSrcStart + (xMouseStart-ox);
		int newy = ySrcStart + (yMouseStart-oy);
		if (newx<0) newx = 0;
		if (newy<0) newy = 0;
		if ((newx+srcRect.width)>imageWidth) newx = imageWidth-srcRect.width;
		if ((newy+srcRect.height)>imageHeight) newy = imageHeight-srcRect.height;
		srcRect.x = newx;
		srcRect.y = newy;
	}
}
