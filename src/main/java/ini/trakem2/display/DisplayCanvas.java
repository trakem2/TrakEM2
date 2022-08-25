/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


package ini.trakem2.display;

import java.awt.AWTException;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Event;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Stroke;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.awt.image.VolatileImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.scijava.vecmath.Point2f;
import org.scijava.vecmath.Vector2f;
import org.scijava.vecmath.Vector3d;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.display.inspect.InspectPatchTrianglesMode;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Lock;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

public final class DisplayCanvas extends ImageCanvas implements KeyListener/*, FocusListener*/, MouseWheelListener {

	private static final long serialVersionUID = 1L;

	private Display display;

	private boolean update_graphics = false;
	private BufferedImage offscreen = null;
	private final HashSet<BufferedImage> to_flush = new HashSet<BufferedImage>();
	private ArrayList<Displayable> al_top = new ArrayList<Displayable>();

	private final Lock lock_paint = new Lock();

	private Rectangle box = null; // the bounding box of the active

	private FakeImageWindow fake_win;

	private FreeHandProfile freehandProfile = null;
	private Robot r;// used for setting the mouse pointer

	private final Object offscreen_lock = new Object();

	private Cursor noCursor;

	private boolean snapping = false;
	private boolean dragging = false;
	private boolean input_disabled = false;
	private boolean input_disabled2 = false;

	/** Store a copy of whatever data as each Class may define it, one such data object per class.
	 * Private to the package. */
	static private Hashtable<Class<?>,Object> copy_buffer = new Hashtable<Class<?>,Object>();

	static void setCopyBuffer(final Class<?> c, final Object ob) { copy_buffer.put(c, ob); }
	static Object getCopyBuffer(final Class<?> c) { return copy_buffer.get(c); }

	static private boolean openglEnabled = false;
	static private boolean quartzEnabled = false;
	static private boolean ddscaleEnabled = false;

