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

//import java.awt.Canvas;
import ini.trakem2.utils.IJError;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;

public final class DisplayNavigator extends JPanel implements MouseListener, MouseMotionListener {

	private static final long serialVersionUID = 1L;

	private Display display;
	private Layer layer;
	private Set<Displayable> hs_painted = Collections.synchronizedSet(new HashSet<Displayable>());
	static private final int SIDE = 250;
	private BufferedImage image = null;
	private boolean redraw_displayables = true;
	private double scale;
	private Rectangle srcRect;
	private int x_p, y_p;
	private int new_x_old=0, new_y_old=0;

	private final Object offscreen_lock = new Object();
	private final HashSet<BufferedImage> to_flush = new HashSet<BufferedImage>();

	private VolatileImage volatileImage;
	private final Object volatile_lock = new Object();
	private boolean invalid_volatile = false;

	DisplayNavigator(Display display, double layer_width, double layer_height) { // contorsions to avoid java bugs ( a.k.a. the 'this' is not functional until the object in question has finished initialization.
		this.display = display;
		this.layer = display.getLayer();
		this.scale = Math.min(SIDE / layer_width, SIDE / layer_height);
		Dimension d = new Dimension(SIDE, SIDE);
		setPreferredSize(d);
		setMinimumSize(d);
		setMaximumSize(d);
		addMouseMotionListener(this);
		addMouseListener(this);
		addKeyListener(display.getCanvas());
	}

	/** Multithreaded. */
	public void repaint() {
		if (null == display || null == display.getCanvas() || null == display.getLayer() || display.getCanvas().isDragging()) return;
		// fixing null at start up (because the JPanel becomes initialized and repainted before returning to my subclass constructor! Stupid java!)
		if (null == display) return;

		//check if layer has changed
		if (this.layer != display.getLayer()) {
			this.layer = display.getLayer();
		}

		RT.paint(null, redraw_displayables);
	}

	public void repaint(boolean update_graphics) {
		redraw_displayables = update_graphics;
		invalidateVolatile();
		repaint();
	}

	/** Only its bounding box. */ // TODO problems: when the object has been moved, it leaves a trail (no repainting of the old position). So this is for now only useful for the setVisible (where the object doesn't move)
	public void repaint(Displayable d) {
		if (display.getCanvas().isDragging()) return;
		redraw_displayables = true;
		invalidateVolatile();
		final Rectangle r = d.getBoundingBox(null);
		r.x = (int)(r.x * scale);
		r.y = (int)(r.y * scale);
		r.width = (int)Math.ceil(r.width * scale);
		r.height = (int)Math.ceil(r.height * scale);
		RT.paint(r, redraw_displayables);
	}

	/** Overridden to multithread. TrakEM2 does not call this method directly ever. */
	public void repaint(int x, int y, int width, int height) {
		if (display.getCanvas().isDragging()) return;
		RT.paint(new Rectangle(x, y, width, height), redraw_displayables);
	}

	/** Box is given in offscreen canvas coords. */
	public void repaint(Rectangle box) {
		if (null == box || display.getCanvas().isDragging()) return;
		// bring box to the scale
		Rectangle b = new Rectangle((int)(box.x * scale), (int)(box.y * scale), (int)Math.ceil(box.width * scale), (int)Math.ceil(box.height * scale));
		RT.paint(b, redraw_displayables);
	}

	/* // saved as not overridden to make sure there are no infinite thread loops when calling super in buggy JVMs
	public void repaint(long ms, int x, int y, int width, int height) {
		RT.paint(new Rectangle(x, y, width, height));
	}
	*/

	public void update(Graphics g) {
		paint(g);
	}


	private int snapshots_mode = 0;

	private class RepaintProperties implements AbstractOffscreenThread.RepaintProperties {
		final Rectangle clipRect;
		final int snapshots_mode;
		final Layer layer;
		final List<Layer> layers;

		RepaintProperties(final Rectangle clipRect, final Layer layer, final List<Layer> layers, final int snapshots_mode) {
			this.clipRect = clipRect;
			this.layer = layer;
			this.layers = layers;
			this.snapshots_mode = snapshots_mode;
		}
	}

	private final class UpdateGraphicsThread extends AbstractOffscreenThread {

		UpdateGraphicsThread() {
			super("T2-Navigator-UpdateGraphics");
		}

