/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
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

import java.awt.BasicStroke;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Event;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Scrollbar;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.DefaultBoundedRangeModel;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;

import org.janelia.intensity.MatchIntensities;

import ij.IJ;
import ij.IJEventListener;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.Toolbar;
import ij.gui.YesNoCancelDialog;
import ij.io.DirectoryChooser;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.analysis.Graph;
import ini.trakem2.display.inspect.InspectPatchTrianglesMode;
import ini.trakem2.imaging.Blending;
import ini.trakem2.imaging.LayerStack;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.imaging.Segmentation;
import ini.trakem2.imaging.filters.FilterEditor;
import ini.trakem2.io.NeuroML;
import ini.trakem2.parallel.Process;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.ProjectTiler;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.DNDInsertImage;
import ini.trakem2.utils.Dispatcher;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Operation;
import ini.trakem2.utils.OptionPanel;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Saver;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import lenscorrection.DistortionCorrectionTask;
import lenscorrection.NonLinearTransform;
import mpicbg.ij.clahe.Flat;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.align.AlignLayersTask;
import mpicbg.trakem2.align.AlignTask;
import mpicbg.trakem2.align.Util;
import mpicbg.trakem2.transform.AffineModel3D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;

/** A Display is a class to show a Layer and enable mouse and keyboard manipulation of all its components. */
public final class Display extends DBObject implements ActionListener, IJEventListener {

	/** coordinate transform transfer modes */
	final static public int CT_REPLACE = 0;
	final static public int CT_APPEND = 1;
	final static public int CT_PREAPPEND = 2;

	/** The Layer this Display is showing. */
	private Layer layer;

	private Displayable active = null;
	/** All selected Displayable objects, including the active one. */
	final private Selection selection = new Selection(this);

	private JFrame frame;
	private JTabbedPane tabs;

	private Hashtable<Class<?>,RollingPanel> ht_tabs;
	private RollingPanel panel_patches;
	private RollingPanel panel_profiles;
	private RollingPanel panel_zdispl;
	private JScrollPane scroll_channels;
	private JPanel panel_channels;
	private RollingPanel panel_labels;

	private JPanel panel_layers;
	private JScrollPane scroll_layers;
	private final Hashtable<Layer,LayerPanel> layer_panels = new Hashtable<Layer,LayerPanel>();

	private OptionPanel tool_options;
	private JScrollPane scroll_options;

	private OptionPanel filter_options;
	private JScrollPane scroll_filter_options;

	private JEditorPane annot_editor;
	private JLabel annot_label;
	private JPanel annot_panel;
	static private HashMap<Displayable,Document> annot_docs = new HashMap<Displayable,Document>();

	private JSlider transp_slider;
	private DisplayNavigator navigator;
	private JScrollBar scroller;

	private DisplayCanvas canvas; // WARNING this is an AWT component, since it extends ImageCanvas
	private JPanel all;

	private JPopupMenu popup = null;

	private ToolbarPanel toolbar_panel = null;

	/** Contains the packed alphas of every channel. */
	private int c_alphas = 0xffffffff; // all 100 % visible
	private Channel[] channels;

	private final Hashtable<Displayable,DisplayablePanel> ht_panels = new Hashtable<Displayable,DisplayablePanel>();

	/** Handle drop events, to insert image files. */
	private DNDInsertImage dnd;

	private int scroll_step = 1;

	static private final Object DISPLAY_LOCK = new Object();

	/** Keep track of all existing Display objects. */
	static private Set<Display> al_displays = new HashSet<Display>();
	/** The currently focused Display, if any. */
	static private Display front = null;

	/** Displays to open when all objects have been reloaded from the database. */
	static private final Hashtable<Display,Object[]> ht_later = new Hashtable<Display,Object[]>();

	/** A thread to handle user actions, for example an event sent from a popup menu. */
	protected final Dispatcher dispatcher = new Dispatcher("Display GUI Updater");

	static private WindowAdapter window_listener = new WindowAdapter() {
		/** Unregister the closed Display. */
		@Override
        public void windowClosing(final WindowEvent we) {
			final Object source = we.getSource();
			for (final Display d : al_displays) {
				if (source == d.frame) {
					d.remove(false); // calls destroy, which calls removeDisplay
					break;
				}
			}
		}
		/** Set the source Display as front. */
		@Override
        public void windowActivated(final WindowEvent we) {
			// find which was it to make it be the front
			final ImageJ ij = IJ.getInstance();
			if (null != ij && ij.quitting()) return;
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
						d.frame.setMenuBar(Menus.getMenuBar());
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
		@Override
        public void windowDeactivated(final WindowEvent we) {
			// Can't, the user can never click the ProjectToolbar then. This has to be done in a different way, for example checking who is the WindowManager.getCurrentImage (and maybe setting a dummy image into it) //ProjectToolbar.setImageJToolbar();
		}
		/** Call a pack() when the window is maximized to fit the canvas correctly. */
		@Override
        public void windowStateChanged(final WindowEvent we) {
			final Object source = we.getSource();
			for (final Display d : al_displays) {
				if (source != d.frame) continue;
				d.pack();
				break;
			}
		}
	};

	static public final Vector<Display> getDisplays() {
		return new Vector<Display>(al_displays);
	}

	static public final int getDisplayCount() {
		return al_displays.size();
	}

	private final ComponentListener canvas_size_listener = new ComponentAdapter() {
		@Override
        public void componentResized(final ComponentEvent ce) {
			canvas.adjustDimensions();
			canvas.repaint(true);
			navigator.repaint(false); // update srcRect red frame position/size
		}
	};

	/*// Currently not in use
	private ComponentListener display_frame_listener = new ComponentAdapter() {
		public void componentMoved(ComponentEvent ce) {
			updateInDatabase("position");
		}
	};
	*/

	static private ChangeListener tabs_listener = new ChangeListener() {
		/** Listen to tab changes. */
		@Override
        public void stateChanged(final ChangeEvent ce) {
			final Object source = ce.getSource();
			for (final Display d : al_displays) {
				if (source != d.tabs) continue;
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
					JPanel p = null;
					if (tab == d.panel_zdispl) {
						p = d.panel_zdispl;
					} else if (tab == d.panel_patches) {
						p = d.panel_patches;
					} else if (tab == d.panel_labels) {
						p = d.panel_labels;
					} else if (tab == d.panel_profiles) {
						p = d.panel_profiles;
					} else if (tab == d.scroll_layers) {
						// nothing to do
						return;
					} else if (tab == d.scroll_options) {
						// Choose according to tool
						d.updateToolTab();
						return;
					}

					d.updateTab(p);

					if (null != d.active) {
						// set the transp slider to the alpha value of the active Displayable if any
						d.transp_slider.setValue((int)(d.active.getAlpha() * 100));
					}
				}
				break;
			}
		}
	};


	private final AdjustmentListener scroller_listener = new AdjustmentListener() {
		@Override
        public void adjustmentValueChanged(final AdjustmentEvent ae) {
			final int index = ae.getValue();
			final Layer la = layer.getParent().getLayer(index);
			if (la != Display.this.layer) slt.set(la);
		}
	};

	private final SetLayerThread slt = new SetLayerThread();

	private class SetLayerThread extends Thread {

		private volatile Layer layer;
		private final Object lock = new Object();

		SetLayerThread() {
			super("SetLayerThread");
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

				if (layer.getParent().preload_ahead > 0) {
					preloadImagesAhead(Display.this.layer, layer, layer.getParent().preload_ahead);
				}

				Display.this.setLayer(layer);
				Display.this.updateInDatabase("layer_id");
				createColumnScreenshots();
			}
		}

		@Override
        public void run() {
			while (!isInterrupted()) {
				while (null == this.layer) {
					synchronized (this) {
						if (isInterrupted()) return;
						try { wait(); } catch (final InterruptedException ie) {}
					}
				}
				Layer layer = null;
				synchronized (lock) {
					layer = this.layer;
					this.layer = null;
				}
				//
				if (isInterrupted()) return; // after nullifying layer
				//
				setAndWait(layer);
			}
		}

