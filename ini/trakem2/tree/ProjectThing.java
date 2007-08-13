/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005 Albert Cardona and Rodney Douglas.

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
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Display3D;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.persistence.*;

import java.awt.event.ActionListener;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;

import javax.swing.JMenu;
import javax.swing.JMenuItem;


public class ProjectThing extends DBObject implements Thing {

	/** The model for this ProjectThing instance. */
	private TemplateThing template;

	/** Can be null - a ProjectThing with a null parent is by definition a root Thing. */
	private ProjectThing parent;

	private ArrayList al_children = null;

	/** The object holded by this ProjectThing. Can be a simple String when it holds no object. The title of a Thing is the title of the object it holds, or the String itself. The title is always accessed with Object.toString(). */
	private Object object;

	private Hashtable ht_attributes;

	/** Create a new ProjectThing of the given type to contain the given Object. The object cannot be null. */
	public ProjectThing(final TemplateThing template, Project project, Object ob) throws Exception {
		// call super constructor
		super(project);
		// specifics:
		this.template = project.getTemplateThing(template.getType());
		copyAttributes();
		this.object = ob;
		if (null == ob) throw new Exception("ProjectThing constructor: Null Object!");
		if (null == template) throw new Exception("ProjectThing constructor: Null template!");
		// now, ready:
		addToDatabase();
	}

	/** Reconstruct a ProjectThing from the database, used in  combination with the 'setup' method. */
	public ProjectThing(TemplateThing template, Project project, long id, Object ob, ArrayList al_children, Hashtable ht_attributes) {
		// call super constructor
		super(project, id);
		// specifics:
		this.template = project.getTemplateThing(template.getType());
		if (null == al_children || 0 == al_children.size()) {
			this.al_children = null;
		} else {
			this.al_children = al_children;
		}
		if (null == ht_attributes || ht_attributes.isEmpty()) {
			this.ht_attributes = null;
		} else {
			this.ht_attributes = ht_attributes;
		}
		// TEMPORARY: TODO title should be and remain an attribute
		Object ob_title = ht_attributes.get("title");
		if (null != ob_title && ob_title instanceof ProjectAttribute) {
			this.object = (String)((ProjectAttribute)ob_title).getObject();
			ht_attributes.remove("title");
		} else {
			this.object = ob;
		}
	}

	/** Used by the TMLHandler; can be used only once. */
	public void setObject(Object object) {
		if (null != this.object && !this.object.equals(template.getType())) {
			Utils.log(this + " already contains an object.");
			return;
		}
		this.object = object;
	}

	/* Tell the attributes and the children who owns them, and then those of the children, recursively. Used when reconstructing from the database. */
	public void setup() {
		if (null != ht_attributes) {
			Enumeration e = ht_attributes.keys();
			while (e.hasMoreElements()) {
				ProjectAttribute pa = (ProjectAttribute)ht_attributes.get(e.nextElement());
				// now tell it to resolve its object
				pa.setup(this);
			}
		}
		if (null != al_children) {
			Iterator it = al_children.iterator();
			while (it.hasNext()) {
				ProjectThing child = (ProjectThing)it.next();
				child.parent = this;
				child.setup();
			}
		}
	}

	/** Setup the attributes from the template, if any. */
	private void copyAttributes() {
		if (null == template.getAttributes() || template.getAttributes().isEmpty()) {
			this.ht_attributes = null;
			return;
		}
		Enumeration keys = template.getAttributes().keys();
		this.ht_attributes = new Hashtable();
		while (keys.hasMoreElements()) {
			String key = (String)keys.nextElement();
			ht_attributes.put(key, new ProjectAttribute(project, key, null, this));
		}
		updateInDatabase("attributes");
	}

	public String toString() {
		// 'object' can be the title, if not directly holding a Displayable object
		return (null == object ? template.getType() : object.toString()) + " [" + template.getType() + "]";
	}

	public String getTitle() {
		return (null == object ? template.getType() : object.toString());
	}

