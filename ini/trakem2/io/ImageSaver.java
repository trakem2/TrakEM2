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

import ij.ImagePlus;
import ij.ImageJ;
import ij.process.*;
import ij.gui.*;
import ij.io.*;
import ij.measure.Calibration;
import com.sun.image.codec.jpeg.*;
import java.awt.image.*;
import java.awt.Graphics;
import java.awt.Image;
import java.io.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

/** Provides the necessary thread-safe image file saver utilities. */
public class ImageSaver {

	private ImageSaver() {}

	/** Will create parent directories if they don't exist.<br />
	 *  Returns false if the path is unusable.
	 */
	static private final boolean checkPath(final String path) {
		if (null == path) {
			Utils.log("Null path, can't save.");
			return false;
		}
		File fdir = new File(path).getParentFile();
		if (!fdir.exists()) {
			try {
				fdir.mkdirs();
			} catch (Exception e) {
				new IJError(e);
				Utils.log("Can't use path: " + path);
				return false;
			}
		}
		return true;
	}

	/** Returns true on success.<br />
	 *  Core functionality adapted from ij.plugin.JpegWriter class by Wayne Rasband.
	 */
	static public final boolean saveAsJpeg(final ImageProcessor ip, final String path, float quality, boolean as_grey) {
		// safety checks
		if (null == ip) {
			Utils.log("Null ip, can't saveAsJpeg");
			return false;
		}
		if (!checkPath(path)) return false;
		if (quality < 0f) quality = 0f;
		if (quality > 1f) quality = 1f;
		// ok, onward
		// No need to make an RGB int[] image if a byte[] image with a LUT will do.
		/*
		int image_type = BufferedImage.TYPE_INT_ARGB;
		if (ip.getClass().equals(ByteProcessor.class) || ip.getClass().equals(ShortProcessor.class) || ip.getClass().equals(FloatProcessor.class)) {
			image_type = BufferedImage.TYPE_BYTE_GRAY;
		}
		*/
		try {
			BufferedImage bi = null;
			if (as_grey) { // even better would be to make a raster directly from the byte[] array, and pass that to the encoder
				bi = new BufferedImage(ip.getWidth(), ip.getHeight(), BufferedImage.TYPE_BYTE_INDEXED, (IndexColorModel)ip.getColorModel());
			} else {
				bi = new BufferedImage(ip.getWidth(), ip.getHeight(), BufferedImage.TYPE_INT_RGB);
			}
			final FileOutputStream f = new FileOutputStream(path);
			final Graphics g = bi.createGraphics();
			g.drawImage(ip.createImage(), 0, 0, null);
			g.dispose();
			final JPEGImageEncoder encoder = JPEGCodec.createJPEGEncoder(f);
			final JPEGEncodeParam param = encoder.getDefaultJPEGEncodeParam(bi);
			param.setQuality(quality, true);
			encoder.encode(bi, param);
			f.close();
		} catch (Exception e) {
			new IJError(e);
			return false;
		}
		return true;
	}

	/** Open a jpeg image that is known to be grayscale.<br />
	 *  This method avoids having to open it as int[] (4 times as big!) and then convert it to grayscale by looping through all its pixels and comparing if all three channels are the same (which, least you don't know, is what ImageJ 139j and before does).
	 */
	static public final BufferedImage openGreyJpeg(final String path) {
		return openJpeg(path, JPEGDecodeParam.COLOR_ID_GRAY);
	}

	/* // ERROR:
	 * java.lang.IllegalArgumentException: NumComponents not in sync with COLOR_ID
	 * uh?
	 */
	/*
	static public final BufferedImage openColorJpeg(final String path) throws Exception {
		return openJpeg(path, JPEGDecodeParam.COLOR_ID_RGB);
	}
	*/

	static private final BufferedImage openJpeg(final String path, final int color_id) {
		try {
			return openJpeg2(path, color_id);
		} catch (FileNotFoundException fnfe) {
			return null;
		} catch (Exception e) {
			Utils.log2("JPEG Decoder failed for " + path);
			// the file might have been generated while trying to read it. So try once more
			try {
				Thread.sleep(50);
				return openJpeg2(path, color_id);
			} catch (Exception e2) {
				new IJError(e2);
			}
			return null;
		}
	}

