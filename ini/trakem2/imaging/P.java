package ini.trakem2.imaging;

public final class P {
	public static final int[] blend(final byte[] pi, final byte[] pm) {
		final int[] p = new int[pi.length];
		for (int i=0; i<p.length; ++i) {
			final int c = (pi[i]&0xff);
			p[i] = ((pm[i]&0xff) << 24) | (c << 16) | (c << 8) | c;
		}
		return p;
	}
	
	/** Puts the {@param pi} (the greyscale channel) into the R, G and B components of the returned {@code int[]}
	 * after having multiplied them by the {@param pm} (the alpha channel); the alpha channel gets inserted into
	 * the int[] as well. */
	public static final int[] blendPre(final byte[] pi, final byte[] pm) {
		final int[] p = new int[pi.length];
		for (int i=0; i<p.length; ++i) {
			final int a = (pm[i]&0xff);
			final double K = a / 255.0;
			final int c = (int)((pi[i]&0xff) * K + 0.5);
			p[i] = (a << 24) | (c << 16) | (c << 8) | c;
		}
		return p;
	}
	
	public static final int[] blend(final byte[] r, final byte[] g, final byte[] b, final byte[] a) {
		final int[] p = new int[r.length];
		for (int i=0; i<p.length; ++i) {
			p[i] = ((a[i]&0xff) << 24) | ((r[i]&0xff) << 16) | ((g[i]&0xff) << 8) | (b[i]&0xff);
		}
		return p;
	}

	/** Pre-multiplies alpha. */
	public static final int[] blendPre(final byte[] r, final byte[] g, final byte[] b, final byte[] alpha) {
		final int[] p = new int[r.length];
		for (int i=0; i<p.length; ++i) {
			final int a = (alpha[i]&0xff);
			final double K = a / 255.0;
			p[i] = (a << 24)
			       | (((int)((r[i]&0xff) * K + 0.5)) << 16)
			       | (((int)((g[i]&0xff) * K + 0.5)) <<  8)
			       |  ((int)((b[i]&0xff) * K + 0.5));
		}
		return p;
	}
	
	public static final int[] blend(final byte[] r, final byte[] g, final byte[] b) {
		final int[] p = new int[r.length];
		for (int i=0; i<p.length; ++i) {
			p[i] = ((r[i]&0xff) << 16) | ((g[i]&0xff) << 8) | (b[i]&0xff);
		}
		return p;
	}
	

	/** Merges into alpha if outside is null, otherwise returns alpha as is. */
	static public final byte[] merge(final byte[] alpha, byte[] outside) {
		if (null == outside) return alpha;
		for (int i=0; i<alpha.length; ++i) {
			alpha[i] = -1 == outside[i] ? alpha[i] : 0;
		}
		return alpha;
	}

	static public final byte[][] asRGBABytes(final int[] pixels, final byte[] alpha, byte[] outside) {
		merge(alpha, outside); // into alpha
		final byte[] r = new byte[pixels.length],
		             g = new byte[pixels.length],
		             b = new byte[pixels.length];
		for (int i=0; i<pixels.length; ++i) {
			final int x = pixels[i];
			r[i] = (byte)((x >> 16)&0xff);
			g[i] = (byte)((x >>  8)&0xff);
			b[i] = (byte) (x       &0xff);
		}
		return new byte[][]{r, g, b, alpha};
	}

	static public byte[][] asRGBBytes(final int[] pix) {
		final byte[] r = new byte[pix.length],
	                 g = new byte[pix.length],
	                 b = new byte[pix.length];
		for (int i=0; i<pix.length; ++i) {
			final int x = pix[i];
			r[i] = (byte)((x >> 16)&0xff);
			g[i] = (byte)((x >>  8)&0xff);
			b[i] = (byte) (x       &0xff);
		}
		return new byte[][]{r, g, b};
	}
}