	public boolean addChild(Thing child) {
		// check if the child is allowed in this ProjectThing
		if (!template.canHaveAsChild(child) || (null != al_children && -1 != al_children.indexOf(child))) { // paranoid avoidance of duplicates
			return false;
		}
		// proceed to add the child
		if (null == al_children) al_children = new ArrayList();
		al_children.add(child);
		child.setParent(this);
		return true;
	}
	/** In addition, if the child contains an object of class ini.trakem2.display.Profile, reorders all children of the parent Thing by the Z of the layers of the profiles. */
	public boolean addChild(ProjectThing child, int index) {
		// check if the child is allowed in this ProjectThing
		if (!template.canHaveAsChild(child) || (null != al_children && -1 != al_children.indexOf(child))) { // paranoid avoidance of duplicates
			return false;
		}
		// proceed to add the child
		if (null == al_children) {
			al_children = new ArrayList();
			al_children.add(child);
		} else {
			if (index < 0) al_children.add(0, child);
			else if (index >= al_children.size()) al_children.add(child); // append at the end
			else al_children.add(index, child);
		}
		child.setParent(this);
		// fix profile Z ordering
		if (child.object instanceof Profile) {
			child.parent.fixZOrdering();
		}
		return true;
	}

	public ArrayList getChildren() {
		return al_children;
	}

	public boolean addAttribute(String title, Object object) {
		if (null == title || null == object) return false;
		if (title.equals("id")) return true; // no need to store the id as an attribute (but will exists as such in the XML file)
		if (!template.canHaveAsAttribute(title)) return false;
		if (null == ht_attributes) ht_attributes = new Hashtable();
		else if (ht_attributes.containsKey(title)) {
			Utils.log("ProjectThing.addAttribute: " + this + " already has an attribute of type " + title);
			return false;
		}
		ht_attributes.put(title, object);
		return true;
	}

	public boolean setAttribute(String type, Object object) {
		if (null == type || null == object) return false;
		if (null == ht_attributes || !ht_attributes.containsKey(type)) return false;
		ProjectAttribute attr = (ProjectAttribute)ht_attributes.get(type);
		attr.setObject(object);
		return true;
	}

	public boolean removeChild(ProjectThing child) {
		// check that it is contained here
		if (-1 == al_children.indexOf(child)) {
			Utils.log("ProjectThing.removeChild: child " + child + " not contained in parent " + this);
			return false;
		}
		// generates an extra database call for nothing//child.setParent(null);
		al_children.remove(child);
		return true;
	}

