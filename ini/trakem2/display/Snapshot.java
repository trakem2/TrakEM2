/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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

import ini.trakem2.utils.Utils;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.awt.image.IndexColorModel;
import java.awt.Image;
import java.awt.AlphaComposite;
import java.awt.geom.AffineTransform;
import java.awt.Composite;


public class Snapshot {

	private Displayable d;
	public static final double SCALE = 0.25;
	public static final int SCALE_METHOD = Image.SCALE_SMOOTH; //SCALE_FAST;

	/** Ensures the snap awt returned is of the proper type. */
	/*
	static public Image createSnap(final Patch p, final Image awt, final double mag) {
		switch(p.getType()) {
			case ImagePlus.COLOR_256: // preserve LUT
				// TODO needs testing
				ImagePlus imp_lut = p.getProject().getLoader().fetchImagePlus(p);
				return imp_lut.getProcessor().resize((int)Math.ceil(p.getWidth() * mag), (int)Math.ceil(p.getHeight() * mag)).createImage();
			case ImagePlus.COLOR_RGB:
				return awt.getScaledInstance((int)Math.ceil(p.getWidth() * mag), (int)Math.ceil(p.getHeight() * mag), Snapshot.SCALE_METHOD);
			default:
				// scale and make 8-bit again
				ImagePlus imp = new ImagePlus("s", awt.getScaledInstance((int)Math.ceil(p.getWidth() * mag), (int)Math.ceil(p.getHeight() * mag), Snapshot.SCALE_METHOD));
				//Utils.log2("Created snap default");
				if (ImagePlus.GRAY8 != imp.getType()) {
					return imp.getProcessor().convertToByte(true).createImage();
				} else {
					return imp.getProcessor().createImage();
				}
				// the above is more CPU intensive but avoids reloading the ImagePlus
		}
	}
	*/

	public Snapshot(Displayable d) {
		this.d = d;
	}

	/** Remake the image from the Displayable only in the case of Patch. The snapshot ignores the alphas of the channels of the Patch.*/
	public void remake() {
		double w = d.getWidth();
		double h = d.getHeight();
		if (0 == w || 0 == h) {
			// when profiles have no points yet
			w = h = Math.ceil(1.0D / SCALE);
		}
		if (d instanceof Patch) {
			
			//Image image = null;
			/*
			if (((Patch)d).getChannelAlphas() == 0xffffffff) {
				image = d.getProject().getLoader().fetchImage((Patch)d);
			} else {
				image = d.getProject().getLoader().fetchImagePlus((Patch)d).getProcessor().createImage();
			}
			*/
			final Patch p = (Patch)d;
			final Image image = d.getProject().getLoader().fetchImage(p, 1.0); // not the snapshot, so retrieve for magnification 1.0
			final Image snap = Snapshot.createSnap(p, image, Snapshot.SCALE); //image.getScaledInstance((int)(w * SCALE), (int)(h * SCALE), SCALE_METHOD);
			/* // debug
			final BufferedImage bi = new BufferedImage(snap.getWidth(null), snap.getHeight(null), BufferedImage.TYPE_BYTE_INDEXED);
			bi.getGraphics().drawImage(snap, 0, 0, null);
			try {
				new Thread() { public void run() { new ImagePlus("snap", bi).show();} }.start();
				Thread.sleep(1000);
			} catch (Exception ee) {
			}
			*/
			// easier:
			// NO, doesn't follow the general model of quality/non-quality!!
			/*
			final ImageProcessor ip = d.getProject().getLoader().fetchImagePlus(p, false).getProcessor();
			ip.setInterpolate(true);
			ImageProcessor ip_scaled = ip.resize((int)Math.ceil(p.getWidth() * SCALE), (int)Math.ceil(p.getHeight() * SCALE));
			ip_scaled.setColorModel(ip.getColorModel()); // the LUT !
			final Image snap = ip_scaled.createImage();
			*/

			d.getProject().getLoader().cacheSnapshot(d.getId(), snap);
			p.updateInDatabase("tiff_snapshot");
		}
	}

	/** Fetch from database or the cache if this is a Patch. */
	public Image reload() {
		//Image image = d.getProject().getLoader().fetchSnapshot(d); // will cache it
		//if (null == image) {
		//	remake(); // some error ocurred, so failsafe.
		//}
		if (d.getClass().equals(Patch.class)) { // instanceof Patch
			return d.getProject().getLoader().fetchSnapshot((Patch)d);
		}
		return null;
	}

	public void paintTo(Graphics2D g, final Layer layer) {
		d.paint(g, layer);
	}

	static private int c = 0;

	static public void printC() {
		Utils.log2("c is " + c);
	}

	/** Ensures the snap awt returned is of the proper type. Avoids using getScaledInstance, which generates RGB images (big) and is slower than the equivalent code from Graphics2D. */
	static public Image createSnap(final Patch p, final Image awt, final double mag) {
		final int w = (int)Math.ceil(p.getWidth() * mag);
		final int h = (int)Math.ceil(p.getHeight() * mag);
		try {
		if (p.getLayer().getParent().snapshotsQuality()) {
			/*
			try {
			new Exception("CREATE SNAP:").printStackTrace();
			} catch (Exception eee) {
				Utils.log2("HUH?");
			}
			*/
			// best, but very slow
			Utils.log2("QUALITY SNAP! " + (c++));
			return awt.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);

			//second best, much faster, should be slightly blurry but looks grainy as well
			/*
			int type = p.getType();
			int wa = awt.getWidth(null);
			int ha = awt.getHeight(null);
			Image bim = awt;
			do {
				wa /= 2;
				ha /= 2;
				if (wa < w) wa = w;
				if (ha < h) ha = h;
				BufferedImage tmp = null;
				switch (type) {
					case ImagePlus.COLOR_RGB:
						tmp = new BufferedImage(wa, ha, BufferedImage.TYPE_INT_RGB);
						break;
					default:
						tmp = new BufferedImage(wa, ha, BufferedImage.TYPE_BYTE_INDEXED, cm);
						break;
				}
				Graphics2D g2 = tmp.createGraphics();
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g2.drawImage(bim, 0, 0, wa, ha, null);
				g2.dispose();

				bim = tmp;
			} while (wa != w || ha != h);
			return bim;
			*/
		}
		} catch (Exception e) {
			Utils.log2("createSnap: layer is " + p.getLayer());
			new ini.trakem2.utils.IJError(e);
		}
		// else, grainy images by nearest-neighbor
		BufferedImage bi = null;
		Graphics2D g = null;
		switch (p.getType()) {
			case ImagePlus.COLOR_RGB:
				bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				break;
			case ImagePlus.COLOR_256:
				ImagePlus imp_lut = p.getProject().getLoader().fetchImagePlus(p);
				return imp_lut.getProcessor().resize(w, h).createImage();
			default:
				bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
				break;
		}
		g = bi.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		//g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
		g.drawImage(awt, 0, 0, w, h, null);
		g.dispose();
		Utils.log2("NON quality snap! ");
		return bi;
	}

	static private IndexColorModel cm;
	static {
		// from ij.process.ImageProcessor by Wayne Rasband.
		byte[] rLUT = new byte[256];
		byte[] gLUT = new byte[256];
		byte[] bLUT = new byte[256];
		for(int i=0; i<256; i++) {
			rLUT[i]=(byte)i;
			gLUT[i]=(byte)i;
			bLUT[i]=(byte)i;
		}
		cm = new IndexColorModel(8, 256, rLUT, gLUT, bLUT);
	}
}
