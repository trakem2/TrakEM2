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
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.IJError;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.imaging.Blending;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.DNDInsertImage;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Dispatcher;
import ini.trakem2.utils.Lock;
import ini.trakem2.utils.M;
import ini.trakem2.tree.*;
import ini.trakem2.display.graphics.*;

import javax.swing.*;
import javax.swing.event.*;

import mpicbg.trakem2.align.AlignTask;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.Writer;
import java.util.concurrent.Future;

import lenscorrection.DistortionCorrectionTask;

/** A Display is a class to show a Layer and enable mouse and keyboard manipulation of all its components. */
public final class Display extends DBObject implements ActionListener, ImageListener {

	/** The Layer this Display is showing. */
	private Layer layer;

	private Displayable active = null;
	/** All selected Displayable objects, including the active one. */
	final private Selection selection = new Selection(this);

	private JFrame frame;
	private JTabbedPane tabs;
	private Hashtable<Class,JScrollPane> ht_tabs;
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

	private JPanel panel_layers;
	private JScrollPane scroll_layers;
	private Hashtable<Layer,LayerPanel> layer_panels = new Hashtable<Layer,LayerPanel>();

	private JSlider transp_slider;
	private DisplayNavigator navigator;
	private JScrollBar scroller;

	private DisplayCanvas canvas; // WARNING this is an AWT component, since it extends ImageCanvas
	private JPanel canvas_panel; // and this is a workaround, to better (perhaps) integrate the awt canvas inside a JSplitPane
	private JSplitPane split;

	private JPopupMenu popup = null;
	
	private ToolbarPanel toolbar_panel = null;

	/** Contains the packed alphas of every channel. */
	private int c_alphas = 0xffffffff; // all 100 % visible
	private Channel[] channels;

	private Hashtable<Displayable,DisplayablePanel> ht_panels = new Hashtable<Displayable,DisplayablePanel>();

	/** Handle drop events, to insert image files. */
	private DNDInsertImage dnd;

	private boolean size_adjusted = false;

	private int scroll_step = 1;

	/** Keep track of all existing Display objects. */
	static private ArrayList<Display> al_displays = new ArrayList<Display>();
	/** The currently focused Display, if any. */
	static private Display front = null;

	/** Displays to open when all objects have been reloaded from the database. */
	static private final Hashtable ht_later = new Hashtable();

	/** A thread to handle user actions, for example an event sent from a popup menu. */
	private final Dispatcher dispatcher = new Dispatcher();

	static private WindowAdapter window_listener = new WindowAdapter() {
		/** Unregister the closed Display. */
		public void windowClosing(WindowEvent we) {
			final Object source = we.getSource();
			for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
				Display d = (Display)it.next();
				if (source == d.frame) {
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
			final Object source = we.getSource();
			for (final Display d : al_displays) {
				if (source == d.frame) {
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
			final Object source = we.getSource();
			for (final Display d : al_displays) {
				if (source != d.frame) continue;
				d.pack();
				break;
			}
		}
	};

	static private MouseListener frame_mouse_listener = new MouseAdapter() {
		public void mouseReleased(MouseEvent me) {
			Object source = me.getSource();
			for (final Display d : al_displays) {
				if (d.frame == source) {
					if (d.size_adjusted) {
						d.pack();
						d.size_adjusted = false;
						Utils.log2("mouse released on JFrame");
					}
					break;
				}
			}
		}
	};

	private int last_frame_state = frame.NORMAL;

	// THIS WHOLE SYSTEM OF LISTENERS IS BROKEN:
	// * when zooming in, the window growths in width a few pixels.
	// * when enlarging the window quickly, the canvas is not resized as large as it should.
	// -- the whole problem: swing threading, which I am not handling properly. It's hard.
	static private ComponentListener component_listener = new ComponentAdapter() {
		public void componentResized(ComponentEvent ce) {
			final Display d = getDisplaySource(ce);
			if (null != d) {
				d.size_adjusted = true; // works in combination with mouseReleased to call pack(), avoiding infinite loops.
				d.adjustCanvas();
				int frame_state = d.frame.getExtendedState();
			       	if (frame_state != d.last_frame_state) { // this setup avoids infinite loops (for pack() calls componentResized as well
					d.last_frame_state = frame_state;
					if (d.frame.ICONIFIED != frame_state) d.pack();
				}
			}
		}
		public void componentMoved(ComponentEvent ce) {
			Display d = getDisplaySource(ce);
			if (null != d) d.updateInDatabase("position");
		}
		private Display getDisplaySource(ComponentEvent ce) {
			final Object source = ce.getSource();
			for (final Display d : al_displays) {
				if (source == d.frame) {
					return d;
				}
			}
			return null;
		}
	};

	static private ChangeListener tabs_listener = new ChangeListener() {
		/** Listen to tab changes. */
		public void stateChanged(final ChangeEvent ce) {
			final Object source = ce.getSource();
			for (final Display d : al_displays) {
				if (source == d.tabs) {
					d.dispatcher.exec(new Runnable() { public void run() {
					// creating tabs fires the event!!!
					if (null == d.frame || null == d.canvas) return;
					final Container tab = (Container)d.tabs.getSelectedComponent();
					if (tab == d.scroll_channels) {
						// find active channel if any
						for (int i=0; i<d.channels.length; i++) {
							if (d.channels[i].isActive()) {
								d.transp_slider.setValue((int)(d.channels[i].getAlpha() * 100));
								break;
							}
						}
					} else {
						// recreate contents
						/*
						int count = tab.getComponentCount();
						if (0 == count || (1 == count && tab.getComponent(0).getClass().equals(JLabel.class))) {
						*/ // ALWAYS, because it could be the case that the user changes layer while on one specific tab, and then clicks on the other tab which may not be empty and shows totally the wrong contents (i.e. for another layer)

							String label = null;
							ArrayList al = null;
							JPanel p = null;
							if (tab == d.scroll_zdispl) {
								label = "Z-space objects";
								al = d.layer.getParent().getZDisplayables();
								p = d.panel_zdispl;
							} else if (tab == d.scroll_patches) {
								label = "Patches";
								al = d.layer.getDisplayables(Patch.class);
								p = d.panel_patches;
							} else if (tab == d.scroll_labels) {
								label = "Labels";
								al = d.layer.getDisplayables(DLabel.class);
								p = d.panel_labels;
							} else if (tab == d.scroll_profiles) {
								label = "Profiles";
								al = d.layer.getDisplayables(Profile.class);
								p = d.panel_profiles;
							} else if (tab == d.scroll_layers) {
								// nothing to do
								return;
							}

							d.updateTab(p, label, al);
							//Utils.updateComponent(d.tabs.getSelectedComponent());
							//Utils.log2("updated tab: " + p + "  with " + al.size() + "  objects.");
						//}

						if (null != d.active) {
							// set the transp slider to the alpha value of the active Displayable if any
							d.transp_slider.setValue((int)(d.active.getAlpha() * 100));
							DisplayablePanel dp = d.ht_panels.get(d.active);
							if (null != dp) dp.setActive(true);
						}
					}
					}});
					break;
				}
			}
		}
	};

	private final ScrollLayerListener scroller_listener = new ScrollLayerListener();

	private class ScrollLayerListener implements AdjustmentListener {

		public void adjustmentValueChanged(final AdjustmentEvent ae) {
			final int index = scroller.getValue();
			slt.set(layer.getParent().getLayer(index));
		}
	}

	private final SetLayerThread slt = new SetLayerThread();

	private class SetLayerThread extends Thread {

		private boolean go = true;
		private Layer layer;
		private final Lock lock = new Lock();

		SetLayerThread() {
			setPriority(Thread.NORM_PRIORITY);
			setDaemon(true);
			start();
		}

		public final void set(final Layer layer) {
			synchronized (lock) {
				this.layer = layer;
			}
			synchronized (this) {
				notify();
			}
		}

		// Does not use the thread, rather just sets it within the context of the calling thread (would be the same as making the caller thread wait.)
		final void setAndWait(final Layer layer) {
			if (null != layer) {
				Display.this.setLayer(layer);
				Display.this.updateInDatabase("layer_id");
			}
		}

		public void run() {
			while (go) {
				while (null == this.layer) {
					synchronized (this) {
						try { wait(); } catch (InterruptedException ie) {}
					}
				}
				Layer layer = null;
				synchronized (lock) {
					layer = this.layer;
					this.layer = null;
				}
				//
				if (!go) return; // after nullifying layer
				//
				setAndWait(layer);
			}
		}

		public void quit() {
			go = false;
		}
	}

	/** Creates a new Display with adjusted magnification to fit in the screen. */
	static public void createDisplay(final Project project, final Layer layer) {
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			Display display = new Display(project, layer);
			Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			Rectangle srcRect = new Rectangle(0, 0, (int)layer.getLayerWidth(), (int)layer.getLayerHeight());
			double mag = screen.width / layer.getLayerWidth();
			if (mag * layer.getLayerHeight() > screen.height) mag = screen.height / layer.getLayerHeight();
			mag = display.canvas.getLowerZoomLevel2(mag);
			if (mag > 1.0) mag = 1.0;
			//display.getCanvas().setup(mag, srcRect); // would call pack() at the wrong time!
			// ... so instead: manually
			display.getCanvas().setMagnification(mag);
			display.getCanvas().setSrcRect(srcRect.x, srcRect.y, srcRect.width, srcRect.height);
			display.getCanvas().setDrawingSize((int)Math.ceil(srcRect.width * mag), (int)Math.ceil(srcRect.height * mag));
			//
			display.updateFrameTitle(layer);
			ij.gui.GUI.center(display.frame);
			display.frame.pack();
		}});
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
		addToDatabase();
	}

	/** For reconstruction purposes. The Display will be stored in the ht_later.*/
	public Display(Project project, long id, Layer layer, Object[] props) {
		super(project, id);
		synchronized (ht_later) {
			Display.ht_later.put(this, props);
		}
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
		addToDatabase();
	}

