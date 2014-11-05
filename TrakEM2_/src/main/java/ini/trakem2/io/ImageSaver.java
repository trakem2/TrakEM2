/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

import com.sun.media.jai.codec.ImageCodec;
import com.sun.media.jai.codec.TIFFDecodeParam;
import com.sun.media.jai.codec.TIFFEncodeParam;

import ij.ImageJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.io.TiffEncoder;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.media.jai.PlanarImage;

/** Provides the necessary thread-safe image file saver utilities. */
public class ImageSaver {

	private ImageSaver() {}

	static private final Object OBDIRS = new Object();

	/** Will create parent directories if they don't exist.<br />
	 *  Returns false if the path is unusable.
	 */
	static public final boolean checkPath(final String path) {
		if (null == path) {
			Utils.log("Null path, can't save.");
			return false;
		}
		final File fdir = new File(path).getParentFile();
		if (!fdir.exists()) {
			try {
				synchronized (OBDIRS) {
					if (!fdir.exists()) { // need to check again, this time inside the synch block
						fdir.mkdirs(); // returns false if already exists.
					}
					return fdir.exists(); // this is what we care about.
							      // The OS could have created the dirs outside the synch block. So the return value of mkdirs() is insufficient proof.
				}
			} catch (Exception e) {
				IJError.print(e, true);
				Utils.log("Can't use path: " + path + "\nCheck your file read/write permissions.");
				return false;
			}
		}
		return true;
	}

	/** *sigh* -- synchronization is needed. Do so by locking each file path independently.
	 *  This is all assuming that file paths will be identical, without cases in which one has a single '/' and another has '//' and so on. */

	/** A collection of file paths currently being used for saving images. */
	static private final Map<String,Object> pathlocks = new HashMap<String,Object>();

	static private final Object getPathLock(final String path) {
		Object lock = null;
		synchronized (pathlocks) {
			lock = pathlocks.get(path);
			if (null == lock) {
				lock = new Object();
				pathlocks.put(path, lock);
			}
		}
		return lock;
	}

	static private final void removePathLock(final String path) {
		synchronized (pathlocks) {
			pathlocks.remove(path);
		}
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
		// ok, onward
		// No need to make an RGB int[] image if a byte[] image with a LUT will do.
		/*
		int image_type = BufferedImage.TYPE_INT_ARGB;
		if (ip.getClass().equals(ByteProcessor.class) || ip.getClass().equals(ShortProcessor.class) || ip.getClass().equals(FloatProcessor.class)) {
			image_type = BufferedImage.TYPE_BYTE_GRAY;
		}
		*/
		BufferedImage bi = null;
		if (as_grey) { // even better would be to make a raster directly from the byte[] array, and pass that to the encoder. Unfortunately, would have to handle specially all non-8-bit images.
			bi = new BufferedImage(ip.getWidth(), ip.getHeight(), BufferedImage.TYPE_BYTE_GRAY); //, (IndexColorModel)ip.getColorModel());
		} else {
			bi = new BufferedImage(ip.getWidth(), ip.getHeight(), BufferedImage.TYPE_INT_RGB);
		}
		final Graphics g = bi.createGraphics();
		final Image awt = ip.createImage();
		g.drawImage(awt, 0, 0, null);
		g.dispose();
		awt.flush();
		boolean b = saveAsJpeg(bi, path, quality, as_grey);
		bi.flush();
		return b;
	}

	static public final BufferedImage createGrayImage(final byte[] pixels, final int width, final int height) {
		WritableRaster wr = Loader.GRAY_LUT.createCompatibleWritableRaster(1, 1);
		SampleModel sm = wr.getSampleModel().createCompatibleSampleModel(width, height);
		DataBuffer db = new DataBufferByte(pixels, width*height, 0);
		WritableRaster raster = Raster.createWritableRaster(sm, db, null);
		return new BufferedImage(Loader.GRAY_LUT, raster, false, null);
	}

	static public final boolean saveAsGreyJpeg(final byte[] pixels, final int width, final int height, final String path, final float quality) {
		return saveAsJpeg(createGrayImage(pixels, width, height), path, quality, true);
	}

