package ini.trakem2.imaging.filters;

import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;

import java.util.Map;

public class RankFilter implements IFilter
{
	protected double radius = 2;
	/** See {@link RankFilters}. */
	protected int type = RankFilters.MEDIAN;
	
	public RankFilter() {}

	/**
	 * @param radius The radius around every pixel to get values from for the specific algorithm {@param type}.
	 * @param type Any of the types in {@link RankFilters} such as {@link RankFilters#MEDIAN, RankFilters#DESPECKLE}, etc.
	 */
	public RankFilter(double radius, int type) {
		this.radius = radius;
	}
	
	public RankFilter(Map<String,String> params) {
		try {
			this.radius = Double.parseDouble(params.get("radius"));
			this.type = Integer.parseInt(params.get("type"));
		} catch (NumberFormatException nfe) {
			throw new IllegalArgumentException("Cannot create RankFilter!", nfe);
		}
	}

	@Override
	public ImageProcessor process(ImageProcessor ip) {
		RankFilters rf = new RankFilters();
		rf.rank(ip, radius, RankFilters.MEDIAN);
		return ip;
	}

	@Override
	public String toXML(String indent) {
		return new StringBuilder(indent)
			.append("<t2_filter class=\"").append(getClass().getName())
			.append("\" radius=\"").append(radius)
			.append("\" type=\"").append(type)
			.append("\" />\n").toString();
	}
	@Override
	public boolean equals(final Object o) {
		if (null == o) return false;
		if (o.getClass() == getClass()) {
			final RankFilter r = (RankFilter)o;
			return type == r.type && radius == r.radius;
		}
		return false;
	}
}
