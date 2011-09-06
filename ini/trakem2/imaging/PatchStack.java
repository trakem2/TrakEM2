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

package ini.trakem2.imaging;

import ij.ImagePlus;
import ij.ImageStack;
import ij.LookUpTable;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.MedianCut;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.HashSet;
import java.util.Properties;



/** Assumed is all Patch instances in the array are of the same size,
 * live in consecutive layers of the same thickness. 
 *
 * The superclass ImagePlus uses the 'ip' pointer for a single ImageProcessor
 * whose pixels get replaced every time a new slice is selected from the stack,
 * when there is a stack. Here the 'ip' pointer is used for the current slice,
 * which is the currently active Patch.
 * */
public class PatchStack extends ImagePlus {

	/** Contains at least 1 patch.*/
	private Patch[] patch;
	//private int currentSlice = 1; // from 1 to n, as in ImageStack
	private VirtualStack stack;
	private boolean[] called;

	public PatchStack(Patch[] patch, int currentSlice) {
		this.patch = patch;
		//this.currentSlice = currentSlice;
		setSlice(currentSlice);
		Rectangle b = patch[0].getBoundingBox(null);
		this.width = b.width;
		this.height = b.height;
		this.stack = new VirtualStack(width, height);
		this.ip = null; // will be retrieved on the fly when necessary
		this.changes = false;
		called = new boolean[patch.length];
		for (int i=0; i<patch.length; i++) called[i] = false;
		//
		super.setCalibration(patch[0].getLayer().getParent().getCalibrationCopy());
	}

	/** Assumes all patches are of the same dimensions. */
	public Rectangle getBounds() {
		return patch[0].getBoundingBox();
	}

	public boolean contains(Patch p) {
		for (int i=0; i<patch.length; i++)
			if (patch[i].getId() == p.getId())
				return true;
		return false;
	}

	/** If the 'p' is contained in this PatchStack, get the first Patch found to be contained here and in the given 'layer'*/
	public Patch getPatch(Layer layer, Patch p) {
		if (!contains(p)) return null;
		long layer_id = layer.getId();
		for (int i=0; i<patch.length; i++)
			if (patch[i].getLayer().getId() == layer_id)
				return patch[i];
		return null;
	}

	/** From 0 to getNSlices() -1, otherwise null. */
	public Patch getPatch(int i) {
		if (i<0 || i>patch.length) return null;
		return patch[i];
	}

	/** Returns the Patch corresponding to the current slice. */
	public Patch getCurrentPatch() {
		return patch[currentSlice-1];
	}

	public void setCurrentSlice(Patch p) {
		// checks that it exists here first
		for (int i=0; i<patch.length; i++) {
			if (patch[i].getId() == p.getId()) {
				currentSlice = i+1;
				break;
			}
		}
	}

	public void revert(Patch p) {
		for (int i=0; i<patch.length; i++) {
			if (patch[i].getId() == p.getId()) {
				revert2(p);
				Display.repaint(p.getLayer(), p, 0);
				//p.getProject().getLoader().vacuum();
				break;
			}
		}
	}

	public void revertAll() {
		Utils.showProgress(0);
		for (int i=0; i<patch.length; i++) {
			revert2(patch[i]);
			Utils.showProgress((i+1.0) / patch.length);
		}
		Utils.showProgress(0);
		//patch[0].getProject().getLoader().vacuum();
	}

	private void revert2(Patch p) {
		/* // TODO
		 *    needs rewrite: should just flush away and swap paths, and perhaps remove copy of image.
		 *
		 *
		// TODO this is overkill. If only the lut and so on has changed, one could just undo that by calling ip.reset etc. as in ContrastAdjuster
		Loader loader = p.getProject().getLoader();
		ImagePlus current = loader.fetchImagePlus(p);
		p.updateInDatabase("remove_tiff_working"); // remove the copy, no longer needed
		ImagePlus original = loader.fetchOriginal(p);
		if (null == original) {
			Utils.log("PatchStack.revert2: null original!");
			return;
		} else if (original.equals(current)) {
			Utils.log("PatchStack.rever2: original equals current. Not reverting.");
			return;
		}
		Loader.flush(current);
		loader.updateCache(p, original); //flush awt, remake awt, flush snap, remake snap
		Rectangle box = p.getBoundingBox(null);
		
		// ideally, all I want is to remove the scaling and shear components only
		// but unfortunately the m02, m12 are edited to correct for shear and rotation
		// and scaling -induced translations. So this is PARTIAL TODO
		p.getAffineTransform().setToIdentity();
		p.getAffineTransform().translate(box.x, box.y);

		box = p.getBoundingBox(null);

		// TODO this method needs heavy revision and updating
		Display.repaint(p.getLayer(), box, 5); // the previous dimensions
		*/
	}

