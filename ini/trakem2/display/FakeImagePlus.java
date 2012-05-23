/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;


import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.util.Collection;

/** Need a non-null ImagePlus for the ImageCanvas, even if fake. */
public class FakeImagePlus extends ImagePlus {
	
	private int w;
	private int h;
	private Display display;
	private int type;
	
	public FakeImagePlus(int width, int height, Display display) {
		w = width;
		h = height;
		this.display = display;
		setProcessor("", new FakeProcessor(width, height));
		type = ImagePlus.GRAY8;
	}
	public void setProcessor(String title, ImageProcessor ip) {
		if (! (ip instanceof FakeProcessor)) return;
		super.setProcessor(title, ip);
	}
	public void flush() {} // disabled
	protected Display getDisplay() {
		return display;
	}
	public int getType() { return type; }
	public int getWidth() {
		// trick the canvas, but not the ROIs
		//Class dc = null; try { dc = Class.forName("ini.trakem2.DisplayCanvas"); } catch (Exception e) {}
		if ((Utils.caller(this)).endsWith("ImageCanvas")) return 4;
		return w;
	}
	public int getHeight() {
		// trick the canvas, but not the ROIs
		//Class dc = null; try { dc = Class.forName("ini.trakem2.DisplayCanvas"); } catch (Exception e) {}
		if ((Utils.caller(this)).endsWith("ImageCanvas")) return 4;
		return h;
	}

	/** Used to resize the canvas. */
	public void setDimensions(int width, int height) {
		this.w = width;
		this.h = height;
	}

	public int[] getPixel(int x, int y) {
		try {
			//return display.getLayer().getPixel(x, y, display.getCanvas().getMagnification());
			return ((FakeProcessor)getProcessor()).getPixel(display.getCanvas().getMagnification(), x, y, null);
		} catch (Exception e) {
			IJError.print(e);
		}
		return new int[4];
	}

	private class FakeProcessor extends ByteProcessor {
		FakeProcessor(int width, int height) {
			// create a 4x4 processor (just because, perhaps to skip nulls)
			super(4,4);
		}
		/** Override to return the pixel of the Patch under x,y, if any. */
		public int getPixel(final int x, final int y) {
			return getPixel(display.getCanvas().getMagnification(), x, y);
		}
		public int getPixel(double mag, final int x, final int y) {
			final Collection<? extends Displayable> under = display.getLayer().find(Patch.class, x, y, true);
			if (null == under || under.isEmpty()) return 0; // zeros
			for (final Patch p : (Collection<Patch>)under) {
				if (!p.isVisible()) continue;
				FakeImagePlus.this.type = p.getType(); // for proper value string display
				// TODO: edit here when adding layer mipmaps
				return p.getPixel(mag, x, y);
			}
			// Outside images, hence reset:
			FakeImagePlus.this.type = ImagePlus.GRAY8;
			return 0;
		}
		/** @param iArray is ignored. */
		public int[] getPixel(int x, int y, int[] iArray) {
			return getPixel(1.0, x, y, iArray);
		}
		/** @param iArray is ignored. */
		public int[] getPixel(double mag, int x, int y, int[] iArray) {
			final Collection<? extends Displayable> under = display.getLayer().find(Patch.class, x, y, true);
			if (null != under && !under.isEmpty()) {
				for (final Patch p : (Collection<Patch>)under) {
					if (!p.isVisible()) continue;
					FakeImagePlus.this.type = p.getType(); // for proper value string display
					return p.getPixel(mag, x, y, iArray);
				}
			}
			// Outside images, hence reset:
			FakeImagePlus.this.type = ImagePlus.GRAY8;
			return new int[4];
		}
		public int getWidth() { return w; }
		public int getHeight() { return h; }

		public void setColorModel(ColorModel cm) {
			display.getSelection().setLut(cm);
		}
		@Override
		public void setPixels(Object ob) {} // disabled
	}

	// Like ImagePlus.d2s, which is private
	private final String doubleToString(final double n) {
		return n == (int)n ? Integer.toString((int)n)
				   : IJ.d2s(n);
	}

