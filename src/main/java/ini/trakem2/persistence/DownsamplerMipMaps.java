/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
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
package ini.trakem2.persistence;


import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.display.Patch;
import ini.trakem2.imaging.FastIntegralImage;
import ini.trakem2.imaging.P;
import ini.trakem2.utils.CachingThread;
import ini.trakem2.utils.Utils;
import mpicbg.trakem2.util.Downsampler;
import mpicbg.trakem2.util.Downsampler.Pair;

public final class DownsamplerMipMaps
{
	static private final ImageBytes asBytes(final ByteProcessor bp) {
		 return new ImageBytes(new byte[][]{(byte[])bp.getPixels()}, bp.getWidth(), bp.getHeight());
	}
	static private final ImageBytes asBytes(final ShortProcessor sp) {
		return asBytes((ByteProcessor)sp.convertToByte(true));
	}
	static private final ImageBytes asBytes(final FloatProcessor fp) {
		return asBytes((ByteProcessor)fp.convertToByte(true));
	}
	static private final ImageBytes asBytes(final ColorProcessor cp) {
		return new ImageBytes(P.asRGBBytes((int[])cp.getPixels()), cp.getWidth(), cp.getHeight());
	}
	
	static private final ImageBytes asBytes(final ByteProcessor bp, final ByteProcessor mask) {
		 return new ImageBytes(new byte[][]{(byte[])bp.getPixels(), (byte[])mask.getPixels()}, bp.getWidth(), bp.getHeight());
	}
	static private final ImageBytes asBytes(final ShortProcessor sp, final ByteProcessor mask) {
		return asBytes((ByteProcessor)sp.convertToByte(true), mask);
	}
	static private final ImageBytes asBytes(final FloatProcessor fp, final ByteProcessor mask) {
		return asBytes((ByteProcessor)fp.convertToByte(true), mask);
	}
	static private final ImageBytes asBytes(final ColorProcessor cp, final ByteProcessor mask) {
		return new ImageBytes(P.asRGBABytes((int[])cp.getPixels(), (byte[])mask.getPixels(), null), cp.getWidth(), cp.getHeight());
	}
	

