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

import ij.measure.ResultsTable;
import ini.trakem2.Project;
import ini.trakem2.display.AreaContainer;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.DisplayablePanel;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Profile;
import ini.trakem2.display.Tree;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.persistence.DBLoader;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Event;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;


public final class ProjectThing extends DBObject implements TitledThing {

	/** The model for this ProjectThing instance. */
	private TemplateThing template;

	/** Can be null - a ProjectThing with a null parent is by definition a root Thing. */
	private ProjectThing parent;

	/** Access to a non-null al_children is synchronized. Works because al_children is never set to null if it is already not null.*/
	private ArrayList<ProjectThing> al_children = null;

	/** The object held by this ProjectThing. Can be a simple String when it holds no object. The title of a Thing is the title of the object it holds, or the String itself. The title is always accessed with Object.toString(). */
	private Object object;


	/** A new copy with same template, same object and cloned table of same attributes, but no parent and no children. */
	public Thing shallowCopy() {
		return new ProjectThing(this);
	}

	/** Recursively copy the tree of Thing that starts at this Thing, assigning as parent the given one. */
	/*
	public Thing shallowCopyR(final Thing parent) {
		final ProjectThing copy = new ProjectThing(this.template, this.project, this.ob);
		copy.parent = parent;
		if (null != ht_attributes) {
			copy.ht_attributes = (HashMap) ht_attributes.clone();
		}
		if (null != this.al_children) {
			copy.al_children = new ArrayList<ProjectThing>(al_children.size());
			for (final ProjectThing child : this.al_children) {
				copy.al_children.add(child.shallowCopy(copy));
			}
		}
		return copy;
	}
	*/

	/** For shallow copying purposes. */
	private ProjectThing(final ProjectThing pt) {
		super(pt.project, pt.id);
		this.template = pt.template;
		this.object = pt.object;
	}

	/** Create a new ProjectThing of the given type to contain the given Object. The object cannot be null. */
	public ProjectThing(final TemplateThing template, Project project, Object ob) throws Exception {
		// call super constructor
		super(project);
		// specifics:
		if (null == ob) throw new Exception("ProjectThing constructor: Null Object!");
		if (null == template) throw new Exception("ProjectThing constructor: Null template!");
		this.template = project.getTemplateThing(template.getType());
		this.object = ob;
		// now, ready:
		addToDatabase();
	}

	/** Check, recursively, that the children of tt2 are also in tt1.
	 *  Assumes that tt1.type == tt2.type */
	@SuppressWarnings("unchecked")
	private void assertChildren(final TemplateThing tt1, final TemplateThing tt2) throws Exception {
		// Check that if this type exists in the target project, it can have the same children
		final List<TemplateThing> c1 = null == tt1.getChildren() ? Collections.EMPTY_LIST : new ArrayList<TemplateThing>(tt1.getChildren());
		final List<TemplateThing> c2 = null == tt2.getChildren() ? Collections.EMPTY_LIST : new ArrayList<TemplateThing>(tt2.getChildren());
		Collections.sort(c1);
		Collections.sort(c2);

		if (c1.isEmpty() && c2.isEmpty()) {
			return; // no children to check
		} if (c1.size() != c2.size()) {
			throw new Exception("ERROR: receiving project has a different number of children for type " + tt1.getType() + " / " + tt2.getType());
		}

		for (final Iterator<TemplateThing> it1 = c1.iterator(), it2 = c2.iterator(); it2.hasNext(); ) {
			final TemplateThing a = it1.next(),
			                    b = it2.next();
			if (! a.getType().equals(b.getType())) {
				throw new Exception("ERROR: type '" + tt1.getType() + "' of receiving project has a child '" + a.getType() + "' that is not present in the cognate type of the sending project.");
			}
			assertChildren(a, b);
		}
	}

