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

package ini.trakem2.tree;


import ij.gui.GenericDialog;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Polyline;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.VectorData;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Event;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import mpicbg.trakem2.align.AlignTask;

/** A class to hold a tree of Thing nodes */
public final class ProjectTree extends DNDTree implements MouseListener, ActionListener {

	/*
	static {
		System.setProperty("j3d.noOffScreen", "true");
	}
	*/

	private static final long serialVersionUID = 1L;
	private DefaultMutableTreeNode selected_node = null;

	public ProjectTree(Project project, ProjectThing project_thing) {
		super(project, DNDTree.makeNode(project_thing), new Color(240,230,255)); // new Color(185,156,255));
		setEditable(false); // the titles
		addMouseListener(this);
		addTreeExpansionListener(this);
	}

	/** Get a custom, context-sensitive popup menu for the selected node. */
	private JPopupMenu getPopupMenu(DefaultMutableTreeNode node) {
		Object ob = node.getUserObject();
		ProjectThing thing = null;
		if (ob instanceof ProjectThing) {
			thing = (ProjectThing)ob;
		} else {
			return null;
		}
		// context-sensitive popup
		JMenuItem[] items = thing.getPopupItems(this);
		if (0 == items.length) return null;
		JPopupMenu popup = new JPopupMenu();
		for (int i=0; i<items.length; i++) {
			popup.add(items[i]);
		}
		JMenu node_menu = new JMenu("Node");
		JMenuItem item = new JMenuItem("Move up"); item.addActionListener(this); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0, true)); node_menu.add(item);
		item = new JMenuItem("Move down"); item.addActionListener(this); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0, true)); node_menu.add(item);
		item = new JMenuItem("Collapse nodes of children nodes"); item.addActionListener(this); node_menu.add(item);
		popup.add(node_menu);

		JMenu send_menu = new JMenu("Send to");
		item = new JMenuItem("Sibling project"); item.addActionListener(this); send_menu.add(item);
		popup.add(send_menu);

		return popup;
	}

	public void mousePressed(final MouseEvent me) {
		super.dispatcher.execSwing(new Runnable() { public void run() {
		if (!me.getSource().equals(ProjectTree.this) || !project.isInputEnabled()) {
			return;
		}
		final int x = me.getX();
		final int y = me.getY();
		// find the node and set it selected
		final TreePath path = getPathForLocation(x, y);
		if (null == path) {
			return;
		}
		ProjectTree.this.setSelectionPath(path);
		selected_node = (DefaultMutableTreeNode)path.getLastPathComponent();

		if (2 == me.getClickCount() && !me.isPopupTrigger() && MouseEvent.BUTTON1 == me.getButton()) {
			// show in the front Display
			if (null == selected_node) return;
			Object obt = selected_node.getUserObject();
			if (!(obt instanceof ProjectThing)) return;
			ProjectThing thing = (ProjectThing)obt;
			thing.setVisible(true);
			Object obd = thing.getObject();
			if (obd instanceof Displayable) {
				// additionaly, get the front Display (or make a new one if none) and show in it the layer in which the Displayable object is contained.
				Displayable displ = (Displayable)obd;
				Display.showCentered(displ.getLayer(), displ, true, me.isShiftDown());
			}
			return;
		} else if (me.isPopupTrigger() || (ij.IJ.isMacOSX() && me.isControlDown()) || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) { // the last block is from ij.gui.ImageCanvas, aparently to make the right-click work on windows?
			JPopupMenu popup = getPopupMenu(selected_node);
			if (null == popup) return;
			popup.show(ProjectTree.this, x, y);
			return;
		}
		}});
	}

	public void rename(final ProjectThing thing) {
		final Object ob = thing.getObject();
		final String old_title;
		if (null == ob) old_title = thing.getType();
		else if (ob instanceof DBObject) old_title = ((DBObject)ob).getTitle();
		else old_title = ob.toString();
		GenericDialog gd = ControlWindow.makeGenericDialog("New name");
		gd.addMessage("Old name: " + old_title);
		gd.addStringField("New name: ", old_title, 40);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		String title = gd.getNextString();
		if (null == title) {
			Utils.log("WARNING: avoided setting the title to null for " + thing);
			return;
		}
		title = title.replace('"', '\'').trim(); // avoid XML problems - could also replace by double '', then replace again by " when reading.
		project.getRootLayerSet().addUndoStep(new RenameThingStep(thing));
		if (title.length() == 0) {
			// Set the title to the template type
			thing.setTitle(thing.getTemplate().getType());
			return;
		}
		thing.setTitle(title);
		this.updateUILater();
		project.getRootLayerSet().addUndoStep(new RenameThingStep(thing));
	}

	public void mouseDragged(MouseEvent me) { }
	public void mouseReleased(MouseEvent me) { }
	public void mouseEntered(MouseEvent me) { }
	public void mouseExited(MouseEvent me) { }
	public void mouseClicked(MouseEvent me) { }

	public void actionPerformed(final ActionEvent ae) {
		if (!project.isInputEnabled()) return;
		super.dispatcher.exec(new Runnable() { public void run() {
		try {
			if (null == selected_node) return;
			final Object ob = selected_node.getUserObject();
			if (!(ob instanceof ProjectThing)) return;
			final ProjectThing thing = (ProjectThing)ob;
			int i_position = 0;
			String command = ae.getActionCommand();
			final Object obd = thing.getObject();

			if (command.startsWith("new ") || command.equals("Duplicate")) {
				ProjectThing new_thing = null;
				if (command.startsWith("new ")) {
					new_thing = thing.createChild(command.substring(4)); // if it's a Displayable, it will be added to whatever layer is in the front Display
				} else if (command.equals("Duplicate")) { // just to keep myself from screwing in the future
					if (Project.isBasicType(thing.getType()) && null != thing.getParent()) {
						new_thing = ((ProjectThing)thing.getParent()).createClonedChild(thing);
					}
					// adjust parent
					selected_node = (DefaultMutableTreeNode)selected_node.getParent();
				}
				//add it to the tree
				if (null != new_thing) {
					DefaultMutableTreeNode new_node = new DefaultMutableTreeNode(new_thing);
					((DefaultTreeModel)ProjectTree.this.getModel()).insertNodeInto(new_node, selected_node, i_position);
					TreePath treePath = new TreePath(new_node.getPath());
					ProjectTree.this.scrollPathToVisible(treePath);
					ProjectTree.this.setSelectionPath(treePath);
				}
				// bring the display to front
				if (new_thing.getObject() instanceof Displayable) {
					Display.getFront().getFrame().toFront();
				}
			} else if (command.equals("many...")) {
				ArrayList<TemplateThing> children = thing.getTemplate().getChildren();
				if (null == children || 0 == children.size()) return;
				String[] cn = new String[children.size()];
				int i=0;
				for (final TemplateThing child : children) {
					cn[i] = child.getType();
					i++;
				}
				GenericDialog gd = new GenericDialog("Add many children");
				gd.addNumericField("Amount: ", 1, 0);
				gd.addChoice("New child: ", cn, cn[0]);
				gd.addCheckbox("Recursive", true);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				int amount = (int)gd.getNextNumber();
				if (amount < 1) {
					Utils.showMessage("Makes no sense to create less than 1 child!");
					return;
				}
				project.getRootLayerSet().addChangeTreesStep();
				final ArrayList<ProjectThing> nc = thing.createChildren(cn[gd.getNextChoiceIndex()], amount, gd.getNextBoolean());
				addLeafs(nc, new Runnable() {
					public void run() {
						project.getRootLayerSet().addChangeTreesStep();
					}});
			} else if (command.equals("Unhide")) {
				thing.setVisible(true);
			} else if (command.equals("Select in display")) {
				boolean shift_down = 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK);
				selectInDisplay(thing, shift_down);
			} else if (command.equals("Show centered in Display")) {
				if (obd instanceof Displayable) {
					Displayable displ = (Displayable)obd;
					Display.showCentered(displ.getLayer(), displ, true, 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK));
				}
			} else if (command.equals("Show tabular view")) {
				((Tree<?>)obd).createMultiTableView();
			} else if (command.equals("Show in 3D")) {
				Display3D.showAndResetView(thing);
			} else if (command.equals("Remove from 3D view")) {
				Display3D.removeFrom3D(thing);
			} else if (command.equals("Hide")) {
				// find all Thing objects in this subtree starting at Thing and hide their Displayable objects.
				thing.setVisible(false);
			} else if (command.equals("Delete...")) {
				project.getRootLayerSet().addChangeTreesStep(); // store old state
				remove(true, thing, selected_node);
				project.getRootLayerSet().addChangeTreesStep(); // store new state
				return;
			} else if (command.equals("Rename...")) {
				//if (!Project.isBasicType(thing.getType())) {
					rename(thing);
				//}
			} else if (command.equals("Measure")) {
				// block displays while measuring
				Bureaucrat.createAndStart(new Worker("Measuring") { public void run() {
					startedWorking();
					try {
						thing.measure();
					} catch (Throwable e) {
						IJError.print(e);
					} finally {
						finishedWorking();
					}
				}}, thing.getProject());
			}/* else if (command.equals("Export 3D...")) {
				GenericDialog gd = ControlWindow.makeGenericDialog("Export 3D");
				String[] choice = new String[]{".svg [preserves links and hierarchical grouping]", ".shapes [limited to one profile per layer per profile_list]"};
				gd.addChoice("Export to: ", choice, choice[0]);
				gd.addNumericField("Z scaling: ", 1, 2);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				double z_scale = gd.getNextNumber();
				switch (gd.getNextChoiceIndex()) {
					case 0:
						Render.exportSVG(thing, z_scale);
						break;
					case 1:
						new Render(thing).save(z_scale);
						break;
				}
			}*/ else if (command.equals("Export project...") || command.equals("Save as...")) { // "Save as..." is for a FS project
				Utils.log2("Calling export project at " + System.currentTimeMillis());
				thing.getProject().getLoader().saveTask(thing.getProject(), "Save as...");
			} else if (command.equals("Save")) {
				// overwrite the xml file of a FSProject
				// Just do the same as in "Save as..." but without saving the images and overwritting the XML file without asking.
				thing.getProject().getLoader().saveTask(thing.getProject(), "Save");
			} else if (command.equals("Info")) {
				showInfo(thing);
				return;
			} else if (command.equals("Move up")) {
				move(selected_node, -1);
			} else if (command.equals("Move down")) {
				move(selected_node, 1);
			} else if (command.equals("Collapse nodes of children nodes")) {
				if (null == selected_node) return;
				Enumeration<?> c = selected_node.children();
				while (c.hasMoreElements()) {
					DefaultMutableTreeNode child = (DefaultMutableTreeNode) c.nextElement();
					if (child.isLeaf()) continue;
					collapsePath(new TreePath(child.getPath()));
				}
			} else if (command.equals("Sibling project")) {
				sendToSiblingProjectTask(selected_node);
			} else {
				Utils.log("ProjectTree.actionPerformed: don't know what to do with the command: " + command);
				return;
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		}});
	}

	/** Remove the node, its Thing and the object hold by the thing from the database. */
	public void remove(DefaultMutableTreeNode node, boolean check, boolean remove_empty_parents, int levels) {
		Object uob = node.getUserObject();
		if (!(uob instanceof ProjectThing)) return;
		ProjectThing thing = (ProjectThing)uob;
		Display3D.remove(thing);
		ProjectThing parent = (ProjectThing)thing.getParent();
		if (!thing.remove(check)) return;
		((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
		if (remove_empty_parents) removeProjectThingLadder(parent, levels);
		this.updateUILater();
	}

	/** Recursive as long as levels is above zero. Levels defines the number of possibly empty parent levels to remove. */
	public void removeProjectThingLadder(ProjectThing lowest, int levels) {
		if (0 == levels) return;
		if (lowest.getType().toLowerCase().equals("project")) return; // avoid Project node
		// check if empty
		if (null == lowest.getChildren() || 0 == lowest.getChildren().size()) {
			ProjectThing parent = (ProjectThing)lowest.getParent();
			if (!lowest.remove(false)) {
				Utils.log("Failed to remove parent in the ladder: " + lowest);
				return;
			}
			// the node
			DefaultMutableTreeNode node = DNDTree.findNode(lowest, this);
			((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
			removeProjectThingLadder(parent, levels--);
		}
	}

	/** Implements the "Duplicate, link and send to next/previous layer" functionality. The 'position' can be zero (before) or 1 (after). The profile has no layer assigned.  */
	public Profile duplicateChild(Profile original, int position, Layer layer) {
		Utils.log2("ProjectTree: Called duplicateChild " + System.currentTimeMillis() + " for original id = " + original.getId() + " at position " + position);
		// find the Thing that holds it
		Thing child = project.findProjectThing(original);
		if (null == child) {
			Utils.log("ProjectTree.duplicateChild: node not found for original " + original);
			return null;
		}
		Profile copy = (Profile)original.clone();
		copy.setLayer(layer); // for the Z ordering
		ProjectThing new_thing = null;
		try {
			new_thing = new ProjectThing(((ProjectThing)child.getParent()).getChildTemplate(child.getType()), original.getProject(), copy);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)findNode(child, this);
		DefaultMutableTreeNode parent_node = (DefaultMutableTreeNode)child_node.getParent();
		ProjectThing parent_thing = (ProjectThing)parent_node.getUserObject();
		//sanity check:
		if (position < 0) position = 0;
		else if (position > 1) position = 1;
		int index = parent_node.getIndex(child_node) + position;
		if (index < 0) index = 0;
		if (index > parent_node.getChildCount()) index = parent_node.getChildCount() -1;
		if (!parent_thing.addChild(new_thing, index)) return null;
		DefaultMutableTreeNode new_node = new DefaultMutableTreeNode(new_thing);
		((DefaultTreeModel)this.getModel()).insertNodeInto(new_node, parent_node, index /*parent_node.getIndex(child_node) + position*/);
		// relist properly the nodes
		updateList(parent_node);
		TreePath treePath = new TreePath(new_node.getPath());
		this.scrollPathToVisible(treePath);
		this.setSelectionPath(treePath);

		return copy;
	}

	public void destroy() {
		super.destroy();
		this.selected_node = null;
	}

	/* // makes no sense, because there may be multiple projects open and thus the viewport and position may interfere with each other across multiple projects. Saving the collapsed node state suffices.
	public void exportXML(final StringBuffer sb_body, String indent, JScrollPane jsp) {
		Point p = jsp.getViewport().getViewPosition();
		Dimension d = jsp.getSize(null);
		sb_body.append(indent).append("<t2_tree")
		       .append(" width=\"").append(d.width).append('\"')
		       .append(" height=\"").append(d.height).append('\"')
		       .append(" viewport_x=\"").append(p.e).append('\"')
		       .append(" viewport_y=\"").append(p.y).append('\"')
		;
		sb_body.append("\n").append(indent).append("</t2_tree>\n");
	}
	*/

	/** Creates a new node of basic type for each AreaList, Ball, Pipe or Polyline present in the ArrayList. Other elements are ignored. */
	public void insertSegmentations(final Collection<? extends Displayable> al) {
		final TemplateThing tt_root = (TemplateThing)project.getTemplateTree().getRoot().getUserObject();
		// create a new abstract node called "imported_segmentations", if not there
		final String imported_labels = "imported_labels";
		if (!project.typeExists(imported_labels)) {
			// create it
			TemplateThing tet = new TemplateThing(imported_labels, project); // yes I know I should check for the project of each Displayable in the ArrayList
			project.addUniqueType(tet);
			DefaultMutableTreeNode root = project.getTemplateTree().getRoot();
			tt_root.addChild(tet);
			DefaultMutableTreeNode child_node = addChild(tet, root);
			DNDTree.expandNode(project.getTemplateTree(), child_node);
			// JTree is serious pain
		}
		TemplateThing tt_is = project.getTemplateThing(imported_labels); // it's the same as 'tet' above, unless it existed
		// create a project node from "imported_segmentations" template under a new top node
		final DefaultMutableTreeNode project_node = project.getProjectTree().getRoot();
		ProjectThing project_pt = (ProjectThing)project_node.getUserObject();
		final ProjectThing ct = project_pt.createChild(tt_root.getType());
		ProjectThing pt_is = ct.createChild(imported_labels);

		final DefaultMutableTreeNode node_pt_is = new DefaultMutableTreeNode(pt_is); //addChild(pt_is, ctn);

		final HashMap<Class<?>,String> types = new HashMap<Class<?>,String>();
		types.put(AreaList.class, "area_list");
		types.put(Pipe.class, "pipe");
		types.put(Polyline.class, "polyline");
		types.put(Ball.class, "ball");
		types.put(Treeline.class, "treeline");
		types.put(AreaTree.class, "areatree");
		types.put(Connector.class, "connector");

		// now, insert a new ProjectThing if of type AreaList, Ball and/or Pipe under node_child
		for (final Displayable d : al) {
			final String type = types.get(d.getClass());
			if (null == type) {
				Utils.log("insertSegmentations: ignoring " + d);
				continue;
			}
			try {
				final TemplateThing tt = getOrCreateChildTemplateThing(tt_is, type);
				ProjectThing one = new ProjectThing(tt, project, d);
				pt_is.addChild(one);
				//addChild(one, node_pt_is);
				node_pt_is.add(new DefaultMutableTreeNode(one)); // at the end
				//Utils.log2("one parent : " + one.getParent());
			} catch (Exception e) {
				IJError.print(e);
			}
		}

		javax.swing.SwingUtilities.invokeLater(new Runnable() { public void run() {
			DefaultMutableTreeNode ctn = addChild(ct, project_node);
			ctn.add(node_pt_is);
			try {
				ProjectTree.this.scrollPathToVisible(new TreePath(node_pt_is.getPath()));
			} catch (Exception e) {
				e.printStackTrace();
			}
		}});

		DNDTree.expandNode(this, node_pt_is);
	}

	private final TemplateThing getOrCreateChildTemplateThing(TemplateThing parent, String type) {
		TemplateThing tt = parent.getChildTemplate(type);
		if (null == tt) {
			tt = new TemplateThing(type, parent.getProject());
			tt.getProject().addUniqueType(tt);
			parent.addChild(tt);
		}
		return tt;
	}

	@Override
	public void keyPressed(final KeyEvent ke) {
		super.keyPressed(ke);
		if (ke.isConsumed()) return;
		if (!ke.getSource().equals(ProjectTree.this) || !project.isInputEnabled()) {
			return;
		}
		// get the first selected node only
		TreePath path = getSelectionPath();
		if (null == path) return;
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
		if (null == node) return;
		final ProjectThing pt = (ProjectThing)node.getUserObject();
		if (null == pt) return;
		//
		final int flags = ke.getModifiers();
		switch (ke.getKeyCode()) {
			case KeyEvent.VK_PAGE_UP:
				move(node, -1);
				ke.consume(); // in any case
				break;
			case KeyEvent.VK_PAGE_DOWN:
				move(node, 1);
				ke.consume(); // in any case
				break;
			case KeyEvent.VK_F2:
				rename(pt);
				ke.consume();
				break;
			case KeyEvent.VK_H:
				if (0 == (flags ^ Event.ALT_MASK)) {
					pt.setVisible(true);
					ke.consume();
				} else if (0 == flags) {
					pt.setVisible(false);
					ke.consume();
				}
				break;
			case KeyEvent.VK_A:
				if (0 == flags || (0 == (flags ^ Event.SHIFT_MASK))) {
					selectInDisplay(pt, 0 == (flags ^ Event.SHIFT_MASK));
					ke.consume();
				}
				break;
			case KeyEvent.VK_3:
				if (0 == flags) {
					ini.trakem2.display.Display3D.showAndResetView(pt);
					ke.consume();
					break;
				}
				// else, flow:
			case KeyEvent.VK_1:
			case KeyEvent.VK_2:
			case KeyEvent.VK_4:
			case KeyEvent.VK_5:
			case KeyEvent.VK_6:
			case KeyEvent.VK_7:
			case KeyEvent.VK_8:
			case KeyEvent.VK_9:
				// run a plugin, if any
				if (pt.getObject() instanceof Displayable && null != Utils.launchTPlugIn(ke, "Project Tree", project, (Displayable)pt.getObject())) {
					ke.consume();
				}
				break;
		}
		ke.consume();
	}

	/** Move up (-1) or down (1). */
	private void move(final DefaultMutableTreeNode node, final int direction) {
		final ProjectThing pt = (ProjectThing)node.getUserObject();
		if (null == pt) return;
		// Move: within the list of children of the parent ProjectThing:
		if (!pt.move(direction)) return;
		// If moved, reposition within the list of children
		final DefaultMutableTreeNode parent_node = (DefaultMutableTreeNode)node.getParent();
		int index = parent_node.getChildCount() -1;
		((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
		index = pt.getParent().getChildren().indexOf(pt);
		((DefaultTreeModel)this.getModel()).insertNodeInto(node, parent_node, index);
		// restore selection path
		final TreePath trp = new TreePath(node.getPath());
		this.scrollPathToVisible(trp);
		this.setSelectionPath(trp);
		this.updateUILater();
	}

	/** If the given node is null, it will be searched for. */
	public boolean remove(final boolean check, final ProjectThing thing, final DefaultMutableTreeNode node) {
		final Object obd = thing.getObject();
		if (obd.getClass() == Project.class) return ((Project)obd).remove(check); // shortcut to remove everything regardless.
		final boolean b = thing.remove(check) && removeNode(null != node ? node : findNode(thing, this));
		Display.repaint();
		return b;
	}

	/** Remove the Thing and DefaultMutableTreeNode that wrap each of the Displayable;
	 *  calls softRemove on each Displayable, and does NOT call remove on the Displayable.
	 *  If a Displayable is not found, it returns it in a set of not found objects.
	 *  If all are removed, returns an empty set. */
	public final Set<Displayable> remove(final Set<? extends Displayable> displayables, final DefaultMutableTreeNode top) {
		final Enumeration<?> en = (null == top ? (DefaultMutableTreeNode)getModel().getRoot() : top).depthFirstEnumeration();
		final HashSet<DefaultMutableTreeNode> to_remove = new HashSet<DefaultMutableTreeNode>();
		final HashSet<Displayable> remaining = new HashSet<Displayable>(displayables);
		while (en.hasMoreElements()) {
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode)en.nextElement();
			final ProjectThing pt = (ProjectThing)node.getUserObject();
			if (remaining.remove(pt.getObject())) {
				pt.remove(false, false); // don't call remove on the object!
				((Displayable)pt.getObject()).softRemove();
				to_remove.add(node);
			}
		}
		// Updates the model properly:
		for (final DefaultMutableTreeNode node : to_remove) {
			((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
		}
		if (!remaining.isEmpty()) {
			Utils.log("Could not remove:", remaining);
		}
		return remaining;
	}

	public void showInfo(ProjectThing thing) {
		if (null == thing) return;
		HashMap<String,ArrayList<ProjectThing>> ht = thing.getByType();
		ArrayList<String> types = new ArrayList<String>();
		types.addAll(ht.keySet());
		Collections.sort(types);
		StringBuilder sb = new StringBuilder(thing.getNodeInfo());
		sb.append("\nCounts:\n");
		for (String type : types) {
			sb.append(type).append(": ").append(ht.get(type).size()).append('\n');
		}
		sb.append('\n');
		sb.append(thing.getInfo().replaceAll("\t", "    ")); // TextWindow can't handle tabs
		new ij.text.TextWindow("Info", sb.toString(), 500, 500);
	}

	public void selectInDisplay(final ProjectThing thing, final boolean shift_down) {
		Object obd = thing.getObject();
		if (obd instanceof Displayable) {
			Displayable d = (Displayable)obd;
			if (!d.isVisible()) d.setVisible(true);
			Display display = Display.getFront(d.getProject());
			if (null == display) return;
			display.select(d, shift_down);
		} else {
			// select all basic types under this leaf
			final List<Displayable> ds = thing.findChildrenOfType(Displayable.class);
			Display display = null;
			for (final Iterator<Displayable> it = ds.iterator(); it.hasNext(); ) {
				final Displayable d = it.next();
				if (null == display) {
					display = Display.getFront(d.getProject());
					if (null == display) return;
				}
				if (!d.isVisible()) {
					Utils.log("Skipping non-visible object " + d);
					it.remove();
				}
			}
			if (null == display) return;
			if (!shift_down) display.getSelection().clear();
			display.getSelection().selectAll(ds);
		}
	}

	/** Finds the node for the elder and adds the sibling next to it, under the same parent. */
	public DefaultMutableTreeNode addSibling(final Displayable elder, final Displayable sibling) {
		if (null == elder || null == sibling) return null;
		if (elder.getProject() != sibling.getProject()) {
			Utils.log2("Can't mix projects!");
			return null;
		}
		DefaultMutableTreeNode enode = DNDTree.findNode2(elder, this);
		if (null == enode) {
			Utils.log2("Could not find a tree node for elder " + elder);
			return null;
		}
		ProjectThing parent = (ProjectThing)((ProjectThing)enode.getUserObject()).getParent();
		if (null == parent) {
			Utils.log2("No parent for elder " + elder);
			return null;
		}
		TemplateThing tt = elder.getProject().getTemplateThing(Project.getType(sibling.getClass()));
		if (null == tt) {
			Utils.log2("Could not find a template for class " + sibling.getClass());
			return null;
		}
		if (!parent.getTemplate().canHaveAsChild(tt)) {
			// DONE BELOW: parent.getTemplate().addChild(tt);
			if (null == parent.getProject().getTemplateTree().addNewChildType(parent, tt.getType())) {
				return null;
			}
			Utils.log2("Added template " + tt.getType() + " as child of " + parent.getTemplate().getType());
		}
		ProjectThing pt;
		try {
			pt = new ProjectThing(tt, sibling.getProject(), sibling);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		if (!parent.addChild(pt)) {
			Utils.log2("Could not add sibling!");
			return null;
		}

		DefaultMutableTreeNode node = new DefaultMutableTreeNode(pt);
		int index = enode.getParent().getIndex(enode);
		((DefaultTreeModel)getModel()).insertNodeInto(node, (DefaultMutableTreeNode)enode.getParent(), index + 1);
		return node;
	}
	
	/** Attempt to add the {@param d} as a {@link ProjectThing} child of type {@param childType}
	 * to the parent {@link ProjectThing} {@param parent}. A new {@link DefaultMutableTreeNode}
	 * will be added to the {@link DefaultMutableTreeNode} that encapsulates the {@param parent}.
	 * 
	 * Will fail by returning null if:
	 *  1. The {@param parent} {@link ProjectThing} cannot have a child of type {@param childType}.
	 *  2. The {@link ProjectThing} constructor throws an Exception, for example if {@link d} is null.
	 *  3. The {@param parent} {@link ProjectThing#addChild(Thing)} returns false.
	 *  
	 *  @return The new {@link DefaultMutableTreeNode}.
	 * */
	public DefaultMutableTreeNode addChild(final ProjectThing parent, final String childType, final Displayable d) {
		if (!parent.canHaveAsChild(childType)) {
			Utils.log("The type '" + parent.getType() + "' cannot have as child the type '" + childType + "'");
			return null;
		}
		ProjectThing pt;
		try {
			pt = new ProjectThing(project.getTemplateThing(childType), project, d);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		if (!parent.addChild(pt)) {
			Utils.log("Could not add child to " + parent);
			return null;
		}
		DefaultMutableTreeNode parentNode = DNDTree.findNode(parent, this);
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(pt);
		((DefaultTreeModel)getModel()).insertNodeInto(node, parentNode, parentNode.getChildCount());
		return node;
	}

	protected DNDTree.NodeRenderer createNodeRenderer() {
		return new ProjectThingNodeRenderer();
	}

	static protected final Color ACTIVE_DISPL_COLOR = new Color(1.0f, 1.0f, 0.0f, 0.5f);

	protected final class ProjectThingNodeRenderer extends DNDTree.NodeRenderer {
		private static final long serialVersionUID = 1L;

		public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean selected, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
			final JLabel label = (JLabel)super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
			label.setText(label.getText().replace('_', ' ')); // just for display
			if (value.getClass() == DefaultMutableTreeNode.class) {
				final Object obb = ((DefaultMutableTreeNode)value).getUserObject();
				final Object ob = ((ProjectThing)obb).getObject();
				if (ob.getClass().getSuperclass() == Displayable.class) {
					final Displayable displ = (Displayable)ob;
					final Layer layer = Display.getFrontLayer();
					if (null != layer && (displ == Display.getFront().getActive() || layer.contains(displ))) {
						label.setOpaque(true); //this label
						label.setBackground(ACTIVE_DISPL_COLOR); // this label
						//Utils.log(" -- setting background");
					} else {
						label.setOpaque(false); //this label
						label.setBackground(background);
						//Utils.log(" not contained ");
					}
				} else {
					label.setOpaque(false); //this label
					label.setBackground(background);
					//Utils.log("ob is " + ob);
				}
			} else {
				label.setOpaque(false);
				label.setBackground(background);
				//Utils.log("value is " + value);
			}
			return label;
		}
	}

	public Bureaucrat sendToSiblingProjectTask(final DefaultMutableTreeNode node) {
		return Bureaucrat.createAndStart(new Worker.Task("Send to sibling") {
			public void exec() {
				sendToSiblingProject(node);
			}
		}, this.project);
	}

	/** When two or more people work on the same XML file, images may be the same but segmentations and the transformations of the images may diverge.
	 *  This function provides the means to send VectorData instances, wrapped in tree nodes, from one project to another,
	 *  transforming the VectorData as appropriate to fall onto the same locations on the images.
	 *  The ids of the copied objects will be new and unique for the target project.
	 *  A dialog opens asking for options. */
	@SuppressWarnings("unchecked")
	public boolean sendToSiblingProject(final DefaultMutableTreeNode node) {
		ArrayList<Project> ps = Project.getProjects();
		if (1 == ps.size()) {
			Utils.log("There aren't any other projects open!");
			return false;
		}
		final ProjectThing pt = (ProjectThing) node.getUserObject();
		if (pt.getTemplate().getType().equals("project")) {
			Utils.log("Cannot transfer the project node.");
			return false;
		}
		final ArrayList<Project> psother = new ArrayList<Project>(ps);
		psother.remove(this.project);
		ps = null;
		// Find all potential landing nodes for this node: those with a TemplateThing type like the parent of node:
		final String parent_type = ((ProjectThing)pt.getParent()).getTemplate().getType();
		final List<ProjectThing> landing_pt = new ArrayList<ProjectThing>(psother.get(0).getRootProjectThing().findChildrenOfTypeR(parent_type));
		final Comparator<ProjectThing> comparator = new Comparator<ProjectThing>() {
			public int compare(ProjectThing t1, ProjectThing t2) {
				return t1.toString().compareTo(t2.toString());
			}
			public boolean equals(Object o) { return this == o; }
		};
		Collections.sort(landing_pt, comparator);
		String[] landing = new String[landing_pt.size()];
		int next = 0;
		if (landing_pt.isEmpty()) {
			landing = new String[]{"-- NONE --"};
		} else for (ProjectThing t : landing_pt) landing[next++] = t.toString();
		
		// Suggest the first potential landing node that has the same title
		String parentTitle = pt.getParent().toString();
		int k = 0;
		boolean matched = false;
		// First search for exact match
		for (final String candidate : landing) {
			if (candidate.equals(parentTitle)) {
				matched = true;
				break;
			}
			k += 1;
		}
		// If not matched, find one that contains the string
		if (!matched) {
			k = 0;
			for (final String candidate : landing) {
				if (-1 != candidate.indexOf(parentTitle)) {
					matched = true;
					break;
				}
				k += 1;
			}
		}
		if (!matched) {
			k = 0;
		}

		// Ask:
		GenericDialog gd = new GenericDialog("Send to sibling project");
		gd.addMessage("Transfering node: " + pt);
		final String[] trmode = new String[]{"As is", "Transformed as the images"};
		gd.addChoice("Transfer:", trmode, trmode[0]);
		String[] ptitles = new String[psother.size()];
		for (int i=0; i<ptitles.length; i++) ptitles[i] = psother.get(i).toString();
		gd.addChoice("Target project:", ptitles, ptitles[0]);
		gd.addChoice("Landing node:", landing, landing[k]);
		final Vector<Choice> vc = (Vector<Choice>) gd.getChoices();
		final Choice choice_project = vc.get(vc.size()-2);
		final Choice choice_landing = vc.get(vc.size()-1);
		choice_project.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				landing_pt.clear();
				landing_pt.addAll(psother.get(choice_project.getSelectedIndex()).getRootProjectThing().findChildrenOfTypeR(parent_type));
				Collections.sort(landing_pt, comparator);
				choice_landing.removeAll();
				if (landing_pt.isEmpty()) {
					choice_landing.add("-- NONE --");
				} else for (ProjectThing t : landing_pt) choice_landing.add(t.toString());
			}
		});

		gd.showDialog();
		if (gd.wasCanceled()) return false;

		if (choice_landing.getSelectedItem().equals("-- NONE --")) {
			Utils.log("No valid landing nodes!");
			return false;
		}

		final int transfer_mode = gd.getNextChoiceIndex();
		final Project target_project = psother.get(gd.getNextChoiceIndex());
		final ProjectThing landing_parent = landing_pt.get(gd.getNextChoiceIndex());

		return rawSendToSiblingProject(pt, transfer_mode, target_project, landing_parent);
	}
	
	/** Assumes that both projects have the same TemplateThing structure,
	 * and assumes that the parent of the ({@param source_pt} and the {@param landing_parent}
	 * instances are of the same type.
	 * 
	 * @param source_pt The {@link ProjectThing} to be cloned.
	 * @param transfer_mode Either 0 ("As is") or 1 ("Transformed with the images").
	 * @param target_project The sibling project into which insert a clone of the {@param source_pt}.
	 * @param landing_parent The ProjectThing in the sibling project that receives the cloned {@param source_pt}.
	 * 
	 * */
	public boolean rawSendToSiblingProject(
			final ProjectThing source_pt, // the source ProjectThing to copy to the target project
			final int transfer_mode,
			final Project target_project,
			final ProjectThing landing_parent) {

		try {
			// Check that all the Layers used by the objects to transfer also exist in the target project!
			// 1 - Cheap way: check if all layers in the target project exist in the source project, by id
			HashSet<Long> lids = new HashSet<Long>();
			for (final Layer layer : this.project.getRootLayerSet().getLayers()) {
				lids.add(layer.getId());
			}
			HashSet<Long> tgt_lids = new HashSet<Long>(lids);
			for (final Layer layer : target_project.getRootLayerSet().getLayers()) {
				lids.remove(layer.getId());
				tgt_lids.add(layer.getId());
			}

			List<Displayable> original_vdata = null;
			final Set<Long> lids_to_operate = new HashSet<Long>();
			if (0 != lids.size()) {
				original_vdata = new ArrayList<Displayable>();
				// Further checking needed (there could just simply be more layers in the target than in the source project):
				// 2 - Expensive way: check the layers in which each Displayable to clone from this project has data.
				//                    All their layers MUST be in the target project.
				for (final ProjectThing child : source_pt.findChildrenOfTypeR(Displayable.class)) {
					final Displayable d = (Displayable) child.getObject();
					if (!tgt_lids.containsAll(d.getLayerIds())) {
						Utils.log("CANNOT transfer: not all required layers are present in the target project!\n  First object that couldn't be transfered: \n    " + d);
						return false;
					}
					if (d instanceof VectorData) {
						original_vdata.add(d);
						lids_to_operate.addAll(d.getLayerIds());
					}
				}
			}

			// Deep cloning of the ProjectThing to transfer, then added to the landing_parent in the other tree.
			ProjectThing copy;
			try{
				copy = source_pt.deepClone(target_project, false); // new ids, taken from target_project
			} catch (Exception ee) {
				Utils.logAll("Can't send: " + ee.getMessage());
				IJError.print(ee);
				return false;
			}
			if (null == landing_parent.getChildTemplate(copy.getTemplate().getType())) {
				landing_parent.getTemplate().addChild(copy.getTemplate().shallowCopy()); // ensure a copy is there
			}
			if (!landing_parent.addChild(copy)) {
				Utils.log("Could NOT transfer the node!");
				return false;
			}
			
			// Get the list of Profile instances in the source Project, in the same order
			// that they will be in the target project:
			final List<Profile> srcProfiles = new ArrayList<Profile>();
			for (final ProjectThing profile_pt : source_pt.findChildrenOfTypeR(Profile.class)) {
				srcProfiles.add((Profile)profile_pt.getObject());
			}

			final List<ProjectThing> copies = copy.findChildrenOfTypeR(Displayable.class);
			final List<Profile> newProfiles = new ArrayList<Profile>();
			//Utils.log2("copies size: " + copies.size());
			final List<Displayable> vdata = new ArrayList<Displayable>();
			final List<ZDisplayable> zd = new ArrayList<ZDisplayable>();
			for (final ProjectThing t : copies) {
				final Displayable d = (Displayable) t.getObject();
				if (d instanceof VectorData) vdata.add(d); // all should be, this is just future-proof code.
				if (d instanceof ZDisplayable) {
					zd.add((ZDisplayable)d);
				} else {
					// profile: always special
					newProfiles.add((Profile)d);
				}
			}

			// Fix Profile instances: exploit that the order as been conserved when copying.
			int profileIndex = 0;
			for (final Profile newProfile : newProfiles) {
				// Corresponding Profile:
				final Profile srcProfile = srcProfiles.get(profileIndex++);
				// Corresponding layer: layers have the same IDs by definition of what a sibling Project is.
				final Layer newLayer = target_project.getRootLayerSet().getLayer(srcProfile.getLayer().getId());
				newLayer.add(newProfile);
				// Corresponding links
				for (final Displayable srcLinkedProfile : srcProfile.getLinked(Profile.class)) {
					newProfile.link(newProfiles.get(srcProfiles.indexOf(srcLinkedProfile)));
				}
			}

			target_project.getRootLayerSet().addAll(zd); // add them all in one shot

			target_project.getTemplateTree().rebuild(); // could have changed
			target_project.getProjectTree().rebuild(); // When trying to rebuild just the landing_parent, it doesn't always work. Needs checking TODO

			// Open up the path to the landing parent node
			final TreePath tp = new TreePath(DNDTree.findNode(landing_parent, target_project.getProjectTree()).getPath());
			Utils.invokeLater(new Runnable() { public void run() {
				target_project.getProjectTree().scrollPathToVisible(tp);
				target_project.getProjectTree().setSelectionPath(tp);
			}});
			

			// Now that all have been copied, transform if so asked for:

			if (1 == transfer_mode) {
				// Collect original vdata
				if (null == original_vdata) {
					original_vdata = new ArrayList<Displayable>();
					for (final ProjectThing child : source_pt.findChildrenOfTypeR(Displayable.class)) {
						final Displayable d = (Displayable) child.getObject();
						if (d instanceof VectorData) {
							original_vdata.add(d);
							lids_to_operate.addAll(d.getLayerIds());
						}
					}
				}
				//Utils.log2("original vdata:", original_vdata);
				//Utils.log2("vdata:", vdata);
				// Transform with images
				AlignTask.transformVectorData(AlignTask.createTransformPropertiesTable(original_vdata, vdata, lids_to_operate), vdata, target_project.getRootLayerSet());
			} // else if trmodep[0], leave as is.
			
			return true;

		} catch (Exception e) {
			IJError.print(e);
		}

		return false;
	}
	
	@Override
	protected Thing getRootThing() {
		return project.getRootProjectThing();
	}

	/** If the parent node of {@param active} can accept a Connector or has a direct child
	 * node that can accept a {@link Connector}, add a new {@link Connector} and return it.
	 * 
	 * @param d The {@link Displayable} that serves as reference, to decide which node to add the new {@link Connector}.
	 * @param selectNode Whether to select the new node containing the {@link Connector} in the ProjectTree.
	 * 
	 * @return The newly created {@link Connector}. */
	public Connector tryAddNewConnector(final Displayable d, final boolean selectNode) {
		ProjectThing pt = project.findProjectThing(d);
		ProjectThing parent = (ProjectThing) pt.getParent();
		TemplateThing connectorType = project.getTemplateThing("connector");
		boolean add = false;
		if (parent.canHaveAsChild(connectorType)) {
			// Add as a sibling of pt
			add = true;
		} else {
			// Inspect if any of the sibling nodes can have it as child
			for (final ProjectThing child : parent.getChildren()) {
				if (child.canHaveAsChild(connectorType)) {
					parent = child;
					add = true;
					break;
				}
			}
		}
		Connector c = null;
		DefaultMutableTreeNode node = null;
		if (add) {
			c = new Connector(project, connectorType.getType()); // reuse same String instance
			node = addChild(parent, connectorType.getType(), c);
		}
		if (null != node) {
			d.getLayerSet().add(c);
			if (selectNode) setSelectionPath(new TreePath(node.getPath()));
			return c;
		}
		Utils.logAll("Could not add a new Connector related to " + d);
		return null;
	}
}
