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
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.utils.Utils;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.tree.ProjectTree;

import javax.swing.JTree;
import java.util.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import java.awt.dnd.*;

/** A JTree which has a built-in drag and drop feature.
 *
* Adapted from freely available code by DeuDeu from http://forum.java.sun.com/thread.jspa?threadID=296255&start=0&tstart=0
 */
public class DNDTree extends JTree implements TreeExpansionListener {
 
	Insets autoscrollInsets = new Insets(20, 20, 20, 20); // insets

	DefaultTreeTransferHandler dtth = null;

	public DNDTree(Project project, DefaultMutableTreeNode root, Color background) {
		this(project, root);
		this.setScrollsOnExpand(true);
		if (null != background) {
			setBackground(background);
			DefaultTreeCellRenderer renderer = new NodeRenderer(background); // new DefaultTreeCellRenderer();
			renderer.setBackground(background);
			renderer.setBackgroundNonSelectionColor(background);
			setCellRenderer(renderer);
		}
	}


	/** Extends the DefaultTreeCellRenderer to paint the nodes that contain Thing objects present in the current layer with a distinctive orange background; also, attribute nodes are painted with a different icon. */
	private class NodeRenderer extends DefaultTreeCellRenderer {

		// this is a crude hack that needs much cleanup and proper break down to subclasses.

		Color bg;

		NodeRenderer(Color bg) {
			this.bg = bg;
		}

		/** Override to set a yellow background color to elements that belong to the currently displayed layer. The JTree nodes are painted as a JLabel that is transparent (no background, it has a setOpque(true) by default), so the code is insane.*/
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
			JLabel label = (JLabel)super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
			label.setText(label.getText().replace('_', ' ')); // just for display
			if (value instanceof DefaultMutableTreeNode) {
				Object obb = ((DefaultMutableTreeNode)value).getUserObject();
				if (obb instanceof ProjectThing) { // must be, but checking ...
					Object ob = ((ProjectThing)obb).getObject();
					if (ob instanceof Displayable) {
						Displayable displ = (Displayable)ob;
						Layer layer = Display.getFrontLayer();
						if (null != layer && (layer.contains(displ) || displ.equals(Display.getFront().getActive()))) {
							label.setOpaque(true); //this label
							label.setBackground(new Color(1.0f, 1.0f, 0.0f, 0.5f)); // this label
							//Utils.log(" -- setting background");
						} else {
							label.setOpaque(false); //this label
							label.setBackground(bg);
							//Utils.log(" not contained ");
						}
					} else {
						label.setOpaque(false); //this label
						label.setBackground(bg);
						//Utils.log("ob is " + ob);
					}
				} else if (obb instanceof LayerThing) {
					Object ob = ((LayerThing)obb).getObject();
					Layer layer = Display.getFrontLayer();
					if (ob.equals(layer)) {
						label.setOpaque(true); //this label
						label.setBackground(new Color(1.0f, 1.0f, 0.4f, 0.5f)); // this label
					} else if (ob instanceof LayerSet && null != layer && layer.contains((Displayable)ob)) {
						label.setOpaque(true); //this label
						label.setBackground(new Color(1.0f, 1.0f, 0.0f, 0.5f)); // this label
					} else {
						label.setOpaque(false); //this label
						label.setBackground(bg);
					}
				} else {
					label.setOpaque(false); //this label
					label.setBackground(bg);
					//Utils.log("obb is " + obb);
				}
			} else {
				label.setOpaque(false);
				label.setBackground(bg);
				//Utils.log("value is " + value);
			}
			return label;
		}