	/** Clone this ProjectThing with its template and all its children and objects cloned, and return it.
	 *  The templates are added to the project as unique types when not there yet.
	 *  WARNING there isn't any conflict resolution between potentially different kinds of TemplateThing parent/child chains. */
	public ProjectThing deepClone(final Project project, final boolean copy_id) throws Exception {
		// Find a template for this in project, otherwise create it
		TemplateThing tt = project.getTemplateThing(this.template.getType()); // WARNING: not checking if parent/child chain is identical. Just the name.
		if (null == tt) {
			tt = this.template.clone(project, copy_id); // deep copy, with children
			project.addUniqueType(tt);
		} else {
			// Check that if this type exists in the target project, it can have the same children
			assertChildren(tt, this.template);
		}
		// Check that the entire child chain is there:
		ArrayList<String> missing = new ArrayList<String>();
		for (TemplateThing tn : (Collection<TemplateThing>)this.template.collectAllChildren(new ArrayList<TemplateThing>())) {
			if (!project.typeExists(tn.getType())) {
				missing.add(tn.getType());
			}
		}
		if (!missing.isEmpty()) {
			throw new Exception("Can't transfer: missing templates " + Utils.toString(missing));
		}
		

		// Make a deep copy of this
		ProjectThing copy = new ProjectThing(tt, project, object instanceof Displayable ? ((Displayable)object).clone(project, copy_id) : object);
		if (null != this.al_children) {
			synchronized (al_children) {
				for (final ProjectThing child : this.al_children) {
					copy.addChild(child.deepClone(project, copy_id));
				}
			}
		}
		return copy;
	}

	/** Reconstruct a ProjectThing from the database, used in  combination with the 'setup' method. */
	public ProjectThing(TemplateThing template, Project project, long id, Object ob, ArrayList<ProjectThing> al_children) {
		// call super constructor
		super(project, id);
		// specifics:
		this.template = project.getTemplateThing(template.getType());
		if (null == al_children || 0 == al_children.size()) {
			this.al_children = null;
		} else {
			this.al_children = al_children;
		}
		
		this.object = ob;
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
		if (null != al_children) {
			synchronized (al_children) {
				for (ProjectThing child : al_children) {
					child.parent = this;
					child.setup();
				}
			}
		}
	}

	public String toString() {
		// 'object' can be the title, if not directly holding a Displayable object
		final StringBuilder sb = new StringBuilder();
		if (null == object) sb.append(template.getType());
		else sb.append(object.toString()).append(' ').append('[').append(template.getType()).append(']');
		return sb.toString();
	}

	public String getTitle() {
		return (null == object ? template.getType() : object.toString());
	}

	private boolean canAddChild(Thing child) {
		// check if the child is allowed in this ProjectThing
		if (!template.canHaveAsChild(child)) {
			Utils.log2("Rejecting child " + child);
			return false;
		}
		if (null != al_children) {
			synchronized (al_children) {
				if (-1 != al_children.indexOf((ProjectThing)child)) { // paranoid avoidance of duplicates
					Utils.log2("Rejecting child " + child);
					return false;
				}
			}
		}
		return true;
	}
	
