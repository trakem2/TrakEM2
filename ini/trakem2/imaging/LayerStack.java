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

/** This class represents an entire LayerSet of Patch objects only, as it is presented read-only to ImageJ. */
public class LayerStack extends ImageStack {

	final private LayerSet layer_set;
	final private int type;
	/** The virtualization scale. */
	final private double scale;
	/** The class of the objects included. */
	final private Class clazz;
	private ImagePlus layer_imp = null;

	public LayerStack(final LayerSet layer_set, final double scale, final int type, final Class clazz) {
		super((int)(layer_set.getLayerWidth() * (scale > 1 ? 1 : scale)), (int)(layer_set.getLayerHeight() * (scale > 1 ? 1 : scale)), Patch.DCM);
		this.layer_set = layer_set;
		this.type = type;
		this.scale = scale > 1 ? 1 : scale;
		this.clazz = clazz;
	}

	/*
	public int getWidth() {
		return (int)(layer_set.getLayerWidth() * scale);
	}

	public int getHeight() {
		return (int)(layer_set.getLayerHeight() * scale);
	}
	*/

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
		if (n < 1 || n > layer_set.size()) return null;
		// Create a flat image on the fly with everything on it, and return its processor.
		final Layer layer = layer_set.getLayer(n-1);
		return layer.getProject().getLoader().getFlatImage(layer, layer_set.get2DBounds(), this.scale, 0xffffffff, ImagePlus.COLOR_RGB, this.clazz, null).getProcessor();
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
	public void trim() {}

	public int getType() {
		return type;
	}

	public ImagePlus getImagePlus() {
		if (null == layer_imp) {
			layer_imp = new ImagePlus("LayerSet Stack", this);
			layer_imp.setCalibration(layer_set.getCalibrationCopy());
		}
		return layer_imp;
	}
}
