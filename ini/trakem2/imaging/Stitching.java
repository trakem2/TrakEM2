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

package ini.trakem2.imaging;

import java.awt.image.*;

import ij.process.ByteProcessor;

import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;

import java.io.*;


public class Stitching {


	private Stitching() {}

	/** A method that applies Euclidean distance to all possibly overlapping pixel pairs
	 *  corrects the score by the proportion of area actually used, aiming at promoting
	 *  as better larger overlaps.
	 *
	 * This is a brute-force approach, but computer time is cheap. Mine is gold.
	 */
	/* // it's wrong, needs revision or deletion. Nufer's method work fine when the overlap is 50% or less
	static public double[] crossCorrelateMaxOverlap(ByteProcessor bp1, ByteProcessor bp2) {
		// data
		final byte[] pix1 = (byte[])bp1.getPixels();
		final byte[] pix2 = (byte[])bp2.getPixels();
		// get statistical data
		final double m1 = average(pix1);
		final double m2 = average(pix2);
		// results
		double dx=0, dy=0;

		// move second image over first, with a minimal overlap of 1 pixel per side

		final int w1 = bp1.getWidth();
		final int h1 = bp1.getHeight();
		final int w2 = bp2.getWidth();
		final int h2 = bp2.getHeight();

		double max_overlap_area = (w1 < w2 ? w1 : w2 ) * (h1 < h2 ? h1 : h2);

		// ROIs to compare
		int rx1, ry1, rx2, ry2, rw, rh; // rw and rh are common

		double sum, tmp, val;

		double best = Double.MAX_VALUE;

		// Start by placing the second image top left of the first, overlapping only one pixel,
		// and iterate over all possibly overlapping positions
		for (int x=0; x<w1+w2-1; x++) {
			for (int y=0; y<h1+h2-1; y++) {
				// ROIs:
				rx1 = w1 -1 -x;
				ry1 = h1 -1 -y;
				//
				rw = x < w1 ? (x > w2 ? w2 : x) : w2 - x;
				rh = y < h1 ? (y > h2 ? h2 : y) : h2 - y;
				//
				rx2 = w2 - rw;
				ry2 = h2 - rh;
				//
				sum = 0;
				for (int rx=0; rx<rw; rx++) {
					for (int ry=0; ry<rh; ry++) {
						tmp =   ((pix1[(ry1 + ry)*w1 + rx1 + rx]&0xff) - m1)
						      - ((pix2[(ry2 + ry)*w2 + rx2 + rx]&0xff) - m2);
						sum +=  tmp * tmp; // sum of the square of the pixel differences
					}
				}
				// normalized value of the square differences
				val = sum / (rw*rh);
				// apply correction: make smaller areas score worse
				val = val * Math.sqrt((max_overlap_area / (rw*rh)));

				//
				if (val < best) {
					best = val;
					dx = x < w2 ? w2 - rx2 : rx2;
					dy = y < h2 ? h2 - ry2 : ry2;
				}
			}
		}
		return new double[]{dx, dy, best};
	}
	*/