	static public final DirectColorModel RGBA_COLOR_MODEL = new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);
	static public final DirectColorModel RGBA_PRE_COLOR_MODEL = new DirectColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), 32, 0xff0000, 0xff00, 0xff, 0xff000000, true, DataBuffer.TYPE_INT);
	static public final DirectColorModel RGB_COLOR_MODEL = new DirectColorModel(32, 0xff0000, 0xff00, 0xff);
	
	static public final BufferedImage createImage(final int[] pixels, final int width, final int height, final DirectColorModel cm) {
		WritableRaster wr = cm.createCompatibleWritableRaster(1, 1);
		SampleModel sm = wr.getSampleModel().createCompatibleSampleModel(width, height);
		DataBuffer dataBuffer = new DataBufferInt(pixels, width*height, 0);
		WritableRaster rgbRaster = Raster.createWritableRaster(sm, dataBuffer, null);
		return new BufferedImage(cm, rgbRaster, cm == RGBA_PRE_COLOR_MODEL, null);
	}

	static public final BufferedImage createRGBImage(final int[] pixels, final int width, final int height) {
		return createImage(pixels, width, height, RGB_COLOR_MODEL);
	}
	static public final BufferedImage createARGBImage(final int[] pixels, final int width, final int height) {
		return createImage(pixels, width, height, RGBA_COLOR_MODEL);
	}
	/** Assumes the pixels have been premultiplied. */
	static public final BufferedImage createARGBImagePre(final int[] pixels, final int width, final int height) {
		return createImage(pixels, width, height, RGBA_PRE_COLOR_MODEL);
	}

	/** Save as ARGB jpeg. */
	static public final boolean saveAsARGBJpeg(final int[] pixels, final int width, final int height, final String path, final float quality) {
		return saveAsJpeg(createARGBImage(pixels, width, height), path, quality, false);
	}

	/** Will not flush the given BufferedImage. */
	static public final boolean saveAsJpeg(final BufferedImage bi, final String path, float quality, boolean as_grey) {
		if (!checkPath(path)) return false;
		if (quality < 0f) quality = 0f;
		if (quality > 1f) quality = 1f;
		synchronized (getPathLock(path)) {
			ImageOutputStream ios = null;
			ImageWriter writer = null;
			BufferedImage grey = bi;
			try {
				writer = ImageIO.getImageWritersByFormatName("jpeg").next();
				final ByteArrayOutputStream baos = new ByteArrayOutputStream( estimateJPEGFileSize(bi.getWidth(), bi.getHeight()) );
				ios = ImageIO.createImageOutputStream(baos);
				writer.setOutput(ios);
				ImageWriteParam param = writer.getDefaultWriteParam();
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(quality);
				if (as_grey && bi.getType() != BufferedImage.TYPE_BYTE_GRAY) {
					grey = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
					// convert the original colored image to grayscale
					// Very slow:
					//ColorConvertOp op = new ColorConvertOp(bi.getColorModel().getColorSpace(), grey.getColorModel().getColorSpace(), null);
					//op.filter(bi, grey);
					// 9 times faster:
					grey.createGraphics().drawImage(bi, 0, 0, null);
				}
				IIOImage iioImage = new IIOImage(grey, null, null);
				writer.write(null, iioImage, param);
				// Now write to disk
				FileChannel ch = null;
				try {
					// Now write to disk in the fastest way possible
					final RandomAccessFile ra = new RandomAccessFile(new File(path), "rw");
					final ByteBuffer bb = ByteBuffer.wrap((byte[])Bbuf.get(baos), 0, baos.size());
					ch = ra.getChannel();
					while (bb.hasRemaining()) {
						ch.write(bb);
					}
				} finally {
					if (null != ch) ch.close();
					ios.close();
				}
				
				
			} catch (Exception e) {
				IJError.print(e);
				return false;
			} finally {
				if (null != writer) try { writer.dispose(); } catch (Exception ee) {}
				//if (null != ios) try { ios.close(); } catch (Exception ee) {} // NOT NEEDED, it's a ByteArrayOutputStream
				if (bi != grey) try { grey.flush(); /*release native resources*/ } catch (Exception ee) {}
				removePathLock(path);
			}
		}
		return true;
	}

	// Convoluted method to make sure all possibilities of opening and closing the stream are considered.
	static public final BufferedImage open(final String path, final boolean as_grey) {
		InputStream stream = null;
		BufferedImage bi = null;
		synchronized (getPathLock(path)) {
			try {
				// 1 - create a stream if possible
				stream = openStream(path);
				if (null == stream) return null;
				
				// 2 - open it as a BufferedImage
				bi = openFromStream(stream, as_grey);

			} catch (Throwable e) {
				// the file might have been generated while trying to read it. So try once more
				try {
					Utils.log2("Decoder failed for " + path);
					Thread.sleep(50);
					// reopen stream
					if (null != stream) { try { stream.close(); } catch (Exception ee) {} }
					stream = openStream(path);
					// decode
					if (null != stream) bi = openFromStream(stream, as_grey);
				} catch (Exception e2) {
					IJError.print(e2, true);
				}
			} finally {
				removePathLock(path);
				if (null != stream) { try { stream.close(); } catch (Exception e) {} }
			}
		}
		return bi;
	}

	static private final InputStream openStream(final String path) {
		/*
		// Proper implementation, incurs in big drag because of new File(path).exists() OS calls.
		if (FSLoader.isURL(path)) {
			return new URL(path).openStream();
		} else if (new File(path).exists()) {
			return new FileInputStream(path);
		}*/
		// Simple optimization, incurring in horrible practices ... blame me.
		try {
			final File f = new File(path);
			return new BufferedInputStream(new FileInputStream(f), (int)Math.min(f.length(), 35000000)); // 35 Mb
		} catch (FileNotFoundException fnfe) {
			try {
				if (FSLoader.isURL(path)) {
					return new URL(path).openStream();
				}
			} catch (Throwable e) {
				IJError.print(e, true);
			}
		} catch (Throwable t) {
			IJError.print(t, true);
		}
		return null;
	}

	/** The stream IS NOT closed by this method. */
	static public final BufferedImage openFromStream(final InputStream stream, final boolean as_grey) {
		try {
			if (as_grey) {
				final BufferedImage bi = ImageIO.read(stream);
				if (bi.getType() != BufferedImage.TYPE_BYTE_GRAY) {
					final BufferedImage grey = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
					grey.createGraphics().drawImage(bi, 0, 0, null);
					bi.flush();
					return grey;
				}
				return bi;
			} else {
				return ImageIO.read(stream);
			}
		} catch (IllegalArgumentException iae) {
			// According to the documentation, only occurs
			// when stream is null, so this should never happen.
			return null;
		} catch (IOException ioe) {
			return null;
		} catch (Throwable t) {
			t.printStackTrace();
			return null;
		}
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
			IJError.print(e);
			return false;
		}
		return true;
	}

	/** Returns a string containing information about the specified  image. */
	static public final String getDescriptionString(final ImagePlus imp, final FileInfo fi) {
		final Calibration cal = imp.getCalibration();
		final StringBuilder sb = new StringBuilder(100);
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

	static public final Field Bbuf;
	static {
		Field b = null;
		try {
			b = ByteArrayOutputStream.class.getDeclaredField("buf");
			b.setAccessible(true);
		} catch (Exception e) {
			IJError.print(e);
		}
		Bbuf = b;
	}
	
	/** Based on EM images of neuropils with outside alpha masks.
	 * Fitted a polynomial on the length of file vs area size. */
	static public final int estimateJPEGFileSize(final int w, final int h) {
		final long area = w * h;
		return (int)((0.0000000108018 * area * area + 0.315521 * area + 8283.24) * 1.2); // 20% padding
	}

	/** Save an RGB jpeg including the alpha channel if it has one; can be read only by ImageSaver.openJpegAlpha method; in other software the alpha channel is confused by some other color channel. */
	static public final boolean saveAsJpegAlpha(final BufferedImage awt, final String path, final float quality) {
		if (!checkPath(path)) return false;
		synchronized (getPathLock(path)) {
			try {
				// This is all the mid-level junk code I have to learn and manage just to SET THE F*CK*NG compression quality for a jpeg.
				ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next(); // just the first one
				if (null != writer) {
					ImageWriteParam iwp = writer.getDefaultWriteParam(); // with all jpeg specs in it
					iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					iwp.setCompressionQuality(quality); // <---------------------------------------------------------- THIS IS ALL I WANTED
					//((JPEGImageWriteParam)iwp).setProgressiveMode(JPEGImageWriteParam.MODE_DISABLED);
					//((JPEGImageWriteParam)iwp).
					//
					final ByteArrayOutputStream baos = new ByteArrayOutputStream( estimateJPEGFileSize(awt.getWidth(), awt.getHeight()) );
					ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
					RandomAccessFile ra = null;
					FileChannel ch = null;
					try {
						writer.setOutput(ios);
						writer.write(writer.getDefaultStreamMetadata(iwp), new IIOImage(awt, null, null), iwp);
						// Now write to disk in the fastest way possible
						ra = new RandomAccessFile(new File(path), "rw");
						final ByteBuffer bb = ByteBuffer.wrap((byte[])Bbuf.get(baos), 0, baos.size());
						ch = ra.getChannel();
						while (bb.hasRemaining()) {
							ch.write(bb);
						}
						ch.force(false);
					} finally {
						if (null != ch) ch.close();
						ios.close();
					}
					return true; // only one: com.sun.imageio.plugins.jpeg.JPEGImageWriter
				}

				// If the above doesn't find any, magically do it anyway without setting the compression quality:
				ImageIO.write(awt, "jpeg", new File(path));
				return true;
			} catch (FileNotFoundException fnfe) {
				Utils.log2("saveAsJpegAlpha: Path not found: " + path);
			} catch (Exception e) {
				IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return false;
	}

	/** Save an RGB jpeg including the alpha channel if it has one; can be read only by ImageSaver.openJpegAlpha method; in other software the alpha channel is confused by some other color channel. */
	static public final boolean saveAsJpegAlpha(final Image awt, final String path, final float quality) {
		final BufferedImage bi = asBufferedImage(awt);
		boolean b = saveAsJpegAlpha(bi, path, quality);
		if (bi != awt) bi.flush();
		return b;
	}

	static public final BufferedImage asBufferedImage(final Image awt) {
		BufferedImage bi = null;
		if (awt instanceof BufferedImage) {
			bi = (BufferedImage)awt;
		} else {
			bi = new BufferedImage(awt.getWidth(null), awt.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			bi.createGraphics().drawImage(awt, 0, 0, null);
		}
		return bi;
	}

	/** Open a jpeg file including the alpha channel if it has one. */
	static public BufferedImage openJpegAlpha(final String path) {
		return openImage(path, true);
	}

	static public BufferedImage openPNGAlpha(final String path) {
		return openImage(path, true);
	}

	/** Open an image file including the alpha channel if it has one; will open JPEG, PNG and BMP.
	 *  @param ensure_premultiplied_alpha when true, ALWAYS puts the loaded image into a TYPE_INT_ARGB_PRE, which ensures for example PNG images with an alpha channel but of TYPE_CUSTOM to be premultiplied as well. */
	static public BufferedImage openImage(final String path, final boolean ensure_premultiplied_alpha) {
		synchronized (getPathLock(path)) {
			try {
				final BufferedImage img = ImageIO.read(new File(path));
				if (ensure_premultiplied_alpha || img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_CUSTOM) {
					// Premultiply alpha, for speed (makes a huge difference)
					final BufferedImage imgPre = new BufferedImage( img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE );
					imgPre.createGraphics().drawImage( img, 0, 0, null );
					img.flush();
					return imgPre;
				}
				return img;
			} catch (FileNotFoundException fnfe) {
				Utils.log2("openImage: Path not found: " + path);
			} catch (Exception e) {
				Utils.log2("openImage: cannot open " + path);
				//IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return null;
	}

	static public BufferedImage openGreyImage(final String path) {
		synchronized (getPathLock(path)) {
			try {
				return asGrey(ImageIO.read(new File(path)));
			} catch (FileNotFoundException fnfe) {
				Utils.log2("openImage: Path not found: " + path);
			} catch (Exception e) {
				Utils.log2("openImage: cannot open " + path);
				//IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return null;
	}

	/** If the given BufferedImage is of type TYPE_BYTE_GRAY, it will simply return it. If not, it will flush() the given BufferedImage, and return a new grey one. */
	static public final BufferedImage asGrey(final BufferedImage bi) {
		if (null == bi) return null;
		if (bi.getType() == BufferedImage.TYPE_BYTE_GRAY) {
			return bi;
		}
		// Else:
		final BufferedImage grey = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
		grey.createGraphics().drawImage(bi, 0, 0, null);
		bi.flush();
		return grey;
	}

	static public final void debugAlpha() {
		// create an image with an alpha channel
		BufferedImage bi = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
		// get an image without alpha channel to paste into it
		Image baboon = new ij.io.Opener().openImage("http://rsb.info.nih.gov/ij/images/baboon.jpg").getProcessor().createImage();
		bi.createGraphics().drawImage(baboon, 0, 0, null);
		baboon.flush();
		// create a fading alpha channel
		int[] ramp = (int[])ij.gui.NewImage.createRGBImage("ramp", 512, 512, 1, ij.gui.NewImage.FILL_RAMP).getProcessor().getPixels();
		// insert fading alpha ramp into the image
		bi.getAlphaRaster().setPixels(0, 0, 512, 512, ramp);
		// save the image
		String path = "/home/albert/temp/baboonramp.jpg";
		saveAsJpegAlpha(bi, path, 0.75f);
		// open the image
		Image awt = openJpegAlpha(path);
		// show it in a canvas that has some background
		// so that if the alpha was read from the jpeg file, it is readily visible
		javax.swing.JFrame frame = new javax.swing.JFrame("test alpha");
		final Image background = frame.getGraphicsConfiguration().createCompatibleImage(512, 512);
		final Image some = new ij.io.Opener().openImage("http://rsb.info.nih.gov/ij/images/bridge.gif").getProcessor().createImage();
		java.awt.Graphics g = background.getGraphics();
		g.drawImage(some, 0, 0, null);
		some.flush();
		g.drawImage(awt, 0, 0, null);
		@SuppressWarnings("serial")
		java.awt.Canvas canvas = new java.awt.Canvas() {
			public void paint(Graphics g) {
				g.drawImage(background, 0, 0, null);
			}
		};
		canvas.setSize(512, 512);
		frame.getContentPane().add(canvas);
		frame.pack();
		frame.setVisible(true);

		// 1) check if 8-bit images can also be jpegs with an alpha channel: they can't
		// 2) check if ImagePlus preserves the alpha channel as well: it doesn't
	}

	static public final boolean saveAsPNG(final ImageProcessor ip, final String path) {
		Image awt = null;
		try {
			awt = ip.createImage();
			return ImageSaver.saveAsPNG(awt, path);
		} catch (Exception e) {
			IJError.print(e);
			return false;
		} finally {
			if (null != awt) awt.flush();
		}
	}

	static public final boolean saveAsPNG(final Image awt, final String path) {
		try {
			BufferedImage bi = null;
			if (awt instanceof BufferedImage) {
				bi = (BufferedImage)awt;
			} else {
				bi = new BufferedImage(awt.getWidth(null), awt.getHeight(null), BufferedImage.TYPE_INT_ARGB);
				bi.createGraphics().drawImage(awt, 0, 0, null);
			}
			return saveAsPNG(bi, path);
		} catch (Exception e) {
			IJError.print(e);
			return false;
		}
	}

	/** Save a PNG with or without alpha channel with default compression at maximum level (9, expressed as 0 in the ImageWriter compression quality because 0 indicates "maximum compression is important").*/
	static public final boolean saveAsPNG(final BufferedImage awt, final String path) {
		if (!checkPath(path)) return false;
		synchronized (getPathLock(path)) {
			try {
				// java.lang.UnsupportedOperationException: Compression not supported (!)
				/*
				// This is all the mid-level junk code I have to learn and manage just to SET THE F*CK*NG compression level for a PNG
				ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next(); // just the first one
				if (null != writer) {
					ImageWriteParam iwp = writer.getDefaultWriteParam(); // with all PNG specs in it
					iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					iwp.setCompressionQuality(0); // <---------------------------------------------------------- THIS IS ALL I WANTED
					ImageOutputStream ios = ImageIO.createImageOutputStream(new File(path));
					try {
						writer.setOutput(ios); // the stream
						writer.write(writer.getDefaultStreamMetadata(iwp), new IIOImage(awt, null, null), iwp);
					} finally {
						ios.close();
					}
					return true;
				}
				*/

				// If the above doesn't find any, magically do it anyway without setting the compression quality:
				return ImageIO.write(awt, "png", new File(path));
			} catch (FileNotFoundException fnfe) {
				Utils.log2("saveAsPng: Path not found: " + path);
			} catch (Exception e) {
				IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return false;
	}

	/** WARNING fails when there is an alpha channel: generates an empty file. */
	/*
	static public final boolean saveAsBMP(final BufferedImage awt, final String path) {
		if (!checkPath(path)) return false;
		synchronized (getPathLock(path)) {
			try {
				return ImageIO.write(awt, "bmp", new File(path));
			} catch (FileNotFoundException fnfe) {
				Utils.log2("saveAsPng: Path not found: " + path);
			} catch (Exception e) {
				IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return false;
	}
	*/

	static public final boolean saveAsTIFF(final ImageProcessor ip, final String path, final boolean as_grey) {
		try {
			return saveAsTIFF(ip.getBufferedImage(), path, as_grey);
		} catch (Exception e) {
			IJError.print(e);
		}
		return false;
	}

	/** Will not flush @param awt. */
	static public final boolean saveAsTIFF(final Image awt, final String path, final boolean as_grey) {
		BufferedImage bi = null;
		try {
			if (awt instanceof BufferedImage) return saveAsTIFF((BufferedImage)awt, path, as_grey);
			// Else, transform into BufferedImage (which is a RenderedImage):
			bi = new BufferedImage(awt.getWidth(null), awt.getHeight(null), as_grey ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_ARGB);
			bi.createGraphics().drawImage(awt, 0, 0, null);
			return saveAsTIFF(bi, path, false); // no need for more checks for grey
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != bi) bi.flush();
		}
		return false;
	}

	static public final boolean saveAsTIFF(BufferedImage bi, final String path, final boolean as_grey) {
		if (!checkPath(path)) return false;
		synchronized (getPathLock(path)) {
			OutputStream out = null;
			try {
				if (as_grey) bi = asGrey(bi);
				final TIFFEncodeParam param = new TIFFEncodeParam();
				// If the bi is larger than 512x512, I could use COMPRESSION_LZW or COMPRESSION_DEFLATE (zip-in-tiff, lossless), or COMPRESSION_JPEG_TTN2 (Jpeg-in-tiff) -- i.e. an adaptive strategy as suggested by Clay Reid
				param.setCompression(TIFFEncodeParam.COMPRESSION_NONE);
				out = new BufferedOutputStream(new FileOutputStream(path));
				ImageCodec.createImageEncoder("TIFF", out, param).encode(bi);
				out.flush(); // !@#$% Couldn't it do it by itself?
				final File f = new File(path);
				return f.exists() && f.length() > 0; // no other way to check if the writing was successful
			} catch (FileNotFoundException fnfe) {
				Utils.log2("saveAsTIFF: Path not found: " + path);
			} catch (Exception e) {
				IJError.print(e, true);
			} finally {
				if (null != out) try { out.close(); } catch (Exception e) {}
				removePathLock(path);
			}
		}
		return false;
	}

	// WARNING JAI is fragile, throws an Exception when reading malformed tif files (like tif files whose OutputStream was not flush()'ed)

	static private final BufferedImage openTIFF(final String path) throws Exception {
		/*
		final RenderedImage ri = ImageCodec.createImageDecoder("TIFF", new File(path), new TIFFDecodeParam()).decodeAsRenderedImage();
		final PlanarImage pi = PlanarImage.wrapRenderedImage(ri);
		final BufferedImage img = pi.getAsBufferedImage();
		*/
		return PlanarImage.wrapRenderedImage(ImageCodec.createImageDecoder("TIFF", new File(path), new TIFFDecodeParam()).decodeAsRenderedImage()).getAsBufferedImage();
	}

	/** Opens RGB or RGB + alpha images stored in TIFF format. */
	static public final BufferedImage openTIFF(final String path, final boolean ensure_premultiplied_alpha) {
		synchronized (getPathLock(path)) {
			try {
				final BufferedImage img = openTIFF(path);

				if (ensure_premultiplied_alpha || img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_CUSTOM) {
					// Premultiply alpha, for speed (makes a huge difference)
					final BufferedImage imgPre = new BufferedImage( img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE );
					imgPre.createGraphics().drawImage( img, 0, 0, null );
					img.flush();
					return imgPre;
				}
				return img;

			} catch (Exception e) {
				Utils.log2("openTIFF: cannot open " + path + " :\n\t" + e);
				//IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return null;
	}

	static public final BufferedImage openGreyTIFF(final String path) {
		synchronized (getPathLock(path)) {
			try {
				return asGrey(openTIFF(path));
			} catch (Exception e) {
				Utils.log2("openGreyTIFF: cannot open " + path + " :\n\t" + e);
				//IJError.print(e, true);
			} finally {
				removePathLock(path);
			}
		}
		return null;
	}
}
