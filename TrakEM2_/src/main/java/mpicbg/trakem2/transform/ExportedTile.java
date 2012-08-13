package mpicbg.trakem2.transform;

import ij.process.ShortProcessor;

public class ExportedTile {

	public final ShortProcessor sp;
	public final int x;
	public final int y;
	public final double min;
	public final double max;

	public ExportedTile(ShortProcessor sp, int x, int y, double min, double max)
	{
		this.sp = sp;
		this.x = x;
		this.y = y;
		this.min = min;
		this.max = max;
	}

}
