package mpicbg.trakem2.transform;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import jitk.spline.ThinPlateR2LogRSplineKernelTransform;

import org.apache.commons.codec.binary.Base64;

public class ThinPlateSplineTransform implements CoordinateTransform {

	private static final long serialVersionUID = 2214564089916179653L;

	private ThinPlateR2LogRSplineKernelTransform tps;

	public ThinPlateSplineTransform() {
	}

	public ThinPlateSplineTransform(final ThinPlateR2LogRSplineKernelTransform tps) {
		this.tps = tps;
	}

	static private String encodeBase64(final double[] src) {
		final byte[] bytes = new byte[src.length * 8];
		for (int i = 0, j = -1; i < src.length; ++i) {
			final long bits = Double.doubleToLongBits(src[i]);
			bytes[++j] = (byte)(bits >> 56);
			bytes[++j] = (byte)((bits >> 48) & 0xffL);
			bytes[++j] = (byte)((bits >> 40) & 0xffL);
			bytes[++j] = (byte)((bits >> 32) & 0xffL);
			bytes[++j] = (byte)((bits >> 24) & 0xffL);
			bytes[++j] = (byte)((bits >> 16) & 0xffL);
			bytes[++j] = (byte)((bits >> 8) & 0xffL);
			bytes[++j] = (byte)(bits & 0xffL);
		}
		final Deflater deflater = new Deflater();
		deflater.setInput(bytes);
		deflater.finish();
		final byte[] zipped = new byte[bytes.length];
		final int n = deflater.deflate(zipped);
		if (n == bytes.length)
			return '@' + Base64.encodeBase64String(bytes);
		else
			return Base64.encodeBase64String(Arrays.copyOf(zipped, n));
	}

	static private double[] decodeBase64(final String src, final int n)
			throws DataFormatException {
		final byte[] bytes;
		if (src.charAt(0) == '@') {
			bytes = Base64.decodeBase64(src.substring(1));
		} else {
			bytes = new byte[n * 8];
			final byte[] zipped = Base64.decodeBase64(src);
			final Inflater inflater = new Inflater();
			inflater.setInput(zipped, 0, zipped.length);

			inflater.inflate(bytes);
			inflater.end();
		}
		final double[] doubles = new double[n];
		for (int i = 0, j = -1; i < n; ++i) {
			long bits = 0L;
			bits |= (bytes[++j] & 0xffL) << 56;
			bits |= (bytes[++j] & 0xffL) << 48;
			bits |= (bytes[++j] & 0xffL) << 40;
			bits |= (bytes[++j] & 0xffL) << 32;
			bits |= (bytes[++j] & 0xffL) << 24;
			bits |= (bytes[++j] & 0xffL) << 16;
			bits |= (bytes[++j] & 0xffL) << 8;
			bits |= bytes[++j] & 0xffL;
			doubles[i] = Double.longBitsToDouble(bits);
		}
		return doubles;
	}

	@Override
	public double[] apply(final double[] location) {

		final double[] out = location.clone();
		applyInPlace(out);
		return out;
	}

	@Override
	public void applyInPlace(final double[] location) {

		tps.applyInPlace(location);
	}

	@Override
	public void init(final String data) throws NumberFormatException {

		final String[] fields = data.split("\\s+");

		int i = 0;

		final int ndims = Integer.parseInt(fields[++i]);
		final int nLm = Integer.parseInt(fields[++i]);

		double[][] aMtx = null;
		double[] bVec = null;
		if (fields[i + 1].equals("null")) {
			// System.out.println(" No affines " );
			++i;
		} else {
			aMtx = new double[ndims][ndims];
			bVec = new double[ndims];

			final double[] values;
			try {
				values = decodeBase64(fields[++i], ndims * ndims + ndims);
			} catch (final DataFormatException e) {
				throw new NumberFormatException("Failed decoding affine matrix.");
			}
			int l = -1;
			for (int k = 0; k < ndims; k++)
				for (int j = 0; j < ndims; j++) {
					aMtx[k][j] = values[++l];
				}
			for (int j = 0; j < ndims; j++) {
				bVec[j] = values[++l];
			}
		}

		final double[] values;
		try {
			values = decodeBase64(fields[++i], 2 * nLm * ndims);
		} catch (final DataFormatException e) {
			throw new NumberFormatException("Failed decoding landmarks and weights.");
		}
		int k = -1;

		// parse control points
		final double[][] srcPts = new double[ndims][nLm];
		for (int l = 0; l < nLm; l++)
			for (int d = 0; d < ndims; d++) {
				srcPts[d][l] = values[++k];
			}

		// parse control point coordinates
		int m = -1;
		final double[] dMtxDat = new double[nLm * ndims];
		for (int l = 0; l < nLm; l++)
			for (int d = 0; d < ndims; d++) {
				dMtxDat[++m] = values[++k];
			}

		tps = new ThinPlateR2LogRSplineKernelTransform(srcPts, aMtx, bVec, dMtxDat);

	}

	@Override
	public String toXML(final String indent) {
		final StringBuilder xml = new StringBuilder();
		xml.append(indent).append("<ict_transform class=\"")
				.append(this.getClass().getCanonicalName())
				.append("\" data=\"");
		toDataString(xml);
		return xml.append("\"/>").toString();
	}

	@Override
	public String toDataString() {
		final StringBuilder data = new StringBuilder();
		toDataString(data);
		return data.toString();
	}

	@Override
	public CoordinateTransform copy() {
		return new ThinPlateSplineTransform(tps);
	}

	private final void toDataString(final StringBuilder data) {

		data.append("ThinPlateSplineR2LogR");

		final int ndims = tps.getNumDims();
		final int nLm = tps.getNumLandmarks();

		data.append(' ').append(ndims); // dimensions
		data.append(' ').append(nLm); // landmarks

		if (tps.getAffine() == null) {
			data.append(' ').append("null"); // aMatrix
		} else {
			final double[][] aMtx = tps.getAffine();
			final double[] bVec = tps.getTranslation();

			final double[] buffer = new double[ndims * ndims + ndims];
			int k = -1;
			for (int i = 0; i < ndims; i++)
				for (int j = 0; j < ndims; j++) {
					buffer[++k] = aMtx[i][j];
				}
			for (int i = 0; i < ndims; i++) {
				buffer[++k] = bVec[i];
			}

			data.append(' ').append(encodeBase64(buffer));
		}

		final double[][] srcPts = tps.getSourceLandmarks();
		final double[] dMtxDat = tps.getKnotWeights();

		final double[] buffer = new double[2 * nLm * ndims];
		int k = -1;
		for (int l = 0; l < nLm; l++)
			for (int d = 0; d < ndims; d++)
				buffer[++k] = srcPts[d][l];

		for (int i = 0; i < ndims * nLm; i++) {
			buffer[++k] = dMtxDat[i];
		}
		data.append(' ').append(encodeBase64(buffer));
	}
}
