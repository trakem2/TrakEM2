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

import java.util.ArrayList;

public interface Thing {

	public boolean canHaveAsChild(Thing thing);
	
	public String getType();

	public String getTitle();

	public String toString();

	public boolean hasChildren();

	public ArrayList<? extends Thing> getChildren();

	public Object getObject();

	public boolean addChild(Thing thing);

	public void setParent(Thing thing);

	public Thing getParent();

	public Thing findChild(Object ob);

	public void debug(String indent);

	public boolean isExpanded();

	public String getInfo();

	public Thing shallowCopy();
}
