package ini.trakem2.display.paint;

import java.awt.PaintContext;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

final class USHORTPaintContext implements PaintContext
{
	private final ComponentColorModel ccm;
	
	private WritableRaster raster;
	private final short[] value;
	
	USHORTPaintContext(final ComponentColorModel ccm, final short[] value) {
		this.value = value;
		this.ccm = ccm;
	}

	@Override
	public final Raster getRaster(final int x, final int y, final int w, final int h) {
		if (null == raster || raster.getWidth() != w || raster.getHeight() != h) {
			raster = ccm.createCompatibleWritableRaster(w, h);
		}
		final int lenY = y+h;
		final int lenX = x+w;
		for (int j=y; j<lenY; ++j) {
			for (int i=x; i<lenX; ++i) {
				raster.setDataElements(i-x, j-y, value);
			}
		}
		return raster;
	}

	@Override
	public final ColorModel getColorModel() {
		return ccm;
	}

	@Override
	public final void dispose() {
		raster = null;
	}
}