	/** Reset temporary changes such as from dragging B&C sliders and so on, in the current slice (the current Patch). */
	public void resetNonActive() {
		Utils.log2("PatchStack: calling reset");
		// remake the awt for the patch, flush the previous awt
		Loader loader = patch[currentSlice-1].getProject().getLoader();
		for (int i=0; i<patch.length; i++) {
			if (currentSlice-1 == i || !called[i]) continue;
			called[i] = false;
			ImagePlus imp = loader.fetchImagePlus(patch[i]);
			ImageProcessor ip = imp.getProcessor();
			switch (imp.getType()) { // as in ij.plugin.frame.ContrastAdjuster.reset(ImagePlus, ImageProcessor)
				case ImagePlus.COLOR_RGB:
					ip.reset(); break;
				case ImagePlus.GRAY16:
				case ImagePlus.GRAY32:
					ip.resetMinAndMax(); break;
			}
			patch[i].setMinAndMax(ip.getMin(), ip.getMax());
			patch[i].getProject().getLoader().decacheAWT(patch[i].getId());
			Display.repaint(patch[i].getLayer(), patch[i], null, 0, true);
		}
	}

	/** Store working copies and remake the awts and repaint. */
	public void saveImages() {
		Utils.log2("PatchStack: calling saveImages");
		if (!this.changes) {
			Utils.log2("PatchStack.saveImages: nothing changed.");
			return;
		}
		Loader loader = patch[currentSlice-1].getProject().getLoader();
		Utils.showProgress(0);
		for (int i=0; i<patch.length; i++) {
			ImagePlus imp = loader.fetchImagePlus(patch[i]);
			Utils.log2("PatchStack.saveImages: patch imp " + i + " has the imp.changes=" + imp.changes + " and the called[i]=" + called[i]);
			if (imp.changes || called[i]) {
				patch[i].updateInDatabase("tiff_working"); // may be doing it twice, check TODO
				/*
				patch[i].createImage(); //flushes the old awt, and creates the new one, and stores it in the cache.
				*/
				// just flush away all dependent images, will be recreated when needed on repaint
				patch[i].getProject().getLoader().decache(imp);
				Display.repaint(patch[i].getLayer(), patch[i], 0);
				// reset
				imp.changes = false;
				called[i] = false;
			}
			Utils.showProgress((i+1.0) / patch.length);
		}
		this.changes = false;
		//patch[0].getProject().getLoader().vacuum();
		Utils.showProgress(1);
	}

	public void draw() {
		// repaint all Displays that show any layer of the parent LayerSet (this is overkill, but not so bad, almost never there'll be that many Displays)
		Display.repaint(patch[currentSlice-1].getLayer().getParent());
	}

	public void draw(int x, int y, int width, int height) {
		Rectangle r = new Rectangle(x, y, width, height);
		Display.repaint(patch[currentSlice-1].getLayer(), r, 0);
	}

