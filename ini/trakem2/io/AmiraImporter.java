/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.io;

import ini.trakem2.utils.Utils;

import amira.*;
import ij.ImagePlus;
import ij.io.OpenDialog;
import ij.io.FileInfo;


/** Parses an amira labelfield and imports the labels as AreaList instances in the project tree.
 *
 *
 */
public class AmiraImporter {

	static public void importAmiraFile() {
		// open file
		OpenDialog od = new OpenDialog("Choose Amira file", "");
		String filename = od.getFileName();
		if (null == filename || 0 == filename.length()) return;
		String path = od.getDirectory() + filename;
		AmiraMeshDecoder dec = new AmiraMeshDecoder();
		if (!dec.open(path)) {
			Utils.log2("AmiraMeshDecoder failed with path " + path);
			return;
		}
		ImagePlus imp = null;
		if (dec.isTable()) {
			Utils.log2("Select the other file!");
			return;
		} else {
			FileInfo fi = new FileInfo();
			fi.fileName = filename;
			fi.directory = od.getDirectory();
			imp = new ImagePlus("Amira", dec.getStack());
			dec.parameters.setParameters(imp);
		}
		// read its labels
		AmiraParameters ap = dec.parameters;
		String[] materials = ap.getMaterialList();
		for (int i=0; i<materials.length; i++) {
			int mid = ap.getMaterialID(materials[i]);
			double[] color = ap.getMaterialColor(mid);
			String name = ap.getMaterialName(mid);
		}
	}
}
