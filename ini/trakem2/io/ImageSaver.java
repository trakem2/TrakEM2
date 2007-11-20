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
import java.io.*;
import java.util.zip.*;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;

/** Provides the necessary thread-safe image file saver utilities. */
public class ImageSaver {

	private ImageSaver() {}

	/** Will create parent directories if they don't exist.<br />
	 *  Returns false if the path is unusable.
	 */
	static private boolean checkPath(final String path) {
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
	static public boolean saveAsJpeg(final ImageProcessor ip, final String path, float quality) {
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
		int image_type = BufferedImage.TYPE_INT_RGB;
		if (ip.getClass().equals(ByteProcessor.class) || ip.getClass().equals(ShortProcessor.class) || ip.getClass().equals(FloatProcessor.class)) {
			image_type = BufferedImage.TYPE_BYTE_GRAY;
		}
		BufferedImage bi = new BufferedImage(ip.getWidth(), ip.getHeight(), image_type);
		try {
			FileOutputStream f = new FileOutputStream(path);
			Graphics g = bi.createGraphics();
			g.drawImage(ip.createImage(), 0, 0, null);
			g.dispose();
			JPEGImageEncoder encoder = JPEGCodec.createJPEGEncoder(f);
			JPEGEncodeParam param = encoder.getDefaultJPEGEncodeParam(bi);
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
	static public BufferedImage openGreyJpeg(final String path) {
		try {
			FileInputStream f = new FileInputStream(path);
			JPEGImageDecoder decoder = JPEGCodec.createJPEGDecoder(f, JPEGCodec.getDefaultJPEGEncodeParam(1, JPEGDecodeParam.COLOR_ID_GRAY));
			return decoder.decodeAsBufferedImage();
		} catch (FileNotFoundException fnfe) {
			return null;
		} catch (Exception e) {
			new IJError(e);
			return null;
		}
	}

	/** Returns true on success.<br />
	 *  Core functionality adapted from ij.io.FileSaver class by Wayne Rasband.
	 */
	static public boolean saveAsZip(final ImagePlus imp, String path) {
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
	static public String getDescriptionString(final ImagePlus imp, final FileInfo fi) {
		Calibration cal = imp.getCalibration();
		StringBuffer sb = new StringBuffer(100);
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
		ImageProcessor ip = imp.getProcessor();
		double min = ip.getMin();
		double max = ip.getMax();
		int type = imp.getType();
		boolean enhancedLut = (type==ImagePlus.GRAY8 || type==ImagePlus.COLOR_256) && (min!=0.0 || max !=255.0);
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
}
