package ini.trakem2.imaging.filters;

import ij.process.ImageProcessor;

import java.util.Map;

public class RobustNormalizeLocalContrast implements IFilter
{
	protected int scaleLevel = 2, brx1 = 1000, bry1 = 1000, brx2 = 500, bry2 = 500;
	protected float stds1 = 2, stds2 = 3;

	public RobustNormalizeLocalContrast() {}

	public RobustNormalizeLocalContrast(
			final int scaleLevel,
			final int blockRadiusX1,
			final int blockRadiusY1,
			final float stdDevs1,
			final int blockRadiusX2,
			final int blockRadiusY2,
			final float stdDevs2) {
		set( scaleLevel, blockRadiusX1, blockRadiusY1, stdDevs1, blockRadiusX2, blockRadiusY2, stdDevs2 );
	}

	private final void set(
			final int scaleLevel,
			final int blockRadiusX1,
			final int blockRadiusY1,
			final float stdDevs1,
			final int blockRadiusX2,
			final int blockRadiusY2,
			final float stdDevs2) {
		this.scaleLevel = scaleLevel;
		this.brx1 = blockRadiusX1;
		this.bry1 = blockRadiusY1;
		this.stds1 = stdDevs1;
		this.brx2 = blockRadiusX2;
		this.bry2 = blockRadiusY2;
		this.stds2 = stdDevs2;
	}

	public RobustNormalizeLocalContrast(final Map<String,String> params) {
		try {
			set(
					Integer.parseInt(params.get("scalelevel")),
					Integer.parseInt(params.get("brx1")),
					Integer.parseInt(params.get("bry1")),
					Float.parseFloat(params.get("stds1")),
					Integer.parseInt(params.get("brx2")),
					Integer.parseInt(params.get("bry2")),
					Float.parseFloat(params.get("stds2")));
		} catch (final NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create LocalContrast filter!", nfe);
		}
	}


	@Override
	public ImageProcessor process(final ImageProcessor ip) {
		try {
			mpicbg.trakem2.util.RobustNormalizeLocalContrast.run(
					ip, scaleLevel, brx1, bry1, stds1, brx2, bry2, stds2);
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return ip;
	}

	@Override
	public String toXML(final String indent) {
		return new StringBuilder(indent)
		.append("<t2_filter class=\"").append(getClass().getName())
		.append("\" scalelevel=\"").append(scaleLevel)
		.append("\" brx1=\"").append(brx1)
		.append("\" bry1=\"").append(bry1)
		.append("\" stds1=\"").append(stds1)
		.append("\" brx2=\"").append(brx2)
		.append("\" bry2=\"").append(bry2)
		.append("\" stds2=\"").append(stds2)
		.append("\" />\n").toString();
	}

	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == RobustNormalizeLocalContrast.class) {
			final RobustNormalizeLocalContrast c = (RobustNormalizeLocalContrast)o;
			return
					scaleLevel == c.scaleLevel &&
					brx1 == c.brx1 &&
					bry1 == c.bry1 &&
					stds1 == c.stds1 &&
					brx2 == c.brx2 &&
					bry2 == c.bry2 &&
					stds2 == c.stds2;
		}
		return false;
	}
}
