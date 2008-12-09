/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Layer;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.Utils;

import java.util.*;
import javax.swing.JMenuItem;
import javax.swing.JMenu;
import java.awt.event.ActionListener;

public final class LayerThing extends DBObject implements Thing {

	/** The model for this LayerThing instance. */
	private TemplateThing template;

	/** If null, this instance is root. */
	private LayerThing parent;

	private Object object; // a Layer or a LayerSet

	private ArrayList al_children = null;

	private HashMap ht_attributes = null;
	// TODO : attributes, at all?
	private String title = null;


	public LayerThing(TemplateThing template, Project project, Object ob) throws Exception {
		// call super constructor
		super(project);
		// specifics:
		this.template = template;
		if (null == template) throw new Exception("LayerThing constructor: null template!");
		this.object = ob;
		if (null == ob) throw new Exception("LayerThing constructor: null Object!");
		addToDatabase();
	}

	/** Reconstruct from database, in combination with the setup() method. */
	public LayerThing(TemplateThing template, Project project, long id, String title, Object ob, ArrayList al_children, HashMap ht_attributes) {
		super(project, id);
		this.template = template;
		this.object = ob;
		this.title = title;
		if (null != title && (0 == title.length() || title.toLowerCase().equals("null"))) {
			this.title = null;
		}
		this.al_children = al_children;
		this.ht_attributes = ht_attributes;
	}

	/** Tell the attributes who owns them, and the children's attributes as well, and set the parent to the children; used to finish up reconstruction from the database. */
	public void setup() {
		if (null != ht_attributes) {
			for (Iterator it = ht_attributes.values().iterator(); it.hasNext(); ) {
				ProjectAttribute pa = (ProjectAttribute)it.next(); // ?? A ProjectAttribute? WARNING
				pa.setup(this);
			}
		}
		if (null != al_children) {
			Iterator it = al_children.iterator();
			/* // Taken care of in the Loader
			Class[] class_types = null;
			try { 
				class_types = new Class[]{Class.forName("ini.trakem2.DBObject")};
			} catch (Exception e) {
				Utils.log("LayerThing.setup: " + e);
				return;
			}
			Method method = this.object.getClass().getDeclaredMethod("addSilently", class_types);
			*/
			while (it.hasNext()) {
				LayerThing child = (LayerThing)it.next();
				/* // Taken care of in the Loader
				try {
					// add LayerSets to Layers, and viceversa (Patches, profiles, etc. do not belong to Things so they won't be as children of this Thing)
					method.invoke(this.object, new Object[]{child.getObject()});
				} catch (Exception e) {
					Utils.log("LayerThing.setup: " + e);
					continue;
				}
				*/
				child.parent = this;
				child.setup();
			}
		}
	}

	public Thing getParent() {
		return parent;
	}

	public TemplateThing getChildTemplate(String type) {
		return template.getChildTemplate(type);
	}

	public String toString() {
		final StringBuffer sb = new StringBuffer();
		if (null != parent) sb.append(Integer.toString(parent.indexOf(this) + 1)).append(':').append(' ');
		if (null != title) sb.append(title);
		sb.append(' ');
		if (null == object) sb.append(template.getType());
		else sb.append(object.toString()).append(' ').append('[').append(template.getType()).append(']');
		return sb.toString();
	}

	public void setTitle(String title) {
		if (null == title || title.equals(this.title)) return;
		this.title = title;
		updateInDatabase("title");
		if (object instanceof Layer) { // haha, bad design ... the DBObject should have a get/setTitle method pair
			Display.updateTitle((Layer)object);
		}
	}

	/** May be null or empty; call toString() to get a textual representation. */
	public String getTitle() {
		return this.title;
	}

	public boolean canHaveAsChild(Thing thing) {
		if (null == thing) return false;
		return template.canHaveAsChild(thing);
	}

	public boolean addChild(Thing child) {
		if (!template.canHaveAsChild(child)) {
			return false;
		}
		if (null == al_children) al_children = new ArrayList();
		if (null != child.getObject() && child.getObject() instanceof Layer) { // this is a patch, but hey, do you want to redesign the events, which are based on layer titles and toString() contents? TODO ...
			Layer l = (Layer)child.getObject();
			int i = l.getParent().indexOf(l);
			//Utils.log2("al_children.size(): " + al_children.size() + ",  i=" + i);
			if (i >= al_children.size()) { //TODO happens when importing a stack
				al_children.add(child);
			} else {
				try {
					al_children.add(i, child);
				} catch (Exception e) {
					Utils.log2("LayerThing.addChild: " + e);
					al_children.add(child); // at the end
				}
			}
		} else {
			al_children.add(child);
		}
		child.setParent(this);
		return true;
	}

	public boolean removeChild(LayerThing child) {
		if (null == al_children || null == child || -1 == al_children.indexOf(child)) return false;
		al_children.remove(child);
		return true;
	}

	public String getType() {
		return template.getType();
	}

	public HashMap getAttributes() {
		return ht_attributes; // TODO for now, Layer and LayerSet have no attributes
	}

	public boolean canHaveAsAttribute(String type) {
		if (null == type) return false;
		return template.canHaveAsAttribute(type);
	}

	public ArrayList getChildren() {
		return al_children;
	}

	public Object getObject() {
		return object;
	}

	public void setParent(Thing parent) {
		this.parent = (LayerThing)parent;
		updateInDatabase("parent_id");
	}