	/** Reconstruct a Display from an XML entry, to be opened when everything is ready. */
	public Display(Project project, long id, Layer layer, HashMap ht_attributes) {
		super(project, id);
		if (null == layer) {
			Utils.log2("Display: need a non-null Layer for id=" + id);
			return;
		}
		Rectangle srcRect = new Rectangle(0, 0, (int)layer.getLayerWidth(), (int)layer.getLayerHeight());
		double magnification = 0.25;
		Point p = new Point(0, 0);
		int c_alphas = 0xffffffff;
		int c_alphas_state = 0xffffffff;
		for (Iterator it = ht_attributes.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			String key = (String)entry.getKey();
			String data = (String)entry.getValue();
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
				try {
					c_alphas = Integer.parseInt(data);
				} catch (Exception ex) {
					c_alphas = 0xffffffff;
				}
			} else if (key.equals("c_alphas_state")) {
				try {
					c_alphas_state = Integer.parseInt(data);
				} catch (Exception ex) {
					IJError.print(ex);
					c_alphas_state = 0xffffffff;
				}
			} else if (key.equals("scroll_step")) {
				try {
					setScrollStep(Integer.parseInt(data));
				} catch (Exception ex) {
					IJError.print(ex);
					setScrollStep(1);
				}
			}
			// TODO the above is insecure, in that data is not fully checked to be within bounds.
		}
		Object[] props = new Object[]{p, new Double(magnification), srcRect, new Long(layer.getId()), new Integer(c_alphas), new Integer(c_alphas_state)};
		synchronized (ht_later) {
			Display.ht_later.put(this, props);
		}
		this.layer = layer;
	}

	/** After reloading a project from the database, open the Displays that the project had. */
	static public Bureaucrat openLater() {
		final Hashtable ht_later_local;
		synchronized (ht_later) {
			if (0 == ht_later.size()) return null;
			ht_later_local = new Hashtable(ht_later);
			ht_later.keySet().removeAll(ht_later_local.keySet());
		}
		final Worker worker = new Worker("Opening displays") {
			public void run() {
				startedWorking();
				try {
					Thread.sleep(300); // waiting for Swing

		for (Enumeration e = ht_later_local.keys(); e.hasMoreElements(); ) {
			final Display d = (Display)e.nextElement();
			front = d; // must be set before repainting any ZDisplayable!
			Object[] props = (Object[])ht_later_local.get(d);
			if (ControlWindow.isGUIEnabled()) d.makeGUI(d.layer, props);
			d.setLayerLater(d.layer, d.layer.get(((Long)props[3]).longValue())); //important to do it after makeGUI
			if (!ControlWindow.isGUIEnabled()) continue;
			ImagePlus.addImageListener(d);
			al_displays.add(d);
			d.updateFrameTitle(d.layer);
			// force a repaint if a prePaint was done TODO this should be properly managed with repaints using always the invokeLater, but then it's DOG SLOW
			if (d.canvas.getMagnification() > 0.499) {
				SwingUtilities.invokeLater(new Runnable() { public void run() {
					d.repaint(d.layer);
					d.project.getLoader().setChanged(false);
					Utils.log2("A set to false");
				}});
			}
			d.project.getLoader().setChanged(false);
			Utils.log2("B set to false");
		}
		if (null != front) front.getProject().select(front.layer);

				} catch (Throwable t) {
					IJError.print(t);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, ((Display)ht_later_local.keySet().iterator().next()).getProject()); // gets the project from the first Display
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
		for (final KeyListener kl : this.transp_slider.getKeyListeners()) {
			this.transp_slider.removeKeyListener(kl);
		}

		// Tabbed pane on the left
		this.tabs = new JTabbedPane();
		this.tabs.setMinimumSize(new Dimension(250, 300));
		this.tabs.setBackground(Color.white);
		this.tabs.addChangeListener(tabs_listener);

		// Tab 1: Patches
		this.panel_patches = makeTabPanel();
		this.scroll_patches = makeScrollPane(panel_patches);
		this.tabs.add("Patches", scroll_patches);

		// Tab 2: Profiles
		this.panel_profiles = makeTabPanel();
		this.scroll_profiles = makeScrollPane(panel_profiles);
		this.tabs.add("Profiles", scroll_profiles);

		// Tab 3: pipes
		this.panel_zdispl = makeTabPanel();
		this.scroll_zdispl = makeScrollPane(panel_zdispl);
		this.tabs.add("Z space", scroll_zdispl);

		// Tab 4: channels
		this.panel_channels = makeTabPanel();
		this.scroll_channels = makeScrollPane(panel_channels);
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
		this.panel_labels = makeTabPanel();
		this.scroll_labels = makeScrollPane(panel_labels);
		this.tabs.add("Labels", scroll_labels);

		// Tab 6: layers
		this.panel_layers = makeTabPanel();
		this.scroll_layers = makeScrollPane(panel_layers);
		recreateLayerPanels(layer);
		this.scroll_layers.addMouseWheelListener(canvas);
		this.tabs.add("Layers", scroll_layers);

		this.ht_tabs = new Hashtable<Class,JScrollPane>();
		this.ht_tabs.put(Patch.class, scroll_patches);
		this.ht_tabs.put(Profile.class, scroll_profiles);
		this.ht_tabs.put(ZDisplayable.class, scroll_zdispl);
		this.ht_tabs.put(AreaList.class, scroll_zdispl);
		this.ht_tabs.put(Pipe.class, scroll_zdispl);
		this.ht_tabs.put(Polyline.class, scroll_zdispl);
		this.ht_tabs.put(Ball.class, scroll_zdispl);
		this.ht_tabs.put(Dissector.class, scroll_zdispl);
		this.ht_tabs.put(DLabel.class, scroll_labels);
		// channels not included
		// layers not included

		// Navigator
		this.navigator = new DisplayNavigator(this, layer.getLayerWidth(), layer.getLayerHeight());
		// Layer scroller (to scroll slices)
		int extent = (int)(250.0 / layer.getParent().size());
		if (extent < 10) extent = 10;
		this.scroller = new JScrollBar(JScrollBar.HORIZONTAL);
		updateLayerScroller(layer);
		this.scroller.addAdjustmentListener(scroller_listener);

		// Toolbar
		// Left panel, contains the transp slider, the tabbed pane, the navigation panel and the layer scroller
		JPanel left = new JPanel();
		left.setBackground(Color.white);
		BoxLayout left_layout = new BoxLayout(left, BoxLayout.Y_AXIS);
		left.setLayout(left_layout);
		toolbar_panel = new ToolbarPanel();
		left.add(toolbar_panel);
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
		this.split.setOneTouchExpandable(true); // NOT present in all L&F (?)
		this.split.setBackground(Color.white);

		// fix
		gb.setConstraints(split.getRightComponent(), c);

		// JFrame to show the split pane
		this.frame = ControlWindow.createJFrame(layer.toString());
		this.frame.setBackground(Color.white);
		this.frame.getContentPane().setBackground(Color.white);
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
			int cs = ((Integer)props[5]).intValue(); // aka c_alphas_state
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

		final Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();

		if (null != props) {
			// fix positioning outside the screen (dual to single monitor)
			if (p.x >= 0 && p.x < screen.width - 50 && p.y >= 0 && p.y <= screen.height - 50) this.frame.setLocation(p);
			else frame.setLocation(0, 0);
		}

		// fix excessive size
		final Rectangle box = this.frame.getBounds();
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
			if (size > 10000000) canvas.setInitialMagnification(0.25); // 10 Mb
			else {
				this.frame.setSize(new Dimension((int)(screen.width * 0.66), (int)(screen.height * 0.66)));
			}
		}

		updateTab(panel_patches, "Patches", layer.getDisplayables(Patch.class));
		Utils.updateComponent(tabs); // otherwise fails in FreeBSD java 1.4.2 when reconstructing


		// Set the calibration of the FakeImagePlus to that of the LayerSet
		((FakeImagePlus)canvas.getFakeImagePlus()).setCalibrationSuper(layer.getParent().getCalibrationCopy());

		updateFrameTitle(layer);
		// Set the FakeImagePlus as the current image
		setTempCurrentImage();

		// create a drag and drop listener
		dnd = new DNDInsertImage(this);

		// start a repainting thread
		if (null != props) {
			canvas.repaint(true); // repaint() is unreliable
		}

		// Set the minimum size of the tabbed pane on the left, so it can be completely collapsed now that it has been properly displayed. This is a patch to the lack of respect for the setDividerLocation method.
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				tabs.setMinimumSize(new Dimension(0, 100));
				Display.scrollbar_width = Display.this.scroll_patches.getVerticalScrollBar().getPreferredSize().width; // using scroll_patches since it's the one selected by default and thus visible and painted
				ControlWindow.setLookAndFeel();
			}
		});
	}

	static public void repaintToolbar() {
		for (final Display d : al_displays) {
			d.toolbar_panel.repaint();
		}
	}

	private class ToolbarPanel extends JPanel implements MouseListener {
		Method drawButton;
		Field lineType;
		Field SIZE;
		Field OFFSET;
		Toolbar toolbar = Toolbar.getInstance();
		int size;
		int offset;
		ToolbarPanel() {
			setBackground(Color.white);
			addMouseListener(this);
			try {
				drawButton = Toolbar.class.getDeclaredMethod("drawButton", Graphics.class, Integer.TYPE);
				drawButton.setAccessible(true);
				lineType = Toolbar.class.getDeclaredField("lineType");
				lineType.setAccessible(true);
				SIZE = Toolbar.class.getDeclaredField("SIZE");
				SIZE.setAccessible(true);
				OFFSET = Toolbar.class.getDeclaredField("OFFSET");
				OFFSET.setAccessible(true);
				size = ((Integer)SIZE.get(null)).intValue();
				offset = ((Integer)OFFSET.get(null)).intValue();
			} catch (Exception e) {
				IJError.print(e);
			}
			// Magic cocktail:
			Dimension dim = new Dimension(250, size+size);
			setPreferredSize(dim);
			setMinimumSize(dim);
			setMaximumSize(dim);
		}
		public void update(Graphics g) { paint(g); }
		public void paint(Graphics g) {
			try {
				int i = 0;
				for (; i<Toolbar.LINE; i++) {
					drawButton.invoke(toolbar, g, i);
				}
				drawButton.invoke(toolbar, g, lineType.get(toolbar));
				for (; i<=Toolbar.TEXT; i++) {
					drawButton.invoke(toolbar, g, i);
				}
				drawButton.invoke(toolbar, g, Toolbar.ANGLE);
				// newline
				AffineTransform aff = new AffineTransform();
				aff.translate(-size*Toolbar.TEXT, size-1);
				((Graphics2D)g).setTransform(aff);
				for (; i<18; i++) {
					drawButton.invoke(toolbar, g, i);
				}
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		public void mousePressed(MouseEvent me) {
			int x = me.getX();
			int y = me.getY();
			if (y > size) {
				if (x > size * 7) return; // off limits
				x += size * 9;
				y -= size;
			} else {
				if (x > size * 9) return; // off limits
			}
			Toolbar.getInstance().mousePressed(new MouseEvent(toolbar, me.getID(), System.currentTimeMillis(), me.getModifiers(), x, y, me.getClickCount(), me.isPopupTrigger()));
			repaint();
		}
		public void mouseReleased(MouseEvent me) {}
		public void mouseClicked(MouseEvent me) {}
		public void mouseEntered(MouseEvent me) {}
		public void mouseExited(MouseEvent me) {}
	}

	private JPanel makeTabPanel() {
		JPanel panel = new JPanel();
		BoxLayout layout = new BoxLayout(panel, BoxLayout.Y_AXIS);
		panel.setLayout(layout);
		return panel;
	}

	private JScrollPane makeScrollPane(Component c) {
		JScrollPane jsp = new JScrollPane(c);
		jsp.setBackground(Color.white); // no effect
		jsp.getViewport().setBackground(Color.white); // no effect
		// adjust scrolling to use one DisplayablePanel as the minimal unit
		jsp.getVerticalScrollBar().setBlockIncrement(DisplayablePanel.HEIGHT); // clicking within the track
		jsp.getVerticalScrollBar().setUnitIncrement(DisplayablePanel.HEIGHT); // clicking on an arrow
		jsp.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		jsp.setPreferredSize(new Dimension(250, 300));
		jsp.setMinimumSize(new Dimension(250, 300));
		return jsp;
	}

	static protected int scrollbar_width = 0;

	public JPanel getCanvasPanel() {
		return canvas_panel;
	}

	public DisplayCanvas getCanvas() {
		return canvas;
	}

	public synchronized void setLayer(final Layer layer) {
		if (!mode.canChangeLayer()) return;
		if (null == layer || layer == this.layer) return;
		translateLayerColors(this.layer, layer);
		if (tabs.getSelectedComponent() == scroll_layers) {
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				scrollToShow(scroll_layers, layer_panels.get(layer));
			}});
		}
		final boolean set_zdispl = null == Display.this.layer || layer.getParent() != Display.this.layer.getParent();
		if (canvas.isTransforming()) {
			Utils.log("Can't browse layers while transforming.\nCANCEL the transform first with the ESCAPE key or right-click -> cancel.");
			scroller.setValue(Display.this.layer.getParent().getLayerIndex(Display.this.layer.getId()));
			return;
		}
		this.layer = layer;
		scroller.setValue(layer.getParent().getLayerIndex(layer.getId()));

		// update the current Layer pointer in ZDisplayable objects
		for (Iterator it = layer.getParent().getZDisplayables().iterator(); it.hasNext(); ) {
			((ZDisplayable)it.next()).setLayer(layer); // the active layer
		}

		updateVisibleTab(set_zdispl);

		// see if a lot has to be reloaded, put the relevant ones at the end
		project.getLoader().prepare(layer);
		updateFrameTitle(layer); // to show the new 'z'
		// select the Layer in the LayerTree
		project.select(Display.this.layer); // does so in a separate thread
		// update active Displayable:

		// deselect all except ZDisplayables
		final ArrayList<Displayable> sel = selection.getSelected();
		final Displayable last_active = Display.this.active;
		int sel_next = -1;
		for (final Iterator<Displayable> it = sel.iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (!(d instanceof ZDisplayable)) {
				it.remove();
				selection.remove(d);
				if (d == last_active && sel.size() > 0) {
					// select the last one of the remaining, if any
					sel_next = sel.size()-1;
				}
			}
		}
		if (-1 != sel_next && sel.size() > 0) select(sel.get(sel_next), true);

		// repaint everything
		navigator.repaint(true);
		canvas.repaint(true);

		// repaint tabs (hard as hell)
		Utils.updateComponent(tabs);
		// @#$%^! The above works half the times, so explicit repaint as well:
		Component c = tabs.getSelectedComponent();
		if (null == c) {
			c = scroll_patches;
			tabs.setSelectedComponent(scroll_patches);
		}
		Utils.updateComponent(c);

		project.getLoader().setMassiveMode(false); // resetting if it was set true

		// update the coloring in the ProjectTree
		project.getProjectTree().updateUILater();
		
		setTempCurrentImage();
	}

	static public void updateVisibleTabs() {
		for (final Display d : al_displays) {
			d.updateVisibleTab(true);
		}
	}

	/** Recreate the tab that is being shown. */
	public void updateVisibleTab(boolean set_zdispl) {
		// update only the visible tab
		switch (tabs.getSelectedIndex()) {
			case 0:
				ht_panels.clear();
				updateTab(panel_patches, "Patches", layer.getDisplayables(Patch.class));
				break;
			case 1:
				ht_panels.clear();
				updateTab(panel_profiles, "Profiles", layer.getDisplayables(Profile.class));
				break;
			case 2:
				if (set_zdispl) {
					ht_panels.clear();
					updateTab(panel_zdispl, "Z-space objects", layer.getParent().getZDisplayables());
				}
				break;
			// case 3: channel opacities
			case 4:
				ht_panels.clear();
				updateTab(panel_labels, "Labels", layer.getDisplayables(DLabel.class));
				break;
			// case 5: layer panels
		}

	}

	private void setLayerLater(final Layer layer, final Displayable active) {
		if (null == layer) return;
		this.layer = layer;
		if (!ControlWindow.isGUIEnabled()) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
		// empty the tabs, except channels and pipes
		clearTab(panel_profiles);
		clearTab(panel_patches);
		clearTab(panel_labels);
		// distribute Displayable to the tabs. Ignore LayerSet instances.
		if (null == ht_panels) ht_panels = new Hashtable<Displayable,DisplayablePanel>();
		else ht_panels.clear();
		for (final Displayable d : layer.getParent().getZDisplayables()) {
			d.setLayer(layer);
		}
		updateTab(panel_patches, "Patches", layer.getDisplayables(Patch.class));
		navigator.repaint(true); // was not done when adding
		Utils.updateComponent(tabs.getSelectedComponent());
		//
		setActive(active);
		}});
		// swing issues:
		/*
		new Thread() {
			public void run() {
				setPriority(Thread.NORM_PRIORITY);
				try { Thread.sleep(1000); } catch (Exception e) {}
				setActive(active);
			}
		}.start();
		*/
	}

	/** Remove all components from the tab. */
	private void clearTab(final Container c) {
		c.removeAll();
		// magic cocktail:
		if (tabs.getSelectedComponent() == c) {
			Utils.updateComponent(c);
		}
	}

	/** A class to listen to the transparency_slider of the DisplayablesSelectorWindow. */
	private class TransparencySliderListener extends MouseAdapter implements ChangeListener {

		public void stateChanged(ChangeEvent ce) {
			//change the transparency value of the current active displayable
			float new_value = (float)((JSlider)ce.getSource()).getValue();
			setTransparency(new_value / 100.0f);
		}

		public void mousePressed(MouseEvent me) {
			JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
			if (scroll != scroll_channels && !selection.isEmpty()) selection.addDataEditStep(new String[]{"alpha"});
		}

		public void mouseReleased(MouseEvent me) {
			// update navigator window
			navigator.repaint(true);
			JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
			if (scroll != scroll_channels && !selection.isEmpty()) selection.addDataEditStep(new String[]{"alpha"});
		}
	}

	/** Context-sensitive: to a Displayable, or to a channel. */
	private void setTransparency(final float value) {
		JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (scroll == scroll_channels) {
			for (int i=0; i<4; i++) {
				if (channels[i].getBackground() == Color.cyan) {
					channels[i].setAlpha(value); // will call back and repaint the Display
					return;
				}
			}
		} else if (null != active) {
			if (value != active.getAlpha()) { // because there's a callback from setActive that would then affect all other selected Displayable without having dragged the slider, i.e. just by being selected.
				canvas.invalidateVolatile();
				selection.setAlpha(value);
			}
		}
	}

	public void setTransparencySlider(final float transp) {
		if (transp >= 0.0f && transp <= 1.0f) {
			// fire event
			transp_slider.setValue((int)(transp * 100));
		}
	}

	/** Mark the canvas for updating the offscreen images if the given Displayable is NOT the active. */ // Used by the Displayable.setVisible for example.
	static public void setUpdateGraphics(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (layer == d.layer && null != d.active && d.active != displ) {
				d.canvas.setUpdateGraphics(true);
			}
		}
	}

	/** Flag the DisplayCanvas of Displays showing the given Layer to update their offscreen images.*/
	static public void setUpdateGraphics(final Layer layer, final boolean update) {
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.canvas.setUpdateGraphics(update);
			}
		}
	}

	/** Whether to update the offscreen images or not. */
	public void setUpdateGraphics(boolean b) {
		canvas.setUpdateGraphics(b);
	}

	/** Update the entire GUI:
	 *   1 - The layer scroller
	 *   2 - The visible tab panels
	 *   3 - The toolbar
	 *   4 - The navigator
	 *   5 - The canvas
	 */
	static public void update() {
		for (final Display d : al_displays) {
			d.updateLayerScroller(d.layer);
			d.updateVisibleTab(true);
			d.toolbar_panel.repaint();
			d.navigator.repaint(true);
			d.canvas.repaint(true);
		}
	}

	/** Find all Display instances that contain the layer and repaint them, in the Swing GUI thread. */
	static public void update(final Layer layer) {
		if (null == layer) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			for (final Display d : al_displays) {
				if (d.isShowing(layer)) {
					d.repaintAll();
				}
			}
		}});
	}

	static public void update(final LayerSet set) {
		update(set, true);
	}

	/** Find all Display instances showing a Layer of this LayerSet, and update the dimensions of the navigator and canvas and snapshots, and repaint, in the Swing GUI thread. */
	static public void update(final LayerSet set, final boolean update_canvas_dimensions) {
		if (null == set) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			for (final Display d : al_displays) {
				if (set.contains(d.layer)) {
					d.updateSnapshots();
					if (update_canvas_dimensions) d.canvas.setDimensions(set.getLayerWidth(), set.getLayerHeight());
					d.repaintAll();
				}
			}
		}});
	}

	/** Release all resources held by this Display and close the frame. */
	protected void destroy() {
		dispatcher.quit();
		canvas.setReceivesInput(false);
		slt.quit();

		// update the coloring in the ProjectTree and LayerTree
		if (!project.isBeingDestroyed()) {
			try {
				project.getProjectTree().updateUILater();
				project.getLayerTree().updateUILater();
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
		bytypelistener = null;
		canvas.destroy();
		navigator.destroy();
		scroller.removeAdjustmentListener(scroller_listener);
		frame.setVisible(false);
		//no need, and throws exception//frame.dispose();
		active = null;
		if (null != selection) selection.clear();
		//Utils.log2("destroying selection");

		// below, need for SetLayerThread threads to quit
		slt.quit();
		// set a new front if any
		if (null == front && al_displays.size() > 0) {
			front = (Display)al_displays.get(al_displays.size() -1);
		}
		// repaint layer tree (to update the label color)
		try {
			project.getLayerTree().updateUILater(); // works only after setting the front above
		} catch (Exception e) {} // ignore swing sync bullshit when closing everything too fast
		// remove the drag and drop listener
		dnd.destroy();
	}

	/** Find all Display instances that contain a Layer of the given project and close them without removing the Display entries from the database. */
	static synchronized public void close(final Project project) {
		/* // concurrent modifications if more than 1 Display are being removed asynchronously
		for (final Display d : al_displays) {
			if (d.getLayer().getProject().equals(project)) {
				it.remove();
				d.destroy();
			}
		}
		*/
		Display[] d = new Display[al_displays.size()];
		al_displays.toArray(d);
		for (int i=0; i<d.length; i++) {
			if (d[i].getProject() == project) {
				al_displays.remove(d[i]);
				d[i].destroy();
			}
		}
	}

	/** Find all Display instances that contain the layer and close them and remove the Display from the database. */
	static public void close(final Layer layer) {
		for (Iterator it = al_displays.iterator(); it.hasNext(); ) {
			Display d = (Display)it.next();
			if (d.isShowing(layer)) {
				d.remove(false);
				it.remove();
			}
		}
	}

	/** Find all Display instances that are showing the layer and either move to the next or previous layer, or close it if none. */
	static public void remove(final Layer layer) {
		for (Iterator<Display> it = al_displays.iterator(); it.hasNext(); ) {
			final Display d = it.next();
			if (d.isShowing(layer)) {
				Layer la = layer.getParent().next(layer);
				if (layer == la || null == la) la = layer.getParent().previous(layer);
				if (null == la || layer == la) {
					d.remove(false);
					it.remove();
				} else {
					d.slt.set(la);
				}
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

	public LayerSet getLayerSet() {
		return layer.getParent();
	}

	public boolean isShowing(final Layer layer) {
		return this.layer == layer;
	}

	public DisplayNavigator getNavigator() {
		return navigator;
	}

	/** Repaint both the canvas and the navigator, updating the graphics, and the title and tabs. */
	public void repaintAll() {
		if (repaint_disabled) return;
		navigator.repaint(true);
		canvas.repaint(true);
		Utils.updateComponent(tabs);
		updateFrameTitle();
	}

	/** Repaint the canvas updating graphics, the navigator without updating graphics, and the title. */
	public void repaintAll2() {
		if (repaint_disabled) return;
		navigator.repaint(false);
		canvas.repaint(true);
		updateFrameTitle();
	}

	static public void repaintSnapshots(final LayerSet set) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (d.getLayer().getParent() == set) {
				d.navigator.repaint(true);
				Utils.updateComponent(d.tabs);
			}
		}
	}
	static public void repaintSnapshots(final Layer layer) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (d.getLayer() == layer) {
				d.navigator.repaint(true);
				Utils.updateComponent(d.tabs);
			}
		}
	}

	public void pack() {
		dispatcher.exec(new Runnable() { public void run() {
		try {
			Thread.currentThread().sleep(100);
			SwingUtilities.invokeAndWait(new Runnable() { public void run() {
				frame.pack();
			}});
		} catch (Exception e) { IJError.print(e); }
		}});
	}

	static public void pack(final LayerSet ls) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == ls) d.pack();
		}
	}

	private void adjustCanvas() {
		SwingUtilities.invokeLater(new Runnable() { public void run() {
		Rectangle r = split.getRightComponent().getBounds();
		canvas.setDrawingSize(r.width, r.height, true);
		// fix not-on-top-left problem
		canvas.setLocation(0, 0);
		//frame.pack(); // don't! Would go into an infinite loop
		canvas.repaint(true);
		updateInDatabase("srcRect");
		}});
	}

	/** Grab the last selected display (or create an new one if none) and show in it the layer,centered on the Displayable object. */
	static public void setFront(final Layer layer, final Displayable displ) {
		if (null == front) {
			Display display = new Display(layer.getProject(), layer); // gets set to front
			display.showCentered(displ);
		} else if (layer == front.layer) {
			front.showCentered(displ);
		} else {
			// find one:
			for (final Display d : al_displays) {
				if (d.layer == layer) {
					d.frame.toFront();
					d.showCentered(displ);
					return;
				}
			}
			// else, open new one
			new Display(layer.getProject(), layer).showCentered(displ);
		}
	}

	/** Find the displays that show the given Layer, and add the given Displayable to the GUI and sets it active only in the front Display and only if 'activate' is true. */
	static public void add(final Layer layer, final Displayable displ, final boolean activate) {
		for (final Display d : al_displays) {
			if (d.layer == layer) {
				if (front == d) {
					d.add(displ, activate, true);
					//front.frame.toFront();
				} else {
					d.add(displ, false, true);
				}
			}
		}
	}

	static public void add(final Layer layer, final Displayable displ) {
		add(layer, displ, true);
	}

	/** Add the ZDisplayable to all Displays that show a Layer belonging to the given LayerSet. */
	static public void add(final LayerSet set, final ZDisplayable zdispl) {
		for (final Display d : al_displays) {
			if (set.contains(d.layer)) {
				if (front == d) {
					zdispl.setLayer(d.layer); // the active one
					d.add(zdispl, true, true); // calling add(Displayable, boolean, boolean)
					//front.frame.toFront();
				} else {
					d.add(zdispl, false, true);
				}
			}
		}
	}

	static public void addAll(final Layer layer, final Collection<? extends Displayable> coll) {
		for (final Display d : al_displays) {
			if (d.layer == layer) {
				d.addAll(coll);
			}
		}
	}

	static public void addAll(final LayerSet set, final Collection<? extends ZDisplayable> coll) {
		for (final Display d : al_displays) {
			if (set.contains(d.layer)) {
				for (final ZDisplayable zd : coll) {
					if (front == d) zd.setLayer(d.layer);
				}
				d.addAll(coll);
			}
		}
	}

	private final void addAll(final Collection<? extends Displayable> coll) {
		// if any of the elements in the collection matches the type of the current tab, update that tab
		// ... it's easier to just update the front tab
		updateVisibleTab(true);
		selection.clear();
		navigator.repaint(true);
	}

	/** Add it to the proper panel, at the top, and set it active. */
	private final void add(final Displayable d, final boolean activate, final boolean repaint_snapshot) {
		if (activate) {
			DisplayablePanel dp = ht_panels.get(d);
			if (null != dp) dp.setActive(true);
			else updateVisibleTab(d instanceof ZDisplayable);
			selection.clear();
			selection.add(d);
			Display.repaint(d.getLayerSet()); // update the al_top list to contain the active one, or background image for a new Patch.
			Utils.log2("Added " + d);
		}
		if (repaint_snapshot) navigator.repaint(true);
	}

	private void addToPanel(JPanel panel, int index, DisplayablePanel dp, boolean repaint) {
		// remove the label
		if (1 == panel.getComponentCount() && panel.getComponent(0) instanceof JLabel) {
			panel.removeAll();
		}
		panel.add(dp, index);
		if (repaint) {
			Utils.updateComponent(tabs);
		}
	}

	/** Find the displays that show the given Layer, and remove the given Displayable from the GUI. */
	static public void remove(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (layer == d.layer) d.remove(displ);
		}
	}

	private void remove(final Displayable displ) {
		DisplayablePanel ob = ht_panels.remove(displ);
		if (null != ob) {
			final JScrollPane jsp = ht_tabs.get(displ.getClass());
			if (null != jsp) {
				JPanel p = (JPanel)jsp.getViewport().getView();
				p.remove((Component)ob);
				Utils.revalidateComponent(p);
			}
		}
		if (null == active || !selection.contains(displ)) {
			canvas.setUpdateGraphics(true);
		}
		canvas.invalidateVolatile(); // removing active, no need to update offscreen but yes the volatile
		repaint(displ, null, 5, true, false);
		// from Selection.deleteAll this method is called ... but it's ok: same thread, no locking problems.
		selection.remove(displ);
	}

	static public void remove(final ZDisplayable zdispl) {
		for (final Display d : al_displays) {
			if (zdispl.getLayerSet() == d.layer.getParent()) {
				d.remove((Displayable)zdispl);
			}
		}
	}

	static public void repaint(final Layer layer, final Displayable displ, final int extra) {
		repaint(layer, displ, displ.getBoundingBox(), extra);
	}

	static public void repaint(final Layer layer, final Displayable displ, final Rectangle r, final int extra) {
		repaint(layer, displ, r, extra, true);
	}

	/** Find the displays that show the given Layer, and repaint the given Displayable. */
	static public void repaint(final Layer layer, final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.repaint(displ, r, extra, repaint_navigator, false);
			}
		}
	}

	static public void repaint(final Displayable d) {
		if (d instanceof ZDisplayable) repaint(d.getLayerSet(), d, d.getBoundingBox(null), 5, true);
		repaint(d.getLayer(), d, d.getBoundingBox(null), 5, true);
	}

	/** Repaint as much as the bounding box around the given Displayable, or the r if not null. */
	private void repaint(final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator, final boolean update_graphics) {
		if (repaint_disabled || null == displ) return;
		if (update_graphics || displ.getClass() == Patch.class || displ != active) {
			canvas.setUpdateGraphics(true);
		}
		if (null != r) canvas.repaint(r, extra);
		else canvas.repaint(displ, extra);
		if (repaint_navigator) {
			DisplayablePanel dp = ht_panels.get(displ);
			if (null != dp) dp.repaint(); // is null when creating it, or after deleting it
			navigator.repaint(true); // everything
		}
	}

	/** Repaint the snapshot for the given Displayable both at the DisplayNavigator and on its panel,and only if it has not been painted before. This method is intended for the loader to know when to paint a snap, to avoid overhead. */
	static public void repaintSnapshot(final Displayable displ) {
		for (final Display d : al_displays) {
			if (d.layer.contains(displ)) {
				if (!d.navigator.isPainted(displ)) {
					DisplayablePanel dp = d.ht_panels.get(displ);
					if (null != dp) dp.repaint(); // is null when creating it, or after deleting it
					d.navigator.repaint(displ);
				}
			}
		}
	}

	/** Repaint the given Rectangle in all Displays showing the layer, updating the offscreen image if any. */
	static public void repaint(final Layer layer, final Rectangle r, final int extra) {
		repaint(layer, extra, r, true, true);
	}

	static public void repaint(final Layer layer, final int extra, final Rectangle r, final boolean update_navigator) {
		repaint(layer, extra, r, update_navigator, true);
	}

	static public void repaint(final Layer layer, final int extra, final Rectangle r, final boolean update_navigator, final boolean update_graphics) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.canvas.setUpdateGraphics(update_graphics);
				d.canvas.repaint(r, extra);
				if (update_navigator) {
					d.navigator.repaint(true);
					Utils.updateComponent(d.tabs.getSelectedComponent());
				}
			}
		}
	}


	/** Repaint the given Rectangle in all Displays showing the layer, optionally updating the offscreen image (if any). */
	static public void repaint(final Layer layer, final Rectangle r, final int extra, final boolean update_graphics) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.canvas.setUpdateGraphics(update_graphics);
				d.canvas.repaint(r, extra);
				d.navigator.repaint(update_graphics);
				if (update_graphics) Utils.updateComponent(d.tabs.getSelectedComponent());
			}
		}
	}

	/** Repaint the DisplayablePanel (and DisplayNavigator) only for the given Displayable, in all Displays showing the given Layer. */
	static public void repaint(final Layer layer, final Displayable displ) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.repaint();
				d.navigator.repaint(true);
			}
		}
	}

	static public void repaint(LayerSet set, Displayable displ, int extra) {
		repaint(set, displ, null, extra);
	}

	static public void repaint(LayerSet set, Displayable displ, Rectangle r, int extra) {
		repaint(set, displ, r, extra, true);
	}

	/** Repaint the Displayable in every Display that shows a Layer belonging to the given LayerSet. */
	static public void repaint(final LayerSet set, final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (set.contains(d.layer)) {
				if (repaint_navigator) {
					if (null != displ) {
						DisplayablePanel dp = d.ht_panels.get(displ);
						if (null != dp) dp.repaint();
					}
					d.navigator.repaint(true);
				}
				if (null == displ || displ != d.active) d.setUpdateGraphics(true); // safeguard
				// paint the given box or the actual Displayable's box
				if (null != r) d.canvas.repaint(r, extra);
				else d.canvas.repaint(displ, extra);
			}
		}
	}

	/** Repaint the entire LayerSet, in all Displays showing a Layer of it.*/
	static public void repaint(final LayerSet set) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (set.contains(d.layer)) {
				d.navigator.repaint(true);
				d.canvas.repaint(true);
			}
		}
	}
	/** Repaint the given box in the LayerSet, in all Displays showing a Layer of it.*/
	static public void repaint(final LayerSet set, final Rectangle box) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (set.contains(d.layer)) {
				d.navigator.repaint(box);
				d.canvas.repaint(box, 0, true);
			}
		}
	}
	/** Repaint the entire Layer, in all Displays showing it, including the tabs.*/
	static public void repaint(final Layer layer) { // TODO this method overlaps with update(layer)
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.navigator.repaint(true);
				d.canvas.repaint(true);
			}
		}
	}

	/** Call repaint on all open Displays. */
	static public void repaint() {
		if (repaint_disabled) {
			Utils.logAll("Can't repaint -- repainting is disabled!");
			return;
		}
		for (final Display d : al_displays) {
			d.navigator.repaint(true);
			d.canvas.repaint(true);
		}
	}

	static private boolean repaint_disabled = false;

	/** Set a flag to enable/disable repainting of all Display instances. */
	static protected void setRepaint(boolean b) {
		repaint_disabled = !b;
	}

	public Rectangle getBounds() {
		return frame.getBounds();
	}

	public Point getLocation() {
		return frame.getLocation();
	}

	public JFrame getFrame() {
		return frame;
	}

	public void setLocation(Point p) {
		this.frame.setLocation(p);
	}

	public Displayable getActive() {
		return active; //TODO this should return selection.active !!
	}

	public void select(Displayable d) {
		select(d, false);
	}

	/** Select/deselect accordingly to the current state and the shift key. */
	public void select(final Displayable d, final boolean shift_down) {
		if (null != active && active != d && active.getClass() != Patch.class) {
			// active is being deselected, so link underlying patches
			active.linkPatches();
			// If now locked via links:
			if (active.isLocked()) Display.updateCheckboxes(active, DisplayablePanel.LOCK_STATE, true);
			// Update link icons:
			Display.updateCheckboxes(active.getLinkedGroup(null), DisplayablePanel.LINK_STATE);
		}
		if (null == d) {
			//Utils.log2("Display.select: clearing selection");
			canvas.setUpdateGraphics(true);
			selection.clear();
			return;
		}
		if (!shift_down) {
			//Utils.log2("Display.select: single selection");
			if (d != active) {
				selection.clear();
				selection.add(d);
			}
		} else if (selection.contains(d)) {
			if (active == d) {
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
		// update the image shown to ImageJ
		// NO longer necessary, always he same FakeImagePlus // setTempCurrentImage();
	}

	protected void choose(int screen_x_p, int screen_y_p, int x_p, int y_p, final Class c) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, c);
	}
	protected void choose(int screen_x_p, int screen_y_p, int x_p, int y_p) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, null);
	}

	/** Find a Displayable to add to the selection under the given point (which is in offscreen coords); will use a popup menu to give the user a range of Displayable objects to select from. */
	protected void choose(int screen_x_p, int screen_y_p, int x_p, int y_p, boolean shift_down, Class c) {
		//Utils.log("Display.choose: x,y " + x_p + "," + y_p);
		final ArrayList<Displayable> al = new ArrayList<Displayable>(layer.find(x_p, y_p, true));
		al.addAll(layer.getParent().findZDisplayables(layer, x_p, y_p, true)); // only visible ones
		if (al.isEmpty()) {
			Displayable act = this.active;
			selection.clear();
			canvas.setUpdateGraphics(true);
			//Utils.log("choose: set active to null");
			// fixing lack of repainting for unknown reasons, of the active one TODO this is a temporary solution
			if (null != act) Display.repaint(layer, act, 5);
		} else if (1 == al.size()) {
			Displayable d = (Displayable)al.get(0);
			if (null != c && d.getClass() != c) {
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
						if (ob.getClass() != c) it.remove();
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
				choose(screen_x_p, screen_y_p, al, shift_down, x_p, y_p);
			}
			//Utils.log("choose many: set active to " + active);
		}
	}

	private void choose(final int screen_x_p, final int screen_y_p, final Collection al, final boolean shift_down, final int x_p, final int y_p) {
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
				if (null == d) { Utils.log2("Display.choose: returning a null!"); }
				select(d, shift_down);
				pop.setVisible(false);

				// fix selection bug: never receives mouseReleased event when the popup shows
				getMode().mouseReleased(null, x_p, y_p, x_p, y_p, x_p, y_p);
			}
		}.start();
	}

	/** Used by the Selection exclusively. This method will change a lot in the near future, and may disappear in favor of getSelection().getActive(). All this method does is update GUI components related to the currently active and the newly active Displayable; called through SwingUtilities.invokeLater. */
	protected void setActive(final Displayable displ) {
		final Displayable prev_active = this.active;
		this.active = displ;
		SwingUtilities.invokeLater(new Runnable() { public void run() {

		if (null != displ && displ == prev_active) {
			// make sure the proper tab is selected.
			selectTab(displ);
			return; // the same
		}
		// deactivate previously active
		if (null != prev_active) {
			final DisplayablePanel ob = ht_panels.get(prev_active);
			if (null != ob) ob.setActive(false);
			// erase "decorations" of the previously active
			canvas.repaint(selection.getBox(), 4);
		}
		// activate the new active
		if (null != displ) {
			final DisplayablePanel ob = ht_panels.get(displ);
			if (null != ob) ob.setActive(true);
			updateInDatabase("active_displayable_id");
			if (displ.getClass() != Patch.class) project.select(displ); // select the node in the corresponding tree, if any.
			// select the proper tab, and scroll to visible
			selectTab(displ);
			boolean update_graphics = null == prev_active || paintsBelow(prev_active, displ); // or if it's an image, but that's by default in the repaint method
			repaint(displ, null, 5, false, update_graphics); // to show the border, and to repaint out of the background image
			transp_slider.setValue((int)(displ.getAlpha() * 100));
		} else {
			//ensure decorations are removed from the panels, for Displayables in a selection besides the active one
			Utils.updateComponent(tabs.getSelectedComponent());
		}
		}});
	}

	/** If the other paints under the base. */
	public boolean paintsBelow(Displayable base, Displayable other) {
		boolean zd_base = base instanceof ZDisplayable;
		boolean zd_other = other instanceof ZDisplayable;
		if (zd_other) {
			if (base instanceof DLabel) return true; // zd paints under label
			if (!zd_base) return false; // any zd paints over a mere displ if not a label
			else {
				// both zd, compare indices
				ArrayList<ZDisplayable> al = other.getLayerSet().getZDisplayables();
				return al.indexOf(base) > al.indexOf(other);
			}
		} else {
			if (!zd_base) {
				// both displ, compare indices
				ArrayList<Displayable> al = other.getLayer().getDisplayables();
				return al.indexOf(base) > al.indexOf(other);
			} else {
				// base is zd, other is d
				if (other instanceof DLabel) return false;
				return true;
			}
		}
	}

	/** Select the proper tab, and also scroll it to show the given Displayable -unless it's a LayerSet, and unless the proper tab is already showing. */
	private void selectTab(final Displayable displ) {
		Method method = null;
		try {
			if (!(displ instanceof LayerSet)) {
				method = Display.class.getDeclaredMethod("selectTab", new Class[]{displ.getClass()});
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		if (null != method) {
			final Method me = method;
			dispatcher.exec(new Runnable() { public void run() {
				try {
					me.setAccessible(true);
					me.invoke(Display.this, new Object[]{displ});
				} catch (Exception e) { IJError.print(e); }
			}});
		}
	}

	private void selectTab(Patch patch) {
		tabs.setSelectedComponent(scroll_patches);
		scrollToShow(scroll_patches, ht_panels.get(patch));
	}

	private void selectTab(Profile profile) {
		tabs.setSelectedComponent(scroll_profiles);
		scrollToShow(scroll_profiles, ht_panels.get(profile));
	}

	private void selectTab(DLabel label) {
		tabs.setSelectedComponent(scroll_labels);
		scrollToShow(scroll_labels, ht_panels.get(label));
	}

	private void selectTab(ZDisplayable zd) {
		tabs.setSelectedComponent(scroll_zdispl);
		scrollToShow(scroll_zdispl, ht_panels.get(zd));
	}

	private void selectTab(Pipe d) { selectTab((ZDisplayable)d); }
	private void selectTab(Polyline d) { selectTab((ZDisplayable)d); }
	private void selectTab(AreaList d) { selectTab((ZDisplayable)d); } 
	private void selectTab(Ball d) { selectTab((ZDisplayable)d); }
	private void selectTab(Dissector d) { selectTab((ZDisplayable)d); }

	/** A method to update the given tab, creating a new DisplayablePanel for each Displayable present in the given ArrayList, and storing it in the ht_panels (which is cleared first). */
	private void updateTab(final JPanel tab, final String label, final ArrayList al) {
		dispatcher.exec(new Runnable() { public void run() {
			try {
			if (0 == al.size()) {
				tab.removeAll();
			} else {
				Component[] comp = tab.getComponents();
				int next = 0;
				if (1 == comp.length && comp[0].getClass() == JLabel.class) {
					next = 1;
					tab.remove(0);
				}
				// In reverse order:
				for (ListIterator it = al.listIterator(al.size()); it.hasPrevious(); ) {
					Displayable d = (Displayable)it.previous();
					DisplayablePanel dp = null;
					if (next < comp.length) {
						dp = (DisplayablePanel)comp[next++]; // recycling panels
						dp.set(d);
					} else {
						dp = new DisplayablePanel(Display.this, d);
						tab.add(dp);
					}
					ht_panels.put(d, dp);
				}
				if (next < comp.length) {
					// remove from the end, to avoid potential repaints of other panels
					for (int i=comp.length-1; i>=next; i--) {
						tab.remove(i);
					}
				}
			}
			if (null != Display.this.active) scrollToShow(Display.this.active);
			} catch (Throwable e) { IJError.print(e); }
		}});
		dispatcher.execSwing(new Runnable() { public void run() {
			Component c = tabs.getSelectedComponent();
			c.invalidate();
			c.validate();
			c.repaint();
		}});
	}

	static public void setActive(final Object event, final Displayable displ) {
		if (!(event instanceof InputEvent)) return;
		// find which Display
		for (final Display d : al_displays) {
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
	static public boolean isTransforming(final Displayable displ) {
		for (final Display d : al_displays) {
			if (null != d.active && d.active == displ && d.canvas.isTransforming()) return true;
		}
		return false;
	}

	static public boolean isAligning(final LayerSet set) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set && set.isAligning()) {
				return true;
			}
		}
		return false;
	}

	/** Check whether the source of the event is located in this instance.*/
	private boolean isOrigin(InputEvent event) {
		Object source = event.getSource();
		// find it ... check the canvas for now TODO
		if (canvas == source) {
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
	static public Layer getFrontLayer(final Project project) {
		if (null == front) return null;
		if (front.project == project) return front.layer;
		// else, find an open Display for the given Project, if any
		for (final Display d : al_displays) {
			if (d.project == project) {
				d.frame.toFront();
				return d.layer;
			}
		}
		return null; // none found
	}

	static public Display getFront(final Project project) {
		if (null == front) return null;
		if (front.project == project) return front;
		for (final Display d : al_displays) {
			if (d.project == project) {
				d.frame.toFront();
				return d;
			}
		}
		return null;
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
			if (selection.isEmpty() || ! (getActive().getClass() == Patch.class && ((Patch)getActive()).isStack())) item.setEnabled(false);
			item = new JMenuItem("Align layers"); item.addActionListener(this); popup.add(item);
			if (1 == layer.getParent().size()) item.setEnabled(false);
			item = new JMenuItem("Align multi-layer mosaic"); item.addActionListener(this); popup.add(item);
			if (1 == layer.getParent().size()) item.setEnabled(false);
			return popup;
		}

		if (canvas.isTransforming()) {
			item = new JMenuItem("Apply transform"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true)); // dummy, for I don't add a MenuKeyListener, but "works" through the normal key listener. It's here to provide a visual cue
			item = new JMenuItem("Apply transform propagating to last layer"); item.addActionListener(this); popup.add(item);
			if (layer.getParent().indexOf(layer) == layer.getParent().size() -1) item.setEnabled(false);
			if (getMode().getClass() != AffineTransformMode.class) item.setEnabled(false);
			item = new JMenuItem("Apply transform propagating to first layer"); item.addActionListener(this); popup.add(item);
			if (0 == layer.getParent().indexOf(layer)) item.setEnabled(false);
			if (getMode().getClass() != AffineTransformMode.class) item.setEnabled(false);
			item = new JMenuItem("Cancel transform"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			item = new JMenuItem("Specify transform..."); item.addActionListener(this); popup.add(item);
			if (getMode().getClass() != AffineTransformMode.class) item.setEnabled(false);
			return popup;
		}

		if (null != active) {
			if (active instanceof Profile) {
				item = new JMenuItem("Duplicate, link and send to next layer"); item.addActionListener(this); popup.add(item);
				Layer nl = layer.getParent().next(layer);
				if (nl == layer) item.setEnabled(false);
				item = new JMenuItem("Duplicate, link and send to previous layer"); item.addActionListener(this); popup.add(item);
				nl = layer.getParent().previous(layer);
				if (nl == layer) item.setEnabled(false);

				menu = new JMenu("Duplicate, link and send to");
				ArrayList al = layer.getParent().getLayers();
				final Iterator it = al.iterator();
				int i = 1;
				while (it.hasNext()) {
					Layer la = (Layer)it.next();
					item = new JMenuItem(i + ": z = " + la.getZ()); item.addActionListener(this); menu.add(item); // TODO should label which layers contain Profile instances linked to the one being duplicated
					if (la == this.layer) item.setEnabled(false);
					i++;
				}
				popup.add(menu);
				item = new JMenuItem("Duplicate, link and send to..."); item.addActionListener(this); popup.add(item);

				popup.addSeparator();

				item = new JMenuItem("Unlink from images"); item.addActionListener(this); popup.add(item);
				if (!active.isLinked()) item.setEnabled(false); // isLinked() checks if it's linked to a Patch in its own layer
				item = new JMenuItem("Show in 3D"); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			} else if (active instanceof Patch) {
				item = new JMenuItem("Unlink from images"); item.addActionListener(this); popup.add(item);
				if (!active.isLinked(Patch.class)) item.setEnabled(false);
				if (((Patch)active).isStack()) {
					item = new JMenuItem("Unlink slices"); item.addActionListener(this); popup.add(item);
				}
				int n_sel_patches = selection.getSelected(Patch.class).size(); 
				if (1 == n_sel_patches) {
					item = new JMenuItem("Snap"); item.addActionListener(this); popup.add(item);
				} else if (n_sel_patches > 1) {
					item = new JMenuItem("Montage"); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("Lens correction"); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("Blend"); item.addActionListener(this); popup.add(item);
				}
				item = new JMenuItem("Remove alpha mask"); item.addActionListener(this); popup.add(item);
				if ( ! ((Patch)active).hasAlphaMask()) item.setEnabled(false);
				item = new JMenuItem("View volume"); item.addActionListener(this); popup.add(item);
				HashSet hs = active.getLinked(Patch.class);
				if (null == hs || 0 == hs.size()) item.setEnabled(false);
				item = new JMenuItem("View orthoslices"); item.addActionListener(this); popup.add(item);
				if (null == hs || 0 == hs.size()) item.setEnabled(false); // if no Patch instances among the directly linked, then it's not a stack
				popup.addSeparator();
			} else {
				item = new JMenuItem("Unlink"); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Show in 3D"); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			}
			if (active instanceof AreaList) {
				item = new JMenuItem("Merge"); item.addActionListener(this); popup.add(item);
				ArrayList al = selection.getSelected();
				int n = 0;
				for (Iterator it = al.iterator(); it.hasNext(); ) {
					if (it.next().getClass() == AreaList.class) n++;
				}
				if (n < 2) item.setEnabled(false);
				popup.addSeparator();
			} else if (active instanceof Pipe) {
				item = new JMenuItem("Identify..."); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Identify with axes..."); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Identify with fiducials..."); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			}

			JMenuItem st = new JMenu("Transform");
			StartTransformMenuListener tml = new StartTransformMenuListener();
			item = new JMenuItem("Transform (affine)"); item.addActionListener(tml); st.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, 0, true));
			item = new JMenuItem("Transform (non-linear)"); item.addActionListener(tml); st.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, Event.SHIFT_MASK, true));
			item = new JMenuItem("Cancel transform"); st.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			item.setEnabled(false); // just added as a self-documenting cue; no listener
			popup.add(st);


			item = new JMenuItem("Duplicate"); item.addActionListener(this); popup.add(item);
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
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0, true)); // this is just to draw the key name by the menu; it does not incur on any event being generated (that I know if), and certainly not any event being listened to by TrakEM2.
			if (ls.isTop(active)) item.setEnabled(false);
			item = new JMenuItem("Move up"); item.addActionListener(this); menu.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0, true));
			if (ls.isTop(active)) item.setEnabled(false);
			item = new JMenuItem("Move down"); item.addActionListener(this); menu.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0, true));
			if (ls.isBottom(active)) item.setEnabled(false);
			item = new JMenuItem("Move to bottom"); item.addActionListener(this); menu.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0, true));
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
			} catch (Exception e) { IJError.print(e); item.setEnabled(false); }

			if (active instanceof Patch) {
				item = new JMenuItem("Revert"); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			}
			item = new JMenuItem("Properties...");    item.addActionListener(this); popup.add(item);
			item = new JMenuItem("Show centered"); item.addActionListener(this); popup.add(item);

			popup.addSeparator();

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
					for (final Layer la : ls.getLayers()) {
						String layer_title = i + ": " + la.getTitle();
						if (-1 == layer_title.indexOf(' ')) layer_title += " ";
						item = new JMenuItem(layer_title); item.addActionListener(this); menu.add(item);
						if (la == this.layer) item.setEnabled(false);
						i++;
					}
					popup.add(menu);
				} else {
					menu.setEnabled(false);
					//Utils.log("Active's linked group not within layer.");
				}
				popup.add(menu);
				popup.addSeparator();
			}
		}

		item = new JMenuItem("Undo");item.addActionListener(this); popup.add(item);
		if (!layer.getParent().canUndo() || canvas.isTransforming()) item.setEnabled(false);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Utils.getControlModifier(), true));
		item = new JMenuItem("Redo");item.addActionListener(this); popup.add(item);
		if (!layer.getParent().canRedo() || canvas.isTransforming()) item.setEnabled(false);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Event.SHIFT_MASK | Event.CTRL_MASK, true));
		popup.addSeparator();

		// Would get so much simpler with a clojure macro ...

		try {
			menu = new JMenu("Hide/Unhide");
			item = new JMenuItem("Hide deselected"); item.addActionListener(this); menu.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, Event.SHIFT_MASK, true));
			boolean none = 0 == selection.getNSelected();
			if (none) item.setEnabled(false);
			item = new JMenuItem("Hide deselected except images"); item.addActionListener(this); menu.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, Event.SHIFT_MASK | Event.ALT_MASK, true));
			if (none) item.setEnabled(false);
			item = new JMenuItem("Hide selected"); item.addActionListener(this); menu.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0, true));
			if (none) item.setEnabled(false);
			none = ! layer.getParent().containsDisplayable(DLabel.class);
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
			none = ! layer.getParent().contains(Polyline.class);
			item = new JMenuItem("Hide all polylines"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all polylines"); item.addActionListener(this); menu.add(item);
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
			item = new JMenuItem("Hide all but images"); item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Unhide all"); item.addActionListener(this); menu.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, Event.ALT_MASK, true));

			popup.add(menu);
		} catch (Exception e) { IJError.print(e); }

		JMenu align_menu = new JMenu("Align");
		item = new JMenuItem("Align stack slices"); item.addActionListener(this); align_menu.add(item);
		if (selection.isEmpty() || ! (getActive().getClass() == Patch.class && ((Patch)getActive()).isStack())) item.setEnabled(false);
		item = new JMenuItem("Align layers"); item.addActionListener(this); align_menu.add(item);
		if (1 == layer.getParent().size()) item.setEnabled(false);
		item = new JMenuItem("Align multi-layer mosaic"); item.addActionListener(this); align_menu.add(item);
		if (1 == layer.getParent().size()) item.setEnabled(false);
		popup.add(align_menu);

		JMenu link_menu = new JMenu("Link");
		item = new JMenuItem("Link images..."); item.addActionListener(this); link_menu.add(item);
		item = new JMenuItem("Unlink all selected images"); item.addActionListener(this); link_menu.add(item);
		item.setEnabled(selection.getSelected(Patch.class).size() > 0);
		item = new JMenuItem("Unlink all"); item.addActionListener(this); link_menu.add(item);
		popup.add(link_menu);

		JMenu adjust_menu = new JMenu("Adjust");
		item = new JMenuItem("Enhance contrast layer-wise..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Enhance contrast (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Set Min and Max layer-wise..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Set Min and Max (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		popup.add(adjust_menu);

		JMenu script = new JMenu("Script");
		MenuScriptListener msl = new MenuScriptListener();
		item = new JMenuItem("Set preprocessor script layer-wise..."); item.addActionListener(msl); script.add(item);
		item = new JMenuItem("Set preprocessor script (selected images)..."); item.addActionListener(msl); script.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Remove preprocessor script layer-wise..."); item.addActionListener(msl); script.add(item);
		item = new JMenuItem("Remove preprocessor script (selected images)..."); item.addActionListener(msl); script.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		popup.add(script);

		menu = new JMenu("Import");
		item = new JMenuItem("Import image"); item.addActionListener(this); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_I, Event.ALT_MASK & Event.SHIFT_MASK, true));
		item = new JMenuItem("Import stack..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import grid..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import sequence as grid..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import from text file..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import labels as arealists..."); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Export");
		item = new JMenuItem("Make flat image..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Arealists as labels (tif)"); item.addActionListener(this); menu.add(item);
		if (0 == layer.getParent().getZDisplayables(AreaList.class).size()) item.setEnabled(false);
		item = new JMenuItem("Arealists as labels (amira)"); item.addActionListener(this); menu.add(item);
		if (0 == layer.getParent().getZDisplayables(AreaList.class).size()) item.setEnabled(false);
		popup.add(menu);

		menu = new JMenu("Display");
		item = new JMenuItem("Resize canvas/LayerSet...");   item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Autoresize canvas/LayerSet");  item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Properties ..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Calibration..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Adjust snapping parameters..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Adjust fast-marching parameters..."); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Project");
		this.project.getLoader().setupMenuItems(menu, this.getProject());
		item = new JMenuItem("Project properties..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Create subproject"); item.addActionListener(this); menu.add(item);
		if (null == canvas.getFakeImagePlus().getRoi()) item.setEnabled(false);
		item = new JMenuItem("Release memory..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Flush image cache"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Regenerate all mipmaps"); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Selection");
		item = new JMenuItem("Select all"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Select all visible"); item.addActionListener(this); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Utils.getControlModifier(), true));
		if (0 == layer.getDisplayables().size() && 0 == layer.getParent().getZDisplayables().size()) item.setEnabled(false);
		item = new JMenuItem("Select none"); item.addActionListener(this); menu.add(item);
		if (0 == selection.getNSelected()) item.setEnabled(false);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));

		JMenu bytype = new JMenu("Select all by type");
		item = new JMenuItem("AreaList"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Ball"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Dissector"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Image"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Text"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Pipe"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Polyline"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Profile"); item.addActionListener(bytypelistener); bytype.add(item);
		menu.add(bytype);

		item = new JMenuItem("Restore selection"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Select under ROI"); item.addActionListener(this); menu.add(item);
		if (canvas.getFakeImagePlus().getRoi() == null) item.setEnabled(false);
		popup.add(menu);

		menu = new JMenu("Tool");
		item = new JMenuItem("Rectangular ROI"); item.addActionListener(new SetToolListener(Toolbar.RECTANGLE)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0, true));
		item = new JMenuItem("Polygon ROI"); item.addActionListener(new SetToolListener(Toolbar.POLYGON)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0, true));
		item = new JMenuItem("Freehand ROI"); item.addActionListener(new SetToolListener(Toolbar.FREEROI)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0, true));
		item = new JMenuItem("Text"); item.addActionListener(new SetToolListener(Toolbar.TEXT)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0, true));
		item = new JMenuItem("Magnifier glass"); item.addActionListener(new SetToolListener(Toolbar.MAGNIFIER)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0, true));
		item = new JMenuItem("Hand"); item.addActionListener(new SetToolListener(Toolbar.HAND)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0, true));
		item = new JMenuItem("Select"); item.addActionListener(new SetToolListener(ProjectToolbar.SELECT)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0, true));
		item = new JMenuItem("Pencil"); item.addActionListener(new SetToolListener(ProjectToolbar.PENCIL)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0, true));
		item = new JMenuItem("Pen"); item.addActionListener(new SetToolListener(ProjectToolbar.PEN)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0, true));
		item = new JMenuItem("Align"); item.addActionListener(new SetToolListener(ProjectToolbar.ALIGN)); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0, true));

		popup.add(menu);

		item = new JMenuItem("Search..."); item.addActionListener(this); popup.add(item);

		//canvas.add(popup);
		return popup;
	}

	private class StartTransformMenuListener implements ActionListener {
		public void actionPerformed(ActionEvent ae) {
			if (null == active) return;
			String command = ae.getActionCommand();
			if (command.equals("Transform (affine)")) {
				setMode(new AffineTransformMode(Display.this));
			} else if (command.equals("Transform (non-linear)")) {
				List<Displayable> col = selection.getSelected(Patch.class);
				for (final Displayable d : col) {
					if (d.isLinked()) {
						Utils.showMessage("Can't enter manual non-linear transformation mode:\nat least one image is linked.");
						return;
					}
				}
				setMode(new NonLinearTransformMode(Display.this, col));
			}
		}
	}

	private class MenuScriptListener implements ActionListener {
		public void actionPerformed(ActionEvent ae) {
			String command = ae.getActionCommand();
			if (command.equals("Set preprocessor script layer-wise...")) {
				Collection<Layer> ls = getLayerList("Set preprocessor script");
				if (null == ls) return;
				String path = getScriptPath();
				if (null == path) return;
				for (final Layer la : ls) setScriptPath(la.getDisplayables(Patch.class), path);
			} else if (command.equals("Set preprocessor script (selected images)...")) {
				if (selection.isEmpty()) return;
				String path = getScriptPath();
				if (null == path) return;
				setScriptPath(selection.getSelected(Patch.class), path);
			} else if (command.equals("Remove preprocessor script layer-wise...")) {
				Collection<Layer> ls = getLayerList("Remove preprocessor script");
				if (null == ls) return;
				for (final Layer la : ls) setScriptPath(la.getDisplayables(Patch.class), null);
			} else if (command.equals("Remove preprocessor script (selected images)...")) {
				if (selection.isEmpty()) return;
				setScriptPath(selection.getSelected(Patch.class), null);
			}
		}
		/** Accepts null script, to remove it if there. */
		private void setScriptPath(final Collection<Displayable> list, final String script) {
			for (final Displayable d : list) {
				Patch p = (Patch) d;
				p.setPreprocessorScriptPath(script);
			}
		}
		private Collection<Layer> getLayerList(String title) {
			final GenericDialog gd = new GenericDialog(title);
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return null;
			return layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1); // exclusive end
		}
		private String getScriptPath() {
			OpenDialog od = new OpenDialog("Select script", OpenDialog.getLastDirectory(), null);
			String dir = od.getDirectory();
			if (null == dir) return null;
			if (IJ.isWindows()) dir = dir.replace('\\','/');
			if (!dir.endsWith("/")) dir += "/";
			return dir + od.getFileName();
		}
	}

	private class SetToolListener implements ActionListener {
		final int tool;
		SetToolListener(int tool) {
			this.tool = tool;
		}
		public void actionPerformed(ActionEvent ae) {
			ProjectToolbar.setTool(tool);
			toolbar_panel.repaint();
		}
	}

	private ByTypeListener bytypelistener = new ByTypeListener(this);

	static private class ByTypeListener implements ActionListener {
		final Display d;
		ByTypeListener(final Display d) {
			this.d = d;
		}
		public void actionPerformed(final ActionEvent ae) {
			final String command = ae.getActionCommand();

			final Area aroi = M.getArea(d.canvas.getFakeImagePlus().getRoi());

			d.dispatcher.exec(new Runnable() { public void run() {

			try {
				String type = command;
				if (type.equals("Image")) type = "Patch";
				Class c = Class.forName("ini.trakem2.display." + type);

				java.util.List<Displayable> a = new ArrayList<Displayable>();
				if (null != aroi) {
					a.addAll(d.layer.getDisplayables(c, aroi, true));
					a.addAll(d.layer.getParent().getZDisplayables(c, d.layer, aroi, true));
				} else {
					a.addAll(d.layer.getDisplayables(c));
					a.addAll(d.layer.getParent().getZDisplayables(c));
					// Remove non-visible ones
					for (final Iterator<Displayable> it = a.iterator(); it.hasNext(); ) {
						if (!it.next().isVisible()) it.remove();
					}
				}

				if (0 == a.size()) return;

				boolean selected = false;

				if (0 == ae.getModifiers()) {
					Utils.log2("first");
					d.selection.clear();
					d.selection.selectAll(a);
					selected = true;
				} else if (0 == (ae.getModifiers() ^ Event.SHIFT_MASK)) {
					Utils.log2("with shift");
					d.selection.selectAll(a); // just add them to the current selection
					selected = true;
				}
				if (selected) {
					// Activate last:
					d.selection.setActive(a.get(a.size() -1));
				}

			} catch (ClassNotFoundException e) {
				Utils.log2(e.toString());
			}

			}});
		}
	}

	/** Check if a panel for the given Displayable is completely visible in the JScrollPane */
	public boolean isWithinViewport(final Displayable d) {
		final JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (ht_tabs.get(d.getClass()) == scroll) return isWithinViewport(scroll, ht_panels.get(d));
		return false;
	}

	private boolean isWithinViewport(JScrollPane scroll, DisplayablePanel dp) {
		if(null == dp) return false;
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
	public boolean isPartiallyWithinViewport(final Displayable d) {
		final JScrollPane scroll = ht_tabs.get(d.getClass());
		if (tabs.getSelectedComponent() == scroll) return isPartiallyWithinViewport(scroll, ht_panels.get(d));
		return false;
	}

	/** Check if a panel for the given Displayable is at least partially visible in the JScrollPane */
	private boolean isPartiallyWithinViewport(final JScrollPane scroll, final DisplayablePanel dp) {
		if(null == dp) {
			//Utils.log2("Display.isPartiallyWithinViewport: null DisplayablePanel ??");
			return false; // to fast for you baby
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
	private void scrollToShow(final Displayable d) {
		dispatcher.execSwing(new Runnable() { public void run() {
		final JScrollPane scroll = (JScrollPane)tabs.getSelectedComponent();
		if (d instanceof ZDisplayable && scroll == scroll_zdispl) {
			scrollToShow(scroll_zdispl, ht_panels.get(d));
			return;
		}
		final Class c = d.getClass();
		if (Patch.class == c && scroll == scroll_patches) {
			scrollToShow(scroll_patches, ht_panels.get(d));
		} else if (DLabel.class == c && scroll == scroll_labels) {
			scrollToShow(scroll_labels, ht_panels.get(d));
		} else if (Profile.class == c && scroll == scroll_profiles) {
			scrollToShow(scroll_profiles, ht_panels.get(d));
		}
		}});
	}

	private void scrollToShow(final JScrollPane scroll, final JPanel dp) {
		if (null == dp) return;
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
			}
			// if it's below (even if partially), show at the bottom
			else if (panel_y + 50 > current.y + extent.height) {
				view.setViewPosition(new Point(0, panel_y - extent.height + 50));
				//Utils.log("Display.scrollToShow: panel_y: " + panel_y + "   current.y: " + current.y + "  extent.height: " + extent.height);
			}
		}
	}

	/** Update the title of the given Displayable in its DisplayablePanel, if any. */
	static public void updateTitle(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.updateTitle();
			}
		}
	}

	/** Update the Display's title in all Displays showing the given Layer. */
	static public void updateTitle(final Layer layer) {
		for (final Display d : al_displays) {
			if (d.layer == layer) {
				d.updateFrameTitle();
			}
		}
	}
	/** Update the Display's title in all Displays showing a Layer of the given LayerSet. */
	static public void updateTitle(final LayerSet ls) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == ls) {
				d.updateFrameTitle();
			}
		}
	}

	/** Set a new title in the JFrame, showing info on the layer 'z' and the magnification. */
	public void updateFrameTitle() {
		updateFrameTitle(layer);
	}
	private void updateFrameTitle(Layer layer) {
		// From ij.ImagePlus class, the solution:
		String scale = "";
		final double magnification = canvas.getMagnification();
		if (magnification!=1.0) {
			final double percent = magnification*100.0;
			scale = new StringBuilder(" (").append(Utils.d2s(percent, percent==(int)percent ? 0 : 1)).append("%)").toString();
		}
		final Calibration cal = layer.getParent().getCalibration();
		String title = new StringBuilder().append(layer.getParent().indexOf(layer) + 1).append('/').append(layer.getParent().size()).append(' ').append((null == layer.getTitle() ? "" : layer.getTitle())).append(scale).append(" -- ").append(getProject().toString()).append(' ').append(' ').append(Utils.cutNumber(layer.getParent().getLayerWidth() * cal.pixelWidth, 2, true)).append('x').append(Utils.cutNumber(layer.getParent().getLayerHeight() * cal.pixelHeight, 2, true)).append(' ').append(cal.getUnit()).toString();
		frame.setTitle(title);
		// fix the title for the FakeImageWindow and thus the WindowManager listing in the menus
		canvas.getFakeImagePlus().setTitle(title);
	}

	/** If shift is down, scroll to the next non-empty layer; otherwise, if scroll_step is larger than 1, then scroll 'scroll_step' layers ahead; else just the next Layer. */
	public void nextLayer(final int modifiers) {
		final Layer l;
		if (0 == (modifiers ^ Event.SHIFT_MASK)) {
			l = layer.getParent().nextNonEmpty(layer);
		} else if (scroll_step > 1) {
			int i = layer.getParent().indexOf(this.layer);
			Layer la = layer.getParent().getLayer(i + scroll_step);
			if (null != la) l = la;
			else l = null;
		} else {
			l = layer.getParent().next(layer);
		}
		if (l != layer) {
			slt.set(l);
			updateInDatabase("layer_id");
		}
	}

	private final void translateLayerColors(final Layer current, final Layer other) {
		if (current == other) return;
		if (layer_channels.size() > 0) {
			final LayerSet ls = getLayerSet();
			// translate colors by distance from current layer to new Layer l
			final int dist = ls.indexOf(other) - ls.indexOf(current);
			translateLayerColor(Color.red, dist);
			translateLayerColor(Color.blue, dist);
		}
	}

	private final void translateLayerColor(final Color color, final int dist) {
		final LayerSet ls = getLayerSet();
		final Layer l = layer_channels.get(color);
		if (null == l) return;
		updateColor(Color.white, layer_panels.get(l));
		final Layer l2 = ls.getLayer(ls.indexOf(l) + dist);
		if (null != l2) updateColor(color, layer_panels.get(l2));
	}

	private final void updateColor(final Color color, final LayerPanel lp) {
		lp.setColor(color);
		setColorChannel(lp.layer, color);
	}

	/** Calls setLayer(la) on the SetLayerThread. */
	public void toLayer(final Layer la) {
		if (la.getParent() != layer.getParent()) return; // not of the same LayerSet
		if (la == layer) return; // nothing to do
		slt.set(la);
		updateInDatabase("layer_id");
	}

	/** If shift is down, scroll to the previous non-empty layer; otherwise, if scroll_step is larger than 1, then scroll 'scroll_step' layers backward; else just the previous Layer. */
	public void previousLayer(final int modifiers) {
		final Layer l;
		if (0 == (modifiers ^ Event.SHIFT_MASK)) {
			l = layer.getParent().previousNonEmpty(layer);
		} else if (scroll_step > 1) {
			int i = layer.getParent().indexOf(this.layer);
			Layer la = layer.getParent().getLayer(i - scroll_step);
			if (null != la) l = la;
			else l = null;
		} else {
			l = layer.getParent().previous(layer);
		}
		if (l != layer) {
			slt.set(l);
			updateInDatabase("layer_id");
		}
	}

	static public void updateLayerScroller(LayerSet set) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
				d.updateLayerScroller(d.layer);
			}
		}
	}

	private void updateLayerScroller(Layer layer) {
		int size = layer.getParent().size();
		if (size <= 1) {
			scroller.setValues(0, 1, 0, 0);
			scroller.setEnabled(false);
		} else {
			scroller.setEnabled(true);
			scroller.setValues(layer.getParent().getLayerIndex(layer.getId()), 1, 0, size);
		}
		recreateLayerPanels(layer);
	}

	// Can't use this.layer, may still be null. User argument instead.
	private synchronized void recreateLayerPanels(final Layer layer) {
		synchronized (layer_channels) {
			panel_layers.removeAll();

			if (0 == layer_panels.size()) {
				for (final Layer la : layer.getParent().getLayers()) {
					final LayerPanel lp = new LayerPanel(this, la);
					layer_panels.put(la, lp);
					this.panel_layers.add(lp);
				}
			} else {
				// Set theory at work: keep old to reuse
				layer_panels.keySet().retainAll(layer.getParent().getLayers());
				for (final Layer la : layer.getParent().getLayers()) {
					LayerPanel lp = layer_panels.get(la);
					if (null == lp) {
						lp = new LayerPanel(this, la);
						layer_panels.put(la, lp);
					}
					this.panel_layers.add(lp);
				}
				for (final Iterator<Map.Entry<Integer,LayerPanel>> it = layer_alpha.entrySet().iterator(); it.hasNext(); ) {
					final Map.Entry<Integer,LayerPanel> e = it.next();
					if (-1 == getLayerSet().indexOf(e.getValue().layer)) it.remove();
				}
				for (final Iterator<Map.Entry<Color,Layer>> it = layer_channels.entrySet().iterator(); it.hasNext(); ) {
					final Map.Entry<Color,Layer> e = it.next();
					if (-1 == getLayerSet().indexOf(e.getValue())) it.remove();
				}
				scroll_layers.repaint();
			}
		}
	}

	private void updateSnapshots() {
		Enumeration<DisplayablePanel> e = ht_panels.elements();
		while (e.hasMoreElements()) {
			e.nextElement().repaint();
		}
		Utils.updateComponent(tabs.getSelectedComponent());
	}

	static public void updatePanel(Layer layer, final Displayable displ) {
		if (null == layer && null != front) layer = front.layer; // the front layer
		for (final Display d : al_displays) {
			if (d.layer == layer) {
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
		DisplayablePanel dp = ht_panels.get(d);
		dp.repaint();
		Utils.updateComponent(c);
	}

	static public void updatePanelIndex(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (d.layer == layer || displ instanceof ZDisplayable) {
				d.updatePanelIndex(displ);
			}
		}
	}

	private void updatePanelIndex(final Displayable d) {
		updateTab( (JPanel) ht_tabs.get(d.getClass()).getViewport().getView(), "",
			  ZDisplayable.class.isAssignableFrom(d.getClass()) ?
			     layer.getParent().getZDisplayables()
			   : layer.getDisplayables(d.getClass()));
	}

	/** Repair possibly missing panels and other components by simply resetting the same Layer */
	public void repairGUI() {
		Layer layer = this.layer;
		this.layer = null;
		setLayer(layer);
	}

	public void actionPerformed(final ActionEvent ae) {
		dispatcher.exec(new Runnable() { public void run() {

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
			duplicateLinkAndSendTo(active, 1, layer.getParent().next(layer));
		} else if (command.equals("Duplicate, link and send to previous layer")) {
			duplicateLinkAndSendTo(active, 0, layer.getParent().previous(layer));
		} else if (command.equals("Duplicate, link and send to...")) {
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
			if (layer == la) {
				Utils.showMessage("Can't duplicate, link and send to the same layer.");
				return;
			}
			duplicateLinkAndSendTo(active, 0, la);
		} else if (-1 != command.indexOf("z = ")) {
			// this is an item from the "Duplicate, link and send to" menu of layer z's
			Layer target_layer = layer.getParent().getLayer(Double.parseDouble(command.substring(command.lastIndexOf(' ') +1)));
			Utils.log2("layer: __" +command.substring(command.lastIndexOf(' ') +1) + "__");
			if (null == target_layer) return;
			duplicateLinkAndSendTo(active, 0, target_layer);
		} else if (-1 != command.indexOf("z=")) {
			// WARNING the indexOf is very similar to the previous one
			// Send the linked group to the selected layer
			int iz = command.indexOf("z=")+2;
			Utils.log2("iz=" + iz + "  other: " + command.indexOf(' ', iz+2));
			int end = command.indexOf(' ', iz);
			if (-1 == end) end = command.length();
			double lz = Double.parseDouble(command.substring(iz, end));
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
				for (Displayable displ: selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (Exception e) { IJError.print(e); }
		} else if (command.equals("Unlink slices")) {
			YesNoCancelDialog yn = new YesNoCancelDialog(frame, "Attention", "Really unlink all slices from each other?\nThere is no undo.");
			if (!yn.yesPressed()) return;
			final ArrayList<Patch> pa = ((Patch)active).getStackPatches();
			for (int i=pa.size()-1; i>0; i--) {
				pa.get(i).unlink(pa.get(i-1));
			}
		} else if (command.equals("Send to next layer")) {
			Rectangle box = selection.getBox();
			try {
				// unlink Patch instances
				for (final Displayable displ : selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (Exception e) { IJError.print(e); }
			//layer.getParent().moveDown(layer, active); // will repaint whatever appropriate layers
			selection.moveDown();
			repaint(layer.getParent(), box);
		} else if (command.equals("Send to previous layer")) {
			Rectangle box = selection.getBox();
			try {
				// unlink Patch instances
				for (final Displayable displ : selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (Exception e) { IJError.print(e); }
			//layer.getParent().moveUp(layer, active); // will repaint whatever appropriate layers
			selection.moveUp();
			repaint(layer.getParent(), box);
		} else if (command.equals("Show centered")) {
			if (active == null) return;
			showCentered(active);
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
			if (null == active || active.getClass() != Patch.class) return;
			Patch p = (Patch)active;
			if (!p.revert()) {
				if (null == p.getOriginalPath()) Utils.log("No editions to save for patch " + p.getTitle() + " #" + p.getId());
				else Utils.log("Could not revert Patch " + p.getTitle() + " #" + p.getId());
			}
		} else if (command.equals("Remove alpha mask")) {
			final ArrayList<Displayable> patches = selection.getSelected(Patch.class); 
			if (patches.size() > 0) {
				Bureaucrat.createAndStart(new Worker.Task("Removing alpha mask" + (patches.size() > 1 ? "s" : "")) { public void exec() {
					final ArrayList<Future> jobs = new ArrayList<Future>();
					for (final Displayable d : patches) {
						final Patch p = (Patch) d;
						p.setAlphaMask(null);
						Future job = p.getProject().getLoader().regenerateMipMaps(p); // submit to queue
						if (null != job) jobs.add(job);
					}
					// join all
					for (final Future job : jobs) try {
						job.get();
					} catch (Exception ie) {}
				}}, patches.get(0).getProject());
			}
		} else if (command.equals("Undo")) {
			Bureaucrat.createAndStart(new Worker.Task("Undo") { public void exec() {
				layer.getParent().undoOneStep();
				Display.repaint(layer.getParent());
			}}, project);
		} else if (command.equals("Redo")) {
			Bureaucrat.createAndStart(new Worker.Task("Redo") { public void exec() {
				layer.getParent().redoOneStep();
				Display.repaint(layer.getParent());
			}}, project);
		} else if (command.equals("Apply transform")) {
			if (null == active) return;
			getMode().apply();
			setMode(new DefaultMode(Display.this));
		} else if (command.equals("Apply transform propagating to last layer")) {
			if (mode.getClass() == AffineTransformMode.class) {
				final java.util.List<Layer> layers = layer.getParent().getLayers();
				((AffineTransformMode)mode).applyAndPropagate(new HashSet<Layer>(layers.subList(layers.indexOf(Display.this.layer)+1, layers.size()))); // +1 to exclude current layer
				setMode(new DefaultMode(Display.this));
			}
		} else if (command.equals("Apply transform propagating to first layer")) {
			if (mode.getClass() == AffineTransformMode.class) {
				final java.util.List<Layer> layers = layer.getParent().getLayers();
				((AffineTransformMode)mode).applyAndPropagate(new HashSet<Layer>(layers.subList(0, layers.indexOf(Display.this.layer))));
				setMode(new DefaultMode(Display.this));
			}
		} else if (command.equals("Cancel transform")) {
			if (null == active) return;
			canvas.cancelTransform();
		} else if (command.equals("Specify transform...")) {
			if (null == active) return;
			selection.specify();
		} else if (command.equals("Hide all but images")) {
			ArrayList<Class> type = new ArrayList<Class>();
			type.add(Patch.class);
			Collection<Displayable> col = layer.getParent().hideExcept(type, false);
			selection.removeAll(col);
			Display.updateCheckboxes(col, DisplayablePanel.VISIBILITY_STATE);
			Display.update(layer.getParent(), false);
		} else if (command.equals("Unhide all")) {
			Display.updateCheckboxes(layer.getParent().setAllVisible(false), DisplayablePanel.VISIBILITY_STATE);
			Display.update(layer.getParent(), false);
		} else if (command.startsWith("Hide all ")) {
			String type = command.substring(9, command.length() -1); // skip the ending plural 's'
			type = type.substring(0, 1).toUpperCase() + type.substring(1);
			Collection<Displayable> col = layer.getParent().setVisible(type, false, true);
			selection.removeAll(col);
			Display.updateCheckboxes(col, DisplayablePanel.VISIBILITY_STATE);
		} else if (command.startsWith("Unhide all ")) {
			String type = command.substring(11, command.length() -1); // skip the ending plural 's'
			type = type.substring(0, 1).toUpperCase() + type.substring(1);
			updateCheckboxes(layer.getParent().setVisible(type, true, true), DisplayablePanel.VISIBILITY_STATE);
		} else if (command.equals("Hide deselected")) {
			hideDeselected(0 != (ActionEvent.ALT_MASK & ae.getModifiers()));
		} else if (command.equals("Hide deselected except images")) {
			hideDeselected(true);
		} else if (command.equals("Hide selected")) {
			selection.setVisible(false); // TODO should deselect them too? I don't think so.
			Display.updateCheckboxes(selection.getSelected(), DisplayablePanel.VISIBILITY_STATE);
		} else if (command.equals("Resize canvas/LayerSet...")) {
			resizeCanvas();
		} else if (command.equals("Autoresize canvas/LayerSet")) {
			layer.getParent().setMinimumDimensions();
		} else if (command.equals("Import image")) {
			importImage();
		} else if (command.equals("Import next image")) {
			importNextImage();
		} else if (command.equals("Import stack...")) {
			Display.this.getLayerSet().addLayerContentStep(layer);
			Rectangle sr = getCanvas().getSrcRect();
			Bureaucrat burro = project.getLoader().importStack(layer, sr.x + sr.width/2, sr.y + sr.height/2, null, true, null);
			burro.addPostTask(new Runnable() { public void run() {
				Display.this.getLayerSet().addLayerContentStep(layer);
			}});
		} else if (command.equals("Import grid...")) {
			Display.this.getLayerSet().addLayerContentStep(layer);
			Bureaucrat burro = project.getLoader().importGrid(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { public void run() {
					Display.this.getLayerSet().addLayerContentStep(layer);
				}});
		} else if (command.equals("Import sequence as grid...")) {
			Display.this.getLayerSet().addLayerContentStep(layer);
			Bureaucrat burro = project.getLoader().importSequenceAsGrid(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { public void run() {
					Display.this.getLayerSet().addLayerContentStep(layer);
				}});
		} else if (command.equals("Import from text file...")) {
			Display.this.getLayerSet().addLayerContentStep(layer);
			Bureaucrat burro = project.getLoader().importImages(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { public void run() {
					Display.this.getLayerSet().addLayerContentStep(layer);
				}});
		} else if (command.equals("Import labels as arealists...")) {
			Display.this.getLayerSet().addChangeTreesStep();
			Bureaucrat burro = project.getLoader().importLabelsAsAreaLists(layer, null, Double.MAX_VALUE, 0, 0.4f, false);
			burro.addPostTask(new Runnable() { public void run() {
				Display.this.getLayerSet().addChangeTreesStep();
			}});
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
			final String[] types = new String[]{"8-bit grayscale", "RGB Color"};
			int the_type = ImagePlus.GRAY8;
			final GenericDialog gd = new GenericDialog("Choose", frame);
			gd.addSlider("Scale: ", 1, 100, 100);
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
				Utils.addLayerRangeChoices(Display.this.layer, gd); /// $#%! where are my lisp macros
				gd.addCheckbox("Include non-empty layers only", true);
			}
			gd.addMessage("Background color:");
			Utils.addRGBColorSliders(gd, Color.black);
			gd.addCheckbox("Best quality", false);
			gd.addMessage("");
			gd.addCheckbox("Save to file", false);
			gd.addCheckbox("Save for web", false);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			scale = gd.getNextNumber() / 100;
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
					if (!la.isEmpty() || !non_empty_only) al.add(la); // checks both the Layer and the ZDisplayable objects in the parent LayerSet
				}
				if (0 == al.size()) {
					Utils.showMessage("All layers are empty!");
					return;
				}
				layer_array = new Layer[al.size()];
				al.toArray(layer_array);
			} else {
				layer_array = new Layer[]{Display.this.layer};
			}
			final Color background = new Color((int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
			final boolean quality = gd.getNextBoolean();
			final boolean save_to_file = gd.getNextBoolean();
			final boolean save_for_web = gd.getNextBoolean();
			// in its own thread
			if (save_for_web) project.getLoader().makePrescaledTiles(layer_array, Patch.class, srcRect, scale, c_alphas, the_type);
			else project.getLoader().makeFlatImage(layer_array, srcRect, scale, c_alphas, the_type, save_to_file, quality, background);

		} else if (command.equals("Lock")) {
			selection.setLocked(true);
			Utils.revalidateComponent(tabs.getSelectedComponent());
		} else if (command.equals("Unlock")) {
			selection.setLocked(false);
			Utils.revalidateComponent(tabs.getSelectedComponent());
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
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			Layer la_start = layer.getParent().getLayer(gd.getNextChoiceIndex());
			Layer la_end = layer.getParent().getLayer(gd.getNextChoiceIndex());
			if (la_start == la_end) {
				Utils.showMessage("Need at least two layers.");
				return;
			}
			if (selection.isLocked()) {
				Utils.showMessage("There are locked objects.");
				return;
			}
			layer.getParent().startAlign(Display.this);
			layer.getParent().applyAlign(la_start, la_end, selection);
		} else if (command.equals("Align stack slices")) {
			if (getActive() instanceof Patch) {
				final Patch slice = (Patch)getActive();
				if (slice.isStack()) {
					// check linked group
					final HashSet hs = slice.getLinkedGroup(new HashSet());
					for (Iterator it = hs.iterator(); it.hasNext(); ) {
						if (it.next().getClass() != Patch.class) {
							Utils.showMessage("Images are linked to other objects, can't proceed to cross-correlate them."); // labels should be fine, need to check that
							return;
						}
					}
					final LayerSet ls = slice.getLayerSet();
					final HashSet<Displayable> linked = slice.getLinkedGroup(null);
					ls.addTransformStep(linked);
					Bureaucrat burro = AlignTask.registerStackSlices((Patch)getActive()); // will repaint
					burro.addPostTask(new Runnable() { public void run() {
						// The current state when done
						ls.addTransformStep(linked);
					}});
				} else {
					Utils.log("Align stack slices: selected image is not part of a stack.");
				}
			}
		} else if (command.equals("Align layers")) {
			final Layer la = layer;; // caching, since scroll wheel may change it
			la.getParent().addTransformStep(la);
			Bureaucrat burro = AlignTask.alignLayersLinearlyTask( la );
			burro.addPostTask(new Runnable() { public void run() {
				la.getParent().addTransformStep(la);
			}});
		} else if (command.equals("Align multi-layer mosaic")) {
			final Layer la = layer; // caching, since scroll wheel may change it
			la.getParent().addTransformStep();
			Bureaucrat burro = AlignTask.alignMultiLayerMosaicTask( la );
			burro.addPostTask(new Runnable() { public void run() {
				la.getParent().addTransformStep();
			}});
		} else if (command.equals("Properties ...")) { // NOTE the space before the dots, to distinguish from the "Properties..." command that works on Displayable objects.
			GenericDialog gd = new GenericDialog("Properties", Display.this.frame);
			//gd.addNumericField("layer_scroll_step: ", this.scroll_step, 0);
			gd.addSlider("layer_scroll_step: ", 1, layer.getParent().size(), Display.this.scroll_step);
			gd.addChoice("snapshots_mode", LayerSet.snapshot_modes, LayerSet.snapshot_modes[layer.getParent().getSnapshotsMode()]);
			gd.addCheckbox("prefer_snapshots_quality", layer.getParent().snapshotsQuality());
			Loader lo = getProject().getLoader();
			boolean using_mipmaps = lo.isMipMapsEnabled();
			gd.addCheckbox("enable_mipmaps", using_mipmaps);
			gd.addCheckbox("enable_layer_pixels virtualization", layer.getParent().isPixelsVirtualizationEnabled());
			double max = layer.getParent().getLayerWidth() < layer.getParent().getLayerHeight() ? layer.getParent().getLayerWidth() : layer.getParent().getLayerHeight();
			gd.addSlider("max_dimension of virtualized layer pixels: ", 0, max, layer.getParent().getPixelsMaxDimension());
			// --------
			gd.showDialog();
			if (gd.wasCanceled()) return;
			// --------
			int sc = (int) gd.getNextNumber();
			if (sc < 1) sc = 1;
			Display.this.scroll_step = sc;
			updateInDatabase("scroll_step");
			//
			layer.getParent().setSnapshotsMode(gd.getNextChoiceIndex());
			layer.getParent().setSnapshotsQuality(gd.getNextBoolean());
			//
			boolean generate_mipmaps = gd.getNextBoolean();
			if (using_mipmaps && generate_mipmaps) {
				// nothing changed
			} else {
				if (using_mipmaps) { // and !generate_mipmaps
					lo.flushMipMaps(true);
				} else {
					// not using mipmaps before, and true == generate_mipmaps
					lo.generateMipMaps(layer.getParent().getDisplayables(Patch.class));
				}
			}
			//
			layer.getParent().setPixelsVirtualizationEnabled(gd.getNextBoolean());
			layer.getParent().setPixelsMaxDimension((int)gd.getNextNumber());
		} else if (command.equals("Adjust snapping parameters...")) {
			AlignTask.p_snap.setup("Snap");
		} else if (command.equals("Adjust fast-marching parameters...")) {
			Segmentation.fmp.setup();
		} else if (command.equals("Search...")) {
			new Search();
		} else if (command.equals("Select all")) {
			selection.selectAll();
			repaint(Display.this.layer, selection.getBox(), 0);
		} else if (command.equals("Select all visible")) {
			selection.selectAllVisible();
			repaint(Display.this.layer, selection.getBox(), 0);
		} else if (command.equals("Select none")) {
			Rectangle box = selection.getBox();
			selection.clear();
			repaint(Display.this.layer, box, 0);
		} else if (command.equals("Restore selection")) {
			selection.restore();
		} else if (command.equals("Select under ROI")) {
			Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null == roi) return;
			selection.selectAll(roi, true);
		} else if (command.equals("Merge")) {
			Bureaucrat burro = Bureaucrat.create(new Worker.Task("Merging AreaLists") {
				public void exec() {
					ArrayList al_sel = selection.getSelected(AreaList.class);
					// put active at the beginning, to work as the base on which other's will get merged
					al_sel.remove(Display.this.active);
					al_sel.add(0, Display.this.active);
					getLayerSet().addDataEditStep(new HashSet<Displayable>(al_sel));
					AreaList ali = AreaList.merge(al_sel);
					if (null != ali) {
						// remove all but the first from the selection
						for (int i=1; i<al_sel.size(); i++) {
							Object ob = al_sel.get(i);
							if (ob.getClass() == AreaList.class) {
								selection.remove((Displayable)ob);
							}
						}
						selection.updateTransform(ali);
						repaint(ali.getLayerSet(), ali, 0);
					}
				}
			}, Display.this.project);
			burro.addPostTask(new Runnable() { public void run() {
				getLayerSet().addDataEditStep(new HashSet<Displayable>(selection.getSelected(AreaList.class)));
			}});
			burro.goHaveBreakfast();
		} else if (command.equals("Identify...")) {
			// for pipes only for now
			if (!(active instanceof Line3D)) return;
			ini.trakem2.vector.Compare.findSimilar((Line3D)active);
		} else if (command.equals("Identify with axes...")) {
			if (!(active instanceof Pipe)) return;
			if (Project.getProjects().size() < 2) {
				Utils.showMessage("You need at least two projects open:\n-A reference project\n-The current project with the pipe to identify");
				return;
			}
			ini.trakem2.vector.Compare.findSimilarWithAxes((Line3D)active);
		} else if (command.equals("Identify with fiducials...")) {
			if (!(active instanceof Line3D)) return;
			ini.trakem2.vector.Compare.findSimilarWithFiducials((Line3D)active);
		} else if (command.equals("View orthoslices")) {
			if (!(active instanceof Patch)) return;
			Display3D.showOrthoslices(((Patch)active));
		} else if (command.equals("View volume")) {
			if (!(active instanceof Patch)) return;
			Display3D.showVolume(((Patch)active));
		} else if (command.equals("Show in 3D")) {
			for (Iterator it = selection.getSelected(ZDisplayable.class).iterator(); it.hasNext(); ) {
				ZDisplayable zd = (ZDisplayable)it.next();
				Display3D.show(zd.getProject().findProjectThing(zd));
			}
			// handle profile lists ...
			HashSet hs = new HashSet();
			for (Iterator it = selection.getSelected(Profile.class).iterator(); it.hasNext(); ) {
				Displayable d = (Displayable)it.next();
				ProjectThing profile_list = (ProjectThing)d.getProject().findProjectThing(d).getParent();
				if (!hs.contains(profile_list)) {
					Display3D.show(profile_list);
					hs.add(profile_list);
				}
			}
		} else if (command.equals("Snap")) {
			// Take the active if it's a Patch
			if (!(active instanceof Patch)) return;
			Display.snap((Patch)active);
		} else if (command.equals("Blend")) {
			HashSet<Patch> patches = new HashSet<Patch>();
			for (final Displayable d : selection.getSelected()) {
				if (d.getClass() == Patch.class) patches.add((Patch)d);
			}
			if (patches.size() > 1) {
				GenericDialog gd = new GenericDialog("Blending");
				gd.addCheckbox("Respect current alpha mask", true);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				Blending.blend(patches, gd.getNextBoolean());
			} else {
				IJ.log("Please select more than one overlapping image.");
			}
		} else if (command.equals("Montage")) {
			if (!(active instanceof Patch)) {
				Utils.showMessage("Please select only images.");
				return;
			}
			final Set<Displayable> affected = new HashSet<Displayable>(selection.getAffected());
			for (final Displayable d : affected)
				if (d.isLinked()) {
					Utils.showMessage( "You cannot montage linked objects." );
					return;
				}
			// make an undo step!
			final LayerSet ls = layer.getParent();
			ls.addTransformStep(affected);
			Bureaucrat burro = AlignTask.alignSelectionTask( selection );
			burro.addPostTask(new Runnable() { public void run() {
				ls.addTransformStep(affected);
			}});
		} else if (command.equals("Lens correction")) {
			final Layer la = layer;
			la.getParent().addDataEditStep(new HashSet<Displayable>(la.getParent().getDisplayables()));
			Bureaucrat burro = DistortionCorrectionTask.correctDistortionFromSelection( selection );
			burro.addPostTask(new Runnable() { public void run() {
				// no means to know which where modified and from which layers!
				la.getParent().addDataEditStep(new HashSet<Displayable>(la.getParent().getDisplayables()));
			}});
		} else if (command.equals("Link images...")) {
			GenericDialog gd = new GenericDialog("Options");
			gd.addMessage("Linking images to images (within their own layer only):");
			String[] options = {"all images to all images", "each image with any other overlapping image"};
			gd.addChoice("Link: ", options, options[1]);
			String[] options2 = {"selected images only", "all images in this layer", "all images in all layers, within the layer only", "all images in all layers, within and across consecutive layers"};
			gd.addChoice("Apply to: ", options2, options2[0]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			Layer lay = layer;
			final HashSet<Displayable> ds = new HashSet<Displayable>(lay.getParent().getDisplayables());
			lay.getParent().addDataEditStep(ds, new String[]{"data"});
			boolean overlapping_only = 1 == gd.getNextChoiceIndex();
			Collection<Displayable> coll = null;
			switch (gd.getNextChoiceIndex()) {
				case 0:
					coll = selection.getSelected(Patch.class);
					Patch.crosslink(coll, overlapping_only);
					break;
				case 1:
					coll = lay.getDisplayables(Patch.class);
					Patch.crosslink(coll, overlapping_only);
					break;
				case 2:
					coll = new ArrayList<Displayable>();
					for (final Layer la : lay.getParent().getLayers()) {
						Collection<Displayable> acoll = la.getDisplayables(Patch.class);
						Patch.crosslink(acoll, overlapping_only);
						coll.addAll(acoll);
					}
					break;
				case 3:
					ArrayList<Layer> layers = lay.getParent().getLayers();
					Collection<Displayable> lc1 = layers.get(0).getDisplayables(Patch.class);
					if (lay == layers.get(0)) coll = lc1;
					for (int i=1; i<layers.size(); i++) {
						Collection<Displayable> lc2 = layers.get(i).getDisplayables(Patch.class);
						if (null == coll && Display.this.layer == layers.get(i)) coll = lc2;
						Collection<Displayable> both = new ArrayList<Displayable>();
						both.addAll(lc1);
						both.addAll(lc2);
						Patch.crosslink(both, overlapping_only);
						lc1 = lc2;
					}
					break;
			}
			if (null != coll) Display.updateCheckboxes(coll, DisplayablePanel.LINK_STATE, true);
			lay.getParent().addDataEditStep(ds);
		} else if (command.equals("Unlink all selected images")) {
			if (Utils.check("Really unlink selected images?")) {
				for (final Displayable d : selection.getSelected(Patch.class)) {
					d.unlink();
				}
			}
		} else if (command.equals("Unlink all")) {
			if (Utils.check("Really unlink all objects from all layers?")) {
				for (final Displayable d : layer.getParent().getDisplayables()) {
					d.unlink();
				}
			}
		} else if (command.equals("Calibration...")) {
			try {
				IJ.run(canvas.getFakeImagePlus(), "Properties...", "");
			} catch (RuntimeException re) {
				Utils.log2("Calibration dialog canceled.");
			}
		} else if (command.equals("Enhance contrast (selected images)...")) {
			final Layer la = layer;
			final HashSet<Displayable> ds = new HashSet<Displayable>(la.getParent().getDisplayables());
			la.getParent().addDataEditStep(ds);
			ArrayList al = selection.getSelected(Patch.class);
			Bureaucrat burro = getProject().getLoader().homogenizeContrast(al);
			burro.addPostTask(new Runnable() { public void run() {
				la.getParent().addDataEditStep(ds);
			}});
		} else if (command.equals("Enhance contrast layer-wise...")) {
			// ask for range of layers
			final GenericDialog gd = new GenericDialog("Choose range");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			java.util.List list = layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1); // exclusive end
			Layer[] la = new Layer[list.size()];
			list.toArray(la);
			final HashSet<Displayable> ds = new HashSet<Displayable>();
			for (final Layer l : la) ds.addAll(l.getDisplayables(Patch.class));
			getLayerSet().addDataEditStep(ds);
			Bureaucrat burro = project.getLoader().homogenizeContrast(la);
			burro.addPostTask(new Runnable() { public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Set Min and Max layer-wise...")) {
			Displayable active = getActive();
			double min = 0;
			double max = 0;
			if (null != active && active.getClass() == Patch.class) {
				min = ((Patch)active).getMin();
				max = ((Patch)active).getMax();
			}
			final GenericDialog gd = new GenericDialog("Min and Max");
			gd.addMessage("Set min and max to all images in the layer range");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.addNumericField("min: ", min, 2);
			gd.addNumericField("max: ", max, 2);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			//
			min = gd.getNextNumber();
			max = gd.getNextNumber();
			ArrayList<Displayable> al = new ArrayList<Displayable>();
			for (final Layer la : layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1)) { // exclusive end
				al.addAll(la.getDisplayables(Patch.class));
			}
			final HashSet<Displayable> ds = new HashSet<Displayable>(al);
			getLayerSet().addDataEditStep(ds);
			Bureaucrat burro = project.getLoader().setMinAndMax(al, min, max);
			burro.addPostTask(new Runnable() { public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Set Min and Max (selected images)...")) {
			Displayable active = getActive();
			double min = 0;
			double max = 0;
			if (null != active && active.getClass() == Patch.class) {
				min = ((Patch)active).getMin();
				max = ((Patch)active).getMax();
			}
			final GenericDialog gd = new GenericDialog("Min and Max");
			gd.addMessage("Set min and max to all selected images");
			gd.addNumericField("min: ", min, 2);
			gd.addNumericField("max: ", max, 2);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			//
			min = gd.getNextNumber();
			max = gd.getNextNumber();
			final HashSet<Displayable> ds = new HashSet<Displayable>(selection.getSelected(Patch.class));
			getLayerSet().addDataEditStep(ds);
			Bureaucrat burro = project.getLoader().setMinAndMax(selection.getSelected(Patch.class), min, max);
			burro.addPostTask(new Runnable() { public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Duplicate")) {
			// only Patch and DLabel, i.e. Layer-only resident objects that don't exist in the Project Tree
			final HashSet<Class> accepted = new HashSet<Class>();
			accepted.add(Patch.class);
			accepted.add(DLabel.class);
			final ArrayList<Displayable> originals = new ArrayList<Displayable>();
			final ArrayList<Displayable> selected = selection.getSelected();
			for (final Displayable d : selected) {
				if (accepted.contains(d.getClass())) {
					originals.add(d);
				}
			}
			if (originals.size() > 0) {
				getLayerSet().addChangeTreesStep();
				for (final Displayable d : originals) {
					d.getLayer().add(d.clone());
				}
				getLayerSet().addChangeTreesStep();
			} else if (selected.size() > 0) {
				Utils.log("Can only duplicate images and text labels.\nDuplicate *other* objects in the Project Tree.\n");
			}
		} else if (command.equals("Create subproject")) {
			Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null == roi) return; // the menu item is not active unless there is a ROI
			Layer first, last;
			if (1 == layer.getParent().size()) {
				first = last = layer;
			} else {
				GenericDialog gd = new GenericDialog("Choose layer range");
				Utils.addLayerRangeChoices(layer, gd);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				first = layer.getParent().getLayer(gd.getNextChoiceIndex());
				last = layer.getParent().getLayer(gd.getNextChoiceIndex());
				Utils.log2("first, last: " + first + ", " + last);
			}
			Project sub = getProject().createSubproject(roi.getBounds(), first, last);
			final LayerSet subls = sub.getRootLayerSet();
			final Display d = new Display(sub, subls.getLayer(0));
			SwingUtilities.invokeLater(new Runnable() { public void run() {
			d.canvas.showCentered(new Rectangle(0, 0, (int)subls.getLayerWidth(), (int)subls.getLayerHeight()));
			}});
		} else if (command.startsWith("Arealists as labels")) {
			GenericDialog gd = new GenericDialog("Export labels");
			gd.addSlider("Scale: ", 1, 100, 100);
			final String[] options = {"All area list", "Selected area lists"};
			gd.addChoice("Export: ", options, options[0]);
			Utils.addLayerRangeChoices(layer, gd);
			gd.addCheckbox("Visible only", true);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final float scale = (float)(gd.getNextNumber() / 100);
			java.util.List al = 0 == gd.getNextChoiceIndex() ? layer.getParent().getZDisplayables(AreaList.class) : selection.getSelected(AreaList.class);
			if (null == al) {
				Utils.log("No area lists found to export.");
				return;
			}
			// Generics are ... a pain? I don't understand them? They fail when they shouldn't? And so easy to workaround that they are a shame?
			al = (java.util.List<Displayable>) al;

			int first = gd.getNextChoiceIndex();
			int last  = gd.getNextChoiceIndex();
			boolean visible_only = gd.getNextBoolean();
			if (-1 != command.indexOf("(amira)")) {
				AreaList.exportAsLabels(al, canvas.getFakeImagePlus().getRoi(), scale, first, last, visible_only, true, true);
			} else if (-1 != command.indexOf("(tif)")) {
				AreaList.exportAsLabels(al, canvas.getFakeImagePlus().getRoi(), scale, first, last, visible_only, false, false);
			}
		} else if (command.equals("Project properties...")) {
			project.adjustProperties();
		} else if (command.equals("Release memory...")) {
			Bureaucrat.createAndStart(new Worker("Releasing memory") {
				public void run() {
					startedWorking();
					try {
			GenericDialog gd = new GenericDialog("Release Memory");
			int max = (int)(IJ.maxMemory() / 1000000);
			gd.addSlider("Megabytes: ", 0, max, max/2);
			gd.showDialog();
			if (!gd.wasCanceled()) {
				int n_mb = (int)gd.getNextNumber();
				project.getLoader().releaseToFit((long)n_mb*1000000);
			}
					} catch (Throwable e) {
						IJError.print(e);
					} finally {
						finishedWorking();
					}
				}
			}, project);
		} else if (command.equals("Flush image cache")) {
			Loader.releaseAllCaches();
		} else if (command.equals("Regenerate all mipmaps")) {
			for (final Displayable d : getLayerSet().getDisplayables(Patch.class)) {
				d.getProject().getLoader().regenerateMipMaps((Patch) d);
			}
		} else {
			Utils.log2("Display: don't know what to do with command " + command);
		}
		}});
	}

	/** Update in all displays the Transform for the given Displayable if it's selected. */
	static public void updateTransform(final Displayable displ) {
		for (final Display d : al_displays) {
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

	protected Bureaucrat importImage() {
		Worker worker = new Worker("Import image") {  /// all this verbosity is what happens when functions are not first class citizens. I could abstract it away by passing a string name "importImage" and invoking it with reflection, but that is an even bigger PAIN
			public void run() {
				startedWorking();
				try {
		///

		Rectangle srcRect = canvas.getSrcRect();
		int x = srcRect.x + srcRect.width / 2;
		int y = srcRect.y + srcRect.height/ 2;
		Patch p = project.getLoader().importImage(project, x, y);
		if (null == p) {
			finishedWorking();
			Utils.showMessage("Could not open the image.");
			return;
		}

		Display.this.getLayerSet().addLayerContentStep(layer);

		layer.add(p); // will add it to the proper Displays

		Display.this.getLayerSet().addLayerContentStep(layer);

		///
				} catch (Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, getProject());
	}

	protected Bureaucrat importNextImage() {
		Worker worker = new Worker("Import image") {  /// all this verbosity is what happens when functions are not first class citizens. I could abstract it away by passing a string name "importImage" and invoking it with reflection, but that is an even bigger PAIN
			public void run() {
				startedWorking();
				try {

		Rectangle srcRect = canvas.getSrcRect();
		int x = srcRect.x + srcRect.width / 2;// - imp.getWidth() / 2;
		int y = srcRect.y + srcRect.height/ 2;// - imp.getHeight()/ 2;
		Patch p = project.getLoader().importNextImage(project, x, y);
		if (null == p) {
			Utils.showMessage("Could not open next image.");
			finishedWorking();
			return;
		}

		Display.this.getLayerSet().addLayerContentStep(layer);

		layer.add(p); // will add it to the proper Displays

		Display.this.getLayerSet().addLayerContentStep(layer);

				} catch (Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, getProject());
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
		//Utils.log2("c_alphas: " + c_alphas);
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
		Utils.updateComponent(panel_channels);
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

	/** Show the layer in the front Display, or in a new Display if the front Display is showing a layer from a different LayerSet. */
	static public void showFront(final Layer layer) {
		Display display = front;
		if (null == display || display.layer.getParent() != layer.getParent()) {
			display = new Display(layer.getProject(), layer, null); // gets set to front
		} else {
			display.setLayer(layer);
		}
	}

	/** Show the given Displayable centered and selected. If select is false, the selection is cleared. */
	static public void showCentered(Layer layer, Displayable displ, boolean select, boolean shift_down) {
		// see if the given layer belongs to the layer set being displayed
		Display display = front; // to ensure thread consistency to some extent
		if (null == display || display.layer.getParent() != layer.getParent()) {
			display = new Display(layer.getProject(), layer, displ); // gets set to front
		} else if (display.layer != layer) {
			display.setLayer(layer);
		}
		if (select) {
			if (!shift_down) display.selection.clear();
			display.selection.add(displ);
		} else {
			display.selection.clear();
		}
		display.showCentered(displ);
	}

	private final void showCentered(final Displayable displ) {
		if (null == displ) return;
		SwingUtilities.invokeLater(new Runnable() { public void run() {
		displ.setVisible(true);
		Rectangle box = displ.getBoundingBox();
		if (0 == box.width || 0 == box.height) {
			box.width = (int)layer.getLayerWidth();
			box.height = (int)layer.getLayerHeight();
		}
		canvas.showCentered(box);
		scrollToShow(displ);
		if (displ instanceof ZDisplayable) {
			// scroll to first layer that has a point
			ZDisplayable zd = (ZDisplayable)displ;
			setLayer(zd.getFirstLayer());
		}
		}});
	}

	/** Listen to interesting updates, such as the ColorPicker and updates to Patch objects. */
	public void imageUpdated(ImagePlus updated) {
		// detect ColorPicker WARNING this will work even if the Display is not the window immediately active under the color picker.
		if (this == front && updated instanceof ij.plugin.ColorPicker) {
			if (null != active && project.isInputEnabled()) {
				selection.setColor(Toolbar.getForegroundColor());
				Display.repaint(front.layer, selection.getBox(), 0);
			}
			return;
		}
		// $%#@!!  LUT changes don't set the image as changed
		//if (updated instanceof PatchStack) {
		//	updated.changes = 1
		//}

		//Utils.log2("imageUpdated: " + updated + "  " + updated.getClass());

		/* // never gets called (?)
		// the above is overkill. Instead:
		if (updated instanceof PatchStack) {
			Patch p = ((PatchStack)updated).getCurrentPatch();
			ImageProcessor ip = updated.getProcessor();
			p.setMinAndMax(ip.getMin(), ip.getMax());
			Utils.log2("setting min and max: " + ip.getMin() + ", " + ip.getMax());
			project.getLoader().decacheAWT(p.getId()); // including level 0, which will be editable
			// on repaint, it will be recreated
			//((PatchStack)updated).decacheAll(); // so that it will repaint with a newly created image
		}
		*/

		// detect LUT changes: DONE at PatchStack, which is the active (virtual) image
		//Utils.log2("calling decache for " + updated);
		//getProject().getLoader().decache(updated);
	}

	public void imageClosed(ImagePlus imp) {}
	public void imageOpened(ImagePlus imp) {}

	/** Release memory captured by the offscreen images */
	static public void flushAll() {
		for (final Display d : al_displays) {
			d.canvas.flush();
		}
		//System.gc();
		Thread.yield();
	}

	/** Can be null. */
	static public Display getFront() {
		return front;
	}

	static public void setCursorToAll(final Cursor c) {
		for (final Display d : al_displays) {
			d.frame.setCursor(c);
		}
	}

	protected void setCursor(Cursor c) {
		frame.setCursor(c);
	}

	/** Used by the Displayable to update the visibility and locking state checkboxes in other Displays. */
	static public void updateCheckboxes(final Displayable displ, final int cb, final boolean state) {
		for (final Display d : al_displays) {
			DisplayablePanel dp = d.ht_panels.get(displ);
			if (null != dp) dp.updateCheckbox(cb, state);
		}
	}
	/** Set the checkbox @param cb state to @param state value, for each Displayable. Assumes all Displayable objects belong to one specific project. */
	static public void updateCheckboxes(final Collection<Displayable> displs, final int cb, final boolean state) {
		if (null == displs || 0 == displs.size()) return;
		final Project p = displs.iterator().next().getProject();
		for (final Display d : al_displays) {
			if (d.getProject() != p) continue;
			for (final Displayable displ : displs) {
				DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.updateCheckbox(cb, state);
			}
		}
	}
	/** Update the checkbox @param cb state to an appropriate value for each Displayable. Assumes all Displayable objects belong to one specific project. */
	static public void updateCheckboxes(final Collection<Displayable> displs, final int cb) {
		if (null == displs || 0 == displs.size()) return;
		final Project p = displs.iterator().next().getProject();
		for (final Display d : al_displays) {
			if (d.getProject() != p) continue;
			for (final Displayable displ : displs) {
				DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.updateCheckbox(cb);
			}
		}
	}

	protected boolean isActiveWindow() {
		return frame.isActive();
	}

	/** Toggle user input; pan and zoom are always enabled though.*/
	static public void setReceivesInput(final Project project, final boolean b) {
		for (final Display d : al_displays) {
			if (d.project == project) d.canvas.setReceivesInput(b);
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
			 .append(indent).append("<!ATTLIST t2_display c_alphas NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display c_alphas_state NMTOKEN #REQUIRED>\n")
		;
	}
	/** Export all displays of the given project as XML entries. */
	static public void exportXML(final Project project, final Writer writer, final String indent, final Object any) throws Exception {
		final StringBuffer sb_body = new StringBuffer();
		final String in = indent + "\t";
		for (final Display d : al_displays) {
			if (d.project != project) continue;
			final Rectangle r = d.frame.getBounds();
			final Rectangle srcRect = d.canvas.getSrcRect();
			final double magnification = d.canvas.getMagnification();
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
		writer.write(sb_body.toString());
	}

	static public void toolChanged(final String tool_name) {
		Utils.log2("tool name: " + tool_name);
		if (!tool_name.equals("ALIGN")) {
			for (final Display d : al_displays) {
				d.layer.getParent().cancelAlign();
			}
		}
		for (final Display d : al_displays) {
			Utils.updateComponent(d.toolbar_panel);
			Utils.log2("updating toolbar_panel");
		}
	}

	static public void toolChanged(final int tool) {
		//Utils.log2("int tool is " + tool);
		if (ProjectToolbar.PEN == tool) {
			// erase bounding boxes
			for (final Display d : al_displays) {
				if (null != d.active) d.repaint(d.layer, d.selection.getBox(), 2);
			}
		}
		if (null != front) {
			WindowManager.setTempCurrentImage(front.canvas.getFakeImagePlus());
		}
		for (final Display d : al_displays) {
			Utils.updateComponent(d.toolbar_panel);
			Utils.log2("updating toolbar_panel");
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
	static public void updateSelection(final Display calling) {
		final HashSet hs = new HashSet();
		for (final Display d : al_displays) {
			if (hs.contains(d.layer)) continue;
			hs.add(d.layer);
			if (null == d || null == d.selection) {
				Utils.log2("d is : "+ d + " d.selection is " + d.selection);
			} else {
				d.selection.update(); // recomputes box
			}
			if (d != calling) { // TODO this is so dirty!
				if (d.selection.getNLinked() > 1) d.canvas.setUpdateGraphics(true); // this is overkill anyway
				d.canvas.repaint(d.selection.getLinkedBox(), Selection.PADDING);
				d.navigator.repaint(true); // everything
			}
		}
	}

	static public void clearSelection(final Layer layer) {
		for (final Display d : al_displays) {
			if (d.layer == layer) d.selection.clear();
		}
	}
	static public void clearSelection() {
		for (final Display d : al_displays) {
			d.selection.clear();
		}
	}

	private void setTempCurrentImage() {
		WindowManager.setCurrentWindow(canvas.getFakeImagePlus().getWindow(), true);
		WindowManager.setTempCurrentImage(canvas.getFakeImagePlus());
	}

	/** Check if any display will paint the given Displayable at the given magnification. */
	static public boolean willPaint(final Displayable displ, final double magnification) {
		Rectangle box = null; ;
		for (final Display d : al_displays) {
			/* // Can no longer do this check, because 'magnification' is now affected by the Displayable AffineTransform! And thus it would not paint after the prePaint.
			if (Math.abs(d.canvas.getMagnification() - magnification) > 0.00000001) {
				continue;
			}
			*/
			if (null == box) box = displ.getBoundingBox(null);
			if (d.canvas.getSrcRect().intersects(box)) {
				return true;
			}
		}
		return false;
	}

	public void hideDeselected(final boolean not_images) {
		// hide deselected
		final ArrayList all = layer.getParent().getZDisplayables(); // a copy
		all.addAll(layer.getDisplayables());
		all.removeAll(selection.getSelected());
		if (not_images) all.removeAll(layer.getDisplayables(Patch.class));
		for (final Displayable d : (ArrayList<Displayable>)all) {
			if (d.isVisible()) {
				d.setVisible(false);
				Display.updateCheckboxes(d, DisplayablePanel.VISIBILITY_STATE, false);
			}
		}
		Display.update(layer);
	}

	/** Cleanup internal lists that may contain the given Displayable. */
	static public void flush(final Displayable displ) {
		for (final Display d : al_displays) {
			d.selection.removeFromPrev(displ);
		}
	}

	public void resizeCanvas() {
		GenericDialog gd = new GenericDialog("Resize LayerSet");
		gd.addNumericField("new width: ", layer.getLayerWidth(), 3);
		gd.addNumericField("new height: ",layer.getLayerHeight(),3);
		gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[7]);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		double new_width = gd.getNextNumber();
		double new_height =gd.getNextNumber();
		layer.getParent().setDimensions(new_width, new_height, gd.getNextChoiceIndex()); // will complain and prevent cropping existing Displayable objects
	}

	/*
	// To record layer changes -- but it's annoying, this is visualization not data.
	static class DoSetLayer implements DoStep {
		final Display display;
		final Layer layer;
		DoSetLayer(final Display display) {
			this.display = display;
			this.layer = display.layer;
		}
		public Displayable getD() { return null; }
		public boolean isEmpty() { return false; }
		public boolean apply(final int action) {
			display.setLayer(layer);
		}
		public boolean isIdenticalTo(final Object ob) {
			if (!ob instanceof DoSetLayer) return false;
			final DoSetLayer dsl = (DoSetLayer) ob;
			return dsl.display == this.display && dsl.layer == this.layer;
		}
	}
	*/

	protected void duplicateLinkAndSendTo(final Displayable active, final int position, final Layer other_layer) {
		if (null == active || !(active instanceof Profile)) return;
		if (active.getLayer() == other_layer) return; // can't do that!
		Profile profile = project.getProjectTree().duplicateChild((Profile)active, position, other_layer);
		if (null == profile) return;
		active.link(profile);
		other_layer.add(profile);
		slt.setAndWait(other_layer);
		selection.add(profile);
	}

	private final HashMap<Color,Layer> layer_channels = new HashMap<Color,Layer>();
	private final TreeMap<Integer,LayerPanel> layer_alpha = new TreeMap<Integer,LayerPanel>();
	boolean invert_colors = false;

	/** Remove all red/blue coloring of layers, and repaint canvas. */
	protected void resetLayerColors() {
		synchronized (layer_channels) {
			for (final Layer l : new ArrayList<Layer>(layer_channels.values())) { // avoid concurrent modification exception
				final LayerPanel lp = layer_panels.get(l);
				lp.setColor(Color.white);
				setColorChannel(lp.layer, Color.white);
				lp.slider.setEnabled(true);
			}
			layer_channels.clear();
		}
		canvas.repaint();
	}

	/** Set all layer alphas to zero, and repaint canvas. */
	protected void resetLayerAlphas() {
		synchronized (layer_channels) {
			for (final LayerPanel lp : new ArrayList<LayerPanel>(layer_alpha.values())) {
				lp.setAlpha(0);
			}
			layer_alpha.clear(); // should have already been cleared
		}
		canvas.repaint();
	}

	/** Add to layer_alpha table, or remove if alpha is zero. */
	protected void storeLayerAlpha(final LayerPanel lp, final float a) {
		synchronized (layer_channels) {
			if (M.equals(0, a)) {
				layer_alpha.remove(lp.layer.getParent().indexOf(lp.layer));
			} else {
				layer_alpha.put(lp.layer.getParent().indexOf(lp.layer), lp);
			}
		}
	}

	static protected final int REPAINT_SINGLE_LAYER = 0;
	static protected final int REPAINT_MULTI_LAYER = 1;
	static protected final int REPAINT_RGB_LAYER = 2;

	/** Sets the values atomically, returns the painting mode. */
	protected int getPaintMode(final HashMap<Color,Layer> hm, final ArrayList<LayerPanel> list) {
		synchronized (layer_channels) {
			if (layer_channels.size() > 0) {
				hm.putAll(layer_channels);
				hm.put(Color.green, this.layer);
				return REPAINT_RGB_LAYER;
			}
			list.addAll(layer_alpha.values());
			final int len = list.size();
			if (len > 1) return REPAINT_MULTI_LAYER;
			if (1 == len) {
				if (list.get(0).layer == this.layer) return REPAINT_SINGLE_LAYER; // normal mode
				return REPAINT_MULTI_LAYER;
			}
			return REPAINT_SINGLE_LAYER;
		}
	}

	/** Set a layer to be painted as a specific color channel in the canvas.
	 *  Only Color.red and Color.blue are accepted.
	 *  Color.green is reserved for the current layer. */
	protected void setColorChannel(final Layer layer, final Color color) {
		synchronized (layer_channels) {
			if (Color.white == color) {
				// Remove
				for (final Iterator<Layer> it = layer_channels.values().iterator(); it.hasNext(); ) {
					if (it.next() == layer) {
						it.remove();
						break;
					}
				}
				canvas.repaint();
			} else if (Color.red == color || Color.blue == color) {
				// Reset current of that color, if any, to white
				final Layer l = layer_channels.remove(color);
				if (null != l) layer_panels.get(l).setColor(Color.white);
				// Replace or set new
				layer_channels.put(color, layer);
				tabs.repaint();
				canvas.repaint();
			} else {
				Utils.log2("Trying to set unacceptable color for layer " + layer + " : " + color);
			}
			// enable/disable sliders
			final boolean b = 0 == layer_channels.size();
			for (final LayerPanel lp : layer_panels.values()) lp.slider.setEnabled(b);
		}
		this.canvas.repaint(true);
	}

	static public final void updateComponentTreeUI() {
		try {
			for (final Display d : al_displays) SwingUtilities.updateComponentTreeUI(d.frame);
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	/** Snap a Patch to the most overlapping Patch, if any.
	 *  This method is a shallow wrap around AlignTask.snap, setting proper undo steps. */
	static public final Bureaucrat snap(final Patch patch) {
		final Set<Displayable> linked = patch.getLinkedGroup(null);
		patch.getLayerSet().addTransformStep(linked);
		Bureaucrat burro = AlignTask.snap(patch, null, false);
		burro.addPostTask(new Runnable() { public void run() {
			patch.getLayerSet().addTransformStep(linked);
		}});
		return burro;
	}

	private Mode mode = new DefaultMode(this);

	public void setMode(final Mode mode) {
		this.mode = mode;
		canvas.repaint(true);
		scroller.setEnabled(mode.getClass() == DefaultMode.class);
	}

	public Mode getMode() {
		return mode;
	}
}