	public boolean addChild(Thing child) {
		if (!canAddChild(child)) return false;

		// proceed to add the child
		if (null == al_children) al_children = new ArrayList<ProjectThing>();
		synchronized (al_children) {
			al_children.add((ProjectThing)child);
		}
		child.setParent(this);
		return true;
	}
	/** In addition, if the child contains an object of class ini.trakem2.display.Profile, reorders all children of the parent Thing by the Z of the layers of the profiles. */
	public boolean addChild(ProjectThing child, int index) {
		if (!canAddChild(child)) return false;
		
		// proceed to add the child
		if (null == al_children) {
			al_children = new ArrayList<ProjectThing>();
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

	public ArrayList<ProjectThing> getChildren() {
		return al_children;
	}

	public boolean hasChildren() {
		return !(null == al_children || 0 == al_children.size());
	}

	public boolean removeChild(ProjectThing child) {
		if (null == al_children) return false;
		synchronized (al_children) {
			// check that it is contained here
			if (-1 == al_children.indexOf(child)) {
				Utils.log("ProjectThing.removeChild: child " + child + " not contained in parent " + this);
				return false;
			}
			// generates an extra database call for nothing//child.setParent(null);
			al_children.remove(child);
		}
		return true;
	}

	public boolean remove(boolean check) {
		return remove(check, true);
	}
	
	protected boolean remove(boolean check, boolean remove_object) {
		if (check) {
			if (!Utils.check("Really delete " + this.toString() + (null == al_children || 0 == al_children.size() ? "" : " and all its children?"))) return false;
		}
		// remove the children, which will propagate to their own objects
		if (null != al_children) {
			final ProjectThing[] children;
			synchronized (al_children) {
				// can't delete directly from the al_children because the child will call removeChild on its parent
				children = al_children.toArray(new ProjectThing[al_children.size()]);
			}

			for (int i=0; i<children.length; i++) {
				if (!children[i].remove(false)) {
					Utils.showMessage("Deletion incomplete, check database, for child: " + children[i]);
					return false;
				}
			}
		}
		// remove the object
		if (remove_object && null != object && object instanceof DBObject) {
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
				IJError.print(e);
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

	/** Crawl up until finding a parent that has no parent: the root. */
	public ProjectThing getRootParent() {
		if (null == parent) return this;
		else return parent.getRootParent();
	}

	/** Check if this or any of its parents are of the given type. */
	public boolean hasParent(final String type) {
		if (null == parent) return false;
		if (template.getType().equals(type)) return true; // also for itself
		return parent.hasParent(type);
	}

	public void setTitle(String title) {
		// A Thing has a title as the object when it has no object, because the object gives it the title (the Thing only defines the type)
		if (null == title || title.length() < 1) {
			if (object != null && object instanceof String) this.object = template.getType(); // reset title
			return;
		}
		if (null == object || object instanceof String) {
			object = title;
			updateInDatabase("title");
			// find any children that are using this title in addition to their own for the DisplayablePanel, and update it.
			if (null != al_children) {
				synchronized (al_children) {
					for (ProjectThing pt : al_children) {
						if (pt.object instanceof Displayable) {
							Displayable d = (Displayable)pt.object;
							Display.updateTitle(d.getLayer(), d);
						} else if (pt.getType().equals("profile_list")) {
							if (null == pt.al_children) continue;
							for (ProjectThing pd : pt.al_children) {
								Displayable d = (Displayable)pd.object;
								Display.updateTitle(d.getLayer(), d);
							}
						}
					}
				}
			}
		} else {
			try {
				Method setTitle = null;
				if (object instanceof Displayable) {
					((Displayable)object).setTitle(title);
				} else {
					setTitle = object.getClass().getDeclaredMethod("setTitle", new Class[]{String.class});
					setTitle.invoke(object, new Object[]{title});
				}
			} catch (NoSuchMethodException nsme) {
				Utils.log("No such method: setTitle, for object " + object);
			} catch (Exception e) {
				Utils.log("ProjectThing.setTitle: no such method setTitle or can't access it, in the object " + object);
				IJError.print(e);
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
	public ArrayList<TemplateThing> getTemplatePathTo(String type) {
		return template.getTemplatePathTo(type, new ArrayList<TemplateThing>());
	}

	public String getType() {
		return template.getType();
	}

	public TemplateThing getTemplate() {
		return template;
	}

	public JMenuItem[] getPopupItems(final ActionListener listener) {
		JMenuItem item = null;
		ArrayList<JMenuItem> al_items = new ArrayList<JMenuItem>();

		JMenu menu = new JMenu("Add...");
		final ArrayList<TemplateThing> tc = project.getTemplateThing(template.getType()).getChildren(); // call the project unique type
		if (null != tc) {
			for (final TemplateThing tt : tc) {
				item = new JMenuItem("new " + tt.getType());
				item.addActionListener(listener);
				menu.add(item);
			}
			item = new JMenuItem("many..."); item.addActionListener(listener); menu.add(item);
		}
		if (0 != menu.getItemCount()) {
			if (template.getType().equals("profile_list") && null != al_children && al_children.size() > 0) {
				item.setEnabled(false); // can't add a new profile unless linked to another one.
			}
			al_items.add(menu);
		}
		// generic for all:
		// a 'Show' command on a non-basic type is a render preview.
		addPopupItem("Unhide", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, Event.ALT_MASK, true));

		addPopupItem("Hide", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0, true));

		addPopupItem("Info", listener, al_items);

		addPopupItem("Rename...", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0, true));
		// enable duplicating for basic types only
		if (Project.isBasicType(getType())) {
			addPopupItem("Duplicate", listener, al_items);
		}

		addPopupItem("Select in display", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0, true));

		if (null != object && object instanceof Displayable) {
			addPopupItem("Show centered in Display", listener, al_items);
		}

		if (null != object && object instanceof Tree<?>) {
			addPopupItem("Show tabular view", listener, al_items);
		}

		// plugins
		JMenuItem plugin_menu = Utils.addPlugIns("Project Tree", project, new Callable<Displayable>() {
										public Displayable call() {
											if (object instanceof Displayable) return (Displayable)object;
											return null; }} );
		if (null != plugin_menu) al_items.add(plugin_menu);

		addPopupItem("Measure", listener, al_items);

		addPopupItem("Show in 3D", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0, true));
		addPopupItem("Remove from 3D view", listener, al_items);

		//addPopupItem("Export 3D...", listener, al_items);

		if (template.getType().equals("project")) {
			if (project.getLoader() instanceof DBLoader) {
				addPopupItem("Export project...", listener, al_items);
			} else if (project.getLoader() instanceof FSLoader) {
				addPopupItem("Save", listener, al_items).setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0, true));
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

	private JMenuItem addPopupItem(String command, ActionListener listener, ArrayList<JMenuItem> al_items) {
		JMenuItem item = new JMenuItem(command);
		item.addActionListener(listener);
		al_items.add(item);
		return item;
	}

	/** Switch the visibility of the Displayable objects contained here or in the children. */
	public void setVisible(boolean b) {
		if (object instanceof Displayable) {
			Displayable d = (Displayable) object;
			d.setVisible(b);
			Display.updateCheckboxes(d, DisplayablePanel.VISIBILITY_STATE, b);
		}
		if (null != al_children) {
			synchronized (al_children) {
				for (ProjectThing pt : al_children) pt.setVisible(b);
			}
		}
	}

	/** Creates one instance of the given type and if recursive, also of all possible children of it, and of them, and so on.
	 *  @return all created nodes, or null if the given type cannot be contained here as a child. */
	public ArrayList<ProjectThing> createChildren(final String type, final int amount, final boolean recursive) {
		final ArrayList<ProjectThing> al = new ArrayList<ProjectThing>();
		for (int i=0; i<amount; i++) {
			final ProjectThing pt = createChild(type);
			if (null == pt) continue;
			al.add(pt);
			if (recursive) pt.createChildren(al, new HashSet<String>());
		}
		return al;
	}

	/** Recursively create one instance of each possible children, and store them in the given ArrayList. Will stop if the new child to create has already been created as a parent, i.e. if it's nested. */
	private void createChildren(final ArrayList<ProjectThing> nc, final HashSet<String> parents) {
		if (parents.contains(template.getType())) {
			// don't dive into nested nodes
			return;
		}
		parents.add(template.getType());
		// the template itself is never nested; the ProjectThing has a pointer to the master one.
		final ArrayList<TemplateThing> children = template.getChildren();
		if (null == children) return;
		for (final TemplateThing tt : children) {
			if (parents.contains(tt.getType())) continue; // don't create directly nested types
			ProjectThing newchild = createChild(tt.getType());
			if (null == newchild) continue;
			nc.add(newchild);
			newchild.createChildren(nc, new HashSet<String>(parents)); // each branch needs its own copy of the parent chain
		}
	}

	public boolean canHaveAsChild(String type) {
		return null != template.getChildTemplate(type);
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
			IJError.print(e);
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
		final Displayable displ = (Displayable)((Displayable)child.object).clone();
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
			// user-friendly copy:
			displ.setLocked(false);
			displ.setVisible(true);
			// set the copy as selected in the front Display, if any
			if (null != Display.getFront()) Display.getFront().select(displ);
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
		return pt;
	}

	/** Recursive into children, searches for the things whose object.toString() matches the given regex, case insensitive. If shallow, the recursive search does not look into the children of a parent that matches. */
	public ArrayList<ProjectThing> findChildren(final String regex, final String regex_exclude, final boolean shallow) {
		final ArrayList<ProjectThing> found = new ArrayList<ProjectThing>();
		findChildren(found,
			     null == regex ? null : Pattern.compile("^.*" + regex + ".*$", Pattern.DOTALL),
			     null == regex_exclude ? null : Pattern.compile("^.*" + regex_exclude + ".*$", Pattern.DOTALL),
			     shallow);
		return found;
	}
	/** Recursive into children, searches for things whose object.toString() matches the given regex pattern, and stores the found ProjectThing in the given ArrayList. If shallow, the recursive search does not look into the children of a parent that matches. */
	public void findChildren(final ArrayList<ProjectThing> found, final Pattern pattern, final Pattern pattern_exclude, final boolean shallow) {
		if (null == object) return;
		final String name = object.toString();
		if (null != pattern_exclude && pattern_exclude.matcher(name).matches()) return;
		if (null == pattern) {
			found.add(this);
		} else if (pattern.matcher(name).matches()) {
			found.add(this);
			if (shallow) return; // don't look into children
		}
		if (null == al_children) return;
		synchronized (al_children) {
			for (ProjectThing pt : al_children) {
				pt.findChildren(found, pattern, pattern_exclude, shallow);
			}
		}
	}

	/** Recursive search for the thing that contains the given object. */
	public Thing findChild(final Object ob) {
		if (null != object && object.equals(ob)) return this;
		if (null == al_children) return null;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				Thing found = child.findChild(ob);
				if (null != found) return found;
			}
		}
		return null;
	}

	/** Recursive search for the thing of the given id. */
	public ProjectThing findChild(final long id) {
		if (id == this.id) return this;
		if (null == al_children) return null;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				ProjectThing found = child.findChild(id);
				if (null != found) return found;
			}
		}
		return null;
	}

	/** Recursive search for the object with the given id. */
	public DBObject findObject(final long id) {
		if (id == this.id) return this;
		if (null != object && object instanceof DBObject) {
			DBObject dbo = (DBObject)object;
			if (dbo.getId() == id) return dbo;
		}
		if (null == al_children) return null;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				DBObject dbo = child.findObject(id);
				if (null != dbo) return dbo;
			}
		}
		return null;
	}

	public final class Profile_List {}

	/** Call on children things, and on itself if it contains a basic data type directly.
	 *  All children of the same type report to the same table.
	 *  Result tables are returned without ever displaying them.
	 */
	public HashMap<Class<?>,ResultsTable> measure(HashMap<Class<?>,ResultsTable> ht) {
		if (null == ht) ht = new HashMap<Class<?>,ResultsTable>();
		if (null != object && object instanceof Displayable) {
			Displayable d = (Displayable)object;
			if (d.isVisible()) {
				ResultsTable rt = d.measure(ht.get(d.getClass()));
				if (null != rt) ht.put(d.getClass(), rt);
				// Areas:
				if (object instanceof AreaContainer) {
					ResultsTable rta = ((AreaContainer)object).measureAreas(ht.get(AreaContainer.class));
					if (null != rta) ht.put(AreaContainer.class, rta);
				}
			} else {
				Utils.log("Measure: skipping hidden object " + d.getProject().getMeaningfulTitle(d));
			}
		}
		if (null == al_children) return ht;
		// profile list: always special ...
		if (template.getType().equals("profile_list") && null != al_children) {
			synchronized (al_children) {
				if (al_children.size() > 1) {
					Profile[] p = new Profile[al_children.size()];
					for (int i=0; i<al_children.size(); i++) p[i] = (Profile)al_children.get(i).object;
					ResultsTable rt = Profile.measure(p, ht.get(Profile.class), this.id);
					if (null != rt) ht.put(Profile_List.class, rt);
					//return ht; // don't return: do each profile separately as well
				}
			}
		}
		synchronized (al_children) {
			for (ProjectThing child : al_children) child.measure(ht);
		}
		return ht;
	}

	/** Measure each node, recursively into children, and at the end display all the result tables, one for each data type. */
	public void measure() {
		final HashMap<Class<?>,ResultsTable> ht = new HashMap<Class<?>,ResultsTable>();
		measure(ht);
		// Show all tables. Need to be done at the end -- otherwise, at each call to "show"
		// the entire text panel is flushed and refilled with all data and repainted.
		Utils.showAllTables(ht);
	}

	public void exportSVG(StringBuffer data, double z_scale, String indent) {
		String type = template.getType();
		if (!type.equals("profile_list") && Project.isBasicType(type)) {
			((Displayable)object).exportSVG(data, z_scale, indent);
		} else {
			data.append(indent).append("<g type=\"").append(type).append("\" title=\"").append(null != object ? object.toString() : type).append("\"");
			data.append(" id=\"").append(id).append("\">\n");
			String in = indent + "\t";
			if (null != al_children) {
				synchronized (al_children) {
					for (ProjectThing child : al_children) { 
						child.exportSVG(data, z_scale, in);
					}
				}
			}
			data.append(indent).append("</g>\n");
		}
	}

	/** Check if this Thing directly contains any child of the given type, and return them all. */
	public ArrayList<ProjectThing> findChildrenOfType(final String type) {
		ArrayList<ProjectThing> al = new ArrayList<ProjectThing>();
		if (null == al_children) return al;
		synchronized (al_children) {
			for (final ProjectThing pt : al_children) {
				if (pt.template.getType().equals(type)) {
					al.add(pt);
				}
			}
		}
		return al;
	}
	/** Check if this Thing directly contains any child of the given object class (including interfaces), and return them all.
	 *  This method looks at the object wrapped by the ProjectThing, and returns a list of objects whose class
	 *  or superclass or interface matches {@param c}. */
	@SuppressWarnings("unchecked")
	public<T> ArrayList<T> findChildrenOfType(final Class<T> c) {
		final ArrayList<T> al = new ArrayList<T>();
		if (null == al_children) return al;
		synchronized (al_children) {
			for (final ProjectThing pt : al_children) {
				if (c.isInstance(pt.object)) {
					al.add((T)pt.object);
				}
			}
		}
		return al;
	}

	/** Recursive into children. */
	public HashSet<ProjectThing> findChildrenOfTypeR(final String type) {
		return findChildrenOfTypeR(new HashSet<ProjectThing>(), type);
	}
	/** Recursive into children. */
	public HashSet<ProjectThing> findChildrenOfTypeR(HashSet<ProjectThing> hs, final String type) {
		if (null == hs) hs = new HashSet<ProjectThing>();
		else if (hs.contains(this)) return hs;
		if (template.getType().equals(type)) hs.add(this);
		if (null == al_children) return hs;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				child.findChildrenOfTypeR(hs, type);
			}
		}
		return hs;
	}

	/** Finds them in order. Recursive into children. */
	public List<ProjectThing> findChildrenOfTypeR(final Class<?> c) {
		return findChildrenOfTypeR(new ArrayList<ProjectThing>(), c);
	}
	/** Finds them in order. Recursive into children. */
	public List<ProjectThing> findChildrenOfTypeR(List<ProjectThing> list, final Class<?> c) {
		if (null == list) list = new ArrayList<ProjectThing>();
		if (c.isInstance(object)) list.add(this);
		if (null == al_children) return list;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				child.findChildrenOfTypeR(list, c);
			}
		}
		return list;
	}
	
	/** Find objects of the given class, as determined by c.isInstance(this.object). */
	public<T> List<T> findObjects(final Class<T> c) {
		final ArrayList<T> col = new ArrayList<T>();
		findObjects(c, col);
		return col;
	}
	@SuppressWarnings("unchecked")
	private final<T> void findObjects(final Class<T> c, final List<T> col) {
		if (c.isInstance(this.object)) col.add((T)this.object);
		if (null == al_children) return;
		synchronized (al_children) {
			for (final ProjectThing pt : al_children) pt.findObjects(c, col);
		}
	}

	/** Recursive into children. */
	public HashSet<ProjectThing> findBasicTypeChildren() {
		return findBasicTypeChildren(new HashSet<ProjectThing>(), new HashSet<ProjectThing>());
	}

	/** Recursive into children, and adds this instance as well if it's a basic type. */
	public HashSet<ProjectThing> findBasicTypeChildren(HashSet<ProjectThing> hs_basic, HashSet<ProjectThing> hs_visited) {
		if (null == hs_basic) hs_basic = new HashSet<ProjectThing>();
		if (null == hs_visited) hs_visited = new HashSet<ProjectThing>();
		if (hs_basic.contains(this) || hs_visited.contains(this)) return hs_basic;
		// register as visited
		hs_visited.add(this);
		// add if basic
		if (Project.isBasicType(template.getType())) hs_basic.add(this); // I don't return here because I make no assumptions of Basic types not having children in the future, plus profile_list is basic and has children
		// search children
		if (null == al_children) return hs_basic;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				child.findBasicTypeChildren(hs_basic, hs_visited); // ignoring returned HashSet pointer; the object should never change
			}
		}
		return hs_basic;
	}

	/** Recursive into children and object (to update it's DisplayablePanel title). Does not affect the database. */
	public void updateTitle() {
		updateTitle(null);
	}

	private void updateTitle(final String old_type) {
		if (this.object instanceof String && this.object.equals(old_type)) this.object = template.getType(); // Set the object to be the template type (a String) unless this thing had a name given to it, which is stored also in the object pointer.
		else if (this.object instanceof Displayable) {
			Displayable d = (Displayable)this.object;
			Display.updateTitle(d.getLayer(), d);
		}
		if (null != al_children) {
			synchronized (al_children) {
				for (ProjectThing child : al_children) {
					child.updateTitle(old_type);
				}
			}
		}
	}

	/** Recursive into children, changes the type column in the ab_things table. Only if the given 'type' is equal to this instance's template type, the type will be updated in the database and the title will be updated wherever it shows. */
	public void updateType(final String new_type, final String old_type) {
		if (new_type.equals(template.getType())) {
			updateInDatabase("type");
			updateTitle(old_type);
		}
		if (null == al_children) return;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				child.updateType(new_type, old_type);
			}
		}
	}

	/** Recursive into children, find those of the given type that have the same parent chain as the given TemplateThing. */
	public HashSet<ProjectThing> collectSimilarThings(final TemplateThing tt, HashSet<ProjectThing> hs) {
		if (template.getType().equals(tt.getType()) && parent.template.getType().equals(tt.getParent().getType())) {
			hs.add(this);
		}
		if (null == al_children) return hs;
		synchronized (al_children) {
			for (final ProjectThing child : al_children) {
				hs = child.collectSimilarThings(tt, hs);
			}
		}
		return hs;
	}

	/** Expects a HashMap<Thing,Boolean> as @param any. */
	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		// write in opening tag, put in there the attributes, then close, then call the children (indented), then closing tag.
		final String in = indent + "\t";
		// 1 - opening tag with attributes:
		final String tag = template.getType().replace(' ','_');
		sb_body.append(indent).append('<').append(tag).append(" id=\"").append(id).append('"');
		// the object id if any
		if (null == object || object.getClass() == String.class) {
				if (!template.getType().equals(object)) {
					sb_body.append(" title=\"").append((String)object).append('"');
				}
		} else {
			sb_body.append(" oid=\"").append(((DBObject)object).getId()).append('"');
		}

		if (null != al_children) {
			final Boolean b = options.expanded_states.get(this);
			if (null != b && Boolean.TRUE.equals(b)) sb_body.append(" expanded=\"true\"");
		}
		// 2 - list of children:
		if (null != al_children) {
			sb_body.append(">\n");
			synchronized (al_children) {
				for (ProjectThing child : al_children) {
					child.exportXML(sb_body, in, options);
				}
			}
			sb_body.append(indent).append("</").append(tag).append(">\n");
		} else {
			sb_body.append("/>\n");
		}
	}

	public void debug(String indent) {
		System.out.println(indent + template.getType() + " (id)");
		if (null != al_children) {
			if (indent.length() > 20) {
				System.out.println("INDENT OVER 20 !");
				return;
			}
			for (ProjectThing child : al_children) {
				child.debug(indent + "\t");
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
		final HashMap<Double,ArrayList<ProjectThing>> ht = new HashMap<Double,ArrayList<ProjectThing>>();
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				Profile p = (Profile)child.object;
				Layer layer = p.getLayer();
				Double z = new Double(layer.getZ()); // contortions: there could be more than one profile in the same layer
				ArrayList<ProjectThing> al = ht.get(z);
				if (null == al) {
					al = new ArrayList<ProjectThing>();
					al.add(child);
					ht.put(z, al);
				} else {
					al.add(child);
				}
			}
			Double[] zs = new Double[ht.size()];
			ht.keySet().toArray(zs);
			Arrays.sort(zs);
			al_children.clear();
			for (int i=0; i<zs.length; i++) {
				al_children.addAll(ht.get(zs[i]));
			}
		}
		return true;
	}

	public boolean isExpanded() {
		return project.getProjectTree().isExpanded(this);
	}

	/** Return information on this node and its object. */
	public String getNodeInfo() {
		return "Node: " + object + " [" + template.getType() + "]\n" + (object instanceof DBObject ? ((DBObject)object).getInfo() : "");
	}

	/** Return information on this node and its object, and also on all the children, recursively. */
	@Override
	public String getInfo() {
		final StringBuilder sb = new StringBuilder();
		getInfo(new HashSet<ProjectThing>(), sb);
		return sb.toString();
	}

	/** Accumulate info recursively from all children nodes. */
	private final void getInfo(final HashSet<ProjectThing> hs, final StringBuilder info) {
		if (hs.contains(this)) return;
		hs.add(this);
		info.append('\n').append(getNodeInfo());
		if (null == al_children) return;
		synchronized (al_children) {
			for (final ProjectThing child : al_children) {
				child.getInfo(hs, info);
			}
		}
	}

	/** Implicit id copying; assumes the ids of the object are also the same in the given project; the object, if it is a DBObject, is retrieved from the given project by matching its id. */
	public ProjectThing subclone(final Project pr) {
		Object ob = null;
		if (null != this.object) {
			if (this.object instanceof DBObject) ob = pr.getRootLayerSet().findById(((DBObject)this.object).getId());
			else ob = this.object; // String is a final class: no copy protection needed.
		}
		final ProjectThing copy = new ProjectThing(pr.getTemplateThing(this.template.getType()), pr, this.id, ob, new ArrayList<ProjectThing>());
		if (null != this.al_children) {
			copy.al_children = new ArrayList<ProjectThing>();
			synchronized (al_children) {
				for (ProjectThing child : this.al_children) {
					ProjectThing cc = child.subclone(pr);
					// check object:
					if (child.object instanceof DBObject && null == cc.object) continue; // don't add: the object was not cloned and thus not found.
					cc.setParent(this);
					copy.al_children.add(cc);
				}
			}
		}
		return copy;
	}

	/** Moves this ProjectThing up and down the parent's children list. Returns false if no movement was done. */
	public boolean move(final int inc) {
		if (null == parent) return false;
		int i = parent.al_children.indexOf(this);
		if (0 == inc || i + inc < 0 || i + inc >= parent.al_children.size()) return false;
		// swap objects (the ArrayList.set(..) replaces the element at the index)
		parent.al_children.set(i, parent.al_children.get(i+inc));
		parent.al_children.set(i+inc, this);
		return true;
	}

	/** Recursively browse all children to classify all nodes by type. Returns a table of String types and ArrayList ProjectThing. */
	public HashMap<String,ArrayList<ProjectThing>> getByType() {
		return getByType(new HashMap<String,ArrayList<ProjectThing>>());
	}

	private HashMap<String,ArrayList<ProjectThing>> getByType(final HashMap<String,ArrayList<ProjectThing>> ht) {
		String type = template.getType();
		ArrayList<ProjectThing> ap = ht.get(type);
		if (null == ap) {
			ap = new ArrayList<ProjectThing>();
			ht.put(type, ap);
		}
		ap.add(this);
		if (null == al_children) return ht;
		synchronized (al_children) {
			for (ProjectThing child : al_children) {
				child.getByType(ht);
			}
		}
		return ht;
	}
}