		/** paint all snapshots, scaled, to an offscreen awt.Image */
		public void paint() {

			final Layer layer;
			final List<Layer> layers;
			final int snapshots_mode;
			final Rectangle clipRect;
			final Rectangle srcRect;

			synchronized (this) {
				DisplayNavigator.RepaintProperties rp = (DisplayNavigator.RepaintProperties) this.rp;
				layer = rp.layer;
				layers = rp.layers;
				snapshots_mode = rp.snapshots_mode;
				clipRect = rp.clipRect;
				srcRect = layer.getParent().get2DBounds(); // The whole canvas!
			}

			if (null != DisplayNavigator.this.image && 2 == snapshots_mode && DisplayNavigator.this.snapshots_mode == snapshots_mode) {
				DisplayNavigator.this.redraw_displayables = false;
				RT.paint(clipRect, false);
				return;
			} else {
				DisplayNavigator.this.snapshots_mode = snapshots_mode;
			}

			final BufferedImage target = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
			scale = Math.min(SIDE / layer.getLayerWidth(), SIDE / layer.getLayerHeight());

			try {
				final Graphics2D g = target.createGraphics();
				int lw = (int) layer.getLayerWidth();
				int lh = (int) layer.getLayerHeight();
				if (lw != lh) {
					g.setColor(Color.gray);
					int bx = (int)(scale * lw);
					g.fillRect(bx, 0, SIDE - bx, SIDE);
					int by = (int)(scale * lh);
					g.fillRect(0, by, bx, SIDE - by);
					g.setColor(Color.black);
					g.fillRect(0, 0, bx, by);
				} else {
					g.setColor(Color.black);
					g.fillRect(0, 0, SIDE, SIDE);
				}
				
				hs_painted.clear();

				// check if disabled
				if (2 != snapshots_mode) {
					// set a scaled stroke, or 0.4 if too small
					if (scale >= 0.4D) g.setStroke(new BasicStroke((float)scale));
					else g.setStroke(new BasicStroke(0.4f));

					g.scale(scale, scale);

					final ArrayList<Displayable> al = display.getLayer().getDisplayables();
					final int size = al.size();
					boolean zd_done = false;
					for (int i=0; i<size; i++) {
						final Displayable d = (Displayable)al.get(i);
						//if (d.isOutOfRepaintingClip(clip, scale)) continue; // needed at least for the visibility
						if (!d.isVisible()) continue; // TODO proper clipRect for this navigator image may be necessary (lots of changes needed in the lines above reltive to filling the black background, etc)
						final Class<?> c = d.getClass();
						if (!zd_done && DLabel.class == c) {
							zd_done = true;
							// paint ZDisplayables before the labels (i.e. text labels on top)
							final Iterator<ZDisplayable> itz = display.getLayer().getParent().getZDisplayables().iterator();
							while (itz.hasNext()) {
								ZDisplayable zd = (ZDisplayable)itz.next();
								if (!zd.isVisible()) continue;
								zd.paintSnapshot(g, layer, layers, srcRect, scale);
							}
							// paint the label too!
							d.paint(g, srcRect, scale, false, 1, DisplayNavigator.this.layer, layers);
						} else if (Patch.class == c) {
							if (0 == snapshots_mode) {
								// paint fully
								final Patch p = (Patch)d;
								final MipMapImage mipMap = d.getProject().getLoader().getCachedClosestAboveImage(p, scale);
								if (null != mipMap) {
									if (d.isVisible()) d.paint(g, srcRect, scale, false, p.getChannelAlphas(), layer, layers);
									hs_painted.add(d);
								} else  {
									d.paintAsBox(g);
								}
							} else {
								// paint as outlines
								d.paintAsBox(g);
							}
						} else {
							if (d.isVisible()) d.paint(g, srcRect, scale, false, 1, layer, layers);
						}
					}
					if (!zd_done) { // if no labels, ZDisplayables haven't been painted
						zd_done = true;
						// paint ZDisplayables before the labels
						for (final ZDisplayable zd : display.getLayer().getParent().getZDisplayables()) {
							if (!zd.isVisible()) continue;
							zd.paintSnapshot(g, layer, layers, srcRect, scale);
						}
					}
				}
				// finally, when done, call repaint (like sending an event)

				// block only while modifying the image pointer
				synchronized (offscreen_lock) {
					if (null != DisplayNavigator.this.image) to_flush.add(DisplayNavigator.this.image);
					DisplayNavigator.this.image = target;
					redraw_displayables = false;
				}
				// Outside, otherwise would deadlock
				invalidateVolatile();
				RT.paint(clipRect, false);
			} catch (Exception e) {
				IJError.print(e);
			}
		}
	}

