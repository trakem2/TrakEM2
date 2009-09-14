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


import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.gui.Toolbar;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.Project;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.vector.VectorString3D;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Event;
import java.awt.Image;
import java.awt.Color;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Toolkit;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.MemoryImageSource;
import java.awt.image.DirectColorModel;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Polygon;
import java.awt.geom.PathIterator;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.PixelGrabber;
import java.awt.event.KeyEvent;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.io.File;

import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.TransformMesh;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.TransformMeshMapping;

public final class Patch extends Displayable {

	private int type = -1; // unknown
	/** The channels that the currently existing awt image has ready for painting. */
	private int channels = 0xffffffff;

	/** To generate contrasted images non-destructively. */
	private double min = 0;
	private double max = 255;

	private int o_width = 0, o_height = 0;

	/** To be set after the first successful query on whether this file exists, from the Loader, via the setCurrentPath method. This works as a good path cache to avoid excessive calls to File.exists(), which shows up as a huge performance drag. */
	private String current_path = null;
	/** To be read from XML, or set when the file ImagePlus has been updated and the current_path points to something else. */
	private String original_path = null;

	/** The CoordinateTransform that transfers image data to mipmap image data. The AffineTransform is then applied to the mipmap image data. */
	private CoordinateTransform ct = null;

	/** Construct a Patch from an image. */
	public Patch(Project project, String title, double x, double y, ImagePlus imp) {
		super(project, title, x, y);
		this.type = imp.getType();
		// Color LUT in ImageJ is a nightmare of inconsistency. We set the COLOR_256 only for 8-bit images that are LUT images themselves; not for 16 or 32-bit images that may have a color LUT (which, by the way, ImageJ tiff encoder cannot save with the tif file.)
		if (ImagePlus.GRAY8 == this.type && imp.getProcessor().isColorLut()) this.type = ImagePlus.COLOR_256;
		this.min = imp.getProcessor().getMin();
		this.max = imp.getProcessor().getMax();
		checkMinMax();
		this.o_width = imp.getWidth();
		this.o_height = imp.getHeight();
		this.width = (int)o_width;
		this.height = (int)o_height;
		project.getLoader().cache(this, imp);
		addToDatabase();
	}

	/** Reconstruct a Patch from the database. The ImagePlus will be loaded when necessary. */
	public Patch(Project project, long id, String title,
		     double width, double height,
		     int o_width, int o_height,
		     int type, boolean locked, double min, double max, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.type = type;
		this.min = min;
		this.max = max;
		this.width = width;
		this.height = height;
		this.o_width = o_width;
		this.o_height = o_height;
		checkMinMax();
	}

	/** Reconstruct from an XML entry. */
	public Patch(Project project, long id, HashMap ht_attributes, HashMap ht_links) {
		super(project, id, ht_attributes, ht_links);
		// cache path:
		project.getLoader().addedPatchFrom((String)ht_attributes.get("file_path"), this);
		boolean hasmin = false;
		boolean hasmax = false;
		// parse specific fields
		final Iterator it = ht_attributes.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry entry = (Map.Entry)it.next();
			final String key = (String)entry.getKey();
			final String data = (String)entry.getValue();
			if (key.equals("type")) {
				this.type = Integer.parseInt(data);
			} else if (key.equals("min")) {
				this.min = Double.parseDouble(data);
				hasmin = true;
			} else if (key.equals("max")) {
				this.max = Double.parseDouble(data);
				hasmax = true;
			} else if (key.equals("original_path")) {
				this.original_path = data;
			} else if (key.equals("o_width")) {
				this.o_width = Integer.parseInt(data);
			} else if (key.equals("o_height")) {
				this.o_height = Integer.parseInt(data);
			} else if (key.equals("pps")) {
				String path = data;
				if (FSLoader.isRelativePath(path)) {
					path = project.getLoader().getParentFolder() + path;
				}
				project.getLoader().setPreprocessorScriptPathSilently(this, path);
			}
		}

		if (0 == o_width || 0 == o_height) {
			// The original image width and height are unknown.
			try {
				Utils.log2("Restoring original width/height from file for id=" + id);
				// Use BioFormats to read the dimensions out of the original file's header
				final Dimension dim = project.getLoader().getDimensions(this);
				o_width = dim.width;
				o_height = dim.height;
			} catch (Exception e) {
				Utils.log("Could not read source data width/height for patch " + this +"\n --> To fix it, close the project and add o_width=\"XXX\" o_height=\"YYY\"\n     to patch entry with oid=\"" + id + "\",\n     where o_width,o_height are the image dimensions as defined in the image file.");
				// So set them to whatever is somewhat survivable for the moment
				o_width = (int)width;
				o_height = (int)height;
				IJError.print(e);
			}
		}