		/** Override to show tooptip text as well. */
		public void setText(String text) {
			super.setText(text);
			setToolTipText(text); // TODO doesn't work ??
		}
	}

	public DNDTree(Project project, DefaultMutableTreeNode root) {
		setAutoscrolls(true);
		DefaultTreeModel treemodel = new DefaultTreeModel(root);
		setModel(treemodel);
		setRootVisible(true); 
		setShowsRootHandles(false);//to show the root icon
		getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION); //set single selection for the Tree
		setEditable(true);
		//DNDTree.expandAllNodes(this, root);
		// so weird this instance below does not need to be kept anywhere: where is Java storing it?
		dtth = new DefaultTreeTransferHandler(project, this, DnDConstants.ACTION_COPY_OR_MOVE);
	}
 
	public void autoscroll(Point cursorLocation)  {
		Insets insets = getAutoscrollInsets();
		Rectangle outer = getVisibleRect();
		Rectangle inner = new Rectangle(outer.x+insets.left, outer.y+insets.top, outer.width-(insets.left+insets.right), outer.height-(insets.top+insets.bottom));
		if (!inner.contains(cursorLocation))  {
			Rectangle scrollRect = new Rectangle(cursorLocation.x-insets.left, cursorLocation.y-insets.top, insets.left+insets.right, insets.top+insets.bottom);
			scrollRectToVisible(scrollRect);
		}
	}
 
	public Insets getAutoscrollInsets()  {
		return autoscrollInsets;
	}
 
	/*
	public static DefaultMutableTreeNode makeDeepCopy(DefaultMutableTreeNode node) {
		DefaultMutableTreeNode copy = new DefaultMutableTreeNode(node.getUserObject());
		for (Enumeration e = node.children(); e.hasMoreElements();) {   
			copy.add(makeDeepCopy((DefaultMutableTreeNode)e.nextElement()));
		}
		return copy;
	}


	/** Expand all nodes recursively starting at the given node. It checks whether the node is a non-leaf first. Adapted quite literally from: http://www.koders.com/java/fid4BF9016D39E1A9EEDBB7875D3D2E1FE4DA9F726D.aspx a GPL TreeTool.java by Dirk Moebius (or so it says). */
	static public void expandAllNodes(final JTree tree, final TreePath path) {
		final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
		final TreeModel tree_model = tree.getModel();
		if (tree_model.isLeaf(node)) return;
		tree.expandPath(path);
		final int n_children = tree_model.getChildCount(node);
		for (int i=0; i<n_children; i++) {
			expandAllNodes(tree, path.pathByAddingChild(tree_model.getChild(node, i)));
		}
	}

	static public boolean expandNode(final JTree tree, final DefaultMutableTreeNode node) {
		final TreeModel tree_model = tree.getModel();
		if (tree_model.isLeaf(node)) return false;
		tree.expandPath(new TreePath(node.getPath()));
		return true;
	}

	/** Convenient method.*/
	static public void expandAllNodes(JTree tree, DefaultMutableTreeNode root_node) {
		expandAllNodes(tree, new TreePath(root_node.getPath()));
	}

	static public DefaultMutableTreeNode makeNode(Thing thing) {
		return makeNode(thing, false);
	}
	/** Returns a DefaultMutableTreeNode with all its children. If this is called on a root node, it will fill in the whole tree. The Attribute nodes are only added it their value is non-null, and at the top of the list. */ //This method is designed as functional programming.
	static public DefaultMutableTreeNode makeNode(Thing thing, boolean childless_nested) {
		//make a new node
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(thing);
		// add attributes and children only if nested are allowed (for ProjectThing)

		if (childless_nested) {
			// check if the given thing has a parent (or parent of parent, etc.) of the same type as itself (in which case the node will be returned as is, and thus added childless and attributeless)
			Thing parent = thing.getParent();
			String type = thing.getType();
			while (null != parent) {
				if (type.equals(parent.getType())) {
					// finish!
					return node;
				}
				parent = parent.getParent();
			}
		}

		// add the attributes first, only for ProjectThing or TemplateThing instances (not LayerThing)
		if (thing instanceof ProjectThing || thing instanceof TemplateThing) {
			Hashtable hs_attributes = thing.getAttributes();
			if (null != hs_attributes) {
				Enumeration keys = hs_attributes.keys();
				while (keys.hasMoreElements()) {
					String key = (String)keys.nextElement();
					if (key.equals("id") || key.equals("title") || key.equals("index") || key.equals("expanded")) {
						// ignore: the id is internal, and the title is shown in the node itself. The index is ignored.
						continue;
					}
					DefaultMutableTreeNode attr_node = new DefaultMutableTreeNode(hs_attributes.get(key));
					node.add(attr_node);
				}
			}
		}
		//fill in with children
		ArrayList al_children = thing.getChildren();
		if (null == al_children) return node; // end
		Iterator it = al_children.iterator();
		while (it.hasNext()) {
			Thing child = (Thing)it.next();
			node.add(makeNode(child, childless_nested)); // recursive call
		}
		return node;
	}

	/** Find the node in the tree that contains the Thing of the given id.*/
	/* // EQUALS Project.find(long id)
	static public DefaultMutableTreeNode findNode(final long thing_id, final JTree tree) {
		// find which node contains the thing_ob
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getModel().getRoot();
		if (((DBObject)node.getUserObject()).getId() == thing_id) return node; // the root itself
		Enumeration e = node.depthFirstEnumeration();
		while (e.hasMoreElements()) {
			node = (DefaultMutableTreeNode)e.nextElement();
			if (((DBObject)node.getUserObject()).getId() == thing_id) {
				//gotcha
				return node;
			}
		}
		return null;
	}
	*/

	/** Find the node in the tree that contains the given Thing.*/
	static public DefaultMutableTreeNode findNode(Object thing_ob, JTree tree) {
		if (null != thing_ob) {
			// find which node contains the thing_ob
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getModel().getRoot();
			if (node.getUserObject().equals(thing_ob)) return node; // the root itself
			Enumeration e = node.depthFirstEnumeration();
			while (e.hasMoreElements()) {
				node = (DefaultMutableTreeNode)e.nextElement();
				if (node.getUserObject().equals(thing_ob)) {
					//gotcha
					return node;
				}
			}
		}
		return null;
	}

	/** Find the node in the tree that contains a Thing which contains the given project_ob. */
	static public DefaultMutableTreeNode findNode2(Object project_ob, JTree tree) {
		if (null != project_ob) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getModel().getRoot();
			// check if it's the root itself
			Object o = node.getUserObject();
			if (null != o && o instanceof Thing && project_ob.equals(((Thing)o).getObject())) {
				return node; // the root itself
			}
			Enumeration e = node.depthFirstEnumeration();
			while (e.hasMoreElements()) {
				node = (DefaultMutableTreeNode)e.nextElement();
				o = node.getUserObject();
				if (null != o && o instanceof Thing && project_ob.equals(((Thing)o).getObject())) {
					//gotcha
					return node;
				}
			}
		}
		return null;
	}

	/** Deselects whatever node is selected in the tree, and tries to select the one that contains the given object. */
	static public void selectNode(Object ob, JTree tree) {
		if (null == ob) {
			Utils.log("DNDTree.selectNode: null ob?");
			return;
		}
		// deselect whatever is selected
		tree.setSelectionPath(null);
		// check first:
		if (null == ob) return;
		DefaultMutableTreeNode node = DNDTree.findNode(ob, tree);
		if (null != node) {
			TreePath path = new TreePath(node.getPath());
			try {
			tree.scrollPathToVisible(path);
			} catch (Exception e) {
				Utils.log2("Error in DNDTree.selectNode tree.scrollPathToVisible(path). Java is buggy, see for yourself: " + e);
			}
			tree.setSelectionPath(path);
		} else {
			// Not found. But also occurs when adding a new profile/pipe/ball, because it is called 'setActive' on before adding it to the project tree.
			//Utils.log("DNDTree.selectNode: not found for ob: " + ob);
		}
	}

	public void destroy() {
		this.dtth.destroy();
		this.dtth = null;
	}

	private void superUpdateUI() {
		super.updateUI();
	}

	/** Overriding to fix synchronization issues: the path changes while the multithreaded swing attempts to repaint it, so we wait. */
	public void updateUI() {
		/*
		java.lang.reflect.Method m1 = null;
		try {
			m1 = super.getClass().getMethod("updateUI"); // exists in JComponent, from which JTree inherits
		} catch (NoSuchMethodException nsme) {}
		final java.lang.reflect.Method method = m1; // loops and loops ..
		*/

		final DNDTree tree = this;
		final Runnable updater = new Runnable() {
			public void run() {
				try { Thread.sleep(200); } catch (InterruptedException ie) {}
				try {
					tree.superUpdateUI();
				} catch (Exception e) {
					//Utils.log2("updateUI failed at " + Utils.faultyLine(e));
					new ini.trakem2.utils.IJError(e);
				}
			}
		};
		//javax.swing.SwingUtilities.invokeLater(updater); // generates random lock ups at start up
		new Thread() {
			public void run() {
				// avoiding "can't call invokeAndWait from the EventDispatch thread" error
				try {
					javax.swing.SwingUtilities.invokeAndWait(updater);
				} catch (Exception e) {
					Utils.log2("ERROR: " + e);
				}
			}
		}.start();


		/*
		// just catch it
		try {
			super.updateUI();
		} catch (Exception e) {
			Utils.log2("updateUI failed for " + this.getClass().getName());
			// try again once:
			try {
				Thread.sleep(100);
				super.updateUI();
			} catch (Exception e2) {
				Utils.log2("updateUI failed a second time.");
			}
		}
		*/
	}

	/** Rebuilds the part of the tree under the given node, one level deep only, for reordering purposes. */
	public void updateList(Thing thing) {
		updateList(DNDTree.findNode(thing, this));
	}

	/** Rebuilds the part of the tree under the given node, one level deep only, for reordering purposes. */
	public void updateList(DefaultMutableTreeNode node) {
		if (null == node) return;
		Thing thing = (Thing)node.getUserObject();
		// store scrolling position for restoring purposes
		Component c = this.getParent();
		Point point = null;
		if (c instanceof JScrollPane) {
			point = ((JScrollPane)c).getViewport().getViewPosition();
		}
		// collect all current nodes
		Hashtable ht = new Hashtable();
		for (Enumeration e = node.children(); e.hasMoreElements(); ) {
			DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)e.nextElement();
			ht.put(child_node.getUserObject(), child_node);
		}
		// clear node
		node.removeAllChildren();
		// re-add nodes in the order present in the contained Thing
		for (Iterator it = thing.getChildren().iterator(); it.hasNext(); ) {
			Object ob_thing = it.next();
			Object ob = ht.remove(ob_thing);
			if (null == ob) {
				Utils.log2("Adding missing node for " + ob_thing);
				node.add(new DefaultMutableTreeNode(ob_thing));
				continue;
			}
			node.add((DefaultMutableTreeNode)ob);
		}
		// consistency check: that all nodes have been re-added
		if (0 != ht.size()) {
			Utils.log2("WARNING DNDTree.updateList: did not end up adding this nodes:");
			for (Iterator it = ht.keySet().iterator(); it.hasNext(); ) {
				Utils.log2(it.next().toString());
			}
		}
		this.updateUI();
		// restore viewport position
		if (null != point) {
			((JScrollPane)c).getViewport().setViewPosition(point);
		}
	}

	public DefaultMutableTreeNode getRootNode() {
		return (DefaultMutableTreeNode)this.getModel().getRoot();
	}

	/** Does not incur in firing a TreeExpansion event, and affects the node only, not any of its parents. */
	public void setExpandedSilently(final Thing thing, final boolean b) {
		DefaultMutableTreeNode node = findNode(thing, this);
		if (null == node) return;
		try {
			java.lang.reflect.Field f = JTree.class.getDeclaredField("expandedState");
			f.setAccessible(true);
			Hashtable ht = (Hashtable)f.get(this);
			ht.put(new TreePath(node.getPath()), new Boolean(b)); // this queries directly the expandedState transient private Hashtable of the JTree
		 } catch (Exception e) {
			 Utils.log2("ERROR: " + e); // no IJError, potentially lots of text printed in failed applets
		 }
	}

	/** Check if there is a node holding the given Thing, and whether such node is expanded. */
	public boolean isExpanded(final Thing thing) {
		DefaultMutableTreeNode node = findNode(thing, this);
		if (null == node) return false;
		try {
			java.lang.reflect.Field f = JTree.class.getDeclaredField("expandedState");
			f.setAccessible(true);
			Hashtable ht = (Hashtable)f.get(this);
			return Boolean.TRUE.equals(ht.get(new TreePath(node.getPath()))); // this queries directly the expandedState transient private Hashtable of the JTree
		 } catch (Exception e) {
			 Utils.log2("ERROR: " + e); // no IJError, potentially lots of text printed in failed applets
			 return false;
		 }

		/* // Java's idiotic API confuses isExpanded with isVisible, making them equal! The JTree.isVisible() check should not be done within the JTree.isExpanded() method!
		DefaultMutableTreeNode node = findNode(thing, this);
		Utils.log2("node is " + node);
		if (null == node) return false;
		TreePath path = new TreePath(node.getPath());
		//return this.isExpanded(new TreePath(node.getPath())); // the API is ludicrous: isExpanded, isCollapsed and isVisible return a value relative to whether the node is visible, not its specific expanded state.
		Utils.log("contains: " + hs_expanded_paths.contains(path));
		//return hs_expanded_paths.contains(node.getPath());
		for (Iterator it = hs_expanded_paths.iterator(); it.hasNext(); ) {
			if (path.equals(it.next())) return true;
		}
		Utils.log2("thing not expanded: " + thing);
		return false;
		*/
	}

	//private HashSet hs_expanded_paths = new HashSet();

	/** Sense node expansion events (the method name is missleading). */
	public void treeCollapsed(TreeExpansionEvent tee) {
		TreePath path = tee.getPath();
		//hs_expanded_paths.remove(path);
		updateInDatabase(path);
		//Utils.log2("collapsed " + path);
	}
	public void treeExpanded(TreeExpansionEvent tee) {
		TreePath path = tee.getPath();
		//hs_expanded_paths.add(path);
		updateInDatabase(path);
		//Utils.log2("expanded " + path);
	}
	private void updateInDatabase(final TreePath path) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
		ProjectThing thing = (ProjectThing)node.getUserObject(); // the Thing
		thing.updateInDatabase(new StringBuffer("expanded='").append(isExpanded(thing)).append('\'').toString());
	}

	//abstract public void exportXML(final StringBuffer sb_body, String indent);

	public String getInfo() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)this.getModel().getRoot();
		int n_basic = 0, n_abstract = 0;
		for (Enumeration e = node.depthFirstEnumeration(); e.hasMoreElements(); ) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode)e.nextElement();
			Thing thing = (Thing)child.getUserObject();
			if (Project.isBasicType(thing.getType())) n_basic++;
			else n_abstract++;
		}
		return this.getClass().getName() + ": \n\tAbstract nodes: " + n_abstract + "\n\tBasic nodes: " + n_basic + "\n";
	}
	// TODO: all these non-DND methods should go into an abstract child class that would be super of the trees proper

	public final DefaultMutableTreeNode getRoot() {
		return (DefaultMutableTreeNode)this.getModel().getRoot();
	}

	/** Appends at the end of the parent_node child list. */
	protected DefaultMutableTreeNode addChild(Thing child, DefaultMutableTreeNode parent_node) {
		DefaultMutableTreeNode node_child = new DefaultMutableTreeNode(child);
		((DefaultTreeModel)getModel()).insertNodeInto(node_child, parent_node, parent_node.getChildCount());
		updateUI();
		return node_child;
	}
}
