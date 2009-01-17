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
import ini.trakem2.Project;
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
import java.awt.image.VolatileImage;
import java.util.*;
import java.awt.Cursor;
import java.util.concurrent.atomic.AtomicInteger;

import ini.trakem2.utils.Lock;


public final class DisplayCanvas extends ImageCanvas implements KeyListener/*, FocusListener*/, MouseWheelListener {

	private Display display;

	private boolean update_graphics = false;
	private BufferedImage offscreen = null;
	private ArrayList al_top = new ArrayList();

	private final Lock lock_paint = new Lock();

	private Rectangle box = null; // the bounding box of the active

	private FakeImageWindow fake_win;

	private FreeHandProfile freehandProfile = null;
	private Robot r;// used for setting the mouse pointer

	//private RepaintThread rt_old = null;
	/** Any painting operation should wait on this object, set the controling flag to true, and when done set controling to false and controler.notifyAll(). */
	private final Object controler_ob = new Object();
	private boolean controling = false;

	private final Lock offscreen_lock = new Lock();

	private Cursor noCursor;

	private boolean snapping = false;
	private boolean dragging = false;
	private boolean input_disabled = false;
	private boolean input_disabled2 = false;

	private static final int NONE = 0;
	private static final int MOUSE = 1;
	private static final int KEY_MOVE = 2;
	private static final int Z_KEY = 4; // the undo system
	private int last_action = NONE;

	/** Store a copy of whatever data as each Class may define it, one such data object per class.
	 * Private to the package. */
	static private Hashtable<Class,Object> copy_buffer = new Hashtable<Class,Object>();

	static void setCopyBuffer(final Class c, final Object ob) { copy_buffer.put(c, ob); }
	static Object getCopyBuffer(final Class c) { return copy_buffer.get(c); }

	static private boolean openglEnabled = false;
	static private boolean quartzEnabled = false;
	static private boolean ddscaleEnabled = false;

	// Private to the display package:
	static final RenderingHints rhints;
 
	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent; */
	static {
		final Map<RenderingHints.Key, Object> hints = new HashMap<RenderingHints.Key, Object>();
		try {
			String openglProperty = System.getProperty("sun.java2d.opengl");
			openglEnabled = openglProperty != null && Boolean.parseBoolean(openglProperty);
		} catch (Exception ex) { }
		try {
			String quartzProperty = System.getProperty("apple.awt.graphics.UseQuartz");
			quartzEnabled = Boolean.parseBoolean(quartzProperty);
		} catch (Exception ex) { }
		try {
			String ddscaleProperty = System.getProperty("sun.java2d.ddscale");
			String d3dProperty = System.getProperty("sun.java2d.d3d");
			ddscaleEnabled = Boolean.parseBoolean(ddscaleProperty) && Boolean.parseBoolean(d3dProperty);
		} catch (Exception ex) { }

		if (openglEnabled) {
			// Bilinear interpolation can be accelerated by the OpenGL pipeline
			hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			hints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			
		} else if (quartzEnabled) {
			//hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			hints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
			hints.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			
		} else if (ddscaleEnabled) {
			hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		}

		rhints = new RenderingHints(hints);
	}

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent; */
	/*
	private ActionListener resourceReaper = new ActionListener() {
		public void actionPerformed(final ActionEvent ae) {
			if (!frameRendered) {
				if (volatileImage != null) {
					volatileImage.flush();
					volatileImage = null;
				}
				frameRendered = false;

				// Stop the timer so we don't wakeup needlessly
				resourceTimer.stop();
			}
		}
 	};
	*/

