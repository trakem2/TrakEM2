package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;

import java.util.Map;

public class NormalizeLocalContrast implements IFilter
{
	protected int brx = 500, bry = 500;
	protected float stds = 3;
	protected boolean cent = true, stret = true;

	public NormalizeLocalContrast() {}

	public NormalizeLocalContrast(
			final int blockRadiusX,
			final int blockRadiusY,
			final float stdDevs,
			final boolean center,
			final boolean stretch) {
		set(blockRadiusX, blockRadiusY, stdDevs, center, stretch);
	}

	private final void set(final int blockRadiusX,
			final int blockRadiusY,
			final float stdDevs,
			final boolean center,
			final boolean stretch) {
		this.brx = blockRadiusX;
		this.bry = blockRadiusY;
		this.stds = stdDevs;
		this.cent = center;
		this.stret = stretch;
	}

	public NormalizeLocalContrast(final Map<String,String> params) {
		try {
			set(Integer.parseInt(params.get("brx")),
			    Integer.parseInt(params.get("bry")),
			    Float.parseFloat(params.get("stds")),
			    Boolean.parseBoolean(params.get("stret")),
			    Boolean.parseBoolean(params.get("cent")));
		} catch (final NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create LocalContrast filter!", nfe);
		}
	}


	@Override
	public ImageProcessor process(final ImageProcessor ip) {
		try {
			mpicbg.ij.plugin.NormalizeLocalContrast.run(ip, brx, bry, stds, cent, stret);
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return ip;
	}

	@Override
	public String toXML(final String indent) {
		return new StringBuilder(indent)
		.append("<t2_filter class=\"").append(getClass().getName())
		.append("\" brx=\"").append(brx)
		.append("\" bry=\"").append(bry)
		.append("\" stds=\"").append(stds)
		.append("\" cent=\"").append(cent)
		.append("\" stret=\"").append(stret)
		.append("\" />\n").toString();
	}

	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == NormalizeLocalContrast.class) {
			final NormalizeLocalContrast c = (NormalizeLocalContrast)o;
			return brx == c.brx && bry == c.bry && stds == c.stds && cent == c.cent && stret == c.stret;
		}
		return false;
	}
}
