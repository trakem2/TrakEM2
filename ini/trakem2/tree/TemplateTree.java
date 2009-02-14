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

import ini.trakem2.Project;
import ini.trakem2.ControlWindow;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Event;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import javax.swing.tree.*;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import java.util.regex.Pattern;


public final class TemplateTree extends DNDTree implements MouseListener, ActionListener {

	private DefaultMutableTreeNode selected_node = null;
	private TemplateThing root;

	public TemplateTree(Project project, TemplateThing root) {
		super(project, DNDTree.makeNode(root, true), new Color(245, 255, 245)); //Color(208, 255, 177));
		this.root = root;
		setEditable(false); // affects the titles only
		addMouseListener(this);
		expandAllNodes(this, (DefaultMutableTreeNode)getModel().getRoot());
	}

	public void mousePressed(MouseEvent me) {
		Object source = me.getSource();
		if (!source.equals(this) || !Project.getInstance(this).isInputEnabled()) {
			return;
		}
		// allow right-click only
		/*if (!(me.isPopupTrigger() || me.isControlDown() || MouseEvent.BUTTON2 == me.getButton() || 0 != (me.getModifiers() & Event.META_MASK))) { // the last block is from ij.gui.ImageCanvas, aparently to make the right-click work on windows?
			return;
		}*/
		if (!Utils.isPopupTrigger(me)) return;
		int x = me.getX();
		int y = me.getY();
		// find the node and set it selected
		TreePath path = getPathForLocation(x, y);
		if (null == path) return;
		setSelectionPath(path);
		this.selected_node = (DefaultMutableTreeNode)path.getLastPathComponent();
		TemplateThing tt = (TemplateThing)selected_node.getUserObject();
		String type = tt.getType();
		//
		JPopupMenu popup = new JPopupMenu();
		JMenuItem item;

		if (!Project.isBasicType(type) && !tt.isNested()) {
			JMenu menu = new JMenu("Add new child");
			popup.add(menu);
			item = new JMenuItem("new..."); item.addActionListener(this); menu.add(item);
			menu.addSeparator();
			String[] ut = tt.getProject().getUniqueTypes();
			for (int i=0; i<ut.length; i++) {
				item = new JMenuItem(ut[i]); item.addActionListener(this); menu.add(item);
			}
		}

		item = new JMenuItem("Delete..."); item.addActionListener(this); popup.add(item);
		if (null == selected_node.getParent()) item.setEnabled(false); //disable deletion of root.

		if (!Project.isBasicType(type)) {
			item = new JMenuItem("Rename..."); item.addActionListener(this); popup.add(item);
		}
		popup.addSeparator();
		item = new JMenuItem("Export XML template..."); item.addActionListener(this); popup.add(item);

		popup.show(this, x, y);
	}

	public void mouseDragged(MouseEvent me) { }
	public void mouseReleased(MouseEvent me) { }
	public void mouseEntered(MouseEvent me) { }
	public void mouseExited(MouseEvent me) { }
	public void mouseClicked(MouseEvent me) { }