		public void quit() {
			interrupt();
			synchronized (this) {
				notify();
			}
		}
	}

	static final public void clearColumnScreenshots(final LayerSet ls) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == ls) d.clearColumnScreenshots();
		}
	}

	private final ImagePreloader imagePreloader = new ImagePreloader();

	private final class ImagePreloader extends Thread {
		private Layer oldLayer, newLayer;
		private int nLayers;
		private boolean restart = false;

		ImagePreloader() {
			setPriority(Thread.NORM_PRIORITY);
			setDaemon(true);
			start();
		}

		final void reset(final Layer oldLayer, final Layer newLayer, final int nLayers) {
			synchronized (this) {
				this.restart = true;
				this.oldLayer = oldLayer;
				this.newLayer = newLayer;
				this.nLayers = nLayers;
				notify();
			}
		}

		@Override
		public void run() {
			while (!isInterrupted()) {
				final Layer oldLayer, newLayer;
				final int nLayers;
				synchronized (this) {
					try {
						if (!restart) wait();
					} catch (final InterruptedException e) {
						return;
					}
					oldLayer = this.oldLayer;
					newLayer = this.newLayer;
					this.oldLayer = null;
					this.newLayer = null;
					nLayers = this.nLayers;
					restart = false;
				}

				final LayerSet ls = oldLayer.getParent();
				final int old_layer_index = ls.indexOf(oldLayer);
				final int new_layer_index = ls.indexOf(newLayer);
				final int sign = new_layer_index - old_layer_index;

				final Area aroi = new Area(canvas.getSrcRect());
				final double mag = canvas.getMagnification();

				// Preload from most distant ahead to least.
				// The assumption being that least distant are already cached.
				//Utils.log2("-- started");
				for (final Layer la : ls.getLayers(Math.max(0, Math.min(new_layer_index + sign * nLayers, ls.size() -1)), new_layer_index)) {
					if (restart) break;
					for (final Displayable d : la.getDisplayables(Patch.class, aroi, true)) {
						if (restart) break;
						if (isInterrupted()) return;
						project.getLoader().fetchImage((Patch)d, mag);
						//Utils.log2("preloaded #" + d.getId() + " from layer " + la.getParent().indexOf(la));
					}
				}
				//Utils.log2("-- completed: restart is " + restart);
			}
		}
	}

	private final void preloadImagesAhead(final Layer oldLayer, final Layer newLayer, final int nLayers) {
		imagePreloader.reset(oldLayer, newLayer, nLayers);
	}

	final public void clearColumnScreenshots() {
		getLayerSet().clearScreenshots();
	}

	/** Only for DefaultMode. */
	final private void createColumnScreenshots() {
		final int n;
		try {
			if (mode.getClass() == DefaultMode.class) {
				int ahead = project.getProperty("look_ahead_cache", 0);
				if (ahead < 0) ahead = 0;
				if (0 == ahead) return;
				n = ahead;
			} else return;
		} catch (final Exception e) {
			IJError.print(e);
			return;
		}
		project.getLoader().doLater(new Callable<Object>() {
			@Override
            public Object call() {
				final Layer current = Display.this.layer;
				// 1 - Create DisplayCanvas.Screenshot instances for the next 5 and previous 5 layers
				final ArrayList<DisplayCanvas.Screenshot> s = new ArrayList<DisplayCanvas.Screenshot>();
				Layer now = current;
				Layer prev = now.getParent().previous(now);
				int i = 0;
				Layer next = now.getParent().next(now);
				while (now != next && i < n) {
					s.add(canvas.createScreenshot(next));
					now = next;
					next = now.getParent().next(now);
					i++;
				}
				now = current;
				i = 0;
				while (now != prev && i < n) {
					s.add(0, canvas.createScreenshot(prev));
					now = prev;
					prev = now.getParent().previous(now);
					i++;
				}
				// Store them all into the LayerSet offscreens hashmap, but trigger image creation in parallel threads.
				for (final DisplayCanvas.Screenshot sc : s) {
					if (!current.getParent().containsScreenshot(sc)) {
						sc.init();
						current.getParent().storeScreenshot(sc);
						project.getLoader().doLater(new Callable<Object>() {
							@Override
                            public Object call() {
								sc.createImage();
								return null;
							}
						});
					}
				}
				current.getParent().trimScreenshots();
				return null;
			}
		});
	}

	/** Creates a new Display with adjusted magnification to fit in the screen. */
	static public void createDisplay(final Project project, final Layer layer) {
		// Really execute in a second round of event dispatch thread
		// to enable the calling component to repaint back to normal
		// and the events to terminate.
		SwingUtilities.invokeLater(new Runnable() { @Override
        public void run() {
			final Display display = new Display(project, layer);
			final Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			final Rectangle srcRect = new Rectangle(0, 0, (int)layer.getLayerWidth(), (int)layer.getLayerHeight());
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

	//
	// The only two methods that ever modify the set of al_displays
	//

	/** Swap the current al_displays list with a new list that has the @param display in it. */
	static private final void addDisplay(final Display display) {
		if (null == display) return;
		synchronized (DISPLAY_LOCK) {
			final Set<Display> a = new HashSet<Display>();
			if (null != al_displays) a.addAll(al_displays);
			a.add(display);
			al_displays = a;
			front = display;
		}
	}

	/** Swap the current al_displays list with a new list that lacks the @param dispaly, and set a new front if needed. */
	static private final void removeDisplay(final Display display) {
		if (null == display) return;
		synchronized (DISPLAY_LOCK) {
			final Set<Display> a = new HashSet<Display>(al_displays);
			a.remove(display);
			if (null == front || front == display) {
				if (a.size() > 0) {
					front = a.iterator().next();
				} else {
					front = null;
				}
			}
			al_displays = a;
		}
	}

	/** For reconstruction purposes. The Display will be stored in the ht_later.*/
	public Display(final Project project, final long id, final Layer layer, final Object[] props) {
		super(project, id);
		synchronized (ht_later) {
			Display.ht_later.put(this, props);
		}
		this.layer = layer;
		IJ.addEventListener(this);
		Display.addDisplay(this);
	}

	/** A new Display from scratch, to show the given Layer. */
	public Display(final Project project, final Layer layer) {
		this(project, layer, null);
	}

	/** Open a new Display centered around the given Displayable. */
	public Display(final Project project, final Layer layer, final Displayable displ) {
		super(project);
		active = displ;
		this.layer = layer;
		makeGUI(layer, null);
		IJ.addEventListener(this);
		setLayer(layer);
		this.layer = layer; // after set layer!
		addToDatabase();
		addDisplay(this); // last: if there is an Exception, it won't be added
	}

	/** Reconstruct a Display from an XML entry, to be opened when everything is ready. */
	public Display(final Project project, final long id, final Layer layer, final HashMap<String,String> ht) {
		super(project, id);
		if (null == layer) {
			Utils.log2("Display: need a non-null Layer for id=" + id);
			return;
		}
		final Rectangle srcRect = new Rectangle(0, 0, (int)layer.getLayerWidth(), (int)layer.getLayerHeight());
		double magnification = 0.25;
		final Point p = new Point(0, 0);
		int c_alphas = 0xffffffff;
		int c_alphas_state = 0xffffffff;
		String data = ht.get("srcrect_x");
		if (null != data) srcRect.x = Integer.parseInt(data);
		data = ht.get("srcrect_y");
		if (null != data) srcRect.y = Integer.parseInt(data);
		data = ht.get("srcrect_width");
		if (null != data) srcRect.width = Integer.parseInt(data);
		data = ht.get("srcrect_height");
		if (null != data) srcRect.height = Integer.parseInt(data);
		data = ht.get("magnification");
		if (null != data) magnification = Double.parseDouble(data);
		data = ht.get("x");
		if (null != data) p.x = Integer.parseInt(data);
		data = ht.get("y");
		if (null != data) p.y = Integer.parseInt(data);
		data = ht.get("c_alphas");
		if (null != data) {
			try {
				c_alphas = Integer.parseInt(data);
			} catch (final Exception ex) {
				c_alphas = 0xffffffff;
			}
		}
		data = ht.get("c_alphas_state");
		if (null != data) {
			try {
				c_alphas_state = Integer.parseInt(data);
			} catch (final Exception ex) {
				IJError.print(ex);
				c_alphas_state = 0xffffffff;
			}
		}
		data = ht.get("scroll_step");
		if (null != data) {
			try {
				setScrollStep(Integer.parseInt(data));
			} catch (final Exception ex) {
				IJError.print(ex);
				setScrollStep(1);
			}
		}
		data = ht.get("filter_enabled");
		if (null != data) filter_enabled = Boolean.parseBoolean(data);
		data = ht.get("filter_min_max_enabled");
		if (null != data) filter_min_max_enabled = Boolean.parseBoolean(data);
		data = ht.get("filter_min");
		if (null != data) filter_min = Integer.parseInt(data);
		data = ht.get("filter_max");
		if (null != data) filter_max = Integer.parseInt(data);
		data = ht.get("filter_invert");
		if (null != data) filter_invert = Boolean.parseBoolean(data);
		data = ht.get("filter_clahe_enabled");
		if (null != data) filter_clahe_enabled = Boolean.parseBoolean(data);
		data = ht.get("filter_clahe_block_size");
		if (null != data) filter_clahe_block_size = Integer.parseInt(data);
		data = ht.get("filter_clahe_histogram_bins");
		if (null != data) filter_clahe_histogram_bins = Integer.parseInt(data);
		data = ht.get("filter_clahe_max_slope");
		if (null != data) filter_clahe_max_slope = Float.parseFloat(data);

		// TODO the above is insecure, in that data is not fully checked to be within bounds.


		final Object[] props = new Object[]{p, new Double(magnification), srcRect, new Long(layer.getId()), new Integer(c_alphas), new Integer(c_alphas_state)};
		synchronized (ht_later) {
			Display.ht_later.put(this, props);
		}
		this.layer = layer;
		IJ.addEventListener(this);

		// addDisplay(this) will be called inside the loop in openLater, see below
	}

	/** After reloading a project from the database, open the Displays that the project had. */
	static public Bureaucrat openLater() {
		final HashMap<Display,Object[]> ht_later_local;
		final Project[] ps;
		synchronized (ht_later) {
			if (0 == ht_later.size()) return null;
			ht_later_local = new HashMap<Display,Object[]>(ht_later);
			ht_later.keySet().removeAll(ht_later_local.keySet());
			final HashSet<Project> unique = new HashSet<Project>();
			for (final Display d : ht_later_local.keySet()) {
				unique.add(d.project);
			}
			ps = unique.toArray(new Project[unique.size()]);
		}
		final Worker worker = new Worker.Task("Opening displays") {
			@Override
            public void exec() {
				try {
					Thread.sleep(300); // waiting for Swing

		for (final Display d : ht_later_local.keySet()) {
			addDisplay(d); // must be set as front before repainting any ZDisplayable!
			final Object[] props = ht_later_local.get(d);
			if (ControlWindow.isGUIEnabled()) d.makeGUI(d.layer, props);
			d.setLayerLater(d.layer, d.layer.get(((Long)props[3]).longValue())); //important to do it after makeGUI
			if (!ControlWindow.isGUIEnabled()) continue;

			d.updateFrameTitle(d.layer);
			// force a repaint if a prePaint was done TODO this should be properly managed with repaints using always the invokeLater, but then it's DOG SLOW
			if (d.canvas.getMagnification() > 0.499) {
				Utils.invokeLater(new Runnable() { @Override
                public void run() {
					Display.repaint(d.layer);
					d.project.getLoader().setChanged(false);
					Utils.log2("A set to false");
				}});
			}
			d.project.getLoader().setChanged(false);
			Utils.log2("B set to false");
		}
		if (null != front) front.getProject().select(front.layer);

				} catch (final Throwable t) {
					IJError.print(t);
				}
			}
		};
		return Bureaucrat.createAndStart(worker, ps);
	}

	private final class ScrollerModel extends DefaultBoundedRangeModel {
		private static final long serialVersionUID = 1L;
		int index;
		ScrollerModel(final Layer la) {
			this.index = la.getParent().indexOf(la);
		}
		public void setValueWithoutEvent(final int index) {
			this.index = index;
			scroller.updateUI(); // so the model needs to update the UI: how pretty!
		}
		@Override
		public void setValue(final int index) {
			this.index = index;
			super.setValue(index);
		}
		@Override
		public int getValue() {
			return this.index;
		}
	}

	private void makeGUI(final Layer layer, final Object[] props) {
		// gather properties
		final Point p;
		double mag = 1.0D;
		Rectangle srcRect = null;
		if (null != props) {
			p = (Point)props[0];
			mag = ((Double)props[1]).doubleValue();
			srcRect = (Rectangle)props[2];
		} else {
			p = null;
		}

		// transparency slider
		this.transp_slider = new JSlider(javax.swing.SwingConstants.HORIZONTAL, 0, 100, 100);
		this.transp_slider.setBackground(Color.white);
		this.transp_slider.setMinimumSize(new Dimension(250, 20));
		this.transp_slider.setMaximumSize(new Dimension(250, 20));
		this.transp_slider.setPreferredSize(new Dimension(250, 20));
		final TransparencySliderListener tsl = new TransparencySliderListener();
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
		this.panel_patches = new RollingPanel(this, Patch.class);
		this.addTab("Patches", panel_patches);

		// Tab 2: Profiles
		this.panel_profiles = new RollingPanel(this, Profile.class);
		this.addTab("Profiles", panel_profiles);

		// Tab 3: ZDisplayables
		this.panel_zdispl = new RollingPanel(this, ZDisplayable.class);
		this.addTab("Z space", panel_zdispl);

		// Tab 4: channels
		this.panel_channels = makeTabPanel();
		this.scroll_channels = makeScrollPane(panel_channels);
		this.channels = new Channel[4];
		this.channels[0] = new Channel(this, Channel.MONO);
		this.channels[1] = new Channel(this, Channel.RED);
		this.channels[2] = new Channel(this, Channel.GREEN);
		this.channels[3] = new Channel(this, Channel.BLUE);
		//this.panel_channels.add(this.channels[0]);
		addGBRow(this.panel_channels, this.channels[1], null);
		addGBRow(this.panel_channels, this.channels[2], this.channels[1]);
		addGBRow(this.panel_channels, this.channels[3], this.channels[2]);
		this.addTab("Opacity", scroll_channels);

		// Tab 5: labels
		this.panel_labels = new RollingPanel(this, DLabel.class);
		this.addTab("Labels", panel_labels);

		// Tab 6: layers
		this.panel_layers = makeTabPanel();
		this.scroll_layers = makeScrollPane(panel_layers);
		recreateLayerPanels(layer);
		this.scroll_layers.addMouseWheelListener(canvas);
		this.addTab("Layers", scroll_layers);

		// Tab 7: tool options
		this.tool_options = new OptionPanel(); // empty
		this.scroll_options = makeScrollPane(this.tool_options);
		this.scroll_options.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		this.addTab("Tool options", this.scroll_options);

		// Tab 8: annotations
		this.annot_editor = new JEditorPane();
		this.annot_editor.setEnabled(false); // by default, nothing is selected
		this.annot_editor.setMinimumSize(new Dimension(200, 50));
		this.annot_label = new JLabel("(No selected object)");
		this.annot_panel = makeAnnotationsPanel(this.annot_editor, this.annot_label);
		this.addTab("Annotations", this.annot_panel);

		// Tab 9: filter options
		this.filter_options = createFilterOptionPanel();
		this.scroll_filter_options = makeScrollPane(this.filter_options);
		this.scroll_filter_options.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		this.addTab("Live filter", this.scroll_filter_options);

		this.ht_tabs = new Hashtable<Class<?>,RollingPanel>();
		this.ht_tabs.put(Patch.class, panel_patches);
		this.ht_tabs.put(Profile.class, panel_profiles);
		this.ht_tabs.put(ZDisplayable.class, panel_zdispl);
		this.ht_tabs.put(AreaList.class, panel_zdispl);
		this.ht_tabs.put(Pipe.class, panel_zdispl);
		this.ht_tabs.put(Polyline.class, panel_zdispl);
		this.ht_tabs.put(Treeline.class, panel_zdispl);
		this.ht_tabs.put(AreaTree.class, panel_zdispl);
		this.ht_tabs.put(Connector.class, panel_zdispl);
		this.ht_tabs.put(Ball.class, panel_zdispl);
		this.ht_tabs.put(Dissector.class, panel_zdispl);
		this.ht_tabs.put(DLabel.class, panel_labels);
		this.ht_tabs.put(Stack.class, panel_zdispl);
		// channels not included
		// layers not included
		// tools not included
		// annotations not included

		// Navigator
		this.navigator = new DisplayNavigator(this, layer.getLayerWidth(), layer.getLayerHeight());
		// Layer scroller (to scroll slices)
		int extent = (int)(250.0 / layer.getParent().size());
		if (extent < 10) extent = 10;
		this.scroller = new JScrollBar(JScrollBar.HORIZONTAL);
		this.scroller.setModel(new ScrollerModel(layer));
		updateLayerScroller(layer);
		this.scroller.addAdjustmentListener(scroller_listener);

		// LAYOUT
		final GridBagLayout layout = new GridBagLayout();
		final GridBagConstraints c = new GridBagConstraints();
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		c.weighty = 0;
		c.gridx = 0;
		c.gridy = 0;
		c.ipadx = 0;
		c.ipady = 0;
		c.gridwidth = 1;
		c.gridheight = 1;

		Display.this.all = new JPanel();
		all.setBackground(Color.white);
		all.setLayout(layout);

		c.insets = new Insets(0, 0, 0, 5);

		// 1
		toolbar_panel = new ToolbarPanel(); // fixed dimensions
		layout.setConstraints(toolbar_panel, c);
		all.add(toolbar_panel);

		// 2
		c.gridy++;
		c.fill = GridBagConstraints.HORIZONTAL;
		layout.setConstraints(transp_slider, c);
		all.add(transp_slider);

		// 3
		c.gridy++;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		layout.setConstraints(tabs, c);
		all.add(tabs);

		// 4
		c.gridy++;
		c.weighty = 0;
		c.fill = GridBagConstraints.NONE;
		layout.setConstraints(navigator, c);
		all.add(navigator);

		// 5
		c.gridy++;
		c.fill = GridBagConstraints.HORIZONTAL;
		layout.setConstraints(scroller, c);
		all.add(scroller);

		// Canvas
		this.canvas = new DisplayCanvas(this, (int)Math.ceil(layer.getLayerWidth()), (int)Math.ceil(layer.getLayerHeight()));

		c.insets = new Insets(0, 0, 0, 0);
		c.fill = GridBagConstraints.BOTH;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.gridx = 1;
		c.gridy = 0;
		c.gridheight = GridBagConstraints.REMAINDER;
		c.weightx = 1;
		c.weighty = 1;
		layout.setConstraints(Display.this.canvas, c);
		all.add(canvas);

		// prevent new Displays from screwing up if input is globally disabled
		if (!project.isInputEnabled()) Display.this.canvas.setReceivesInput(false);

		this.canvas.addComponentListener(canvas_size_listener);
		this.navigator.addMouseWheelListener(canvas);
		this.transp_slider.addKeyListener(canvas);

		// JFrame to show the split pane
		this.frame = ControlWindow.createJFrame(layer.toString());
		this.frame.setBackground(Color.white);
		this.frame.getContentPane().setBackground(Color.white);
		if (IJ.isMacintosh() && IJ.getInstance()!=null) {
			IJ.wait(10); // may be needed for Java 1.4 on OS X
			this.frame.setMenuBar(ij.Menus.getMenuBar());
		}
		this.frame.addWindowListener(window_listener);
		//this.frame.addComponentListener(display_frame_listener);
		this.frame.getContentPane().add(all);

		if (null != props) {
			// restore canvas
			canvas.setup(mag, srcRect);
			// restore visibility of each channel
			final int cs = ((Integer)props[5]).intValue(); // aka c_alphas_state
			final int[] sel = new int[4];
			sel[0] = ((cs&0xff000000)>>24);
			sel[1] = ((cs&0xff0000)>>16);
			sel[2] = ((cs&0xff00)>>8);
			sel[3] =  (cs&0xff);
			// restore channel alphas
			Display.this.c_alphas = ((Integer)props[4]).intValue();
			channels[0].setAlpha( (float)((c_alphas&0xff000000)>>24) / 255.0f , 0 != sel[0]);
			channels[1].setAlpha( (float)((c_alphas&0xff0000)>>16) / 255.0f ,   0 != sel[1]);
			channels[2].setAlpha( (float)((c_alphas&0xff00)>>8) / 255.0f ,      0 != sel[2]);
			channels[3].setAlpha( (float) (c_alphas&0xff) / 255.0f ,            0 != sel[3]);
			// restore visibility in the working c_alphas
			Display.this.c_alphas = ((0 != sel[0] ? (int)(255 * channels[0].getAlpha()) : 0)<<24) + ((0 != sel[1] ? (int)(255 * channels[1].getAlpha()) : 0)<<16) + ((0 != sel[2] ? (int)(255 * channels[2].getAlpha()) : 0)<<8) + (0 != sel[3] ? (int)(255 * channels[3].getAlpha()) : 0);
		}

		if (null != active && null != layer) {
			final Rectangle r = active.getBoundingBox();
			r.x -= r.width/2;
			r.y -= r.height/2;
			r.width += r.width;
			r.height += r.height;
			if (r.x < 0) r.x = 0;
			if (r.y < 0) r.y = 0;
			if (r.width > layer.getLayerWidth()) r.width = (int)layer.getLayerWidth();
			if (r.height> layer.getLayerHeight())r.height= (int)layer.getLayerHeight();
			final double magn = layer.getLayerWidth() / (double)r.width;
			canvas.setup(magn, r);
		}

		// add keyListener to the whole frame
		this.tabs.addKeyListener(canvas);
		this.frame.addKeyListener(canvas);

		// create a drag and drop listener
		dnd = new DNDInsertImage(Display.this);

		Utils.invokeLater(new Runnable()  {
			@Override
            public void run() {
				Display.this.frame.pack();
				ij.gui.GUI.center(Display.this.frame);
				Display.this.frame.setVisible(true);
				ProjectToolbar.setProjectToolbar(); // doesn't get it through events

				final Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();

				if (null != props) {
					// fix positioning outside the screen (dual to single monitor)
					if (p.x >= 0 && p.x < screen.width - 50 && p.y >= 0 && p.y <= screen.height - 50) Display.this.frame.setLocation(p);
					else frame.setLocation(0, 0);
				}

				// fix excessive size
				final Rectangle box = Display.this.frame.getBounds();
				int x = box.x;
				int y = box.y;
				int width = box.width;
				int height = box.height;
				if (box.width > screen.width) { x = 0; width = screen.width; }
				if (box.height > screen.height) { y = 0; height = screen.height; }
				if (x != box.x || y != box.y) {
					Display.this.frame.setLocation(x, y + (0 == y ? 30 : 0)); // added insets for bad window managers
				}
				if (width != box.width || height != box.height) {
					Display.this.frame.setSize(new Dimension(width -10, height -30)); // added insets for bad window managers
				}
				if (null == props) {
					// try to optimize canvas dimensions and magn
					double magn = layer.getLayerHeight() / screen.height;
					if (magn > 1.0) magn = 1.0;
					long size = 0;
					// limit magnification if appropriate
					for (final Displayable pa : layer.getDisplayables(Patch.class)) {
						final Rectangle ba = pa.getBoundingBox();
						size += (long)(ba.width * ba.height);
					}
					if (size > 10000000) canvas.setInitialMagnification(0.25); // 10 Mb
					else {
						Display.this.frame.setSize(new Dimension((int)(screen.width * 0.66), (int)(screen.height * 0.66)));
					}
				}

				updateTab(panel_patches);

				// re-layout:
				tabs.validate();

				// Set the calibration of the FakeImagePlus to that of the LayerSet
				((FakeImagePlus)canvas.getFakeImagePlus()).setCalibrationSuper(layer.getParent().getCalibrationCopy());

				updateFrameTitle(layer);
				// Set the FakeImagePlus as the current image
				setTempCurrentImage();

				// start a repainting thread
				if (null != props) {
					canvas.repaint(true); // repaint() is unreliable
				}

				ControlWindow.setLookAndFeel();
		}});
	}

	static public void repaintToolbar() {
		for (final Display d : al_displays) {
			if (null == d.toolbar_panel) continue; // not yet ready
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				d.toolbar_panel.repaint();
			}});
		}
	}

	private class ToolbarPanel extends JPanel implements MouseListener {
		private static final long serialVersionUID = 1L;
		Method drawButton;
		Field lineType;
		Field SIZE;
		Field OFFSET;
		Toolbar toolbar = Toolbar.getInstance();
		int size;
		int scale = 1;
		//int offset;
		ToolbarPanel() {
			setBackground(Color.white);
			addMouseListener(this);
			try {
				drawButton = Toolbar.class.getDeclaredMethod("drawButton", Graphics.class, Integer.TYPE);
				drawButton.setAccessible(true);
				lineType = Toolbar.class.getDeclaredField("lineType");
				lineType.setAccessible(true);
				SIZE = Toolbar.class.getDeclaredField("buttonHeight");
				SIZE.setAccessible(true);
				OFFSET = Toolbar.class.getDeclaredField("OFFSET");
				OFFSET.setAccessible(true);
				size = ((Integer)SIZE.get(null)).intValue();
				//offset = ((Integer)OFFSET.get(null)).intValue();
				Field scaleField = Toolbar.class.getDeclaredField("scale");
				scaleField.setAccessible(true);
				scale = ((Integer)scaleField.get(null)).intValue();
			} catch (final Exception e) {
				IJError.print(e);
				scale = 1;
			}
			// Magic cocktail:
			final Dimension dim = new Dimension(9 * size, size+size); // 9 buttons per row
			setMinimumSize(dim);
			setPreferredSize(dim);
			setMaximumSize(dim);
		}
		@Override
        public void update(final Graphics g) { paint(g); }
		@Override
        public void paint(final Graphics gr) {
			try {
				// Either extend the heavy-weight Canvas, or use an image to paint to.
				// Otherwise, rearrangements of the layout while painting will result
				// in incorrectly positioned toolbar buttons.
				final BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
				final Graphics g = bi.getGraphics();
				// Use same hints as ij.gui.Toolbar 
				Graphics2D g2d = (Graphics2D)g;
				g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				// Set stroke as per ij.gui.Toolbar
				if (scale==1) {
					if (ij.Prefs.getGuiScale()>1.0)
						g2d.setStroke(new BasicStroke(IJ.isMacOSX()?1.4f:1.25f));
				} else {
					g2d.setStroke(new BasicStroke(scale));
				}
				g.setColor(Color.white);
				g.fillRect(0, 0, getWidth(), getHeight());
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
				final AffineTransform aff = new AffineTransform();
				aff.translate(-size*Toolbar.TEXT, size-1);
				((Graphics2D)g).setTransform(aff);
				for (; i<18; i++) {
					drawButton.invoke(toolbar, g, i);
				}
				gr.drawImage(bi, 0, 0, null);
				bi.flush();
				g.dispose();
			} catch (final Exception e) {
				IJError.print(e);
			}
		}
		/*
		// Fails: "origin not in parent's hierarchy" ... right.
		private void showPopup(String name, int x, int y) {
			try {
				Field f = Toolbar.getInstance().getClass().getDeclaredField(name);
				f.setAccessible(true);
				PopupMenu p = (PopupMenu) f.get(Toolbar.getInstance());
				p.show(this, x, y);
			} catch (Throwable t) {
				IJError.print(t);
			}
		}
		*/
		@Override
        public void mousePressed(final MouseEvent me) {
			int x = me.getX();
			int y = me.getY();
			if (y > size) {
				if (x > size * 7) return; // off limits
				x += size * 9;
				y -= size;
			} else {
				if (x > size * 9) return; // off limits
			}
			/*
			if (Utils.isPopupTrigger(me)) {
				if (x >= size && x <= size * 2 && y >= 0 && y <= size) {
					showPopup("ovalPopup", x, y);
					return;
				} else if (x >= size * 4 && x <= size * 5 && y >= 0 && y <= size) {
					showPopup("linePopup", x, y);
					return;
				}
			}
			*/
			Toolbar.getInstance().mousePressed(new MouseEvent(toolbar, me.getID(), System.currentTimeMillis(), me.getModifiers(), x, y, me.getClickCount(), me.isPopupTrigger()));
			repaint();
			Display.toolChanged(ProjectToolbar.getToolId()); // should fire on its own but it does not (?) TODO
		}
		@Override
        public void mouseReleased(final MouseEvent me) {}
		@Override
        public void mouseClicked(final MouseEvent me) {}
		@Override
        public void mouseEntered(final MouseEvent me) {}
		@Override
        public void mouseExited(final MouseEvent me) {}
	}

	private JPanel makeTabPanel() {
		final JPanel panel = new JPanel();
		panel.setLayout(new GridBagLayout());
		return panel;
	}

	private JScrollPane makeScrollPane(final Component c) {
		final JPanel p = new JPanel();
		final GridBagLayout gb = new GridBagLayout();
		p.setLayout(gb);
		//
		final GridBagConstraints co = new GridBagConstraints();
		co.anchor = GridBagConstraints.NORTHWEST;
		co.fill = GridBagConstraints.HORIZONTAL;
		co.gridy = 0;
		co.weighty = 0;
		gb.setConstraints(c, co);
		p.add(c);
		//
		final JPanel padding = new JPanel();
		padding.setPreferredSize(new Dimension(0,0));
		co.fill = GridBagConstraints.BOTH;
		co.gridy = 1;
		co.weighty = 1;
		gb.setConstraints(padding, co);
		p.add(padding);
		//
		final JScrollPane jsp = new JScrollPane(p);
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

	private void addGBRow(final Container container, final Component comp, final Component previous) {
		final GridBagLayout gb = (GridBagLayout) container.getLayout();
		GridBagConstraints c = null;
		if (null == previous) {
			c = new GridBagConstraints();
			c.anchor = GridBagConstraints.NORTHWEST;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.gridy = 0;
		} else {
			c = gb.getConstraints(previous);
			c.gridy += 1;
		}
		gb.setConstraints(comp, c);
		container.add(comp);
	}

	private JPanel makeAnnotationsPanel(final JEditorPane ep, final JLabel label) {
		final JPanel p = new JPanel();
		final GridBagLayout gb = new GridBagLayout();
		final GridBagConstraints c = new GridBagConstraints();
		p.setLayout(gb);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.ipadx = 5;
		c.ipady = 5;
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 0;
		c.weighty = 0;
		final JLabel title = new JLabel("Annotate:");
		gb.setConstraints(title, c);
		p.add(title);
		c.gridy++;
		gb.setConstraints(label, c);
		p.add(label);
		c.weighty = 1;
		c.gridy++;
		c.fill = GridBagConstraints.BOTH;
		c.ipadx = 0;
		c.ipady = 0;
		final JScrollPane sp = new JScrollPane(ep, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		sp.setPreferredSize(new Dimension(250, 300));
		sp.setMinimumSize(new Dimension(250, 300));
		gb.setConstraints(sp, c);
		p.add(sp);
		return p;
	}

	public DisplayCanvas getCanvas() {
		return canvas;
	}

	public synchronized void setLayer(final Layer new_layer) {
		setLayer(new_layer, false);
	}

	private synchronized void setLayer(final Layer new_layer, final boolean bypass_checks) {
		if (!bypass_checks) {
			if (null == new_layer || new_layer == this.layer || new_layer.getParent() != this.layer.getParent()) return;
		}

		final Layer current_layer = this.layer;

		if (!mode.canChangeLayer()) {
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				((ScrollerModel)scroller.getModel()).setValueWithoutEvent(current_layer.getParent().indexOf(current_layer));
			}});
			return;
		}

		// Set:
		this.layer = new_layer;

		// deselect all except ZDisplayables
		final ArrayList<Displayable> sel = selection.getSelected();
		final Displayable last_active = this.active;
		int sel_next = -1;
		for (final Iterator<Displayable> it = sel.iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (!(d instanceof ZDisplayable)) {
				it.remove();
				selection.remove(d);
				if (d == last_active && sel.size() > 0) {
					// set as active (by selecting it) the last one of the remaining, if any
					sel_next = sel.size()-1;
				}
			}
		}
		if (-1 != sel_next && sel.size() > 0) select(sel.get(sel_next), true);

		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			translateLayerColors(current_layer, new_layer);
			if (tabs.getSelectedComponent() == scroll_layers) {
				scrollToShow(scroll_layers, layer_panels.get(new_layer));
			}
			// Below, will fire the event as well, and call stl.set(layer) which calls setLayer with the same layer, and returns.
			// But just scroller.getModel().setValue(int) will ALSO fire the event. So let it do the loop.
			final int index = Display.this.layer.getParent().indexOf(new_layer);
			if (scroller.getValue() != index) {
				((ScrollerModel)scroller.getModel()).setValueWithoutEvent(index);
			}

			/* // OBSOLETE
			// update the current Layer pointer in ZDisplayable objects
			for (Iterator it = layer.getParent().getZDisplayables().iterator(); it.hasNext(); ) {
				((ZDisplayable)it.next()).setLayer(layer); // the active layer
			}
			 */

			if (tabs.getSelectedComponent() == panel_zdispl) {
				// no need, doesn't change
			} else {
				updateVisibleDisplayableTab();
			}

			updateFrameTitle(new_layer); // to show the new 'z'
			// select the Layer in the LayerTree
			project.select(new_layer); // does so in a separate thread
			// update active Displayable:

			// trigger repaints
			navigator.repaint(true);
			canvas.repaint(true);

			// repaint tabs (hard as hell)
			Utils.updateComponent(tabs);
			// @#$%^! The above works half the times, so explicit repaint as well:
			Component c = tabs.getSelectedComponent();
			if (null == c) {
				c = panel_patches;
				tabs.setSelectedComponent(panel_patches);
			}
			Utils.updateComponent(c);

			// update the coloring in the ProjectTree
			project.getProjectTree().updateUILater();

			setTempCurrentImage();
		}});
	}

	static public void updateVisibleTabs() {
		for (final Display d : al_displays) {
			d.updateVisibleDisplayableTab();
		}
	}
	static public void updateVisibleTabs(final Project p) {
		for (final Display d : al_displays) {
			if (d.project == p) d.updateVisibleDisplayableTab();
		}
	}

	/** Recreate the tab that is being shown. */
	private void updateVisibleDisplayableTab() {
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			final Component c = tabs.getSelectedComponent();
			if (c instanceof RollingPanel) {
				((RollingPanel)c).updateList();
			}
		}});
	}

	private void setLayerLater(final Layer layer, final Displayable active) {
		if (null == layer) return;
		this.layer = layer;
		if (!ControlWindow.isGUIEnabled()) return;
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			navigator.repaint(true); // was not done when adding
			// Order matters: first set active
			setActive(active);
			final Container c = (Container)tabs.getSelectedComponent();
			if (c != panel_zdispl) updateTab(c);
		}});
	}

	/** A class to listen to the transparency_slider of the DisplayablesSelectorWindow. */
	private class TransparencySliderListener extends MouseAdapter implements ChangeListener {

		@Override
        public void stateChanged(final ChangeEvent ce) {
			//change the transparency value of the current active displayable
			final float new_value = (float)((JSlider)ce.getSource()).getValue();
			setTransparency(new_value / 100.0f);
		}

		@Override
        public void mouseReleased(final MouseEvent me) {
			// update navigator window
			navigator.repaint(true);
		}
	}

	/** Context-sensitive: to a Displayable, or to a channel. */
	private void setTransparency(final float value) {
		final Component scroll = tabs.getSelectedComponent();
		if (scroll == scroll_channels) {
			for (int i=0; i<4; i++) {
				if (channels[i].getBackground() == Color.cyan) {
					channels[i].setAlpha(value); // will call back and repaint the Display
					return;
				}
			}
		} else if (null != active) {
			if (value != active.getAlpha()) { // because there's a callback from setActive that would then affect all other selected Displayable without having dragged the slider, i.e. just by being selected.
				selection.setAlpha(value);
				Display.repaint(active.getLayerSet(), active, active.getBoundingBox(), 5, false);
			}
		}
	}

	public void setTransparencySlider(final float transp) {
		if (transp >= 0.0f && transp <= 1.0f) {
			// fire event
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				transp_slider.setValue((int)(transp * 100));
			}});
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
	public void setUpdateGraphics(final boolean b) {
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
			d.updateVisibleDisplayableTab();
			d.toolbar_panel.repaint();
			d.navigator.repaint(true);
			d.canvas.repaint(true);
		}
	}

	/** Find all Display instances that contain the layer and repaint them, in the Swing GUI thread. */
	static public void update(final Layer layer) {
		if (null == layer) return;
		for (final Display d : al_displays) {
			if (d.isShowing(layer)) {
				d.repaintAll();
			}
		}
	}

	static public void update(final LayerSet set) {
		update(set, true);
	}

	/** Find all Display instances showing a Layer of this LayerSet, and update the dimensions of the navigator and canvas and snapshots, and repaint, in the Swing GUI thread. */
	static public void update(final LayerSet set, final boolean update_canvas_dimensions) {
		if (null == set) return;
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
				d.updateSnapshots();
				if (update_canvas_dimensions) d.canvas.setDimensions(set.getLayerWidth(), set.getLayerHeight());
				d.repaintAll();
			}
		}
	}

	/** Release all resources held by this Display and close the frame. */
	protected void destroy() {
		// Set a new front if any and remove from the list of open Displays
		removeDisplay(this);
		// Inactivate this Display:
		dispatcher.quit();
		canvas.setReceivesInput(false);
		slt.quit();
		imagePreloader.interrupt();

		// update the coloring in the ProjectTree and LayerTree
		if (!project.isBeingDestroyed()) {
			try {
				project.getProjectTree().updateUILater();
				project.getLayerTree().updateUILater();
			} catch (final Exception e) {
				Utils.log2("updateUI failed at Display.destroy()");
			}
		}

		//frame.removeComponentListener(component_listener);
		frame.removeWindowListener(window_listener);
		frame.removeWindowFocusListener(window_listener);
		frame.removeWindowStateListener(window_listener);
		frame.removeKeyListener(canvas);
		//frame.removeMouseListener(frame_mouse_listener);
		canvas.removeKeyListener(canvas);
		tabs.removeChangeListener(tabs_listener);
		tabs.removeKeyListener(canvas);
		IJ.removeEventListener(this);
		bytypelistener = null;
		canvas.destroy();
		navigator.destroy();
		scroller.removeAdjustmentListener(scroller_listener);
		frame.setVisible(false);
		//no need, and throws exception//frame.dispose();
		active = null;
		if (null != selection) selection.clear();

		// repaint layer tree (to update the label color)
		try {
			project.getLayerTree().updateUILater(); // works only after setting the front above
		} catch (final Exception e) {} // ignore swing sync bullshit when closing everything too fast
		// remove the drag and drop listener
		dnd.destroy();
	}

	/** Find all Display instances that contain a Layer of the given project and close them without removing the Display entries from the database. */
	static synchronized public void close(final Project project) {
		final Display[] d = new Display[al_displays.size()];
		al_displays.toArray(d);
		for (int i=0; i<d.length; i++) {
			if (d[i].getProject() == project) {
				removeDisplay(d[i]);
				d[i].destroy();
			}
		}
	}

	/** Find all Display instances that contain the layer and close them and remove the Display from the database. */
	static public void close(final Layer layer) {
		for (final Display d : al_displays) {
			if (d.isShowing(layer)) {
				d.remove(false); // calls destroy which calls removeDisplay
			}
		}
	}

	/** Find all Display instances that are showing the layer and either move to the next or previous layer, or close it if none. */
	static public void remove(final Layer layer) {
		for (final Display d : al_displays) {
			if (d.isShowing(layer)) {
				Layer la = layer.getParent().next(layer);
				if (layer == la || null == la) la = layer.getParent().previous(layer);
				if (null == la || layer == la) {
					d.remove(false); // will call destroy which calls removeDisplay
				} else {
					d.slt.set(la);
				}
			}
		}
	}

	/** Close this Display window. */
	@Override
    public boolean remove(final boolean check) {
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

	/** Repaint the canvas updating graphics, and the navigator without updating graphics. */
	public void repaintAll3() {
		if (repaint_disabled) return;
		navigator.repaint(false);
		canvas.repaint(true);
		updateFrameTitle();
	}

	static protected void repaintSnapshots(final LayerSet set) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (d.getLayer().getParent() == set) {
				d.navigator.repaint(true);
				Utils.updateComponent(d.tabs);
			}
		}
	}
	static protected void repaintSnapshots(final Layer layer) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (d.getLayer() == layer) {
				d.navigator.repaint(true);
				Utils.updateComponent(d.tabs);
			}
		}
	}

	public void pack() {
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			frame.pack();
			navigator.repaint(false); // update srcRect red frame position/size
		}});
	}

	static public void pack(final LayerSet ls) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == ls) d.pack();
		}
	}

	/** Grab the last selected display (or create an new one if none) and show in it the layer,centered on the Displayable object. */
	static public void setFront(final Layer layer, final Displayable displ) {
		if (null == front) {
			final Display display = new Display(layer.getProject(), layer); // gets set to front
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
	static protected void add(final Layer layer, final Displayable displ, final boolean activate) {
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

	static protected void add(final Layer layer, final Displayable displ) {
		add(layer, displ, true);
	}

	/** Add the ZDisplayable to all Displays that show a Layer belonging to the given LayerSet. */
	static protected void add(final LayerSet set, final ZDisplayable zdispl) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
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

	static protected void addAll(final Layer layer, final Collection<? extends Displayable> coll) {
		for (final Display d : al_displays) {
			if (d.layer == layer) {
				d.addAll(coll);
			}
		}
	}

	static protected void addAll(final LayerSet set, final Collection<? extends ZDisplayable> coll) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
				for (final ZDisplayable zd : coll) {
					if (front == d) zd.setLayer(d.layer); // this is obsolete now, TODO
				}
				d.addAll(coll);
			}
		}
	}

	private final void addAll(final Collection<? extends Displayable> coll) {
		// if any of the elements in the collection matches the type of the current tab, update that tab
		// ... it's easier to just update the front tab
		updateVisibleDisplayableTab();
		selection.clear();
		navigator.repaint(true);
	}

	/** Add it to the proper panel, at the top, and set it active. */
	private final void add(final Displayable d, final boolean activate, final boolean repaint_snapshot) {
		if (activate) {
			updateVisibleDisplayableTab();
			selection.clear();
			selection.add(d);
			Display.repaint(d.getLayerSet()); // update the al_top list to contain the active one, or background image for a new Patch.
			Utils.log2("Added " + d);
		}
		if (repaint_snapshot) navigator.repaint(true);
	}

	/** Find the displays that show the given Layer, and remove the given Displayable from the GUI. */
	static public void remove(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (layer == d.layer) d.remove(displ);
		}
	}

	private void remove(final Displayable displ) {
		final DisplayablePanel ob = ht_panels.remove(displ);
		if (null != ob) {
			final RollingPanel rp = ht_tabs.get(displ.getClass());
			if (null != rp) {
				Utils.invokeLater(new Runnable() { @Override
                public void run() {
					rp.updateList();
				}});
			}
		}
		canvas.setUpdateGraphics(true);
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

	/** Find the displays that show the given Layer, and repaint the given Displayable; does NOT update graphics for the offscreen image. */
	static public void repaint(final Layer layer, final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator) {
		repaint(layer, displ, r, extra, false, repaint_navigator);
	}

	/**
	 * @param layer   The layer to repaint
	 * @param r       The Rectangle to repaint, in world coordinates (aka pixel coordinates or canvas coordinates).
	 * @param extra   The number of pixels to pad @param r with.
	 * @param update_graphics Whether to recreate the offscreen image of the canvas, which is necessary for images.
	 * @param repaint_navigator Whether to repaint the navigator.
	 */
	static public void repaint(final Layer layer, final Displayable displ, final Rectangle r, final int extra, final boolean update_graphics, final boolean repaint_navigator) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				d.repaint(displ, r, extra, repaint_navigator, update_graphics);
			}
		}
	}

	static public void repaint(final Displayable d) {
		if (d instanceof ZDisplayable) repaint(d.getLayerSet(), d, d.getBoundingBox(null), 5, true);
		repaint(d.getLayer(), d, d.getBoundingBox(null), 5, true);
	}

	/** Repaint as much as the bounding box around the given Displayable, or the r if not null.
	 *  @param update_graphics will be made true if the @param displ is a Patch or it's not the active Displayable. */
	private void repaint(final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator, boolean update_graphics) {
		if (repaint_disabled || null == displ) return;
		update_graphics = (update_graphics || displ.getClass() == Patch.class || displ != active);
		if (null != r) canvas.repaint(r, extra, update_graphics);
		else canvas.repaint(displ, extra, update_graphics);
		if (repaint_navigator) {
			final DisplayablePanel dp = ht_panels.get(displ);
			if (null != dp) {
				Utils.invokeLater(new Runnable() { @Override
                public void run() {
					dp.repaint(); // is null when creating it, or after deleting it
				}});
			}
			navigator.repaint(true); // everything
		}
	}

	/** Repaint the snapshot for the given Displayable both at the DisplayNavigator and on its panel,and only if it has not been painted before. This method is intended for the loader to know when to paint a snap, to avoid overhead. */
	static public void repaintSnapshot(final Displayable displ) {
		for (final Display d : al_displays) {
			if (d.layer.contains(displ)) {
				if (!d.navigator.isPainted(displ)) {
					final DisplayablePanel dp = d.ht_panels.get(displ);
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
				final DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.repaint();
				d.navigator.repaint(true);
			}
		}
	}

	static public void repaint(final LayerSet set, final Displayable displ, final int extra) {
		repaint(set, displ, null, extra);
	}

	static public void repaint(final LayerSet set, final Displayable displ, final Rectangle r, final int extra) {
		repaint(set, displ, r, extra, true);
	}

	/** Repaint the Displayable in every Display that shows a Layer belonging to the given LayerSet. */
	static public void repaint(final LayerSet set, final Displayable displ, final Rectangle r, final int extra, final boolean repaint_navigator) {
		if (repaint_disabled) return;
		if (null == set) return;
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
				if (repaint_navigator) {
					if (null != displ) {
						final DisplayablePanel dp = d.ht_panels.get(displ);
						if (null != dp) dp.repaint();
					}
					d.navigator.repaint(true);
				}
				if (null == displ || displ != d.active || displ instanceof ImageData) d.setUpdateGraphics(true); // safeguard
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
			if (d.layer.getParent() == set) {
				d.navigator.repaint(true);
				d.canvas.repaint(true);
			}
		}
	}
	/** Repaint the given box in the LayerSet, in all Displays showing a Layer of it.*/
	static public void repaint(final LayerSet set, final Rectangle box) {
		if (repaint_disabled) return;
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
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
	static protected void setRepaint(final boolean b) {
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

	/** Feel free to add more tabs. Don't remove any of the existing tabs or the sky will fall on your head. */
	public JTabbedPane getTabbedPane() {
		return tabs;
	}

	/** Returns the tab index in this Display's JTabbedPane. */
	public int addTab(final String title, final Component comp) {
		this.tabs.add(title, comp);
		return this.tabs.getTabCount() -1;
	}

	public void setLocation(final Point p) {
		this.frame.setLocation(p);
	}

	public Displayable getActive() {
		return active; //TODO this should return selection.active !!
	}

	public void select(final Displayable d) {
		select(d, false);
	}

	/** Select/deselect accordingly to the current state and the shift key. */
	public void select(final Displayable d, final boolean shift_down) {
		if (null != active && active != d && active.getClass() != Patch.class) {
			// active is being deselected, so link underlying patches
			final String prop = active.getClass() == DLabel.class ? project.getProperty("label_nolinks")
									      : project.getProperty("segmentations_nolinks");
			HashSet<Displayable> glinked = null;
			if (null != prop && prop.equals("true")) {
				// do nothing: linking disabled for active's type
			} else if (active.linkPatches()) {
				// Locking state changed:
				glinked = active.getLinkedGroup(null);
				Display.updateCheckboxes(glinked, DisplayablePanel.LOCK_STATE, true);
			}
			// Update link icons:
			Display.updateCheckboxes(null == glinked ? active.getLinkedGroup(null) : glinked, DisplayablePanel.LINK_STATE);
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
				//Utils.log2("Display.select: activating within a selection");
				selection.setActive(d);
			}
		} else {
			//Utils.log2("Display.select: adding to an existing selection");
			selection.add(d);
		}
		// update the image shown to ImageJ
		// NO longer necessary, always he same FakeImagePlus // setTempCurrentImage();
	}

	protected void choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p, final Class<?> c) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, c);
	}
	protected void choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p) {
		choose(screen_x_p, screen_y_p, x_p, y_p, false, null);
	}

	/** Find a Displayable to add to the selection under the given point (which is in offscreen coords); will use a popup menu to give the user a range of Displayable objects to select from. */
	protected void choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p, final boolean shift_down, final Class<?> c) {
		//Utils.log("Display.choose: x,y " + x_p + "," + y_p);
		final ArrayList<Displayable> al = new ArrayList<Displayable>(layer.find(x_p, y_p, true));
		al.addAll(layer.getParent().findZDisplayables(layer, x_p, y_p, true)); // only visible ones
		if (al.isEmpty()) {
			final Displayable act = this.active;
			selection.clear();
			canvas.setUpdateGraphics(true);
			//Utils.log("choose: set active to null");
			// fixing lack of repainting for unknown reasons, of the active one TODO this is a temporary solution
			if (null != act) Display.repaint(layer, act, 5);
		} else if (1 == al.size()) {
			final Displayable d = (Displayable)al.get(0);
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
					for (final Iterator<?> it = al.iterator(); it.hasNext(); ) {
						final Object ob = it.next();
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

	private void choose(final int screen_x_p, final int screen_y_p, final Collection<Displayable> al, final boolean shift_down, final int x_p, final int y_p) {
		// show a popup on the canvas to choose
		new Thread() {
			{ setPriority(Thread.NORM_PRIORITY); }
			@Override
            public void run() {
				final Object lock = new Object();
				final DisplayableChooser d_chooser = new DisplayableChooser(al, lock);
				final JPopupMenu pop = new JPopupMenu("Select:");
				for (final Displayable d : al) {
					final JMenuItem menu_item = new JMenuItem(d.toString());
					menu_item.addActionListener(d_chooser);
					pop.add(menu_item);
				}

				SwingUtilities.invokeLater(new Runnable() { @Override
                public void run() {
					pop.show(canvas, screen_x_p, screen_y_p);
				}});

				//now wait until selecting something
				synchronized(lock) {
					do {
						try {
							lock.wait();
						} catch (final InterruptedException ie) {}
					} while (d_chooser.isWaiting() && pop.isShowing());
				}

				//grab the chosen Displayable object
				final Displayable d = d_chooser.getChosen();
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
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			if (null != displ && displ == prev_active && tabs.getSelectedComponent() != annot_panel) {
				// make sure the proper tab is selected.
				selectTab(displ);
				return; // the same
			}
			// deactivate previously active
			if (null != prev_active) {
				// erase "decorations" of the previously active
				canvas.repaint(selection.getBox(), 4);
				// Adjust annotation doc
				synchronized (annot_docs) {
					boolean remove_doc = true;
					for (final Display d : al_displays) {
						if (prev_active == d.active) {
							remove_doc = false;
							break;
						}
					}
					if (remove_doc) annot_docs.remove(prev_active);
				}
			}
			// activate the new active
			if (null != displ) {
				updateInDatabase("active_displayable_id");
				if (displ.getClass() != Patch.class) project.select(displ); // select the node in the corresponding tree, if any.
				// select the proper tab, and scroll to visible
				if (tabs.getSelectedComponent() != annot_panel) { // don't swap tab if its the annotation one
					selectTab(displ);
				}
				final boolean update_graphics = null == prev_active || paintsBelow(prev_active, displ); // or if it's an image, but that's by default in the repaint method
				repaint(displ, null, 5, false, update_graphics); // to show the border, and to repaint out of the background image
				transp_slider.setValue((int)(displ.getAlpha() * 100));
				// Adjust annotation tab:
				synchronized (annot_docs) {
					annot_label.setText(displ.toString());
					Document doc = annot_docs.get(displ); // could be open in another Display
					if (null == doc) {
						doc = annot_editor.getEditorKit().createDefaultDocument();
						doc.addDocumentListener(new DocumentListener() {
							@Override
                            public void changedUpdate(final DocumentEvent e) {}
							@Override
                            public void insertUpdate(final DocumentEvent e) { push(); }
							@Override
                            public void removeUpdate(final DocumentEvent e) { push(); }
							private void push() {
								displ.setAnnotation(annot_editor.getText());
							}
						});
						annot_docs.put(displ, doc);
					}
					annot_editor.setDocument(doc);
					if (null != displ.getAnnotation()) annot_editor.setText(displ.getAnnotation());
				}
				annot_editor.setEnabled(true);
			} else {
				//ensure decorations are removed from the panels, for Displayables in a selection besides the active one
				Utils.updateComponent(tabs.getSelectedComponent());
				annot_label.setText("(No selected object)");
				annot_editor.setDocument(annot_editor.getEditorKit().createDefaultDocument()); // a clear, empty one
				annot_editor.setEnabled(false);
			}
		}});
	}

	/** If the other paints under the base. */
	public boolean paintsBelow(final Displayable base, final Displayable other) {
		final boolean zd_base = base instanceof ZDisplayable;
		final boolean zd_other = other instanceof ZDisplayable;
		if (zd_other) {
			if (base instanceof DLabel) return true; // zd paints under label
			if (!zd_base) return false; // any zd paints over a mere displ if not a label
			else {
				// both zd, compare indices
				final ArrayList<ZDisplayable> al = other.getLayerSet().getZDisplayables();
				return al.indexOf(base) > al.indexOf(other);
			}
		} else {
			if (!zd_base) {
				// both displ, compare indices
				final ArrayList<Displayable> al = other.getLayer().getDisplayables();
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
		} catch (final Exception e) {
			IJError.print(e);
		}
		if (null != method) {
			final Method me = method;
			dispatcher.exec(new Runnable() { @Override
            public void run() {
				try {
					me.setAccessible(true);
					me.invoke(Display.this, new Object[]{displ});
				} catch (final Exception e) { IJError.print(e); }
			}});
		}
	}

	// Methods used by reflection:

	@SuppressWarnings("unused")
	private void selectTab(final Patch patch) {
		tabs.setSelectedComponent(panel_patches);
		panel_patches.scrollToShow(patch);
	}

	@SuppressWarnings("unused")
	private void selectTab(final Profile profile) {
		tabs.setSelectedComponent(panel_profiles);
		panel_profiles.scrollToShow(profile);
	}

	@SuppressWarnings("unused")
	private void selectTab(final DLabel label) {
		tabs.setSelectedComponent(panel_labels);
		panel_labels.scrollToShow(label);
	}

	private void selectTab(final ZDisplayable zd) {
		tabs.setSelectedComponent(panel_zdispl);
		panel_zdispl.scrollToShow(zd);
	}

	@SuppressWarnings("unused")
	private void selectTab(final Pipe d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Polyline d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Treeline d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final AreaTree d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Connector d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final AreaList d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Ball d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Dissector d) { selectTab((ZDisplayable)d); }
	@SuppressWarnings("unused")
	private void selectTab(final Stack d) { selectTab((ZDisplayable)d); }

	/** Must be invoked in the event dispatch thread. */
	private void updateTab(final Container tab) {
		if (tab instanceof RollingPanel) {
			((RollingPanel)tab).updateList();
		}
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

	/** Check whether the source of the event is located in this instance.*/
	private boolean isOrigin(final InputEvent event) {
		final Object source = event.getSource();
		// find it ... check the canvas for now TODO
		if (canvas == source) {
			return true;
		}
		return false;
	}

	/** Get the layer of the front Display, or null if none.*/
	static public Layer getFrontLayer() {
		final Display d = front;
		if (null == d) return null;
		return d.layer;
	}

	/** Get the layer of an open Display of the given Project, or null if none.*/
	static public Layer getFrontLayer(final Project project) {
		final Display df = front;
		if (null == df) return null;
		if (df.project == project) return df.layer;

		// else, find an open Display for the given Project, if any
		for (final Display d : al_displays) {
			if (d.project == project) {
				d.frame.toFront();
				return d.layer;
			}
		}
		return null; // none found
	}

	/** Get a pointer to a Display for @param project, or null if none. */
	static public Display getFront(final Project project) {
		final Display df = front;
		if (null == df) return null;
		if (df.project == project) return df;
		for (final Display d : al_displays) {
			if (d.project == project) {
				d.frame.toFront();
				return d;
			}
		}
		return null;
	}

	/** Return the list of selected Displayable objects of the front Display, or an emtpy list if no Display or none selected. */
	static public List<Displayable> getSelected() {
		final Display d = front;
		if (null == d) return new ArrayList<Displayable>();
		return d.selection.getSelected();
	}
	/** Return the list of selected Displayable objects of class @param c of the front Display, or an emtpy list if no Display or none selected. */
	static public List<? extends Displayable> getSelected(final Class<? extends Displayable> c) {
		final Display d = front;
		if (null == d) return new ArrayList<Displayable>();
		return d.selection.getSelected(c);
	}

	public boolean isReadOnly() {
		// TEMPORARY: in the future one will be able show displays as read-only to other people, remotely
		return false;
	}

	protected void showPopup(final Component c, final int x, final int y) {
		final Display d = front;
		if (null == d) return;
		d.getPopupMenu().show(c, x, y);
	}

	/** Return a context-sensitive popup menu. */
	protected JPopupMenu getPopupMenu() { // called from canvas
		// get the job canceling dialog
		if (!canvas.isInputEnabled()) {
			return project.getLoader().getJobsPopup(this);
		}

		// create new
		this.popup = new JPopupMenu();
		JMenuItem item = null;
		JMenu menu = null;

		if (mode instanceof InspectPatchTrianglesMode) {
			item = new JMenuItem("Exit inspection"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			return popup;
		} else if (canvas.isTransforming()) {
			item = new JMenuItem("Apply transform"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true)); // dummy, for I don't add a MenuKeyListener, but "works" through the normal key listener. It's here to provide a visual cue
			item = new JMenuItem("Apply transform propagating to last layer"); item.addActionListener(this); popup.add(item);
			if (layer.getParent().indexOf(layer) == layer.getParent().size() -1) item.setEnabled(false);
			if (!(getMode().getClass() == AffineTransformMode.class || getMode().getClass() == NonLinearTransformMode.class)) item.setEnabled(false);
			item = new JMenuItem("Apply transform propagating to first layer"); item.addActionListener(this); popup.add(item);
			if (0 == layer.getParent().indexOf(layer)) item.setEnabled(false);
			if (!(getMode().getClass() == AffineTransformMode.class || getMode().getClass() == NonLinearTransformMode.class)) item.setEnabled(false);
			item = new JMenuItem("Cancel transform"); item.addActionListener(this); popup.add(item);
			item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
			item = new JMenuItem("Specify transform..."); item.addActionListener(this); popup.add(item);
			if (getMode().getClass() != AffineTransformMode.class) item.setEnabled(false);
			if (getMode().getClass() == ManualAlignMode.class) {
				final JMenuItem lexport = new JMenuItem("Export landmarks"); popup.add(lexport);
				final JMenuItem limport = new JMenuItem("Import landmarks"); popup.add(limport);
				final ActionListener a = new ActionListener() {
					@Override
                    public void actionPerformed(final ActionEvent ae) {
						final ManualAlignMode mam = (ManualAlignMode)getMode();
						final Object source = ae.getSource();
						if (lexport == source) {
							mam.exportLandmarks();
						} else if (limport == source) {
							mam.importLandmarks();
						}
					}
				};
				lexport.addActionListener(a);
				limport.addActionListener(a);
			}
			return popup;
		}

		final Class<?> aclass = null == active ? null : active.getClass();

		if (null != active) {
			if (Profile.class == aclass) {
				item = new JMenuItem("Duplicate, link and send to next layer"); item.addActionListener(this); popup.add(item);
				Layer nl = layer.getParent().next(layer);
				if (nl == layer) item.setEnabled(false);
				item = new JMenuItem("Duplicate, link and send to previous layer"); item.addActionListener(this); popup.add(item);
				nl = layer.getParent().previous(layer);
				if (nl == layer) item.setEnabled(false);

				menu = new JMenu("Duplicate, link and send to");
				int i = 1;
				for (final Layer la : layer.getParent().getLayers()) {
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
			} else if (Patch.class == aclass) {
				final JMenu m = new JMenu("Patch");
				item = new JMenuItem("Fill ROI in alpha mask"); item.addActionListener(this); m.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0));
				item.setEnabled(null != getRoi());
				item = new JMenuItem("Fill inverse ROI in alpha mask"); item.addActionListener(this); m.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, Event.SHIFT_MASK));
				item.setEnabled(null != getRoi());
				item = new JMenuItem("Remove alpha mask"); item.addActionListener(this); m.add(item);
				if ( ! ((Patch)active).hasAlphaMask()) item.setEnabled(false);
				item = new JMenuItem("Unlink from images"); item.addActionListener(this); m.add(item);
				if (!active.isLinked(Patch.class)) item.setEnabled(false);
				if (((Patch)active).isStack()) {
					item = new JMenuItem("Unlink slices"); item.addActionListener(this); m.add(item);
				}
				final int n_sel_patches = selection.getSelected(Patch.class).size();
				item = new JMenuItem("Snap"); item.addActionListener(this); m.add(item);
				item.setEnabled(1 == n_sel_patches);
				item = new JMenuItem("Montage"); item.addActionListener(this); m.add(item);
				item.setEnabled(n_sel_patches > 1);
				item = new JMenuItem("Lens correction"); item.addActionListener(this); m.add(item);
				item.setEnabled(n_sel_patches > 1);
				item = new JMenuItem("Blend"); item.addActionListener(this); m.add(item);
				item.setEnabled(n_sel_patches > 1);
				item = new JMenuItem("Open image"); item.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(final ActionEvent e) {
						for (final Patch p : selection.get(Patch.class)) {
							p.getImagePlus().show();
						}
					}
				}); m.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.SHIFT_MASK, true));
				item = new JMenuItem("Open original image"); item.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(final ActionEvent e) {
						for (final Patch p : selection.get(Patch.class)) {
							p.getProject().getLoader().releaseToFit(p.getOWidth(), p.getOHeight(), p.getType(), 5);
							p.getProject().getLoader().openImagePlus(p.getImageFilePath()).show();
						}
					}
				});
				item = new JMenuItem("View volume"); item.addActionListener(this); m.add(item);
				final HashSet<Displayable> hs = active.getLinked(Patch.class);
				if (null == hs || 0 == hs.size()) item.setEnabled(false);
				item = new JMenuItem("View orthoslices"); item.addActionListener(this); m.add(item);
				if (null == hs || 0 == hs.size()) item.setEnabled(false); // if no Patch instances among the directly linked, then it's not a stack
				popup.add(m);
				popup.addSeparator();
			} else {
				item = new JMenuItem("Unlink"); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Show in 3D"); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			}

			if (AreaList.class == aclass) {
				final ArrayList<?> al = selection.getSelected();
				int n = 0;
				for (final Iterator<?> it = al.iterator(); it.hasNext(); ) {
					if (it.next().getClass() == AreaList.class) n++;
				}
				
				item = new JMenuItem("Merge"); item.addActionListener(this); popup.add(item);
				if (n < 2) item.setEnabled(false);
				
				item = new JMenuItem("Split"); item.addActionListener(this); popup.add(item);
				if (n < 1) item.setEnabled(false);

				addAreaListAreasMenu(popup, active);
				
				popup.addSeparator();
			} else if (Pipe.class == aclass) {
				item = new JMenuItem("Reverse point order"); item.addActionListener(this); popup.add(item);
				popup.addSeparator();
			} else if (Treeline.class == aclass || AreaTree.class == aclass) {
				if (AreaTree.class == aclass) addAreaTreeAreasMenu(popup, (AreaTree)active);
				item = new JMenuItem("Reroot"); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Part subtree"); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Join"); item.addActionListener(this); popup.add(item);
				item = new JMenuItem("Show tabular view"); item.addActionListener(this); popup.add(item);
				final Collection<Tree> trees = selection.get(Tree.class);

				//
				final JMenu nodeMenu = new JMenu("Nodes");
				item = new JMenuItem("Mark"); item.addActionListener(this); nodeMenu.add(item);
				item = new JMenuItem("Clear marks (selected Trees)"); item.addActionListener(this); nodeMenu.add(item);
				final JMenuItem nodeColor = new JMenuItem("Color..."); nodeMenu.add(nodeColor);
				nodeColor.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.SHIFT_MASK, true));
				final JMenuItem nodePairColor = new JMenuItem("Color path between two nodes tagged as..."); nodeMenu.add(nodePairColor);
				final JMenuItem nodeRadius = active instanceof Treeline ? new JMenuItem("Radius...") : null;
				if (null != nodeRadius) {
					nodeMenu.add(nodeRadius);
					nodeRadius.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, 0, true));
				}
				final JMenuItem removeAllTags = new JMenuItem("Drop all tags (selected trees)"); nodeMenu.add(removeAllTags);
				final JMenuItem removeTag = new JMenuItem("Drop all occurrences of tag..."); nodeMenu.add(removeTag);
				final JMenuItem colorizeByNodeCentrality = new JMenuItem("Colorize by node betweenness centrality"); nodeMenu.add(colorizeByNodeCentrality);
				final JMenuItem colorizeByBranchCentrality = new JMenuItem("Colorize by branch betweenness centrality"); nodeMenu.add(colorizeByBranchCentrality);

				popup.add(nodeMenu);
				final ActionListener ln = new ActionListener() {
					@Override
					public void actionPerformed(final ActionEvent ae) {
						if (null == active) {
							Utils.showMessage("No tree selected!");
							return;
						}
						if (!(active instanceof Tree)) {
							Utils.showMessage("The selected object is not a Tree!");
							return;
						}
						final Tree tree = (Tree)active;
						final Object src = ae.getSource();
						//
						if (src == nodeColor) {
							final Node nd = tree.getLastVisited();
							if (null == nd) {
								Utils.showMessage("Select a node first by clicking on it\nor moving the mouse over it and pushing 'g'.");
								return;
							}
							tree.adjustNodeColors(nd); // sets an undo step
						} else if (src == nodePairColor) {
							final TreeMap<String,Tag> sm = getTags(tree);
							if (null == sm) return;
							if (1 == sm.size()) {
								Utils.showMessage("Need at least two different tags in the tree!");
								return;
							}
							final Color color = tree.getColor();
							final GenericDialog gd = new GenericDialog("Node colors");
							gd.addSlider("Red: ", 0, 255, color.getRed());
							gd.addSlider("Green: ", 0, 255, color.getGreen());
							gd.addSlider("Blue: ", 0, 255, color.getBlue());
							final String[] stags = asStrings(sm);
							sm.keySet().toArray(stags);
							gd.addChoice("Upstream tag:", stags, stags[0]);
							gd.addChoice("Downstream tag:", stags, stags[1]);
							gd.showDialog();
							if (gd.wasCanceled()) return;
							final Color newColor = new Color((int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
							final Tag upstreamTag = sm.get(gd.getNextChoice());
							final Tag downstreamTag = sm.get(gd.getNextChoice());
							final List<Tree<?>.NodePath> pairs = tree.findTaggedPairs(upstreamTag, downstreamTag);
							if (null == pairs || pairs.isEmpty()) {
								Utils.showMessage("No pairs found for '" + upstreamTag + "' and '" + downstreamTag + "'");
								return;
							}
							getLayerSet().addDataEditStep(tree);
							for (final Tree<?>.NodePath pair : pairs) {
								for (final Node<?> nd : pair.path) {
									nd.setColor(newColor);
								}
							}
							getLayerSet().addDataEditStep(tree);
							Display.repaint();
						} else if (src == nodeRadius) {
							if (!(tree instanceof Treeline)) return;
							final Node nd = tree.getLastVisited();
							if (null == nd) {
								Utils.showMessage("Select a node first by clicking on it\nor moving the mouse over it and pushing 'g'.");
								return;
							}
							((Treeline)tree).askAdjustRadius(nd); // sets an undo step
						} else if (src == removeAllTags) {
							if (!Utils.check("Really remove all tags from all selected trees?")) return;
							final List<Tree> sel = selection.get(Tree.class);
							getLayerSet().addDataEditStep(new HashSet<Displayable>(sel));
							try {
								for (final Tree t : sel) {
									t.dropAllTags();
								}
								getLayerSet().addDataEditStep(new HashSet<Displayable>(sel)); // current state
							} catch (final Exception e) {
								getLayerSet().undoOneStep();
								IJError.print(e);
							}
							Display.repaint();
						} else if (src == removeTag) {
							final TreeMap<String,Tag> tags = getTags(tree);
							final String[] ts = asStrings(tags);
							final GenericDialog gd = new GenericDialog("Remove tags");
							gd.addChoice("Tag:", ts, ts[0]);
							final String[] c = new String[]{"Active tree", "All selected trees and connectors", "All trees and connectors"};
							gd.addChoice("From: ", c, c[0]);
							gd.showDialog();
							if (gd.wasCanceled()) return;
							final HashSet<Displayable> ds = new HashSet<Displayable>();
							final Tag tag = tags.get(gd.getNextChoice());
							switch (gd.getNextChoiceIndex()) {
								case 0: ds.add(tree); break;
								case 1: ds.addAll(selection.get(Tree.class));
								case 2: ds.addAll(getLayerSet().getZDisplayables(Tree.class, true));
							}
							getLayerSet().addDataEditStep(ds);
							try {
								for (final Displayable d : ds) {
									final Tree t = (Tree)d;
									t.removeTag(tag);
								}
								getLayerSet().addDataEditStep(ds);
							} catch (final Exception e) {
								getLayerSet().undoOneStep();
								IJError.print(e);
							}
							Display.repaint();
						} else if (src == colorizeByNodeCentrality) {
							final List<Tree> ts = selection.get(Tree.class);
							final HashSet<Tree> ds = new HashSet<Tree>(ts);
							getLayerSet().addDataEditStep(ds);
							try {
								for (final Tree t : ts) {
									t.colorizeByNodeBetweennessCentrality();
								}
								getLayerSet().addDataEditStep(ds);
								Display.repaint();
							} catch (final Exception e) {
								getLayerSet().undoOneStep();
								IJError.print(e);
							}
						} else if (src == colorizeByBranchCentrality) {
							final List<Tree> ts = selection.get(Tree.class);
							final HashSet<Tree> ds = new HashSet<Tree>(ts);
							getLayerSet().addDataEditStep(ds);
							try {
								for (final Tree t : ts) {
									t.colorizeByBranchBetweennessCentrality(2);
								}
								getLayerSet().addDataEditStep(ds);
								Display.repaint();
							} catch (final Exception e) {
								getLayerSet().undoOneStep();
								IJError.print(e);
							}
						}
					}
				};
				for (final JMenuItem a : new JMenuItem[]{nodeColor, nodePairColor, nodeRadius,
						removeAllTags, removeTag, colorizeByNodeCentrality, colorizeByBranchCentrality}) {
					if (null == a) continue;
					a.addActionListener(ln);
				}

				//
				final JMenu review = new JMenu("Review");
				final JMenuItem tgenerate = new JMenuItem("Generate review stacks (selected Trees)"); review.add(tgenerate);
				tgenerate.setEnabled(trees.size() > 0);
				final JMenuItem tslab = new JMenuItem("Generate review stack for current slab"); review.add(tslab);
				final JMenuItem tsubtree = new JMenuItem("Generate review stacks for subtree"); review.add(tsubtree);
				final JMenuItem tremove = new JMenuItem("Remove reviews (selected Trees)"); review.add(tremove);
				tremove.setEnabled(trees.size() > 0);
				final JMenuItem tconnectors = new JMenuItem("View table of outgoing/incoming connectors"); review.add(tconnectors);
				final ActionListener l = new ActionListener() {
					@Override
                    public void actionPerformed(final ActionEvent ae) {
						if (!Utils.check("Really " + ae.getActionCommand())) {
							return;
						}
						dispatcher.exec(new Runnable() {
							@Override
                            public void run() {
								int count = 0;
								for (final Tree<?> t : trees) {
									Utils.log("Processing " + (++count) + "/" + trees.size());
									Bureaucrat bu = null;
									if (ae.getSource() == tgenerate) bu = t.generateAllReviewStacks();
									else if (ae.getSource() == tremove) bu = t.removeReviews();
									else if (ae.getSource() == tslab) {
										final Point po = canvas.consumeLastPopupPoint();
										Utils.log2(po, layer, 1.0);
										bu = t.generateReviewStackForSlab(po.x, po.y, Display.this.layer, 1.0);
									} else if (ae.getSource() == tsubtree) {
										final Point po = canvas.consumeLastPopupPoint();
										bu = t.generateSubtreeReviewStacks(po.x, po.y, Display.this.layer, 1.0);
									}
									if (null != bu) try {
										bu.getWorker().join();
									} catch (final InterruptedException ie) { return; }
								}
							}
						});
					}
				};
				for (final JMenuItem c : new JMenuItem[]{tgenerate, tslab, tsubtree, tremove}) c.addActionListener(l);
				tconnectors.addActionListener(new ActionListener() {
					@Override
                    public void actionPerformed(final ActionEvent ae) {
						for (final Tree<?> t : trees) TreeConnectorsView.create(t);
					}
				});
				popup.add(review);

				final JMenu go = new JMenu("Go");
				item = new JMenuItem("Previous branch node or start"); item.addActionListener(this); go.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0, true));
				item = new JMenuItem("Next branch node or end"); item.addActionListener(this); go.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0, true));
				item = new JMenuItem("Root"); item.addActionListener(this); go.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0, true));
				go.addSeparator();
				item = new JMenuItem("Last added node"); item.addActionListener(this); go.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, 0, true));
				item = new JMenuItem("Last edited node"); item.addActionListener(this); go.add(item);
				item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, 0, true));
				popup.add(go);

				final JMenu tmeasure = new JMenu("Measure");
				final JMenuItem dist_to_root = new JMenuItem("Distance from this node to root"); tmeasure.add(dist_to_root);
				final JMenuItem dist_to_tag = new JMenuItem("Distance from this node to all nodes tagged as..."); tmeasure.add(dist_to_tag);
				final JMenuItem dist_to_mark = new JMenuItem("Distance from this node to the marked node"); tmeasure.add(dist_to_mark);
				final JMenuItem dist_pairs = new JMenuItem("Shortest distances between all pairs of nodes tagged as..."); tmeasure.add(dist_pairs);
				final ActionListener tma = getTreePathMeasureListener((Tree<?>)active);
				for (final JMenuItem mi : new JMenuItem[]{dist_to_root, dist_to_tag, dist_to_mark, dist_pairs}) {
					mi.addActionListener(tma);
				}
				popup.add(tmeasure);

				final String[] name = new String[]{AreaTree.class.getSimpleName(), Treeline.class.getSimpleName()};
				if (Treeline.class == aclass) {
					final String a = name[0];
					name[0] = name[1];
					name[1] = a;
				}
				item = new JMenuItem("Duplicate " + name[0] + " as " + name[1]);
				item.addActionListener(new ActionListener() {
					@Override
                    public void actionPerformed(final ActionEvent e) {
						Bureaucrat.createAndStart(new Worker.Task("Converting") {
							@Override
                            public void exec() {
								try {
									getLayerSet().addChangeTreesStep();
									final Map<Tree<?>,Tree<?>> m = Tree.duplicateAs(selection.getSelected(), (Class<Tree<?>>)(Treeline.class == aclass ? AreaTree.class : Treeline.class));
									if (m.isEmpty()) {
										getLayerSet().removeLastUndoStep();
									} else {
										getLayerSet().addChangeTreesStep();
									}
								} catch (final Exception e) {
									IJError.print(e);
								}
							}
						}, getProject());
					}
				});
				popup.add(item);
				popup.addSeparator();
			} else if (Connector.class == aclass) {
				item = new JMenuItem("Merge"); item.addActionListener(new ActionListener() {
					@Override
                    public void actionPerformed(final ActionEvent ae) {
						if (null == getActive() || getActive().getClass() != Connector.class) {
							Utils.log("Active object must be a Connector!");
							return;
						}
						final List<Connector> col = selection.get(Connector.class);
						if (col.size() < 2) {
							Utils.log("Select more than one Connector!");
							return;
						}
						if (col.get(0) != getActive()) {
							if (col.remove(getActive())) {
								col.add(0, (Connector)getActive());
							} else {
								Utils.log("ERROR: cannot find active object in selection list!");
								return;
							}
						}
						Bureaucrat.createAndStart(new Worker.Task("Merging connectors") {
							@Override
                            public void exec() {
								getLayerSet().addChangeTreesStep();
								Connector base = null;
								try {
									base = Connector.merge(col);
								} catch (final Exception e) {
									IJError.print(e);
								}
								if (null == base) {
									Utils.log("ERROR: could not merge connectors!");
									getLayerSet().undoOneStep();
								} else {
									getLayerSet().addChangeTreesStep();
								}
								Display.repaint();
							}
						}, getProject());
					}
				});
				popup.add(item);
				item.setEnabled(selection.getSelected(Connector.class).size() > 1);
				popup.addSeparator();
			}

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
			final LayerSet ls = layer.getParent();
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
				if (Patch.class == aclass) {
					if (!active.isOnlyLinkedTo(Patch.class)) {
						item.setEnabled(false);
					}
				}
			} catch (final Exception e) { IJError.print(e); item.setEnabled(false); }

			if (Patch.class == aclass) {
				item = new JMenuItem("Revert"); item.addActionListener(this); popup.add(item);
				if ( null == ((Patch)active).getOriginalPath()) item.setEnabled(false);
				popup.addSeparator();
			}
			item = new JMenuItem("Properties...");    item.addActionListener(this); popup.add(item);
			item = new JMenuItem("Show centered"); item.addActionListener(this); popup.add(item);

			popup.addSeparator();

			if (! (active instanceof ZDisplayable)) {
				final int i_layer = layer.getParent().indexOf(layer);
				final int n_layers = layer.getParent().size();
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
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Event.SHIFT_MASK | Utils.getControlModifier(), true));
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
			none = ! layer.getParent().contains(Treeline.class);
			item = new JMenuItem("Hide all treelines"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all treelines"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			none = ! layer.getParent().contains(AreaTree.class);
			item = new JMenuItem("Hide all areatrees"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all areatrees"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			none = ! layer.getParent().contains(Ball.class);
			item = new JMenuItem("Hide all balls"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all balls"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			none = ! layer.getParent().contains(Connector.class);
			item = new JMenuItem("Hide all connectors"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all connectors"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			none = ! layer.getParent().containsDisplayable(Patch.class);
			item = new JMenuItem("Hide all images"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Unhide all images"); item.addActionListener(this); menu.add(item);
			if (none) item.setEnabled(false);
			item = new JMenuItem("Hide all but images"); item.addActionListener(this); menu.add(item);
			item = new JMenuItem("Unhide all"); item.addActionListener(this); menu.add(item); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, Event.ALT_MASK, true));

			popup.add(menu);
		} catch (final Exception e) { IJError.print(e); }

		// plugins, if any
		Utils.addPlugIns(popup, "Display", project, new Callable<Displayable>() { @Override
        public Displayable call() { return Display.this.getActive(); }});

		final JMenu align_menu = new JMenu("Align");
		item = new JMenuItem("Align stack slices"); item.addActionListener(this); align_menu.add(item);
		if (selection.isEmpty() || ! (getActive().getClass() == Patch.class && ((Patch)getActive()).isStack())) item.setEnabled(false);
		item = new JMenuItem("Align layers"); item.addActionListener(this); align_menu.add(item);
		if (1 == layer.getParent().size()) item.setEnabled(false);
		item = new JMenuItem("Align layers manually with landmarks"); item.addActionListener(this); align_menu.add(item);
		if (1 == layer.getParent().size()) item.setEnabled(false);
		item = new JMenuItem("Align multi-layer mosaic"); item.addActionListener(this); align_menu.add(item);
		if (1 == layer.getParent().size()) item.setEnabled(false);
		item = new JMenuItem("Montage all images in this layer"); item.addActionListener(this); align_menu.add(item);
		if (layer.getDisplayables(Patch.class).size() < 2) item.setEnabled(false);
		item = new JMenuItem("Montage selected images"); item.addActionListener(this); align_menu.add(item);
		if (selection.getSelected(Patch.class).size() < 2) item.setEnabled(false);
		item = new JMenuItem("Montage multiple layers"); item.addActionListener(this); align_menu.add(item);
		popup.add(align_menu);

		final JMenuItem st = new JMenu("Transform");
		final StartTransformMenuListener tml = new StartTransformMenuListener();
		item = new JMenuItem("Transform (affine)"); item.addActionListener(tml); st.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, 0, true));
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Transform (non-linear)"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, Event.SHIFT_MASK, true));
		item = new JMenuItem("Cancel transform"); st.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));
		item.setEnabled(false); // just added as a self-documenting cue; no listener
		item = new JMenuItem("Remove rotation, scaling and shear (selected images)"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Remove rotation, scaling and shear layer-wise"); item.addActionListener(tml); st.add(item);
		item = new JMenuItem("Remove coordinate transforms (selected images)"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Remove coordinate transforms layer-wise"); item.addActionListener(tml); st.add(item);
		item = new JMenuItem("Adjust mesh resolution (selected images)"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Adjust mesh resolution layer-wise"); item.addActionListener(tml); st.add(item);
		item = new JMenuItem("Set coordinate transform of selected image to other selected images"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Set coordinate transform of selected image layer-wise"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Set affine transform of selected image to other selected images"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		item = new JMenuItem("Set affine transform of selected image layer-wise"); item.addActionListener(tml); st.add(item);
		if (null == active) item.setEnabled(false);
		popup.add(st);

		final JMenu link_menu = new JMenu("Link");
		item = new JMenuItem("Link images..."); item.addActionListener(this); link_menu.add(item);
		item = new JMenuItem("Unlink all selected images"); item.addActionListener(this); link_menu.add(item);
		item.setEnabled(selection.getSelected(Patch.class).size() > 0);
		item = new JMenuItem("Unlink all"); item.addActionListener(this); link_menu.add(item);
		popup.add(link_menu);

		final JMenu adjust_menu = new JMenu("Adjust images");
		item = new JMenuItem("Enhance contrast layer-wise..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Enhance contrast (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Adjust image filters (selected images)"); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Set Min and Max layer-wise..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Set Min and Max (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Adjust min and max (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_J, 0));
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Mask image borders (layer-wise)..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Mask image borders (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Remove alpha masks (layer-wise)..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Remove alpha masks (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Split images under polyline ROI"); item.addActionListener(this); adjust_menu.add(item);
		final Roi roi = canvas.getFakeImagePlus().getRoi();
		if (null == roi || !(roi.getType() == Roi.POLYLINE || roi.getType() == Roi.FREELINE)) item.setEnabled(false);
		item = new JMenuItem("Blend (layer-wise)..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Blend (selected images)..."); item.addActionListener(this); adjust_menu.add(item);
		if (selection.isEmpty()) item.setEnabled(false);
		item = new JMenuItem("Match intensities (layer-wise)..."); item.addActionListener(this); adjust_menu.add(item);
		item = new JMenuItem("Remove intensity maps (layer-wise)..."); item.addActionListener(this); adjust_menu.add(item);
        popup.add(adjust_menu);

		final JMenu script = new JMenu("Script");
		final MenuScriptListener msl = new MenuScriptListener();
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
		item = new JMenuItem("Import stack with landmarks..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import grid..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import sequence as grid..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import from text file..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Import labels as arealists..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Tags ..."); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Export");
		final boolean has_arealists = layer.getParent().contains(AreaList.class);
		item = new JMenuItem("Make flat image..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Arealists as labels (tif)"); item.addActionListener(this); menu.add(item);
		item.setEnabled(has_arealists);
		item = new JMenuItem("Arealists as labels (amira)"); item.addActionListener(this); menu.add(item);
		item.setEnabled(has_arealists);
		item = new JMenuItem("Image stack under selected Arealist"); item.addActionListener(this); menu.add(item);
		item.setEnabled(null != active && AreaList.class == active.getClass());
		item = new JMenuItem("Fly through selected Treeline/AreaTree"); item.addActionListener(this); menu.add(item);
		item.setEnabled(null != active && Tree.class.isInstance(active));
		item = new JMenuItem("Tags..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Connectivity graph..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("NeuroML..."); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Display");
		item = new JMenuItem("Resize canvas/LayerSet...");   item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Autoresize canvas/LayerSet");  item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Resize canvas/LayerSet to ROI"); item.addActionListener(this); menu.add(item);
		item.setEnabled(null != canvas.getFakeImagePlus().getRoi());
		item = new JMenuItem("Properties ..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Calibration..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Grid overlay..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Adjust snapping parameters..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Adjust fast-marching parameters..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Adjust arealist paint parameters..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Show current 2D position in 3D"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Show layers as orthoslices in 3D"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Inspect image mesh triangles"); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Project");
		this.project.getLoader().setupMenuItems(menu, this.getProject());
		item = new JMenuItem("Project properties..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Create subproject"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Create sibling project with retiled layers"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Release memory..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Flush image cache"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Regenerate all mipmaps"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Regenerate mipmaps (selected images)"); item.addActionListener(this); menu.add(item);
		menu.addSeparator();
		item = new JMenuItem("Measurement options..."); item.addActionListener(this); menu.add(item);
		popup.add(menu);

		menu = new JMenu("Selection");
		item = new JMenuItem("Select all"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Select all visible"); item.addActionListener(this); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, Utils.getControlModifier(), true));
		if (0 == layer.getDisplayableList().size() && 0 == layer.getParent().getDisplayableList().size()) item.setEnabled(false);
		item = new JMenuItem("Select all that match..."); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Select none"); item.addActionListener(this); menu.add(item);
		if (0 == selection.getNSelected()) item.setEnabled(false);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0, true));

		final JMenu bytype = new JMenu("Select all by type");
		item = new JMenuItem("AreaList"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("AreaTree"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Ball"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Connector"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Dissector"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Image"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Pipe"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Polyline"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Profile"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Text"); item.addActionListener(bytypelistener); bytype.add(item);
		item = new JMenuItem("Treeline"); item.addActionListener(bytypelistener); bytype.add(item);
		menu.add(bytype);

		item = new JMenuItem("Restore selection"); item.addActionListener(this); menu.add(item);
		item = new JMenuItem("Select under ROI"); item.addActionListener(this); menu.add(item);
		if (canvas.getFakeImagePlus().getRoi() == null) item.setEnabled(false);
		final JMenu graph = new JMenu("Graph");
		final GraphMenuListener gl = new GraphMenuListener();
		item = new JMenuItem("Select outgoing Connectors"); item.addActionListener(gl); graph.add(item);
		item = new JMenuItem("Select incoming Connectors"); item.addActionListener(gl); graph.add(item);
		item = new JMenuItem("Select downstream targets"); item.addActionListener(gl); graph.add(item);
		item = new JMenuItem("Select upstream targets"); item.addActionListener(gl); graph.add(item);
		graph.setEnabled(!selection.isEmpty());
		menu.add(graph);

		item = new JMenuItem("Measure"); item.addActionListener(this); menu.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0, true));
		item.setEnabled(!selection.isEmpty());

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

		popup.add(menu);

		item = new JMenuItem("Search..."); item.addActionListener(this); popup.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, Utils.getControlModifier(), true));

		//canvas.add(popup);
		return popup;
	}

	private void addAreaTreeAreasMenu(final JPopupMenu popup, final AreaTree atree) {
		final ActionListener listener = new ActionListener() {
			private final Node<?> findNearestNode() {
				final Layer la = getLayer();
				final Point p = canvas.consumeLastPopupPoint();
				final Node<?> lv = atree.getLastVisited();
				boolean use_last_visited = false;
				if (null != lv) {
					final float[] xy = new float[]{lv.x, lv.y};
					atree.getAffineTransform().transform(xy, 0, xy, 0, 1);
					use_last_visited = lv.getLayer() == la && canvas.getSrcRect().contains((int)xy[0], (int)xy[1]);
				}
				// Last visited node must be within the field of view in order to be used
				// if no node lays near the clicked point.
				return atree.findNodeNear(p.x, p.y, la, canvas, use_last_visited);
			}
			@Override
			public void actionPerformed(final ActionEvent ae) {
				final String command = ae.getActionCommand();
				final LayerSet ls = atree.getLayerSet();

				Bureaucrat.createAndStart(new Worker.Task(command) {
					@Override
					public void exec() {
						final Node<?> nd = findNearestNode();
						if (null == nd) {
							Utils.log("No node found in the field of view!");
							return;
						}
						if (command.equals("Copy area")) {
							final Area area = (Area) nd.getData();
							if (null == area) return;
							DisplayCanvas.setCopyBuffer(atree.getClass(), area.createTransformedArea(atree.getAffineTransform()));
						} else if (command.equals("Paste area")) {
							final Area wa = (Area) DisplayCanvas.getCopyBuffer(atree.getClass());
							if (null == wa) return;
							try {
								getLayerSet().addDataEditStep(atree);
								atree.addWorldAreaTo(nd, wa);
								atree.calculateBoundingBox(nd.getLayer());
								getLayerSet().addDataEditStep(atree);
							} catch (final Exception e) {
								IJError.print(e);
								getLayerSet().removeLastUndoStep();
							}
						} else if (command.equals("Interpolate gaps towards parent (node-centric)")) {
							interpolate(nd, true);
						} else if (command.equals("Interpolate gaps towards parent (absolute)")) {
							interpolate(nd, false);
						} else if (command.equals("Interpolate all gaps")) {
							final GenericDialog gd = new GenericDialog("Interpolate");
							final String[] a = new String[]{"node-centric", "absolute"};
							gd.addChoice("Mode", a, a[0]);
							gd.addCheckbox("Always use distance map", project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map));
							final String[] b = new String[]{"All selected AreaTrees", "Active AreaTree"};
							gd.addChoice("Process", b, b[0]);
							gd.showDialog();
							if (gd.wasCanceled()) return;
							final boolean node_centric = 0 == gd.getNextChoiceIndex();
							final boolean use_distance_map = gd.getNextBoolean();
							final boolean all = 0 == gd.getNextChoiceIndex();
							final Set<Displayable> s = new HashSet<Displayable>();
							if (all) s.addAll(selection.get(AreaTree.class));
							else s.add(atree);
							// Store current state for undo
							ls.addDataEditStep(s);
							try {
								for (final Displayable d : s) {
									((AreaTree)d).interpolateAllGaps(node_centric, use_distance_map);
								}
								ls.addDataEditStep(s);
							} catch (final Exception e) {
								IJError.print(e);
								ls.undoOneStep();
							}
							Display.repaint();
						}
					}
					private final void interpolate(final Node<?> nd, final boolean node_centric) {
						if (null == nd.getDataCopy() || ((Area)nd.getData()).isEmpty()) {
							Utils.log("Can't interpolate: node lacks an area!");
							return;
						}
						ls.addDataEditStep(atree);
						try {
							if (atree.interpolateTowardsParent((AreaTree.AreaNode)nd, node_centric, project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map))) {
								ls.addDataEditStep(atree);
							} else {
								Utils.log("Nothing to interpolate: the parent node already has an area.");
								ls.removeLastUndoStep();
							}
						} catch (final Exception e) {
							IJError.print(e);
							ls.undoOneStep();
						}
						Display.repaint();
					}
				}, atree.getProject());
			}
		};

		final JMenu interpolate = new JMenu("Areas");
		JMenuItem item = new JMenuItem("Interpolate gaps towards parent (node-centric)"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Interpolate gaps towards parent (absolute)"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Interpolate all gaps"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Area interpolation options..."); item.addActionListener(Display.this); interpolate.add(item);
		interpolate.addSeparator();
		item = new JMenuItem("Copy area"); item.addActionListener(listener); interpolate.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0, true));
		item = new JMenuItem("Paste area"); item.addActionListener(listener); interpolate.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0, true));
		item.setEnabled(null != DisplayCanvas.getCopyBuffer(active.getClass()));
		popup.add(interpolate);
	}

	private void addAreaListAreasMenu(final JPopupMenu popup2, final Displayable active) {
		final ActionListener listener = new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent ae) {
				final String command = ae.getActionCommand();
				Bureaucrat.createAndStart(new Worker.Task(command) {
					@Override
                    public void exec() {
						if (command.equals("Copy area")) {
							if (null == active || !(active instanceof AreaList)) return;
							final AreaList ali = (AreaList)active;
							final Area area = ali.getArea(getLayer());
							if (null == area) return;
							DisplayCanvas.setCopyBuffer(ali.getClass(), area.createTransformedArea(ali.getAffineTransform()));
						} else if (command.equals("Paste area")) {
							if (null == active || !(active instanceof AreaList)) return;
							final AreaList ali = (AreaList)active;
							final Area wa = (Area) DisplayCanvas.getCopyBuffer(ali.getClass());
							if (null == wa) return;
							try {
								getLayerSet().addDataEditStep(ali);
								ali.addArea(getLayer().getId(), wa.createTransformedArea(ali.getAffineTransform().createInverse()));
								ali.calculateBoundingBox(getLayer());
								getLayerSet().addDataEditStep(ali);
							} catch (final NoninvertibleTransformException e) {
								IJError.print(e);
								getLayerSet().undoOneStep();
							}
						} else if (command.equals("Interpolate gaps towards previous area")) {
							if (null == active || !(active instanceof AreaList)) return;
							final AreaList ali = (AreaList)active;
							// Is there an area in this layer?
							final Layer current = getLayer();
							if (null == ali.getArea(current)) return;
							// Find a layer before the current that has an area
							final LayerSet ls = getLayerSet();
							if (0 == ls.indexOf(current)) return; // already at first
							Layer previous = null;
							// Iterate layers towards the first layer
							for (final ListIterator<Layer> it = ls.getLayers().listIterator(ls.indexOf(current)); it.hasPrevious(); ) {
								final Layer la = it.previous();
								if (null != ali.getArea(la)) {
									previous = la;
									break;
								}
							}
							if (null == previous) return; // all empty
							try {
								ls.addDataEditStep(ali);
								ali.interpolate(previous, current, project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map));
								ls.addDataEditStep(ali);
							} catch (final Exception e) {
								IJError.print(e);
								ls.undoOneStep();
							}
						} else if (command.equals("Interpolate gaps towards next area")) {
							if (null == active || !(active instanceof AreaList)) return;
							final AreaList ali = (AreaList)active;
							// Is there an area in this layer?
							final Layer current = getLayer();
							if (null == ali.getArea(current)) return;
							// Find a layer after the current that has an area
							final LayerSet ls = getLayerSet();
							if (ls.size() -1 == ls.indexOf(current)) return; // already at the end
							Layer next = null;
							// Iterate towards the next layer
							for (final ListIterator<Layer> it = ls.getLayers().listIterator(ls.indexOf(current)+1); it.hasNext(); ) {
								final Layer la = it.next();
								if (null != ali.getArea(la)) {
									next = la;
									break;
								}
							}
							if (null == next) return; // all empty
							try {
								ls.addDataEditStep(ali);
								ali.interpolate(current, next, project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map));
								ls.addDataEditStep(ali);
							} catch (final Exception e) {
								IJError.print(e);
								ls.undoOneStep();
							}
						} else if (command.equals("Interpolate all gaps")) {
							if (null == active || !(active instanceof AreaList)) return;
							final AreaList ali = (AreaList)active;
							// find the first and last layers with areas
							Layer first = null;
							Layer last = null;
							final LayerSet ls = getLayerSet();
							final List<Layer> las = ls.getLayers();
							for (final Layer la : las) {
								if (null == first && null != ali.getArea(la)) {
									first = la;
									break;
								}
							}
							for (final ListIterator<Layer> it = las.listIterator(las.size()); it.hasPrevious(); ) {
								final Layer la = it.previous();
								if (null == last && null != ali.getArea(la)) {
									last = la;
									break;
								}
							}
							Utils.log2(first, last);
							if (null != first && first != last) {
								try {
									ls.addDataEditStep(ali);
									ali.interpolate(first, last, project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map));
									ls.addDataEditStep(ali);
								} catch (final Exception e) {
									IJError.print(e);
									ls.undoOneStep();
								}
							}
						}

						Display.repaint(getLayer());
					}
				}, active.getProject());
			}
		};

		final JMenu interpolate = new JMenu("Areas");
		JMenuItem item = new JMenuItem("Interpolate gaps towards previous area"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Interpolate gaps towards next area"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Interpolate all gaps"); item.addActionListener(listener); interpolate.add(item);
		item = new JMenuItem("Area interpolation options..."); item.addActionListener(Display.this); interpolate.add(item);
		interpolate.addSeparator();
		item = new JMenuItem("Copy area"); item.addActionListener(listener); interpolate.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0, true));
		item = new JMenuItem("Paste area"); item.addActionListener(listener); interpolate.add(item);
		item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0, true));
		item.setEnabled(null != DisplayCanvas.getCopyBuffer(active.getClass()));
		popup.add(interpolate);
	}

	static private final TreeMap<String,Tag> getTags(final Tree tree) {
		final Set<Tag> tags = tree.findTags();
		if (tags.isEmpty()) {
			Utils.log("The nodes of the tree '" + tree + "' don't have any tags!");
			return null;
		}
		final TreeMap<String,Tag> sm = new TreeMap<String,Tag>();
		for (final Tag t : tags) sm.put(t.toString(), t);
		return sm;
	}
	static private final String[] asStrings(final TreeMap<String,Tag> tags) {
		if (null == tags) return null;
		final String[] stags = new String[tags.size()];
		tags.keySet().toArray(stags);
		return stags;
	}

	private ActionListener getTreePathMeasureListener(final Tree tree) {
		return new ActionListener() {
			@Override
            public void actionPerformed(final ActionEvent ae) {
				final String command = ae.getActionCommand();
				if (command.equals("Shortest distances between all pairs of nodes tagged as...")) {
					final TreeMap<String,Tag> sm = getTags(tree);
					if (null == sm) return;
					if (1 == sm.size()) {
						Utils.showMessage("Need at least two different tags in the tree!");
						return;
					}
					final String[] stags = asStrings(sm);
					sm.keySet().toArray(stags);
					final GenericDialog gd = new GenericDialog("Choose tag");
					gd.addChoice("Upstream tag:", stags, stags[0]);
					gd.addChoice("Downstream tag:", stags, stags[1]);
					gd.addNumericField("Scale:", 1, 2);
					final LayerSet ls = tree.getLayerSet();
					final int resample = Display3D.estimateResamplingFactor(ls, ls.getLayerWidth(), ls.getLayerHeight());
					gd.addSlider("Resample: ", 1, Math.max(resample, 100), resample);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					final Tag upstreamTag = sm.get(gd.getNextChoice());
					final Tag downstreamTag = sm.get(gd.getNextChoice());
					final List<Tree<?>.MeasurementPair> pairs = tree.measureTaggedPairs(upstreamTag, downstreamTag);
					ResultsTable rt = null;
					int index = 1;
					for (final Tree<?>.MeasurementPair pair : pairs) {
						rt = pair.toResultsTable(rt, index++, 1.0, resample);
						Utils.showProgress(((double)index) / pairs.size());
					}
					if (index > 0) {
						rt.show(pairs.get(0).getResultsTableTitle());
					} else {
						Utils.logAll("No pairs found for '" + upstreamTag + "' and '" + downstreamTag + "'");
					}
					return;
				}
				// Measurements related to the node under the mouse
				final Point p = getCanvas().consumeLastPopupPoint();
				final Node clicked = tree.findClosestNodeW(p.x, p.y, getLayer(), canvas.getMagnification());
				if (null == clicked) {
					final Calibration cal = getLayerSet().getCalibration();
					Utils.log("No node found at " + p.x * cal.pixelWidth + ", " + p.y * cal.pixelHeight);
					return;
				}
				ResultsTable rt = null;
				if (command.equals("Distance from this node to root")) {
					rt = tree.measurePathDistance(clicked, tree.getRoot(), null);
				} else if (command.equals("Distance from this node to the marked node")) {
					if (null == tree.getMarked()) {
						Utils.log("No marked node!");
						return;
					}
					rt = tree.measurePathDistance(clicked, tree.getMarked(), null);
				} else if (command.equals("Distance from this node to all nodes tagged as...")) {
					final Set<Tag> tags = tree.findTags();
					if (tags.isEmpty()) {
						Utils.log("The nodes of the tree '" + tree + "' don't have any tags!");
						return;
					}
					final TreeMap<String,Tag> sm = new TreeMap<String,Tag>();
					for (final Tag t : tags) sm.put(t.toString(), t);
					final String[] stags = new String[sm.size()];
					sm.keySet().toArray(stags);
					final GenericDialog gd = new GenericDialog("Choose tag");
					gd.addChoice("Tag:", stags, stags[0]);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					// So we have a Tag:
					final Tag tag = sm.get(gd.getNextChoice());
					// Measure distance to each node that has the tag
					for (final Node nd : (Collection<Node>)tree.getRoot().getSubtreeNodes()) {
						if (nd.hasTag(tag)) {
							rt = tree.measurePathDistance(clicked, nd, rt);
						}
					}
				}
				if (null == rt) Utils.log("No nodes found!");
				else rt.show("Tree path measurements");
			}
		};
	}

	private final class GraphMenuListener implements ActionListener {
		@Override
        public void actionPerformed(final ActionEvent ae) {
			final String command = ae.getActionCommand();
			final Collection<Displayable> sel = selection.getSelected();
			if (null == sel || sel.isEmpty()) return;

			Bureaucrat.createAndStart(new Worker.Task(command) {
				@Override
                public void exec() {


			final Collection<Connector> connectors = (Collection<Connector>) (Collection) getLayerSet().getZDisplayables(Connector.class);
			final HashSet<Displayable> to_select = new HashSet<Displayable>();

			if (command.equals("Select outgoing Connectors")) {
				for (final Connector con : connectors) {
					final Set<Displayable> origins = con.getOrigins();
					origins.retainAll(sel);
					if (origins.isEmpty()) continue;
					to_select.add(con);
				}
			} else if (command.equals("Select incoming Connectors")) {
				for (final Connector con : connectors) {
					for (final Set<Displayable> targets : con.getTargets()) {
						targets.retainAll(sel);
						if (targets.isEmpty()) continue;
						to_select.add(con);
					}
				}
			} else if (command.equals("Select downstream targets")) {
				for (final Connector con : connectors) {
					final Set<Displayable> origins = con.getOrigins();
					origins.retainAll(sel);
					if (origins.isEmpty()) continue;
					// else, add all targets
					for (final Set<Displayable> targets : con.getTargets()) {
						to_select.addAll(targets);
					}
				}
			} else if (command.equals("Select upstream targets")) {
				for (final Connector con : connectors) {
					for (final Set<Displayable> targets : con.getTargets()) {
						targets.retainAll(sel);
						if (targets.isEmpty()) continue;
						to_select.addAll(con.getOrigins());
						break; // origins will be the same for all targets of 'con'
					}
				}
			}

			selection.selectAll(new ArrayList<Displayable>(to_select));

				}}, Display.this.project);
		}
	}

	protected class GridOverlay {
		ArrayList<Line2D> lines = new ArrayList<Line2D>();
		int ox=0, oy=0,
		    width=(int)layer.getLayerWidth(),
		    height=(int)layer.getLayerHeight(),
		    xoffset=0, yoffset=0,
		    tilewidth=100, tileheight=100,
		    linewidth=1;
		boolean visible = true;
		Color color = new Color(255,255,0,255); // yellow with full alpha

		/** Expects values in pixels. */
		void init() {
			lines.clear();
			// Vertical lines:
			if (0 != xoffset) {
				lines.add(new Line2D.Float(ox, oy, ox, oy+height));
			}
			lines.add(new Line2D.Float(ox+width, oy, ox+width, oy+height));
			for (int x = ox + xoffset; x <= ox + width; x += tilewidth) {
				lines.add(new Line2D.Float(x, oy, x, oy + height));
			}
			// Horizontal lines:
			if (0 != yoffset) {
				lines.add(new Line2D.Float(ox, oy, ox+width, oy));
			}
			lines.add(new Line2D.Float(ox, oy+height, ox+width, oy+height));
			for (int y = oy + yoffset; y <= oy + height; y += tileheight) {
				lines.add(new Line2D.Float(ox, y, ox + width, y));
			}
		}
		protected void paint(final Graphics2D g) {
			if (!visible) return;
			g.setStroke(new BasicStroke((float)(linewidth/canvas.getMagnification())));
			g.setColor(color);
			for (final Line2D line : lines) {
				g.draw(line);
			}
		}
		void setup(final Roi roi) {
			final GenericDialog gd = new GenericDialog("Grid overlay");
			final Calibration cal = getLayerSet().getCalibration();
			gd.addNumericField("Top-left corner X:", ox*cal.pixelWidth, 1, 10, cal.getUnits());
			gd.addNumericField("Top-left corner Y:", oy*cal.pixelHeight, 1, 10, cal.getUnits());
			gd.addNumericField("Grid total width:", width*cal.pixelWidth, 1, 10, cal.getUnits());
			gd.addNumericField("Grid total height:", height*cal.pixelHeight, 1, 10, cal.getUnits());
			gd.addCheckbox("Read bounds from ROI", null != roi);
			((Component)gd.getCheckboxes().get(0)).setEnabled(null != roi);
			gd.addMessage("");
			gd.addNumericField("Tile width:", tilewidth*cal.pixelWidth, 1, 10, cal.getUnits());
			gd.addNumericField("Tile height:", tileheight*cal.pixelHeight, 1, 10, cal.getUnits());
			gd.addNumericField("Tile offset X:", xoffset*cal.pixelWidth, 1, 10, cal.getUnits());
			gd.addNumericField("Tile offset Y:", yoffset*cal.pixelHeight, 1, 10, cal.getUnits());
			gd.addMessage("");
			gd.addNumericField("Line width:", linewidth, 1, 10, "pixels");
			gd.addSlider("Red: ", 0, 255, color.getRed());
			gd.addSlider("Green: ", 0, 255, color.getGreen());
			gd.addSlider("Blue: ", 0, 255, color.getBlue());
			gd.addSlider("Alpha: ", 0, 255, color.getAlpha());
			gd.addMessage("");
			gd.addCheckbox("Visible", visible);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			this.ox = (int)(gd.getNextNumber() / cal.pixelWidth);
			this.oy = (int)(gd.getNextNumber() / cal.pixelHeight);
			this.width = (int)(gd.getNextNumber() / cal.pixelWidth);
			this.height = (int)(gd.getNextNumber() / cal.pixelHeight);
			if (gd.getNextBoolean() && null != roi) {
				final Rectangle r = roi.getBounds();
				this.ox = r.x;
				this.oy = r.y;
				this.width = r.width;
				this.height = r.height;
			}
			this.tilewidth = (int)(gd.getNextNumber() / cal.pixelWidth);
			this.tileheight = (int)(gd.getNextNumber() / cal.pixelHeight);
			this.xoffset = (int)(gd.getNextNumber() / cal.pixelWidth) % tilewidth;
			this.yoffset = (int)(gd.getNextNumber() / cal.pixelHeight) % tileheight;
			this.linewidth = (int)gd.getNextNumber();
			this.color = new Color((int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
			this.visible = gd.getNextBoolean();
			init();
		}
	}

	protected GridOverlay gridoverlay = null;


	private class StartTransformMenuListener implements ActionListener {
		@Override
        public void actionPerformed(final ActionEvent ae) {
			final String command = ae.getActionCommand();
			if (command.equals("Transform (affine)")) {
				if (null == active) return;
				getLayerSet().addTransformStepWithData(selection.getAffected());
				setMode(new AffineTransformMode(Display.this));
			} else if (command.equals("Transform (non-linear)")) {
				if (null == active) return;
				getLayerSet().addTransformStepWithData(selection.getAffected());
				final List<Displayable> col = selection.getSelected(Patch.class);
				for (final Displayable d : col) {
					if (d.isLinked()) {
						Utils.showMessage("Can't enter manual non-linear transformation mode:\nat least one image is linked.");
						return;
					}
				}
				setMode(new NonLinearTransformMode(Display.this, col));
			} else if (command.equals("Remove coordinate transforms (selected images)")) {
				if (null == active) return;
				final List<Displayable> col = selection.getSelected(Patch.class);
				if (col.isEmpty()) return;
				removeCoordinateTransforms( (List<Patch>) (List) col);
			} else if (command.equals("Remove coordinate transforms layer-wise")) {
				final GenericDialog gd = new GenericDialog("Remove Coordinate Transforms");
				gd.addMessage("Remove coordinate transforms");
				gd.addMessage("for all images in:");
				Utils.addLayerRangeChoices(Display.this.layer, gd);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				final ArrayList<Displayable> patches = new ArrayList<Displayable>();
				for (final Layer layer : getLayerSet().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()+1)) {
					patches.addAll(layer.getDisplayables(Patch.class));
				}
				removeCoordinateTransforms( (List<Patch>) (List) patches);
			} else if (command.equals("Remove rotation, scaling and shear (selected images)")) {
				if (null == active) return;
				final List<Displayable> col = selection.getSelected(Patch.class);
				if (col.isEmpty()) return;
				removeScalingRotationShear( (List<Patch>) (List) col);
			} else if (command.equals("Remove rotation, scaling and shear layer-wise")) {
				// Because we love copy-paste
				final GenericDialog gd = new GenericDialog("Remove Scaling/Rotation/Shear");
				gd.addMessage("Remove scaling, translation");
				gd.addMessage("and shear for all images in:");
				Utils.addLayerRangeChoices(Display.this.layer, gd);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				final ArrayList<Displayable> patches = new ArrayList<Displayable>();
				for (final Layer layer : getLayerSet().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()+1)) {
					patches.addAll(layer.getDisplayables(Patch.class));
				}
				removeScalingRotationShear( (List<Patch>) (List) patches);
			} else if (command.equals("Adjust mesh resolution (selected images)")) {
				if (null == active) return;
				final List<Patch> col = selection.get(Patch.class);
				if (col.isEmpty()) return;
				final GenericDialog gd = new GenericDialog("Adjust mesh resolution");
				gd.addSlider("Mesh resolution:", 2, 512,
						((Patch)(active.getClass() == Patch.class ? active : col.get(0))).getMeshResolution());
				gd.showDialog();
				if (gd.wasCanceled()) return;
				setMeshResolution(col, (int)gd.getNextNumber());
			} else if (command.equals("Adjust mesh resolution layer-wise")) {
				final GenericDialog gd = new GenericDialog("Adjust mesh resolution");
				Utils.addLayerRangeChoices(Display.this.layer, gd);
				gd.addSlider("Mesh resolution:", 2, 512, project.getProperty("mesh_resolution", 32));
				gd.showDialog();
				if (gd.wasCanceled()) return;
				final ArrayList<Patch> patches = new ArrayList<Patch>();
				for (final Layer layer : getLayerSet().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()+1)) {
					patches.addAll(layer.getAll(Patch.class));
				}
				setMeshResolution(patches, (int)gd.getNextNumber());
			} else if (command.startsWith("Set coordinate transform of selected image")) { // )) {
				if (null == active || !(active instanceof Patch)) return;
				CoordinateTransform ct = ((Patch)active).getCoordinateTransform();
				if (null == ct) {
					Utils.showMessage("The selected image does not have a coordinate transform!");
					return;
				}
				final List<Patch> patches;
				final GenericDialog gd = new GenericDialog("Set coordinate transform");
				gd.addChoice(
						"Existing coordinate transform",
						new String[]{ "Replace", "Append", "Pre-append" },
						"Replace" );
				gd.addCheckbox("Only the lens distortion correction", false);
				if (command.endsWith("to other selected images")) {
					patches = selection.get(Patch.class);
					patches.remove((Patch)active);
					if (patches.isEmpty()) {
						Utils.showMessage("Select more than one image!");
						return;
					}
					gd.showDialog();
					if (gd.wasCanceled()) return;

				} else if (command.endsWith("layer-wise")) {
					Utils.addLayerRangeChoices(Display.this.layer, gd);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					patches = new ArrayList<Patch>();
					for (final Layer layer : getLayerSet().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()+1)) {
						patches.addAll(layer.getAll(Patch.class));
					}
				} else {
					return;
				}
				final int existingCT = gd.getNextChoiceIndex();
				final boolean only_lens_model = gd.getNextBoolean();

				if (only_lens_model) {
					ct = findFirstLensDeformationModel(ct);
					if (null == ct) {
						Utils.showMessage("Could not find a lens distortion correction model\n in image " + active);
						return;
					}
				}

				setCoordinateTransform(patches, ct, existingCT);
			} else if (command.equals("Set affine transform of selected image to other selected images")) {
				if (null == active || !(active instanceof Patch)) return;
				final AffineTransform aff = active.getAffineTransformCopy();
				final HashSet<Layer> layers = new HashSet<Layer>();
				final Collection<Displayable> patches = selection.getSelected(Patch.class);
				getLayerSet().addTransformStep(patches);
				for (final Displayable p : patches) {
					if (p == active) continue;
					p.setAffineTransform(aff);
					layers.add(p.getLayer());
				}
				for (final Layer l : layers) {
					l.recreateBuckets();
				}
				// Current state
				getLayerSet().addTransformStep(patches);
			} else if (command.equals("Set affine transform of selected image layer-wise")) {
				if (null == active || !(active instanceof Patch)) return;
				final AffineTransform aff = active.getAffineTransformCopy();
				final GenericDialog gd = new GenericDialog("Choose range of layers");
				Utils.addLayerRangeChoices(Display.this.layer, gd);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				final ArrayList<Patch> patches = new ArrayList<Patch>();
				final List<Layer> layers = getLayerSet().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()+1);
				for (final Layer layer : layers) {
					patches.addAll(layer.getAll(Patch.class));
				}
				getLayerSet().addTransformStep(patches);
				for (final Patch p: patches) {
					p.setAffineTransform(aff);
				}
				for (final Layer l : layers) {
					l.recreateBuckets();
				}
				// Current state
				getLayerSet().addTransformStep(patches);
			}
			repaint();
		}
		private NonLinearTransform findFirstLensDeformationModel(CoordinateTransform ct) {
			/* unwind CT lists to get the very first actual CT */
			while ( CoordinateTransformList.class.isInstance( ct ) )
				ct = ( ( CoordinateTransformList< CoordinateTransform > )ct ).get( 0 );
			if (ct instanceof NonLinearTransform) return (NonLinearTransform) ct;
			// Not found
			return null;
		}
	}

	public Bureaucrat removeScalingRotationShear(final List<Patch> patches) {
		return Bureaucrat.createAndStart(new Worker.Task("Removing coordinate transforms") { @Override
        public void exec() {
			getLayerSet().addTransformStep(patches);
			for (final Patch p : patches) {
				final Rectangle box = p.getBoundingBox();
				final AffineTransform aff = new AffineTransform();
				// translate so that the center remains where it is
				aff.setToTranslation(box.x + (box.width - p.getWidth())/2, box.y + (box.height - p.getHeight())/2);
				p.setAffineTransform(aff);
			}
			getLayerSet().addTransformStep(patches);
			Display.repaint();
		}}, this.project);
	}

	/** Meant for tasks that require setting an undo and regenerating mipmaps.
	 *  The method will NOT run if any Patch is linked.
	 *
	 * @param patches
	 * @param task
	 * @param filter
	 * @param taskTitle
	 * @return the {@link Bureaucrat} in charge of the task.
	 */
	public Bureaucrat applyPatchTask(final List<Patch> patches, final String taskTitle, final Operation<Boolean,Patch> task, final Filter<Patch> filter) {
		return Bureaucrat.createAndStart(new Worker.Task(taskTitle) { @Override
        public void exec() {
			final HashSet<Patch> ds = new HashSet<Patch>();
			for (final Patch p : patches) {
				// Check if any are linked: cannot remove, would break image-to-segmentation relationship
				if (p.isLinked()) {
					Utils.logAll("Cannot apply task: some images are linked to segmentations!");
					return;
				}
				if (Thread.currentThread().isInterrupted() || hasQuitted()) return;
				if (filter.accept(p)) {
					ds.add(p);
				}
			}

			if (ds.isEmpty()) {
				Utils.log("Nothing to do.");
				return;
			}

			// Add undo step:
			getLayerSet().addDataEditStep(ds);

			// Execute
			final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
			for (final Patch p : ds) {
				if (Thread.currentThread().isInterrupted() || hasQuitted()) return;
				if (task.apply(p)) {
					fus.add(p.getProject().getLoader().regenerateMipMaps(p)); // queue
				}
			}
			Utils.wait(fus);

			// Set current state
			getLayerSet().addDataEditStep(ds);
		}}, project);
	}

	public Bureaucrat removeCoordinateTransforms(final List<Patch> patches) {
		return applyPatchTask(
				patches,
				"Removing coordinate transforms",
				new Operation<Boolean, Patch>() {
					@Override
					public Boolean apply(final Patch o) {
						o.setCoordinateTransform(null);
						return true;
					}
				},
				new Filter<Patch>() {
					@Override
					public boolean accept(final Patch t) {
						return t.hasCoordinateTransform();
					}
				});
	}

	public Bureaucrat setCoordinateTransform(final List<Patch> patches, final CoordinateTransform ct, final int existingTransform) {
		return applyPatchTask(
				patches,
				"Set coordinate transform",
				new Operation<Boolean, Patch>() {
					@Override
					public Boolean apply(final Patch o) {
						switch ( existingTransform )
						{
						case CT_REPLACE:
							o.setCoordinateTransform(ct);
							break;
						case CT_APPEND:
							o.appendCoordinateTransform(ct);
							break;
						case CT_PREAPPEND:
							o.preAppendCoordinateTransform(ct);
						}
						return true;
					}
				},
				new Filter<Patch>() {
					@Override
					public boolean accept(final Patch t) {
						return true;
					}
				});
	}


	/**
	 * @deprecated Use {@link #setCoordinateTransform(List, CoordinateTransform, int)} instead which implements pre-appending as a third mode.
	 *
	 * @param patches
	 * @param ct
	 * @param append
	 * @return
	 */
	@Deprecated
	public Bureaucrat setCoordinateTransform(final List<Patch> patches, final CoordinateTransform ct, final boolean append) {
		return setCoordinateTransform( patches, ct, append ? CT_APPEND : CT_REPLACE );
	}

	public Bureaucrat setMeshResolution(final List<Patch> patches, final int meshResolution) {
		if (meshResolution < 1) {
			Utils.log("Cannot apply a mesh resolution smaller than 1!");
			return null;
		}
		return applyPatchTask(
				patches,
				"Alter mesh resolution",
				new Operation<Boolean, Patch>() {
					@Override
					public Boolean apply(final Patch o) {
						o.setMeshResolution(meshResolution);
						// Return false to avoid regenerating mipmaps when there isn't a CoordinateTransform
						return null != o.getCoordinateTransform();
					}
				},
				new Filter<Patch>() {
					@Override
					public boolean accept(final Patch t) {
						return t.getMeshResolution() != meshResolution;
					}
				});
	}

	private class MenuScriptListener implements ActionListener {
		@Override
        public void actionPerformed(final ActionEvent ae) {
			final String command = ae.getActionCommand();
			Bureaucrat.createAndStart(new Worker.Task("Setting preprocessor script") { @Override
            public void exec() {
			try{
				if (command.equals("Set preprocessor script layer-wise...")) {
					final Collection<Layer> ls = getLayerList("Set preprocessor script");
					if (null == ls) return;
					final String path = getScriptPath();
					if (null == path) return;
					setScriptPathToLayers(ls, path);
				} else if (command.equals("Set preprocessor script (selected images)...")) {
					if (selection.isEmpty()) return;
					final String path = getScriptPath();
					if (null == path) return;
					setScriptPath(selection.get(Patch.class), path);
				} else if (command.equals("Remove preprocessor script layer-wise...")) {
					final Collection<Layer> ls = getLayerList("Remove preprocessor script");
					if (null == ls) return;
					setScriptPathToLayers(ls, null);
				} else if (command.equals("Remove preprocessor script (selected images)...")) {
					if (selection.isEmpty()) return;
					setScriptPath(selection.get(Patch.class), null);
				}
			} catch (final Exception e) {
				IJError.print(e);
			}
			}}, Display.this.project);
		}
		private void setScriptPathToLayers(final Collection<Layer> ls, final String script) throws Exception {
			final ArrayList<Patch> ds = new ArrayList<Patch>();
			for (final Layer la : ls) {
				if (Thread.currentThread().isInterrupted()) return;
				ds.addAll(la.getAll(Patch.class));
			}
			setScriptPath(ds, script); // no lazy sequences ...
		}
		/** Accepts null script, to remove it if there. */
		private void setScriptPath(final Collection<Patch> list, final String script) throws Exception {
			Process.progressive(list, new TaskFactory<Patch,Object>() {
				@Override
                public Object process(final Patch p) {
					p.setPreprocessorScriptPath(script);
					try {
						p.updateMipMaps().get(); // wait for mipmap regeneration so that the processed image is in cache for mipmap regeneration
					} catch (final Throwable t) {
						IJError.print(t);
					}
					return null;
				}
			}, Math.max(1, Runtime.getRuntime().availableProcessors() -1));
		}
		private Collection<Layer> getLayerList(final String title) {
			final GenericDialog gd = new GenericDialog(title);
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return null;
			return layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1); // exclusive end
		}
		private String getScriptPath() {
			final OpenDialog od = new OpenDialog("Select script", OpenDialog.getLastDirectory(), null);
			String dir = od.getDirectory();
			if (null == dir) return null;
			if (IJ.isWindows()) dir = dir.replace('\\','/');
			if (!dir.endsWith("/")) dir += "/";
			return dir + od.getFileName();
		}
	}

	private class SetToolListener implements ActionListener {
		final int tool;
		SetToolListener(final int tool) {
			this.tool = tool;
		}
		@Override
        public void actionPerformed(final ActionEvent ae) {
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
		@Override
        public void actionPerformed(final ActionEvent ae) {
			final String command = ae.getActionCommand();

			final Area aroi = M.getArea(d.canvas.getFakeImagePlus().getRoi());

			d.dispatcher.exec(new Runnable() { @Override
            public void run() {

			try {
				String type = command;
				if (type.equals("Image")) type = "Patch";
				else if (type.equals("Text")) type = "DLabel";
				final Class<?> c = Class.forName("ini.trakem2.display." + type);

				final java.util.List<Displayable> a = new ArrayList<Displayable>();
				if (null != aroi) {
					a.addAll(d.layer.getDisplayables(c, aroi, true));
					a.addAll(d.layer.getParent().findZDisplayables(c, d.layer, aroi, true, true));
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

			} catch (final ClassNotFoundException e) {
				Utils.log2(e.toString());
			}

			}});
		}
	}

	/** Check if a panel for the given Displayable is completely visible in the JScrollPane */
	public boolean isWithinViewport(final Displayable d) {
		final Component comp = tabs.getSelectedComponent();
		if (!(comp instanceof RollingPanel)) return false;
		final RollingPanel rp = (RollingPanel)tabs.getSelectedComponent();
		if (ht_tabs.get(d.getClass()) == rp) return rp.isShowing(d);
		return false;
	}

	/** Check if a panel for the given Displayable is partially visible in the JScrollPane */
	public boolean isPartiallyWithinViewport(final Displayable d) {
		final RollingPanel rp = ht_tabs.get(d.getClass());
		return rp.isShowing(d);
	}

	// for Layer panels
	private void scrollToShow(final JScrollPane scroll, final JPanel dp) {
		if (null == dp) return;
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			final JViewport view = scroll.getViewport();
			final Point current = view.getViewPosition();
			final Dimension extent = view.getExtentSize();
			final int panel_y = dp.getY();
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
		}});
	}

	/** Update the title of the given Displayable in its DisplayablePanel, if any. */
	static public void updateTitle(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (layer == d.layer) {
				final DisplayablePanel dp = d.ht_panels.get(displ);
				if (null != dp) dp.updateTitle();
			}
		}
	}

	/** Update the Display's title in all Displays showing the given Layer. */
	static public void updateTitle(final Layer layer) {
		for (final Display d : al_displays) {
			if (d.layer == layer) d.updateFrameTitle();
		}
	}
	static public void updateTitle(final Project project) {
		for (final Display d : al_displays) {
			if (d.project == project) d.updateFrameTitle();
		}
	}
	/** Update the Display's title in all Displays showing a Layer of the given LayerSet. */
	static public void updateTitle(final LayerSet ls) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == ls) d.updateFrameTitle(d.layer);
		}
	}

	/** Set a new title in the JFrame, showing info on the layer 'z' and the magnification. */
	public void updateFrameTitle() {
		updateFrameTitle(layer);
	}
	private void updateFrameTitle(final Layer layer) {
		// From ij.ImagePlus class, the solution:
		String scale = "";
		final double magnification = canvas.getMagnification();
		if (magnification!=1.0) {
			final double percent = magnification*100.0;
			scale = new StringBuilder(" (").append(Utils.d2s(percent, percent==(int)percent ? 0 : 1)).append("%)").toString();
		}
		final LayerSet ls = layer.getParent();
		final Calibration cal = ls.getCalibration();
		final Layer last = ls.getLayer(ls.size()-1);
		final double depth = (last.getZ() - ls.getLayer(0).getZ() + last.getThickness()) * cal.pixelWidth;
		final String title = new StringBuilder(100)
			.append(layer.getParent().indexOf(layer) + 1).append('/').append(layer.getParent().size())
			.append("  z:").append(layer.getZ() * cal.pixelWidth).append(' ').append(cal.getUnits()).append(' ') // Not pixelDepth
			.append(' ').append(layer.getLayerThingTitle())
			.append(scale)
			.append(" -- ").append(getProject().toString())
			.append(' ').append(' ').append(Utils.cutNumber(layer.getParent().getLayerWidth() * cal.pixelWidth, 2, true))
			.append('x').append(Utils.cutNumber(layer.getParent().getLayerHeight() * cal.pixelHeight, 2, true))
			.append('x').append(Utils.cutNumber(depth, 2, true))
			.append(' ').append(cal.getUnit()).toString();
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			frame.setTitle(title);
		}});
		// fix the title for the FakeImageWindow and thus the WindowManager listing in the menus
		canvas.getFakeImagePlus().setTitle(title);
	}

	/** If shift is down, scroll to the next non-empty layer; otherwise, if scroll_step is larger than 1, then scroll 'scroll_step' layers ahead; else just the next Layer. */
	public void nextLayer(final int modifiers) {
		final Layer l;
		if (0 == (modifiers ^ Event.SHIFT_MASK)) {
			l = layer.getParent().nextNonEmpty(layer);
		} else if (scroll_step > 1) {
			final int i = layer.getParent().indexOf(this.layer);
			final Layer la = layer.getParent().getLayer(i + scroll_step);
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

	/** Should be invoked within event dispatch thread. */
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
			final int i = layer.getParent().indexOf(this.layer);
			final Layer la = layer.getParent().getLayer(i - scroll_step);
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

	static public void updateLayerScroller(final LayerSet set) {
		for (final Display d : al_displays) {
			if (d.layer.getParent() == set) {
				d.updateLayerScroller(d.layer);
			}
		}
	}

	private void updateLayerScroller(final Layer layer) {
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			final int size = layer.getParent().size();
			if (size <= 1) {
				scroller.setValues(0, 1, 0, 0);
				scroller.setEnabled(false);
			} else {
				scroller.setEnabled(true);
				scroller.setValues(layer.getParent().indexOf(layer), 1, 0, size);
			}
			recreateLayerPanels(layer);
		}});
	}

	// Can't use this.layer, may still be null. User argument instead.
	private synchronized void recreateLayerPanels(final Layer layer) {
		synchronized (layer_channels) {
			panel_layers.removeAll();

			final GridBagLayout gb = (GridBagLayout) panel_layers.getLayout();
			panel_layers.setLayout(gb);

			final GridBagConstraints c = new GridBagConstraints();
			c.anchor = GridBagConstraints.NORTHWEST;
			c.fill = GridBagConstraints.HORIZONTAL;
			c.gridx = 0;
			c.gridy = 0;

			if (0 == layer_panels.size()) {
				for (final Layer la : layer.getParent().getLayers()) {
					final LayerPanel lp = new LayerPanel(this, la);
					layer_panels.put(la, lp);
					gb.setConstraints(lp, c);
					this.panel_layers.add(lp);
					c.gridy += 1;
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
					gb.setConstraints(lp, c);
					this.panel_layers.add(lp);
					c.gridy += 1;
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
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			final Enumeration<DisplayablePanel> e = ht_panels.elements();
			while (e.hasMoreElements()) {
				e.nextElement().repaint();
			}
			Utils.updateComponent(tabs.getSelectedComponent());
		}});
	}

	static public void updatePanel(Layer layer, final Displayable displ) {
		if (null == layer && null != front) layer = front.layer; // the front layer
		for (final Display d : al_displays) {
			if (d.layer == layer) {
				d.updatePanel(displ);
			}
		}
	}

	private void updatePanel(final Displayable d) {
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
		final DisplayablePanel dp = ht_panels.get(d);
		if (null != dp) {
			dp.repaint();
			Utils.updateComponent(c);
		}
	}

	@Deprecated
	static public void updatePanelIndex(final Layer layer, final Displayable displ) {
		for (final Display d : al_displays) {
			if (d.layer == layer || displ instanceof ZDisplayable) {
				d.updatePanelIndex(displ);
			}
		}
	}

	@Deprecated
	private void updatePanelIndex(final Displayable d) {
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			ht_tabs.get(d.getClass()).updateList();
		}});
	}

	/** Repair possibly missing panels and other components by simply resetting the same Layer */
	public void repairGUI() {
		setLayer(layer, true);
	}

	@Override
    public void actionPerformed(final ActionEvent ae) {
		dispatcher.exec(new Runnable() { @Override
        public void run() {

		final String command = ae.getActionCommand();
		if (command.startsWith("Job")) {
			if (Utils.checkYN("Really cancel job?")) {
				project.getLoader().quitJob(command);
				repairGUI();
			}
			return;
		} else if (command.equals("Move to top")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			getLayerSet().addUndoMoveStep(active);
			layer.getParent().move(LayerSet.TOP, active);
			getLayerSet().addUndoMoveStep(active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move up")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			getLayerSet().addUndoMoveStep(active);
			layer.getParent().move(LayerSet.UP, active);
			getLayerSet().addUndoMoveStep(active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move down")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			getLayerSet().addUndoMoveStep(active);
			layer.getParent().move(LayerSet.DOWN, active);
			getLayerSet().addUndoMoveStep(active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Move to bottom")) {
			if (null == active) return;
			canvas.setUpdateGraphics(true);
			getLayerSet().addUndoMoveStep(active);
			layer.getParent().move(LayerSet.BOTTOM, active);
			getLayerSet().addUndoMoveStep(active);
			Display.repaint(layer.getParent(), active, 5);
			//Display.updatePanelIndex(layer, active);
		} else if (command.equals("Duplicate, link and send to next layer")) {
			duplicateLinkAndSendTo(active, 1, layer.getParent().next(layer));
		} else if (command.equals("Duplicate, link and send to previous layer")) {
			duplicateLinkAndSendTo(active, 0, layer.getParent().previous(layer));
		} else if (command.equals("Duplicate, link and send to...")) {
			// fix non-scrolling popup menu
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				final GenericDialog gd = new GenericDialog("Send to");
				gd.addMessage("Duplicate, link and send to...");
				final String[] sl = new String[layer.getParent().size()];
				int next = 0;
				for (final Layer la : layer.getParent().getLayers()) {
					sl[next++] = project.findLayerThing(la).toString();
				}
				gd.addChoice("Layer: ", sl, sl[layer.getParent().indexOf(layer)]);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				final Layer la = layer.getParent().getLayer(gd.getNextChoiceIndex());
				if (layer == la) {
					Utils.showMessage("Can't duplicate, link and send to the same layer.");
					return;
				}
				duplicateLinkAndSendTo(active, 0, la);
			}});
		} else if (-1 != command.indexOf("z = ")) {
			// this is an item from the "Duplicate, link and send to" menu of layer z's
			final Layer target_layer = layer.getParent().getLayer(Double.parseDouble(command.substring(command.lastIndexOf(' ') +1)));
			Utils.log2("layer: __" +command.substring(command.lastIndexOf(' ') +1) + "__");
			if (null == target_layer) return;
			duplicateLinkAndSendTo(active, 0, target_layer);
		} else if (-1 != command.indexOf("z=")) {
			// WARNING the indexOf is very similar to the previous one
			// Send the linked group to the selected layer
			final int iz = command.indexOf("z=")+2;
			Utils.log2("iz=" + iz + "  other: " + command.indexOf(' ', iz+2));
			int end = command.indexOf(' ', iz);
			if (-1 == end) end = command.length();
			final double lz = Double.parseDouble(command.substring(iz, end));
			final Layer target = layer.getParent().getLayer(lz);
			layer.getParent().move(selection.getAffected(), active.getLayer(), target); // TODO what happens when ZDisplayable are selected?
		} else if (command.equals("Unlink")) {
			if (null == active || active instanceof Patch) return;
			active.unlink();
			updateSelection();//selection.update();
		} else if (command.equals("Unlink from images")) {
			if (null == active) return;
			try {
				for (final Displayable displ: selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (final Exception e) { IJError.print(e); }
		} else if (command.equals("Unlink slices")) {
			final YesNoCancelDialog yn = new YesNoCancelDialog(frame, "Attention", "Really unlink all slices from each other?\nThere is no undo.");
			if (!yn.yesPressed()) return;
			final ArrayList<Patch> pa = ((Patch)active).getStackPatches();
			for (int i=pa.size()-1; i>0; i--) {
				pa.get(i).unlink(pa.get(i-1));
			}
		} else if (command.equals("Send to next layer")) {
			final Rectangle box = selection.getBox();
			try {
				// unlink Patch instances
				for (final Displayable displ : selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (final Exception e) { IJError.print(e); }
			//layer.getParent().moveDown(layer, active); // will repaint whatever appropriate layers
			selection.moveDown();
			repaint(layer.getParent(), box);
		} else if (command.equals("Send to previous layer")) {
			final Rectangle box = selection.getBox();
			try {
				// unlink Patch instances
				for (final Displayable displ : selection.getSelected()) {
					displ.unlinkAll(Patch.class);
				}
				updateSelection();//selection.update();
			} catch (final Exception e) { IJError.print(e); }
			//layer.getParent().moveUp(layer, active); // will repaint whatever appropriate layers
			selection.moveUp();
			repaint(layer.getParent(), box);
		} else if (command.equals("Show centered")) {
			if (active == null) return;
			showCentered(active);
		} else if (command.equals("Delete...")) {
			// remove all selected objects
			selection.deleteAll();
		} else if (command.equals("Color...")) {
			IJ.doCommand("Color Picker...");
		} else if (command.equals("Revert")) {
			if (null == active || active.getClass() != Patch.class) return;
			final Patch p = (Patch)active;
			if (!p.revert()) {
				if (null == p.getOriginalPath()) Utils.log("No editions to save for patch " + p.getTitle() + " #" + p.getId());
				else Utils.log("Could not revert Patch " + p.getTitle() + " #" + p.getId());
			}
		} else if (command.equals("Remove alpha mask")) {
			Display.removeAlphaMasks(selection.get(Patch.class));
		} else if (command.equals("Undo")) {
			Bureaucrat.createAndStart(new Worker.Task("Undo") { @Override
            public void exec() {
				layer.getParent().undoOneStep();
				Display.repaint(layer.getParent());
			}}, project);
		} else if (command.equals("Redo")) {
			Bureaucrat.createAndStart(new Worker.Task("Redo") { @Override
            public void exec() {
				layer.getParent().redoOneStep();
				Display.repaint(layer.getParent());
			}}, project);
		} else if (command.equals("Apply transform")) {
			canvas.applyTransform();
		} else if (command.equals("Apply transform propagating to last layer")) {
			if (mode.getClass() == AffineTransformMode.class || mode.getClass() == NonLinearTransformMode.class) {
				final LayerSet ls = getLayerSet();
				final HashSet<Layer> subset = new HashSet<Layer>(ls.getLayers(ls.indexOf(Display.this.layer)+1, ls.size()-1)); // +1 to exclude current layer
				if (mode.getClass() == AffineTransformMode.class) ((AffineTransformMode)mode).applyAndPropagate(subset);
				else if (mode.getClass() == NonLinearTransformMode.class) ((NonLinearTransformMode)mode).apply(subset);
				setMode(new DefaultMode(Display.this));
			}
		} else if (command.equals("Apply transform propagating to first layer")) {
			if (mode.getClass() == AffineTransformMode.class || mode.getClass() == NonLinearTransformMode.class) {
				final LayerSet ls = getLayerSet();
				final HashSet<Layer> subset = new HashSet<Layer>(ls.getLayers(0, ls.indexOf(Display.this.layer) -1)); // -1 to exclude current layer
				if (mode.getClass() == AffineTransformMode.class) ((AffineTransformMode)mode).applyAndPropagate(subset);
				else if (mode.getClass() == NonLinearTransformMode.class) ((NonLinearTransformMode)mode).apply(subset);
				setMode(new DefaultMode(Display.this));
			}
		} else if (command.equals("Cancel transform")) {
			canvas.cancelTransform(); // calls getMode().cancel()
		} else if (command.equals("Specify transform...")) {
			if (null == active) return;
			selection.specify();
		} else if (command.equals("Exit inspection")) {
			getMode().cancel();
			setMode(new DefaultMode(Display.this));
		} else if (command.equals("Inspect image mesh triangles")) {
			setMode(new InspectPatchTrianglesMode(Display.this));
		} else if (command.equals("Hide all but images")) {
			final ArrayList<Class<?>> type = new ArrayList<Class<?>>();
			type.add(Patch.class);
			type.add(Stack.class);
			final Collection<Displayable> col = layer.getParent().hideExcept(type, false);
			selection.removeAll(col);
			Display.updateCheckboxes(col, DisplayablePanel.VISIBILITY_STATE);
			Display.update(layer.getParent(), false);
		} else if (command.equals("Unhide all")) {
			Display.updateCheckboxes(layer.getParent().setAllVisible(false), DisplayablePanel.VISIBILITY_STATE);
			Display.update(layer.getParent(), false);
		} else if (command.startsWith("Hide all ")) {
			final String type = command.substring(9, command.length() -1); // skip the ending plural 's'
			final Collection<Displayable> col = layer.getParent().setVisible(type, false, true);
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
		} else if (command.equals("Resize canvas/LayerSet to ROI")) {
			final Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null == roi) {
				Utils.log("No ROI present!");
				return;
			}
			resizeCanvas(roi.getBounds());
		} else if (command.equals("Import image")) {
			importImage();
		} else if (command.equals("Import next image")) {
			importNextImage();
		} else if (command.equals("Import stack...")) {
			Display.this.getLayerSet().addChangeTreesStep();
			final Rectangle sr = getCanvas().getSrcRect();
			final Bureaucrat burro = project.getLoader().importStack(layer, sr.x + sr.width/2, sr.y + sr.height/2, null, true, null, false);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				Display.this.getLayerSet().addChangeTreesStep();
			}});
		} else if (command.equals("Import stack with landmarks...")) {
			// 1 - Find out if there's any other project open
			final List<Project> pr = Project.getProjects();
			if (1 == pr.size()) {
				Utils.logAll("Need another project open!");
				return;
			}
			// 2 - Ask for a "landmarks" type
			final GenericDialog gd = new GenericDialog("Landmarks");
			gd.addStringField("landmarks type:", "landmarks");
			final String[] none = {"-- None --"};
			final Hashtable<String,Project> mpr = new Hashtable<String,Project>();
			for (final Project p : pr) {
				if (p == project) continue;
				mpr.put(p.toString(), p);
			}
			final String[] project_titles = mpr.keySet().toArray(new String[0]);

			final Hashtable<String,ProjectThing> map_target = findLandmarkNodes(project, "landmarks");
			final String[] target_landmark_titles = map_target.isEmpty() ? none : map_target.keySet().toArray(new String[0]);
			gd.addChoice("Landmarks node in this project:", target_landmark_titles, target_landmark_titles[0]);

			gd.addMessage("");
			gd.addChoice("Source project:", project_titles, project_titles[0]);

			final Hashtable<String,ProjectThing> map_source = findLandmarkNodes(mpr.get(project_titles[0]), "landmarks");
			final String[] source_landmark_titles = map_source.isEmpty() ? none : map_source.keySet().toArray(new String[0]);
			gd.addChoice("Landmarks node in source project:", source_landmark_titles, source_landmark_titles[0]);

			final List<Patch> stacks = Display.getPatchStacks(mpr.get(project_titles[0]).getRootLayerSet());

			String[] stack_titles;
			if (stacks.isEmpty()) {
				if (1 == mpr.size()) {
					IJ.showMessage("Project " + project_titles[0] + " does not contain any Stack.");
					return;
				}
				stack_titles = none;
			} else {
				stack_titles = new String[stacks.size()];
				int next = 0;
				for (final Patch pa : stacks) stack_titles[next++] = pa.toString();
			}
			gd.addChoice("Stacks:", stack_titles, stack_titles[0]);

			final Vector<?> vc = gd.getChoices();
			final Choice choice_target_landmarks = (Choice) vc.get(0);
			final Choice choice_source_projects = (Choice) vc.get(1);
			final Choice choice_source_landmarks = (Choice) vc.get(2);
			final Choice choice_stacks = (Choice) vc.get(3);

			final TextField input = (TextField) gd.getStringFields().get(0);
			input.addTextListener(new TextListener() {
				@Override
                public void textValueChanged(final TextEvent te) {
					final String text = input.getText();
					update(choice_target_landmarks, Display.this.project, text, map_target);
					update(choice_source_landmarks, mpr.get(choice_source_projects.getSelectedItem()), text, map_source);
				}
				private void update(final Choice c, final Project p, final String type, final Hashtable<String,ProjectThing> table) {
					table.clear();
					table.putAll(findLandmarkNodes(p, type));
					c.removeAll();
					if (table.isEmpty()) c.add(none[0]);
					else for (final String t : table.keySet()) c.add(t);
				}
			});

			choice_source_projects.addItemListener(new ItemListener() {
				@Override
                public void itemStateChanged(final ItemEvent e) {
					final String item = (String) e.getItem();
					final Project p = mpr.get(choice_source_projects.getSelectedItem());
					// 1 - Update choice of landmark items
					map_source.clear();
					map_source.putAll(findLandmarkNodes(p, input.getText()));
					choice_target_landmarks.removeAll();
					if (map_source.isEmpty()) choice_target_landmarks.add(none[0]);
					else for (final String t : map_source.keySet()) choice_target_landmarks.add(t);
					// 2 - Update choice of Stack items
					stacks.clear();
					choice_stacks.removeAll();
					stacks.addAll(Display.getPatchStacks(mpr.get(project_titles[0]).getRootLayerSet()));
					if (stacks.isEmpty()) choice_stacks.add(none[0]);
					else for (final Patch pa : stacks) choice_stacks.add(pa.toString());
				}
			});

			gd.showDialog();
			if (gd.wasCanceled()) return;

			final String type = gd.getNextString();
			if (null == type || 0 == type.trim().length()) {
				Utils.log("Invalid landmarks node type!");
				return;
			}
			final ProjectThing target_landmarks_node = map_target.get(gd.getNextChoice());
			final Project source = mpr.get(gd.getNextChoice());
			final ProjectThing source_landmarks_node = map_source.get(gd.getNextChoice());
			final Patch stack_patch = stacks.get(gd.getNextChoiceIndex());

			// Store current state
			Display.this.getLayerSet().addLayerContentStep(layer);

			// Insert stack
			insertStack(target_landmarks_node, source, source_landmarks_node, stack_patch);

			// Store new state
			Display.this.getLayerSet().addChangeTreesStep();
		} else if (command.equals("Import grid...")) {
			Display.this.getLayerSet().addLayerContentStep(layer);
			final Bureaucrat burro = project.getLoader().importGrid(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { @Override
                public void run() {
					Display.this.getLayerSet().addLayerContentStep(layer);
				}});
		} else if (command.equals("Import sequence as grid...")) {
			Display.this.getLayerSet().addChangeTreesStep();
			final Bureaucrat burro = project.getLoader().importSequenceAsGrid(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { @Override
                public void run() {
					Display.this.getLayerSet().addChangeTreesStep();
				}});
		} else if (command.equals("Import from text file...")) {
			Display.this.getLayerSet().addChangeTreesStep();
			final Bureaucrat burro = project.getLoader().importImages(layer);
			if (null != burro)
				burro.addPostTask(new Runnable() { @Override
                public void run() {
					Display.this.getLayerSet().addChangeTreesStep();
				}});
		} else if (command.equals("Import labels as arealists...")) {
			Display.this.getLayerSet().addChangeTreesStep();
			final Bureaucrat burro = project.getLoader().importLabelsAsAreaLists(layer, null, Double.MAX_VALUE, 0, 0.4f, false);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				Display.this.getLayerSet().addChangeTreesStep();
			}});
		} else if (command.equals("Make flat image...")) {
			// if there's a ROI, just use that as cropping rectangle
			Rectangle srcRect = null;
			final Roi roi = canvas.getFakeImagePlus().getRoi();
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
			gd.addNumericField("Width: ", srcRect.width, 0);
			gd.addNumericField("height: ", srcRect.height, 0);
			// connect the above 3 fields:
			final Vector<?> numfields = gd.getNumericFields();
			final UpdateDimensionField udf = new UpdateDimensionField(srcRect.width, srcRect.height, (TextField) numfields.get(1), (TextField) numfields.get(2), (TextField) numfields.get(0), (Scrollbar) gd.getSliders().get(0));
			for (final Object ob : numfields) ((TextField)ob).addTextListener(udf);

			gd.addChoice("Type: ", types, types[0]);
			if (layer.getParent().size() > 1) {
				Utils.addLayerRangeChoices(Display.this.layer, gd); /// $#%! where are my lisp macros
				gd.addCheckbox("Include non-empty layers only", true);
			}
			gd.addMessage("Background color:");
			Utils.addRGBColorSliders(gd, Color.black);
			gd.addCheckbox("Best quality", false);
			gd.addMessage("");
			final String[] choices = new String[]{"Show", "Save to file", "Save for web (CATMAID)"};
			gd.addChoice("Export:", choices, choices[0]);
			final String[] formats = Saver.formats();
			gd.addChoice("Format:", formats, formats[0]);
			gd.addNumericField("Tile_side", 1024, 0);
			final Choice cformats = (Choice)gd.getChoices().get(gd.getChoices().size() -1);
			cformats.setEnabled(false);
			final Choice cchoices = (Choice)gd.getChoices().get(gd.getChoices().size() -2);
			final TextField tf = (TextField)gd.getNumericFields().get(gd.getNumericFields().size() -1);
			tf.setEnabled(false);
			gd.addChoice("Directory structure", new String[]{"<section>/<Y>_<X>_<scale_power>.<ext>", "<section>/<scale_power>/<Y>_<X>.<ext>"}, "<section>/<scale_power>/<Y>_<X>.<ext>");
			final Choice tile_directory_structure = (Choice)gd.getChoices().get(gd.getChoices().size() -1);
			tile_directory_structure.setEnabled(false);
			gd.addChoice("Strategy:", new String[]{"Use original images", "Use mipmaps", "Use mipmaps (multi-layer)"}, "Use mipmaps (multi-layer)");
			final Choice cstrategy = (Choice)gd.getChoices().get(gd.getChoices().size() -1);
			cstrategy.setEnabled(false);
			gd.addCheckbox("Skip empty tiles", true);
			final Checkbox cb_skip = (Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size() -1);
			cb_skip.setEnabled(false);
			gd.addCheckbox("Use layer indices", true);
			final Checkbox cb_li = (Checkbox)gd.getCheckboxes().get(gd.getCheckboxes().size() -1);
			cb_li.setEnabled(false);
			gd.addNumericField("Number of threads", project.getProperty("n_mipmap_threads", 1), 0);
			final Component cnt = (Component)gd.getNumericFields().get(gd.getNumericFields().size() -1);
			cnt.setEnabled(false);
			final Component[] cweb = new Component[]{tf, tile_directory_structure, cstrategy, cb_skip, cb_li, cnt};
			
			cchoices.addItemListener(new ItemListener() {
				@Override
				public void itemStateChanged(final ItemEvent e) {
					cformats.setEnabled(cchoices.getSelectedIndex() > 0);
					if (2 == cchoices.getSelectedIndex()) {
						cformats.select(".jpg");
						for (final Component c : cweb) c.setEnabled(true);
					} else {
						tf.setEnabled(false);
					}
				}
			});
			
			gd.showDialog();
			if (gd.wasCanceled()) return;

			scale = gd.getNextNumber() / 100;
			the_type = (0 == gd.getNextChoiceIndex() ? ImagePlus.GRAY8 : ImagePlus.COLOR_RGB);
			if (Double.isNaN(scale) || scale <= 0.0) {
				Utils.showMessage("Invalid scale.");
				return;
			}

			// consuming and ignoring width and height:
			gd.getNextNumber();
			gd.getNextNumber();

			Layer[] layer_array = null;
			boolean non_empty_only = false;
			if (layer.getParent().size() > 1) {
				non_empty_only = gd.getNextBoolean();
				final int i_start = gd.getNextChoiceIndex();
				final int i_end = gd.getNextChoiceIndex();
				final ArrayList<Layer> al = new ArrayList<Layer>();
				final ArrayList<ZDisplayable> al_zd = layer.getParent().getZDisplayables();
				final ZDisplayable[] zd = new ZDisplayable[al_zd.size()];
				al_zd.toArray(zd);
				for (int i=i_start; i <= i_end; i++) {
					final Layer la = layer.getParent().getLayer(i);
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

			final int choice = gd.getNextChoiceIndex();
			final boolean save_to_file = 1 == choice;
			final boolean save_for_web = 2 == choice;
			final String format = gd.getNextChoice();
			final Saver saver = new Saver(format);
			final int tile_side = (int)gd.getNextNumber();
			final int directory_structure_type = gd.getNextChoiceIndex();
			final int strategy = gd.getNextChoiceIndex();
			final boolean skip_empty_tiles = gd.getNextBoolean();
			final boolean use_layer_indices = gd.getNextBoolean();
			double nt = gd.getNextNumber();
			final int n_threads = (int) (Double.isNaN(nt) ? 1 : Math.max(1, nt));
			// in its own thread
			if (save_for_web) project.getLoader().makePrescaledTiles(layer_array, Patch.class, srcRect, c_alphas,
					the_type, null, strategy, saver, tile_side, directory_structure_type, skip_empty_tiles, use_layer_indices, n_threads);
			else project.getLoader().makeFlatImage(layer_array, srcRect, scale, c_alphas, the_type, save_to_file, format, quality, background);

		} else if (command.equals("Lock")) {
			selection.setLocked(true);
			Utils.revalidateComponent(tabs.getSelectedComponent());
		} else if (command.equals("Unlock")) {
			selection.setLocked(false);
			Utils.revalidateComponent(tabs.getSelectedComponent());
		} else if (command.equals("Properties...")) {
			switch(selection.getSelected().size()) {
			case 0:
				return;
			case 1:
				active.adjustProperties();
				break;
			default:
				adjustGroupProperties(selection.getSelected());
				break;
			}
			updateSelection();
		} else if (command.equals("Measurement options...")) {
			adjustMeasurementOptions();
		} else if (command.equals("Show current 2D position in 3D")) {
			final Point p = canvas.consumeLastPopupPoint();
			if (null == p) return;
			Display3D.addFatPoint("Current 2D Position", getLayerSet(), p.x, p.y, layer.getZ(), 10, Color.magenta);
		} else if (command.equals("Show layers as orthoslices in 3D")) {
			final GenericDialog gd = new GenericDialog("Options");
			final Roi roi = canvas.getFakeImagePlus().getRoi();
			final Rectangle r = null == roi ? getLayerSet().get2DBounds() : roi.getBounds();
			gd.addMessage("ROI 2D bounds:");
			gd.addNumericField("x:", r.x, 0, 30, "pixels");
			gd.addNumericField("y:", r.y, 0, 30, "pixels");
			gd.addNumericField("width:", r.width, 0, 30, "pixels");
			gd.addNumericField("height:", r.height, 0, 30, "pixels");
			gd.addMessage("Layers to include:");
			Utils.addLayerRangeChoices(layer, gd);
			gd.addMessage("Constrain dimensions to:");
			gd.addNumericField("max width and height:", getLayerSet().getPixelsMaxDimension(), 0, 30, "pixels");
			gd.addMessage("Options:");
			final String[] types = {"Greyscale", "Color RGB"};
			gd.addChoice("Image type:", types, types[0]);
			gd.addCheckbox("Invert images", false);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final int x = (int)gd.getNextNumber(),
			    y = (int)gd.getNextNumber(),
			    width = (int)gd.getNextNumber(),
			    height = (int)gd.getNextNumber();
			final int first = gd.getNextChoiceIndex(),
					  last = gd.getNextChoiceIndex();
			final List<Layer> layers = getLayerSet().getLayers(first, last);
			final int max_dim = Math.min((int)gd.getNextNumber(), Math.max(width, height));
			float scale = 1;
			if (max_dim < Math.max(width, height)) {
				scale = max_dim / (float)Math.max(width, height);
			}
			final int type = 0 == gd.getNextChoiceIndex() ? ImagePlus.GRAY8 : ImagePlus.COLOR_RGB;
			final boolean invert = gd.getNextBoolean();
			final LayerStack stack = new LayerStack(layers, new Rectangle(x, y, width, height), scale, type, Patch.class, max_dim, invert);
			Display3D.showOrthoslices(stack.getImagePlus(), "LayerSet [" + x + "," + y + "," + width + "," + height + "] " + first + "--" + last, x, y, scale, layers.get(0));
		} else if (command.equals("Align stack slices")) {
			if (getActive() instanceof Patch) {
				final Patch slice = (Patch)getActive();
				if (slice.isStack()) {
					// check linked group
					final HashSet hs = slice.getLinkedGroup(new HashSet());
					for (final Iterator it = hs.iterator(); it.hasNext(); ) {
						if (it.next().getClass() != Patch.class) {
							Utils.showMessage("Images are linked to other objects, can't proceed to cross-correlate them."); // labels should be fine, need to check that
							return;
						}
					}
					final LayerSet ls = slice.getLayerSet();
					final HashSet<Displayable> linked = slice.getLinkedGroup(null);
					ls.addTransformStepWithData(linked);
					final Bureaucrat burro = AlignTask.registerStackSlices((Patch)getActive()); // will repaint
					burro.addPostTask(new Runnable() { @Override
                    public void run() {
						ls.enlargeToFit(linked);
						// The current state when done
						ls.addTransformStepWithData(linked);
					}});
				} else {
					Utils.log("Align stack slices: selected image is not part of a stack.");
				}
			}
		} else if (command.equals("Align layers manually with landmarks")) {
			setMode(new ManualAlignMode(Display.this));
		} else if (command.equals("Align layers")) {
			Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null != roi) {
				final YesNoCancelDialog yn = new YesNoCancelDialog(frame, "Use ROI?", "Snapshot layers using the ROI bounds?\n" + roi.getBounds());
				if (yn.cancelPressed()) return;
				if (!yn.yesPressed()) {
					roi = null;
				}
			}
			final Layer la = layer; // caching, since scroll wheel may change it
			la.getParent().addTransformStep(la.getParent().getLayers());
			final Bureaucrat burro = AlignLayersTask.alignLayersTask( la, null == roi ? null : roi.getBounds() );
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().enlargeToFit(getLayerSet().getDisplayables(Patch.class));
				la.getParent().addTransformStep(la.getParent().getLayers());
			}});
		} else if (command.equals("Align multi-layer mosaic")) {
			final Layer la = layer; // caching, since scroll wheel may change it
			la.getParent().addTransformStep();
			final Bureaucrat burro = AlignTask.alignMultiLayerMosaicTask( la, active instanceof Patch ? (Patch)active : null );
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().enlargeToFit(getLayerSet().getDisplayables(Patch.class));
				la.getParent().addTransformStep();
			}});
		} else if (command.equals("Montage all images in this layer")) {
			final Layer la = layer;
			final List<Patch> patches = new ArrayList<Patch>( (List<Patch>) (List) la.getDisplayables(Patch.class, true));
			if (patches.size() < 2) {
				Utils.showMessage("Montage needs 2 or more visible images");
				return;
			}
			final Collection<Displayable> col = la.getParent().addTransformStepWithDataForAll(Arrays.asList(new Layer[]{la}));

			// find any locked or selected patches
			final HashSet<Patch> fixed = new HashSet<Patch>();
			for (final Patch p : patches) {
				if (p.isLocked2() || selection.contains(p)) fixed.add(p);
			}

			if (patches.size() == fixed.size()) {
				Utils.showMessage("Can't do", "No montage possible: all images are selected,\nand hence all are considered locked.\nSelect only one image to be used as reference, or none.");
				return;
			}

			Utils.log("Using " + fixed.size() + " image" + (fixed.size() == 1 ? "" : "s") + " as reference.");

			final Bureaucrat burro = AlignTask.alignPatchesTask(patches, fixed);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().enlargeToFit(patches);
				la.getParent().addTransformStepWithData(col);
			}});
		} else if (command.equals("Montage selected images")) {
			final Layer la = layer;
			if (selection.getSelected(Patch.class).size() < 2) {
				Utils.showMessage("Montage needs 2 or more images selected");
				return;
			}
			final Collection<Displayable> col = la.getParent().addTransformStepWithDataForAll(Arrays.asList(new Layer[]{la}));
			final Bureaucrat burro = AlignTask.alignSelectionTask(selection);
			if (null == burro) return;
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				la.getParent().enlargeToFit(selection.getAffected());
				la.getParent().addTransformStepWithData(col);
			}});
		} else if (command.equals("Montage multiple layers")) {
			final GenericDialog gd = new GenericDialog("Choose range");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final List<Layer> layers = getLayerSet().getLayers(gd.getNextChoiceIndex(), gd.getNextChoiceIndex());
			final Collection<Displayable> col = getLayerSet().addTransformStepWithDataForAll(layers);
			final Bureaucrat burro = AlignTask.montageLayersTask(layers);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				final Collection<Displayable> ds = new ArrayList<Displayable>();
				for (final Layer la : layers) ds.addAll(la.getDisplayables(Patch.class));
				getLayerSet().enlargeToFit(ds);
				getLayerSet().addTransformStepWithData(col);
			}});
		} else if (command.equals("Properties ...")) { // NOTE the space before the dots, to distinguish from the "Properties..." command that works on Displayable objects.
			adjustProperties();
		} else if (command.equals("Adjust snapping parameters...")) {
			AlignTask.p_snap.setup("Snap");
		} else if (command.equals("Adjust fast-marching parameters...")) {
			Segmentation.fmp.setup();
		} else if (command.equals("Adjust arealist paint parameters...")) {
			AreaWrapper.PP.setup();
		} else if (command.equals("Fill ROI in alpha mask")) {
			if (active.getClass() == Patch.class) {
				((Patch)active).keyPressed(new KeyEvent(getCanvas(), -1, System.currentTimeMillis(), 0, KeyEvent.VK_F, 'f'));
			}
		} else if (command.equals("Fill inverse ROI in alpha mask")) {
			if (active.getClass() == Patch.class) {
				((Patch)active).keyPressed(new KeyEvent(getCanvas(), -1, System.currentTimeMillis(), Event.SHIFT_MASK, KeyEvent.VK_F, 'f'));
			}
		} else if (command.equals("Search...")) {
			Search.showWindow();
		} else if (command.equals("Select all")) {
			selection.selectAll();
			repaint(Display.this.layer, selection.getBox(), 0);
		} else if (command.equals("Select all visible")) {
			selection.selectAllVisible();
			repaint(Display.this.layer, selection.getBox(), 0);
		} else if (command.equals("Select all that match...")) {
			final List<Displayable> ds = find();
			selection.selectAll(ds);
			Utils.showStatus("Added " + ds.size() + " to selection.");
		} else if (command.equals("Select none")) {
			final Rectangle box = selection.getBox();
			selection.clear();
			repaint(Display.this.layer, box, 0);
		} else if (command.equals("Restore selection")) {
			selection.restore();
		} else if (command.equals("Select under ROI")) {
			final Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null == roi) return;
			selection.selectAll(roi, true);
		} else if (command.equals("Merge") || command.equals("Split")) {
			final Bureaucrat burro = Bureaucrat.create(new Worker.Task(command + "ing AreaLists") {
				@Override
                public void exec() {
					final ArrayList<Displayable> al_sel = selection.getSelected(AreaList.class);
					// put active at the beginning, to work as the base on which other's will get merged
					al_sel.remove(Display.this.active);
					al_sel.add(0, Display.this.active);
					final Set<DoStep> dataedits = new HashSet<DoStep>();
					if (command.equals("Merge")) {
						// Add data undo for active only, which will be edited
						dataedits.add(new Displayable.DoEdit(Display.this.active).init(Display.this.active, new String[]{"data"}));
						getLayerSet().addChangeTreesStep(dataedits);
						
						final AreaList ali = AreaList.merge(al_sel);
						if (null != ali) {
							// remove all but the first from the selection
							for (int i=1; i<al_sel.size(); i++) {
								final Object ob = al_sel.get(i);
								if (ob.getClass() == AreaList.class) {
									selection.remove((Displayable)ob);
								}
							}
							selection.updateTransform(ali);
							repaint(ali.getLayerSet(), ali, 0);
						}
					} else if (command.equals("Split")) {
						// Add data undo for every AreaList
						for (final Displayable d: al_sel) {
							if (d.getClass() != AreaList.class) continue;
							dataedits.add(new Displayable.DoEdit(d).init(d, new String[]{"data"}));
						}
						getLayerSet().addChangeTreesStep(dataedits);
						
						try {
							List<AreaList> alis = AreaList.split(al_sel);
							for (AreaList ali: alis) {
								if (selection.contains(ali)) continue;
								selection.add(ali);
							}
						} catch (Exception e) {
							IJError.print(e);
							getLayerSet().undoOneStep();
						}
					}
				}
			}, Display.this.project);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				final Set<DoStep> dataedits = new HashSet<DoStep>();
				dataedits.add(new Displayable.DoEdit(Display.this.active).init(Display.this.active, new String[]{"data"}));
				getLayerSet().addChangeTreesStep(dataedits);
			}});
			burro.goHaveBreakfast();
		} else if (command.equals("Reroot")) {
			if (!(active instanceof Tree<?>)) return;
			getLayerSet().addDataEditStep(active);
			if (((Tree)active).reRoot(((Tree)active).getLastVisited())) {
				getLayerSet().addDataEditStep(active);
				Display.repaint(getLayerSet());
			} else {
				getLayerSet().removeLastUndoStep();
			}
		} else if (command.equals("Part subtree")) {
			if (!(active instanceof Tree<?>)) return;
			if (!Utils.check("Really part the subtree?")) return;
			final LayerSet.DoChangeTrees step = getLayerSet().addChangeTreesStep();
			final Set<DoStep> deps = new HashSet<DoStep>();
			deps.add(new Displayable.DoEdit(active).init(active, new String[]{"data"})); // I hate java
			step.addDependents(deps);
			final List<ZDisplayable> ts = ((Tree)active).splitAt(((Tree)active).getLastVisited());
			if (null == ts) {
				getLayerSet().removeLastUndoStep();
				return;
			}
			final Displayable elder = Display.this.active;
			final HashSet<DoStep> deps2 = new HashSet<DoStep>();
			for (final ZDisplayable t : ts) {
				deps2.add(new Displayable.DoEdit(t).init(t, new String[]{"data"}));
				if (t == elder) continue;
				getLayerSet().add(t); // will change Display.this.active !
				project.getProjectTree().addSibling(elder, t);
			}
			selection.clear();
			selection.selectAll(ts);
			selection.add(elder);
			final LayerSet.DoChangeTrees step2 = getLayerSet().addChangeTreesStep();
			step2.addDependents(deps2);
			Display.repaint(getLayerSet());
		} else if (command.equals("Show tabular view")) {
			if (!(active instanceof Tree<?>)) return;
			((Tree<?>)active).createMultiTableView();
		} else if (command.equals("Mark")) {
			if (!(active instanceof Tree<?>)) return;
			final Point p = canvas.consumeLastPopupPoint();
			if (null == p) return;
			if (((Tree<?>)active).markNear(p.x, p.y, layer, canvas.getMagnification())) {
				Display.repaint(getLayerSet());
			}
		} else if (command.equals("Clear marks (selected Trees)")) {
			for (final Tree<?> t : selection.get(Tree.class)) {
				t.unmark();
			}
			Display.repaint(getLayerSet());
		} else if (command.equals("Join")) {
			if (!(active instanceof Tree<?>)) return;
			final List<Tree<?>> tlines = (List<Tree<?>>) selection.get(active.getClass());
			if (((Tree)active).canJoin(tlines)) {
				final int nNodes_active = ((Tree)active).getRoot().getSubtreeNodes().size();
				String warning = "";
				for (final Tree<?> t : tlines) {
					if (active == t) continue;
					if (null == t.getRoot()) {
						Utils.log("Removed empty tree #" + t.getId() + " from those to join.");
						tlines.remove(t);
						continue;
					}
					if (t.getRoot().getSubtreeNodes().size() > nNodes_active) {
						warning = "\nWARNING joining into a tree that is not the largest!";
						break;
					}
				}
				if (!Utils.check("Join these " + tlines.size() + " trees into the tree " + active + " ?" + warning)) return;
				// Record current state
				final Set<DoStep> dataedits = new HashSet<DoStep>(tlines.size());
				for (final Tree<?> tl : tlines) {
					dataedits.add(new Displayable.DoEdit(tl).init(tl, new String[]{"data"}));
				}
				getLayerSet().addChangeTreesStep(dataedits);
				//
				((Tree)active).join(tlines);
				for (final Tree<?> tl : tlines) {
					if (tl == active) continue;
					tl.remove2(false);
				}
				Display.repaint(getLayerSet());
				// Again, to record current state (just the joined tree this time)
				final Set<DoStep> dataedits2 = new HashSet<DoStep>(1);
				dataedits2.add(new Displayable.DoEdit(active).init(active, new String[]{"data"}));
				getLayerSet().addChangeTreesStep(dataedits2);
			} else {
				Utils.showMessage("Can't do", "Only one tree is selected.\nSelect more than one tree to perform a join operation!");
			}
		} else if (command.equals("Previous branch node or start")) {
			if (!(active instanceof Tree<?>)) return;
			final Point p = canvas.consumeLastPopupPoint();
			if (null == p) return;
			center(((Treeline)active).findPreviousBranchOrRootPoint(p.x, p.y, layer, canvas));
		} else if (command.equals("Next branch node or end")) {
			if (!(active instanceof Tree<?>)) return;
			final Point p = canvas.consumeLastPopupPoint();
			if (null == p) return;
			center(((Tree<?>)active).findNextBranchOrEndPoint(p.x, p.y, layer, canvas));
		} else if (command.equals("Root")) {
			if (!(active instanceof Tree<?>)) return;
			final Point p = canvas.consumeLastPopupPoint();
			if (null == p) return;
			center(((Tree)active).createCoordinate(((Tree<?>)active).getRoot()));
		} else if (command.equals("Last added node")) {
			if (!(active instanceof Tree<?>)) return;
			center(((Treeline)active).getLastAdded());
		} else if (command.equals("Last edited node")) {
			if (!(active instanceof Tree<?>)) return;
			center(((Treeline)active).getLastEdited());
		} else if (command.equals("Reverse point order")) {
			if (!(active instanceof Pipe)) return;
			getLayerSet().addDataEditStep(active);
			((Pipe)active).reverse();
			Display.repaint(Display.this.layer);
			getLayerSet().addDataEditStep(active);
		} else if (command.equals("View orthoslices")) {
			if (!(active instanceof Patch)) return;
			Display3D.showOrthoslices(((Patch)active));
		} else if (command.equals("View volume")) {
			if (!(active instanceof Patch)) return;
			Display3D.showVolume(((Patch)active));
		} else if (command.equals("Show in 3D")) {
			for (final ZDisplayable zd : selection.get(ZDisplayable.class)) {
				Display3D.show(zd.getProject().findProjectThing(zd));
			}
			// handle profile lists ...
			final HashSet<ProjectThing> hs = new HashSet<ProjectThing>();
			for (final Profile d : selection.get(Profile.class)) {
				final ProjectThing profile_list = (ProjectThing)d.getProject().findProjectThing(d).getParent();
				if (!hs.contains(profile_list)) {
					Display3D.show(profile_list);
					hs.add(profile_list);
				}
			}
		} else if (command.equals("Snap")) {
			// Take the active if it's a Patch
			if (!(active instanceof Patch)) return;
			Display.snap((Patch)active);
		} else if (command.equals("Blend") || command.equals("Blend (selected images)...")) {
			final HashSet<Patch> patches = new HashSet<Patch>(selection.get(Patch.class));
			if (patches.size() > 1) {
				final GenericDialog gd = new GenericDialog("Blending");
				gd.addCheckbox("Respect current alpha mask", true);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				Blending.blend(patches, gd.getNextBoolean());
			} else {
				IJ.log("Please select more than one overlapping image.");
			}
		} else if (command.equals("Blend (layer-wise)...")) {
			final GenericDialog gd = new GenericDialog("Blending");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.addCheckbox("Respect current alpha mask", true);
			gd.addMessage("Filter:");
			gd.addStringField("Use only images whose title matches:", "", 30);
			gd.addCheckbox("Blend visible patches only", true);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final boolean respect_alpha_mask = gd.getNextBoolean();
			final String toMatch = gd.getNextString().trim();
			final String regex = 0 == toMatch.length() ? null : ".*" + toMatch + ".*";
			final boolean visible_only = gd.getNextBoolean();
			Blending.blendLayerWise(getLayerSet().getLayers(gd.getNextChoiceIndex(), gd.getNextChoiceIndex()),
					respect_alpha_mask,
					new Filter<Patch>() {
						@Override
						public final boolean accept(final Patch patch) {
							if (visible_only && !patch.isVisible()) return false;
							if (null == regex) return true;
							return patch.getTitle().matches(regex);
						}
					});
		} else if (command.equals("Match intensities (layer-wise)...")) {
			Bureaucrat.createAndStart(new Worker.Task("Match intensities") {
				@Override
	            public void exec() {
					final MatchIntensities matching = new MatchIntensities();
					matching.invoke(getActive());
				}
			}, project);
		} else if (command.equals("Remove intensity maps (layer-wise)...")) {
			final GenericDialog gd = new GenericDialog("Remove intensity maps");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			Bureaucrat.createAndStart(new Worker.Task("Match intensities") {
				@Override
	            public void exec() {
					for (final Layer layer : getLayerSet().getLayers(gd.getNextChoiceIndex(), gd.getNextChoiceIndex())) {
						for (final Displayable p : layer.getDisplayables(Patch.class)) {
							final Patch patch = (Patch)p;
							if (patch.clearIntensityMap()) {
								patch.updateMipMaps();
							}
						}
					}
				}
			}, project);
		} else if (command.equals("Montage")) {
			final Set<Displayable> affected = new HashSet<Displayable>(selection.getAffected());
			// make an undo step!
			final LayerSet ls = layer.getParent();
			ls.addTransformStepWithData(affected);
			final Bureaucrat burro = AlignTask.alignSelectionTask( selection );
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				ls.enlargeToFit(affected);
				ls.addTransformStepWithData(affected);
			}});
		} else if (command.equals("Lens correction")) {
			final Layer la = layer;
			la.getParent().addDataEditStep(new HashSet<Displayable>(la.getParent().getDisplayables()));
			final Bureaucrat burro = DistortionCorrectionTask.correctDistortionFromSelection( selection );
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				// no means to know which where modified and from which layers!
				la.getParent().addDataEditStep(new HashSet<Displayable>(la.getParent().getDisplayables()));
			}});
		} else if (command.equals("Link images...")) {
			final GenericDialog gd = new GenericDialog("Options");
			gd.addMessage("Linking images to images (within their own layer only):");
			final String[] options = {"all images to all images", "each image with any other overlapping image"};
			gd.addChoice("Link: ", options, options[1]);
			final String[] options2 = {"selected images only", "all images in this layer", "all images in all layers, within the layer only", "all images in all layers, within and across consecutive layers"};
			gd.addChoice("Apply to: ", options2, options2[0]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Layer lay = layer;
			final HashSet<Displayable> ds = new HashSet<Displayable>(lay.getParent().getDisplayables());
			lay.getParent().addDataEditStep(ds, new String[]{"data"});
			final boolean overlapping_only = 1 == gd.getNextChoiceIndex();
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
						final Collection<Displayable> acoll = la.getDisplayables(Patch.class);
						Patch.crosslink(acoll, overlapping_only);
						coll.addAll(acoll);
					}
					break;
				case 3:
					final ArrayList<Layer> layers = lay.getParent().getLayers();
					Collection<Displayable> lc1 = layers.get(0).getDisplayables(Patch.class);
					if (lay == layers.get(0)) coll = lc1;
					for (int i=1; i<layers.size(); i++) {
						final Collection<Displayable> lc2 = layers.get(i).getDisplayables(Patch.class);
						if (null == coll && Display.this.layer == layers.get(i)) coll = lc2;
						final Collection<Displayable> both = new ArrayList<Displayable>();
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
				final Collection<Displayable> ds = selection.getSelected(Patch.class);
				for (final Displayable d : ds) {
					d.unlink();
				}
				Display.updateCheckboxes(ds, DisplayablePanel.LINK_STATE);
			}
		} else if (command.equals("Unlink all")) {
			if (Utils.check("Really unlink all objects from all layers?")) {
				final Collection<Displayable> ds = layer.getParent().getDisplayables();
				for (final Displayable d : ds) {
					d.unlink();
				}
				Display.updateCheckboxes(ds, DisplayablePanel.LINK_STATE);
			}
		} else if (command.equals("Calibration...")) {
			try {
				IJ.run(canvas.getFakeImagePlus(), "Properties...", "");
				Display.updateTitle(getLayerSet());
				project.getLayerTree().updateUILater(); // repaint layer names
			} catch (final RuntimeException re) {
				Utils.log2("Calibration dialog canceled.");
			}
		} else if (command.equals("Grid overlay...")) {
			if (null == gridoverlay) gridoverlay = new GridOverlay();
			gridoverlay.setup(canvas.getFakeImagePlus().getRoi());
			canvas.repaint(false);
		} else if (command.equals("Enhance contrast (selected images)...")) {
			final Layer la = layer;
			final ArrayList<Displayable> selected = selection.getSelected(Patch.class);
			final HashSet<Displayable> ds = new HashSet<Displayable>(selected);
			la.getParent().addDataEditStep(ds);
			final Displayable active = Display.this.getActive();
			final Patch ref = active.getClass() == Patch.class ? (Patch)active : null;
			final Bureaucrat burro = getProject().getLoader().enhanceContrast(selected, ref);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				la.getParent().addDataEditStep(ds);
			}});
		} else if (command.equals("Enhance contrast layer-wise...")) {
			// ask for range of layers
			final GenericDialog gd = new GenericDialog("Choose range");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final java.util.List<Layer> layers = layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1); // exclusive end
			final HashSet<Displayable> ds = new HashSet<Displayable>();
			for (final Layer l : layers) ds.addAll(l.getDisplayables(Patch.class));
			getLayerSet().addDataEditStep(ds);
			final Bureaucrat burro = project.getLoader().enhanceContrast(layers);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Adjust image filters (selected images)")) {
			if (selection.isEmpty() || !(active instanceof Patch)) return;
			FilterEditor.GUI(selection.get(Patch.class), (Patch)active);
		} else if (command.equals("Set Min and Max layer-wise...")) {
			final Displayable active = getActive();
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
			final ArrayList<Displayable> al = new ArrayList<Displayable>();
			for (final Layer la : layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1)) { // exclusive end
				al.addAll(la.getDisplayables(Patch.class));
			}
			final HashSet<Displayable> ds = new HashSet<Displayable>(al);
			getLayerSet().addDataEditStep(ds);
			final Bureaucrat burro = project.getLoader().setMinAndMax(al, min, max);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Set Min and Max (selected images)...")) {
			final Displayable active = getActive();
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
			final Bureaucrat burro = project.getLoader().setMinAndMax(selection.getSelected(Patch.class), min, max);
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Adjust min and max (selected images)...")) {
			adjustMinAndMaxGUI();
		} else if (command.equals("Mask image borders (layer-wise)...")) {
			final GenericDialog gd = new GenericDialog("Mask borders");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.addMessage("Borders:");
			gd.addNumericField("left: ", 6, 2);
			gd.addNumericField("top: ", 6, 2);
			gd.addNumericField("right: ", 6, 2);
			gd.addNumericField("bottom: ", 6, 2);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Collection<Layer> layers = layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1);
			final HashSet<Displayable> ds = new HashSet<Displayable>();
			for (final Layer l : layers) ds.addAll(l.getDisplayables(Patch.class));
			getLayerSet().addDataEditStep(ds);
			final Bureaucrat burro = project.getLoader().maskBordersLayerWise(layers, (int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Mask image borders (selected images)...")) {
			final GenericDialog gd = new GenericDialog("Mask borders");
			gd.addMessage("Borders:");
			gd.addNumericField("left: ", 6, 2);
			gd.addNumericField("top: ", 6, 2);
			gd.addNumericField("right: ", 6, 2);
			gd.addNumericField("bottom: ", 6, 2);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Collection<Displayable> patches = selection.getSelected(Patch.class);
			final HashSet<Displayable> ds = new HashSet<Displayable>(patches);
			getLayerSet().addDataEditStep(ds);
			final Bureaucrat burro = project.getLoader().maskBorders(patches, (int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
			burro.addPostTask(new Runnable() { @Override
            public void run() {
				getLayerSet().addDataEditStep(ds);
			}});
		} else if (command.equals("Remove alpha masks (layer-wise)...")) {
			final GenericDialog gd = new GenericDialog("Remove alpha masks");
			Utils.addLayerRangeChoices(Display.this.layer, gd);
			gd.addCheckbox("Visible only", true);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Collection<Layer> layers = layer.getParent().getLayers().subList(gd.getNextChoiceIndex(), gd.getNextChoiceIndex() +1);
			final boolean visible_only = gd.getNextBoolean();
			final Collection<Patch> patches = new ArrayList<Patch>();
			for (final Layer l : layers) {
				patches.addAll((Collection<Patch>)(Collection)l.getDisplayables(Patch.class, visible_only));
			}
			Display.removeAlphaMasks(patches);
		} else if (command.equals("Remove alpha masks (selected images)...")) {
			Display.removeAlphaMasks(selection.get(Patch.class));
		} else if (command.equals("Split images under polyline ROI")) {
			final Roi roi = canvas.getFakeImagePlus().getRoi();
			if (null == roi) return;
			if (!(roi.getType() == Roi.POLYLINE || roi.getType() == Roi.FREELINE)) {
				Utils.showMessage("Need a polyline or freeline ROI, not just any ROI!");
				return;
			}
			if (!Utils.check("Really split images under the ROI?")) {
				return;
			}
			// OK identify images whose contour intersects the ROI
			final Set<Displayable> col = new HashSet<Displayable>();
			final PolygonRoi proi = (PolygonRoi)roi; // FreehandRoi is a subclass of PolygonRoi
			final int[] x = proi.getXCoordinates(),
				  y = proi.getYCoordinates();
			final Rectangle b = proi.getBounds();
			final Polygon[] pols = new Polygon[proi.getNCoordinates() -1];
			for (int i=0; i<pols.length; i++) {
				pols[i] = new Polygon(new int[]{b.x + x[i], b.x + x[i] + 1, b.x + x[i+1], b.x + x[i+1] + 1},
									  new int[]{b.y + y[i], b.y + y[i], b.y + y[i+1], b.y + y[i+1]}, 4);
			}
			for (final Patch p : getLayer().getAll(Patch.class)) {
				if (!p.isVisible()) continue;
				final Area a = p.getArea();
				for (int i=0; i<pols.length; i++) {
					final Area c = new Area(pols[i]);
					c.intersect(a);
					if (M.isEmpty(c)) continue;
					// Else, add it:
					col.add(p);
					break;
				}
			}

			if (col.isEmpty()) {
				Utils.showMessage("No images intersect the ROI!");
				return;
			}
			// Create the area that will be "one half"
			// and overlay it in the display, repaint, and ask for "yes/no" to continue.

			for (int i=1; i<proi.getNCoordinates(); i++) {
				for (int k=i+2; k<proi.getNCoordinates(); k++) { // skip the immediate next segment
					// check if the two segments intersect
					if (null != M.computeSegmentsIntersection(x[i-1], y[i-1], x[i], y[i],
															  x[k-1], y[k-1], x[k], y[k])) {
						Utils.showMessage("Cannot split images with a polygon ROI that intersects itself!");
						return;
					}
				}
			}
			final Area[] as = M.splitArea(new Area(getLayerSet().get2DBounds()), proi, getLayerSet().get2DBounds());
			final Color[] c = new Color[]{Color.blue, Color.red};
			int i = 0;
			for (final Area a : as) {
				//Utils.log2("Added overlay " + i + " with color " + c[i] + " and area " + AreaCalculations.area(a.getPathIterator(null)));
				getLayer().getOverlay().add(a, c[i++], null, true, false, 0.4f);
			}
			Display.repaint(getLayer());

			final YesNoDialog yn = new YesNoDialog(frame, "Check", "Does the splitting match your expectations?\nPush 'yes' to split the images.", false);
			yn.setModal(false);
			for (final WindowListener wl : yn.getWindowListeners()) yn.removeWindowListener(wl);
			yn.setClosingTask(new Runnable() {
				@Override
				public void run() {
					try {
						// Remove overlay shapes
						for (final Area a : as) {
							getLayer().getOverlay().remove(a);
						}
						if (!yn.yesPressed()) {
							Utils.log2("Pushed 'no'");
							return;
						}
						// Split intersecting patches
						// Duplicate each intersecting patch, and assign a[0] to the original and a[1] to the copy, as mask.
						Bureaucrat.createAndStart(new Worker.Task("Spliting images") {
							@Override
                            public void exec() {
								final Roi r1 = new ShapeRoi(as[0]),
										  r2 = new ShapeRoi(as[1]);
								final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
								for (final Patch p : (Collection<Patch>)(Collection)col) {
									final Patch copy = (Patch) p.clone(p.getProject(), false);
									p.addAlphaMask(r1, 0);
									copy.addAlphaMask(r2, 0);
									fus.add(p.updateMipMaps());
									fus.add(copy.updateMipMaps());
									p.getLayer().add(copy); // after submitting mipmaps, since it will get added to all Displays and repainted.
								}
								Utils.wait(fus);
							}
						}, project);
					} catch (final Throwable t) {
						IJError.print(t);
					} finally {
						yn.dispose();
						Display.repaint(getLayer());
					}
				}
			});
			yn.setVisible(true);
		} else if (command.equals("Duplicate")) {
			// only Patch and DLabel, i.e. Layer-only resident objects that don't exist in the Project Tree
			final HashSet<Class> accepted = new HashSet<Class>();
			accepted.add(Patch.class);
			accepted.add(DLabel.class);
			accepted.add(Stack.class);
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
					if (d instanceof ZDisplayable) {
						d.getLayerSet().add((ZDisplayable)d.clone());
					} else {
						d.getLayer().add(d.clone());
					}
				}
				getLayerSet().addChangeTreesStep();
			} else if (selected.size() > 0) {
				Utils.log("Can only duplicate images and text labels.\nDuplicate *other* objects in the Project Tree.\n");
			}
		} else if (command.equals("Create subproject")) {
			// Choose a 2D rectangle
			final Roi roi = canvas.getFakeImagePlus().getRoi();
			Rectangle bounds;
			if (null != roi) {
				if (!Utils.check("Use bounds as defined by the ROI:\n" + roi.getBounds() + " ?")) return;
				bounds = roi.getBounds();
			} else bounds = getLayerSet().get2DBounds();
			// Choose a layer range, and whether to ignore hidden images
			final GenericDialog gd = new GenericDialog("Choose layer range");
			Utils.addLayerRangeChoices(layer, gd);
			gd.addCheckbox("Ignore hidden images", true);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final Layer first = layer.getParent().getLayer(gd.getNextChoiceIndex());
			final Layer last = layer.getParent().getLayer(gd.getNextChoiceIndex());
			final boolean ignore_hidden_patches = gd.getNextBoolean();
			final Project sub = getProject().createSubproject(bounds, first, last, ignore_hidden_patches);
			if (null == sub) {
				Utils.log("ERROR: failed to create subproject.");
				return;
			}
			final LayerSet subls = sub.getRootLayerSet();
			Display.createDisplay(sub, subls.getLayer(0));
		} else if (command.startsWith("Image stack under selected Arealist")) {
			if (null == active || active.getClass() != AreaList.class) return;
			final GenericDialog gd = new GenericDialog("Stack options");
			final String[] types = {"8-bit", "16-bit", "32-bit", "RGB"};
			gd.addChoice("type:", types, types[0]);
			gd.addSlider("Scale: ", 1, 100, 100);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final int type;
			switch (gd.getNextChoiceIndex()) {
				case 0: type = ImagePlus.GRAY8; break;
				case 1: type = ImagePlus.GRAY16; break;
				case 2: type = ImagePlus.GRAY32; break;
				case 3: type = ImagePlus.COLOR_RGB; break;
				default: type = ImagePlus.GRAY8; break;
			}
			final ImagePlus imp = ((AreaList)active).getStack(type, gd.getNextNumber()/100);
			if (null != imp) imp.show();
		} else if (command.equals("Fly through selected Treeline/AreaTree")) {
			if (null == active || !(active instanceof Tree<?>)) return;
			Bureaucrat.createAndStart(new Worker.Task("Creating fly through", true) {
				@Override
                public void exec() {
					final GenericDialog gd = new GenericDialog("Fly through");
					gd.addNumericField("Width", 512, 0);
					gd.addNumericField("Height", 512, 0);
					final String[] types = new String[]{"8-bit gray", "Color RGB"};
					gd.addChoice("Image type", types, types[0]);
					gd.addSlider("scale", 0, 100, 100);
					gd.addCheckbox("save to file", false);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					final int w = (int)gd.getNextNumber();
					final int h = (int)gd.getNextNumber();
					final int type = 0 == gd.getNextChoiceIndex() ? ImagePlus.GRAY8 : ImagePlus.COLOR_RGB;
					final double scale = gd.getNextNumber();
					if (w <=0 || h <=0) {
						Utils.log("Invalid width or height: " + w + ", " + h);
						return;
					}
					if (0 == scale || Double.isNaN(scale)) {
						Utils.log("Invalid scale: " + scale);
						return;
					}
					String dir = null;
					if (gd.getNextBoolean()) {
						final DirectoryChooser dc = new DirectoryChooser("Target directory");
						dir = dc.getDirectory();
						if (null == dir) return; // canceled
						dir = Utils.fixDir(dir);
					}
					final ImagePlus imp = ((Tree<?>)active).flyThroughMarked(w, h, scale/100, type, dir);
					if (null == imp) {
						Utils.log("Mark a node first!");
						return;
					}
					imp.show();
				}
			}, project);
		} else if (command.startsWith("Arealists as labels")) {
			final GenericDialog gd = new GenericDialog("Export labels");
			gd.addSlider("Scale: ", 1, 100, 100);
			final String[] options = {"All area list", "Selected area lists"};
			gd.addChoice("Export: ", options, options[0]);
			Utils.addLayerRangeChoices(layer, gd);
			gd.addCheckbox("Visible only", true);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final float scale = (float)(gd.getNextNumber() / 100);
			final java.util.List<Displayable> al = (java.util.List<Displayable>)(0 == gd.getNextChoiceIndex() ? layer.getParent().getZDisplayables(AreaList.class) : selection.getSelectedSorted(AreaList.class));
			if (null == al) {
				Utils.log("No area lists found to export.");
				return;
			}
			// Generics are ... a pain? I don't understand them? They fail when they shouldn't? And so easy to workaround that they are a shame?

			final int first = gd.getNextChoiceIndex();
			final int last  = gd.getNextChoiceIndex();
			final boolean visible_only = gd.getNextBoolean();
			if (-1 != command.indexOf("(amira)")) {
				AreaList.exportAsLabels(al, canvas.getFakeImagePlus().getRoi(), scale, first, last, visible_only, true, true);
			} else if (-1 != command.indexOf("(tif)")) {
				AreaList.exportAsLabels(al, canvas.getFakeImagePlus().getRoi(), scale, first, last, visible_only, false, false);
			}
		} else if (command.equals("Project properties...")) {
			project.adjustProperties();
		} else if (command.equals("Release memory...")) {
			Bureaucrat.createAndStart(new Worker("Releasing memory") {
				@Override
                public void run() {
					startedWorking();
					try {
			final GenericDialog gd = new GenericDialog("Release Memory");
			final int max = (int)(IJ.maxMemory() / 1000000);
			gd.addSlider("Megabytes: ", 0, max, max/2);
			gd.showDialog();
			if (!gd.wasCanceled()) {
				final int n_mb = (int)gd.getNextNumber();
				project.getLoader().releaseToFit((long)n_mb*1000000);
			}
					} catch (final Throwable e) {
						IJError.print(e);
					} finally {
						finishedWorking();
					}
				}
			}, project);
		} else if (command.equals("Create sibling project with retiled layers")) {
			final GenericDialog gd = new GenericDialog("Export flattened layers");
			gd.addNumericField("Tile_width", 2048, 0);
			gd.addNumericField("Tile_height", 2048, 0);
			final String[] types = new String[]{"16-bit", "RGB color"};
			gd.addChoice("Export_image_type", types, types[0]);
			gd.addCheckbox("Create mipmaps", true);
			gd.addNumericField("Number_of_threads_to_use", Runtime.getRuntime().availableProcessors(), 0);
			gd.showDialog();
			if (gd.wasCanceled()) return;

			final DirectoryChooser dc = new DirectoryChooser("Choose target folder");
			final String folder = dc.getDirectory();
			if (null == folder) return;

			final int tileWidth = (int)gd.getNextNumber(),
			          tileHeight = (int)gd.getNextNumber();
			if (tileWidth < 0 || tileHeight < 0) {
				Utils.showMessage("Invalid tile sizes: " + tileWidth + ", " + tileHeight);
				return;
			}

			if (tileWidth != tileHeight) {
				if (!Utils.check("The tile width (" + tileWidth + ") differs from the tile height (" + tileHeight + ").\nContinue anyway?")) {
					return;
				}
			}

			final int imageType = 0 == gd.getNextChoiceIndex() ? ImagePlus.GRAY16 : ImagePlus.COLOR_RGB;
			final boolean createMipMaps = gd.getNextBoolean();
			final int nThreads = (int)gd.getNextNumber();

			Bureaucrat.createAndStart(new Worker.Task("Export flattened sibling project") {
				@Override
				public void exec() {
					try {
						ProjectTiler.createRetiledSibling(
								project,
								folder,
								tileWidth,
								tileHeight,
								imageType,
								true,
								nThreads,
								createMipMaps);
					} catch (final Throwable t) {
						Utils.showMessage("ERROR: " + t);
						IJError.print(t);
					}
				}
			}, project);

		} else if (command.equals("Flush image cache")) {
			Loader.releaseAllCaches();
		} else if (command.equals("Regenerate all mipmaps")) {
			project.getLoader().regenerateMipMaps(getLayerSet().getDisplayables(Patch.class));
		} else if (command.equals("Regenerate mipmaps (selected images)")) {
			project.getLoader().regenerateMipMaps(selection.getSelected(Patch.class));
		} else if (command.equals("Tags...")) {
			// get a file first
			final File f = Utils.chooseFile(null, "tags", ".xml");
			if (null == f) return;
			if (!Utils.saveToFile(f, getLayerSet().exportTags())) {
				Utils.logAll("ERROR when saving tags to file " + f.getAbsolutePath());
			}
		} else if (command.equals("Tags ...")) {
			final String[] ff = Utils.selectFile("Import tags");
			if (null == ff) return;
			final GenericDialog gd = new GenericDialog("Import tags");
			final String[] modes = new String[]{"Append to current tags", "Replace current tags"};
			gd.addChoice("Import tags mode:", modes, modes[0]);
			gd.addMessage("Replacing current tags\nwill remove all tags\n from all nodes first!");
			gd.showDialog();
			if (gd.wasCanceled()) return;
			getLayerSet().importTags(new StringBuilder(ff[0]).append('/').append(ff[1]).toString(), 1 == gd.getNextChoiceIndex());
		} else if (command.equals("Connectivity graph...")) {
			Bureaucrat.createAndStart(new Worker.Task("Connectivity graph") {
				@Override
                public void exec() {
					Graph.extractAndShowGraph(getLayerSet());
				}
			}, getProject());
		} else if (command.equals("NeuroML...")) {
			final GenericDialog gd = new GenericDialog("Export NeuroML");
			final String[] a = new String[]{"NeuroML (arbors and synapses)", "MorphML (arbors)"};
			gd.addChoice("Type:", a, a[0]);
			final String[] b = new String[]{"All treelines and areatrees", "Selected treelines and areatrees"};
			gd.addChoice("Export:", b, b[0]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			final int type = gd.getNextChoiceIndex();
			final int export = gd.getNextChoiceIndex();
			//
			final SaveDialog sd = new SaveDialog("Choose .mml file", null, ".mml");
			final String filename = sd.getFileName();
			if (null == filename) return; // canceled
			final File f = new File(sd.getDirectory() + filename);
			//
			Bureaucrat.createAndStart(new Worker.Task("Export NeuroML") {
				@Override
                public void exec() {
					OutputStreamWriter w = null;
					try {
						final Set<Tree<?>> trees = new HashSet<Tree<?>>();
						Collection<? extends Displayable> ds = null;
						switch (export) {
						case 0:
							ds = getLayerSet().getZDisplayables();
							break;
						case 1:
							ds = selection.getSelected();
							break;
						}
						for (final Displayable d : ds) {
							if (d.getClass() == Treeline.class || d.getClass() == AreaTree.class) {
								trees.add((Tree<?>)d);
							}
						}
						if (trees.isEmpty()) {
							Utils.showMessage("No trees to export!");
							return;
						}
						//
						w = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(f), 65536), "8859_1"); // encoding in Latin 1 (for macosx not to mess around
						//
						switch (type) {
						case 0:
							NeuroML.exportNeuroML(trees, w);
							break;
						case 1:
							NeuroML.exportMorphML(trees, w);
							break;
						}
						//
						w.flush();
						w.close();
						//
					} catch (final Throwable t) {
						IJError.print(t);
						try {
							if (null != w) w.close();
						} catch (final Exception ee) { IJError.print(ee); }
					}
				}
			}, getProject());
		} else if (command.equals("Measure")) {
			if (selection.isEmpty()) {
				Utils.log("Nothing selected to measure!");
				return;
			}
			selection.measure();
		} else if (command.equals("Area interpolation options...")) {
			final GenericDialog gd = new GenericDialog("Area interpolation");
			final boolean a = project.getBooleanProperty(AreaUtils.always_interpolate_areas_with_distance_map);
			gd.addCheckbox("Always use distance map method", a);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			project.setProperty(AreaUtils.always_interpolate_areas_with_distance_map, gd.getNextBoolean() ? "true" : null);
		} else {
			Utils.log2("Display: don't know what to do with command " + command);
		}
		}});
	}

	public void adjustMinAndMaxGUI() {
		final List<Displayable> list = selection.getSelected(Patch.class);
		if (list.isEmpty()) {
			Utils.log("No images selected!");
			return;
		}
		Bureaucrat.createAndStart(new Worker.Task("Init contrast adjustment") {
			@Override
            public void exec() {
				try {
					setMode(new ContrastAdjustmentMode(Display.this, list));
				} catch (final Exception e) {
					e.printStackTrace();
					Utils.log("All images must be of the same type!");
				}
			}
		}, list.get(0).getProject());
	}

	public void adjustMeasurementOptions() {
		final GenericDialog gd = new GenericDialog("Measurement options");
		gd.addMessage("The point interdistance for resampling the contours\nof AreaLists when measuring areas and volumes.\nThe recommended value is one calibrated unit, in pixels.\nThe default value is one pixel.");
		gd.addNumericField("Resolution", project.getProperty("measurement_resampling_delta", 1.0f), 1, 10, "pixels");
		gd.addMessage("Whether to measure the largest diameter of an AreaList\nwhich is the largest distance between any two points of its contours.\nA very expensive operation, by default it is turned off.");
		final boolean diameters = project.getBooleanProperty("measure_largest_diameter");
		gd.addCheckbox("Measure_largest_diameter", diameters);
		gd.showDialog();

		if (gd.wasCanceled()) return;

		final float delta = (float)gd.getNextNumber();
		if (Float.isNaN(delta) || delta <= 0) {
			Utils.log("Rejected resampling resolution of " + delta + ", which should be larger than zero.");
		} else {
			project.setProperty("measurement_resampling_delta", Float.toString(delta));
		}

		final boolean diameters2 = gd.getNextBoolean();
		if (diameters != diameters2) {
			project.setProperty("measure_largest_diameter", Boolean.toString(diameters2));
		}
	}

	/** Pops up a dialog to adjust the alpha, visible, color, locked and compositeMode of all Displayables in {@code col}.
	 *
	 * @param col
	 */
	public void adjustGroupProperties(final Collection<Displayable> col) {
		if (col.isEmpty()) return;
		final Displayable first = col.iterator().next();
		final GenericDialog gd = new GenericDialog("Properties of selected");
		gd.addSlider("alpha: ", 0, 100, (int)(first.getAlpha()*100));
		gd.addCheckbox("visible", first.isVisible());
		gd.addSlider("Red: ", 0, 255, first.getColor().getRed());
		gd.addSlider("Green: ", 0, 255, first.getColor().getGreen());
		gd.addSlider("Blue: ", 0, 255, first.getColor().getBlue());
		gd.addCheckbox("locked", first.isLocked2());
		gd.addChoice( "composite mode: ", Displayable.compositeModes, Displayable.compositeModes[ first.getCompositeMode() ] );
		gd.showDialog();

		if (gd.wasCanceled()) return;

		final HashSet<Displayable> hs = new HashSet<Displayable>(col);
		final String[] fields = new String[]{"alpha", "visible", "color", "locked", "compositeMode"};
		getLayerSet().addDataEditStep(hs, fields);

		final float alpha = (float)gd.getNextNumber();
		final boolean visible = gd.getNextBoolean();
		final Color color = new Color((int)gd.getNextNumber(), (int)gd.getNextNumber(), (int)gd.getNextNumber());
		final boolean locked = gd.getNextBoolean();
		final byte compositeMode = (byte)gd.getNextChoiceIndex();

		for (final Displayable d : col) {
			d.setAlpha(alpha);
			d.setVisible(visible);
			d.setColor(color);
			d.setLocked(locked);
			d.setCompositeMode(compositeMode);
		}

		// Record the current state
		getLayerSet().addDataEditStep(hs, fields);
	}

	public void adjustProperties() {
		final GenericDialog gd = new GenericDialog("Properties", Display.this.frame);
		//gd.addNumericField("layer_scroll_step: ", this.scroll_step, 0);
		gd.addSlider("layer_scroll_step: ", 1, layer.getParent().size(), Display.this.scroll_step);
		gd.addChoice("snapshots_mode", LayerSet.snapshot_modes, LayerSet.snapshot_modes[layer.getParent().getSnapshotsMode()]);
		gd.addCheckbox("prefer_snapshots_quality", layer.getParent().snapshotsQuality());
		final Loader lo = getProject().getLoader();
		final boolean using_mipmaps = lo.isMipMapsRegenerationEnabled();
		gd.addCheckbox("enable_mipmaps", using_mipmaps);
		gd.addCheckbox("enable_layer_pixels virtualization", layer.getParent().isPixelsVirtualizationEnabled());
		final double max = layer.getParent().getLayerWidth() < layer.getParent().getLayerHeight() ? layer.getParent().getLayerWidth() : layer.getParent().getLayerHeight();
		gd.addSlider("max_dimension of virtualized layer pixels: ", 0, max, layer.getParent().getPixelsMaxDimension());
		gd.addCheckbox("Show arrow heads in Treeline/AreaTree", layer.getParent().paint_arrows);
		gd.addCheckbox("Show edge confidence boxes in Treeline/AreaTree", layer.getParent().paint_edge_confidence_boxes);
		gd.addSlider("Stroke width (Treeline, AreaTree, Ball)", 1.0, 10.0, DisplayCanvas.DEFAULT_STROKE.getLineWidth());
		gd.addCheckbox("Show color cues", layer.getParent().color_cues);
		gd.addSlider("+/- layers to color cue", 0, 10, layer.getParent().n_layers_color_cue);
		gd.addCheckbox("Show color cues for areas", layer.getParent().area_color_cues);
		gd.addCheckbox("Use red/blue for color cues", layer.getParent().use_color_cue_colors);
		gd.addCheckbox("Prepaint images", layer.getParent().prepaint);
		gd.addSlider("Preload ahead from sections: ", 0, layer.getParent().size(), layer.getParent().preload_ahead);
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
		final boolean using_mipmaps2 = gd.getNextBoolean();
		if (using_mipmaps2 == using_mipmaps) {
			// Nothing changed
		} else if (!using_mipmaps2 && using_mipmaps) {
			// Desactivate mipmaps
			lo.setMipMapsRegeneration(false);
			lo.flushMipMaps(true);
		} else if (using_mipmaps2 && !using_mipmaps) {
			// Reactivate mipmaps
			lo.setMipMapsRegeneration(true);
			lo.generateMipMaps(layer.getParent().getDisplayables(Patch.class));
		}
		//
		layer.getParent().setPixelsVirtualizationEnabled(gd.getNextBoolean());
		layer.getParent().setPixelsMaxDimension((int)gd.getNextNumber());
		layer.getParent().paint_arrows = gd.getNextBoolean();
		layer.getParent().paint_edge_confidence_boxes = gd.getNextBoolean();
		DisplayCanvas.DEFAULT_STROKE = new BasicStroke((float)Math.max(1, gd.getNextNumber()), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER);
		layer.getParent().color_cues = gd.getNextBoolean();
		layer.getParent().n_layers_color_cue = (int)gd.getNextNumber();
		layer.getParent().area_color_cues = gd.getNextBoolean();
		layer.getParent().use_color_cue_colors = gd.getNextBoolean();
		layer.getParent().prepaint = gd.getNextBoolean();
		layer.getParent().preload_ahead = (int) Math.min(gd.getNextNumber(), layer.getParent().size());
		Display.repaint(layer.getParent());
	}

	private static class UpdateDimensionField implements TextListener {
		final TextField width, height, scale;
		final Scrollbar bar;
		final int initial_width, initial_height;
		UpdateDimensionField(final int initial_width, final int initial_height, final TextField width, final TextField height, final TextField scale, final Scrollbar bar) {
			this.initial_width = initial_width;
			this.initial_height = initial_height;
			this.width = width;
			this.height = height;
			this.scale = scale;
			this.bar = bar;
		}
		@Override
        public void textValueChanged(final TextEvent e) {
			try {
				final TextField source = (TextField) e.getSource();
				if (scale == source && (scale.isFocusOwner() || bar.isFocusOwner())) {
					final double sc = Double.parseDouble(scale.getText()) / 100;
					// update both
					width.setText(Integer.toString((int) (sc * initial_width + 0.5)));
					height.setText(Integer.toString((int) (sc * initial_height + 0.5)));
				} else if (width == source && width.isFocusOwner()) {
					/*
					final int width = Integer.toString((int) (width.getText() + 0.5));
					final double sc = width / (double)initial_width;
					scale.setText(Integer.toString((int)(sc * 100 + 0.5)));
					height.setText(Integer.toString((int)(sc * initial_height + 0.5)));
					*/
					set(width, height, initial_width, initial_height);
				} else if (height == source && height.isFocusOwner()) {
					set(height, width, initial_height, initial_width);
				}
			} catch (final NumberFormatException nfe) {
				Utils.logAll("Unparsable number: " + nfe.getMessage());
			} catch (final Exception ee) {
				IJError.print(ee);
			}
		}
		private void set(final TextField source, final TextField target, final int initial_source, final int initial_target) {
			final int dim = (int) ((Double.parseDouble(source.getText()) + 0.5));
			final double sc = dim / (double)initial_source;
			scale.setText(Utils.cutNumber(sc * 100, 3));
			target.setText(Integer.toString((int)(sc * initial_target + 0.5)));
		}
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
		final Worker worker = new Worker("Import image") {  /// all this verbosity is what happens when functions are not first class citizens. I could abstract it away by passing a string name "importImage" and invoking it with reflection, but that is an even bigger PAIN
			@Override
            public void run() {
				startedWorking();
				try {
		///

		final Rectangle srcRect = canvas.getSrcRect();
		final int x = srcRect.x + srcRect.width / 2;
		final int y = srcRect.y + srcRect.height/ 2;
		final Patch p = project.getLoader().importImage(project, x, y);
		if (null == p) {
			finishedWorking();
			Utils.showMessage("Could not open the image.");
			return;
		}

		Display.this.getLayerSet().addLayerContentStep(layer);

		layer.add(p); // will add it to the proper Displays

		Display.this.getLayerSet().addLayerContentStep(layer);

		///
				} catch (final Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, getProject());
	}

	protected Bureaucrat importNextImage() {
		final Worker worker = new Worker("Import image") {  /// all this verbosity is what happens when functions are not first class citizens. I could abstract it away by passing a string name "importImage" and invoking it with reflection, but that is an even bigger PAIN
			@Override
            public void run() {
				startedWorking();
				try {

		final Rectangle srcRect = canvas.getSrcRect();
		final int x = srcRect.x + srcRect.width / 2;// - imp.getWidth() / 2;
		final int y = srcRect.y + srcRect.height/ 2;// - imp.getHeight()/ 2;
		final Patch p = project.getLoader().importNextImage(project, x, y);
		if (null == p) {
			Utils.showMessage("Could not open next image.");
			finishedWorking();
			return;
		}

		Display.this.getLayerSet().addLayerContentStep(layer);

		layer.add(p); // will add it to the proper Displays

		Display.this.getLayerSet().addLayerContentStep(layer);

				} catch (final Exception e) {
					IJError.print(e);
				}
				finishedWorking();
			}
		};
		return Bureaucrat.createAndStart(worker, getProject());
	}


	/** Make the given channel have the given alpha (transparency). */
	public void setChannel(final int c, final float alpha) {
		final int a = (int)(255 * alpha);
		final int l = (c_alphas&0xff000000)>>24;
		final int r = (c_alphas&0xff0000)>>16;
                final int g = (c_alphas&0xff00)>>8;
		final int b =  c_alphas&0xff;
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
	public void setActiveChannel(final Channel channel) {
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
	static public void showCentered(final Layer layer, final Displayable displ, final boolean select, final boolean shift_down) {
		// see if the given layer belongs to the layer set being displayed
		Display display = front; // to ensure thread consistency to some extent
		if (null == display || display.layer.getParent() != layer.getParent()) {
			display = new Display(layer.getProject(), layer, displ); // gets set to front
		}
		display.show(layer, displ, select, shift_down);
	}
	/** Set this Display to show the specific layer, centered at the @param displ, and perhaps selected,
	 *  adding to the selection instead of clearing it if @param shift_down is true. */
	public void show(final Layer layer, final Displayable displ, final boolean select, final boolean shift_down) {
		if (this.layer != layer) {
			setLayer(layer);
		}
		if (select) {
			if (!shift_down) selection.clear();
			selection.add(displ);
		} else {
			selection.clear();
		}
		showCentered(displ);
	}

	/** Center the view, if possible, on x,y. It's not possible when zoomed out, in which case it will try to do its best. */
	public final void center(final double x, final double y) {
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			final Rectangle r = (Rectangle)canvas.getSrcRect().clone();
			r.x = (int)x - r.width/2;
			r.y = (int)y - r.height/2;
			canvas.center(r, canvas.getMagnification());
		}});
	}

	public final void center(final Coordinate<?> c) {
		if (null == c) return;
		slt.set(c.layer);
		center(c.x, c.y);
	}

	public final void centerIfNotWithinSrcRect(final Coordinate<?> c) {
		if (null == c) return;
		slt.set(c.layer);
		final Rectangle srcRect = canvas.getSrcRect();
		if (srcRect.contains((int)(c.x+0.5), (int)(c.y+0.5))) return;
		center(c.x, c.y);
	}

	public final void animateBrowsingTo(final Coordinate<?> c) {
		if (null == c) return;
		final double padding = 50/canvas.getMagnification(); // 50 screen pixels
		canvas.animateBrowsing(new Rectangle((int)(c.x - padding), (int)(c.y - padding), (int)(2*padding), (int)(2*padding)), c.layer);
	}

	static public final void centerAt(final Coordinate c) {
		centerAt(c, false, false);
	}
	static public final void centerAt(final Coordinate<Displayable> c, final boolean select, final boolean shift_down) {
		if (null == c) return;
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			Layer la = c.layer;
			if (null == la) {
				if (null == c.object) return;
				la = c.object.getProject().getRootLayerSet().getLayer(0);
				if (null == la) return; // nothing to center on
			}
			Display display = front;
			if (null == display || la.getParent() != display.getLayerSet()) {
				display = new Display(la.getProject(), la); // gets set to front
			}
			display.center(c);

			if (select) {
				if (!shift_down) display.selection.clear();
				display.selection.add(c.object);
			}
		}});
	}

	private final void showCentered(final Displayable displ) {
		if (null == displ) return;
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			displ.setVisible(true);
			final Rectangle box = displ.getBoundingBox();

			if (0 == box.width && 0 == box.height) {
				box.width = 100; // old: (int)layer.getLayerWidth();
				box.height = 100; // old: (int)layer.getLayerHeight();
			} else if (0 == box.width) {
				box.width = box.height;
			} else if (0 == box.height) {
				box.height = box.width;
			}

			canvas.showCentered(box);
			ht_tabs.get(displ.getClass()).scrollToShow(displ);
			if (displ instanceof ZDisplayable) {
				// scroll to first layer that has a point
				final ZDisplayable zd = (ZDisplayable)displ;
				setLayer(zd.getFirstLayer());
			}
		}});
	}

	@Override
    public void eventOccurred(final int eventID) {
		if (IJEventListener.FOREGROUND_COLOR_CHANGED == eventID) {
			if (this != front || null == active || !project.isInputEnabled()) return;
			selection.setColor(Toolbar.getForegroundColor());
			Display.repaint(front.layer, selection.getBox(), 0);
		} else if (IJEventListener.TOOL_CHANGED == eventID) {
			Display.repaintToolbar();
		}
	}

	public void imageClosed(final ImagePlus imp) {}
	public void imageOpened(final ImagePlus imp) {}

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
		final Collection<Display> ds = al_displays;
		if (null == front && ds.size() > 0) {
			// Should never happen, this is a safety net
			Utils.log2("Fixing error with null 'Display.getFront()'");
			final Display d = ds.iterator().next();
			d.frame.toFront();
			front = d;
		}
		return front;
	}

	static public Display getOrCreateFront(final Project project) {
		final Display df = front;
		if (null != df && df.project == project) return df;
		for (final Display d : al_displays) {
			if (d.project == project) {
				d.frame.toFront();
				return d;
			}
		}
		final LayerSet ls = project.getRootLayerSet();
		if (0 == ls.size()) return null;
		return new Display(project, ls.getLayer(0)); // sets it to front
	}

	static public void setCursorToAll(final Cursor c) {
		for (final Display d : al_displays) {
			if (null != d.frame) d.frame.setCursor(c);
		}
	}

	/** Used by the Displayable to update the visibility and locking state checkboxes in other Displays. */
	static public void updateCheckboxes(final Displayable displ, final int cb, final boolean state) {
		for (final Display d : al_displays) {
			final DisplayablePanel dp = d.ht_panels.get(displ);
			if (null != dp) {
				Utils.invokeLater(new Runnable() { @Override
                public void run() {
					dp.updateCheckbox(cb, state);
				}});
			}
		}
	}
	/** Set the checkbox @param cb state to @param state value, for each Displayable. Assumes all Displayable objects belong to one specific project. */
	static public void updateCheckboxes(final Collection<Displayable> displs, final int cb, final boolean state) {
		if (null == displs || 0 == displs.size()) return;
		final Project p = displs.iterator().next().getProject();
		for (final Display d : al_displays) {
			if (d.getProject() != p) continue;
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				for (final Displayable displ : displs) {
					final DisplayablePanel dp = d.ht_panels.get(displ);
					if (null != dp) {
						dp.updateCheckbox(cb, state);
					}
				}
			}});
		}
	}
	/** Update the checkbox @param cb state to an appropriate value for each Displayable. Assumes all Displayable objects belong to one specific project. */
	static public void updateCheckboxes(final Collection<Displayable> displs, final int cb) {
		if (null == displs || 0 == displs.size()) return;
		final Project p = displs.iterator().next().getProject();
		for (final Display d : al_displays) {
			if (d.getProject() != p) continue;
			Utils.invokeLater(new Runnable() { @Override
            public void run() {
				for (final Displayable displ : displs) {
					final DisplayablePanel dp = d.ht_panels.get(displ);
					if (null != dp) {
						dp.updateCheckbox(cb);
					}
				}
			}});
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
	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
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
			 .append(indent).append("<!ATTLIST t2_display filter_enabled NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_min_max_enabled NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_min NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_max NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_invert NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_clahe_enabled NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_clahe_block_size NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_clahe_histogram_bins NMTOKEN #REQUIRED>\n")
			 .append(indent).append("<!ATTLIST t2_display filter_clahe_max_slope NMTOKEN #REQUIRED>\n")
		;
	}
	/** Export all displays of the given project as XML entries. */
	static public void exportXML(final Project project, final Writer writer, final String indent, final XMLOptions options) throws Exception {
		final StringBuilder sb_body = new StringBuilder();
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
			       .append(in).append("filter_enabled=\"").append(d.filter_enabled).append("\"\n")
			       .append(in).append("filter_min_max_enabled=\"").append(d.filter_min_max_enabled).append("\"\n")
			       .append(in).append("filter_min=\"").append(d.filter_min).append("\"\n")
			       .append(in).append("filter_max=\"").append(d.filter_max).append("\"\n")
			       .append(in).append("filter_invert=\"").append(d.filter_invert).append("\"\n")
			       .append(in).append("filter_clahe_enabled=\"").append(d.filter_clahe_enabled).append("\"\n")
			       .append(in).append("filter_clahe_block_size=\"").append(d.filter_clahe_block_size).append("\"\n")
			       .append(in).append("filter_clahe_histogram_bins=\"").append(d.filter_clahe_histogram_bins).append("\"\n")
			       .append(in).append("filter_clahe_max_slope=\"").append(d.filter_clahe_max_slope).append("\"\n")
			;
			sb_body.append(indent).append("/>\n");
		}
		writer.write(sb_body.toString());
	}

	private void updateToolTab() {
		OptionPanel op = null;
		switch (ProjectToolbar.getToolId()) {
			case ProjectToolbar.PENCIL:
				op = Segmentation.fmp.asOptionPanel();
				break;
			case ProjectToolbar.BRUSH:
				op = AreaWrapper.PP.asOptionPanel();
				break;
			default:
				break;
		}
		scroll_options.getViewport().removeAll();
		if (null != op) {
			op.bottomPadding();
			scroll_options.setViewportView(op);
		}
		scroll_options.invalidate();
		scroll_options.validate();
		scroll_options.repaint();
	}

	// Never called; ProjectToolbar.toolChanged is also never called, which should forward here.
	static public void toolChanged(final String tool_name) {
		Utils.log2("tool name: " + tool_name);
		for (final Display d : al_displays) {
			d.updateToolTab();
			Utils.updateComponent(d.toolbar_panel);
			Utils.log2("updating toolbar_panel");
		}
	}

	static public void toolChanged(final int tool) {
		//Utils.log2("int tool is " + tool);
		if (ProjectToolbar.PEN == tool) {
			// erase bounding boxes
			final HashSet<Layer> s = new HashSet<Layer>();
			for (final Display d : al_displays) {
				if (null != d.active && !s.contains(d.layer)) {
					Display.repaint(d.layer, d.selection.getBox(), 2);
					s.add(d.layer);
				}
			}
		}
		for (final Display d: al_displays) {
			d.updateToolTab();
		}
		if (null != front) {
			try {
				WindowManager.setTempCurrentImage(front.canvas.getFakeImagePlus());
			} catch (final Exception e) {} // may fail when changing tools while opening a Display
		}
	}

	/* Filter the Display images */
	private boolean filter_enabled = false,
					filter_invert = false,
					filter_clahe_enabled = false,
					filter_min_max_enabled = false;
	private int filter_clahe_block_size = 127,
				filter_clahe_histogram_bins = 256,
				filter_min = 0,
				filter_max = 255;
	private float filter_clahe_max_slope = 3;

	public boolean isLiveFilteringEnabled() { return filter_enabled; }

	private OptionPanel createFilterOptionPanel() {
		final OptionPanel fop = new OptionPanel();
		final Runnable reaction = new Runnable() {
			@Override
            public void run() {
				Display.repaint(getLayer());
			}
		};
		fop.addCheckbox("Live filters enabled", filter_enabled, new OptionPanel.BooleanSetter(this, "filter_enabled", reaction));
		fop.addMessage("Contrast:");
		fop.addCheckbox("Min/Max enabled", filter_min_max_enabled, new OptionPanel.BooleanSetter(this, "filter_min_max_enabled", reaction));
		fop.addNumericField("Min:", filter_min, 0, new OptionPanel.IntSetter(this, "filter_min", reaction, 0, 255));
		fop.addNumericField("Max:", filter_max, 0, new OptionPanel.IntSetter(this, "filter_max", reaction, 0, 255));
		fop.addCheckbox("Invert", filter_invert, new OptionPanel.BooleanSetter(this, "filter_invert", reaction));
		fop.addMessage("CLAHE options:");
		fop.addCheckbox("CLAHE enabled", filter_clahe_enabled, new OptionPanel.BooleanSetter(this, "filter_clahe_enabled", reaction));
		fop.addNumericField("block size:", filter_clahe_block_size, 0, new OptionPanel.IntSetter(this, "filter_clahe_block_size", reaction, 1, Integer.MAX_VALUE));
		fop.addNumericField("histogram bins:", filter_clahe_histogram_bins, 0, new OptionPanel.IntSetter(this, "filter_clahe_histogram_bins", reaction, 1, Integer.MAX_VALUE));
		fop.addNumericField("max slope:", filter_clahe_max_slope, 2, new OptionPanel.FloatSetter(this, "filter_clahe_max_slope", reaction, 0, Integer.MAX_VALUE));
		return fop;
	}

	protected Image applyFilters(final Image img) {
		if (!filter_enabled) return img;
		return applyFilters(new ImagePlus("filtered", img)).getProcessor().createImage();
	}

	protected ImagePlus applyFilters(final ImageProcessor ip) {
		final ImagePlus imp = new ImagePlus("filtered", ip);
		applyFilters(imp);
		return imp;
	}

	protected ImagePlus applyFilters(final ImagePlus imp) {
		// Currently the order is hard-coded
		// 0: enabled?
		if (!filter_enabled) return imp;
		// 1: min/max?
		if (filter_min_max_enabled) imp.getProcessor().setMinAndMax(filter_min, filter_max);
		// 2: invert?
		if (filter_invert) imp.getProcessor().invert();
		// 3: CLAHE?
		if (filter_clahe_enabled) {
			Flat.getFastInstance().run(imp, filter_clahe_block_size, filter_clahe_histogram_bins, filter_clahe_max_slope, null, false);
		}

		return imp;
	}

	/////

	public Selection getSelection() {
		return selection;
	}

	public final boolean isSelected(final Displayable d) {
		return selection.contains(d);
	}

	static public void updateSelection() {
		Display.updateSelection(null);
	}
	static public void updateSelection(final Display calling) {
		final HashSet<Layer> hs = new HashSet<Layer>();
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
	static public void clearSelection(final Project p) {
		for (final Display d : al_displays) {
			if (d.project == p) d.selection.clear();
		}
	}

	private void setTempCurrentImage() {
		WindowManager.setCurrentWindow(canvas.getFakeImagePlus().getWindow());
		WindowManager.setTempCurrentImage(canvas.getFakeImagePlus());
	}

	/** Check if any display will paint the given Displayable within its srcRect. */
	static public boolean willPaint(final Displayable displ) {
		Rectangle box = null;
		for (final Display d : al_displays) {
			if (displ.getLayer() == d.layer) {
				if (null == box) box = displ.getBoundingBox(null);
				if (d.canvas.getSrcRect().intersects(box)) {
					return true;
				}
			}
		}
		return false;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
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

	public void resizeCanvas(final Rectangle bounds) {
		if (bounds.width <= 0|| bounds.height <= 0) throw new IllegalArgumentException("width and height must be larger than zero.");
		layer.getParent().setDimensions(bounds.x, bounds.y, bounds.width, bounds.height);
	}

	public void resizeCanvas() {
		final GenericDialog gd = new GenericDialog("Resize LayerSet");
		gd.addNumericField("new width: ", layer.getLayerWidth(), 1, 8, "pixels");
		gd.addNumericField("new height: ", layer.getLayerHeight(), 1, 8, "pixels");
		gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[7]);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		final float new_width = (float)gd.getNextNumber();
		final float new_height = (float)gd.getNextNumber();
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
		// set current state
		Set<DoStep> dataedits = new HashSet<DoStep>();
		dataedits.add(new Displayable.DoEdit(active).init(active, new String[]{"data"})); // the links!
		getLayerSet().addChangeTreesStep(dataedits);
		final Profile profile = project.getProjectTree().duplicateChild((Profile)active, position, other_layer);
		if (null == profile) {
			getLayerSet().removeLastUndoStep();
			return;
		}
		active.link(profile);
		other_layer.add(profile);
		slt.setAndWait(other_layer);
		selection.add(profile);
		// set new state
		dataedits = new HashSet<DoStep>();
		dataedits.add(new Displayable.DoEdit(active).init(active, new String[]{"data"})); // the links!
		dataedits.add(new Displayable.DoEdit(profile).init(profile, new String[]{"data"})); // the links!
		getLayerSet().addChangeTreesStep(dataedits);
	}

	private final HashMap<Color,Layer> layer_channels = new HashMap<Color,Layer>();
	private final TreeMap<Integer,LayerPanel> layer_alpha = new TreeMap<Integer,LayerPanel>();
	private final HashMap<Layer,Byte> layer_composites = new HashMap<Layer,Byte>();
	boolean invert_colors = false,
			transp_overlay_images = true,
			transp_overlay_areas = false,
			transp_overlay_text_labels = false;
	Set<Class<?>> classes_to_multipaint = getClassesToMultiPaint();

	protected void setTranspOverlayImages(final boolean b) {
		this.transp_overlay_images = b;
		updateMultiPaint();
	}
	protected void setTranspOverlayAreas(final boolean b) {
		this.transp_overlay_areas = b;
		updateMultiPaint();
	}
	protected void setTranspOverlayTextLabels(final boolean b) {
		this.transp_overlay_text_labels = b;
		updateMultiPaint();
	}
	protected void updateMultiPaint() {
		this.classes_to_multipaint = getClassesToMultiPaint();
		this.canvas.repaint(true);
	}
	/** Only Patch, Stack; AreaList, Profile; and DLabel are considered.
	 *  The rest paints in other layers with color cues. */
	protected Set<Class<?>> getClassesToMultiPaint() {
		final HashSet<Class<?>> include = new HashSet<Class<?>>();
		if (transp_overlay_images) {
			include.add(Patch.class);
			include.add(Stack.class);
		}
		if (transp_overlay_areas) {
			include.add(AreaList.class);
			include.add(Profile.class);
		}
		if (transp_overlay_text_labels) {
			include.add(DLabel.class);
		}
		return include;
	}

	protected byte getLayerCompositeMode(final Layer layer) {
		synchronized (layer_composites) {
			final Byte b = layer_composites.get(layer);
			return null == b ? Displayable.COMPOSITE_NORMAL : b;
		}
	}

	protected void setLayerCompositeMode(final Layer layer, final byte compositeMode) {
		synchronized (layer_composites) {
			if (-1 == compositeMode || Displayable.COMPOSITE_NORMAL == compositeMode) {
				layer_composites.remove(layer);
			} else {
				layer_composites.put(layer, compositeMode);
			}
		}
	}

	protected void resetLayerComposites() {
		synchronized (layer_composites) {
			layer_composites.clear();
		}
		canvas.repaint(true);
	}

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
		canvas.repaint(true);
	}

	/** Set all layer alphas to zero, and repaint canvas. */
	protected void resetLayerAlphas() {
		synchronized (layer_channels) {
			for (final LayerPanel lp : new ArrayList<LayerPanel>(layer_alpha.values())) {
				lp.setAlpha(0);
			}
			layer_alpha.clear(); // should have already been cleared
		}
		canvas.repaint(true);
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
		} catch (final Exception e) {
			IJError.print(e);
		}
	}

	/** Snap a Patch to the most overlapping Patch, if any.
	 *  This method is a shallow wrap around AlignTask.snap, setting proper undo steps. */
	static public final Bureaucrat snap(final Patch patch) {
		final Set<Displayable> linked = patch.getLinkedGroup(null);
		patch.getLayerSet().addTransformStep(linked);
		final Bureaucrat burro = AlignTask.snap(patch, null, false);
		burro.addPostTask(new Runnable() { @Override
        public void run() {
			patch.getLayerSet().addTransformStep(linked);
		}});
		return burro;
	}

	private Mode mode = new DefaultMode(this);

	public void setMode(final Mode mode) {
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		this.mode = mode;
		canvas.repaint(true);
		Utils.invokeLater(new Runnable() { @Override
        public void run() {
			scroller.setEnabled(mode.canChangeLayer());
		}});
	}

	public Mode getMode() {
		return mode;
	}


	static private final Hashtable<String,ProjectThing> findLandmarkNodes(final Project p, final String landmarks_type) {
		final Set<ProjectThing> landmark_nodes = p.getRootProjectThing().findChildrenOfTypeR(landmarks_type);
		final Hashtable<String,ProjectThing> map = new Hashtable<String,ProjectThing>();
		for (final ProjectThing pt : landmark_nodes) {
			map.put(pt.toString() + "# " + pt.getId(), pt);
		}
		return map;
	}

	/** @param stack_patch is just a Patch of a series of Patch that make a stack of Patches. */
	private boolean insertStack(final ProjectThing target_landmarks, final Project source, final ProjectThing source_landmarks, final Patch stack_patch) {
		final List<Ball> l1 = new ArrayList<Ball>();
		final List<Ball> l2 = new ArrayList<Ball>();
		final Collection<ProjectThing> b1s = source_landmarks.findChildrenOfType("ball"); // source is the one that has the stack_patch
		final Collection<ProjectThing> b2s = target_landmarks.findChildrenOfType("ball"); // target is this
		final HashSet<String> seen = new HashSet<String>();
		for (final ProjectThing b1 : b1s) {
			final Ball ball1 = (Ball) b1.getObject();
			if (null == ball1) {
				Utils.log("ERROR: there's an empty 'ball' node in target project" + project.toString());
				return false;
			}
			final String title1 = ball1.getTitle();
			for (final ProjectThing b2 : b2s) {
				final Ball ball2 = (Ball) b2.getObject();
				if (null == ball2) {
					Utils.log("ERROR: there's an empty 'ball' node in source project" + source.toString());
					return false;
				}
				if (title1.equals(ball2.getTitle())) {
					if (seen.contains(title1)) continue;
					seen.add(title1);
					l1.add(ball1);
					l2.add(ball2);
				}
			}
		}
		if (l1.size() < 4) {
			Utils.log("ERROR: found only " + l1.size() + " common landmarks: needs at least 4!");
			return false;
		}
		// Extract coordinates of source project landmarks, in patch stack coordinate space
		final List<double[]> c1 = new ArrayList<double[]>();
		for (final Ball ball1 : l1) {
			final Map<Layer,double[]> m = ball1.getRawBalls();
			if (1 != m.size()) {
				Utils.log("ERROR: ball object " + ball1 + " from target project " + project + " has " + m.size() + " balls instead of just 1.");
				return false;
			}
			final Map.Entry<Layer,double[]> e = m.entrySet().iterator().next();
			final Layer layer = e.getKey();
			final double[] xyr = e.getValue();
			final double[] fin = new double[]{xyr[0], xyr[1]};
			final AffineTransform affine = ball1.getAffineTransformCopy();
			try {
				affine.preConcatenate(stack_patch.getAffineTransform().createInverse());
			} catch (final Exception nite) {
				IJError.print(nite);
				return false;
			}
			final double[] fout = new double[2];
			affine.transform(fin, 0, fout, 0, 1);
			c1.add(new double[]{fout[0], fout[1], layer.getParent().indexOf(layer)});
		}

		// Extract coordinates of target (this) project landmarks, in calibrated world space
		final List<double[]> c2 = new ArrayList<double[]>();
		for (final Ball ball2 : l2) {
			final double[][] b = ball2.getBalls();
			if (1 != b.length) {
				Utils.log("ERROR: ball object " + ball2 + " from source project " + source + " has " + b.length + " balls instead of just 1.");
				return false;
			}
			final double[] fin = new double[]{b[0][0], b[0][1]};
			final AffineTransform affine = ball2.getAffineTransformCopy();
			final double[] fout = new double[2];
			affine.transform(fin, 0, fout, 0, 1);
			c2.add(new double[]{fout[0], fout[1], b[0][2]});
		}

		// Print landmarks:
		Utils.log("Landmarks:");
		for (Iterator<double[]> it1 = c1.iterator(), it2 = c2.iterator(); it1.hasNext(); ) {
			Utils.log(Utils.toString(it1.next()) + " <--> " + Utils.toString(it2.next()));
		}

		// Create point matches
		final List<PointMatch> pm = new ArrayList<PointMatch>();
		for (Iterator<double[]> it1 = c1.iterator(), it2 = c2.iterator(); it1.hasNext(); ) {
			pm.add(new mpicbg.models.PointMatch(new mpicbg.models.Point(it1.next()), new mpicbg.models.Point(it2.next())));
		}

		// Estimate AffineModel3D
		final AffineModel3D aff3d = new AffineModel3D();
		try {
			aff3d.fit(pm);
		} catch (final Exception e) {
			IJError.print(e);
			return false;
		}

		// Create and add the Stack
		final String path = stack_patch.getImageFilePath();
		final Stack st = new Stack(project, new File(path).getName(), 0, 0, getLayerSet().getLayers().get(0), path);
		st.setInvertibleCoordinateTransform(aff3d);
		getLayerSet().add(st);
		return true;
	}

	static private List<Patch> getPatchStacks(final LayerSet ls) {
		final HashSet<Patch> stacks = new HashSet<Patch>();
		for (final Patch pa : ls.getAll(Patch.class)) {
			if (stacks.contains(pa)) continue;
			final PatchStack ps = pa.makePatchStack();
			if (1 == ps.getNSlices()) continue;
			stacks.add(ps.getPatch(0));
		}
		return new ArrayList<Patch>(stacks);
	}

	static public Bureaucrat removeAlphaMasks(final Collection<Patch> patches) {
		return Bureaucrat.createAndStart(new Worker.Task("Remove alpha masks" + (patches.size() > 1 ? "s" : "")) {
			@Override
            public void exec() {
				if (null == patches || patches.isEmpty()) return;
				final ArrayList<Future<Boolean>> jobs = new ArrayList<Future<Boolean>>();
				for (final Patch p : patches) {
					p.setAlphaMask(null);
					final Future<Boolean> job = p.getProject().getLoader().regenerateMipMaps(p); // submit to queue
					if (null != job) jobs.add(job);
				}
				// join all
				for (final Future<?> job : jobs) try {
					job.get();
				} catch (final Exception ie) {}
			}}, patches.iterator().next().getProject());
	}

	/** Get the current {@link Roi}, if any. */
	public Roi getRoi() {
		return canvas.getFakeImagePlus().getRoi();
	}

	/** Open a {@link GenericDialog} to ask for parameters to find all {@link Displayable} that abide to them.
	 *
	 * @return The list of {@link Displayable} instances found.
	 * @see Display#find(String, boolean, Class, List)
	 */
	public <T extends Displayable> List<T> find() {
		final GenericDialog gd = new GenericDialog("Select matching");
		Utils.addLayerRangeChoices(layer, gd);
		gd.addStringField("Regular expression:", "");
		final TreeMap<String,Class<?>> types = new TreeMap<String,Class<?>>();
		types.put("01 - All", Displayable.class);
		types.put("02 - Image (Patch, Stack)", ImageData.class);
		types.put("03 - Non-image (VectorData subtypes)", VectorData.class);
		types.put("04 - Tree (Treeline, AreaTree, Connector)", Tree.class);
		types.put("05 - Area container (AreaTree, AreaList)", AreaContainer.class);
		types.put("06 - Text labels", DLabel.class);
		types.put("07 - Patch (image)", Patch.class);
		types.put("08 - Stack (image)", Stack.class);
		types.put("09 - AreaList", AreaList.class);
		types.put("10 - AreaTree", AreaTree.class);
		types.put("11 - Treeline", Treeline.class);
		types.put("12 - Connector", Connector.class);
		types.put("13 - Ball", Ball.class);
		types.put("14 - Pipe", Pipe.class);
		types.put("15 - Polyline", Polyline.class);
		types.put("16 - Profile", Profile.class);
		types.put("17 - Dissector", Dissector.class);
		//
		gd.addChoice("Type:", types.keySet().toArray(new String[types.size()]), types.firstKey());
		gd.addCheckbox("Visible only", true);
		gd.showDialog();
		if (gd.wasCanceled()) return Collections.EMPTY_LIST;
		final int first = gd.getNextChoiceIndex();
		final int last = gd.getNextChoiceIndex();
		return find(gd.getNextString(), gd.getNextBoolean(), (Class<T>)types.get(gd.getNextChoice()), first, last);
	}

	/** @see Display#find(String, boolean, Class, List) */
	public <T extends Displayable> List<T> find(final String regex, final boolean visible_only, final Class<T> c, final int firstLayerIndex, final int lastLayerIndex) {
		return find(regex, visible_only, c, layer.getParent().getLayers(firstLayerIndex, lastLayerIndex));
	}

	static private final String fixRegex(String regex) {
		if (regex.charAt(0) != '^') {
			if (regex.startsWith(".*")) regex = "^" + regex;
			else regex = "^.*" + regex;
		}
		if (regex.charAt(regex.length()-1) != '$') {
			if (regex.endsWith(".*")) regex += "$";
			else regex += ".*$";
		}
		return regex;
	}

	/**
	 *
	 * @param regex The regular expression to match against the title of a {@link Displayable}.
	 * @param visible_only Whether to gather only {@link Displayable} instances that are visible.
	 * @param c The specific class of {@link Displayable}; can be an interface like {@link VectorData}, {@link ImageData} or {@link AreaContainer}. Use {@link Displayable} to mean any.
	 * @param layers The range of {@link Layer} to search in.
	 * @return
	 */
	static public <T extends Displayable> List<T> find(final String regex, final boolean visible_only, final Class<T> c, final List<Layer> layers) {
		Utils.log2(regex, visible_only, c, layers);
		final List<T> ds = new ArrayList<T>();
		final Pattern pattern = (null == regex || 0 == regex.length()) ? null : Pattern.compile(fixRegex(regex));
		for (final Layer l : layers) {
			for (final Displayable d : l.getDisplayables()) {
				if (visible_only && !d.isVisible()) continue;
				if (c.isInstance(d)) {
					if (null == pattern) ds.add((T)d);
					else if (pattern.matcher(d.getTitle()).matches()) ds.add((T)d);
				}
			}
		}
		for (final ZDisplayable d : layers.get(0).getParent().getZDisplayables()) {
			if (visible_only && !d.isVisible()) continue;
			if (c.isInstance(d)
			 && (null == pattern || pattern.matcher(d.getTitle()).matches())) {
				for (final Layer l : layers) {
					if (d.paintsAt(l)) {
						ds.add((T)d);
						break;
					}
				}
			}
		}
		return ds;
	}
}
