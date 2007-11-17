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

	public Snapshot(Displayable d) {
		this.d = d;
	}

	/** Remake the image from the Displayable only in the case of Patch. The snapshot ignores the alphas of the channels of the Patch.*/
	public void remake() {
		// TODO this function should disappear for mipmaps
		/*
		if (d.getClass().equals(Patch.class)) {
			final Patch p = (Patch)d;
			final Image image = d.getProject().getLoader().fetchImage(p, 1.0); // not the snapshot, so retrieve for magnification 1.0
			final Image snap = Snapshot.createSnap(p, image, Snapshot.SCALE);
			d.getProject().getLoader().cacheSnapshot(d.getId(), snap);
			p.updateInDatabase("tiff_snapshot");
		}
		*/
	}

	public void paintTo(final Graphics2D g, final Layer layer, final double mag) {
		if (layer.getParent().areSnapshotsEnabled()) {
			if (d.getClass().equals(Patch.class) && !d.getProject().getLoader().isSnapPaintable(d.getId())) {
				Snapshot.paintAsBox(g, d);
			} else {
				d.paint(g, mag, false, (d.getClass().equals(Patch.class) ? ((Patch)d).getChannelAlphas() : 1), layer);
			}
		} else {
			paintAsBox(g, d);
		}
	}

	static final public void paintAsBox(final Graphics2D g, final Displayable d) {
		double[] c = new double[]{0,0,  d.getWidth(),0,  d.getWidth(),d.getHeight(),  0,d.getHeight()};
		final double[] c2 = new double[8];
		d.getAffineTransform().transform(c, 0, c2, 0, 4);
		g.setColor(d.getColor());
		g.drawLine((int)c2[0], (int)c2[1], (int)c2[2], (int)c2[3]);
		g.drawLine((int)c2[2], (int)c2[3], (int)c2[4], (int)c2[5]);
		g.drawLine((int)c2[4], (int)c2[5], (int)c2[6], (int)c2[7]);
		g.drawLine((int)c2[6], (int)c2[7], (int)c2[0], (int)c2[1]);
	}

	/** Ensures the snap awt returned is of the proper type. Avoids using getScaledInstance, which generates RGB images (big) and is slower than the equivalent code from Graphics2D. The @param awt Image is expected to have the same dimensions as the ImagePlus from which it originates. */
	static public Image createSnap(final Patch p, final Image awt, final double mag) {
		final int w = (int)Math.ceil(awt.getWidth(null) * mag);
		final int h = (int)Math.ceil(awt.getHeight(null) * mag); // NOTE: can't get the Patch width and height because in the event of a rotation, it will work incorrectly (it returns the width and height of the bounding box, not of the image). But I can't fetch the actual image either, because this method is heavily used from the Loader and I would have to lock/unlock its usage, which is a pain.
		if (0 == w || 0 == h) {
			Utils.log2("Snapshot.createSnap: width or height are zero (?)");
			return null;
		}
		try {
		if (null != p.getLayer() && p.getLayer().getParent().snapshotsQuality()) {
			// best, but very slow
			Image snap = awt.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING);
			switch (p.getType()) {
				case ImagePlus.GRAY16:
				case ImagePlus.GRAY32:
				case ImagePlus.GRAY8:
					// convert to 8-bit (reduce memory footprint by 4x)
					final Image snap8 = new ImagePlus("", snap).getProcessor().convertToByte(true).createImage();
					snap.flush();
					return snap8;
				//case ImagePlus.COLOR_RGB:
				//case ImagePlus.COLOR_256:
				default:
					return snap;
			}

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
