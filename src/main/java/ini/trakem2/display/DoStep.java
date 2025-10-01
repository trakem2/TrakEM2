/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2025 Albert Cardona, Stephan Saalfeld and others.
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
package ini.trakem2.display;

public interface DoStep {

	// The actions accepted by apply(int)
	static public final int UNDO = 0;
	static public final int REDO = 1;

	/** Returns true on success. */
	public boolean apply(int action);

	public boolean isEmpty();

	/** May return null. */
	public Displayable getD();

	public boolean isIdenticalTo(Object ob); // bypassing equals
}
