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
import ini.trakem2.display.Display;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.util.ArrayList;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
 
/** Adapted from freely available code by DeuDeu from http://forum.java.sun.com/thread.jspa?threadID=296255&start=0&tstart=0 */
public class DefaultTreeTransferHandler extends AbstractTreeTransferHandler {

	Project project;
 
	public DefaultTreeTransferHandler(Project project, DNDTree tree, int action) {
		super(tree, action, true);
		this.project = project;
	}
 
	public boolean canPerformAction(DNDTree target, DefaultMutableTreeNode dragged_node, int action, Point location) {

		/* //debug:
		Utils.log2(DnDConstants.ACTION_COPY + " " + DnDConstants.ACTION_COPY_OR_MOVE + " " + DnDConstants.ACTION_LINK + " " + DnDConstants.ACTION_MOVE + " " + DnDConstants.ACTION_NONE + " " + DnDConstants.ACTION_REFERENCE);
		Utils.log2("action: " + action);
		*/



		// prevent drags from non-tree components
		if (null == dragged_node) return false;
		// Can't drop onto a TemplateTree
		if (target instanceof TemplateTree) {
			return false;
		}
		// Can't drag a node that contains a Project!
		if (dragged_node.getUserObject() instanceof ProjectThing && ((ProjectThing)dragged_node.getUserObject()).getObject() instanceof Project) {
			return false;
		}
		// Can't drag basic object nodes from a template tree RECONSIDERED, I like it even if it looks inconsistent (but types are types!)
		/*
		if (dragged_node.getUserObject() instanceof TemplateThing && project.isBasicType(((Thing)dragged_node.getUserObject()).getType())) {
			return false;
		}
		*/

		// else, the target has to be not null
		TreePath pathTarget = target.getPathForLocation(location.x, location.y);
		if (pathTarget == null) {
			target.setSelectionPath(null);
			return false;
		}

		/* // debug
		if (action == DnDConstants.ACTION_COPY) {
			Utils.log("can drop: Action copy");
		} else if (action == DnDConstants.ACTION_MOVE) {
			Utils.log("can drop: Action move");
		} else {
			Utils.log("can drop: Unexpected action: " + action);
		}
		*/

		target.setSelectionPath(pathTarget);
		DefaultMutableTreeNode parent_node = (DefaultMutableTreeNode)pathTarget.getLastPathComponent();
		Object parent_ob = parent_node.getUserObject(); // can be a Thing or an Attribute
		Thing child_thing = (Thing)dragged_node.getUserObject();

		if (DnDConstants.ACTION_MOVE == action || DnDConstants.ACTION_COPY == action) {
			if (parent_ob instanceof ProjectThing) {
				ProjectThing parent_thing = (ProjectThing)parent_ob;

				// TODO: check if the parent/parent/parent/.../parent of the dragged thing can have such parent as child, and autocreate them (but not for basic things though, or only if there is no confusion possible)

				// check if it's allowed to give to this parent such a child:
				if (!parent_thing.uniquePathExists(child_thing.getType()) && !parent_thing.canHaveAsChild(child_thing)) {
					//Utils.log("Not possible.");
					return false;
				}
				// enable to drop:
				// - any of the leafs in the template, including the root, to the project tree
				// disable to drop:
				// - the root leaf of the project tree
				// - the leaf that is going to be dropped into itself or any of its descendants.
				if (parent_node == dragged_node.getParent() || dragged_node.isNodeDescendant(parent_node)) {
					//Utils.log("preventing dragging onto itself or any of the self children.");
					return false;
				} else {
					return true;
				}
			}
		}

		// default:
		return false;
	}
 
