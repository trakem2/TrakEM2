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
import ini.trakem2.persistence.DBObject;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;


public class ProjectAttribute extends DBObject implements Attribute { // stupid Java! I have to duplicate all the code just because there isn't multiple inheritance!
	private String title;
	private Object object;
	private Thing owner;

	/** Construct a new attribute with the given title and the given object, which for example can be of class Thing. */
	public ProjectAttribute(Project project, String title, Object object, Thing owner) {
		super(project);
		this.title = title;
		this.object = object;
		this.owner = owner;
		addToDatabase();
	}

	/** Construct an attribute from the database, used in combination with the setup method to restore the owner and set the proper object (this constructor is for setting a temporary object). */
	public ProjectAttribute(Project project, long id, String title, Object object) {
		super(project, id);
		this.title = title;
		this.object = object;
		this.owner = null;
	}

	/** Resolve the temporary object to the proper object, and assign the owner Thing. */
	public void setup(Thing owner) {
		this.owner = owner;
		// now get the temporary String object and find the proper object using the information it contains, if it's a long
		try {
			if (object instanceof String) {
				String sob = (String)object;
				int i = title.lastIndexOf("_id");
				if (0 == sob.length() || sob.equals("")) {
					// an empty string means empty, no object yet
					object = null; //empty
				} else if (-1 != i && i == title.length() -3) {
					long id = Long.parseLong(sob);
					ProjectThing pt = project.find(id);
					if (null == pt) Utils.log("ProjectAttribute.setup: Object not found for id=" + id + " for ProjectAttribute of id " + this.id + " and title " + title);
					else this.object = pt;
				} else {
					// TODO: in the future, when attributes are used for metadata, this must be edited. TEMPORARY
				}
			}
		} catch (NumberFormatException nfe) {
			Utils.log("ProjectAttribute.setup: " + title + ": not an id number in object _|_" + object.toString() + "_|_");
			this.object = null; // so, empty
		} catch (Exception e) {
			IJError.print(e);
		}
	}

	public String getTitle() {
		return this.title;
	}

	public Thing getOwner() {
		return owner;
	}

	public void setObject(Object object) {
		this.object = object;
		updateInDatabase("object");
	}

	public Object getObject() {
		return this.object;
	}

	public String asXML() {
		String xml = title.replace(' ', '_') + "=\"";
		if (null == object) {
			// pass
		} else if (object instanceof ProjectThing) {
			xml += ((ProjectThing)object).getId();
		} else {
			xml += object.toString();
		}
		return xml + "\"";
	}

	public long getObjectId() {
		if (null == this.object) {
			return -1;
		}
		if (object instanceof ProjectThing) {
			return ((ProjectThing)object).getId();
		}

		// default
		return -1;
	}

	public String toString() {
		if (null == object) {
			return title + ": [empty]";
		}
		return title + ": " + object.toString();
	}

	/** Get a proper String representation of the object, for the database to store in a generic TEXT column. */
	public String getObjectString() {
		if (null == object) return "";
		if (object instanceof DBObject) return Long.toString(((DBObject)object).getId());
		// else, the object will know what it is.
		return object.toString();
	}
}