	/** Returns an array of size 3, containing the new dx,dy of the second image relative to the 0,0 of the first, and the cross-correlation coefficient; memory efficient (and possibly speeded-up) version. @param bp1 is the template or base image, and @param bp2 is the moving image. */
	static public double[] crossCorrelate(ByteProcessor bp1, ByteProcessor bp2) {

		//debug(bp2);

		byte[] pix1 = (byte[])bp1.getPixels();
		byte[] pix2 = (byte[])bp2.getPixels();

		// take the short side, extract its proportion to 20x100 or 100x20 (the STRIPE and SNAP_WIDTH values in S. Nufer's implementation of the snapping)
		final int COUNT = (int)(((bp2.getWidth() * bp2.getHeight()) / 2000.0) * 1500);

		// get statistical data
		double m1 = average(pix1);
		double m2 = average(pix2);

		// vars
		boolean corronnewline = false;
		int i, j;
		double max_correlation_coeff = 0;
		int pos = 0;
		double s1 = 0, s2 = 0;
		double r1, r2;
		int w1 = bp1.getWidth();
		int w2 = bp2.getWidth();

		// Calculate the constant denominator
		for (i=0; i < pix1.length; i++) {
			s1 += ( (pix1[i]&0xff) - m1) * ( (pix1[i]&0xff) - m1);
		}
		for (i=0; i < pix2.length; i++) {
			s2 += ( (pix2[i]&0xff) - m2) * ( (pix2[i]&0xff) - m2);
		}
		double denom = Math.sqrt(s1 * s2);

		// Calculate the correlation of the two images
		// Method: calculate Euclidean distance between both images,
		//         for all possible translations of the second image on top of the first.
		//         Which means, sum all distances between paired pixels
		//         distance = sqrt((pixel1[x,y] - pixel2[x,y])^2)
		//
		double rowdiff;
		double sxy1;
		double sxy2;
		int col, row;

		for (int delay = -pix1.length; delay < pix1.length; delay++) {

			rowdiff = delay / w1;
			sxy1 = 0;
			sxy2 = 0;
			int count1 = COUNT; //1500; // 1500 is an optimal for images 20x100 or 100x20 (width*height)
			int count2 = COUNT; //1500;

			if (delay < 0) rowdiff--;

			for (i = 0; i < pix1.length; i++) {

				j = i - delay;

				col = j % w1;
				row = j / w1;
				if (row < 0) {
					col += w1;
					row -= 1;
				}
				if (j < 0) {
					i = delay - 1;
					continue;
				}

				j = row * w2 + col;

				if (j >= pix2.length) break;

				if (col >= w2) {
					i += w1 - col;
					continue;
				}

				if (rowdiff == i / w1 - row) {
					sxy1 += ( (pix1[i]&0xff) - m1) * ( (pix2[j]&0xff) - m2);
					count1++;
				} else {
					sxy2 += ( (pix1[i]&0xff) - m1) * ( (pix2[j]&0xff) - m2);
					count2++;
				}
			}

			sxy1 = Math.sqrt(sxy1 / count1);
			sxy2 = Math.sqrt(sxy2 / count2);

			r1 = sxy1 / denom;
			r2 = sxy2 / denom;

			if (r2 > max_correlation_coeff) {
				max_correlation_coeff = r2;
				pos = delay;
				corronnewline = true;
				// System.err.println(max_correlation_coeff);
			}
			if (r1 > max_correlation_coeff) {
				max_correlation_coeff = r1;
				pos = delay;
				corronnewline = false;
				// System.err.println(max_correlation_coeff);
			}
		}

		int dx = pos % w1;
		int dy = pos / w1;

		if (pos < 0) {
			dx += w1;
			dy -= 1;
		}

		if (corronnewline) {
			dx -= w1;
			dy++;
		}
		return new double[]{dx, dy, max_correlation_coeff};
	}

	/** Computes average by first shifting to range [0, 255] */
	static private final double average(final byte[] pix) {
		double m = 0;
		for (int i=0; i<pix.length; i++) {
			m += pix[i]&0xff;
		}
		//Utils.log2("average: " + (m / pix.length));
		return m / pix.length;
	}

	/*
	static private final double average(final byte[] pix1, final byte[] pix2) {
		double m1 = 0;
		double m2 = 0;
		int count = 0;
		for (int i=0; i<pix.length; i++) {
			if (-1 == pix[i]) {
				m += pix[i]&0xff;
				count++;
			}
		}
		//Utils.log2("average: " + (m / pix.length));
		return m / count;
	}
	*/

	static private void debug(final ByteProcessor bp) {
		// compare the pixels in the bp vs. in its image
		byte[] pix = (byte[])bp.getPixels();
		BufferedImage y_img = new BufferedImage(bp.getWidth(), bp.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);
		y_img.createGraphics().drawImage(bp.createImage(), 0, 0, null);

		int[] y_rgb_pixels = new int[y_img.getWidth() * y_img.getHeight()];
		int[] y_gray_pixels = new int[y_img.getWidth() * y_img.getHeight()];
		int w = y_img.getWidth();
		int h = y_img.getHeight();
		try {
		PixelGrabber pgother = new PixelGrabber(y_img, 0, 0, w, h, y_rgb_pixels, 0, w);
		pgother.grabPixels();
		} catch (Exception e) {}

		/* Calculate the mean intensity of the y_rgb_pixels */
		int red, green, blue;
		double my = 0;
		for (int i = 0; i < y_rgb_pixels.length; i++)
		{
			red = (y_rgb_pixels[i] >> 16) & 0xff;
			green = (y_rgb_pixels[i] >> 8) & 0xff;
			blue = (y_rgb_pixels[i]) & 0xff;
			y_gray_pixels[i] = red + green + blue; // no division by 3!?
			my += y_gray_pixels[i];
			//Utils.log2("byte: " + (pix[i]&0xff) + "  r,g,b : " + red + "," + green + "," + blue + " s: " + y_gray_pixels[i]);
		}
	}

