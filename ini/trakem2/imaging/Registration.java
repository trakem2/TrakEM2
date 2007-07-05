/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.imaging;

import ini.trakem2.display.*;
import ini.trakem2.utils.*;
//import mpi.fruitfly.registration.*;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ColorProcessor;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Accessor methods to Stephan Preibisch's FFT-based registration implementation.
 * 
 * Preibisch's registration:
 * - returns angles between 0 and 180, perfectly reciprocal (img1 to img2 equals img2 to img1)
 * - is non-reciprocal (but almos) for translations (must choose between best)
 * - will only work reliably if there is at least 50% overlap between any two images to register
 *
 *
 * */
public class Registration {

	/** The result of registering two images. */
	static public class Data {
		/** the angle of rotation, in degrees*/
		int rot;
		/** the translation */
		double dx, dy;
	}

	/** Returns the Transform object that defines the registration of the second image to the first. Images will be duplicated and the originals provided will be left untouched. */
	static public Registration.Data register(final ImagePlus imp1, final ImagePlus imp2, final boolean rotate, final boolean fix_angle, final boolean ignore_squared_angles, final boolean enhance_edges) {


		// no need
		//final ImagePlus imp1 = new ImagePlus(imp_1.getTitle(), imp_1.getProcessor().duplicate());
		//final ImagePlus imp2 = new ImagePlus(imp_2.getTitle(), imp_2.getProcessor().duplicate());

		final Registration.Data data = new Registration.Data();

		/*
		 *

		// constants limiting exploration
		final int MAX1 = 5;

		if (rotate) {
			// Find possible rotations
			Rotation2D rot = new Rotation2D(imp1, imp2, 1, true, enhance_edges, false, false);
			double[] angles = rot.getAllAngles();
			// for each rotation, find possible translations
			for (int i=0; i<angles.length && i < MAX1; i++) { // best scoring angle is at index 0
				// ignore perfectly squared angles
				if (ignore_squared_angles && (0 == angles[i] || 90 == angles[i] || 180 == angles[i] || 270 == angles[i])) {
					Utils.log("Ignoring angle " + angles[i]);
					continue;
				}
				double gamma = angles[i];
				// translations for angle i and its pair
				final PhaseCorrelation2D translation1 = new PhaseCorrelation2D(imp1, makeRotatedCopy(imp2, gamma), true, true, false);
				final PhaseCorrelation2D translation2 = new PhaseCorrelation2D(imp1, makeRotatedCopy(imp2, gamma -180), true, true, false);
				///
				//
			}
		}

		*/

		// correct translation to make it relative to the image centers, not the top left corner of the rotated second image.

		return data;
	}

	/** Create a rotated version of the processor, within an enlarged canvas whose background is filled with zeros. */
	private ImageProcessor makeRotatedImage(final ImageProcessor ip, final double angle) {
		// new dimensions:
		final Transform t = Transform.createEmptyTransform();
		t.width = ip.getWidth();
		t.height = ip.getHeight();
		t.rot = angle;
		final Rectangle box = t.getBoundingBox(new Rectangle());
		final ImageProcessor ip2 = ip.createProcessor(box.width, box.height);
		if (ip2 instanceof ColorProcessor) {
			// ensure black background
			ip2.setValue(0);
			ip2.setRoi(0, 0, (int)Math.ceil(t.width), (int)Math.ceil(t.height));
			ip2.fill();
		}
		ip2.insert(ip, (int)((box.width - t.width) / 2), (int)((box.height - t.height) / 2));
		ip2.setBackgroundValue(0);
		ip2.rotate(angle);
		return ip2;
	}

	/** Try the registration from all 4 sides, and return the best. */
	static public Registration.Data register(final ImagePlus imp_1, final ImagePlus imp_2, final boolean rotate, final double percent_overlap) {
		// TODO
		return null;
	}

	/** Makes a snapshot with the Patch objects in both layers at the given scale, and rotates/translates all Displayable elements in the second Layer relative to the first. Returns the Transform needed to apply to layer2 objects to register it with layer1.*/
	static public boolean registerLayers(final Layer layer1, final Layer layer2, final double max_rot, final double max_displacement, final double scale, final boolean ignore_squared_angles, final boolean enhance_edges) {
		if (scale <= 0) return false;
		Registration.Data data = null;
		try {
			// get minimal enclosing boxes
			Rectangle box1 = layer1.getMinimalBoundingBox(Patch.class);
			if (null == box1) return false;
			Rectangle box2 = layer2.getMinimalBoundingBox(Patch.class);
			if (null == box2) return false;
			// get flat grayscale images, scaled
			ImagePlus imp1 = layer1.getProject().getLoader().getFlatImage(layer1, box1, scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
			ImagePlus imp2 = layer2.getProject().getLoader().getFlatImage(layer2, box2, scale, 0xFFFFFFFF, ImagePlus.GRAY8, Patch.class, true);
			// ready to start
			data = register(imp1, imp2, true, true, ignore_squared_angles, enhance_edges); // WARNING relative to the box1 and box2 centers!!
			// Grab all objects
			final Selection selection = new Selection(null);
			selection.selectAll(layer2);
			// rotate
			if (Math.abs(data.rot) <= Math.abs(max_rot)) {
				selection.setFloater(box2.x + box2.width/2, box2.y + box2.height/2);
				selection.rotate(data.rot);
			} else {
				// displacement makes no sense if rotation was not allowed, so recompute
				data = register(imp1, imp2, false, true, ignore_squared_angles, enhance_edges);
			}
			// correct scale
			if (1 != scale) {
				data.dx = data.dx / scale;
				data.dy = data.dy / scale;
			}
			// translate
			if (Math.sqrt(data.dx * data.dx + data.dy * data.dy) <= max_displacement) {
				selection.translate(data.dx, data.dy);
			}
			// done!
		} catch (Exception e) {
			new IJError(e);
			return false;
		}
		return true;
	}
}