	// Private to the display package:
	static final RenderingHints rhints;

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent; */
	static {
		final Map<RenderingHints.Key, Object> hints = new HashMap<RenderingHints.Key, Object>();
		try {
			final String openglProperty = System.getProperty("sun.java2d.opengl");
			openglEnabled = openglProperty != null && Boolean.parseBoolean(openglProperty);
		} catch (final Exception ex) { }
		try {
			final String quartzProperty = System.getProperty("apple.awt.graphics.UseQuartz");
			quartzEnabled = Boolean.parseBoolean(quartzProperty);
		} catch (final Exception ex) { }
		try {
			final String ddscaleProperty = System.getProperty("sun.java2d.ddscale");
			final String d3dProperty = System.getProperty("sun.java2d.d3d");
			ddscaleEnabled = Boolean.parseBoolean(ddscaleProperty) && Boolean.parseBoolean(d3dProperty);
		} catch (final Exception ex) { }

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

	private VolatileImage volatileImage;
	private Object volatile_lock = new Object();
	//private javax.swing.Timer resourceTimer = new javax.swing.Timer(10000, resourceReaper);
	//private boolean frameRendered = false;
	private boolean invalid_volatile = false;

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent.
	 *  MUST be called within a "synchronized (volatile_lock) { ... }" block. */
	private void renderVolatileImage(final GraphicsConfiguration gc, final BufferedImage offscreen,
			final ArrayList<Displayable> top, final Displayable active,
			final Layer active_layer, final List<Layer> layers,
			final int c_alphas, final AffineTransform at, Rectangle clipRect) {
		do {
			// Recreate volatileImage ONLY if necessary: when null, when incompatible, or when dimensions have changed
			// Otherwise, just paint on top of it
			final int w = getWidth(), h = getHeight();
			if (0 == w || 0 == h) return;
			if (null == volatileImage || volatileImage.getWidth() != w
			  || volatileImage.getHeight() != h || volatileImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
				if (null != volatileImage) volatileImage.flush();
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
			if (null != offscreen) g.drawImage(offscreen, 0, 0, null);

			// 3 - Paint the active Displayable and all cached on top

			//Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
			//Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			//Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			if (null != active_layer) {
				g.setTransform(at);
				g.setStroke(this.stroke); // AFTER setting the transform
				// Active has to be painted wherever it is, within al_top, if it's not an image
				//if (null != active && active.getClass() != Patch.class && !active.isOutOfRepaintingClip(magnification, srcRect, clipRect)) active.paint(g, magnification, true, c_alphas, active_layer);
				final boolean must_paint_active = null != active && active.isVisible() && !ImageData.class.isAssignableFrom(active.getClass());
				boolean active_painted = !must_paint_active;

				if (null != top) {
					final Rectangle tmp = null != clipRect ? new Rectangle() : null;
					final Rectangle clip = null != clipRect ? new Rectangle((int)(clipRect.x * magnification) - srcRect.x, (int)(clipRect.y * magnification) - srcRect.y, (int)(clipRect.width * magnification), (int)(clipRect.height * magnification)) : null;
					for (final Displayable d : top) {
						if (null != clipRect && !d.getBoundingBox(tmp).intersects(clip)) continue;
						d.paint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
						if (active_painted) continue;
						else active_painted = d == active;
					}
				}
				if (must_paint_active && !active_painted) {
					// Active may not have been part of top array if it was added new and the offscreen image was not updated,
					// which is the case for any non-image object
					// Or, when selecting an object if there were none selected yet.
					active.paint(g, srcRect, magnification, true, c_alphas, active_layer, layers);
				}
			}

			display.getMode().getGraphicsSource().paintOnTop(g, display, srcRect, magnification);

			if (null != active_layer.getOverlay2())
				active_layer.getOverlay2().paint(g, srcRect, magnification);
			if (null != active_layer.getParent().getOverlay2())
				active_layer.getParent().getOverlay2().paint(g, srcRect, magnification);

			if (null != display.gridoverlay) display.gridoverlay.paint(g);

			/* // debug: paint the ZDisplayable's bucket in this layer
			if (null != active_layer.getParent().lbucks) {
				active_layer.getParent().lbucks.get(active_layer).root.paint(g, srcRect, magnification, Color.red);
			}
			*/

			g.dispose();
		} while (volatileImage.contentsLost());
	}

	/** Adapted code from Wayne Meissner, for gstreamer-java org.gstreamer.swing.GstVideoComponent;
	 *  Paints (and re-renders, if necessary) the volatile image onto the given Graphics object, which
	 *  is that of the DisplayCanvas as provided to the paint(Graphics g) method.
	 *
	 *  Expects clipRect in screen coordinates
	 */
	private void render(final Graphics g, final Displayable active, final Layer active_layer,
			final List<Layer> layers, final int c_alphas, final AffineTransform at, Rectangle clipRect) {
		final Graphics2D g2d = (Graphics2D) g.create();
		g2d.setRenderingHints(rhints);
		do {
			final ArrayList<Displayable> top;
			final BufferedImage offscreen;
			synchronized (offscreen_lock) {
				offscreen = this.offscreen;
				top = this.al_top; // will never be cleared, but may be swapped
			}
			final GraphicsConfiguration gc = getGraphicsConfiguration();
			display.getProject().getLoader().releaseToFit(getWidth() * getHeight() * 4 * 5); // 5 images

			// Protect volatile image while rendering it
			synchronized (volatile_lock) {
				if (invalid_volatile || null == volatileImage
				 || volatileImage.validate(gc) != VolatileImage.IMAGE_OK)
				{
					// clear clip, remade in full
					clipRect = null;
					renderVolatileImage(gc, offscreen, top, active, active_layer, layers, c_alphas, at, clipRect);
				}
				if (null != clipRect) g2d.setClip(clipRect);
				g2d.drawImage(volatileImage, 0, 0, null);
			}
		} while (volatileImage.contentsLost());

		g2d.dispose();

		// Flush all old offscreen images
		synchronized (offscreen_lock) {
			for (final BufferedImage bi : to_flush) {
				bi.flush();
			}
			to_flush.clear();
		}
	}

	protected void invalidateVolatile() {
		synchronized (volatile_lock) {
			this.invalid_volatile = true;
		}
	}

	/////////////////

	public DisplayCanvas(final Display display, final int width, final int height) {
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
	protected void setInitialMagnification(final double mag) { // calling this method 'setMagnification' would conflict with the super class homonimous method.
		this.magnification = mag; // don't save in the database. This value is overriden when reopening from the database by calling the setup method.
	}

	/** Used for restoring properties from the database. */
	public void setup(final double mag, final Rectangle srcRect) {
		this.magnification = mag;
		this.srcRect = (Rectangle)srcRect.clone(); // just in case
		super.setDrawingSize((int)Math.ceil(srcRect.width * mag), (int)Math.ceil(srcRect.height * mag));
		setMagnification(mag);
		//no longer needed//display.pack(); // TODO should be run via invokeLater ... need to check many potential locks of invokeLater calling each other.
	}

	/** Does not repaint. */
	public void setDimensions(final double width, final double height) {
		this.imageWidth = (int)Math.ceil(width);
		this.imageHeight = (int)Math.ceil(height);
		((FakeImagePlus)imp).setDimensions(imageWidth, imageHeight);
	}

	/** Overriding to disable it. */
	public void handlePopupMenu() {}

	@Override
	public final void update(final Graphics g) {
		// overriding to avoid default behaviour in java.awt.Canvas which consists in first repainting the entire drawable area with the background color, and then calling method paint.
		this.paint(g);
	}

	/** Handles repaint event requests and the generation of offscreen threads. */
	private final AbstractRepaintThread RT = new AbstractRepaintThread(this, "T2-Canvas-Repainter", new OffscreenThread()) {
		@Override
		protected void handleUpdateGraphics(final Component target, final Rectangle clipRect) {
			final Layer active_layer = display.getLayer();
			this.off.setProperties(new RepaintProperties(clipRect, active_layer, active_layer.getParent().getColorCueLayerRange(active_layer), target.getWidth(), target.getHeight(), srcRect, magnification, display.getActive(), display.getDisplayChannelAlphas(), display.getMode().getGraphicsSource()));
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

	@Override
	public void setMagnification(double mag) {
		if (mag < 0.00000001) mag = 0.00000001;
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

	@Override
	public void paint(final Graphics g) {
		if (null == g) return;
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
			final List<Layer> layers = active_layer.getParent().getColorCueLayerRange(active_layer);

			final Graphics2D g2d = (Graphics2D)g;

			// prepare the canvas for the srcRect and magnification
			final AffineTransform at_original = g2d.getTransform();
			atc.setToIdentity();
			atc.scale(magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);
			at_original.preConcatenate(atc);

			if (null != offscreen && dragging) invalidateVolatile(); // to update the active at least
			render(g, active, active_layer, layers, c_alphas, at_original, clipRect);

			g2d.setTransform(at_original);

			g2d.setStroke(this.stroke);

			// debug buckets
			//if (null != display.getLayer().root) display.getLayer().root.paint(g2d, srcRect, magnification, Color.red);
			//if (null != display.getLayer().getParent().lbucks.get(display.getLayer()).root) display.getLayer().getParent().lbucks.get(display.getLayer()).root.paint(g2d, srcRect, magnification, Color.blue);


			// reset to identity
			g2d.setTransform(new AffineTransform());
			// reset to 1.0 thickness
			g2d.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));

			// paint brush outline for AreaList, or fast-marching area
			if (mouse_in && null != active && AreaContainer.class.isInstance(active)) {
				switch (ProjectToolbar.getToolId()) {
					case ProjectToolbar.BRUSH:
						final int brushSize = ProjectToolbar.getBrushSize();
						g.setColor(active.getColor());
						g.drawOval((int)((xMouse -srcRect.x -brushSize/2)*magnification), (int)((yMouse - srcRect.y -brushSize/2)*magnification), (int)(brushSize * magnification), (int)(brushSize * magnification));
						break;
					case ProjectToolbar.PENCIL:
					case ProjectToolbar.WAND:
						final Composite co = g2d.getComposite();
						if (IJ.isWindows()) g2d.setColor(Color.yellow);
						else g2d.setXORMode(Color.yellow); // XOR on yellow for best contrast
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

		} catch (final Exception e) {
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
			lock_paint.lock();
			lock_paint.unlock();
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

	/** Paints a handle at x,y screen coords. */
	static public void drawScreenHandle(final Graphics g, final int x, final int y) {
		g.setColor(Color.orange);
		g.drawRect(x - 2, y - 2, 5, 5);
		g.setColor(Color.black);
		g.drawRect(x - 1, y - 1, 3, 3);
		g.setColor(Color.orange);
		g.fillRect(x, y, 1, 1);
	}

	/** Paints a handle on the offscreen x,y. Adapted from ij.gui.Roi class. */
	/*
	private void drawHandle(Graphics g, double x, double y) {
		g.setColor(Color.black);
		g.fillRect((int) ((x - srcRect.x) * magnification) - 1, (int) ((y - srcRect.y) * magnification) - 1, 3, 3);
		g.setColor(Color.white);
		g.drawRect((int) ((x - srcRect.x) * magnification) - 2, (int) ((y - srcRect.y) * magnification) - 2, 5, 5);
	}
	*/

	static protected BasicStroke DEFAULT_STROKE = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
	static protected AffineTransform DEFAULT_AFFINE = new AffineTransform();

	static public void drawHandle(final Graphics2D g, final double x, final double y, final Rectangle srcRect, final double magnification) {
		final AffineTransform original = g.getTransform();
		g.setTransform(DEFAULT_AFFINE);
		final Stroke st = g.getStroke();
		g.setStroke(DEFAULT_STROKE);

		g.setColor(Color.black);
		g.fillRect((int) ((x - srcRect.x) * magnification) - 1, (int) ((y - srcRect.y) * magnification) - 1, 3, 3);
		g.setColor(Color.white);
		g.drawRect((int) ((x - srcRect.x) * magnification) - 2, (int) ((y - srcRect.y) * magnification) - 2, 5, 5);

		g.setStroke(st);
		g.setTransform(original);
	}

	/** As offscreen. */
	private int x_p, y_p, x_d, y_d, x_d_old, y_d_old;

	private boolean popup = false;

	private boolean locked = false;

	private int tmp_tool = -1;

	/** In world coordinates. */
	protected Point last_popup = null;

	protected Point consumeLastPopupPoint() {
		final Point p = last_popup;
		last_popup = null;
		return p;
	}

	@Override
	public void mousePressed(final MouseEvent me) {

		super.flags = me.getModifiers();

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

		if (!zoom_and_pan) {
			// stop animations when clicking (and on pushing ESC)
			cancelAnimations();
		}

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
		case ProjectToolbar.PENCIL:
			if (null != active && active.isVisible() && active.getClass() == Profile.class) {
				final Profile prof = (Profile) active;
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
					final DLabel label = new DLabel(display.getProject(), "  ", x_p, y_p);
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
			// gather initial box (for repainting purposes)
			box = display.getMode().getRepaintBounds();
			// check if the active is usable:
			// check if the selection contains locked objects
			if (selection.isLocked()) {
				locked = true;
				return;
			}
			display.getMode().mousePressed(me, x_p, y_p, magnification);
			break;
		default: // the PEN and PENCIL tools, and any other custom tool
			display.getLayerSet().addPreDataEditStep(active);
			box = active.getBoundingBox();
			active.mousePressed(me, display.getLayer(), x_p, y_p, magnification);
			invalidateVolatile();
			break;
		}
	}

	@Override
	public void mouseDragged(final MouseEvent me) {

		super.flags = me.getModifiers();

		if (popup) return;

		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		if (ProjectToolbar.SELECT == ProjectToolbar.getToolId() && locked) {
			Utils.log2("Selection is locked.");
			return;
		}

		dragging = true;

		x_d_old = x_d;
		y_d_old = y_d;

		x_d = srcRect.x + (int) (me.getX() / magnification); // offscreen
		y_d = srcRect.y + (int) (me.getY() / magnification);

		this.xMouse = x_d;
		this.yMouse = y_d;

		// protection:
		final int me_x = me.getX();
		final int me_y = me.getY();
		if (me_x < 0 || me_x > this.getWidth() || me_y < 0 || me_y > this.getHeight()) {
			x_d = x_d_old;
			y_d = y_d_old;
			return;
		}

		final int tool = ProjectToolbar.getToolId();


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
			final int srx = srcRect.x,
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

		final Displayable active = display.getActive();
		if (null != active && active.getClass() == Profile.class) {
			try {
				if (r == null) {
					r = new Robot(this.getGraphicsConfiguration().getDevice());
				}
			} catch (final AWTException e) {
				e.printStackTrace();
			}
		}

		switch (tool) {
		case ProjectToolbar.PENCIL:
			if (null != active && active.isVisible() && active.getClass() == Profile.class) {
				if (freehandProfile == null)
					return; // starting painting out of the DisplayCanvas border
				final double dx = x_d - x_d_old;
				final double dy = y_d - y_d_old;
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
					active.mouseDragged(me, display.getLayer(), x_p, y_p, x_d, y_d, x_d_old, y_d_old);
					// the line above must repaint on its own
					break;
				}
			} else {
				//beyond_srcRect = true;
				Utils.log("DisplayCanvas.mouseDragged: preventing drag beyond layer limits.");
			}
		} else if (display.getMode() instanceof ManualAlignMode
			|| display.getMode() instanceof InspectPatchTrianglesMode) {
			if (display.getLayer().contains(x_d, y_d, 1)) {
				if (tool >= ProjectToolbar.SELECT) {
					display.getMode().mouseDragged(me, x_p, y_p, x_d, y_d, x_d_old, y_d_old);
				}
			}
		}
	}

	@Override
	public void mouseReleased(final MouseEvent me) {

		super.flags = me.getModifiers();

		final boolean dragging2 = dragging;
		dragging = false;
		final boolean locked2 = locked;
		locked = false;

		if (popup) {
			popup = false;
			return;
		}

		// ban if beyond bounds:
		if (x_p < srcRect.x || y_p < srcRect.y || x_p > srcRect.x + srcRect.width || y_p > srcRect.y + srcRect.height) {
			return;
		}

		final int tool = ProjectToolbar.getToolId();

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

		if (locked2) {
			if (ProjectToolbar.SELECT == tool) {
				if (dragging2) {
					Utils.showMessage("Selection is locked!");
				}
				return;
			}
		}

		// pan with middle mouse like in inkscape
		/* // works, but can't use it: the alt button then is useless (so no erasing with areas, and so on)
		if (0 != (flags & InputEvent.BUTTON2_MASK)) {
			tool = Toolbar.HAND;
		}
		*/

		super.flags &= ~InputEvent.BUTTON1_MASK; // make sure button 1 bit is not set
		super.flags &= ~InputEvent.BUTTON2_MASK; // make sure button 2 bit is not set
		super.flags &= ~InputEvent.BUTTON3_MASK; // make sure button 3 bit is not set

		final int x_r = srcRect.x + (int)(me.getX() / magnification);
		final int y_r = srcRect.y + (int)(me.getY() / magnification);

		/*
		if (beyond_srcRect) {
			// Artificial release on the last dragged point
			x_r = x_d;
			y_r = y_d;
		}
		*/

		this.xMouse = x_r;
		this.yMouse = y_r;

		final Displayable active = display.getActive();

		switch (tool) {
			case ProjectToolbar.PENCIL:
				if (null != active && active.isVisible() && active.getClass() == Profile.class) {
					if (freehandProfile == null)
						return; // starting painting out of the DisplayCanvas boarder
					freehandProfile.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
					freehandProfile = null;
					//repaint(true);
					final Selection selection = display.getSelection();
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

		final Selection selection = display.getSelection();

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
				active.mouseReleased(me, display.getLayer(), x_p, y_p, x_d, y_d, x_r, y_r); // active, not selection (Selection only handles transforms, not active's data editions)
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

	@Override
	public void mouseEntered(final MouseEvent me) {
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

	@Override
	public void mouseExited(final MouseEvent me) {
		mouse_in = false;
		// paint away the circular brush if any
		if (ProjectToolbar.getToolId() == ProjectToolbar.BRUSH) {
			final Displayable active = display.getActive();
			if (null != active && active.isVisible() && AreaContainer.class.isInstance(active)) {
				if (null != old_brush_box) {
					this.repaint(old_brush_box, 0);
					old_brush_box = null;
				}
			}
		}
	}

	/** Sets the cursor based on the current tool and cursor location. */
	@Override
	public void setCursor(final int sx, final int sy, final int ox, final int oy) {
		// copy of ImageCanvas.setCursor without the win==null
		xMouse = ox;
		yMouse = oy;
		final Roi roi = imp.getRoi();
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

			if (roi != null && roi.getState() != Roi.CONSTRUCTING && roi.isHandle(sx, sy) >= 0)
				setCursor(handCursor);
			else if (Prefs.usePointerCursor || (roi != null && roi.getState() != Roi.CONSTRUCTING && roi.contains(ox, oy)))
				setCursor(defaultCursor);
			else
				setCursor(crosshairCursor);
			break;
		}
	}
	/** Set the srcRect - used by the DisplayNavigator. */
	protected void setSrcRect(final int x, final int y, int width, int height) {
		if (width < 1) width = 1;
		if (height < 1) height = 1;
		this.srcRect.setRect(x, y, width, height);
		display.updateInDatabase("srcRect");
		display.getMode().srcRectUpdated(srcRect, magnification);
	}

	@Override
	public void setDrawingSize(final int new_width, final int new_height) {
		adjustSrcRect(new_width, new_height);
		super.setDrawingSize(new_width, new_height);
	}

	/** Adjust srcRect and internal variables to the canvas' bounds. */
	public void adjustDimensions() {
		final Rectangle r = getBounds();
		adjustSrcRect(r.width, r.height);
		super.dstWidth = r.width;
		super.dstHeight = r.height;
	}

	/** Adjust srcRect to new dimensions. */
	public void adjustSrcRect(final int new_width, final int new_height) {
		final double mag = super.getMagnification();
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

	private void zoomIn2(int x, int y) {
		// copy of ImageCanvas.zoomIn except for the canEnlarge is different and
		// there's no call to the non-existing ImageWindow
		if (magnification >= 32)
			return;
		final double newMag = getHigherZoomLevel2(magnification);

		// zoom at point: correct mag drift
		final int cx = getWidth() / 2;
		final int cy = getHeight() / 2;
		final int dx = (int)(((x - cx) * magnification) / newMag);
		final int dy = (int)(((y - cy) * magnification) / newMag);
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
		final double newMag = getLowerZoomLevel2(magnification);

		// zoom at point: correct mag drift
		final int cx = getWidth() / 2;
		final int cy = getHeight() / 2;
		final int dx = (int)(((x - cx) * magnification) / newMag);
		final int dy = (int)(((y - cy) * magnification) / newMag);
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
		} else {
			// Shrink srcRect, but NOT the dstWidth,dstHeight of the canvas, which remain the same:
			srcRect = new Rectangle(0, 0, imageWidth, imageHeight);
		}

		setMagnification(newMag);
		display.repaintAll2(); // this repaint includes this canvas's repaint, but updates the navigator without update_graphics
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
		private volatile MouseEvent me = null;
		MouseMovedThread() {
			super("T2-mouseMoved");
			setDaemon(true);
			setPriority(Thread.NORM_PRIORITY);
			start();
		}
		void dispatch(final MouseEvent me) {
			//Utils.log2("before");
			synchronized (this) {
				//Utils.log2("in");
				this.me = me;
				notifyAll();
			}
		}
		void quit() {
			interrupt();
			synchronized (this) { notifyAll(); }
		}
		@Override
		public void run() {
			while (!isInterrupted()) {
				MouseEvent me = this.me;
				if (null != me) {
					try { mouseMoved(me); } catch (final Exception e) { IJError.print(e); }
				}
				// Wait only if the event has not changed
				synchronized (this) {
					if (me == this.me) {
						// Release the pointer
						me = null;
						this.me = null;
						if (isInterrupted()) return;
						// Wait until there is a new event
						try { wait(); } catch (final Exception e) {}
					}
				}
			}
		}
		private void mouseMoved(final MouseEvent me) {
			if (null == me) return;

			if (input_disabled || display.getMode().isDragging()) return;

			xMouse = (int)(me.getX() / magnification) + srcRect.x;
			yMouse = (int)(me.getY() / magnification) + srcRect.y;
			final Displayable active = display.getActive();

			// only when no mouse buttons are down
			final int flags = DisplayCanvas.super.flags;
			if (0 == (flags & InputEvent.BUTTON1_MASK)
			/* && 0 == (flags & InputEvent.BUTTON2_MASK) */ // this is the alt key down ..
			 && 0 == (flags & InputEvent.BUTTON3_MASK)
			//if (me.getButton() == MouseEvent.NOBUTTON
			 && null != active && active.isVisible() && AreaContainer.class.isInstance(active)) {
				final int tool = ProjectToolbar.getToolId();
				Rectangle r = null;
				if (ProjectToolbar.BRUSH == tool) {
					// repaint area where the brush circle is
					final int brushSize = ProjectToolbar.getBrushSize() +2; // +2 padding
					r = new Rectangle( xMouse - brushSize/2,
							   yMouse - brushSize/2,
							   brushSize+1,
							   brushSize+1 );
				} else if (ProjectToolbar.PENCIL == tool || ProjectToolbar.WAND == tool) {
					// repaint area where the fast-marching box is
					r = new Rectangle( xMouse - Segmentation.fmp.width/2 - 2,
							   yMouse - Segmentation.fmp.height/2 - 2,
							   Segmentation.fmp.width + 4,
							   Segmentation.fmp.height + 4 );
				}
				if (null != r) {
					final Rectangle copy = (Rectangle)r.clone();
					if (null != old_brush_box) r.add(old_brush_box);
					old_brush_box = copy;
					repaint(r, 1); // padding because of painting rounding which would live dirty trails
				}
			}

			if (me.isShiftDown()) {
				// Print a comma-separated list of objects under the mouse pointer
				final Layer layer = DisplayCanvas.this.display.getLayer();
				final List<Displayable> al = getDisplayablesUnderMouse(me);
				if (0 == al.size()) {
					Utils.showStatus("", false);
					return;
				}
				final StringBuilder sb = new StringBuilder();
				final Project pr = layer.getProject();
				for (final Displayable d : al) sb.append(pr.getShortMeaningfulTitle(d)).append(", ");
				sb.setLength(sb.length()-2);
				Utils.showStatus(sb.toString(), false);
			} else {
				// For very large images, the Patch.getPixel can take even half a minute
				// to do the pixel grab operation.
				//DisplayCanvas.super.mouseMoved(me);
				// Instead, find out over what are we
				final List<Displayable> under = getDisplayablesUnderMouse(me);
				final Calibration cal = display.getLayerSet().getCalibration();
				if (under.isEmpty()) {
					Utils.showStatus("x=" + (int)(xMouse * cal.pixelWidth) + " " + cal.getUnit()
													 + ", y=" + (int)(yMouse * cal.pixelHeight) + " " + cal.getUnit());
					return;
				}
				final Displayable top = under.get(0);
				String msg =
							"x=" + (int)(xMouse * cal.pixelWidth) + " " + cal.getUnit()
							+ ", y=" + (int)(yMouse * cal.pixelHeight) + " " + cal.getUnit();
				if (top.getClass() == Patch.class) {
					final Patch patch = (Patch)top;
					final int[] p = new int[4];
					BufferedImage offsc;
					synchronized (offscreen_lock) {
						offsc = offscreen;
					}
					if (null == offsc) return;
					try {
						final PixelGrabber pg = new PixelGrabber(offsc, me.getX(), me.getY(), 1, 1, p, 0, offsc.getWidth(null));
						pg.grabPixels();
					} catch (final InterruptedException ie) {
						IJError.print(ie);
						return;
					} catch (final Throwable t) {
						// The offscreen might have been flushed. Just ignore; pixel value will be reported next.
						return;
					}
					patch.approximateTransferPixel(p);
					msg += ", value=";
					switch (patch.getType()) {
						case ImagePlus.GRAY16:
						case ImagePlus.GRAY8:
							msg += p[0];
							break;
						case ImagePlus.GRAY32:
							msg += Float.intBitsToFloat(p[0]);
							break;
						case ImagePlus.COLOR_RGB:
						case ImagePlus.COLOR_256:
							msg += "(" + p[0] + "," + p[1] + "," + p[2] + ")";
							break;
					}
					msg += " [Patch #" + patch.getId() + "]";
				} else {
					final Color c = top.getColor();
					msg += ", value=[" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "] [" + Project.getName(top.getClass()) + " #" + top.getId() + "]";
				}
				Utils.showStatus(msg);
			}
		}
	}

	/** See {@link DisplayCanvas#getDisplayablesUnderMouse(MouseEvent)}. */
	public List<Displayable> getDisplayablesUnderMouse() {
		return getDisplayablesUnderMouse(new MouseEvent(this, -1, 0, 0, xMouse, yMouse, 1, false));
	}

	/** Return the list of Displayable objects under the mouse,
	 * sorted by proper stack order. */
	public List<Displayable> getDisplayablesUnderMouse(final MouseEvent me) {
				final Layer layer = display.getLayer();
				final int x_p = offScreenX(me.getX()),
				          y_p = offScreenY(me.getY());
				final ArrayList<Displayable> al = new ArrayList<Displayable>(layer.getParent().findZDisplayables(layer, x_p, y_p, true));
				Collections.reverse(al);
				final ArrayList<Displayable> al2 = new ArrayList<Displayable>(layer.find(x_p, y_p, true));
				Collections.reverse(al2);
				al.addAll(al2);
				return al;
	}

	public boolean isDragging() {
		return display.getMode().isDragging();
	}

	@Override
	public void mouseMoved(final MouseEvent me) {
		super.flags = me.getModifiers();
		final int tool = Toolbar.getToolId();
		switch (tool) {
		case Toolbar.POLYLINE:
		case Toolbar.POLYGON:
		case Toolbar.ANGLE:
			super.mouseMoved(me);
			repaint();
			return;
		}
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
	@Override
	public void zoomIn(final int x, final int y) {
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
	@Override
	public void zoomOut(final int x, final int y) {
		update_graphics = true; // update the offscreen images.
		zoomOut2(x, y);
	}

	/** Center the srcRect around the given object(s) bounding box, zooming if necessary,
	 *  so that the given r becomes a rectangle centered in the srcRect and zoomed out by a factor of 2. */
	public void showCentered(final Rectangle r) {
		// multiply bounding box dimensions by two
		r.x -= r.width / 2;
		r.y -= r.height / 2;
		r.width += r.width;
		r.height += r.height;
		// compute target magnification
		final double magn = getWidth() / (double)(r.width > r.height ? r.width : r.height);
		center(r, magn);
	}

	/** Show the given r as the srcRect (or as much of it as possible) at the given magnification. */
	public void center(final Rectangle r, double magn) {
		// bring bounds within limits of the layer and the canvas' drawing size
		final double lw = display.getLayer().getLayerWidth();
		final double lh = display.getLayer().getLayerHeight();
		final int cw = (int) (getWidth() / magn); // canvas dimensions in offscreen coords
		final int ch = (int) (getHeight() / magn);

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
		try { Thread.sleep(200); } catch (final Exception e) {} // swing ... waiting for the display.pack()
		update_graphics = true;
		RT.paint(null, update_graphics);
		display.updateInDatabase("srcRect");
		display.updateFrameTitle();
		display.getNavigator().repaint(false);
	}

	/** Repaint as much as the bounding box around the given Displayable. If the Displayable is null, the entire canvas is repainted, remaking the offscreen images. */
	public void repaint(final Displayable d) {
		repaint(d, 0);
	}

	/**
	 * Repaint as much as the bounding box around the given Displayable plus the
	 * extra padding. If the Displayable is null, the entire canvas is
	 * repainted, remaking the offscreen images.
	 */
	public void repaint(final Displayable displ, final int extra) {
		repaint(displ, extra, update_graphics);
	}
	public void repaint(final Displayable displ, final int extra, final boolean update_graphics) {
		if (null != displ) {
			final Rectangle r = displ.getBoundingBox();
			r.x = (int) ((r.x - srcRect.x) * magnification) - extra;
			r.y = (int) ((r.y - srcRect.y) * magnification) - extra;
			r.width = (int) Math.ceil(r.width * magnification) + extra + extra;
			r.height = (int) Math.ceil(r.height * magnification) + extra + extra;
			invalidateVolatile();
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
	public void repaint(final HashSet<Displayable> hs) {
		if (null == hs) return;
		Rectangle r = null;
		final Layer dl = display.getLayer();
		for (final Displayable d : hs) {
			if (d.getLayer() == dl) {
				if (null == r) r = d.getBoundingBox();
				else r.add(d.getBoundingBox());
			}
		}
		if (null != r) {
			//repaint(r.x, r.y, r.width, r.height);
			invalidateVolatile();
			RT.paint(r, update_graphics);
		}
	}

	/**
	 * Repaint the given offscreen Rectangle after transforming its data on the fly to the
	 * srcRect and magnification of this DisplayCanvas. The Rectangle is not
	 * modified.
	 */
	public void repaint(final Rectangle r, final int extra) {
		invalidateVolatile();
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
	public void repaint(final Rectangle box, final int extra, final boolean update_graphics) {
		this.update_graphics = update_graphics;
		repaint(box, extra);
	}

	/** Repaint everything, updating offscreen graphics if so specified. */
	public void repaint(final boolean update_graphics) {
		this.update_graphics = update_graphics | this.update_graphics;
		invalidateVolatile();
		RT.paint(null, this.update_graphics);
	}

	/** Overridden to multithread. This method is here basically to enable calls to the FakeImagePlus.draw from the HAND and other tools to repaint properly.*/
	@Override
	public void repaint() {
		//Utils.log2("issuing thread");
		invalidateVolatile();
		RT.paint(null, update_graphics);
	}

	/** Overridden to multithread. */
	/* // saved as unoveridden to make sure there are no infinite thread loops when calling super in buggy JVMs
	public void repaint(long ms, int x, int y, int width, int height) {
		RT.paint(new Rectangle(x, y, width, height), update_graphics);
	}
	*/

	/** Overridden to multithread. */
	@Override
	public void repaint(final int x, final int y, final int width, final int height) {
		invalidateVolatile();
		RT.paint(new Rectangle(x, y, width, height), update_graphics);
	}

	public void setUpdateGraphics(final boolean b) {
		update_graphics = b;
	}

	/** Release offscreen images and stop threads. */
	public void flush() {
		// cleanup update graphics thread if any
		RT.quit();
		synchronized (offscreen_lock) {
			if (null != offscreen) {
				offscreen.flush();
				offscreen = null;
			}
			update_graphics = true;
			for (final BufferedImage bi : to_flush) bi.flush();
			to_flush.clear();
		}
		mouse_moved.quit();
		try {
			synchronized (this) { if (null != animator) animator.shutdownNow(); }
			cancelAnimations();
		} catch (final Exception e) {}
		animator = null;
	}

	public void destroy() {
		flush();
		WindowManager.setTempCurrentImage(imp); // the FakeImagePlus
		WindowManager.removeWindow(fake_win); // the FakeImageWindow
	}

	public boolean applyTransform() {
		final boolean b = display.getMode().apply();
		if (b) {
			display.setMode(new DefaultMode(display));
			Display.repaint();
		}
		return b;
	}

	public boolean isTransforming() {
		// TODO this may have to change if modes start getting used for a task other than transformation.
		// Perhaps "isTransforming" will have to broaden its meaning to "isNotDefaultMode"
		return display.getMode().getClass() != DefaultMode.class;
	}

	public void cancelTransform() {
		display.getMode().cancel();
		display.setMode(new DefaultMode(display));
		repaint(true);
	}

	private int last_keyCode = KeyEvent.VK_ESCAPE;
	private boolean tagging = false;

	@Override
	public void keyPressed(final KeyEvent ke) {

		final Displayable active = display.getActive();

		if (null != freehandProfile
			&& ProjectToolbar.getToolId() == ProjectToolbar.PENCIL
			&& ke.getKeyCode() == KeyEvent.VK_ESCAPE
			&& null != freehandProfile)
		{
			freehandProfile.abort();
			ke.consume();
			return;
		}

		final int keyCode = ke.getKeyCode();

		try {
			// Enable tagging system for any alphanumeric key:
			if (!input_disabled && null != active && active instanceof Tree<?> && ProjectToolbar.isDataEditTool(ProjectToolbar.getToolId())) {
				if (tagging) {
					if (KeyEvent.VK_0 == keyCode && KeyEvent.VK_0 != last_keyCode) {
						// do nothing: keep tagging as true
					} else {
						// last step of tagging: a char after t or after t and a number (and the char itself can be a number)
						tagging = false;
					}
					active.keyPressed(ke);
					return;
				} else if (KeyEvent.VK_T == keyCode) {
					tagging = true;
					active.keyPressed(ke);
					return;
				}
			}
		} finally {
			last_keyCode = keyCode;
		}

		tagging = false;

		if (ke.isConsumed()) return;

		/*
		 * TODO screen editor ... TEMPORARY if (active instanceof DLabel) {
		 * active.keyPressed(ke); ke.consume(); return; }
		 */

		if (!zoom_and_pan) {
			if (KeyEvent.VK_ESCAPE == keyCode) {
				cancelAnimations();
			}
			return;
		}

		final int keyChar = ke.getKeyChar();

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

		if (KeyEvent.VK_S == keyCode && 0 == ke.getModifiers() && display.getProject().getLoader().isAsynchronous()) {
			display.getProject().getLoader().saveTask(display.getProject(), "Save");
			ke.consume();
			return;
		} else if (KeyEvent.VK_F == keyCode && Utils.isControlDown(ke)) {
			Search.showWindow();
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

		final Layer layer = display.getLayer();

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
					Bureaucrat.createAndStart(new Worker.Task("Undo") { @Override
					public void exec() {
						if (isTransforming()) display.getMode().undoOneStep();
						else display.getLayerSet().undoOneStep();
						Display.repaint(display.getLayerSet());
					}}, display.getProject());
					ke.consume();
				// REDO: alt+z or ctrl+shift+z
				} else if (0 == (mod ^ Event.ALT_MASK) || 0 == (mod ^ (Event.SHIFT_MASK | Utils.getControlModifier())) ) {
					Bureaucrat.createAndStart(new Worker.Task("Redo") { @Override
					public void exec() {
						if (isTransforming()) display.getMode().redoOneStep();
						else display.getLayerSet().redoOneStep();
						Display.repaint(display.getLayerSet());
					}}, display.getProject());
					ke.consume();
				}
				// else, the 'z' command restores the image using ImageJ internal undo
				break;
			case KeyEvent.VK_T:
				// Enable with any tool to the left of the PENCIL
				if (null != active && !isTransforming() && ProjectToolbar.getToolId() < ProjectToolbar.PENCIL) {
					ProjectToolbar.setTool(ProjectToolbar.SELECT);
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
					final Roi roi = getFakeImagePlus().getRoi();
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
				break;
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
						Display.repaint();
						ke.consume();
					}
				} else {
					// ;)
					final int kem = ke.getModifiers();
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
				ke.consume();
				break;
			case KeyEvent.VK_J:
				if (!display.getSelection().isEmpty()) {
					display.adjustMinAndMaxGUI();
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
				ke.consume();
				break;
			case KeyEvent.VK_M:
				if (0 == ke.getModifiers() && ProjectToolbar.getToolId() == ProjectToolbar.SELECT) {
					display.getSelection().measure();
					ke.consume();
				}
				break;
		}

		switch (keyChar) {
			case ':':
			case ';':
				if (null != active && active instanceof ZDisplayable) {
					if (null != display.getProject().getProjectTree().tryAddNewConnector(active, true)) {
						ProjectToolbar.setTool(ProjectToolbar.PEN);
					}
					ke.consume();
				}
				break;
		}

		if (ke.isConsumed()) return;

		if (null != active) {
			if (display.getMode().getClass() == DefaultMode.class) {
				active.keyPressed(ke);
			}
			if (ke.isConsumed()) return;
		}

		// Else:
		switch (keyCode) {
			case KeyEvent.VK_G:
				if (browseToNodeLayer(ke.isShiftDown())) {
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
					layer.getParent().addUndoMoveStep(active);
					layer.getParent().move(LayerSet.UP, active);
					layer.getParent().addUndoMoveStep(active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_PAGE_DOWN: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().addUndoMoveStep(active);
					layer.getParent().move(LayerSet.DOWN, active);
					layer.getParent().addUndoMoveStep(active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_HOME: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().addUndoMoveStep(active);
					layer.getParent().move(LayerSet.TOP, active);
					layer.getParent().addUndoMoveStep(active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_END: // as in Inkscape
				if (null != active) {
					update_graphics = true;
					layer.getParent().addUndoMoveStep(active);
					layer.getParent().move(LayerSet.BOTTOM, active);
					layer.getParent().addUndoMoveStep(active);
					Display.repaint(layer, active, 5);
					Display.updatePanelIndex(layer, active);
					ke.consume();
				}
				break;
			case KeyEvent.VK_V:
				if (0 == ke.getModifiers()) {
					if (null == active || active.getClass() == Patch.class) {
						// paste a new image
						final ImagePlus clipboard = ImagePlus.getClipboard();
						if (null != clipboard) {
							final ImagePlus imp = new ImagePlus(clipboard.getTitle() + "_" + System.currentTimeMillis(), clipboard.getProcessor().crop());
							final Object info = clipboard.getProperty("Info");
							if (null != info) imp.setProperty("Info", (String)info);
							final double x = srcRect.x + srcRect.width/2 - imp.getWidth()/2;
							final double y = srcRect.y + srcRect.height/2 - imp.getHeight()/2;
							// save the image somewhere:
							final Patch pa = display.getProject().getLoader().addNewImage(imp, x, y);
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
			case KeyEvent.VK_P:
				if (0 == ke.getModifiers()) {
					display.getLayerSet().color_cues = !display.getLayerSet().color_cues;
					Display.repaint(display.getLayerSet());
					ke.consume();
				}
				break;
			case KeyEvent.VK_F:
				if (0 == (ke.getModifiers() ^ KeyEvent.SHIFT_MASK)) {
					// toggle visibility of tags
					display.getLayerSet().paint_tags = !display.getLayerSet().paint_tags;
					Display.repaint();
					ke.consume();
				} else if (0 == (ke.getModifiers() ^ KeyEvent.ALT_MASK)) {
					// toggle visibility of edge arrows
					display.getLayerSet().paint_arrows = !display.getLayerSet().paint_arrows;
					Display.repaint();
					ke.consume();
				} else if (0 == ke.getModifiers()) {
					// toggle visibility of edge confidence boxes
					display.getLayerSet().paint_edge_confidence_boxes = !display.getLayerSet().paint_edge_confidence_boxes;
					Display.repaint();
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

	@Override
	public void keyTyped(final KeyEvent ke) {}

	@Override
	public void keyReleased(final KeyEvent ke) {}

	public void zoomToFit() {
		final double magw = (double) getWidth() / imageWidth;
		final double magh = (double) getHeight() / imageHeight;
		this.magnification = magw < magh ? magw : magh;
		this.srcRect.setRect(0, 0, imageWidth, imageHeight);
		setMagnification(magnification);
		display.updateInDatabase("srcRect"); // includes magnification
	}


	public void setReceivesInput(final boolean b) {
		this.input_disabled = !b;
	}

	public boolean isInputEnabled() {
		return !input_disabled;
	}

	/** CAREFUL: the ImageProcessor of the returned ImagePlus is fake, that is, a 4x4 byte array; but the dimensions that it returns are those of the host LayerSet. Used to retrieve ROIs for example.*/
	public ImagePlus getFakeImagePlus() {
		return this.imp;
	}

	/** Key/Mouse bindings like:
	 * - ij.gui.StackWindow: wheel to scroll slices (in this case Layers)
	 * - Inkscape: control+wheel to zoom (apple+wheel in macosx, since control+wheel zooms desktop)
	 */
	@Override
	public void mouseWheelMoved(final MouseWheelEvent mwe) {
		if (dragging) return; // prevent unexpected mouse wheel movements
		final int modifiers = mwe.getModifiers();
		final int rotation = mwe.getWheelRotation();
		final int tool = ProjectToolbar.getToolId();
		if (0 != (modifiers & Utils.getControlModifier())) {
			if (!zoom_and_pan) return;
			// scroll zoom under pointer
			int x = mwe.getX();
			int y = mwe.getY();
			if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
				x = getWidth()/2;
				y = getHeight()/2;
			}

			// Current mouse point in world coords
			final double xx = x/magnification + srcRect.x;
			final double yy = y/magnification + srcRect.y;
			// Delta of view, in screen pixels:
			final int px_inc;
			if ( 0 != (modifiers & MouseWheelEvent.SHIFT_MASK)) {
				if (0 != (modifiers & MouseWheelEvent.ALT_MASK)) px_inc = 1;
				else px_inc = 5;
			} else px_inc = 20;
			final double inc = px_inc/magnification;

			final Rectangle r = new Rectangle();

			if (rotation > 0) {
				// zoom out
				r.width = srcRect.width + (int)(inc+0.5);
				r.height = srcRect.height + (int)(inc+0.5);
				r.x = (int)(xx - ((xx - srcRect.x)/srcRect.width) * r.width + 0.5);
				r.y = (int)(yy - ((yy - srcRect.y)/srcRect.height) * r.height + 0.5);
				// check boundaries
				if (r.width * magnification < getWidth()
				 || r.height * magnification < getHeight()) {
					// Can't zoom at point: would chage field of view's flow or would have to shift the canvas position!
					Utils.showStatus("To zoom more, use -/+ keys");
					return;
				}
			} else {
				//zoom in
				r.width = srcRect.width - (int)(inc+0.5);
				r.height = srcRect.height - (int)(inc+0.5);
				if (r.width < 1 || r.height < 1) {
					return;
				}
				r.x = (int)(xx - ((xx - srcRect.x)/srcRect.width) * r.width + 0.5);
				r.y = (int)(yy - ((yy - srcRect.y)/srcRect.height) * r.height + 0.5);
			}
			final double newMag = magnification * (srcRect.width / (double)r.width);
			// correct floating-point-induced erroneous drift: the int-precision offscreen point under the mouse shoud remain the same
			r.x -= (int)((x/newMag + r.x) - xx);
			r.y -= (int)((y/newMag + r.y) - yy);

			// adjust bounds
			int w = (int) Math.round(dstWidth / newMag);
			if (w * newMag < dstWidth) w++;
			if (w > imageWidth) w = imageWidth;
			int h = (int) Math.round(dstHeight / newMag);
			if (h * newMag < dstHeight) h++;
			if (h > imageHeight) h = imageHeight;
			if (r.x < 0) r.x = 0;
			if (r.y < 0) r.y = 0;
			if (r.x + w > imageWidth) r.x = imageWidth - w;
			if (r.y + h > imageHeight) r.y = imageHeight - h; //imageWidth and imageHeight are the LayerSet's width,height, ie. the world's 2D dimensions.

			// set!
			this.setMagnification(newMag);
			this.setSrcRect(r.x, r.y, w, h);
			display.repaintAll2();

		} else if (0 == (modifiers ^ InputEvent.SHIFT_MASK) && null != display.getActive() && ProjectToolbar.PEN != tool && AreaContainer.class.isInstance(display.getActive())) {
			final int sign = rotation > 0 ? 1 : -1;
			if (ProjectToolbar.BRUSH == tool) {
				final int brushSize_old = ProjectToolbar.getBrushSize();
				// resize brush for AreaList/AreaTree painting
				int brushSize = ProjectToolbar.setBrushSize((int)(5 * sign / magnification)); // the getWheelRotation provides the sign
				if (brushSize_old > brushSize) brushSize = brushSize_old; // for repainting purposes alone
				int extra = (int)(10 / magnification);
				if (extra < 2) extra = 2;
				extra += 4; // for good measure
				this.repaint(new Rectangle((int)(mwe.getX() / magnification) + srcRect.x - brushSize/2 - extra, (int)(mwe.getY() / magnification) + srcRect.y - brushSize/2 - extra, brushSize+extra, brushSize+extra), 0);
			} else if (ProjectToolbar.PENCIL == tool || ProjectToolbar.WAND == tool) {
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
		final private List<Layer> layers;
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

		RepaintProperties(final Rectangle clipRect, final Layer layer, final List<Layer> layers, final int g_width, final int g_height, final Rectangle srcRect, final double magnification, final Displayable active, final int c_alphas, final GraphicsSource graphics_source) {
			this.clipRect = clipRect;
			this.layer = layer;
			this.layers = layers;
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

		@Override
		public void paint() {
			final Layer active_layer;
			final List<Layer> layers;
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
				active_layer = rp.layer;
				layers = rp.layers;
				g_width = rp.g_width;
				g_height = rp.g_height;
				srcRect = rp.srcRect;
				magnification = rp.magnification;
				active = rp.active;
				c_alphas = rp.c_alphas;
				clipRect = rp.clipRect;
				loader = active_layer.getProject().getLoader();
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
					sc = active_layer.getParent().getScreenshot(new ScreenshotProperties(active_layer, srcRect, magnification, g_width, g_height, c_alphas, graphics_source));
					if (null != sc) {
						//Utils.log2("Using cached screenshot " + sc + " with srcRect " + sc.srcRect);
						target = (BufferedImage) loader.getCachedAWT(sc.sid, 0);
						if (null == target) active_layer.getParent().removeFromOffscreens(sc); // the image was thrown out of the cache
						else if ( (sc.al_top.size() > 0 && sc.al_top.get(0) != display.getActive())
						       || (0 == sc.al_top.size() && null != display.getActive()) ) {
							// Can't accept: different active object
							Utils.log2("rejecting: different active object");
							target = null;
						} else {
							al_top.addAll(sc.al_top);
							display.applyFilters(target);
						}
					}
				}
			} catch (final Throwable t) {
				IJError.print(t);
			}

			//Utils.log2("Found target " + target + "\n  with al_top.size() = " + al_top.size());

			if (null == target) {
				target = paintOffscreen(active_layer, layers, g_width, g_height, srcRect, magnification, active, c_alphas, clipRect, loader, hm, blending_list, mode, graphics_source, active_layer.getParent().prepaint, al_top, true);
				// Store it:
				/* CAN'T, may have prePaint in it
				if (null != sc && display.getProject().getProperty("look_ahead_cache", 0) > 0) {
					sc.assoc(target);
					layer.getParent().storeScreenshot(sc);
				}
				*/
			}

			synchronized (offscreen_lock) {
				// only on success:
				if (null != offscreen) to_flush.add(offscreen);
				offscreen = target;
				update_graphics = false;
				DisplayCanvas.this.al_top = al_top;
			}
			// Outside, otherwise could deadlock
			invalidateVolatile();

			// Send repaint event, without offscreen graphics
			RT.paint(clipRect, false);
		}
	}

	/** Looks into the layer and its LayerSet and finds out what needs to be painted, putting it into the three lists.
	 *  @return the index of the first non-image object. */
	private final int gatherDisplayables(final Layer layer, final List<Layer> layers, final Rectangle srcRect, final Displayable active, final ArrayList<Displayable> al_paint, final ArrayList<Displayable> al_top, final boolean preload_patches) {
		layer.getParent().checkBuckets();
		layer.checkBuckets();
		final Iterator<Displayable> ital = layer.find(srcRect, true).iterator();
		final Collection<Displayable> zdal;
		final LayerSet layer_set = layer.getParent();
		// Which layers to color cue, if any?
		if (layer_set.color_cues) {
			final Collection<Displayable> atlayer = layer_set.roughlyFindZDisplayables(layer, srcRect, true);
			final Set<Displayable> others = new HashSet<Displayable>();
			for (final Layer la : layers) {
				if (la == layer) continue;
				others.addAll(layer_set.roughlyFindZDisplayables(la, srcRect, true));
			}
			others.removeAll(atlayer);
			zdal = new ArrayList<Displayable>(others); // in whatever order, to paint under
			zdal.addAll(atlayer); // in proper stack-index order
		} else {
			zdal = layer_set.roughlyFindZDisplayables(layer, srcRect, true);
		}
		final Iterator<Displayable> itzd = zdal.iterator();

		// Assumes the Layer has its objects in order:
		// 1 - Patches
		// 2 - Profiles, Balls
		// 3 - Pipes and ZDisplayables (from the parent LayerSet)
		// 4 - DLabels

		Displayable tmp = null;
		boolean top = false;
		final ArrayList<Patch> al_patches = preload_patches ? new ArrayList<Patch>() : null;

		int first_non_patch = 0;

		while (ital.hasNext()) {
			final Displayable d = ital.next();
			final Class<?> c = d.getClass();
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
			first_non_patch += 1;
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

		return first_non_patch;
	}

	@Deprecated
	public BufferedImage paintOffscreen(final Layer active_layer, final int g_width, final int g_height,
			final Rectangle srcRect, final double magnification, final Displayable active,
			final int c_alphas, final Rectangle clipRect, final Loader loader, final HashMap<Color,Layer> hm,
			final ArrayList<LayerPanel> blending_list, final int mode, final GraphicsSource graphics_source,
			final boolean prepaint, final ArrayList<Displayable> al_top) {
		return paintOffscreen(active_layer, active_layer.getParent().getColorCueLayerRange(active_layer), g_width, g_height, srcRect, magnification, active,
						c_alphas, clipRect, loader, hm, blending_list, mode, graphics_source,
						prepaint, al_top, false);
	}

	/** This method uses data only from the arguments, and changes none.
	 *  Will fill @param al_top with proper Displayable objects, or none when none are selected. */
	public BufferedImage paintOffscreen(final Layer active_layer, final List<Layer> layers, final int g_width, final int g_height,
			final Rectangle srcRect, final double magnification, final Displayable active,
			final int c_alphas, final Rectangle clipRect, final Loader loader, final HashMap<Color,Layer> hm,
			final ArrayList<LayerPanel> blending_list, final int mode, final GraphicsSource graphics_source,
			final boolean prepaint, final ArrayList<Displayable> al_top, final boolean preload) {

		final ArrayList<Displayable> al_paint = new ArrayList<Displayable>();
		final int first_non_patch = gatherDisplayables(active_layer, layers, srcRect, active, al_paint, al_top, preload);

		return paintOffscreen(active_layer, layers, al_paint, active, g_width, g_height, c_alphas, loader, hm, blending_list, mode, graphics_source, prepaint, first_non_patch);
	}

	public BufferedImage paintOffscreen(final Layer active_layer, final List<Layer> layers, final ArrayList<Displayable> al_paint, final Displayable active, final int g_width, final int g_height, final int c_alphas, final Loader loader, final HashMap<Color,Layer> hm, final ArrayList<LayerPanel> blending_list, final int mode, final GraphicsSource graphics_source, final boolean prepaint, int first_non_patch) {
		try {
			if (0 == g_width || 0 == g_height) return null;
			// ALMOST, but not always perfect //if (null != clipRect) g.setClip(clipRect);

			// prepare the canvas for the srcRect and magnification
			final AffineTransform atc = new AffineTransform();
			atc.scale(magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);

			// the non-srcRect areas, in offscreen coords
			final Rectangle r1 = new Rectangle(srcRect.x + srcRect.width, srcRect.y, (int)(g_width / magnification) - srcRect.width, (int)(g_height / magnification));
			final Rectangle r2 = new Rectangle(srcRect.x, srcRect.y + srcRect.height, srcRect.width, (int)(g_height / magnification) - srcRect.height);

			// create new graphics
			try {
				display.getProject().getLoader().releaseToFit(g_width * g_height * 10);
			} catch (final Exception e) {} // when closing, asynch state may throw for a null loader.

			final BufferedImage target = getGraphicsConfiguration().createCompatibleImage(g_width, g_height, Transparency.TRANSLUCENT); // creates a BufferedImage.TYPE_INT_ARGB image in my T60p ATI FireGL laptop
			//Utils.log2("offscreen acceleration priority: " + target.getAccelerationPriority());
			final Graphics2D g = target.createGraphics();

			g.setTransform(atc); //at_original);

			//setRenderingHints(g);
			// always a stroke of 1.0, regardless of magnification; the stroke below corrects for that
			g.setStroke(stroke);



			// Testing: removed Area.subtract, now need to fill in background
			g.setColor(Color.black);
			g.fillRect(0, 0, g_width - r1.x, g_height - r2.y);


			// paint:
			//  1 - background
			//  2 - images and anything else not on al_top
			//  3 - non-srcRect areas

			//Utils.log2("offscreen painting: " + al_paint.size());

			// filter paintables
			final Collection<? extends Paintable> paintables = graphics_source.asPaintable(al_paint);

			// adjust:
			first_non_patch = paintables.size() - (al_paint.size() - first_non_patch);

			// Determine painting mode
			if (Display.REPAINT_SINGLE_LAYER == mode) {
				if (display.isLiveFilteringEnabled()) {
					paintWithFiltering(g, al_paint, paintables, first_non_patch, g_width, g_height, active, c_alphas, active_layer, layers, true);
				} else {
					// Direct painting mode, with prePaint abilities
					int i = 0;
					for (final Paintable d : paintables) {
						if (i == first_non_patch) {
							//Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
							g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
							//Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
							g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
							//Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
							g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
						}
						if (prepaint) d.prePaint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
						else d.paint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
						i++;
					}
				}
			} else if (Display.REPAINT_MULTI_LAYER == mode) {
				// paint first the current layer Patches only (to set the background)
				// With prePaint capabilities:
				if (display.isLiveFilteringEnabled()) {
					paintWithFiltering(g, al_paint, paintables, first_non_patch, g_width, g_height, active, c_alphas, active_layer, layers, false);
				} else {
					int i = 0;
					if (prepaint) {
						for (final Paintable d : paintables) {
							if (first_non_patch == i) break;
							d.prePaint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
							i++;
						}
					} else {
						for (final Paintable d : paintables) {
							if (first_non_patch == i) break;
							d.paint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
							i++;
						}
					}
				}

				// then blend on top the ImageData of the others, in reverse Z order and using the alpha of the LayerPanel
				final Composite original = g.getComposite();
				// reset
				g.setTransform(new AffineTransform());
				// Paint what:
				final Set<Class<?>> included = display.classes_to_multipaint;
				for (final ListIterator<LayerPanel> it = blending_list.listIterator(blending_list.size()); it.hasPrevious(); ) {
					final LayerPanel lp = it.previous();
					if (lp.layer == active_layer) continue;
					active_layer.getProject().getLoader().releaseToFit(g_width * g_height * 4 + 1024);
					final BufferedImage bi = getGraphicsConfiguration().createCompatibleImage(g_width, g_height, Transparency.TRANSLUCENT);
					final Graphics2D gb = bi.createGraphics();
					gb.setTransform(atc);
					for (final Displayable d : lp.layer.find(srcRect, true)) {
						if (included.contains(d.getClass()))
							d.paint(gb, srcRect, magnification, false, c_alphas, lp.layer, layers); // not prePaint! We want direct painting, even if potentially slow
					}
					// Repeating loop ... the human compiler at work, just because one cannot lazily concatenate both sequences:
					for (final Displayable d : lp.layer.getParent().roughlyFindZDisplayables(lp.layer, srcRect, true)) {
						if (included.contains(d.getClass()))
								d.paint(gb, srcRect, magnification, false, c_alphas, lp.layer, layers); // not prePaint! We want direct painting, even if potentially slow
					}
					try {
						g.setComposite(Displayable.getComposite(display.getLayerCompositeMode(lp.layer), lp.getAlpha()));
						g.drawImage(display.applyFilters(bi), 0, 0, null);
					} catch (final Throwable t) {
						Utils.log("Could not use composite mode for layer overlays! Your graphics card may not support it.");
						g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, lp.getAlpha()));
						g.drawImage(bi, 0, 0, null);
						IJError.print(t);
					}
					bi.flush();
				}
				// restore
				g.setComposite(original);
				g.setTransform(atc);

				// then paint the non-Patch objects of the current layer

				//Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
				//Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				//Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

				// TODO this loop should be reading from the paintable_patches and paintables, since their length/order *could* have changed
				// For the current layer:
				for (int i = first_non_patch; i < al_paint.size(); i++) {
					final Displayable d = al_paint.get(i);
					d.paint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
				}
			} else if(Display.REPAINT_RGB_LAYER == mode) {
				// TODO rewrite to avoid calling the list twice
				final Collection<? extends Paintable> paintable_patches = graphics_source.asPaintable(al_paint);
				//
				final HashMap<Color,byte[]> channels = new HashMap<Color,byte[]>();
				hm.put(Color.green, active_layer);
				for (final Map.Entry<Color,Layer> e : hm.entrySet()) {
					final BufferedImage bi = new BufferedImage(g_width, g_height, BufferedImage.TYPE_BYTE_GRAY); //INDEXED, Loader.GRAY_LUT);
					final Graphics2D gb = bi.createGraphics();
					gb.setTransform(atc);
					final Layer la = e.getValue();
					final ArrayList<Paintable> list = new ArrayList<Paintable>();
					if (la == active_layer) {
						if (Color.green != e.getKey()) continue; // don't paint current layer in two channels
						list.addAll(paintable_patches);
					} else {
						list.addAll(la.find(Patch.class, srcRect, true));
					}
					list.addAll(la.getParent().getZDisplayables(ImageData.class, true)); // Stack.class and perhaps others
					for (final Paintable d : list) {
						d.paint(gb, srcRect, magnification, false, c_alphas, la, layers);
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
				display.applyFilters(cp);
				final Image img = cp.createImage();
				g.drawImage(img, 0, 0, null);
				img.flush();
				// reset
				g.setTransform(atc);

				// then paint the non-Image objects of the current layer
				//Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
				//Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				//Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

				for (final Displayable d : al_paint) {
					if (ImageData.class.isInstance(d)) continue;
					d.paint(g, srcRect, magnification, d == active, c_alphas, active_layer, layers);
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
		} catch (final OutOfMemoryError oome) {
			// so OutOfMemoryError won't generate locks
			IJError.print(oome);
		} catch (final Exception e) {
			IJError.print(e);
		}
		return null;
	}

	private final void paintWithFiltering(final Graphics2D g, final ArrayList<Displayable> al_paint,
										  final Collection<? extends Paintable> paintables,
										  final int first_non_patch,
										  final int g_width, final int g_height,
										  final Displayable active, final int c_alphas,
										  final Layer layer, final List<Layer> layers, final boolean paint_non_images) {
		// Determine the type of the image: if any Patch is of type COLOR_RGB or COLOR_256, use RGB
		int type = BufferedImage.TYPE_BYTE_GRAY;
		search: for (final Displayable d : al_paint) {
			if (d.getClass() == Patch.class) {
				switch (((Patch)d).getType()) {
					case ImagePlus.COLOR_256:
					case ImagePlus.COLOR_RGB:
						type = BufferedImage.TYPE_INT_ARGB;
						break search;
				}
			}
		}

		// Paint all patches to an image
		final BufferedImage bi = new BufferedImage(g_width, g_height, type);
		final Graphics2D gpre = bi.createGraphics();
		gpre.setTransform(atc);
		int i = 0;
		for (final Paintable p : paintables) {
			if (i == first_non_patch) break;
			p.paint(gpre, srcRect, magnification, p == active, c_alphas, layer, layers);
			i++;
		}
		gpre.dispose();
		final ImagePlus imp = new ImagePlus("filtered", type == BufferedImage.TYPE_BYTE_GRAY ? new ByteProcessor(bi) : new ColorProcessor(bi));
		bi.flush();

		display.applyFilters(imp);

		// Paint the filtered image
		final AffineTransform aff = g.getTransform();
		g.setTransform(new AffineTransform()); // reset
		g.drawImage(imp.getProcessor().createImage(), 0, 0, null);
		// Paint the remaining elements if any
		if (paint_non_images && first_non_patch != paintables.size()) {
			g.setTransform(aff); // restore srcRect and magnification
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			i = 0;
			for (final Paintable p : paintables) {
				if (i < first_non_patch) {
					i++;
					continue;
				}
				p.paint(g, srcRect, magnification, p == active, c_alphas, layer, layers);
				i++;
			}
		}

	}

	// added here to prevent flickering, but doesn't help. All it does is avoid a call to imp.redraw()
	@Override
	protected void scroll(final int sx, final int sy) {
		final int ox = xSrcStart + (int)(sx/magnification);  //convert to offscreen coordinates
		final int oy = ySrcStart + (int)(sy/magnification);
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

	DisplayCanvas.Screenshot createScreenshot(final Layer layer) {
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
		ScreenshotProperties(final Layer layer, final Rectangle srcRect, final double magnification, final int g_width, final int g_height, final int c_alphas, final GraphicsSource graphics_source) {
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
			final Layer current_layer = display.getLayer();
			if (Display.REPAINT_RGB_LAYER == mode) {
				final Layer red = hm.get(Color.red);
				final Layer blue = hm.get(Color.blue);
				if (null != red || null != blue) {
					final LayerSet ls = layer.getParent();
					final int i_layer = ls.indexOf(layer);
					final int i_current = ls.indexOf(current_layer);
					if (null != red) {
						final int i_red = ls.indexOf(red);
						final Layer l = red.getParent().getLayer(i_red + i_current - i_layer);
						if (null != l) {
							hm.put(Color.red, l);
						} else {
							hm.remove(Color.red);
						}
					}
					if (null != blue) {
						final int i_blue = ls.indexOf(blue);
						final Layer l = blue.getParent().getLayer(i_blue + i_current - i_layer);
						if (null != l) {
							hm.put(Color.blue, l);
						} else {
							hm.remove(Color.blue);
						}
					}
				}
			}
		}
		@Override
		public final boolean equals(final Object o) {
			final ScreenshotProperties s = (ScreenshotProperties)o;
			return s.layer == this.layer
			  && s.magnification == this.magnification
			  && s.srcRect.x == this.srcRect.x && s.srcRect.y == this.srcRect.y
			  && s.srcRect.width == this.srcRect.width && s.srcRect.height == this.srcRect.height
			  && s.mode == this.mode
			  && s.c_alphas == this.c_alphas
			  && Utils.equalContent(s.blending_list, this.blending_list)
			  && Utils.equalContent(s.hm, this.hm);
		}
		@Override
		public int hashCode() { return 0; } //$%^&$#@!
	}

	public class Screenshot {
		final Layer layer;
		long sid = Long.MIN_VALUE;
		long born = 0;
		final ArrayList<Displayable> al_top = new ArrayList<Displayable>();
		final ScreenshotProperties props;

		Screenshot(final Layer layer) {
			this(layer, DisplayCanvas.this.srcRect, DisplayCanvas.this.magnification, DisplayCanvas.this.getWidth(), DisplayCanvas.this.getHeight(), DisplayCanvas.this.display.getDisplayChannelAlphas(), DisplayCanvas.this.display.getMode().getGraphicsSource());
		}

		Screenshot(final Layer layer, final Rectangle srcRect, final double magnification, final int g_width, final int g_height, final int c_alphas, final GraphicsSource graphics_source) {
			this.layer = layer;
			this.props = new ScreenshotProperties(layer, srcRect, magnification, g_width, g_height, c_alphas, graphics_source);
		}

		public long init() {
			this.born = System.currentTimeMillis();
			this.sid = layer.getProject().getLoader().getNextTempId();
			return this.sid;
		}
		/** Associate @param img to this, with a new sid. */
		public long assoc(final BufferedImage img) {
			init();
			if (null != img) layer.getProject().getLoader().cacheAWT(this.sid, img);
			return this.sid;
		}
		public void createImage() {
			final BufferedImage img = paintOffscreen(layer, layer.getParent().getColorCueLayerRange(layer),  props.g_width, props.g_height, props.srcRect, props.magnification,
						  display.getActive(), props.c_alphas, null, layer.getProject().getLoader(),
						  props.hm, props.blending_list, props.mode, props.graphics_source, false, al_top, false);
			layer.getProject().getLoader().cacheAWT(sid, img);
		}
		public void flush() {
			layer.getProject().getLoader().decacheAWT(sid);
		}
	}

	private boolean browseToNodeLayer(final boolean is_shift_down) {
		// find visible instances of Tree that are currently painting in the canvas
		try {
			final Layer active_layer = display.getLayer();
			final Point po = getCursorLoc(); // in offscreen coords
			for (final ZDisplayable zd : display.getLayerSet().getDisplayableList()) {
				if (!zd.isVisible()) continue;
				if (!(zd instanceof Tree<?>)) continue;
				final Tree<?> t = (Tree<?>)zd;
				final Layer la = t.toClosestPaintedNode(active_layer, po.x, po.y, magnification);
				if (null == la) continue;
				// Else:
				display.toLayer(la);
				if (!is_shift_down) display.getSelection().clear();
				display.getSelection().add(t);
				switch (ProjectToolbar.getToolId()) {
					case ProjectToolbar.PEN:
					case ProjectToolbar.BRUSH:
						break;
					default:
						ProjectToolbar.setTool(ProjectToolbar.PEN);
						break;
				}
				return true;
			}
		} catch (final Exception e) {
			Utils.log2("Oops: " + e);
		}
		return false;
	}

	/** Smoothly move the canvas from x0,y0,layer0 to x1,y1,layer1 */
	protected void animateBrowsing(final int dx, final int dy) {
		// check preconditions
		final float mag = (float)this.magnification;
		final Rectangle startSrcRect = (Rectangle)this.srcRect.clone();
		// The motion will be displaced by some screen pixels at every time step.
		final Vector2f v = new Vector2f(dx, dy);
		final float sqdist_to_travel = v.lengthSquared();
		v.normalize();
		v.scale(20/mag);
		final Point2f cp = new Point2f(0, 0); // the current deltas
		//
		final ScheduledFuture<?>[] sf = new ScheduledFuture[1];
		sf[0] = animate(new Runnable() {
			@Override
			public void run() {
				cp.add(v);
				//Utils.log2("advanced by x,y = " + cp.x + ", " + cp.y);
				int x, y;
				if (v.lengthSquared() >= sqdist_to_travel) {
					// set target position
					x = startSrcRect.x + dx;
					y = startSrcRect.y + dy;
					// quit animation
					cancelAnimation(sf[0]);
				} else {
					// set position
					x = startSrcRect.x + (int)(cp.x);
					y = startSrcRect.y + (int)(cp.y);
				}
				setSrcRect(x, y, startSrcRect.width, startSrcRect.height);
				display.repaintAll2();
			}
		}, 0, 50, TimeUnit.MILLISECONDS);
	}

	/** Smoothly move the canvas from its current position until the given rectangle is included within the srcRect.
	 * If the given rectangle is larger than the srcRect, it will refuse to work (for now). */
	public boolean animateBrowsing(final Rectangle target_, final Layer target_layer) {
		// Crop target to world's 2D dimensions
		final Area a = new Area(target_);
		a.intersect(new Area(display.getLayerSet().get2DBounds()));
		final Rectangle target = a.getBounds();
		if (0 == target.width || 0 == target.height) {
			return false;
		}
		// animate at all?
		if (this.srcRect.contains(target) && target_layer == display.getLayer()) {
			// So: don't animate, but at least highlight the target
			playHighlight(target);
			return false;
		}

		// The motion will be displaced by some screen pixels at every time step.
		final int ox = srcRect.x + srcRect.width/2;
		final int oy = srcRect.y + srcRect.height/2;
		final int tx = target.x + target.width/2;
		final int ty = target.y + target.height/2;
		final Vector2f v = new Vector2f(tx - ox, ty - oy);
		v.normalize();
		v.scale(20/(float)magnification);


		// The layer range
		final Layer start_layer = display.getLayer();
		/*
		int ithis = display.getLayerSet().indexOf(start_layer);
		int itarget = display.getLayerSet().indexOf(target_layer);
		final java.util.List<Layer> layers = display.getLayerSet().getLayers(ithis, itarget);
		*/
		final Calibration cal = display.getLayerSet().getCalibrationCopy();
		final double pixelWidth = cal.pixelWidth;
		final double pixelHeight = cal.pixelHeight;

		//final double dist_to_travel = Math.sqrt(Math.pow((tx - ox)*pixelWidth, 2) + Math.pow((ty - oy)*pixelHeight, 2)
		//				                + Math.pow((start_layer.getZ() - target_layer.getZ()) * pixelWidth, 2));

		// vector in calibrated coords between origin and target
		final Vector3d g = new Vector3d((tx - ox)*pixelWidth, (ty - oy)*pixelHeight, (target_layer.getZ() - start_layer.getZ())*pixelWidth);

		final ScheduledFuture<?>[] sf = new ScheduledFuture[1];
		sf[0] = animate(new Runnable() {
			@Override
			public void run() {
				if (DisplayCanvas.this.srcRect.contains(target)) {
					// reached destination
					if (display.getLayer() != target_layer) display.toLayer(target_layer);
					playHighlight(target);
					cancelAnimation(sf[0]);
				} else {
					setSrcRect(srcRect.x + (int)v.x, srcRect.y + (int)v.y, srcRect.width, srcRect.height);
					// which layer?
					if (start_layer != target_layer) {
						final int cx = srcRect.x + srcRect.width/2;
						final int cy = srcRect.y + srcRect.height/2;
						final double dist = Math.sqrt(Math.pow((cx - ox)*pixelWidth, 2) + Math.pow((cy - oy)*pixelHeight, 2)
									+ Math.pow((display.getLayer().getZ() - start_layer.getZ()) * pixelWidth, 2));

						final Vector3d gg = new Vector3d(g);
						gg.normalize();
						gg.scale((float)dist);
						final Layer la = display.getLayerSet().getNearestLayer(start_layer.getZ() + gg.z/pixelWidth);
						if (la != display.getLayer()) {
							display.toLayer(la);
						}
					}
					display.repaintAll2();
				}
			}
		}, 0, 50, TimeUnit.MILLISECONDS);
		return true;
	}

	private ScheduledExecutorService animator = null;
	private boolean zoom_and_pan = true;
	private final Vector<ScheduledFuture<?>> sfs = new Vector<ScheduledFuture<?>>();

	private void cancelAnimations() {
		if (sfs.isEmpty()) return;
		Vector<ScheduledFuture<?>> sfs;
		synchronized (this.sfs) { sfs = new Vector<ScheduledFuture<?>>(this.sfs); }
		for (final ScheduledFuture<?> sf : sfs) {
			sf.cancel(true);
		}
		this.sfs.clear();
		try {
			// wait
			Thread.sleep(150);
		} catch (final InterruptedException ie) {}
		// Re-enable input, in case the watcher task is canceled as well:
		// (It's necessary since there isn't any easy way to tell the scheduler to execute a code block when it cancels its tasks).
		restoreUserInput();
	}
	private void cancelAnimation(final ScheduledFuture<?> sf) {
		sfs.remove(sf);
		sf.cancel(true);
		restoreUserInput();
	}

	private void restoreUserInput() {
		zoom_and_pan = true;
		display.getProject().setReceivesInput(true);
	}

	private ScheduledFuture<?> animate(final Runnable run, final long initialDelay, final long delay, final TimeUnit units) {
		initAnimator();
		// Cancel any animations currently running
		cancelAnimations();
		// Disable user input
		display.getProject().setReceivesInput(false);
		zoom_and_pan = false;
		// Create tasks to run periodically: a task and a watcher task
		final ScheduledFuture<?>[] sf = new ScheduledFuture[2];
		sf[0] = animator.scheduleWithFixedDelay(run, initialDelay, delay, units);
		sf[1] = animator.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				if (sf[0].isCancelled()) {
					// Enable user input
					zoom_and_pan = true;
					display.getProject().setReceivesInput(true);
					// cancel yourself
					sf[1].cancel(true);
				}
			}
		}, 100, 700, TimeUnit.MILLISECONDS);
		// Store task for future cancelation
		sfs.add(sf[0]);
		// but not the watcher task, which must finish on its own after the main task finishes.
		return sf[0];
	}

	/** Draw a dotted circle centered on the given Rectangle. */
	private final class Highlighter {
		Ellipse2D.Float elf;
		final Stroke stroke = new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3, new float[]{4,4,4,4}, 0);
		final float dec;
		final Rectangle target;
		Highlighter(final Rectangle target) {
			this.target = target;
			elf = new Ellipse2D.Float(target.x, target.y, target.width, target.height);
			display.getLayerSet().getOverlay().add(elf, Color.yellow, stroke, true);
			dec = (float)((Math.max(target.width, target.height)*magnification / 10)/magnification);
		}
		boolean next() {
			invalidateVolatile();
			repaint(target, 5, false);
			// setup next iteration
			display.getLayerSet().getOverlay().remove(elf);
			final Ellipse2D.Float elf2 = (Ellipse2D.Float) elf.clone();
			elf2.x += dec;
			elf2.y += dec;
			elf2.width -= (dec+dec);
			elf2.height -= (dec+dec);
			if (elf2.width > 1 || elf2.height > 1) {
				elf = elf2;
				display.getLayerSet().getOverlay().add(elf, Color.yellow, stroke, true);
				return true;
			} else {
				display.getLayerSet().getOverlay().remove(elf);
				return false;
			}
		}
		void cleanup() {
			display.getLayerSet().getOverlay().remove(elf);
		}
	}

	private interface Animation extends Runnable {}

	private ScheduledFuture<?> playHighlight(final Rectangle target) {
		initAnimator();
		final Highlighter highlight = new Highlighter(target);
		final ScheduledFuture<?>[] sf = (ScheduledFuture<?>[])new ScheduledFuture[2];
		sf[0] = animator.scheduleWithFixedDelay(new Animation() {
			@Override
			public void run() {
				if (!highlight.next()) {
					cancelAnimation(sf[0]);
					highlight.cleanup();
				}
			}
		}, 10, 100, TimeUnit.MILLISECONDS);
		sf[1] = animator.scheduleWithFixedDelay(new Animation() {
			@Override
			public void run() {
				if (sf[0].isCancelled()) {
					highlight.cleanup();
					sf[1].cancel(true); // itself
				}
			}
		}, 50, 100, TimeUnit.MILLISECONDS);
		sfs.add(sf[0]);
		return sf[0];
	}

	synchronized private void initAnimator() {
		if (null == animator) animator = Executors.newScheduledThreadPool(2);
	}
}
