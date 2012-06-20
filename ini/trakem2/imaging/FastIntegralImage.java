package ini.trakem2.imaging;

/** Written following the code in ImgLib1's IntegralImage2 and ScaleAreaAveraging2d,
 * authored by Stephan Preibisch and Albert Cardona. 
 * 
 * @author Albert Cardona
 */
public final class FastIntegralImage
{
	/** Returns an image of @{param w}+1, @{param y}+1, where the first row and the first column are zeros,
	 * and the rest contain the sum of the area from 0,0 to that pixel in {@param b}.
	 * 
	 * @param b
	 * @param w
	 * @param h
	 * @return a double[] representing the integral image, with the first row and the first column with zeros.
	 */
	static public final double[] doubleIntegralImage(final byte[] b, final int w, final int h) {
		final int w2 = w+1;
		final int h2 = h+1;
		final double[] f = new double[w2 * h2];
		// Sum rows
		for (int y=0, offset1=0, offset2=w2+1; y<h; ++y) {
			double s = b[offset1] & 0xff;
			f[offset2] = s;
			for (int x=1; x<w; ++x) {
				s += b[offset1 + x] & 0xff;
				f[offset2 + x] = s;
			}
			offset1 += w;
			offset2 += w2;
		}
		// Sum columns over the summed rows
		for (int x=1; x<w2; ++x) {
			 double s = 0;
			 for (int y=1, i=w2+x; y<h2; ++y) {
				 s += f[i];
				 f[i] = s;
				 i += w2;
			 }
		}

		return f;
	}
	
	/** Returns an image of @{param w}+1, @{param y}+1, where the first row and the first column are zeros,
	 * and the rest contain the sum of the area from 0,0 to that pixel in {@param b}.
	 * 
	 * @param b
	 * @param w
	 * @param h
	 * @return a float[] representing the integral image, with the first row and the first column with zeros.
	 */
	static public final long[] longIntegralImage(final byte[] b, final int w, final int h) {
		final int w2 = w+1;
		final int h2 = h+1;
		final long[] f = new long[w2 * h2];
		// Sum rows
		for (int y=0, offset1=0, offset2=w2+1; y<h; ++y) {
			long s = b[offset1] & 0xff;
			f[offset2] = s;
			for (int x=1; x<w; ++x) {
				s += b[offset1 + x] & 0xff;
				f[offset2 + x] = s;
			}
			offset1 += w;
			offset2 += w2;
		}
		// Sum columns over the summed rows
		for (int x=1; x<w2; ++x) {
			 long s = 0;
			 for (int y=1, i=w2+x; y<h2; ++y) {
				 s += f[i];
				 f[i] = s;
				 i += w2;
			 }
		}

		return f;
	}

	static public final long[] longIntegralImage(final short[] b, final int w, final int h) {
		final int w2 = w+1;
		final int h2 = h+1;
		final long[] f = new long[w2 * h2];
		// Sum rows
		for (int y=0, offset1=0, offset2=w2+1; y<h; ++y) {
			long s = b[offset1] & 0xffff;
			f[offset2] = s;
			for (int x=1; x<w; ++x) {
				s += b[offset1 + x] & 0xffff;
				f[offset2 + x] = s;
			}
			offset1 += w;
			offset2 += w2;
		}
		// Sum columns over the summed rows
		for (int x=1; x<w2; ++x) {
			 long s = 0;
			 for (int y=1, i=w2+x; y<h2; ++y) {
				 s += f[i];
				 f[i] = s;
				 i += w2;
			 }
		}

		return f;
	}

