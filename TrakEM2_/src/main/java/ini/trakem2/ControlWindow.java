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

package ini.trakem2;

import ij.IJ;
import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ini.trakem2.display.ImageJCommandListener;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.display.Display3D;
import ini.trakem2.tree.LayerTree;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.TemplateTree;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.RedPhone;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.StdOutWindow;
import ini.trakem2.persistence.Loader;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ImageProducer;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Set;
import java.util.Map;
import java.awt.event.*;


/** Static class that shows one project per tab in a JFrame.
 *  Creates itself when a project requests to be have its trees displayed.
 *  Destroys itself when there are no more projects to show.
 * 
 * */
public class ControlWindow {

	static private JFrame frame = null;
	static private JTabbedPane tabs = null;
	/** Project instances are keys, JSplitPane are the objects. */
	static private Hashtable<Project,JSplitPane> ht_projects = null;
	/** While the instance is not null, the other fields (frame, tabs, ht_projects) are not null either. */
	static private ControlWindow instance = null;
	/** Control changes to the instance. */
	static private final Object LOCK = new Object();
	
	private final RedPhone red_phone = new RedPhone();

	static private boolean gui_enabled = true;

	/** Intercept ImageJ menu commands if the front image is a FakeImagePlus. */
	private ImageJCommandListener command_listener;

	private ControlWindow() {
		if (null != ij.gui.Toolbar.getInstance()) {
			ij.gui.Toolbar.getInstance().addMouseListener(tool_listener);
		}
		Utils.setup(this);
		Loader.setupPreloader(this);
		if (IJ.isWindows() && isGUIEnabled()) StdOutWindow.start();
		Display3D.init();
		setLookAndFeel();
		this.command_listener = new ImageJCommandListener();
		this.red_phone.start();
	}
	
	// private to the package
	static final ControlWindow getInstance() {
		synchronized (LOCK) {
			if (null == instance) instance = new ControlWindow();
			return instance;
		}
	}