		if (hasmin && hasmax) {
			checkMinMax();
		} else {
			// standard, from the image, to be defined when first painted
			min = max = -1;
		}
		//Utils.log2("new Patch from XML, min and max: " + min + "," + max);
	}

	/** The original width of the pixels in the source image file. */
	public int getOWidth() { return o_width; }
	/** The original height of the pixels in the source image file. */
	public int getOHeight() { return o_height; }

	/** Fetches the ImagePlus from the cache; <b>be warned</b>: the returned ImagePlus may have been flushed, removed and then recreated if the program had memory needs that required flushing part of the cache; use @getImageProcessor to get the pixels guaranteed not to be ever null. */
	public ImagePlus getImagePlus() {
		return this.project.getLoader().fetchImagePlus(this);
	}

	/** Fetches the ImageProcessor from the cache, which will never be flushed or its pixels set to null. If you keep many of these, you may end running out of memory: I advise you to call this method everytime you need the processor. */
	public ImageProcessor getImageProcessor() {
		return this.project.getLoader().fetchImageProcessor(this);
	}

	/** Recreate mipmaps and flush away any cached ones.
	 * This method is essentially the same as patch.getProject().getLoader().update(patch);
	 * which in turn it's the same as the following two calls:
	 *     patch.getProject().getLoader().generateMipMaps(patch);
	 *     patch.getProject().getLoader().decacheAWT(patch.getId());
	 *
	 * If you want to update lots of Patch instances in parallel, consider also
	 *    project.getLoader().generateMipMaps(ArrayList patches, boolean overwrite);
	 */
	public boolean updateMipmaps() {
		return project.getLoader().update(this);
	}

	/** Update type, original dimensions and min,max from the ImagePlus.
	 *  This is automatically done after a preprocessor script has modified the image. */
	public void updatePixelProperties() {
		readProps(getImagePlus());
	}

	/** Update type, original dimensions and min,max from the given ImagePlus. */
	private void readProps(final ImagePlus imp) {
		this.type = imp.getType();
		if (imp.getWidth() != (int)this.o_width || imp.getHeight() != this.o_height) {
			this.o_width = imp.getWidth();
			this.o_height = imp.getHeight();
			this.width = o_width;
			this.height = o_height;
			updateBucket();
		}
		ImageProcessor ip = imp.getProcessor();
		this.min = ip.getMin();
		this.max = ip.getMax();
		final HashSet<String> keys = new HashSet<String>();
		keys.add("type");
		keys.add("dimensions");
		keys.add("min_and_max");
		updateInDatabase(keys);
		//updateInDatabase(new HashSet<String>(Arrays.asList(new String[]{"type", "dimensions", "min_and_max"})));
	}

	/** Set a new ImagePlus for this Patch.
	 * The original path and image remain untouched. Any later image is deleted and replaced by the new one.
	 */
	synchronized public String set(final ImagePlus new_imp) {
		if (null == new_imp) return null;
		// flag to mean: this Patch has never been set to any image except the original
		//    The intention is never to remove the mipmaps of original images
		boolean first_time = null == original_path;
		// 0 - set original_path to the current path if there is no original_path recorded:
		if (isStack()) {
			for (Patch p : getStackPatches()) {
				if (null == p.original_path) original_path = p.project.getLoader().getAbsolutePath(p);
			}
		} else {
			if (null == original_path) original_path = project.getLoader().getAbsolutePath(this);
		}
		// 1 - tell the loader to store the image somewhere, unless the image has a path already
		final String path = project.getLoader().setImageFile(this, new_imp);
		if (null == path) {
			Utils.log2("setImageFile returned null!");
			return null; // something went wrong
		}
		// 2 - update properties and mipmaps
		if (isStack()) {
			for (Patch p : getStackPatches()) {
				p.readProps(new_imp);
				project.getLoader().generateMipMaps(p); // sequentially
				project.getLoader().decacheAWT(p.id);
			}
		} else {
			readProps(new_imp);
			project.getLoader().generateMipMaps(this);
			project.getLoader().decacheAWT(this.id);
		}
		Display.repaint(layer, this, 5);
		return project.getLoader().getAbsolutePath(this);
	}

	/** Boundary checks on min and max, given the image type. */
	private void checkMinMax() {
		if (-1 == this.type) return;
		switch (type) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_RGB:
			case ImagePlus.COLOR_256:
			     if (this.min < 0) this.min = 0;
			     break;
		}
		final double max_max = Patch.getMaxMax(this.type);
		if (this.max > max_max) this.max = max_max;
		// still this.max could be -1, in which case putMinAndMax will fix it to the ImageProcessor's values
	}

	/** The min and max values are stored with the Patch, so that the image can be flushed away but the non-destructive contrast settings preserved. */
	public void setMinAndMax(double min, double max) {
		this.min = min;
		this.max = max;
		updateInDatabase("min_and_max");
		Utils.log2("Patch.setMinAndMax: min,max " + min + "," + max);
	}

	public double getMin() { return min; }
	public double getMax() { return max; }

	/** Needs a non-null ImagePlus with a non-null ImageProcessor in it. This method is meant to be called only immediately after the ImagePlus is loaded. */
	/* // OBSOLETE
	public void putMinAndMax(final ImagePlus imp) throws Exception {
		ImageProcessor ip = imp.getProcessor();
		// adjust lack of values
		if (-1 == min || -1 == max) {
			min = ip.getMin();
			max = ip.getMax();
		} else {
			ip.setMinAndMax(min, max);
		}
		//Utils.log2("Patch.putMinAndMax: min,max " + min + "," + max);
	}
	*/

	/** Returns the ImagePlus type of this Patch. */
	public int getType() {
		return type;
	}

	public Image createImage(ImagePlus imp) {
		return adjustChannels(channels, true, imp);
	}

	public Image createImage() {
		return adjustChannels(channels, true, null);
	}

	private Image adjustChannels(int c) {
		return adjustChannels(c, false, null);
	}

	public int getChannelAlphas() {
		return channels;
	}

	/** @param c contains the current Display 'channels' value (the transparencies of each channel). This method creates a new color image in which each channel (R, G, B) has the corresponding alpha (in fact, opacity) specified in the 'c'. This alpha is independent of the alpha of the whole Patch. The method updates the Loader cache with the newly created image. The argument 'imp' is optional: if null, it will be retrieved from the loader.<br />
	 * For non-color images, a standard image is returned regardless of the @param c
	 */
	private Image adjustChannels(final int c, final boolean force, ImagePlus imp) {
		if (null == imp) imp = project.getLoader().fetchImagePlus(this);
		ImageProcessor ip = imp.getProcessor();
		if (null == ip) return null; // fixing synch problems when deleting a Patch
		Image awt = null;
		if (ImagePlus.COLOR_RGB == type) {
			if (imp.getType() != type ) {
				ip = Utils.convertTo(ip, type, false); // all other types need not be converted, since there are no alphas anyway
			}
			if ((c&0x00ffffff) == 0x00ffffff && !force) {
				// full transparency
				awt = ip.createImage(); //imp.getImage();
				// pixels array will be shared using ij138j and above
			} else {
				// modified from ij.process.ColorProcessor.createImage() by Wayne Rasband
				int[] pixels = (int[])ip.getPixels();
				float cr = ((c&0xff0000)>>16) / 255.0f;
				float cg = ((c&0xff00)>>8) / 255.0f;
				float cb = (c&0xff) / 255.0f;
				int[] pix = new int[pixels.length];
				int p;
				for (int i=pixels.length -1; i>-1; i--) {
					p = pixels[i];
					pix[i] =  (((int)(((p&0xff0000)>>16) * cr))<<16)
						+ (((int)(((p&0xff00)>>8) * cg))<<8)
						+   (int) ((p&0xff) * cb);
				}
				int w = imp.getWidth();
				MemoryImageSource source = new MemoryImageSource(w, imp.getHeight(), DCM, pix, 0, w);
				source.setAnimated(true);
				source.setFullBufferUpdates(true);
				awt = Toolkit.getDefaultToolkit().createImage(source);
			}
		} else {
			awt = ip.createImage();
		}

		//Utils.log2("ip's min, max: " + ip.getMin() + ", " + ip.getMax());

		this.channels = c;

		return awt;
	}

	static final public DirectColorModel DCM = new DirectColorModel(24, 0xff0000, 0xff00, 0xff);

	/** Just throws the cached image away if the alpha of the channels has changed. */
	private final void checkChannels(int channels, double magnification) {
		if (this.channels != channels && (ImagePlus.COLOR_RGB == this.type || ImagePlus.COLOR_256 == this.type)) {
			final int old_channels = this.channels;
			this.channels = channels; // before, so if any gets recreated it's done right
			project.getLoader().adjustChannels(this, old_channels);
		}
	}

	/** Takes an image and scales its channels according to the values packed in this.channels.
	 *  This method is intended for fixing RGB images which are loaded from jpegs (the mipmaps), and which
	 *  have then the full colorization of the original image present in their pixels array.
	 *  Otherwise the channel opacity scaling makes no sense.
	 *  If 0xffffffff == this.channels the awt is returned as is.
	 *  If the awt is null returns null.
	 */
	public final Image adjustChannels(final Image awt) {
		if (0xffffffff == this.channels || null == awt) return awt;
		BufferedImage bi = null;
		// reuse if possible
		if (awt instanceof BufferedImage) bi = (BufferedImage)awt;
		else {
			bi = new BufferedImage(awt.getWidth(null), awt.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			bi.getGraphics().drawImage(awt, 0, 0, null);
		}
		// extract channel values
		final float cr = ((channels&0xff0000)>>16) / 255.0f;
		final float cg = ((channels&0xff00)>>8   ) / 255.0f;
		final float cb = ( channels&0xff         ) / 255.0f;
		// extract pixels
		Utils.log2("w, h: " + bi.getWidth() + ", " + bi.getHeight());
		final int[] pixels = bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, 1);
		// scale them according to channel opacities
		int p;
		for (int i=0; i<pixels.length; i++) {
			p = pixels[i];
			pixels[i] =  (((int)(((p&0xff0000)>>16) * cr))<<16)
				+ (((int)(((p&0xff00)>>8) * cg))<<8)
				+   (int) ((p&0xff) * cb);
		}
		// replace pixels
		bi.setRGB(0, 0, bi.getWidth(), bi.getHeight(), pixels, 0, 1);
		return bi;
	}

	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {

		AffineTransform atp = this.at;

		checkChannels(channels, magnification);

		// Consider all possible scaling components: m00, m01
		//                                           m10, m11
		double sc = magnification * Math.max(Math.abs(at.getScaleX()),
				                     Math.max(Math.abs(at.getScaleY()),
							      Math.max(Math.abs(at.getShearX()),
								       Math.abs(at.getShearY()))));
		if (sc < 0) sc = magnification;
		final Image image = project.getLoader().fetchImage(this, sc);
		//Utils.log2("Patch " + id + " painted image " + image);

		if (null == image) {
			//Utils.log2("Patch.paint: null image, returning");
			return; // TEMPORARY from lazy repaints after closing a Project
		}

		// fix dimensions: may be smaller or bigger mipmap than the image itself
		final int iw = image.getWidth(null);
		final int ih = image.getHeight(null);
		if (iw != this.width || ih != this.height) {
			atp = (AffineTransform)atp.clone();
			atp.scale(this.width / iw, this.height / ih);
		}

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		g.drawImage(image, atp, null);

		//Transparency: fix composite back to original.
		if (alpha != 1.0f) {
			g.setComposite(original_composite);
		}
	}

	/** Paint first whatever is available, then request that the proper image be loaded and painted. */
	public void prePaint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {

		AffineTransform atp = this.at;

		checkChannels(channels, magnification);

		// Consider all possible scaling components: m00, m01
		//                                           m10, m11
		double sc = magnification * Math.max(Math.abs(at.getScaleX()),
				                     Math.max(Math.abs(at.getScaleY()),
							      Math.max(Math.abs(at.getShearX()),
								       Math.abs(at.getShearY()))));
		if (sc < 0) sc = magnification;

		Image image = project.getLoader().getCachedClosestAboveImage(this, sc); // above or equal
		if (null == image) {
			image = project.getLoader().getCachedClosestBelowImage(this, sc); // below, not equal
			boolean thread = false;
			if (null == image) {
				// fetch the proper image, nothing is cached
				if (sc <= 0.5001) {
					// load the mipmap
					image = project.getLoader().fetchImage(this, sc);
				} else {
					// load a smaller mipmap, and then load the larger one and repaint on load.
					image = project.getLoader().fetchImage(this, 0.25);
					thread = true;
				}
				// TODO to be non-blocking, this should paint a black square with a "loading..." legend in it or something, then fire a later repaint thread like below. So don't wait!
			} else {
				// painting a smaller image, will need to repaint with the proper one
				thread = true;
			}
			if (thread && !Loader.NOT_FOUND.equals(image)) {
				// use the lower resolution image, but ask to repaint it on load
				Loader.preload(this, sc, true);
			}
		}

		if (null == image) {
			Utils.log2("Patch.paint: null image, returning");
			return; // TEMPORARY from lazy repaints after closing a Project
		}

		// fix dimensions: may be smaller or bigger mipmap than the image itself
		final int iw = image.getWidth(null);
		final int ih = image.getHeight(null);
		if (iw != this.width || ih != this.height) {
			atp = (AffineTransform)atp.clone();
			atp.scale(this.width / iw, this.height / ih);
		}

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		g.drawImage(image, atp, null);

		//Transparency: fix composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	/** A method to paint, simply (to a flat image for example); no magnification or srcRect are considered. */
	public void paint(Graphics2D g) {
		if (!this.visible) return;

		Image image = project.getLoader().fetchImage(this); // TODO: could read the scale parameter of the graphics object and call for the properly sized mipmap accordingly.

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		g.drawImage(image, this.at, null);

		//Transparency: fix composite back to original.
		if (alpha != 1.0f) {
			g.setComposite(original_composite);
		}
	}

	public boolean isDeletable() {
		return 0 == width && 0 == height;
	}

	/** Remove only if linked to other Patches or to noone. */
	public boolean remove(boolean check) {
		if (check && !Utils.check("Really remove " + this.toString() + " ?")) return false;
		if (isStack()) { // this Patch is part of a stack
			GenericDialog gd = new GenericDialog("Stack!");
			gd.addMessage("Really delete the entire stack?");
			gd.addCheckbox("Delete layers if empty", true);
			gd.showDialog();
			if (gd.wasCanceled()) return false;
			boolean delete_empty_layers = gd.getNextBoolean();
			// gather all
			HashMap<Double,Patch> ht = new HashMap<Double,Patch>();
			getStackPatchesNR(ht);
			Utils.log("Stack patches: " + ht.size());
			ArrayList al = new ArrayList();
			for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
				Patch p = (Patch)it.next();
				if (!p.isOnlyLinkedTo(this.getClass())) {
					Utils.showMessage("At least one slice of the stack (z=" + p.getLayer().getZ() + ") is supporting other data.\nCan't delete.");
					return false;
				}
			}
			for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
				Patch p = (Patch)it.next();
				if (!p.layer.remove(p) || !p.removeFromDatabase()) {
					Utils.showMessage("Can't delete Patch " + p);
					return false;
				}
				p.unlink();
				p.removeLinkedPropertiesFromOrigins();
				//no need//it.remove();
				al.add(p.layer);
				if (p.layer.isEmpty()) Display.close(p.layer);
				else Display.repaint(p.layer);
			}
			if (delete_empty_layers) {
				for (Iterator it = al.iterator(); it.hasNext(); ) {
					Layer la = (Layer)it.next();
					if (la.isEmpty()) {
						project.getLayerTree().remove(la, false);
						Display.close(la);
					}
				}
			}
			Search.remove(this);
			return true;
		} else {
			if (isOnlyLinkedTo(Patch.class, this.layer) && layer.remove(this) && removeFromDatabase()) { // don't alow to remove linked patches (unless only linked to other patches in the same layer)
				unlink();
				removeLinkedPropertiesFromOrigins();
				Search.remove(this);
				return true;
			} else {
				Utils.showMessage("Patch: can't remove! The image is linked and thus supports other data).");
				return false;
			}
		}
	}

	/** Returns true if this Patch holds direct links to at least one other image in a different layer. Doesn't check for total overlap. */
	public final boolean isStack() {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		for (final Displayable d : hs_linked) {
			if (d.getClass() == Patch.class && d.layer.getId() != this.layer.getId()) return true;
		}
		return false;
	}

	/** Retuns a virtual ImagePlus with a virtual stack if necessary. */
	public PatchStack makePatchStack() {
		// are we a stack?
		final TreeMap<Double,Patch> ht = new TreeMap<Double,Patch>();
		getStackPatchesNR(ht);
		final Patch[] patch;
		int currentSlice = 1; // from 1 to n, as in ImageStack
		if (ht.size() > 1) {
			patch = new Patch[ht.size()];
			int i = 0;
			for (final Patch p : ht.values()) { // sorted by z
				patch[i] = p;
				if (p.id == this.id) currentSlice = i+1;
				i++;
			}
		} else {
			patch = new Patch[]{ this };
		}
		return new PatchStack(patch, currentSlice);
	}

	public ArrayList<Patch> getStackPatches() {
		final TreeMap<Double,Patch> ht = new TreeMap<Double,Patch>();
		getStackPatchesNR(ht);
		return new ArrayList(ht.values()); // sorted by z
	}

	/** Collect linked Patch instances that do not lay in this layer. Recursive over linked Patch instances that lay in different layers. */ // This method returns a usable stack because Patch objects are only linked to other Patch objects when inserted together as stack. So the slices are all consecutive in space and have the same thickness. Yes this is rather convoluted, stacks should be full-grade citizens
	private void getStackPatches(HashMap<Double,Patch> ht) {
		if (ht.containsKey(this)) return;
		ht.put(new Double(layer.getZ()), this);
		if (null != hs_linked && hs_linked.size() > 0) {
			/*
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Displayable ob = (Displayable)it.next();
				if (ob instanceof Patch && !ob.layer.equals(this.layer)) {
					((Patch)ob).getStackPatches(ht);
				}
			}
			*/
			// avoid stack overflow (with as little as 114 layers ... !!!)
			Displayable[] d = new Displayable[hs_linked.size()];
			hs_linked.toArray(d);
			for (int i=0; i<d.length; i++) {
				if (d[i] instanceof Patch && d[i].layer.equals(this.layer)) {
					((Patch)d[i]).getStackPatches(ht);
				}
			}
		}
	}

	/** Non-recursive version to avoid stack overflows with "excessive" recursion (I hate java). */
	private void getStackPatchesNR(final Map<Double,Patch> ht) {
		final ArrayList<Patch> list1 = new ArrayList<Patch>();
		list1.add(this);
		final ArrayList<Patch> list2 = new ArrayList<Patch>();
		while (list1.size() > 0) {
			list2.clear();
			for (Patch p : list1) {
				if (null != p.hs_linked) {
					for (Iterator it = p.hs_linked.iterator(); it.hasNext(); ) {
						Object ln = it.next();
						if (ln instanceof Patch) {
							Patch pa = (Patch)ln;
							if (!ht.containsValue(pa)) {
								ht.put(pa.layer.getZ(), pa);
								list2.add(pa);
							}
						}
					}
				}
			}
			list1.clear();
			list1.addAll(list2);
		}
	}

	/** Opens and closes the tag and exports data. The image is saved in the directory provided in @param any as a String. */
	public void exportXML(StringBuffer sb_body, String indent, Object any) { // TODO the Loader should handle the saving of images, not this class.
		String in = indent + "\t";
		String path = null;
		String path2 = null;
		//Utils.log2("#########\np id=" + id + "  any is " + any);
		if (null != any) {
			path = any + title; // ah yes, automatic toString() .. it's like the ONLY smart logic at the object level built into java.
			// save image without overwritting, and add proper extension (.zip)
			path2 = project.getLoader().exportImage(this, path, false);
			//Utils.log2("p id=" + id + "  path2: " + path2);
			// path2 will be null if the file exists already
		}
		sb_body.append(indent).append("<t2_patch\n");
		String rel_path = null;
		if (null != path && path.equals(path2)) { // this happens when a DB project is exported. It may be a different path when it's a FS loader
			//Utils.log2("p id=" + id + "  path==path2");
			rel_path = path2;
			int i_slash = rel_path.lastIndexOf(java.io.File.separatorChar);
			if (i_slash > 0) {
				i_slash = rel_path.lastIndexOf(java.io.File.separatorChar, i_slash -1);
				if (-1 != i_slash) {
					rel_path = rel_path.substring(i_slash+1);
				}
			}
		} else {
			//Utils.log2("Setting rel_path to " + path2);
			rel_path = path2;
		}
		// For FSLoader projects, saving a second time will save images as null unless calling it
		if (null == rel_path) {
			//Utils.log2("path2 was null");
			Object ob = project.getLoader().getPath(this);
			path2 = null == ob ? null : (String)ob;
			if (null == path2) {
				//Utils.log2("ERROR: No path for Patch id=" + id + " and title: " + title);
				rel_path = title; // at least some clue for recovery
			} else {
				rel_path = path2;
			}
		}

		//Utils.log("Patch path is: " + rel_path);

		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);
		int type = this.type;
		if (-1 == this.type) {
			Utils.log2("Retrieving type for p = " + this);
			ImagePlus imp = project.getLoader().fetchImagePlus(this);
			if (null != imp) type = imp.getType();
		}
		sb_body.append(in).append("type=\"").append(type /*null == any ? ImagePlus.GRAY8 : type*/).append("\"\n")
		       .append(in).append("file_path=\"").append(rel_path).append("\"\n")
		       .append(in).append("style=\"fill-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";\"\n")
		       .append(in).append("o_width=\"").append(o_width).append("\"\n")
		       .append(in).append("o_height=\"").append(o_height).append("\"\n")
		;
		if (null != original_path) {
			sb_body.append(in).append("original_path=\"").append(original_path).append("\"\n");
		}
		if (0 != min) sb_body.append(in).append("min=\"").append(min).append("\"\n");
		if (max != Patch.getMaxMax(type)) sb_body.append(in).append("max=\"").append(max).append("\"\n");

		String pps = getPreprocessorScriptPath();
		if (null != pps) sb_body.append(in).append("pps=\"").append(project.getLoader().makeRelativePath(pps)).append("\"\n");

		sb_body.append(indent).append(">\n");

		if (null != ct) {
			sb_body.append(ct.toXML(in)).append('\n');
		}

		super.restXML(sb_body, in, any);

		sb_body.append(indent).append("</t2_patch>\n");
	}

	static private final double getMaxMax(final int type) {
		int pow = 1;
		switch (type) {
			case ImagePlus.GRAY16: pow = 2; break; // TODO problems with unsigned short most likely
			case ImagePlus.GRAY32: pow = 4; break;
			default: return 255;
		}
		return Math.pow(256, pow) - 1;
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_patch";
		if (hs.contains(type)) return;
		// The Patch itself:
		sb_header.append(indent).append("<!ELEMENT t2_patch (").append(Displayable.commonDTDChildren()).append(",ict_transform,ict_transform_list)>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" file_path").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" original_path").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" type").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" ct").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" o_width").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" o_height").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" pps").append(TAG_ATTR2) // preprocessor script
		;
		// The InvertibleCoordinateTransform and a list of:
		sb_header.append(indent).append("<!ELEMENT ict_transform EMPTY>\n");
		sb_header.append(indent).append(TAG_ATTR1).append("ict_transform class").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append("ict_transform data").append(TAG_ATTR2);
		sb_header.append(indent).append("<!ELEMENT ict_transform_list (ict_transform)>\n");

	}

	/** Performs a copy of this object, without the links, unlocked and visible, except for the image which is NOT duplicated. If the project is NOT the same as this instance's project, then the id of this instance gets assigned as well to the returned clone. */
	public Displayable clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Patch copy = new Patch(pr, nid, null != title ? title.toString() : null, width, height, o_width, o_height, type, false, min, max, (AffineTransform)at.clone());
		copy.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		copy.alpha = this.alpha;
		copy.visible = true;
		copy.channels = this.channels;
		copy.min = this.min;
		copy.max = this.max;
		copy.ct = null == ct ? null : this.ct.clone();
		copy.addToDatabase();
		pr.getLoader().addedPatchFrom(this.project.getLoader().getAbsolutePath(this), copy);
		copy.setAlphaMask(this.project.getLoader().fetchImageMask(this));
		return copy;
	}

	/** Override to cancel. */
	public void linkPatches() {
		Utils.log2("Patch class can't link other patches using Displayble.linkPatches()");
	}

	public void paintSnapshot(final Graphics2D g, final double mag) {
		switch (layer.getParent().getSnapshotsMode()) {
			case 0:
				if (!project.getLoader().isSnapPaintable(this.id)) {
					paintAsBox(g);
				} else {
					paint(g, mag, false, this.channels, layer);
				}
				return;
			case 1:
				paintAsBox(g);
				return;
			default: return; // case 2: // disabled, no paint
		}
	}

	static protected void crosslink(final Collection<Displayable> patches, final boolean overlapping_only) {
		if (null == patches) return;
		final ArrayList<Patch> al = new ArrayList<Patch>();
		for (Object ob : patches) if (ob instanceof Patch) al.add((Patch)ob); // ... 
		final int len = al.size();
		if (len < 2) return;
		final Patch[] pa = new Patch[len];
		al.toArray(pa);
		// linking is reciprocal: need only call link() on one member of the pair
		for (int i=0; i<pa.length; i++) {
			for (int j=i+1; j<pa.length; j++) {
				if (overlapping_only && !pa[i].intersects(pa[j])) continue;
				pa[i].link(pa[j]);
			}
		}
	}

	/** Magnification-dependent counterpart to ImageProcessor.getPixel(x, y). Expects x,y in world coordinates. This method is intended for grabing an occasional pixel; to grab all pixels, see @getImageProcessor method.*/
	public int getPixel(double mag, final int x, final int y) {
		final int[] iArray = getPixel(x, y, mag);
		if (ImagePlus.COLOR_RGB == this.type) {
			return (iArray[0]<<16) + (iArray[1]<<8) + iArray[2];
		}
		return iArray[0];
	}

	/** Magnification-dependent counterpart to ImageProcessor.getPixel(x, y, iArray). Expects x,y in world coordinates.  This method is intended for grabing an occasional pixel; to grab all pixels, see @getImageProcessor method.*/
	public int[] getPixel(double mag, final int x, final int y, final int[] iArray) {
		final int[] ia = getPixel(x, y, mag);
		if(null != iArray) {
			iArray[0] = ia[0];
			iArray[1] = ia[1];
			iArray[2] = ia[2];
			return iArray;
		}
		return ia;
	}

	/** Expects x,y in world coordinates.  This method is intended for grabing an occasional pixel; to grab all pixels, see @getImageProcessor method. */
	public int[] getPixel(final int x, final int y, final double mag) {
		if (project.getLoader().isUnloadable(this)) return new int[4];
		final Image img = project.getLoader().fetchImage(this, mag);
		if (Loader.isSignalImage(img)) return new int[4];
		final int w = img.getWidth(null);
		final double scale = w / width;
		final Point2D.Double pd = inverseTransformPoint(x, y);
		final int x2 = (int)(pd.x * scale);
		final int y2 = (int)(pd.y * scale);
		final int[] pvalue = new int[4];
		final PixelGrabber pg = new PixelGrabber(img, x2, y2, 1, 1, pvalue, 0, w);
		try {
			pg.grabPixels();
		} catch (InterruptedException ie) {
			return pvalue;
		}
		switch (type) {
			case ImagePlus.COLOR_256: // mipmaps use RGB images internally, so I can't compute the index in the LUT
			case ImagePlus.COLOR_RGB:
				final int c = pvalue[0];
				pvalue[0] = (c&0xff0000)>>16; // R
				pvalue[1] = (c&0xff00)>>8;    // G
				pvalue[2] = c&0xff;           // B
				break;
			case ImagePlus.GRAY8:
				pvalue[0] = pvalue[0]&0xff;
				break;
			case ImagePlus.GRAY16:
				pvalue[0] = pvalue[0]&0xff;
				// correct range: from 8-bit of the mipmap to 16 bit
				pvalue[0] = (int)(min + pvalue[0] * ( (max - min) / 256 ));
				break;
			case ImagePlus.GRAY32:
				pvalue[0] = pvalue[0]&0xff;
				// correct range: from 8-bit of the mipmap to 32 bit
				// ... and encode, so that it will be decoded with Float.intToFloatBits
				pvalue[0] = Float.floatToIntBits((float)(min + pvalue[0] * ( (max - min) / 256 )));
				break;
		}

		return pvalue;
	}

	/** If this patch is part of a stack, the file path will contain the slice number attached to it, in the form -----#slice=10 for slice number 10. */
	public final String getFilePath() {
		if (null != current_path) return current_path;
		return project.getLoader().getAbsolutePath(this);
	}

	/** Returns the absolute path to the image file, as read by the OS. */
	public final String getImageFilePath() {
		return project.getLoader().getImageFilePath(this);
	}

	/** Returns the value of the field current_path, which may be null. If not null, the value may contain the slice info in it if it's part of a stack. */
	public final String getCurrentPath() { return current_path; }

	/** Cache a proper, good, known path to the image wrapped by this Patch. */
	public final void cacheCurrentPath(final String path) {
		this.current_path = path;
	}

	/** Returns the value of the field original_path, which may be null. If not null, the value may contain the slice info in it if it's part of a stack. */
	synchronized public String getOriginalPath() { return original_path; }

	protected void setAlpha(float alpha, boolean update) {
		if (isStack()) {
			HashMap<Double,Patch> ht = new HashMap<Double,Patch>();
			getStackPatchesNR(ht);
			for (Patch pa : ht.values()) {
				pa.alpha = alpha;
				pa.updateInDatabase("alpha");
				Display.repaint(pa.layer, pa, 5);
			}
			Display3D.setTransparency(this, alpha);
		} else super.setAlpha(alpha, update);
	}

	public void debug() {
		Utils.log2("Patch id=" + id + "\n\toriginal_path=" + original_path + "\n\tcurrent_path=" + current_path);
	}

	/** Revert the ImagePlus to the one stored in original_path, if any; will revert all linked patches if this is part of a stack. */
	synchronized public boolean revert() {
		if (null == original_path) return false; // nothing to revert to
		// 1 - check that original_path exists
		if (!new File(original_path).exists()) {
			Utils.log("CANNOT revert: Original file path does not exist: " + original_path + " for patch " + getTitle() + " #" + id);
			return false;
		}
		// 2 - check that the original can be loaded
		final ImagePlus imp = project.getLoader().fetchOriginal(this);
		if (null == imp || null == set(imp)) {
			Utils.log("CANNOT REVERT: original image at path " + original_path + " fails to load, for patch " + getType() + " #" + id);
			return false;
		}
		// 3 - update path in loader, and cache imp for each stack slice id
		if (isStack()) {
			for (Patch p : getStackPatches()) {
				p.project.getLoader().addedPatchFrom(p.original_path, p);
				p.project.getLoader().cacheImagePlus(p.id, imp);
				p.project.getLoader().generateMipMaps(p);
			}
		} else {
			project.getLoader().addedPatchFrom(original_path, this);
			project.getLoader().cacheImagePlus(id, imp);
			project.getLoader().generateMipMaps(this);
		}
		// 4 - update screens
		Display.repaint(layer, this, 0);
		Utils.showStatus("Reverted patch " + getTitle(), false);
		return true;
	}

	/** For reconstruction purposes, overwrites the present CoordinateTransform, if any, with the given one. */
	public void setCoordinateTransformSilently(final CoordinateTransform ct) {
		this.ct = ct;
	}

	/** Set a CoordinateTransform to this Patch.
	 *  The resulting image of applying the coordinate transform does not need to be rectangular: an alpha mask will take care of the borders. You should call updateMipmaps() afterwards to update the mipmap images used for painting this Patch to the screen. */
	public final void setCoordinateTransform(final CoordinateTransform ct) {
		if (isLinked()) {
			Utils.log("Cannot set coordinate transform: patch is linked!");
			return;
		}

		if (null != this.ct) {
			// restore image without the transform
			final TransformMesh mesh = new TransformMesh(this.ct, 32, o_width, o_height);
			final Rectangle box = mesh.getBoundingBox();
			this.at.translate(-box.x, -box.y);
			updateInDatabase("transform+dimensions");
		}

		this.ct = ct;
		updateInDatabase("ict_transform");

		if (null == this.ct) {
			width = o_width;
			height = o_height;
			updateBucket();
			return;
		}

		// Adjust the AffineTransform to correct for bounding box displacement

		final TransformMesh mesh = new TransformMesh(this.ct, 32, o_width, o_height);
		final Rectangle box = mesh.getBoundingBox();
		this.at.translate(box.x, box.y);
		this.width = box.width;
		this.height = box.height;
		updateInDatabase("transform+dimensions"); // the AffineTransform
		updateBucket();

		// Updating the mipmaps will call createTransformedImage below if ct is not null
		/* DISABLED */ //updateMipmaps();
	}
	
	/**
	 * Append a {@link CoordinateTransform} to the current
	 * {@link CoordinateTransformList}.  If there is no transform yet, it just
	 * sets it.  If there is only one transform, it replaces it by a list
	 * containing both.
	 */
	public final void appendCoordinateTransform(final CoordinateTransform ct) {
		if (null == this.ct)
			setCoordinateTransform(ct);
		else {
			final CoordinateTransformList< CoordinateTransform > ctl;
			if (this.ct instanceof CoordinateTransformList)
				ctl = (CoordinateTransformList< CoordinateTransform >)this.ct.clone();
			else {
				ctl = new CoordinateTransformList< CoordinateTransform >();
				ctl.add(this.ct);
			}
			ctl.add(ct);
			setCoordinateTransform(ctl);
		}
	}
	
	/**
	 * Get the bounding rectangle of the transformed image relative to the
	 * original image.
	 * 
	 * TODO
	 *   Currently, this is done in a very expensive way.  The
	 *   {@linkplain TransformMesh} is built and its bounding rectangle is
	 *   returned.  Think about just storing this rectangle in the
	 *   {@linkplain Patch} instance.
	 * 
	 * @return
	 */
	public final Rectangle getCoordinateTransformBoundingBox() {
		if (null==ct)
			return new Rectangle(0,0,o_width,o_height);
		final TransformMesh mesh = new TransformMesh(this.ct, 32, o_width, o_height);
		return mesh.getBoundingBox();
	}

	public final CoordinateTransform getCoordinateTransform() { return ct; }
	
	public final Patch.PatchImage createCoordinateTransformedImage() {
		if (null == ct) return null;
		
		final ImageProcessor source = getImageProcessor();

		//Utils.log2("source image dimensions: " + source.getWidth() + ", " + source.getHeight());

		final TransformMesh mesh = new TransformMesh(ct, 32, o_width, o_height);
		final Rectangle box = mesh.getBoundingBox();

		/* We can calculate the exact size of the image to be rendered, so let's do it */
//		project.getLoader().releaseToFit(o_width, o_height, type, 5);
		final long b =
			  2 * o_width * o_height		// outside and mask source
			+ 2 * box.width * box.height	// outside and mask target
			+ 5 * o_width * o_height		// image source
			+ 5 * box.width * box.height;	// image target
		project.getLoader().releaseToFit( b );

		final TransformMeshMapping mapping = new TransformMeshMapping( mesh );
		
		ImageProcessor target = mapping.createMappedImageInterpolated( source );
		
		ByteProcessor outside = new ByteProcessor( source.getWidth(), source.getHeight() );
		outside.setValue(255);
		outside.fill();
		
		outside = (ByteProcessor) mapping.createMappedImageInterpolated( outside );
		
		ByteProcessor mask = project.getLoader().fetchImageMask(this);
		if (null != mask)
			mask = (ByteProcessor) mapping.createMappedImageInterpolated( mask );
		
		// Set all non-white pixels to zero
		final byte[] pix = (byte[])outside.getPixels();
		for (int i=0; i<pix.length; i++)
			if ((pix[i]&0xff) != 255) pix[i] = 0;

		//Utils.log2("New image dimensions: " + target.getWidth() + ", " + target.getHeight());
		//Utils.log2("box: " + box);

		return new PatchImage( target, mask, outside, box, true );
	}

	public final class PatchImage {
		/** The image, coordinate-transformed if null != ct. */
		final public ImageProcessor target;
		/** The alpha mask, coordinate-transformed if null != ct. */
		final public ByteProcessor mask;
		/** The outside mask, coordinate-transformed if null != ct. */
		final public ByteProcessor outside;
		/** The bounding box of the image relative to the original, with x,y as the displacement relative to the pixels of the original image. */
		final public Rectangle box;
		/** Whether the image was generated with a CoordinateTransform or not. */
		final public boolean coordinate_transformed;

		private PatchImage( ImageProcessor target, ByteProcessor mask, ByteProcessor outside, Rectangle box, boolean coordinate_transformed ) {
			this.target = target;
			this.mask = mask;
			this.outside = outside;
			this.box = box;
			this.coordinate_transformed = coordinate_transformed;
		}
	}

	/** Returns a PatchImage object containing the bottom-of-transformation-stack image and alpha mask, if any (except the AffineTransform, which is used for direct hw-accel screen rendering). */
	public Patch.PatchImage createTransformedImage() {
		final Patch.PatchImage pi = createCoordinateTransformedImage();
		if (null != pi) return pi;
		// else, a new one with the untransformed, original image (a duplicate):
		project.getLoader().releaseToFit(o_width, o_height, type, 3);
		final ImageProcessor ip = getImageProcessor();
		if (null == ip) return null;
		return new PatchImage(ip.duplicate(), project.getLoader().fetchImageMask(this), null, new Rectangle(0, 0, o_width, o_height), false);
	}

	private boolean has_alpha = false;
	private boolean alpha_path_checked = false;

	/** Caching system to avoid repeated checks. No automatic memoization ... snif */
	public final boolean hasAlphaMask() {
		if (alpha_path_checked) return has_alpha;
		// else, see if the path exists:
		try {
			has_alpha = new File(project.getLoader().getAlphaPath(this)).exists();
		} catch (Exception e) {
			IJError.print(e);
		}
		alpha_path_checked = true;
		return has_alpha;
	}

	public boolean hasAlphaChannel() {
		return null != ct || hasAlphaMask();
	}

	/** Must call updateMipmaps() afterwards. Set it to null to remove it. */
	public void setAlphaMask(ByteProcessor bp) throws IllegalArgumentException {
		if (null == bp) {
			if (hasAlphaMask()) {
				if (project.getLoader().removeAlphaMask(this)) {
					alpha_path_checked = false;
				}
			}
			return;
		}

		Utils.log2(o_width, o_height, width, height, bp.getWidth(), bp.getHeight());

		// Check that the alpha mask represented by argument bp
		// has the appropriate dimensions:
		if (o_width != bp.getWidth() || o_height != bp.getHeight()) {
			throw new IllegalArgumentException("Need a mask of identical dimensions as the original image.");
		}

		project.getLoader().storeAlphaMask(this, bp);
		alpha_path_checked = false;
	}

	public void keyPressed(KeyEvent ke) {
		Object source = ke.getSource();
		if (! (source instanceof DisplayCanvas)) return;
		DisplayCanvas dc = (DisplayCanvas)source;
		final Layer la = dc.getDisplay().getLayer();
		final Roi roi = dc.getFakeImagePlus().getRoi();

		switch (ke.getKeyCode()) {
			case KeyEvent.VK_C:
				// copy into ImageJ clipboard
				int mod = ke.getModifiers();

				// Ignoring masks: outside is already black, and ImageJ cannot handle alpha masks.
				if (0 == mod || (0 == (mod ^ Event.SHIFT_MASK))) {
					CoordinateTransformList list = null;
					if (null != ct) {
						list = new CoordinateTransformList();
						list.add(this.ct);
					}
					if (0 == mod) { //SHIFT is not down
						AffineModel2D am = new AffineModel2D();
						am.set(this.at);
						if (null == list) list = new CoordinateTransformList();
						list.add(am);
					}
					ImageProcessor ip;
					if (null != list) {
						TransformMesh mesh = new TransformMesh(list, 32, o_width, o_height);
						TransformMeshMapping mapping = new TransformMeshMapping(mesh);
						ip = mapping.createMappedImageInterpolated(getImageProcessor());
					} else {
						ip = getImageProcessor();
					}
					new ImagePlus(this.title, ip).copy(false);
				} else if (0 == (mod ^ (Event.SHIFT_MASK | Event.ALT_MASK))) {
					// On shift down (and no other flags!):
					// Place the source image, untransformed, into clipboard:
					ImagePlus imp = getImagePlus();
					if (null != imp) imp.copy(false);
				}
				ke.consume();
				break;
			case KeyEvent.VK_F:
				// fill mask with current ROI using 
				Utils.log2("VK_F: roi is " + roi);
				if (null != roi && M.isAreaROI(roi)) {
					Bureaucrat.createAndStart(new Worker("Filling image mask") { public void run() { try {
					startedWorking();
					ByteProcessor mask = project.getLoader().fetchImageMask(Patch.this);
					boolean is_new = false;
					if (null == mask) {
						mask = new ByteProcessor(o_width, o_height);
						mask.setValue(255);
						mask.fill();
						is_new = true;
					}
					try {
						// a roi local to the image bounding box
						final Area a = new Area(new Rectangle(0, 0, (int)o_width, (int)o_height));
						a.intersect(M.getArea(roi).createTransformedArea(Patch.this.at.createInverse()));

						if (M.isEmpty(a)) {
							Utils.log("ROI does not intersect the active image!");
							return;
						}

						// Correct numerical instability:
						// (needed when ROI intersects left margin of the image, at least)
						Rectangle ab = a.getBounds();
						if (-1 == ab.x || -1 == ab.y) {
							AffineTransform aff = new AffineTransform();
							aff.translate(-1 == ab.x ? 1 : 0, -1 == ab.y ? 1 : 0);
							Area aa = a.createTransformedArea(aff);
							a.reset();
							a.add(aa);
						}

						if (null != ct) {
							// inverse the coordinate transform
							final TransformMesh mesh = new TransformMesh(ct, 32, o_width, o_height);
							final TransformMeshMapping mapping = new TransformMeshMapping( mesh );

							ByteProcessor rmask = new ByteProcessor((int)o_width, (int)o_height);

							if (is_new) {
								rmask.setColor(Toolbar.getForegroundColor());
							} else {
								rmask.setValue(255);
							}
							ShapeRoi sroi = new ShapeRoi(a);
							rmask.setRoi(sroi);
							rmask.fill(sroi.getMask());

							ByteProcessor inv_mask = (ByteProcessor) mapping.createInverseMappedImageInterpolated(rmask);

							if (is_new) {
								mask = inv_mask;
								// done!
							} else {
								// Blend
								rmask = null;
								inv_mask.setMinAndMax(255, 255);
								final byte[] b1 = (byte[]) mask.getPixels();
								final byte[] b2 = (byte[]) inv_mask.getPixels();
								final int color = mask.getBestIndex(Toolbar.getForegroundColor());
								for (int i=0; i<b1.length; i++) {
									b1[i] = (byte) ((int)( (b2[i] & 0xff) / 255.0f ) * (color - (b1[i] & 0xff) ) + (b1[i] & 0xff));
								}
							}
						} else {
							ShapeRoi sroi = new ShapeRoi(a);
							mask.setRoi(sroi);
							mask.setColor(Toolbar.getForegroundColor());
							mask.fill(sroi.getMask());
						}
					} catch (NoninvertibleTransformException nite) { IJError.print(nite); }
					setAlphaMask(mask);
					updateMipmaps();
					Display.repaint();
					} catch (Exception e) {
						IJError.print(e);
					} finally {
						finishedWorking();
					}}}, project);
				}
				// capturing:
				ke.consume();
				break;
		}
	}

	@Override
	Class getInternalDataPackageClass() {
		return DPPatch.class;
	}

	@Override
	Object getDataPackage() {
		return new DPPatch(this);
	}

	static private final class DPPatch extends Displayable.DataPackage {
		final double min, max;
		CoordinateTransform ct = null;
		
		DPPatch(final Patch patch) {
			super(patch);
			this.min = patch.min;
			this.max = patch.max;
			this.ct = null == ct ? null : patch.ct.clone();
			// channels is visualization
			// path is absolute
			// type is dependent on path, so absolute
			// o_width, o_height idem
		}
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Patch p = (Patch) d;
			boolean mipmaps = false;
			if (p.min != min || p.max != max || p.ct != ct || (p.ct == ct && ct instanceof CoordinateTransformList)) {
				Utils.log2("mipmaps is true! " + (p.min != min)  + " " + (p.max != max) + " " + (p.ct != ct) + " " + (p.ct == ct && ct instanceof CoordinateTransformList));
				mipmaps = true;
			}
			p.min = min;
			p.max = max;
			p.ct = null == ct ? null : (CoordinateTransform) ct.clone();

			if (mipmaps) {
				p.project.getLoader().regenerateMipMaps(p);
			}
			return true;
		}
	}

	/** Considers the alpha mask. */
	public boolean contains(final int x_p, final int y_p) {
		if (!hasAlphaChannel()) return super.contains(x_p, y_p);
		// else, get pixel from image
		if (project.getLoader().isUnloadable(this)) return super.contains(x_p, y_p);
		final Image img = project.getLoader().fetchImage(this, 0.12499); // TODO ideally, would ask for image within 256x256 dimensions, but that would need knowing the screen image dimensions beforehand, or computing it from the CoordinateTransform, which may be very costly.
		if (Loader.isSignalImage(img)) return super.contains(x_p, y_p);
		final int w = img.getWidth(null);
		final double scale = w / width;
		final Point2D.Double pd = inverseTransformPoint(x_p, y_p);
		final int x2 = (int)(pd.x * scale);
		final int y2 = (int)(pd.y * scale);
		final int[] pvalue = new int[1];
		final PixelGrabber pg = new PixelGrabber(img, x2, y2, 1, 1, pvalue, 0, w);
		try {
			pg.grabPixels();
		} catch (InterruptedException ie) {
			return super.contains(x_p, y_p);
		}
		// Not true if alpha value is zero
		return 0 != (pvalue[0] & 0xff000000);
	}

	public void setPreprocessorScriptPath(final String path) {
		project.getLoader().setPreprocessorScriptPath(this, path);
	}

	public String getPreprocessorScriptPath() {
		return project.getLoader().getPreprocessorScriptPath(this);
	}
}