	private VolatileImage volatileImage;
	//private javax.swing.Timer resourceTimer = new javax.swing.Timer(10000, resourceReaper);
	//private boolean frameRendered = false;
	private boolean invalid_volatile = false;

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent; */
	private void renderVolatileImage(final BufferedImage bufferedImage, final Displayable active, final Displayable[] top, final Layer active_layer, final int c_alphas, final AffineTransform at, Rectangle clipRect) {
		do {
			final int w = getWidth(), h = getHeight();
			final GraphicsConfiguration gc = getGraphicsConfiguration();
			if (invalid_volatile || volatileImage == null || volatileImage.getWidth() != w 
					|| volatileImage.getHeight() != h
					|| volatileImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
				if (volatileImage != null) {
					volatileImage.flush();
				}
				volatileImage = gc.createCompatibleVolatileImage(w, h);
				volatileImage.setAccelerationPriority(1.0f);
				invalid_volatile = false;
				clipRect = null; // paint all
			}
			// 
			// Now paint the BufferedImage into the accelerated image
			//
			final Graphics2D g = volatileImage.createGraphics();

			// 0 - set clipRect
			if (null != clipRect) g.setClip(clipRect);

			// 1 - Erase any background
			g.setColor(Color.black);
			if (null == clipRect) g.fillRect(0, 0, w, h);
			else g.fillRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);

			// 2 - Paint offscreen image
			g.drawImage(bufferedImage, 0, 0, null);

			// 3 - Paint the active Displayable and all cached on top
			if (null != active_layer) {
				g.setTransform(at);
				g.setStroke(this.stroke); // AFTER setting the transform
				if (null != active && active.getClass() != Patch.class && !active.isOutOfRepaintingClip(magnification, srcRect, clipRect)) active.paint(g, magnification, true, c_alphas, active_layer);
				if (null != top) {
					final Rectangle tmp = null != clipRect ? new Rectangle() : null;
					final Rectangle clip = null != clipRect ? new Rectangle((int)(clipRect.x * magnification) - srcRect.x, (int)(clipRect.y * magnification) - srcRect.y, (int)(clipRect.width * magnification), (int)(clipRect.height * magnification)) : null;
					for (int i=0; i<top.length; i++) {
						if (null != clipRect && !top[i].getBoundingBox(tmp).intersects(clip)) continue;
						top[i].paint(g, magnification, false, c_alphas, active_layer);
					}
				}
				//Utils.log2("painted new volatile with active " + active);
			}

			g.dispose();
		} while (volatileImage.contentsLost());
	}

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent;
	 *  Paints (and re-renders, if necessary) the volatile image onto the given Graphics object, which
	 *  is that of the DisplayCanvas as provided to the paint(Graphics g) method.
	 *
	 *  Expects clipRect in screen coordinates
	 */
	private void render(final Graphics g, final Displayable active, final Displayable[] top, final Layer active_layer, final int c_alphas, final AffineTransform at, Rectangle clipRect) {
		final Graphics2D g2d = (Graphics2D) g.create();
		g2d.setRenderingHints(rhints);
		do {
			if (invalid_volatile || null == volatileImage
			 || volatileImage.validate(getGraphicsConfiguration()) != VolatileImage.IMAGE_OK)
			{
				// clear clip, remake in full
				clipRect = null;
				renderVolatileImage(offscreen, active, top, active_layer, c_alphas, at, clipRect);
			}
			if (null != clipRect) g2d.setClip(clipRect);
			g2d.drawImage(volatileImage, 0, 0, null);
		} while (volatileImage.contentsLost());

		g2d.dispose();

		//
		// Restart the resource reaper timer if neccessary
		//
		/*
		if (!frameRendered) {
			frameRendered = true;
			if (!resourceTimer.isRunning()) {
				resourceTimer.restart();
			}
		}
		*/
	}

	protected void invalidateVolatile() {
		this.invalid_volatile = true;
	}

	/////////////////

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

	public Display getDisplay() { return display; }

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
		display.pack(); // TODO should be run via invokeLater ... need to check many potential locks of invokeLater calling each other.
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
		// overriding to avoid default behaviour in java.awt.Canvas which consists in first repainting the entire drawable area with the background color, and then calling method paint.
		this.paint(g);
	}

	//private AtomicInteger counter = new AtomicInteger(0); // threads' tag

	private long last_paint = 0;

	/** Handles repaint event requests and the generation of offscreen threads. */
	private final AbstractRepaintThread RT = new AbstractRepaintThread(this, "T2-Canvas-Repainter") {
		protected void handleUpdateGraphics(final Component target, final Rectangle clipRect) {
			try {
				// Signal previous offscreen threads to quit
				cancelOffs();
				// issue new offscreen thread
				final OffscreenThread off = new OffscreenThread(clipRect, display.getLayer(), target.getWidth(), target.getHeight(), display.getActive(), display.getDisplayChannelAlphas());
				// store to be canceled if necessary
				add(off);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		public void paintFromOff(final Rectangle clipRect, final long time) {
			// WARNING this is just a patch
			//Utils.log("paintFromOff");
			super.paintFromOff(display.getSelection().contains(Patch.class) ? null : clipRect, time);
		}
	};

	/*
	private final void setRenderingHints(final Graphics2D g) {
		// so slow!! Particularly the first one.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		//g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		//g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
		//g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		//g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}
	*/

	public void setMagnification(double mag) {
		// ensure a stroke of thickness 1.0 regardless of magnification
		this.stroke = new BasicStroke((float)(1.0/mag), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
		// FIXES MAG TO ImageCanvas.zoomLevel LIMITS!!
		//super.setMagnification(mag);
		// So, manually:
		this.magnification = mag;
		imp.setTitle(imp.getTitle());
	}

	/** Paint lines always with a thickness of 1 pixel. This stroke is modified when the magnification is changed, to compensate. */
	private BasicStroke stroke = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);

	/** The affine transform representing the srcRect displacement and the magnification. */
	private final AffineTransform atc = new AffineTransform();

	public void paint(final Graphics g) {
		try {
			synchronized (lock_paint) {
				lock_paint.lock();
			}
			//display.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

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

			/*if (ProjectToolbar.getToolId() == ProjectToolbar.PEN && (0 != (flags & InputEvent.BUTTON1_MASK)) && (0 == (flags & InputEvent.ALT_MASK)) && null != active && active.getClass() == AreaList.class && ((AreaList)active).isFillPaint()) {
				// no background paint if painting in fill_paint mode and not erasing
			} else {
			*/
				synchronized (offscreen_lock) {
					offscreen_lock.lock();
					try {

						// prepare the canvas for the srcRect and magnification
						final AffineTransform at_original = g2d.getTransform();
						atc.setToIdentity();
						atc.scale(magnification, magnification);
						atc.translate(-srcRect.x, -srcRect.y);
						at_original.preConcatenate(atc);

						di = new Displayable[al_top.size()];
						al_top.toArray(di);

						if (null != offscreen) {
							//g.drawImage(offscreen, 0, 0, null);
							if (dragging) invalidateVolatile(); // to update the active at least
							render(g, active, di, active_layer, c_alphas, at_original, clipRect);
						}

						g2d.setTransform(at_original);

					} catch (Exception e) {
						e.printStackTrace();
					}
					offscreen_lock.unlock();
				}
			//}

			g2d.setStroke(this.stroke);

			/*
			// paint the active unless it's a Patch (since it's been painted offscreen already)
			if (null != active && active.getClass() != Patch.class && !active.isOutOfRepaintingClip(magnification, srcRect, clipRect)) {
				active.paint(g2d, magnification, true, c_alphas, active_layer);
			}

			if (null != di) {
				for (int i=0; i<di.length; i++) {
					di[i].paint(g2d, magnification, false, c_alphas, active_layer);
				}
			}
			*/

			// always a stroke of 1.0, regardless of magnification
			//g2d.setStroke(this.stroke);

			// paint a pink frame around selected objects, and a white frame around the active object
			if (null != selection && ProjectToolbar.getToolId() < ProjectToolbar.PENCIL) { // i.e. PENCIL, PEN and ALIGN
				selection.paint(g2d, srcRect, magnification);
			}


			// debug
			//if (null != display.getLayer().root) display.getLayer().root.paint(g2d, srcRect, magnification, Color.red);
			//if (null != display.getLayer().getParent().root) display.getLayer().getParent().root.paint(g2d, srcRect, magnification, Color.blue);


			// reset to identity
			g2d.setTransform(new AffineTransform());
			// reset to 1.0 thickness
			g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

			final Align align = null != active_layer ? active_layer.getParent().getAlign() : null;
			if (null != align) {
				align.paint(active_layer, g2d, srcRect, magnification);
			}

			// paint brush outline for AreaList
			if (mouse_in && null != active && ProjectToolbar.getToolId() == ProjectToolbar.PEN && active.getClass() == AreaList.class) {
				int brushSize = ProjectToolbar.getBrushSize();
				g.setColor(active.getColor());
				g.drawOval((int)((xMouse -srcRect.x -brushSize/2)*magnification), (int)((yMouse - srcRect.y -brushSize/2)*magnification), (int)(brushSize * magnification), (int)(brushSize * magnification));
			}

			if (null != roi) {
				roi.draw(g);
			}

			// restore cursor
			//display.setCursor(Cursor.getDefaultCursor());

			// Mathias code:
			if (null != freehandProfile) {
				freehandProfile.paint(g, magnification, srcRect, true);
				if(noCursor == null)
					noCursor = Toolkit.getDefaultToolkit().createCustomCursor(new BufferedImage(1,1,BufferedImage.TYPE_BYTE_BINARY), new Point(0,0), "noCursor");
			}

			final long now = System.currentTimeMillis();
			//p("interval: " + (now - last_paint));
			last_paint = now;

		} catch (Exception e) {
			Utils.log2("DisplayCanvas.paint(Graphics) Error: " + e);
			IJError.print(e);
		} finally {
			// restore cursor
			/*
			if (null == freehandProfile) {
				setCursor(Cursor.getDefaultCursor());
			} else {
				setCursor(noCursor);
			}
			*/
			synchronized (lock_paint) {
				lock_paint.unlock();
			}
		}
	}

	public void waitForRepaint() {
		// wait for all offscreen methods to finish painting
		RT.waitForOffs();
		// wait for the paint method to finish painting
		synchronized (lock_paint) {
			lock_paint.lock(); // wait until painting is done
			lock_paint.unlock();
		}
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
		if (0 != (flags & InputEvent.BUTTON2_MASK))
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
				// TODO should use LayerStack virtualization ... then scale back the ROI
			}
			return;
		case ProjectToolbar.PENCIL:
			if (null != active && active.isVisible() && active.getClass() == Profile.class) {
				Profile prof = (Profile) active;
				this.freehandProfile = new FreeHandProfile(prof);
				freehandProfile.mousePressed(x_p, y_p);
				return;
			}
			break;
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
				display.getLayer().add(label);
				label.edit();
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
			selection.mousePressed(me, x_p, y_p, magnification);
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
			// if there is one step, don't add it.
			if (!display.getLayer().getParent().canUndo()) display.getLayer().getParent().addUndoStep(selection.getTransformationsCopy());
		}

		if (ProjectToolbar.ALIGN == tool) {
			LayerSet set = display.getLayer().getParent();
			if (!set.isAligning()) {
				set.startAlign(display);
			}
			set.getAlign().mousePressed(display.getLayer(), me, x_p, y_p, magnification);
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
			selection.mousePressed(me, x_p, y_p, magnification);
			break;
		default: // the PEN and PENCIL tools, and any other custom tool
			box = active.getBoundingBox();
			active.mousePressed(me, x_p, y_p, magnification);
			invalidateVolatile();
			break;
		}
		//Utils.log("locked: " + locked + " popup: " + popup + " input_disabled2: " + input_disabled2);
	}

	public void mouseDragged(MouseEvent me) {
		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		Selection selection = display.getSelection();
		if (locked && !selection.isEmpty()) {
			Utils.log("Selection is locked.");
			return;
		}

		if (popup) return;

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
			int srx = srcRect.x,
			    sry = srcRect.y;
			scroll(me.getX(), me.getY());
			if (0 != srx - srcRect.x || 0 != sry - srcRect.y) {
				update_graphics = true; // update the offscreen images.
				display.getNavigator().repaint(false);
				repaint(true);
			}
			return;
		}

		if (input_disabled2) return;

		if (null != display.getLayer().getParent().getAlign()) return;


		//debug:
		//Utils.log2("x_d,y_d : " + x_d + "," + y_d + "   x_d_old, y_d_old : " + x_d_old + "," + y_d_old + "  dx, dy : " + (x_d_old - x_d) + "," + (y_d_old - y_d));

		// Code for Matthias' FreehandProfile (TODO this should be done on mousePressed, not on mouseDragged)

		Displayable active = display.getActive();
		if (null != active && active.getClass() == Profile.class) {
			try {
				if (r == null) {
					r = new Robot(this.getGraphicsConfiguration().getDevice());
				}
			} catch (AWTException e) {
				e.printStackTrace();
			}
		}

		switch (tool) {
		case ProjectToolbar.PENCIL:
			if (null != active && active.isVisible() && active.getClass() == Profile.class) {
				if (freehandProfile == null)
					return; // starting painting out of the DisplayCanvas border
				double dx = x_d - x_d_old;
				double dy = y_d - y_d_old;
				freehandProfile.mouseDragged(me, x_d, y_d, dx, dy);
				repaint();
				// Point screenLocation = getLocationOnScreen();
				// mousePos[0] += screenLocation.x;
				// mousePos[1] += screenLocation.y;
				// r.mouseMove( mousePos[0], mousePos[1]);
				return;
			}
			break;
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

		if (null != active && active.isVisible()) {
			// prevent dragging beyond the layer limits
			if (display.getLayer().contains(x_d, y_d, 1)) {
				Rectangle box2;
				switch (tool) {
				case ProjectToolbar.SELECT:
					selection.mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
					box2 = selection.getLinkedBox();
					box.add(box2);
					// repaint all Displays (where it was and where it is now, hence the sum of both boxes):
			//TODO//Utils.log2("md: " + box.toString());
					Display.repaint(display.getLayer(), Selection.PADDING, box, false, active.isLinked() || active.getClass() == Patch.class);
					// box for next mouse dragged iteration
					box = box2;
					break;
				case ProjectToolbar.ALIGN:
					break; // nothing
				default:
					active.mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
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
		last_action = MOUSE;
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
					msg += display.getActive().getClass() == Patch.class ? "Unlock" : "Unlink";
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

		/*
		if (!dragging2) {
			// no real change
			display.getLayer().getParent().discardLastUndo();
		}
		*/
		if (dragging2) {
			display.getLayer().getParent().addUndoStep(display.getSelection().getTransformationsCopy());
		}

		int x_r = srcRect.x + (int)(me.getX() / magnification);
		int y_r = srcRect.y + (int)(me.getY() / magnification);

		this.xMouse = x_r;
		this.yMouse = y_r;

		Displayable active = display.getActive();

		switch (tool) {
			case ProjectToolbar.PENCIL:
				if (null != active && active.isVisible() && active.getClass() == Profile.class) {
					if (freehandProfile == null)
						return; // starting painting out of the DisplayCanvas boarder
					freehandProfile.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
					freehandProfile = null;
					//repaint(true);
					Selection selection = display.getSelection();
					selection.updateTransform(display.getActive());
					Display.repaint(display.getLayer(), selection.getBox(), Selection.PADDING); // repaints the navigator as well
					return;
				}
				break;
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
				// return; // replaced by #SET_ROI
		}

		final Roi roi = imp.getRoi();

		// check:
		if (display.isReadOnly()) return;

		if (tool >= ProjectToolbar.SELECT) {
			if (null != roi) imp.killRoi();
		} else return; // #SET_ROI

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
				Display.repaint(display.getLayer(), Selection.PADDING, box, !selection.isTransforming(), active.isLinked() || active.getClass() == Patch.class); // does not repaint the navigator
				break;
			case ProjectToolbar.PENCIL:
			case ProjectToolbar.PEN:
				active.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r); // active, not selection (Selection only handles transforms, not active's data editions)
				// update active's bounding box
				selection.updateTransform(active);
				box.add(selection.getBox());
				Display.repaint(display.getLayer(), Selection.PADDING, box, !selection.isTransforming(), active.isLinked() || active.getClass() == Patch.class); // does not repaint the navigator
				//if (!active.getClass().equals(AreaList.class)) Display.repaint(display.getLayer(), box, Selection.PADDING); // repaints the navigator as well
				// TODO: this last repaint call is unnecessary, if the box was properly repainted on mouse drag for Profile etc.
				//else 
				if (null != old_brush_box) {
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
			if (null != active && active.isVisible() && AreaList.class == active.getClass()) {
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
		case ProjectToolbar.SELECT:
		case ProjectToolbar.PENCIL:
		case ProjectToolbar.ALIGN:
			setCursor(defaultCursor);
			break;
		default: // selection tool

			if (roi != null && roi.getState() != roi.CONSTRUCTING && roi.isHandle(sx, sy) >= 0)
				setCursor(handCursor);
			else if (Prefs.usePointerCursor || (roi != null && roi.getState() != roi.CONSTRUCTING && roi.contains(ox, oy)))
				setCursor(defaultCursor);
			else
				setCursor(crosshairCursor);
			break;
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
		// there's no call to the non-existing ImageWindow
		if (magnification >= 32)
			return;
		double newMag = getHigherZoomLevel2(magnification);

		// zoom at point: correct mag drift
		int cx = getWidth() / 2;
		int cy = getHeight() / 2;
		int dx = (int)(((x - cx) * magnification) / newMag);
		int dy = (int)(((y - cy) * magnification) / newMag);
		x -= dx;
		y -= dy;

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
		final Rectangle rb = display.getCanvasPanel().getBounds(null);
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
		final Rectangle r = new Rectangle(x - w / 2, y - h / 2, w, h);
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
		//if (magnification <= 0.03125)
		//	return;
		double newMag = getLowerZoomLevel2(magnification);

		// zoom at point: correct mag drift
		int cx = getWidth() / 2;
		int cy = getHeight() / 2;
		int dx = (int)(((x - cx) * magnification) / newMag);
		int dy = (int)(((y - cy) * magnification) / newMag);
		x -= dx;
		y -= dy;

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

		// pack display only if dimensions overflow
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		Rectangle rd = display.getBounds();
		if (screen.width < rd.width || screen.height < rd.height) {
			display.pack();
			Point p = display.getLocation();
			if (p.x < 0 || p.x > screen.width) p.x = 0;
			if (p.y < 0 || p.y > screen.height) p.y = 0;
			display.setLocation(p);
		}

		setMagnification(newMag);
		display.repaintAll2(); // this repaint includes this canvas's repaint
		// as well, but also the navigator, etc.
		// repaint();
		display.updateInDatabase("srcRect");
	}

	/** The minimum amout of pixels allowed for width or height when zooming out. */
	static private final int MIN_DIMENSION = 10; // pixels

	/** Enable zooming out up to the point where the display becomes 10 pixels in width or height. */
	protected double getLowerZoomLevel2(final double currentMag) {
		// if it is 1/72 or lower, then:
		if (Math.abs(currentMag - 1/72.0) < 0.00000001 || currentMag < 1/72.0) { // lowest zoomLevel in ImageCanvas is 1/72.0
			// find nearest power of two under currentMag
			// start at level 7, which is 1/128
			int level = 7;
			double scale = currentMag;
			while (scale * srcRect.width > MIN_DIMENSION && scale * srcRect.height > MIN_DIMENSION) {
				scale = 1 / Math.pow(2, level);
				// if not equal and actually smaller, break:
				if (Math.abs(scale - currentMag) != 0.00000001 && scale < currentMag) break;
				level++;
			}
			return scale;
		} else {
			return ImageCanvas.getLowerZoomLevel(currentMag); 
		}
	}
	protected double getHigherZoomLevel2(final double currentMag) {
		// if it is not 1/72 and its lower, then:
		if (Math.abs(currentMag - 1/72.0) > 0.00000001 && currentMag < 1/72.0) { // lowest zoomLevel in ImageCanvas is 1/72.0
			// find nearest power of two above currentMag
			// start at level 14, which is 0.00006103515625 (0.006 %)
			int level = 14; // this value may be increased in the future
			double scale = currentMag;
			while (level >= 0) {
				scale = 1 / Math.pow(2, level);
				if (scale > currentMag) break;
				level--;
			}
			return scale;
		} else {
			return ImageCanvas.getHigherZoomLevel(currentMag);
		}
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

	private MouseMovedThread mouse_moved = new MouseMovedThread();

	private class MouseMovedThread extends Thread {
		private MouseEvent me = null;
		private boolean go = true;
		MouseMovedThread() {
			super("T2-mouseMoved");
			setDaemon(true);
			setPriority(Thread.NORM_PRIORITY);
			start();
		}
		void dispatch(MouseEvent me) {
			//Utils.log2("before");
			synchronized (this) {
				//Utils.log2("in");
				this.me = me;
				notify();
			}
		}
		void quit() {
			go = false;
			synchronized (this) { notify(); }
		}
		public void run() {
			while (go) {
				MouseEvent me = null;
				synchronized (this) {
					try { this.wait(); } catch (Exception e) {}
					me = this.me;
					this.me = null;
				}
				try { mouseMoved(me); } catch (Exception e) { IJError.print(e); }
			}
		}
		private void mouseMoved(MouseEvent me) {
			if (null == me) return;
			if (input_disabled || display.getSelection().isDragging()) return;

			final Displayable active = display.getActive();

			// only when no mouse buttons are down
			final int flags = me.getModifiers(); // override, the super fails for some reason
			if (0 == (flags & InputEvent.BUTTON1_MASK)
			/* && 0 == (flags & InputEvent.BUTTON2_MASK) */ // this is the alt key down ..
			 && 0 == (flags & InputEvent.BUTTON3_MASK)
			//if (me.getButton() == MouseEvent.NOBUTTON
			 && ProjectToolbar.getToolId() == ProjectToolbar.PEN && null != active && active.isVisible() && AreaList.class == active.getClass()) {
				// repaint area where the brush circle is
				int brushSize = ProjectToolbar.getBrushSize() +2; // +2 padding
				Rectangle r = new Rectangle( xMouse - brushSize/2,
							     yMouse - brushSize/2,
							     brushSize+1,
							     brushSize+1 );
				Rectangle copy = (Rectangle)r.clone(); 
				if (null != old_brush_box) r.add(old_brush_box);
				old_brush_box = copy;
				repaint(r, 1); // padding because of painting rounding which would live dirty trails
			}

			if (me.isShiftDown()) {
				// Print a comma-separated list of objects under the mouse pointer
				final Layer layer = DisplayCanvas.this.display.getLayer();
				final int x_p = offScreenX(me.getX()),
				          y_p = offScreenY(me.getY());
				final ArrayList<Displayable> al = new ArrayList(layer.getParent().findZDisplayables(layer, x_p, y_p, true));
				final ArrayList al2 = new ArrayList(layer.find(x_p, y_p, true));
				Collections.reverse(al2); // text labels first
				al.addAll(al2);
				if (0 == al.size()) {
					Utils.showStatus("", false);
					return;
				}
				final StringBuffer sb = new StringBuffer();
				final Project pr = layer.getProject();
				for (Displayable d : al) sb.append(pr.getShortMeaningfulTitle(d)).append(", ");
				sb.setLength(sb.length()-2);
				Utils.showStatus(sb.toString(), false);
			} else {
				// set xMouse, yMouse, and print pixel value
				DisplayCanvas.super.mouseMoved(me);
			}
		}
	}

	public void mouseMoved(final MouseEvent me) {
		mouse_moved.dispatch(me);
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
		RT.paint(null, update_graphics);
		display.updateInDatabase("srcRect");
		display.updateTitle();
		display.getNavigator().repaint(false);
	}

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
			r.height = (int) Math.ceil(r.height * magnification) + extra + extra;
			RT.paint(r, update_graphics);
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
	public void repaint(final HashSet hs) {
		if (null == hs) return;
		final Iterator it = hs.iterator();
		int count = 0;
		Rectangle r = new Rectangle();
		final Layer dl = display.getLayer();
		while (it.hasNext()) {
			final Displayable d = (Displayable) it.next();
			if (d.getLayer() == dl) {
				count++;
				r.add(d.getBoundingBox());
			}
		}
		if (count > 0) {
			//repaint(r.x, r.y, r.width, r.height);
			RT.paint(r, update_graphics);
		}
	}

	/**
	 * Repaint the given offscreen Rectangle after transforming its data on the fly to the
	 * srcRect and magnification of this DisplayCanvas. The Rectangle is not
	 * modified.
	 */
	public void repaint(final Rectangle r, final int extra) {
		if (null == r) {
			//Utils.log2("DisplayCanvas.repaint(Rectangle, int) warning: null r");
			RT.paint(null, update_graphics);
			return;
		}
		// repaint((int) ((r.x - srcRect.x) * magnification) - extra, (int) ((r.y - srcRect.y) * magnification) - extra, (int) Math .ceil(r.width * magnification) + extra + extra, (int) Math.ceil(r.height * magnification) + extra + extra);
		RT.paint(new Rectangle((int) ((r.x - srcRect.x) * magnification) - extra, (int) ((r.y - srcRect.y) * magnification) - extra, (int) Math.ceil(r.width * magnification) + extra + extra, (int) Math.ceil(r.height * magnification) + extra + extra), update_graphics);
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
	public void repaint(final boolean update_graphics) {
		this.update_graphics = update_graphics | this.update_graphics;
		RT.paint(null, update_graphics);
	}

	/** Overridden to multithread. This method is here basically to enable calls to the FakeImagePlus.draw from the HAND and other tools to repaint properly.*/
	public void repaint() {
		//Utils.log2("issuing thread");
		RT.paint(null, update_graphics);
	}

	/** Overridden to multithread. */
	/* // saved as unoveridden to make sure there are no infinite thread loops when calling super in buggy JVMs
	public void repaint(long ms, int x, int y, int width, int height) {
		RT.paint(new Rectangle(x, y, width, height), update_graphics);
	}
	*/

	/** Overridden to multithread. */
	public void repaint(int x, int y, int width, int height) {
		RT.paint(new Rectangle(x, y, width, height), update_graphics);
	}

	public void setUpdateGraphics(boolean b) {
		update_graphics = b;
	}

	/** Release offscreen images and stop threads. */
	public void flush() {
		// cleanup update graphics thread if any
		RT.quit();
		synchronized (offscreen_lock) {
			offscreen_lock.lock();

			offscreen = null;
			// reset for remaking if necessary TODO doesn't work in at least java 1.6 ?
			update_graphics = true;

			offscreen_lock.unlock();
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
			Utils.log("Display n_panels:" + ((java.util.HashMap)f.get(display)).size());
			Utils.log("Display displ.:  " + display.getLayer().getDisplayables().size());
			ke.consume();
			} catch (Exception e) {
				IJError.print(e);
			}
			return;
		}
		*/

		Displayable active = display.getActive();

		if (ProjectToolbar.getToolId() == ProjectToolbar.PENCIL && null != active && active.getClass() == Profile.class) {
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


		if (used) {
			ke.consume(); // otherwise ImageJ would use it!
			return;
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
		case '>':
		case '.': // select next Layer down
			display.nextLayer(ke.getModifiers());
			ke.consume();
			return;
		}

		if ((null == active || Patch.class == active.getClass()) && null != imp.getRoi()) {
			IJ.getInstance().keyPressed(ke);
			return;
		}

		// end here if display is read-only
		if (display.isReadOnly()) {
			ke.consume();
			display.repaintAll();
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
					// If it's the last step and the last action was not Z_KEY undo action, then store current:
					if (!layer.getParent().canRedo() && Z_KEY != last_action) {
						// catch the current
						display.getLayer().getParent().addUndoStep(display.getSelection().getTransformationsCopy());
						Utils.log2("Storing current at key pressed.");
					}
					last_action = Z_KEY;
					display.getLayer().getParent().undoOneStep();
					ke.consume();
				} else if (0 == (mod ^ Event.ALT_MASK)) {
					last_action = Z_KEY;
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
						invalidateVolatile();
						if (Math.abs(active.getAlpha() - 0.5f) > 0.001f) active.setAlpha(0.5f);
						else active.setAlpha(1.0f);
						display.setTransparencySlider(active.getAlpha());
						ke.consume();
					}
				} else {
					// ;)
					int kem = ke.getModifiers();
					if (0 != (kem & KeyEvent.SHIFT_MASK)
					 && 0 != (kem & KeyEvent.ALT_MASK)
					 && 0 != (kem & KeyEvent.CTRL_MASK)) {
						Utils.showMessage("A mathematician, like a painter or poet,\nis a maker of patterns.\nIf his patterns are more permanent than theirs,\nit is because they are made with ideas.");
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
			case KeyEvent.VK_H:
				handleHide(ke);
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
					layer.getParent().move(LayerSet.UP, active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_PAGE_DOWN: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().move(LayerSet.DOWN, active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_HOME: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().move(LayerSet.TOP, active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_END: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().move(LayerSet.BOTTOM, active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_V:
				if (0 == ke.getModifiers()) {
					if (null == active || active.getClass() == Patch.class) {
						// paste a new image
						ImagePlus clipboard = ImagePlus.getClipboard();
						if (null != clipboard) {
							ImagePlus imp = new ImagePlus(clipboard.getTitle() + "_" + System.currentTimeMillis(), clipboard.getProcessor().crop());
							Object info = clipboard.getProperty("Info");
							if (null != info) imp.setProperty("Info", (String)info);
							double x = srcRect.x + srcRect.width/2 - imp.getWidth()/2;
							double y = srcRect.y + srcRect.height/2 - imp.getHeight()/2;
							// save the image somewhere:
							Patch pa = display.getProject().getLoader().addNewImage(imp, x, y);
							display.getLayer().add(pa);
							ke.consume();
						} // TODO there isn't much ImageJ integration in the pasting. Can't paste to a selected image, for example.
					} else {
						// Each type may know how to paste data from the copy buffer into itself:
						active.keyPressed(ke);
						ke.consume();
					}
				}
				break;
			case KeyEvent.VK_C:
				if (null != active) {
					active.keyPressed(ke);
				}
				break;
			case KeyEvent.VK_P:
				if (0 == ke.getModifiers()) {
					final Project pro = display.getProject();
					if ("true".equals(pro.getProperty("no_color_cues"))) {
						// remove key
						pro.setProperty("no_color_cues", null);
					} else {
						pro.setProperty("no_color_cues", "true");
					}
					Display.repaint(display.getLayer().getParent());
					ke.consume();
				}
				break;
			case KeyEvent.VK_DELETE:
				if (0 == ke.getModifiers()) {
					display.getSelection().deleteAll();
				}
				break;
			case KeyEvent.VK_UP:
			case KeyEvent.VK_DOWN:
			case KeyEvent.VK_LEFT:
			case KeyEvent.VK_RIGHT:
				last_action = KEY_MOVE;
				// bleed to active:
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
			int brushSize = ProjectToolbar.setBrushSize((int)(5 * mwe.getWheelRotation() / magnification)); // the getWheelRotation provides the sign
			if (brushSize_old > brushSize) brushSize = brushSize_old; // for repainting purposes alnne
			int extra = (int)(5 / magnification);
			if (extra < 2) extra = 2;
			extra += 4;
			Rectangle r = new Rectangle((int)(mwe.getX() / magnification) + srcRect.x - brushSize/2 - extra, (int)(mwe.getY() / magnification) + srcRect.y - brushSize/2 - extra, brushSize+extra, brushSize+extra);
			this.repaint(r, 0);
		} else {
			// scroll layers
			if (1 == mwe.getWheelRotation()) display.nextLayer(modifiers);
			else display.previousLayer(modifiers);
		}
	}

	/** Minimum time an offscreen thread will run before it can be quit, in miliseconds. */
	static private final int min_time = 200;

	private final class OffscreenThread extends AbstractOffscreenThread {
		final private Layer layer;
		final private int g_width;
		final private int g_height;
		final private Displayable active;
		final private int c_alphas;
		final private Rectangle clipRect;
		//public final int label = counter.getAndIncrement();
		OffscreenThread(final Rectangle clipRect, final Layer layer, final int g_width, final int g_height, final Displayable active, final int c_alphas) {
			super("T2-Canvas-Offscreen");
			this.clipRect = clipRect;
			this.layer = layer;
			this.g_width = g_width;
			this.g_height = g_height;
			this.active = active;
			this.c_alphas = c_alphas;
			setPriority(Thread.NORM_PRIORITY);
			start();
		}

		public final boolean canQuit() {
			final long now = System.currentTimeMillis();
			if (now - this.start > min_time && now - last_paint < min_time) {
				//Utils.log2(label + " off canQuit yes");
				return true;
			}
			//Utils.log2(label + " off canQuit NO");
			return false;
		}

		public void run() {
			try {
				if (quit && canQuit()) return;

				final Loader loader = layer.getProject().getLoader();
				// flag Loader to do massive flushing if needed
				loader.setMassiveMode(true);

				// ALMOST, but not always perfect //if (null != clipRect) g.setClip(clipRect);

				// prepare the canvas for the srcRect and magnification
				final AffineTransform atc = new AffineTransform();
				atc.scale(magnification, magnification);
				atc.translate(-srcRect.x, -srcRect.y);

				// Area to which each Patch will subtract from
				//final Area background =  new Area(new Rectangle(0, 0, g_width, g_height));
				// bring the area to Layer space
				//background.transform(atc.createInverse());

				// the non-srcRect areas, in offscreen coords
				final Rectangle r1 = new Rectangle(srcRect.x + srcRect.width, srcRect.y, (int)(g_width / magnification) - srcRect.width, (int)(g_height / magnification));
				final Rectangle r2 = new Rectangle(srcRect.x, srcRect.y + srcRect.height, srcRect.width, (int)(g_height / magnification) - srcRect.height);


				final ArrayList<Displayable> al_top = new ArrayList<Displayable>();
				boolean top = false;

				final ArrayList<Displayable> al_paint = new ArrayList<Displayable>();

				final ArrayList<Patch> al_patches = new ArrayList<Patch>();

				// start
				//final ArrayList al = layer.getDisplayables();
				layer.getParent().checkBuckets();
				layer.checkBuckets();
				final Iterator<Displayable> ital = layer.find(srcRect, true).iterator();
				final Collection<Displayable> al_zdispl = layer.getParent().findZDisplayables(layer, srcRect, true);
				final Iterator<Displayable> itzd = al_zdispl.iterator();

				// Assumes the Layer has its objects in order:
				// 1 - Patches
				// 2 - Profiles, Balls
				// 3 - Pipes and ZDisplayables (from the parent LayerSet)
				// 4 - DLabels

				Displayable tmp = null;

				int i = 0;
				while (ital.hasNext()) {
					if (quit && 0 == i % 10 && canQuit()) {
						return;
					}
					final Displayable d = ital.next();
					final Class c = d.getClass();
					if (DLabel.class == c || LayerSet.class == c) {
						tmp = d; // since ital.next() has moved forward already
						break;
					}
					if (Patch.class == c) {
						al_paint.add(d);
						al_patches.add((Patch)d);
					} else {
						if (!top && d == active) top = true; // no Patch on al_top ever
						if (top) al_top.add(d);
						else al_paint.add(d);
					}
					i++;
				}

				// preload concurrently as many as possible
				Loader.preload(al_patches, magnification, false); // must be false; a 'true' would incur in an infinite loop.

				// paint the ZDisplayables here, before the labels and LayerSets, if any
				while (itzd.hasNext()) {
					if (quit && 0 == i % 10 && canQuit()) {
						Loader.quitPreloading(al_patches, magnification);
						return;
					}
					final Displayable zd = itzd.next();
					if (zd == active) top = true;
					else if (top) al_top.add(zd);
					else al_paint.add(zd);
					i++;
				}
				// paint LayerSet and DLabel objects!
				if (null != tmp) {
					if (tmp == active) top = true;
					else if (top) al_top.add(tmp);
					else al_paint.add(tmp);
				}
				while (ital.hasNext()) {
					if (quit && 0 == i % 10 && canQuit()) {
						Loader.quitPreloading(al_patches, magnification);
						return;
					}
					final Displayable d = ital.next();
					if (d == active) top = true;
					else if (top) al_top.add(d);
					else al_paint.add(d);
					i++;
				}

				if (quit && canQuit()) { // TODO: NO NEED to quitPreloading those patches that are actually going to be immediately need in the call that is quitting this thread.
					Loader.quitPreloading(al_patches, magnification);
					return;
				}

				// create new graphics
				layer.getProject().getLoader().releaseToFit(g_width * g_height * 4 + 1024);
				final BufferedImage target = getGraphicsConfiguration().createCompatibleImage(g_width, g_height, Transparency.TRANSLUCENT); // creates a BufferedImage.TYPE_INT_ARGB image in my T60p ATI FireGL laptop
				final Graphics2D g = (Graphics2D)target.getGraphics();

				g.setTransform(atc); //at_original);

				//setRenderingHints(g);
				// always a stroke of 1.0, regardless of magnification; the stroke below corrects for that
				g.setStroke(stroke);



				// Testing: removed Area.subtract, now need to fill int background
				g.setColor(Color.black);
				g.fillRect(0, 0, g_width - r1.x, g_height - r2.y);


				// paint:
				//  1 - background
				//  2 - images and anything else not on al_top
				//  3 - non-srcRect areas

				/*
				if (!background.isEmpty()) {
					// subtract non-srcRect areas
					background.subtract(new Area(r1));
					background.subtract(new Area(r2));
					// paint background
					g.setColor(Color.black);
					g.fill(background);
				}
				*/

				//Utils.log2("offscreen painting: " + al_paint.size());

				i = 0;
				for (Displayable d : al_paint) {
					if (quit && canQuit()) {
						Loader.quitPreloading(al_patches, magnification);
						g.dispose();
						target.flush();
						return;
					}
					d.prePaint(g, magnification, d == active, c_alphas, layer);
					i++;
				}

				// finally, paint non-srcRect areas
				if (r1.width > 0 || r1.height > 0 || r2.width > 0 || r2.height > 0) {
					g.setColor(Color.gray);
					g.setClip(r1);
					g.fill(r1);
					g.setClip(r2);
					g.fill(r2);
				}

				// Not needed.
				//Thread.yield();

				if (quit && canQuit()) {
					g.dispose();
					target.flush();
					return;
				}

				synchronized (offscreen_lock) {
					offscreen_lock.lock();
					try {
						// only on success:
						update_graphics = false;
						loader.setMassiveMode(false);
						if (null != offscreen) offscreen.flush();
						offscreen = target;
						invalidateVolatile();
						DisplayCanvas.this.al_top = al_top;

					} catch (Exception e) {
						e.printStackTrace();
					}

					offscreen_lock.unlock();
				}

				//Utils.log2(label + " called RT.paintFromOff");
				// Repaint!
				RT.paintFromOff(clipRect, this.start);


			} catch (OutOfMemoryError oome) {
				// so OutOfMemoryError won't generate locks
				IJError.print(oome);
			} catch (Exception e) {
				IJError.print(e);
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

	private void handleHide(final KeyEvent ke) {
		if (ke.isAltDown() && !ke.isShiftDown()) {
			// show hidden
			display.getLayer().getParent().setAllVisible(false);
			//Display.repaint(display.getLayer());
			Display.update(display.getLayer());
			ke.consume();
			return;
		}
		if (ke.isShiftDown()) {
			// hide deselected
			display.hideDeselected(ke.isAltDown());
			ke.consume();
			return;
		}
		// else, hide selected
		display.getSelection().setVisible(false);
		Display.update(display.getLayer());
		ke.consume();
	}

	private class ScreenImage {
		long layer_id = -1;
		Rectangle srcRect = null;
		double mag = 0;
		Image awt = null;
		final boolean equals(final long layer_id, final Rectangle srcRect, final double mag) {
			return layer_id == this.layer_id && mag == this.mag && srcRect.equals(this.srcRect);
		}
		/** Flushes the old awt if any. */
		final void set(final Image awt, final long layer_id, final Rectangle srcRect, final double mag) {
			this.layer_id = layer_id;
			this.srcRect = (Rectangle)srcRect.clone();
			if (null != awt && awt != this.awt) this.awt.flush();
			this.awt = awt;
			this.mag = mag;
		}
		final void flush() {
			this.layer_id = -1;
			this.srcRect = null;
			if (null != this.awt) this.awt.flush();
			this.awt = null;
			this.mag = 0;
		}
		final boolean isFlushed() { return -1 == layer_id; }
	}

	/*** Stores and manages a listing of max 10 recently made offscreen images. The images are actually stored in the loader's cache; this class simply assigns the layer_id to each.  */
	private class ShallowCache {
		final ScreenImage[] sim = new ScreenImage[75];
		int oldest = 0;
		void add(final Image awt, final Layer layer, final Rectangle srcRect, final double mag) {
			final long layer_id = layer.getId();
			// Only one awt per layer_id
			int i = 0;
			for (;i<sim.length; i++) {
				if (null == sim[i]) { sim[i] = new ScreenImage(); break; }
				if (sim[i].isFlushed()) break;
				if (sim[i].layer_id == layer_id) {
					sim[i].set(awt, layer_id, srcRect, mag);
					return;
				}
			}
			// ok so set the given image at 'i'
			int k = i;
			if (sim.length == i) {
				// no space, pop oldest
				// So now oldest is next to oldest;
				oldest++;
				if (oldest == sim.length) k = oldest = 0;
			}
			// set
			sim[k].set(awt, layer_id, srcRect, mag);
			layer.getProject().getLoader().cacheOffscreen(layer, awt);
		}
		Image get(final Layer layer, final Rectangle srcRect, final double mag) {
			final long layer_id = layer.getId();
			for (int i=0; i<sim.length; i++) {
				if (null == sim[i]) return null;
				if (sim[i].equals(layer_id, srcRect, mag)) {
					Image awt = layer.getProject().getLoader().getCached(layer_id, 0);
					if (null == awt) sim[i].flush(); // got lost
					return awt;
				}
			}
			return null;
		}
		void flush(Layer layer) {
			final long layer_id = layer.getId();
			for (int i=0; i<sim.length; i++) {
				if (sim[i].layer_id == layer_id) {
					layer.getProject().getLoader().decacheAWT(layer_id);
					sim[i].flush();
					break; // only one per layer_id
				}
			}
		}
	}
}