	/**
	 * 
	 * @param patch
	 * @param type
	 * @param ip
	 * @param alpha
	 * @param outside
	 * @param first_level If larger than zero, generate mipmaps starting at first_level.
	 * @return array of ImageByte instances, with nulls before the first_level index.
	 * @throws Exception 
	 */
	static public final ImageBytes[] create(
			final Patch patch,
			final int type,
			final ImageProcessor ip,
			final ByteProcessor alpha,
			final ByteProcessor outside,
			final int first_level) throws Exception
	{
		if (0 == first_level) {
			// Trivial case: full pyramid
			return create(patch, type, Loader.getHighestMipMapLevel(patch) + 1, ip, alpha, outside);
		}
		else if (1 == first_level) {
			// Skip only the 100% mipmap: speedier to merely downsample it and nullify it
			final ImageBytes[] p = DownsamplerMipMaps.create(patch, type, Loader.getHighestMipMapLevel(patch) + 1, ip, alpha, outside);
			CachingThread.storeForReuse(p[0].c);
			p[0] = null;
			return p;
		}
		
		// Else: scale to the first level mipmap using integral images
		
		final ImageBytes[] p = new ImageBytes[Loader.getHighestMipMapLevel(patch) + 1];
		final int w = ip.getWidth(),
				  h = ip.getHeight(),
				  scale_inv = (int) Math.pow(2, first_level), // e.g. level 2 is a scale of 1/4 or 25%
				  tw = w / scale_inv, // target width
				  th = h / scale_inv; // target height
		
		ByteProcessor bpa = alpha,
				      bpo = outside;
		final ImageProcessor ipi;
		
		// Create image at the first level using integral images
		int type2 = ImagePlus.GRAY8;
		
		if ( ImagePlus.GRAY8 == type ) {
			final long[] im = FastIntegralImage.longIntegralImage((byte[])ip.getPixels(), w, h);
			final byte[] bip = FastIntegralImage.scaleAreaAverage(im, w + 1, h + 1, tw, th);
			ipi = new ByteProcessor(tw, th, bip, ip.getColorModel());
		} else if ( ImagePlus.GRAY16 == type ) {
			final long[] im = FastIntegralImage.longIntegralImage((short[])ip.getPixels(), w, h);
			final byte[] bip = FastIntegralImage.scaleAreaAverage(im, w + 1, h + 1, tw, th);
			ipi = new ByteProcessor(tw, th, bip, null); // yes, ByteProcessor: mipmaps are 8-bit or color
		} else if ( ImagePlus.GRAY32 == type ) {
			final double[] im = FastIntegralImage.doubleIntegralImage((float[])ip.getPixels(), w, h);
			final byte[] bip = FastIntegralImage.scaleAreaAverage(im, w + 1, h + 1, tw, th);
			ipi = new ByteProcessor(tw, th, bip, null);
		} else if ( ImagePlus.COLOR_RGB == type
				 || ImagePlus.COLOR_256 == type ) {
			final int[] argb;
			if ( ImagePlus.COLOR_256 == type ) argb = (int[]) ip.convertToRGB().getPixels();
			else argb = (int[])ip.getPixels();
			final byte[] r = new byte[w * h],
					     g = new byte[r.length],
					     b = new byte[r.length];
			for (int i=0, s=0; i<r.length; ++i) {
				s = argb[i];
				r[i] = (byte)((s & 0x00ff0000) >> 16);
				g[i] = (byte)((s & 0x0000ff00) >>  8);
				b[i] = (byte)( s & 0x000000ff       );
			}
			final byte[] rs = FastIntegralImage.scaleAreaAverage(FastIntegralImage.longIntegralImage(r, w, h), w + 1, h + 1, tw, th),
					     gs = FastIntegralImage.scaleAreaAverage(FastIntegralImage.longIntegralImage(g, w, h), w + 1, h + 1, tw, th),
					     bs = FastIntegralImage.scaleAreaAverage(FastIntegralImage.longIntegralImage(b, w, h), w + 1, h + 1, tw, th);
			final int[] argbs = new int[tw * th];
			for (int i=0; i<rs.length; ++i) {
				argbs[i] = 0xff000000 // alpha: fully visible
						   & ((rs[i] & 0xff) << 16)
                           & ((gs[i] & 0xff) <<  8)
                           & ( bs[i] & 0xff       );
			}
			ipi = new ColorProcessor(tw, th, argbs);
			type2 = ImagePlus.COLOR_RGB;
		} else {
			throw new Exception( "Unhandable ImagePlus type: " + type );
		}
		
		if ( null != alpha ) {
			final long[] ima = FastIntegralImage.longIntegralImage((byte[])alpha.getPixels(), w, h);
			final byte[] balpha = FastIntegralImage.scaleAreaAverage(ima, w + 1, h + 1, tw, th);
			bpa = new ByteProcessor(tw, th, balpha, alpha.getColorModel());
		}
		
		if ( null != outside ) {
			final long[] imo = FastIntegralImage.longIntegralImage((byte[])outside.getPixels(), w, h);
			final byte[] boutside = FastIntegralImage.scaleAreaAverage(imo, w + 1, h + 1, tw, th);
			bpo = new ByteProcessor(tw, th, boutside, alpha.getColorModel());
		}
		
		// Call create with scaled-down image
		final ImageBytes[] ib = DownsamplerMipMaps.create(patch, type2, Loader.getHighestMipMapLevel(patch) + 1 - first_level, ipi, bpa, bpo);
		
		// Copy into p, leaving nulls for absent upper levels
		// Necessary to introduce a shift in the array
		System.arraycopy(ib, 0, p, first_level, ib.length);
		
		return p;
	}
	
	static public final ImageBytes[] create(
			final Patch patch,
			final int type,
			final ImageProcessor ip,
			final ByteProcessor alpha,
			final ByteProcessor outside) {
		return create(patch, type, Loader.getHighestMipMapLevel(patch) + 1, ip, alpha, outside);
	}
	
