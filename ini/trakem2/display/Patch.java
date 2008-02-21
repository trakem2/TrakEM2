/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

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
import ij.io.FileSaver;
import ij.process.ImageProcessor;
import ini.trakem2.Project;
import ini.trakem2.imaging.PatchStack;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Search;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.persistence.Loader;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MemoryImageSource;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.geom.AffineTransform;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Hashtable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class Patch extends Displayable {

	private int type = -1; // unknown
	/** The channels that the currently existing awt image has ready for painting. */
	private int channels = 0xffffffff;

	/** To generate contrasted images non-destructively. */
	private double min = 0;
	private double max = 255;

	/** Construct a Patch from an image. */
	public Patch(Project project, String title, double x, double y, ImagePlus imp) {
		super(project, title, x, y);
		this.type = imp.getType();
		this.min = imp.getProcessor().getMin();
		this.max = imp.getProcessor().getMax();
		checkMinMax();
		this.width = imp.getWidth();
		this.height = imp.getHeight();
		project.getLoader().cache(this, imp);
		addToDatabase();
	}

	/** Reconstruct a Patch from the database. The ImagePlus will be loaded when necessary. */
	public Patch(Project project, long id, String title, double width, double height, int type, boolean locked, double min, double max, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.type = type;
		this.min = min;
		this.max = max;
		checkMinMax();
	}

	/** Reconstruct from an XML entry. */
	public Patch(Project project, long id, Hashtable ht_attributes, Hashtable ht_links) {
		super(project, id, ht_attributes, ht_links);
		// cache path:
		project.getLoader().addedPatchFrom((String)ht_attributes.get("file_path"), this);
		boolean hasmin = false;
		boolean hasmax = false;
		// parse specific fields
		for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht_attributes.get(key);
			if (key.equals("type")) {
				this.type = Integer.parseInt(data);
			} else if (key.equals("min")) {
				this.min = Double.parseDouble(data);
				hasmin = true;
			} else if (key.equals("max")) {
				this.max = Double.parseDouble(data);
				hasmax = true;
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

	/** Fetches the ImagePlus from the cache. Be warned: the returned ImagePlus may have been flushed, removed and then recreated if the program had memory needs that required flushing part of the cache. */
	public ImagePlus getImagePlus() {
		return this.project.getLoader().fetchImagePlus(this);
	}

	/** Fetches the ImageProcessor from the cache, which will never be flushed or its pixels set to null. If you keep many of these, you may end running out of memory: I adivse you to call this method everytime you need the processor. */
	public ImageProcessor getImageProcessor() {
		return this.project.getLoader().fetchImageProcessor(this);
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

	/** Needs a non-null ImagePlus with a non-null ImageProcessor in it. This method is meant to be called only mmediately after the ImagePlus is loaded. */
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
	private Image adjustChannels(int c, boolean force, ImagePlus imp) {
		if (null == imp) imp = project.getLoader().fetchImagePlus(this, false); // calling create_snap will end up calling this method adjustChannels twice, which is ludicrous, so 'false'
		// the method fetchImage above has set the min and max already on the image
		//Utils.log2("Patch " + this + "   imp: slice is " + imp.getCurrentSlice());
		//Utils.printCaller(this, 12);
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
		//Utils.log("checkChannels called: this.channels = " + this.channels + "  channels = " + channels);
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
		final float cg = ((channels&0xff00)>>8) / 255.0f;
		final float cb = (channels&0xff) / 255.0f;
		// extract pixels
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

		final Image image = project.getLoader().fetchImage(this, magnification);
		//Utils.log2("Patch " + id + " painted image " + image);

		if (null == image) {
			//Utils.log2("Patch.paint: null image, returning");
			return; // TEMPORARY from lazy repaints after closing a Project
		}

		// fix dimensions (may be smaller; either a snap or a smaller awt)
		final int iw = image.getWidth(null);
		if (iw < this.width) {  // no need to check height
			atp = (AffineTransform)atp.clone();
			final double K = this.width / (double)iw;
			atp.scale(K, K);
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

	
	/** Paint first whatever is available, then spawn a thread to load the proper image and paint it. */
	public void prePaint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {

		AffineTransform atp = this.at;

		checkChannels(channels, magnification);

		Image image = project.getLoader().getCachedClosestAboveImage(this, magnification); // above or equal
		if (null == image) {
			image = project.getLoader().getCachedClosestBelowImage(this, magnification); // below, not equal
			boolean thread = false;
			if (null == image) {
				// fetch the proper image, nothing is cached
				if (magnification <= 0.5001) {
					// load the mipmap
					image = project.getLoader().fetchImage(this, magnification);
				} else {
					// load a smaller mipmap, and then spawn the thread
					image = project.getLoader().fetchImage(this, 0.25);
					thread = true;
				}
				// TODO to be non-blocking, this should paint a black square with a "loading..." legend in it or something, then fire a later repaint thread like below. So don't wait!
			} else {
				// painting a smaller image, will need to repaint with the proper one
				thread = true;
			}
			if (thread && !Loader.NOT_FOUND.equals(image)) {
				// use the lower resolution image, but spawn a thread to load and paint the proper one on loading it.
				Loader.preload(this, magnification, true);
			}
		}

		if (null == image) {
			Utils.log2("Patch.paint: null image, returning");
			return; // TEMPORARY from lazy repaints after closing a Project
		}

		// fix dimensions (may be smaller; either a snap or a smaller awt)
		final int iw = image.getWidth(null);
		if (iw < this.width) {  // no need to check height
			atp = (AffineTransform)atp.clone();
			final double K = this.width / (double)iw;
			atp.scale(K, K);
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
			Hashtable ht = new Hashtable();
			getStackPatches(ht);
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
				Search.remove(this);
				return true;
			} else {
				Utils.showMessage("Patch: can't remove! The image is linked and thus supports other data).");
				return false;
			}
		}
	}

	/** Returns true if this Patch holds direct links to at least one other image in a different layer. Doesn't check for total overlap. */
	public boolean isStack() {
		if (null == hs_linked || hs_linked.isEmpty()) return false;
		Iterator it = hs_linked.iterator();
		while (it.hasNext()) {
			Displayable d = (Displayable)it.next();
			if (d instanceof Patch && d.layer.getId() != this.layer.getId()) return true;
		}
		return false;
	}

	/** Retuns a virtual ImagePlus with a virtual stack if necessary. */
	public PatchStack makePatchStack() {
		// are we a stack?
		Hashtable ht = new Hashtable();
		getStackPatches(ht);
		Patch[] patch = null;
		int currentSlice = 1; // from 1 to n, as in ImageStack
		if (ht.size() > 1) {
			// a stack. Order by layer Z
			patch = new Patch[ht.size()];
			ArrayList al = new ArrayList();
			al.addAll(ht.keySet());
			Double[] zs = new Double[al.size()];
			al.toArray(zs);
			Arrays.sort(zs);
			for (int i=0; i<zs.length; i++) {
				patch[i] = (Patch)ht.get(zs[i]);
				if (patch[i].id == this.id) currentSlice = i+1;
			}
		} else {
			patch = new Patch[]{ this };
		}
		return new PatchStack(patch, currentSlice);
	}

	/** Collect linked Patch instances that do not lay in this layer. Recursive over linked Patch instances that lay in different layers. */ // This method returns a usable stack because Patch objects are only linked to other Patch objects when inserted together as stack. So the slices are all consecutive in space and have the same thickness. Yes this is rather convoluted, stacks should be full-grade citizens
	private void getStackPatches(Hashtable ht) {
		if (ht.contains(this)) return;
		ht.put(new Double(layer.getZ()), this);
		if (null != hs_linked && hs_linked.size() > 0) {
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Displayable ob = (Displayable)it.next();
				if (ob instanceof Patch && !ob.layer.equals(this.layer)) {
					((Patch)ob).getStackPatches(ht);
				}
			}
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
		;
		if (0 != min) sb_body.append(in).append("min=\"").append(min).append("\"\n");
		if (max != Patch.getMaxMax(type)) sb_body.append(in).append("max=\"").append(max).append("\"\n");
		sb_body.append(indent).append("/>\n");
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
		sb_header.append(indent).append("<!ELEMENT t2_patch EMPTY>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" file_path").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" type").append(TAG_ATTR2)
		;
	}

	/** Performs a copy of this object, without the links, unlocked and visible, except for the image which is NOT duplicated. */
	public Displayable clone(Project project) {
		final Patch copy = new Patch(project, project.getLoader().getNextId(), null != title ? title.toString() : null, width, height, type, false, min, max, (AffineTransform)at.clone());
		copy.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		copy.alpha = this.alpha;
		copy.visible = true;
		copy.channels = this.channels;
		copy.min = this.min;
		copy.max = this.max;
		copy.addToDatabase();
		project.getLoader().addedPatchFrom(this.project.getLoader().getAbsolutePath(this), copy);
		return copy;
	}

	public void paintSnapshot(final Graphics2D g, final double mag) {
		if (layer.getParent().areSnapshotsEnabled()) {
			if (this.getClass().equals(Patch.class) && !project.getLoader().isSnapPaintable(this.id)) {
				paintAsBox(g);
			} else {
				paint(g, mag, false, this.channels, layer);
			}
		} else {
			paintAsBox(g);
		}
	}
}
