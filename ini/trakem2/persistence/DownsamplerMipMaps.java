package ini.trakem2.persistence;


import java.util.Random;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.display.Patch;
import ini.trakem2.imaging.P;
import mpicbg.trakem2.util.Downsampler;

public final class DownsamplerMipMaps
{
	static private final ImageBytes asBytes(final ImageProcessor ip, final byte[] bytes) {
		 return new ImageBytes(new byte[][]{bytes}, ip.getWidth(), ip.getHeight());
	}
	static private final ImageBytes asBytes(final ImageProcessor ip, final byte[][] bytes) {
		 return new ImageBytes(bytes, ip.getWidth(), ip.getHeight());
	}
	static private final ImageBytes asBytes(final ByteProcessor bp) {
		 return asBytes(bp,(byte[])bp.getPixels());
	}
	static private final ImageBytes asBytes(final ShortProcessor sp) {
		return asBytes((ByteProcessor)sp.convertToByte(true));
	}
	static private final ImageBytes asBytes(final FloatProcessor fp) {
		return asBytes((ByteProcessor)fp.convertToByte(true));
	}
	static private final ImageBytes asBytes(final ColorProcessor cp) {
		return asBytes(cp,P.asRGBBytes((int[])cp.getPixels()));
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

	// TODO the int[] should be preserved for color images
	static public final ImageBytes[] create(
			final Patch patch,
			final int type,
			final ImageProcessor ip,
			final ByteProcessor alpha,
			final ByteProcessor outside) {
		// Create pyramid
		final ImageBytes[] p = new ImageBytes[Loader.getHighestMipMapLevel(patch) + 1];
		final double min = ip.getMin(),
		             max = ip.getMax();
		final int[] with = new int[ p.length ];
		final int[] height = new int[ p.length ];
		final byte[][][] pixels = new byte[ p.length ][][];
		final byte[][] alphas = new byte[ p.length ][];
		
		new Random().nextInt( n )
		
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
					while (i < p.length) {
						final Downsampler.Pair< ShortProcessor, byte[] > spp = Downsampler.downsampleShort(sp);
						p[i++] = asBytes(spp.a, spp.b);
					}
					break;
				case ImagePlus.GRAY32:
					FloatProcessor fp = (FloatProcessor)ip;
					p[0] = asBytes(fp);
					while (i < p.length) {
						final Downsampler.Pair< FloatProcessor, byte[] > fpp = Downsampler.downsampleFloat(fp);
						p[i++] = asBytes(fpp.a, fpp.b);
					}
					break;
				case ImagePlus.COLOR_RGB:
					ColorProcessor cp = (ColorProcessor)ip;
					p[0] = asBytes(cp); // TODO the int[] could be reused
					while (i < p.length) {
						final Downsampler.Pair< ColorProcessor, byte[][] > cpp = Downsampler.downsampleColor(cp);
						p[i++] = asBytes(cpp.a, cpp.b);
					}
					break;
			}
		} else {
			// Alpha channel
			final ByteProcessor[] masks = new ByteProcessor[p.length];
			masks[0] = mask;
			int i = 1;
			while (i < p.length) {
				masks[i] = Downsampler.downsampleMask(masks[i-1]);
				++i;
			}
			// Image channels
			i = 1;
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
						sp = Downsampler.downsampleShortProcessor(sp);
						sp.setMinAndMax(min, max);
						p[i] = asBytes(sp, masks[i]);
						++i;
					}
					break;
				case ImagePlus.GRAY32:
					FloatProcessor fp = (FloatProcessor)ip;
					p[0] = asBytes(fp, masks[0]);
					while (i < p.length) {
						fp = Downsampler.downsampleFloatProcessor(fp);
						fp.setMinAndMax(min, max);
						p[i] = asBytes(fp, masks[i]);
						++i;
					}
					break;
				case ImagePlus.COLOR_RGB:
					ColorProcessor cp = (ColorProcessor)ip;
					p[0] = asBytes(cp, masks[0]); // TODO the int[] could be reused
					while (i < p.length) {
						cp = Downsampler.downsampleColorProcessor(cp);
						p[i] = asBytes(cp, masks[i]);
						++i;
					}
					break;
			}
		}

		return p;
	}
}
