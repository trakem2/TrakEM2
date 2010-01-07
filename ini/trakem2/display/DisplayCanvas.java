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

import ij.*;
import ij.gui.*;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
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

import ini.trakem2.display.graphics.*;
import ini.trakem2.plugin.TPlugIn;

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
				// Active has to be painted wherever it is, within al_top
				//if (null != active && active.getClass() != Patch.class && !active.isOutOfRepaintingClip(magnification, srcRect, clipRect)) active.paint(g, magnification, true, c_alphas, active_layer);
				if (null != top) {
					final Rectangle tmp = null != clipRect ? new Rectangle() : null;
					final Rectangle clip = null != clipRect ? new Rectangle((int)(clipRect.x * magnification) - srcRect.x, (int)(clipRect.y * magnification) - srcRect.y, (int)(clipRect.width * magnification), (int)(clipRect.height * magnification)) : null;
					for (int i=0; i<top.length; i++) {
						if (null != clipRect && !top[i].getBoundingBox(tmp).intersects(clip)) continue;
						top[i].paint(g, srcRect, magnification, top[i] == active, c_alphas, active_layer);
					}
				}
			}

			display.getMode().getGraphicsSource().paintOnTop(g, display, srcRect, magnification);

			if (null != active_layer.getOverlay2())
				active_layer.getOverlay2().paint(g, srcRect, magnification);
			if (null != active_layer.getParent().getOverlay2())
				active_layer.getParent().getOverlay2().paint(g, srcRect, magnification);

			if (null != display.gridoverlay) display.gridoverlay.paint(g);

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
		//no longer needed//display.pack(); // TODO should be run via invokeLater ... need to check many potential locks of invokeLater calling each other.
	}

	/** Does not repaint. */
	public void setDimensions(double width, double height) {
		this.imageWidth = (int)Math.ceil(width);
		this.imageHeight = (int)Math.ceil(height);
		((FakeImagePlus)imp).setDimensions(imageWidth, imageHeight);
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
	private final AbstractRepaintThread RT = new AbstractRepaintThread(this, "T2-Canvas-Repainter", new OffscreenThread()) {
		protected void handleUpdateGraphics(final Component target, final Rectangle clipRect) {
			this.off.setProperties(new RepaintProperties(clipRect, display.getLayer(), target.getWidth(), target.getHeight(), srcRect, magnification, display.getActive(), display.getDisplayChannelAlphas(), display.getMode().getGraphicsSource()));
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
		display.getMode().magnificationUpdated(srcRect, mag);
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

			// ensure proper positioning
			g.translate(0, 0); // ints!

			final Rectangle clipRect = g.getClipBounds();

			final Displayable active = display.getActive();
			final int c_alphas = display.getDisplayChannelAlphas();

			final Layer active_layer = display.getLayer();

			final Graphics2D g2d = (Graphics2D)g;

			Displayable[] di = null;

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

					//Utils.log2("al_top.size(): " + di.length);

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

			g2d.setStroke(this.stroke);

			// debug buckets
			//if (null != display.getLayer().root) display.getLayer().root.paint(g2d, srcRect, magnification, Color.red);
			//if (null != display.getLayer().getParent().root) display.getLayer().getParent().root.paint(g2d, srcRect, magnification, Color.blue);


			// reset to identity
			g2d.setTransform(new AffineTransform());
			// reset to 1.0 thickness
			g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

			// paint brush outline for AreaList, or fast-marching area
			if (mouse_in && null != active && AreaContainer.class.isInstance(active)) {
				switch (ProjectToolbar.getToolId()) {
					case ProjectToolbar.BRUSH:
						int brushSize = ProjectToolbar.getBrushSize();
						g.setColor(active.getColor());
						g.drawOval((int)((xMouse -srcRect.x -brushSize/2)*magnification), (int)((yMouse - srcRect.y -brushSize/2)*magnification), (int)(brushSize * magnification), (int)(brushSize * magnification));
						break;
					case ProjectToolbar.PENCIL:
						Composite co = g2d.getComposite();
						g2d.setXORMode(active.getColor());
						if (IJ.isWindows()) g2d.setColor(active.getColor());
						g2d.drawRect((int)((xMouse -srcRect.x -Segmentation.fmp.width/2)  * magnification),
							     (int)((yMouse -srcRect.y -Segmentation.fmp.height/2) * magnification),
							     (int)(Segmentation.fmp.width  * magnification),
							     (int)(Segmentation.fmp.height * magnification)); 
						g2d.setComposite(co); // undo XOR mode
						break;
				}
			}


			final Roi roi = imp.getRoi();
			if (null != roi) {
				roi.draw(g);
			}

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
			// wait until painting is done
		}
	}

	/** Paints a handle on the screen coords. Adapted from ij.gui.Roi class. */
	static public void drawHandle(final Graphics g, final int x, final int y, final double magnification) {
		final int width5 = (int)(5 / magnification + 0.5);
		final int width3 = (int)(3 / magnification + 0.5);
		final int corr2 = (int)(2 / magnification + 0.5);
		final int corr1 = (int)(1 / magnification + 0.5);
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

	static protected BasicStroke DEFAULT_STROKE = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	static protected AffineTransform DEFAULT_AFFINE = new AffineTransform();

	static public void drawHandle(Graphics2D g, double x, double y, Rectangle srcRect, double magnification) {
		AffineTransform original = g.getTransform();
		g.setTransform(DEFAULT_AFFINE);
		Stroke st = g.getStroke();
		g.setStroke(DEFAULT_STROKE);

		g.setColor(Color.black);
		g.fillRect((int) ((x - srcRect.x) * magnification) - 1, (int) ((y - srcRect.y) * magnification) - 1, 3, 3);
		g.setColor(Color.white);
		g.drawRect((int) ((x - srcRect.x) * magnification) - 2, (int) ((y - srcRect.y) * magnification) - 2, 5, 5);

		g.setStroke(st);
		g.setTransform(original);
	}

	protected void setDrawingColor(int ox, int oy, boolean setBackground) {
		super.setDrawingColor(ox, oy, setBackground);
	}

	/** As offscreen. */
	private int x_p, y_p, x_d, y_d, x_d_old, y_d_old;

	private boolean popup = false;

	private boolean locked = false; // TODO temporary!

	private boolean beyond_srcRect = false;

	private int tmp_tool = -1;

	/** In world coordinates. */
	protected Point last_popup = null;

	protected Point consumeLastPopupPoint() {
		Point p = last_popup;
		last_popup = null;
		return p;
	}

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
		if (Utils.isPopupTrigger(me)) {
			popup = true;
			last_popup = new Point(x_p, y_p);
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
			return;
		}

		if (input_disabled) {
			input_disabled2 = true;
			Utils.showMessage("Please wait while completing the task.\nOnly the glass and hand tool are enabled.");
			return; // only zoom and pan are allowed
		}

		Displayable active = display.getActive();

		if (isTransforming() && ProjectToolbar.SELECT != tool) {
			Utils.logAll("Notice: the 'Select' tool is not active!\n Activate the 'Select' tool to operate transformation modes.");
		}

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
			if (!isTransforming()) {
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
			}
			return;
		}

		// SPECIFIC for SELECT and above tools

		// no ROIs allowed past this point
		if (tool >= ProjectToolbar.SELECT) imp.killRoi();
		else return;

		Selection selection = display.getSelection();
		if (isTransforming()) {
			box = display.getMode().getRepaintBounds();
			display.getMode().mousePressed(me, x_p, y_p, magnification);
			return;
		}
		// select or deselect another active Displayable, or add it to the selection group:
		if (ProjectToolbar.SELECT == tool) {
			display.choose(me.getX(), me.getY(), x_p, y_p, me.isShiftDown(), null);
		}
		active = display.getActive();
		selection = display.getSelection();

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
			box = display.getMode().getRepaintBounds();
			display.getMode().mousePressed(me, x_p, y_p, magnification);
			break;
		default: // the PEN and PENCIL tools, and any other custom tool
			display.getLayerSet().addPreDataEditStep(active);
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
					display.getMode().mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
					box2 = display.getMode().getRepaintBounds();
					box.add(box2);
					// repaint all Displays (where it was and where it is now, hence the sum of both boxes):
					Display.repaint(display.getLayer(), Selection.PADDING, box, false, active.isLinked() || active.getClass() == Patch.class);
					// box for next mouse dragged iteration
					box = box2;
					break;
				default:
					active.mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
					// the line above must repaint on its own
					break;
				}
			} else {
				beyond_srcRect = true;
				Utils.log("DisplayCanvas.mouseDragged: preventing drag beyond layer limits.");
			}
		} else if (display.getMode() instanceof ManualAlignMode) {
			if (display.getLayer().contains(x_d, y_d, 1)) {
				if (tool >= ProjectToolbar.SELECT) {
					display.getMode().mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
				}
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
			display.updateFrameTitle();
			return;
		case Toolbar.HAND:
			display.updateInDatabase("srcRect");
			if (-1 != tmp_tool) {
				ProjectToolbar.setTool(tmp_tool);
				tmp_tool = -1;
			}
			if (!dragging2) repaint(true); // TEMPORARY just to allow fixing bad screen when simply cliking with the hand
			display.getMode().srcRectUpdated(srcRect, magnification);
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

		int x_r = srcRect.x + (int)(me.getX() / magnification);
		int y_r = srcRect.y + (int)(me.getY() / magnification);

		/*
		if (beyond_srcRect) {
			// Artificial release on the last dragged point
			x_r = x_d;
			y_r = y_d;
		}
		*/

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
			display.getMode().mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
			box.add(display.getMode().getRepaintBounds());
			Display.repaint(display.getLayer(), box, Selection.PADDING); // repaints the navigator as well
			Display.snap((Patch)active);
			// reset:
			snapping = false;
			return;
		}

		if (null != active && active.isVisible()) {
			switch(tool) {
			case ProjectToolbar.SELECT:
				display.getMode().mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
				box.add(display.getMode().getRepaintBounds());
				Display.repaint(display.getLayer(), Selection.PADDING, box, !isTransforming(), active.isLinked() || active.getClass() == Patch.class); // does not repaint the navigator
				break;
			case ProjectToolbar.PENCIL:
			case ProjectToolbar.PEN:
			case ProjectToolbar.BRUSH:
				active.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r); // active, not selection (Selection only handles transforms, not active's data editions)
				// update active's bounding box
				selection.updateTransform(active);
				box.add(selection.getBox());
				Display.repaint(display.getLayer(), Selection.PADDING, box, !isTransforming(), active.isLinked() || active.getClass() == Patch.class); // does not repaint the navigator
				//if (!active.getClass().equals(AreaList.class)) Display.repaint(display.getLayer(), box, Selection.PADDING); // repaints the navigator as well
				// TODO: this last repaint call is unnecessary, if the box was properly repainted on mouse drag for Profile etc.
				//else 
				if (null != old_brush_box) {
					repaint(old_brush_box, 0, false);
					old_brush_box = null; // from mouseMoved
				}
				// The current state:
				display.getLayerSet().addDataEditStep(active);
				break;
			}
		}
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
		if (ProjectToolbar.getToolId() == ProjectToolbar.BRUSH) {
			Displayable active = display.getActive();
			if (null != active && active.isVisible() && AreaContainer.class.isInstance(active)) {
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
		display.getMode().srcRectUpdated(srcRect, magnification);
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

		// Adjust the srcRect to the new dimensions
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

		//display.pack();

		setMagnification(newMag);
		display.updateInDatabase("srcRect");
		display.repaintAll2(); // this repaint includes this canvas's repaint as well, but also the navigator, etc. // repaint();
	}

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

		if (imageWidth * newMag > dstWidth || imageHeight * newMag > dstHeight) {
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
			// Shrink srcRect, but NOT the dstWidth,dstHeight of the canvas, which remain the same:
			srcRect = new Rectangle(0, 0, imageWidth, imageHeight);
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
			if (input_disabled || display.getMode().isDragging()) return;

			xMouse = (int)(me.getX() / magnification) + srcRect.x;
			yMouse = (int)(me.getY() / magnification) + srcRect.y;
			final Displayable active = display.getActive();

			// only when no mouse buttons are down
			final int flags = me.getModifiers(); // override, the super fails for some reason
			if (0 == (flags & InputEvent.BUTTON1_MASK)
			/* && 0 == (flags & InputEvent.BUTTON2_MASK) */ // this is the alt key down ..
			 && 0 == (flags & InputEvent.BUTTON3_MASK)
			//if (me.getButton() == MouseEvent.NOBUTTON
			 && null != active && active.isVisible() && AreaContainer.class.isInstance(active)) {
				final int tool = ProjectToolbar.getToolId();
				Rectangle r = null;
				if (ProjectToolbar.BRUSH == tool) {
					// repaint area where the brush circle is
					int brushSize = ProjectToolbar.getBrushSize() +2; // +2 padding
					r = new Rectangle( xMouse - brushSize/2,
							   yMouse - brushSize/2,
							   brushSize+1,
							   brushSize+1 );
				} else if (ProjectToolbar.PENCIL == tool) {
					// repaint area where the fast-marching box is
					r = new Rectangle( xMouse - Segmentation.fmp.width/2 - 2,
							   yMouse - Segmentation.fmp.height/2 - 2,
							   Segmentation.fmp.width + 4,
							   Segmentation.fmp.height + 4 );
				}
				if (null != r) {
					Rectangle copy = (Rectangle)r.clone(); 
					if (null != old_brush_box) r.add(old_brush_box);
					old_brush_box = copy;
					repaint(r, 1); // padding because of painting rounding which would live dirty trails
				}
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
				final StringBuilder sb = new StringBuilder();
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
	
	public boolean isDragging() {
		return display.getMode().isDragging();
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

	/** Center the srcRect around the given object(s) bounding box, zooming if necessary,
	 *  so that the given r becomes a rectangle centered in the srcRect and zoomed out by a factor of 2. */
	public void showCentered(Rectangle r) {
		// multiply bounding box dimensions by two
		r.x -= r.width / 2;
		r.y -= r.height / 2;
		r.width += r.width;
		r.height += r.height;
		// compute target magnification
		double magn = getWidth() / (double)r.width;
		center(r, magn);
	}

	/** Show the given r as the srcRect (or as much of it as possible) at the given magnification. */
	public void center(Rectangle r, double magn) {
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
		display.updateFrameTitle();
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
		RT.paint(null, this.update_graphics);
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

	public boolean applyTransform() {
		boolean b = display.getMode().apply();
		if (b) {
			display.setMode(new DefaultMode(display));
			repaint(true);
		}
		return b;
	}

	public boolean isTransforming() {
		// TODO this may have to change if modes start getting used for a task other than transformation.
		// Perhaps "isTransforming" will have to broaden its meaning to "isNotDefaultMode"
		return display.getMode().getClass() != DefaultMode.class;
	}

	public void cancelTransform() {
		Selection selection = display.getSelection();
		display.getMode().cancel();
		display.setMode(new DefaultMode(display));
		repaint(true);
	}

	public void keyPressed(KeyEvent ke) {

		Displayable active = display.getActive();

		if (null != freehandProfile
			&& ProjectToolbar.getToolId() == ProjectToolbar.PENCIL
			&& ke.getKeyCode() == KeyEvent.VK_ESCAPE
			&& null != freehandProfile)
		{
			freehandProfile.abort();
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

		if (null == active && null != imp.getRoi() && KeyEvent.VK_A != keyCode) { // control+a and a roi should select under roi
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
			if (isTransforming()) {
				applyTransform();
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

		final int mod = ke.getModifiers();

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
				// UNDO: shift+z or ctrl+z
				if (0 == (mod ^ Event.SHIFT_MASK) || 0 == (mod ^ Utils.getControlModifier())) {
					// If it's the last step and the last action was not Z_KEY undo action, then store current:
					Bureaucrat.createAndStart(new Worker.Task("Undo") { public void exec() {
						if (isTransforming()) display.getMode().undoOneStep();
						else display.getLayerSet().undoOneStep();
						Display.repaint(display.getLayerSet());
					}}, display.getProject());
					ke.consume();
				// REDO: alt+z or ctrl+shift+z
				} else if (0 == (mod ^ Event.ALT_MASK) || 0 == (mod ^ (Event.SHIFT_MASK | Utils.getControlModifier())) ) {
					Bureaucrat.createAndStart(new Worker.Task("Redo") { public void exec() {
						if (isTransforming()) display.getMode().redoOneStep();
						else display.getLayerSet().redoOneStep();
						Display.repaint(display.getLayerSet());
					}}, display.getProject());
					ke.consume();
				}
				// else, the 'z' command restores the image using ImageJ internal undo
				break;
			case KeyEvent.VK_T:
				if (null != active && !isTransforming()) {
					if (0 == ke.getModifiers()) {
						display.setMode(new AffineTransformMode(display));
					} else if (Event.SHIFT_MASK == ke.getModifiers()) {
						for (final Displayable d : display.getSelection().getSelected()) {
							if (d.isLinked()) {
								Utils.showMessage("Can't enter manual non-linear transformation mode:\nat least one image is linked.");
								return;
							}
						}
						display.setMode(new NonLinearTransformMode(display));
					}
					ke.consume();
				}
				// else, let ImageJ grab the ROI into the Manager, if any
				break;
			case KeyEvent.VK_A:
				if (0 == (ke.getModifiers() ^ Utils.getControlModifier())) {
					Roi roi = getFakeImagePlus().getRoi();
					if (null != roi) display.getSelection().selectAll(roi, true);
					else display.getSelection().selectAllVisible();
					Display.repaint(display.getLayer(), display.getSelection().getBox(), 0);
					ke.consume();
					break; // INSIDE the 'if' block, so that it can bleed to the default block which forwards to active!
				} else if (null != active) {
					active.keyPressed(ke);
					if (ke.isConsumed()) break;
					// TODO this is just a hack really. Should just fall back to default switch option.
					// The whole keyPressed method needs revision: should not break from it when not using the key.
				}
			case KeyEvent.VK_ESCAPE: // cancel transformation
				if (isTransforming()) cancelTransform();
				else {
					display.select(null); // deselect
					// repaint out the brush if present
					if (ProjectToolbar.BRUSH == ProjectToolbar.getToolId()) {
						repaint(old_brush_box, 0);
					}
				}
				ke.consume();
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
						Utils.showMessage("A mathematician, like a painter or poet,\nis a maker of patterns.\nIf his patterns are more permanent than theirs,\nit is because they are made with ideas\n \nG. H. Hardy.");
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
			case KeyEvent.VK_B:
				if (0 == ke.getModifiers() && null != active && active.getClass() == Profile.class) {
					display.duplicateLinkAndSendTo(active, 0, active.getLayer().getParent().previous(layer));
					ke.consume();
				}
				break;
			case KeyEvent.VK_N:
				if (0 == ke.getModifiers() && null != active && active.getClass() == Profile.class) {
					display.duplicateLinkAndSendTo(active, 1, active.getLayer().getParent().next(layer));
					ke.consume();
				}
				break;
			case KeyEvent.VK_F1:
			case KeyEvent.VK_F2:
			case KeyEvent.VK_F3:
			case KeyEvent.VK_F4:
			case KeyEvent.VK_F5:
			case KeyEvent.VK_F6:
			case KeyEvent.VK_F7:
			case KeyEvent.VK_F8:
			case KeyEvent.VK_F9:
			case KeyEvent.VK_F10:
			case KeyEvent.VK_F11:
			case KeyEvent.VK_F12:
				ProjectToolbar.keyPressed(ke);
				break;
			case KeyEvent.VK_1:
			case KeyEvent.VK_2:
			case KeyEvent.VK_3:
			case KeyEvent.VK_4:
			case KeyEvent.VK_5:
			case KeyEvent.VK_6:
			case KeyEvent.VK_7:
			case KeyEvent.VK_8:
			case KeyEvent.VK_9:
				// run a plugin, if any
				if (null != Utils.launchTPlugIn(ke, "Display", display.getProject(), display.getActive())) {
					ke.consume();
					break;
				}
				// bleed to active
			case KeyEvent.VK_UP:
			case KeyEvent.VK_DOWN:
			case KeyEvent.VK_LEFT:
			case KeyEvent.VK_RIGHT:
				// bleed to active:
			default:
				// forward event to active
				if (null != active) {
					active.keyPressed(ke);
					if (ke.isConsumed()) {
						repaint(display.getMode().getRepaintBounds(), Selection.PADDING + 2); // optimization
					}
				}
		}

		if ( !(keyCode == KeyEvent.VK_UNDEFINED || keyChar == KeyEvent.CHAR_UNDEFINED) && !ke.isConsumed() && null != active && active instanceof Patch) {
			// TODO should allow forwarding for all, not just Patch
			// forward to ImageJ for a final try
			IJ.getInstance().keyPressed(ke);
			repaint(active, 5);
			ke.consume();
		}
		//Utils.log2("keyCode, keyChar: " + keyCode + ", " + keyChar + " ref: " + KeyEvent.VK_UNDEFINED + ", " + KeyEvent.CHAR_UNDEFINED);
	}

	public void keyTyped(KeyEvent ke) {}

	public void keyReleased(KeyEvent ke) {}

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
		final int modifiers = mwe.getModifiers();
		final int rotation = mwe.getWheelRotation();
		if (0 == (modifiers ^ Utils.getControlModifier())) {
			// scroll zoom under pointer
			int x = mwe.getX();
			int y = mwe.getY();
			if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
				x = getWidth()/2;
				y = getHeight()/2;
			}
			if (rotation > 0) {
				zoomOut(x, y);
			} else {
				zoomIn(x, y);
			}
		} else if (0 == (modifiers ^ InputEvent.SHIFT_MASK) && null != display.getActive() && AreaContainer.class.isInstance(display.getActive())) {
			final int tool = ProjectToolbar.getToolId();
			final int sign = rotation > 0 ? 1 : -1;
			if (ProjectToolbar.BRUSH == tool) {
				int brushSize_old = ProjectToolbar.getBrushSize();
				// resize brush for AreaList/AreaTree painting
				int brushSize = ProjectToolbar.setBrushSize((int)(5 * sign / magnification)); // the getWheelRotation provides the sign
				if (brushSize_old > brushSize) brushSize = brushSize_old; // for repainting purposes alone
				int extra = (int)(10 / magnification);
				if (extra < 2) extra = 2;
				extra += 4; // for good measure
				this.repaint(new Rectangle((int)(mwe.getX() / magnification) + srcRect.x - brushSize/2 - extra, (int)(mwe.getY() / magnification) + srcRect.y - brushSize/2 - extra, brushSize+extra, brushSize+extra), 0);
			} else if (ProjectToolbar.PENCIL == tool) {
				// resize area to consider for fast-marching
				int w = Segmentation.fmp.width;
				int h = Segmentation.fmp.height;
				Segmentation.fmp.resizeArea(sign, magnification);
				w = Math.max(w, Segmentation.fmp.width);
				h = Math.max(h, Segmentation.fmp.height);
				this.repaint(new Rectangle((int)(mwe.getX() / magnification) + srcRect.x - w/2 + 2,
							   (int)(mwe.getY() / magnification) + srcRect.y - h/2 + 2,
							   w + 4, h + 4), 0);
			}
		} else if (0 == modifiers) {
			// scroll layers
			if (rotation > 0) display.nextLayer(modifiers);
			else display.previousLayer(modifiers);
		} else if (null != display.getActive()) {
			// forward to active
			display.getActive().mouseWheelMoved(mwe);
		}
	}

	protected class RepaintProperties implements AbstractOffscreenThread.RepaintProperties {
		final private Layer layer;
		final private int g_width;
		final private int g_height;
		final private Rectangle srcRect;
		final private double magnification;
		final private Displayable active;
		final private int c_alphas;
		final private Rectangle clipRect;
		final private int mode;
		final private HashMap<Color,Layer> hm;
		final private ArrayList<LayerPanel> blending_list;
		final private GraphicsSource graphics_source;

		RepaintProperties(final Rectangle clipRect, final Layer layer, final int g_width, final int g_height, final Rectangle srcRect, final double magnification, final Displayable active, final int c_alphas, final GraphicsSource graphics_source) {
			this.clipRect = clipRect;
			this.layer = layer;
			this.g_width = g_width;
			this.g_height = g_height;
			this.srcRect = srcRect;
			this.magnification = magnification;
			this.active = active;
			this.c_alphas = c_alphas;

			// query the display for repainting mode
			this.hm = new HashMap<Color,Layer>();
			this.blending_list = new ArrayList<LayerPanel>();
			this.mode = display.getPaintMode(hm, blending_list);
			this.graphics_source = graphics_source;
		}
	}

	private final class OffscreenThread extends AbstractOffscreenThread {

		OffscreenThread() {
			super("T2-Canvas-Offscreen");
		}

		public void paint() {
			final Layer layer;
			final int g_width;
			final int g_height;
			final Rectangle srcRect;
			final double magnification;
			final Displayable active;
			final int c_alphas;
			final Rectangle clipRect;
			final Loader loader;
			final HashMap<Color,Layer> hm;
			final ArrayList<LayerPanel> blending_list;
			final int mode;
			final GraphicsSource graphics_source;

			synchronized (this) {
				final DisplayCanvas.RepaintProperties rp = (DisplayCanvas.RepaintProperties) this.rp;
				layer = rp.layer;
				g_width = rp.g_width;
				g_height = rp.g_height;
				srcRect = rp.srcRect;
				magnification = rp.magnification;
				active = rp.active;
				c_alphas = rp.c_alphas;
				clipRect = rp.clipRect;
				loader = layer.getProject().getLoader();
				mode = rp.mode;
				hm = rp.hm;
				blending_list = rp.blending_list;
				graphics_source = rp.graphics_source;
			}

			BufferedImage target = null;

			final ArrayList<Displayable> al_top = new ArrayList<Displayable>();

			// Check if the image is cached
			Screenshot sc = null;
			try {
				if (display.getMode().getClass() == DefaultMode.class) {
					sc = layer.getParent().getScreenshot(new ScreenshotProperties(layer, srcRect, magnification, g_width, g_height, c_alphas, graphics_source));
					if (null != sc) {
						//Utils.log2("Using cached screenshot " + sc + " with srcRect " + sc.srcRect);
						target = (BufferedImage) layer.getProject().getLoader().getCachedAWT(sc.sid, 0);
						if (null == target) layer.getParent().removeFromOffscreens(sc); // the image was thrown out of the cache
						else if ( (sc.al_top.size() > 0 && sc.al_top.get(0) != display.getActive())
						       || (0 == sc.al_top.size() && null != display.getActive()) ) {
							// Can't accept: different active object
							Utils.log2("rejecting: different active object");
							target = null;
						} else al_top.addAll(sc.al_top);
					}
				}
			} catch (Throwable t) {
				IJError.print(t);
			}

			//Utils.log2("Found target " + target + "\n  with al_top.size() = " + al_top.size());

			if (null == target) {
				target = paintOffscreen(layer, g_width, g_height, srcRect, magnification, active, c_alphas, clipRect, loader, hm, blending_list, mode, graphics_source, true, al_top);
				// Store it:
				/* CAN'T, may have prePaint in it
				if (null != sc && display.getProject().getProperty("look_ahead_cache", 0) > 0) {
					sc.assoc(target);
					layer.getParent().storeScreenshot(sc);
				}
				*/
			}

			synchronized (offscreen_lock) {
				offscreen_lock.lock();
				try {
					// only on success:
					update_graphics = false;
					if (null != offscreen) offscreen.flush();
					offscreen = target;
					invalidateVolatile();
					DisplayCanvas.this.al_top = al_top;

				} catch (Exception e) {
					e.printStackTrace();
				}

				offscreen_lock.unlock();
			}

			// Send repaint event, without offscreen graphics
			RT.paint(clipRect, false);
		}
	}

	/** Looks into the layer and its LayerSet and finds out what needs to be painted, putting it into the three lists. */
	private final void gatherDisplayables(final Layer layer, final Rectangle srcRect, final Displayable active, final ArrayList<Displayable> al_paint, final ArrayList<Displayable> al_top, final boolean preload_patches) {
		layer.getParent().checkBuckets();
		layer.checkBuckets();
		final Iterator<Displayable> ital = layer.find(srcRect, true).iterator();
		final Iterator<Displayable> itzd = layer.getParent().findZDisplayables(layer, srcRect, true).iterator();

		// Assumes the Layer has its objects in order:
		// 1 - Patches
		// 2 - Profiles, Balls
		// 3 - Pipes and ZDisplayables (from the parent LayerSet)
		// 4 - DLabels

		Displayable tmp = null;
		boolean top = false;
		final ArrayList<Patch> al_patches = preload_patches ? new ArrayList<Patch>() : null;

		while (ital.hasNext()) {
			final Displayable d = ital.next();
			final Class c = d.getClass();
			if (DLabel.class == c || LayerSet.class == c) {
				tmp = d; // since ital.next() has moved forward already
				break;
			}
			if (Patch.class == c) {
				al_paint.add(d);
				if (preload_patches) al_patches.add((Patch)d);
			} else {
				if (!top && d == active) top = true; // no Patch on al_top ever
				if (top) al_top.add(d); // so active is added to al_top, if it's not a Patch
				else al_paint.add(d);
			}
		}

		// preload concurrently as many as possible
		if (preload_patches) Loader.preload(al_patches, magnification, false); // must be false; a 'true' would incur in an infinite loop.

		// paint the ZDisplayables here, before the labels and LayerSets, if any
		while (itzd.hasNext()) {
			final Displayable zd = itzd.next();
			if (zd == active) top = true;
			if (top) al_top.add(zd);
			else al_paint.add(zd);
		}
		// paint LayerSet and DLabel objects!
		if (null != tmp) {
			if (tmp == active) top = true;
			if (top) al_top.add(tmp);
			else al_paint.add(tmp);
		}
		while (ital.hasNext()) {
			final Displayable d = ital.next();
			if (d == active) top = true;
			if (top) al_top.add(d);
			else al_paint.add(d);
		}
	}

	/** This method uses data only from the arguments, and changes none.
	 *  Will fill @param al_top with proper Displayable objects, or none when none are selected. */
	public BufferedImage paintOffscreen(final Layer layer, final int g_width, final int g_height, final Rectangle srcRect, final double magnification, final Displayable active, final int c_alphas, final Rectangle clipRect, final Loader loader, final HashMap<Color,Layer> hm, final ArrayList<LayerPanel> blending_list, final int mode, final GraphicsSource graphics_source, final boolean prepaint, final ArrayList<Displayable> al_top) {

		final ArrayList<Displayable> al_paint = new ArrayList<Displayable>();
		gatherDisplayables(layer, srcRect, active, al_paint, al_top, true);

		return paintOffscreen(layer, al_paint, active, g_width, g_height, c_alphas, loader, hm, blending_list, mode, graphics_source, prepaint);
	}

	public BufferedImage paintOffscreen(final Layer layer, final ArrayList<Displayable> al_paint, final Displayable active, final int g_width, final int g_height, final int c_alphas, final Loader loader, final HashMap<Color,Layer> hm, final ArrayList<LayerPanel> blending_list, final int mode, final GraphicsSource graphics_source, final boolean prepaint) {
		try {
			// ALMOST, but not always perfect //if (null != clipRect) g.setClip(clipRect);

			// prepare the canvas for the srcRect and magnification
			final AffineTransform atc = new AffineTransform();
			atc.scale(magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);

			// the non-srcRect areas, in offscreen coords
			final Rectangle r1 = new Rectangle(srcRect.x + srcRect.width, srcRect.y, (int)(g_width / magnification) - srcRect.width, (int)(g_height / magnification));
			final Rectangle r2 = new Rectangle(srcRect.x, srcRect.y + srcRect.height, srcRect.width, (int)(g_height / magnification) - srcRect.height);

			// create new graphics
			display.getProject().getLoader().releaseToFit(g_width * g_height * 4 + 1024);
			final BufferedImage target = getGraphicsConfiguration().createCompatibleImage(g_width, g_height, Transparency.TRANSLUCENT); // creates a BufferedImage.TYPE_INT_ARGB image in my T60p ATI FireGL laptop
			final Graphics2D g = target.createGraphics();

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

			//Utils.log2("offscreen painting: " + al_paint.size());

			// filter paintables
			final Collection<? extends Paintable> paintables = graphics_source.asPaintable(al_paint);
			final Collection<? extends Paintable> paintable_patches = graphics_source.asPaintable(al_paint);

			// Determine painting mode
			if (Display.REPAINT_SINGLE_LAYER == mode) {
				// Direct painting mode, with prePaint abilities
				if (prepaint)
					for (final Paintable d : paintables)
						d.prePaint(g, srcRect, magnification, d == active, c_alphas, layer);
				else
					for (final Paintable d : paintables)
						d.paint(g, srcRect, magnification, d == active, c_alphas, layer);
			} else if (Display.REPAINT_MULTI_LAYER == mode) {
				// paint first the current layer Patches only (to set the background)
				int count = 0;
				// With prePaint capabilities:
				if (prepaint) {
					for (final Paintable d : paintable_patches) {
						d.prePaint(g, srcRect, magnification, d == active, c_alphas, layer);
					}
				} else {
					for (final Paintable d : paintable_patches) {
						d.paint(g, srcRect, magnification, d == active, c_alphas, layer);
					}
				}

				// then blend on top the ImageData of the others, in reverse Z order and using the alpha of the LayerPanel
				final Composite original = g.getComposite();
				// reset
				g.setTransform(new AffineTransform());
				for (final ListIterator<LayerPanel> it = blending_list.listIterator(blending_list.size()); it.hasPrevious(); ) {
					final LayerPanel lp = it.previous();
					if (lp.layer == layer) continue;
					layer.getProject().getLoader().releaseToFit(g_width * g_height * 4 + 1024);
					final BufferedImage bi = getGraphicsConfiguration().createCompatibleImage(g_width, g_height, Transparency.TRANSLUCENT);
					final Graphics2D gb = bi.createGraphics();
					gb.setTransform(atc);
					for (final Displayable d : lp.layer.find(srcRect, true)) {
						if ( ! ImageData.class.isInstance(d)) continue; // skip non-images
						d.paint(gb, srcRect, magnification, false, c_alphas, lp.layer); // not prePaint! We want direct painting, even if potentially slow
					}
					// Repeating loop ... the human compiler at work, just because one cannot lazily concatenate both sequences:
					for (final Displayable d : lp.layer.getParent().findZDisplayables(lp.layer, srcRect, true)) {
						if ( ! ImageData.class.isInstance(d)) continue; // skip non-images
						d.paint(gb, srcRect, magnification, false, c_alphas, lp.layer); // not prePaint! We want direct painting, even if potentially slow
					}
					try {
						g.setComposite(Displayable.getComposite(display.getLayerCompositeMode(lp.layer), lp.getAlpha()));
						g.drawImage(bi, 0, 0, null);
					} catch (Throwable t) {
						Utils.log("Could not use composite mode for layer overlays! Your graphics card may not support it.");
						g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, lp.getAlpha()));
						g.drawImage(bi, 0, 0, null);
					} 
					bi.flush();
				}
				// restore
				g.setComposite(original);
				g.setTransform(atc);

				// then paint the non-Patch objects of the current layer
				// TODO this loop should be reading from the paintable_patches and paintables, since they length/order *could* have changed
				//      And yes this means iterating and checking the Class of each.
				for (final Displayable d : al_paint.subList(paintable_patches.size(), al_paint.size())) {
					d.paint(g, srcRect, magnification, d == active, c_alphas, layer);
				}
			} else { // Display.REPAINT_RGB_LAYER == mode
				final HashMap<Color,byte[]> channels = new HashMap<Color,byte[]>();
				hm.put(Color.green, layer);
				for (final Map.Entry<Color,Layer> e : hm.entrySet()) {
					final BufferedImage bi = new BufferedImage(g_width, g_height, BufferedImage.TYPE_BYTE_GRAY); //INDEXED, Loader.GRAY_LUT);
					final Graphics2D gb = bi.createGraphics();
					gb.setTransform(atc);
					final Layer la = e.getValue();
					ArrayList<Paintable> list = new ArrayList<Paintable>();
					if (la == layer) {
						if (Color.green != e.getKey()) continue; // don't paint current layer in two channels
						list.addAll(paintable_patches);
					} else {
						list.addAll(la.find(Patch.class, srcRect, true));
					}
					list.addAll(la.getParent().getZDisplayables(ImageData.class, true)); // Stack.class and perhaps others
					for (final Paintable d : list) {
						d.paint(gb, srcRect, magnification, false, c_alphas, la);
					}
					channels.put(e.getKey(), (byte[])new ByteProcessor(bi).getPixels());
				}
				final byte[] red, green, blue;
				green = channels.get(Color.green);
				if (null == channels.get(Color.red)) red = new byte[green.length];
				else red = channels.get(Color.red);
				if (null == channels.get(Color.blue)) blue = new byte[green.length];
				else blue = channels.get(Color.blue);
				final int[] pix = new int[green.length];
				for (int i=0; i<green.length; i++) {
					pix[i] = ((red[i] & 0xff) << 16) + ((green[i] & 0xff) << 8) + (blue[i] & 0xff);
				}
				// undo transform, is intended for Displayable objects
				g.setTransform(new AffineTransform());
				final ColorProcessor cp = new ColorProcessor(g_width, g_height, pix);
				if (display.invert_colors) cp.invert();
				final Image img = cp.createImage();
				g.drawImage(img, 0, 0, null);
				img.flush();
				// reset
				g.setTransform(atc);

				// then paint the non-Image objects of the current layer
				for (final Displayable d : al_paint) {
					if (ImageData.class.isInstance(d)) continue;
					d.paint(g, srcRect, magnification, d == active, c_alphas, layer);
				}
				// TODO having each object type in a key/list<type> table would be so much easier and likely performant.
			}

			// finally, paint non-srcRect areas
			if (r1.width > 0 || r1.height > 0 || r2.width > 0 || r2.height > 0) {
				g.setColor(Color.gray);
				g.setClip(r1);
				g.fill(r1);
				g.setClip(r2);
				g.fill(r2);
			}

			return target;
		} catch (OutOfMemoryError oome) {
			// so OutOfMemoryError won't generate locks
			IJError.print(oome);
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
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
		display.getMode().srcRectUpdated(srcRect, magnification);
	}

	private void handleHide(final KeyEvent ke) {
		if (ke.isAltDown() && !ke.isShiftDown()) {
			// show hidden
			Display.updateCheckboxes(display.getLayer().getParent().setAllVisible(false), DisplayablePanel.VISIBILITY_STATE);
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

	DisplayCanvas.Screenshot createScreenshot(Layer layer) {
		return new Screenshot(layer);
	}

	protected class ScreenshotProperties {
		final Layer layer;
		final Rectangle srcRect;
		final double magnification;
		final int g_width, g_height, c_alphas;
		final GraphicsSource graphics_source;
		final ArrayList<LayerPanel> blending_list;
		final HashMap<Color,Layer> hm;
		final int mode;
		ScreenshotProperties(Layer layer, Rectangle srcRect, double magnification, int g_width, int g_height, int c_alphas, GraphicsSource graphics_source) {
			this.srcRect = new Rectangle(srcRect);
			this.magnification = magnification;
			this.layer = layer;
			this.blending_list = new ArrayList<LayerPanel>();
			this.hm = new HashMap<Color,Layer>();
			this.mode = display.getPaintMode(hm, blending_list);
			this.g_width = g_width;
			this.g_height = g_height;
			this.graphics_source = graphics_source;
			this.c_alphas = c_alphas;
			Layer current_layer = display.getLayer();
			if (Display.REPAINT_RGB_LAYER == mode) {
				Layer red = hm.get(Color.red);
				Layer blue = hm.get(Color.blue);
				if (null != red || null != blue) {
					LayerSet ls = layer.getParent();
					int i_layer = ls.indexOf(layer);
					int i_current = ls.indexOf(current_layer);
					if (null != red) {
						int i_red = ls.indexOf(red);
						Layer l = red.getParent().getLayer(i_red + i_current - i_layer);
						if (null != l) {
							hm.put(Color.red, l);
						} else {
							hm.remove(Color.red);
						}
					}
					if (null != blue) {
						int i_blue = ls.indexOf(blue);
						Layer l = blue.getParent().getLayer(i_blue + i_current - i_layer);
						if (null != l) {
							hm.put(Color.blue, l);
						} else {
							hm.remove(Color.blue);
						}
					}
				}
			}
		}
		public final boolean equals(final Object o) {
			final ScreenshotProperties s = (ScreenshotProperties)o;
			return s.layer == this.layer
			  && s.magnification == this.magnification
			  && s.srcRect.x == this.srcRect.x && s.srcRect.y == this.srcRect.y
			  && s.srcRect.width == this.srcRect.width && s.srcRect.height == this.srcRect.height
			  && s.mode == this.mode
			  && c_alphas == this.c_alphas
			  && Utils.equalContent(s.blending_list, this.blending_list)
			  && Utils.equalContent(s.hm, this.hm);
		}
		public int hashCode() { return 0; } //$%^&$#@!
	}

	public class Screenshot {
		final Layer layer;
		long sid = Long.MIN_VALUE;
		long born = 0;
		final ArrayList<Displayable> al_top = new ArrayList<Displayable>();
		final ScreenshotProperties props;

		Screenshot(Layer layer) {
			this(layer, DisplayCanvas.this.srcRect, DisplayCanvas.this.magnification, DisplayCanvas.this.getWidth(), DisplayCanvas.this.getHeight(), DisplayCanvas.this.display.getDisplayChannelAlphas(), DisplayCanvas.this.display.getMode().getGraphicsSource());
		}

		Screenshot(Layer layer, Rectangle srcRect, double magnification, int g_width, int g_height, int c_alphas, GraphicsSource graphics_source) {
			this.layer = layer;
			this.props = new ScreenshotProperties(layer, srcRect, magnification, g_width, g_height, c_alphas, graphics_source);
		}

		public long init() {
			this.born = System.currentTimeMillis();
			this.sid = layer.getProject().getLoader().getNextTempId();
			return this.sid;
		}
		/** Associate @param img to this, with a new sid. */
		public long assoc(BufferedImage img) {
			init();
			if (null != img) layer.getProject().getLoader().cacheAWT(this.sid, img);
			return this.sid;
		}
		public void createImage() {
			BufferedImage img = paintOffscreen(layer, props.g_width, props.g_height, props.srcRect, props.magnification,
						  display.getActive(), props.c_alphas, null, layer.getProject().getLoader(),
						  props.hm, props.blending_list, props.mode, props.graphics_source, false, al_top);
			layer.getProject().getLoader().cacheAWT(sid, img);
		}
	}
}