	private void renderVolatileImage(final GraphicsConfiguration gc, final BufferedImage image) {
		do {
			if (volatileImage == null || volatileImage.getWidth() != SIDE
					|| volatileImage.getHeight() != SIDE
					|| volatileImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
				if (volatileImage != null) {
					volatileImage.flush();
				}
				volatileImage = gc.createCompatibleVolatileImage(SIDE, SIDE);
				volatileImage.setAccelerationPriority(1.0f);
				invalid_volatile = false;
			}
			// 
			// Now paint the BufferedImage into the accelerated image
			//
			final Graphics2D g = volatileImage.createGraphics();
			if (null != image) g.drawImage(image, 0, 0, SIDE, SIDE, null);

			// paint red rectangle indicating srcRect
			final Rectangle srcRect = display.getCanvas().getSrcRect();
			g.setColor(Color.red);
			g.setStroke(new BasicStroke(2.0f));
			int gw = (int)(srcRect.width * scale) -2;
			int gh = (int)(srcRect.height * scale) -2;
			if (gw < 5) gw = 5;
			if (gh < 5) gh = 5;
			g.drawRect((int)(srcRect.x * scale) +1, (int)(srcRect.y * scale) +1, gw, gh);

		} while (volatileImage.contentsLost());
	}

	private void render(final Graphics g) {
		final Graphics2D g2d = (Graphics2D) g.create();
		g2d.setRenderingHints(DisplayCanvas.rhints);
		final GraphicsConfiguration gc = getGraphicsConfiguration(); // outside synch, avoid deadlocks
		do {
			final BufferedImage image;
			synchronized (offscreen_lock) {
				image = this.image;
			}
			// Protect volatileImage while generating it
			synchronized (volatile_lock) {
				if (invalid_volatile || null == volatileImage
				 || volatileImage.validate(gc) != VolatileImage.IMAGE_OK)
				{
					renderVolatileImage(gc, image);
				}
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

	public void invalidateVolatile() {
		synchronized (volatile_lock) {
			invalid_volatile = true;
		}
	}

	public void paint(final Graphics g) {
		if (null == g) return;
		render(g);
	}

	/** Handles repaint event requests and the generation of offscreen threads. */
	private final AbstractRepaintThread RT = new AbstractRepaintThread(this, "T2-Navigator-Repainter", new UpdateGraphicsThread()) {
		protected void handleUpdateGraphics(Component target, Rectangle clipRect) {
			this.off.setProperties(new RepaintProperties(clipRect, layer, layer.getParent().getColorCueLayerRange(layer), layer.getParent().getSnapshotsMode()));
		}
	};

	private boolean drag = false;

	public void mousePressed(final MouseEvent me) {
		x_p = me.getX();
		y_p = me.getY();
		this.srcRect = (Rectangle)display.getCanvas().getSrcRect().clone();
		// prevent dragging unless mouse is inside the red box
		if (srcRect.contains((int)(x_p / scale), (int)(y_p / scale))) {
			drag = true;
		}
	}

	public void mouseDragged(final MouseEvent me) {
		if (!drag) return;
		// prevent action if the srcRect takes over the whole area
		if (this.srcRect.width == display.getLayer().getLayerWidth() && this.srcRect.height == display.getLayer().getLayerHeight()) {
			return;
		}
		int x_d = me.getX();
		int y_d = me.getY();
		// prevent dragging beyond screen
		if (x_d > this.getWidth() || x_d < 0 || y_d > this.getHeight() || y_d < 0) {
			return;
		}
		int new_x = srcRect.x + (int)((x_d - x_p) / scale);
		int new_y = srcRect.y + (int)((y_d - y_p) / scale);
		if (new_x < 0) new_x = 0;
		if (new_y < 0) new_y = 0;
		int slw = (int)(this.scale * display.getLayer().getLayerWidth());
		int slh = (int)(this.scale * display.getLayer().getLayerHeight());
		if (new_x + srcRect.width > (int)(slw / scale)) new_x = (int)(slw / scale - srcRect.width);
		if (new_y + srcRect.height > (int)(slh / scale)) new_y = (int)(slh / scale - srcRect.height);
		if (new_x_old == new_x && new_y_old == new_y) {
			// avoid repaints
			return;
		}
		new_x_old = new_x;
		new_y_old = new_y;
		DisplayCanvas canvas = display.getCanvas();
		canvas.setSrcRect(new_x, new_y, this.srcRect.width, this.srcRect.height);
		canvas.repaint(true);
		invalidateVolatile();
		this.repaint();
	}

	public void mouseReleased(MouseEvent me) { drag = false; }
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
	public void mouseMoved(MouseEvent me) {}

	/** Release resources. */
	public void destroy() {
		RT.quit();
		synchronized (offscreen_lock) {
			if (null != image) {
				image.flush();
				image = null;
			}
			redraw_displayables = true;
			for (final BufferedImage bi : to_flush) bi.flush();
			to_flush.clear();
		}
	}

	/** Returns true if the given Displayable has been painted as an image and false if as a box or not at all. */
	public boolean isPainted(Displayable d) {
		return hs_painted.contains(d);
	}
}