	static public final ImageBytes[] create(
			final Patch patch,
			final int type,
			final int n_levels,
			final ImageProcessor ip,
			final ByteProcessor alpha,
			final ByteProcessor outside) {
		// Create pyramid
		final ImageBytes[] p = new ImageBytes[n_levels];

		if (null == alpha && null == outside) {
			int i = 1;
			switch (type) {
				case ImagePlus.GRAY8:
					ByteProcessor bp = (ByteProcessor)ip;
					p[0] = asBytes(bp);
					while (i < p.length) {
						bp = Downsampler.downsampleByteProcessor(bp);
						p[i++] = asBytes(bp);
					}
					break;
				case ImagePlus.GRAY16:
					ShortProcessor sp = (ShortProcessor)ip;
					p[0] = asBytes(sp);
					Pair<ShortProcessor, byte[]> rs;
					while (i < p.length) {
						rs = Downsampler.downsampleShort(sp);
						sp = rs.a;
						p[i++] = new ImageBytes(new byte[][]{rs.b}, sp.getWidth(), sp.getHeight());
					}
					break;
				case ImagePlus.GRAY32:
					FloatProcessor fp = (FloatProcessor)ip;
					p[0] = asBytes(fp);
					Pair<FloatProcessor, byte[]> rf;
					while (i < p.length) {
						rf = Downsampler.downsampleFloat(fp);
						fp = rf.a;
						p[i++] = new ImageBytes(new byte[][]{rf.b}, fp.getWidth(), fp.getHeight());
					}
					break;
				case ImagePlus.COLOR_RGB:
					ColorProcessor cp = (ColorProcessor)ip;
					p[0] = asBytes(cp); // TODO the int[] could be reused
					Pair<ColorProcessor, byte[][]> rc;
					while (i < p.length) {
						rc = Downsampler.downsampleColor(cp);
						cp = rc.a;
						p[i++] = new ImageBytes(rc.b, cp.getWidth(), cp.getHeight());
					}
					break;
			}
		} else {
			// Alpha channel
			final ByteProcessor[] masks = new ByteProcessor[p.length];
			if (null != alpha && null != outside) {
				// Use both alpha and outside:
				final byte[] b1 = (byte[])alpha.getPixels(),
				             b2 = (byte[])outside.getPixels();
				for (int i=0; i<b1.length; ++i) {
					b1[i] = b2[i] != -1 ? 0 : b1[i]; // 'outside' is a binary mask, qualitative. -1 means 255
				}
				masks[0] = alpha;
				//
				int i = 1;
				Pair<ByteProcessor,ByteProcessor> pair;
				ByteProcessor a = alpha,
				              o = outside;
				while (i < p.length) {
					pair = Downsampler.downsampleAlphaAndOutside(a, o);
					a = pair.a;
					o = pair.b;
					masks[i] = a; // o is already combined into it
					++i;
				}
			} else {
				// Only one of the two is not null:
				if (null == alpha) {
					masks[0] = outside;
					int i = 1;
					while (i < p.length) {
						masks[i] = Downsampler.downsampleOutside(masks[i-1]);
						++i;
					}
				} else {
					masks[0] = alpha;
					int i = 1;
					while (i < p.length) {
						masks[i] = Downsampler.downsampleByteProcessor(masks[i-1]);
						++i;
					}
				}
			}
			// Image channels
			int i = 1;
			switch (type) {
				case ImagePlus.GRAY8:
					ByteProcessor bp = (ByteProcessor)ip;
					p[0] = asBytes(bp, masks[0]);
					while (i < p.length) {
						bp = Downsampler.downsampleByteProcessor(bp);
						p[i] = asBytes(bp, masks[i]);
						++i;
					}
					break;
				case ImagePlus.GRAY16:
					ShortProcessor sp = (ShortProcessor)ip;
					p[0] = asBytes(sp, masks[0]);
					while (i < p.length) {
						final Pair< ShortProcessor, byte[] > rs = Downsampler.downsampleShort(sp);
						sp = rs.a;
						p[i] = new ImageBytes(new byte[][]{rs.b, (byte[])masks[i].getPixels()}, sp.getWidth(), sp.getHeight());
						++i;
					}
					break;
				case ImagePlus.GRAY32:
					FloatProcessor fp = (FloatProcessor)ip;
					p[0] = asBytes(fp, masks[0]);
					while (i < p.length) {
						final Pair< FloatProcessor, byte[] > rs = Downsampler.downsampleFloat( fp );
						fp = rs.a;
						p[i] = new ImageBytes(new byte[][]{rs.b, (byte[])masks[i].getPixels()}, fp.getWidth(), fp.getHeight());
						++i;
					}
					break;
				case ImagePlus.COLOR_RGB:
					ColorProcessor cp = (ColorProcessor)ip;
					p[0] = asBytes(cp, masks[0]); // TODO the int[] could be reused
					while (i < p.length) {
						final Pair< ColorProcessor, byte[][] > rs = Downsampler.downsampleColor( cp );
						cp = rs.a;
						final byte[][] rgb = rs.b;
						p[i] = new ImageBytes(new byte[][]{rgb[0], rgb[1], rgb[2], (byte[])masks[i].getPixels()}, cp.getWidth(), cp.getHeight());
						++i;
					}
					break;
			}
		}

		return p;
	}
}
