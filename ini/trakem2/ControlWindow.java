/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ini.trakem2.display.YesNoDialog;
import ini.trakem2.tree.LayerTree;
import ini.trakem2.tree.ProjectTree;
import ini.trakem2.tree.TemplateTree;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
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
	static private Hashtable ht_projects = null;
	static private ControlWindow instance = null;

	static private boolean gui_enabled = true;

	private ControlWindow() {
		if (null != ij.gui.Toolbar.getInstance()) {
			ij.gui.Toolbar.getInstance().addMouseListener(tool_listener);
		}
	}

	/** Prevents ControlWindow from displaying projects.*/
	static public void setGUIEnabled(boolean b) {
		gui_enabled = b;
		if (gui_enabled && null != frame) frame.setVisible(false);
	}

	static public final boolean isGUIEnabled() {
		return gui_enabled;
	}

	/** Returns null if there are no projects */
	static public Set getProjects() {
		if (null == ht_projects) return null;
		return ht_projects.keySet();
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
		if (null != ht_projects) {
			// destroy open projects, release memory
			Enumeration e = ht_projects.keys();
			Project[] project = new Project[ht_projects.size()]; //concurrent modifications ..
			int next = 0;
			while (e.hasMoreElements()) {
				project[next++] = (Project)e.nextElement();
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
			frame.setVisible(false);
			frame.dispose();
			frame = null;
			ProjectToolbar.destroy();
			if (null != ij.gui.Toolbar.getInstance()) ij.gui.Toolbar.getInstance().repaint();
			//ij.WindowManager.removeWindow(frame);
		}
		if (null != tool_listener && null != ij.gui.Toolbar.getInstance()) {
			ij.gui.Toolbar.getInstance().removeMouseListener(tool_listener);
		}
		instance = null;
	}

	static private boolean hooked = false;

	/** Beware that this method is asynchronous, as it delegates the launching to the SwingUtilities.invokeLater method to avoid havoc with Swing components. */
	static public void add(final Project project, final TemplateTree template_tree, final ProjectTree thing_tree, final LayerTree layer_tree) {

		final Runnable[] other = new Runnable[2];

		final Runnable gui_thread = new Runnable() {
			public void run() {


		if (null == instance) {
			instance = new ControlWindow();
		}

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
			frame = new JFrame("TrakEM2");
			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			frame.addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent we) {
					if (!Utils.check("Close " + (1 == ht_projects.size() ? "the project?" : "all projects?"))) {
						return;
					}
					destroy();
				}
				public void windowClosed(WindowEvent we) {
					// ImageJ is quitting (never detected, so I added the dispose extension above)
					destroy();
				}
			});
			tabs = new JTabbedPane(JTabbedPane.TOP);
			tabs.setMinimumSize(new Dimension(500, 400));
			tabs.addMouseListener(instance.makeTabListener());
			frame.getContentPane().add(tabs);
			// register with ij.WindowManager so that when ImageJ quits it can be detected
			// ADDS annoying dialog "Are you sure you want to close ImageJ?"//ij.WindowManager.addWindow(frame);
			// Make the JPopupMenu instances be heavy weight components by default in Windows and elsewhere, not macosx.
			if (!ij.IJ.isMacOSX()) JPopupMenu.setDefaultLightWeightPopupEnabled(false);
			// make the tool tip text for JLabel be heavy weight so they don't hide under the AWT DisplayCanvas
			javax.swing.ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
		}

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
		final JSplitPane tab = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, scroll_layers);
		tab.setBackground(Color.white);
		tab.setPreferredSize(new Dimension(900, 400));

		// add the tab, titled with the project title
		tabs.addTab(project.toString(), instance.makeCloseIcon(), tab);
		tabs.setSelectedIndex(tabs.getTabCount() -1);

		// store the tab linked to the project
		if (null == ht_projects) ht_projects = new Hashtable();
		ht_projects.put(project, tab);

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

			}};
		// I hate java: can't call invokeLater from the EventDispatch thread
		new Thread() {
			public void run() {
				try {
					SwingUtilities.invokeAndWait(gui_thread);
					for (int i=0; i<other.length; i++) {
						SwingUtilities.invokeAndWait(other[i]);
					}
					Utils.log2("done");
				} catch (Exception e) { new IJError(e); }
			}
		}.start();
	}

	static public Project getActive() {
		if (null == tabs || 0 == ht_projects.size()) return null;
		if (1 == ht_projects.size()) return (Project)ht_projects.keySet().iterator().next();
		else {
			Component c = tabs.getSelectedComponent();
			for (Iterator it = ht_projects.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry entry = (Map.Entry)it.next();
				if (entry.getValue().equals(c)) return (Project)entry.getKey();
			}
		}
		return null;
	}

	static public void remove(Project project) {
		if (null == tabs || null == ht_projects) return;
		if (ht_projects.containsKey(project)) {
			JSplitPane tab = (JSplitPane)ht_projects.get(project);
			tabs.remove(tab);
			ht_projects.remove(project);
			if (0 == tabs.getTabCount()) {
				destroy();
			}
		}
	}

	static public void updateTitle(Project project) {
		if (null == tabs) return;
		if (ht_projects.containsKey(project)) {
			JSplitPane tab = (JSplitPane)ht_projects.get(project);
			int index = tabs.indexOfComponent(tab);
			if (-1 != index) {
				tabs.setTitleAt(index, project.toString());
			}
		}
	}

	private class TabListener extends MouseAdapter {
		public void mouseReleased(MouseEvent me) {
			if (me.isConsumed()) return;
			int i_tab = tabs.getSelectedIndex();
			Component comp = tabs.getComponentAt(i_tab);
			Icon icon = tabs.getIconAt(i_tab);
			if (icon instanceof CloseIcon) {
				CloseIcon ci = (CloseIcon)icon;
				if (ci.contains(me.getX(), me.getY())) {
					// find the project
					Enumeration e = ht_projects.keys();
					Project project = null;
					while (e.hasMoreElements()) {
						project = (Project)e.nextElement();
						if (comp.equals(ht_projects.get(project))) {
							break;
						}
					}
					if (null == project) return;
					// ask for confirmation
					if (!Utils.check("Close the project " + project.toString() + " ?")) {
						return;
					}
					// proceed to close:
					if (project.destroy()) {
						ci.flush();
						if (null != ht_projects) {  // may have been destroyed already
							ht_projects.remove(project);
							// done at project.destroy() //tabs.remove(i_tab);
							if (0 == tabs.getTabCount()) {
								instance.destroy();
							}
						}
					}
				} else if (2 == me.getClickCount()) {
					// pop dialog to rename the project
					// find the project
					Enumeration e = ht_projects.keys();
					Project project = null;
					while (e.hasMoreElements()) {
						project = (Project)e.nextElement();
						if (comp.equals(ht_projects.get(project))) {
							break;
						}
					}
					if (null == project) return;
					project.getProjectTree().rename(project.getRootProjectThing());
				}
			}
		}
	}

	// stupid java (working around static namespace limitations)
	private TabListener makeTabListener() {
		return new TabListener(); 
	}

	private CloseIcon makeCloseIcon() {
		return new CloseIcon();
	}

	private class CloseIcon implements Icon {

		private Icon icon;
		private BufferedImage img;
		private int x = 0;
		private int y = 0;

		CloseIcon() {
			img = new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_BINARY);
			Graphics2D g = img.createGraphics();
			g.setColor(Color.blue);
			g.fillRect(0, 0, 16, 16);
			g.setColor(Color.white);
			g.drawRect(2, 2, 12, 12);
			g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(4, 4, 11, 11);
			g.drawLine(4, 11, 11, 4);
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
		if (null != frame) frame.toFront();
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
		if (null != IJ.getInstance()) IJ.getInstance().setCursor(c);
		ini.trakem2.display.Display.setCursorToAll(c);
		if (null != frame && frame.isVisible()) frame.setCursor(c); // the ControlWindow frame
	}
}
