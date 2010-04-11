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
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.*;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Render;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Dispatcher;
import mpicbg.trakem2.align.AlignTask;

import java.awt.Color;
import java.awt.Component;
import java.awt.Event;
import java.awt.event.KeyEvent;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import java.awt.Choice;
import javax.swing.KeyStroke;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import javax.swing.JLabel;
import javax.swing.JTree;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.tree.*;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.Hashtable;
import java.util.Collections;
import java.util.Vector;
import java.util.Comparator;

/** A class to hold a tree of Thing nodes */
public final class ProjectTree extends DNDTree implements MouseListener, ActionListener {

	/*
	static {
		System.setProperty("j3d.noOffScreen", "true");
	}
	*/

	private DefaultMutableTreeNode selected_node = null;
	private DefaultMutableTreeNode repeatable_node = null;
	private DefaultMutableTreeNode selected_before_repeated_node = null;

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
		item = new JMenuItem("Set as repeatable (davi-experimenting)"); item.addActionListener(this); item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SEMICOLON, 0, true)); node_menu.add(item);
		popup.add(node_menu);

		JMenu send_menu = new JMenu("Send to");
		item = new JMenuItem("Sibling project"); item.addActionListener(this); send_menu.add(item);
		popup.add(send_menu);

		return popup;
	}

	public void mousePressed(final MouseEvent me) {
		super.dispatcher.execSwing(new Runnable() { public void run() {
		if (!me.getSource().equals(ProjectTree.this) || !Project.getInstance(ProjectTree.this).isInputEnabled()) {
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

	public void rename(ProjectThing thing) {
		Object ob = thing.getObject();
		String old_title = null;
		if (null == ob) old_title = thing.getType();
		else if (ob instanceof DBObject) old_title = ((DBObject)ob).getTitle();
		else old_title = ob.toString();
		GenericDialog gd = ControlWindow.makeGenericDialog("New name");
		gd.addMessage("Old name: " + old_title);
		gd.addStringField("New name: ", old_title, 40);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		project.getRootLayerSet().addUndoStep(new RenameThingStep(thing));
		String title = gd.getNextString();
		title = title.replace('"', '\'').trim(); // avoid XML problems - could also replace by double '', then replace again by " when reading.
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
		if (!Project.getInstance(this).isInputEnabled()) return;
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
				ArrayList children = thing.getTemplate().getChildren();
				if (null == children || 0 == children.size()) return;
				String[] cn = new String[children.size()];
				int i=0;
				for (Iterator it = children.iterator(); it.hasNext(); ) {
					cn[i] = ((TemplateThing)it.next()).getType();
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
				final ArrayList nc = thing.createChildren(cn[gd.getNextChoiceIndex()], amount, gd.getNextBoolean());
				addLeafs((ArrayList<Thing>)nc, new Runnable() {
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
			} else if (command.equals("Show in 3D")) {
				ini.trakem2.display.Display3D.showAndResetView(thing);
			} else if (command.equals("Hide")) {
				// find all Thing objects in this subtree starting at Thing and hide their Displayable objects.
				thing.setVisible(false);
			} else if (command.equals("Delete...")) {
				if (repeatable_node == selected_node) {
					setRepeatableNode(null);
				}
				remove(true, thing, selected_node);
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
				thing.getProject().getLoader().saveAs(thing.getProject());
			} else if (command.equals("Save")) {
				// overwrite the xml file of a FSProject
				// Just do the same as in "Save as..." but without saving the images and overwritting the XML file without asking.
				thing.getProject().getLoader().save(thing.getProject());
			} else if (command.equals("Merge many...")) { // davi-experimenting
				mergeManyTask();
			} else if (command.equals("Info")) {
				showInfo(thing);
				return;
			} else if (command.equals("Move up")) {
				move(selected_node, -1);
			} else if (command.equals("Move down")) {
				move(selected_node, 1);
			} else if (command.equals("Sibling project")) {
				sendToSiblingProjectTask(selected_node);			
			} else if (command.equals("Set as repeatable (davi-experimenting)")) {
				setRepeatableNode(selected_node);
				return;
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

	/** Recursive as long as levels is above zero. Levels defines the number of possibly emtpy parent levels to remove. */
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
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)this.getModel().getRoot();
		ProjectThing root_thing = (ProjectThing)root.getUserObject();
		Thing child = root_thing.findChild(original);
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
	public void insertSegmentations(final Project project, final Collection al) {
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

		final HashMap<Class,String> types = new HashMap<Class,String>();
		types.put(AreaList.class, "area_list");
		types.put(Pipe.class, "pipe");
		types.put(Polyline.class, "polyline");
		types.put(Ball.class, "ball");

		// now, insert a new ProjectThing if of type AreaList, Ball and/or Pipe under node_child
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			TemplateThing tt = null;
			String type = types.get(ob.getClass());
			if (null == type) {
				Utils.log("insertSegmentations: ignoring " + ob);
				continue;
			} else {
				tt = getOrCreateChildTemplateThing(tt_is, type);
			}
			try {
				ProjectThing one = new ProjectThing(tt, project, ob);
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

	public void keyPressed(final KeyEvent ke) {
		super.keyPressed(ke);
		if (ke.isConsumed()) return;
		super.dispatcher.execSwing(new Runnable() { public void run() {
		if (!ke.getSource().equals(ProjectTree.this) || !Project.getInstance(ProjectTree.this).isInputEnabled()) {
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
			case KeyEvent.VK_SEMICOLON:
				setRepeatableNode(node);
				ke.consume();
				break;
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
		}});
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
	
	private void setRepeatableNode(final DefaultMutableTreeNode node) {
		repeatable_node = node;
	}
	
	public void createRepeatable() {
		// following code for ProjectTree.actionPerformed "new" command
		if (!Project.getInstance(this).isInputEnabled()) { Utils.log("Warning: ProjectTree.createRepeatable was invoked when the project tree was not input enabled"); return;}
		if (null == repeatable_node) { Utils.log("Warning: ProjectTree.createRepeatable was invoked when repeatable_node was null"); return; }
		final Object ob = repeatable_node.getUserObject();
		if (!(ob instanceof ProjectThing)) { Utils.log("Warning: ProjectTree.createRepeatable was invoked on a repeatable_node not of type ProjectThing"); return; }
		final ProjectThing thing = (ProjectThing)ob;
		if (thing.getRootParent() == thing) { Utils.log("Warning: ProjectTree.createRepeatable was invoked on the root node"); return; } // TODO dunno if this is possible; dunno what to do if it is.
		
		selected_before_repeated_node = selected_node;
		
		project.getRootLayerSet().addChangeTreesStep();
		ArrayList nc = thing.createSibling(null, (ProjectThing)thing.getParent());
		addLeafs((ArrayList<Thing>)nc, new Runnable() {
			public void run() {
				project.getRootLayerSet().addChangeTreesStep();
			}});
		// This approach is not working -- other nodes added as part of createRepeatable are confusing the issue?
		// The Restore Selection command is also confused by this. Haven't figured out how to overcome it, so kludge a "selected_before_repeated_node" class variable onto it
		/*if (null != old_selected_node) {
			final TreePath old_tree_path = new TreePath(old_selected_node.getPath());
			// bounce back & forth so that "restore selection" command selects what was selected before createRepeatable was called
			this.setSelectionPath(old_tree_path);
			this.scrollPathToVisible(old_tree_path);
			this.updateUILater();
		}*/
	}
	
	public boolean hasRepeatable() {
		return (null != repeatable_node);
	}

	public void reboundFromRepeated() {
		Utils.log2("ProjectTree.reboundFromRepeated called");
		if (null != selected_before_repeated_node) {
			final TreePath trp = new TreePath(selected_before_repeated_node.getPath());
			this.scrollPathToVisible(trp);
			this.setSelectionPath(trp);
			this.updateUILater();
		}
	}
	/** If the given node is null, it will be searched for. */
	public boolean remove(boolean check, ProjectThing thing, DefaultMutableTreeNode node) {
		Object obd = thing.getObject();
		if (obd instanceof Project) return ((Project)obd).remove(check); // shortcut to remove everything regardless.
		boolean b = thing.remove(check) && removeNode(null != node ? node : findNode(thing, this));
		// This is a patch: removal from buckets is subtly broken
		// thing.getProject().getRootLayerSet().recreateBuckets(true);
		// The true problem is that the offscreen repaint thread sets the DisplayCanvas.al_top list before, not at the end of removing all.
		// --> actually no: querying the LayerSet buckets for ZDisplayables still returns them, but al_zdispl doesn't have them.
		thing.getProject().getRootLayerSet().recreateBuckets(true);
		Display.repaint();
		return b;
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
			HashSet hs = thing.findBasicTypeChildren();
			boolean first = true;
			Display display = null;
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				Object ptob = ((ProjectThing)it.next()).getObject();
				if (!(ptob instanceof Displayable)) {
					Utils.log2("Skipping non-Displayable object " + ptob);
					continue;
				}
				Displayable d = (Displayable)ptob;
				if (null == display) {
					display = Display.getFront(d.getProject());
					if (null == display) return;
				}
				if (!d.isVisible()) d.setVisible(true);
				if (first) {
					display.select(d, shift_down);
					first = false;
				} else {
					display.select(d, true);
				}
			}
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
		ProjectThing pt;
		try {
			pt = new ProjectThing(tt, sibling.getProject(), sibling);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		parent.addChild(pt);

		DefaultMutableTreeNode node = new DefaultMutableTreeNode(pt);
		int index = enode.getParent().getIndex(enode);
		((DefaultTreeModel)getModel()).insertNodeInto(node, (DefaultMutableTreeNode)enode.getParent(), index + 1);
		return node;
	}

	protected DNDTree.NodeRenderer createNodeRenderer() {
		return new ProjectThingNodeRenderer();
	}

	static protected final Color ACTIVE_DISPL_COLOR = new Color(1.0f, 1.0f, 0.0f, 0.5f);

	protected final class ProjectThingNodeRenderer extends DNDTree.NodeRenderer {
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

	// davi-experimenting
	public Bureaucrat mergeManyTask() {
		return Bureaucrat.createAndStart(new Worker.Task("Merge many") {
			public void exec() {
				mergeMany();
			}
		}, this.project);
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
		final Comparator comparator = new Comparator<ProjectThing>() {
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

		// Ask:
		GenericDialog gd = new GenericDialog("Send to sibling project");
		gd.addMessage("Transfering node: " + pt);
		final String[] trmode = new String[]{"As is", "Transformed as the images"};
		gd.addChoice("Transfer:", trmode, trmode[0]);
		String[] ptitles = new String[psother.size()];
		for (int i=0; i<ptitles.length; i++) ptitles[i] = psother.get(i).toString();
		gd.addChoice("Target project:", ptitles, ptitles[0]);
		gd.addChoice("Landing node:", landing, landing[0]);
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

		final String transfer_mode = gd.getNextChoice();
		final Project target_project = psother.get(gd.getNextChoiceIndex());
		final ProjectThing landing_parent = landing_pt.get(gd.getNextChoiceIndex());


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
			if (0 != lids.size()) {
				original_vdata = new ArrayList<Displayable>();
				// Further checking needed (there could just simply be more layers in the target than in the source project):
				// 2 - Expensive way: check the layers in which each Displayable to clone from this project has data.
				//                    All their layers MUST be in the target project.
				for (final ProjectThing child : pt.findChildrenOfTypeR(Displayable.class)) {
					final Displayable d = (Displayable) child.getObject();
					if (!tgt_lids.containsAll(d.getLayerIds())) {
						Utils.log("CANNOT transfer: not all required layers are present in the target project!\n  First object that couldn't be transfered: \n    " + d);
						return false;
					}
					if (d instanceof VectorData) original_vdata.add(d);
				}
			}

			// Deep cloning of the ProjectThing to transfer, then added to the landing_parent in the other tree.
			ProjectThing copy;
			try{
				copy = pt.deepClone(target_project, false); // new ids, taken from target_project
			} catch (Exception ee) {
				Utils.log("Can't send: " + ee.getMessage());
				return false;
			}
			if (null == landing_parent.getChildTemplate(copy.getTemplate().getType())) {
				landing_parent.getTemplate().addChild(copy.getTemplate().shallowCopy()); // ensure a copy is there
			}
			if (!landing_parent.addChild(copy)) {
				Utils.log("Could NOT transfer the node!");
				return false;
			}

			final List<ProjectThing> copies = copy.findChildrenOfTypeR(Displayable.class);
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
					Utils.log("Cannot copy Profile: not implemented yet"); // some day I will make a ProfileList extends ZDisplayable object...
				}
			}
			target_project.getRootLayerSet().addAll(zd); // add them all in one shot

			target_project.getTemplateTree().rebuild(); // could have changed
			target_project.getProjectTree().rebuild(); // When trying to rebuild just the landing_parent, it doesn't always work. Needs checking TODO

			// Now that all have been copied, transform if so asked for:

			if (transfer_mode.equals(trmode[1])) {
				// Collect original vdata
				if (null == original_vdata) {
					original_vdata = new ArrayList<Displayable>();
					for (final ProjectThing child : pt.findChildrenOfTypeR(Displayable.class)) {
						final Displayable d = (Displayable) child.getObject();
						if (d instanceof VectorData) original_vdata.add(d);
					}
				}
				//Utils.log2("original vdata:", original_vdata);
				//Utils.log2("vdata:", vdata);
				// Transform with images
				AlignTask.transformVectorData(AlignTask.createTransformPropertiesTable(original_vdata, vdata), vdata, target_project.getRootLayerSet());
			} // else if trmodep[0], leave as is.
			
			return true;

		} catch (Exception e) {
			IJError.print(e);
		}

		return false;
	}
	
	// begin davi-experimenting block
	/** Detect the following categories of event by comparing other open projects to this one:
	 * 1. creation of: (a) ProjectThings, i.e. ids present in source ProjectTree(s) not present in dest (current project); and (b) DLabels, i.e. oids present in source Displayable(s) not present in dest
	 * 2. deletion: ids or oids (see above) present in dest and not present in source(s)
	 * 3. ProjectThing title changes: same ID in source and dest, name different
	 * 4. Displayable edits: edited_yn = true, overwrite dest displayable with source
	 * In cases of (1) and (3), IDs are preserved. In no case are links transferred or preserved.
	 */
	// Much of this will be derived from sendToSiblingProject, above, and there will be some code in common, but I am not going to touch that method at this time for ease of future merging.
	// If Albert wants to roll this functionality out more broadly, I will need to remove the 'davi-experimenting' tags in source, and maybe at that time, code-in-common can be factored
	// out and put in methods.
	public boolean mergeMany() {
		ArrayList<Project> ps = Project.getProjects();
		FSLoader loader = (FSLoader) project.getLoader(); // incompatible cast should never happen since ProjectThing context menu item is only added if type is FSLoader, and that is the only entry point to this code
		// start off with some sanity checking
		if (ps.size() < 2) {
			Utils.log2("ProjectTree.mergeMany() called when less than two projects are open");
			return false;
		}
		ArrayList<Project> other_ps = new ArrayList<Project>(ps);
		other_ps.remove(this.project);
		for (final Project p : other_ps) {
			if (!(p.getLoader() instanceof FSLoader)) {
				Utils.log("WARNING: mergeMany failed due to incompatible loader type in project '" + ProjectTree.getHumanFacingNameFromProject(p) + "')");
				return false;
			}
			FSLoader other_loader = (FSLoader) p.getLoader();
			if (!loader.userIDRangesAreCompatible(other_loader)) {
				Utils.log("WARNING: mergeMany failed due to incompatible user ID ranges between the projects (failed on project: '" + ProjectTree.getHumanFacingNameFromProject(p) + "')");
				return false; // TODO check Java syntax, does this break from for loop correctly?
			}
		}
		Utils.log2("ProjectTree.mergeMany: all open projects have compatible user ID ranges");
		
		// TODO check compatibility of (1) templates, when createdThings exist; (2) layers; (3) transformations
		
	
		// there is a probably a nice way to abstract all this out, but it is late and I am not particularly clever
		Hashtable<Project, ArrayList<Long>> ps_pt_ids = new Hashtable<Project, ArrayList<Long>>(); // ProjectThing ids for each project
		Hashtable<Project, ArrayList<Long>> ps_dlabel_ids = new Hashtable<Project,ArrayList<Long>>(); // DLabel oids for each project (I *think* these are the only Displayable type I care about that does not have a containing ProjectThing)
		// lists of creation, edit, and deletion events -- so we can see if the same id occurs in multiple projects and/or event types
		IDsInProjects created_pt_ids = new IDsInProjects();
		IDsInProjects created_dlabel_ids = new IDsInProjects();
		IDsInProjects edited_pt_ids = new IDsInProjects();
		IDsInProjects edited_dlabel_ids = new IDsInProjects();
		IDsInProjects deleted_pt_ids = new IDsInProjects();
		IDsInProjects deleted_dlabel_ids = new IDsInProjects();
		
		for (final Project p : ps) {
			ProjectThing pt = p.getRootProjectThing(); // can be null?
			ArrayList<Long> al_pt_ids = new ArrayList<Long>();
			ProjectTree.getChildrenIDsR(pt, al_pt_ids);
			ps_pt_ids.put(p, al_pt_ids);
			
			ArrayList<Long> al_dlabel_ids = new ArrayList<Long>();
			ArrayList<Displayable> al_dlabels = p.getRootLayerSet().getDisplayables(DLabel.class);
			for (final Displayable d : al_dlabels) {
				al_dlabel_ids.add(d.getId());
			}
			ps_dlabel_ids.put(p, al_dlabel_ids);
		}
		
		ArrayList<Long> this_pt_ids = ps_pt_ids.get(this.project);
		ArrayList<Long> this_dlabel_ids = ps_dlabel_ids.get(this.project);
		for (final Project other_p : other_ps) {
			// detect ProjectThing creation and edit events
			ArrayList<Long> other_pt_ids = ps_pt_ids.get(other_p);
			for (final long other_pt_id : other_pt_ids) {
				if (!this_pt_ids.contains(other_pt_id)) {
					// a ProjectThing was created in the other project
					created_pt_ids.addEntry(other_pt_id, other_p);
					Utils.log2("found newly created ProjectThing '" + other_p.findById(other_pt_id).getTitle() + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
				} else {
					// check for title changes
					if (!other_p.findById(other_pt_id).getTitle().equals(this.project.findById(other_pt_id).getTitle()) && other_pt_id != 1) { // WARNING "other_pt_id !=1" is a heinous kludge to avoid detecting 'renaming' of the root ProjectThing, which gets its name from the XML file. In other words, filter out differences in project name.
						edited_pt_ids.addEntry(other_pt_id, other_p);
						Utils.log2("found retitled ProjectThing '" + other_p.findById(other_pt_id).getTitle() + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
					} else {
						// no difference in title, but maybe a difference in the contained Displayable (if any)
						Object other_p_o = ((ProjectThing) other_p.findById(other_pt_id)).getObject();
						if (other_p_o instanceof Displayable) {
							Displayable other_d = (Displayable) other_p_o;
							if (other_d.getEditedYN()) {
								edited_pt_ids.addEntry(other_pt_id, other_p);
								Utils.log2("found edited Displayable in ProjectThing '" + other_p.findById(other_pt_id).getTitle() + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
							}
						}
					}
				}
			}
			
			// detect DLabel creation and edit events
			ArrayList<Long> other_dlabel_ids = ps_dlabel_ids.get(other_p);
			for (final long other_dlabel_id : other_dlabel_ids) {
				if (!this_dlabel_ids.contains(other_dlabel_id)) {
					created_dlabel_ids.addEntry(other_dlabel_id, other_p);
					Utils.log2("found newly created DLabel '" + other_p.getRootLayerSet().findDisplayable(other_dlabel_id).getTitle() + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
				} else {
					// check for editing
					Displayable d = other_p.getRootLayerSet().findDisplayable(other_dlabel_id);
					if (null == d) {
						Utils.log2("WARNING: can't find displayable for DLabel oid='" + Long.toString(other_dlabel_id) + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
					} else if (d.getEditedYN()) {
						edited_dlabel_ids.addEntry(other_dlabel_id, other_p);
						Utils.log2("found edited DLabel '" + other_p.getRootLayerSet().findDisplayable(other_dlabel_id).getTitle() + "' in  project " + ProjectTree.getHumanFacingNameFromProject(other_p));
					}
				}
			}
			
			// detect deletion events
			// TODO bug: if mergeMany, then user deletes the most recently created object they created, then creates a new one, the deletion will not be detected; rather, the type, parent, and content may/may not "change".
			// 			 To deal with this, an 'all time high' attribute in UserIDRange class, DTD, and XML needs to be stored.
			for (final long this_pt_id : this_pt_ids) {
				if (!other_pt_ids.contains(this_pt_id)) {
					// a ProjectThing was deleted in the other project
					deleted_pt_ids.addEntry(this_pt_id, other_p);
					Utils.log2("found ProjectThing '" + this.project.findById(this_pt_id).getTitle() + "' was deleted from project " + ProjectTree.getHumanFacingNameFromProject(other_p));
				}
			}
			for (final long this_dlabel_id : this_dlabel_ids) {
				if (!other_dlabel_ids.contains(this_dlabel_id)) {
					// a ProjectThing was deleted in the other project
					deleted_dlabel_ids.addEntry(this_dlabel_id, other_p);
					Utils.log2("found DLabel '" + this.project.findById(this_dlabel_id).getTitle() + "' was deleted from project " + ProjectTree.getHumanFacingNameFromProject(other_p));
				}
			}
		}
		
		// ---------------------------
		// All the events have been detected. Now for each event, check for conflicts and if all is clear, propagate the change back here
		
		// TODO check for conflicts -- e.g. edited in one, deleted in another
		
		// TODO this code is going to be common between created & deleted events, factor out methods for them both to use?
		// transfer created ProjectThings and their associated Displayables, if any
		ArrayList<Long> transferred_pt_ids = new ArrayList<Long>();
		for (Iterator it = created_pt_ids.ht_ids_in_projects.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long created_pt_id = (Long) entry.getKey();
			ArrayList<Project> al_created_ps = (ArrayList<Project>)entry.getValue(); // all the projects showing this ProjectThing as having been created in
			if (al_created_ps.size() > 1) {
				Utils.log2("WARNING: ProjectThing with_id=" + Long.toString(created_pt_id) + " seems to have been created in multiple projects, skipping it. This may corrupt the overall merge."); // shouldn't happen if UserIDRanges are setup & used properly
				return false;
			}
			if (!transferred_pt_ids.contains(created_pt_id)) {
				Project source_p = al_created_ps.get(0);
				// The idea here is that all of a newly created object's children must also be newly created. So, starting from an arbitrary newly created leaf, find the topmost newly created branch node
				// above it, and add recursively from there. This way the child will always find its parent in the new project. Keep track of who has been added in the transferred_ids ArrayList, so as to avoid
				// modifying the created_pt_ids.ht_ids_in_projects data structure during iteration.
				long topmost_pt_id = ProjectTree.getTopMostParentR(created_pt_id, source_p, created_pt_ids);
				if (!this.transferProjectThingR(topmost_pt_id, source_p, transferred_pt_ids)) return false;
			}
		}	
		// and finally remove all the added entries from created_pt_ids?
		
		// transfer created DLabels
		// this iteration code is redundant with above, ugly to recapitulate it... TODO refactor
		ArrayList<Long> transferred_dlabel_ids = new ArrayList<Long>();
		for (Iterator it = created_dlabel_ids.ht_ids_in_projects.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long created_dlabel_id = (Long) entry.getKey();
			ArrayList<Project> al_created_ps = (ArrayList<Project>)entry.getValue(); // all the projects showing this ProjectThing as having been created in
			if (al_created_ps.size() > 1) {
				Utils.log2("WARNING: DLabel with_id=" + Long.toString(created_dlabel_id) + " seems to have been created in multiple projects, skipping it. This may corrupt the overall merge."); // shouldn't happen if UserIDRanges are setup & used properly
				return false;
			}
			if (!transferred_dlabel_ids.contains(created_dlabel_id)) {
				Project source_p = al_created_ps.get(0);
				DLabel created_dlabel = (DLabel) source_p.getRootLayerSet().findDisplayable(created_dlabel_id);
				if (null == created_dlabel) {
					Utils.log2("WARNING: Can't find DLabel with_id=" + Long.toString(created_dlabel_id) + " in source project '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "'. This may corrupt the overall merge.");
					return false;
				}
				if (!this.transferDisplayable(created_dlabel)) return false;
			}
		}
		
		// propagate ProjectThing edits
		// this iteration code is redundant with above, ugly to recapitulate it... TODO refactor
		ArrayList<Long> propagated_pt_ids = new ArrayList<Long>();
		for (Iterator it = edited_pt_ids.ht_ids_in_projects.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long edited_pt_id = (Long) entry.getKey();
			ArrayList<Project> al_edited_ps = (ArrayList<Project>)entry.getValue(); // all the projects showing this ProjectThing as having been created in
			if (al_edited_ps.size() > 1) {
				Utils.log2("WARNING: ProjectThing with_id=" + Long.toString(edited_pt_id) + " seems to have been edited in multiple projects, skipping it. This may corrupt the overall merge.");
				return false;
			}
			if (!propagated_pt_ids.contains(edited_pt_id)) {
				Project source_p = al_edited_ps.get(0);
				ProjectThing edited_pt = source_p.find(edited_pt_id);
				if (null == edited_pt) {
					Utils.log2("WARNING: can't find edited ProjectThing with_id=" + Long.toString(edited_pt_id) + " and name '" + edited_pt.getTitle() + "' in source project '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "', merge may be corrupted.");
					return false;
				}
				if (this.propagateProjectThingEdit(edited_pt)) {
					propagated_pt_ids.add(edited_pt_id);
				} else return false;
			}
		}	
		// and finally remove all the added entries from edited_pt_ids?
		
		// propagate DLabel edits
		// this iteration code is redundant with above, ugly to recapitulate it... TODO refactor
		ArrayList<Long> propagated_dlabel_ids = new ArrayList<Long>();
		for (Iterator it = edited_dlabel_ids.ht_ids_in_projects.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long edited_dlabel_id = (Long) entry.getKey();
			ArrayList<Project> al_edited_ps = (ArrayList<Project>)entry.getValue(); // all the projects showing this ProjectThing as having been created in
			if (al_edited_ps.size() > 1) {
				Utils.log2("WARNING: DLabel with_id=" + Long.toString(edited_dlabel_id) + " seems to have been edited in multiple projects, skipping it. This may corrupt the overall merge.");
				return false;
			}
			if (!propagated_dlabel_ids.contains(edited_dlabel_id)) {
				Project source_p = al_edited_ps.get(0);
				Displayable edited_dlabel = (Displayable) source_p.getRootLayerSet().findDisplayable(edited_dlabel_id);
				if (null == edited_dlabel) {
					Utils.log2("WARNING: can't find edited DLabel with_id=" + Long.toString(edited_dlabel_id) + " and name '" + edited_dlabel.getTitle() + "' in source project '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "', merge may be corrupted.");
					return false;
				}
				Displayable this_dlabel = (Displayable) this.project.getRootLayerSet().findDisplayable(edited_dlabel_id);
				if (null == this_dlabel) {
					Utils.log2("WARNING: can't find edited DLabel with_id=" + Long.toString(edited_dlabel_id) + " in dest project '" + ProjectTree.getHumanFacingNameFromProject(this.project) + "', merge may be corrupted.");
					return false;
				}
				if (!this_dlabel.remove(false)) { // don't check with user
					// TODO BUG -- if you do a transfer, then call mergeMany again, this remove call will fail. 
					Utils.log2("WARNING: removal of Displayable '" + edited_dlabel.getTitle() + "' with id=" + Long.toString(edited_dlabel.getId()) + " failed, merge may be corrupted.");
					return false; 
				}
				if (!this.transferDisplayable(edited_dlabel)) return false;
			}
		}	
		// and finally remove all the added entries from edited_pt_ids?
		
		// propagate deletions of ProjectThings and their associated Displayables, if any
		// this iteration code is redundant with above, ugly to recapitulate it... TODO refactor
		ArrayList<Long> propagated_deleted_pt_ids = new ArrayList<Long>();
		for (Iterator it = deleted_pt_ids.ht_ids_in_projects.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry entry = (Map.Entry)it.next();
			long deleted_pt_id = (Long) entry.getKey();
			ArrayList<Project> al_created_ps = (ArrayList<Project>)entry.getValue(); // all the projects showing this ProjectThing as having been created in
			if (al_created_ps.size() > 1) {
				Utils.log2("WARNING: ProjectThing with_id=" + Long.toString(deleted_pt_id) + " seems to have been created in multiple projects, skipping it. This may corrupt the overall merge."); // shouldn't happen if UserIDRanges are setup & used properly
				return false;
			}
			if (!propagated_deleted_pt_ids.contains(deleted_pt_id)) {
				Project source_p = al_created_ps.get(0);
								
				ProjectThing target_pt = this.project.find(deleted_pt_id);
				if (null == target_pt) {
					Utils.log2("WARNING: can't find to-be-deleted ProjectThing with_id=" + Long.toString(deleted_pt_id) + "' in source project '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "', merge may be corrupted.");
					return false;
				}
				ArrayList<Long> al_deleted_pt_ids = new ArrayList<Long>();
				ProjectTree.getChildrenIDsR(target_pt, al_deleted_pt_ids);
				this.remove(false, target_pt, null); // is this all I have to do?
			}
		}	
		// and finally remove all the added entries from created_pt_ids?
		
		this.project.getTemplateTree().rebuild(); // could have changed
		this.project.getProjectTree().rebuild(); // When trying to rebuild just the landing_parent, it doesn't always work. Needs checking TODO
		// TODO also need to update all the panes in each Display -- the ZDisplayables, the Labels, etc. -- and the display itself
		
		return true;
	}
	
	// TODO factor out commonalities in error messages and append as suffix
	
	
	private boolean propagateProjectThingEdit(ProjectThing edited_pt) {
		Project source_p = edited_pt.getProject();
		Utils.log2("propagating edits in ProjectThing '" + edited_pt.getTitle() + "' with id=" + edited_pt.getId() + " from '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "' to '" + ProjectTree.getHumanFacingNameFromProject(this.project) + "'");
		ProjectThing this_pt = this.project.find(edited_pt.getId());
		if (null == this_pt) {
			Utils.log2("WARNING: can't find ProjectThing with id=" + Long.toString(edited_pt.getId()) + "', title '" + edited_pt.getTitle() + "', source project '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "', merge may be corrupted."); 
			return false; 
		}
		
		Object edited_pt_ob = edited_pt.getObject();
		if (edited_pt_ob instanceof Displayable) {
			Displayable edited_pt_d = (Displayable) edited_pt_ob;
			if (edited_pt_d.getEditedYN()) {
				Object this_pt_ob = this_pt.getObject();
				if ((this_pt_ob instanceof Displayable)) {
					Displayable this_pt_d = (Displayable) this_pt_ob;
					if (!this_pt_d.remove(false)) { // don't check with user
						// TODO BUG -- if you do a transfer, then call mergeMany again, this remove call will fail. 
						Utils.log2("WARNING: removal of Displayable '" + this_pt_d.getTitle() + "' with id=" + Long.toString(this_pt_d.getId()) + " failed, merge may be corrupted.");
						return false; 
					}
					if (!this.transferDisplayable(edited_pt_d)) return false;
				} else {
					Utils.log2("Oddly, the source edited ProjectThing has a Displayable object, whereas the target ProjectThing does not. The merge may be corrupted");
					return false; 
				}
			}
		}
		// try doing this after adding the Displayable (if any), in order to get the name right --> doesn't work
		this_pt.setTitle(edited_pt.getTitle()); // the only ProjectThing attribute that can be edited that we care about
		// TODO need to set the DefaultMutableNode's object as well, to prevent it from calling Displayable.toString() again and appending another number
		// --> maybe need to findNode(edited_pt, this), then set its userObject to the name String
		return true;
	}
	
	// TODO where in here does the displayable get set as the object in its containing ProjectThing, if any?
	private boolean transferDisplayable(Displayable source_d) {
		Utils.log2("transferring Displayable '" + source_d.getTitle() + "' from '" + ProjectTree.getHumanFacingNameFromProject(source_d.getProject()) + "' to '" + ProjectTree.getHumanFacingNameFromProject(this.project));
		// need to clone the source_pt's displayable, exactly
		boolean copy_id = true;
		Displayable this_pt_d = source_d.clone(this.project, copy_id);
		if (this_pt_d instanceof ZDisplayable) {
			this.project.getRootLayerSet().add((ZDisplayable) this_pt_d);
			this.rebuild();
		} else {
			// this case handles Profile Displayables and DLabels, which live inside Layer objects
			long source_d_layer_id = source_d.getLayer().getId();
			Layer this_dest_layer = this.project.getRootLayerSet().getLayer(source_d_layer_id); // ASSUME layers are the same between the projects
			if (null == this_dest_layer) {
				Utils.log2("WARNING: can't find local destination layer with id=" + Long.toString(source_d_layer_id) + " for source Displayable with id=" + Long.toString(source_d.getId()) + " from source project '" + ProjectTree.getHumanFacingNameFromProject(source_d.getProject()) + "', merge may be corrupted.");
				return false;
			}
			this_dest_layer.add(source_d);
			// Display.repaint(original.getLayer(), displ, 5);
		}
		return true;
	}
	private boolean transferProjectThing(ProjectThing source_pt) { 
		Project source_p = source_pt.getProject();
		Utils.log2("transferring ProjectThing '" + source_pt.getTitle() + "' with id=" + source_pt.getId() + " from '" + ProjectTree.getHumanFacingNameFromProject(source_p) + "' to '" + ProjectTree.getHumanFacingNameFromProject(this.project) + "'");
		ProjectThing source_pt_parent, this_pt_parent;
		source_pt_parent = (ProjectThing) source_pt.getParent();	
		if (null == source_pt_parent) { 
			Utils.log2("WARNING: source_pt '" + source_pt.getTitle() + "' has null parent, merge may be corrupted."); 
			return false; 
		}
		long this_pt_parent_id = source_pt_parent.getId();
		this_pt_parent = this.project.find(this_pt_parent_id); 
		if (null == this_pt_parent) { 
			Utils.log2("WARNING: can't find parent with id=" + Long.toString(this_pt_parent_id) + "' in current project for ProjectThing '" + source_pt.getTitle() + "', merge may be corrupted."); 
			return false; 
		}
		// /motivated by the ProjectThing.sub
		// WARNING the templates between the two projects are assumed to be the same TODO check this before running mergeMany
		String source_pt_tt = source_pt.getTemplate().getType();
		TemplateThing this_pt_template = (TemplateThing) this.project.getTemplateThing(source_pt_tt); 
		if (null == this_pt_template) { 
			Utils.log2("WARNING: can't find template of type '" + source_pt_tt + "' for source ProjectThing with id=" + Long.toString(source_pt.getId()) + " in project '" + source_p.getTitle() + "', merge may be corrupted."); 
			return false; 
		}
		Object source_pt_ob = source_pt.getObject();
		Object this_pt_ob = source_pt_ob; // if it's not a Displayable, it's a title string, and Strings are immutable, so no fear of it getting changed on us.
		if (source_pt_ob instanceof Displayable) {
			Displayable source_pt_d = (Displayable) source_pt_ob;
			this.transferDisplayable(source_pt_d);
		}
		ProjectThing this_pt = new ProjectThing(this_pt_template, this.project, source_pt.getId(), this_pt_ob, new ArrayList(), new HashMap());
		this_pt_parent.addChild(this_pt, null == this_pt_parent.getChildren() ? 0 : this_pt_parent.getChildren().size());
		return true;
	}
	// TODO this code is going to be common between created & deleted events, factor out methods for them both to use?
	private boolean transferProjectThingR(long topmost_pt_id, Project source_p, ArrayList<Long> transferred_pt_ids) {
		if (null == transferred_pt_ids) transferred_pt_ids = new ArrayList<Long>();
		ProjectThing source_pt = source_p.find(topmost_pt_id);
		if (!this.transferProjectThing(source_pt)) {
			return false;
		}
		transferred_pt_ids.add(topmost_pt_id);
		if (null != source_pt.getChildren()) {
			ArrayList<ProjectThing> source_pt_children = source_pt.getChildren();
			for (ProjectThing source_pt_child : source_pt_children) {
				if (!this.transferProjectThingR(source_pt_child.getId(), source_p, transferred_pt_ids)) return false;
			}
		}
		return true;
	}
	
	/** recursively look in the passed-in IDsInProjects structure for parent of passed-in pt_id in Project p */
	private static long getTopMostParentR(long pt_id, Project p, IDsInProjects ids_in_ps) {
		// if pt_id's parent is in the list, call getTopMostFromR on the parent; otherwise, return pt_id
		ProjectThing pt = p.find(pt_id);
		if (null != pt || !ids_in_ps.entryExists(pt_id, p)) { // only the entry into the function actually needs the second check
			Thing t = pt.getParent();
			if (null != t && (t instanceof ProjectThing)) {
				ProjectThing parent_pt = (ProjectThing) t;
				long parent_id = parent_pt.getId();
				if (ids_in_ps.entryExists(parent_id, p)) {
					return ProjectTree.getTopMostParentR(parent_id, p, ids_in_ps);
				}
			}
			return pt_id;
		}
		return -1; // this is an error condition TODO warning
	}
	
	private static String getHumanFacingNameFromProject(Project p) { // a 'real' way to do this, elsewhere?
		return ((ProjectThing) p.getProjectTree().getRootNode().getUserObject()).getTitle();
	}
	/** recursively get all child ProjectThing's ids	 */ // TODO put in ProjectThing?
	private static void getChildrenIDsR(ProjectThing pt, ArrayList<Long> al_ptids) {
		if (null != pt) {
			if (null == al_ptids) al_ptids = new ArrayList<Long>();
			al_ptids.add(pt.getId()); // why not a type mismatch here, getId() returns long, add expects Long? Some implicit type conversion?
			ArrayList<ProjectThing> pt_children = pt.getChildren();
			if (null != pt_children) {
				for (final ProjectThing child_pt : pt_children) {
					getChildrenIDsR(child_pt, al_ptids);
				}
			}
		}		
	}
	// this hash of arrays could be abstracted for general use...
	private static class IDsInProjects {
		Hashtable<Long, ArrayList<Project>> ht_ids_in_projects = new Hashtable<Long, ArrayList<Project>>();
		public boolean addEntry(long id, Project p) {
			ArrayList<Project> al_p = null;
			if (!ht_ids_in_projects.containsKey(id)) {
				al_p = new ArrayList<Project>();
				ht_ids_in_projects.put(id, al_p);
			}
			al_p = ht_ids_in_projects.get(id);
			if (al_p.contains(p)) {
				Utils.log2("WARNING: during call to ProjectTree.IDsInProjects.addEntry, duplicate key='" + Long.toString(id) + "', project='" + (null != p ? p.toString() : "null") + "'");
				return false;
			} else {
				al_p.add(p);
				return true;
			}
		}
		/**side effect: if this would leave the ArrayList empty, also remove the ht_ids_in_projects <id,ArrayList<project> Hashtable entry */
		public boolean removeEntry(long id, Project p) {
			if (!ht_ids_in_projects.containsKey(id)) {
				Utils.log2("WARNING: during call to ProjectTree.IDsInProjects.removeEntry, missing key='" + Long.toString(id) + "', project='" + (null != p ? p.toString() : "null") + "'");
				return false;
			}
			ArrayList<Project> al_p = ht_ids_in_projects.get(id);
			if (!al_p.remove(p)) {
				Utils.log2("WARNING: during call to ProjectTree.IDsInProjects.removeEntry, key'" + Long.toString(id) + "', missing project='" + (null != p ? p.toString() : "null") + "'");
				return false;
			}
			if (al_p.size() == 0) {
				ht_ids_in_projects.remove(id);
			}
			return true;
		}
		public boolean entryExists(long id, Project p) {
			return (ht_ids_in_projects.containsKey(id) && ht_ids_in_projects.get(id).contains(p));
		}
	}
	// end davi-experimenting block
}
/*	// steals liberally from sendToSiblingProject(), but assumes target project has same transformations as source and various other simplifying brittlenesses 
private boolean transferProjectThing(long pt_id, Project source_p) {
	ProjectThing pt = source_p.find(pt_id); 
	if (null == pt) { 
		Utils.log2("WARNING: can't find ProjectThing with id=" + Long.toString(pt_id) + "' in project '" +  ProjectTree.getHumanFacingNameFromProject(source_p) + "'"); 
		return false; 
	}
	// now recursively add Projects from there to here
	ProjectThing new_pt;
	try{
		new_pt = pt.deepClone(this.project, true); // new ids, taken from target_project
	} catch (Exception ee) {
		Utils.log2("WARNING: deepClone() failed for ProjectThing with id=" + Long.toString(pt_id) + "' in project '" +  ProjectTree.getHumanFacingNameFromProject(source_p) + "'. Error:  " + ee.getMessage());
		return false;
	}
	ProjectThing parent_pt = (ProjectThing) pt.getParent();
	if (!parent_pt.addChild(new_pt)) {
		Utils.log2("WARNING: addChild() failed for ProjectThing with id=" + Long.toString(pt_id) + "' in project '" +  ProjectTree.getHumanFacingNameFromProject(source_p) + "'");
		return false;
	}
	
	final List<ProjectThing> copies = new_pt.findChildrenOfTypeR(Displayable.class);
	//Utils.log2("copies size: " + copies.size());
	final List<ZDisplayable> zd = new ArrayList<ZDisplayable>();
	for (final ProjectThing t : copies) {
		final Displayable d = (Displayable) t.getObject();
		if (d instanceof ZDisplayable) {
			zd.add((ZDisplayable)d);
		} else {
			// profile: always special
			Utils.log("Cannot copy Profile: not implemented yet"); // some day I will make a ProfileList extends ZDisplayable object...
			
			// maybe this from ProjectThing.createClonedChild()
			
			// Displayable original = (Displayable)child.object;
			// original.getLayer().add(displ);
			// Display.repaint(original.getLayer(), displ, 5);
			 
		}
	}
	this.project.getRootLayerSet().addAll(zd); // add them all in one shot
	 
	return true;
}
*/