	public synchronized boolean executeDrop(final DNDTree target, DefaultMutableTreeNode dragged_node, DefaultMutableTreeNode new_parent_node, int action) {

		/* //debug:
		Utils.log2(DnDConstants.ACTION_COPY + " " + DnDConstants.ACTION_COPY_OR_MOVE + " " + DnDConstants.ACTION_LINK + " " + DnDConstants.ACTION_MOVE + " " + DnDConstants.ACTION_NONE + " " + DnDConstants.ACTION_REFERENCE);
		Utils.log2("action: " + action);
		*/

		try {
			// Can't drop onto a TemplateTree
			/*
			if (target instanceof TemplateTree) {
			/	return false;
			}
			*/
			// More specifically: can only drop onto the ProjectTree
			if (!(target instanceof ProjectTree)) {
				return false;
			}

			Thing dragged_thing = null;
			Object ob = dragged_node.getUserObject();
			if (null != ob && ob instanceof Thing) {
				dragged_thing = (Thing)ob;
			} else {
				//Utils.log("DefaultTreeTransferHandler.executeDrop(....): null Thing in the dragged node, or not a Thing instance.");
				return false;
			}
			ProjectThing new_parent_thing = null;
			Object obp = new_parent_node.getUserObject();
			if (null != obp && obp instanceof ProjectThing) {
				new_parent_thing = (ProjectThing)obp;
			}
			
			if (null == new_parent_thing) {
				Utils.log("WARNING: null parent element while dragging and dropping.");
				return false;
			}

			// Prevent adding more profiles to a profile_list if it contains at least one already
			if (new_parent_thing.getType().equals("profile_list") && null != new_parent_thing.getChildren() && new_parent_thing.getChildren().size() > 0) {
				Utils.showMessage("Add new profiles by duplicating and linking existing ones.\nAlternatively, start a new profile_list.");
				return false;
			}

			// Setup undo step
			project.getRootLayerSet().addChangeTreesStep();

			/* //debug:
			if (action == DnDConstants.ACTION_COPY) {
				Utils.log("exec drop: Action copy");
			} else if (action == DnDConstants.ACTION_MOVE) {
				Utils.log("exec drop: Action move");
			} else {
				Utils.log("exec drop: Unexpected action: " + action);
			}
			*/

			final Runnable after = new Runnable() {
				public void run() {
					// Store current state
					project.getRootLayerSet().addChangeTreesStep();
				}
			};


			if (DnDConstants.ACTION_MOVE == action || action == DnDConstants.ACTION_COPY) {
				// MOVE is used for both dragging from the template tree to the project tree, and also for dragging within the project tree! Insane!
				// So, detect if the dragged node is part of the tempalte or part of the project:
				if (dragged_thing instanceof TemplateThing) {
					// make a copy of the node without its children into the project tree
					//
					// create a new Thing of the same type of the dragged_node, and add it as child to the parent Thing. That it is of the proper type has been checked in the method above 'canPerformAction()'
					TemplateThing tt = (TemplateThing)dragged_thing;
					//ProjectThing new_thing = new ProjectThing(tt, this.project, this.project.makeObject(tt)); // TODO WARNING: I think the this.project will always be the project of the tree in which the node is being dropped, because the DefaultTreeTransferHandler is a listener on the right tree.

					//debug:
					//Utils.log2("DTTH: isBasicType: " + Project.isBasicType(dragged_thing.getType()));
					//Utils.log2("DTTH: canHaveAsChild: " + new_parent_thing.canHaveAsChild(dragged_thing));
					//Utils.log2("DTTH: uniquePathExists: " + new_parent_thing.uniquePathExists(dragged_thing.getType()));
					// add missing parents if any
					if (!Project.isBasicType(dragged_thing.getType()) && !new_parent_thing.canHaveAsChild(dragged_thing) && new_parent_thing.uniquePathExists(dragged_thing.getType())) {
						// a unique path exists to one of its children or children of children, etc.
						// 1 - get the cascade of parent types
						final ArrayList<TemplateThing> al = new_parent_thing.getTemplatePathTo(dragged_thing.getType());
						// discard first (the self) and last (the child to make)
						al.remove(0);
						al.remove(al.size()-1);
						// 2 - check if any of such parents exists already, and create them if necessary
						if (0 == al.size()) return false; // some error ocurred ...
						ProjectThing a_parent = new_parent_thing;
						DefaultMutableTreeNode a_parent_node = new_parent_node;
						for (final TemplateThing t : al) {
							String type = t.getType();
							final ArrayList<ProjectThing> al_c = a_parent.findChildrenOfType(type);
							if (0 == al_c.size()) {
								// create a parent of the given type and assign it to a_parent
								ProjectThing a_pt = a_parent.createChild(type);
								DefaultMutableTreeNode a_node = ProjectTree.makeNode(a_pt);
								((DefaultTreeModel)target.getModel()).insertNodeInto(a_node,a_parent_node,a_parent_node.getChildCount());
								// assign
								a_parent = a_pt;
								a_parent_node = a_node;
							} else {
								// a parent exists!
								//if (1 != al_c.size()) return false; // more than one, can't decide!
								// just add it to the first found:
								a_parent = (ProjectThing)al_c.get(0);
								a_parent_node = DNDTree.findNode(a_parent, target);
							}
						}
						// 3 - add the node, finally:
						new_parent_node = a_parent_node;
						new_parent_thing = a_parent;
						//  The creation is done below! In the above two lines the parent is adjusted, that's all.

						// debug: print
						/*
						for (int k = 0; k<al.size(); k++) {
							Utils.log2("parent: " + al.get(k));
						}
						*/
					}

					if (DnDConstants.ACTION_COPY == action) {
						if (Project.isBasicType(tt.getType()) && null == Display.getFront()) {
							return false;
						}
						// create nodes recursively
						final ArrayList<ProjectThing> nc = new_parent_thing.createChildren(tt.getType(), 1, true);
						target.addLeafs(nc, after);
						return true;
					}


					ProjectThing new_thing = new_parent_thing.createChild(tt.getType());
					if (null == new_thing) return false; // for example, if no Display is open for a Profile or Pipe
					DefaultMutableTreeNode new_node = ProjectTree.makeNode(new_thing);
					if (null == new_node) {
						//Utils.log("DefaultTreeTransferHandler.executeDrop(....): can't add new project thing.");
						return false;
					}

					// on success, edit the target tree
					((DefaultTreeModel)target.getModel()).insertNodeInto(new_node,new_parent_node,new_parent_node.getChildCount());
					TreePath treePath = new TreePath(new_node.getPath());
					target.scrollPathToVisible(treePath);
					target.setSelectionPath(treePath);
					// and set the new_thing as child!
					new_parent_thing.addChild(new_thing);

					// open the parent node
					//DNDTree.expandAllNodes(target, new_parent_node);
					DNDTree.expandNode(target, new_parent_node);

					// the dragged node that remains in the template tree is being collapsed for no good reason: expand it
					//DNDTree.expandAllNodes(project.getTemplateTree(), dragged_node);
					DNDTree.expandNode(project.getTemplateTree(), dragged_node);

					after.run();
					return true;
				} else if (DnDConstants.ACTION_MOVE == action && dragged_thing instanceof ProjectThing) {
					// change the parent of the dragged thing, within the project tree, only if possible:
					// check first if possible
					if (!new_parent_thing.canHaveAsChild(dragged_thing)) return false;
					// remove from previous parent
					ProjectThing p_dragged_thing = (ProjectThing)dragged_thing;
					ProjectThing old_parent = (ProjectThing)p_dragged_thing.getParent();
					if (null != old_parent) {
						if (!old_parent.removeChild(p_dragged_thing)) {
							return false;
						}
					} else {
						Utils.log("WARNING: the parent of the source node is null when drag and drop!");
						return false;
					}
					if (!new_parent_thing.addChild(p_dragged_thing)) {
						// on failure, restore
						old_parent.addChild(p_dragged_thing);
						return false;
					}
					// on success, edit the tree:
					dragged_node.removeFromParent();
					((DefaultTreeModel)target.getModel()).insertNodeInto(dragged_node,new_parent_node,new_parent_node.getChildCount());
					TreePath treePath = new TreePath(dragged_node.getPath());
					target.scrollPathToVisible(treePath);
					target.setSelectionPath(treePath);

					// open the parent node
					//DNDTree.expandAllNodes(target, new_parent_node);
					DNDTree.expandNode(target, new_parent_node);

					after.run();
					return true;
				}
			}

		} catch (Exception e) {
			IJError.print(e);
			return false;
		}

		// default:
		return false;
	}

	public void destroy() {
		super.destroy();
		this.project = null;
	}
}
