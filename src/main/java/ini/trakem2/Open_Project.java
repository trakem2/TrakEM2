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

package ini.trakem2;

import ij.plugin.PlugIn;
//import ini.trakem2.utils.BigBrother;

public class Open_Project implements PlugIn {

	public void run(String arg) {
		//BigBrother bb = new BigBrother("TrakEM2");
		//new Thread(bb, "Open_Project_thread") {
		//	public void run() {
				Project.openFSProject(null);
		//	}
		//}.start();
		//
		// The big brother ThreadGroup approach makes the ij.Macro not find the proper arguments, so that XML projets are no londer readable from macro files. Plus I don't get for free all other threads to belong to this group.
	}
}
