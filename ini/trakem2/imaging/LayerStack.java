package ini.trakem2.imaging;

import ij.process.*;
import ij.*;
import ij.gui.Roi;
import ij.measure.Calibration;

import ini.trakem2.display.*;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ini.trakem2.persistence.Loader;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Hashtable;

/** This class represents an entire LayerSet, as it is presented read-only to ImageJ. */
public class LayerStack extends ImageStack {
	int nSlices;
	private LayerSet layer_set;
	/** The Displayable objects to represent. If null, all are represented. */
	private ArrayList al_displ = null;
	/** The PatchStack objects among the displayables, with the Patch as key. */
	private Hashtable ht_ps = null;

	private LayerImagePlus layer_imp;

	private Rectangle box = null;

	private boolean flag = true;

	private Display display;

	public LayerStack(final LayerSet layer_set, final int width, final int height, final Display display) {
		super(width, height, Patch.DCM);
		this.layer_set = layer_set;
		this.display = display;
	}

	public int getWidth() {
		return box.width;
	}

	public int getHeight() {
		return box.height;
	}

	/** Accepts only Displayable objects in the ArrayList. */
	public void setDisplayables(ArrayList al_displ) {
		if (null == al_displ || 0 == al_displ.size()) {
			this.al_displ = null;
			box = layer_set.get2DBounds();
			flushCache();
			return;
		}
		// check if all objects are identical, although in different order (to save from flushing away perfectly valid cached images)
		if (null != this.al_displ && this.al_displ.size() == al_displ.size()) {
			int n = this.al_displ.size();
			for (Iterator it = al_displ.iterator(); it.hasNext(); ) {
				if (this.al_displ.contains(it.next())) n--;
			}
			if (0 == n) {
				// lists are identical
				this.al_displ = al_displ; // updating pointer
				return;
			}
		}
		// assign new list
		this.al_displ = al_displ;
		this.ht_ps = new Hashtable();
		// compute new box
		box = null;
		try {
			for (Iterator it = al_displ.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				if (!(ob instanceof Displayable)) {
					Utils.log2("LayerStack: rejecting non-Displayable object " + ob);
					continue;
				}
				if (ob instanceof Patch) {
					ht_ps.put(ob, ((Patch)ob).makePatchStack());
				}
				Displayable d = (Displayable)ob;
				Rectangle r = d.getBoundingBox(null);
				if (null == box) box = r;
				else box.add(r);
			}
		} catch (Exception e) {
			new IJError(e);
			// set box to everything
			box = layer_set.get2DBounds();
		}
		flushCache();
		this.layer_imp = new LayerImagePlus(layer_set.getTitle(), this);
	}

	private void flushCache() {
		// flush away all cached ImagePlus
		long[] lid = new long[layer_set.size()];
		ArrayList al = layer_set.getLayers();
		for (int i=layer_set.size()-1; i>-1; i--) {
			lid[i] = ((Layer)al.get(i)).getId();
		}
		layer_set.getProject().getLoader().decacheImagePlus(lid);
	}

	/** Does nothing. */
	public void addSlice(String sliceLabel, Object pixels) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	public void addSlice(String sliceLabel, ImageProcessor ip) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	public void addSlice(String sliceLabel, ImageProcessor ip, int n) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	public void deleteSlice(int n) {
		Utils.log("LayerStack: cannot delete slices.");
	}

	/** Does nothing. */
	public void deleteLastSlice() {
		Utils.log("LayerStack: cannot delete slices.");
	}

	/** Returns the pixel array for the specified slice, were 1<=n<=nslices. The scale of the returned flat image for the Layer at index 'n-1' will be defined by the LayerSet virtualization options.*/
	public Object getPixels(final int n) {
		if (n < 1 || n > layer_set.size()) return null;
		return getProcessor(n).getPixels();
	}

	/** Does nothing. */
	public void setPixels(Object pixels, int n) {
		Utils.log("LayerStack: cannot set pixels.");
	}

	/** Returns an ImageProcessor for the specified slice,
		were 1<=n<=nslices. Returns null if the stack is empty.
	*/
	public ImageProcessor getProcessor(int n) {
		//Utils.printCaller(this, 6);
		if (n < 1 || n > layer_set.size()) return null;
		if (0 == box.width || 0 == box.height) {
			// necessary when creating a new, empty non-Patch Displayable
			return new ByteProcessor(2,2); // dummy
		}
		Rectangle box = this.box;
		Roi roi = display.getCanvas().getFakeImagePlus().getRoi();
		if (null != roi) {
			box = roi.getBounds();
		}
		// else, create a flat image on the fly with everything on it, and return its processor.
		final Layer layer = layer_set.getLayer(n-1);
		//Utils.log2("LayerStack: layer is " + layer + " from n=" + n);
		final Loader loader = layer_set.getProject().getLoader();
		ImagePlus flat = loader.getCachedImagePlus(layer.getId());
		if (null == flat || flat.getWidth() != box.width || flat.getHeight() != box.height) {
			// TODO fix cache, store as Patch with x,y
			ArrayList al = new ArrayList();
			for (Iterator it = al_displ.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				if (ob instanceof Patch) {
					Patch p = (Patch)ob;
					// add the patch corresponding to this layer, if any
					p = ((PatchStack)ht_ps.get(ob)).getPatch(layer, p);
					if (null != p) al.add(p);
				} else {
					al.add(ob);
				}
			}
			int c_alphas = 0xffffffff;
			if (null != Display.getFront()) c_alphas = Display.getFront().getDisplayChannelAlphas();
			flat = loader.getFlatImage(layer, box, layer_set.getPixelsVirtualizationScale(box), c_alphas, ImagePlus.COLOR_RGB, Displayable.class, al);
			loader.cacheImagePlus(layer.getId(), flat);
		}
		return flat.getProcessor();
	}
 
	 /** Returns the number of slices in this stack. */
	public int getSize() {
		return layer_set.size();
	}

	/** Returns the file name of the Nth image. */
	public String getSliceLabel(int n) {
		if (n < 1 || n > layer_set.size()) return null;
		return layer_set.getLayer(n-1).getTitle();
	}

	/** Returns null. */
	public Object[] getImageArray() {
		return null;
	}

	/** Does nothing. */
	public void setSliceLabel(String label, int n) {
		Utils.log("LayerStack: cannot set the slice label.");
	}

	/** Always return true. */
	public boolean isVirtual() {
		return true;
	}

	/** Override: always false. */
	public boolean isProcessor() {
		return false;
	}
	/** Override: always false. */
	public boolean isHSB() {
		return false;
	}
	/** Override: always false. */
	public boolean isRGB() {
		return false;
	}

	/** Does nothing. */
	public void trim() {
	}

	public ImagePlus getImagePlus() {
		return layer_imp;
	}

	private class LayerImagePlus extends ImagePlus {

		public LayerImagePlus(String title, ImageStack stack) {
			super(title, stack);
			super.setCalibration(layer_set.getCalibrationCopy());
		}

		/** Prevent premature calls to getPixels. */
		public synchronized void setSlice(int index) {
			if (index == currentSlice) {
				return;
			}
			if (index>= 1 && index <=layer_set.size()) {
				currentSlice = index;
			}
			ip = getProcessor(); // creates a new one for the stack
			ip.setPixels(getPixels(currentSlice));
		}

		public void setCalibration(Calibration cal) {
			layer_set.setCalibration(cal);
			super.setCalibration(cal);
		}
	}
}