	/** For testing. */
	static public final void main(String[] args) {
		{
			// Test float[] integral image:
			byte[] b = new byte[3 * 3];
			for (int i=0; i<b.length; ++i) b[i] = 1;
			double[] f = doubleIntegralImage(b, 3, 3);
			System.out.println("IntegralImage is correct: " + (9 == f[f.length-1]));
			System.out.println(ini.trakem2.utils.Utils.toString(f));

			// Test scaleAreaAverage with integer division
			ij.process.ByteProcessor bp = (ij.process.ByteProcessor) ij.IJ.openImage("/home/albert/Desktop/t2/test-mipmaps/src.tif").getProcessor();
			byte[] pix = (byte[]) bp.getPixels();
			double[] fii = doubleIntegralImage(pix, bp.getWidth(), bp.getHeight());
			byte[] scaled = scaleAreaAverage(fii, bp.getWidth()+1, bp.getHeight()+1, bp.getWidth()/4, bp.getHeight()/4);
			new ij.ImageJ();
			final float[] fpix = new float[fii.length];
			for (int i=0; i<fpix.length; ++i) fpix[i] = (float)fii[i];
			new ij.ImagePlus("integral", new ij.process.FloatProcessor(bp.getWidth()+1, bp.getHeight()+1, fpix, null)).show();
			new ij.ImagePlus("scaled", new ij.process.ByteProcessor(bp.getWidth()/4, bp.getHeight()/4, scaled, null)).show();
		}
		{
			// Test long[] integral image:
			byte[] b = new byte[3 * 3];
			for (int i=0; i<b.length; ++i) b[i] = 1;
			long[] f = longIntegralImage(b, 3, 3);
			System.out.println("IntegralImage is correct: " + (9 == f[f.length-1]));
			System.out.println(ini.trakem2.utils.Utils.toString(f));

			// Test scaleAreaAverage with integer division
			ij.process.ByteProcessor bp = (ij.process.ByteProcessor) ij.IJ.openImage("/home/albert/Desktop/t2/test-mipmaps/src.tif").getProcessor();
			byte[] pix = (byte[]) bp.getPixels();
			long[] lii = longIntegralImage(pix, bp.getWidth(), bp.getHeight());
			byte[] scaled = scaleAreaAverage(lii, bp.getWidth()+1, bp.getHeight()+1, bp.getWidth()/4, bp.getHeight()/4);
			new ij.ImageJ();
			final float[] fpix = new float[lii.length];
			for (int i=0; i<fpix.length; ++i) fpix[i] = (float)lii[i];
			new ij.ImagePlus("integral", new ij.process.FloatProcessor(bp.getWidth()+1, bp.getHeight()+1, fpix, null)).show();
			new ij.ImagePlus("scaled", new ij.process.ByteProcessor(bp.getWidth()/4, bp.getHeight()/4, scaled, null)).show();
		}
		
		// Non-integer version tested by commenting out the integer version.
	}
	
	
	/**
	 * 
	 * @param f  The pixels of the integral image.
	 * @param fw The width of the integral image (source image width + 1)
	 * @param fh The height of the integral image (source image height + 1)
	 * @param tw The target width to scale to.
	 * @param th The target height to scale to.
	 * @return The pixels of the scaled image.
	 */
	static public final byte[] scaleAreaAverage(
			final double[] f, final int fw, final int fh,
			final int tw, final int th) {
		final byte[] b = new byte[tw * th];
		
		if (0 == (fw -1) % tw && 0 == (fh -1) % th) {
			// Integer division
			final int stepSizeX = (fw -1) / tw;
			final int stepSizeY = (fh -1) / th;
			final int area = stepSizeX * stepSizeY;
			
			int startY = 0;
			int o3 = 0;
			
			for (int y=0; y<th; ++y) {
				/*
				final int startY = y * stepSizeY;
				for (int x=0; x<tw; ++x) {
					int startX = x * stepSizeX;
					float sum =   f[startY * fw + startX]
							    - f[startY * fw + startX + stepSizeX]
							    + f[(startY + stepSizeY) * fw + startX + stepSizeX]
							    - f[(startY + stepSizeY) * fw + startX];
					b[y * tw + x] = (byte)(sum / area + 0.5f);
				}
				*/
				// Same as above without repeated operations and with additions instead of multiplications
				int startX = 0;
				final int o1 = startY * fw,
				          o2 = (startY + stepSizeY) * fw;
				for (int x=0; x<tw; ++x) {
					b[o3 + x] = (byte)((  f[o1 + startX]
							            - f[o1 + startX + stepSizeX]
							            + f[o2 + startX + stepSizeX]
							            - f[o2 + startX]) / area + 0.5f);
					startX += stepSizeX;
				}
				startY += stepSizeY;
				o3 += tw;
			}
			
		} else {
			// Non-integer division
			final double stepSizeX = ((double)fw -1) / (double)tw;
			final double stepSizeY = ((double)fh -1) / (double)th;
			//
			double tmp2 = 0.5;
			int o3 = 0;
			//
			for (int y=0; y<th; ++y) {
				//final double tmp2 = y * stepSizeY + 0.5;
				final int startY = (int)(tmp2);
				final int vY = (int)(tmp2 + stepSizeY) - startY;
				//
				final int o1 = startY * fw;
				final int o2 = (startY + vY) * fw;
				//
				double tmp1 = 0.5;
				for (int x=0; x<tw; ++x) {
					//final double tmp1 = x * stepSizeX + 0.5;
					final int startX = (int)(tmp1);
					final int vX = (int)(tmp1 + stepSizeX) - startX;

					/*
					final double area = vX * vY;

					double sum =   f[startY * fw + startX]
							     - f[startY * fw + startX + vX]
							     + f[(startY + vY) * fw + startX + vX]
							     - f[(startY + vY) * fw + startX];
					b[y * tw + x] = (byte)(sum / area + 0.5);
					*/
					// Same as above, less operations and variables
					b[o3 + x] = (byte)((  f[o1 + startX]
							            - f[o1 + startX + vX]
							            + f[o2 + startX + vX]
							            - f[o2 + startX]) / (vX * vY) + 0.5);
					//
					tmp1 += stepSizeX;
				}
				//
				tmp2 += stepSizeY;
				o3 += tw;
			}
		}
		
		return b;
	}
	
