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

package ini.trakem2.persistence;

import ini.trakem2.Project;
import ini.trakem2.utils.Utils;

import java.util.Set;

/** Base class of all objects that can be saved in a database or XML file.<br />
 *  Methods to add to, update in and remove from a database are called anyway for XML projects,
 *  and can thus be used to perform tasks on updating a specific object.
 *
 */
public class DBObject {

	protected long id;
	protected Project project;

	/** Create new and later add it to the database. */
	public DBObject(Project project) {
		this.project = project;
		this.id = project.getLoader().getNextId();
	}

	/** Reconstruct from database. */
	public DBObject(Project project, long id) {
		this.project = project;
		this.id = id;
	}

	/** For the Project */
	public DBObject(Loader loader) {
		this.id = loader.getNextId();
	}

	public final long getId() { return id; }
	
	/**
	 *  Create a unique String identifier for this object instance.
	 *  
	 *  TODO
	 *    The default implementation returns the project-specific id.
	 *    This behaviour has to be overridden in order to get an identifier
	 *    that is unique beyond the project scope, e.g. for use in cache file
	 *    names.
	 *    
	 * @return Unique name
	 */
	public String getUniqueIdentifier()
	{
		return Long.toString(this.id);
	}

	public final Project getProject() { return project; }

	public boolean addToDatabase() {
		return project.getLoader().addToDatabase(this);
	}

	public boolean updateInDatabase(String key) {
		return project.getLoader().updateInDatabase(this, key);
	}
	public boolean updateInDatabase(Set<String> keys) {
		return project.getLoader().updateInDatabase(this, keys);
	}

	public boolean removeFromDatabase() {
		return project.getLoader().removeFromDatabase(this);
	}

	/** Subclasses can override this method to perform other tasks before removing itself from the database. */
	public boolean remove(boolean check) {
		if (check && !Utils.check("Really remove " + this.toString() + " ?")) return false;
		return removeFromDatabase();
	}

	/** Subclasses can override this method to store the instance as XML. */
	public void exportXML(StringBuilder sb_body, String indent, XMLOptions options) {
		Utils.log("ERROR: exportXML not implemented for " + getClass().getName());
	}

	/** Sublcasses can override this method to provide a proper String, otherwise calls toString()*/
	public String getTitle() {
		return this.toString();
	}

	/** Sublcasses can override this method to provide a proper String, otherwise calls getTitle() */
	public String getShortTitle() {
		return getTitle();
	}

	/** Returns id and project name; this method is meant to be overriden by any of the subclasses. */
	public String getInfo() {
		return "Class: " + this.getClass().getName() + "\nID: " + this.id + "\nFrom:\n" + this.project.getInfo();
	}
}
