package ini.trakem2.persistence;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Patch;
import ini.trakem2.imaging.FastIntegralImage;
import ini.trakem2.imaging.P;
import ini.trakem2.io.ImageSaver;

import java.awt.image.BufferedImage;

import mpicbg.imglib.algorithm.integral.IntegralImage;
import mpicbg.imglib.algorithm.integral.ScaleAreaAveraging2d;
import mpicbg.imglib.container.array.Array;
import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.container.basictypecontainer.array.ByteArray;
import mpicbg.imglib.function.IntegerTypeConverter;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.type.numeric.integer.LongType;
import mpicbg.imglib.type.numeric.integer.UnsignedByteType;

public class IntegralImageMipMaps
{
	/** WARNING modifies the {@param outside} array when both {@param alpha} and {@param outside} are not null,
	 * and when {@param ip} is a {@link ColorProcessor}, will also modify ints int[] pixels
	 * if {@param alpha} or {@param outside} are not null (the alpha channel is or'ed in).
	 * 
	 * @param patch
	 * @param ip
	 * @param alpha
	 * @param outside
	 * @param type
	 * @return An array of images represented by an array of byte[], where each is a channel of the image.
	 */
	static public final ImageBytes[] create(
			final Patch patch,
			ImageProcessor ip,
			final ByteProcessor alpha,
			final ByteProcessor outside,
			final int type) {
		// Combine alpha and outside
		// WARNING modifies alpha array
		final ByteProcessor mask;
		if (null == alpha) {
			mask = null == outside ? null : outside;
		} else if (null == outside) {
			mask = alpha;
		} else {
			final byte[] b1 = (byte[])alpha.getPixels(),
			             b2 = (byte[])outside.getPixels();
			for (int i=0; i<b1.length; ++i) {
				b2[i] = (byte)((b2[i]&0xff) != 255 ? 0 : (b1[i]&0xff)); // 'outside' is a binary mask, qualitative
			}
			mask = outside;
		}
		
		// Set min and max
		/** // No NEED, taken care of by the FSLoader callee function
		switch (type) {
			case ImagePlus.COLOR_256:
				ip = ip.convertToRGB();
			//$FALL-THROUGH$
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_RGB:
				if (0 != patch.getMin() || 255 != patch.getMax()) {
					ip = ip.duplicate(); // min,max is destructive on 8-bit channels, so make a copy
					ip.setMinAndMax(patch.getMin(), patch.getMax());
				}
				break;
			default:
				ip.setMinAndMax(patch.getMin(), patch.getMax());
				break;
		}
		*/
		// Generate pyramid
		switch (type) {
			case ImagePlus.GRAY16:
			case ImagePlus.GRAY32:
				ip = ip.convertToByte(true);
				//$FALL-THROUGH$
			case ImagePlus.GRAY8:
				return fastCreateGRAY8(patch, (ByteProcessor)ip, mask);
			case ImagePlus.COLOR_256: // Already converted to RGB above
			case ImagePlus.COLOR_RGB:
				return fastCreateRGB(patch, (ColorProcessor)ip, mask);
		}
		return null;
	}

	private static final Image<UnsignedByteType> wrap(final byte[] p, final int[] dims) {
		final Array<UnsignedByteType,ByteArray> c = new Array<UnsignedByteType,ByteArray>(
			new ArrayContainerFactory(),
			new ByteArray(p),
			dims, 1);
		final UnsignedByteType t = new UnsignedByteType(c);
		c.setLinkedType(t);
		return new Image<UnsignedByteType>(c, t);
	}
	
	private static final int[] blend(final byte[] pi, final byte[] pm) {
		final int[] p = new int[pi.length];
		for (int i=0; i<p.length; ++i) {
			final int c = (pi[i]&0xff);
			p[i] = ((pm[i]&0xff) << 24) | (c << 16) | (c << 8) | c;
		}
		return p;
	}

