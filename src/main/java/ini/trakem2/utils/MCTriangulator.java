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
package ini.trakem2.utils;

import java.util.List;

import org.scijava.vecmath.Point3f;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij3d.Volume;
import isosurface.Triangulator;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.type.numeric.RealType;
import vib.NaiveResampler;

public class MCTriangulator implements Triangulator {

	@Override
	public List getTriangles(ImagePlus image, final int threshold,
					final boolean[] channels, final int resamplingF) {

		if(resamplingF != 1)
			image = NaiveResampler.resample(image, resamplingF);
		// There is no need to zero pad any more. MCCube automatically
		// scans one pixel more in each direction, assuming a value
		// of zero outside the image.
		// zeroPad(image);
		// create Volume
		final Volume volume = new Volume(image, channels);
		volume.setAverage(true);

		// get triangles
		final List l = MCCube.getTriangles(volume, threshold);
		return l;
	}

	/**
	 * @param img The {@code Image<? extends RealType>} instance to use.
	 * @param threshold The cut-off (inclusive) of pixel values considered inside.
	 * @param origin The translation of the origin, in 3D.
	 */
	public<T extends RealType<T>> List<Point3f> getTriangles(final Image<T> img, final int threshold, final float[] origin) throws Exception {
		return MCCube.getTriangles(new ImgLibVolume(img, origin), threshold);
	}

	static public void zeroPad(final ImagePlus imp) {
		final ImageStack stack = imp.getStack();
		final int w = stack.getWidth();
		final int h = stack.getHeight();
		final int d = stack.getSize();
		final int type = imp.getType();
		// create new stack
		final ImageStack st = new ImageStack(w+2, h+2);

		// retrieve 1st processor
		ImageProcessor old = stack.getProcessor(1);

		// enlarge it and add it as a first slide.
		ImageProcessor ne = createProcessor(w+2, h+2, type);
		st.addSlice("", ne);

		// now do the same for all slices in the old stack
		for(int z = 0; z < d; z++) {
			old = stack.getProcessor(z+1);
			ne = createProcessor(w+2, h+2, type);
			ne.insert(old, 1, 1);
			st.addSlice(Integer.toString(z+1), ne);
		}

		// now add an empty new slice
		ne = createProcessor(w+2, h+2, type);
		st.addSlice(Integer.toString(d+1), ne);

		imp.setStack(null, st);

		// update the origin
		final Calibration cal = imp.getCalibration();
		cal.xOrigin -= cal.pixelWidth;
		cal.yOrigin -= cal.pixelHeight;
		cal.zOrigin -= cal.pixelDepth;
		imp.setCalibration(cal);
	}

	private static final ImageProcessor createProcessor(
					final int w, final int h, final int type) {
		if(type == ImagePlus.COLOR_RGB)
			return new ColorProcessor(w, h);
		return new ByteProcessor(w, h);
	}
}
