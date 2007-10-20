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
import ij.io.*;
import ij.gui.*;
import ij.process.*;
import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.IJError;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.imaging.LayerStack;
import ini.trakem2.imaging.Registration;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.DNDInsertImage;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.tree.*;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.lang.reflect.Method;
import java.io.File;

/** A Display is a class to show a Layer and enable mouse and keyboard manipulation of all its components. */
public class Display extends DBObject implements ActionListener, ImageListener {

	/** The Layer this Display is showing. */
	private Layer layer;

	private Displayable active = null;
	/** All selected Displayable objects, including the active one. */
	final private Selection selection = new Selection(this);

	private ImagePlus active_imp_copy = null;
	private long active_imp_id = -1;
	private boolean undo = false;

	private ImagePlus last_temp = null;

	private JFrame frame;
	private JTabbedPane tabs;
	private Hashtable ht_tabs;
	private JScrollPane scroll_patches;
	private JPanel panel_patches;
	private JScrollPane scroll_profiles;
	private JPanel panel_profiles;
	private JScrollPane scroll_zdispl;
	private JPanel panel_zdispl;
	private JScrollPane scroll_channels;
	private JPanel panel_channels;
	private JScrollPane scroll_labels;
	private JPanel panel_labels;

	private JSlider transp_slider;
	private DisplayNavigator navigator;
	private JScrollBar scroller;

	private DisplayCanvas canvas; // WARNING this is an AWT component, since it extends ImageCanvas
	private JPanel canvas_panel; // and this is a workaround, to better (perhaps) integrate the awt canvas inside a JSplitPane
	private JSplitPane split;

	private JPopupMenu popup = null;

	/** Contains the packed alphas of every channel. */
	private int c_alphas = 0xffffffff; // all 100 % visible
	private Channel[] channels;

	private Hashtable hs_panels = new Hashtable();

	/** Handle drop events, to insert image files. */
	private DNDInsertImage dnd;

	private boolean size_adjusted = false;

	private int scroll_step = 1;

	//private boolean preload_snapshots = true;

	/** Keep track of all existing Display objects. */
	static private ArrayList al_displays = new ArrayList();
	/** The currently focused Display, if any. */
	static private Display front = null;

	/** Displays to open when all objects have been reloaded from the database. */
	static private Hashtable ht_later = null;

	static private WindowAdapter window_listener = new WindowAdapter() {
		/** Unregister the closed Display. */
		public void windowClosing(WindowEvent we) {
			Object source = we.getSource();
			Iterator it = al_displays.iterator();
			while (it.hasNext()) {
				Display d = (Display)it.next();
				if (source.equals(d.frame)) {
					it.remove();
					if (d == front) front = null;
					d.remove(false); //calls destroy
					break;
				}
			}
		}
		/** Set the source Display as front. */
		public void windowActivated(WindowEvent we) {
			// find which was it to make it be the front
			Object source = we.getSource();
			Iterator it = al_displays.iterator();
			while (it.hasNext()) {
				Display d = (Display)it.next();
				if (source.equals(d.frame)) {
					front = d;
					// set toolbar
					ProjectToolbar.setProjectToolbar();
					// now, select the layer in the LayerTree
					front.getProject().select(front.layer);
					// finally, set the virtual ImagePlus that ImageJ will see
					d.setTempCurrentImage();
					// copied from ij.gui.ImageWindow, with modifications
					if (IJ.isMacintosh() && IJ.getInstance()!=null) {
						IJ.wait(10); // may be needed for Java 1.4 on OS X
						d.frame.setMenuBar(ij.Menus.getMenuBar());
					}
					return;
				}
			}
			// else, restore the ImageJ toolbar for non-project images
			//if (!source.equals(IJ.getInstance())) {
			//	ProjectToolbar.setImageJToolbar();
			//}
		}
		/** Restore the ImageJ toolbar */
		public void windowDeactivated(WindowEvent we) {
			// Can't, the user can never click the ProjectToolbar then. This has to be done in a different way, for example checking who is the WindowManager.getCurrentImage (and maybe setting a dummy image into it) //ProjectToolbar.setImageJToolbar();
		}
		/** Call a pack() when the window is maximized to fit the canvas correctly. */
		public void windowStateChanged(WindowEvent we) {
			Object source = we.getSource();
			Iterator it = al_displays.iterator();
			while (it.hasNext()) {
				Display d = (Display)it.next();
				d.frame.pack();
				break;
			}
		}
	};