	/** WARNING will reuse the int[] and return it. */
	private static final int[] blend(final int[] pi, final byte[] pm) {
		for (int i=0; i<pi.length; ++i) {
			pi[i] = ((pm[i]&0xff) << 24) | pi[i];
		}
		return pi;
	}
	
	private static final int[] blend(final byte[] r, final byte[] g, final byte[] b, final byte[] a) {
		final int[] p = new int[r.length];
		for (int i=0; i<p.length; ++i) {
			p[i] = ((a[i]&0xff) << 24) | ((r[i]&0xff) << 16) | ((g[i]&0xff) << 8) | (b[i]&0xff);
		}
		return p;
	}
	
	private static final int[] blend(final byte[] r, final byte[] g, final byte[] b) {
		final int[] p = new int[r.length];
		for (int i=0; i<p.length; ++i) {
			p[i] = ((r[i]&0xff) << 16) | ((g[i]&0xff) << 8) | (b[i]&0xff);
		}
		return p;
	}

	@SuppressWarnings({ "unused", "unchecked", "null" })
	private static final BufferedImage[] createGRAY8(
			final Patch patch,
			final ByteProcessor ip,
			final ByteProcessor mask) {
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		final int[] dims = new int[]{w, h};
		final ScaleAreaAveraging2d<LongType, UnsignedByteType> saai, saam;
		{
			// Integral of the image
			final IntegralImage<UnsignedByteType, LongType> oa =
					new IntegralImage<UnsignedByteType, LongType>(
							wrap((byte[])ip.getPixels(), dims),
							new LongType(), new IntegerTypeConverter<UnsignedByteType, LongType>());
			oa.process();
			saai = new ScaleAreaAveraging2d<LongType, UnsignedByteType>(
					oa.getResult(), new UnsignedByteType(), dims);

			// Integral of the mask, if any
			if (null != mask) {
				final IntegralImage<UnsignedByteType, LongType> ma = new IntegralImage<UnsignedByteType, LongType>(
						wrap((byte[])mask.getPixels(), dims),
						new LongType(), new IntegerTypeConverter<UnsignedByteType, LongType>());
				ma.process();
				saam = new ScaleAreaAveraging2d<LongType, UnsignedByteType>(ma.getResult(), new UnsignedByteType(), dims);
			} else {
				saam = null;
			}
		}
		
		// Generate images
		final BufferedImage[] bis = new BufferedImage[Loader.getHighestMipMapLevel(patch) + 1];
		//
		if (null == saam) { // mask is null
			// Save images as grayscale
			bis[0] = ImageSaver.createGrayImage((byte[])ip.getPixels(), w, h); // sharing the byte[]
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				saai.setOutputDimensions(wk, hk);
				saai.process();
				bis[i] = ImageSaver.createGrayImage(((Array<UnsignedByteType,ByteArray>) saai.getResult().getContainer()).update(null).getCurrentStorageArray(), wk, hk);
			}
		} else {
			// Save images as RGBA, where all 3 color channels are the same
			bis[0] = ImageSaver.createARGBImage(blend((byte[])ip.getPixels(), (byte[])mask.getPixels()), w, h);
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				saai.setOutputDimensions(wk, hk);
				saai.process();
				// A mask of the scaled size
				saam.setOutputDimensions(wk, hk);
				saam.process();
				//
				bis[i] = ImageSaver.createARGBImage(
					blend(
						((Array<UnsignedByteType,ByteArray>) saai.getResult().getContainer()).update(null).getCurrentStorageArray(),
						((Array<UnsignedByteType,ByteArray>) saam.getResult().getContainer()).update(null).getCurrentStorageArray()),
					wk, hk);
			}
		}
		
		return bis;
	}
	
	private static final ImageBytes[] fastCreateGRAY8(
			final Patch patch,
			final ByteProcessor ip,
			final ByteProcessor mask) {
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		
		// Integrals
		final long[] ii = FastIntegralImage.longIntegralImage((byte[])ip.getPixels(), w, h);
		final long[] mi = null == mask ? null : FastIntegralImage.longIntegralImage((byte[])mask.getPixels(), w, h);
		
		// Generate images
		final ImageBytes[] bis = new ImageBytes[Loader.getHighestMipMapLevel(patch) + 1];
		//
		if (null == mask) { // mask is null
			// Save images as grayscale
			bis[0] = new ImageBytes(new byte[][]{(byte[])ip.getPixels()}, ip.getWidth(), ip.getHeight());
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			              wk = w / K,
			              hk = h / K;
				// An image of the scaled size
				bis[i] = new ImageBytes(new byte[][]{FastIntegralImage.scaleAreaAverage(ii, w+1, h+1, wk, hk)}, wk, hk);
			}
		} else {
			// Save images as RGBA, where all 3 color channels are the same
			bis[0] = new ImageBytes(new byte[][]{(byte[])ip.getPixels(), (byte[])mask.getPixels()}, ip.getWidth(), ip.getHeight());
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			              wk = w / K,
			              hk = h / K;
				//
				bis[i] = new ImageBytes(
						new byte[][]{FastIntegralImage.scaleAreaAverage(ii, w+1, h+1, wk, hk),
						             FastIntegralImage.scaleAreaAverage(mi, w+1, h+1, wk, hk)},
						wk, hk);
			}
		}
		
		return bis;
	}
	
	
	
	static private final ScaleAreaAveraging2d<LongType, UnsignedByteType> saa(final byte[] b, final int[] dims) {
		final IntegralImage<UnsignedByteType, LongType> ii =
			new IntegralImage<UnsignedByteType, LongType>(
				wrap(b, dims),
				new LongType(), new IntegerTypeConverter<UnsignedByteType, LongType>());
		ii.process();
		return new ScaleAreaAveraging2d<LongType, UnsignedByteType>(
			ii.getResult(), new UnsignedByteType(), dims);
	}
	
	@SuppressWarnings({ "unused", "unchecked", "null" })
	private static final BufferedImage[] createRGB(
			final Patch patch,
			final ColorProcessor ip,
			final ByteProcessor mask) {
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		final int[] dims = new int[]{w, h};
		final ScaleAreaAveraging2d<LongType, UnsignedByteType> saar, saag, saab, saam;
		{
			// Split color channels
			final int[] p = (int[]) ip.getPixels();
			final byte[] r = new byte[p.length],
			             g = new byte[p.length],
			             b = new byte[p.length];
			for (int i=0; i<p.length; ++i) {
				final int a = p[i];
				r[i] = (byte)((a >> 16)&0xff);
				g[i] = (byte)((a >> 8)&0xff);
				b[i] = (byte)(a&0xff);
			}
			//
			saar = saa(r, dims);
			saag = saa(g, dims);
			saab = saa(b, dims);
			saam = null == mask? null : saa((byte[])mask.getPixels(), dims);
		}
		
		// Generate images
		final BufferedImage[] bis = new BufferedImage[Loader.getHighestMipMapLevel(patch) + 1];
		//
		if (null == saam) { // mask is null
			bis[0] = ImageSaver.createARGBImage((int[])ip.getPixels(), w, h); // sharing the int[] pixels
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				saar.setOutputDimensions(wk, hk);
				saar.process();
				saag.setOutputDimensions(wk, hk);
				saag.process();
				saab.setOutputDimensions(wk, hk);
				saab.process();
				bis[i] = ImageSaver.createARGBImage(
					blend(((Array<UnsignedByteType,ByteArray>) saar.getResult().getContainer()).update(null).getCurrentStorageArray(),
						  ((Array<UnsignedByteType,ByteArray>) saag.getResult().getContainer()).update(null).getCurrentStorageArray(),
						  ((Array<UnsignedByteType,ByteArray>) saab.getResult().getContainer()).update(null).getCurrentStorageArray()),
					wk, hk);
			}
		} else {
			// With alpha channel
			bis[0] = ImageSaver.createARGBImage(blend((int[])ip.getPixels(), (byte[])mask.getPixels()), w, h); // sharing the int[] pixels
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				saar.setOutputDimensions(wk, hk);
				saar.process();
				saag.setOutputDimensions(wk, hk);
				saag.process();
				saab.setOutputDimensions(wk, hk);
				saab.process();
				saam.setOutputDimensions(wk, hk);
				saam.process();
				bis[i] = ImageSaver.createARGBImage(
					blend(((Array<UnsignedByteType,ByteArray>) saar.getResult().getContainer()).update(null).getCurrentStorageArray(),
						  ((Array<UnsignedByteType,ByteArray>) saag.getResult().getContainer()).update(null).getCurrentStorageArray(),
						  ((Array<UnsignedByteType,ByteArray>) saab.getResult().getContainer()).update(null).getCurrentStorageArray(),
						  ((Array<UnsignedByteType,ByteArray>) saam.getResult().getContainer()).update(null).getCurrentStorageArray()),
					wk, hk);
			}
		}
		
		return bis;
	}
	
	private static final ImageBytes[] fastCreateRGB(
			final Patch patch,
			final ColorProcessor ip,
			final ByteProcessor mask) {
		final int w = ip.getWidth();
		final int h = ip.getHeight();
		
		final long[] ir, ig, ib, im;
		{
			// Split color channels
			final int[] p = (int[]) ip.getPixels();
			final byte[] r = new byte[p.length],
			             g = new byte[p.length],
			             b = new byte[p.length];
			for (int i=0; i<p.length; ++i) {
				final int a = p[i];
				r[i] = (byte)((a >> 16)&0xff);
				g[i] = (byte)((a >> 8)&0xff);
				b[i] = (byte)(a&0xff);
			}
			//
			ir = FastIntegralImage.longIntegralImage(r, w, h);
			ig = FastIntegralImage.longIntegralImage(g, w, h);
			ib = FastIntegralImage.longIntegralImage(b, w, h);
			im = null == mask ? null : FastIntegralImage.longIntegralImage((byte[])mask.getPixels(), w, h);
		}
		
		// Generate images
		final ImageBytes[] bis = new ImageBytes[Loader.getHighestMipMapLevel(patch) + 1];
		//
		if (null == mask) {
			bis[0] = new ImageBytes(P.asRGBBytes((int[])ip.getPixels()), ip.getWidth(), ip.getHeight());
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				bis[i] = new ImageBytes(new byte[][]{FastIntegralImage.scaleAreaAverage(ir, w+1, h+1, wk, hk),
				                                     FastIntegralImage.scaleAreaAverage(ig, w+1, h+1, wk, hk),
				                                     FastIntegralImage.scaleAreaAverage(ib, w+1, h+1, wk, hk)},
				                        wk, hk);
			}
		} else {
			// With alpha channel
			bis[0] = new ImageBytes(P.asRGBABytes((int[])ip.getPixels(), (byte[])mask.getPixels(), null), ip.getWidth(), ip.getHeight());
			for (int i=1; i<bis.length; i++) {
				final int K = (int) Math.pow(2, i),
			          wk = w / K,
			          hk = h / K;
				// An image of the scaled size
				bis[i] = new ImageBytes(new byte[][]{FastIntegralImage.scaleAreaAverage(ir, w+1, h+1, wk, hk),
				                                     FastIntegralImage.scaleAreaAverage(ig, w+1, h+1, wk, hk),
				                                     FastIntegralImage.scaleAreaAverage(ib, w+1, h+1, wk, hk),
				                                     FastIntegralImage.scaleAreaAverage(im, w+1, h+1, wk, hk)},
				                        wk, hk);
			}
		}

		return bis;
	}
}
