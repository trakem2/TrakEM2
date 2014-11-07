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


import ini.trakem2.Project;
import ini.trakem2.utils.Dispatcher;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DnDConstants;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/** A JTree which has a built-in drag and drop feature.
 *
* Adapted from freely available code by DeuDeu from http://forum.java.sun.com/thread.jspa?threadID=296255&start=0&tstart=0
 */
public abstract class DNDTree extends JTree implements TreeExpansionListener, KeyListener {

	Insets autoscrollInsets = new Insets(20, 20, 20, 20); // insets

	DefaultTreeTransferHandler dtth = null;

	protected final Dispatcher dispatcher = new Dispatcher();

	final protected Project project;

	protected final Color background;

	public DNDTree(final Project project, final DefaultMutableTreeNode root, final Color background) {
		this.project = project;
		this.background = background;
		setAutoscrolls(true);
		setModel(new DefaultTreeModel(root));
		setRootVisible(true); 
		setShowsRootHandles(false);//to show the root icon
		getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION); //set single selection for the Tree
		setEditable(true);
		//DNDTree.expandAllNodes(this, root);
		// so weird this instance below does not need to be kept anywhere: where is Java storing it?
		dtth = new DefaultTreeTransferHandler(project, this, DnDConstants.ACTION_COPY_OR_MOVE);
		//
		this.setScrollsOnExpand(true);
		KeyListener[] kls = getKeyListeners();
		if (null != kls) for (KeyListener kl : kls) { Utils.log2("removing kl: " + kl); removeKeyListener(kl); }
		//resetKeyboardActions(); // removing the KeyListeners is not enough!
		//setActionMap(new ActionMap()); // an empty one -- none of these two lines has any effect towards stopping the key events.
		this.addKeyListener(this);

