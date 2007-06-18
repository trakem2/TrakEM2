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

import java.awt.Color;
import java.awt.Event;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.tree.*;
import java.util.HashSet;
import java.util.Iterator;
import java.io.File;

/** A class to hold a tree of Thing nodes */
public class ProjectTree extends DNDTree implements MouseListener, ActionListener {

	static {
		System.setProperty("j3d.noOffScreen", "true");
	}

	private DefaultMutableTreeNode selected_node = null;

	public ProjectTree(Project project, ProjectThing project_thing) {
		super(project, DNDTree.makeNode(project_thing), new Color(185,156,255));
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
		JMenuItem[] item = thing.getPopupItems(this);
		if (0 == item.length) return null;
		JPopupMenu popup = new JPopupMenu();
		for (int i=0; i<item.length; i++) {
			popup.add(item[i]);
		}
		return popup;
	}

	public void mousePressed(MouseEvent me) {
		Object source = me.getSource();
		if (!source.equals(this)) {
			return;
		}
		int x = me.getX();
		int y = me.getY();
		// find the node and set it selected
		TreePath path = getPathForLocation(x, y);
		if (null == path) {
			return;
		}
		this.setSelectionPath(path);
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
				Display.setFront(displ.getLayer(), displ);
			}
			return;
		} else if (me.isPopupTrigger() || me.isControlDown() || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK)) { // the last block is from ij.gui.ImageCanvas, aparently to make the right-click work on windows?
			JPopupMenu popup = getPopupMenu(selected_node);
			if (null == popup) return;
			popup.show(this, x, y);
			return;
		}
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
		thing.setTitle(gd.getNextString());
		this.updateUI();
	}


	public void mouseDragged(MouseEvent me) { }
	public void mouseReleased(MouseEvent me) { }
	public void mouseEntered(MouseEvent me) { }
	public void mouseExited(MouseEvent me) { }
	public void mouseClicked(MouseEvent me) { }

	public void actionPerformed(ActionEvent ae) {
		try { 
			if (null == selected_node) return;
			Object ob = selected_node.getUserObject();
			if (!(ob instanceof ProjectThing)) return;
			ProjectThing thing = (ProjectThing)ob;
			int i_position = 0;
			String command = ae.getActionCommand();

			if (command.startsWith("new ") || command.equals("Duplicate")) {
				ProjectThing new_thing = null;
				if (command.startsWith("new ")) {
					new_thing = thing.createChild(command.substring(4)); // if it's a Displayable, it will be added to whatever layer is in the front Display
				} else if (command.equals("Duplicate")) { // just to keep myself fro mscrewing in the future
					if (Project.isBasicType(thing.getType()) && null != thing.getParent()) {
						new_thing = ((ProjectThing)thing.getParent()).createClonedChild(thing);
					}
					// adjust parent
					selected_node = (DefaultMutableTreeNode)selected_node.getParent();
				}
				//add it to the tree
				if (null != new_thing) {
					DefaultMutableTreeNode new_node = new DefaultMutableTreeNode(new_thing);
					((DefaultTreeModel)this.getModel()).insertNodeInto(new_node, selected_node, i_position);
					TreePath treePath = new TreePath(new_node.getPath());
					this.scrollPathToVisible(treePath);
					this.setSelectionPath(treePath);
				}
			} else if (command.equals("Unhide")) {
				thing.setVisible(true);
				Object obd = thing.getObject();
				if (obd instanceof Displayable) {
					// additionality, get the front Display (or make a new one if none) and show in it the layer in which the Displayable object is contained.
					Displayable displ = (Displayable)obd;
					Display.setFront(displ.getLayer(), displ);
				}
			} else if (command.equals("Show centered in Display")) {
				Object obd = thing.getObject();
				if (obd instanceof Displayable) {
					Displayable displ = (Displayable)obd;
					Display.showCentered(displ.getLayer(), displ);
				}
			} else if (command.equals("Show in 3D")) {
				ini.trakem2.display.Display3D.show(thing);
			} else if (command.equals("Hide")) {
				// find all Thing objects in this subtree starting at Thing and hide their Displayable objects.
				thing.setVisible(false);
			} else if (command.equals("Delete...")) {
				Object obd = thing.getObject();
				if (obd instanceof Project) { ((Project)obd).remove(true); return; } // shortcut to remove everything regardless.
				if (!thing.remove(true)) return;
				((DefaultTreeModel)this.getModel()).removeNodeFromParent(selected_node);
				this.updateUI();
			} else if (command.equals("Rename...")) {
				//if (!Project.isBasicType(thing.getType())) {
					rename(thing);
				//}
			} else if (command.equals("Show centered in Display")) {
				if (thing.getObject() instanceof Displayable) {
					Displayable dob = (Displayable)thing.getObject();
					Display.showCentered(dob.getLayer(), dob);
				}
			} else if (command.equals("Measure")) {
				thing.measure();
			} else if (command.equals("Export 3D...")) {
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
			} else if (command.equals("Export project...") || command.equals("Save as...")) { // "Save as..." is for a FS project
				Utils.log2("Calling export project at " + System.currentTimeMillis());
				thing.getProject().getLoader().saveAs(thing.getProject());
			} else if (command.equals("Save")) {
				// overwrite the xml file of a FSProject
				// Just do the same as in "Save as..." but without saving the images and overwritting the XML file without asking.
				thing.getProject().getLoader().save(thing.getProject());
			} else if (command.equals("Info")) {
				String info = thing.getInfo();
				info = info.replaceAll("\t", "    "); // TextWindow can't handle tabs
				new ij.text.TextWindow("Info", info, 500, 500);
			} else {
				Utils.log("ProjectTree.actionPerformed: don't know what to do with the command: " + command);
				return;
			}
		} catch (Exception e) {
			new IJError(e);
		}
	}

	/** Remove the node, its Thing and the object hold by the thing from the database. */
	public void remove(DefaultMutableTreeNode node, boolean check, boolean remove_empty_parents, int levels) {
		Object uob = node.getUserObject();
		if (!(uob instanceof ProjectThing)) return;
		ProjectThing thing = (ProjectThing)uob;
		ProjectThing parent = (ProjectThing)thing.getParent();
		if (!thing.remove(check)) return;
		((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
		if (remove_empty_parents) removeProjectThingLadder(parent, levels);
		this.updateUI();
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
			new IJError(e);
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
}
