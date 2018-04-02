package ini.trakem2.utils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.ZDisplayable;
import mpicbg.trakem2.transform.ExportBestFlatImage;

public class SliceMaker
{
	private final int type;
	private Color background;
	private Stroke stroke;
	private boolean images_only;
	
	/**
	 * 
	 * 
	 * @param background The color to fill the background with.
	 * @param type Either ImagePlus.COLOR_RGB or ImagePlus.GRAY8
	 */
	public SliceMaker( final boolean images_only, final Color background, final int type, final Stroke stroke )
	{
		this.images_only = images_only;
		this.background = background;
		this.type = type;
		this.stroke = stroke;
	}
	
	public ImagePlus make( final Layer layer, final Rectangle srcRect, final int c_alphas, final double scale, boolean use_original_images ) {
		return use_original_images ?
				  this.fromOriginalImages(layer, srcRect, c_alphas, scale)
				: this.fromMipMaps(layer, srcRect, c_alphas, scale);
	}
	
	public ImagePlus fromOriginalImages( final Layer layer, final Rectangle srcRect, final int c_alphas, final double scale )
	{
		final int backgroundValue = ImagePlus.COLOR_RGB == this.type ?
				  0xff000000 | ((background.getRed() & 0xff) << 16) | ((background.getGreen() & 0xff) << 8) | background.getBlue()
				: (int)((background.getRed() + background.getGreen() + background.getBlue()) / 3.0 + 0.5);
		final ExportBestFlatImage e = new ExportBestFlatImage( layer.getPatches(true), srcRect, backgroundValue, scale );
		final ImageProcessor ip;
		
		if (ImagePlus.COLOR_RGB == this.type) {
			ip = SliceMaker.applyChannelAlphas(e.makeFlatColorImage().a, c_alphas);
		} else {
			ip = e.makeFlatGrayImage();
		}
		
		if ( !this.images_only ) {
			// An image that will allow painting onto the cp int[] pixels array
			BufferedImage bi = (BufferedImage) ip.createImage();
			Graphics2D g2d = bi.createGraphics();
			AffineTransform aff = new AffineTransform();
			aff.scale(scale, scale);
			aff.translate(-srcRect.x, -srcRect.y);
			g2d.setTransform(aff);

			g2d.setStroke( this.stroke );

			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON); // to smooth edges of the images
			//Object text_antialias = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			//Object render_quality = g.getRenderingHint(RenderingHints.KEY_RENDERING);
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			for (final Displayable d : layer.getDisplayables()) {
				if (d.getClass() == Patch.class) continue;
				if (!d.isVisible()) continue;
				d.paintOffscreen( g2d, srcRect, scale, false, c_alphas, null, layer.getParent().getLayers() );
			}

			for (final ZDisplayable zd : layer.getParent().getZDisplayables()) {
				zd.paintOffscreen( g2d, srcRect, scale, false, c_alphas, null, layer.getParent().getLayers()) ;
			}
		}
		
		return new ImagePlus( layer.getTitle(), ip );
	}
	
	static public ColorProcessor applyChannelAlphas( final ColorProcessor cp, final int c_alphas ) {
		final double fgreen = ((c_alphas & 0x00ff0000) >> 16) / 255d,
			         fred   = ((c_alphas & 0x0000ff00) >>  8) / 255d,
					 fblue  = ( c_alphas & 0x000000ff       ) / 255d;
		final int[] pix = (int[]) cp.getPixels();
		for (int i=0; i<pix.length; ++i) {
			final int p = pix[i];
			pix[i] =  ((int)(((p & 0x00ff0000) >> 16) * fgreen + 0.5)) << 16
					| ((int)(((p & 0x0000ff00) >>  8) * fred   + 0.5)) <<  8
					|  (int)(( p & 0x000000ff       ) * fblue  + 0.5);
		}
		
		return cp;
	}

	public ImagePlus fromMipMaps( final Layer layer, final Rectangle srcRect, final int c_alphas, final double scale ) {
		return layer.getProject().getLoader().getFlatImage( layer, srcRect, scale, c_alphas, type, images_only ? Patch.class : Displayable.class, null, true, this.background);
	}
}