	public void updateAndDraw() {
		Utils.log2("PatchStack: calling updateAndDraw");
		//Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], 0);
		// TODO : notify listeners ?
		//No, instead do it directly:
		if (changes) {
			saveImages(); //only those perhaps affected (can't really tell)
			changes = false;
		} else {
			Utils.log2("PatchStack.updateAndDraw 'else'");
			// decache (to force remaking) and redraw
			patch[currentSlice-1].getProject().getLoader().decacheAWT(patch[currentSlice-1].getId());
			Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], null, 0, true);
			// reset the others if necessary
			//resetNonActive(); // TODO there must to be a better way, this is overkill because not all images over which a getProcessor() has been called will have been modified. It would be solved if imp.changes was accessed through a method instead, because then I could flag the proper imp as changed
		}
	}

	public void repaintWindow() {
		Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], 0);
	}

	public void updateAndRepaintWindow() {
		Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], 0);
	}

	public void updateImage() {
		Utils.log2("PS: Update image");
		patch[currentSlice-1].createImage(); //flushes the old awt, and creates the new one.
		Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], 0);
	}

	public void hide() {
		Utils.log("PatchStack: can't hide.");
	}

	public void close() {
		Utils.log("PatchStack: can't close.");
	}

	public void show() {
		Utils.log("PatchStack: can't show.");
	}

	public void show(String statusMessage) {
		this.show();
	}

	public void invertLookupTable() {
		Loader loader = patch[currentSlice-1].getProject().getLoader();
		ImagePlus imp = loader.fetchImagePlus(patch[currentSlice-1]);
		imp.getProcessor().invert();
		Display.repaint(patch[currentSlice-1].getLayer(), patch[currentSlice-1], 0);
		patch[currentSlice-1].updateInDatabase("tiff_working"); // TODO if the database updates are too much, then one could put a "save" button somewhere that shows as "unsaved" (red?) when there are unsaved changes.
		imp.changes = false; // just saved
	}

	public Image getImage() {
		return patch[currentSlice-1].getProject().getLoader().fetchImage(patch[currentSlice-1]).image;
		// TODO: is this safe? Can be flushed!
	}

	public void setImage(Image img) {
		Utils.log("PatchStack: can't setImage");
	}

	public void setProcessor(String title, ImageProcessor ip) {
		if (1 != patch.length) return; //so not applied to stacks
		Loader loader = patch[currentSlice-1].getProject().getLoader();
		ImagePlus imp = loader.fetchImagePlus(patch[currentSlice-1]);
		if (ip.getWidth() != imp.getWidth() || ip.getHeight() != imp.getHeight()) throw new IllegalArgumentException("PatchStack: ip wrong size");
		imp.setProcessor(null, ip); // null means don't touch title
		patch[currentSlice-1].updateInDatabase("tiff_working");
		//loader.vacuum();
		// repainting elsewhere
	}

	public void setStack(String title, ImageStack stack) {
		Utils.log("PatchStack: can't setStack");
	}

	public void setFileInfo(FileInfo fi) {
		Utils.log("PatchStack: can't setFileInfo");
	}

	public void setWindow(Window win) {
		Utils.log("PatchStack: can't setWindow");
	}

	public void setColor(Color c) {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		imp.getProcessor().setColor(c);
	}

	public boolean isProcessor() {
		return true; // TODO needs testing. This function simply creates an ImageProcessor in the superclass, to hold the pixels object from the stack array
	}

	public ImageProcessor getProcessor() {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		ImageProcessor ip = imp.getProcessor();
		if (null!=this.roi) imp.setRoi(this.roi);
		called[currentSlice-1] = true;
		return ip;
	}

	public synchronized void trimProcessor() {
		if (!locked) {
			try {
				ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
				imp.trimProcessor();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public ImageProcessor getMask() {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		ImageProcessor ip = imp.getProcessor();
		Roi roi = getRoi();
		if (null == roi) {
			ip.resetRoi();
			return null;
		}
		ImageProcessor mask = roi.getMask();
		if (null==mask) return null;
		ip.setMask(mask);
		ip.setRoi(roi.getBounds());
		return mask;
	}

	// only need to override one, as the others point to this method
	@Override
	public ImageStatistics getStatistics(int nOptions, int nBins, double histMin, double histMax) {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		if (null!=this.roi) imp.setRoi(this.roi); // to be sure!
		return imp.getStatistics(nOptions, nBins, histMin, histMax);
	}

	public String getTitle() {
		return patch[currentSlice-1].getTitle();
	}

	public void setTitle(String title) {
		patch[currentSlice-1].setTitle(title);
	}

	public int getStackSize() {
		return patch.length;
	}

	public void setDimensions(int nChannels, int nSlices, int nFrames) {
		Utils.log("PatchStack: Can't setDimensions.");
	}

	/** Override to return 1. */
	public int getNChannels() {
		return 1;
	}

	public int getNSlices() {
		return patch.length;
	}

	/** Override to return 1. */
	public int getNFrames() {
		return 1;
	}

	/** Override to return width, height, 1, patch.length, 1*/
	public int[] getDimensions() {
		return new int[]{width, height, 1, patch.length, 1};
	}

	public int getType() {
		/*
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getType();
		*/
		return patch[currentSlice-1].getType();
	}

	public int getBitDepth() {
		int type = getType();
		switch(type) {
			case GRAY8:
			case COLOR_256:
				return 8;
			case GRAY16:
				return 16;
			case GRAY32:
				return 32;
			case COLOR_RGB:
				return 24;
		}
		return 8;
	}

	protected void setType(int type) {
		Utils.log("PatchStack: Can't set type");
	}

	public void setProperty(String key, Object value) {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		imp.setProperty(key, value);
		patch[currentSlice-1].updateInDatabase("tiff_working"); // TODO same as above, may be too much saving
		//patch[currentSlice-1].getProject().getLoader().vacuum();
	}

	public Object getProperty(String key) {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getProperty(key);
	}

	public Properties getProperties() {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getProperties();
	}

	public LookUpTable createLut() {
		Image awt = patch[currentSlice-1].getProject().getLoader().fetchImage(patch[currentSlice-1]).image;
		return new LookUpTable(awt);
	}

	public boolean isInvertedLut() {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getProcessor().isInvertedLut();
	}

	public int[] getPixel(int x, int y) {
		try {
			double mag = Display.getFront().getCanvas().getMagnification();
			return patch[currentSlice-1].getPixel(x, y, mag);
		} catch (Exception e) {
			IJError.print(e);
		}
		return new int[4];
	}

	public ImageStack createEmptyStack() {
		Utils.log("PatchStack: can't createEmptyStack");
		return null;
	}

	public ImageStack getStack() { return stack; } // the virtual one

	public ImageStack getImageStack() { return stack; } // idem

	public int getCurrentSlice() {
		return currentSlice;
	}

	public synchronized void setSlice(int index) {
		if (index == currentSlice) {
			//no need//updateAndRepaintWindow();
			return;
		}
		if (index >= 1 && index <= patch.length) {
			Roi roi = getRoi();
			if (null != roi) roi.endPaste();
			currentSlice = index;
			//no need//updateAndRepaintWindow();
		}
	}

	public Roi getRoi() {
		return roi;
	}
	private Roi getImpRoi() {
		return roi;
	}


	public void setRoi(Roi roi) {
		killRoi();
		//super.setRoi(roi); // needed ? If roi is protected, no
		if (null==roi) { this.roi = null; return; }
		// translate roi to Patch coordinate system
		Rectangle b = getBounds();
		Roi roi2 = (Roi)roi.clone();
		Rectangle r = roi2.getBounds();
		roi2.setLocation(r.x - b.x, r.y - b.y);
		roi = roi2;
		if (roi.isVisible()) roi = (Roi)roi.clone();
		Rectangle bounds = roi.getBounds();
		if (0==bounds.width && 0==bounds.height) return;
		this.roi = roi;
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		imp.setRoi(roi);
		ImageProcessor ip = imp.getProcessor();
		if (null!=ip) {
			ip.setMask(null);
			ip.setRoi(bounds);
		}
		this.roi.setImage(this);
		//draw(); //not needed
		//stack.setRoi(roi.getBounds()); // doesn't make a difference because I'm setting the Roi at VirtualStack.getProcessor(int) as well.
	}

	public void setRoi(Rectangle r) {
		if (null==r) { killRoi(); return; }
		killRoi();
		this.roi = new Roi(r.x, r.y, r.width, r.height);
		this.roi.setImage(this);
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		ImageProcessor ip = imp.getProcessor();
		if (null != ip) {
			ip.setMask(null);
			ip.setRoi(r);
		}
		//draw(); // not needed
	}

	public void createNewRoi(int sx, int sy) {
		super.createNewRoi(sx, sy);
		this.roi = getRoi(); // stupid privates
	}

	public void killRoi() {
		if (null!=roi) {
			saveRoi();
			roi = null;
		}
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().getCachedImagePlus(patch[currentSlice-1].getId()); // .fetchImagePlus(patch[currentSlice-1]);
		if (null != imp) imp.killRoi();
		//draw() // not needed
	}

	public void saveRoi() {
		if (null != roi) {
			roi.endPaste();
			Rectangle r = roi.getBounds();
			if (r.width>0 && r.height>0) {
				Roi.previousRoi = (Roi)roi.clone();
			}
		}
	}

	public FileInfo getFileInfo() {
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getFileInfo();
	}

	public synchronized void flush() {}

	public Calibration getCalibration() {
		/*
		if (null != getGlobalCalibration()) {
			return getGlobalCalibration();
		}
		*/
		return getLocalCalibration();
	}

	public void setCalibration(Calibration cal) {
		// pass it to the LayerSet
		patch[0].getLayer().getParent().setCalibration(cal);
		// and the super of course
		super.setCalibration(cal);
		// set it locally to the ImagePlus'es
		for (int i=0; i<patch.length; i++) {
			ImagePlus imp = patch[i].getProject().getLoader().fetchImagePlus(patch[i]);
			imp.setCalibration(cal);
			if (imp.getStackSize() > 1) return; // done, for real stacks
		}
	}

	public Calibration getLocalCalibration() {
		/* // never ! The calibration of the active image is ignored. The LayerSet calibration is what counts. Plus this method ends up loading the image when selecting it!
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		return imp.getCalibration();
		*/
		return patch[currentSlice-1].getLayer().getParent().getCalibrationCopy();
	}

	public void copy(boolean cut) {
		super.copy(cut);
	}

	public void paste() {
		super.paste();
		ImagePlus imp = patch[currentSlice-1].getProject().getLoader().fetchImagePlus(patch[currentSlice-1]);
		imp.changes = true;
		// TODO: make sure that the srcRect that the super method gets makes any sense to it when the Canvas is larger than this image, or viceversa.

		patch[currentSlice-1].updateInDatabase("tiff_working");
	}

	/** Remove all awts and snaps from the loader's cache, and repaint (which will remake as many as needed) */
	public void decacheAll() {
		final HashSet<ImagePlus> hs = new HashSet<ImagePlus>(); // record already flushed imps, since there can be shared imps among Patch instances (for example in stacks)
		final Loader loader = patch[currentSlice-1].getProject().getLoader();
		for (int i=0; i<patch.length; i++) {
			ImagePlus imp = loader.fetchImagePlus(patch[i]);
			if (hs.contains(imp)) continue;
			else if (null != imp) hs.add(imp);
			loader.decache(imp);
			loader.flushMipMaps(patch[i].getId());
			Display.repaint(patch[i].getLayer(), patch[i], 0);
		}
	}

	@Override
	public void setPosition(int channel, int slice, int frame) {
		if (slice >= 1 && slice <= patch.length) {
			currentSlice = slice;
		}
	}
	
	@Override
	public int getStackIndex(int channel, int slice, int frame) {
		return slice;
	}

	final class VirtualStack extends ImageStack {

		VirtualStack(int width, int height) {
			super(width, height);
		}
		public void addSlice(String label, ImageProcessor ip) {
			Utils.log("PatchStack: Can't add a slice.");
		}

		public void addSlice(String label, Object pixels) {
			Utils.log("PatchStack: Can't add a slice.");
		}

		public void addSlice(String label, Object pixels, int n) {
			Utils.log("PatchStack: Can't add a slice.");
		}

		public void addUInsignedShortSlice(String label, Object pixels) {
			Utils.log("PatchStack: Can't add a slice.");
		}

		public void deleteSlice(int n) {
			Utils.log("PatchStack: Can't delete a slice.");
		}

		public void deleteLastSlice() {
			Utils.log("PatchStack: Can't delete last slice.");
		}

		/** Must override, for superclass fields are private! */
		public int getWidth() { return width; }

		/** Must override, for superclass fields are private! */
		public int getHeight() { return height; }

		public Object getPixels(int n) {
			if (n<1 || n>patch.length) throw new IllegalArgumentException("PatchStack: out of range " + n);
			ImagePlus imp = patch[n-1].getProject().getLoader().fetchImagePlus(patch[n-1]);
			return imp.getProcessor().getPixels(); // TODO should clone?
		}

		public void setPixels(Object pixels, int n) {
			if (n<1 || n>patch.length) throw new IllegalArgumentException("PatchStack: out of range " + n);
			if (null == pixels) throw new IllegalArgumentException("PatchStack: 'pixels' is null!");
			ImagePlus imp = patch[n-1].getProject().getLoader().fetchImagePlus(patch[n-1]);
			imp.getProcessor().setPixels(pixels);
			patch[n-1].updateInDatabase("tiff_working");
			//patch[n-1].getProject().getLoader().vacuum();
		}

		public Object[] getImageArray() {
			Object[] ob = new Object[patch.length];
			for (int i=0; i<ob.length; i++) {
				ImagePlus imp = patch[i].getProject().getLoader().fetchImagePlus(patch[i]);
				ob[i] = imp.getProcessor().getPixels(); // TODO should clone? This is mostly for saving files, and it's safe because the database is untouched (but then, it may look inconsistent if the images are updated in some way)
			}
			return ob;
		}

		public int getSize() { return patch.length; }

		public String[] getSliceLabels() {
			String[] labels = new String[patch.length];
			for (int i=0; i<labels.length; i++) labels[i] = patch[i].getTitle();
			return labels;
		}

		public String getSliceLabel(int n) { //  using 'n' for index is not a good idea, Wayne!
			if (n<1 || n>patch.length) throw new IllegalArgumentException("PatchStack: out of range " + n);
			return patch[n-1].getTitle();
		}
		
		public String getShortSliceLabel(int n) {
			String shortLabel = getSliceLabel(n);
			if (shortLabel==null) return null;
			int newline = shortLabel.indexOf('\n');
			if (0 == newline) return null;
			if (newline>0) shortLabel = shortLabel.substring(0, newline);
			int len = shortLabel.length();
			if (len>4 && '.' == shortLabel.charAt(len-4) && Character.isDigit(shortLabel.charAt(len-1))) shortLabel = shortLabel.substring(0,len-4);
			if (shortLabel.length()>60) shortLabel = shortLabel.substring(0, 60);
			return shortLabel;
		}

		public void setSliceLabel(String label, int n) {
			if (n<1 || n>patch.length) throw new IllegalArgumentException("PatchStack: out of range " + n);
			if (null != label) patch[n-1].setTitle(label);
		}

		public ImageProcessor getProcessor(int n) {
			//Utils.log("VirtualStack: called getProcessor " + n);
			if (n<1 || n>patch.length) throw new IllegalArgumentException("PatchStack: out of range " + n);
			ImagePlus imp = patch[n-1].getProject().getLoader().fetchImagePlus(patch[n-1]);
			ImageProcessor ip = imp.getProcessor();
			Roi roi = getImpRoi(); // oddities of private classes
			if (null!=roi) imp.setRoi(roi);
			called[n-1] = true;
			/* // no, instead reload the ImagePlus from the database if flushed (setup at the Loader.fetchImagePlus and fetchImage methods)
			patch[n-1].getProject().getLoader().releaseToFit(ip.getWidth() * ip.getHeight() * imp.getBitDepth() / 1024L);
			return ip.duplicate(); // let's give a copy to ImageJ
			*/
			return ip;
		}

		/** Override: always false. */
		public boolean isRGB() {
			return getType() == COLOR_RGB;
		}

		/** Override: always false. */
		public boolean isHSB() {
			return false;
		}

		public boolean isVirtual() {
			return true; // TODO got to test this
		}

		/** Override: does nothing. */
		public void trim() {
			Utils.log("PatchStack.VirtualStack: can't trim");
		}

		public String toString() {
			return "Virtual Patch Stack: width=" + width + ", height=" + height + ", nSlices: " + patch.length;
		}
	}

	// WARNING This method will fail if the stack has slices of different dimensions
	/** Does not respect local transform of the patches, this is intended for confocal stacks. */
	public ImagePlus createGray8Copy() {
		final Rectangle box = patch[0].getBoundingBox();
		final int width = box.width;
		final int height = box.height;
		// compute minimum bounding box
		ImageStack st = new ImageStack(width, height);
		Loader loader = patch[0].getProject().getLoader();
		for (int i=1; i<patch.length; i++) {
			loader.releaseToFit(width * height);
			st.addSlice(Integer.toString(i), this.stack.getProcessor(i).convertToByte(true));
		}
		ImagePlus imp = new ImagePlus("byte", st);
		imp.setCalibration(patch[0].getLayer().getParent().getCalibrationCopy());
		//imp.getCalibration().pixelDepth = patch[0].getLayer().getThickness();
		return imp;
	}

	// WARNING This method will fail if the stack has slices of different dimensions
	/** Does not respect local transform of the patches, this is intended for confocal stacks. */
	public ImagePlus createColor256Copy() {
		final Rectangle box = patch[0].getBoundingBox();
		final int width = box.width;
		final int height = box.height;
		Loader loader = patch[0].getProject().getLoader();
		patch[0].getProject().getLoader().releaseToFit(4 * patch.length * width * height); // the montage, in RGB
		final ColorProcessor montage = new ColorProcessor(width*patch.length, height);
		for (int i=0; i<patch.length; i++) {
			montage.insert(this.stack.getProcessor(i+1), i*width, 0);
		}
		final MedianCut mc = new MedianCut(montage);
		loader.releaseToFit(patch.length * width * height);
		ImageProcessor m2 = mc.convertToByte(256);
		final ImageStack st = new ImageStack(width, height);
		for (int i=0; i<patch.length; i++) {
			m2.setRoi(i*width, 0, width, height);
			loader.releaseToFit(width * height);
			st.addSlice(null, m2.crop());
        	}
		ImagePlus imp = new ImagePlus("color256", st);
		imp.setCalibration(patch[0].getLayer().getParent().getCalibrationCopy());
		//imp.getCalibration().pixelDepth = patch[0].getLayer().getThickness();
		return imp;
	}
}