	/** Returns an array of size 3, containing the new dx,dy of the second image relative to the 0,0 of the first, and the cross-correlation coefficient; code by Stephan Nufer at INI 2006. */
	static public double[] cross_correlate(BufferedImage x_img,
			BufferedImage y_img) throws InterruptedException
	{
		boolean corronnewline = false;
		int red, blue, green;
		double mx = 0, my = 0;
		int i, j;

		/* Prepare x image */
		int w = x_img.getWidth();
		int h = x_img.getHeight();
		int[] x_rgb_pixels = new int[x_img.getWidth() * x_img.getHeight()];
		int[] x_gray_pixels = new int[x_img.getWidth() * x_img.getHeight()];

		PixelGrabber pgbase = new PixelGrabber(x_img, 0, 0, w, h, x_rgb_pixels,
				0, w);

		pgbase.grabPixels();
		pgbase = null;

		/* Calculate the mean intensity of the x_rgb_pixels */
		for (i = 0; i < x_rgb_pixels.length; i++)
		{
			red = (x_rgb_pixels[i] >> 16) & 0xff;
			green = (x_rgb_pixels[i] >> 8) & 0xff;
			blue = (x_rgb_pixels[i]) & 0xff;
			x_gray_pixels[i] = red + green + blue;
			mx += x_gray_pixels[i];
		}
		mx /= x_gray_pixels.length;
		x_rgb_pixels = null;

		/* Prepare y image */
		w = y_img.getWidth();
		h = y_img.getHeight();
		int[] y_rgb_pixels = new int[y_img.getWidth() * y_img.getHeight()];
		int[] y_gray_pixels = new int[y_img.getWidth() * y_img.getHeight()];

		PixelGrabber pgother = new PixelGrabber(y_img, 0, 0, w, h,
				y_rgb_pixels, 0, w);

		pgother.grabPixels();

		/* Calculate the mean intensity of the y_rgb_pixels */
		for (i = 0; i < y_rgb_pixels.length; i++)
		{
			red = (y_rgb_pixels[i] >> 16) & 0xff;
			green = (y_rgb_pixels[i] >> 8) & 0xff;
			blue = (y_rgb_pixels[i]) & 0xff;
			y_gray_pixels[i] = red + green + blue; // no division by 3!?
			my += y_gray_pixels[i];
		}
		my /= y_gray_pixels.length;
		y_rgb_pixels = null;

		double max_correlation_coeff = 0;
		int pos = 0;
		double sx = 0, sy = 0;
		double r1, r2;
		double denom;
		w = x_img.getWidth();

		/* Calculate the constant denominator */
		for (i = 0; i < x_gray_pixels.length; i++)
		{
			sx += (x_gray_pixels[i] - mx) * (x_gray_pixels[i] - mx);
		}
		for (i = 0; i < y_gray_pixels.length; i++)
		{
			sy += (y_gray_pixels[i] - my) * (y_gray_pixels[i] - my);
		}
		denom = Math.sqrt(sx * sy);


		//Utils.log2("mx, my: " + mx/3 + "," + my/3);

		double rowdiff;
		double sxy1;
		double sxy2;
		int col, row;

		/* Calculate the correlation of the two images */
		for (int delay = -x_gray_pixels.length; delay < x_gray_pixels.length; delay++)
		{
			rowdiff = delay / w;
			sxy1 = 0;
			sxy2 = 0;
			int count1 = 1500;
			int count2 = 1500;

			if (delay < 0)
				rowdiff--;
			for (i = 0; i < x_gray_pixels.length; i++)
			{

				j = i - delay;

				col = j % w;
				row = j / w;
				if (row < 0)
				{
					col += w;
					row -= 1;
				}
				if (j < 0)
				{
					i = delay - 1;
					continue;
				}

				j = row * y_img.getWidth() + col;

				if (j >= y_gray_pixels.length)
					break;

				if (col >= y_img.getWidth())
				{
					i += x_img.getWidth() - col;
					continue;
				}

				if (rowdiff == i / w - row)
				{
					sxy1 += (x_gray_pixels[i] - mx) * (y_gray_pixels[j] - my);
					count1++;
				}
				else
				{
					sxy2 += (x_gray_pixels[i] - mx) * (y_gray_pixels[j] - my);
					count2++;
				}
			}

			sxy1 = Math.sqrt(sxy1 / count1);
			sxy2 = Math.sqrt(sxy2 / count2);

			r1 = sxy1 / denom;
			r2 = sxy2 / denom;

			if (r2 > max_correlation_coeff)
			{
				max_correlation_coeff = r2;
				pos = delay;
				corronnewline = true;
				// System.err.println(max_correlation_coeff);
			}
			if (r1 > max_correlation_coeff)
			{
				max_correlation_coeff = r1;
				pos = delay;
				corronnewline = false;
				// System.err.println(max_correlation_coeff);
			}
		}

		int x = pos % w;
		int y = pos / w;

		if (pos < 0)
		{
			x += w;
			y -= 1;
		}

		if (corronnewline)
		{
			x -= w;
			y++;
		}
		return new double[] { x, y, max_correlation_coeff };
	}