	static public void setLookAndFeel() {
		try {
			if (ij.IJ.isLinux()) {
				// Nimbus looks great but it's unstable: after a while, swing components stop repainting, throwing all sort of exceptions.
				//UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
				UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
				for (final Frame frame : Frame.getFrames()) {
					if (frame.isEnabled()) SwingUtilities.updateComponentTreeUI(frame);
				}
				// all done above
				//if (null != frame) SwingUtilities.updateComponentTreeUI(frame);
				//if (null != IJ.getInstance()) javax.swing.SwingUtilities.updateComponentTreeUI(IJ.getInstance());
				//Display.updateComponentTreeUI();
			}
		} catch (ClassNotFoundException cnfe) {
			Utils.log2("Could not find Nimbus L&F");
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	/** Prevents ControlWindow from displaying projects.*/
	static public void setGUIEnabled(boolean b) {
		gui_enabled = b;
		if (gui_enabled && null != frame) frame.setVisible(true);
	}

	static public final boolean isGUIEnabled() {
		return gui_enabled;
	}

	/** Returns null if there are no projects */
	synchronized static public Set<Project> getProjects() {
		synchronized (LOCK) {
			if (null == ht_projects) return null;
			return ht_projects.keySet();
		}
	}

	static private MouseListener tool_listener = new MouseAdapter() {
		private int last_tool = ij.gui.Toolbar.RECTANGLE;
		public void mousePressed(MouseEvent me) {
			int tool = ini.trakem2.utils.ProjectToolbar.getToolId();
			if (tool != last_tool) {
				last_tool = tool;
				ini.trakem2.display.Display.toolChanged(tool);
			}
		}
	};

	static private void destroy() {
		synchronized(LOCK) {
			if (null == instance) return;
			if (IJ.isWindows()) StdOutWindow.quit();
			Display3D.destroy();
			if (null != ht_projects) {
				// destroy open projects, release memory
				Enumeration<Project> e = ht_projects.keys();
				Project[] project = new Project[ht_projects.size()]; //concurrent modifications ..
				int next = 0;
				while (e.hasMoreElements()) {
					project[next++] = e.nextElement();
				}
				for (int i=0; i<next; i++) {
					ht_projects.remove(project[i]);
					if (!project[i].destroy()) {
						return;
					}
				}
				ht_projects = null;
			}
			if (null != tabs) {
				tabs.removeMouseListener((tabs.getMouseListeners())[0]);
				tabs = null;
			}
			if (null != frame) {
				final JFrame fr = frame;
				SwingUtilities.invokeLater(new Runnable() { public void run() {
					fr.setVisible(false);
					fr.dispose();
					if (null != ij.gui.Toolbar.getInstance()) ij.gui.Toolbar.getInstance().repaint();
				}});
				frame = null;
				ProjectToolbar.destroy();
			}
			if (null != tool_listener && null != ij.gui.Toolbar.getInstance()) {
				ij.gui.Toolbar.getInstance().removeMouseListener(tool_listener);
			}
			Utils.destroy(instance);
			Loader.destroyPreloader(instance);
			instance.command_listener.destroy();
			instance.command_listener = null;
			if (null != instance.red_phone) instance.red_phone.quit();
			instance = null;
		}
	}

	static private boolean hooked = false;

	/** Beware that this method is asynchronous, as it delegates the launching to the SwingUtilities.invokeLater method to avoid havoc with Swing components. */
	static public void add(final Project project, final TemplateTree template_tree, final ProjectTree thing_tree, final LayerTree layer_tree) {

		final Runnable[] other = new Runnable[2];

        if (!gui_enabled)
        {
            return;
        }

		final Runnable gui_thread = new Runnable() {
			public void run() {

		synchronized (LOCK) {

			getInstance(); // init
			if (null == frame) {
				if (!hooked) {
					Runtime.getRuntime().addShutdownHook(new Thread() { // necessary to disconnect properly from the database instead of with an EOF, and also to ask to save changes for FSLoader projects.
						public void run() {
							// threaded quit???// if (null != IJ.getInstance() && !IJ.getInstance().quitting()) IJ.getInstance().quit(); // to ensure the Project offers a YesNoDialog, not a YesNoCancelDialog
							ControlWindow.destroy();
						}
					});
					hooked = true;
				}
				frame = createJFrame("TrakEM2");
				frame.setBackground(Color.white);
				frame.getContentPane().setBackground(Color.white);
				frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
				frame.addWindowListener(new WindowAdapter() {
					public void windowClosing(WindowEvent we) {
						synchronized (LOCK) {
							if (!Utils.check("Close " + (1 == ht_projects.size() ? "the project?" : "all projects?"))) {
								return;
							}
							destroy();
						}
					}
					public void windowClosed(WindowEvent we) {
						// ImageJ is quitting (never detected, so I added the dispose extension above)
						destroy();
					}
				});
				tabs = new JTabbedPane(JTabbedPane.TOP);
				tabs.setBackground(Color.white);
				tabs.setMinimumSize(new Dimension(500, 400));
				tabs.addMouseListener(new TabListener());
				frame.getContentPane().add(tabs);
				// register with ij.WindowManager so that when ImageJ quits it can be detected
				// ADDS annoying dialog "Are you sure you want to close ImageJ?"//ij.WindowManager.addWindow(frame);
				// Make the JPopupMenu instances be heavy weight components by default in Windows and elsewhere, not macosx.
				if (!ij.IJ.isMacOSX()) JPopupMenu.setDefaultLightWeightPopupEnabled(false);
				// make the tool tip text for JLabel be heavy weight so they don't hide under the AWT DisplayCanvas
				javax.swing.ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
			}

			// create the tab
			final JSplitPane tab = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
			tab.setBackground(Color.white);
			// store the tab linked to the project (before setting the trees, so that they won't get repainted and get in trouble not being able to get a project title if the project has no name)
			if (null == ht_projects) ht_projects = new Hashtable<Project,JSplitPane>();
			ht_projects.put(project, tab);

			// create a scrolling pane for the template_tree
			final JScrollPane scroll_template = new JScrollPane(template_tree);
			scroll_template.setBackground(Color.white);
			scroll_template.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(0,5,0,5), "Template"));
			scroll_template.setMinimumSize(new Dimension(0, 100));
			scroll_template.setPreferredSize(new Dimension(300, 400));

			// create a scrolling pane for the thing_tree
			final JScrollPane scroll_things   = new JScrollPane(thing_tree);
			scroll_things.setBackground(Color.white);
			scroll_things.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(0,5,0,5), "Project Objects"));
			scroll_things.setMinimumSize(new Dimension(0, 100));
			scroll_things.setPreferredSize(new Dimension(300, 400));

			// create a scrolling pane for the layer_tree
			final JScrollPane scroll_layers = new JScrollPane(layer_tree);
			scroll_layers.setBackground(Color.white);
			scroll_layers.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(0,5,0,5), "Layers"));
			scroll_layers.setMinimumSize(new Dimension(0, 100));
			scroll_layers.setPreferredSize(new Dimension(300, 400));