	public boolean remove(boolean check) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null == al_children || 0 == al_children.size() ? "" : " and all its children?"))) return false;
		}
		// remove the children, which will propagate to their own objects
		if (null != al_children) {
			Object[] children = new Object[al_children.size()];
			al_children.toArray(children); // can't delete directly from the al_children because the child will call removeChild on its parent
			for (int i=0; i<children.length; i++) {
				Object ob = children[i];
				if (ob instanceof DBObject) {
					if (!((DBObject)ob).remove(false)) {
						Utils.showMessage("Deletion incomplete, check database, for child: " + ob.toString());
						return false;
					}
				}
			}
		}
		// remove the attributes
		if (null != ht_attributes) {
			Enumeration e = ht_attributes.keys();
			while (e.hasMoreElements()) {
				if (! ((ProjectAttribute)ht_attributes.get(e.nextElement())).remove(false)) {
					Utils.showMessage("Deletion incomplete at attributes, check database for thing: " + this);
					return false;
				}
			}
		}
		// remove the object
		if (null != object && object instanceof DBObject) {
			if (!((DBObject)object).remove(false)) {
				Utils.showMessage("Deletion incomplete, check database, for object: " + object.toString());
				return false;
			}
			// remove from the 3D world if needed
			try {
				if (null != Class.forName("ij3d.ImageWindow3D")) {
					Display3D.remove(this);
				}
			} catch (ClassNotFoundException cnfe) {
				Utils.log("ImageJ_3D_Viewer.jar not installed.");
			} catch (Exception e) {
				new IJError(e);
			}
		}
		// remove the Thing itself
		if (null != parent && !parent.removeChild(this)) {
			Utils.showMessage("Deletion incomplete, check database, for parent of ProjectThing id=" + id);
			return false;
		}
		return removeFromDatabase();
	}

	public void setParent(Thing parent) {
		this.parent = (ProjectThing)parent;
		updateInDatabase("parent_id");
	}

	public Thing getParent() {
		return parent;
	}

	public void setTitle(String title) {
		// A Thing has a title as the object when it has no object, because the object gives it the title (the Thing only defines the type)
		if (null == title || title == "") return;
		if (null == object || object instanceof String) {
			object = title;
			updateInDatabase("title");
			// find any children that are using this title in addition to their own for the DisplayablePanel, and update it.
			if (null != al_children) {
				for (Iterator it = al_children.iterator(); it.hasNext(); ) {
					ProjectThing pt = (ProjectThing)it.next();
					if (pt.object instanceof Displayable) {
						Displayable d = (Displayable)pt.object;
						Display.updateTitle(d.getLayer(), d);
					} else if (pt.getType().equals("profile_list")) {
						if (null == pt.al_children) continue;
						for (Iterator pit = pt.al_children.iterator(); pit.hasNext(); ) {
							ProjectThing pd = (ProjectThing)pit.next();
							Displayable d = (Displayable)pd.object;
							Display.updateTitle(d.getLayer(), d);
						}
					}
				}
			}
		} else {
			try {
				Method setTitle = object.getClass().getDeclaredMethod("setTitle", new Class[]{String.class});
				if (null != setTitle) {
					setTitle.invoke(object, new Object[]{title});
				}
			} catch (Exception e) {
				Utils.log("ProjectThing.setTitle: no such method setTitle or can't access it, in the object " + object);
				new IJError(e);
			}
		}
	}

	public Object getObject() {
		if (null == object) return template.getType();
		return object;
	}

	public TemplateThing getChildTemplate(String type) {
		return template.getChildTemplate(type);
	}

	public boolean canHaveAsChild(Thing thing) {
		if (null == thing) return false;
		return template.canHaveAsChild(thing);
	}

	/** Check if any of the possible children of this Thing can have the given Thing as child, or any of they children can, recursing down. Returns true only if one possibility exists. */
	public boolean uniquePathExists(String type) {
		return template.uniquePathExists(type);
	}

	/** Returns a list of parent types (String), in order, for the given Thing if it exists as a child in the traversed trees. This method returns the first path that it finds, avoiding a check to uniquePathExists(type). */
	public ArrayList getTemplatePathTo(String type) {
		return template.getTemplatePathTo(type, new ArrayList());
	}

	public boolean canHaveAsAttribute(String type) {
		if (null == type) return false;
		return template.canHaveAsAttribute(type);
	}

	public Hashtable getAttributes() {
		return ht_attributes;
	}

	public String getType() {
		return template.getType();
	}

	public JMenuItem[] getPopupItems(ActionListener listener) {
		JMenuItem item = null;
		ArrayList al_items = new ArrayList();

		JMenu menu = new JMenu("Add...");
		ArrayList tc = template.getChildren(); // may need to call on the project unique types
		if (null != tc) {
			Iterator it = tc.iterator();
			while (it.hasNext()) {
				item = new JMenuItem("new " + ((Thing)it.next()).getType());
				item.addActionListener(listener);
				menu.add(item);
			}
		}
		if (0 != menu.getItemCount()) {
			if (template.getType().equals("profile_list") && null != al_children && al_children.size() > 0) {
				item.setEnabled(false); // can't add a new profile unless linked to another one.
			}
			al_items.add(menu);
		}
		// generic for all:
		addPopupItem("Unhide", listener, al_items); // a 'Show' command on a non-basic type is a render preview.

		addPopupItem("Hide", listener, al_items);

		addPopupItem("Info", listener, al_items);

		// enable renaming for non-basic types only
		if (!Project.isBasicType(getType())) {
			addPopupItem("Rename...", listener, al_items);
		} else {
			addPopupItem("Duplicate", listener, al_items);
		}

		if (null != object && object instanceof Displayable) {
			addPopupItem("Show centered in Display", listener, al_items);
		}

		addPopupItem("Measure", listener, al_items);

		addPopupItem("Show in 3D", listener, al_items);
		addPopupItem("Export 3D...", listener, al_items);

		if (template.getType().equals("project")) {
			if (project.getLoader() instanceof DBLoader) {
				addPopupItem("Export project...", listener, al_items);
			} else if (project.getLoader() instanceof FSLoader) {
				addPopupItem("Save", listener, al_items);
				addPopupItem("Save as...", listener, al_items);
			}
		}

		if (!(template.getType().equals("project") && project.getLoader() instanceof FSLoader)) {

			addPopupItem("Delete...", listener, al_items);
		}

		JMenuItem[] items = new JMenuItem[al_items.size()];
		al_items.toArray(items);
		return items;
	}

	private void addPopupItem(String command, ActionListener listener, ArrayList al_items) {
		JMenuItem item = new JMenuItem(command);
		item.addActionListener(listener);
		al_items.add(item);
	}

	/** Switch the visibility of the Displayable objects contained here or in the children. */
	public void setVisible(boolean b) {
		if (object instanceof Displayable) {
			((Displayable)object).setVisible(b);
		}
		if (null != al_children) {
			Iterator it = al_children.iterator();
			while (it.hasNext()) {
				((ProjectThing)it.next()).setVisible(b);
			}
		}
	}

	public ProjectThing createChild(String type) {
		// create the Displayable
		TemplateThing tt = template.getChildTemplate(type);
		if (null == tt) {
			Utils.log2("Can't create a child of type " + type);
			return null;
		}
		Object ob = project.makeObject(tt);
		Layer layer = null;
		if (ob instanceof Displayable) {
			// which layer to add it to? Get it from the front Display
			layer = Display.getFrontLayer(this.project);
			if (null == layer) {
				Utils.showMessage("Open a display first!");
				((DBObject)ob).removeFromDatabase();
				return null;
			}
		}
		// wrap it in a new ProjectThing
		ProjectThing pt = null;
		try {
			pt = new ProjectThing(tt, project, ob);
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
		// add it here as child
		addChild(pt);
		// finally, add it to the layer if appropriate
		if (null != layer) {
			if (ob instanceof ZDisplayable) {
				layer.getParent().add((ZDisplayable)ob);
			} else {
				layer.add((Displayable)ob);
			}
		}
		// select tab
		//if (ob instanceof Displayable) Display.getFront().selectTab((Displayable)ob);// TODO this should have been done at the Display.setActive level
		// finally, return it to be added to the ProjectTree as a new node
		return pt;
	}

	/** At the moment only for basic types, which by definition have no children. */
	public ProjectThing createClonedChild(final ProjectThing child) {
		// must be a child and a basic type
		if (null == child || null == child.object || null == al_children || !al_children.contains(child) || !Project.isBasicType(child.getType())) {
			return null;
		}
		Displayable displ = (Displayable)((Displayable)child.object).clone();
		ProjectThing pt = null;
		try {
			pt = new ProjectThing(child.template, project, displ);
			addChild(pt);
			// add to the proper container
			if (displ instanceof ZDisplayable) {
				ZDisplayable original = (ZDisplayable)child.object;
				original.getLayerSet().add((ZDisplayable)displ);
				Display.repaint(original.getLayerSet(), (ZDisplayable)displ, 5);
			} else {
				Displayable original = (Displayable)child.object;
				original.getLayer().add(displ);
				Display.repaint(original.getLayer(), displ, 5);
			}
			// set the copy as selected in the front Display, if any
			if (null != Display.getFront()) Display.getFront().select(displ);
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
		// the cloned object has already added itself to the same Layer or LayerSet as the original
		return pt;
	}

	/** Recursive search for the thing that contains the given object. */
	public Thing findChild(Object ob) {
		if (null != object && object.equals(ob)) return this;
		if (null == al_children) return null;
		Iterator it = al_children.iterator();
		while (it.hasNext()) {
			Thing found = ((Thing)it.next()).findChild(ob);
			if (null != found) return found;
		}
		return null;
	}

	/** Recursive search for the thing of the given id. */
	public ProjectThing findChild(long id) {
		if (id == this.id) return this;
		if (null == al_children) return null;
		Iterator it = al_children.iterator();
		while (it.hasNext()) {
			ProjectThing found = ((ProjectThing)it.next()).findChild(id);
			if (null != found) return found;
		}
		return null;
	}

	/** Call on children things, and on itself if it contains a basic data type directly. */
	public void measure() {
		Utils.log("Measure: not implemented yet.");
	}

	public void exportSVG(StringBuffer data, double z_scale, String indent) {
		String type = template.getType();
		if (!type.equals("profile_list") && Project.isBasicType(type)) {
			((Displayable)object).exportSVG(data, z_scale, indent);
		} else {
			data.append(indent).append("<g type=\"").append(type).append("\" title=\"").append(null != object ? object.toString() : type).append("\"");
			data.append(" id=\"").append(id).append("\">\n");
			String in = indent + "\t";
			for (Iterator it=al_children.iterator(); it.hasNext(); ) { 
				((ProjectThing)it.next()).exportSVG(data, z_scale, in);
			}
			data.append(indent).append("</g>\n");
		}
	}

	/** Check if this Thing directly contains any child of the given type, and return them all. */
	public ArrayList findChildrenOfType(String type) {
		ArrayList al = new ArrayList();
		if (null == al_children) return al;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			ProjectThing pt = (ProjectThing)it.next();
			if (pt.template.getType().equals(type)) {
				al.add(pt);
			}
		}
		return al;
	}

	/** Recursive into children. */
	public HashSet findChildrenOfTypeR(final String type) {
		return findChildrenOfTypeR(new HashSet(), type);
	}
	/** Recursive into children. */
	public HashSet findChildrenOfTypeR(HashSet hs, final String type) {
		if (null == hs) hs = new HashSet();
		else if (hs.contains(this)) return hs;
		if (template.getType().equals(type)) hs.add(this);
		if (null == al_children) return hs;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			ProjectThing child = (ProjectThing)it.next();
			child.findChildrenOfTypeR(hs, type);
		}
		return hs;
	}

	/** Recursive into children. */
	public HashSet findBasicTypeChildren() {
		return findBasicTypeChildren(new HashSet(), new HashSet());
	}

	/** Recursive into children, and adds this instance as well if it's a basic type. */
	public HashSet findBasicTypeChildren(HashSet hs_basic, HashSet hs_visited) {
		if (null == hs_basic) hs_basic = new HashSet();
		if (null == hs_visited) hs_visited = new HashSet();
		if (hs_basic.contains(this) || hs_visited.contains(this)) return hs_basic;
		// register as visited
		hs_visited.add(this);
		// add if basic
		if (Project.isBasicType(template.getType())) hs_basic.add(this); // I don't return here because I make no assumptions of Basic types not having children in the future, plus profile_list is basic and has children, even if useless at the moment
		// search children
		if (null == al_children) return hs_basic;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			ProjectThing child = (ProjectThing)it.next();
			child.findBasicTypeChildren(hs_basic, hs_visited); // ignoring returned HashSet pointer; the object should never change
		}
		return hs_basic;
	}

	/** Recursive into children and object (to update it's DisplayablePanel title). Does not affect the database. */
	public void updateTitle() {
		if (this.object instanceof String) this.object = template.getType();
		else if (this.object instanceof Displayable) {
			Displayable d = (Displayable)this.object;
			Display.updateTitle(d.getLayer(), d);
		}
		if (null != al_children && 0 != al_children.size()) {
			for (Iterator it = al_children.iterator(); it.hasNext(); ) {
				((ProjectThing)it.next()).updateTitle();
			}
		}
	}

	/** Recursive into children, changes the type column in the ab_things table. Only if the given 'type' is equal to this instance's template type, the type will be updated in the database and the title will be updated wherever it shows. */
	public void updateType(String type) {
		if (type.equals(template.getType())) {
			updateInDatabase("type");
			updateTitle();
		}
		if (null == al_children || al_children.isEmpty()) return;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			((ProjectThing)it.next()).updateType(type);
		}
	}

	/** Recursive into children, find those of the given type that have the same parent chain as the given TemplateThing. */
	public HashSet collectSimilarThings(TemplateThing tt, HashSet hs) {
		if (template.getType().equals(tt.getType()) && parent.template.getType().equals(tt.getParent().getType())) {
			hs.add(this);
		}
		if (null == al_children || al_children.isEmpty()) return hs;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			hs = ((ProjectThing)it.next()).collectSimilarThings(tt, hs);
		}
		return hs;
	}

	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		if (null != object && object instanceof Displayable && ((Displayable)object).isDeletable()) {
			// don't save // WARNING I'm not checking on children, there should never be any if this ProjectThing is wrapping a Displayable object.
			return;
		}
		// write in opening tag, put in there the attributes, then close, then call the children (indented), then closing tag.
		String in = indent + "\t";
		// 1 - opening tag with attributes:
		String tag = template.getType().replace(' ','_');
		sb_body.append(indent).append("<").append(tag).append(" id=\"").append(id).append("\"");
		// the object id if any
		if (object instanceof DBObject) {
			sb_body.append(" oid=\"").append(((DBObject)object).getId()).append("\"");
		} else if (object instanceof String && !object.equals(template.getType())) { // TODO the title should be an attribute
			// the title
			sb_body.append(" title=\"").append((String)object).append("\"");
		}
		boolean expanded = this.project.getProjectTree().isExpanded(this);
		if (expanded) sb_body.append(" expanded=\"true\"");
		if (null != ht_attributes && !ht_attributes.isEmpty() ) {
			sb_body.append("\n");
			// the rest of the attributes:
			for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
				String key = (String)e.nextElement();
				ProjectAttribute pa = (ProjectAttribute)ht_attributes.get(key);
				sb_body.append(in).append(pa.asXML()).append("\n");
			}
			sb_body.append(indent).append(">\n");
		} else {
			sb_body.append(">\n");
		}
		// 2 - list of children:
		if (null != al_children && 0 != al_children.size()) {
			for (Iterator it = al_children.iterator(); it.hasNext(); ) {
				DBObject dbo = (DBObject)it.next();
				dbo.exportXML(sb_body, in, any);
			}
		}
		// 3 - closing tag:
		sb_body.append(indent).append("</").append(tag).append(">\n");
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
		if (null != object) {
			System.out.println(indent + "ob:" + object);
		}
	}

	/** If this is of type profile_list, order all children Profile by their layer's Z coordinate, ascending.*/
	public boolean fixZOrdering() {
		if (!this.template.getType().equals("profile_list")) return false;
		if (null == al_children || al_children.size() < 2) return true; // no need
		// fix Z ordering in the tree
		Hashtable ht = new Hashtable();
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			ProjectThing child = (ProjectThing)it.next();
			Profile p = (Profile)child.object;
			Layer layer = p.getLayer();
			Double z = new Double(layer.getZ()); // contortions: there could be more than one profile in the same layer
			Object ob_al = ht.get(z);
			ArrayList al;
			if (null == ob_al) {
				al = new ArrayList();
				al.add(child);
				ht.put(z, al);
			} else {
				al = (ArrayList)ob_al;
				al.add(child);
			}
		}
		Double[] zs = new Double[ht.size()];
		ht.keySet().toArray(zs);
		Arrays.sort(zs);
		al_children.clear();
		for (int i=0; i<zs.length; i++) {
			al_children.addAll((ArrayList)ht.get(zs[i]));
		}
		return true;
	}

	public boolean isExpanded() {
		return project.getLayerTree().isExpanded(this);
	}

	/** Return information on this node and its object. */
	public String getInfo() {
		return "Node: " + object + "\n" + (object instanceof DBObject ? ((DBObject)object).getInfo() : "") + " [" + template.getType() + "]";
	}
}