	/**
	 * 
	 * @param f  The pixels of the integral image.
	 * @param fw The width of the integral image (source image width + 1)
	 * @param fh The height of the integral image (source image height + 1)
	 * @param tw The target width to scale to.
	 * @param th The target height to scale to.
	 * @return The pixels of the scaled image.
	 */
	static public final byte[] scaleAreaAverage(
			final long[] f, final int fw, final int fh,
			final int tw, final int th) {
		final byte[] b = new byte[tw * th];
		
		if (0 == (fw -1) % tw && 0 == (fh -1) % th) {
			// Integer division
			final int stepSizeX = (fw -1) / tw;
			final int stepSizeY = (fh -1) / th;
			final int area = stepSizeX * stepSizeY;
			
			int startY = 0;
			int o3 = 0;
			
			for (int y=0; y<th; ++y) {
				/*
				final int startY = y * stepSizeY;
				for (int x=0; x<tw; ++x) {
					int startX = x * stepSizeX;
					float sum =   f[startY * fw + startX]
							    - f[startY * fw + startX + stepSizeX]
							    + f[(startY + stepSizeY) * fw + startX + stepSizeX]
							    - f[(startY + stepSizeY) * fw + startX];
					b[y * tw + x] = (byte)(sum / area + 0.5f);
				}
				*/
				// Same as above without repeated operations and with additions instead of multiplications
				int startX = 0;
				final int o1 = startY * fw,
				          o2 = (startY + stepSizeY) * fw;
				for (int x=0; x<tw; ++x) {
					b[o3 + x] = (byte)((  f[o1 + startX]
							            - f[o1 + startX + stepSizeX]
							            + f[o2 + startX + stepSizeX]
							            - f[o2 + startX]) / area + 0.5f);
					startX += stepSizeX;
				}
				startY += stepSizeY;
				o3 += tw;
			}
			
		} else {
			// Non-integer division
			final double stepSizeX = ((double)fw -1) / (double)tw;
			final double stepSizeY = ((double)fh -1) / (double)th;
			//
			double tmp2 = 0.5;
			int o3 = 0;
			//
			for (int y=0; y<th; ++y) {
				//final double tmp2 = y * stepSizeY + 0.5;
				final int startY = (int)(tmp2);
				final int vY = (int)(tmp2 + stepSizeY) - startY;
				//
				final int o1 = startY * fw;
				final int o2 = (startY + vY) * fw;
				//
				double tmp1 = 0.5;
				for (int x=0; x<tw; ++x) {
					//final double tmp1 = x * stepSizeX + 0.5;
					final int startX = (int)(tmp1);
					final int vX = (int)(tmp1 + stepSizeX) - startX;
					
					/*
					final double area = vX * vY;
					
					double sum =   f[startY * fw + startX]
							     - f[startY * fw + startX + vX]
							     + f[(startY + vY) * fw + startX + vX]
							     - f[(startY + vY) * fw + startX];
					b[y * tw + x] = (byte)(sum / area + 0.5);
					*/
					// Same as above, less operations and variables
					b[o3 + x] = (byte)((  f[o1 + startX]
							            - f[o1 + startX + vX]
							            + f[o2 + startX + vX]
							            - f[o2 + startX]) / (vX * vY) + 0.5);
					//
					tmp1 += stepSizeX;
				}
				//
				tmp2 += stepSizeY;
				o3 += tw;
			}
		}
		
		return b;
	}
}
