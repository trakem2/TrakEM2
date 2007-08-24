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

//import java.awt.Canvas;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.Rectangle;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseEvent;
import java.util.Iterator;
import java.util.ArrayList;
import ini.trakem2.utils.*;
import java.awt.geom.AffineTransform;

public class DisplayNavigator extends JPanel implements MouseListener, MouseMotionListener {

	private Display display;
	static private final int FIXED_WIDTH = 250;
	private int height;
	private BufferedImage image = null;
	private boolean redraw_displayables = true;
	private double scale;
	private Rectangle srcRect;
	private int x_p, y_p;
	private int new_x_old=0, new_y_old=0;
	/* // TODO
	private RepaintThread rt;
	private Object repainting_ob = new Object();
	private boolean repainting = false;
	private UpdateGraphicsThread ugt;
	private updating_ob = new Object();
	private boolean updating = false;
	*/

	private RepaintThread rt = null;
	private UpdateGraphicsThread ugt = null;
	private final Object updating_ob = new Object();
	private boolean updating = false;

	DisplayNavigator(Display display, double layer_width, double layer_height) { // contorsions to avoid java bugs ( a.k.a. the 'this' is not functional until the object in question has finished initialization.
		this.display = display;
		this.scale = FIXED_WIDTH / layer_width;
		this.height = (int)(layer_height * scale);
		//Utils.log("fixed_w, h: " + FIXED_WIDTH +","+ height + "   layer_width,height: " + layer_width + "," + layer_height);
		Dimension d = new Dimension(FIXED_WIDTH, height);
		setPreferredSize(d);
		setMinimumSize(d);
		setMaximumSize(d);
		addMouseMotionListener(this);
		addMouseListener(this);
		addKeyListener(display.getCanvas());
	}

