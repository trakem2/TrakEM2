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
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public final class LayerTree extends DNDTree implements MouseListener, ActionListener {

	private static final long serialVersionUID = 1L;

	private DefaultMutableTreeNode selected_node = null;

	public LayerTree(Project project, LayerThing root) {
		super(project, DNDTree.makeNode(root), new Color(230, 235, 255)); // Color(200, 200, 255));
		setEditable(false);
		addMouseListener(this);
		// enable multiple discontiguous selection
		this.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
	}

	/** Get a custom, context-sensitive popup menu for the selected node. */
	private JPopupMenu getPopupMenu(DefaultMutableTreeNode node) {
		Object ob = node.getUserObject();
		LayerThing thing = null;
		if (ob instanceof LayerThing) {
			thing = (LayerThing)ob;
		} else {
			return null;
		}
		// context-sensitive popup
		JMenuItem[] item = thing.getPopupItems(this);
		if (0 == item.length) return null;
		JPopupMenu popup = new JPopupMenu();
		for (int i=0; i<item.length; i++) {
			if (null == item[i] || "" == item[i].getText()) popup.addSeparator();
			else popup.add(item[i]);
		}
		return popup;
	}

	public void mousePressed(MouseEvent me) {
		Object source = me.getSource();
		if (!source.equals(this) || !project.isInputEnabled()) {
			return;
		}

		// ignore if doing multiple selection
		if (!Utils.isPopupTrigger(me) && (me.isShiftDown() || (!ij.IJ.isMacOSX() && me.isControlDown()))) {
			return;
		}

		int x = me.getX();
		int y = me.getY();

		// check if there is a multiple selection
		TreePath[] paths = this.getSelectionPaths();
		if (null != paths && paths.length > 1) {
			if (Utils.isPopupTrigger(me)) {
				// check that all items are of the same type
				String type_first = ((LayerThing)((DefaultMutableTreeNode)paths[0].getLastPathComponent()).getUserObject()).getType();
				for (int i=1; i<paths.length; i++) {
					String type = ((LayerThing)((DefaultMutableTreeNode)paths[i].getLastPathComponent()).getUserObject()).getType();
					if (!type.equals(type_first)) {
						Utils.showMessage("All selected items must be of the same type for operations on multiple items.");
						return;
					}
				}
				// prepare popup menu
				JPopupMenu popup = new JPopupMenu();
				JMenuItem item = null;
				if (type_first.equals("layer")) {
					item = new JMenuItem("Reverse layer Z coords"); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("Translate layers in Z..."); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("Scale Z and thickness..."); item.addActionListener(this); popup.add(item);
					item = new JMenuItem("Delete..."); item.addActionListener(this); popup.add(item);
				}
				if (popup.getSubElements().length > 0) {
					popup.show(this, x, y);
				}
			}
			// disable commands depending upon a single node being selected
			selected_node = null;
			return;
		}

		// find the node and set it selected
		TreePath path = getPathForLocation(x, y);
		if (null == path) {
			return;
		}
		setSelectionPath(path);
		selected_node = (DefaultMutableTreeNode)path.getLastPathComponent();

		if (2 == me.getClickCount() && !Utils.isPopupTrigger(me) && MouseEvent.BUTTON1 == me.getButton()) {
			// create a new Display
			LayerThing thing = (LayerThing)selected_node.getUserObject();
			DBObject ob = (DBObject)thing.getObject();
			if (thing.getType().toLowerCase().replace('_', ' ').equals("layer set") && null == ((LayerSet)ob).getParent()) { // the top level LayerSet
				return;
			}
			//new Display(ob.getProject(), thing.getType().toLowerCase().equals("layer") ? (Layer)ob : ((LayerSet)ob).getParent());
			Display.createDisplay(ob.getProject(), thing.getType().toLowerCase().equals("layer") ? (Layer)ob : ((LayerSet)ob).getParent());
			return;
		} else if (Utils.isPopupTrigger(me)) {
			JPopupMenu popup = getPopupMenu(selected_node);
			if (null == popup) return;
			popup.show(this, x, y);
			return;
		}
	}

	public void mouseDragged(MouseEvent me) {
	}
	public void mouseReleased(MouseEvent me) {
	}
	public void mouseEntered(MouseEvent me) {
	}
	public void mouseExited(MouseEvent me) {
	}
	public void mouseClicked(MouseEvent me) {
	}

	public void actionPerformed(ActionEvent ae) {
		try {
			String command = ae.getActionCommand();

			// commands for multiple selections:
			TreePath[] paths = this.getSelectionPaths();
			if (null != paths && paths.length > 1) {
				if (command.equals("Reverse layer Z coords")) {
					// check that all layers belong to the same layer set
					// just do it
					Layer[] layer = new Layer[paths.length];
					LayerSet ls = null;
					for (int i=0; i<paths.length; i++) {
						layer[i] = (Layer) ((LayerThing)((DefaultMutableTreeNode)paths[i].getLastPathComponent()).getUserObject()).getObject();
						if (null == ls) ls = layer[i].getParent();
						else if (!ls.equals(layer[i].getParent())) {
							Utils.showMessage("To reverse, all layers must belong to the same layer set");
							return;
						}
					}
					final ArrayList<Layer> al = new ArrayList<Layer>();
					for (int i=0; i<layer.length; i++) al.add(layer[i]);
					ls.addLayerEditedStep(al);
					// ASSSUMING layers are already Z ordered! CHECK
					for (int i=0, j=layer.length-1; i<layer.length/2; i++, j--) {
						double z = layer[i].getZ();
						layer[i].setZ(layer[j].getZ());
						layer[j].setZ(z);
					}
					updateList(ls);
					ls.addLayerEditedStep(al);
					Display.updateLayerScroller(ls);
				} else if (command.equals("Translate layers in Z...")) {
					GenericDialog gd = ControlWindow.makeGenericDialog("Range");
					gd.addMessage("Translate selected range in the Z axis:");
					gd.addNumericField("by: ", 0, 4);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					// else, displace
					double dz = gd.getNextNumber();
					if (Double.isNaN(dz)) {
						Utils.showMessage("Invalid number");
						return;
					}
					HashSet<LayerSet> hs_parents = new HashSet<LayerSet>();
					for (int i=0; i<paths.length; i++) {
						Layer layer = (Layer) ((LayerThing)((DefaultMutableTreeNode)paths[i].getLastPathComponent()).getUserObject()).getObject();
						layer.setZ(layer.getZ() + dz);
						hs_parents.add(layer.getParent());
					}
					for (LayerSet ls : hs_parents) {
						updateList(ls);
					}
					// now update all profile's Z ordering in the ProjectTree
					ProjectThing root_pt = project.getRootProjectThing();
					for (final ProjectThing pt : root_pt.findChildrenOfType("profile_list")) {
						pt.fixZOrdering();
						project.getProjectTree().updateList(pt);
					}
					project.getProjectTree().updateUILater();
					//Display.updateLayerScroller((LayerSet)((DefaultMutableTreeNode)getModel().getRoot()).getUserObject());
				} else if (command.equals("Delete...")) {
					if (!Utils.check("Really remove all selected layers?")) return;
					for (int i=0; i<paths.length; i++) {
						DefaultMutableTreeNode lnode = (DefaultMutableTreeNode)paths[i].getLastPathComponent();
						LayerThing lt = (LayerThing)lnode.getUserObject();
						Layer layer = (Layer)lt.getObject();
						if (!layer.remove(false)) {
							Utils.showMessage("Could not delete layer " + layer);
							this.updateUILater();
							return;
						}
						if (lt.remove(false)) {
							((DefaultTreeModel)this.getModel()).removeNodeFromParent(lnode);
						}
					}
					this.updateUILater();
				} else if (command.equals("Scale Z and thickness...")) {
					GenericDialog gd = new GenericDialog("Scale Z");
					gd.addNumericField("scale: ", 1.0, 2);
					gd.showDialog();
					double scale = gd.getNextNumber();
					if (Double.isNaN(scale) || 0 == scale) {
						Utils.showMessage("Imvalid scaling factor: " + scale);
						return;
					}
					for (int i=0; i<paths.length; i++) {
						DefaultMutableTreeNode lnode = (DefaultMutableTreeNode)paths[i].getLastPathComponent();
						LayerThing lt = (LayerThing)lnode.getUserObject();
						Layer layer = (Layer)lt.getObject();
						layer.setZ(layer.getZ() * scale);
						layer.setThickness(layer.getThickness() * scale);
					}
					this.updateUILater();
				} else {
					Utils.showMessage("Don't know what to do with command " + command + " for multiple selected nodes");
				}
				return;
			}

			// commands for single selection:
			if (null == selected_node) return;
			LayerThing thing = (LayerThing)selected_node.getUserObject();
			LayerThing new_thing = null;
			TemplateThing tt = null;
			Object ob = null;
			int i_position = -1;

			if (command.startsWith("new ")) {
				String name = command.substring(4).toLowerCase();
				if (name.equals("layer")) {
					// Create new Layer and add it to the selected node
					LayerSet set = (LayerSet)thing.getObject();
					Layer new_layer = Layer.create(thing.getProject(), set);
					if (null == new_layer) return;
					tt = thing.getChildTemplate("layer");
					ob = new_layer;
					Display.updateTitle(set);
				} else if (name.equals("layer set")) { // with space in the middle
					// Create a new LayerSet and add it in the middle
					Layer layer = (Layer)thing.getObject();
					LayerSet new_set = layer.getParent().create(layer);
					if (null == new_set) return;
					layer.add(new_set);
					// add it at the end of the list
					tt = thing.getChildTemplate("layer set"); // with space, not underscore
					ob = new_set;
					i_position = selected_node.getChildCount();
					Display.update(layer);
				} else {
					Utils.log("LayerTree.actionPerformed: don't know what to do with the command: " + command);
					return;
				}
			} else if (command.equals("many new layers...")) {
				LayerSet set = (LayerSet)thing.getObject();
				List<Layer> layers = Layer.createMany(set.getProject(), set);
				// add them to the tree as LayerThing
				if (null == layers) return;
				for (Layer la : layers) {
					addLayer(set, la); // null layers will be skipped
				}
				Display.updateTitle(set);
				return;
			} else if (command.equals("Show")) {
				// create a new Display
				DBObject dbo = (DBObject)thing.getObject();
				if (thing.getType().equals("layer_set") && null == ((LayerSet)dbo).getParent()) return; // the top level LayerSet
				Display.createDisplay(dbo.getProject(), thing.getType().equals("layer") ? (Layer)dbo : ((LayerSet)dbo).getParent());
				return;
			} else if (command.equals("Show centered in Display")) {
				LayerSet ls = (LayerSet)thing.getObject();
				Display.showCentered(ls.getParent(), ls, false, false);
			} else if (command.equals("Delete...")) {
				remove(true, thing, selected_node);
				return;
			} else if (command.equals("Import stack...")) {
				if (thing.getObject() instanceof LayerSet) {
					LayerSet set = (LayerSet)thing.getObject();
					Layer layer = null;
					if (0 == set.getLayers().size()) {
						layer = Layer.create(set.getProject(), set);
						if (null == layer) return;
						tt = thing.getChildTemplate("Layer");
						ob = layer;
					} else return; // click on a desired, existing layer.
					layer.getProject().getLoader().importStack(layer, null, true);
				} else if (thing.getObject() instanceof Layer) {
					Layer layer = (Layer)thing.getObject();
					layer.getProject().getLoader().importStack(layer, null, true);
					return;
				}
			} else if (command.equals("Import grid...")) {
				if (thing.getObject() instanceof Layer) {
					Layer layer = (Layer)thing.getObject();
					layer.getProject().getLoader().importGrid(layer);
				}
			} else if (command.equals("Import sequence as grid...")) {
				if (thing.getObject() instanceof Layer) {
					Layer layer = (Layer)thing.getObject();
					layer.getProject().getLoader().importSequenceAsGrid(layer);
				}
			} else if (command.equals("Import from text file...")) {
				if (thing.getObject() instanceof Layer) {
					Layer layer = (Layer)thing.getObject();
					layer.getProject().getLoader().importImages(layer);
				}
			} else if (command.equals("Resize LayerSet...")) {
				if (thing.getObject() instanceof LayerSet) {
					LayerSet ls = (LayerSet)thing.getObject();
					ij.gui.GenericDialog gd = ControlWindow.makeGenericDialog("Resize LayerSet");
					gd.addNumericField("new width: ", ls.getLayerWidth(), 3);
					gd.addNumericField("new height: ",ls.getLayerHeight(),3);
					gd.addChoice("Anchor: ", LayerSet.ANCHORS, LayerSet.ANCHORS[0]);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					float new_width = (float)gd.getNextNumber(),
						  new_height = (float)gd.getNextNumber();
					ls.setDimensions(new_width, new_height, gd.getNextChoiceIndex()); // will complain and prevent cropping existing Displayable objects
				}
			} else if (command.equals("Autoresize LayerSet")) {
				if (thing.getObject() instanceof LayerSet) {
					LayerSet ls = (LayerSet)thing.getObject();
					ls.setMinimumDimensions();
				}
			} else if (command.equals("Adjust...")) {
				if (thing.getObject() instanceof Layer) {
					Layer layer= (Layer)thing.getObject();
					ij.gui.GenericDialog gd = ControlWindow.makeGenericDialog("Adjust Layer");
					gd.addNumericField("new z: ", layer.getZ(), 4);
					gd.addNumericField("new thickness: ",layer.getThickness(),4);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					double new_z = gd.getNextNumber();
					layer.setThickness(gd.getNextNumber());
					if (new_z != layer.getZ()) {
						layer.setZ(new_z);
						// move in the tree
						/*
						DefaultMutableTreeNode child = findNode(thing, this);
						DefaultMutableTreeNode parent = (DefaultMutableTreeNode)child.getParent();
						parent.remove(child);
						// reinsert
						int n = parent.getChildCount();
						int i = 0;
						for (; i < n; i++) {
							DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)parent.getChildAt(i);
							LayerThing child_thing = (LayerThing)child_node.getUserObject();
							if (!child_thing.getType().equals("Layer")) continue;
							double iz = ((Layer)child_thing.getObject()).getZ();
							if (iz < new_z) continue;
							// else, add the layer here, after this one
							break;
						}
						((DefaultTreeModel)this.getModel()).insertNodeInto(child, parent, i);
						*/
						
						// fix tree crappiness (empty slot ?!?)
						/* // the fix doesn't work. ARGH TODO
						Enumeration e = parent.children();
						parent.removeAllChildren();
						i = 0;
						while (e.hasMoreElements()) {
							//parent.add((DefaultMutableTreeNode)e.nextElement());
							((DefaultTreeModel)this.getModel()).insertNodeInto(child, parent, i);
							i++;
						}*/

						// easier and correct: overkill
						updateList(layer.getParent());

						// set selected
						DefaultMutableTreeNode child = findNode(thing, this);
						TreePath treePath = new TreePath(child.getPath());
						this.scrollPathToVisible(treePath);
						this.setSelectionPath(treePath);
					}
				}
				return;
			} else if (command.equals("Rename...")) {
				GenericDialog gd = ControlWindow.makeGenericDialog("Rename");
				gd.addStringField("new name: ", thing.getTitle());
				gd.showDialog();
				if (gd.wasCanceled()) return;
				project.getRootLayerSet().addUndoStep(new RenameThingStep(thing));
				thing.setTitle(gd.getNextString());
				project.getRootLayerSet().addUndoStep(new RenameThingStep(thing));
			} else if (command.equals("Translate layers in Z...")) {
				/// TODO: this method should use multiple selections directly on the tree
				if (thing.getObject() instanceof LayerSet) {
					LayerSet ls = (LayerSet)thing.getObject();
					ArrayList<Layer> al_layers = ls.getLayers();
					String[] layer_names = new String[al_layers.size()];
					for (int i=0; i<layer_names.length; i++) {
						layer_names[i] = ls.getProject().findLayerThing(al_layers.get(i)).toString();
					}
					GenericDialog gd = ControlWindow.makeGenericDialog("Range");
					gd.addMessage("Translate selected range in the Z axis:");
					gd.addChoice("from: ", layer_names, layer_names[0]);
					gd.addChoice("to: ", layer_names, layer_names[layer_names.length-1]);
					gd.addNumericField("by: ", 0, 4);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					// else, displace
					double dz = gd.getNextNumber();
					if (Double.isNaN(dz)) {
						Utils.showMessage("Invalid number");
						return;
					}
					int i_start = gd.getNextChoiceIndex();
					int i_end = gd.getNextChoiceIndex();
					for (int i = i_start; i<=i_end; i++) {
						Layer layer = (Layer)al_layers.get(i);
						layer.setZ(layer.getZ() + dz);
					}
					// update node labels and position
					updateList(ls);
				}
			} else if (command.equals("Reverse layer Z coords...")) {
				/// TODO: this method should use multiple selections directly on the tree
				if (thing.getObject() instanceof LayerSet) {
					LayerSet ls = (LayerSet)thing.getObject();
					ArrayList<Layer> al_layers = ls.getLayers();
					String[] layer_names = new String[al_layers.size()];
					for (int i=0; i<layer_names.length; i++) {
						layer_names[i] = ls.getProject().findLayerThing(al_layers.get(i)).toString();
					}
					GenericDialog gd = ControlWindow.makeGenericDialog("Range");
					gd.addMessage("Reverse Z coordinates of selected range:");
					gd.addChoice("from: ", layer_names, layer_names[0]);
					gd.addChoice("to: ", layer_names, layer_names[layer_names.length-1]);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					int i_start = gd.getNextChoiceIndex();
					int i_end = gd.getNextChoiceIndex();
					for (int i = i_start, j=i_end; i<i_end/2; i++, j--) {
						Layer layer1 = (Layer)al_layers.get(i);
						double z1 = layer1.getZ();
						Layer layer2 = (Layer)al_layers.get(j);
						layer1.setZ(layer2.getZ());
						layer2.setZ(z1);
					}
					// update node labels and position
					updateList(ls);
				}
			} else if (command.equals("Search...")) {
				Search.showWindow();
			} else if (command.equals("Reset layer Z and thickness")) {
				LayerSet ls = ((LayerSet)thing.getObject());
				List<Layer> layers = ls.getLayers();
				ls.addLayerEditedStep(layers);
				int i = 0;
				for (final Layer la : ls.getLayers()) {
					la.setZ(i++);
					la.setThickness(1);
				}
				ls.addLayerEditedStep(layers);
			} else {
				Utils.log("LayerTree.actionPerformed: don't know what to do with the command: " + command);
				return;
			}

			if (null == tt) return;

			new_thing = new LayerThing(tt, thing.getProject(), ob);

			if (-1 == i_position && new_thing.getType().equals("layer")) {
				// find the node whose 'z' is larger than z, and add the Layer before that.
				// (just because there could be objects other than LayerThing with a Layer in it in the future, so set.getLayers().indexOf(layer) may not be useful)
				double z = ((Layer)ob).getZ();
				int n = selected_node.getChildCount();
				int i = 0;
				for (; i < n; i++) {
					DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)selected_node.getChildAt(i);
					LayerThing child_thing = (LayerThing)child_node.getUserObject();
					if (!child_thing.getType().equals("layer")) {
						continue;
					}
					double iz = ((Layer)child_thing.getObject()).getZ();
					if (iz < z) {
						continue;
					}
					// else, add the layer here, after this one
					break;
				}
				i_position = i;
			}
			thing.addChild(new_thing);
			DefaultMutableTreeNode new_node = new DefaultMutableTreeNode(new_thing);
			((DefaultTreeModel)this.getModel()).insertNodeInto(new_node, selected_node, i_position);
			TreePath treePath = new TreePath(new_node.getPath());
			this.scrollPathToVisible(treePath);
			this.setSelectionPath(treePath);

			if (new_thing.getType().equals("layer set")) {
				// add the first layer to it, and open it in a Display
				LayerSet newls = (LayerSet)new_thing.getObject();
				Layer la = new Layer(newls.getProject(), 0, 1, newls);
				addLayer(newls, la);
				new Display(newls.getProject(), la);
			}

		} catch (Exception e) {
			IJError.print(e);
		}
	}

	public boolean remove(Layer layer, boolean check) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode)this.getModel().getRoot();
		LayerThing thing = (LayerThing)(((LayerThing)root.getUserObject()).findChild(layer));
		if (null == thing) { Utils.log2("LayerTree.remove(Layer): thing not found"); return false; }
		DefaultMutableTreeNode node = DNDTree.findNode(thing, this);
		if (null == node) { Utils.log2("LayerTree.remove(Layer): node not found"); return false; }
		if (thing.remove(check)) {
			((DefaultTreeModel)this.getModel()).removeNodeFromParent(node);
			this.updateUILater();
		}
		return true;
	}

	/** Used by the Loader.importStack and the "many new layers" command. */
	public void addLayer(LayerSet layer_set, Layer layer) {
		if (null == layer_set || null == layer) return;
		try {
			// find the node that contains the LayerSet
			DefaultMutableTreeNode root_node = (DefaultMutableTreeNode)this.getModel().getRoot();
			LayerThing root_lt = (LayerThing)root_node.getUserObject();
			Thing thing = null;
			if (root_lt.getObject().equals(layer_set)) thing = root_lt;
			else thing = root_lt.findChild(layer_set);
			DefaultMutableTreeNode parent_node = DNDTree.findNode(thing, this);
			if (null == parent_node) { Utils.log("LayerTree: LayerSet not found."); return; }
			LayerThing parent_thing = (LayerThing)parent_node.getUserObject();
			double z = layer.getZ();
			// find the node whose 'z' is larger than z, and add the Layer before that.
			int n = parent_node.getChildCount();
			int i = 0;
			for (; i < n; i++) {
				DefaultMutableTreeNode child_node = (DefaultMutableTreeNode)parent_node.getChildAt(i);
				LayerThing child_thing = (LayerThing)child_node.getUserObject();
				if (!child_thing.getType().equals("layer")) {
					continue;
				}
				double iz = ((Layer)child_thing.getObject()).getZ();
				if (iz < z) {
					continue;
				}
				// else, add the layer here, after the 'i' layer which has a larger z
				break;
			}
			TemplateThing tt = parent_thing.getChildTemplate("layer");
			if (null == tt) {
				Utils.log("LayerTree: Null template Thing!");
				return;
			}
			LayerThing new_thing = new LayerThing(tt, layer.getProject(), layer);
			// Add the new_thing to the tree
			if (null != new_thing) {
				parent_thing.addChild(new_thing);
				DefaultMutableTreeNode new_node = new DefaultMutableTreeNode(new_thing);
				//TODO when changing the Z of a layer, the insertion is proper but an empty space is left //Utils.log("LayerTree: inserting at: " + i);
				((DefaultTreeModel)this.getModel()).insertNodeInto(new_node, parent_node, i);
				TreePath treePath = new TreePath(new_node.getPath());
				this.scrollPathToVisible(treePath);
				this.setSelectionPath(treePath);
			}
		} catch (Exception e) { IJError.print(e); }
	}

	public void destroy() {
		super.destroy();
		this.selected_node = null;
	}

	/** Remove all layer nodes from the given layer_set, and add them again according to the layer's Z value. */
	public void updateList(LayerSet layer_set) {

		// store scrolling position for restoring purposes
		/*
		Component c = this.getParent();
		Point point = null;
		if (c instanceof JScrollPane) {
			point = ((JScrollPane)c).getViewport().getViewPosition();
		}
		*/

		LayerThing lt = layer_set.getProject().findLayerThing(layer_set);
		if (null == lt) {
			Utils.log2("LayerTree.updateList: could not find LayerSet " + layer_set);
			return;
		}
		// call super
		updateList(lt);
		/*
		DefaultMutableTreeNode ls_node = DNDTree.findNode(lt, this);
		if (null == ls_node) {
			Utils.log2("LayerTree.updateList: could not find a node for LayerThing " + lt);
			return;
		}
		Hashtable ht = new Hashtable();
		for (Enumeration e = ls_node.children(); e.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)e.nextElement();
			ht.put(node.getUserObject(), node);
		}
		ls_node.removeAllChildren();
		for (Iterator it = lt.getChildren().iterator(); it.hasNext(); ) {
			Object ob = ht.remove(it.next());
			ls_node.add((DefaultMutableTreeNode)ob);
		}
		if (0 != ht.size()) {
			Utils.log2("WARNING LayerTree.updateList: did not end up adding this nodes:");
			for (Iterator it = ht.keySet().iterator(); it.hasNext(); ) {
				Utils.log2(it.next().toString());
			}
		}
		this.updateUILater();

		// restore viewport position
		if (null != point) {
			((JScrollPane)c).getViewport().setViewPosition(point);
		}
		*/
		// what the hell:
		this.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
	}

	/** If the given node is null, it will be searched for. */
	public boolean remove(boolean check, LayerThing thing, DefaultMutableTreeNode node) {
		if (null == thing || null == thing.getParent()) return false; // can't remove the root LayerSet
		return thing.remove(check) && removeNode(null != node ? node : findNode(thing, this));
	}

	protected DNDTree.NodeRenderer createNodeRenderer() {
		return new LayerThingNodeRender();
	}

	static private final Color FRONT_LAYER_COLOR = new Color(1.0f, 1.0f, 0.4f, 0.5f);

	protected final class LayerThingNodeRender extends DNDTree.NodeRenderer {
		private static final long serialVersionUID = 1L;

		public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean selected, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {

			final JLabel label = (JLabel)super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
			label.setText(label.getText().replace('_', ' ')); // just for display

			try {

			if (value.getClass() == DefaultMutableTreeNode.class) {
				final Object obb = ((DefaultMutableTreeNode)value).getUserObject();
				if (!(obb instanceof LayerThing)) {
					Utils.log2("WARNING: not a LayerThing: obb is " + obb.getClass() + " and contains " + obb + "   " + ((Thing)obb).getObject());
				}
				final Object ob = ((Thing)obb).getObject();
				final Layer layer = Display.getFrontLayer();
				if (ob == layer) {
					label.setOpaque(true); //this label
					label.setBackground(FRONT_LAYER_COLOR); // this label
				} else if (ob.getClass() == LayerSet.class && null != layer && layer.contains((Displayable)ob)) {
					label.setOpaque(true); //this label
					label.setBackground(ProjectTree.ACTIVE_DISPL_COLOR); // this label
				} else {
					label.setOpaque(false); //this label
					label.setBackground(background);
				}
			}

			} catch (Throwable t) {
				t.printStackTrace();
			}
			return label;
		}
	}
	
	/** Deselects whatever node is selected in the tree, and tries to select the one that contains the given object. */
	public void selectNode(final Layer layer) {
		final DefaultMutableTreeNode node = DNDTree.findNode2(layer, this);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// deselect whatever is selected
				setSelectionPath(null);
				if (null != node) {
					final TreePath path = new TreePath(node.getPath());
					try {
						scrollPathToVisible(path); // involves repaint, so must be set through invokeAndWait. Why it doesn't do so automatically is beyond me.
						setSelectionPath(path);
					} catch (Exception e) {
						IJError.print(e, true);
					}
				}
		}});
	}
	
	@Override
	protected Thing getRootThing() {
		return project.getRootLayerThing();
	}
}
