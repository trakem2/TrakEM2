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

import ini.trakem2.persistence.DBObject;
import ini.trakem2.Project;


public class TemplateAttribute extends DBObject implements Attribute {

	private String title;
	private Object object;

	/** Construct a new attribute with the given title and the given object, which for example can be of class Thing. */
	public TemplateAttribute(String title, Object object) {
		super(null, -1);
		this.title = title;
		this.object = object;
	}

	/** Reconstruct a new attribute from the database. */
	public TemplateAttribute(String title, Object object, Project project, long id) {
		super(project, id);
		this.title = title;
		this.object = object;
	}

	/** WARNING: the object is not cloned. */
	public TemplateAttribute clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final TemplateAttribute copy = new TemplateAttribute(this.title, this.object, pr, nid);
		return copy;
	}

	public void addToDatabase(Project project) {
		this.project = project;
		this.id = project.getLoader().getNextId();
		super.addToDatabase();
	}

	public String getTitle() {
		return this.title;
	}

	public void setObject(Object object) {
		this.object = object;
	}

	public Thing getOwner() {
		return null; // stupid java
	}

	public Object getObject() {
		return this.object;
	}

	public long getObjectId() {
		// default
		return -1;
	}

	public String toString() {
		if (null == object) {
			return title + ": [empty]";
		}
		return title + ": " + object.toString();
	}

	public String getObjectString() { // dummy
		return "";
	}
}