	public void actionPerformed(ActionEvent ae) {
		String command = ae.getActionCommand();
		//Utils.log2("command: " + command);
		TemplateThing tt = (TemplateThing)selected_node.getUserObject();

		// Determine whether tt is a nested type
		Thing parent = tt.getParent();

		if (command.equals("Rename...")) {
			final GenericDialog gd = new GenericDialog("Rename");
			gd.addStringField("New type name: ", tt.getType());
			gd.showDialog();
			if (gd.wasCanceled()) return;
			String old_name = tt.getType();
			String new_name = gd.getNextString().replace(' ', '_');
			if (null == new_name || 0 == new_name.length()) {
				Utils.showMessage("Unacceptable new name: '" + new_name + "'");
				return;
			}
			// to lower case!
			new_name = new_name.toLowerCase();
			if (new_name.equals(old_name)) {
				return;
			} else if (tt.getProject().typeExists(new_name)) {
				Utils.showMessage("Type '" + new_name + "' exists already.\nChoose a different name.");
				return;
			}
			// process name change in all TemplateThing instances that have it
			ArrayList al = root.collectAllChildren(new ArrayList());
			al.add(root);
			for (Iterator it = al.iterator(); it.hasNext(); ) {
				TemplateThing tet = (TemplateThing)it.next();
				//Utils.log("\tchecking " + tet.getType() + " " + tet.getId());
				if (tet.getType().equals(old_name)) tet.rename(new_name);
			}
			// and update the ProjectThing objects in the tree and its dependant Displayable objects in the open Displays
			tt.getProject().getRootProjectThing().updateType(new_name, old_name);
			// tell the project about it
			tt.getProject().updateTypeName(old_name, new_name);
			// repaint both trees (will update the type names)
			updateUILater();
			tt.getProject().getProjectTree().updateUILater();
		} else if (command.equals("Delete...")) {
			// find dependent objects, if any, that have the same type of parent chain
			HashSet hs = tt.getProject().getRootProjectThing().collectSimilarThings(tt, new HashSet());
			YesNoCancelDialog yn = ControlWindow.makeYesNoCancelDialog("Remove type?", "Really remove type '" + tt.getType() + "'" + ((null != tt.getChildren() && 0 != tt.getChildren().size()) ? " and its children" : "") + (0 == hs.size() ? "" : " from parent " + tt.getParent().getType() + ",\nand its " + hs.size() + " existing instance" + (1 == hs.size() ? "" : "s") + " in the project tree?"));
			if (!yn.yesPressed()) return;
			// else, proceed to delete:
			Utils.log("Going to delete TemplateThing: " + tt.getType() + "  id: " + tt.getId());
			// first, remove the project things
			DNDTree project_tree = tt.getProject().getProjectTree();
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				ProjectThing pt = (ProjectThing)it.next();
				Utils.log("\tDeleting ProjectThing: " + pt + " " + pt.getId());
				if (!pt.remove(false)) {
					Utils.showMessage("Can't delete ProjectThing " + pt + " " + pt.getId());
				}
				DefaultMutableTreeNode node = DNDTree.findNode(pt, project_tree);
				if (null != node) ((DefaultTreeModel)project_tree.getModel()).removeNodeFromParent(node);
				else Utils.log("Can't find a node for PT " + pt + " " + pt.getId());
			}
			// then, remove the template things that have the same type and parent type as the selected one
			hs = root.collectSimilarThings(tt, new HashSet());
			HashSet hs_same_type = root.collectThingsOfEqualType(tt, new HashSet());
			Utils.log2("hs_same_type.size() = " + hs_same_type.size());
			for (Iterator it = hs.iterator(); it.hasNext(); ) {
				TemplateThing tet = (TemplateThing)it.next();
				if (1 != hs_same_type.size() && tet.equals(tet.getProject().getTemplateThing(tet.getType()))) {
					// don't delete if this is the primary copy, stored in the project unique types (which should be clones, to avoid this problem)
					Utils.log2("avoiding 1");
				} else {
					Utils.log("\tDeleting TemplateThing: " + tet + " " + tet.getId());
					if (!tet.remove(false)) {
						Utils.showMessage("Can't delete TemplateThing" + tet + " " + tet.getId());
					}
				}
				// remove the node in any case
				DefaultMutableTreeNode node = DNDTree.findNode(tet, this);
				if (null != node) ((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
				else Utils.log("Can't find a node for TT " + tet + " " + tet.getId());
			}
			// finally, find out whether there are any TT of the deleted type in the Project unique collection of TT, and delete it. Considers nested problem: if the deleted TT was a nested one, doesn't delete it from the unique types Hashtable. Also should not delete it if there are other instances of the same TT but under different parents.
			if (!tt.isNested() && 1 == hs_same_type.size()) {
				tt.getProject().removeUniqueType(tt.getType());
				Utils.log2("removing unique type");
			} else {
				Utils.log2("avoiding 2");
			}
			// update trees
			this.updateUILater();
			project_tree.updateUILater();
		} else if (command.equals("Export XML template...")) {
			/*
			GenericDialog gd = ControlWindow.makeGenericDialog("Doc Name");
			gd.addMessage("Please provide an XML document type");
			gd.addStringField("DOCTYPE: ", "");
			gd.showDialog();
			if (gd.wasCanceled()) return;
			String doctype = gd.getNextString();
			if (null == doctype || 0 == doctype.length()) {
				Utils.showMessage("Invalid DOCTYPE!");
				return;
			}
			doctype = doctype.replace(' ', '_'); //spaces may not play well in the XML file
			*/
			//StringBuffer sb = new StringBuffer("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<!DOCTYPE ");
			StringBuffer sb = new StringBuffer("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
			//sb.append(doctype).append(" [\n");
			HashSet hs = new HashSet(); // accumulate ELEMENT and ATTLIST
			//StringBuffer sb2 = new StringBuffer();
			//root.exportXMLTemplate(sb, sb2, hs, "");
			//tt.exportXMLTemplate(sb, sb2, hs, ""); // from the selected one (a subtree unless the selected is the root)
			tt.exportDTD(sb, hs, ""); // from the selected one (a subtree unless the selected is the root)
			//String xml = sb.append("] >\n\n").toString() + sb2.toString();
			Utils.saveToFile(tt.getType(), ".dtd", sb.toString()/*xml*/);
		} else {
			TemplateThing tet = null;
			boolean is_new = false;
			String new_child_type = null;

			if (command.equals("new...")) {
				is_new = true;
				// for adding a new child, prevent so in nested types
				// ALREADY done, since menus to add a new type don't show up for nested types
				GenericDialog gd = ControlWindow.makeGenericDialog("New child");
				gd.addStringField("Type name: ", "");
				gd.showDialog();
				if (gd.wasCanceled()) return;
				String new_type = gd.getNextString().toLowerCase(); // TODO WARNING toLowerCase enforced, need to check the TMLHandler
				if (tt.getProject().typeExists(new_type.toLowerCase())) {
					Utils.showMessage("Type '" + new_type + "' exists already.\nSelect it from the contextual menu list\nor choose a different name.");
					return;
				} else if (tt.getProject().isBasicType(new_type.toLowerCase())) {
					Utils.showMessage("Type '" + new_type + "' is reserved.\nPlease choose a different name.");
					return;
				}
				
				// replace spaces before testing for non-alphanumeric chars
				new_type = new_type.replace(' ', '_'); // spaces don't play well in an XML file.

				String pattern = "^.*[^a-zA-Z0-9_-].*$";
				final Pattern pat = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
				if (pat.matcher(new_type).matches()) {
					Utils.showMessage("Only alphanumeric characters, underscore, hyphen and space are accepted.");
					return;
				}

				//tet = new TemplateThing(new_type, tt.getProject());
				//tt.getProject().addUniqueType(tet);
				new_child_type = new_type;
			} else {
				// create from a listed type
				tet = tt.getProject().getTemplateThing(command);
				if (tt.canHaveAsChild(tet)) {
					Utils.log("'" + tt.getType() + "' already contains a child of type '" + command + "'");
					return;
				} else if (null == tet) {
					Utils.log("TemplateTree internal error: no type exists for '" + command + "'");
					return;
				}
				// else add as new
				new_child_type = command; //tet = new TemplateThing(command, tt.getProject());
				// the 'profile_list' type needs an automatic 'profile' type inside
			}

			// add the new type to the database and to the tree, to all instances that are similar to tt (but not nested)
			addNewChildType(tt, new_child_type);
		}
	}

	public void destroy() {
		super.destroy();
		this.root = null;
		this.selected_node = null;
	}

	/** Recursively create TemplateThing copies and new nodes to fill in the whole subtree of the given parent; nested types will be prevented from being filled.*/
	private void fillChildren(final TemplateThing parent, final DefaultMutableTreeNode parent_node) {
		TemplateThing parent_full = parent.getProject().getTemplateThing(parent.getType());
		if (parent.isNested()) {
			//Utils.log2("avoiding nested infinite recursion problem");
			return;
		}
		final ArrayList al_children = parent_full.getChildren();
		if (null == al_children) {
			//Utils.log2("no children for " + parent_full);
			return;
		}
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			TemplateThing child = (TemplateThing)it.next();
			TemplateThing copy = new TemplateThing(child.getType(), parent.getProject());
			parent.addChild(copy);
			DefaultMutableTreeNode copy_node = new DefaultMutableTreeNode(copy);
			((DefaultTreeModel)this.getModel()).insertNodeInto(copy_node, parent_node, parent_node.getChildCount());
			fillChildren(copy, copy_node);
		}
	}

	/** tt_parent is the parent TemplateThing
	 *  tet_child is the child to add to tt parent, and to insert as child to all nodes that host the tt parent.
	 *
	 *  Returns the TemplateThing used, either new or a reused, unique, already-existing one. */
	public TemplateThing addNewChildType(final TemplateThing tt_parent, String new_child_type) {
		// check preconditions
		if (null == tt_parent || null == new_child_type) return null;
		// fix any potentially dangerous chars for the XML
		new_child_type = new_child_type.trim().toLowerCase().replace(' ', '_').replace('-', '_').replace('\n','_').replace('\t','_'); // XML valid
		// See if such TemplateThing exists already
		TemplateThing tet_child = tt_parent.getProject().getTemplateThing(new_child_type);
		boolean is_new = null == tet_child;
		// In any case we need a copy to add as a node to the trees
		tet_child = new TemplateThing(null == tet_child ? new_child_type : tet_child.getType(), tt_parent.getProject()); // reusing same String
		if (is_new) {
			tt_parent.getProject().addUniqueType(tet_child);
			tt_parent.addChild(tet_child);
		}

		// add the new type to the database and to the tree, to all instances that are similar to tt (but not nested)
		HashSet hs = root.collectSimilarThings2(tt_parent, new HashSet());
		TemplateThing tti, ttc;
		for (Iterator it = hs.iterator(); it.hasNext(); ) {
			tti = (TemplateThing)it.next();
			if (tti.equals(tt_parent)) {
				tti = tt_parent; // parent
				ttc = tet_child; // child
			} else {
				ttc = new TemplateThing(tet_child.getType(), tt_parent.getProject());
			}
			tti.addChild(ttc);
			ttc.addToDatabase();
			// find the parent
			DefaultMutableTreeNode node_parent = DNDTree.findNode(tti, this);
			DefaultMutableTreeNode node_child = new DefaultMutableTreeNode(ttc);
			// see first if there isn't already one such child
			boolean add = true;
			for (final Enumeration e = node_parent.children(); e.hasMoreElements(); ) {
				DefaultMutableTreeNode nc = (DefaultMutableTreeNode) e.nextElement();
				TemplateThing ttnc = (TemplateThing) nc.getUserObject();
				if (ttnc.getType().equals(ttc.getType())) {
					add = false;
					break;
				}
			}
			if (add) {
				((DefaultTreeModel)this.getModel()).insertNodeInto(node_child, node_parent, node_parent.getChildCount());
			}

			Utils.log2("ttc parent: " + ttc.getParent());
			Utils.log2("tti is parent: " + (tti == ttc.getParent()));

			// generalize the code below to add all children of an exisiting type when adding it as a leaf somewhere else than it's first location
			// 1 - find if the new 'tet' is of a type that existed already
			if (!is_new) {
				// 2 - add new TemplateThing nodes to fill in the whole subtree, preventing nested expansion
				//Utils.log2("Calling fillChildren for " + tet);
				fillChildren(tet_child, node_child); // recursive
				DNDTree.expandAllNodes(this, node_child);
			} else {
				//Utils.log2("NOT Calling fillChildren for " + tet);
			}
		}
		this.updateUILater();
		return tet_child;
	}
}
