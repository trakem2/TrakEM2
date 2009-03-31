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

package ini.trakem2.tree;


import ij.gui.GenericDialog;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.*;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Render;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Dispatcher;

import java.awt.Color;
import java.awt.Event;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
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
import java.util.Enumeration;
import java.util.Set;
import java.util.Hashtable;
import java.util.Collections;
import java.io.File;

/** A class to hold a tree of Thing nodes */
public final class ProjectTree extends DNDTree implements MouseListener, ActionListener {

	/*
	static {
		System.setProperty("j3d.noOffScreen", "true");
	}
	*/

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

		popup.add(node_menu);
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
		gd.addStringField("New name: ", old_title);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		String title = gd.getNextString();
		title = title.replace('"', '\'').trim(); // avoid XML problems - could also replace by double '', then replace again by " when reading.
		thing.setTitle(title);
		this.updateUILater();
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
				final ArrayList nc = thing.createChildren(cn[gd.getNextChoiceIndex()], amount, gd.getNextBoolean());
				addLeaves((ArrayList<Thing>)nc);
			} else if (command.equals("Unhide")) {
				thing.setVisible(true);
			} else if (command.equals("Select in display")) {
				boolean shift_down = 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK);
				selectInDisplay(thing, shift_down);
			} else if (command.equals("Identify...")) {
				// for pipes only for now
				if (!(obd instanceof Line3D)) return;
				ini.trakem2.vector.Compare.findSimilar((Line3D)obd);
			} else if (command.equals("Identify with axes...")) {
				if (!(obd instanceof Line3D)) return;
				if (Project.getProjects().size() < 2) {
					Utils.showMessage("You need at least two projects open:\n-A reference project\n-The current project with the pipe to identify");
					return;
				}
				ini.trakem2.vector.Compare.findSimilarWithAxes((Line3D)obd);
			} else if (command.equals("Show centered in Display")) {
				if (obd instanceof Displayable) {
					Displayable displ = (Displayable)obd;
					Display.showCentered(displ.getLayer(), displ, true, 0 != (ae.getModifiers() & ActionEvent.SHIFT_MASK));
				}
			} else if (command.equals("Show in 3D")) {
				ini.trakem2.display.Display3D.show(thing);
			} else if (command.equals("Hide")) {
				// find all Thing objects in this subtree starting at Thing and hide their Displayable objects.
				thing.setVisible(false);
			} else if (command.equals("Delete...")) {
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
			} else if (command.equals("Info")) {
				showInfo(thing);
				return;
			} else if (command.equals("Move up")) {
				move(selected_node, -1);
			} else if (command.equals("Move down")) {
				move(selected_node, 1);
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
	public void insertSegmentations(final Project project, final List al) {
		final TemplateThing tt_root = (TemplateThing)project.getTemplateTree().getRoot().getUserObject();
		// create a new abstract node called "imported_segmentations", if not there
		final String imported_labels = "imported_labels";
		if (!project.typeExists(imported_labels)) {
			// create it
			TemplateThing tet = new TemplateThing(imported_labels, project); // yes I know I should check for the project of each Displayable in the ArrayList
			project.addUniqueType(tet);
			DefaultMutableTreeNode root = project.getTemplateTree().getRoot();
			tt_root.addChild(tet);
			addChild(tet, root);
			DNDTree.expandNode(project.getTemplateTree(), DNDTree.findNode(tet, project.getTemplateTree()));
			// JTree is serious pain
		}
		TemplateThing tt_is = project.getTemplateThing(imported_labels); // it's the same as 'tet' above, unless it existed
		// create a project node from "imported_segmentations" template under a new top node
		DefaultMutableTreeNode project_node = project.getProjectTree().getRoot();
		ProjectThing project_pt = (ProjectThing)project_node.getUserObject();
		ProjectThing ct = project_pt.createChild(tt_root.getType());
		DefaultMutableTreeNode ctn = addChild(ct, project_node);
		ProjectThing pt_is = ct.createChild(imported_labels);
		DefaultMutableTreeNode node_pt_is = addChild(pt_is, ctn);
		try {
			// fails when importing labels from Amira TODO
			this.scrollPathToVisible(new TreePath(node_pt_is.getPath()));
		} catch (Exception e) {
			e.printStackTrace();
		}

		// now, insert a new ProjectThing if of type AreaList, Ball and/or Pipe under node_child
		for (Iterator it = al.iterator(); it.hasNext(); ) {
			Object ob = it.next();
			TemplateThing tt = null;
			if (ob instanceof AreaList) {
				tt = getOrCreateChildTemplateThing(tt_is, "area_list");
			} else if (ob instanceof Pipe) {
				tt = getOrCreateChildTemplateThing(tt_is, "pipe");
			} else if (ob instanceof Polyline) {
				tt = getOrCreateChildTemplateThing(tt_is, "polyline");
			} else if (ob instanceof Ball) {
				tt = getOrCreateChildTemplateThing(tt_is, "ball");
			} else {
				Utils.log("insertSegmentations: ignoring " + ob);
				continue;
			}
			//Utils.log2("tt is " + tt);
			try {
				ProjectThing one = new ProjectThing(tt, project, ob);
				pt_is.addChild(one);
				addChild(one, node_pt_is);
				//Utils.log2("one parent : " + one.getParent());
			} catch (Exception e) {
				IJError.print(e);
			}
		}
		DNDTree.expandNode(this, DNDTree.findNode(pt_is, this));
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
		int key_code = ke.getKeyCode();
		switch (key_code) {
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

	/** If the given node is null, it will be searched for. */
	public boolean remove(boolean check, ProjectThing thing, DefaultMutableTreeNode node) {
		Object obd = thing.getObject();
		if (obd instanceof Project) return ((Project)obd).remove(check); // shortcut to remove everything regardless.
		return thing.remove(true) && removeNode(null != node ? node : findNode(thing, this));
	}

	public void showInfo(ProjectThing thing) {
		if (null == thing) return;
		HashMap<String,ArrayList<ProjectThing>> ht = thing.getByType();
		ArrayList<String> types = new ArrayList<String>();
		types.addAll(ht.keySet());
		Collections.sort(types);
		StringBuffer sb = new StringBuffer(thing.getNodeInfo());
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

}