	/** Fixes size if changed. Multithreaded. */
	public void repaint() {
		if (null == display || null == display.getCanvas() || null == display.getLayer() || display.getCanvas().isDragging()) return;
		// fixing null at start up (because the JPanel becomes initialized and repainted before returning to my subclass constructor! Stupid java!)
		if (null == display) return;
		scale = FIXED_WIDTH / display.getLayer().getLayerWidth();
		int height = (int)(display.getLayer().getLayerHeight() * scale);
		if (height != this.height) {
			Dimension d = new Dimension(FIXED_WIDTH, height);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d); //this triple set *should* update the values in the super class JPanel
			redraw_displayables = true;
			this.height = height;
		}
		//Utils.log2("w,h: " + FIXED_WIDTH + "," + height + ",   scale: " + scale);
		// magic cocktel:
		//this.invalidate();
		//this.validate();  // possible cause of infinite loops with infinite threads
		new RepaintThread(null);
	}

	public void repaint(boolean update_graphics) {
		redraw_displayables = update_graphics;
		repaint();
	}

	/** Only its bounding box. */ // TODO problems: when the object has been moved, it leaves a trail (no repainting of the old position). So this is for now only useful for the setVisible (where the object doesn't move)
	public void repaint(Displayable d) {
		if (display.getCanvas().isDragging()) return;
		redraw_displayables = true;
		new RepaintThread(new Rectangle((int)(d.x * scale), (int)(d.y * scale), (int)Math.ceil(d.width * scale), (int)Math.ceil(d.height * scale))); // accessing protected vars directly within the package, WARNING
	}

	/** Overridden to multithread. TrakEM2 does not call this method directly ever. */
	public void repaint(int x, int y, int width, int height) {
		if (display.getCanvas().isDragging()) return;
		new RepaintThread(new Rectangle(x, y, width, height));
	}

	/** Box is given in offscreen canvas coords. */
	public void repaint(Rectangle box) {
		if (display.getCanvas().isDragging()) return;
		// bring box to the scale
		Rectangle b = new Rectangle((int)(box.x * scale), (int)(box.y * scale), (int)Math.ceil(box.width * scale), (int)Math.ceil(box.height * scale));
		new RepaintThread(b);
	}

	/* // saved as unoverridden to make sure there are no infinite thread loops when calling super in buggy JVMs
	public void repaint(long ms, int x, int y, int width, int height) {
		new RepaintThread(new Rectangle(x, y, width, height));
	}
	*/

	public void update(Graphics g) {
		paint(g);
	}

	private class UpdateGraphicsThread extends Thread {

		private boolean quit = false;

		UpdateGraphicsThread() {
			synchronized (updating_ob) {
				while (updating) {
					try {
						if (quit) return;
						updating_ob.wait();
					} catch (InterruptedException ie) {}
				}
				updating = true;
				if (null != ugt) {
					ugt.quit = true;
				}
				ugt = this;
				quit = false;
				updating = false;
				updating_ob.notifyAll();
			}
			Thread.yield();
			setPriority(Thread.NORM_PRIORITY);
			start();
		}

		public void quit() {
			quit = true;
		}

		/** paint all snapshots, scaled, to an offscreen awt.Image */
		public void run() {
			synchronized (updating_ob) {
			try {
				while (updating) {
					try { updating_ob.wait(); } catch (InterruptedException ie) {}
				}
				updating = true;
				if (quit) {
					updating = false;
					updating_ob.notifyAll();
					return;
				}
				height = DisplayNavigator.super.getHeight();
				if (null == image) {
					image = new BufferedImage(FIXED_WIDTH, height, BufferedImage.TYPE_INT_ARGB); // looks very crappy with RGB images// BufferedImage.TYPE_BYTE_INDEXED);
					// for efficiency with large montages, the Display should be queried to find out if any RGB image is present TODO same with the snapshot background one
				}
				if (quit) {
					updating = false;
					updating_ob.notifyAll();
					return;
				}

				//g2d.getRenderingHints().put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				//g2d.getRenderingHints().put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
				//Rectangle clipRect = g.getClipBounds();

				final Graphics2D graphics = image.createGraphics();
				graphics.setColor(Color.black);
				graphics.fillRect(0, 0, DisplayNavigator.super.getWidth(), DisplayNavigator.super.getHeight());
				// set a scaled stroke, or 0.4 if too small
				if (scale >= 0.4D) graphics.setStroke(new BasicStroke((float)scale));
				else graphics.setStroke(new BasicStroke(0.4f));
				// paint background as black
				graphics.setColor(Color.black);
				graphics.fillRect(0, 0, FIXED_WIDTH, height);

				graphics.scale(scale, scale);

				if (quit) {
					updating = false;
					updating_ob.notifyAll();
					return;
				}

				final ArrayList al = display.getLayer().getDisplayables();
				final int size = al.size();
				boolean zd_done = false;
				for (int i=0; i<size; i++) {
					if (quit) {
						updating = false;
						updating_ob.notifyAll();
						return;
					}
					final Displayable d = (Displayable)al.get(i);
					//if (d.isOutOfRepaintingClip(clip, scale)) continue; // needed at least for the visibility
					if (!d.isVisible()) continue; // TODO proper clipRect for this navigator image may be necessary (lots of changes needed in the lines above reltive to filling the black background, etc)
					if (!zd_done && d instanceof DLabel) {
						zd_done = true;
						// paint ZDisplayables before the labels
						Iterator itz = display.getLayer().getParent().getZDisplayables().iterator();
						while (itz.hasNext()) {
							if (quit) {
								updating = false;
								updating_ob.notifyAll();
								return;
							}
							ZDisplayable zd = (ZDisplayable)itz.next();
							if (!zd.isVisible()) continue;
							zd.getSnapshot().paintTo(graphics, display.getLayer());
						}
					}
					d.getSnapshot().paintTo(graphics, display.getLayer());
				}
				if (!zd_done) { // if no labels, ZDisplayables haven't been painted
					zd_done = true;
					// paint ZDisplayables before the labels
					Iterator itz = display.getLayer().getParent().getZDisplayables().iterator();
					while (itz.hasNext()) {
						if (quit) {
							updating = false;
							updating_ob.notifyAll();
							return;
						}
						ZDisplayable zd = (ZDisplayable)itz.next();
						if (!zd.isVisible()) continue;
						zd.getSnapshot().paintTo(graphics, display.getLayer());
					}
				}
				updating = false;
				updating_ob.notifyAll();
			} catch (Exception e) {
				if (updating) {
					updating = false;
					try {
						updating_ob.notifyAll();
					} catch (Exception ee) {
						Utils.log2("Navigator: synchronization issues.");
					}
				}
				new IJError(e);
			}
			}
		}
	}

	public void paint(Graphics g) {
		Graphics2D g2d = (Graphics2D)g;
		synchronized (updating_ob) {
			while (updating) { try { updating_ob.wait(); } catch (InterruptedException ie) {} }
			updating = true;
			if (null != image) {
				g.drawImage(image, 0, 0, FIXED_WIDTH, this.height, null);
			}
			//Utils.log2("this.height:" + this.height);
			//Utils.log2("super.height: " + super.getHeight());
			updating = false;
			updating_ob.notifyAll();
		}
		// paint red rectangle indicating srcRect
		Rectangle srcRect = display.getCanvas().getSrcRect();
		g.setColor(Color.red);
		g2d.setStroke(new BasicStroke(2.0f));
		int gw = (int)(srcRect.width * scale) -2;
		int gh = (int)(srcRect.height * scale) -2;
		if (gw < 5) gw = 5;
		if (gh < 5) gh = 5;
		g.drawRect((int)(srcRect.x * scale) +1, (int)(srcRect.y * scale) +1, gw, gh);
	}


	private final Object control_lock = new Object();
	private boolean controling = false;

	private class RepaintThread extends Thread {

		private boolean quit = true;
		private Rectangle clipRect = null;

		RepaintThread(Rectangle clipRect_) {
			synchronized (control_lock) {
				while (controling) { try { control_lock.wait(); } catch (InterruptedException ie) {}}
				controling = true;
				if (null != rt) {
					rt.quit = true;
					Thread.yield();
					// accumulate clipRect. A null clipRect means the entire area
					if (null != clipRect) {
						if (null == clipRect_) clipRect = new Rectangle(0, 0, FIXED_WIDTH, height);
						else clipRect.add(clipRect_);
					}
				} else {
					clipRect = clipRect_;
				}
				rt = this;
				controling = false;
				control_lock.notifyAll();
			}
			Thread.yield(); // still the launcher thread
			if (redraw_displayables) {
				new UpdateGraphicsThread();
				redraw_displayables = false; // reset
			}
			quit = false;
			setPriority(Thread.NORM_PRIORITY);
			start();
		}

		public void quit() {
			quit = true;
		}

		public void run() {
			if (quit) return;
			// now wait for the image to be done
			synchronized (updating_ob) {
				while (updating) {
					if (quit) return;
					try {
						updating_ob.wait();
					} catch (InterruptedException ie) {}
				}
			}
			// this is the only place where the real, super repaint is to be called directly
			if (null == clipRect) DisplayNavigator.super.repaint(0, 0, 0, FIXED_WIDTH, height); // calling super.repaint() causes infinite loops of RepaintThread instances, for unknown reasons in the IBM-1.4.2-ppc. The method long, int, int, int, int exists in the JComponent and holds ...
			else DisplayNavigator.super.repaint(0, clipRect.x, clipRect.y, clipRect.width, clipRect.height);
		}
	}

	public void mousePressed(MouseEvent me) {
		x_p = me.getX();
		y_p = me.getY();
		this.srcRect = (Rectangle)display.getCanvas().getSrcRect().clone();
	}

	public void mouseDragged(MouseEvent me) {
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
		if (new_x + srcRect.width > (int)(this.getWidth() / scale)) new_x = (int)(this.getWidth() / scale - srcRect.width);
		if (new_y + srcRect.height > (int)(this.getHeight() / scale)) new_y = (int)(this.getHeight() / scale - srcRect.height);
		if (new_x_old == new_x && new_y_old == new_y) {
			// avoid repaints
			return;
		}
		new_x_old = new_x;
		new_y_old = new_y;
		DisplayCanvas canvas = display.getCanvas();
		canvas.setSrcRect(new_x, new_y, this.srcRect.width, this.srcRect.height);
		canvas.repaint(true);
		this.repaint();
	}

	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
	public void mouseMoved(MouseEvent me) {}

	/** Release resources. */
	public void destroy() {
		synchronized (updating_ob) {
			while (updating) try { updating_ob.wait(); } catch (InterruptedException ie) {}
			updating = true;
			if (null != ugt) ugt.quit();
			if (null != rt) rt.quit();
			updating = false;
			updating_ob.notifyAll();
		}
		Thread.yield();
		synchronized (updating_ob) {
			while (updating) try { updating_ob.wait(); } catch (InterruptedException ie) {}
			updating = true;
			if (null != image) {
				image.flush();
				image = null;
			}
			updating = false;
			updating_ob.notifyAll();
		}
	}
}
