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
package ini.trakem2.scripting;

import ij.IJ;
import ij.ImagePlus;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.IJError;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class PatchScript {

	static private Method m = null;

	/** Run the script at path on the ImagePlus of patch. */
	static public void run(final Patch patch, final ImagePlus imp, final String path) {
		try {
			HashMap<String,Object> vars = new HashMap<String,Object>();
			vars.put("patch", patch);
			vars.put("imp", imp);
			if (null == m) {
				Class<?> c = Class.forName("common.ScriptRunner");
				m = c.getDeclaredMethod("run", String.class, Map.class);
			}
			m.invoke(null, (IJ.isWindows() ? path.replace('/', '\\') : path), vars);
		} catch (Exception e) {
			IJError.print(e);
		}
	}
}