	static private final BufferedImage openJpeg2(final String path, final int color_id) throws Exception {
		if (!new File(path).exists()) return null;
		FileInputStream f = new FileInputStream(path);
		final JPEGImageDecoder decoder = JPEGCodec.createJPEGDecoder(f, JPEGCodec.getDefaultJPEGEncodeParam(1, color_id));
		final BufferedImage bi = decoder.decodeAsBufferedImage();
		f.close();
		return bi;
	}

	/** Returns true on success.<br />
	 *  Core functionality adapted from ij.io.FileSaver class by Wayne Rasband.
	 */
	static public final boolean saveAsZip(final ImagePlus imp, String path) {
		// safety checks
		if (null == imp) {
			Utils.log("Null imp, can't saveAsZip");
			return false;
		}
		if (!checkPath(path)) return false;
		// ok, onward:
		FileInfo fi = imp.getFileInfo();
		if (!path.endsWith(".zip")) path = path+".zip";
		String name = imp.getTitle();
		if (name.endsWith(".zip")) name = name.substring(0,name.length()-4);
		if (!name.endsWith(".tif")) name = name+".tif";
		fi.description = ImageSaver.getDescriptionString(imp, fi);
		Object info = imp.getProperty("Info");
		if (info!=null && (info instanceof String))
			fi.info = (String)info;
		fi.sliceLabels = imp.getStack().getSliceLabels();
		try {
			ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path));
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(zos));
        	zos.putNextEntry(new ZipEntry(name));
			TiffEncoder te = new TiffEncoder(fi);
			te.write(out);
			out.close();
		}
		catch (IOException e) {
			new IJError(e);
			return false;
		}
		return true;
	}

	/** Returns a string containing information about the specified  image. */
	static public final String getDescriptionString(final ImagePlus imp, final FileInfo fi) {
		final Calibration cal = imp.getCalibration();
		final StringBuffer sb = new StringBuffer(100);
		sb.append("ImageJ="+ImageJ.VERSION+"\n");
		if (fi.nImages>1 && fi.fileType!=FileInfo.RGB48)
			sb.append("images="+fi.nImages+"\n");
		int channels = imp.getNChannels();
		if (channels>1)
			sb.append("channels="+channels+"\n");
		int slices = imp.getNSlices();
		if (slices>1)
			sb.append("slices="+slices+"\n");
		int frames = imp.getNFrames();
		if (frames>1)
			sb.append("frames="+frames+"\n");
		if (fi.unit!=null)
			sb.append("unit="+fi.unit+"\n");
		if (fi.valueUnit!=null && fi.calibrationFunction!=Calibration.CUSTOM) {
			sb.append("cf="+fi.calibrationFunction+"\n");
			if (fi.coefficients!=null) {
				for (int i=0; i<fi.coefficients.length; i++)
					sb.append("c"+i+"="+fi.coefficients[i]+"\n");
			}
			sb.append("vunit="+fi.valueUnit+"\n");
			if (cal.zeroClip()) sb.append("zeroclip=true\n");
		}
		
		// get stack z-spacing and fps
		if (fi.nImages>1) {
			if (fi.pixelDepth!=0.0 && fi.pixelDepth!=1.0)
				sb.append("spacing="+fi.pixelDepth+"\n");
			if (cal.fps!=0.0) {
				if ((int)cal.fps==cal.fps)
					sb.append("fps="+(int)cal.fps+"\n");
				else
					sb.append("fps="+cal.fps+"\n");
			}
			sb.append("loop="+(cal.loop?"true":"false")+"\n");
			if (cal.frameInterval!=0.0) {
				if ((int)cal.frameInterval==cal.frameInterval)
					sb.append("finterval="+(int)cal.frameInterval+"\n");
				else
					sb.append("finterval="+cal.frameInterval+"\n");
			}
			if (!cal.getTimeUnit().equals("sec"))
				sb.append("tunit="+cal.getTimeUnit()+"\n");
		}
		
		// get min and max display values
		final ImageProcessor ip = imp.getProcessor();
		final double min = ip.getMin();
		final double max = ip.getMax();
		final int type = imp.getType();
		final boolean enhancedLut = (type==ImagePlus.GRAY8 || type==ImagePlus.COLOR_256) && (min!=0.0 || max !=255.0);
		if (enhancedLut || type==ImagePlus.GRAY16 || type==ImagePlus.GRAY32) {
			sb.append("min="+min+"\n");
			sb.append("max="+max+"\n");
		}
		
		// get non-zero origins
		if (cal.xOrigin!=0.0)
			sb.append("xorigin="+cal.xOrigin+"\n");
		if (cal.yOrigin!=0.0)
			sb.append("yorigin="+cal.yOrigin+"\n");
		if (cal.zOrigin!=0.0)
			sb.append("zorigin="+cal.zOrigin+"\n");
		if (cal.info!=null && cal.info.length()<=64 && cal.info.indexOf('=')==-1 && cal.info.indexOf('\n')==-1)
			sb.append("info="+cal.info+"\n");			
		sb.append((char)0);
		return new String(sb);
	}

	/** Save a jpeg including the alpha channel if it has one. */
	static public final void saveJpegAlpha(final Image awt, final String path) {
		try {
			if (awt instanceof BufferedImage) {
				ImageIO.write((BufferedImage)awt, "jpeg", new File(path));
			} else {
				final BufferedImage bi = new BufferedImage(awt.getWidth(null), awt.getHeight(null), BufferedImage.TYPE_INT_ARGB);
				bi.createGraphics().drawImage(awt, 0, 0, null);
				ImageIO.write(bi, "jpeg", new File(path));
			}
		} catch (FileNotFoundException fnfe) {
			Utils.log2("saveJpegAlpha: Path not found: " + path);
		} catch (Exception e) {
			new IJError(e);
		}
	}

	/** Open a jpeg file including the alpha channel if it has one. */
	static public BufferedImage openJpegAlpha(final String path) {
		try {
			return ImageIO.read(new File(path));
		} catch (FileNotFoundException fnfe) {
			Utils.log2("openJpegAlpha: Path not found: " + path);
		} catch (Exception e) {
			new IJError(e);
		}
		return null;
	}

	static public final void debugAlpha() {
		// create an image with an alpha channel
		BufferedImage bi = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
		// get an image without alpha channel to paste into it
		Image baboon = new ij.io.Opener().openImage("http://rsb.info.nih.gov/ij/images/baboon.jpg").getProcessor().createImage();
		bi.createGraphics().drawImage(baboon, 0, 0, null);
		// create a fading alpha channel
		int[] ramp = (int[])ij.gui.NewImage.createRGBImage("ramp", 512, 512, 1, ij.gui.NewImage.FILL_RAMP).getProcessor().getPixels();
		// insert fading alpha ramp into the image
		bi.getAlphaRaster().setPixels(0, 0, 512, 512, ramp);
		// save the image
		String path = "/home/albert/temp/baboonramp.jpg";
		saveJpegAlpha(bi, path);
		// open the image
		Image awt = openJpegAlpha(path);
		// show it in a canvas that has some background
		// so that if the alpha was read from the jpeg file, it is readily visible
		javax.swing.JFrame frame = new javax.swing.JFrame("test alpha");
		final Image background = frame.getGraphicsConfiguration().createCompatibleImage(512, 512);
		final Image some = new ij.io.Opener().openImage("http://rsb.info.nih.gov/ij/images/bridge.gif").getProcessor().createImage();
		java.awt.Graphics g = background.getGraphics();
		g.drawImage(some, 0, 0, null);
		g.drawImage(awt, 0, 0, null);
		java.awt.Canvas canvas = new java.awt.Canvas() {
			public void paint(Graphics g) {
				g.drawImage(background, 0, 0, null);
			}
		};
		canvas.setSize(512, 512);
		frame.getContentPane().add(canvas);
		frame.pack();
		frame.setVisible(true);
	}

}
