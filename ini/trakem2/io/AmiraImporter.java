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
import ini.trakem2.utils.IJError;
import ini.trakem2.tree.Thing;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.AreaList;
import ini.trakem2.display.Layer;
import ini.trakem2.display.YesNoDialog;

import amira.*;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.OpenDialog;
import ij.io.FileInfo;
import ij.gui.ShapeRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Iterator;


/** Parses an amira labelfield and imports the labels as AreaList instances into the project tree.*/
public class AmiraImporter {

	/** Returns the array of AreaList or null if the file dialog is canceled. The xo,yo is the pivot of reference. */
	static public ArrayList importAmiraLabels(Layer first_layer, double xo, double yo, final String default_dir) {
		// open file
		OpenDialog od = new OpenDialog("Choose Amira Labels File", default_dir, "");
		String filename = od.getFileName();
		if (null == filename || 0 == filename.length()) return null;
		String path = od.getDirectory() + filename;
		AmiraMeshDecoder dec = new AmiraMeshDecoder();
		if (!dec.open(path)) {
			YesNoDialog yn = new YesNoDialog("Error", "File was not an Amira labels file.\nChoose another one?");
			if (yn.yesPressed()) return importAmiraLabels(first_layer, xo, yo, default_dir);
			return null;
		}
		ImagePlus imp = null;
		if (dec.isTable()) {
			Utils.showMessage("Select the other file (the labels)!");
			return null;
		} else {
			FileInfo fi = new FileInfo();
			fi.fileName = filename;
			fi.directory = od.getDirectory();
			imp = new ImagePlus("Amira", dec.getStack());
			dec.parameters.setParameters(imp);
		}
		return extractAmiraLabels(imp, dec.parameters, first_layer, xo, yo);
	}

	/** Returns an ArrayList containing all AreaList objects. The xo,yo is the pivot of reference. */
	static public ArrayList extractAmiraLabels(ImagePlus labels, AmiraParameters ap, Layer first_layer, double xo, double yo) {
		// adjust first layer thickness to be that of the pixelDepth
		/* // done at Loader.importStack
		boolean calibrate = true;
		if (first_layer.getParent().isCalibrated()) {
			YesNoDialog yn = new YesNoDialog("Calibration", "The layer set is already calibrated. Override with the Amira's stack calibration values?");
			if (!yn.yesPressed()) {
				calibrate = false;
			}
		}
		if (calibrate) {
			final Calibration cal = labels.getCalibration();
			final double depth = cal.pixelDepth / cal.pixelHeight; // assuming pixelWidth and pixelHeight to be the same
			first_layer.getParent().setCalibration(cal);
			first_layer.setThickness(depth);
			// TODO: need to think about a proper, integrated setup for Z and thickness regarding calibration.
		}
		*/
		String[] materials = ap.getMaterialList();
		// extract labels as ArrayList of Area
		ArrayList al_alis = new ArrayList();
		for (int i=0; i<materials.length; i++) {
			AmiraLabel label = new AmiraLabel();
			label.id = ap.getMaterialID(materials[i]);
			label.color = ap.getMaterialColor(label.id);
			label.name = ap.getMaterialName(label.id);
			Utils.log2("Processing label " + label.id + " " + label.name);
			if (label.name.equals("Exterior")) {
				Utils.log("Ignoring Amira's \"Exterior\" label");
				continue; // ignoring
			}
			label.al_areas = extractLabelAreas(label.id, labels.getStack());
			if (null == label.al_areas || 0 == label.al_areas.size()) continue;
			AreaList ali = createAreaList(label, first_layer);
			ali.translate(xo, yo, false);
			al_alis.add(ali);
		}
		return al_alis;
	}

	static private AreaList createAreaList(final AmiraLabel label, final Layer first_layer) {
		final AreaList ali = new AreaList(first_layer.getProject(), label.name, 0, 0);
		first_layer.getParent().add(ali);
		ali.setColor(new Color((float)label.color[0], (float)label.color[1], (float)label.color[2]));
		final double thickness = first_layer.getThickness();
		int i=1;
		for (Iterator it = label.al_areas.iterator(); it.hasNext(); ) {
			double z = first_layer.getZ() + (i-1) * thickness;
			Area area = (Area)it.next();
			Layer layer = first_layer;
			if (i > 1) layer = first_layer.getParent().getLayer(z, thickness, true); // will create new layer if not found
			// after creating the layer, avoid adding the area if empty
			if (!area.isEmpty()) {
				ali.setArea(layer.getId(), area);
			}
			// increase layer count
			i++;
		}
		ali.setTitle(label.name);
		// make all areas local to ali's x,y
		ali.calculateBoundingBox();

		return ali;
	}

	static private class AmiraLabel {
		ArrayList al_areas;
		String name;
		double[] color;
		int id;
	}

	/** One Area per slice, even if empty.
	 * @param label The label id or index, within 0-255 range.
	 * */
	static private ArrayList extractLabelAreas(final int label, final ImageStack stack) {
		try {
			final int size = stack.getSize();
			final ImagePlus tmp = new ImagePlus("", stack);
			//
			final ArrayList al_areas = new ArrayList();
			//
			for (int slice=1; slice<=size; slice++) {
				//
				tmp.setSlice(slice);
				final ImageProcessor ip = tmp.getProcessor();
				ip.setThreshold(label, label, ImageProcessor.NO_LUT_UPDATE);
				//
				final ThresholdToSelection tts = new ThresholdToSelection();
				tts.setup("", tmp);
				tts.run(ip);
				Roi roi = tmp.getRoi();
				if (null == roi) {
					al_areas.add(new Area()); // empty
					continue;
				}
				Rectangle bounds = roi.getBounds();
				if (0 == bounds.width || 0 == bounds.height) {
					al_areas.add(new Area()); // empty
					continue;
				} else if (!(roi instanceof ShapeRoi)) {
					roi = new ShapeRoi(roi);
				}
				Area area = new Area(Utils.getShape((ShapeRoi)roi));
				AffineTransform at = new AffineTransform();
				at.translate(bounds.x, bounds.y);
				area = area.createTransformedArea(at);
				al_areas.add(area);
			}
			return al_areas;
		} catch (Exception e) {
			IJError.print(e);
			return null;
		}
	}
}
