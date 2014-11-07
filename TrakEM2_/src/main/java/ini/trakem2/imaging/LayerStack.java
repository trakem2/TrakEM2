package ini.trakem2.imaging;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;

/** This class represents an entire LayerSet of Patch objects only, as it is presented read-only to ImageJ. */
public class LayerStack extends ImageStack {

	final private List<Layer> layers;
	final private int type;
	/** The virtualization scale. */
	final private double scale;
	/** The class of the objects included. */
	final private Class<?> clazz;
	final private int c_alphas;
	final private boolean invert;
	/** In world coordinates. */
	final Rectangle roi;
	final HashMap<Long,Long> id_cache = new HashMap<Long, Long>();
	
	private ImagePlus layer_imp = null;

	public LayerStack(final LayerSet layer_set, final double scale, final int type, final Class<?> clazz, final int c_alphas, final boolean invert) {
		this(layer_set.getLayers(), layer_set.get2DBounds(), scale, type, clazz, c_alphas, invert);
	}

	/** If scale <=0 || scale > 1, throws IllegalArgumentException. */
	public LayerStack(final List<Layer> layers, final Rectangle roi, final double scale, final int type, final Class<?> clazz, final int c_alphas, final boolean invert) {
		super((int)(roi.width * scale), (int)(roi.height * scale), Patch.DCM);
		if (scale <= 0 || scale > 1) throw new IllegalArgumentException("Cannot operate with a scale larger than 1 or smaller or equal to 0!");
		this.layers = layers;
		this.type = type;
		this.scale = scale;
		this.clazz = clazz;
		this.c_alphas = c_alphas;
		this.invert = invert;
		this.roi = new Rectangle(roi);
	}

	public LayerStack(final LayerSet layer_set, final double scale, final int type, final Class<?> clazz, final int c_alphas) {
		this(layer_set, scale, type, clazz, c_alphas, false);
	}

	/** Does nothing. */
	public void addSlice(String sliceLabel, Object pixels) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	@Override
	public void addSlice(String sliceLabel, ImageProcessor ip) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	@Override
	public void addSlice(String sliceLabel, ImageProcessor ip, int n) {
		Utils.log("LayerStack: cannot add slices.");
	}

	/** Does nothing. */
	@Override
	public void deleteSlice(int n) {
		Utils.log("LayerStack: cannot delete slices.");
	}

	/** Does nothing. */
	@Override
	public void deleteLastSlice() {
		Utils.log("LayerStack: cannot delete slices.");
	}

	/** Returns the pixel array for the specified slice, were 1<=n<=nslices. The scale of the returned flat image for the Layer at index 'n-1' will be defined by the LayerSet virtualization options.*/
	@Override
	public Object getPixels(final int n) {
		if (n < 1 || n > layers.size()) return null;
		return getProcessor(n).getPixels();
	}

	/** Does nothing. */
	@Override
	public void setPixels(Object pixels, int n) {
		Utils.log("LayerStack: cannot set pixels.");
	}

	/** Returns an ImageProcessor for the specified slice,
		were 1<=n<=nslices. Returns null if the stack is empty.
	*/
	@Override
	public ImageProcessor getProcessor(int n) {
		if (n < 1 || n > layers.size()) return null;
		// Create a flat image on the fly with everything on it, and return its processor.
		final Layer layer = layers.get(n-1);
		final Loader loader = layer.getProject().getLoader();
		Long cid;
		synchronized (id_cache) {
			cid = id_cache.get(layer.getId());
			if (null == cid) {
				cid = loader.getNextTempId();
				id_cache.put(layer.getId(), cid);
			}
		}
		ImageProcessor ip;
		synchronized (cid) { 
			ImagePlus imp = loader.getCachedImagePlus(cid);
			if (null == imp || null == imp.getProcessor() || null == imp.getProcessor().getPixels()) {
				ip = loader.getFlatImage(layer, this.roi, this.scale, this.c_alphas, this.type, this.clazz, null).getProcessor();
				if (invert) ip.invert();
				loader.cacheImagePlus(cid, new ImagePlus("", ip));
			} else ip = imp.getProcessor();
		}
		return ip;
	}
 
	 /** Returns the number of slices in this stack. */
	@Override
	public int getSize() {
		return layers.size();
	}

	/** Returns the file name of the Nth image. */
	@Override
	public String getSliceLabel(int n) {
		if (n < 1 || n > layers.size()) return null;
		return layers.get(n-1).getTitle();
	}

	/** Returns a linear array for each slice, real (not virtual)! */
	@Override
	public Object[] getImageArray() {
		// Release 3 times an RGB stack with this dimensions.
		layers.get(0).getProject().getLoader().releaseToFit((long)(getSize() * getWidth() * getHeight() * 4 * 3));
		final Object[] ia = new Object[getSize()];
		for (int i=0; i<ia.length; i++) {
			ia[i] = getProcessor(i+1).getPixels(); // slices 1<=slice<=n_slices
		}
		return ia;
	}

	/** Does nothing. */
	@Override
	public void setSliceLabel(String label, int n) {
		Utils.log("LayerStack: cannot set the slice label.");
	}

	/** Always return true. */
	@Override
	public boolean isVirtual() {
		return true;
	}

	/** Override: always false. */
	@Override
	public boolean isHSB() {
		return false;
	}
	/** Override: always false. */
	@Override
	public boolean isRGB() {
		return false;
	}

	/** Does nothing. */
	@Override
	public void trim() {}

	public int getType() {
		return type;
	}

	synchronized public ImagePlus getImagePlus() {
		if (null == layer_imp) {
			layer_imp = new ImagePlus("LayerSet Stack", this);
			Calibration cal = layers.get(0).getParent().getCalibrationCopy();
			// adjust to scale
			cal.pixelWidth /= scale;
			cal.pixelHeight /= scale;
			// Simulate depth: assume all layers have the same thickness
			cal.pixelDepth = (layers.get(0).getThickness() * cal.pixelWidth) / scale; // not pixelDepth
			layer_imp.setCalibration(cal);
		}
		return layer_imp;
	}
}
