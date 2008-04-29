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

package ini.trakem2.utils;

import java.awt.Point;
import java.awt.dnd.*;
import java.awt.datatransfer.*;
import java.io.File;
import java.io.StringReader;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import ij.gui.YesNoCancelDialog;
import ij.gui.GenericDialog;
import ij.io.*;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ini.trakem2.imaging.VirtualStack; //import ij.VirtualStack; // only after 1.38q
import ini.trakem2.display.*;
import ini.trakem2.*;
import ini.trakem2.persistence.*;


public class DNDInsertImage implements DropTargetListener {

	private Display display;
	private DropTarget dt;

	public DNDInsertImage(Display display) {
		this.display = display;
		this.dt = new DropTarget(display.getCanvas(), this);
	}

	public void destroy() {
		// is there any way to really destroy it?
		dt.setActive(false);
		display.getCanvas().setDropTarget(null);
		dt.setComponent(null);
	}

	public void dragEnter(DropTargetDragEvent e) {
		e.acceptDrag(DnDConstants.ACTION_COPY);
	}
	public void dragExit(DropTargetEvent e) {}
	public void dragOver(DropTargetDragEvent e) {}
	public void dropActionChanged(DropTargetDragEvent e) {}
	public void drop(final DropTargetDropEvent dtde)  {
		if (!display.getProject().isInputEnabled()) return;

		try {

			dtde.acceptDrop(DnDConstants.ACTION_COPY);
			Point point = dtde.getLocation();
			point.x = display.getCanvas().offScreenX(point.x);
			point.y = display.getCanvas().offScreenY(point.y);

			Transferable t = dtde.getTransferable();
			DataFlavor[] flavors = t.getTransferDataFlavors();
			int success = 0;
			for (int i=0; i<flavors.length; i++) {
				if (!flavors[i].getRepresentationClass().equals(String.class)) continue;
				Object ob = t.getTransferData(flavors[i]);
				if (!(ob instanceof String)) continue;
				String s = ob.toString().trim();

				BufferedReader br = new BufferedReader(new StringReader(s));
				String tmp;
				while (null != (tmp = br.readLine())) {
					tmp = java.net.URLDecoder.decode(tmp, "UTF-8");
					if (tmp.startsWith("file://")) {
						tmp = tmp.substring(7);
					}
					File f = new File(tmp);
					if (importImageFile(f, tmp, point)) success++;
				}
				break;
			}
			if (0 == success && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
				// from ij.plugin.DragAndDrop class by Wayne Rasband
				Object data = t.getTransferData(DataFlavor.javaFileListFlavor);
				Iterator iterator = ((List)data).iterator();
				while(iterator.hasNext()) {
					File f = (File)iterator.next();
					String path = f.getCanonicalPath().replace('\\', '/');
					importImageFile(f, path, point);
				}
			}
			dtde.dropComplete(true);

		} catch (Exception e) {
			dtde.dropComplete(false);
		}
	}

	private boolean importImageFile(File f, String path, Point point) throws Exception {
		if (f.exists()) {
			if (f.isDirectory()) {
				// ask:
				GenericDialog gd = new GenericDialog("Import directory");
				String[] choice = new String[]{"Stack", "Grid", "Sequence as grid"};
				gd.addChoice("Directory as: ", choice, choice[0]);
				gd.showDialog();
				if (gd.wasCanceled()) {
					return true; // the user cancel it, so all is ok.
				}
				switch (gd.getNextChoiceIndex()) {
				case 0: // as stack
					// if importing image sequence as a stack:
					String[] names = f.list();
					int max_len = 0;
					// fake natural sorting: pre-pad short names with zeros
					for (int i=0; i<names.length; i++) {
						if (names[i].length() > max_len) max_len = names[i].length();
					}
					for (int i=0; i<names.length; i++) {
						while (names[i].length() < max_len) names[i] = "0" + names[i];
					}
					Utils.log2("stack size: " + names.length);
					for (int i=0; i<names.length; i++) {
						Utils.log2(names[i]);
					}
					Arrays.sort(names);
					VirtualStack stack = new VirtualStack(10, 10, null, f.getAbsolutePath().replace('\\', '/')); // I don't care about the dimensions
					for (int k=0; k<names.length; k++) {
						IJ.redirectErrorMessages();
						if (names[k].toLowerCase().endsWith(".xml")) continue; // ignore trakem2 files
						stack.addSlice(names[k]);
					}
					if (stack.getSize() > 0) {
						display.getProject().getLoader().importStack(display.getLayer(), new ImagePlus("stack", stack), true, path);
					}
					break;
				case 1: // as grid
					display.getProject().getLoader().importGrid(display.getLayer(), path);
					break;
				case 2: // sequence as grid
					display.getProject().getLoader().importSequenceAsGrid(display.getLayer(), path);
					break;
				}
			} else {
				// single image file (single image or a stack)
				display.getProject().getLoader().importImage(display.getLayer(), point.x, point.y, path);
			}
			return true;
		} else {
			Utils.log("File not found: " + path);
			return false;
		}
	}
}
