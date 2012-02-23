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
	
	/** Pre-multiplies alpha. */
	public static final int[] blendPre(final byte[] pi, final byte[] pm) {
		final int[] p = new int[pi.length];
		for (int i=0; i<p.length; ++i) {
			final int a = (pm[i]&0xff);
			final double K = a / 255.0f;
			final int c = (int)((pi[i]&0xff) * K + 0.5f);
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
			final double K = a / 255.0f;
			p[i] = (a << 24)
			       | (((int)((r[i]&0xff) * K + 0.5f)) << 16)
			       | (((int)((g[i]&0xff) * K + 0.5f)) <<  8)
			       |  ((int)((b[i]&0xff) * K + 0.5f));
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
}
