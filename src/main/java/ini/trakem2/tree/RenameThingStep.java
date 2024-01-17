/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ini.trakem2.tree;

import ini.trakem2.display.Displayable;
import ini.trakem2.display.DoStep;

public class RenameThingStep implements DoStep {
	TitledThing thing;
	String title;
	RenameThingStep(TitledThing thing) {
		this.thing = thing;
		this.title = thing.getTitle();
	}
	public boolean apply(int action) {
		thing.setTitle(title);
		return true;
	}
	public boolean isEmpty() { return false; }
	public Displayable getD() { return null; }
	public boolean isIdenticalTo(Object ob) {
		if (!(ob instanceof RenameThingStep)) return false;
		RenameThingStep rn = (RenameThingStep) ob;
		return rn.thing == this.thing && rn.title == this.title;
	}
}