	static public double[] crossCorrelatePreibisch(final ByteProcessor bp1, final ByteProcessor bp2) {
		
		double relMinOverlap = 0.50;

		int w1 = bp1.getWidth();
		int w2 = bp2.getWidth();

		int h1 = bp1.getHeight();
		int h2 = bp2.getHeight();


		Utils.log2("bp1: " + w1 + "," + h1);
		Utils.log2("bp2: " + w2 + "," + h2);

		double maxR = -2;
		int displaceX = 0, displaceY = 0;

		int min_border_w = (int)(w1 < w2 ? w1*relMinOverlap : w2 * relMinOverlap);
		int min_border_h = (int)(h1 < h2 ? h1*relMinOverlap : h2 * relMinOverlap);

		Utils.log2("borders: " + min_border_w + " , " + min_border_h);
		PrintWriter fw = null;
		try {
			fw = new PrintWriter(new FileWriter("/home/albert/temp/cc2.txt"));
		} catch (Exception e) {}
 		StringBuffer sb = new StringBuffer("moveX\tmoveY\tcount\tR\n");

		for (int moveX = -w2 + min_border_w; moveX < w1 - min_border_w; moveX++)
			for (int moveY = -h2 + min_border_h; moveY < h1 - min_border_h; moveY++)
			{
				// compute average
				double avg1 = 0, avg2 = 0;
				int count = 0;

				// iterate over first image
				// and look what of the sec nd ON IS INside
				for (int x1 = moveX; x1 < w1; x1++)
					for (int y1 = moveY; y1 < h1; y1++)
					{
						int x2 = (moveX < 0 ? x1 + moveX : x1 - moveX);
						int y2 = (moveY < 0 ? y1 + moveY : y1 - moveY);

						if (x1 < 0 || y1 < 0 || x2 < 0 || x2 > w2-1 || y2 < 0 || y2 > h2-1)
							continue;

						if (bp1.getPixel(x1, y1) != -1 || bp2.getPixel(x2, y2) != -1)
						{
							avg1 += bp1.getPixel(x1, y1);
							avg2 += bp2.getPixel(x2, y2);
							count++;
						}
					}
				//if (0 == count) continue;

				avg1 /= (double)count;
				avg2 /= (double)count;

				double var1 = 0, var2 = 0;
				double coVar = 0;

				double dist1, dist2;

				// compute variances and co-variance
				for (int x1 = 0; x1 < w1; x1++)
					for (int y1 = 0; y1 < h1; y1++)
					{
						int x2 = (moveX < 0 ? x1 + moveX : x1 - moveX);
						int y2 = (moveY < 0 ? y1 + moveY : y1 - moveY);
						
						if (x2 < 0 || x2 > w2-1 || y2 < 0 || y2 > h2-1)
							continue;
						
						if (bp1.getPixel(x1, y1) != -1 || bp2.getPixel(x2, y2) != -1)
						{
							dist1 = bp1.getPixel(x1, y1) - avg1;
							dist2 = bp2.getPixel(x2, y2) - avg2;


							coVar += dist1 * dist2;
							var1 += Math.pow(dist1, 2);
							var2 += Math.pow(dist2, 2);
						}				
					}


				fw.println(moveX + " , " + moveY + " , " + avg1 + " , " + avg2);
				fw.flush();

				var1 /= (double)count;
				var2 /= (double)count;
				coVar /= (double)count;

				double stDev1 = Math.sqrt(var1);
				double stDev2 = Math.sqrt(var2);

				// compute correlation coeffienct		
				double R = coVar / (stDev1  * stDev2);
				
				if (R > maxR)
				{
					maxR = R;
					displaceX = moveX;
					displaceY = moveY;
				}

				sb.append(moveX).append('\t').append(moveY).append('\t').append(count).append('\t').append(R).append('\n');

				if (R < -1 || R > 1)
				{
					Utils.log2("BIG ERROR! R =" + R);
				}
			}
		Utils.saveToFile(new java.io.File("/home/albert/temp/cc.txt"), sb.toString());
		return new double[]{displaceX, displaceY, maxR};
	}

}