		if (null != background) {
			final DefaultTreeCellRenderer renderer = createNodeRenderer();
			renderer.setBackground(background);
			renderer.setBackgroundNonSelectionColor(background);
			// I hate swing, I really do. And java has no closures, no macros, and reflection is nearly as verbose as the code below!
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				DNDTree.this.setCellRenderer(renderer);
			}});
			SwingUtilities.invokeLater(new Runnable() { public void run() {
				DNDTree.this.setBackground(background);
			}});
		}
	}

	/** Removing all KeyListener and ActionMap is not enough:
	 *  one must override this method to stop the JTree from reacting to keys. */
	@Override
	protected void processKeyEvent(KeyEvent ke) {
		if (ke.isConsumed()) return;
		if (KeyEvent.KEY_PRESSED == ke.getID()) {
			keyPressed(ke);
		}
	}

	/** Prevent processing. */ // Never occurred so far
	@Override
	protected boolean processKeyBinding(KeyStroke ks,
            KeyEvent e,
            int condition,
            boolean pressed) {
		Utils.log2("intercepted binding: " + e.getKeyChar() + " " + ks.getKeyChar());
		return false;
	}

	/** Subclasses should override this method to return a subclass of DNDTree.NodeRenderer */
	protected NodeRenderer createNodeRenderer() {
		return new NodeRenderer();
	}       

	protected class NodeRenderer extends DefaultTreeCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean isSelected, final boolean isExpanded, final boolean isLeaf, final int row, final boolean hasTheFocus) {
			final JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, isSelected, isExpanded, isLeaf, row, hasTheFocus);
			label.setText(label.getText().replace('_', ' ')); // just for display
			return label;
		}       

		/** Override to show tooltip text as well. */
		@Override
		public void setText(final String text) {
			super.setText(text);
			setToolTipText(text); // TODO doesn't work ??
		}       
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

	static public boolean expandNode(final DNDTree tree, final DefaultMutableTreeNode node) {
		final TreeModel tree_model = tree.getModel();
		if (tree_model.isLeaf(node)) return false;
		tree.expandPath(new TreePath(node.getPath()));
		tree.updateUILater();
		return true;
	}

	/** Convenient method.*/
	static public void expandAllNodes(DNDTree tree, DefaultMutableTreeNode root_node) {
		expandAllNodes(tree, new TreePath(root_node.getPath()));
		tree.updateUILater();
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

		//fill in with children
		ArrayList<? extends Thing> al_children = thing.getChildren();
		if (null == al_children) return node; // end
		for (final Thing child : al_children) {
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
	static public DefaultMutableTreeNode findNode(final Object thing_ob, final JTree tree) {
		if (null != thing_ob) {
			// find which node contains the thing_ob
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getModel().getRoot();
			if (node.getUserObject().equals(thing_ob)) return node; // the root itself
			final Enumeration<?> e = node.depthFirstEnumeration();
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
	static public DefaultMutableTreeNode findNode2(final Object project_ob, final JTree tree) {
		if (null != project_ob) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)tree.getModel().getRoot();
			// check if it's the root itself
			Object o = node.getUserObject();
			if (null != o && o instanceof Thing && project_ob.equals(((Thing)o).getObject())) {
				return node; // the root itself
			}
			final Enumeration<?> e = node.depthFirstEnumeration();
			while (e.hasMoreElements()) {
				node = (DefaultMutableTreeNode)e.nextElement();
				o = node.getUserObject();
				if (null != o && o instanceof Thing && project_ob == ((Thing)o).getObject()) {
					//gotcha
					return node;
				}
			}
		}
		return null;
	}

	/** Deselects whatever node is selected in the tree, and tries to select the one that contains the given object. */
	static public void selectNode(final Object ob, final DNDTree tree) {
		if (null == ob) {
			Utils.log2("DNDTree.selectNode: null ob?");
			return;
		}
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// deselect whatever is selected
				tree.setSelectionPath(null);
				final DefaultMutableTreeNode node = DNDTree.findNode(ob, tree);
				if (null != node) {
					final TreePath path = new TreePath(node.getPath());
					try {
						tree.scrollPathToVisible(path); // involves repaint, so must be set through invokeAndWait. Why it doesn't do so automatically is beyond me.
						tree.setSelectionPath(path);
					} catch (Exception e) {
						Utils.log2("Swing, swing, until you hit the building in front.");
					}
				} else {
					// Not found. But also occurs when adding a new profile/pipe/ball, because it is called 'setActive' on before adding it to the project tree.
					//Utils.log("DNDTree.selectNode: not found for ob: " + ob);
				}
		}});
	}

	public void destroy() {
		this.dtth.destroy();
		this.dtth = null;
		this.dispatcher.quit();
	}

	/** Overriding to fix synchronization issues: the path changes while the multithreaded swing attempts to repaint it, so we "invoke later". Hilarious. */
	public void updateUILater() {
		Utils.invokeLater(new Runnable() {
			public void run() {
				//try { Thread.sleep(200); } catch (InterruptedException ie) {}
				try {
					DNDTree.this.updateUI();
				} catch (Exception e) {
					IJError.print(e);
				}
			}
		});
	}

	/** Rebuilds the entire tree, starting at the root Thing object. */
	public void rebuild() {
		rebuild((DefaultMutableTreeNode)this.getModel().getRoot(), false);
		updateUILater();
	}

	/** Rebuilds the entire tree, starting at the given Thing object. */
	public void rebuild(final Thing thing) {
		rebuild(DNDTree.findNode(thing, this), false);
		updateUILater();
	}

	/** Rebuilds the entire tree, from the given node downward. */
	public void rebuild(final DefaultMutableTreeNode node, final boolean repaint) {
		if (null == node) return;
		if (0 != node.getChildCount()) node.removeAllChildren();
		final Thing thing = (Thing)node.getUserObject();
		final ArrayList<? extends Thing> al_children = thing.getChildren();
		if (null == al_children) return;
		for (Iterator<? extends Thing> it = al_children.iterator(); it.hasNext(); ) {
			Thing child = it.next();
			DefaultMutableTreeNode childnode = new DefaultMutableTreeNode(child);
			node.add(childnode);
			rebuild(childnode, false);
		}
		if (repaint) updateUILater();
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
		HashMap<Object,DefaultMutableTreeNode> ht = new HashMap<Object,DefaultMutableTreeNode>();
		for (Enumeration<?> e = node.children(); e.hasMoreElements(); ) {
			DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)e.nextElement();
			ht.put(child_node.getUserObject(), child_node);
		}
		// clear node
		node.removeAllChildren();
		// re-add nodes in the order present in the contained Thing
		for (Iterator<? extends Thing> it = thing.getChildren().iterator(); it.hasNext(); ) {
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
			for (Iterator<?> it = ht.keySet().iterator(); it.hasNext(); ) {
				Utils.log2(it.next().toString());
			}
		}
		this.updateUILater();
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
		setExpandedSilently(node, b);
	}

	static private final java.lang.reflect.Field f_expandedState = DNDTree.getExpandedStateField();

	static private final java.lang.reflect.Field getExpandedStateField() {
		try {
			java.lang.reflect.Field f = JTree.class.getDeclaredField("expandedState");
			f.setAccessible(true);
			return f;
		} catch (Exception e) {
			Utils.log2("ERROR: " + e);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public void setExpandedSilently(final DefaultMutableTreeNode node, final boolean b) {
		try {
			final Hashtable<Object,Boolean> ht = (Hashtable<Object,Boolean>)f_expandedState.get(this);
			ht.put(new TreePath(node.getPath()), b); // this queries directly the expandedState transient private Hashtable of the JTree
		 } catch (Exception e) {
			 Utils.log2("ERROR: " + e); // no IJError, potentially lots of text printed in failed applets
		 }
	}

	/** Get the map of Thing vs. expanded state for all nodes that have children. */
	public HashMap<Thing,Boolean> getExpandedStates() {
		return getExpandedStates(new HashMap<Thing,Boolean>());
	}

	/** Get the map of Thing vs. expanded state for all nodes that have children,
	 * and put the mappins into the {@param m}.
	 * @return {@param m}
	 */
	@SuppressWarnings("unchecked")
	public HashMap<Thing,Boolean> getExpandedStates(final HashMap<Thing,Boolean> m) {
		try {
			final Hashtable<TreePath,Boolean> ht = (Hashtable<TreePath,Boolean>)f_expandedState.get(this);
			for (final Map.Entry<TreePath,Boolean> e : ht.entrySet()) {
				final Thing t = (Thing)((DefaultMutableTreeNode)e.getKey().getLastPathComponent()).getUserObject();
				if (t.hasChildren()) m.put(t, e.getValue());
			}
			return m;
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}

	/** Check if there is a node holding the given Thing, and whether such node is expanded. */
	public boolean isExpanded(final Thing thing) {
		DefaultMutableTreeNode node = findNode(thing, this);
		if (null == node) return false;
		return isExpanded(node);
	}

	@SuppressWarnings("unchecked")
	public boolean isExpanded(final DefaultMutableTreeNode node) {
		try {
			final Hashtable<Object,Boolean> ht = (Hashtable<Object,Boolean>)f_expandedState.get(this);
			return Boolean.TRUE.equals(ht.get(new TreePath(node.getPath()))); // this queries directly the expandedState transient private HashMap of the JTree
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

	public String getInfo() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode)this.getModel().getRoot();
		int n_basic = 0, n_abstract = 0;
		for (Enumeration<?> e = node.depthFirstEnumeration(); e.hasMoreElements(); ) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode)e.nextElement();
			Object ob = child.getUserObject();
			if (ob instanceof Thing && Project.isBasicType(((Thing)ob).getType())) n_basic++;
			else n_abstract++;
		}
		return this.getClass().getName() + ": \n\tAbstract nodes: " + n_abstract + "\n\tBasic nodes: " + n_basic + "\n";
	}
	// TODO: all these non-DND methods should go into an abstract child class that would be super of the trees proper

	public final DefaultMutableTreeNode getRoot() {
		return (DefaultMutableTreeNode)this.getModel().getRoot();
	}

	/** Appends at the end of the parent_node child list, and waits until the tree's UI is updated. */
	protected DefaultMutableTreeNode addChild(final Thing child, final DefaultMutableTreeNode parent_node) {
		try {
			final DefaultMutableTreeNode node_child = new DefaultMutableTreeNode(child);
			((DefaultTreeModel)getModel()).insertNodeInto(node_child, parent_node, parent_node.getChildCount());
			try { DNDTree.this.updateUI(); } catch (Exception e) { IJError.print(e, true); }
			return node_child;
		} catch (Exception e) { IJError.print(e, true); }
		return null;
	}

	/** Will add only those for which a node doesn't exist already. */
	public void addLeafs(final java.util.List<? extends Thing> leafs, final Runnable after) {
		javax.swing.SwingUtilities.invokeLater(new Runnable() { public void run() {
		for (final Thing th : leafs) {
			// find parent node
			final DefaultMutableTreeNode parent = DNDTree.findNode(th.getParent(), DNDTree.this);
			if (null == parent) {
				Utils.log("Ignoring node " + th + " : null parent!");
				continue;
			}
			// see if it exists already as a child of that node
			boolean exists = false;
			if (parent.getChildCount() > 0) {
				final Enumeration<?> e = parent.children();
				while (e.hasMoreElements()) {
					DefaultMutableTreeNode child = (DefaultMutableTreeNode)e.nextElement();
					if (child.getUserObject().equals(th)) {
						exists = true;
						break;
					}
				}
			}
			// otherwise add!
			if (!exists) addChild(th, parent);

			if (null != after) {
				try {
					after.run();
				} catch (Throwable t) {
					IJError.print(t);
				}
			}
		}
		}});
	}

	protected boolean removeNode(DefaultMutableTreeNode node) {
		if (null == node) return false;
		((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
		this.updateUILater();
		return true;
	}

	/** Shallow copy of the tree: returns a clone of the root node and cloned children, recursively, with all Thing cloned as well, but the Thing object is the same. */
	public Thing duplicate(final HashMap<Thing,Boolean> expanded_state) {
		DefaultMutableTreeNode root_node = (DefaultMutableTreeNode) this.getModel().getRoot();
		// Descend both the root_copy tree and the root_node tree, and build shallow copies of Thing with same expanded state
		return duplicate(root_node, expanded_state);
	}

	/** Returns the copy of the node's Thing. */
	private Thing duplicate(final DefaultMutableTreeNode node, final HashMap<Thing,Boolean> expanded_state) {
		Thing thing = (Thing) node.getUserObject();
		Thing copy = thing.shallowCopy();
		if (null != expanded_state) {
			expanded_state.put(copy, isExpanded(node)); 
		}
		final Enumeration<?> e = node.children();
		while (e.hasMoreElements()) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
			copy.addChild(duplicate(child, expanded_state));
		}
		return copy;
	}

	/** Set the root Thing, and the expanded state of all nodes if @param expanded_state is not null.
	 *  Used for restoring purposes from an undo step. */
	public void reset(final HashMap<Thing,Boolean> expanded_state) {
		// rebuild all nodes, restore their expansion state.
		DefaultMutableTreeNode root_node = (DefaultMutableTreeNode) this.getModel().getRoot();
		root_node.removeAllChildren();
		set(root_node, getRootThing(), expanded_state);
		updateUILater();
	}
	
	protected abstract Thing getRootThing();

	/** Recursive */
	protected void set(final DefaultMutableTreeNode root, final Thing root_thing, final HashMap<Thing,Boolean> expanded_state) {
		root.setUserObject(root_thing);
		final ArrayList<? extends Thing> al_children = root_thing.getChildren();
		if (null != al_children) {
			for (final Thing thing : al_children) {
				DefaultMutableTreeNode child = new DefaultMutableTreeNode(thing);
				root.add(child);
				set(child, thing, expanded_state);
			}
		}
		if (null != expanded_state) {
			final Boolean b = expanded_state.get(root_thing);
			if (null != b) setExpandedSilently(root, b.booleanValue());
		}
	}

	public void keyPressed(final KeyEvent ke) {
		if (!ke.getSource().equals(DNDTree.this) || !project.isInputEnabled()) {
			ke.consume();
			return;
		}
		int key_code = ke.getKeyCode();
		switch (key_code) {
			case KeyEvent.VK_S:
				project.getLoader().saveTask(project, "Save");
				ke.consume();
				break;
		}
	}
	public void keyReleased(KeyEvent ke) {}
	public void keyTyped(KeyEvent ke) {}
}