			// make a new tab for the project
			final JSplitPane left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll_template, scroll_things);
			left.setBackground(Color.white);
			left.setPreferredSize(new Dimension(600, 400));

			// setup the tab
			tab.setBackground(Color.white);
			tab.setLeftComponent(left);
			tab.setRightComponent(scroll_layers);
			tab.setPreferredSize(new Dimension(900, 400));

			// add the tab, titled with the project title
			tabs.addTab(project.toString(), new CloseIcon(), tab);
			tabs.setSelectedIndex(tabs.getTabCount() -1);

			// the frame is created ANYWAY, it is just not made visible if !gui_enabled
			if (!frame.isVisible() && gui_enabled) {
				frame.pack();
				frame.setVisible(true);
				frame.toFront();
			}
			Rectangle bounds = frame.getBounds();
			if (bounds.width < 200) {
				frame.setSize(new Dimension(200, bounds.height > 100 ? bounds.height : 100));
				frame.pack();
			}
			// now set minimum size again, after showing it (stupid Java), so they are shown correctly (opened) but can be completely collapsed to the sides.
			try { Thread.sleep(100); } catch (Exception e) {}
			//scroll_template.setMinimumSize(new Dimension(0, 100));
			//scroll_things.setMinimumSize(new Dimension(0, 100));
			//scroll_layers.setMinimumSize(new Dimension(0, 100));
			tab.setDividerLocation(0.66D); // first, so that left is visible! setDividerLocation depends on the dimensions as they are when painted on the screen
			left.setDividerLocation(0.5D);

			// select the SELECT tool if it's the first open project
			if (1 == ht_projects.size() && gui_enabled) {
				ProjectToolbar.setTool(ProjectToolbar.SELECT);
			}

			// so wait until the setDividerLocation of the 'tab' has finished, then do the left one
			other[0] = new Runnable() {
				public void run() {
					tab.setDividerLocation(0.66D);
				}
			};
			other[1] = new Runnable() {
				public void run() {
					left.setDividerLocation(0.5D);
				}
			};
			// FINALLY! WHAT DEGREE OF IDIOCY POSSESSED SWING DEVELOPERS?

		}

		}};

		new Thread() {
			{ setPriority(Thread.NORM_PRIORITY); }
			public void run() {
				try {
					SwingUtilities.invokeAndWait(gui_thread);
					for (int i=0; i<other.length; i++) {
						SwingUtilities.invokeAndWait(other[i]);
					}
					//Utils.log2("done");
				} catch (Exception e) { IJError.print(e); }
			}
		}.start();
	}

	synchronized static public Project getActive() {
		synchronized (LOCK) {
			if (null == tabs || 0 == ht_projects.size()) return null;
			if (1 == ht_projects.size()) return (Project)ht_projects.keySet().iterator().next();
			else {
				Component c = tabs.getSelectedComponent();
				for (final Map.Entry<Project,JSplitPane> e : ht_projects.entrySet()) {
					if (e.getValue().equals(c)) return e.getKey();
				}
			}
			return null;
		}
	}

	static public void remove(final Project project) {
		synchronized (LOCK) {
			if (null == tabs || null == ht_projects) return;
			if (null == instance) return;
			if (ht_projects.containsKey(project)) {
				int n_tabs = 0;
				JSplitPane tab = (JSplitPane)ht_projects.get(project);
				tabs.remove(tab);
				ht_projects.remove(project);
				n_tabs = tabs.getTabCount();
				// close the ControlWindow if no projects remain open.
				if (0 == n_tabs) {
					destroy();
				}
			}
		}
	}

	static public void updateTitle(final Project project) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				synchronized (LOCK) {
					if (null == tabs) return;
					if (ht_projects.containsKey(project)) {
						if (null == instance) return;
						JSplitPane tab = (JSplitPane)ht_projects.get(project);
						int index = tabs.indexOfComponent(tab);
						if (-1 != index) {
							tabs.setTitleAt(index, project.toString());
						}
					}
				}
			}
		});
	}

	private static class TabListener extends MouseAdapter {
		public void mouseReleased(MouseEvent me) {
			if (me.isConsumed()) return;
			synchronized (LOCK) {
				if (null == tabs) return;
				int i_tab = tabs.getSelectedIndex();
				Component comp = tabs.getComponentAt(i_tab);
				Icon icon = tabs.getIconAt(i_tab);
				if (icon instanceof CloseIcon) {
					CloseIcon ci = (CloseIcon)icon;
					// find the project
					Project project = null;
					for (final Map.Entry<Project,JSplitPane> e: ht_projects.entrySet()) {
						project = e.getKey();
						if (e.getValue().equals(comp)) break;
					}
					if (ci.contains(me.getX(), me.getY())) {
						if (null == project) return;
						// ask for confirmation before closing
						if (!Utils.check("Close the project " + project.toString() + " ?")) {
							return;
						}
						// proceed to close:
						if (project.destroy()) { // will call ControlWindow.remove(project)
							ci.flush();
						}
					} else if (2 == me.getClickCount()) {
						// pop dialog to rename the project
						if (null == project) return;
						project.getProjectTree().rename(project.getRootProjectThing());
					}
				}
			}
		}
	}

	static private class CloseIcon implements Icon {

		private Icon icon;
		private BufferedImage img;
		private int x = 0;
		private int y = 0;

		CloseIcon() {
			img = frame.getGraphicsConfiguration().createCompatibleImage(20, 16, Transparency.TRANSLUCENT);
			Graphics2D g = img.createGraphics();
			g.setColor(Color.black);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawOval(4 + 2, 2, 12, 12);
			g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(4 + 4, 4, 4 + 11, 12);
			g.drawLine(4 + 4, 12, 4 + 11, 4);
			icon = new ImageIcon(img);
		}

		public void paintIcon(Component c, Graphics g, int x, int y) {
			// store coordinates of the last painting event
			this.x = x;
			this.y = y;
			icon.paintIcon(c, g, x, y );
		}

		public boolean contains(int x, int y) {
			return new Rectangle(this.x, this.y, icon.getIconWidth(), icon.getIconHeight()).contains(x, y);
		}

		public int getIconWidth() { return icon.getIconWidth(); }
		public int getIconHeight() { return icon.getIconHeight(); }

		public void flush() {
			if (null != img) {
				img.flush();
				img = null;
			}
		}
	}

	/** For the generic dialogs to be parented properly. */
	static public GenericDialog makeGenericDialog(String title) {
		Frame f = (null == frame ? IJ.getInstance() : (java.awt.Frame)frame);
		return new GenericDialog(title, f);
	}

	/** For the YesNoCancelDialog dialogs to be parented properly. */
	static public YesNoCancelDialog makeYesNoCancelDialog(String title, String msg) {
		Frame f = (null == frame ? IJ.getInstance() : (java.awt.Frame)frame);
		return new YesNoCancelDialog(f, title, msg);
	}
	/** For the YesNoDialog dialogs to be parented properly. */
	static public YesNoDialog makeYesNoDialog(String title, String msg) {
		Frame f = (null == frame ? IJ.getInstance() : (java.awt.Frame)frame);
		return new YesNoDialog(f, title, msg);
	}

	static public void toFront() {
		synchronized (instance) {
			if (null != frame) frame.toFront();
		}
	}

	/** Appends to the buffer data relative to the viewport of the given tree. */
	/*
	static public void exportTreesXML(final Project project, final StringBuffer sb_data, final String indent, final JTree tree) {
		// find the JSplitPane of the given tree
		JScrollPane[] jsp = new JScrollPane[1];
		jsp[0] = null;
		findJSP((Container)ht_projects.get(project), tree, jsp);
		if (null == jsp[0]) {
			Utils.log2("Cound not find a JScrollPane for the tree.");
			return;
		}
		// else, we have it
		tree.exportXML(sb_data, indent, jsp[0]);
	}
	*/

	// /** Recursive. */
	/*
	static private void findJSP(final Container parent, final JTree  tree, final JScrollPane[] jsp) {
		if (null != jsp[0]) return;
		Component[] comps = parent.getComponents();
		for (int i=0; i<comps.length; i++) {
			if (comps[i] instanceof Container) {
				findJSP(comps[i], tree, jsp);
			} else if (comps[i].equals(tree)) {
				jsp[0] = (JScrollPane)parent; // MUST be
				break;
			}
		}
	}
	*/

	static public void startWaitingCursor() { setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)); }

	static public void endWaitingCursor() { setCursor(Cursor.getDefaultCursor()); }

	static private void setCursor(final Cursor c) {
		Utils.invokeLater(new Runnable() { public void run() {
			if (null != IJ.getInstance()) IJ.getInstance().setCursor(c);
			ini.trakem2.display.Display.setCursorToAll(c);
			if (null != frame && frame.isVisible()) frame.setCursor(c); // the ControlWindow frame
		}});
	}

	/** Returns -1 if not found. */
	synchronized static public int getTabIndex(final Project project) {
		if (null == project || null == ht_projects) return -1;
		Component tab = (Component)ht_projects.get(project);
		if (null == tab) return -1;
		return tabs.indexOfComponent(tab);
	}

	static private Image icon = null;

	/** Returns a new JFrame with the proper icon from ImageJ.iconPath set, if any. */
	static public JFrame createJFrame(final String title) {
		if (null == instance) return new JFrame(title);
		return instance.newJFrame(title);
	}
	synchronized private JFrame newJFrame(final String title) {
		final JFrame frame = new JFrame(title);

		if (null == icon) {
			try {
				Field mic = ImageJ.class.getDeclaredField("iconPath");
				mic.setAccessible(true);
				String path = (String) mic.get(IJ.getInstance());
				icon = IJ.getInstance().createImage((ImageProducer) new URL("file:" + path).getContent());
			} catch (Exception e) {}
		}

		if (null != icon) frame.setIconImage(icon);
		return frame;
	}
}
