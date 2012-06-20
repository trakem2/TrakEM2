package ini.trakem2.io;

import ini.trakem2.imaging.P;
import ini.trakem2.persistence.ImageBytes;
import ini.trakem2.utils.CachingThread;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class RawMipMaps {

	/** These are both the 4 supported types and the number of channels that each type has. */
	static public final byte GREY = 1,
	                         GREY_ALPHA = 2,
	                         RGB = 3,
	                         RGBA = 4;
	/** Two 4-byte ints, for width and height, and one byte for the type. */
	static public final int HEADER_SIZE = 9;
	
	static public final boolean save(final String path, final byte[][] b, final int width, final int height) {
		if (!ImageSaver.checkPath(path)) return false;
		RandomAccessFile ra = null;
		try {
			ra = new RandomAccessFile(new File(path), "rw");
			// Header: must write as an array or integers get saved with less than 4 bytes
			final byte[] h = new byte[9];
			h[0] = (byte)((width  >> 24) & 0xff);
			h[1] = (byte)((width  >> 16) & 0xff);
			h[2] = (byte)((width  >>  8) & 0xff);
			h[3] = (byte) (width         & 0xff);
			h[4] = (byte)((height >> 24) & 0xff);
			h[5] = (byte)((height >> 16) & 0xff);
			h[6] = (byte)((height >>  8) & 0xff);
			h[7] = (byte) (height        & 0xff);
			h[8] = (byte)  b.length             ; // only possible values: 1,2,3,4; it's the type
			ra.write(h);
			// Write channels
			for (int i=0; i<b.length; ++i) {
				ra.write(b[i]);
			}
			return true;

		} catch (Exception e) {
			IJError.print(e);
		} finally {
			if (null != ra) try { ra.close(); } catch (Exception e) { IJError.print(e); }
		}
		return false;
	}
	
	static public final ImageBytes load(final String path) {
		return load(path, 0);
	}

	static public final ImageBytes load(final String path, final int retry) {
		RandomAccessFile ra = null;
		try {
			final File f = new File(path);
			ra = new RandomAccessFile(f, "r");
			final byte[] h = new byte[9];
			read(ra, h);
			final int width =  ((h[0]&0xff) << 24) | ((h[1]&0xff) << 16) | ((h[2]&0xff) << 8) | (h[3]&0xff);
			final int height = ((h[4]&0xff) << 24) | ((h[5]&0xff) << 16) | ((h[6]&0xff) << 8) | (h[7]&0xff);
			final int nCh = h[8];
			final int chLength = (((int)f.length()) - HEADER_SIZE) / nCh;
			final byte[][] ch = CachingThread.getOrCreateByteArray(nCh, chLength); // new byte[nCh][chLength];
			for (int i=0; i<nCh; ++i) {
				read(ra, ch[i]);
			}
			return new ImageBytes(ch, width, height);
		} catch (FileNotFoundException fnfe) {
			Utils.log2("File not found: " + path);
		} catch (Exception e) {
			// Possible: NegativeArraySizeException
			// ... meaning that the file exists but hasn't yet been fully written
			// Rather than going fancy with file locks, just wait 100 ms and retry
			// Retry
			if (retry < 2) {
				// Wait for image to be fully written
				try { Thread.sleep(100); } catch (InterruptedException ie) {}
				return load(path, retry + 1);
			}
			// Else the error is for real
			else IJError.print(e);
		} finally {
			if (null != ra) try { ra.close(); } catch (Exception e) { IJError.print(e); }
		}
		return null;
	}

	static public final BufferedImage read(final String path) {
		try {
			final ImageBytes ib = load(path);
			if (null == ib) return null;
			final byte[][] ch = ib.c;
			// Channel length also specifies the type
			switch (ch.length) {
				case GREY:
					return ImageSaver.createGrayImage(ch[0], ib.width, ib.height);
			}
			
			try {
				// Given that the BufferedImage is created with an int[], store the byte[] arrays for reuse
				switch (ch.length) {
					case GREY_ALPHA:
						// TODO: price of PRE shold be paid when saving, not when reading
						return ImageSaver.createARGBImagePre(P.blendPre(ch[0], ch[1]), ib.width, ib.height);
					case RGB:
						return ImageSaver.createRGBImage(P.blend(ch[0], ch[1], ch[2]), ib.width, ib.height);
					case RGBA:
						// TODO: price of PRE shold be paid when saving, not when reading
						return ImageSaver.createARGBImagePre(P.blendPre(ch[0], ch[1], ch[2], ch[3]), ib.width, ib.height);
				}
			} finally {
				CachingThread.storeForReuse(ch);
			}
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}
	
	static private final void read(final RandomAccessFile ra, final byte[] b) throws IOException {
		int s = 0;
		while (s < b.length) {
			int r = ra.read(b, s, b.length - s);
			if (-1 == r) return;
			s += r;
		}
	}
}