	@Override
	public void mouseMoved(final int x, final int y) {
		final Calibration cal = getCalibration();
		final StringBuilder sb = new StringBuilder(64).append("x=").append(doubleToString(cal.getX(x))).append(' ').append(cal.getUnit())
		  .append(", y=").append(doubleToString(cal.getY(y))).append(' ').append(cal.getUnit());
		if (ProjectToolbar.getToolId() <= ProjectToolbar.SELECT) {
			sb.append(", value=");
			final int[] v = getPixel(x, y);
			switch (type) {
				case ImagePlus.GRAY8:
				case ImagePlus.GRAY16:
					sb.append(v[0]);
					break;
				case ImagePlus.COLOR_256:
				case ImagePlus.COLOR_RGB:
					sb.append(v[0]).append(',').append(v[1]).append(',').append(v[2]);
					break;
				case ImagePlus.GRAY32:
					sb.append(Float.intBitsToFloat(v[0]));
					break;
				default:
					sb.setLength(sb.length() -8); // no value info
					break;
			}
		}
		// Utils.showStatus would be too slow at reporting, because it waits for fast subsequent calls.
		IJ.showStatus(sb.toString());
	}

	// TODO: use layerset virtualization
	public ImageStatistics getStatistics(int mOptions, int nBins, double histMin, double histMax) {
		Displayable active = display.getActive();
		if (null == active || !(active instanceof Patch)) {
			Utils.log("No patch selected.");
			return super.getStatistics(mOptions, nBins, histMin, histMax); // TODO can't return null, but something should be done about it.
		}
		ImagePlus imp = active.getProject().getLoader().fetchImagePlus((Patch)active);
		ImageProcessor ip = imp.getProcessor(); // don't create a new onw every time // ((Patch)active).getProcessor();
		Roi roi = super.getRoi();
		if (null != roi) {
			// translate ROI to be meaningful for the Patch
			int patch_x = (int)active.getX();
			int patch_y = (int)active.getY();
			Rectangle r = roi.getBounds();
			roi.setLocation(patch_x - r.x, patch_y - r.y);
		}
		ip.setRoi(roi); // even if null, to reset
		ip.setHistogramSize(nBins);
		Calibration cal = getCalibration();
		if (getType()==GRAY16&& !(histMin==0.0&&histMax==0.0))
			{histMin=cal.getRawValue(histMin); histMax=cal.getRawValue(histMax);}
		ip.setHistogramRange(histMin, histMax);
		ImageStatistics stats = ImageStatistics.getStatistics(ip, mOptions, cal);
		ip.setHistogramSize(256);
		ip.setHistogramRange(0.0, 0.0);
		return stats;
	}

	/** Returns a virtual stack made of boxes with the dimension of the ROI or the whole layer, so that pixels are retrieved on the fly. */
	public ImageStack getStack() {
		return null;
	}

	/** Returns 1. */
	public int getStackCount() {
		return 1;
		//return display.getLayer().getParent().size();
	}

	public boolean isVirtual() { return true; }

	/** Returns the super, which is a dummy 4x4 */ //Returns a virtual stack made of boxes with the dimension of the ROI or the whole layer, so that pixels are retrieved on the fly.
	public ImageProcessor getProcessor() {
		return super.getProcessor();
	}

	/** Forward to LayerSet. */
	public void setCalibration(Calibration cal) {
		try { super.setCalibration(cal); } catch (Throwable e) { IJError.print(e); }
		display.getLayer().getParent().setCalibration(cal);
	}

	public void setCalibrationSuper(Calibration cal) {
		super.setCalibration(cal);
	}

	public Calibration getCalibration() {
		// initialization problems with ij1.40a
		if (null == display || null == display.getLayer()) return new Calibration();
		return display.getLayer().getParent().getCalibrationCopy();
	}

	/** Forward kill roi to the last_temp of the associated Display. */
	public void killRoi() {
		if (null!=roi) {
			saveRoi();
			roi = null;
			ImageProcessor ip = getProcessor();
			if (null != ip) ip.resetRoi();
		}
	}

	public synchronized void setSlice(int slice) {}

	public void updateAndRepaintWindow() {
		// TODO: if a selected image is a stack, the LUT applies to it as well...
		Display.repaint(display.getLayer(), display.getSelection().getBox(), 0);
	}
}