	public JMenuItem[] getPopupItems(ActionListener listener) {
		JMenuItem item;
		ArrayList al_items = new ArrayList();

		JMenu menu = new JMenu("Add...");
		ArrayList tc = template.getChildren();
		if (null != tc) {
			for (Iterator it = tc.iterator(); it.hasNext(); ) {
				item = new JMenuItem("new " + ((Thing)it.next()).getType().replace('_', ' ')); // changing underscores for spaces, for the 'layer_set' type to read nice
				item.addActionListener(listener);
				menu.add(item);
			}
			if (template.getType().replaceAll("_", " ").equals("layer set")) {
				item = new JMenuItem("many new layers...");
				item.addActionListener(listener);
				menu.add(item);
			}
		}
		if (0 != menu.getItemCount()) {
			al_items.add(menu);
		}

		// Add a "Show" for all except the root LayerSet
		if (null != parent) {
			item = new JMenuItem("Show");
			item.addActionListener(listener);
			al_items.add(item);
		}
		if (template.getType().equals("layer")) {
			item = new JMenuItem("Adjust..."); // adjust z and thickness
			item.addActionListener(listener);
			al_items.add(item);
		}
		item = new JMenuItem("Rename...");
		item.addActionListener(listener);
		al_items.add(item);
		if (template.getType().replaceAll("_", " ").equals("layer set")) {
			if (null != parent) {
				item = new JMenuItem("Show centered in Display");
				item.addActionListener(listener);
				al_items.add(item);
			}
			item = new JMenuItem("Resize LayerSet...");
			item.addActionListener(listener);
			al_items.add(item);

			LayerSet layer_set = (LayerSet)object;
			final boolean empty = 0 == layer_set.getLayers().size();

			item = new JMenuItem("Autoresize LayerSet");
			item.addActionListener(listener);
			al_items.add(item);
			if (empty) item.setEnabled(false);

			item = new JMenuItem("Translate layers in Z...");
			item.addActionListener(listener);
			al_items.add(item);
			if (empty) item.setEnabled(false);

			item = new JMenuItem("Reverse layer Z coords...");
			item.addActionListener(listener);
			al_items.add(item);
			if (empty) item.setEnabled(false);

			item = new JMenuItem("Search...");
			item.addActionListener(listener);
			al_items.add(item);
			if (empty) item.setEnabled(false);
		}
		item = new JMenuItem("Import stack...");
		if (template.getType().replaceAll("_", " ").equals("layer set") && 0 != ((LayerSet)object).getLayers().size()) item.setEnabled(false); // contains layers already, wouldn't know where to put it!
		item.addActionListener(listener);
		al_items.add(item);
		if (template.getType().equals("layer")) {
			item = new JMenuItem("Import grid...");
			item.addActionListener(listener);
			al_items.add(item);
			item = new JMenuItem("Import sequence as grid...");
			item.addActionListener(listener);
			al_items.add(item);
			item = new JMenuItem("Import from text file...");
			item.addActionListener(listener);
			al_items.add(item);
		}

		// add a delete to all except the root LayerSet
		if (null != parent) {
			al_items.add(new JMenuItem(""));
			item = new JMenuItem("Delete...");
			item.addActionListener(listener);
			al_items.add(item);
		}

		JMenuItem[] items = new JMenuItem[al_items.size()];
		al_items.toArray(items);
		return items;
	}

	/** Remove this instance, cascading the remove action to the children and the objects.  Will also cleanup the nodes in the ProjectTree. */
	public boolean remove(boolean check) {
		if (check && !Utils.check("Really delete " + this.toString() + (object instanceof Layer && ((Layer)object).getDisplayables().size() > 0 ? " and all its children?" : ""))) return false;
		// remove the children, which will propagate to their own objects
		if (null != al_children) {
			Object[] ob = new Object[al_children.size()];
			al_children.toArray(ob);
			for (int i=0; i<ob.length; i++) {
				if (ob[i] instanceof DBObject) {
					if (!((DBObject)ob[i]).remove(false)) {
						Utils.showMessage("Could not delete " + ob[i]);
						return false;
					}
				}
			}
			al_children.clear();
		}
		// TODO the attributes are being ignored! (not even created either)

		// remove the object
		if (null != object && object instanceof DBObject) {
			if (!((DBObject)object).remove(false)) {
				Utils.showMessage("Could not delete " + object);
				return false;
			}
		}

		// remove the Thing itself
		if (null != parent && !parent.removeChild(this)) {
			Utils.showMessage("Could not delete LayerThing with id=" + id);
			return false;
		}
		removeFromDatabase();
		return true;
	}

	/** Recursive search for the thing that contains the given object. */
	public Thing findChild(Object ob) {
		if (this.object.equals(ob)) return this;
		if (null == al_children) return null;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			Thing found = ((Thing)it.next()).findChild(ob);
			if (null != found) return found;
		}
		return null;
	}

	public int indexOf(LayerThing child) {
		return al_children.indexOf(child);
	}

	public void debug(String indent) {
		StringBuffer sb_at = new StringBuffer(" (id,"); // 'id' exists regardless
		if (null != ht_attributes) {
			for (Iterator it = ht_attributes.values().iterator(); it.hasNext(); ) {
				ProjectAttribute ta = (ProjectAttribute)it.next();
				sb_at.append(ta.getTitle()).append(",");
			}
		}
		sb_at.append(")");
		// append object
		sb_at.append("  object: ").append(object);
		System.out.println(indent + template.getType() + sb_at.toString());
		if (null != al_children) {
			if (indent.length() > 20) {
				System.out.println("INDENT OVER 20 !");
				return;
			}
			for (Iterator it = al_children.iterator(); it.hasNext(); ) {
				((Thing)it.next()).debug(indent + "\t");
			}
		}
	}

	public boolean isExpanded() {
		return project.getLayerTree().isExpanded(this);
	}

	/** Return information on this node and its object. */ // TODO: make it recursive on all children objects, listing their attributes and their object's info
	public String getInfo() {
		return "Node: " + object + "\n" + (object instanceof DBObject ? ((DBObject)object).getInfo() : "");
	}
}
