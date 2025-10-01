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

package ini.trakem2.io;

import java.io.File;
import java.io.FilenameFilter;

/** Accepts .dtd and/or .xml extensions. */
public class XMLFileFilter implements FilenameFilter {

	static public final int DTD = 0;
	static public final int XML = 1;
	static public final int BOTH = 2;
	private int ext = -1;

	public XMLFileFilter(int ext) {
		this.ext = ext;
	}
	public boolean accept(File dir, String name) {
		File file = new File(dir + "/" + name);
		if (file.isDirectory()) {
			return true;
		}
		name = name.toLowerCase();
		switch (ext) {
			case DTD:
				if (name.length() -4 == name.lastIndexOf(".dtd")) return true;
				break;
			case XML:
				if (name.length() -4 == name.lastIndexOf(".xml")) return true;
				break;
			case BOTH:
				if (name.length() -4 == name.lastIndexOf(".xml")
				|| name.length() -4 == name.lastIndexOf(".dtd")
				) {
					return true;
				}
				break;
		}
		return false;
	}
}