	static private MouseListener frame_mouse_listener = new MouseAdapter() {
		public void mouseReleased(MouseEvent me) {
			Object ob = me.getSource();
			if (ob instanceof JFrame) {
				Display d = null;
				for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
					d = (Display)it.next();
					if (d.equals(ob)) break;
				}
				if (d == null) return;
				if (d.size_adjusted) {
					d.pack();
					d.size_adjusted = false;
				}
			}
		}
	};

	private int last_frame_state = frame.NORMAL;

	static private ComponentListener component_listener = new ComponentAdapter() {
		public void componentResized(ComponentEvent ce) {
			Display d = getDisplaySource(ce);
			if (null != d) {
				//if (!d.size_adjusted) {
					// keep a minimum width and height
					Rectangle r = d.frame.getBounds();
					int w = r.width;
					int h = r.height;
					if (h < 600) h = 600;
					if (w < 500) w = 500;
					if (w != r.width || h != r.height) {
						d.frame.setSize(w, h);
						//d.size_adjusted = true;
					}
				//} else {
					//d.size_adjusted = false;
				//}
				d.size_adjusted = true; // works in combination with mouseReleased to call pack(), avoiding infinite loops.
				d.adjustCanvas();

				int frame_state = d.frame.getExtendedState();
			       	if (frame_state != d.last_frame_state) { // this setup avoids infinite loops (for pack() calls componentResized as well
					d.last_frame_state = frame_state;
					if (d.frame.ICONIFIED != frame_state) d.frame.pack();
				}
			}
		}
		public void componentMoved(ComponentEvent ce) {
			Display d = getDisplaySource(ce);
			if (null != d) d.updateInDatabase("position");
		}
		private Display getDisplaySource(ComponentEvent ce) {
			Object source = ce.getSource();
			Iterator it = al_displays.iterator();
			while (it.hasNext()) {
				Display d = (Display)it.next();
				if (source.equals(d.frame)) {
					return d;
				}
			}
			return null;
		}
	};

	static private ChangeListener tabs_listener = new ChangeListener() {
		/** Listen to tab changes. */
		public void stateChanged(ChangeEvent ce) {
			Object source = ce.getSource();
			Iterator it = al_displays.iterator();
			while (it.hasNext()) {
				Display d = (Display)it.next();
				if (source.equals(d.tabs)) {
					// creating tabs fires the event!!!
					if (null == d.frame || null == d.canvas) return;
					if (d.tabs.getSelectedComponent().equals(d.scroll_channels)) {
						// find active channel if any
						for (int i=0; i<d.channels.length; i++) {
							if (d.channels[i].isActive()) {
								d.transp_slider.setValue((int)(d.channels[i].getAlpha() * 100));
								break;
							}
						}
					} else if (null != d.active) {
						// set the transp slider to the alpha value of the active Displayable if any
					
						d.transp_slider.setValue((int)(d.active.getAlpha() * 100));
					}
					break;
				}
			}
		}
	};

	private final ScrollLayerListener scroller_listener = new ScrollLayerListener();

	private class ScrollLayerListener implements AdjustmentListener {

		public void adjustmentValueChanged(AdjustmentEvent ae) {
			int index = scroller.getValue();
			new SetLayerThread(Display.this, layer.getParent().getLayer(index));
			return;
		}
	}

	private Object setting_layer_lock = new Object();
	private boolean setting_layer = false;
	private SetLayerThread set_layer_thread = null;

	private class SetLayerThread extends Thread {

		private boolean quit = false;
		private Display d;
		private Layer layer;

		SetLayerThread(Display d, Layer layer) {
			setPriority(Thread.NORM_PRIORITY);
			if (null != set_layer_thread) set_layer_thread.quit();
			set_layer_thread = this;
			this.d = d;
			this.layer = layer;
			start();
		}

		public void run() {
			if (quit) return;
			synchronized (setting_layer_lock) {
				while (setting_layer) {
					try { setting_layer_lock.wait(); } catch (InterruptedException ie) {}
				}
				if (quit) {
					setting_layer = false;
					setting_layer_lock.notifyAll();
					return;
				}
				setting_layer = true;
				try {
					// preload snapshots in the background
					/*
					if (preload_snapshots) {
						final ArrayList al_l = layer.getParent().getNeighborLayers(layer, 2);
						for (Iterator it = al_l.iterator(); it.hasNext(); ) {
							Layer la = (Layer)it.next();
							la.getProject().getLoader().preloadSnapshots((Layer)it.next(), canvas.getMagnification(), canvas.getSrcRect(), null, 30000000);
						}
					}
					*/
					//
					d.setLayer(layer);
					updateInDatabase("layer_id"); // not being done at the setLayer method to avoid thread locking design problems (the setLayer is used when reconstructing from the database)
					Thread.yield();
					Thread.sleep(100); // And still throws JTree UI exceptions from time to time!
				} catch (Exception e) {
					new IJError(e);
				} finally {
					// cleanup (removal of reference necessary for join() calls to this thread to succeed)
					if (this.equals(set_layer_thread)) {
						set_layer_thread = null;
					}
				}
				setting_layer = false;
				setting_layer_lock.notifyAll();
			}
		}

		public void quit() {
			quit = true;
		}
	}

	/** A new Display from scratch, to show the given Layer. */
	public Display(Project project, final Layer layer) {
		super(project);
		front = this;
		makeGUI(layer, null);
		ImagePlus.addImageListener(this);
		setLayer(layer);
		this.layer = layer; // after, or it doesn't update properly
		al_displays.add(this);
		layer.getParent().setActiveLayer(layer);
		addToDatabase();
	}

	/** For reconstruction purposes. The Display will be stored in the ht_later.*/
	public Display(Project project, long id, Layer layer, Object[] props) {
		super(project, id);
		if (null == ht_later) ht_later = new Hashtable();
		Display.ht_later.put(this, props);
		this.layer = layer;
	}

	/** Open a new Display centered around the given Displayable. */
	public Display(Project project, Layer layer, Displayable displ) {
		super(project);
		front = this;
		active = displ;
		makeGUI(layer, null);
		ImagePlus.addImageListener(this);
		setLayer(layer);
		this.layer = layer; // after set layer!
		al_displays.add(this);
		layer.getParent().setActiveLayer(layer);
		addToDatabase();
	}

	/** Reconstruct a Display from an XML entry, to be opened when everything is ready. */
	public Display(Project project, long id, Layer layer, Hashtable ht_attributes) {
		super(project, id);
		if (null == layer) {
			Utils.log2("Display: need a non-null Layer for id=" + id);
			return;
		}
		Rectangle srcRect = new Rectangle(0, 0, (int)layer.getLayerWidth(), (int)layer.getLayerHeight());
		double magnification = 0.25;
		Point p = new Point(0, 0);
		int c_alphas = 0x00FFFFFF;
		int c_alphas_state = 0xFFFFFFFF;
		for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht_attributes.get(key);
			if (key.equals("srcrect_x")) { // reflection! Reflection!
				srcRect.x = Integer.parseInt(data);
			} else if (key.equals("srcrect_y")) {
				srcRect.y = Integer.parseInt(data);
			} else if (key.equals("srcrect_width")) {
				srcRect.width = Integer.parseInt(data);
			} else if (key.equals("srcrect_height")) {
				srcRect.height = Integer.parseInt(data);
			} else if (key.equals("magnification")) {
				magnification = Double.parseDouble(data);
			} else if (key.equals("x")) {
				p.x = Integer.parseInt(data);
			} else if (key.equals("y")) {
				p.y = Integer.parseInt(data);
			} else if (key.equals("c_alphas")) {
				c_alphas = Integer.parseInt(data);
			} else if (key.equals("c_alphas_state")) {
				try {
					c_alphas_state = Integer.parseInt(data);
				} catch (Exception ex) {
					new IJError(ex);
					c_alphas_state = 1;
				}
			} else if (key.equals("scroll_step")) {
				try {
					setScrollStep(Integer.parseInt(data));
				} catch (Exception ex) {
					new IJError(ex);
					setScrollStep(1);
				}
			}
			// TODO the above is insecure, in that data is not fully checked to be within bounds.
		}
		Object[] props = new Object[]{p, new Double(magnification), srcRect, new Long(layer.getId()), new Integer(c_alphas), new Integer(c_alphas_state)};
		if (null == ht_later) ht_later = new Hashtable();
		Display.ht_later.put(this, props);
		this.layer = layer;
	}

	/** After reloading a project from the database, open the Displays that the project had. */
	static public Bureaucrat openLater() {
		if (null == ht_later || 0 == ht_later.size()) return null;
		final Worker worker = new Worker("Opening displays") {
			public void run() {
				startedWorking();
				try {
					Thread.sleep(300); // waiting for Swing

		for (Enumeration e = ht_later.keys(); e.hasMoreElements(); ) {
			final Display d = (Display)e.nextElement();
			front = d; // must be set before repainting any ZDisplayable!
			Object[] props = (Object[])ht_later.get(d);
			if (ControlWindow.isGUIEnabled()) d.makeGUI(d.layer, props);
			d.setLayerLater(d.layer, d.layer.get(((Long)props[3]).longValue())); //important to do it after makeGUI
			if (!ControlWindow.isGUIEnabled()) continue;
			ImagePlus.addImageListener(d);
			al_displays.add(d);
			d.updateTitle();
		}
		ht_later.clear();
		ht_later = null;
		if (null != front) front.getProject().select(front.layer);

				} catch (Throwable t) {
					new IJError(t);
				} finally {
					finishedWorking();
				}
			}
		};
		final Bureaucrat burro = new Bureaucrat(worker, ((Display)ht_later.keySet().iterator().next()).getProject()); // gets the project from the first Display
		burro.goHaveBreakfast();
		return burro;
	}

	private void makeGUI(final Layer layer, final Object[] props) {
		// gather properties
		Point p = null;
		double mag = 1.0D;
		Rectangle srcRect = null;
		if (null != props) {
			p = (Point)props[0];
			mag = ((Double)props[1]).doubleValue();
			srcRect = (Rectangle)props[2];
		}

		// transparency slider
		this.transp_slider = new JSlider(javax.swing.SwingConstants.HORIZONTAL, 0, 100, 100);
		this.transp_slider.setBackground(Color.white);
		this.transp_slider.setMinimumSize(new Dimension(250, 20));
		this.transp_slider.setMaximumSize(new Dimension(250, 20));
		this.transp_slider.setPreferredSize(new Dimension(250, 20));
		TransparencySliderListener tsl = new TransparencySliderListener();
		this.transp_slider.addChangeListener(tsl);
		this.transp_slider.addMouseListener(tsl);
		KeyListener[] kl = this.transp_slider.getKeyListeners();
		for (int i=0; i<kl.length; i++) {
			this.transp_slider.removeKeyListener(kl[i]);
		}

		// Tabbed pane on the left
		this.tabs = new JTabbedPane();
		this.tabs.setMinimumSize(new Dimension(250, 300));
		this.tabs.setBackground(Color.white);
		this.tabs.addChangeListener(tabs_listener);

		// Tab 1: Patches
		this.panel_patches = new JPanel();
		BoxLayout patches_layout = new BoxLayout(panel_patches, BoxLayout.Y_AXIS);
		this.panel_patches.setLayout(patches_layout);
		this.panel_patches.add(new JLabel("No patches."));
		this.scroll_patches = new JScrollPane(panel_patches);
		this.scroll_patches.setPreferredSize(new Dimension(250, 300));
		this.scroll_patches.setMinimumSize(new Dimension(250, 300));
		this.tabs.add("Patches", scroll_patches);

		// Tab 2: Profiles
		this.panel_profiles = new JPanel();
		BoxLayout profiles_layout = new BoxLayout(panel_profiles, BoxLayout.Y_AXIS);
		this.panel_profiles.setLayout(profiles_layout);
		this.panel_profiles.add(new JLabel("No profiles."));
		this.scroll_profiles = new JScrollPane(panel_profiles);
		this.scroll_profiles.setPreferredSize(new Dimension(250, 300));
		this.scroll_profiles.setMinimumSize(new Dimension(250, 300));
		this.tabs.add("Profiles", scroll_profiles);

		// Tab 3: pipes
		this.panel_zdispl = new JPanel();
		BoxLayout pipes_layout = new BoxLayout(panel_zdispl, BoxLayout.Y_AXIS);
		this.panel_zdispl.setLayout(pipes_layout);
		this.panel_zdispl.add(new JLabel("No objects."));
		this.scroll_zdispl = new JScrollPane(panel_zdispl);
		this.scroll_zdispl.setPreferredSize(new Dimension(250, 300));
		this.scroll_zdispl.setMinimumSize(new Dimension(250, 300));
		this.tabs.add("Z space", scroll_zdispl);

		// Tab 4: channels
		this.panel_channels = new JPanel();
		BoxLayout channels_layout = new BoxLayout(panel_channels, BoxLayout.Y_AXIS);
		this.panel_channels.setLayout(channels_layout);
		this.scroll_channels = new JScrollPane(panel_channels);
		this.scroll_channels.setPreferredSize(new Dimension(250, 300));
		this.scroll_channels.setMinimumSize(new Dimension(250, 300));
		this.channels = new Channel[4];
		this.channels[0] = new Channel(this, Channel.MONO);
		this.channels[1] = new Channel(this, Channel.RED);
		this.channels[2] = new Channel(this, Channel.GREEN);
		this.channels[3] = new Channel(this, Channel.BLUE);
		//this.panel_channels.add(this.channels[0]);
		this.panel_channels.add(this.channels[1]);
		this.panel_channels.add(this.channels[2]);
		this.panel_channels.add(this.channels[3]);
		this.tabs.add("Opacity", scroll_channels);

		// Tab 5: labels
		this.panel_labels = new JPanel();
		BoxLayout labels_layout = new BoxLayout(panel_labels, BoxLayout.Y_AXIS);
		this.panel_labels.setLayout(labels_layout);
		this.panel_labels.add(new JLabel("No labels."));
		this.scroll_labels = new JScrollPane(panel_labels);
		this.scroll_labels.setPreferredSize(new Dimension(250, 300));
		this.scroll_labels.setMinimumSize(new Dimension(250, 300));
		this.tabs.add("Labels", scroll_labels);

		this.ht_tabs = new Hashtable();
		this.ht_tabs.put(Patch.class, scroll_patches);
		this.ht_tabs.put(Profile.class, scroll_profiles);
		this.ht_tabs.put(ZDisplayable.class, scroll_zdispl);
		this.ht_tabs.put(Pipe.class, scroll_zdispl);
		this.ht_tabs.put(Ball.class, scroll_zdispl);
		this.ht_tabs.put(DLabel.class, scroll_labels);
		// channels not included

		// Navigator
		this.navigator = new DisplayNavigator(this, layer.getLayerWidth(), layer.getLayerHeight());
		// Layer scroller (to scroll slices)
		int extent = (int)(250.0 / layer.getParent().size());
		if (extent < 10) extent = 10;
		this.scroller = new JScrollBar(JScrollBar.HORIZONTAL);
		if (layer.getParent().size() > 1) updateLayerScroller(layer);
		this.scroller.addAdjustmentListener(scroller_listener);


		// Left panel, contains the transp slider, the tabbed pane, the navigation panel and the layer scroller
		JPanel left = new JPanel();
		BoxLayout left_layout = new BoxLayout(left, BoxLayout.Y_AXIS);
		left.setLayout(left_layout);
		left.add(transp_slider);
		left.add(tabs);
		left.add(navigator);
		left.add(scroller);

		// Canvas
		this.canvas = new DisplayCanvas(this, (int)Math.ceil(layer.getLayerWidth()), (int)Math.ceil(layer.getLayerHeight()));
		this.canvas_panel = new JPanel();
		GridBagLayout gb = new GridBagLayout();
		this.canvas_panel.setLayout(gb);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.NORTHWEST;
		gb.setConstraints(this.canvas_panel, c);
		gb.setConstraints(this.canvas, c);

		// prevent new Displays from screweing up if input is globally disabled
		if (!project.isInputEnabled()) this.canvas.setReceivesInput(false);

		this.canvas_panel.add(canvas);

		this.navigator.addMouseWheelListener(canvas);

		this.transp_slider.addKeyListener(canvas);

		// Split pane to contain everything
		this.split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, canvas_panel);

		// fix
		gb.setConstraints(split.getRightComponent(), c);

		// JFrame to show the split pane
		this.frame = new JFrame(layer.toString());
		if (IJ.isMacintosh() && IJ.getInstance()!=null) {
			IJ.wait(10); // may be needed for Java 1.4 on OS X
			this.frame.setMenuBar(ij.Menus.getMenuBar());
		}
		this.frame.addWindowListener(window_listener);
		this.frame.addComponentListener(component_listener);
		this.frame.getContentPane().add(split);
		this.frame.addMouseListener(frame_mouse_listener);
		//doesn't exist//this.frame.setMinimumSize(new Dimension(270, 600));

		if (null != props) {
			// restore canvas
			canvas.setup(mag, srcRect);
			// restore visibility of each channel
			int cs = ((Integer)props[5]).intValue();
			int[] sel = new int[4];
			sel[0] = ((cs&0xff000000)>>24);
			sel[1] = ((cs&0xff0000)>>16);
			sel[2] = ((cs&0xff00)>>8);
			sel[3] =  (cs&0xff);
			// restore channel alphas
			this.c_alphas = ((Integer)props[4]).intValue();
			channels[0].setAlpha( (float)((c_alphas&0xff000000)>>24) / 255.0f , 0 != sel[0]);
			channels[1].setAlpha( (float)((c_alphas&0xff0000)>>16) / 255.0f ,   0 != sel[1]);
			channels[2].setAlpha( (float)((c_alphas&0xff00)>>8) / 255.0f ,      0 != sel[2]);
			channels[3].setAlpha( (float) (c_alphas&0xff) / 255.0f ,            0 != sel[3]);
			// restore visibility in the working c_alphas
			this.c_alphas = ((0 != sel[0] ? (int)(255 * channels[0].getAlpha()) : 0)<<24) + ((0 != sel[1] ? (int)(255 * channels[1].getAlpha()) : 0)<<16) + ((0 != sel[2] ? (int)(255 * channels[2].getAlpha()) : 0)<<8) + (0 != sel[3] ? (int)(255 * channels[3].getAlpha()) : 0);
		}

		if (null != active && null != layer) {
			Rectangle r = active.getBoundingBox();
			r.x -= r.width/2;
			r.y -= r.height/2;
			r.width += r.width;
			r.height += r.height;
			if (r.x < 0) r.x = 0;
			if (r.y < 0) r.y = 0;
			if (r.width > layer.getLayerWidth()) r.width = (int)layer.getLayerWidth();
			if (r.height> layer.getLayerHeight())r.height= (int)layer.getLayerHeight();
			double magn = layer.getLayerWidth() / (double)r.width;
			canvas.setup(magn, r);
		}

		// add keyListener to the whole frame
		this.tabs.addKeyListener(canvas);
		this.canvas_panel.addKeyListener(canvas);
		this.frame.addKeyListener(canvas);

		this.frame.pack();
		ij.gui.GUI.center(this.frame);
		this.frame.setVisible(true);
		ProjectToolbar.setProjectToolbar(); // doesn't get it through events

		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();

		if (null != props) {
			// fix positioning outside the screen (dual to single monitor)
			if (p.x >= 0 && p.x < screen.width - 50 && p.y >= 0 && p.y <= screen.height - 50) this.frame.setLocation(p);
			else frame.setLocation(0, 0);
		}

		// fix excessive size
		Rectangle box = this.frame.getBounds();
		int x = box.x;
		int y = box.y;
		int width = box.width;
		int height = box.height;
		if (box.width > screen.width) { x = 0; width = screen.width; }
		if (box.height > screen.height) { y = 0; height = screen.height; }
		if (x != box.x || y != box.y) {
			this.frame.setLocation(x, y + (0 == y ? 30 : 0)); // added insets for bad window managers
			updateInDatabase("position");
		}
		if (width != box.width || height != box.height) {
			this.frame.setSize(new Dimension(width -10, height -30)); // added insets for bad window managers
		}
		if (null == props) {
			// try to optimize canvas dimensions and magn
			double magn = layer.getLayerHeight() / screen.height;
			if (magn > 1.0) magn = 1.0;
			long size = 0;
			// limit magnification if appropriate
			for (Iterator it = layer.getDisplayables(Patch.class).iterator(); it.hasNext(); ) {
				final Patch pa = (Patch)it.next();
				final Rectangle ba = pa.getBoundingBox();
				size += (long)(ba.width * ba.height);
			}
			if (size > 10000000) canvas.setInitialMagnification(Snapshot.SCALE); // 10 Mb
			else {
				this.frame.setSize(new Dimension((int)(screen.width * 0.66), (int)(screen.height * 0.66)));
			}
		}

		updateComponent(tabs); // otherwise fails in FreeBSD java 1.4.2 when reconstructing

		
		((FakeImagePlus)canvas.getFakeImagePlus()).setCalibrationSuper(layer.getParent().getCalibrationCopy());

		// create a drag and drop listener
		dnd = new DNDInsertImage(this);

		// start a repainting thread
		if (null != props) {
			canvas.repaint(true); // repaint() is unreliable
		}

		// Set the minimum size of the tabbed pane on the left, so it can be completely collapsed now that it has been properly displayed. This is a patch to the lack of respect for the setDividerLocation method.
		new Thread() {
			public void run() {
				try { Thread.sleep(500); } catch (Exception e) {}
				tabs.setMinimumSize(new Dimension(0, 100));
			}
		}.run();
	}

	public JPanel getCanvasPanel() {
		return canvas_panel;
	}

	public DisplayCanvas getCanvas() {
		return canvas;
	}

	public void setLayer(Layer layer) {
		if (null == layer || layer.equals(this.layer)) return;
		if (selection.isTransforming()) {
			Utils.log("Can't browse layers while transforming.\nCANCEL the transform first with the ESCAPE key or right-click -> cancel.");
			scroller.setValue(this.layer.getParent().getLayerIndex(this.layer.getId()));
			return;
		}
		this.layer = layer;
		scroller.setValue(layer.getParent().getLayerIndex(layer.getId()));
		// debug:
		//Utils.log2("Display c_alphas: " + Integer.toHexString(c_alphas));
		// empty the tabs, except channels
		clearTab(panel_profiles, "Profiles");
		clearTab(panel_patches, "Patches");
		clearTab(panel_labels, "Labels");
		clearTab(panel_zdispl, "Z-space objects");
		// distribute Displayable to the tabs. Ignore LayerSet instances.
		if (null == hs_panels) hs_panels = new Hashtable();
		else hs_panels.clear();
		Iterator it = layer.getDisplayables().iterator();
		while (it.hasNext()) {
			add((Displayable)it.next(), false);
		}
		// update the current Layer pointer in ZDisplayable objects
		it = layer.getParent().getZDisplayables().iterator(); // the pipe and ball objects, that live in the LayerSet
		while (it.hasNext()) {
			ZDisplayable zd = (ZDisplayable)it.next();
			zd.setLayer(layer); // the active layer
			add(zd, false);
		}
		// see if a lot has to be reloaded, put the relevant ones at the end
		project.getLoader().prepare(layer);
		updateTitle(); // to show the new 'z'
		// select the Layer in the LayerTree
		project.select(this.layer);
		// update active Displayable
		Displayable last_active = this.active;
		selection.clear();

		// repaint everything
		navigator.repaint(true);
		canvas.repaint(true);

		// reselect patches (from a stack) and ZDislayables if appropriate
		if (last_active instanceof ZDisplayable) {
			selection.add(last_active);
		} else if (last_active instanceof Patch && null != last_temp && last_temp instanceof PatchStack) {
			Displayable d = ((PatchStack)last_temp).getPatch(layer, (Patch)active); // TODO this is wrong, should be the next patch
			if (null != d) selection.add(d);
		}
		// repaint tabs (hard as hell)
		updateComponent(tabs);
		// @#$%^! The above works half the times, so explicit repaint as well:
		Component c = tabs.getSelectedComponent();
		if (null == c) {
			c = scroll_patches;
			tabs.setSelectedComponent(scroll_patches);
		}
		updateComponent(c);

		project.getLoader().setMassiveMode(false); // resetting if it was set true

		// update the coloring in the ProjectTree
		project.getProjectTree().updateUI();
		
		//
		createTempCurrentImage();
		setTempCurrentImage();
	}

	private void setLayerLater(Layer layer, final Displayable active) {
		if (null == layer) return;
		this.layer = layer;
		if (!ControlWindow.isGUIEnabled()) return;
		// empty the tabs, except channels and pipes
		clearTab(panel_profiles, "Profiles");
		clearTab(panel_patches, "Patches");
		clearTab(panel_labels, "Labels");
		// distribute Displayable to the tabs. Ignore LayerSet instances.
		if (null == hs_panels) hs_panels = new Hashtable();
		else hs_panels.clear();
		Iterator it = layer.getDisplayables().iterator();
		while (it.hasNext()) {
			add((Displayable)it.next(), false);
		}
		it = layer.getParent().getZDisplayables().iterator(); // the pipes, that live in the LayerSet
		while (it.hasNext()) {
			add((Displayable)it.next(), false);
		}
		updateComponent(tabs.getSelectedComponent());
		// swing issues:
		new Thread() {
			public void run() {
				try { Thread.sleep(1000); } catch (Exception e) {}
				setActive(active);
			}
		}.start();
	}

	/** Remove all components from the tab and add a "No [label]" label to each. */
	private void clearTab(Container c, String label) {
		c.removeAll();
		c.add(new JLabel("No " + label + "."));
		// magic cocktail:
		if (tabs.getSelectedComponent().equals(c)) {
			c.invalidate();
			c.validate();
			c.repaint();
		}
	}

	/** A class to listen to the transparency_slider of the DisplayablesSelectorWindow. */
	private class TransparencySliderListener extends MouseAdapter implements ChangeListener {

		public void stateChanged(ChangeEvent ce) {
			//change the transparency value of the current active displayable
			float new_value = (float)((JSlider)ce.getSource()).getValue();
			setTransparency(new_value / 100.0f);
		}

		public void mouseReleased(MouseEvent me) {
			// update navigator window
			navigator.repaint(true);
		}
	}

	/** Context-sensitive: to a Displayable, or to a channel. */
	private void setTransparency(float value) {
		JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (scroll.equals(scroll_channels)) {
			for (int i=0; i<4; i++) {
				if (channels[i].getBackground().equals(Color.cyan)) {
					channels[i].setAlpha(value); // will call back and repaint the Display
					return;
				}
			}
		} else if (null != active) {
			active.setAlpha(value);
		}
	}

	public void setTransparencySlider(float transp) {
		if (transp >= 0.0f && transp <= 1.0f) {
			transp_slider.setValue((int)(transp * 100));
		}
	}

	/** Mark the canvas for updating the offscreen images if the given Displayable is NOT the active. */ // Used by the Displayable.setVisible for example.
	static public void setUpdateGraphics(Layer layer, Displayable displ) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer) && d.active != displ) {
				d.canvas.setUpdateGraphics(true);
			}
		}
	}

	/** Flag the DisplayCanvas of Displays showing the given Layer to update their offscreen images.*/
	static public void setUpdateGraphics(Layer layer, boolean update) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				d.canvas.setUpdateGraphics(update);
			}
		}
	}

	/** Whether to update the offscreen images or not. */
	public void setUpdateGraphics(boolean b) {
		canvas.setUpdateGraphics(b);
	}

	/** Find all Display instances that contain the layer and repaint them. */
	static public void update(Layer layer) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.isShowing(layer)) {
				d.repaintAll();
			}
		}
	}

	/** Find all Display instances showing a Layer of this LayerSet, and update the dimensions of the navigator and canvas and snapshots, and repaint. */
	static public void update(LayerSet set) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (set.contains(d.layer)) {
				d.updateSnapshots();
				d.canvas.setDimensions(set.getLayerWidth(), set.getLayerHeight());
				d.repaintAll();
			}
		}
	}

	/** Release all resources held by this Display and close the frame. */
	protected void destroy() {
		canvas.setReceivesInput(false);
		synchronized (setting_layer_lock) {
			while (setting_layer) {
				try { setting_layer_lock.wait(); } catch (InterruptedException ie) {}
			}
			setting_layer = true;
			if (null != set_layer_thread) set_layer_thread.quit();

			// update the coloring in the ProjectTree and LayerTree
			if (!project.isBeingDestroyed()) {
				try {
					project.getProjectTree().updateUI();
					project.getLayerTree().updateUI();
				} catch (Exception e) {
					Utils.log2("updateUI failed at Display.destroy()");
				}
			}

			frame.removeComponentListener(component_listener);
			frame.removeWindowListener(window_listener);
			frame.removeWindowFocusListener(window_listener);
			frame.removeWindowStateListener(window_listener);
			frame.removeKeyListener(canvas);
			frame.removeMouseListener(frame_mouse_listener);
			canvas_panel.removeKeyListener(canvas);
			canvas.removeKeyListener(canvas);
			tabs.removeChangeListener(tabs_listener);
			tabs.removeKeyListener(canvas);
			ImagePlus.removeImageListener(this);
			canvas.destroy();
			navigator.destroy();
			scroller.removeAdjustmentListener(scroller_listener);
			frame.setVisible(false);
			//no need, and throws exception//frame.dispose();
			if (null != active_imp_copy) {
				active_imp_copy.flush(); // TODO inspect implications for other displays if this is the current or the copy (I think it's the copy) // Calls System.gc()
				active_imp_copy = null;
			}
			if (null != last_temp) {
				last_temp.flush();
				last_temp = null;
			}
			//TEMPORARY W//WindowManager.setTempCurrentImage(null);
			active = null;
			if (null != selection) selection.clear();
			Utils.log2("destroying selection");

			// below, need for SetLayerThread threads to quit if any.
			setting_layer = false;
			setting_layer_lock.notifyAll();
			// set a new front if any
			if (null == front && al_displays.size() > 0) {
				front = (Display)al_displays.get(al_displays.size() -1);
			}
			// repaint layer tree (to update the label color)
			try {
				project.getLayerTree().updateUI(); // works only after setting the front above
			} catch (Exception e) {} // ignore swing sync bullshit when closing everything too fast
			// remove the drag and drop listener
			dnd.destroy();
		}
	}

	/** Find all Display instances that contain a Layer of the given project and close them without removing the Display entries from the database. */
	static synchronized public void close(Project project) {
		/* // concurrent modifications if more than 1 Display are being removed asynchronously
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.getLayer().getProject().equals(project)) {
				it.remove();
				d.destroy();
			}
		}
		*/
		Display[] d = new Display[al_displays.size()];
		al_displays.toArray(d);
		for (int i=0; i<d.length; i++) {
			if (d[i].getProject().equals(project)) {
				al_displays.remove(d[i]);
				d[i].destroy();
			}
		}
	}

	/** Find all Display instances that contain the layer and close them and remove the Display from the database. */
	static public void close(Layer layer) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.isShowing(layer)) {
				d.remove(false);
				it.remove();
			}
		}
	}

	public boolean remove(boolean check) {
		if (check) {
			if (!Utils.check("Delete the Display ?")) return false;
		}
		// flush the offscreen images and close the frame
		destroy();
		removeFromDatabase();
		return true;
	}

	public Layer getLayer() {
		return layer;
	}

	public boolean isShowing(Layer layer) {
		return this.layer.equals(layer);
	}

	public DisplayNavigator getNavigator() {
		return navigator;
	}

	/** Repaint both the canvas and the navigator, updating the graphics, and the title and tabs. */
	public void repaintAll() {
		if (repaint_disabled) return;
		navigator.repaint(true);
		canvas.repaint(true);
		updateComponent(tabs);
		updateTitle();
	}

	/** Repaint the canvas updating graphics, the navigator without updating graphics, and the title. */
	public void repaintAll2() {
		if (repaint_disabled) return;
		navigator.repaint(false);
		canvas.repaint(true);
		updateTitle();
	}

	static public void repaintSnapshots(LayerSet set) {
		if (repaint_disabled) return;
		for (Iterator it = al_displays.iterator(); it.hasNext(); ){
			Display d = (Display)it.next();
			if (d.getLayer().getParent().equals(set)) {
				d.navigator.repaint(true);
				d.updateComponent(d.tabs);
			}
		}
	}
	static public void repaintSnapshots(Layer layer) {
		if (repaint_disabled) return;
		for (Iterator it = al_displays.iterator(); it.hasNext(); ){
			Display d = (Display)it.next();
			if (d.getLayer().equals(layer)) {
				d.navigator.repaint(true);
				d.updateComponent(d.tabs);
			}
		}
	}

	public void pack() {
		frame.pack();
	}

	protected void adjustCanvas() {
		Rectangle r = split.getRightComponent().getBounds();
		canvas.setDrawingSize(r.width, r.height, true);
		//frame.pack(); // don't! Would go into an infinite loop
		canvas.repaint(true);
		updateInDatabase("srcRect");
	}

	/** Grab the last selected display (or create an new one if none) and show in it the layer,centered on the Displayable object. */
	static public void setFront(Layer layer, Displayable displ) {
		if (null == front) {
			Display display = new Display(layer.getProject(), layer);
			display.show(displ);
		} else {
			front.show(displ);
		}
	}

	public void show(Displayable displ) {
		if (null == displ) return;
		frame.toFront();
		if (displ instanceof ZDisplayable) {
			ZDisplayable zd = (ZDisplayable)displ;
			if (!zd.paintsAt(this.layer)) setLayer(zd.getFirstLayer());
		} else {
			if (this.layer != displ.getLayer()) setLayer(displ.getLayer());
		}
		if (!IJ.shiftKeyDown()) selection.clear();
		selection.add(displ);
		canvas.showCentered(displ.getBoundingBox());
	}

	/** Find the displays that show the given Layer, and add the given Displayable to the GUI and sets it active only in the front Display and only if 'activate' is true. */
	static public void add(Layer layer, Displayable displ, boolean activate) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.layer.equals(layer)) {
				if (front == d) {
					d.add(displ, activate);
					front.frame.toFront();
				} else {
					d.add(displ, false);
				}
			}
		}
	}

	static public void add(Layer layer, Displayable displ) {
		add(layer, displ, true);
	}

	/** Add the ZDisplayable to all Displays that show a Layer belonging to the given LayerSet. */
	static public void add(LayerSet set, ZDisplayable zdispl) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (set.contains(d.layer)) {
				if (front == d) {
					d.add(zdispl, true); // calling add(Displayable, boolean)
					front.frame.toFront();
				} else {
					d.add(zdispl, false);
				}
			}
		}
	}

	/** Add it to the proper panel, at the top, and set it active. */
	private void add(Displayable d, boolean activate) {
		DisplayablePanel dp = (DisplayablePanel)hs_panels.get(d);
		if (null != dp) { // for ZDisplayable objects (TODO I think this is not used anymore)
			dp.setActive(true);
			//setActive(d);
			selection.clear();
			selection.add(d);
			return;
		}
		// add to the proper list
		JPanel p = null;
		if (d instanceof Profile) {
			p = panel_profiles;
		} else if (d instanceof Patch) {
			p = panel_patches;
		} else if (d instanceof DLabel) {
			p = panel_labels;
		} else if (d instanceof ZDisplayable) { //both pipes and balls
			p = panel_zdispl;
		} else {
			// LayerSet objects
			return;
		}
		dp = new DisplayablePanel(this, d); // TODO: instead of destroying/recreating, we could just recycle them by reassigning a different Displayable. See how it goes!
		addToPanel(p, 0, dp, activate);
		hs_panels.put(d, dp);
		if (activate) {
			dp.setActive(true);
			//setActive(d);
			selection.clear();
			selection.add(d);
		}
		navigator.repaint(true);
	}

	private void addToPanel(JPanel panel, int index, DisplayablePanel dp, boolean repaint) {
		// remove the label
		if (1 == panel.getComponentCount() && panel.getComponent(0) instanceof JLabel) {
			panel.removeAll();
		}
		panel.add(dp, index);
		/*
		panel.invalidate();
		panel.validate();
		panel.repaint();
		*/
		if (repaint) {
			tabs.invalidate();
			tabs.validate();
			tabs.repaint();
		}
	}

	static private void updateComponent(Component c) {
		c.invalidate();
		c.validate();
		c.repaint();
	}

	/** Find the displays that show the given Layer, and remove the given Displayable from the GUI. */
	static public void remove(Layer layer, Displayable displ) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				d.remove(displ);
			}
		}
	}

	private void remove(Displayable displ) {
		if (displ instanceof Patch) {
			panel_patches.remove((Component)hs_panels.remove(displ));
			updateComponent(panel_patches);
		} else if (displ instanceof Profile) {
			Component c = (Component)hs_panels.remove(displ);
			if (null == c) Utils.log("null dp !");
			panel_profiles.remove(c);
			updateComponent(panel_profiles);
		} else if (displ instanceof ZDisplayable) {
			panel_zdispl.remove((Component)hs_panels.remove(displ));
			updateComponent(panel_zdispl);
		} else if (displ instanceof DLabel) {
			panel_labels.remove((Component)hs_panels.remove(displ));
			updateComponent(panel_labels);
		}
		if (null == active || !selection.contains(displ)) {
			canvas.setUpdateGraphics(true);
		}
		selection.remove(displ); //if (displ == active) setActive(null);
		layer.getParent().removeFromUndo(displ);
		repaint(displ, 5);
	}

	static public void remove(ZDisplayable zdispl) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (zdispl.getLayerSet().equals(d.layer.getParent())) {
				d.remove((Displayable)zdispl);
			}
		}
	}

	static public void repaint(Layer layer, Displayable displ, int extra) {
		repaint(layer, displ, null, extra);
	}

	/** Find the displays that show the given Layer, and repaint the given Displayable. */
	static public void repaint(Layer layer, Displayable displ, Rectangle r, int extra) {
		if (repaint_disabled) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				if (null == r) d.repaint(displ, extra);
				else d.repaint(displ, r, extra);
				d.navigator.repaint(true); // everything
			}
		}
	}
	/** Repaint as much as the bounding box around the given Displayable. */
	private void repaint(Displayable displ, int extra) {
		if (repaint_disabled) return;
		if (!displ.equals(active)) canvas.setUpdateGraphics(true);
		navigator.repaint(true); // everything
		canvas.repaint(displ, extra);
		DisplayablePanel dp = (DisplayablePanel)hs_panels.get(displ);
		if (null != dp) dp.repaint(); // is null when creating it, or after deleting it
	}

	/** Repaint as much as the bounding box around the given Displayable. */
	private void repaint(Displayable displ, Rectangle r, int extra) {
		if (repaint_disabled) return;
		if (!displ.equals(active)) canvas.setUpdateGraphics(true);
		navigator.repaint(true); // everything
		canvas.repaint(r, extra);
		DisplayablePanel dp = (DisplayablePanel)hs_panels.get(displ);
		if (null != dp) dp.repaint(); // is null when creating it, or after deleting it
	}

	/** Repaint the snapshot for the given Displayable both at the DisplayNavigator and on its panel. */
	static public void repaintSnapshot(Displayable displ) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.contains(displ)) {
				DisplayablePanel dp = (DisplayablePanel)d.hs_panels.get(displ);
				if (null != dp) dp.repaint(); // is null when creating it, or after deleting it
				d.navigator.repaint(displ);
			}
		}
	}

	/** Repaint the given Rectangle in all Displays showing the layer, updating the offscreen image if any. */
	static public void repaint(Layer layer, Rectangle r, int extra) {
		repaint(layer, r, extra, true);
		/*
		if (repaint_disabled) return;
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				d.canvas.setUpdateGraphics(true);
				d.canvas.repaint(r, extra);
				d.navigator.repaint(true); // everything
				updateComponent(d.tabs.getSelectedComponent());
			}
		}
		*/
	}
	/** Repaint the given Rectangle in all Displays showing the layer, optionally updating the offscreen image (if any). */
	static public void repaint(Layer layer, Rectangle r, int extra, boolean update_graphics) {
		if (repaint_disabled) return;
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				d.canvas.setUpdateGraphics(update_graphics);
				d.canvas.repaint(r, extra);
				d.navigator.repaint(update_graphics);
				if (update_graphics) updateComponent(d.tabs.getSelectedComponent());
			}
		}
	}

	/** Repaint the DisplayablePanel (and DisplayNavigator) only for the given Displayable, in all Displays showing the given Layer. */
	static public void repaint(Layer layer, Displayable displ) {
		if (repaint_disabled) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				DisplayablePanel dp = (DisplayablePanel)d.hs_panels.get(displ);
				if (null != dp) dp.repaint();
				d.navigator.repaint(true);
			}
		}
	}

	static public void repaint(LayerSet set, Displayable displ, int extra) {
		repaint(set, displ, null, extra);
	}

	/** Repaint the Displayable in every Display that shows a Layer belonging to the given LayerSet. */
	static public void repaint(LayerSet set, Displayable displ, Rectangle r, int extra) {
		if (repaint_disabled) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (set.contains(d.layer)) {
				DisplayablePanel dp = (DisplayablePanel)d.hs_panels.get(displ);
				if (null != dp) dp.repaint();
				d.navigator.repaint(true);
				if (!displ.equals(d.active)) d.setUpdateGraphics(true); // safeguard
				// paint the given box or the actual Displayable's box
				if (null != r) d.canvas.repaint(r, extra);
				else d.canvas.repaint(displ, extra);
			}
		}
	}

	/** Repaint the entire LayerSet, in all Displays showing a Layer of it.*/
	static public void repaint(LayerSet set) {
		if (repaint_disabled) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (set.contains(d.layer)) {
				d.navigator.repaint(true);
				d.canvas.repaint(true);
			}
		}
	}
	/** Repaint the given box in the LayerSet, in all Displays showing a Layer of it.*/
	static public void repaint(LayerSet set, Rectangle box) {
		if (repaint_disabled) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (set.contains(d.layer)) {
				d.navigator.repaint(box);
				d.canvas.repaint(box, 0, true);
			}
		}
	}
	/** Repaint the entire Layer, in all Displays showing it, including the tabs.*/
	static public void repaint(Layer layer) {
		if (repaint_disabled) return;
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)) {
				d.navigator.repaint(true);
				d.canvas.repaint(true);
			}
		}
	}

	static private boolean repaint_disabled = false;

	/** Set a flag to enable/disable repainting of all Display instances. If setting the flag to false, current UpdateGraphics threads are quitted.*/
	static protected void setRepaint(boolean b) {
		if (!b) {
			for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
				Display d = (Display)it.next();
				d.canvas.cancelRepaints(); // will quit all UpdateGraphics threads
			}
		}
		repaint_disabled = !b;
	}

	public Rectangle getBounds() {
		return frame.getBounds();
	}

	public Displayable getActive() {
		return active; //TODO this should return selection.active !!
	}

	public void select(Displayable d) {
		select(d, false);
	}

	/** Select/deselect accordingly to the current state and the shift key. */
	public void select(Displayable d, boolean shift_down) {
		if (null == d) {
			//Utils.log2("Display.select: clearing selection");
			canvas.setUpdateGraphics(true);
			selection.clear();
			return;
		}
		if (!shift_down) {
			//Utils.log2("Display.select: single selection");
			if (!d.equals(active)) {
				selection.clear();
				selection.add(d);
			}
		} else if (selection.contains(d)) {
			if (active.equals(d)) {
				selection.remove(d);
				//Utils.log2("Display.select: removing from a selection");
			} else {
				//Utils.log2("Display.select: activing within a selection");
				selection.setActive(d);
			}
		} else {
			//Utils.log2("Display.select: adding to an existing selection");
			selection.add(d);
		}
	}

	public void choose(int screen_x_p, int screen_y_p, int x_p, int y_p, final Class c) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, c);
	}
	public void choose(int screen_x_p, int screen_y_p, int x_p, int y_p) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, null);
	}

	/** Find a Displayable to add to the selection under the given point (which is in offscreen coords). */
	public void choose(int screen_x_p, int screen_y_p, int x_p, int y_p, boolean shift_down, Class c) {
		//Utils.log("Display.choose: x,y " + x_p + "," + y_p);
		ArrayList al = layer.find(x_p, y_p);
		al.addAll(layer.getParent().findZDisplayables(layer, x_p, y_p));
		if (al.isEmpty()) {
			selection.clear();
			canvas.setUpdateGraphics(true);
			//Utils.log("choose: set active to null");
		} else if (1 == al.size()) {
			Displayable d = (Displayable)al.get(0);
			if (null != c && !d.getClass().equals(c)) {
				selection.clear();
				return;
			}
			select(d, shift_down);
			//Utils.log("choose 1: set active to " + active);
		} else {
			if (al.contains(active) && !shift_down) {
				// do nothing
			} else {
				if (null != c) {
					// check if at least one of them is of class c
					// if only one is of class c, set as selected
					// else show menu
					for (Iterator it = al.iterator(); it.hasNext(); ) {
						Object ob = it.next();
						if (!ob.getClass().equals(c)) it.remove();
					}
					if (0 == al.size()) {
						// deselect
						selection.clear();
						return;
					}
					if (1 == al.size()) {
						select((Displayable)al.get(0), shift_down);
						return;
					}
					// else, choose among the many
				}
				choose(screen_x_p, screen_y_p, al, shift_down);
			}
			//Utils.log("choose many: set active to " + active);
		}
	}

	private void choose(final int screen_x_p, final int screen_y_p, final ArrayList al, final boolean shift_down) {
		// show a popup on the canvas to choose
		new Thread() {
			public void run() {
				final Object lock = new Object();
				final DisplayableChooser d_chooser = new DisplayableChooser(al, lock);
				final JPopupMenu pop = new JPopupMenu("Select:");
				final Iterator itu = al.iterator();
				while (itu.hasNext()) {
					Displayable d = (Displayable)itu.next();
					JMenuItem menu_item = new JMenuItem(d.toString());
					menu_item.addActionListener(d_chooser);
					pop.add(menu_item);
				}

				new Thread() {
					public void run() {
						pop.show(canvas, screen_x_p, screen_y_p);
					}
				}.start();

				//now wait until selecting something
				synchronized(lock) {
					do {
						try {
							lock.wait();
						} catch (InterruptedException ie) {}
					} while (d_chooser.isWaiting() && pop.isShowing());
				}

				//grab the chosen Displayable object
				Displayable d = d_chooser.getChosen();
				//Utils.log("Chosen: " + d.toString());
				if (null == d) { Utils.log("Display.choose: returning a null!"); }
				select(d, shift_down);
				pop.setVisible(false);
			}
		}.start();
	}

	/** Used by the Selection exclusively. This method will change a lot in the near future, and may disappear in favor of getSelection().getActive() */
	protected void setActive(Displayable displ) {
		if (null != displ && displ.equals(active)) {
			// make sure the proper tab is selected.
			selectTab(displ);
			//Utils.log2("Display.setActive : returning early");
			return; // the same
		}
		// deactivate previously active
		if (null != active) {
			//  DON'T, so as long as a new Patch not belonging to the last_temp is selected, the same ImagePlus is presented to ImageJ
			//if (active instanceof Patch) {
			//	WindowManager.setTempCurrentImage(null);
			//}
			/* // DISABLED, will be handled at saving time TODO what about DBLoader projects?
			if (active.isDeletable()) {
				// don't keep empty objects around
				if (active instanceof Profile || active instanceof Pipe || active instanceof Ball || active instanceof AreaList) { // will call back Display.remove(any)
					project.removeProjectThing(active, false);
					// can't call Displayable.remove(boolean) directly because the Project.removeProjectThing will do so on its ProjectThing and any children.
				} else {
					// text labels (for now the only type)
					active.remove(false);
				}
			} else {
			*/
				Object ob = hs_panels.get(active);
				if (null != ob) ((DisplayablePanel)ob).setActive(false);
				// link Patch objects underneath if it's not a Patch itself
				if (null != active && !(active instanceof Patch)) {
					active.linkPatches();
				}
			/*}*/
			// erase "decorations" of the previosuly active while it's still the active (so no offscreen image remaking needed)
			if (null != active) canvas.repaint(active, 4);
		}
		active = displ;
		// activate the new active
		if (null != displ) {
			Object ob = hs_panels.get(displ);
			if (null != ob) ((DisplayablePanel)ob).setActive(true);
			updateInDatabase("active_displayable_id");
			project.select(displ); // select the node in the corresponding tree, if any.
			// select the proper tab, and scroll to visible
			selectTab(displ);
			canvas.setUpdateGraphics(true); // remake offscreen images
			repaint(displ, 5);
			transp_slider.setValue((int)(displ.getAlpha() * 100));
		} else {
			//ensure decorations are removed from the panels, for Displayables in a selection besides the active one
			updateComponent(tabs.getSelectedComponent());
		}
		createTempCurrentImage();
		setTempCurrentImage();
	}

	/** Select the proper tab, and also scroll it to show the given Displayable -unless it's a LayerSet, and unless the proper tab is already showing. */
	private void selectTab(Displayable displ) {
		try {
			Object ob_tab = ht_tabs.get(displ.getClass());
			if (null == ob_tab || !tabs.getSelectedComponent().equals(ob_tab)) return;
			if (!(displ instanceof LayerSet)) {
				Method method = getClass().getDeclaredMethod("selectTab", new Class[]{displ.getClass()});
				method.invoke(this, new Object[]{displ});
			}
		} catch (Exception e) {
			Utils.log("Display.setActive(" + displ + "): " + e);
		}
	}

	private void selectTab(Patch patch) {
		tabs.setSelectedComponent(scroll_patches);
		scrollToShow(scroll_patches, (DisplayablePanel)hs_panels.get(patch));
	}

	private void selectTab(Profile profile) {
		tabs.setSelectedComponent(scroll_profiles);
		scrollToShow(scroll_profiles, (DisplayablePanel)hs_panels.get(profile));
	}

	private void selectTab(Pipe pipe) {
		tabs.setSelectedComponent(scroll_zdispl);
		scrollToShow(scroll_zdispl, (DisplayablePanel)hs_panels.get(pipe));
	}

	private void selectTab(AreaList area_list) {
		tabs.setSelectedComponent(scroll_zdispl);
		scrollToShow(scroll_zdispl, (DisplayablePanel)hs_panels.get(area_list));
	}

	private void selectTab(Ball ball) {
		tabs.setSelectedComponent(scroll_zdispl);
		scrollToShow(scroll_zdispl, (DisplayablePanel)hs_panels.get(ball));
	}

	private void selectTab(DLabel label) {
		tabs.setSelectedComponent(scroll_labels);
		scrollToShow(scroll_labels, (DisplayablePanel)hs_panels.get(label));
	}

	static public void setActive(Object event, Displayable displ) {
		if (!(event instanceof InputEvent)) return;
		// find which Display
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.isOrigin((InputEvent)event)) {
				d.setActive(displ);
				break;
			}
		}
	}

	/** Find out whether this Display is Transforming its active Displayable. */
	public boolean isTransforming() {
		return canvas.isTransforming();
	}

	/** Find whether any Display is transforming the given Displayable. */
	static public boolean isTransforming(Displayable displ) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (null != d.active && d.active.equals(displ) && d.canvas.isTransforming()) return true;
		}
		return false;
	}

	static public boolean isAligning(LayerSet set) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.getParent().equals(set) && set.isAligning()) {
				return true;
			}
		}
		return false;
	}

	/** Set the front Display to transform the Displayable only if no other canvas is transforming it. */
	static public void setTransforming(Displayable displ) {
		if (null == front) return;
		if (front.active != displ) return;
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.active.equals(displ)) {
				if (d.canvas.isTransforming()) {
					Utils.showMessage("Already transforming " + displ.getTitle());
					return;
				}
			}
		}
		front.canvas.setTransforming(true);
	}

	/** Finish transforming the given Displayable */ // TODO RETHINK what can be transformed? Only Patches ? Why not profiles as well ? And Labels ?
	/*
	static public void endTransform(Displayable displ) {
		if (null == front) return;
		if (front.active != displ) return;
		front.canvas.setTransforming(false);
	}
	*/

	/** Check whether the source of the event is located in this instance.*/
	private boolean isOrigin(InputEvent event) {
		Object source = event.getSource();
		// find it ... check the canvas for now TODO
		if (canvas.equals(source)) {
			return true;
		}
		return false;
	}

	/** Get the layer of the front Display, or null if none.*/
	static public Layer getFrontLayer() {
		if (null == front) return null;
		return front.layer;
	}

	/** Get the layer of an open Display of the given Project, or null if none.*/
	static public Layer getFrontLayer(Project project) {
		if (front.project.equals(project)) return front.layer;
		// else, find an open Display for the given Project, if any
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.project.equals(project)) {
				d.frame.toFront();
				return d.layer;
			}
		}
		return null; // none found
	}

	public boolean isReadOnly() {
		// TEMPORARY: in the future one will be able show displays as read-only to other people, remotely
		return false;
	}

	static public void showPopup(Component c, int x, int y) {
		if (null != front) front.getPopupMenu().show(c, x, y);
	}

	/** Return a context-sensitive popup menu. */
	public JPopupMenu getPopupMenu() { // called from canvas
		// get the job canceling dialog
		if (!canvas.isInputEnabled()) {
			return project.getLoader().getJobsPopup(this);
		}

		// create new
		this.popup = new JPopupMenu();
		JMenuItem item = null;
		JMenu menu = null;

		if (ProjectToolbar.ALIGN == Toolbar.getToolId()) {
			boolean aligning = layer.getParent().isAligning();
			item = new JMenuItem("Cancel alignment"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			if (!aligning) item.setEnabled(false);
			item = new JMenuItem("Align with landmarks"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true));
			if (!aligning) item.setEnabled(false);
			item = new JMenuItem("Align and register"); item.addActionListener(this); popup.add(item);
			if (!aligning) item.setEnabled(false);
			item = new JMenuItem("Align using profiles");  item.addActionListener(this); popup.add(item);
			if (!aligning || selection.isEmpty() || !selection.contains(Profile.class)) item.setEnabled(false);
			item = new JMenuItem("Align stack slices"); item.addActionListener(this); popup.add(item);
			if (selection.isEmpty() || ! (getActive().getClass().equals(Patch.class) && ((Patch)getActive()).isStack())) item.setEnabled(false);
			item = new JMenuItem("Align layers (montage-based)"); item.addActionListener(this); popup.add(item);
			if (1 == layer.getParent().size()) item.setEnabled(false);
			item = new JMenuItem("Align layers (tile-based global minimization)"); item.addActionListener(this); popup.add(item);
			if (1 == layer.getParent().size()) item.setEnabled(false);
			return popup;
		}

		if (null != active) {
			if (!canvas.isTransforming()) {
				if (active instanceof Profile) {
					item = new JMenuItem("Duplicate, link and send to next layer"); item.addActionListener(this); popup.add(item);
					Layer nl = layer.getParent().next(layer);
					if (nl.equals(layer)) item.setEnabled(false);
					item = new JMenuItem("Duplicate, link and send to previous layer"); item.addActionListener(this); popup.add(item);
					nl = layer.getParent().previous(layer);
					if (nl.equals(layer)) item.setEnabled(false);

					menu = new JMenu("Duplicate, link and send to");
					ArrayList al = layer.getParent().getLayers();
					Iterator it = al.iterator();
					int i = 1;
					while (it.hasNext()) {
						Layer la = (Layer)it.next();
						item = new JMenuItem(i + ": z = " + la.getZ()); item.addActionListener(this); menu.add(item); // TODO should label which layers contain Profile instances linked to the one being duplicated
						if (la.equals(this.layer)) item.setEnabled(false);
						i++;
					}
					popup.add(menu);
					if (IJ.isLinux()) {
						item = new JMenuItem("Duplicate, link and send to..."); item.addActionListener(this); popup.add(item);
					}

					popup.addSeparator();

					item = new JMenuItem("Unlink from images"); item.addActionListener(this); popup.add(item);
					if (!active.isLinked()) item.setEnabled(false); // isLinked() checks if it's linked to a Patch in its own layer
					popup.addSeparator();
				} else if (active instanceof Patch) {
					item = new JMenuItem("Unlink from images"); item.addActionListener(this); popup.add(item);
					if (!active.isOnlyLinkedTo(Patch.class, active.getLayer())) {
						item.setEnabled(false);
					}
					item = new JMenuItem("Snap"); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("View volume"); item.addActionListener(this); popup.add(item);
					HashSet hs = active.getLinked(Patch.class);
					if (null == hs || 0 == hs.size()) item.setEnabled(false);
					item = new JMenuItem("View orthoslices"); item.addActionListener(this); popup.add(item);
					if (null == hs || 0 == hs.size()) item.setEnabled(false); // if no Patch instances among the directly linked, then it's not a stack
					popup.addSeparator();
				} else {
					item = new JMenuItem("Unlink"); item.addActionListener(this); popup.add(item);
					popup.addSeparator();
				}
				if (active instanceof AreaList) {
					item = new JMenuItem("Merge"); item.addActionListener(this); popup.add(item);
					ArrayList al = selection.getSelected();
					int n = 0;
					for (Iterator it = al.iterator(); it.hasNext(); ) {
						if (it.next().getClass().equals(AreaList.class)) n++;
					}
					if (n < 2) item.setEnabled(false);
				}
			}
			if (canvas.isTransforming()) {
				item = new JMenuItem("Apply transform"); item.addActionListener(this); popup.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true)); // dummy, for I don't add a MenuKeyListener, but "works" through the normal key listener. It's here to provide a visual cue
			} else {
				item = new JMenuItem("Transform"); item.addActionListener(this); popup.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, 0, true));
			}
			item = new JMenuItem("Cancel transform"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			if (!canvas.isTransforming()) item.setEnabled(false);
			if (canvas.isTransforming()) {
				item = new JMenuItem("Specify transform..."); item.addActionListener(this); popup.add(item);
			}

			if (!canvas.isTransforming()) {
				item = new JMenuItem("Color..."); item.addActionListener(this); popup.add(item);
				if (active instanceof LayerSet) item.setEnabled(false);
				if (active.isLocked()) {
					item = new JMenuItem("Unlock");  item.addActionListener(this); popup.add(item);
				} else {
					item = new JMenuItem("Lock");  item.addActionListener(this); popup.add(item);
				}
				menu = new JMenu("Move");
				popup.addSeparator();
				LayerSet ls = layer.getParent();
				item = new JMenuItem("Move to top"); item.addActionListener(this); menu.add(item);
				if (ls.isTop(active)) item.setEnabled(false);
				item = new JMenuItem("Move up"); item.addActionListener(this); menu.add(item);
				if (ls.isTop(active)) item.setEnabled(false);
				item = new JMenuItem("Move down"); item.addActionListener(this); menu.add(item);
				if (ls.isBottom(active)) item.setEnabled(false);
				item = new JMenuItem("Move to bottom"); item.addActionListener(this); menu.add(item);
				if (ls.isBottom(active)) item.setEnabled(false);

				popup.add(menu);
				popup.addSeparator();
				item = new JMenuItem("Delete..."); item.addActionListener(this); popup.add(item);
				try {
					if (active instanceof Patch) {
						if (!active.isOnlyLinkedTo(Patch.class)) {
							item.setEnabled(false);
						}
					} else if (!(active instanceof DLabel)) { // can't delete elements from the trees (Profile, Pipe, LayerSet)
						item.setEnabled(false);
					}
				} catch (Exception e) { new IJError(e); item.setEnabled(false); }

				if (active instanceof Patch) {
					item = new JMenuItem("Undo");   item.addActionListener(this); popup.add(item);
					if (!undo) item.setEnabled(false);
					item = new JMenuItem("Revert"); item.addActionListener(this); popup.add(item);
					popup.addSeparator();
				}
				item = new JMenuItem("Undo transforms");item.addActionListener(this); popup.add(item);
				if (!layer.getParent().canUndo() || canvas.isTransforming()) item.setEnabled(false);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Event.SHIFT_MASK, true));
				item = new JMenuItem("Redo transforms");item.addActionListener(this); popup.add(item);
				if (!layer.getParent().canRedo() || canvas.isTransforming()) item.setEnabled(false);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Event.ALT_MASK, true));
				item = new JMenuItem("Properties...");    item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Show centered"); item.addActionListener(this); popup.add(item);

				if (! (active instanceof ZDisplayable)) {
					ArrayList al_layers = layer.getParent().getLayers();
					int i_layer = al_layers.indexOf(layer);
					int n_layers = al_layers.size();
					item = new JMenuItem("Send to previous layer"); item.addActionListener(this); popup.add(item);
					if (1 == n_layers || 0 == i_layer || active.isLinked()) item.setEnabled(false);
					// check if the active is a profile and contains a link to another profile in the layer it is going to be sent to, or it is linked
					else if (active instanceof Profile && !active.canSendTo(layer.getParent().previous(layer))) item.setEnabled(false);
					item = new JMenuItem("Send to next layer"); item.addActionListener(this); popup.add(item);
					if (1 == n_layers || n_layers -1 == i_layer || active.isLinked()) item.setEnabled(false);
					else if (active instanceof Profile && !active.canSendTo(layer.getParent().next(layer))) item.setEnabled(false);
					menu = new JMenu("Send linked group to...");
					if (active.hasLinkedGroupWithinLayer(this.layer)) {
						int i = 1;
						for (Iterator it = layer.getParent().getLayers().iterator(); it.hasNext(); ) {
							Layer la = (Layer)it.next();
							item = new JMenuItem(la.getTitle()); item.addActionListener(this); menu.add(item);
							if (la.equals(this.layer)) item.setEnabled(false);
							i++;
						}
						popup.add(menu);
					} else {
						menu.setEnabled(false);
						//Utils.log("Active's linked group not within layer.");
					}
					popup.add(menu);
				}
				popup.addSeparator();
			}
		}

		if (!canvas.isTransforming()) {
			try {
				menu = new JMenu("Hide/Unhide");
				boolean none = ! layer.getParent().containsDisplayable(DLabel.class);
				item = new JMenuItem("Hide all labels"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all labels"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				none = ! layer.getParent().contains(AreaList.class);
				item = new JMenuItem("Hide all arealists"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all arealists"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				none = ! layer.contains(Profile.class);
				item = new JMenuItem("Hide all profiles"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all profiles"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				none = ! layer.getParent().contains(Pipe.class);
				item = new JMenuItem("Hide all pipes"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all pipes"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				none = ! layer.getParent().contains(Ball.class);
				item = new JMenuItem("Hide all balls"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all balls"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				none = ! layer.getParent().containsDisplayable(Patch.class);
				item = new JMenuItem("Hide all images"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				item = new JMenuItem("Unhide all images"); item.addActionListener(this); menu.add(item);
				if (none) item.setEnabled(false);
				popup.add(menu);
			} catch (Exception e) { new IJError(e); }

			menu = new JMenu("Import");
			item = new JMenuItem("Import image"); item.addActionListener(this); menu.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, Event.ALT_MASK & Event.SHIFT_MASK, true));
			item = new JMenuItem("Import stack..."); item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Import grid..."); item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Import sequence as grid..."); item.addActionListener(this); menu.add(item);
			popup.add(menu);

			menu = new JMenu("Display");
			item = new JMenuItem("Make flat image..."); item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Resize canvas/LayerSet...");   item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Autoresize canvas/LayerSet");  item.addActionListener(this); menu.add(item);
			// OBSOLETE // item = new JMenuItem("Rotate Layer/LayerSet...");   item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Properties ..."); item.addActionListener(this); menu.add(item);
			popup.add(menu);
			menu = new JMenu("Project");
			this.project.getLoader().setupMenuItems(menu, this.getProject());
			if (menu.getItemCount() > 0) popup.add(menu);
			menu = new JMenu("Selection");
			item = new JMenuItem("Select all"); item.addActionListener(this); menu.add(item);
			if (0 == layer.getDisplayables().size() && 0 == layer.getParent().getZDisplayables().size()) item.setEnabled(false);
			item = new JMenuItem("Select none"); item.addActionListener(this); menu.add(item);
			if (0 == selection.getNSelected()) item.setEnabled(false);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			popup.add(menu);
			item = new JMenuItem("Search..."); item.addActionListener(this); popup.add(item);
		}

		//canvas.add(popup);
		return popup;
	}

	/** Check if a panel for the given Displayable is completely visible in the JScrollPane */
	public boolean isWithinViewport(Displayable d) {
		JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (d instanceof Profile && scroll.equals(scroll_profiles)) {
			return isWithinViewport(scroll_profiles, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof Patch && scroll.equals(scroll_patches)) {
			return isWithinViewport(scroll_patches, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof Pipe && scroll.equals(scroll_zdispl)) {
			return isWithinViewport(scroll_zdispl, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof DLabel && scroll.equals(scroll_labels)) {
			return isWithinViewport(scroll_labels, (DisplayablePanel)hs_panels.get(d));
		} else return false;
	}

	private boolean isWithinViewport(JScrollPane scroll, DisplayablePanel dp) {
		if(null == dp) {
			Utils.log("Display.isWithinViewport: null DisplayablePanel ??");
			return false;
		}
		JViewport view = scroll.getViewport();
		java.awt.Dimension dimensions = view.getExtentSize();
		java.awt.Point p = view.getViewPosition();
		int y = dp.getY();
		if ((y + DisplayablePanel.HEIGHT - p.y) <= dimensions.height && y >= p.y) {
			return true;
		}
		return false;
	}

	/** Check if a panel for the given Displayable is partially visible in the JScrollPane */
	public boolean isPartiallyWithinViewport(Displayable d) {
		JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (d instanceof Profile && scroll.equals(scroll_profiles)) {
			return isPartiallyWithinViewport(scroll_profiles, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof Patch && scroll.equals(scroll_patches)) {
			return isPartiallyWithinViewport(scroll_patches, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof ZDisplayable && scroll.equals(scroll_zdispl)) {
			return isPartiallyWithinViewport(scroll_zdispl, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof DLabel && scroll.equals(scroll_labels)) {
			return isPartiallyWithinViewport(scroll_labels, (DisplayablePanel)hs_panels.get(d));
		} else return false;
	}

	/** Check if a panel for the given Displayable is at least partially visible in the JScrollPane */
	private boolean isPartiallyWithinViewport(JScrollPane scroll, DisplayablePanel dp) {
		if(null == dp) {
			Utils.log("Display.isPartiallyWithinViewport: null DisplayablePanel ??");
			return false;
		}
		JViewport view = scroll.getViewport();
		java.awt.Dimension dimensions = view.getExtentSize();
		java.awt.Point p = view.getViewPosition();
		int y = dp.getY();
		if (   ((y + DisplayablePanel.HEIGHT - p.y) <= dimensions.height && y >= p.y) // completely visible
		    || ((y + DisplayablePanel.HEIGHT - p.y) >  dimensions.height && y < p.y + dimensions.height) // partially hovering at the bottom
		    || ((y + DisplayablePanel.HEIGHT) > p.y && y < p.y) // partially hovering at the top
		) {
			return true;
		}
		return false;
	}

	/** A function to make a Displayable panel be visible in the screen, by scrolling the viewport of the JScrollPane. */
	public void scrollToShow(Displayable d) {
		JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (d instanceof Profile && scroll.equals(scroll_profiles)) {
			scrollToShow(scroll_profiles, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof Patch && scroll.equals(scroll_patches)) {
			scrollToShow(scroll_patches, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof Pipe && scroll.equals(scroll_zdispl)) {
			scrollToShow(scroll_zdispl, (DisplayablePanel)hs_panels.get(d));
		} else if (d instanceof DLabel && scroll.equals(scroll_labels)) {
			scrollToShow(scroll_labels, (DisplayablePanel)hs_panels.get(d));
		}
	}

	private void scrollToShow(JScrollPane scroll, DisplayablePanel dp) {
		if(null == dp) {
			Utils.log2("Display.scrollToShow: null DisplayablePanel ??");
			return;
		}
		JViewport view = scroll.getViewport();
		Point current = view.getViewPosition();
		Dimension extent = view.getExtentSize();
		int panel_y = dp.getY();
		if ((panel_y + DisplayablePanel.HEIGHT - current.y) <= extent.height && panel_y >= current.y) {
			// it's completely visible already
			return;
		} else {
			// scroll just enough
			// if it's above, show at the top
			if (panel_y - current.y < 0) {
				view.setViewPosition(new Point(0, panel_y));
				return;
			}
			// if it's below (even if partially), show at the bottom
			if (panel_y + 50 > current.y + extent.height) {
				view.setViewPosition(new Point(0, panel_y - extent.height + 50));
				//Utils.log("Display.scrollToShow: panel_y: " + panel_y + "   current.y: " + current.y + "  extent.height: " + extent.height);
				return;
			}
		}
	}

	/** Update the title of the given Displayable in its DisplayablePanel, if any. */
	static public void updateTitle(Layer layer, Displayable displ) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (layer.equals(d.layer)){
				((DisplayablePanel)d.hs_panels.get(displ)).updateTitle();
			}
		}
	}

	static public void updateTitle(Layer layer) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.equals(layer)) {
				d.updateTitle();
			}
		}
	}
	static public void updateTitle(LayerSet ls) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.getParent().equals(ls)) {
				d.updateTitle();
			}
		}
	}

	/** Set a new title in the JFrame, showing info on the layer 'z' and the magnification. */
	public void updateTitle() {
		// From ij.ImagePlus class, the solution:
		String scale = "";
		double magnification = canvas.getMagnification();
		if (magnification!=1.0) {
			double percent = magnification*100.0;
			if (percent==(int)percent)
				scale = " (" + Utils.d2s(percent,0) + "%)";
			else
				scale = " (" + Utils.d2s(percent,1) + "%)";
		}
		String title = (layer.getParent().indexOf(layer) + 1) + "/" + layer.getParent().size() +" " + (null == layer.getTitle() ? "" : layer.getTitle()) + scale;
		frame.setTitle(title);
		// fix the title for the FakeImageWindow and thus the WindowManager listing in the menus
		canvas.getFakeImagePlus().setTitle(title);
	}

	/** If shift is down, scroll to the next non-empty layer; otherwise, if scroll_step is larger than 1, then scroll 'scroll_step' layers ahead; else just the next Layer. */
	public void nextLayer(int modifiers) {
		//setLayer(layer.getParent().next(layer));
		//scroller.setValue(layer.getParent().getLayerIndex(layer.getId()));
		if (0 == (modifiers ^ Event.SHIFT_MASK)) {
			new SetLayerThread(this, layer.getParent().nextNonEmpty(layer));
		} else if (scroll_step > 1) {
			int i = layer.getParent().indexOf(this.layer);
			Layer la = layer.getParent().getLayer(i + scroll_step);
			if (null != la) new SetLayerThread(this, la);
		} else {
			new SetLayerThread(this, layer.getParent().next(layer));
		}
		updateInDatabase("layer_id");
	}

	/** If shift is down, scroll to the previous non-empty layer; otherwise, if scroll_step is larger than 1, then scroll 'scroll_step' layers backward; else just the previous Layer. */
	public void previousLayer(int modifiers) {
		//setLayer(layer.getParent().previous(layer));
		//scroller.setValue(layer.getParent().getLayerIndex(layer.getId()));
		if (0 == (modifiers ^ Event.SHIFT_MASK)) {
			new SetLayerThread(this, layer.getParent().previousNonEmpty(layer));
		} else if (scroll_step > 1) {
			int i = layer.getParent().indexOf(this.layer);
			Layer la = layer.getParent().getLayer(i - scroll_step);
			if (null != la) new SetLayerThread(this, la);
		} else {
			new SetLayerThread(this, layer.getParent().previous(layer));
		}
		updateInDatabase("layer_id");
	}

	static public void updateLayerScroller(LayerSet set) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.getParent().equals(set)) {
				d.updateLayerScroller(d.layer);
			}
		}
	}

	private void updateLayerScroller(Layer layer) {
		int size = layer.getParent().size();
		if (size <= 1) {
			scroller.setValues(0, 1, 0, 0);
			scroller.setEnabled(false);
			return;
		}
		scroller.setValues(layer.getParent().getLayerIndex(layer.getId()), 1, 0, size);
	}

	private void updateSnapshots() {
		Enumeration e = hs_panels.elements();
		while (e.hasMoreElements()) {
			DisplayablePanel dp = (DisplayablePanel)e.nextElement();
			dp.remake();
		}
		updateComponent(tabs.getSelectedComponent());
	}

	static public void updatePanel(Layer layer, Displayable displ) {
		if (null == layer && null != front) layer = front.layer; // the front layer
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.layer.equals(layer)) {
				d.updatePanel(displ);
			}
		}
	}

	private void updatePanel(Displayable d) {
		JPanel c = null;
		if (d instanceof Profile) {
			c = panel_profiles;
		} else if (d instanceof Patch) {
			c = panel_patches;
		} else if (d instanceof DLabel) {
			c = panel_labels;
		} else if (d instanceof Pipe) {
			c = panel_zdispl;
		}
		if (null == c) return;
		DisplayablePanel dp = (DisplayablePanel)hs_panels.get(d);
		dp.remake();
		updateComponent(c);
	}

	static public void updatePanelIndex(Layer layer, Displayable displ) {
		Iterator it = al_displays.iterator();
		while (it.hasNext()) {
			Display d = (Display)it.next();
			if (d.layer.equals(layer) || displ instanceof ZDisplayable) {
				d.updatePanelIndex(displ);
			}
		}
	}

	private void updatePanelIndex(Displayable d) {
		// find first of the kind, then remove and insert its panel
		int i = 0;
		JPanel c = null;
		if (d instanceof ZDisplayable) {
			i = layer.getParent().indexOf((ZDisplayable)d);
			c = panel_zdispl;
		} else {
			i = layer.relativeIndexOf(d);
			if (d instanceof Profile) {
				c = panel_profiles;
			} else if (d instanceof Patch) {
				c = panel_patches;
			} else if (d instanceof DLabel) {
				c = panel_labels;
			}
		}
		if (null == c) return;
		DisplayablePanel dp = (DisplayablePanel)hs_panels.get(d);
		if (null == dp) return; // may be half-baked, wait
		c.remove(dp);
		c.add(dp, i); // java and its fabulous consistency
		updateComponent(c);
	}

	/** Repair possibly missing panels and other components by simply resetting the same Layer */
	public void repairGUI() {
		Layer layer = this.layer;
		this.layer = null;
		setLayer(layer);
	}

	public void actionPerformed(ActionEvent ae) {
		String command = ae.getActionCommand();
		if (command.startsWith("Job")) {
			if (Utils.checkYN("Really cancel job?")) {
				project.getLoader().quitJob(command);
				repairGUI();
			}
			return;
		} else if (command.equals("Move to top")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			layer.getParent().move(LayerSet.TOP, active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move up")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			layer.getParent().move(LayerSet.UP, active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move down")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			layer.getParent().move(LayerSet.DOWN, active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move to bottom")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			layer.getParent().move(LayerSet.BOTTOM, active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Duplicate, link and send to next layer")) {
			if (null == active || !(active instanceof Profile)) return;
			Layer next_layer = layer.getParent().next(layer);
			if (layer.equals(next_layer)) return; // no next layer! The menu item will be disabled anyway
			Profile profile = project.getProjectTree().duplicateChild((Profile)active, 1, next_layer);
			if (null == profile) return;
			active.link(profile);
			next_layer.add(profile);
			Thread thread = new SetLayerThread(this, next_layer);//setLayer(next_layer);
			try { thread.join(); } catch (InterruptedException ie) {} // wait until finished!
			selection.add(profile); //setActive(profile);
		} else if (command.equals("Duplicate, link and send to previous layer")) {
			if (null == active || !(active instanceof Profile)) return;
			Layer previous_layer = layer.getParent().previous(layer);
			if (layer.equals(previous_layer)) return; // no previous layer!
			Profile profile = project.getProjectTree().duplicateChild((Profile)active, 0, previous_layer);
			if (null == profile) return;
			active.link(profile);
			previous_layer.add(profile);
			Thread thread = new SetLayerThread(this, previous_layer);//setLayer(previous_layer);
			try { thread.join(); } catch (InterruptedException ie) {} // wait until finished!
			selection.add(profile); //setActive(profile);
		} else if (IJ.isLinux() && command.equals("Duplicate, link and send to...")) {
			// fix non-scrolling popup menu
			GenericDialog gd = new GenericDialog("Send to");
			gd.addMessage("Duplicate, link and send to...");
			String[] sl = new String[layer.getParent().size()];
			int next = 0;
			for (Iterator it = layer.getParent().getLayers().iterator(); it.hasNext(); ) {
				sl[next++] = project.findLayerThing(it.next()).toString();
			}
			gd.addChoice("Layer: ", sl, sl[layer.getParent().indexOf(layer)]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			Layer la = layer.getParent().getLayer(gd.getNextChoiceIndex());
			if (layer.equals(la)) {
				Utils.showMessage("Can't duplicate, link and send to the same layer.");
				return;
			}
			Profile profile = project.getProjectTree().duplicateChild((Profile)active, 0, la);
			if (null == profile) return;
			active.link(profile);
			la.add(profile);
			Thread thread = new SetLayerThread(this, la);//setLayer(la);
			try { thread.join(); } catch (InterruptedException ie) {} // waint until finished!
			selection.add(profile);
		} else if (-1 != command.indexOf("z = ")) {
			// this is an item from the "Duplicate, link and send to" menu of layer z's
			Layer target_layer = layer.getParent().getLayer(Double.parseDouble(command.substring(command.lastIndexOf(' ') +1)));
			Utils.log2("layer: __" +command.substring(command.lastIndexOf(' ') +1) + "__");
			if (null == target_layer) return;
			Profile profile = project.getProjectTree().duplicateChild((Profile)active, 0, target_layer);
			if (null == profile) return;
			active.link(profile);
			target_layer.add(profile);
			Thread thread = new SetLayerThread(this, target_layer);//setLayer(target_layer);
			try { thread.join(); } catch (InterruptedException ie) {} // waint until finished!
			selection.add(profile); // setActive(profile); // this is repainting only the active, not everything, because it cancels the repaint sent by the setLayer ! BUT NO, it should add up to the max box.
		} else if (-1 != command.indexOf("z=")) {
			// WARNING the indexOf is very similar to the previous one
			// Send the linked group to the selected layer
			int iz = command.indexOf("z=")+2;
			double lz = Double.parseDouble(command.substring(iz, command.indexOf(' ', iz+2)));
			Layer target = layer.getParent().getLayer(lz);
			HashSet hs = active.getLinkedGroup(new HashSet());
			layer.getParent().move(hs, active.getLayer(), target);
		} else if (command.equals("Unlink")) {
			if (null == active || active instanceof Patch) return;
			active.unlink();
			updateSelection();//selection.update();
		} else if (command.equals("Unlink from images")) {
			if (null == active) return;
			try {
				active.unlinkAll(Patch.class);
				updateSelection();//selection.update();
			} catch (Exception e) { new IJError(e); }
		} else if (command.equals("Send to next layer")) {
			try {
				// unlink Patch instances
				active.unlinkAll(Patch.class);
				updateSelection();//selection.update();
			} catch (Exception e) { new IJError(e); }
			//layer.getParent().moveDown(layer, active); // will repaint whatever appropriate layers
			selection.moveDown();
		} else if (command.equals("Send to previous layer")) {
			try {
				// unlink Patch instances
				active.unlinkAll(Patch.class);
				updateSelection();//selection.update();
			} catch (Exception e) { new IJError(e); }
			//layer.getParent().moveUp(layer, active); // will repaint whatever appropriate layers
			selection.moveUp();
		} else if (command.equals("Show centered")) {
			if (active == null) return;
			canvas.showCentered(selection.getBox());
		} else if (command.equals("Delete...")) {
			/*
			if (null != active) {
				Displayable d = active;
				selection.remove(d);
				d.remove(true); // will repaint
			}
			*/
			// remove all selected objects
			selection.deleteAll();
		} else if (command.equals("Color...")) {
			IJ.doCommand("Color Picker...");
		} else if (command.equals("Revert")) {
			if (! (active instanceof Patch && last_temp instanceof PatchStack)) return;
			Patch patch = (Patch)active;
			PatchStack ps = (PatchStack)last_temp;
			if (1 == last_temp.getStackSize()) {
				ps.revert(patch);
			} else {
				YesNoCancelDialog yd = new YesNoCancelDialog(this.frame, "Revert", "Revert all slices?");
				if (yd.yesPressed()) {
					// revert all slices
					ps.revertAll();
				} else if (!yd.cancelPressed()) {
					// revert current slice only
					ps.revert(patch);
				}
				// else do nothing (canceled)
			}
			navigator.repaint(true);
		} else if (command.equals("Undo")) {
			if (! (active instanceof Patch)) return;
			if (!undo) return;
			Patch patch = (Patch)active;
			undo = false; // no more undos until the active_imp_copy has been made new in the imageUpdated(ImagePlus) method.
			// set a copy of the active_imp_copy as the working one for the active Patch
			ImagePlus copy = new ImagePlus(active_imp_copy.getTitle(), active_imp_copy.getProcessor().duplicate());
			project.getLoader().updateCache(patch, copy);
			patch.updateInDatabase("tiff_working"); //saves the cached one above.
			canvas.repaint(active, 5);
			navigator.repaint(true);
			patch.getSnapshot().remake();
			((JPanel)hs_panels.get(patch)).repaint();
		} else if (command.equals("Undo transforms")) {
			if (canvas.isTransforming()) return;
			if (!layer.getParent().canRedo()) {
				// catch the current
				layer.getParent().appendCurrent(selection.getTransformationsCopy());
			}
			layer.getParent().undoOneStep();
		} else if (command.equals("Redo transforms")) {
			if (canvas.isTransforming()) return;
			layer.getParent().redoOneStep();
		} else if (command.equals("Transform")) {
			if (null == active) {
				Utils.log2("Display \"Transform\": null active!");
				return;
			}
			//TODO//active.handleDoubleClick(); //this is badly named
			canvas.setTransforming(true);
		} else if (command.equals("Apply transform")) {
			if (null == active) return;
			canvas.setTransforming(false);
		} else if (command.equals("Cancel transform")) {
			if (null == active) return;
			canvas.cancelTransform();
		} else if (command.equals("Specify transform...")) {
			if (null == active) return;
			final GenericDialog gd = new GenericDialog("Specify");
			gd.addMessage("Relative to the floater's position:");
			gd.addNumericField("rotate : ", 0, 3);
			gd.addNumericField("translate in X: ", 102.4, 3);
			gd.addNumericField("translate in Y: ", 102.4, 3);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Rectangle sel_box = selection.getLinkedBox();
			final double rot = gd.getNextNumber();
			final double dx = gd.getNextNumber();
			final double dy = gd.getNextNumber();
			if (0 != dx || 0 != dy) selection.translate(dx, dy);
			if (0 != rot) selection.rotate(rot);
			sel_box.add(selection.getLinkedBox());
			repaint(this.layer, sel_box, Selection.PADDING);
		} else if (command.startsWith("Hide all ")) {
			String type = command.substring(9, command.length() -1); // skip the ending plural 's'
			type = type.substring(0, 1).toUpperCase() + type.substring(1);
			layer.getParent().setVisible(type, false, true);
		} else if (command.startsWith("Unhide all ")) {
			String type = command.substring(11, command.length() -1); // skip the ending plural 's'
			type = type.substring(0, 1).toUpperCase() + type.substring(1);
			layer.getParent().setVisible(type, true, true);
		} else if (command.equals("Resize canvas/LayerSet...")) {
			GenericDialog gd = new GenericDialog("Resize LayerSet");
			gd.addNumericField("new width: ", layer.getLayerWidth(), 3);
			gd.addNumericField("new height: ",layer.getLayerHeight(),3);
			gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[7]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			double new_width = gd.getNextNumber();
			double new_height =gd.getNextNumber();
			layer.getParent().setDimensions(new_width, new_height, gd.getNextChoiceIndex()); // will complain and prevent cropping existing Displayable objects
		} else if (command.equals("Autoresize canvas/LayerSet")) {
			layer.getParent().setMinimumDimensions();
			//frame.pack(); // not even this solves the unresizable JPanel problem
		} /* OBSOLETE *//* else if (command.equals("Rotate Layer/LayerSet...")) {
			GenericDialog gd = new GenericDialog("Rotate LayerSet");
			gd.addChoice("Rotate: ", LayerSet.ROTATIONS, LayerSet.ROTATIONS[0]);
			gd.addCheckbox("All Layers", false);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			int direction = LayerSet.R90 + gd.getNextChoiceIndex();
			if (gd.getNextBoolean()) {
				layer.getParent().rotate(direction);
			} else {
				layer.rotate(direction, true, true);
			}
		} */else if (command.equals("Import image")) {
			importImage();
		} else if (command.equals("Import next image")) {
			importNextImage();
		} else if (command.equals("Import stack...")) {
			project.getLoader().importStack(layer, null, true);
		} else if (command.equals("Import grid...")) {
			project.getLoader().importGrid(layer);
		} else if (command.equals("Import sequence as grid...")) {
			project.getLoader().importSequenceAsGrid(layer);
		} else if (command.equals("Make flat image...")) {
			// if there's a ROI, just use that as cropping rectangle
			Rectangle srcRect = null;
			Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null != roi) {
				srcRect = roi.getBounds();
			} else {
				// otherwise, whatever is visible
				//srcRect = canvas.getSrcRect();
				// The above is confusing. That is what ROIs are for. So paint all:
				srcRect = new Rectangle(0, 0, (int)Math.ceil(layer.getParent().getLayerWidth()), (int)Math.ceil(layer.getParent().getLayerHeight()));
			}
			double scale = 1.0;
			final String[] types = new String[]{"8-bit grayscale", "RGB"};
			int the_type = ImagePlus.GRAY8;
			final GenericDialog gd = new GenericDialog("Choose", frame);
			gd.addNumericField("Scale: ", 1.0, 2);
			gd.addChoice("Type: ", types, types[0]);
			if (layer.getParent().size() > 1) {
				/*
				String[] layers = new String[layer.getParent().size()];
				int i = 0;
				for (Iterator it = layer.getParent().getLayers().iterator(); it.hasNext(); ) {
					layers[i] = layer.getProject().findLayerThing((Layer)it.next()).toString();
					i++;
				}
				int i_layer = layer.getParent().indexOf(layer);
				gd.addChoice("Start: ", layers, layers[i_layer]);
				gd.addChoice("End: ", layers, layers[i_layer]);
				*/
				Utils.addLayerRangeChoices(this.layer, gd); /// $#%! where are my lisp macros
				gd.addCheckbox("Include non-empty layers only", true);
			}
			gd.addCheckbox("Best quality", false);
			gd.addMessage("");
			gd.addCheckbox("Save to file", false);
			gd.addCheckbox("Save for web", false);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			scale = gd.getNextNumber();
			the_type = (0 == gd.getNextChoiceIndex() ? ImagePlus.GRAY8 : ImagePlus.COLOR_RGB);
			if (Double.isNaN(scale) || scale <= 0.0) {
				Utils.showMessage("Invalid scale.");
				return;
			}
			Layer[] layer_array = null;
			boolean non_empty_only = false;
			if (layer.getParent().size() > 1) {
				non_empty_only = gd.getNextBoolean();
				int i_start = gd.getNextChoiceIndex();
				int i_end = gd.getNextChoiceIndex();
				ArrayList al = new ArrayList();
				ArrayList al_zd = layer.getParent().getZDisplayables();
				ZDisplayable[] zd = new ZDisplayable[al_zd.size()];
				al_zd.toArray(zd);
				for (int i=i_start, j=0; i <= i_end; i++, j++) {
					Layer la = layer.getParent().getLayer(i);
					if (!la.isEmpty()) al.add(la); // checks both the Layer and the ZDisplayable objects in the parent LayerSet
				}
				if (0 == al.size()) {
					Utils.showMessage("All layers are empty!");
					return;
				}
				layer_array = new Layer[al.size()];
				al.toArray(layer_array);
			} else {
				layer_array = new Layer[]{this.layer};
			}
			final boolean quality = gd.getNextBoolean();
			final boolean save_to_file = gd.getNextBoolean();
			final boolean save_for_web = gd.getNextBoolean();
			// in its own thread
			if (save_for_web) project.getLoader().makePrescaledTiles(layer_array, Patch.class, srcRect, scale, c_alphas, the_type);
			else project.getLoader().makeFlatImage(layer_array, srcRect, scale, c_alphas, the_type, save_to_file, quality);

		} else if (command.equals("Lock")) {
			selection.setLocked(true);
		} else if (command.equals("Unlock")) {
			selection.setLocked(false);
		} else if (command.equals("Properties...")) {
			active.adjustProperties();
			updateSelection();
		} else if (command.equals("Cancel alignment")) {
			layer.getParent().cancelAlign();
		} else if (command.equals("Align with landmarks")) {
			layer.getParent().applyAlign(false);
		} else if (command.equals("Align and register")) {
			layer.getParent().applyAlign(true);
		} else if (command.equals("Align using profiles")) {
			if (!selection.contains(Profile.class)) {
				Utils.showMessage("No profiles are selected.");
				return;
			}
			// ask for range of layers
			final GenericDialog gd = new GenericDialog("Choose range");
			Utils.addLayerRangeChoices(this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			Layer la_start = layer.getParent().getLayer(gd.getNextChoiceIndex());
			Layer la_end = layer.getParent().getLayer(gd.getNextChoiceIndex());
			if (la_start.equals(la_end)) {
				Utils.showMessage("Need at least two layers.");
				return;
			}
			if (selection.isLocked()) {
				Utils.showMessage("There are locked objects.");
				return;
			}
			Utils.log2("step 1");
			layer.getParent().startAlign(this);
			layer.getParent().applyAlign(la_start, la_end, selection);
		} else if (command.equals("Align stack slices")) {
			if (getActive() instanceof Patch) {
				final Patch slice = (Patch)getActive();
				if (slice.isStack()) {
					// check linked group
					final HashSet hs = slice.getLinkedGroup(new HashSet());
					for (Iterator it = hs.iterator(); it.hasNext(); ) {
						if (!it.next().getClass().equals(Patch.class)) {
							Utils.showMessage("Images are linked to other objects, can't proceed to cross-correlate them."); // labels should be fine, need to check that
							return;
						}
					}
					slice.getLayer().getParent().createUndoStep(); // full
					Registration.registerStackSlices((Patch)getActive()); // will repaint
				} else {
					Utils.log("Align stack slices: selected image is not part of a stack.");
				}
			}
		} else if (command.equals("Align layers (montage-based)")) {
			Registration.registerLayers(layer, Registration.LAYER_SIFT);
		} else if (command.equals("Align layers (tile-based global minimization)")) {
			Registration.registerLayers(layer, Registration.GLOBAL_MINIMIZATION);
		} else if (command.equals("Adjust registration properties...")) {
			layer.getParent().adjustRegistrationProperties();
		} else if (command.equals("Properties ...")) { // NOTE the space before the dots, to distinguish from the "Properties..." command that works on Displayable objects.
			GenericDialog gd = new GenericDialog("Properties", this.frame);
			//gd.addNumericField("layer_scroll_step: ", this.scroll_step, 0);
			gd.addSlider("layer_scroll_step: ", 1, layer.getParent().size(), this.scroll_step);
			gd.addCheckbox("show_snapshots", layer.getParent().areSnapshotsEnabled());
			gd.addCheckbox("prefer_snapshots_quality", layer.getParent().snapshotsQuality());
			String preprocessor = project.getLoader().getPreprocessor();
			gd.addStringField("image_preprocessor: ", null == preprocessor ? "" : preprocessor);
			gd.addCheckbox("enable_layer_pixels virtualization", layer.getParent().isPixelsVirtualizationEnabled());
			double max = layer.getParent().getLayerWidth() < layer.getParent().getLayerHeight() ? layer.getParent().getLayerWidth() : layer.getParent().getLayerHeight();
			gd.addSlider("max_dimension of virtualized layer pixels: ", 0, max, layer.getParent().getPixelsDimension());
			gd.showDialog();
			if (gd.wasCanceled()) return;
			//
			int sc = (int) gd.getNextNumber();
			if (sc < 1) sc = 1;
			this.scroll_step = sc;
			updateInDatabase("scroll_step");
			//
			layer.getParent().setSnapshotsEnabled(gd.getNextBoolean());
			layer.getParent().setSnapshotsQuality(gd.getNextBoolean());
			//
			project.getLoader().setPreprocessor(gd.getNextString());
			//
			layer.getParent().setPixelsVirtualizationEnabled(gd.getNextBoolean());
			layer.getParent().setPixelsDimension((int)gd.getNextNumber());
		} else if (command.equals("Search...")) {
			new Search();
		} else if (command.equals("Select all")) {
			selection.selectAll();
			repaint(this.layer, selection.getBox(), 0);
		} else if (command.equals("Select none")) {
			Rectangle box = selection.getBox();
			selection.clear();
			repaint(this.layer, box, 0);
		} else if (command.equals("Merge")) {
			ArrayList al_sel = selection.getSelected();
			// put active at the beginning, to work as the base on which other's will get merged
			al_sel.remove(this.active);
			al_sel.add(0, this.active);
			AreaList ali = AreaList.merge(al_sel);
			if (null != ali) {
				// remove all but the first from the selection
				for (int i=1; i<al_sel.size(); i++) {
					Object ob = al_sel.get(i);
					if (ob.getClass().equals(AreaList.class)) {
						selection.remove((Displayable)ob);
					}
				}
				selection.updateTransform(ali);
				repaint(ali.getLayerSet(), ali, 0);
			}
		} else if (command.equals("View orthoslices")) {
			if (!(active instanceof Patch)) return;
			Display3D.showOrthoslices(((Patch)active));
		} else if (command.equals("View volume")) {
			if (!(active instanceof Patch)) return;
			Display3D.showVolume(((Patch)active));
		} else if (command.equals("Snap")) {
			if (!(active instanceof Patch)) return;
			canvas.snap(getActive());
		} else {
			Utils.log2("Display: don't know what to do with command " + command);
		}
	}

	/** Update in all displays the Transform for the given Displayable if it's selected. */
	static public void updateTransform(Displayable displ) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.selection.contains(displ)) d.selection.updateTransform(displ);
		}
	}

	/** Order the profiles of the parent profile_list by Z order, and fix the ProjectTree.*/
	/*
	private void fixZOrdering(Profile profile) {
		ProjectThing thing = project.findProjectThing(profile);
		if (null == thing) {
			Utils.log2("Display.fixZOrdering: null thing?");
			return;
		}
		((ProjectThing)thing.getParent()).fixZOrdering();
		project.getProjectTree().updateList(thing.getParent());
	}
	*/

	/** The number of layers to scroll through with the wheel; 1 by default.*/
	public int getScrollStep() { return this.scroll_step; }

	public void setScrollStep(int scroll_step) {
		if (scroll_step < 1) scroll_step = 1;
		this.scroll_step = scroll_step;
		updateInDatabase("scroll_step");
	}

	protected void importImage() {
		Rectangle srcRect = canvas.getSrcRect();
		int x = srcRect.x + srcRect.width / 2;
		int y = srcRect.y + srcRect.height/ 2;
		Patch p = project.getLoader().importImage(project, x, y);
		if (null == p) return;
		layer.add(p); // will add it to the proper Displays
	}

	protected void importNextImage() {
		Rectangle srcRect = canvas.getSrcRect();
		int x = srcRect.x + srcRect.width / 2;// - imp.getWidth() / 2;
		int y = srcRect.y + srcRect.height/ 2;// - imp.getHeight()/ 2;
		Patch p = project.getLoader().importNextImage(project, x, y);
		if (null == p) return;
		layer.add(p); // will add it to the proper Displays
	}


	/** Make the given channel have the given alpha (transparency). */
	public void setChannel(int c, float alpha) {
		int a = (int)(255 * alpha);
		int l = (c_alphas&0xff000000)>>24;
		int r = (c_alphas&0xff0000)>>16;
                int g = (c_alphas&0xff00)>>8;
		int b =  c_alphas&0xff;
		switch (c) {
			case Channel.MONO:
				// all to the given alpha
				c_alphas = (l<<24) + (r<<16) + (g<<8) + b; // parenthesis are NECESSARY
				break;
			case Channel.RED:
				// modify only the red
				c_alphas = (l<<24) + (a<<16) + (g<<8) + b;
				break;
			case Channel.GREEN:
				c_alphas = (l<<24) + (r<<16) + (a<<8) + b;
				break;
			case Channel.BLUE:
				c_alphas = (l<<24) + (r<<16) + (g<<8) + a;
				break;
		}
		//canvas.setUpdateGraphics(true);
		canvas.repaint(true);
		updateInDatabase("c_alphas");
	}

	/** Set the channel as active and the others as inactive. */
	public void setActiveChannel(Channel channel) {
		for (int i=0; i<4; i++) {
			if (channel != channels[i]) channels[i].setActive(false);
			else channel.setActive(true);
		}
		updateComponent(panel_channels);
		transp_slider.setValue((int)(channel.getAlpha() * 100));
	}

	public int getDisplayChannelAlphas() { return c_alphas; }

	// rename this method and the getDisplayChannelAlphas ! They sound the same!
	public int getChannelAlphas() {
		return ((int)(channels[0].getAlpha() * 255)<<24) + ((int)(channels[1].getAlpha() * 255)<<16) + ((int)(channels[2].getAlpha() * 255)<<8) + (int)(channels[3].getAlpha() * 255);
	}

	public int getChannelAlphasState() {
		return ((channels[0].isSelected() ? 255 : 0)<<24)
		     + ((channels[1].isSelected() ? 255 : 0)<<16)
		     + ((channels[2].isSelected() ? 255 : 0)<<8)
		     +  (channels[3].isSelected() ? 255 : 0);
	}

	/** Show the given Displayable centered. */ // TODO this is repeated with 'show(Displayable' to some extent
	static public void showCentered(Layer layer, Displayable displ) {
		// see if the given layer belongs to the layer set being displayed
		if (null != front && front.layer.getParent().equals(layer.getParent())) front.canvas.showCentered(displ.getBoundingBox());
		else {
			front = new Display(layer.getProject(), layer, displ);
			front.canvas.showCentered(displ.getBoundingBox());
		}
		// now a 'front' exists
		if (displ instanceof ZDisplayable) {
			// scroll to first layer that has a point
			ZDisplayable zd = (ZDisplayable)displ;
			front.setLayer(zd.getFirstLayer());
		}
	}

	/** Listen to interesting updates, such as the ColorPicker and updates to Patch objects. */
	public void imageUpdated(ImagePlus updated) {
		// detect ColorPicker
		if (this.equals(front) && updated instanceof ij.plugin.ColorPicker) {
			if (null != active) {
				active.setColor(Toolbar.getForegroundColor());
			}
			return;
		}
		// $%#@!!  LUT changes don't set the image as changed
		//if (updated instanceof PatchStack) {
		//	updated.changes = 1
		//}
		// the above is overkill. Instead:
		if (updated instanceof PatchStack) {
			((PatchStack)updated).deCacheAll();
		}


		// detect LUT changes: DONE at PatchStack, which is the active (virtual) image
		//Utils.log2("calling deCache for " + updated);
		//getProject().getLoader().deCache(updated);

		/* // done at the PatchStack level
		// new way: the temp is a virtual PatchStack
		if (updated.equals(last_temp)) {
			if (!last_temp.changes) {
				last_temp.reset();
			} else {
				last_temp.saveImages();
			}
		}
		*/
		//Utils.log2("imageUpdated: updated imp is " + updated.getTitle() + "\n\t and is last_temp: " + updated.equals(last_temp) + "\n\t and changes=" + updated.changes + " and class: " + updated.getClass());
	}

	public void imageClosed(ImagePlus imp) {}
	public void imageOpened(ImagePlus imp) {}


	protected ImagePlus getLastTemp() { return last_temp; } // can be null

	/** Release memory captured by the offscreen images */
	static public void flushAll() {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			d.canvas.flush();
		}
		System.gc();
		Thread.yield();
	}

	/** Can be null. */
	static public Display getFront() {
		return front;
	}

	static public void setCursorToAll(final Cursor c) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			d.frame.setCursor(c);
		}
	}

	protected void setCursor(Cursor c) {
		frame.setCursor(c);
	}

	/** Used by the Displayable to update the visibility checkbox in other Displays. */
	static protected void updateVisibilityCheckbox(Layer layer, Displayable displ, Display calling_display) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.equals(calling_display)) continue;
			if (d.layer.contains(displ) || (displ instanceof ZDisplayable && d.layer.getParent().contains((ZDisplayable)displ))) {
				DisplayablePanel dp = (DisplayablePanel)d.hs_panels.get(displ);
				dp.updateVisibilityCheckbox();
			}
		}
	}

	protected boolean isActiveWindow() {
		return frame.isActive();
	}

	/** Toggle user input; pan and zoom are always enabled though.*/
	static public void setReceivesInput(Project project, boolean b) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.project.equals(project)) d.canvas.setReceivesInput(b);
		}
	}

	/** Export the DTD that defines this object. */
	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		if (hs.contains("t2_display")) return; // TODO to avoid collisions the type shoud be in a namespace such as tm2:display
		hs.add("t2_display");
		sb_header.append(indent).append("<!ELEMENT t2_display EMPTY>\n")
			 .append(indent).append("<!ATTLIST t2_display id NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display layer_id NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display x NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display y NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display magnification NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display srcrect_x NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display srcrect_y NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display srcrect_width NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display srcrect_height NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display scroll_step NMTOKEN #REQUIRED>\n")
		;
	}
	/** Export all displays of the given project as XML entries. */
	static public void exportXML(Project project, StringBuffer sb_body, String indent, Object any) {
		String in = indent + "\t";
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (!d.project.equals(project)) continue;
			Rectangle r = d.frame.getBounds();
			Rectangle srcRect = d.canvas.getSrcRect();
			double magnification = d.canvas.getMagnification();
			sb_body.append(indent).append("<t2_display id=\"").append(d.id).append("\"\n")
			       .append(in).append("layer_id=\"").append(d.layer.getId()).append("\"\n")
			       .append(in).append("c_alphas=\"").append(d.c_alphas).append("\"\n")
			       .append(in).append("c_alphas_state=\"").append(d.getChannelAlphasState()).append("\"\n")
			       .append(in).append("x=\"").append(r.x).append("\"\n")
			       .append(in).append("y=\"").append(r.y).append("\"\n")
			       .append(in).append("magnification=\"").append(magnification).append("\"\n")
			       .append(in).append("srcrect_x=\"").append(srcRect.x).append("\"\n")
			       .append(in).append("srcrect_y=\"").append(srcRect.y).append("\"\n")
			       .append(in).append("srcrect_width=\"").append(srcRect.width).append("\"\n")
			       .append(in).append("srcrect_height=\"").append(srcRect.height).append("\"\n")
			       .append(in).append("scroll_step=\"").append(d.scroll_step).append("\"\n")
			;
			sb_body.append(indent).append("/>\n");
		}
	}

	static public void toolChanged(String tool_name) {
		Utils.log2("tool name: " + tool_name);
		if (!tool_name.equals("ALIGN")) {
			for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
				Display d = (Display)it.next();
				d.layer.getParent().cancelAlign();
			}
		}
	}

	static public void toolChanged(int tool) {
		Utils.log2("int tool is " + tool);
		if (ProjectToolbar.PEN == tool) {
			// erase bounding boxes
			for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
				Display d = (Display)it.next();
				if (null != d.active) d.repaint(d.layer, d.selection.getBox(), 2);
			}
		}
		if (null != front) {
			if (tool < ProjectToolbar.SELECT || tool > ProjectToolbar.ALIGN) {
				Loader.setTempCurrentImage(front.canvas.getFakeImagePlus());
			} else {
				Loader.setTempCurrentImage(front.last_temp);
			}
		}
	}

	public Selection getSelection() {
		return selection;
	}

	public boolean isSelected(Displayable d) {
		return selection.contains(d);
	}

	static public void updateSelection() {
		Display.updateSelection(null);
	}
	static public void updateSelection(Display calling) {
		HashSet hs = new HashSet();
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (hs.contains(d.layer)) continue;
			hs.add(d.layer);
			if (null == d || null == d.selection) {
				Utils.log("d is : "+ d + " d.selection is " + d.selection);
			} else {
				d.selection.update(); // recomputes box
			}
			if (!d.equals(calling)) { // TODO this is so dirty!
				if (d.selection.getNLinked() > 1) d.canvas.setUpdateGraphics(true); // this is overkill anyway
				d.canvas.repaint(d.selection.getLinkedBox(), Selection.PADDING);
				d.navigator.repaint(true); // everything
			}
		}
	}

	static public void clearSelection(Layer layer) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.layer.equals(layer)) d.selection.clear();
		}
	}

	private void setTempCurrentImage() {
		//Utils.log2("Setting temp current image");
		// synchronized:
		if (null != last_temp) last_temp.setSlice(layer.getParent().indexOf(layer) +1);
		Loader.setTempCurrentImage(last_temp);
	}

	/** Sets the ImagePlus that ImageJ will see in its WindowManager while this Display is activated. */
	private void createTempCurrentImage() {
		ImagePlus temp = null;
		final ArrayList al = selection.getSelected();
		if (1 == selection.getNSelected() && getActive() instanceof Patch) {
			// present the currently selected image or stack
			Patch patch = (Patch)al.get(0);
			PatchStack ps = patch.makePatchStack(); // gets the LayerSet calibration on its own
			ps.setCurrentSlice(patch);
			temp = ps;
		} else {
			if (layer.getParent().isPixelsVirtualizationEnabled()) {
				// show all selected in a LayerStack
				ImageStack stack = null == temp ? null : temp.getStack();
				if (null != stack && stack instanceof LayerStack) {
					((LayerStack)stack).setDisplayables(al);
				} else {
					LayerStack lstack = layer.getParent().makeLayerStack(this);
					lstack.setDisplayables(al);
					stack = lstack;
				}
				// make new to renew the width and height
				temp = ((LayerStack)stack).getImagePlus(); // gets the LayerSet calibration on its own
				int i_slice = layer.getParent().indexOf(layer) +1;
				temp.setSlice(i_slice);
			} else {
				temp = canvas.getFakeImagePlus();
				((FakeImagePlus)temp).setCalibrationSuper(layer.getParent().getCalibrationCopy());
			}
		}
		// Calibration is taken care of in the fake imagepluses constructors
		//temp.getCalibration().pixelDepth = layer.getParent().getLayer(0).getThickness();
		last_temp = temp;
		//Utils.log2("currentSlice: " + temp.getCurrentSlice() + " for layer index " + layer.getParent().indexOf(layer));
	}
}
